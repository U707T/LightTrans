package com.Lighttrans.app;

/**
 * 内置的翻译风格与语言表。
 *
 * 纯常量 + 纯函数，不碰 SharedPreferences、不依赖 Android——
 * 这样 UI / 网络层拿提示词时不必依赖存储层，也能直接单测。
 */
public final class Prompts {

    /** 一个“翻译风格”预选：一段带 {目标语言} 占位符的提示词模板。 */
    public static final class Style {
        public final String id;
        public final String name;
        public final String prompt;

        public Style(String id, String name, String prompt) {
            this.id = id;
            this.name = name;
            this.prompt = prompt;
        }
    }

    /** 自定义风格的 id（可选，一般人用不到）。 */
    public static final String CUSTOM_STYLE_ID = "custom";

    /** 内置翻译风格预选：通用 / 直译 / 地道 / 口语 / 正式 / 文学。 */
    public static final Style[] STYLES = {
            new Style("general", "通用",
                    "你是一个专业的翻译引擎，请把用户输入的文本翻译成{目标语言}。\n"
                    + "要求：\n"
                    + "1. 只输出译文本身，不要解释、不要加引号、不要输出“译文：”这样的前缀；\n"
                    + "2. 保留原文的换行、段落与格式，专业术语前后一致；\n"
                    + "3. 如果原文已经是目标语言，就翻译成英文。"),
            new Style("literal", "直译",
                    "你是一个专业的翻译引擎，请把用户输入的文本逐句直译成{目标语言}。\n"
                    + "要求：\n"
                    + "1. 忠实原文的结构与语序，逐句对应，不增删、不省略、不发挥；\n"
                    + "2. 保留原文的换行、段落与格式，专业术语前后一致；\n"
                    + "3. 只输出译文本身，不要解释、不要加引号。"),
            new Style("natural", "地道",
                    "你是一名母语级译者，请把用户输入的文本翻译成{目标语言}。\n"
                    + "要求：\n"
                    + "1. 译文要自然地道，读起来像目标语言的母语者写的一样，摆脱“翻译腔”；\n"
                    + "2. 保留原文的换行、段落与格式，语气与原文一致；\n"
                    + "3. 只输出译文本身，不要解释、不要加引号。"),
            new Style("casual", "口语",
                    "请把用户输入的文本翻译成{目标语言}。\n"
                    + "要求：\n"
                    + "1. 用轻松、口语化的语气，像日常聊天一样自然；\n"
                    + "2. 保留原文的换行与段落；\n"
                    + "3. 只输出译文本身，不要解释、不要加引号。"),
            new Style("formal", "正式",
                    "你是一名专业译者，请把用户输入的文本翻译成{目标语言}。\n"
                    + "要求：\n"
                    + "1. 使用正式、严谨的书面语，措辞专业（适合商务、公文、学术场景）；\n"
                    + "2. 术语准确、前后一致，保留原文的换行与格式；\n"
                    + "3. 只输出译文本身，不要解释、不要加引号。"),
            new Style("literary", "文学",
                    "你是一名文学译者，请把用户输入的文本翻译成{目标语言}。\n"
                    + "要求：\n"
                    + "1. 讲究文采与语感，可以适度意译、润色，让译文优美流畅；\n"
                    + "2. 保留原文的段落与格式，尽力传达原文的情绪与风格；\n"
                    + "3. 只输出译文本身，不要解释、不要加引号。"),
    };

    /** “自动”语言：原文是中文就翻成英文，其它语言都翻成中文。 */
    public static final String LANG_AUTO = "auto";

    /** 语言 id：持久化只存这个，显示名随时可以改（改文案不会丢用户设置）。 */
    public static final String[] LANG_IDS = {
            "auto", "zh", "en", "ja", "ko", "fr", "de", "ru", "es"
    };

    /** 语言的显示名（只用于界面，与 LANG_IDS 一一对应）。 */
    public static final String[] LANG_LABELS = {
            "自动 (中↔英)", "中文", "English", "日本語", "한국어", "Français", "Deutsch", "Русский", "Español"
    };

    private Prompts() {
    }

    /** 把语言 id 翻译成给 AI 的指令。未知 id 原样返回（兼容旧版本存下来的显示名）。 */
    public static String langInstruction(String langId) {
        if (langId == null || langId.length() == 0 || LANG_AUTO.equals(langId)) {
            return "中文或英文（原文是中文就翻译成英文，其它语言的原文都翻译成中文）";
        }
        return langLabel(langId);
    }

    /** 语言 id -> 显示名。 */
    public static String langLabel(String langId) {
        for (int i = 0; i < LANG_IDS.length; i++) {
            if (LANG_IDS[i].equals(langId)) return LANG_LABELS[i];
        }
        return langId == null ? "" : langId;
    }

    public static Style styleById(String id) {
        for (int i = 0; i < STYLES.length; i++) {
            if (STYLES[i].id.equals(id)) return STYLES[i];
        }
        return null;
    }

    /** 风格下拉框用的名字列表（只列内置风格；「自定义」见 {@link #styleLabelsWithCustom()}）。 */
    public static String[] styleLabels() {
        String[] out = new String[STYLES.length];
        for (int i = 0; i < STYLES.length; i++) out[i] = STYLES[i].name;
        return out;
    }

    /**
     * 悬浮卡片用：内置风格 + 末尾「自定义」。
     * 自定义提示词为空时会退化成「通用」，所以多列一项不会产生坏结果；
     * 这样下拉里永远有一项能如实表示“这个引擎正在用自定义风格”。
     */
    public static String[] styleLabelsWithCustom() {
        String[] out = new String[STYLES.length + 1];
        for (int i = 0; i < STYLES.length; i++) out[i] = STYLES[i].name;
        out[STYLES.length] = "自定义";
        return out;
    }

    /** 风格的显示名（空/未知都按“通用”）。 */
    public static String styleName(String id) {
        Style s = styleById(id);
        if (s != null) return s.name;
        return CUSTOM_STYLE_ID.equals(id) ? "自定义" : "通用";
    }

    /** 解析某个引擎实际使用的提示词模板（“自定义”留空时退回“通用”）。 */
    public static String promptFor(Engine e) {
        String sid = (e == null || e.style == null) ? "" : e.style;
        if (CUSTOM_STYLE_ID.equals(sid)) {
            String cp = e.customPrompt == null ? "" : e.customPrompt.trim();
            if (cp.length() > 0) return cp;
            return STYLES[0].prompt;
        }
        Style s = styleById(sid);
        return s != null ? s.prompt : STYLES[0].prompt;
    }
}
