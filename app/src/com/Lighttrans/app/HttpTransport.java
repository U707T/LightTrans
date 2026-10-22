package com.Lighttrans.app;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/**
 * HTTP 管道：建连接、写 body、读响应、把非 2xx 变成带原始 body 的 {@link HttpError}。
 *
 * 这一层不认识 OpenAI / Anthropic / Gemini，只管“把 JSON 发出去、把字节读回来”，
 * 协议差异在 {@link TranslateClient} 里。取消通过 {@link TranslateClient.Cancel} 传进来：
 * 连上之后把连接挂到句柄上，cancel() 就能直接把阻塞中的读打断。
 */
final class HttpTransport {

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

    private HttpTransport() {
    }

    static HttpURLConnection openPost(String url, JSONObject body, Map<String, String> headers,
                                      int timeoutSec, boolean stream, TranslateClient.Cancel cancel) throws Exception {
        if (url == null || url.length() == 0) throw new Exception("没有配置接口地址");
        int t = timeoutSec <= 0 ? TranslateClient.TIMEOUT_SEC : timeoutSec;
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        if (cancel != null) cancel.attach(conn);
        boolean ok = false;
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(t * 1000);
            // 流式要读很久，非流式也留足余量（读超时是按“单次 read”算的）
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
                throw new HttpError(code, readAll(conn.getErrorStream()));
            }
            ok = true;
            return conn;
        } finally {
            if (!ok) {
                conn.disconnect();
                if (cancel != null) cancel.detach(conn);
            }
        }
    }

    static JSONObject postJson(String url, JSONObject body, Map<String, String> headers,
                               int timeoutSec, TranslateClient.Cancel cancel) throws Exception {
        HttpURLConnection conn = openPost(url, body, headers, timeoutSec, false, cancel);
        try {
            return new JSONObject(readAll(conn.getInputStream()));
        } finally {
            conn.disconnect();
            if (cancel != null) cancel.detach(conn);
        }
    }

    static JSONObject getJson(String url, Map<String, String> headers, int timeoutSec,
                              TranslateClient.Cancel cancel) throws Exception {
        if (url == null || url.length() == 0) throw new Exception("没有配置接口地址");
        int t = timeoutSec <= 0 ? TranslateClient.TIMEOUT_SEC : timeoutSec;
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        if (cancel != null) cancel.attach(conn);
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
            if (cancel != null) cancel.detach(conn);
        }
    }

    static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    /** 把服务端错误体里的人类可读信息抠出来（OpenAI/Anthropic/Gemini 三种形状都试一遍）。 */
    static String errorMessage(String raw) {
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

    static String clip(String s, int max) {
        if (s == null) return "";
        s = s.replace('\n', ' ').trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
