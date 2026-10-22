package com.Lighttrans.app;

/**
 * 输出清洗：把模型爱加的“壳”去掉，只留译文。
 *
 * 提示词已经写了“只输出译文”，但模型（尤其是套了代理的）照样会加：
 *   “好的，以下是翻译：” / “译文：xxx” / ``` 包一层 / 首尾引号 / “（注：…）” / <think>…
 * 这里统一剥掉，纯 JDK，可单测（见 test/TextCleanerTest.java）。
 */
final class TextCleaner {

    /** 单行前缀标签 */
    private static final String[] LABELS = {
            "译文：", "译文:", "翻译：", "翻译:", "Translation:", "Translation：", "Translated text:", "Translated:"
    };

    /** 纯客套的一行（必须整行精确匹配才丢） */
    private static final String[] COURTESY = {
            "好的", "好的。", "好的!", "好的！", "当然", "当然。", "没问题", "没问题。",
            "ok", "ok.", "ok!", "sure", "sure.", "sure!"
    };

    /** 以冒号结尾的第一行，还得含这些词才判定为开场白（避免误伤正常译文） */
    private static final String[] MARKERS = {
            "翻译", "译文", "以下", "下面", "here", "translation", "translated"
    };

    /** 结尾说明行 */
    private static final String[] TAIL_MARKS = {
            "（注", "(注", "注：", "注:", "ps:", "p.s.", "note:", "希望", "如需", "以上为", "以上是"
    };

    private TextCleaner() {
    }

    static String clean(String s) {
        if (s == null) return "";
        s = s.replaceAll("(?s)<(think|thinking|reasoning)>.*?</\\1>", "");
        s = stripFence(s.trim());
        s = stripPreamble(s);
        s = stripInlineLabel(s);
        s = stripLabel(s.trim());
        s = stripQuotes(s.trim());
        s = stripTail(s);
        return s.trim();
    }

    /**
     * 带原文的清洗：如果原文只有一段，而译文多出一段、且那一段像 AI 在说话，
     * 就把它当“聊天天回复”删掉。
     *
     * 例：原文 "I can verify the source is correct..."（一段）
     *     译文 "验证来源正确……\n\n我无法确认“来源正确”这一论断……请提供构建记录。"
     *     → 只保留第一段。
     * 原文本身就是多段时不动，避免误删真正的译文。
     */
    static String clean(String s, String source) {
        return dropMetaParagraphs(clean(s), source);
    }

    /** 流式用：第一段一写完（出现空行）就能把“任务分析”剔掉，不用等整段结束 */
    static String cleanPartial(String s, String source) {
        return dropMetaParagraphs(cleanPartial(s), source);
    }

    /**
     * 原文只有一段、译文却拆成多段时，把“分析/闲聊”段删掉。
     * 原文本身就是多段就不动，避免误删真正的译文。
     */
    private static String dropMetaParagraphs(String out, String source) {
        if (out == null || out.length() == 0) return "";
        String src = source == null ? "" : source.trim();
        if (src.indexOf("\n\n") >= 0 || out.indexOf("\n\n") < 0) return out;

        String[] paras = out.split("\n\n");
        StringBuilder keep = new StringBuilder();
        for (int i = 0; i < paras.length; i++) {
            String p = paras[i].trim();
            if (p.length() == 0) continue;
            if (isMetaParagraph(p, src)) continue;
            if (keep.length() > 0) keep.append("\n\n");
            keep.append(p);
        }
        if (keep.length() == 0) return out; // 全被判成分析：宁可原样留着
        return keep.toString();
    }

    /**
     * 判断某一段是不是“任务分析”而不是译文。
     *
     * 最硬的信号：这一段把原文原样引用了一遍（模型思考时爱复述原文），
     * 真正的译文不会整段包含原文。其次是"我无法/若需/作为 AI"这类口吻。
     */
    private static boolean isMetaParagraph(String p, String src) {
        String key = normalize(src);
        if (key.length() >= 8 && normalize(p).indexOf(key) >= 0) return true;
        return startsWithMeta(p);
    }

    /** 只保留字母/数字/汉字，忽略空白与标点，便于判断“这段是不是引用了原文”。 */
    private static String normalize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    private static final String[] META = {
            "我无法确认", "我无法核实", "我无法判断", "我无法查看", "我无法访问",
            "作为 ai", "作为一个 ai", "我是一个人工智能", "我是一个语言模型",
            "若需我", "需要我核查", "如需我", "请提供更多", "请提供来源", "请提供具体"
    };

    private static boolean startsWithMeta(String t) {
        String low = t.toLowerCase();
        for (int i = 0; i < META.length; i++) {
            if (low.startsWith(META[i])) return true;
        }
        return false;
    }

    private static final String[] INLINE_LABELS = {
            "译文：", "译文:", "翻译：", "翻译:", "Translation:", "translation:"
    };

    private static final String PUNCT = "。！？，、；：“”‘’「」『』()（）《》";

    /** “xxx译文：”这种行内前缀：冒号前 ≤12 个字符且不含标点，就砍到冒号后面 */
    private static String stripInlineLabel(String s) {
        for (int i = 0; i < INLINE_LABELS.length; i++) {
            String lab = INLINE_LABELS[i];
            int at = s.indexOf(lab);
            if (at > 0 && at <= 12) {
                String head = s.substring(0, at);
                boolean punct = false;
                for (int j = 0; j < PUNCT.length(); j++) {
                    if (head.indexOf(PUNCT.charAt(j)) >= 0) punct = true;
                }
                if (!punct) return s.substring(at + lab.length()).trim();
            }
        }
        return s;
    }

    /**
     * 流式渲染用：已经写完的开场白、还没闭合的思维链、开头的代码块围栏先藏起来，
     * 但不动还没写完的正文（所以不能用完整版 clean，会把半个引号当包裹）。
     */
    static String cleanPartial(String s) {
        if (s == null) return "";
        int t = s.indexOf("<think");
        if (t < 0) t = s.indexOf("<thinking>");
        if (t < 0) t = s.indexOf("<reasoning>");
        if (t >= 0) s = s.substring(0, t);
        s = s.replaceAll("(?s)<(think|thinking|reasoning)>.*?</\\1>", "");
        s = stripFence(s);
        s = stripPreamble(s);
        return stripInlineLabel(s);
    }

    /** ```lang ... ``` 整段包裹时取里面 */
    private static String stripFence(String s) {
        if (!s.startsWith("```")) return s;
        int nl = s.indexOf('\n');
        String body = nl >= 0 ? s.substring(nl + 1) : s.substring(3);
        int end = body.lastIndexOf("```");
        if (end >= 0) body = body.substring(0, end);
        return body.trim();
    }

    /** 丢掉第一行的开场白（“好的，以下是翻译：”这类） */
    private static String stripPreamble(String s) {
        int nl = s.indexOf('\n');
        if (nl < 0) return s; // 只有一行：多半就是译文，不动
        String first = s.substring(0, nl).trim();
        if (first.length() == 0) return s.substring(nl + 1);
        if (first.length() > 40) return s;
        String low = first.toLowerCase();

        boolean courtesy = false;
        for (int i = 0; i < COURTESY.length; i++) {
            if (low.equals(COURTESY[i])) courtesy = true;
        }
        boolean colon = first.endsWith("：") || first.endsWith(":");
        boolean marker = false;
        for (int i = 0; i < MARKERS.length; i++) {
            if (low.indexOf(MARKERS[i]) >= 0) marker = true;
        }
        if (!courtesy && !(colon && marker)) return s;
        return s.substring(nl + 1);
    }

    /** 单行开头的“译文：” */
    private static String stripLabel(String s) {
        for (int i = 0; i < LABELS.length; i++) {
            if (s.startsWith(LABELS[i])) return s.substring(LABELS[i].length()).trim();
        }
        return s;
    }

    /** 整段被引号包住时去掉引号 */
    private static String stripQuotes(String s) {
        if (s.length() < 4) return s;
        char a = s.charAt(0);
        char b = s.charAt(s.length() - 1);
        boolean wrapped = (a == '“' && b == '”') || (a == '「' && b == '」') || (a == '『' && b == '』');
        if (!wrapped) return s;
        String inner = s.substring(1, s.length() - 1).trim();
        if (inner.length() == 0 || inner.indexOf(b) >= 0) return s;
        return inner;
    }

    /** 丢掉结尾的“（注：…）”“希望对你有帮助”之类 */
    private static String stripTail(String s) {
        int nl = s.lastIndexOf('\n');
        if (nl < 0) return s;
        String last = s.substring(nl + 1).trim();
        if (last.length() == 0) return s.substring(0, nl);
        if (last.length() > 60) return s;
        String low = last.toLowerCase();
        for (int i = 0; i < TAIL_MARKS.length; i++) {
            if (low.startsWith(TAIL_MARKS[i])) return s.substring(0, nl);
        }
        boolean paren = (last.startsWith("（") && last.endsWith("）")) || (last.startsWith("(") && last.endsWith(")"));
        if (paren && (low.indexOf("注") >= 0 || low.indexOf("希望") >= 0 || low.indexOf("说明") >= 0)) {
            return s.substring(0, nl);
        }
        return s;
    }
}
