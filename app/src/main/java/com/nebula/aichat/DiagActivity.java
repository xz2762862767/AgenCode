package com.nebula.aichat;

import com.nebula.aichat.ui.NebAlert;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.nebula.aichat.ui.Palette;
import com.nebula.aichat.util.Diag;

import java.io.File;
import java.util.List;

/**
 * 运行诊断。
 *
 * 解决的是"闪退了但手机里没有 logcat、没有 dumpsys、连不上电脑"这个死局：
 * 把能靠公开 API 拿到的现场（内存水位、进程优先级、电池限制、被 trim 的记录、
 * 崩溃与 ANR 落盘文件）全部集中到一屏，一键复制就能发出去。
 */
public class DiagActivity extends AppCompatActivity {

    private LinearLayout content;
    private TextView output;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Palette.apply(this);
        setContentView(R.layout.activity_diag);
        content = findViewById(R.id.contentList);

        findViewById(R.id.btnBack).setOnClickListener(v -> {
            finish();
            overridePendingTransition(0, android.R.anim.fade_out);
        });

        buildSnapshotCard();
        buildLogCard();
        buildNoteCard();
    }

    // ------------------------------------------------------------ 快照

    private void buildSnapshotCard() {
        LinearLayout card = newCard("现场快照");

        TextView desc = new TextView(this);
        desc.setText("采集内存、进程优先级、电池限制、保活服务等状态。"
                + "闪退后第一时间点一次，能把「是不是被系统杀的」区分出来。");
        desc.setTextColor(Palette.dim(this));
        desc.setTextSize(11f);
        desc.setLineSpacing(dp(2), 1f);
        card.addView(desc, lpTop(dp(8)));

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(btns, lpTop(dp(12)));

        TextView run = miniBtn("采集快照", true);
        run.setOnClickListener(v -> {
            String s = Diag.snapshot(this);
            output.setText(s);
            Toast.makeText(this, "已采集", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        btns.addView(run, rlp);

        TextView copy = miniBtn("复制", false);
        copy.setOnClickListener(v -> {
            String s = output.getText().toString();
            if (s.trim().isEmpty()) {
                Toast.makeText(this, "先点一次「采集快照」", Toast.LENGTH_SHORT).show();
                return;
            }
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("diag", s));
            Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        clp.leftMargin = dp(8);
        btns.addView(copy, clp);

        TextView share = miniBtn("分享", false);
        share.setOnClickListener(v -> {
            String s = output.getText().toString();
            if (s.trim().isEmpty()) {
                Toast.makeText(this, "先点一次「采集快照」", Toast.LENGTH_SHORT).show();
                return;
            }
            shareText("AgenCode 诊断快照", s);
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        slp.leftMargin = dp(8);
        btns.addView(share, slp);

        output = new TextView(this);
        output.setText("");
        output.setTextColor(Palette.text(this));
        output.setTextSize(10.5f);
        output.setTypeface(Typeface.MONOSPACE);
        output.setLineSpacing(dp(2), 1f);
        output.setTextIsSelectable(true);
        output.setBackgroundResource(R.drawable.code_bg);
        output.setPadding(dp(10), dp(10), dp(10), dp(10));
        ScrollView box = new ScrollView(this);
        box.addView(output);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(300));
        blp.topMargin = dp(10);
        card.addView(box, blp);

        content.addView(card);
    }

    // ------------------------------------------------------------ 日志

    private void buildLogCard() {
        LinearLayout card = newCard("诊断日志");

        TextView desc = new TextView(this);
        desc.setText("闪退堆栈、主线程卡死现场、关键节点流水都在这里。"
                + "文件同时写到 下载/AgenCode/ 目录，可以直接用文件管理器取走。");
        desc.setTextColor(Palette.dim(this));
        desc.setTextSize(11f);
        desc.setLineSpacing(dp(2), 1f);
        card.addView(desc, lpTop(dp(8)));

        for (final File f : Diag.logFiles(this)) {
            card.addView(logRow(f));
        }

        if (Diag.logFiles(this).isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无日志文件（没有出过事是好事）");
            empty.setTextColor(Palette.dim(this));
            empty.setTextSize(11.5f);
            card.addView(empty, lpTop(dp(12)));
        }

        TextView clear = miniBtn("清空全部日志", false);
        clear.setOnClickListener(v -> new NebAlert.Builder(this)
                .setTitle("清空诊断日志")
                .setMessage("会删除所有 crash / anr / diag 记录，删掉后就查不了旧问题了。确定？")
                .setPositiveButton("清空", (d, w) -> {
                    Diag.clearAll(this);
                    rebuild();
                    Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        clp.topMargin = dp(14);
        card.addView(clear, clp);

        content.addView(card);
    }

    private View logRow(final File f) {
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
        t1.setText(f.getName());
        t1.setTextColor(Palette.text(this));
        t1.setTextSize(12.5f);
        texts.addView(t1);

        TextView t2 = new TextView(this);
        t2.setText((f.length() / 1024) + "KB · 点开查看");
        t2.setTextColor(Palette.dim(this));
        t2.setTextSize(10.5f);
        t2.setPadding(0, dp(2), 0, 0);
        texts.addView(t2);

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextColor(Palette.dim(this));
        arrow.setTextSize(18f);
        r.addView(arrow);

        r.setOnClickListener(v -> showLog(f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(2);
        r.setLayoutParams(lp);
        return r;
    }

    private void showLog(final File f) {
        String body = Diag.read(f, 96 * 1024);
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(body);
        tv.setTextColor(Palette.text(this));
        tv.setTextSize(10.5f);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(14), dp(10), dp(14), dp(10));
        sv.addView(tv);

        new NebAlert.Builder(this)
                .setTitle(f.getName())
                .setView(sv)
                .setPositiveButton("关闭", null)
                .setNeutralButton("分享", (d, w) -> shareText(f.getName(), body))
                .show();
    }

    // ------------------------------------------------------------ 说明

    private void buildNoteCard() {
        LinearLayout card = newCard("为什么没有 logcat");

        TextView t = new TextView(this);
        t.setText("从 Android 10 开始，系统日志和设备信息（logcat / dumpsys）只对系统应用开放，"
                + "普通应用执行这两条命令只会得到权限错误——这不是本机坏了，所有没有 root 的手机都一样。\n\n"
                + "替代方案有三层：\n"
                + "1. 应用自己记账：崩溃堆栈、主线程卡死、每个关键动作都会落盘（上面的日志列表）；\n"
                + "2. 公开 API 采集：内存水位、进程优先级、电池限制、保活状态（上面的现场快照）；\n"
                + "3. 沙箱里执行 logcat / dumpsys 这两个名字时，会自动转成内置采集，不会空手而归。\n\n"
                + "如果设备装了 Shizuku 或 root，系统日志才能真的读到。");
        t.setTextColor(Palette.dim(this));
        t.setTextSize(11f);
        t.setLineSpacing(dp(3), 1f);
        card.addView(t, lpTop(dp(8)));

        content.addView(card);
    }

    // ------------------------------------------------------------ 工具

    private void rebuild() {
        content.removeAllViews();
        buildSnapshotCard();
        buildLogCard();
        buildNoteCard();
    }

    private void shareText(String title, String body) {
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_SUBJECT, title);
            i.putExtra(Intent.EXTRA_TEXT, body);
            startActivity(Intent.createChooser(i, "分享诊断信息"));
        } catch (Throwable t) {
            Toast.makeText(this, "分享失败：" + t.getClass().getSimpleName(), Toast.LENGTH_SHORT).show();
        }
    }

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
        t.setTypeface(null, Typeface.BOLD);
        card.addView(t);
        return card;
    }

    private TextView miniBtn(String text, boolean primary) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(12.5f);
        t.setBackgroundResource(primary ? R.drawable.btn_primary : R.drawable.btn_ghost);
        t.setTextColor(primary ? Palette.c(0xFFFFFFFF) : Palette.text(this));
        return t;
    }

    private LinearLayout.LayoutParams lpTop(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
