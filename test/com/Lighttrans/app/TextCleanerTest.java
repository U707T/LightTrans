package com.Lighttrans.app;

/**
 * 输出清洗测试：纯 JDK，不需要 Android SDK。
 * 跑法：./build.sh test
 *
 * 目标：模型多说的话必须被剥掉，但正常译文一个字都不能动。
 */
public final class TextCleanerTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        // 1. 正常译文原样保留
        eq("纯译文不动", "今天天气很好。", "今天天气很好。");
        eq("多行译文不动", "第一段。\n第二段。", "第一段。\n第二段。");

        // 2. 开场白
        eq("好的+以下是翻译", "好的，以下是翻译：\n今天天气很好。", "今天天气很好。");
        eq("以下是译文", "以下是译文：\n今天天气很好。", "今天天气很好。");
        eq("纯客套行", "好的\n今天天气很好。", "今天天气很好。");
        eq("英文开场", "Here is the translation:\nIt is a nice day.", "It is a nice day.");

        // 3. 前缀标签
        eq("译文：前缀", "译文：今天天气很好。", "今天天气很好。");
        eq("Translation:", "Translation: It is a nice day.", "It is a nice day.");

        // 4. 代码块包裹
        eq("```包裹", "```\n今天天气很好。\n```", "今天天气很好。");
        eq("```zh包裹", "```zh\n今天天气很好。\n```", "今天天气很好。");

        // 5. 引号包裹
        eq("中文引号", "“今天天气很好。”", "今天天气很好。");
        eq("直角引号", "「今天天气很好。」", "今天天气很好。");

        // 6. 结尾说明
        eq("结尾（注）", "今天天气很好。\n（注：原文为英文）", "今天天气很好。");
        eq("结尾希望", "今天天气很好。\n希望对你有帮助！", "今天天气很好。");

        // 7. 思维链标签
        eq("think 标签", "<think>先想一下</think>今天天气很好。", "今天天气很好。");

        // 8. 流式（还没写完）时的部分清洗
        eqp("流式：开场白已换行", "好的，以下是翻译：\n今天", "今天");
        eqp("流式：正文不完整不动", "今天天气", "今天天气");
        eqp("流式：未闭合的 think", "<think>先想一下", "");
        eqp("流式：围栏先藏", "```\n今天", "今天");

        // 9. 不能误伤
        eq("首行含冒号但是正常译文", "他说：\n今天天气很好。", "他说：\n今天天气很好。");
        eq("单行以注开头", "注：这里指的是天气。", "注：这里指的是天气。");
        eq("译文里本来就有引号", "他说“你好”。", "他说“你好”。");
        eq("空字符串", "", "");

        System.out.println();
        System.out.println(failed == 0
                ? "全部通过：" + passed + " 项"
                : "失败 " + failed + " 项（通过 " + passed + " 项）");
        if (failed != 0) System.exit(1);
    }

    private static void eqp(String name, String input, String expect) {
        String got = TextCleaner.cleanPartial(input);
        if (expect.equals(got)) {
            passed++;
            System.out.println("  ok  " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name + "\n      得到 [" + got + "]\n      期望 [" + expect + "]");
        }
    }

    private static void eq(String name, String input, String expect) {
        String got = TextCleaner.clean(input);
        if (expect.equals(got)) {
            passed++;
            System.out.println("  ok  " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name + "\n      得到 [" + got + "]\n      期望 [" + expect + "]");
        }
    }
}
