package com.Lighttrans.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * “悬浮窗”本体。
 *
 * 它由系统的文本选择菜单（android.intent.action.PROCESS_TEXT）以半透明对话框的形式启动，
 * 因此天然叠加在被选中文本的 App 之上 —— 看起来像悬浮窗，但完全不需要悬浮窗权限。
 *
 * v3.2：译文流式显示（打字机）；可切换翻译风格；可快捷调整模型思考模式。
 */
public class ProcessTextActivity extends Activity {

    /** 超过这个字数就提示“可能超出模型上限”（只提示，不擅自截断）。 */
    private static final int LONG_TEXT_WARN = 20000;

    private EngineStore store;
    private List<Engine> engines;
    private Spinner spEngine, spStyle;
    private TextView tvSrc, tvSrcToggle, tvDst, tvStatus, tvThink;
    private Button btnRetry, btnCopy, btnReplace, btnThink;
    private LinearLayout boxResults;
    private String source = "";
    private String result = "";
    private boolean readOnly = false;
    private boolean srcExpanded = false;
    private boolean longSource = false;
    private int reqSeq = 0;
    private int lastTriggeredPos = -1;
    private int lastStylePos = -1;
    /** 多引擎对比的结构化结果：复制/替换都从这里取，不再去反解 TextView 里的文本。 */
    private List<Result> multiResults;
    /** 当前正在跑的请求，换引擎/关卡片时统一取消。 */
    private final List<TranslateClient.Cancel> running = new ArrayList<TranslateClient.Cancel>();

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        store = new EngineStore(this);

        Window win = getWindow();
        applyWindowWidth(win);
        win.setBackgroundDrawable(new ColorDrawable(0x00000000));

        setContentView(R.layout.activity_process_text);

        tvSrc = (TextView) findViewById(R.id.tvSrc);
        tvSrcToggle = (TextView) findViewById(R.id.tvSrcToggle);
        tvDst = (TextView) findViewById(R.id.tvDst);
        tvStatus = (TextView) findViewById(R.id.tvStatus);
        tvThink = (TextView) findViewById(R.id.tvThink);
        boxResults = (LinearLayout) findViewById(R.id.boxResults);
        spEngine = (Spinner) findViewById(R.id.spEngine);
        spStyle = (Spinner) findViewById(R.id.spStyle);
        btnRetry = (Button) findViewById(R.id.btnRetry);
        btnCopy = (Button) findViewById(R.id.btnCopy);
        btnReplace = (Button) findViewById(R.id.btnReplace);
        btnThink = (Button) findViewById(R.id.btnThink);
        Button btnClose = (Button) findViewById(R.id.btnClose);

        Intent it = getIntent();
        CharSequence cs = it == null ? null : it.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        readOnly = it != null && it.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false);
        if (cs == null && it != null) {
            cs = it.getCharSequenceExtra(Intent.EXTRA_TEXT); // 从“分享”菜单进来
            if (cs != null) readOnly = true;                 // 分享进来的文本没法写回原处
        }
        source = cs == null ? "" : cs.toString().trim();
        longSource = source.length() > LONG_TEXT_WARN;

        tvSrc.setText(source);
        // 原文超过 3 行时给一个展开入口：不然长文本没法核对到底把什么发出去了
        if (source.length() > 80) {
            tvSrcToggle.setVisibility(View.VISIBLE);
            tvSrcToggle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    srcExpanded = !srcExpanded;
                    tvSrc.setMaxLines(srcExpanded ? Integer.MAX_VALUE : 3);
                    tvSrcToggle.setText(srcExpanded ? "收起原文 ▴" : "展开原文 ▾");
                }
            });
        }
        btnReplace.setVisibility(readOnly ? View.GONE : View.VISIBLE);

        // ---------- 引擎下拉 ----------
        engines = store.engines();
        ArrayAdapter<Engine> ad = new ArrayAdapter<Engine>(this, R.layout.item_spinner, engines);
        ad.setDropDownViewResource(R.layout.item_spinner_dropdown);
        spEngine.setAdapter(ad);
        int idx = 0;
        for (int i = 0; i < engines.size(); i++) {
            if (engines.get(i).id.equals(store.selectedId())) {
                idx = i;
                break;
            }
        }
        spEngine.setSelection(idx);
        spEngine.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (pos == lastTriggeredPos) return; // 忽略打开时系统自动触发的那一次
                if (pos >= 0 && pos < engines.size()) store.setSelectedId(engines.get(pos).id);
                updateThinkLabel();
                syncStyleSpinner();
                doTranslate();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        // ---------- 风格下拉（每个引擎各自的翻译风格） ----------
        // 多给一项「自定义」：索引 STYLES.length 是合法的，自定义风格也能如实显示。
        ArrayAdapter<String> sa = new ArrayAdapter<String>(this, R.layout.item_spinner, Prompts.styleLabelsWithCustom());
        sa.setDropDownViewResource(R.layout.item_spinner_dropdown);
        spStyle.setAdapter(sa);
        int styleIdx = styleIndex(engines.isEmpty() ? null : engines.get(idx));
        spStyle.setSelection(styleIdx);
        lastStylePos = styleIdx;
        spStyle.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (pos == lastStylePos) return;
                lastStylePos = pos;
                Engine e = currentEngine();
                if (e == null) return;
                e.style = pos >= 0 && pos < Prompts.STYLES.length
                        ? Prompts.STYLES[pos].id : Prompts.CUSTOM_STYLE_ID;
                store.setEngineStyle(e.id, e.style);
                doTranslate();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        // ---------- 思考模式快捷按钮：自动 → 关闭 → 开启 循环 ----------
        btnThink.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Engine cur = currentEngine();
                if (cur == null) return;
                cur.think = cur.think == Engine.THINK_AUTO ? Engine.THINK_OFF
                        : (cur.think == Engine.THINK_OFF ? Engine.THINK_ON : Engine.THINK_AUTO);
                store.setEngineThink(cur.id, cur.think);
                updateThinkLabel();
                doTranslate();
            }
        });
        updateThinkLabel();

        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        btnRetry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doTranslate();
            }
        });
        btnCopy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String s;
                if (multiResults != null && !multiResults.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < multiResults.size(); i++) {
                        Result r = multiResults.get(i);
                        if (r.text.length() == 0) continue;
                        sb.append(r.name).append("：\n").append(r.text).append("\n\n");
                    }
                    s = sb.toString().trim();
                    if (s.length() == 0) s = source;
                } else {
                    s = result.length() > 0 ? result : source;
                }
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("轻译", s));
                btnCopy.setText("已复制");
                btnCopy.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        btnCopy.setText("复制");
                    }
                }, 1200);
            }
        });
        btnReplace.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String out = result;
                if (out.length() == 0 && multiResults != null) {
                    for (int i = 0; i < multiResults.size(); i++) {
                        Result r = multiResults.get(i);
                        if (r.error == null && r.text.length() > 0) {
                            out = r.text;
                            break;
                        }
                    }
                }
                if (out.length() == 0) {
                    toast("还没有译文");
                    return;
                }
                Intent res = new Intent();
                res.putExtra(Intent.EXTRA_PROCESS_TEXT, (CharSequence) out);
                setResult(RESULT_OK, res);
                finish();
            }
        });

        if (source.length() == 0) {
            setRetryEnabled(false);
            tvStatus.setText("没有收到选中文本：请先选中一段文字，再在菜单里点「AI 翻译」。");
        } else {
            doTranslate();
        }
    }

    @Override
    protected void onDestroy() {
        cancelRunning();
        super.onDestroy();
    }

    /** 旋转后窗口宽度要按新屏幕重算（本 Activity 声明了 configChanges，不会重建）。 */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyWindowWidth(getWindow());
    }

    private void applyWindowWidth(Window win) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        win.setLayout((int) (dm.widthPixels * 0.94f), ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    // ---------- 小工具 ----------

    private int styleIndex(Engine e) {
        String id = (e == null || e.style == null) ? "" : e.style;
        for (int i = 0; i < Prompts.STYLES.length; i++) {
            if (Prompts.STYLES[i].id.equals(id)) return i;
        }
        return Prompts.CUSTOM_STYLE_ID.equals(id) ? Prompts.STYLES.length : 0; // 未知/空按“通用”
    }

    private Engine currentEngine() {
        int pos = spEngine.getSelectedItemPosition();
        if (pos < 0 || pos >= engines.size()) return null;
        return engines.get(pos);
    }

    /** 切换引擎后，把风格下拉同步到该引擎自己的风格（不触发重译）。 */
    private void syncStyleSpinner() {
        Engine e = currentEngine();
        int idx = styleIndex(e);
        if (idx == spStyle.getSelectedItemPosition()) return;
        lastStylePos = idx;
        spStyle.setSelection(idx);
    }

    private void updateThinkLabel() {
        Engine e = currentEngine();
        if (e == null) {
            btnThink.setText("思考");
            btnThink.setEnabled(false);
            return;
        }
        btnThink.setEnabled(true);
        btnThink.setText("思考：" + e.thinkName());
    }

    private String langText() {
        return Prompts.langInstruction(store.targetLang());
    }

    private static String secs(long ms) {
        return String.format(Locale.CHINA, "%.1fs", ms / 1000.0);
    }

    /** 原文过长时的提醒（只提醒，不截断——截断译文比报错更难发现）。 */
    private String longNote() {
        return longSource ? "（原文 " + source.length() + " 字，可能超出模型上限，建议分段翻译）" : "";
    }

    /** 禁用时同时压暗：否则「翻译中」的按钮看起来和可点的一模一样。 */
    private void setRetryEnabled(boolean on) {
        btnRetry.setEnabled(on);
        btnRetry.setAlpha(on ? 1f : 0.45f);
    }

    private void cancelRunning() {
        for (int i = 0; i < running.size(); i++) running.get(i).cancel();
        running.clear();
    }

    /** 按“流式开关”发请求；提示词按“这个引擎自己的风格”解析。返回的句柄用于取消。 */
    private TranslateClient.Cancel startTranslate(final Engine e, final TranslateClient.StreamCallback sink) {
        String prompt = Prompts.promptFor(e).replace("{目标语言}", Prompts.langInstruction(store.targetLang()));
        TranslateClient.Cancel c;
        if (store.streamOn()) {
            c = TranslateClient.translateStreamAsync(e, prompt, source, TranslateClient.TIMEOUT_SEC, sink);
        } else {
            c = TranslateClient.translateAsync(e, prompt, source, TranslateClient.TIMEOUT_SEC,
                    new TranslateClient.Callback() {
                        @Override
                        public void onDone(String text, String error) {
                            sink.onDone(text, error);
                        }
                    });
        }
        running.add(c);
        return c;
    }

    // ---------- 翻译 ----------

    private void doTranslate() {
        if (source.length() == 0) return;
        if (engines.isEmpty()) {
            tvDst.setText("");
            tvStatus.setTextColor(getColor(R.color.text_hint));
            tvStatus.setText("还没有引擎：先打开「轻译」点「添加引擎」配置一个。");
            setRetryEnabled(false);
            return;
        }
        int pos = spEngine.getSelectedItemPosition();
        if (pos < 0 || pos >= engines.size()) pos = 0;
        lastTriggeredPos = pos;
        final Engine cur = engines.get(pos);
        store.setSelectedId(cur.id);

        final List<Engine> targets = store.multiMode() ? store.compareEngines() : null;

        cancelRunning(); // 旧请求直接断开，别让它继续烧 token

        setRetryEnabled(false);
        result = "";
        multiResults = null;

        if (targets == null || targets.size() <= 1) {
            // ---------------- 单引擎（流式打字机） ----------------
            final Engine e = (targets != null && targets.size() == 1) ? targets.get(0) : cur;
            boxResults.setVisibility(View.GONE);
            boxResults.removeAllViews();
            tvDst.setVisibility(View.VISIBLE);
            tvThink.setVisibility(View.GONE);
            tvThink.setText("");
            if (!e.isConfigured()) {
                tvDst.setText("");
                tvStatus.setTextColor(getColor(R.color.text_hint));
                tvStatus.setText("「" + e.name + "」还没填接口地址或模型：请打开「轻译」应用配置一下。");
                setRetryEnabled(true);
                return;
            }
            final int seq = ++reqSeq;
            tvDst.setText("…");
            tvStatus.setTextColor(getColor(R.color.text_hint));
            tvStatus.setText("正在用 " + e.name + " 翻译…");
            startTranslate(e, new SingleSink(seq, e));
            return;
        }

        // ---------------- 多引擎并行（像欧路那样同时看多家结果） ----------------
        tvDst.setVisibility(View.GONE);
        tvDst.setText("");
        boxResults.setVisibility(View.VISIBLE);
        boxResults.removeAllViews();
        final int batchSeq = ++reqSeq;
        final Batch batch = new Batch();
        batch.total = targets.size();
        final List<Result> results = new ArrayList<Result>();
        for (int i = 0; i < targets.size(); i++) {
            final Engine e = targets.get(i);
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(0, dp(8), 0, dp(8));
            TextView head = new TextView(this);
            head.setText(e.name + (e.model.length() > 0 ? "  ·  " + e.model : "") + "  ·  " + e.typeName()
                    + (e.style == null || e.style.length() == 0 || "general".equals(e.style)
                       ? "" : "  ·  " + Prompts.styleName(e.style))
                    + (e.think == Engine.THINK_ON ? "  ·  思考开启" : ""));
            head.setTextSize(11);
            head.setTextColor(getColor(R.color.text_hint));
            final TextView body = new TextView(this);
            body.setTextSize(16);
            body.setLineSpacing(dp(4), 1f);
            body.setTextIsSelectable(true);
            body.setTextColor(getColor(R.color.text_main));
            body.setText(e.isConfigured() ? "…" : "未配置接口地址或模型");
            col.addView(head);
            col.addView(body);
            boxResults.addView(col);

            final Result r = new Result(e.name);
            results.add(r);
            if (!e.isConfigured()) {
                r.error = "未配置接口地址或模型";
                batch.done++;
                continue;
            }
            startTranslate(e, new MultiSink(batchSeq, e, body, r, batch));
        }
        multiResults = results;
        tvStatus.setTextColor(getColor(R.color.text_hint));
        tvStatus.setText(targets.size() + " 个引擎 · 已完成 " + batch.done + "/" + batch.total
                + " · " + langText() + longNote());
        setRetryEnabled(true);
    }

    // ---------- 流式接收 ----------

    private static final class Batch {
        int total;
        int done;
    }

    /** 多引擎对比里单个引擎的结果。 */
    private static final class Result {
        final String name;
        String text = "";
        String error = null;

        Result(String name) {
            this.name = name;
        }
    }

    private abstract class BaseSink implements TranslateClient.StreamCallback {
        final int seq;
        final Engine eng;
        final long start = System.currentTimeMillis();
        final StringBuilder ans = new StringBuilder();
        final StringBuilder rea = new StringBuilder();
        private long lastFlush = 0;

        BaseSink(int seq, Engine eng) {
            this.seq = seq;
            this.eng = eng;
        }

        @Override
        public final void onDelta(String d) {
            ans.append(d);
            maybeFlush(false);
        }

        @Override
        public final void onReasoning(String d) {
            rea.append(d);
            maybeFlush(false);
        }

        @Override
        public final void onDone(String full, String error) {
            if (reqSeq != seq) return;
            store.flushEngineFlags(eng);
            if (error == null && ans.length() == 0 && full != null && full.length() > 0) ans.append(full);
            // 有些代理会把  thinking 直接塞进正文：收尾时统一剥一次，别把裸标签留在界面上
            if (ans.indexOf("<think") >= 0 || ans.indexOf("<reasoning>") >= 0) {
                String cleaned = TranslateClient.clean(ans.toString());
                ans.setLength(0);
                ans.append(cleaned);
            }
            render(true);
            onFinish(error);
        }

        private void maybeFlush(boolean force) {
            if (reqSeq != seq) return;
            long now = System.currentTimeMillis();
            if (!force && now - lastFlush < 70) return; // 70ms 节流，太快反而闪烁
            lastFlush = now;
            render(false);
        }

        abstract void render(boolean finished);

        abstract void onFinish(String error);
    }

    /** 单引擎：译文打在 tvDst 上，思考过程打在 tvThink 上。 */
    private final class SingleSink extends BaseSink {
        SingleSink(int seq, Engine e) {
            super(seq, e);
        }

        @Override
        void render(boolean finished) {
            if (reqSeq != seq) return;
            if (rea.length() > 0) {
                tvThink.setVisibility(View.VISIBLE);
                tvThink.setText((finished ? "思考过程" : "思考中…") + "\n" + rea.toString());
            }
            String t = ans.toString();
            if (t.length() > 0) {
                tvDst.setText(finished ? t : t + "▍");
                result = t;
            }
        }

        @Override
        void onFinish(String error) {
            if (reqSeq != seq) return;
            setRetryEnabled(true);
            if (error != null) {
                tvStatus.setTextColor(getColor(R.color.danger));
                tvStatus.setText("翻译失败： " + error
                        + (ans.length() > 0 ? "（以上为已收到的部分译文）" : "") + longNote());
                if (ans.length() == 0) {
                    tvDst.setText("");
                    result = "";
                }
            } else {
                tvStatus.setTextColor(getColor(R.color.text_hint));
                tvStatus.setText(eng.name + " · " + secs(System.currentTimeMillis() - start)
                        + " · " + langText() + longNote());
            }
        }
    }

    /** 多引擎：每个引擎一块，各自流式刷新。 */
    private final class MultiSink extends BaseSink {
        private final TextView body;
        private final Result res;
        private final Batch batch;

        MultiSink(int seq, Engine e, TextView body, Result res, Batch batch) {
            super(seq, e);
            this.body = body;
            this.res = res;
            this.batch = batch;
        }

        @Override
        void render(boolean finished) {
            if (reqSeq != seq) return;
            String t = ans.toString();
            res.text = t;
            if (t.length() > 0) {
                body.setTextColor(getColor(R.color.text_main));
                body.setText(finished ? t : t + "▍");
            } else if (!finished) {
                body.setTextColor(getColor(R.color.text_hint));
                body.setText(rea.length() > 0 ? "思考中…" : "…");
            }
        }

        @Override
        void onFinish(String error) {
            if (reqSeq != seq) return;
            res.text = ans.toString();
            res.error = error;
            batch.done++;
            if (error != null) {
                body.setTextColor(getColor(R.color.danger));
                body.setText(ans.length() > 0 ? ans.toString() + "\n（失败： " + error + "）" : "失败： " + error);
            }
            tvStatus.setTextColor(getColor(R.color.text_hint));
            tvStatus.setText(batch.total + " 个引擎 · 已完成 " + batch.done + "/" + batch.total
                    + " · " + langText() + longNote());
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
