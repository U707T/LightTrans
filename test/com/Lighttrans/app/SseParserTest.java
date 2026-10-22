package com.Lighttrans.app;

import java.util.ArrayList;
import java.util.List;

/**
 * SseParser 的单元测试：纯 JDK，不需要 Android SDK。
 * 跑法：./build.sh test
 */
public final class SseParserTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        // 1. 最普通的一个事件
        check("单个事件", "data: {\"a\":1}\n\n",
                "|{\"a\":1}");
        // 2. event 字段 + 多行 data（按 SSE 规范用 \n 拼接）
        check("多行 data", "event: message\ndata: 第一行\ndata: 第二行\n\n",
                "message|第一行\n第二行");
        // 3. [DONE] 原样交给上层（由 TranslateClient 自己忽略）
        check("[DONE]", "data: [DONE]\n\n",
                "|[DONE]");
        // 4. 半截 JSON 也要原样吐出（上层 try/catch 丢掉，但不能丢掉整条流）
        check("截断的 JSON", "data: {\"a\":1}\n\ndata: {\"b\":\n\n",
                "|{\"a\":1}",
                "|{\"b\":");
        // 5. 末尾没有空行
        check("末尾无空行", "data: tail",
                "|tail");
        // 6. 注释行与心跳不影响数据
        check("注释行", ": ping\ndata: x\n\n: ping\n\n",
                "|x");
        // 7. 连续空行不产生空事件
        check("连续空行", "\n\ndata: y\n\n\n\n",
                "|y");
        // 8. data: 后面没有空格也要能解析
        check("无空格 data", "data:z\n\n",
                "|z");

        System.out.println();
        System.out.println(failed == 0
                ? "全部通过：" + passed + " 项"
                : "失败 " + failed + " 项（通过 " + passed + " 项）");
        if (failed != 0) System.exit(1);
    }

    private static void check(String name, String input, String... expected) throws Exception {
        final List<String> got = new ArrayList<String>();
        SseParser.parse(input, new SseParser.Handler() {
            @Override
            public void onEvent(String event, String data) {
                got.add(event + "|" + data);
            }
        });
        if (got.size() != expected.length) {
            fail(name, "事件数 " + got.size() + " != " + expected.length + "，实际=" + got);
            return;
        }
        for (int i = 0; i < expected.length; i++) {
            if (!expected[i].equals(got.get(i))) {
                fail(name, "第 " + (i + 1) + " 条是 [" + got.get(i) + "]，期望 [" + expected[i] + "]");
                return;
            }
        }
        passed++;
        System.out.println("  ok  " + name);
    }

    private static void fail(String name, String why) {
        failed++;
        System.out.println("  FAIL " + name + "：" + why);
    }
}
