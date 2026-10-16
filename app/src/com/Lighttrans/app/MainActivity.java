package com.Lighttrans.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/** 设置界面：目标语言、翻译风格(预选)、提示词、流式开关、引擎管理、测试。 */
public class MainActivity extends Activity {

    private static final int ACCENT = 0xFF2F6BFF;

    private EngineStore store;
    private LinearLayout boxEngines;
    private Spinner spLang;
    private EditText edTest;
    private TextView tvTest;
    private CheckBox cbMulti, cbStream;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new EngineStore(this);
        setContentView(R.layout.activity_main);

        boxEngines = (LinearLayout) findViewById(R.id.boxEngines);
        spLang = (Spinner) findViewById(R.id.spLang);
        edTest = (EditText) findViewById(R.id.edTest);
        tvTest = (TextView) findViewById(R.id.tvTest);

        // ---------- 目标语言 ----------
        ArrayAdapter<String> la = new ArrayAdapter<String>(this, R.layout.item_spinner, EngineStore.LANGS);
        la.setDropDownViewResource(R.layout.item_spinner_dropdown);
        spLang.setAdapter(la);
        String lang = store.targetLang();
        for (int i = 0; i < EngineStore.LANGS.length; i++) {
            if (EngineStore.LANGS[i].equals(lang)) spLang.setSelection(i);
        }
        spLang.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                store.setTargetLang(EngineStore.LANGS[pos]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });

        // ---------- 流式输出开关 ----------
        cbStream = (CheckBox) findViewById(R.id.cbStream);
        cbStream.setChecked(store.streamOn());
        cbStream.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                store.setStreamOn(checked);
            }
        });

        findViewById(R.id.btnAddEngine).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addEngine();
            }
        });

        findViewById(R.id.btnTest).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                test();
            }
        });

        findViewById(R.id.btnSelfCheck).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selfCheck();
            }
        });

        cbMulti = (CheckBox) findViewById(R.id.cbMulti);
        cbMulti.setChecked(store.multiMode());
        cbMulti.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                store.setMultiMode(checked);
            }
        });

        rebuildEngines();
    }

    // ---------- 引擎列表 ----------

    private void rebuildEngines() {
        boxEngines.removeAllViews();
        final List<Engine> list = store.engines();
        final String sel = store.selectedId();
        for (int i = 0; i < list.size(); i++) {
            final Engine e = list.get(i);
            final int index = i;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.bg_row);
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = dp(8);
            row.setLayoutParams(rlp);

            RadioButton rb = new RadioButton(this);
            rb.setChecked(e.id.equals(sel));
            rb.setButtonTintList(ColorStateList.valueOf(ACCENT));
            rb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    store.setSelectedId(e.id);
                    rebuildEngines();
                }
            });

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView t1 = new TextView(this);
            t1.setText(e.name);
            t1.setTextSize(15);
            t1.setTextColor(0xFF15181E);
            TextView t2 = new TextView(this);
            t2.setText(e.typeName()
                    + (e.model.length() == 0 ? " · 未填模型" : " · " + e.model)
                    + (e.url.length() == 0 ? " · 未填地址" : "")
                    + (e.key.length() == 0 ? " · 无 Key" : "")
                    + (e.style == null || e.style.length() == 0 || "general".equals(e.style)
                       ? "" : " · 风格:" + EngineStore.styleName(e.style))
                    + (e.think == Engine.THINK_ON ? " · 思考:开启" : ""));
            t2.setTextSize(11);
            t2.setTextColor(0xFF9AA1AE);
            CheckBox cmp = new CheckBox(this);
            cmp.setText("参与对比");
            cmp.setTextSize(11);
            cmp.setButtonTintList(ColorStateList.valueOf(ACCENT));
            cmp.setChecked(e.compare);
            cmp.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    store.setCompare(e.id, ((CheckBox) v).isChecked());
                }
            });
            col.addView(t1);
            col.addView(t2);
            col.addView(cmp);

            Button test = smallBtn("测试", R.drawable.btn_ghost, ACCENT);
            test.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    testEngine(e);
                }
            });
            Button edit = smallBtn("编辑", R.drawable.btn_ghost, ACCENT);
            edit.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    editEngine(e, index);
                }
            });
            Button del = smallBtn("删除", R.drawable.btn_plain, 0xFF5F6673);
            del.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmDelete(e, index);
                }
            });

            row.addView(rb);
            row.addView(col);
            row.addView(test);
            row.addView(edit);
            row.addView(del);
            boxEngines.addView(row);
        }
    }

    private Button smallBtn(String text, int bg, int textColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setTextColor(textColor);
        b.setStateListAnimator(null);
        b.setBackgroundResource(bg);
        b.setPadding(dp(10), dp(4), dp(10), dp(4));
        b.setMinimumWidth(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinHeight(0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(4);
        b.setLayoutParams(lp);
        return b;
    }

    /** 添加引擎：直接用 OpenAI 预设当模板，省掉一层“选预设”的弹窗。 */
    private void addEngine() {
        Engine p = Engine.presets().get(0);
        editEngine(new Engine("", p.name, p.type, p.url, p.model, p.key), -1);
    }

    private void editEngine(final Engine e, final int index) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_engine, null);
        final EditText edName = (EditText) v.findViewById(R.id.edName);
        final Spinner spType = (Spinner) v.findViewById(R.id.spType);
        final Spinner spThink = (Spinner) v.findViewById(R.id.spThink);
        final EditText edUrl = (EditText) v.findViewById(R.id.edUrl);
        final EditText edModel = (EditText) v.findViewById(R.id.edModel);
        final EditText edKey = (EditText) v.findViewById(R.id.edKey);

        final String[] types = {"OpenAI 兼容", "Anthropic", "Gemini"};
        ArrayAdapter<String> ta = new ArrayAdapter<String>(this, R.layout.item_spinner, types);
        ta.setDropDownViewResource(R.layout.item_spinner_dropdown);
        spType.setAdapter(ta);
        if (e.type >= 0 && e.type < types.length) spType.setSelection(e.type);

        final String[] thinks = {"自动（跟模型默认）", "关闭思考", "开启思考"};
        ArrayAdapter<String> tha = new ArrayAdapter<String>(this, R.layout.item_spinner, thinks);
        tha.setDropDownViewResource(R.layout.item_spinner_dropdown);
        spThink.setAdapter(tha);
        spThink.setSelection((e.think >= 0 && e.think <= 2) ? e.think : 0);

        // 翻译风格：每个引擎各自的（下拉只列内置风格）；想用自定义提示词要勾底下的高级开关
        final Spinner spStyle = (Spinner) v.findViewById(R.id.spStyle);
        final CheckBox cbCustom = (CheckBox) v.findViewById(R.id.cbCustomPrompt);
        final EditText edCustomPrompt = (EditText) v.findViewById(R.id.edCustomPrompt);
        final View boxCustomPrompt = v.findViewById(R.id.boxCustomPrompt);
        ArrayAdapter<String> stA = new ArrayAdapter<String>(this, R.layout.item_spinner, EngineStore.styleLabels());
        stA.setDropDownViewResource(R.layout.item_spinner_dropdown);
        spStyle.setAdapter(stA);
        int stylePos = 0;
        for (int i = 0; i < EngineStore.STYLES.length; i++) {
            if (EngineStore.STYLES[i].id.equals(e.style)) {
                stylePos = i;
                break;
            }
        }
        spStyle.setSelection(stylePos);
        final boolean wasCustom = EngineStore.CUSTOM_STYLE_ID.equals(e.style);
        cbCustom.setChecked(wasCustom);
        edCustomPrompt.setText(e.customPrompt == null ? "" : e.customPrompt);
        boxCustomPrompt.setVisibility(wasCustom ? View.VISIBLE : View.GONE);
        spStyle.setEnabled(!wasCustom);
        cbCustom.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                boxCustomPrompt.setVisibility(checked ? View.VISIBLE : View.GONE);
                spStyle.setEnabled(!checked);
            }
        });

        edName.setText(e.name);
        edUrl.setText(e.url);
        edModel.setText(e.model);
        edKey.setText(e.key);

        final Button btnFetch = (Button) v.findViewById(R.id.btnFetchModels);
        btnFetch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                int tt = spType.getSelectedItemPosition();
                Engine probe = new Engine(e.id, "probe", (tt < 0 || tt > 2) ? Engine.TYPE_OPENAI : tt,
                        edUrl.getText().toString().trim(),
                        edModel.getText().toString().trim(),
                        edKey.getText().toString().trim());
                if (probe.url.length() == 0) {
                    toast("先填接口地址再拉取");
                    return;
                }
                btnFetch.setEnabled(false);
                btnFetch.setText("拉取中…");
                TranslateClient.listModelsAsync(probe, store.timeoutSec(), new TranslateClient.ModelsCallback() {
                    @Override
                    public void onDone(final List<String> models, final String error) {
                        btnFetch.setEnabled(true);
                        btnFetch.setText("拉取模型");
                        if (error != null) {
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("拉取失败")
                                    .setMessage(error)
                                    .setPositiveButton("知道了", null)
                                    .show();
                            return;
                        }
                        final String[] arr = models.toArray(new String[0]);
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("选择模型（" + arr.length + " 个）")
                                .setItems(arr, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int which) {
                                        edModel.setText(arr[which]);
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                });
            }
        });

        new AlertDialog.Builder(this)
                .setTitle(index < 0 ? "添加引擎" : "编辑引擎")
                .setView(v)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        e.name = edName.getText().toString().trim();
                        int t = spType.getSelectedItemPosition();
                        e.type = (t < 0 || t > 2) ? Engine.TYPE_OPENAI : t;
                        int th = spThink.getSelectedItemPosition();
                        e.think = (th < 0 || th > 2) ? Engine.THINK_AUTO : th;
                        e.customPrompt = edCustomPrompt.getText().toString().trim();
                        if (cbCustom.isChecked() && e.customPrompt.length() > 0) {
                            e.style = EngineStore.CUSTOM_STYLE_ID;
                        } else {
                            int st = spStyle.getSelectedItemPosition();
                            e.style = (st >= 0 && st < EngineStore.STYLES.length)
                                    ? EngineStore.STYLES[st].id : "general";
                        }
                        e.thinkParamOk = true; // 配置有变化，重新探测一次思考参数支持
                        e.url = edUrl.getText().toString().trim();
                        e.model = edModel.getText().toString().trim();
                        e.key = edKey.getText().toString().trim();
                        if (e.name.length() == 0) e.name = "未命名引擎";

                        List<Engine> list = store.engines();
                        if (index < 0) {
                            e.id = "e" + System.currentTimeMillis();
                            list.add(e);
                            store.setSelectedId(e.id);
                        } else if (index < list.size()) {
                            list.set(index, e);
                        } else {
                            list.add(e);
                        }
                        store.saveEngines(list);
                        rebuildEngines();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDelete(final Engine e, final int index) {
        new AlertDialog.Builder(this)
                .setTitle("删除引擎")
                .setMessage("确定删除「" + e.name + "」？")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        List<Engine> list = store.engines();
                        if (index >= 0 && index < list.size()) list.remove(index);
                        store.saveEngines(list);
                        if (e.id.equals(store.selectedId()) && list.size() > 0) {
                            store.setSelectedId(list.get(0).id);
                        }
                        rebuildEngines();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------- 测试 ----------

    private String resolvePrompt(Engine e) {
        return EngineStore.promptFor(e).replace("{目标语言}",
                EngineStore.langInstruction(store.targetLang()));
    }

    private void test() {
        String text = edTest.getText().toString().trim();
        if (text.length() == 0) {
            toast("先输入要翻译的文字");
            return;
        }
        final Engine e = store.selectedEngine();
        if (!e.isConfigured()) {
            tvTest.setText("「" + e.name + "」还没填接口地址或模型。");
            return;
        }
        tvTest.setTextColor(0xFF15181E);
        tvTest.setText("测试中… (" + e.name + ")");
        runTest(e, resolvePrompt(e), text, false);
    }

    /** 单独测试某个引擎（用“测试翻译”框里的文字，空则用默认句子）。 */
    private void testEngine(final Engine e) {
        String text = edTest.getText().toString().trim();
        if (text.length() == 0) text = "Hello, this is a test of the translation engine.";
        if (!e.isConfigured()) {
            toast("「" + e.name + "」还没填接口地址或模型");
            return;
        }
        tvTest.setTextColor(0xFF15181E);
        tvTest.setText("测试中… (" + e.name + " / " + e.model + ")");
        runTest(e, resolvePrompt(e), text, true);
    }

    /** 流式测试：结果边收边显示，跟悬浮卡片同款效果。 */
    private void runTest(final Engine e, String prompt, final String text, final boolean withModel) {
        final StringBuilder tb = new StringBuilder();
        final long[] last = {0};
        final boolean[] thinking = {false};
        final long start = System.currentTimeMillis();
        final TranslateClient.StreamCallback sink = new TranslateClient.StreamCallback() {
            @Override
            public void onDelta(String d) {
                tb.append(d);
                long now = System.currentTimeMillis();
                if (now - last[0] < 80) return;
                last[0] = now;
                tvTest.setText(head(e, withModel, 0) + tb + "▍");
            }

            @Override
            public void onReasoning(String d) {
                if (tb.length() == 0 && !thinking[0]) {
                    thinking[0] = true;
                    tvTest.setText("模型思考中…");
                }
            }

            @Override
            public void onDone(String full, String error) {
                store.flushEngineFlags(e);
                if (error != null) {
                    tvTest.setTextColor(0xFFCC3B33);
                    tvTest.setText(head(e, withModel, 0) + "失败： " + error
                            + (tb.length() > 0 ? "\n（部分输出）\n" + tb : ""));
                } else {
                    if (tb.length() == 0 && full != null) tb.append(full);
                    tvTest.setTextColor(0xFF15181E);
                    tvTest.setText(head(e, withModel, System.currentTimeMillis() - start) + tb);
                }
            }
        };
        if (store.streamOn()) {
            TranslateClient.translateStreamAsync(e, prompt, text, store.timeoutSec(), sink);
        } else {
            TranslateClient.translateAsync(e, prompt, text, store.timeoutSec(), new TranslateClient.Callback() {
                @Override
                public void onDone(String out, String err) {
                    sink.onDone(out, err);
                }
            });
        }
    }

    private static String head(Engine e, boolean withModel, long ms) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(e.name);
        if (withModel && e.model.length() > 0) sb.append(" / ").append(e.model);
        sb.append(" · ").append(EngineStore.styleName(e.style));
        if (ms > 0) sb.append(" · ").append(String.format(Locale.CHINA, "%.1fs", ms / 1000.0));
        sb.append("]\n");
        return sb.toString();
    }

    /** 自检：把系统里所有“选中菜单 / 分享菜单”提供方列出来，方便判断是没装好还是被菜单藏起来了。 */
    private void selfCheck() {
        StringBuilder sb = new StringBuilder();
        Intent probe = new Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain");
        List<ResolveInfo> ris = getPackageManager().queryIntentActivities(probe, 0);
        sb.append("选中菜单(ACTION_PROCESS_TEXT)提供方 ").append(ris.size()).append(" 个：\n");
        boolean self = false;
        int selfIndex = -1;
        for (int i = 0; i < ris.size(); i++) {
            ResolveInfo ri = ris.get(i);
            String pkg = ri.activityInfo == null ? "?" : ri.activityInfo.packageName;
            if ("com.Lighttrans.app".equals(pkg)) {
                self = true;
                selfIndex = i + 1;
            }
            sb.append("  ").append(i + 1).append(". ")
                    .append(String.valueOf(ri.loadLabel(getPackageManager())))
                    .append("  [").append(pkg).append("]\n");
        }
        if (self) {
            sb.append("\n✔ 本应用已经在系统列表里：排第 ").append(selfIndex).append(" 位 / 共 ")
                    .append(ris.size()).append(" 个。\n");
            sb.append("（系统按包名 ASCII 序排；系统应用可能被提到最前。菜单里的顺序同理。）\n");
        } else {
            sb.append("\n✘ 本应用不在系统列表里：请确认装的是最新版 APK（重装一次）。\n");
        }
        Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain");
        int n = getPackageManager().queryIntentActivities(share, 0).size();
        sb.append("\n分享菜单(ACTION_SEND)提供方 ").append(n)
                .append(" 个 —— 选中文字后点「分享」，同样能找到「AI 翻译」。");

        sb.append("\n点这段文字即可一键复制（或点「复制全部」），发给 AI 分析。");

        final String text = sb.toString();
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("自检：菜单支持情况")
                .setMessage(text)
                .setPositiveButton("复制全部", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        copyText(text);
                    }
                })
                .setNegativeButton("知道了", null)
                .create();
        dlg.show();
        View msg = dlg.findViewById(android.R.id.message);
        if (msg != null) {
            msg.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    copyText(text);
                }
            });
        }
    }

    private void copyText(String s) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("轻译", s));
        toast("已复制到剪贴板");
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
