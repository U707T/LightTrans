package com.Lighttrans.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/** 引擎列表与各种设置的本地存储(SharedPreferences + JSON)。 */
public class EngineStore {

    public static final String DEFAULT_PROMPT =
            "你是一个专业的翻译引擎，请把用户输入的文本翻译成{目标语言}。\n"
            + "要求：\n"
            + "1. 只输出译文本身，不要解释、不要加引号、不要输出“译文：”这样的前缀；\n"
            + "2. 保留原文的换行、段落与格式，专业术语前后一致；\n"
            + "3. 如果原文已经是目标语言，就翻译成英文。";

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

    public String promptTemplate() {
        return sp.getString("prompt", DEFAULT_PROMPT);
    }

    public void setPromptTemplate(String p) {
        sp.edit().putString("prompt", p).apply();
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

    public int timeoutSec() {
        return sp.getInt("timeout", 30);
    }
}
