package com.nebula.aichat;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.nebula.aichat.data.ChatStore;
import com.nebula.aichat.data.Message;
import com.nebula.aichat.data.Settings;
import com.nebula.aichat.net.ChatClient;
import com.nebula.aichat.net.ModelListClient;
import com.nebula.aichat.ui.ChatBackground;
import com.nebula.aichat.ui.Palette;

import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends AppCompatActivity {

    private Settings settings;
    private EditText etKey, etBase, etModel, etSystem, etMaxTokens;
    private SeekBar sbTemp, sbFont;
    private TextView tvTemp, tvFont, tvToggleKey, tvPickModel, tvModelHint;
    private SwitchCompat swStream;
    private Button btnTest;
    private boolean keyVisible = false;
    private final ChatClient testClient = new ChatClient();
    private final ModelListClient modelListClient = new ModelListClient();
    private List<String> cachedModels = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.nebula.aichat.ui.Palette.apply(this);   // 主题/强调色必须在 setContentView 之前
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        settings = Settings.load(this);

        etKey = findViewById(R.id.etKey);
        etBase = findViewById(R.id.etBase);
        etModel = findViewById(R.id.etModel);
        etSystem = findViewById(R.id.etSystem);
        etMaxTokens = findViewById(R.id.etMaxTokens);
        sbTemp = findViewById(R.id.sbTemp);
        tvTemp = findViewById(R.id.tvTemp);
        sbFont = findViewById(R.id.sbFont);
        tvFont = findViewById(R.id.tvFont);
        tvToggleKey = findViewById(R.id.tvToggleKey);
        swStream = findViewById(R.id.swStream);
        btnTest = findViewById(R.id.btnTest);
        tvPickModel = findViewById(R.id.tvPickModel);
        tvModelHint = findViewById(R.id.tvModelHint);

        findViewById(R.id.btnBack).setOnClickListener(v -> {
            finish();
            overridePendingTransition(0, android.R.anim.fade_out);
        });

        etKey.setText(settings.apiKey);
        etBase.setText(settings.baseUrl);
        etModel.setText(settings.model);
        etSystem.setText(settings.systemPrompt);
        etMaxTokens.setText(String.valueOf(settings.maxTokens));
        sbTemp.setProgress(Math.round(settings.temperature * 10));
        int fs = settings.msgFontSp <= 0 ? 13 : settings.msgFontSp;
        sbFont.setProgress(Math.max(0, Math.min(11, fs - 12)));
        tvFont.setText(String.valueOf(fs));
        tvTemp.setText(String.format(java.util.Locale.US, "%.1f", settings.temperature));
        swStream.setChecked(settings.stream);

        bindAbout();
        bindAppearanceGroup();
        bindDataGroup();
        bindPermissionGroup();

        sbTemp.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvTemp.setText(String.format(java.util.Locale.US, "%.1f", progress / 10f));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        sbFont.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvFont.setText(String.valueOf(12 + progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        tvToggleKey.setOnClickListener(v -> {
            keyVisible = !keyVisible;
            etKey.setInputType(keyVisible
                    ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            etKey.setSelection(etKey.getText().length());
            tvToggleKey.setText(keyVisible ? "隐藏" : "显示");
        });

        findViewById(R.id.btnSave).setOnClickListener(v -> {
            if (collectAndSave()) {
                Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            }
        });

        btnTest.setOnClickListener(v -> testConnection());

        tvPickModel.setOnClickListener(v -> showProviderDialog());

        // Key 或地址变更后，已拉取的列表失效
        android.text.TextWatcher invalidate = new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                if (!cachedModels.isEmpty()) {
                    cachedModels.clear();
                    tvModelHint.setText("Key 或地址已改，请重新拉取");
                }
            }
        };
        etKey.addTextChangedListener(invalidate);
        etBase.addTextChangedListener(invalidate);
    }

    /** 关于区块：版本 / 当前模型 / 进入关于页 */
    private void bindAbout() {
        TextView tvVer = findViewById(R.id.tvAboutVer);
        TextView tvModel = findViewById(R.id.tvAboutModel);

        tvVer.setText(versionLine());

        String m = settings.model == null ? "" : settings.model.trim();
        tvModel.setText("当前模型：" + (m.isEmpty() ? "未设置" : m));

        findViewById(R.id.btnAboutPage).setOnClickListener(v ->
                startActivity(new Intent(this, AboutActivity.class)));
    }

    /** "星云 AI  v1.8.26  (build 150)" 这样的一行 */
    static String versionLine(android.content.Context ctx) {
        String ver = "";
        int vc = 0;
        try {
            android.content.pm.PackageInfo pi =
                    ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            ver = pi.versionName == null ? "" : pi.versionName.trim();
            vc = Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Throwable ignored) {
        }
        return "星云 AI  v" + ver + "  (build " + vc + ")";
    }

    private String versionLine() {
        return versionLine(this);
    }

    // ==================== 外观分组 ====================

    private static final int REQ_PICK_BG = 0x9B01;

    private interface IntCallback {
        void onValue(int v);
    }

    private void bindAppearanceGroup() {
        LinearLayout card = newCard("外观");

        // —— 主题 ——
        label(card, "主题", 12);
        LinearLayout themeRow = new LinearLayout(this);
        themeRow.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(themeRow, lpTop(dp(8)));
        for (int i = 0; i < Palette.THEME_NAMES.length; i++) {
            final int idx = i;
            TextView chip = chip(Palette.THEME_NAMES[i], settings.theme == i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(36), 1f);
            if (i == 0) lp.rightMargin = dp(8);
            themeRow.addView(chip, lp);
            chip.setOnClickListener(v -> {
                if (settings.theme == idx) return;
                persistEditsQuietly();
                settings.theme = idx;
                settings.save(this);
                recreate();
            });
        }

        // —— 强调色 ——
        label(card, "强调色", 16);
        LinearLayout accentRow = new LinearLayout(this);
        accentRow.setOrientation(LinearLayout.HORIZONTAL);
        accentRow.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(accentRow, lpTop(dp(10)));
        for (int i = 0; i < Palette.ACCENT_COLORS.length; i++) {
            final int ai = i;
            View dot = new View(this);
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL);
            gd.setColor(Palette.ACCENT_COLORS[i]);
            if (settings.accent == i) gd.setStroke(dp(2), 0xFFFFFFFF);
            dot.setBackground(gd);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(26), dp(26));
            lp.rightMargin = dp(12);
            accentRow.addView(dot, lp);
            dot.setOnClickListener(v -> {
                if (settings.accent == ai) return;
                persistEditsQuietly();
                settings.accent = ai;
                settings.save(this);
                recreate();
            });
        }

        // —— 聊天背景图 ——
        label(card, "聊天背景图", 17);
        final TextView tvBgState = new TextView(this);
        tvBgState.setTextColor(Palette.dim(this));
        tvBgState.setTextSize(11.5f);
        tvBgState.setText(bgStateText());
        card.addView(tvBgState, lpTop(dp(6)));

        LinearLayout bgBtns = new LinearLayout(this);
        bgBtns.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(bgBtns, lpTop(dp(10)));

        TextView btnPick = miniBtn("选择图片", true);
        TextView btnClearBg = miniBtn("清除背景", false);
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, dp(40), 1f);
        b1.rightMargin = dp(8);
        bgBtns.addView(btnPick, b1);
        bgBtns.addView(btnClearBg, new LinearLayout.LayoutParams(0, dp(40), 1f));

        btnPick.setOnClickListener(v -> pickBackground());
        btnClearBg.setOnClickListener(v -> {
            if (settings.chatBg == null || settings.chatBg.trim().isEmpty()) {
                Toast.makeText(this, "当前没有设置背景图", Toast.LENGTH_SHORT).show();
                return;
            }
            ChatBackground.clear(this);
            settings.chatBg = "";
            settings.save(this);
            tvBgState.setText(bgStateText());
            Toast.makeText(this, "已清除背景图", Toast.LENGTH_SHORT).show();
        });

        seekLine(card, "背景不透明度", settings.chatBgAlpha, v -> {
            settings.chatBgAlpha = v;
            settings.save(this);
            tvBgState.setText(bgStateText());
        });
        seekLine(card, "背景压暗（越高文字越清晰）", settings.chatBgDim, v -> {
            settings.chatBgDim = v;
            settings.save(this);
            tvBgState.setText(bgStateText());
        });

        insertBeforeSaveButton(card);
    }

    /** 重建界面之前，把输入框里已经填好的内容落盘，避免主题切换丢失编辑 */
    private void persistEditsQuietly() {
        try {
            String k = etKey.getText().toString().trim();
            if (!k.isEmpty()) settings.apiKey = k;
            String b = etBase.getText().toString().trim();
            if (!b.isEmpty()) settings.baseUrl = b;
            String m = etModel.getText().toString().trim();
            if (!m.isEmpty()) settings.model = m;
            settings.systemPrompt = etSystem.getText().toString();
            try {
                int mt = Integer.parseInt(etMaxTokens.getText().toString().trim());
                if (mt > 0) settings.maxTokens = mt;
            } catch (Throwable ignored) {
            }
            settings.temperature = sbTemp.getProgress() / 10f;
            settings.msgFontSp = 12 + sbFont.getProgress();
            settings.stream = swStream.isChecked();
            settings.save(this);
        } catch (Throwable ignored) {
        }
    }

    private String bgStateText() {
        if (settings.chatBg == null || settings.chatBg.trim().isEmpty()
                || !ChatBackground.exists(this, settings.chatBg)) {
            return "未设置 · 使用纯色背景";
        }
        return "已设置 · 不透明度 " + settings.chatBgAlpha + "% · 压暗 " + settings.chatBgDim + "%";
    }

    private void pickBackground() {
        Intent it = new Intent(Intent.ACTION_GET_CONTENT);
        it.setType("image/*");
        it.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(it, "选择聊天背景图"), REQ_PICK_BG);
        } catch (Throwable t) {
            Toast.makeText(this, "没有可用的图片选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_BG || resultCode != RESULT_OK || data == null
                || data.getData() == null) {
            return;
        }
        try {
            ChatBackground.clear(this);
            String name = ChatBackground.importFrom(this, data.getData());
            settings.chatBg = name;
            settings.save(this);
            Toast.makeText(this, "背景图已应用，回到聊天页即可看到", Toast.LENGTH_SHORT).show();
            recreate();
        } catch (Throwable t) {
            Toast.makeText(this, "设置背景图失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ==================== 数据与存储分组 ====================

    private void bindDataGroup() {
        LinearLayout card = newCard("数据与存储");

        row(card, "清理缓存", cacheSummary(), v -> confirmClearCache());
        final TextView[] holder = new TextView[1];
        holder[0] = row(card, "会话记录", convSummary(), v -> confirmClearConv(holder[0]));

        insertBeforeSaveButton(card);
    }

    private String cacheSummary() {
        long n = cacheSize();
        return n <= 0 ? "临时文件已清空" : "临时文件 " + human(n) + " · 点一下清理";
    }

    private String convSummary() {
        int n = ChatStore.get(this).list().size();
        return n <= 0 ? "还没有会话记录" : n + " 个会话（仅存本机）· 点一下清空";
    }

    private void confirmClearCache() {
        final long size = cacheSize();
        if (size <= 0) {
            Toast.makeText(this, "缓存已经是空的", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("清理缓存")
                .setMessage("将清掉 " + human(size) + " 的临时文件（不会删除会话、背景图和已导出的产物）。继续？")
                .setNegativeButton("取消", null)
                .setPositiveButton("清理", (d, w) -> {
                    clearCacheDirs();
                    Toast.makeText(this, "已清理 " + human(size), Toast.LENGTH_SHORT).show();
                    recreate();
                })
                .show();
    }

    private void confirmClearConv(TextView tvSub) {
        final ChatStore store = ChatStore.get(this);
        final int n = store.list().size();
        if (n == 0) {
            Toast.makeText(this, "还没有会话记录", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("清空全部会话")
                .setMessage("将删除全部 " + n + " 个会话记录，删除后无法恢复。确定继续？")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (d, w) -> {
                    store.deleteAll();
                    if (tvSub != null) tvSub.setText(convSummary());
                    Toast.makeText(this, "已清空全部会话", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    /** 缓存/临时目录总大小（不含会话、背景图与导出产物） */
    private long cacheSize() {
        long n = dirSize(getCacheDir());
        n += dirSize(getCodeCacheDir());
        try {
            n += dirSize(getExternalCacheDir());
        } catch (Throwable ignored) {
        }
        n += dirSize(new java.io.File(getFilesDir(), "tmp"));
        n += dirSize(new java.io.File(getFilesDir(), "build"));
        return n;
    }

    private void clearCacheDirs() {
        wipe(getCacheDir());
        wipe(getCodeCacheDir());
        try {
            wipe(getExternalCacheDir());
        } catch (Throwable ignored) {
        }
        wipe(new java.io.File(getFilesDir(), "tmp"));
        wipe(new java.io.File(getFilesDir(), "build"));
    }

    private static void wipe(java.io.File f) {
        if (f == null || !f.exists()) return;
        java.io.File[] fs = f.listFiles();
        if (fs == null) return;
        for (java.io.File c : fs) {
            if (c.isDirectory()) wipe(c);
            c.delete();
        }
    }

    private static long dirSize(java.io.File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        java.io.File[] fs = f.listFiles();
        if (fs == null) return 0;
        long n = 0;
        for (java.io.File c : fs) n += dirSize(c);
        return n;
    }

    private static String human(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024L * 1024) return String.format(java.util.Locale.US, "%.1f KB", b / 1024f);
        if (b < 1024L * 1024 * 1024) return String.format(java.util.Locale.US, "%.1f MB", b / 1048576f);
        return String.format(java.util.Locale.US, "%.2f GB", b / 1073741824f);
    }

    // ==================== 权限与后台分组 ====================

    private void bindPermissionGroup() {
        LinearLayout card = newCard("权限与后台");

        row(card, "应用信息", "通知、权限、存储占用等系统设置", v -> openAppDetails());
        row(card, "电池优化", "设为「不限制」，后台长任务才不会被系统掐断", v -> openBattery());
        row(card, "通知设置", "长任务完成的提醒需要通知权限", v -> openNotifications());
        row(card, "自启动 / 后台运行", "部分系统需在「手机管家」里额外放行", v -> openAppDetails());

        TextView tip = new TextView(this);
        tip.setText("提示：选择聊天背景图走系统选择器，不需要存储权限；只有把产物导出到公共目录时才会请求授权。");
        tip.setTextColor(Palette.dim(this));
        tip.setTextSize(11f);
        tip.setLineSpacing(dp(2), 1f);
        tip.setPadding(0, dp(10), 0, 0);
        card.addView(tip);

        insertBeforeSaveButton(card);
    }

    private void openAppDetails() {
        Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        i.setData(Uri.parse("package:" + getPackageName()));
        safeStart(i, "无法打开应用信息页");
    }

    private void openBattery() {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            safeStart(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                    "无法打开电池优化设置");
        }
    }

    private void openNotifications() {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(i);
        } catch (Throwable t) {
            openAppDetails();
        }
    }

    private void safeStart(Intent i, String err) {
        try {
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, err, Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== 通用 UI 工具 ====================

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams lpTop(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    /** 新建一张分组卡片（surface + 圆角 + 描边），返回容器 */
    private LinearLayout newCard(String title) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        card.setLayoutParams(lp);
        card.setBackgroundResource(R.drawable.card_bg);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Palette.accent(this));
        t.setTextSize(13f);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(t);
        return card;
    }

    /** 新分组排在「关于」卡片之前，保持 模型 → 外观 → 数据 → 权限 → 关于 → 保存 的顺序 */
    private void insertBeforeSaveButton(View card) {
        LinearLayout content = findViewById(R.id.contentList);
        if (content == null) return;
        View anchor = findViewById(R.id.cardAbout);
        if (anchor == null) anchor = findViewById(R.id.btnSave);
        if (anchor != null && anchor.getParent() == content) {
            content.addView(card, content.indexOfChild(anchor));
        } else {
            content.addView(card);
        }
    }

    private void label(LinearLayout card, String text, int topDp) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Palette.dim(this));
        t.setTextSize(11.5f);
        card.addView(t, lpTop(dp(topDp)));
    }

    /** 一行设置项：标题 + 副标题 + 右侧箭头，返回副标题控件便于刷新 */
    private TextView row(LinearLayout card, String title, String sub, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(11), 0, dp(11));
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                && tv.resourceId != 0) {
            r.setBackgroundResource(tv.resourceId);
        }
        r.setOnClickListener(click);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        r.addView(texts, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView t1 = new TextView(this);
        t1.setText(title);
        t1.setTextColor(Palette.text(this));
        t1.setTextSize(13.5f);
        texts.addView(t1);

        TextView t2 = new TextView(this);
        t2.setText(sub);
        t2.setTextColor(Palette.dim(this));
        t2.setTextSize(11f);
        t2.setPadding(0, dp(3), 0, 0);
        texts.addView(t2);

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextColor(Palette.dim(this));
        arrow.setTextSize(18f);
        arrow.setPadding(dp(8), 0, 0, 0);
        r.addView(arrow);

        card.addView(r, lpTop(dp(4)));
        return t2;
    }

    private TextView chip(String text, boolean selected) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(12.5f);
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(10));
        if (selected) {
            gd.setColor(Palette.accentSoft(this, 0x33));
            gd.setStroke(dp(2), Palette.accent(this));
            t.setTextColor(Palette.accent(this));
            t.setTypeface(null, android.graphics.Typeface.BOLD);
        } else {
            gd.setColor(0x00000000);
            gd.setStroke(dp(1), Palette.stroke(this));
            t.setTextColor(Palette.dim(this));
        }
        t.setBackground(gd);
        return t;
    }

    private TextView miniBtn(String text, boolean primary) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(13f);
        t.setBackgroundResource(primary ? R.drawable.btn_primary : R.drawable.btn_ghost);
        t.setTextColor(primary ? 0xFFFFFFFF : Palette.text(this));
        return t;
    }

    private SeekBar seekLine(LinearLayout card, String title, int value, IntCallback onStop) {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(head, lpTop(dp(12)));

        TextView tl = new TextView(this);
        tl.setText(title);
        tl.setTextColor(Palette.dim(this));
        tl.setTextSize(11.5f);
        head.addView(tl, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final TextView tvVal = new TextView(this);
        tvVal.setTextColor(Palette.text(this));
        tvVal.setTextSize(11.5f);
        tvVal.setText(value + "%");
        head.addView(tvVal);

        SeekBar sb = new SeekBar(this);
        sb.setMax(100);
        sb.setProgress(Math.max(0, Math.min(100, value)));
        sb.setPadding(0, 0, 0, 0);
        card.addView(sb, lpTop(dp(2)));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                tvVal.setText(p + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                onStop.onValue(s.getProgress());
            }
        });
        return sb;
    }

    @Override
    protected void onDestroy() {
        testClient.cancel();
        modelListClient.cancel();
        super.onDestroy();
    }

    /** 拉取该账号可用的模型列表（GET {baseUrl}/models） */
    private void fetchModels() {
        String key = etKey.getText().toString().trim();
        String base = etBase.getText().toString().trim();

        if (TextUtils.isEmpty(key)) {
            Toast.makeText(this, "请先填写上方 API Key，再拉取模型列表", Toast.LENGTH_SHORT).show();
            etKey.requestFocus();
            return;
        }
        if (TextUtils.isEmpty(base)) {
            base = Settings.DEFAULT_BASE_URL;
            etBase.setText(base);
        }
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            Toast.makeText(this, "Base URL 需要以 http:// 或 https:// 开头", Toast.LENGTH_SHORT).show();
            return;
        }

        Settings probe = Settings.load(this);
        probe.apiKey = key;
        probe.baseUrl = base;

        final String oldLabel = tvPickModel.getText().toString();
        tvPickModel.setEnabled(false);
        tvPickModel.setText("拉取中…");
        tvModelHint.setText("正在请求模型列表…");

        modelListClient.fetch(probe, new ModelListClient.Callback() {
            @Override
            public void onSuccess(List<String> models) {
                if (isFinishing() || isDestroyed()) return;
                tvPickModel.setEnabled(true);
                tvPickModel.setText(oldLabel);
                cachedModels = models;
                tvModelHint.setText("共 " + models.size() + " 个可选");
                showModelDialog();
            }

            @Override
            public void onError(String message) {
                if (isFinishing() || isDestroyed()) return;
                tvPickModel.setEnabled(true);
                tvPickModel.setText(oldLabel);
                tvModelHint.setText("拉取失败，可手动输入");
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("拉取模型失败")
                        .setMessage(message + "\n\n请检查：\n· API Key 是否正确\n· 接口地址是否为 OpenAI 兼容格式（如 https://api.deepseek.com/v1）\n\n也可以直接在输入框手动填写模型名称。")
                        .setPositiveButton("知道了", null)
                        .show();
            }
        });
    }

    /**
     * 服务商预设：{名称, Base URL, 模型1, 模型2, ...}
     * 选服务商 → 选模型，地址自动填好，用户只需要填一次 Key。
     * 模型名只是常用项，可手改，也可用「从账号拉取」取该账号真实可用列表。
     */
    private static final String[][] PROVIDERS = {
            {"DeepSeek", "https://api.deepseek.com/v1",
                    "deepseek-chat", "deepseek-reasoner"},
            {"通义千问（阿里云）", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                    "qwen-plus", "qwen-max", "qwen-turbo", "qwen-long"},
            {"Kimi（月之暗面）", "https://api.moonshot.cn/v1",
                    "moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k", "kimi-latest"},
            {"智谱 GLM", "https://open.bigmodel.cn/api/paas/v4",
                    "glm-4-plus", "glm-4-air", "glm-4-flash"},
            {"OpenAI", "https://api.openai.com/v1",
                    "gpt-4o", "gpt-4o-mini", "gpt-4.1", "gpt-4.1-mini"},
            {"硅基流动 SiliconFlow", "https://api.siliconflow.cn/v1",
                    "deepseek-ai/DeepSeek-V3", "deepseek-ai/DeepSeek-R1",
                    "Qwen/Qwen2.5-72B-Instruct"}
    };

    /** 一级：选服务商（末项为从账号拉取真实列表） */
    private void showProviderDialog() {
        final String[] items = new String[PROVIDERS.length + 1];
        for (int i = 0; i < PROVIDERS.length; i++) {
            items[i] = PROVIDERS[i][0] + "  ·  " + hostOf(PROVIDERS[i][1]);
        }
        items[PROVIDERS.length] = "从账号拉取模型列表（需先填 Key）";

        new AlertDialog.Builder(this)
                .setTitle("选择服务商")
                .setItems(items, (dialog, which) -> {
                    if (which == PROVIDERS.length) {
                        fetchModels();
                        return;
                    }
                    showProviderModels(PROVIDERS[which]);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 二级：选该服务商的模型，选中即把地址和模型一起填好 */
    private void showProviderModels(final String[] p) {
        final String base = p[1];
        final String[] models = new String[p.length - 2];
        System.arraycopy(p, 2, models, 0, models.length);

        String cur = etModel.getText().toString().trim();
        int checked = -1;
        for (int i = 0; i < models.length; i++) {
            if (models[i].equals(cur)) {
                checked = i;
                break;
            }
        }

        new AlertDialog.Builder(this)
                .setTitle(p[0] + " · 选择模型")
                .setSingleChoiceItems(models, checked, (dialog, which) -> {
                    etBase.setText(base);          // 地址自动填
                    etModel.setText(models[which]); // 模型自动填
                    if (TextUtils.isEmpty(etKey.getText().toString().trim())) {
                        tvModelHint.setText("地址和模型已填好，只差上面的 Key");
                        etKey.requestFocus();
                    } else {
                        tvModelHint.setText("已选好，点「保存设置」生效");
                    }
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static String hostOf(String url) {
        try {
            String h = android.net.Uri.parse(url).getHost();
            return h == null ? url : h;
        } catch (Exception e) {
            return url;
        }
    }

    /** 弹出模型单选列表 */
    private void showModelDialog() {
        if (cachedModels.isEmpty()) {
            fetchModels();
            return;
        }
        final String[] items = cachedModels.toArray(new String[0]);
        String cur = etModel.getText().toString().trim();
        int checked = cachedModels.indexOf(cur);

        new AlertDialog.Builder(this)
                .setTitle("选择模型")
                .setSingleChoiceItems(items, checked, (dialog, which) -> {
                    etModel.setText(items[which]);
                    tvModelHint.setText("已选好，点「保存设置」生效");
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private boolean collectAndSave() {
        String key = etKey.getText().toString().trim();
        String base = etBase.getText().toString().trim();
        String model = etModel.getText().toString().trim();

        if (TextUtils.isEmpty(key)) {
            Toast.makeText(this, "请填写 API Key", Toast.LENGTH_SHORT).show();
            etKey.requestFocus();
            return false;
        }
        if (TextUtils.isEmpty(base)) {
            base = Settings.DEFAULT_BASE_URL;
            etBase.setText(base);
        }
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            Toast.makeText(this, "Base URL 需要以 http:// 或 https:// 开头", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (TextUtils.isEmpty(model)) {
            model = Settings.DEFAULT_MODEL;
            etModel.setText(model);
        }

        int maxTokens = 4096;
        try {
            maxTokens = Integer.parseInt(etMaxTokens.getText().toString().trim());
        } catch (Exception ignored) {
        }
        if (maxTokens < 64) maxTokens = 64;
        if (maxTokens > 32768) maxTokens = 32768;

        settings.apiKey = key;
        settings.baseUrl = base;
        settings.model = model;
        settings.systemPrompt = etSystem.getText().toString();
        settings.temperature = sbTemp.getProgress() / 10f;
        settings.maxTokens = maxTokens;
        settings.stream = swStream.isChecked();
        settings.msgFontSp = 12 + sbFont.getProgress();
        settings.save(this);
        return true;
    }

    private void testConnection() {
        if (!collectAndSave()) return;

        btnTest.setEnabled(false);
        btnTest.setText("测试中…");

        Settings probe = Settings.load(this);
        probe.stream = false;
        probe.maxTokens = 64;

        List<Message> history = new ArrayList<>();
        history.add(new Message(Message.ROLE_USER, "你好"));

        testClient.send(probe, history, new ChatClient.Listener() {
            @Override
            public void onStart() {
            }

            @Override
            public void onDelta(String piece) {
            }

            @Override
            public void onDone(String full) {
                btnTest.setEnabled(true);
                btnTest.setText("测试连接");
                String preview = full == null ? "" : full.trim();
                if (preview.length() > 60) preview = preview.substring(0, 60) + "…";
                Toast.makeText(SettingsActivity.this,
                        "连接成功 ✓  模型回复：" + (preview.isEmpty() ? "（空）" : preview),
                        Toast.LENGTH_LONG).show();
            }

            @Override
            public void onError(String message) {
                btnTest.setEnabled(true);
                btnTest.setText("测试连接");
                Toast.makeText(SettingsActivity.this, "连接失败：" + message, Toast.LENGTH_LONG).show();
            }
        });
    }
}
