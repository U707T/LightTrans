package com.Lighttrans.app;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.UnknownServiceException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLException;

/**
 * 翻译请求：支持 OpenAI 兼容 / Anthropic / Gemini 三种协议。
 * 只用 JDK 自带的 HttpURLConnection；流式走 SSE（{@link SseParser}）逐块解析。
 *
 * 思考模式：按引擎的 think 字段给请求加上对应的参数
 * （OpenAI 兼容：thinking / enable_thinking；Anthropic：thinking；Gemini：thinkingConfig），
 * 如果服务商不认识这些参数（HTTP 400/422 参数类错误），会自动去掉参数重试一次。
 *
 * 线程与取消：请求都跑在固定大小的线程池里，并返回一个 {@link Cancel} 句柄。
 * 调用方换引擎、换风格或关掉卡片时 cancel()，连接会被立刻断开、回调也不再送达——
 * 否则旧请求会继续跑完，等于白付一次钱。
 */
public class TranslateClient {

    /** 连接超时基准（秒）；读取超时另按流式/非流式放大。 */
    public static final int TIMEOUT_SEC = 30;

    /** 同时最多几个请求（多引擎对比会同时开好几个）。 */
    private static final int POOL_SIZE = 8;

    private static final ExecutorService POOL = Executors.newFixedThreadPool(POOL_SIZE);

    /** 一次请求的取消句柄。 */
    public static final class Cancel {
        private volatile boolean cancelled;
        private volatile HttpURLConnection conn;

        public void cancel() {
            cancelled = true;
            HttpURLConnection c = conn;
            conn = null;
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignore) {
                }
            }
        }

        public boolean isCancelled() {
            return cancelled;
        }

        void attach(HttpURLConnection c) {
            conn = c;
            if (cancelled) {
                try {
                    c.disconnect();
                } catch (Throwable ignore) {
                }
            }
        }

        void detach(HttpURLConnection c) {
            if (conn == c) conn = null;
        }
    }

    public interface Callback {
        void onDone(String text, String error);
    }

    /** 流式回调（都在主线程调用）：onDelta/onReasoning 可能多次，最后 onDone 收尾。 */
    public interface StreamCallback {
        void onDelta(String delta);

        void onReasoning(String delta);

        void onDone(String fullText, String error);
    }

    public interface ModelsCallback {
        void onDone(List<String> models, String error);
    }

    private interface StreamSink {
        void onDelta(String d);

        void onReasoning(String d);
    }

    /** 一次请求体的构造（思考参数由重试逻辑决定）。 */
    private interface BodyBuilder {
        JSONObject build(int think) throws Exception;
    }

    /** SSE 里服务端明确报错时抛出，好把消息带给用户。 */
    private static final class StreamError extends RuntimeException {
        StreamError(String m) {
            super(m);
        }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // ==================== 对外入口 ====================

    /** 非流式翻译（一次性返回整段）。返回的句柄可用来取消。 */
    public static Cancel translateAsync(final Engine engine, final String prompt, final String text,
                                        final int timeoutSec, final Callback cb) {
        final Cancel cancel = new Cancel();
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                String out = null;
                String err = null;
                try {
                    out = translate(engine, prompt, text, timeoutSec, cancel);
                } catch (Throwable t) {
                    err = friendly(t);
                }
                if (cancel.isCancelled()) return;
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
        });
        return cancel;
    }

    /** 流式翻译（打字机效果）。引擎/中间层不支持 SSE 时自动回退成一次性请求。 */
    public static Cancel translateStreamAsync(final Engine engine, final String prompt, final String text,
                                              final int timeoutSec, final StreamCallback cb) {
        final Cancel cancel = new Cancel();
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                final StringBuilder full = new StringBuilder();
                final boolean[] gotAny = new boolean[1];
                String err = null;
                try {
                    streamRun(engine, prompt, text, timeoutSec, cancel, new StreamSink() {
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
                if (cancel.isCancelled()) return;
                if (err != null && !gotAny[0]) {
                    // 流式不可用（老服务/代理不支持 SSE，或握手就失败）：回退成一次性请求
                    try {
                        String out = translate(engine, prompt, text, timeoutSec, cancel);
                        if (cancel.isCancelled()) return;
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
                if (cancel.isCancelled()) return;
                final String fOut = full.toString();
                final String fErr = err;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onDone(fOut, fErr);
                    }
                });
            }
        });
        return cancel;
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

    /** 这次失败值不值得“去掉思考参数再试一次”。 */
    private static boolean canRetryThink(Engine e, boolean anthropicStyle) {
        if (!e.thinkParamOk) return false;
        if (e.think == Engine.THINK_AUTO) return false;          // 根本没发参数，不是它的问题
        return !anthropicStyle || e.think == Engine.THINK_ON;    // Anthropic 只在“开启”时才发 thinking
    }

    // ==================== 非流式 ====================

    public static String translate(Engine e, String prompt, String text, int timeoutSec, Cancel cancel) throws Exception {
        if (e.type == Engine.TYPE_ANTHROPIC) return anthropic(e, prompt, text, timeoutSec, cancel);
        if (e.type == Engine.TYPE_GEMINI) return gemini(e, prompt, text, timeoutSec, cancel);
        return openai(e, prompt, text, timeoutSec, cancel);
    }

    private static String openai(final Engine e, final String prompt, final String text,
                                 int timeoutSec, Cancel cancel) throws Exception {
        Map<String, String> h = new HashMap<String, String>();
        if (e.key.length() > 0) h.put("Authorization", "Bearer " + e.key);

        JSONObject resp = postJsonWithRetry(e, chatUrl(e), h, timeoutSec, cancel, false, new BodyBuilder() {
            @Override
            public JSONObject build(int think) throws Exception {
                return openaiBody(e, prompt, text, false, think);
            }
        });
        JSONArray choices = resp.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new Exception("响应里没有 choices： " + HttpTransport.clip(resp.toString(), 200));
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

    private static String anthropic(final Engine e, final String prompt, final String text,
                                    int timeoutSec, Cancel cancel) throws Exception {
        Map<String, String> h = new HashMap<String, String>();
        h.put("x-api-key", e.key);
        h.put("anthropic-version", "2023-06-01");

        JSONObject resp = postJsonWithRetry(e, chatUrl(e), h, timeoutSec, cancel, true, new BodyBuilder() {
            @Override
            public JSONObject build(int think) throws Exception {
                return anthropicBody(e, prompt, text, false, think);
            }
        });
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

    private static String gemini(final Engine e, final String prompt, final String text,
                                 int timeoutSec, Cancel cancel) throws Exception {
        JSONObject resp = postJsonWithRetry(e, geminiUrl(e, false), null, timeoutSec, cancel, false, new BodyBuilder() {
            @Override
            public JSONObject build(int think) throws Exception {
                return geminiBody(e, prompt, text, think);
            }
        });
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
            throw new Exception("响应里没有 candidates： " + HttpTransport.clip(resp.toString(), 200));
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

    private static void streamRun(Engine e, String prompt, String text, int timeoutSec, Cancel cancel,
                                  StreamSink sink) throws Exception {
        if (e.type == Engine.TYPE_ANTHROPIC) {
            anthropicStream(e, prompt, text, timeoutSec, cancel, sink);
            return;
        }
        if (e.type == Engine.TYPE_GEMINI) {
            geminiStream(e, prompt, text, timeoutSec, cancel, sink);
            return;
        }
        openaiStream(e, prompt, text, timeoutSec, cancel, sink);
    }

    private static void openaiStream(final Engine e, final String prompt, final String text,
                                     int timeoutSec, final Cancel cancel, final StreamSink sink) throws Exception {
        Map<String, String> h = new HashMap<String, String>();
        if (e.key.length() > 0) h.put("Authorization", "Bearer " + e.key);

        final HttpURLConnection conn = openStreamWithRetry(e, chatUrl(e), h, timeoutSec, cancel, false,
                new BodyBuilder() {
                    @Override
                    public JSONObject build(int think) throws Exception {
                        return openaiBody(e, prompt, text, true, think);
                    }
                });
        try {
            SseParser.parse(conn.getInputStream(), new SseParser.Handler() {
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
            cancel.detach(conn);
        }
    }

    private static void anthropicStream(final Engine e, final String prompt, final String text,
                                        int timeoutSec, final Cancel cancel, final StreamSink sink) throws Exception {
        Map<String, String> h = new HashMap<String, String>();
        h.put("x-api-key", e.key);
        h.put("anthropic-version", "2023-06-01");

        final HttpURLConnection conn = openStreamWithRetry(e, chatUrl(e), h, timeoutSec, cancel, true,
                new BodyBuilder() {
                    @Override
                    public JSONObject build(int think) throws Exception {
                        return anthropicBody(e, prompt, text, true, think);
                    }
                });
        try {
            SseParser.parse(conn.getInputStream(), new SseParser.Handler() {
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
            cancel.detach(conn);
        }
    }

    private static void geminiStream(final Engine e, final String prompt, final String text,
                                     int timeoutSec, final Cancel cancel, final StreamSink sink) throws Exception {
        final String url = geminiUrl(e, true);
        final HttpURLConnection conn = openStreamWithRetry(e, url, null, timeoutSec, cancel, false,
                new BodyBuilder() {
                    @Override
                    public JSONObject build(int think) throws Exception {
                        return geminiBody(e, prompt, text, think);
                    }
                });
        try {
            SseParser.parse(conn.getInputStream(), new SseParser.Handler() {
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
            cancel.detach(conn);
        }
    }

    // ==================== 拉取模型列表 ====================

    public static Cancel listModelsAsync(final Engine e, final int timeoutSec, final ModelsCallback cb) {
        final Cancel cancel = new Cancel();
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                List<String> out = null;
                String err = null;
                try {
                    out = listModels(e, timeoutSec, cancel);
                } catch (Throwable t) {
                    err = friendly(t);
                }
                if (cancel.isCancelled()) return;
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
        });
        return cancel;
    }

    /** 拉取模型列表：OpenAI 兼容 / Anthropic 用 /models，Gemini 用 /v1beta/models?key= */
    public static List<String> listModels(Engine e, int timeoutSec, Cancel cancel) throws Exception {
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

        JSONObject resp = HttpTransport.getJson(url, h, timeoutSec, cancel);
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

    // ==================== 思考参数重试 ====================

    /**
     * 非流式请求：先按引擎的思考设置发一次，服务商不认参数时去掉参数重发一次。
     * （三种协议共用这一份重试逻辑，别再各写一遍。）
     */
    private static JSONObject postJsonWithRetry(final Engine e, final String url, final Map<String, String> h,
                                                final int timeoutSec, final Cancel cancel,
                                                final boolean anthropicStyle, final BodyBuilder body) throws Exception {
        try {
            return HttpTransport.postJson(url, body.build(bodyThink(e)), h, timeoutSec, cancel);
        } catch (HttpTransport.HttpError he) {
            if (!canRetryThink(e, anthropicStyle) || !isParamError(he.code, he.raw)) throw he;
            e.thinkParamOk = false;
            return HttpTransport.postJson(url, body.build(Engine.THINK_AUTO), h, timeoutSec, cancel);
        }
    }

    /** 流式请求（同上），返回的是已经拿到 2xx 的连接。 */
    private static HttpURLConnection openStreamWithRetry(final Engine e, final String url, final Map<String, String> h,
                                                        final int timeoutSec, final Cancel cancel,
                                                        final boolean anthropicStyle, final BodyBuilder body) throws Exception {
        try {
            return HttpTransport.openPost(url, body.build(bodyThink(e)), h, timeoutSec, true, cancel);
        } catch (HttpTransport.HttpError he) {
            if (!canRetryThink(e, anthropicStyle) || !isParamError(he.code, he.raw)) throw he;
            e.thinkParamOk = false;
            return HttpTransport.openPost(url, body.build(Engine.THINK_AUTO), h, timeoutSec, true, cancel);
        }
    }

    /** 400/422 且报错信息看着像“不认识这个参数”时返回 true（用于去掉思考参数重试）。 */
    private static boolean isParamError(int code, String raw) {
        if ((code != 400 && code != 422) || raw == null) return false;
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

    /**
     * 去掉思考型模型输出的思维链，只留下译文。
     * 非流式直接在这里剥；流式由 UI 在收尾时对整段调用一次（见 BaseSink.onDone）。
     */
    static String clean(String s) {
        if (s == null) return "";
        s = s.replaceAll("(?s)<(think|thinking|reasoning)>.*?</\\1>", "");
        return s.trim();
    }

    public static String friendly(Throwable t) {
        if (t instanceof UnknownHostException) return "连不上网络（域名解析失败）";
        if (t instanceof SocketTimeoutException) return "请求超时，换个引擎或检查网络";
        if (t instanceof UnknownServiceException) {
            return "该地址是 http 明文，系统禁止了明文请求：请改用 https 地址，或确认系统允许明文流量";
        }
        if (t instanceof SSLException) return "HTTPS 连接失败： " + HttpTransport.clip(t.getMessage(), 120);
        String m = t.getMessage();
        return (m == null || m.length() == 0) ? t.getClass().getSimpleName() : HttpTransport.clip(m, 200);
    }
}
