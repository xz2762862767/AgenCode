package com.nebula.aichat.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** 只读源码查看器：行号 + 语法高亮 + 一键复制 */
public final class SourceViewer {

    private SourceViewer() {
    }

    public static void show(final Activity act, String title, String subtitle,
                            final String code, String lang) {
        // 用 App 弹窗主题（随浅色/深色切换），窗口尺寸下面再拉满
        final Dialog dlg = new Dialog(act);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = Ui.col(act);
        root.setBackgroundColor(Palette.c(0xFF0C1016));

        LinearLayout head = Ui.row(act);
        head.setBackgroundColor(Palette.c(0xFF10151C));
        head.setPadding(Ui.dp(act, 6), Ui.dp(act, 9), Ui.dp(act, 10), Ui.dp(act, 9));

        TextView back = Ui.text(act, "‹", 26f, 0xFFD7E0EE);
        back.setGravity(Gravity.CENTER);
        back.setIncludeFontPadding(false);
        back.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(act, 38), Ui.dp(act, 38)));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dlg.dismiss();
            }
        });
        head.addView(back);

        LinearLayout info = Ui.col(act);
        TextView t1 = Ui.text(act, title == null ? "" : title, 13.5f, 0xFFE8EDF6);
        t1.setTypeface(Typeface.DEFAULT_BOLD);
        t1.setSingleLine(true);
        t1.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        TextView t2 = Ui.text(act, subtitle == null ? "" : subtitle, 10f, 0xFF7A879B);
        t2.setSingleLine(true);
        t2.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        info.addView(t1);
        info.addView(t2);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        ilp.leftMargin = Ui.dp(act, 2);
        head.addView(info, ilp);

        TextView copy = Ui.chip(act, "复制", 0xFF1C2532, 0xFF9DC0FF, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) act.getSystemService(Activity.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("code", code));
                Toast.makeText(act, "已复制源码", Toast.LENGTH_SHORT).show();
            }
        });
        head.addView(copy);

        root.addView(head);
        root.addView(Ui.divider(act));

        TextView tv = new TextView(act);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setTextColor(Palette.c(0xFFC8D3F5));
        tv.setLineSpacing(Ui.dp(act, 2.5f), 1.18f);
        tv.setTextIsSelectable(true);
        tv.setPadding(0, Ui.dp(act, 8), Ui.dp(act, 16), Ui.dp(act, 40));
        String src = code == null ? "" : code;
        tv.setText(src.length() > 120000 || lang == null || lang.isEmpty()
                ? src : CodeHighlighter.highlight(src, lang));

        int lineCount = src.split("\n", -1).length;
        CodeGutter gutter = new CodeGutter(act, tv);
        gutter.setTotalLines(lineCount);

        LinearLayout row = Ui.row(act);
        row.setGravity(Gravity.TOP);
        row.addView(gutter, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        HorizontalScrollView hsv = new HorizontalScrollView(act);
        hsv.setHorizontalScrollBarEnabled(true);
        hsv.addView(tv);
        row.addView(hsv, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        ScrollView sv = new ScrollView(act);
        sv.setFillViewport(true);
        sv.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        dlg.setContentView(root);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Palette.bg(act)));
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        }
        dlg.show();
    }
}
