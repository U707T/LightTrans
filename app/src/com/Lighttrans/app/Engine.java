package com.Lighttrans.app;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 一个 AI 翻译引擎(提供方)的配置。 */
public class Engine {

    public static final int TYPE_OPENAI = 0;    // OpenAI 兼容 /chat/completions
    public static final int TYPE_ANTHROPIC = 1; // Anthropic /v1/messages
    public static final int TYPE_GEMINI = 2;    // Google Gemini

    /** 思考模式：跟随模型/服务商默认（不发送任何思考相关参数）。 */
    public static final int THINK_AUTO = 0;
    /** 思考模式：尽量关闭思考。 */
    public static final int THINK_OFF = 1;
    /** 思考模式：尽量开启深度思考。 */
    public static final int THINK_ON = 2;

    public static final String[] THINK_NAMES = {"自动", "关闭", "开启"};

    public String id;
    public String name;
    public int type;
    public String url;
    public String model;
    public String key;
    /** 是否参与“多引擎对比”（勾选后，选中翻译时会和别的引擎同时出动） */
    public boolean compare = true;
    /** 思考模式：THINK_AUTO / THINK_OFF / THINK_ON（默认关闭思考，翻译更快） */
    public int think = THINK_OFF;
    /** 这个引擎认不认思考参数：被服务商拒过一次后置 false，之后直接跳过参数（省一次往返）。 */
    public boolean thinkParamOk = true;
    /** 这个引擎用的翻译风格 id（Prompts.STYLES 里的 id 或 CUSTOM_STYLE_ID）；空 = 通用。 */
    public String style = "";
    /** 风格选「自定义」时使用的提示词（一般人用不到）。 */
    public String customPrompt = "";

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

    /** 思考模式的中文名（自动/关闭/开启）。 */
    public String thinkName() {
        if (think < 0 || think >= THINK_NAMES.length) return THINK_NAMES[0];
        return THINK_NAMES[think];
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
        o.put("think", think);
        o.put("thinkOk", thinkParamOk);
        o.put("style", style);
        o.put("customPrompt", customPrompt);
        return o;
    }

    public static Engine fromJson(JSONObject o) {
        Engine e = new Engine(o.optString("id", ""), o.optString("name", ""), o.optInt("type", 0),
                o.optString("url", ""), o.optString("model", ""), o.optString("key", ""));
        e.compare = o.optBoolean("cmp", true);
        e.think = o.optInt("think", THINK_OFF);
        if (e.think < THINK_AUTO || e.think > THINK_ON) e.think = THINK_OFF;
        e.thinkParamOk = o.optBoolean("thinkOk", true);
        e.style = o.optString("style", "");
        e.customPrompt = o.optString("customPrompt", "");
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
