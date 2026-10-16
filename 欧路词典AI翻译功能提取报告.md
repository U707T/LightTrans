# 欧路词典 26.9.1 · AI翻译 / 引擎选择 / 划词悬浮窗 提取报告

对象：`欧路词典_26.9.1.apk`（`com.eusoft.eudic`，versionCode 34302，targetSdk 34，7 个 dex，48727 个类）

## 一、结论速览

| 功能点 | 欧路的实现 | 证据 |
| --- | --- | --- |
| 划词/选中翻译的入口 | **`android.intent.action.PROCESS_TEXT`**（系统选择菜单，无需任何权限） | dex 字符串 `android.intent.action.PROCESS_TEXT`、`android.intent.extra.PROCESS_TEXT_READONLY`（类 `Lʽˈ/native$this;`） |
| 引擎列表 | **服务端下发**，客户端只存 engine id | 本地只有开关/ID 的存储 key，没有任何接口地址、模型名的明文 |
| 引擎选择 | 单选 + 支持多个引擎、可禁用 | `tool_translate_engine`、`tool_translate_engine_disable` |
| 自定义引擎 | 支持，**仅限 OpenAI 兼容接口** | 资源串：`自定义大模型翻译引擎，仅支持与OpenAI兼容的API接口`、`自定义AI引擎`、`自定义模型名`、`自定义翻译 API KEY` |
| 第三方 Key | 只有“智谱”留了本地 Key 存储位 | `tool_translate_engine_zhipu_apikey` |
| 翻译风格 | 官方风格 / 用户风格 / 用户收藏风格，可自定义提示词 | `tool_translate_config_styles`、`_official_styles`、`_user_styles`、`_user_collection_styles`、资源串 `自定义...` |
| 多引擎对比 | 支持“同时参考不同引擎返回的结果” | 资源串 `您可以安装多个翻译引擎，同时参考不同引擎返回的结果` |
| 离线翻译 | 有（需下载离线引擎模型） | `tool_translation_engine_offline*` 系列资源串 |
| 自带“轻览”悬浮窗 | 另一套实现（通知/悬浮窗体系），**不是**划词翻译的必需项 | `light_peek_window_is_top`、`notification_lightpeek_show/hide` |

## 二、存储 key（全部在混淆常量类 `Lˏﹳ/break;` 中，source 标记 `r8-map-id-…`）

```
tool_translate_config                     翻译配置(整体)
tool_translate_config_engines             引擎列表(服务端下发后缓存)
tool_translate_config_styles              风格列表
tool_translate_config_official_styles     官方风格
tool_translate_config_user_styles         用户自定义风格
tool_translate_config_user_collection_styles  用户收藏风格
tool_translate_engine                     当前选中的引擎
tool_translate_engine_disable             被禁用的引擎
tool_translate_engine_zhipu_apikey        智谱自定义 API KEY
ai_chat_setting_model / key_explain_chat_engine / ai_chat_use_direct_chat   问答/解释引擎相关
```

## 三、资源表中的界面文案（节选，可反映产品设计）

```
translation_engine            翻译引擎
main_menu_trans_engine        翻译引擎管理
left_menu_translation_engine  翻译引擎
tool_translation_engine_add / _download   添加翻译引擎
tool_translation_engine_default(_desc)    默认引擎 / 默认机器翻译引擎
tool_translation_engine_gpt_desc          欧路大模型翻译引擎
tool_translation_engine_custom(_desc)     自定义 / 自定义大模型翻译引擎，仅支持与OpenAI兼容的API接口
tool_translation_engine_custom_input_default   自定义AI引擎
tool_translation_engine_model_name_custom      自定义模型名
tool_translation_engine_user_define            自定义翻译 API KEY
tool_translation_engine_style_*                翻译风格(含 自定义...)
tool_translation_engine_offline*               离线引擎(下载/管理/不支持语种等提示)
add_translation_engine    您可以安装多个翻译引擎，同时参考不同引擎返回的结果
```

## 四、没有找到的东西（很关键）

对 7 个 dex 的字符串做了全量检索，以下**全部 0 命中**：

`deepseek` · `openai.com` · `chat/completions` · `dashscope` · `qwen` · `bigmodel` · `glm-4`
`moonshot` · `anthropic` · `generativelanguage` · `ernie` · `volces`(火山) · `ollama`
`translator` 提示词模板 · `只输出` / `翻译成` 类系统提示词

=> 欧路的做法是：**引擎清单、接口地址、模型名、系统提示词全部由服务端下发**（`api.frdic.com` 等自有域名），
客户端只保留“当前引擎 id / 风格 id / 智谱 key”这类轻量配置。所以“AI 引擎选择”在客户端不是一个静态列表，
而是一个远程配置。

## 五、对本次实现的取舍

`轻译` 把这个“服务端下发”的引擎清单换成 **本地内置预设 + 完全自定义**：

- 内置 DeepSeek / 智谱 / 通义 / Kimi / 豆包 / 硅基流动 / OpenAI / Claude / Gemini / Ollama 十种预设，
  均可改地址/模型/Key，也可删；
- 保留“自定义引擎（OpenAI 兼容）”这一欧路式的开放入口 —— 任何 OpenAI 兼容服务都能接；
- 保留可编辑的“翻译风格”（提示词模板，含 `{目标语言}` 占位符）；
- 入口同样用 `ACTION_PROCESS_TEXT`，用半透明对话框 Activity 呈现“悬浮卡片”，
  以此实现 **零权限** 的“选中文本 → 上层显示译文”。
