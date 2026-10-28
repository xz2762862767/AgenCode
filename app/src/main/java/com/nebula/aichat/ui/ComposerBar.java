package com.nebula.aichat.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 输入框上方的功能栏：
 *   📎 文件 / 🖼 图片 / ⚡ 技能 / 🔌 MCP / 🌐 联网
 * 已附加的文件与图片以可删除的小标签形式展示在第二行。
 */
public class ComposerBar {

    public interface Listener {
        void onPickFile();
        void onPickImage();
        void onSkill();
        void onMcp();
        void onNetToggle(boolean on);

        /** 浏览器自动化开关（AI 可主动打开网页读正文） */
        void onBrowserToggle(boolean on);

        /** 点击联网按钮：展开折叠的联网设置（两个开关 / 搜索引擎） */
        void onNetMenu();
    }

    /** 附件：text 为文本内容；path 非空表示二进制文件（内容不内联，只给沙箱路径） */
    public static class FileAttachment {
        public String name;
        public String text;
        public long size;
        /** 二进制附件的沙箱绝对路径，文本附件为 null */
        public String path;

        public FileAttachment(String name, String text, long size) {
            this.name = name;
            this.text = text;
            this.size = size;
        }

        public FileAttachment(String name, String path, long size, boolean binary) {
            this.name = name;
            this.text = null;
            this.size = size;
            this.path = binary ? path : null;
        }
    }

    private static final int C_ON_FILL = 0xFF1E3A6B;
    private static final int C_ON_TEXT = 0xFF9DC0FF;
    private static final int C_ON_STROKE = 0xFF5B93FF;
    private static final int C_OFF_FILL = 0xFF1E2530;
    private static final int C_OFF_TEXT = 0xFFB9C5D6;
    private static final int C_OFF_STROKE = 0xFF2A3240;

    private final Activity act;
    private final Listener listener;

    private final LinearLayout chipsRow;
    private final LinearLayout attachRow;
    private final HorizontalScrollView attachScroll;

    private final TextView chipFile;
    private final TextView chipImage;
    private final TextView chipSkill;
    private final TextView chipMcp;
    private final TextView chipNet;

    private final List<FileAttachment> files = new ArrayList<>();
    private final List<String> images = new ArrayList<>();
    private final List<String> imageLabels = new ArrayList<>();
    private boolean netOn;
    private boolean browserOn;

    public ComposerBar(Activity act, LinearLayout container, Listener listener,
                       boolean netInitial, boolean browserInitial) {
        this.act = act;
        this.listener = listener;
        this.netOn = netInitial;
        this.browserOn = browserInitial;

        container.removeAllViews();
        container.setOrientation(LinearLayout.VERTICAL);

        // 第一行：功能 chips（可横滑）
        HorizontalScrollView hs = new HorizontalScrollView(act);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        chipsRow = new LinearLayout(act);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        chipsRow.setGravity(Gravity.CENTER_VERTICAL);
        int p = Ui.dp(act, 10);
        // 上下各 3dp：与分割线 10dp、与输入框 7dp（见 activity_main 输入行 paddingTop=4dp）
        chipsRow.setPadding(p, Ui.dp(act, 3), p, Ui.dp(act, 3));
        hs.addView(chipsRow, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT,
                HorizontalScrollView.LayoutParams.WRAP_CONTENT));
        container.addView(hs, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        chipFile = makeChip("📎 文件");
        chipImage = makeChip("🖼 图片");
        chipSkill = makeChip("⚡ 技能");
        chipMcp = makeChip("🔌 MCP");
        chipNet = makeChip("🌐 联网");

        chipFile.setOnClickListener(v -> {
            if (listener != null) listener.onPickFile();
        });
        chipImage.setOnClickListener(v -> {
            if (listener != null) listener.onPickImage();
        });
        chipSkill.setOnClickListener(v -> {
            if (listener != null) listener.onSkill();
        });
        chipMcp.setOnClickListener(v -> {
            if (listener != null) listener.onMcp();
        });
        // 点击＝展开联网设置菜单（折叠态）；长按＝快速开关
        chipNet.setOnClickListener(v -> {
            if (listener != null) listener.onNetMenu();
        });
        chipNet.setOnLongClickListener(v -> {
            // 长按：两个都开 -> 全关；否则 -> 全开（快速省事）
            boolean turnOn = !(netOn && browserOn);
            setNetOn(turnOn, true);
            setBrowserOn(turnOn, true);
            return true;
        });

        // 第二行：已附加内容预览
        attachScroll = new HorizontalScrollView(act);
        attachScroll.setHorizontalScrollBarEnabled(false);
        attachScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        attachRow = new LinearLayout(act);
        attachRow.setOrientation(LinearLayout.HORIZONTAL);
        attachRow.setGravity(Gravity.CENTER_VERTICAL);
        attachRow.setPadding(p, 0, p, Ui.dp(act, 4));
        attachScroll.addView(attachRow, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT,
                HorizontalScrollView.LayoutParams.WRAP_CONTENT));
        attachScroll.setVisibility(View.GONE);
        container.addView(attachScroll, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        paint();
    }

    private TextView makeChip(String label) {
        TextView tv = new TextView(act);
        tv.setText(label);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tv.setIncludeFontPadding(false);
        tv.setGravity(Gravity.CENTER);
        int hx = Ui.dp(act, 11);
        int hy = Ui.dp(act, 6);
        tv.setPadding(hx, hy, hx, hy);
        tv.setClickable(true);
        tv.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(act, 7);
        chipsRow.addView(tv, lp);
        return tv;
    }

    private void paint() {
        style(chipFile, false);
        style(chipImage, false);
        style(chipSkill, false);
        style(chipMcp, false);
        style(chipNet, netOn || browserOn);
        chipFile.setText("📎 文件");
        chipImage.setText(images.isEmpty() ? "🖼 图片" : "🖼 图片 " + images.size());
        // 保留“联网”二字；设置折叠进点击菜单，两个开关的状态直接写在按钮上
        chipNet.setText(netLabel());
    }

    private void style(TextView tv, boolean on) {
        tv.setBackground(Ui.roundRect(act, on ? Palette.c(C_ON_FILL) : Palette.c(C_OFF_FILL),
                on ? Palette.c(C_ON_STROKE) : Palette.c(C_OFF_STROKE), 15f));
        tv.setTextColor(on ? Palette.c(C_ON_TEXT) : Palette.c(C_OFF_TEXT));
        tv.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    // ---------------- 对外接口 ----------------

    public void setNetOn(boolean on, boolean notify) {
        netOn = on;
        paint();
        if (notify && listener != null) listener.onNetToggle(on);
    }

    /** 联网 chip 作为弹出菜单锚点 */
    public View netChip() {
        return chipNet;
    }

    public boolean isNetOn() {
        return netOn;
    }

    public void setBrowserOn(boolean on, boolean notify) {
        browserOn = on;
        paint();
        if (notify && listener != null) listener.onBrowserToggle(on);
    }

    public boolean isBrowserOn() {
        return browserOn;
    }

    /** 联网按钮文案：0 个开 / 只搜索 / 只浏览 / 全开 */
    private String netLabel() {
        if (netOn && browserOn) return "🌐 联网·全开";
        if (netOn) return "🌐 联网·搜索";
        if (browserOn) return "🌐 联网·浏览";
        return "🌐 联网";
    }


    public void addFile(String name, String text, long size) {
        files.add(new FileAttachment(name, text, size));
        rebuildAttachments();
    }

    /** 二进制文件（APK/zip/图片原文件等）：只登记沙箱路径，让模型用工具去处理 */
    public void addBinaryFile(String name, String sandboxPath, long size) {
        files.add(new FileAttachment(name, sandboxPath, size, true));
        rebuildAttachments();
    }

    /** 图片数量上限：防止单次请求体积/费用失控 */
    public static final int MAX_IMAGES = 4;

    public boolean canAddImage() {
        return images.size() < MAX_IMAGES;
    }

    public void addImage(String dataUrl, String label) {
        if (images.size() >= MAX_IMAGES) return;
        images.add(dataUrl);
        imageLabels.add(label);
        rebuildAttachments();
        paint();
    }

    public void clearAttachments() {
        files.clear();
        images.clear();
        imageLabels.clear();
        rebuildAttachments();
        paint();
    }

    public List<FileAttachment> files() {
        return new ArrayList<>(files);
    }

    public List<String> images() {
        return new ArrayList<>(images);
    }

    public int attachmentCount() {
        return files.size() + images.size();
    }

    private void rebuildAttachments() {
        attachRow.removeAllViews();
        for (int i = 0; i < files.size(); i++) {
            final int idx = i;
            FileAttachment f = files.get(i);
            String icon = f.path != null ? "📦 " : "📄 ";
            attachRow.addView(attachTag(icon + shorten(f.name) + "  ✕", () -> {
                files.remove(idx);
                rebuildAttachments();
                paint();
            }));
        }
        for (int i = 0; i < imageLabels.size(); i++) {
            final int idx = i;
            attachRow.addView(attachTag("🖼 " + shorten(imageLabels.get(i)) + "  ✕", () -> {
                images.remove(idx);
                imageLabels.remove(idx);
                rebuildAttachments();
                paint();
            }));
        }
        attachScroll.setVisibility(attachRow.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }

    private TextView attachTag(String label, Runnable onRemove) {
        TextView tv = new TextView(act);
        tv.setText(label);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setIncludeFontPadding(false);
        tv.setTextColor(Palette.c(0xFF9DC0FF));
        tv.setBackground(Ui.roundRect(act, Palette.c(0xFF171E2A), Palette.c(0xFF2F3B4D), 10f));
        int hx = Ui.dp(act, 9);
        int hy = Ui.dp(act, 4);
        tv.setPadding(hx, hy, hx, hy);
        tv.setClickable(true);
        tv.setOnClickListener(v -> onRemove.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(act, 6);
        tv.setLayoutParams(lp);
        return tv;
    }

    private static String shorten(String s) {
        if (s == null) return "";
        return s.length() <= 14 ? s : s.substring(0, 13) + "…";
    }
}
