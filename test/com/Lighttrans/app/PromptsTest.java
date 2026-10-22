package com.Lighttrans.app;

/**
 * 提示词拼装测试：纯 JDK，不需要 Android SDK（Prompts 不依赖 Engine / org.json）。
 * 跑法：./build.sh test
 *
 * 这里盯的就是那个“翻译模式根本没生效”的 bug：
 * 1) 默认必须是外语→中文，不能再自动翻成英文；
 * 2) 六个内置风格都必须带 {目标语言} 占位符，否则语言设置会被丢掉；
 * 3) 自定义提示词没写占位符时，目标语言必须补在末尾。
 */
public final class PromptsTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        String general = Prompts.STYLES[0].id;

        // 1. 默认（auto）：外语译中
        String auto = Prompts.buildPrompt(general, "", Prompts.LANG_AUTO);
        check("默认包含“中文”", auto.contains("中文"));
        check("默认不再翻成英文", !auto.contains("翻译成英文"));
        check("默认对中文原文原样输出", auto.contains("原样输出"));

        // 2. 指定中文：不能被“翻成英文”的规则覆盖
        String zh = Prompts.buildPrompt(general, "", "zh");
        check("中文模式指向中文", zh.contains("翻译成中文"));
        check("中文模式没有英文指令", !zh.contains("英文"));

        // 3. 指定其它语言
        check("日文模式", Prompts.buildPrompt(general, "", "ja").contains("日本語"));

        // 4. 六个内置风格都必须带占位符（漏一个，那个风格的语言设置就失效）
        for (int i = 0; i < Prompts.STYLES.length; i++) {
            Prompts.Style st = Prompts.STYLES[i];
            check("风格「" + st.name + "」带占位符", st.prompt.indexOf("{目标语言}") >= 0);
            check("风格「" + st.name + "」已替换", Prompts.buildPrompt(st.id, "", "ja").indexOf("{目标语言}") < 0);
        }

        // 5. 自定义提示词没写占位符：目标语言要兜底补上
        String cp = Prompts.buildPrompt(Prompts.CUSTOM_STYLE_ID, "把下面的内容翻一下。", "ja");
        check("自定义保留原文", cp.contains("把下面的内容翻一下。"));
        check("自定义也带目标语言", cp.contains("目标语言：日本語"));

        // 6. 自定义提示词里写了占位符：正常替换且不重复追加
        String cp2 = Prompts.buildPrompt(Prompts.CUSTOM_STYLE_ID, "翻译成{目标语言}，只输出译文。", "en");
        check("自定义占位符替换", cp2.contains("翻译成English"));
        check("自定义不重复追加", cp2.indexOf("目标语言：") < 0);

        System.out.println();
        System.out.println(failed == 0
                ? "全部通过：" + passed + " 项"
                : "失败 " + failed + " 项（通过 " + passed + " 项）");
        if (failed != 0) System.exit(1);
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  ok  " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name);
        }
    }
}
