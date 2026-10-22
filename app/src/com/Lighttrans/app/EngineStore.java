package com.Lighttrans.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 引擎列表与各种设置的本地存储(SharedPreferences + JSON)。
 * 提示词/风格/语言表在 {@link Prompts} 里，跟存储无关。
 */
public class EngineStore {

    private final SharedPreferences sp;

    public EngineStore(Context c) {
        sp = c.getSharedPreferences("lighttrans", Context.MODE_PRIVATE);
    }

    // ---------------- 流式输出 ----------------

    public boolean streamOn() {
        return sp.getBoolean("stream", true);
    }

    public void setStreamOn(boolean on) {
        sp.edit().putBoolean("stream", on).apply();
    }

    // ---------------- 引擎列表 ----------------

    /**
     * 读引擎列表。
     *
     * 读坏了的处理原则：坏一项只丢一项；一项都没读出来时给一份预设先用着，
     * 但**绝不把用户原来的 JSON 覆盖掉**——否则一次解析失败就等于把配置和所有 Key 全清了。
     */
    public List<Engine> engines() {
        String raw = sp.getString("engines", null);
        if (raw == null) {
            // 第一次运行：写入预设
            List<Engine> def = Engine.presets();
            saveEngines(def);
            if (selectedId().length() == 0 && !def.isEmpty()) setSelectedId(def.get(0).id);
            return def;
        }

        List<Engine> list = new ArrayList<Engine>();
        boolean broken = false;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    broken = true; // 不是对象：跳过这一项
                    continue;
                }
                list.add(Engine.fromJson(o));
            }
        } catch (Throwable ignore) {
            broken = true;
        }

        if (list.isEmpty() && broken) {
            // 整份配置都读不出来：内存里先给预设，但保留 raw 不动
            return Engine.presets();
        }

        SharedPreferences.Editor ed = sp.edit();
        if (cleanup(list, ed)) {
            saveEngines(list, ed);
            ed.apply(); // 迁移标志与引擎列表一次提交，避免“标志写了、数据没写”
        }
        return list;
    }

    /**
     * 升级清理与一次性迁移。所有写入都走传进来的 editor，调用方统一 apply。
     *
     * @return 是否改动过（需要提交）
     */
    private boolean cleanup(List<Engine> list, SharedPreferences.Editor ed) {
        boolean changed = false;

        // 升级清理：删掉旧版本内置、现在已去掉的预设（只删没填过 Key 的，不误伤用户自己配置过的）。
        for (int i = list.size() - 1; i >= 0; i--) {
            Engine e = list.get(i);
            if (Engine.isRemovedPreset(e.id) && e.key.length() == 0) {
                list.remove(i);
                changed = true;
            }
        }

        // 一次性迁移：默认供应商换成“皮皮”（把没填过 Key 的 openai 预设替换掉）
        if (!sp.getBoolean("migrated_pipi", false)) {
            ed.putBoolean("migrated_pipi", true);
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
                if (selWasOpenai || selectedId().length() == 0) ed.putString("selectedId", "pipi");
            }
            changed = true;
        }

        // 一次性迁移：v3.2 及以前的“全局风格”改成“每个引擎各自的风格”
        if (!sp.getBoolean("migrated_styles", false)) {
            ed.putBoolean("migrated_styles", true);
            String legacyStyle = sp.getString("styleId", null);
            String legacyPrompt = sp.getString("prompt", null);
            String fallback = "general";
            if (Prompts.styleById(legacyStyle) != null || Prompts.CUSTOM_STYLE_ID.equals(legacyStyle)) {
                fallback = legacyStyle;
            } else if (legacyPrompt != null && legacyPrompt.length() > 0
                    && !legacyPrompt.equals(Prompts.STYLES[0].prompt)) {
                fallback = Prompts.CUSTOM_STYLE_ID;
            }
            for (int i = 0; i < list.size(); i++) {
                Engine e = list.get(i);
                if (e.style == null || e.style.length() == 0) e.style = fallback;
                if (Prompts.CUSTOM_STYLE_ID.equals(e.style)
                        && (e.customPrompt == null || e.customPrompt.length() == 0)
                        && legacyPrompt != null && legacyPrompt.length() > 0) {
                    e.customPrompt = legacyPrompt;
                }
            }
            ed.remove("styleId").remove("prompt").remove("prompt_custom");
            changed = true;
        }

        // 一次性迁移：思考模式默认关闭（旧默认“自动”→“关闭”，翻译更快）
        if (!sp.getBoolean("migrated_think_off", false)) {
            ed.putBoolean("migrated_think_off", true);
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).think == Engine.THINK_AUTO) {
                    list.get(i).think = Engine.THINK_OFF;
                    changed = true;
                }
            }
        }

        // 选中的引擎被删掉时，回退到第一个（列表为空就清空选择，交给 UI 提示“还没添加引擎”）
        if (changed) {
            String sel = selectedId();
            boolean alive = false;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id.equals(sel)) alive = true;
            }
            if (!alive && !list.isEmpty()) ed.putString("selectedId", list.get(0).id);
        }
        return changed;
    }

    public void saveEngines(List<Engine> list) {
        SharedPreferences.Editor ed = sp.edit();
        saveEngines(list, ed);
        ed.apply();
    }

    private void saveEngines(List<Engine> list, SharedPreferences.Editor ed) {
        JSONArray arr = new JSONArray();
        try {
            for (int i = 0; i < list.size(); i++) arr.put(list.get(i).toJson());
        } catch (Throwable ignore) {
        }
        ed.putString("engines", arr.toString());
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

    // ---------------- 目标语言 ----------------

    /** 目标语言 id（auto/zh/en/…）；兼容旧版本存的显示名，读到就顺手换成 id。 */
    public String targetLang() {
        String v = sp.getString("targetLang", "");
        for (int i = 0; i < Prompts.LANG_IDS.length; i++) {
            if (Prompts.LANG_IDS[i].equals(v)) return v;
        }
        for (int i = 0; i < Prompts.LANG_LABELS.length; i++) {
            if (Prompts.LANG_LABELS[i].equals(v)) {
                sp.edit().putString("targetLang", Prompts.LANG_IDS[i]).apply();
                return Prompts.LANG_IDS[i];
            }
        }
        return Prompts.LANG_AUTO;
    }

    public void setTargetLang(String langId) {
        sp.edit().putString("targetLang", langId).apply();
    }

    // ---------------- 多引擎对比 ----------------

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
        if (out.isEmpty() && !all.isEmpty()) out.add(selectedEngine());
        return out;
    }

    public void setCompare(String id, boolean on) {
        List<Engine> list = engines();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) list.get(i).compare = on;
        }
        saveEngines(list);
    }

    // ---------------- 单个引擎的快捷设置 ----------------

    /** 改某个引擎的翻译风格（悬浮卡片上的风格下拉会调它）。 */
    public void setEngineStyle(String id, String style) {
        List<Engine> list = engines();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) list.get(i).style = style;
        }
        saveEngines(list);
    }

    /** 改某个引擎的思考模式（悬浮卡片上的快捷按钮会调它）；改了之后重新探测一次参数支持情况。 */
    public void setEngineThink(String id, int think) {
        List<Engine> list = engines();
        for (int i = 0; i < list.size(); i++) {
            Engine e = list.get(i);
            if (e.id.equals(id)) {
                e.think = think;
                e.thinkParamOk = true;
            }
        }
        saveEngines(list);
    }

    /** 请求层发现某引擎不认识思考参数后，把“别再用参数”这个结论落盘（下次直接跳过，省一次 400 往返）。 */
    public void flushEngineFlags(Engine e) {
        if (e == null || e.thinkParamOk) return;
        List<Engine> list = engines();
        boolean changed = false;
        for (int i = 0; i < list.size(); i++) {
            Engine x = list.get(i);
            if (x.id.equals(e.id) && x.thinkParamOk) {
                x.thinkParamOk = false;
                changed = true;
            }
        }
        if (changed) saveEngines(list);
    }
}
