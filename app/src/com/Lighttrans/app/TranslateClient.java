package com.Lighttrans.app;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
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

/** 翻译请求：支持 OpenAI 兼容 / Anthropic / Gemini 三种协议，只用 JDK 自带的 HttpURLConnection。 */
public class TranslateClient {

    public interface Callback {
        void onDone(String text, String error);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

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

    public static String translate(Engine e, String prompt, String text, int timeoutSec) throws Exception {
        if (e.type == Engine.TYPE_ANTHROPIC) return anthropic(e, prompt, text, timeoutSec);
        if (e.type == Engine.TYPE_GEMINI) return gemini(e, prompt, text, timeoutSec);
        return openai(e, prompt, text, timeoutSec);
    }

    private static String openai(Engine e, String prompt, String text, int timeoutSec) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", e.model);
        JSONArray msgs = new JSONArray();
        msgs.put(message("system", prompt));
        msgs.put(message("user", text));
        body.put("messages", msgs);
        body.put("temperature", 0.2);
        body.put("stream", false);

        Map<String, String> h = new HashMap<String, String>();
        if (e.key.length() > 0) h.put("Authorization", "Bearer " + e.key);

        JSONObject resp = postJson(chatUrl(e), body, h, timeoutSec);
        JSONArray choices = resp.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new Exception("响应里没有 choices： " + clip(resp.toString(), 200));
        }
        JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
        return clean(msg == null ? "" : msg.optString("content", ""));
    }

    private static JSONObject message(String role, String content) throws Exception {
        JSONObject m = new JSONObject();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private static String anthropic(Engine e, String prompt, String text, int timeoutSec) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", e.model);
        body.put("max_tokens", 2048);
        body.put("system", prompt);
        JSONArray msgs = new JSONArray();
        msgs.put(message("user", text));
        body.put("messages", msgs);

        Map<String, String> h = new HashMap<String, String>();
        h.put("x-api-key", e.key);
        h.put("anthropic-version", "2023-06-01");

        JSONObject resp = postJson(chatUrl(e), body, h, timeoutSec);
        JSONArray content = resp.optJSONArray("content");
        StringBuilder sb = new StringBuilder();
        if (content != null) {
            for (int i = 0; i < content.length(); i++) {
                JSONObject c = content.optJSONObject(i);
                if (c != null && "text".equals(c.optString("type"))) sb.append(c.optString("text"));
            }
        }
        return clean(sb.toString());
    }

    private static String gemini(Engine e, String prompt, String text, int timeoutSec) throws Exception {
        String url = e.url;
        if (url.indexOf("%s") >= 0) url = url.replace("%s", e.model);
        if (e.key.length() > 0) url = url + (url.indexOf('?') >= 0 ? "&" : "?") + "key=" + e.key;

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

        JSONObject body = new JSONObject();
        body.put("contents", contents);
        body.put("systemInstruction", si);
        body.put("generationConfig", gen);

        JSONObject resp = postJson(url, body, null, timeoutSec);
        JSONArray cands = resp.optJSONArray("candidates");
        if (cands == null || cands.length() == 0) {
            throw new Exception("响应里没有 candidates： " + clip(resp.toString(), 200));
        }
        JSONObject c0 = cands.getJSONObject(0).optJSONObject("content");
        StringBuilder sb = new StringBuilder();
        if (c0 != null) {
            JSONArray ps = c0.optJSONArray("parts");
            if (ps != null) {
                for (int i = 0; i < ps.length(); i++) {
                    JSONObject p = ps.optJSONObject(i);
                    if (p == null || p.optBoolean("thought", false)) continue;
                    sb.append(p.optString("text", ""));
                }
            }
        }
        return clean(sb.toString());
    }

    // ---------- 拉取模型列表 ----------

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

    private static JSONObject postJson(String url, JSONObject body, Map<String, String> headers,
                                       int timeoutSec) throws Exception {
        if (url == null || url.length() == 0) throw new Exception("没有配置接口地址");
        int t = timeoutSec <= 0 ? 30 : timeoutSec;
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(t * 1000);
            conn.setReadTimeout(Math.max(t, 60) * 1000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
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
            InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String raw = readAll(in);
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code + " " + errorMessage(raw));
            return new JSONObject(raw);
        } finally {
            conn.disconnect();
        }
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
