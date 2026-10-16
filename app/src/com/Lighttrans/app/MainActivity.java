package com.Lighttrans.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
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

/** 设置界面：目标语言、提示词(风格)、引擎管理、测试。 */
public class MainActivity extends Activity {

    private EngineStore store;
    private LinearLayout boxEngines;
    private Spinner spLang;
    private EditText edPrompt, edTest;
    private TextView tvTest;
    private CheckBox cbMulti;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new EngineStore(this);
        setContentView(R.layout.activity_main);

        boxEngines = (LinearLayout) findViewById(R.id.boxEngines);
        spLang = (Spinner) findViewById(R.id.spLang);
        edPrompt = (EditText) findViewById(R.id.edPrompt);
        edTest = (EditText) findViewById(R.id.edTest);
        tvTest = (TextView) findViewById(R.id.tvTest);

        ArrayAdapter<String> la = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, EngineStore.LANGS);
        la.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
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

        edPrompt.setText(store.promptTemplate());
        edPrompt.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int c, int d) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int c, int d) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                store.setPromptTemplate(s.toString());
            }
        });

        findViewById(R.id.btnResetPrompt).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                edPrompt.setText(EngineStore.DEFAULT_PROMPT);
                toast("已恢复默认提示词");
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
            row.setPadding(0, dp(6), 0, dp(6));

            RadioButton rb = new RadioButton(this);
            rb.setChecked(e.id.equals(sel));
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
            TextView t2 = new TextView(this);
            t2.setText(e.typeName()
                    + (e.model.length() == 0 ? " · 未填模型" : " · " + e.model)
                    + (e.url.length() == 0 ? " · 未填地址" : "")
                    + (e.key.length() == 0 ? " · 无 Key" : ""));
            t2.setTextSize(11);
            t2.setTextColor(0xFF888888);
            CheckBox cmp = new CheckBox(this);
            cmp.setText("参与对比");
            cmp.setTextSize(11);
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

            Button edit = new Button(this);
            edit.setText("编辑");
            edit.setMinimumWidth(0);
            edit.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    editEngine(e, index);
                }
            });

            Button del = new Button(this);
            del.setText("删除");
            del.setMinimumWidth(0);
            del.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmDelete(e, index);
                }
            });

            Button test = new Button(this);
            test.setText("测试");
            test.setTextSize(12);
            test.setMinimumWidth(0);
            test.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    testEngine(e);
                }
            });
            edit.setTextSize(12);
            edit.setMinimumWidth(0);
            del.setTextSize(12);
            del.setMinimumWidth(0);

            row.addView(rb);
            row.addView(col);
            row.addView(test);
            row.addView(edit);
            row.addView(del);
            boxEngines.addView(row);
        }
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
        final EditText edUrl = (EditText) v.findViewById(R.id.edUrl);
        final EditText edModel = (EditText) v.findViewById(R.id.edModel);
        final EditText edKey = (EditText) v.findViewById(R.id.edKey);

        final String[] types = {"OpenAI 兼容", "Anthropic", "Gemini"};
        ArrayAdapter<String> ta = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, types);
        ta.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spType.setAdapter(ta);
        if (e.type >= 0 && e.type < types.length) spType.setSelection(e.type);

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
        tvTest.setText("测试中… (" + e.name + ")");
        String prompt = store.promptTemplate().replace("{目标语言}",
                EngineStore.langInstruction(store.targetLang()));
        TranslateClient.translateAsync(e, prompt, text, store.timeoutSec(), new TranslateClient.Callback() {
            @Override
            public void onDone(String out, String error) {
                tvTest.setText(error != null ? "失败： " + error : out);
            }
        });
    }

    /** 单独测试某个引擎（用“测试翻译”框里的文字，空则用默认句子）。 */
    private void testEngine(final Engine e) {
        String text = edTest.getText().toString().trim();
        if (text.length() == 0) text = "Hello, this is a test of the translation engine.";
        if (!e.isConfigured()) {
            toast("「" + e.name + "」还没填接口地址或模型");
            return;
        }
        tvTest.setText("测试中… (" + e.name + " / " + e.model + ")");
        String prompt = store.promptTemplate().replace("{目标语言}",
                EngineStore.langInstruction(store.targetLang()));
        TranslateClient.translateAsync(e, prompt, text, store.timeoutSec(), new TranslateClient.Callback() {
            @Override
            public void onDone(String out, String error) {
                tvTest.setText(error != null
                        ? "[" + e.name + "] 失败： " + error
                        : "[" + e.name + " / " + e.model + "]\n" + out);
            }
        });
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
