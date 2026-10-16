package com.Lighttrans.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
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

/**
 * “悬浮窗”本体。
 *
 * 它由系统的文本选择菜单（android.intent.action.PROCESS_TEXT）以半透明对话框的形式启动，
 * 因此天然叠加在被选中文本的 App 之上 —— 看起来像悬浮窗，但完全不需要悬浮窗权限。
 */
public class ProcessTextActivity extends Activity {

    private EngineStore store;
    private List<Engine> engines;
    private Spinner spEngine;
    private TextView tvSrc, tvDst, tvStatus;
    private Button btnRetry, btnCopy, btnReplace;
    private LinearLayout boxResults;
    private List<TextView> multiOuts;
    private List<String> multiNames;
    private String source = "";
    private String result = "";
    private boolean readOnly = false;
    private int reqSeq = 0;
    private int lastTriggeredPos = -1;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        store = new EngineStore(this);

        Window win = getWindow();
        DisplayMetrics dm = getResources().getDisplayMetrics();
        win.setLayout((int) (dm.widthPixels * 0.94f), ViewGroup.LayoutParams.WRAP_CONTENT);
        win.setBackgroundDrawable(new ColorDrawable(0x00000000));

        setContentView(R.layout.activity_process_text);

        tvSrc = (TextView) findViewById(R.id.tvSrc);
        tvDst = (TextView) findViewById(R.id.tvDst);
        tvStatus = (TextView) findViewById(R.id.tvStatus);
        boxResults = (LinearLayout) findViewById(R.id.boxResults);
        spEngine = (Spinner) findViewById(R.id.spEngine);
        btnRetry = (Button) findViewById(R.id.btnRetry);
        btnCopy = (Button) findViewById(R.id.btnCopy);
        btnReplace = (Button) findViewById(R.id.btnReplace);
        Button btnClose = (Button) findViewById(R.id.btnClose);

        Intent it = getIntent();
        CharSequence cs = it == null ? null : it.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        readOnly = it != null && it.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false);
        if (cs == null && it != null) {
            cs = it.getCharSequenceExtra(Intent.EXTRA_TEXT); // 从“分享”菜单进来
            if (cs != null) readOnly = true;                 // 分享进来的文本没法写回原处
        }
        source = cs == null ? "" : cs.toString().trim();

        tvSrc.setText(source);
        btnReplace.setVisibility(readOnly ? View.GONE : View.VISIBLE);

        engines = store.engines();
        ArrayAdapter<Engine> ad = new ArrayAdapter<Engine>(this, android.R.layout.simple_spinner_item, engines);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
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
                doTranslate();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

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
                if (multiOuts != null && multiNames != null && !multiOuts.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < multiOuts.size(); i++) {
                        sb.append(multiNames.get(i)).append("：\n")
                                .append(multiOuts.get(i).getText()).append("\n\n");
                    }
                    s = sb.toString().trim();
                } else {
                    s = result.length() > 0 ? result : source;
                }
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("轻译", s));
                toast("已复制");
            }
        });
        btnReplace.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String out = result;
                if (out.length() == 0 && multiOuts != null) {
                    for (int i = 0; i < multiOuts.size(); i++) {
                        String t = multiOuts.get(i).getText().toString();
                        if (t.length() > 0 && !t.equals("…") && !t.startsWith("失败")
                                && !t.startsWith("未配置")) {
                            out = t;
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
            btnRetry.setEnabled(false);
            tvStatus.setText("没有收到选中文本：请先选中一段文字，再在菜单里点「AI 翻译」。");
        } else {
            doTranslate();
        }
    }

    private void doTranslate() {
        if (source.length() == 0) return;
        int pos = spEngine.getSelectedItemPosition();
        if (pos < 0 || pos >= engines.size()) pos = 0;
        lastTriggeredPos = pos;
        final Engine cur = engines.get(pos);
        store.setSelectedId(cur.id);

        final String prompt = store.promptTemplate().replace("{目标语言}",
                EngineStore.langInstruction(store.targetLang()));
        final List<Engine> targets = store.multiMode() ? store.compareEngines() : null;

        btnRetry.setEnabled(false);
        result = "";
        multiOuts = null;
        multiNames = null;

        if (targets == null || targets.size() <= 1) {
            // ---------------- 单引擎 ----------------
            final Engine e = (targets != null && targets.size() == 1) ? targets.get(0) : cur;
            boxResults.setVisibility(View.GONE);
            boxResults.removeAllViews();
            tvDst.setVisibility(View.VISIBLE);
            if (!e.isConfigured()) {
                tvDst.setText("");
                tvStatus.setText("「" + e.name + "」还没填接口地址或模型：请打开「轻译」应用配置一下。");
                btnRetry.setEnabled(true);
                return;
            }
            final int seq = ++reqSeq;
            tvDst.setText("");
            tvStatus.setText("正在用 " + e.name + " 翻译…");
            TranslateClient.translateAsync(e, prompt, source, store.timeoutSec(), new TranslateClient.Callback() {
                @Override
                public void onDone(String text, String error) {
                    if (seq != reqSeq) return;
                    btnRetry.setEnabled(true);
                    if (error != null) {
                        tvStatus.setText("翻译失败： " + error);
                        return;
                    }
                    result = text;
                    tvDst.setText(text);
                    tvStatus.setText(e.name + " · 目标语言： " + EngineStore.langInstruction(store.targetLang()));
                }
            });
            return;
        }

        // ---------------- 多引擎并行（像欧路那样同时看多家结果） ----------------
        tvDst.setVisibility(View.GONE);
        tvDst.setText("");
        boxResults.setVisibility(View.VISIBLE);
        boxResults.removeAllViews();
        final int batch = ++reqSeq;
        final List<TextView> outs = new ArrayList<TextView>();
        final List<String> names = new ArrayList<String>();
        for (int i = 0; i < targets.size(); i++) {
            final Engine e = targets.get(i);
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(0, dp(6), 0, dp(6));
            TextView head = new TextView(this);
            head.setText(e.name + (e.model.length() > 0 ? "  ·  " + e.model : "") + "  ·  " + e.typeName());
            head.setTextSize(11);
            head.setTextColor(0xFF888888);
            final TextView body = new TextView(this);
            body.setTextSize(16);
            body.setTextIsSelectable(true);
            body.setTextColor(0xFF111111);
            body.setText(e.isConfigured() ? "…" : "未配置接口地址或模型");
            col.addView(head);
            col.addView(body);
            boxResults.addView(col);
            outs.add(body);
            names.add(e.name);
            if (!e.isConfigured()) continue;
            TranslateClient.translateAsync(e, prompt, source, store.timeoutSec(), new TranslateClient.Callback() {
                @Override
                public void onDone(String text, String error) {
                    if (batch != reqSeq) return;
                    if (error != null) {
                        body.setTextColor(0xFFCC3333);
                        body.setText("失败： " + error);
                    } else {
                        body.setText(text);
                    }
                    int done = 0;
                    for (int k = 0; k < outs.size(); k++) {
                        if (!"…".equals(outs.get(k).getText().toString())) done++;
                    }
                    tvStatus.setText(names.size() + " 个引擎 · 已完成 " + done + "/" + outs.size()
                            + " · " + EngineStore.langInstruction(store.targetLang()));
                }
            });
        }
        tvStatus.setText("正在用 " + targets.size() + " 个引擎翻译…");
        multiOuts = outs;
        multiNames = names;
        btnRetry.setEnabled(true);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
