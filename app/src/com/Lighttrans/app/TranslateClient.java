package com.Lighttrans.app;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLException;

/**
 * 翻译请求：支持 OpenAI 兼容 / Anthropic / Gemini 三种协议。
 * 只用 JDK 自带的 HttpURLConnection；流式走 SSE（服务端事件流）逐块解析。
 *
 * 思考模式：按引擎的 think 字段给请求加上对应的参数
 * （OpenAI 兼容：thinking / enable_thinking；Anthropic：thinking；Gemini：thinkingConfig），
 * 如果服务商不认识这些参数（HTTP 400 参数类错误），会自动去掉参数重试一次。
 */
public class TranslateClient {

    public interface Callback {
        void onDone(String text, String error);
    }

    /** 流式回调（都在主线程调用）：onDelta/onReasoning 可能多次，最后 onDone 收尾。 */
    public interface StreamCallback {
        void onDelta(String delta);

        void onReasoning(String delta);

        void onDone(String fullText, String error);
    }

    private interface StreamSink {
        void onDelta(String d);

        void onReasoning(String d);
    }

    private interface SseHandler {
        void onEvent(String event, String data);
    }

    /** SSE 里服务端明确报错时抛出，好把消息带给用户。 */
    private static final class StreamError extends RuntimeException {
        StreamError(String m) {
            super(m);
        }
    }

    /** HTTP 非 2xx：带状态码和原始响应体（用于判断是不是“参数不认识”）。 */
    static final class HttpError extends Exception {
        final int code;
        final String raw;

        HttpError(int code, String raw) {
            super("HTTP " + code + " " + errorMessage(raw));
            this.code = code;
            this.raw = raw == null ? "" : raw;
        }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // ==================== 对外入口 ====================

    /** 非流式翻译（一次性返回整段）。 */
    public static void translateAsync(final Engine engine, final String prompt, final String text,
                                      final int timeoutSec, final Callback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String out = null;
                String err = null;
                try {
                    out = translate(engine, prompt, text, timeoutSec);
                } catch (Throwable t) {
                    err = friendly(t);
                }
                if (err == null && (out == null || out.length() == 0)) err = "引擎返回了空结果";
                final String fOut = out == null ? "" : out;
                final String fErr = err;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onDone(fOut, fErr);
                    }
                });
            }
        }, "lighttrans-net").start();
    }

    /** 流式翻译（打字机效果）。引擎/中间层不支持 SSE 时自动回退成一次性请求。 */
    public static void translateStreamAsync(final Engine engine, final String prompt, final String text,
                                            final int timeoutSec, final StreamCallback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final StringBuilder full = new StringBuilder();
                final boolean[] gotAny = new boolean[1];
                String err = null;
                try {
                    streamRun(engine, prompt, text, timeoutSec, new StreamSink() {
                        @Override
                        public void onDelta(String d) {
                            gotAny[0] = true;
                            full.append(d);
                            postDelta(cb, d, false);
                        }

                        @Override
                        public void onReasoning(String d) {
                            gotAny[0] = true;
                            postDelta(cb, d, true);
                        }
                    });
                    if (full.length() == 0) err = "引擎返回了空结果";
                } catch (Throwable t) {
                    err = friendly(t);
                }
                if (err != null && !gotAny[0]) {
                    // 流式不可用（老服务/代理不支持 SSE，或握手就失败）：回退成一次性请求
                    try {
                        String out = translate(engine, prompt, text, timeoutSec);
                        if (out != null && out.length() > 0) {
                            final String ff = out;
                            MAIN.post(new Runnable() {
                                @Override
                                public void run() {
                                    cb.onDone(ff, null);
                                }
                            });
                            return;
                        }
                        err = "引擎返回了空结果";
                    } catch (Throwable t2) {
                        err = friendly(t2);
                    }
                }
                final String fOut = full.toString();
                final String fErr = err;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onDone(fOut, fErr);
                    }
                });
            }
        }, "lighttrans-stream").start();
    }

    private static void postDelta(final StreamCallback cb, final String d, final boolean reasoning) {
        if (d == null || d.length() == 0) return;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (reasoning) cb.onReasoning(d);
                else cb.onDelta(d);
            }
        });
    }

    // ==================== 地址推导 ====================

    /**
     * 对话接口地址：既能填完整地址(…/chat/completions、…/messages)，也能只填基地址(…/v1)，
     * 这里负责把基地址补全成真正的接口地址。
     */
    public static String chatUrl(Engine e) {
        String u = e.url == null ? "" : e.url.trim();
        if (u.length() == 0) return u;
        if (e.type == Engine.TYPE_GEMINI) return u; // Gemini 用模板地址，自己带 %s
        if (u.endsWith("/chat/completions") || u.endsWith("/completions") || u.endsWith("/messages")) return u;
        if (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (e.type == Engine.TYPE_ANTHROPIC) return u + "/messages";
        return u + "/chat/completions";
    }

    /** Gemini 地址：替换 %s 模型占位符；流式时把 :generateContent 换成 :streamGenerateContent 并加 alt=sse。 */
    private static String geminiUrl(Engine e, boolean stream) {
        String url = e.url == null ? "" : e.url.trim();
        if (url.indexOf("%s") >= 0) url = url.replace("%s", e.model);
        if (stream) {
            if (url.indexOf(":generateContent") >= 0) url = url.replace(":generateContent", ":streamGenerateContent");
            url = url + (url.indexOf('?') >= 0 ? "&" : "?") + "alt=sse";
        }
        if (e.key.length() > 0) url = url + (url.indexOf('?') >= 0 ? "&" : "?") + "key=" + e.key;
        return url;
    }

    /** 由对话接口地址推测“模型列表”地址，例如 …/chat/completions -> …/models。 */
    public static String modelsUrl(Engine e) {
        String url = e.url == null ? "" : e.url.trim();
        if (url.length() == 0) return url;
        if (e.type == Engine.TYPE_GEMINI) {
            int i = url.indexOf("/models/");
            if (i >= 0) return url.substring(0, i + 7);
            return url;
        }
        String[] tails = {"/chat/completions", "/completions", "/messages"};
        for (int i = 0; i < tails.length; i++) {
            if (url.endsWith(tails[i])) {
                return url.substring(0, url.length() - tails[i].length()) + "/models";
            }
        }
        if (url.endsWith("/models")) return url;
        if (url.endsWith("/")) return url + "models";
        return url + "/models";
    }

    /** 本次请求要不要按“关闭/开启思考”发参数；被服务商拒过一次后就不再发（省一次 400 往返）。 */
    private static boolean useThink(Engine e) {
        return e.think != Engine.THINK_AUTO && e.thinkParamOk;
    }

    private static int bodyThink(Engine e) {
        return useThink(e) ? e.think : Engine.THINK_AUTO;
    }

    // ==================== 非流式 ====================

    public static String translate(Engine e, String prompt, String text, int timeoutSec) throws Exception {
        if (e.type == Engine.TYPE_ANTHROPIC) return anthropic(e, prompt, text, timeoutSec);
        if (e.type == Engine.TYPE_GEMINI) return gemini(e, prompt, text, timeoutSec);
        return openai(e, prompt, text, timeoutSec);
    }

    private static String openai(Engine e, String prompt, String text, int timeoutSec) throws Exception {
        Map<String, String> h = new HashMap<String, String>();
        if (e.key.length() > 0) h.put("Authorization", "Bearer " + e.key);

        JSONObject resp;
        try {
            resp = postJson(chatUrl(e), openaiBody(e, prompt, text, false, bodyThink(e)), h, timeoutSec);
        } catch (HttpError he) {
            if (!useThink(e) || !isParamError(he.code, he.raw)) throw he;
            e.thinkParamOk = false;
            resp = postJson(chatUrl(e), openaiBody(e, prompt, text, false, Engine.THINK_AUTO), h, timeoutSec);
        }
        JSONArray choices = resp.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new Exception("响应里没有 choices： " + clip(resp.toString(), 200));
        }
        JSONObject c0 = choices.optJSONObject(0);
        JSONObject msg = c0 == null ? null : c0.optJSONObject("message");
        return clean(contentOf(msg));
    }

    private static JSONObject message(String role, String content) throws Exception {
        JSONObject m = new JSONObject();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private static JSONObject openaiBody(Engine e, String prompt, String text, boolean stream, int think) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", e.model);
        JSONArray msgs = new JSONArray();
        msgs.put(message("system", prompt));
        msgs.put(message("user", text));
        body.put("messages", msgs);
        body.put("temperature", 0.2);
        body.put("stream", stream);
        if (think == Engine.THINK_OFF) {
            body.put("enable_thinking", false);
            body.put("thinking", thinkObj("disabled"));
        } else if (think == Engine.THINK_ON) {
            body.put("enable_thinking", true);
            body.put("thinking", thinkObj("enabled"));
        }
        return body;
    }

    private static JSONObject thinkObj(String type) throws Exception {
        JSONObject o = new JSONObject();
        o.put("type", type);
        return o;
    }

    /** 有的服务把 content 做成数组（多段），这里统一取文本。 */
    private static String contentOf(JSONObject msg) {
        if (msg == null) return "";
        Object c = msg.opt("content");
        if (c instanceof String) return (String) c;
        if (c instanceof JSONArray) {
            JSONArray a = (JSONArray) c;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) {
                    Object t = o.opt("text");
                    if (t instanceof String) sb.append((String) t);
                }
            }
            return sb.toString();
        }
        return "";
    }

    private static String anthropic(Engine e, String prompt, String text, int timeoutSec) throws Exception {
        Map<String, String> h = new HashMap<String, String>();
        h.put("x-api-key", e.key);
        h.put("anthropic-version", "2023-06-01");

        JSONObject resp;
        try {
            resp = postJson(chatUrl(e), anthropicBody(e, prompt, text, false, bodyThink(e)), h, timeoutSec);
        } catch (HttpError he) {
            if (e.think != Engine.THINK_ON || !e.thinkParamOk || !isParamError(he.code, he.raw)) throw he;
            e.thinkParamOk = false;
            resp = postJson(chatUrl(e), anthropicBody(e, prompt, text, false, Engine.THINK_AUTO), h, timeoutSec);
        }
        JSONArray content = resp.optJSONArray("content");
        StringBuilder sb = new StringBuilder();
        if (content != null) {
            for (int i = 0; i < content.length(); i++) {
                JSONObject c = content.optJSONObject(i);
                if (c != null && "text".equals(c.optString("type"))) {
                    Object t = c.opt("text");
                    if (t instanceof String) sb.append((String) t);
                }
            }
        }
        return clean(sb.toString());
    }

    private static JSONObject anthropicBody(Engine e, String prompt, String text, boolean stream, int think) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", e.model);
        JSONArray msgs = new JSONArray();
        msgs.put(message("user", text));
        body.put("messages", msgs);
        body.put("system", prompt);
        body.put("stream", stream);
        if (think == Engine.THINK_ON) {
            // 开启思考：max_tokens 必须大于预算；温度不能改（不传）
            body.put("max_tokens", 4096);
            JSONObject th = thinkObj("enabled");
            th.put("budget_tokens", 2048);
            body.put("thinking", th);
        } else {
            body.put("max_tokens", 2048);
            body.put("temperature", 0.2);
        }
        return body;
    }

    private static String gemini(Engine e, String prompt, String text, int timeoutSec) throws Exception {
        JSONObject resp;
        try {
            resp = postJson(geminiUrl(e, false), geminiBody(e, prompt, text, bodyThink(e)), null, timeoutSec);
        } catch (HttpError he) {
            if (!useThink(e) || !isParamError(he.code, he.raw)) throw he;
            e.thinkParamOk = false;
            resp = postJson(geminiUrl(e, false), geminiBody(e, prompt, text, Engine.THINK_AUTO), null, timeoutSec);
        }
        return clean(geminiText(resp));
    }

    private static JSONObject geminiBody(Engine e, String prompt, String text, int think) throws Exception {
        JSONObject part = new JSONObject();
        part.put("text", text);
        JSONArray parts = new JSONArray();
        parts.put(part);
        JSONObject contentObj = new JSONObject();
        contentObj.put("parts", parts);
        JSONArray contents = new JSONArray();
        contents.put(contentObj);

        JSONObject siPart = new JSONObject();
        siPart.put("text", prompt);
        JSONArray siParts = new JSONArray();
        siParts.put(siPart);
        JSONObject si = new JSONObject();
        si.put("parts", siParts);

        JSONObject gen = new JSONObject();
        gen.put("temperature", 0.2);
        if (think == Engine.THINK_OFF) {
            JSONObject tc = new JSONObject();
            tc.put("thinkingBudget", 0); // 0 = 关闭
            gen.put("thinkingConfig", tc);
        } else if (think == Engine.THINK_ON) {
            JSONObject tc = new JSONObject();
            tc.put("thinkingBudget", -1); // -1 = 动态思考
            gen.put("thinkingConfig", tc);
        }

        JSONObject body = new JSONObject();
        body.put("contents", contents);
        body.put("systemInstruction", si);
        body.put("generationConfig", gen);
        return body;
    }

    /** 非流式 Gemini：把 thought 分片丢掉，只拼正式译文。 */
    private static String geminiText(JSONObject resp) throws Exception {
        JSONArray cands = resp.optJSONArray("candidates");
        if (cands == null || cands.length() == 0) {
            throw new Exception("响应里没有 candidates： " + clip(resp.toString(), 200));
        }
        JSONObject c0 = cands.optJSONObject(0);
        StringBuilder sb = new StringBuilder();
        if (c0 != null) {
            JSONObject content = c0.optJSONObject("content");
            if (content != null) {
                JSONArray ps = content.optJSONArray("parts");
                if (ps != null) {
                    for (int i = 0; i < ps.length(); i++) {
                        JSONObject p = ps.optJSONObject(i);
                        if (p == null || p.optBoolean("thought", false)) continue;
                        Object t = p.opt("text");
                        if (t instanceof String) sb.append((String) t);
                    }
                }
            }
        }
        return sb.toString();
    }

    // ==================== 流式（SSE） ====================

    private static void streamRun(Engine e, String prompt, String text, int timeoutSec, StreamSink sink) throws Exception {
        if (e.type == Engine.TYPE_ANTHROPIC) {
            anthropicStream(e, prompt, text, timeoutSec, sink);
            return;
        }
        if (e.type == Engine.TYPE_GEMINI) {
            geminiStream(e, prompt, text, timeoutSec, sink);
            return;
        }
        openaiStream(e, prompt, text, timeoutSec, sink);
    }

    private static void openaiStream(Engine e, String prompt, String text, int timeoutSec, final StreamSink sink) throws Exception {
        String url = chatUrl(e);
        Map<String, String> h = new HashMap<String, String>();
        if (e.key.length() > 0) h.put("Authorization", "Bearer " + e.key);

        HttpURLConnection conn;
        try {
            conn = openPost(url, openaiBody(e, prompt, text, true, bodyThink(e)), h, timeoutSec, true);
        } catch (HttpError he) {
            if (!useThink(e) || !isParamError(he.code, he.raw)) throw he;
            e.thinkParamOk = false;
            conn = openPost(url, openaiBody(e, prompt, text, true, Engine.THINK_AUTO), h, timeoutSec, true);
        }
        try {
            readSse(conn.getInputStream(), new SseHandler() {
                @Override
                public void onEvent(String event, String data) {
                    String d = data.trim();
                    if (d.length() == 0 || "[DONE]".equals(d)) return;
                    JSONObject j;
                    try {
                        j = new JSONObject(d);
                    } catch (Throwable t) {
                        return; // 忽略不完整的单个事件
                    }
                    JSONObject err = j.optJSONObject("error");
                    if (err != null) throw new StreamError(err.optString("message", "服务端返回错误"));
                    JSONArray ch = j.optJSONArray("choices");
                    if (ch == null || ch.length() == 0) return;
                    JSONObject c0 = ch.optJSONObject(0);
                    JSONObject delta = c0 == null ? null : c0.optJSONObject("delta");
                    if (delta == null) return;
                    Object c = delta.opt("content");
                    if (c instanceof String) sink.onDelta((String) c);
                    Object rc = delta.opt("reasoning_content"); // DeepSeek 等
                    if (rc instanceof String) sink.onReasoning((String) rc);
                    else {
                        Object r = delta.opt("reasoning"); // OpenRouter 等
                        if (r instanceof String) sink.onReasoning((String) r);
                    }
                }
            });
        } catch (StreamError se) {
            throw new Exception(se.getMessage());
        } finally {
            conn.disconnect();
        }
    }

    private static void anthropicStream(Engine e, String prompt, String text, int timeoutSec, final StreamSink sink) throws Exception {
        String url = chatUrl(e);
        Map<String, String> h = new HashMap<String, String>();
        h.put("x-api-key", e.key);
        h.put("anthropic-version", "2023-06-01");

        HttpURLConnection conn;
        try {
            conn = openPost(url, anthropicBody(e, prompt, text, true, bodyThink(e)), h, timeoutSec, true);
        } catch (HttpError he) {
            if (e.think != Engine.THINK_ON || !e.thinkParamOk || !isParamError(he.code, he.raw)) throw he;
            e.thinkParamOk = false;
            conn = openPost(url, anthropicBody(e, prompt, text, true, Engine.THINK_AUTO), h, timeoutSec, true);
        }
        try {
            readSse(conn.getInputStream(), new SseHandler() {
                @Override
                public void onEvent(String event, String data) {
                    String d = data.trim();
                    if (d.length() == 0) return;
                    JSONObject j;
                    try {
                        j = new JSONObject(d);
                    } catch (Throwable t) {
                        return;
                    }
                    String type = j.optString("type", "");
                    if ("error".equals(type)) {
                        JSONObject err = j.optJSONObject("error");
                        throw new StreamError(err == null ? "服务端返回错误" : err.optString("message", "服务端返回错误"));
                    }
                    if (!"content_block_delta".equals(type)) return;
                    JSONObject dd = j.optJSONObject("delta");
                    if (dd == null) return;
                    Object t = dd.opt("text");
                    if (t instanceof String) sink.onDelta((String) t);
                    Object th = dd.opt("thinking");
                    if (th instanceof String) sink.onReasoning((String) th);
                }
            });
        } catch (StreamError se) {
            throw new Exception(se.getMessage());
        } finally {
            conn.disconnect();
        }
    }

    private static void geminiStream(Engine e, String prompt, String text, int timeoutSec, final StreamSink sink) throws Exception {
        String url = geminiUrl(e, true);
        HttpURLConnection conn;
        try {
            conn = openPost(url, geminiBody(e, prompt, text, bodyThink(e)), null, timeoutSec, true);
        } catch (HttpError he) {
            if (!useThink(e) || !isParamError(he.code, he.raw)) throw he;
            e.thinkParamOk = false;
            conn = openPost(url, geminiBody(e, prompt, text, Engine.THINK_AUTO), null, timeoutSec, true);
        }
        try {
            readSse(conn.getInputStream(), new SseHandler() {
                @Override
                public void onEvent(String event, String data) {
                    String d = data.trim();
                    if (d.length() == 0) return;
                    JSONObject j;
                    try {
                        j = new JSONObject(d);
                    } catch (Throwable t) {
                        return;
                    }
                    JSONObject err = j.optJSONObject("error");
                    if (err != null) throw new StreamError(err.optString("message", "服务端返回错误"));
                    JSONArray cands = j.optJSONArray("candidates");
                    if (cands == null || cands.length() == 0) return;
                    JSONObject c0 = cands.optJSONObject(0);
                    JSONObject content = c0 == null ? null : c0.optJSONObject("content");
                    JSONArray ps = content == null ? null : content.optJSONArray("parts");
                    if (ps == null) return;
                    for (int i = 0; i < ps.length(); i++) {
                        JSONObject p = ps.optJSONObject(i);
                        if (p == null) continue;
                        Object t = p.opt("text");
                        if (!(t instanceof String)) continue;
                        if (p.optBoolean("thought", false)) sink.onReasoning((String) t);
                        else sink.onDelta((String) t);
                    }
                }
            });
        } catch (StreamError se) {
            throw new Exception(se.getMessage());
        } finally {
            conn.disconnect();
        }
    }

    /** 极简 SSE 解析：事件由空行分隔，data 可多行；注释行(:)忽略。 */
    private static void readSse(InputStream in, SseHandler handler) throws Exception {
        if (in == null) throw new Exception("连接失败：没有响应内容");
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder data = new StringBuilder();
        String event = "";
        String line;
        while ((line = r.readLine()) != null) {
            if (line.length() == 0) {
                if (data.length() > 0) {
                    handler.onEvent(event, data.toString());
                    data.setLength(0);
                    event = "";
                }
                continue;
            }
            if (line.startsWith(":")) continue;
            if (line.startsWith("event:")) {
                event = line.substring(6).trim();
                continue;
            }
            if (line.startsWith("data:")) {
                String v = line.substring(5);
                if (v.startsWith(" ")) v = v.substring(1);
                if (data.length() > 0) data.append('\n');
                data.append(v);
            }
        }
        if (data.length() > 0) handler.onEvent(event, data.toString());
    }

    // ==================== 拉取模型列表 ====================

    public interface ModelsCallback {
        void onDone(List<String> models, String error);
    }

    public static void listModelsAsync(final Engine e, final int timeoutSec, final ModelsCallback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<String> out = null;
                String err = null;
                try {
                    out = listModels(e, timeoutSec);
                } catch (Throwable t) {
                    err = friendly(t);
                }
                if (err == null && (out == null || out.isEmpty())) err = "这个引擎没有返回任何模型";
                final List<String> fOut = out;
                final String fErr = err;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onDone(fOut, fErr);
                    }
                });
            }
        }, "lighttrans-models").start();
    }

    /** 拉取模型列表：OpenAI 兼容 / Anthropic 用 /models，Gemini 用 /v1beta/models?key= */
    public static List<String> listModels(Engine e, int timeoutSec) throws Exception {
        String url = modelsUrl(e);
        Map<String, String> h = new HashMap<String, String>();
        if (e.type == Engine.TYPE_ANTHROPIC) {
            h.put("x-api-key", e.key);
            h.put("anthropic-version", "2023-06-01");
        } else if (e.type == Engine.TYPE_GEMINI) {
            if (e.key.length() > 0) url = url + (url.indexOf('?') >= 0 ? "&" : "?") + "key=" + e.key;
        } else if (e.key.length() > 0) {
            h.put("Authorization", "Bearer " + e.key);
        }

        JSONObject resp = getJson(url, h, timeoutSec);
        List<String> list = new ArrayList<String>();
        if (e.type == Engine.TYPE_GEMINI) {
            JSONArray arr = resp.optJSONArray("models");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject m = arr.optJSONObject(i);
                    if (m == null) continue;
                    String name = m.optString("name", "");
                    if (name.startsWith("models/")) name = name.substring(7);
                    if (name.length() > 0) list.add(name);
                }
            }
        } else {
            JSONArray arr = resp.optJSONArray("data");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject m = arr.optJSONObject(i);
                    if (m == null) continue;
                    String id = m.optString("id", "");
                    if (id.length() > 0) list.add(id);
                }
            }
        }
        Collections.sort(list);
        return list;
    }

    // ==================== HTTP 基础 ====================

    private static HttpURLConnection openPost(String url, JSONObject body, Map<String, String> headers,
                                              int timeoutSec, boolean stream) throws Exception {
        if (url == null || url.length() == 0) throw new Exception("没有配置接口地址");
        int t = timeoutSec <= 0 ? 30 : timeoutSec;
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        boolean ok = false;
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(t * 1000);
            conn.setReadTimeout(Math.max(t, stream ? 120 : 60) * 1000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", stream ? "text/event-stream" : "application/json");
            if (headers != null) {
                for (Map.Entry<String, String> en : headers.entrySet()) {
                    conn.setRequestProperty(en.getKey(), en.getValue());
                }
            }
            byte[] data = body.toString().getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(data.length);
            OutputStream os = conn.getOutputStream();
            os.write(data);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                String raw = readAll(conn.getErrorStream());
                throw new HttpError(code, raw);
            }
            ok = true;
            return conn;
        } finally {
            if (!ok) conn.disconnect();
        }
    }

    private static JSONObject postJson(String url, JSONObject body, Map<String, String> headers,
                                       int timeoutSec) throws Exception {
        HttpURLConnection conn = openPost(url, body, headers, timeoutSec, false);
        try {
            String raw = readAll(conn.getInputStream());
            return new JSONObject(raw);
        } finally {
            conn.disconnect();
        }
    }

    private static JSONObject getJson(String url, Map<String, String> headers, int timeoutSec) throws Exception {
        if (url == null || url.length() == 0) throw new Exception("没有配置接口地址");
        int t = timeoutSec <= 0 ? 30 : timeoutSec;
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(t * 1000);
            conn.setReadTimeout(Math.max(t, 60) * 1000);
            conn.setRequestProperty("Accept", "application/json");
            if (headers != null) {
                for (Map.Entry<String, String> en : headers.entrySet()) {
                    conn.setRequestProperty(en.getKey(), en.getValue());
                }
            }
            int code = conn.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String raw = readAll(in);
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code + " " + errorMessage(raw));
            return new JSONObject(raw);
        } finally {
            conn.disconnect();
        }
    }

    /** 400 且报错信息看着像“不认识这个参数”时返回 true（用于去掉思考参数重试）。 */
    private static boolean isParamError(int code, String raw) {
        if (code != 400 || raw == null) return false;
        String s = raw.toLowerCase();
        String[] keys = {
                "unrecognized", "unknown parameter", "unknown argument", "unknown field",
                "unsupported parameter", "unsupported field", "invalid parameter", "extra inputs",
                "extra_forbidden", "未知参数", "不支持的参数", "参数错误", "参数不合法",
                "不支持该参数", "多余的参数"
        };
        for (int i = 0; i < keys.length; i++) {
            if (s.contains(keys[i])) return true;
        }
        return false;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static String errorMessage(String raw) {
        if (raw == null || raw.length() == 0) return "";
        try {
            JSONObject o = new JSONObject(raw);
            JSONObject err = o.optJSONObject("error");
            if (err != null) {
                String m = err.optString("message", "");
                if (m.length() > 0) return "- " + m;
            }
            String m2 = o.optString("message", "");
            if (m2.length() > 0) return "- " + m2;
            String m3 = o.optString("error_description", "");
            if (m3.length() > 0) return "- " + m3;
        } catch (Throwable ignore) {
        }
        return "- " + clip(raw, 200);
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        s = s.replace('\n', ' ').trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 去掉思考型模型输出的思维链，只留下译文。 */
    private static String clean(String s) {
        if (s == null) return "";
        s = s.replaceAll("(?s)<(think|thinking|reasoning)>.*?</\\1>", "");
        return s.trim();
    }

    public static String friendly(Throwable t) {
        if (t instanceof UnknownHostException) return "连不上网络（域名解析失败）";
        if (t instanceof SocketTimeoutException) return "请求超时，换个引擎或检查网络";
        if (t instanceof SSLException) return "HTTPS 连接失败： " + clip(t.getMessage(), 120);
        String m = t.getMessage();
        return (m == null || m.length() == 0) ? t.getClass().getSimpleName() : clip(m, 200);
    }
}
