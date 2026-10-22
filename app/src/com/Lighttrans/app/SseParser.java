package com.Lighttrans.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;

/**
 * 极简 SSE（Server-Sent Events）解析：事件由空行分隔，data 可多行，注释行(:)忽略。
 *
 * 刻意不依赖 Android（只用 java.io），这样可以用字符串直接喂测试：
 * 见 test/com/Lighttrans/app/SseParserTest.java 与 `./build.sh test`。
 */
final class SseParser {

    interface Handler {
        void onEvent(String event, String data);
    }

    private SseParser() {
    }

    static void parse(String text, Handler handler) throws IOException {
        parse(new StringReader(text), handler);
    }

    static void parse(InputStream in, Handler handler) throws IOException {
        parse(new InputStreamReader(in, "UTF-8"), handler);
    }

    static void parse(Reader in, Handler handler) throws IOException {
        BufferedReader r = in instanceof BufferedReader ? (BufferedReader) in : new BufferedReader(in);
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
            if (line.charAt(0) == ':') continue; // 注释/心跳
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
        // 最后一段没有以空行结尾时也要吐出来
        if (data.length() > 0) handler.onEvent(event, data.toString());
    }
}
