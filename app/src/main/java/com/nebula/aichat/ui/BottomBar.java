package com.nebula.aichat.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.nebula.aichat.R;

/** 底部导航栏（5 个 tab，矢量图标 + 选中胶囊高亮） */
public class BottomBar {

    public static final int TAB_CHAT = 0;
    public static final int TAB_PROJECT = 1;
    public static final int TAB_TERMINAL = 2;
    public static final int TAB_SANDBOX = 3;
    public static final int TAB_ENV = 4;

    private static final int[] ICONS = {
            R.drawable.ic_tab_chat,
            R.drawable.ic_tab_project,
            R.drawable.ic_tab_terminal,
            R.drawable.ic_tab_sandbox,
            R.drawable.ic_tab_env
    };
    private static final String[] LABELS = {"聊天", "项目", "终端", "沙箱", "环境"};

    private static final int COLOR_ON = 0xFF7FB0FF;
    private static final int COLOR_OFF = 0xFF78849A;
    /** 选中态图标背后的胶囊底色 */
    private static final int PILL_ON = 0x2E5B93FF;

    public interface Listener {
        void onTabSelected(int index);
    }

    private final Activity act;
    private final Listener listener;
    private final ImageView[] icons = new ImageView[ICONS.length];
    private final TextView[] labels = new TextView[LABELS.length];
    private final View[] pills = new View[ICONS.length];
    private int selected = TAB_CHAT;

    public BottomBar(Activity act, LinearLayout container, Listener listener) {
        this.act = act;
        this.listener = listener;
        container.removeAllViews();
        for (int i = 0; i < ICONS.length; i++) {
            final int index = i;

            LinearLayout item = new LinearLayout(act);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setClickable(true);
            item.setFocusable(true);

            // 图标 + 胶囊背景
            FrameLayout wrap = new FrameLayout(act);
            int ww = Ui.dp(act, 50), wh = Ui.dp(act, 30);

            View pill = new View(act);
            pill.setBackground(Ui.roundRect(act, PILL_ON, 0, 15f));
            pill.setVisibility(View.INVISIBLE);
            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(ww, wh);
            plp.gravity = Gravity.CENTER;
            wrap.addView(pill, plp);
            pills[i] = pill;

            ImageView iv = new ImageView(act);
            iv.setImageResource(ICONS[i]);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setColorFilter(COLOR_OFF);
            FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                    Ui.dp(act, 24), Ui.dp(act, 24));
            ilp.gravity = Gravity.CENTER;
            wrap.addView(iv, ilp);
            icons[i] = iv;

            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(ww, wh);
            item.addView(wrap, wlp);

            TextView label = Ui.text(act, LABELS[i], 10.5f, COLOR_OFF);
            label.setGravity(Gravity.CENTER);
            label.setIncludeFontPadding(false);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            llp.topMargin = Ui.dp(act, 3);
            item.addView(label, llp);
            labels[i] = label;

            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (listener != null) listener.onTabSelected(index);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            container.addView(item, lp);
        }
        apply();
    }

    public void select(int index) {
        if (index < 0 || index >= ICONS.length) return;
        selected = index;
        apply();
    }

    public int selected() {
        return selected;
    }

    private void apply() {
        for (int i = 0; i < ICONS.length; i++) {
            boolean on = i == selected;
            int color = on ? COLOR_ON : COLOR_OFF;
            icons[i].setColorFilter(color);
            icons[i].setAlpha(on ? 1f : 0.92f);
            labels[i].setTextColor(color);
            labels[i].setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            pills[i].setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        }
    }
}
