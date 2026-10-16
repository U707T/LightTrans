package com.Lighttrans.app;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 一个 AI 翻译引擎(提供方)的配置。 */
public class Engine {

    public static final int TYPE_OPENAI = 0;    // OpenAI 兼容 /chat/completions
    public static final int TYPE_ANTHROPIC = 1; // Anthropic /v1/messages
    public static final int TYPE_GEMINI = 2;    // Google Gemini

    public String id;
    public String name;
    public int type;
    public String url;
    public String model;
    public String key;
    /** 是否参与“多引擎对比”（勾选后，选中翻译时会和别的引擎同时出动） */
    public boolean compare = true;

    public Engine(String id, String name, int type, String url, String model, String key) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.url = url == null ? "" : url;
        this.model = model == null ? "" : model;
        this.key = key == null ? "" : key;
    }

    public String typeName() {
        if (type == TYPE_ANTHROPIC) return "Anthropic";
        if (type == TYPE_GEMINI) return "Gemini";
        return "OpenAI 兼容";
    }

    public boolean isConfigured() {
        return url.length() > 0 && model.length() > 0;
    }

    public JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("type", type);
        o.put("url", url);
        o.put("model", model);
        o.put("key", key);
        o.put("cmp", compare);
        return o;
    }

    public static Engine fromJson(JSONObject o) {
        Engine e = new Engine(o.optString("id", ""), o.optString("name", ""), o.optInt("type", 0),
                o.optString("url", ""), o.optString("model", ""), o.optString("key", ""));
        e.compare = o.optBoolean("cmp", true);
        return e;
    }

    @Override
    public String toString() {
        return name;
    }

    /** 预设引擎：只留一个“皮皮”。想接别的服务(DeepSeek/OpenAI/本地 Ollama 等)请用“添加引擎”。
     *  接口地址可以填基地址(…/v1)，也可以填完整地址(…/chat/completions)，客户端会自动补全。 */
    public static List<Engine> presets() {
        List<Engine> l = new ArrayList<Engine>();
        l.add(new Engine("pipi", "皮皮", TYPE_OPENAI,
                "https://ai.pipiwangcom.com/v1", "deepseek-v4.1-flash", ""));
        return l;
    }

    /** v1.2 及以前内置、v1.3 起已去掉的预设 id（升级时自动清理没填过 Key 的）。 */
    private static final String[] REMOVED_PRESET_IDS = {
            "deepseek", "zhipu", "qwen", "kimi", "doubao",
            "siliconflow", "claude", "gemini", "ollama", "custom"
    };

    public static boolean isRemovedPreset(String id) {
        if (id == null) return false;
        for (int i = 0; i < REMOVED_PRESET_IDS.length; i++) {
            if (REMOVED_PRESET_IDS[i].equals(id)) return true;
        }
        return false;
    }
}
