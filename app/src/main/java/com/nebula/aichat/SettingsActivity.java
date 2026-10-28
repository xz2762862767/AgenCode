package com.nebula.aichat;

import android.content.Intent;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
    private TextView tvBatteryState;
    private SwitchCompat swStream;
    /** 后台常驻通知开关（权限与后台分组） */
    private SwitchCompat swAlive;
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
        // 一键回填内置提示词（用户改乱了可以立刻回到出厂人设）
        findViewById(R.id.tvResetSystem).setOnClickListener(v ->
                new android.app.AlertDialog.Builder(this)
                        .setTitle("恢复内置系统提示词")
                        .setMessage("输入框内容会替换为「AgenCode」的内置提示词，你当前的修改将被覆盖。\n"
                                + "点保存后生效。")
                        .setPositiveButton("恢复", (d, w) -> {
                            etSystem.setText(Settings.DEFAULT_SYSTEM_PROMPT);
                            etSystem.setSelection(etSystem.getText().length());
                            Toast.makeText(this, "已填入内置提示词，点保存生效",
                                    Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("取消", null)
                        .show());
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

    /** "AgenCode  v1.8.26  (build 150)" 这样的一行 */
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
        return "AgenCode  v" + ver + "  (build " + vc + ")";
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
            View chip = themeChip(Palette.THEME_NAMES[i], i, settings.theme == i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(36), 1f);
            if (i < Palette.THEME_NAMES.length - 1) lp.rightMargin = dp(8);
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
            String sysTxt = etSystem.getText().toString().trim();
        settings.systemPrompt = sysTxt.isEmpty() ? Settings.DEFAULT_SYSTEM_PROMPT : sysTxt;
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
        // 状态直接写在副标题上：用户最需要知道的是"到底生效了没"，而不是"点进去看看"
        tvBatteryState = row(card, "电池优化", batterySubText(), v -> openBattery());
        row(card, "通知设置", "长任务完成的提醒需要通知权限", v -> openNotifications());

        // 常驻通知：开着才能在状态栏确认"后台还在跑"，同时也算一层轻度保活
        swAlive = switchRow(card, "后台常驻通知",
                settings.aliveNotify ? "状态栏常驻「后台运行中」，可减少被系统回收"
                        : "已关闭 · 只在长任务进行时临时出现",
                settings.aliveNotify, this::applyAliveSwitch);

        row(card, "自启动 / 后台运行", "部分系统需在「手机管家」里额外放行", v -> openAppDetails());
        row(card, "运行诊断", "闪退、卡顿、任务中断时采集现场，可一键复制",
                v -> startActivity(new Intent(this, DiagActivity.class)));

        TextView tip = new TextView(this);
        tip.setText("提示：选择聊天背景图走系统选择器，不需要存储权限；只有把产物导出到公共目录时才会请求授权。");
        tip.setTextColor(Palette.dim(this));
        tip.setTextSize(11f);
        tip.setLineSpacing(dp(2), 1f);
        tip.setPadding(0, dp(10), 0, 0);
        card.addView(tip);

        insertBeforeSaveButton(card);
    }

    /**
     * 常驻开关立即生效，不用等"保存"：切了就该在状态栏看到（或消失）。
     * 通知权限没开时会静默失败（服务在跑，通知被系统丢掉），所以这里补一句提示。
     */
    private void applyAliveSwitch() {
        if (swAlive == null) return;
        settings.aliveNotify = swAlive.isChecked();
        settings.save(this);
        try {
            if (settings.aliveNotify) KeepAliveService.startIdle(this);
            else KeepAliveService.stop(this);
        } catch (Throwable ignored) {
        }
        if (settings.aliveNotify && !notifEnabled()) {
            Toast.makeText(this, "通知权限没开：服务照跑，但状态栏不会显示。点上面「通知设置」去开",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** 通知总开关（含 Android 13+ 的运行时权限结果） */
    private boolean notifEnabled() {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            return nm == null || nm.areNotificationsEnabled();
        } catch (Throwable t) {
            return true;
        }
    }

    private void openAppDetails() {
        Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        i.setData(Uri.parse("package:" + getPackageName()));
        safeStart(i, "无法打开应用信息页");
    }

    // ---------------- 电池优化：逐级降级，保证一定有反馈 ----------------

    /**
     * 为什么不能只 startActivity 一次：
     * ColorOS / OriginOS 这类 ROM 会把 ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
     * 的落地页吃掉一部分，导致 startActivity 不抛异常但页面秒退回——
     * 用户看到的就是"点了没反应"。所以这里每一级都先确认真的有人接，
     * 接不上就往下试，最后一定给一句 toast，绝不静默返回。
     */
    private void openBattery() {
        if (batteryUnrestricted()) {
            Toast.makeText(this, "当前已设为「不限制」，后台长任务不会被掐断",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        // 1. 标准系统对话框（让用户二选一）
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
            return;
        } catch (Throwable ignored) {
        }

        // 2. OPPO / OnePlus 自家电池管理页（ColorOS 上经常只剩这条路）
        String[][] roms = {
                {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
                {"com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"},
                {"com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerConsumptionActivity"},
                {"com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"},
        };
        for (String[] c : roms) {
            android.content.ComponentName cn = new android.content.ComponentName(c[0], c[1]);
            try {
                if (getPackageManager().resolveActivity(new Intent().setComponent(cn), 0) == null) {
                    continue;
                }
                startActivity(new Intent().setComponent(cn));
                return;
            } catch (Throwable ignored) {
            }
        }

        // 3. 系统电池优化列表页
        if (launchIfResolvable(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) {
            return;
        }

        // 4. 兜底：应用详情页，至少让用户能自己找
        if (launchIfResolvable(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())))) {
            Toast.makeText(this, "本机没有直达入口，请在「耗电管理 / 应用管理」里手动选择「不限制」",
                    Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(this, "本机无法打开电池设置，请到「手机管家 → 应用管理 → 耗电管理」手动放行",
                Toast.LENGTH_LONG).show();
    }

    private boolean launchIfResolvable(Intent i) {
        try {
            if (getPackageManager().resolveActivity(i, 0) == null) return false;
            startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean batteryUnrestricted() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    private String batterySubText() {
        return batteryUnrestricted()
                ? "当前：已设为「不限制」✓ 后台长任务不会被掐断"
                : "当前：受系统限制 · 点此放行，否则长任务可能被中断";
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从系统设置返回后立刻刷新，用户能直接看到是否生效
        if (tvBatteryState != null) tvBatteryState.setText(batterySubText());
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

    /**
     * 带开关的行：右侧一个 SwitchCompat，点整行也能切。
     * 用 Runnable 而不是监听器接口，省一个 import，调用处也短。
     */
    private SwitchCompat switchRow(LinearLayout card, String title, String sub, boolean checked,
                                   final Runnable onChange) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(11), 0, dp(11));
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                && tv.resourceId != 0) {
            r.setBackgroundResource(tv.resourceId);
        }

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
        t2.setPadding(0, dp(3), dp(8), 0);
        texts.addView(t2);

        final SwitchCompat sw = new SwitchCompat(this);
        sw.setChecked(checked);
        sw.setPadding(0, 0, 0, 0);
        sw.setOnCheckedChangeListener((b, isChecked) -> {
            if (onChange != null) onChange.run();
        });
        r.addView(sw);
        r.setOnClickListener(v -> sw.toggle());

        card.addView(r, lpTop(dp(4)));
        return sw;
    }


    /** 主题选项：左侧一块真实底色的预览点，「默认白」一眼能认出来 */
    private View themeChip(String text, int theme, boolean selected) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(6), 0, dp(6), 0);

        View dot = new View(this);
        GradientDrawable dg = new GradientDrawable();
        dg.setShape(GradientDrawable.OVAL);
        dg.setColor(themeBg(theme));
        dg.setStroke(dp(1), Palette.stroke(this));
        dot.setBackground(dg);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(9), dp(9));
        dlp.rightMargin = dp(5);
        box.addView(dot, dlp);

        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12.5f);
        t.setSingleLine(true);
        box.addView(t);

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
        box.setBackground(gd);
        return box;
    }

    private int themeBg(int theme) {
        if (theme == Palette.THEME_LIGHT) return 0xFFF4F6FA;
        if (theme == Palette.THEME_BLACK) return 0xFF000000;
        return 0xFF0F1218;
    }

    private TextView chip(String text, boolean selected) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(12.5f);
        t.setSingleLine(true);
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(10));
        if (selected) {
            gd.setColor(Palette.accentSoft(this, 0x33));
            gd.setStroke(dp(2), Palette.accent(this));
            t.setTextColor(Palette.accent(this));
            t.setTypeface(null, android.graphics.Typeface.BOLD);
        } else {
            gd.setColor(Palette.c(0x00000000));
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

    /** 各家服务商的品牌色，用于列表左侧的字母徽标 */
    private static final int[] PROVIDER_COLORS = {
            0xFF4D6BFE, // DeepSeek
            0xFF6F5CF1, // 通义千问
            0xFF8B5CF6, // Kimi
            0xFF2E7BE6, // 智谱 GLM
            0xFF10A37F, // OpenAI
            0xFF3AA6BD  // SiliconFlow
    };

    /** 一级：选服务商（末项为从账号拉取真实列表） */
    private void showProviderDialog() {
        final Dialog d = new Dialog(this);
        LinearLayout root = sheetRoot(d);
        sheetHeader(root, "选择服务商", "内置 " + PROVIDERS.length + " 家 · 选完自动填好地址和模型");

        final String curHost = hostOf(etBase.getText().toString().trim());

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        final int rowH = dp(54);
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.44f);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.min(rowH * (PROVIDERS.length + 1) + dp(3) * PROVIDERS.length, maxH));
        svLp.topMargin = dp(12);
        root.addView(sv, svLp);

        for (int i = 0; i < PROVIDERS.length; i++) {
            final String[] p = PROVIDERS[i];
            String host = hostOf(p[1]);
            boolean active = host.equals(curHost);
            View row = providerRow(String.valueOf(p[0].charAt(0)), p[0], host,
                    PROVIDER_COLORS[i % PROVIDER_COLORS.length], active ? 1 : 0, v -> {
                        d.dismiss();
                        showProviderModels(p);
                    });
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, rowH);
            if (i > 0) rlp.topMargin = dp(3);
            list.addView(row, rlp);
        }

        // 从账号拉取：与上面同构，徽标换成下载箭头，走中性色
        View fetchRow = providerRow("\u21e3", "从账号拉取模型列表", "需先在下方填好 API Key",
                0xFF3E6E8C, 2, v -> {
                    d.dismiss();
                    fetchModels();
                });
        LinearLayout.LayoutParams frlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, rowH);
        frlp.topMargin = dp(3);
        list.addView(fetchRow, frlp);

        root.addView(sheetLine(14));

        TextView cancel = sheetBtn("取消", false);
        cancel.setOnClickListener(v -> d.dismiss());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        clp.topMargin = dp(13);
        root.addView(cancel, clp);

        showSheetWindow(d);
    }

    // ---------------- 弹窗外观：与主界面模型弹窗保持一致 ----------------

    /** 深色圆角底板，底色 / 描边 / 内边距统一收在这里 */
    private LinearLayout sheetRoot(Dialog d) {
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(20));
        gd.setColor(Palette.c(0xFF141A23));
        gd.setStroke(Math.max(1, dp(1)), 0xFF2A3240);
        root.setBackground(gd);
        root.setPadding(dp(18), dp(18), dp(18), dp(14));
        d.setContentView(root);
        return root;
    }

    private void showSheetWindow(Dialog d) {
        d.show();
        Window w = d.getWindow();
        if (w == null) return;
        w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.88f),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void sheetHeader(LinearLayout root, String title, String sub) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Palette.c(0xFFEDF2FA));
        root.addView(t);
        if (!TextUtils.isEmpty(sub)) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            s.setTextColor(Palette.c(0xFF7F8C9F));
            s.setPadding(0, dp(5), 0, 0);
            root.addView(s);
        }
    }

    /** 分隔线：中段微亮、两端透明，比通栏实线轻 */
    private View sheetLine(int topDp) {
        View v = new View(this);
        GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x00FFFFFF, 0x26FFFFFF, 0x00FFFFFF});
        v.setBackground(gd);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        lp.topMargin = dp(topDp);
        v.setLayoutParams(lp);
        return v;
    }

    private GradientDrawable pill(int fill, int stroke, float radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(radiusDp));
        gd.setColor(fill);
        if (stroke != 0) gd.setStroke(Math.max(1, dp(1)), stroke);
        return gd;
    }

    /**
     * 服务商行：字母徽标 + 名称 + 域名 + 右侧状态。
     *
     * @param tail 0=箭头（可进入） 1=使用中 2=无
     */
    private View providerRow(String badge, String title, String sub, int color, int tail,
                             final View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(9), 0, dp(9), 0);
        row.setClickable(true);
        row.setFocusable(true);

        boolean active = tail == 1;
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x1FFFFFFF),
                pill(active ? 0x1A63C8FF : 0x08FFFFFF,
                        active ? 0x6663C8FF : 0x14FFFFFF, 12f), null));

        TextView b = new TextView(this);
        b.setText(badge);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(Palette.c(0xFFFFFFFF));
        b.setGravity(Gravity.CENTER);
        b.setBackground(pill(color, 0, 10f));
        row.addView(b, new LinearLayout.LayoutParams(dp(34), dp(34)));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams midLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        midLp.leftMargin = dp(11);

        TextView n = new TextView(this);
        n.setText(title);
        n.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        n.setTextColor(active ? 0xFFBEE3FF : 0xFFE7ECF4);
        n.setSingleLine(true);
        n.setEllipsize(TextUtils.TruncateAt.END);
        mid.addView(n);

        if (!TextUtils.isEmpty(sub)) {
            TextView h = new TextView(this);
            h.setText(sub);
            h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            h.setTextColor(Palette.c(0xFF7C8A9C));
            h.setSingleLine(true);
            h.setEllipsize(TextUtils.TruncateAt.END);
            h.setPadding(0, dp(3), 0, 0);
            mid.addView(h);
        }
        row.addView(mid, midLp);

        if (tail == 1) {
            TextView t = new TextView(this);
            t.setText("使用中");
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
            t.setTextColor(Palette.c(0xFF63C8FF));
            t.setBackground(pill(0x1A63C8FF, 0x5563C8FF, 8f));
            t.setPadding(dp(7), dp(3), dp(7), dp(3));
            row.addView(t);
        } else if (tail == 0) {
            TextView t = new TextView(this);
            t.setText("\u203A");
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
            t.setTextColor(Palette.c(0xFF66768C));
            t.setPadding(dp(6), 0, dp(2), 0);
            row.addView(t);
        }

        row.setOnClickListener(click);
        return row;
    }

    /** 模型行：左侧品牌色圆点 + 名称 + 右侧「当前」标记 */
    private View modelRow(String name, int color, boolean current, final View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), 0, dp(12), 0);
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x1FFFFFFF),
                pill(current ? 0x1A63C8FF : 0x06FFFFFF,
                        current ? 0x6663C8FF : 0x12FFFFFF, 12f), null));

        View dot = new View(this);
        dot.setBackground(pill(color, 0, 4f));
        row.addView(dot, new LinearLayout.LayoutParams(dp(8), dp(8)));

        TextView n = new TextView(this);
        n.setText(name);
        n.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        n.setTextColor(current ? 0xFFBEE3FF : 0xFFE7ECF4);
        n.setSingleLine(true);
        n.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = dp(11);
        row.addView(n, nlp);

        if (current) {
            TextView t = new TextView(this);
            t.setText("当前");
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
            t.setTextColor(Palette.c(0xFF63C8FF));
            t.setBackground(pill(0x1A63C8FF, 0x5563C8FF, 8f));
            t.setPadding(dp(7), dp(3), dp(7), dp(3));
            row.addView(t);
        }

        row.setOnClickListener(click);
        return row;
    }

    /** 弹窗底部按钮：primary=淡蓝描边，否则中性灰 */
    private TextView sheetBtn(String text, boolean primary) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setClickable(true);
        tv.setFocusable(true);
        if (primary) {
            tv.setTextColor(Palette.c(0xFFC6D8FA));
            tv.setBackground(new RippleDrawable(ColorStateList.valueOf(0x3D4A86F7),
                    pill(0x1F4A86F7, 0x6B4A86F7, 14f), null));
        } else {
            tv.setTextColor(Palette.c(0xFF8FA0B5));
            tv.setBackground(new RippleDrawable(ColorStateList.valueOf(0x1FFFFFFF),
                    pill(0x0AFFFFFF, 0x1FFFFFFF, 14f), null));
        }
        return tv;
    }

    /** 二级：选该服务商的模型，选中即把地址和模型一起填好 */
    private void showProviderModels(final String[] p) {
        final String base = p[1];
        final String[] models = new String[p.length - 2];
        System.arraycopy(p, 2, models, 0, models.length);

        final String curHost = hostOf(etBase.getText().toString().trim());
        final String curModel = etModel.getText().toString().trim();
        final boolean sameBase = hostOf(base).equals(curHost);

        int color = 0xFF4D6BFE;
        for (int i = 0; i < PROVIDERS.length; i++) {
            if (PROVIDERS[i] == p) {
                color = PROVIDER_COLORS[i % PROVIDER_COLORS.length];
                break;
            }
        }
        final int brandColor = color;

        final Dialog d = new Dialog(this);
        LinearLayout root = sheetRoot(d);
        sheetHeader(root, p[0] + " · 选择模型", "内置候选 · 选完自动填好地址 " + hostOf(base));

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        final int rowH = dp(48);
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.46f);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.min(rowH * models.length + dp(3) * models.length, maxH));
        svLp.topMargin = dp(12);
        root.addView(sv, svLp);

        for (int i = 0; i < models.length; i++) {
            final String m = models[i];
            boolean current = sameBase && m.equals(curModel);
            View row = modelRow(m, brandColor, current, v -> {
                etBase.setText(base);   // 地址自动填
                etModel.setText(m);     // 模型自动填
                if (TextUtils.isEmpty(etKey.getText().toString().trim())) {
                    tvModelHint.setText("地址和模型已填好，只差上面的 Key");
                    etKey.requestFocus();
                } else {
                    tvModelHint.setText("已选好，点「保存设置」生效");
                }
                d.dismiss();
            });
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, rowH);
            if (i > 0) rlp.topMargin = dp(3);
            list.addView(row, rlp);
        }

        root.addView(sheetLine(14));

        TextView cancel = sheetBtn("取消", false);
        cancel.setOnClickListener(v -> d.dismiss());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        clp.topMargin = dp(13);
        root.addView(cancel, clp);

        showSheetWindow(d);
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
    /** 从账号拉取到的模型列表（数量不定，统一走滚动弹窗） */
    private void showModelDialog() {
        if (cachedModels.isEmpty()) {
            fetchModels();
            return;
        }
        final String[] items = cachedModels.toArray(new String[0]);
        final String cur = etModel.getText().toString().trim();

        // 品牌色按当前地址匹配，匹配不到就走中性青
        int color = 0xFF63C8FF;
        String host = hostOf(etBase.getText().toString().trim());
        for (int i = 0; i < PROVIDERS.length; i++) {
            if (hostOf(PROVIDERS[i][1]).equals(host)) {
                color = PROVIDER_COLORS[i % PROVIDER_COLORS.length];
                break;
            }
        }
        final int brandColor = color;

        final Dialog d = new Dialog(this);
        LinearLayout root = sheetRoot(d);
        sheetHeader(root, "选择模型", "从账号拉取到 " + items.length + " 个");

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        final int rowH = dp(48);
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.5f);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.min(rowH * items.length, maxH));
        svLp.topMargin = dp(12);
        root.addView(sv, svLp);

        for (int i = 0; i < items.length; i++) {
            final String m = items[i];
            View row = modelRow(m, brandColor, m.equals(cur), v -> {
                etModel.setText(m);
                tvModelHint.setText("已选好，点「保存设置」生效");
                d.dismiss();
            });
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, rowH);
            if (i > 0) rlp.topMargin = dp(3);
            list.addView(row, rlp);
        }

        root.addView(sheetLine(14));

        TextView cancel = sheetBtn("取消", false);
        cancel.setOnClickListener(v -> d.dismiss());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        clp.topMargin = dp(13);
        root.addView(cancel, clp);

        showSheetWindow(d);
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
        String sysTxt = etSystem.getText().toString().trim();
        settings.systemPrompt = sysTxt.isEmpty() ? Settings.DEFAULT_SYSTEM_PROMPT : sysTxt;
        settings.temperature = sbTemp.getProgress() / 10f;
        settings.maxTokens = maxTokens;
        settings.stream = swStream.isChecked();
        settings.msgFontSp = 12 + sbFont.getProgress();
        if (swAlive != null) settings.aliveNotify = swAlive.isChecked();
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
