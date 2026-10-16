package com.Lighttrans.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/** 引擎列表与各种设置的本地存储(SharedPreferences + JSON)。 */
public class EngineStore {

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

    /** 自定义风格的 id（对应设置页里自己改的那段提示词）。 */
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

    /** 默认提示词 = “通用”风格。 */
    public static final String DEFAULT_PROMPT = STYLES[0].prompt;

    public static final String[] LANGS = {
            "自动 (中↔英)", "中文", "English", "日本語", "한국어", "Français", "Deutsch", "Русский", "Español"
    };

    private final SharedPreferences sp;

    public EngineStore(Context c) {
        sp = c.getSharedPreferences("lighttrans", Context.MODE_PRIVATE);
    }

    /** 把界面上的语言选项翻译成给 AI 的指令。 */
    public static String langInstruction(String lang) {
        if (lang == null || lang.length() == 0 || lang.indexOf("自动") == 0) {
            return "中文或英文（原文是中文就翻译成英文，其它语言的原文都翻译成中文）";
        }
        return lang;
    }

    // ---------------- 翻译风格 ----------------

    public static Style styleById(String id) {
        for (int i = 0; i < STYLES.length; i++) {
            if (STYLES[i].id.equals(id)) return STYLES[i];
        }
        return null;
    }

    /** 风格下拉框用的名字列表（内置风格 + 末尾一个“自定义”）。 */
    public static String[] styleLabels() {
        String[] out = new String[STYLES.length + 1];
        for (int i = 0; i < STYLES.length; i++) out[i] = STYLES[i].name;
        out[STYLES.length] = "自定义";
        return out;
    }

    /** 当前选中的风格 id；旧版本没有这个键时，按旧提示词是否被改过判断“通用/自定义”。 */
    public String styleId() {
        if (!sp.contains("styleId")) {
            String p = sp.getString("prompt", null);
            return (p == null || p.equals(STYLES[0].prompt)) ? "general" : CUSTOM_STYLE_ID;
        }
        return sp.getString("styleId", "general");
    }

    public void setStyleId(String id) {
        sp.edit().putString("styleId", id).apply();
    }

    /** 用户自己写的那段提示词（“自定义”风格）。 */
    public String customPrompt() {
        return sp.getString("prompt_custom", sp.getString("prompt", STYLES[0].prompt));
    }

    /** 切换到某个预选风格（CUSTOM_STYLE_ID 表示用自定义文本）；同时更新生效的提示词。 */
    public void applyStyle(String id) {
        Style s = styleById(id);
        SharedPreferences.Editor ed = sp.edit();
        ed.putString("styleId", s == null ? CUSTOM_STYLE_ID : id);
        ed.putString("prompt", s != null ? s.prompt : customPrompt());
        ed.apply();
    }

    /** 当前生效的提示词模板。 */
    public String promptTemplate() {
        return sp.getString("prompt", STYLES[0].prompt);
    }

    /** 用户在编辑框里改提示词 = 变成“自定义”风格。 */
    public void setPromptTemplate(String p) {
        sp.edit().putString("prompt", p).putString("prompt_custom", p).putString("styleId", CUSTOM_STYLE_ID).apply();
    }

    // ---------------- 流式输出 ----------------

    public boolean streamOn() {
        return sp.getBoolean("stream", true);
    }

    public void setStreamOn(boolean on) {
        sp.edit().putBoolean("stream", on).apply();
    }

    // ---------------- 引擎列表 ----------------

    public List<Engine> engines() {
        String raw = sp.getString("engines", null);
        if (raw != null) {
            List<Engine> list = new ArrayList<Engine>();
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    list.add(Engine.fromJson(arr.getJSONObject(i)));
                }
            } catch (Throwable ignore) {
                list.clear();
            }
            if (!list.isEmpty()) {
                if (cleanup(list)) saveEngines(list);
                return list;
            }
        }
        List<Engine> def = Engine.presets();
        saveEngines(def);
        if (selectedId().length() == 0 && !def.isEmpty()) setSelectedId(def.get(0).id);
        return def;
    }

    /** 升级清理：删掉旧版本内置、现在已去掉的预设（只删没填过 Key 的，不误伤用户自己配置过的）。 */
    private boolean cleanup(List<Engine> list) {
        boolean changed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            Engine e = list.get(i);
            if (Engine.isRemovedPreset(e.id) && e.key.length() == 0) {
                list.remove(i);
                changed = true;
            }
        }
        // 一次性迁移：默认供应商换成“皮皮”（把没填过 Key 的 openai 预设替换掉）
        if (!sp.getBoolean("migrated_pipi", false)) {
            sp.edit().putBoolean("migrated_pipi", true).apply();
            boolean hasPipi = false;
            for (int i = 0; i < list.size(); i++) {
                if ("pipi".equals(list.get(i).id)) hasPipi = true;
            }
            if (!hasPipi) {
                boolean selWasOpenai = "openai".equals(selectedId());
                boolean openaiConfigured = false;
                for (int i = 0; i < list.size(); i++) {
                    Engine e = list.get(i);
                    if ("openai".equals(e.id) && e.key.length() > 0) openaiConfigured = true;
                }
                if (!openaiConfigured) {
                    for (int i = list.size() - 1; i >= 0; i--) {
                        if ("openai".equals(list.get(i).id)) list.remove(i);
                    }
                }
                list.add(0, Engine.presets().get(0));
                changed = true;
                if (selWasOpenai || selectedId().length() == 0) setSelectedId("pipi");
            }
        }
        if (list.isEmpty()) {
            list.addAll(Engine.presets());
            changed = true;
        }
        if (changed) {
            String sel = selectedId();
            boolean alive = false;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id.equals(sel)) alive = true;
            }
            if (!alive) setSelectedId(list.get(0).id);
        }
        return changed;
    }

    public void saveEngines(List<Engine> list) {
        JSONArray arr = new JSONArray();
        try {
            for (int i = 0; i < list.size(); i++) arr.put(list.get(i).toJson());
        } catch (Throwable ignore) {
        }
        sp.edit().putString("engines", arr.toString()).apply();
    }

    public String selectedId() {
        return sp.getString("selectedId", "");
    }

    public void setSelectedId(String id) {
        sp.edit().putString("selectedId", id).apply();
    }

    public Engine selectedEngine() {
        List<Engine> list = engines();
        String id = selectedId();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) return list.get(i);
        }
        if (list.isEmpty()) return new Engine("none", "未配置引擎", Engine.TYPE_OPENAI, "", "", "");
        return list.get(0);
    }

    public String targetLang() {
        return sp.getString("targetLang", LANGS[0]);
    }

    public void setTargetLang(String lang) {
        sp.edit().putString("targetLang", lang).apply();
    }

    public boolean multiMode() {
        return sp.getBoolean("multi", false);
    }

    public void setMultiMode(boolean on) {
        sp.edit().putBoolean("multi", on).apply();
    }

    /** 多引擎对比：所有勾选了“参与对比”的引擎；一个都没勾就退回当前选中的那个。 */
    public List<Engine> compareEngines() {
        List<Engine> all = engines();
        List<Engine> out = new ArrayList<Engine>();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).compare) out.add(all.get(i));
        }
        if (out.isEmpty()) out.add(selectedEngine());
        return out;
    }

    public void setCompare(String id, boolean on) {
        List<Engine> list = engines();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) list.get(i).compare = on;
        }
        saveEngines(list);
    }

    /** 改某个引擎的思考模式（悬浮卡片上的快捷按钮会调它）。 */
    public void setEngineThink(String id, int think) {
        List<Engine> list = engines();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) list.get(i).think = think;
        }
        saveEngines(list);
    }

    public int timeoutSec() {
        return sp.getInt("timeout", 30);
    }
}
