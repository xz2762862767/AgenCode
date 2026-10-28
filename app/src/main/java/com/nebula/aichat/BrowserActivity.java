package com.nebula.aichat;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.nebula.aichat.data.Settings;
import com.nebula.aichat.net.LlmOnce;
import com.nebula.aichat.ui.Palette;
import com.nebula.aichat.ui.Ui;

/**
 * 内置浏览器：给"边聊边查资料"用的轻量网页窗口。
 *
 * <p>为什么不跳系统浏览器：跳出去就等于把用户从对话里踢走，看完还得手动切回来；
 * 而且网页内容进不了翻译流程。这里自建一个，重点是**页面能就地译成中文**——
 * 大量技术文档、报错页面、官方说明都只有英文，看不懂就直接没法继续对话。
 *
 * <p>翻译走"DOM 文本节点抽取 → 分批译 → 原位置回填"，不是整页截图式的覆盖层：
 * 回填后页面结构、链接、排版全都还在，可以继续点、继续滚。
 */
public class BrowserActivity extends Activity {

    /** 页面文本节点数上限：再多就只译前一段，否则请求数会失控 */
    private static final int MAX_NODES = 600;
    /** 一次翻译的字符预算：太小请求次数多（慢），太大容易撞 max_tokens 被截断 */
    private static final int BATCH_CHARS = 2600;
    private static final int BATCH_NODES = 40;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout root;
    private EditText etUrl;
    private TextView tvTitle;
    private TextView tvTrans;
    private TextView tvStatus;
    private ProgressBar progress;
    private WebView web;
    private LinearLayout emptyBox;

    private Settings settings;
    /** 翻译串行执行：并发打模型接口只会互相拖慢，还容易撞限流 */
    private final java.util.concurrent.ExecutorService pool =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    /** 当前页是否已经"译过"（再点一次就是还原） */
    private boolean translated = false;
    private volatile boolean translating = false;
    /** 页面加载完成标记：地址栏不许在跳转过程中被历史 URL 覆盖 */
    private boolean pageReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Palette.apply(this);
        settings = Settings.load(this);
        setContentView(buildUi());
        setupWeb();

        String start = getIntent() == null ? null : getIntent().getStringExtra("url");
        if (start != null && !start.trim().isEmpty()) {
            load(normalize(start.trim()));
        } else {
            showEmpty(true);
        }
    }

    // ---------------------------------------------------------------- UI

    private View buildUi() {
        int bg = Palette.bg(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        root.setFitsSystemWindows(true);

        // ---- 顶部：返回 + 标题 + 更多
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int p = Ui.dp(this, 10);
        bar.setPadding(p, Ui.dp(this, 6), p, Ui.dp(this, 6));

        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_back);
        back.setColorFilter(Palette.text(this));
        back.setContentDescription("返回");
        back.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));

        back.setOnClickListener(v -> onBackPressed());
        bar.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));

        tvTitle = new TextView(this);
        tvTitle.setText("浏览器");
        tvTitle.setTextSize(16f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setTextColor(Palette.text(this));
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tLP = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tLP.leftMargin = Ui.dp(this, 4);
        bar.addView(tvTitle, tLP);

        ImageView more = new ImageView(this);
        more.setImageResource(R.drawable.ic_menu);
        more.setColorFilter(Palette.dim(this));
        more.setContentDescription("更多");
        more.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        more.setOnClickListener(this::showMoreMenu);
        bar.addView(more, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));

        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(Ui.divider(this));

        // ---- 地址栏：输入框 + 翻译按钮
        LinearLayout addrRow = new LinearLayout(this);
        addrRow.setOrientation(LinearLayout.HORIZONTAL);
        addrRow.setGravity(Gravity.CENTER_VERTICAL);
        addrRow.setPadding(p, Ui.dp(this, 8), p, Ui.dp(this, 8));

        etUrl = new EditText(this);
        etUrl.setSingleLine(true);
        etUrl.setTextSize(13.5f);
        etUrl.setTextColor(Palette.text(this));
        etUrl.setHintTextColor(Palette.dim(this));
        etUrl.setHint("输入网址或搜索内容");
        etUrl.setBackground(Ui.roundRect(this, Palette.surface2(this), Palette.stroke(this), 12f));
        etUrl.setPadding(Ui.dp(this, 12), Ui.dp(this, 9), Ui.dp(this, 12), Ui.dp(this, 9));
        etUrl.setImeOptions(EditorInfo.IME_ACTION_GO);
        etUrl.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        etUrl.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_GO
                    || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                submitUrl();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams uLP = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        addrRow.addView(etUrl, uLP);

        tvTrans = new TextView(this);
        tvTrans.setText("译");
        tvTrans.setTextSize(13f);
        tvTrans.setTypeface(Typeface.DEFAULT_BOLD);
        tvTrans.setGravity(Gravity.CENTER);
        tvTrans.setTextColor(Palette.accent(this));
        tvTrans.setBackground(Ui.roundRect(this, Palette.accentSoft(this, 0x1F),
                Palette.accentSoft(this, 0x55), 12f));
        tvTrans.setOnClickListener(v -> onTranslateClick());
        LinearLayout.LayoutParams trLP = new LinearLayout.LayoutParams(
                Ui.dp(this, 44), ViewGroup.LayoutParams.MATCH_PARENT);
        trLP.leftMargin = Ui.dp(this, 8);
        trLP.topMargin = 0;
        addrRow.addView(tvTrans, trLP);

        root.addView(addrRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 进度条：1.6dp 细线，不占位（gone 时不挤动下方的网页）
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 2)));

        // ---- 网页区（空白态盖在上面）
        FrameLayout stage = new FrameLayout(this);
        root.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        web = new WebView(this);
        web.setBackgroundColor(Palette.bg(this));
        stage.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        emptyBox = buildEmpty();
        stage.addView(emptyBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- 底部状态条（翻译进度/结果），默认隐藏
        tvStatus = new TextView(this);
        tvStatus.setTextSize(12f);
        tvStatus.setTextColor(Palette.accent(this));
        tvStatus.setBackgroundColor(Palette.surface2(this));
        tvStatus.setPadding(p, Ui.dp(this, 7), p, Ui.dp(this, 7));
        tvStatus.setVisibility(View.GONE);
        root.addView(tvStatus, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return root;
    }

    /** 空白态：给几个入口，省得新用户对着空屏想"这玩意怎么用" */
    private LinearLayout buildEmpty() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);

        ImageView ic = new ImageView(this);
        ic.setImageResource(R.drawable.ic_browser);
        ic.setColorFilter(Palette.dim(this));
        box.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)));

        TextView t = new TextView(this);
        t.setText("点击上方输入网址开始浏览");
        t.setTextSize(13.5f);
        t.setTextColor(Palette.dim(this));
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 12);
        box.addView(t, lp);

        TextView hint = new TextView(this);
        hint.setText("英文页面会自动译成中文");
        hint.setTextSize(12f);
        hint.setTextColor(Palette.dim(this));
        hint.setAlpha(0.7f);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = Ui.dp(this, 6);
        box.addView(hint, lp2);

        LinearLayout quick = new LinearLayout(this);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        quick.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ql.topMargin = Ui.dp(this, 18);
        String[][] sites = {
                {"GitHub", "https://github.com"},
                {"MDN", "https://developer.mozilla.org"},
                {"Stack Overflow", "https://stackoverflow.com"},
        };
        for (String[] s : sites) {
            TextView chip = new TextView(this);
            chip.setText(s[0]);
            chip.setTextSize(12f);
            chip.setTextColor(Palette.text(this));
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(Ui.dp(this, 12), Ui.dp(this, 7), Ui.dp(this, 12), Ui.dp(this, 7));
            chip.setBackground(Ui.roundRect(this, Palette.surface2(this),
                    Palette.stroke(this), 99f));
            chip.setOnClickListener(v -> load(normalize(s[1])));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cl.rightMargin = Ui.dp(this, 8);
            quick.addView(chip, cl);
        }
        box.addView(quick, ql);
        return box;
    }

    private void showEmpty(boolean show) {
        emptyBox.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------- WebView

    private void setupWeb() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // 页面文字用系统默认字号，跟着系统"字体大小"走，别自己再改一道
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                // 非 http(s) 协议（intent://、tel:、mailto:）交回系统，WebView 打不开
                if (url == null) return false;
                if (url.startsWith("http://") || url.startsWith("https://")
                        || url.startsWith("about:")) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Throwable ignored) {
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap fav) {
                pageReady = false;
                // 页面要跳走了：__agcNodes 里的节点马上失效，正在跑的翻译必须停
                translating = false;
                progress.setVisibility(View.VISIBLE);
                progress.setProgress(0);
                showEmpty(false);
                if (url != null && !url.startsWith("about:")) etUrl.setText(url);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                pageReady = true;
                progress.setVisibility(View.GONE);
                translated = false;
                renderTransButton();
                String t = v.getTitle();
                tvTitle.setText(t == null || t.trim().isEmpty() ? "浏览器" : t.trim());
                if (url != null && !url.startsWith("about:")
                        && !url.equals(etUrl.getText().toString())) {
                    etUrl.setText(url);
                }
                // 英文页面自动翻：这是这个浏览器的核心价值，默认开
                if (settings.browserAutoTranslate) {
                    autoTranslateIfEnglish();
                }
            }

            @Override
            public void onReceivedError(WebView v, int code, String desc, String url) {
                progress.setVisibility(View.GONE);
                status("打不开这个页面（" + desc + "）");
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                progress.setVisibility(p >= 100 ? View.GONE : View.VISIBLE);
                progress.setProgress(p);
            }
        });

        web.setDownloadListener((url, ua, cd, mime, len) -> {
            // 下载交给系统：WebView 自己没有下载能力，硬扛只会得到一个空白页
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                status("已交给系统下载：" + (cd == null ? url : cd));
            } catch (Throwable t) {
                status("无法下载这个文件");
            }
        });
    }

    private void submitUrl() {
        String raw = etUrl.getText().toString().trim();
        if (raw.isEmpty()) return;
        hideIme();
        load(normalize(raw));
    }

    /**
     * 输入归一化：能看出来是网址就补协议，否则当成搜索词。
     * 判断标准故意宽松 —— 用户输入 "react hooks" 时不该被拼成 https://react hooks。
     */
    private String normalize(String raw) {
        String s = raw.trim();
        if (s.startsWith("http://") || s.startsWith("https://") || s.startsWith("about:")
                || s.startsWith("file://")) {
            return s;
        }
        boolean looksLikeHost = !s.contains(" ") && (s.contains(".") || s.startsWith("localhost"));
        if (looksLikeHost) return "https://" + s;
        return "https://www.bing.com/search?q=" + Uri.encode(s);
    }

    private void load(String url) {
        showEmpty(false);
        web.loadUrl(url);
    }

    private void hideIme() {
        try {
            InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (im != null) im.hideSoftInputFromWindow(etUrl.getWindowToken(), 0);
            etUrl.clearFocus();
        } catch (Throwable ignored) {
        }
    }

    private void status(String msg) {
        if (msg == null) {
            tvStatus.setVisibility(View.GONE);
            return;
        }
        tvStatus.setText(msg);
        tvStatus.setVisibility(View.VISIBLE);
    }

    private void renderTransButton() {
        tvTrans.setText(translated ? "原" : "译");
        tvTrans.setContentDescription(translated ? "显示原文" : "翻译成中文");
    }

    private void showMoreMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "后退");
        m.getMenu().add(0, 2, 1, "前进");
        m.getMenu().add(0, 3, 2, "刷新");
        m.getMenu().add(0, 4, 3, "回到首页");
        m.getMenu().add(0, 5, 4, "用系统浏览器打开");
        m.getMenu().add(0, 6, 5, settings.browserAutoTranslate
                ? "自动翻译英文页面：已开" : "自动翻译英文页面：已关");
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    if (web.canGoBack()) web.goBack();
                    else status("已经是第一页");
                    return true;
                case 2:
                    if (web.canGoForward()) web.goForward();
                    else status("没有下一页");
                    return true;
                case 3:
                    web.reload();
                    return true;
                case 4:
                    web.loadUrl("about:blank");
                    etUrl.setText("");
                    showEmpty(true);
                    tvTitle.setText("浏览器");
                    status(null);
                    return true;
                case 5:
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(web.getUrl())));
                    } catch (Throwable ignored) {
                    }
                    return true;
                case 6:
                    settings.browserAutoTranslate = !settings.browserAutoTranslate;
                    settings.save(this);
                    status(settings.browserAutoTranslate ? "英文页面将自动翻译" : "已关闭自动翻译");
                    return true;
                default:
                    return false;
            }
        });
        m.show();
    }

    // ---------------------------------------------------------------- AI 翻译

    /**
     * 抽取可翻译的文本节点。
     *
     * <p>只取"叶子文本节点"，并且把 script/style/code/pre 整段排除：代码块进了翻译器
     * 会被改写成看不懂的东西，比不翻更糟。同时备份每个节点的原始 nodeValue 和首尾空白，
     * 后面还原、回填都靠它 —— 丢空白会让相邻英文单词粘成一坨。
     */
    private static final String JS_EXTRACT = String.join("\n",
            "(function(){",
            "  if(!document.body) return \"[]\";",
            "  var SKIP={SCRIPT:1,STYLE:1,NOSCRIPT:1,CODE:1,PRE:1,TEXTAREA:1,IFRAME:1,",
            "            SVG:1,CANVAS:1,SELECT:1,OPTION:1,KBD:1,SAMP:1};",
            "  var nodes=[],lead=[],trail=[],out=[];",
            "  var LIMIT=" + MAX_NODES + ";",
            "  function interesting(s){",
            "    for(var i=0;i<s.length;i++){var c=s.charCodeAt(i);",
            "      if((c>=65&&c<=90)||(c>=97&&c<=122)) return true;",
            "      if(c>=19968&&c<=40959) return true;}",
            "    return false;",
            "  }",
            "  var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null,false),n;",
            "  while((n=w.nextNode())){",
            "    if(nodes.length>=LIMIT) break;",
            "    var v=n.nodeValue; if(!v) continue;",
            "    var t=v.trim(); if(t.length<2) continue;",
            "    var p=n.parentNode; if(!p) continue;",
            "    var tag=p.nodeName?p.nodeName.toUpperCase():\"\";",
            "    if(SKIP[tag]) continue;",
            "    if(p.isContentEditable) continue;",
            "    if(!interesting(t)) continue;",
            "    var at=v.indexOf(t);",
            "    nodes.push(n); lead.push(v.substring(0,at)); trail.push(v.substring(at+t.length));",
            "    out.push({i:nodes.length-1,t:t});",
            "  }",
            "  window.__agcNodes=nodes; window.__agcLead=lead; window.__agcTrail=trail;",
            "  window.__agcOri=nodes.map(function(x){return x.nodeValue;});",
            "  return JSON.stringify(out);",
            "})()");

    /** 语言探测：只取正文前 3000 字统计字母/汉字占比，够判断"这是不是英文页"了 */
    private static final String JS_LANG = String.join("\n",
            "(function(){",
            "  var lang=(document.documentElement&&document.documentElement.lang)||'';",
            "  var body=document.body?(document.body.innerText||''):'';",
            "  body=body.slice(0,3000);",
            "  var lat=0,cjk=0;",
            "  for(var i=0;i<body.length;i++){var c=body.charCodeAt(i);",
            "    if((c>=65&&c<=90)||(c>=97&&c<=122)) lat++;",
            "    else if(c>=19968&&c<=40959) cjk++;}",
            "  return JSON.stringify({lang:lang,len:body.length,lat:lat,cjk:cjk});",
            "})()");

    private static final String JS_RESTORE = String.join("\n",
            "(function(){",
            "  var N=window.__agcNodes||[],O=window.__agcOri||[];",
            "  for(var i=0;i<N.length;i++){ if(N[i]&&O[i]!==undefined) N[i].nodeValue=O[i]; }",
            "})()");

    private static final String SYS_TRANSLATE =
            "你是网页翻译引擎。输入是一个 JSON 对象，键是序号，值是英文文本。\n"
                    + "把每个值翻译成简体中文，要求：\n"
                    + "1. 只翻译自然语言；代码、命令、URL、变量名、文件名、API 名称原样保留；\n"
                    + "2. 译文简洁、符合中文技术文档习惯，不加解释、不加引号；\n"
                    + "3. 输出必须是 JSON 对象，键与输入完全一致，值是中文；\n"
                    + "4. 不要输出 Markdown 代码块、不要任何前后说明文字。";

    /** 一个待译片段 */
    private static final class Item {
        final int idx;
        final String text;

        Item(int idx, String text) {
            this.idx = idx;
            this.text = text;
        }
    }

    private void onTranslateClick() {
        if (translating) {
            translating = false;
            renderTransButton();
            status("已停止翻译");
            return;
        }
        if (translated) {
            web.evaluateJavascript(JS_RESTORE, null);
            translated = false;
            renderTransButton();
            status("已切回原文");
            return;
        }
        doTranslate();
    }

    private void doTranslate() {
        if (translating || web == null) return;
        if (!pageReady) {
            status("页面还没加载完，稍等一下");
            return;
        }
        if (settings == null || !settings.isConfigured()) {
            status("翻译需要模型：先到设置里填好 API Key");
            return;
        }
        translating = true;
        tvTrans.setText("…");
        status("正在提取页面文字…");

        web.evaluateJavascript(JS_EXTRACT, raw -> {
            java.util.List<Item> items = parseExtracted(raw);
            if (items == null || items.isEmpty()) {
                translating = false;
                renderTransButton();
                status("这一页没有可翻译的文字");
                return;
            }
            startBatches(items);
        });
    }

    /** evaluateJavascript 的回调值是 JSON 编码过的字符串，要先解一层 */
    private java.util.List<Item> parseExtracted(String raw) {
        try {
            Object v = new org.json.JSONTokener(raw).nextValue();
            org.json.JSONArray arr = new org.json.JSONArray(String.valueOf(v));
            java.util.List<Item> out = new java.util.ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                String t = o.optString("t", "");
                if (!t.trim().isEmpty()) out.add(new Item(o.optInt("i", -1), t));
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 按字符数 + 条数双重预算切批：太大容易撞 max_tokens 被截断成半句 */
    private java.util.List<java.util.List<Item>> pack(java.util.List<Item> items) {
        java.util.List<java.util.List<Item>> out = new java.util.ArrayList<>();
        java.util.List<Item> cur = new java.util.ArrayList<>();
        int chars = 0;
        for (Item it : items) {
            if (!cur.isEmpty()
                    && (chars + it.text.length() > BATCH_CHARS || cur.size() >= BATCH_NODES)) {
                out.add(cur);
                cur = new java.util.ArrayList<>();
                chars = 0;
            }
            cur.add(it);
            chars += it.text.length();
        }
        if (!cur.isEmpty()) out.add(cur);
        return out;
    }

    private void startBatches(java.util.List<Item> items) {
        final java.util.List<java.util.List<Item>> batches = pack(items);
        final int total = batches.size();
        final int[] done = {0};
        final int[] ok = {0};

        pool.execute(() -> {
            for (java.util.List<Item> batch : batches) {
                if (!translating) return;
                final int cur = done[0] + 1;
                ui.post(() -> status("翻译中 " + cur + "/" + total + " 批…"));

                org.json.JSONObject map = translateBatch(batch);
                if (map != null && map.length() > 0) {
                    ok[0] += map.length();
                    final String js = fillJs(map);
                    ui.post(() -> {
                        if (web != null) web.evaluateJavascript(js, null);
                    });
                }
                done[0]++;
            }

            ui.post(() -> {
                translating = false;
                translated = ok[0] > 0;
                renderTransButton();
                if (translated) {
                    status("已译 " + ok[0] + " 段中文 · 点「原」可切回原文");
                } else {
                    status("翻译没有成功，检查网络或密钥后重试");
                }
            });
        });
    }

    /** 译一批；返回 索引→译文 的映射，失败返回 null */
    private org.json.JSONObject translateBatch(java.util.List<Item> batch) {
        try {
            org.json.JSONObject req = new org.json.JSONObject();
            for (Item it : batch) req.put(String.valueOf(it.idx), it.text);

            String reply = LlmOnce.ask(settings, SYS_TRANSLATE, req.toString(), 4000);
            org.json.JSONObject got = extractJsonObject(reply);
            if (got == null) return null;

            // 只保留本批请求过的键：模型偶尔会自己编序号，回填错位置比不翻更糟
            org.json.JSONObject safe = new org.json.JSONObject();
            for (Item it : batch) {
                String key = String.valueOf(it.idx);
                String zh = got.optString(key, null);
                if (zh != null && !zh.trim().isEmpty()) safe.put(key, zh.trim());
            }
            return safe;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 从模型回复里抠出 JSON 对象：它经常会包一层 ```json ```
     */
    private org.json.JSONObject extractJsonObject(String reply) {
        if (reply == null) return null;
        int a = reply.indexOf('{');
        int b = reply.lastIndexOf('}');
        if (a < 0 || b <= a) return null;
        try {
            return new org.json.JSONObject(reply.substring(a, b + 1));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 回填：按索引把译文写回原节点，首尾空白用抽取时备份的补上 */
    private String fillJs(org.json.JSONObject map) {
        return "(function(){var T=" + map.toString() + ";" +
                "var N=window.__agcNodes||[],L=window.__agcLead||[],R=window.__agcTrail||[];" +
                "for(var k in T){var i=parseInt(k,10);" +
                "if(N[i]){N[i].nodeValue=(L[i]||'')+T[k]+(R[i]||'');}}})()";
    }

    /**
     * 英文页自动翻。
     * 判定不只看 &lt;html lang&gt; —— 相当多页面根本不写或写错，
     * 所以再加一条正文统计：字母远多于汉字且篇幅够长，就当英文页处理。
     */
    private void autoTranslateIfEnglish() {
        if (translating || translated || web == null) return;
        if (settings == null || !settings.browserAutoTranslate) return;

        web.evaluateJavascript(JS_LANG, raw -> {
            try {
                Object v = new org.json.JSONTokener(raw).nextValue();
                org.json.JSONObject o = new org.json.JSONObject(String.valueOf(v));
                String lang = o.optString("lang", "").toLowerCase();
                int lat = o.optInt("lat", 0);
                int cjk = o.optInt("cjk", 0);
                int len = o.optInt("len", 0);
                if (len < 200) return;
                boolean en = lang.startsWith("en") || (lat > 200 && cjk * 8 < lat);
                if (en && !translating && !translated) doTranslate();
            } catch (Throwable ignored) {
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        translating = false;
        try {
            pool.shutdownNow();
        } catch (Throwable ignored) {
        }
        try {
            if (web != null) {
                web.stopLoading();
                web.loadUrl("about:blank");
                ViewGroup parent = (ViewGroup) web.getParent();
                if (parent != null) parent.removeView(web);
                web.destroy();
                web = null;
            }
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }
}
