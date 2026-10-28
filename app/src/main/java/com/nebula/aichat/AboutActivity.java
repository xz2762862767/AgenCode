package com.nebula.aichat;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.nebula.aichat.data.Changelog;
import com.nebula.aichat.ui.Palette;

/** 关于页：版本、运行环境、数据说明、开源致谢 */
public class AboutActivity extends AppCompatActivity {

    private String pkgInfo = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Palette.apply(this);   // 主题必须在 setContentView 之前
        super.onCreate(savedInstanceState);
        setContentView(buildRoot());
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Palette.bg(this));
        root.setFitsSystemWindows(true);

        // —— 顶栏 ——
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), 0, dp(12), 0);

        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.ic_back);
        back.setBackgroundResource(R.drawable.icon_btn_bg);
        back.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        back.setColorFilter(Palette.text(this));
        back.setContentDescription("返回");
        back.setOnClickListener(v -> {
            finish();
            overridePendingTransition(0, android.R.anim.fade_out);
        });
        bar.addView(back, new LinearLayout.LayoutParams(dp(38), dp(38)));

        TextView title = new TextView(this);
        title.setText("关于");
        title.setTextColor(Palette.text(this));
        title.setTextSize(16f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(dp(12), 0, 0, 0);
        bar.addView(title);
        root.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));

        View line = new View(this);
        line.setBackgroundColor(Palette.stroke(this));
        root.addView(line, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        // —— 可滚动内容 ——
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(10), dp(14), dp(24));
        sv.addView(col, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        String verName = "";
        int verCode = 0;
        int targetSdk = 0;
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            verName = pi.versionName == null ? "" : pi.versionName.trim();
            verCode = Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
            targetSdk = pi.applicationInfo.targetSdkVersion;
        } catch (Throwable ignored) {
        }
        pkgInfo = "AgenCode v" + verName + " (build " + verCode + ")\n包名：" + getPackageName()
                + "\nAndroid " + Build.VERSION.RELEASE + " · " + Build.MODEL;

        // —— 头部 ——
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setGravity(Gravity.CENTER_HORIZONTAL);
        head.setPadding(0, dp(18), 0, dp(6));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        head.addView(icon, new LinearLayout.LayoutParams(dp(64), dp(64)));

        TextView name = new TextView(this);
        name.setText("AgenCode");
        name.setTextColor(Palette.text(this));
        name.setTextSize(19f);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, dp(10), 0, 0);
        head.addView(name);

        TextView slogan = new TextView(this);
        slogan.setText("装在手机里的 AI 助手 · 可自配模型");
        slogan.setTextColor(Palette.dim(this));
        slogan.setTextSize(12f);
        slogan.setGravity(Gravity.CENTER);
        slogan.setPadding(0, dp(5), 0, 0);
        head.addView(slogan);

        TextView verBadge = new TextView(this);
        verBadge.setText("v" + verName + " · build " + verCode);
        verBadge.setTextColor(Palette.accent(this));
        verBadge.setTextSize(11.5f);
        verBadge.setGravity(Gravity.CENTER);
        verBadge.setPadding(dp(10), dp(3), dp(10), dp(3));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setCornerRadius(dp(9));
        badgeBg.setColor(Palette.accentSoft(this, 0x22));
        badgeBg.setStroke(dp(1), Palette.accentSoft(this, 0x66));
        verBadge.setBackground(badgeBg);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        badgeLp.topMargin = dp(9);
        head.addView(verBadge, badgeLp);

        col.addView(head);

        // —— 更新日志：放在版本信息上面，进关于页先看到"最近改了什么" ——
        changelogCard(col);

        // —— 版本信息 ——
        LinearLayout verCard = card(col, "版本信息");
        infoRow(verCard, "版本名称", verName.isEmpty() ? "未知" : verName);
        infoRow(verCard, "版本号 build", String.valueOf(verCode));
        infoRow(verCard, "包名", getPackageName());
        infoRow(verCard, "targetSdk", String.valueOf(targetSdk));

        // —— 运行环境 ——
        LinearLayout envCard = card(col, "运行环境");
        infoRow(envCard, "设备", Build.MANUFACTURER + " " + Build.MODEL);
        infoRow(envCard, "系统", "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        String abi = (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0)
                ? Build.SUPPORTED_ABIS[0] : "unknown";
        infoRow(envCard, "CPU 架构", abi);
        infoRow(envCard, "沙箱", "proot + Huawei Cloud EulerOS (aarch64)");

        // —— 数据与隐私 ——
        LinearLayout privCard = card(col, "数据与隐私");
        para(privCard, "· API Key 只保存在本机应用私有目录，除了请求你自己配置的接口地址外，不会发往任何第三方。");
        para(privCard, "· 会话记录、背景图、产物文件都保存在本机；卸载应用即全部删除。");
        para(privCard, "· 没有广告 SDK、没有统计埋点、没有账号体系，不收集任何设备标识。");

        // —— 开源致谢 ——
        LinearLayout thanksCard = card(col, "开源与致谢");
        para(thanksCard, "· AndroidX AppCompat / Core");
        para(thanksCard, "· APK Signature Scheme（apksig）用于安装包校验");
        para(thanksCard, "· 兼容任意 OpenAI 格式 /chat/completions 接口");
        para(thanksCard, "· 图标为本项目自绘矢量图");

        // —— 操作按钮 ——
        TextView btnCopy = new TextView(this);
        btnCopy.setText("复制版本信息");
        btnCopy.setGravity(Gravity.CENTER);
        btnCopy.setTextSize(13.5f);
        btnCopy.setTextColor(0xFFFFFFFF);
        btnCopy.setBackgroundResource(R.drawable.btn_primary);
        LinearLayout.LayoutParams cLP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        cLP.topMargin = dp(16);
        col.addView(btnCopy, cLP);
        btnCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("AgenCode 版本信息", pkgInfo));
                Toast.makeText(this, "版本信息已复制", Toast.LENGTH_SHORT).show();
            }
        });

        TextView btnPrivacy = new TextView(this);
        btnPrivacy.setText("隐私与安全说明");
        btnPrivacy.setGravity(Gravity.CENTER);
        btnPrivacy.setTextSize(13.5f);
        btnPrivacy.setTextColor(Palette.text(this));
        btnPrivacy.setBackgroundResource(R.drawable.btn_ghost);
        LinearLayout.LayoutParams pLP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        pLP.topMargin = dp(10);
        col.addView(btnPrivacy, pLP);
        btnPrivacy.setOnClickListener(v -> showPrivacy());

        TextView foot = new TextView(this);
        foot.setText("© 2026 AgenCode · 仅供个人学习与研究使用");
        foot.setTextColor(Palette.dim(this));
        foot.setTextSize(10.5f);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, dp(22), 0, 0);
        col.addView(foot);

        return root;
    }

    private void showPrivacy() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("隐私与安全说明")
                .setMessage("1. 网络请求只发往你在设置里填写的接口地址（默认 DeepSeek 官方）。\n"
                        + "2. API Key 保存在应用私有目录，其它应用无法读取。\n"
                        + "3. 会话、背景图、产物文件只存在本机，卸载即删除。\n"
                        + "4. 应用不含任何统计/广告 SDK，不收集设备标识。\n"
                        + "5. 分享或导出内容时，请注意其中可能包含你与模型的对话原文。")
                .setPositiveButton("知道了", null)
                .show();
    }

    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card_bg);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        parent.addView(card, lp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Palette.accent(this));
        t.setTextSize(13f);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(t);
        return card;
    }

    private void infoRow(LinearLayout parent, String key, String value) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(8), 0, 0);

        TextView k = new TextView(this);
        k.setText(key);
        k.setTextColor(Palette.dim(this));
        k.setTextSize(12f);
        r.addView(k, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(Palette.text(this));
        v.setTextSize(12f);
        v.setGravity(Gravity.END);
        v.setMaxLines(2);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        r.addView(v);

        parent.addView(r);
    }

    private void para(LinearLayout parent, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Palette.dim(this));
        t.setTextSize(12f);
        t.setLineSpacing(dp(3), 1f);
        t.setPadding(0, dp(9), 0, 0);
        parent.addView(t);
    }

    /**
     * 更新日志卡片：版本行为标题，点一下展开该版本条目。
     * 最新一条默认展开 —— 用户点"关于"多半就是想知道刚更新了什么。
     */
    private void changelogCard(LinearLayout parent) {
        LinearLayout card = card(parent, "更新日志");
        for (int i = 0; i < Changelog.ENTRIES.length; i++) {
            final Changelog.Entry e = Changelog.ENTRIES[i];
            final boolean newest = i == 0;

            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPadding(0, dp(10), 0, 0);

            TextView ver = new TextView(this);
            ver.setText("v" + e.version);
            ver.setTextSize(12.5f);
            ver.setTextColor(newest ? Palette.accent(this) : Palette.text(this));
            ver.setTypeface(null, android.graphics.Typeface.BOLD);
            head.addView(ver);

            if (newest) {
                TextView tag = new TextView(this);
                tag.setText("当前");
                tag.setTextSize(10f);
                tag.setTextColor(Palette.accent(this));
                tag.setPadding(dp(7), dp(2), dp(7), dp(2));
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(Palette.accentSoft(this, 0x22));
                bg.setCornerRadius(dp(99));
                tag.setBackground(bg);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                tlp.leftMargin = dp(8);
                head.addView(tag, tlp);
            }

            TextView arrow = new TextView(this);
            arrow.setText(newest ? "▾" : "▸");
            arrow.setTextSize(11f);
            arrow.setTextColor(Palette.dim(this));
            arrow.setGravity(Gravity.END);
            head.addView(arrow, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            final LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(0, dp(2), 0, 0);
            for (String it : e.items) para(body, "· " + it);
            body.setVisibility(newest ? LinearLayout.VISIBLE : LinearLayout.GONE);

            head.setOnClickListener(v -> {
                boolean show = body.getVisibility() != LinearLayout.VISIBLE;
                body.setVisibility(show ? LinearLayout.VISIBLE : LinearLayout.GONE);
                arrow.setText(show ? "▾" : "▸");
            });

            card.addView(head);
            card.addView(body);
        }
    }
}
