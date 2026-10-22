# 轻译 · 选中即译（Android 16 友好 / 零权限）

一个“看起来像悬浮窗、实际上不需要任何权限”的 AI 翻译小工具。

**用法**：在任意 App 里选中文本 → 点系统选择菜单里的「AI 翻译」→ 译文以悬浮卡片出现，直接盖在当前界面之上。

| 项目 | 说明 |
| --- | --- |
| 目标系统 | Android 16 (API 36)，minSdk 26 (Android 8) |
| 权限 | **只有 `INTERNET`**（普通权限，安装即授予，不会弹任何授权框）—— 无悬浮窗/通知/前台服务/无障碍 |
| 没有用到 | 悬浮窗权限 / 无障碍服务 / 通知 / 剪贴板读取 / 后台服务 / 读取其它应用 |
| 体积 | 约 70 KB（零第三方依赖） |
| 版本 | v3.7：**只输出译文**（自动剥掉开场白/前缀/代码块/结尾说明）+ 思考过程默认不显示；v3.6：修复**目标语言/翻译模式被“已经是目标语言就翻成英文”覆盖**（现在默认就是外语→中文）；v3.5：修复**本地 http 引擎（Ollama）不可用**、请求不可取消、配置解析失败会清空 Key 等 10 个问题；引擎列表改为「点一行编辑 + ⋯ 菜单」；补上首套单元测试（`./build.sh test`）；仍是**只有 INTERNET** 的零权限版 |

---

## 一、为什么“无权限”也能有悬浮窗

1. **拿文字不靠权限**：选择菜单里的自定义项 = `android.intent.action.PROCESS_TEXT`（Android 6 起内置）。
   只要声明这个 intent-filter，用户选中文本后系统会把选中内容通过 `Intent.EXTRA_PROCESS_TEXT`
   塞进我们的 Intent —— 不需要剪贴板、不需要无障碍、不需要悬浮窗。
2. **“悬浮”靠 Activity 而不是 WindowManager**：`ProcessTextActivity` 是一个半透明、无标题、
   不遮暗背景的**对话框 Activity**，由系统在选择菜单里启动，因此天然位于调用方（你正在用的那个 App）
   的窗口之上。它本质是“应用自己的界面”，所以**不需要 `SYSTEM_ALERT_WINDOW`**。
3. 翻译请求由 App 自己用 `HttpURLConnection` 发给你配置的引擎，明文只存在本机。

> 欧路词典的划词翻译用的是同一套 `ACTION_PROCESS_TEXT`（见《欧路词典AI翻译功能提取报告.md》）。

## 二、安装

```bash
adb install -r dist/轻译.apk
```
或者把 `dist/轻译.apk` 拷到手机点击安装（自签名，允许“安装未知应用”即可）。

## 三、第一次使用

1. 打开「轻译」，在**目标语言**里选默认要翻译成的语言（默认「自动（外语→中文）」）。
2. 在**AI 引擎**里挑一个，点「编辑」填 API Key（也可以点「添加引擎」加自己的）。
   - **必须填**：接口地址、模型名；**按需填**：API Key（本地服务如 Ollama 可留空）。
   - 本地服务直接填 `http://192.168.x.x:11434/v1` 就行：应用已显式允许明文 HTTP（见「已知限制」）。
3. 点「测试」验证一下能通。
4. 回到任意 App（浏览器、聊天、文档…）**长按选中一段文字**，在弹出菜单里点「AI 翻译」。
   - **重点**：看不到时先点菜单右侧的 **⋮ / 更多** —— Android 14/16 和大多数国产 ROM 会把第三方“选中文本”动作收进二级菜单，不内联显示。
   - 还看不到就换入口：选中文字 → 点「**分享**」→ 分享列表里的「轻译」。
   - 想确认装没装好：打开「轻译」→「自检：菜单支持情况」，会列出系统登记的所有“选中菜单/分享菜单”提供方（含本应用）。
   - **在浏览器/网页里永远看不到**：Chrome / Via / 各种 WebView 的选中菜单是内核自己画的（复制/分享/全选/搜索/查找），不列第三方项 —— 欧路词典、Google 翻译也一样，直接走「分享」入口。
   - 菜单里显示的名字是「**轻译**」（和应用同名）。

## 四、悬浮卡片上有什么

- **引擎下拉框**：随时切换引擎（换一个会立即重新翻译，选择会被记住）。
- **编辑引擎**：设置页里点引擎那一行就是编辑；右侧「⋯」里是 测试 / 参与对比 / 删除。
- **风格下拉框**：切换**当前引擎自己的风格**（通用 / 直译 / 地道 / 口语 / 正式 / 文学），切换立即按新风格重译。
- **「思考：xx」按钮**：点一下循环 自动 → 关闭 → 开启，切换后立即重译；开启时模型吐出的思考过程会以灰色小字显示在译文上方。
- **流式译文**：边收边显示（打字机效果，结尾带 ▍ 光标），下方一行显示 引擎 · 耗时 · 目标语言。
- **原文**：超过 3 行时下面会出现「展开原文」，点一下看全文，再点收起。
- **译文**：只显示译文本身——模型多说的“以下是翻译”“译文：”“（注：…）”、``` 代码块、首尾引号都会被自动剥掉。可长按选中复制；点「复制」整段复制（按钮会短暂变成「已复制」）。
- **替换原文**：把译文写回输入框（只读文本会自动隐藏该按钮）。
- **重新翻译**：重发请求。
- **点卡片外面 / ✕**：关闭，回到原来的页面。

## 五、拉取模型 / 选模型 / 逐个测试（v1.2）

- **拉取模型**：设置页 → 某个引擎点「编辑」→ 点 **拉取模型**。会按引擎类型自动请求模型列表接口：
  - OpenAI 兼容 / Anthropic：`GET …/models`（由对话地址推导，如 `…/chat/completions` → `…/models`），自动带你的 Key；
  - Gemini：`GET …/v1beta/models?key=…`。
  拉回来后弹出列表（按名字排序、显示条数），点一下就把模型名填进“模型名”框。
- **逐引擎测试**：引擎列表每行右侧「⋯」→「测试这个引擎」，用“测试翻译”框里的文字（留空则用一句英文）单独试这个引擎，
  结果开头会带 `[引擎名 / 模型名]`，方便对比同一服务下不同模型的翻译效果。
- 底部的「测试」按钮仍然是“测试当前选中的引擎”。

## 六、预设引擎（默认：皮皮）

默认预设是**皮皮**。想接其它服务（DeepSeek / OpenAI / 智谱 / 本地 Ollama…）：点「**添加引擎**」，填接口地址、模型名、Key 即可 —— 支持 OpenAI 兼容 / Anthropic / Gemini 三种协议，也能用「拉取模型」在线取列表。

接口地址**填基地址或完整地址都行**（客户端会自动补全 `/chat/completions`），`http://` 也可以。
Android 9 起默认禁止明文 HTTP，本项目在 manifest 里显式放开了（`usesCleartextTraffic`），
所以本地 Ollama / LM Studio / 局域网 one-api 这类只能走 http 的地址能直接用。

| 名称 | 类型 | 接口地址 | 模型 |
| --- | --- | --- | --- |
| 皮皮 | OpenAI 兼容 | `https://ai.pipiwangcom.com/v1`（等价 `…/v1/chat/completions`） | `deepseek-v4.1-flash` |

升级说明：v1.3 起会自动清掉旧版本内置的一堆预设；v2.5 起自动把**没配置过**的 OpenAI 预设换成「皮皮」（填过 Key 的会保留）。

翻译风格有 6 个预选：**通用 / 直译 / 地道 / 口语 / 正式 / 文学**（默认「通用」，设置页的下拉里不显示「自定义」；悬浮卡片上会多一项「自定义」，方便看清当前引擎用的是哪种风格）。**每个引擎可以有自己的风格**：在引擎的「编辑」里选，或在悬浮卡片上随手换（切换即重译）；升级时旧版的全局风格会自动套到各引擎。想把提示词改成完全自己的：在引擎编辑弹窗底部勾「高级：使用自定义提示词」，编辑框才会出现（一般用不到）。「通用」的默认文案是：

```
你是一个专业的翻译引擎，请把用户输入的文本翻译成{目标语言}。
要求：1. 只输出译文本身，不要解释、不要加引号；
      2. 保留换行与格式，术语一致；
      3. 如果原文已经是{目标语言}，就原样输出，不要换成别的语言。
```
`{目标语言}` 会被替换成上面选的语言；默认的「自动（外语→中文）」就是**中文**——原文已经是中文时原样输出，不再翻成英文。
自定义提示词里如果没写 `{目标语言}`，目标语言会自动补在末尾（以前会静默丢掉，等于设置没生效）。

### 思考模式（v3.4：默认关闭）

每个引擎有「思考模式」三档：**自动**（不发任何思考参数，跟模型默认）/ **关闭（默认，最快）** / **开启**。悬浮卡片上点「思考：xx」即可循环切换并立即重译。开启后，很多服务会返回思考过程（DeepSeek 的 `reasoning_content`、Claude 的 `thinking`、Gemini 的 `thought` 分片），会实时以灰色小字显示。**只有把「思考」切到「开启」才显示**；默认（自动/关闭）一律不展示，免得一堆推理过程盖住译文。

参数按协议自动映射：

| 协议 | 关闭 | 开启 |
| --- | --- | --- |
| OpenAI 兼容 | `enable_thinking:false` + `thinking:{type:"disabled"}` | `enable_thinking:true` + `thinking:{type:"enabled"}` |
| Anthropic | （API 默认即关闭，不发参数） | `thinking:{type:"enabled",budget_tokens:2048}`（max_tokens 提到 4096，且不再发 temperature） |
| Gemini | `thinkingConfig.thinkingBudget: 0` | `thinkingConfig.thinkingBudget: -1`（动态） |

如果服务商不认识这些参数（HTTP 400 参数类报错），会自动去掉参数**重试一次**，并记住这个结论——之后该引擎的请求直接跳过参数、不再多一次往返，不会因此翻译失败。

### 流式输出（v3.2）

默认开启：译文通过 SSE 边收边显示；引擎/代理不支持 SSE 时会自动回退成一次性请求（设置页「流式输出」可手动关掉）。

## 七、重新编译

本机验证过的构建方式是离线脚本（不需要 Gradle、不需要联网）：

```bash
ANDROID_SDK=/your/android-sdk ./build.sh     # 产出 dist/轻译.apk（自动生成调试证书签名）
./build.sh test                              # 纯 JDK 单元测试（SSE 解析），不需要 SDK

# 想和 CI 的 Release 用同一张证书（这样能覆盖安装）：
KEYSTORE_B64=$(base64 -w0 ../轻译-keys/lighttrans-release.jks) \
KEYSTORE_PASS=$(cat ../轻译-keys/lighttrans-release-password.txt) ./build.sh
```
依赖：SDK 的 `aapt2 / d8 / zipalign / apksigner` + JDK 的 `javac / keytool`。

想在 Android Studio 里改：新建一个 Empty 工程，把 `app/src`、`app/res`、`app/AndroidManifest.xml`
拷进去，删掉 manifest 的 `package="…"`（改用 `namespace "com.Lighttrans.app"`）即可，代码里没有任何第三方依赖。

## 八、已知限制

- 只处理 `text/plain` 的选择；某些 App（自带 WebView 内部选择、PDF 阅读器）不提供 PROCESS_TEXT 菜单。
- 流式输出对个别不支持 SSE 的服务/代理会自动回退成一次性请求（需要时可在设置页关掉流式）。
- 思考模式「关闭」对 Anthropic 等于默认行为（API 本身没有强制关闭的参数）；各家服务对思考参数支持不一，以自动降级后的实际效果为准。
- API Key 明文保存在 App 私有 SharedPreferences —— 手机没 Root 时其它应用读不到；该文件已排除在系统云备份/换机迁移之外
  （`res/xml/backup_rules.xml`、`res/xml/data_extraction_rules.xml`），不会跟着备份跑出去。
- 本版本**不做**常驻悬浮球（那需要 `SYSTEM_ALERT_WINDOW`，v3.0 试过、v3.1 已删）。

## 九、目录结构

```
轻译/
├── build.sh                     # 离线构建脚本(已验证)
├── README.md
├── CHANGELOG.md / LICENSE
├── .github/workflows/ci.yml     # push → 自动构建 + 发 Release
├── ic_launcher.svg              # 图标源文件（矢量，真源）
├── tools/make_icons.py          # 由 svg 生成各密度 PNG
├── 欧路词典AI翻译功能提取报告.md
├── app/
│   ├── AndroidManifest.xml      # 声明 PROCESS_TEXT 入口 + INTERNET
│   ├── res/                     # 主题 / 布局 / 图标
│   └── src/com/Lighttrans/app/
│       ├── ProcessTextActivity.java   # 悬浮卡片本体(流式/风格/思考快捷切换)
│       ├── MainActivity.java          # 设置:目标语言/引擎(风格·思考)/测试
│       ├── TranslateClient.java       # OpenAI兼容 / Anthropic / Gemini(含 SSE 流式、取消)
│       ├── HttpTransport.java         # HTTP 管道(建连接/读响应/错误体解析)
│       ├── SseParser.java             # SSE 解析(不依赖 Android，可单测)
│       ├── EngineStore.java           # 引擎列表与设置(SharedPreferences)
│       ├── Prompts.java               # 内置风格 + 语言表(纯常量)
│       └── Engine.java                # 引擎模型 + 内置预设
└── test/                              # ./build.sh test 跑的纯 JDK 单测
└── dist/轻译.apk                # 构建产物(已签名)
```

## 十、开源协议

[MIT](LICENSE) © 2026 U707T

> 仓库结构：`CHANGELOG.md` 记录版本变更；`.github/workflows/ci.yml` 在 push 到 `main` 后自动构建 APK 并发布 Release
> （若在仓库里配置了 `KEYSTORE_B64` / `KEYSTORE_PASS` 两个 Secret，CI 会用固定证书签名，保证旧版可以覆盖安装；
> 没配置时用每次随机生成的调试证书，仅供测试）。
