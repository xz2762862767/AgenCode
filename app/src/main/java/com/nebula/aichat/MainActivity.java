package com.nebula.aichat;

import com.nebula.aichat.ui.Palette;
import com.nebula.aichat.ui.RingDrawable;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.AlertDialog;
import com.nebula.aichat.ui.NebAlert;
import android.widget.CompoundButton;
import androidx.appcompat.widget.SwitchCompat;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.speech.tts.TextToSpeech;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.HorizontalScrollView;
import android.widget.PopupMenu;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.nebula.aichat.data.ChatStore;
import com.nebula.aichat.data.Conversation;
import com.nebula.aichat.data.Message;
import com.nebula.aichat.data.Settings;
import com.nebula.aichat.net.BalanceClient;
import com.nebula.aichat.net.ModelListClient;
import com.nebula.aichat.net.ChatClient;
import com.nebula.aichat.net.WebSearch;
import com.nebula.aichat.tool.ShellToolProvider;
import com.nebula.aichat.tool.ToolRegistry;
import com.nebula.aichat.ui.BottomBar;
import com.nebula.aichat.ui.ComposerBar;
import com.nebula.aichat.ui.ConclusionCard;
import com.nebula.aichat.ui.ConclusionParser;
import com.nebula.aichat.ui.ConfirmSheet;
import com.nebula.aichat.ui.EnvPage;
import com.nebula.aichat.ui.MarkdownRenderer;
import com.nebula.aichat.ui.StreamCursor;
import com.nebula.aichat.ui.TaskCardView;
import com.nebula.aichat.util.TaskParser;
import com.nebula.aichat.ui.ProjectPage;
import com.nebula.aichat.ui.SandboxPage;
import com.nebula.aichat.ui.TerminalPage;
import com.nebula.aichat.ui.Ui;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_SETTINGS = 100;
    private static final int REQ_PICK_FILE = 201;
    private static final int REQ_PICK_IMAGE = 202;
    /** 单个文本附件上限 */
    private static final int MAX_FILE_CHARS = 60_000;

    /** 上一轮纯文本提问：出错时用来做「一键重试」。带附件的那轮不记，避免重发丢图 */
    private String lastUserAsk;
    /** 半成品落盘的节流时间戳 */
    private long lastDraftAt;

    private ChatStore store;
    private Settings settings;
    private Conversation current;
    private final ChatClient client = new ChatClient();
    /** 工具注册表：内置 APK 工作台 + 已连接的 MCP 服务器工具 */
    private ToolRegistry tools;
    /** 本轮工具调用轨迹视图（每轮重建，不进会话历史） */
    /** 工具调用卡片：头部（可折叠）+ 每次调用一行 */
    private LinearLayout toolTraceBox;
    private LinearLayout toolTraceList;
    private TextView toolTraceCount;
    private final java.util.List<TextView> toolRows = new java.util.ArrayList<>();
    private final java.util.List<String> toolRowNames = new java.util.ArrayList<>();
    private final java.util.List<Boolean> toolRowDone = new java.util.ArrayList<>();
    /** 每行的开始时间 / 参数快照，用于执行中每秒刷新耗时 */
    private final java.util.List<Long> toolRowStart = new java.util.ArrayList<>();
    private final java.util.List<String> toolRowArgs = new java.util.ArrayList<>();
    /** 每次调用的终态，横幅统计失败/中止次数用 */
    private final java.util.List<Integer> toolRowState = new java.util.ArrayList<>();
    /** 工具窗口折叠状态：默认展开（窗口只有 3 行高，不占地方） */
    private boolean toolTraceCollapsed = false;
    /** 「上次任务被系统中断」卡片只弹一次：切会话重建列表时不再重复插 */
    private boolean runResidueShown = false;
    /** 固定高度的滚动容器：新行从底部推进来，旧行向上滚出，气泡不会被越撑越长 */
    private ScrollView toolScroll;
    /** 每行的完整参数/结果，点行时弹窗查看 */
    private final java.util.List<String> toolRowDetail = new java.util.ArrayList<>();
    /** 窗口一次显示的行数 */
    private static final int TOOL_WINDOW_LINES = 3;
    /** 行数上限：长任务下丢掉最早的，避免无限膨胀 */
    private static final int TOOL_MAX_ROWS = 200;

    // ---------------- 时间线：正文段落与工具行按发生顺序交错 ----------------
    /** 当前正在流式写入的正文段容器； 表示「下一段文字要新起」 */
    private LinearLayout tlSeg;
    /** 绑定 tlSeg 的渲染器（每段独立，避免整段全文反复重建） */
    private MarkdownRenderer tlSegR;
    /** streamBuf 中已被前面的段划走的长度，当前段只渲染它之后的部分 */
    private int tlSegStart = 0;
    /** thinkBuf 里已经落成 step 的位置：保证一段思考只存一次 */
    private int thinkSegStart = 0;
    /** 当前这段思考的起点（uptimeMillis），用来给这一段单独计时 */
    private long thinkSegAt = 0;

    /**
     * 本轮已经立起来的执行步骤卡（在气泡顶部）。
     * 流式过程中模型一写出清单就建卡，之后每跑完一个工具调用就打一个勾，
     * 所以用户看到的是一步一个 ✓ 地往前走，而不是等整轮结束才突然冒出来。
     */
    private TaskCardView streamTaskCard;

    /**
     * 正文上方那张「执行步骤」可勾选卡片是否启用。
     * 关掉后整条链一起停：不解析步骤、不立卡、不自动勾选，
     * 并且不再把清单行从正文里剥走——否则卡片没了、正文也被掏空，
     * 步骤会整段消失。清单留在正文里按普通列表渲染即可。
     */
    private static final boolean TASK_CARD_ENABLED = false;
    /** 本轮时间线，收尾时写进 Message.steps */
    private java.util.List<com.nebula.aichat.data.Message.Step> tlSteps;
    /** 每个工具行对应的 Step，结果回来时回填 */
    private final java.util.List<com.nebula.aichat.data.Message.Step> toolRowStep =
            new java.util.ArrayList<>();

    /** 用户亲手折叠过卡片后，就不再自动展开打扰他 */
    private boolean toolUserTouched = false;
    private TextView toolFoldBtn;
    /** 执行中状态的定时刷新（旋转符号 + 计时） */
    private Runnable toolTick;
    private int spinIdx = 0;
    private static final String[] SPIN = {"\u25d0", "\u25d3", "\u25d1", "\u25d2"};
    private static final int ST_RUN = 0, ST_OK = 1, ST_FAIL = 2, ST_ABORT = 3;
    /** 运行色最小可见时长：几十毫秒就跑完的工具，也要让"绿"在屏上停这么久，否则肉眼抓不到 */
    private static final long RUN_MIN_MS = 900;

    // —— 统一状态色板：低饱和、暗底友好。要调整全站配色，只改这几行 ——
    private static final int C_RUN   = 0xFF3DDC97;  // 运行 / 思考：亮绿（还在动）
    private static final int C_OK    = 0xFF4CD7E8;  // 完成：青蓝（已停下）
    private static final int C_FAIL  = 0xFFEE8A94;  // 失败：柔红
    private static final int C_ABORT = 0xFFFFA85C;  // 中止：暖橙
    private static final int C_TEXT  = 0xFFDCE6F2;  // 主文字：近白
    private static final int C_TEXT2 = 0xFFA9B7C9;  // 次级文字：雾灰
    private static final int C_DIM   = 0xFF7C8A9C;  // 弱化文字：耗时 / 计数
    private final Handler ui = new Handler(Looper.getMainLooper());
    /** 已自动弹过的确认块内容指纹，避免同一块反复弹窗 */
    private String lastAutoConfirmKey;
    /** 本轮请求里模型实际调用了多少次工具（判断它到底是动手还是在聊） */
    private int turnToolCalls;
    /** 已自动续跑过几次，防止一口气无限空转 */
    private int autoContinueCount;
    /** 上一条是「确认回传 / 继续执行」，此刻模型理应开始动手 */
    private boolean awaitingExecution;

    /** 工具轮次用尽的原因（非 null 说明这轮是被强制收尾的） */
    private String pendingToolLimit;
    /** 本次 sendMessage 来自按钮或自动续跑，不要重置续跑计数 */
    private boolean viaAutoSend;
    /** 确认卡去重：同一份选择短时间内只发一次（防连点、防重建后重复点） */
    private String lastConfirmMsg;
    private long lastConfirmAt;

    /** 历史消息虚拟化：一次只实例化末尾这么多条，更早的折叠成「展开更早」按钮 */
    private static final int HISTORY_PAGE = 24;
    private int historyLimit = HISTORY_PAGE;
    private String historyConvId = null;
    /** 滚到底的合并标记：一帧只排一次 */
    private boolean scrollPending = false;
    /** 展开更早消息时把自动滚底让开，保住用户当前视线位置 */
    private boolean scrollLockForExpand = false;
    /** 最近一次滚动时间：流式渲染在滑动期间让路，避免「边滑边刷」抢主线程 */
    private long lastScrollAt = 0L;

    private TextView tvTitle, tvSub, tvBalance, tvUsage, tvUsageCount;
    private View usageRow;
    private ImageView ivModelIcon;
    /** 顶栏余额缓存：只在 DeepSeek 端点用，60s 内不重复请求 */
    private BalanceClient.Result balance;
    private long balanceAt;
    private static final long BALANCE_TTL = 60_000L;
    private ScrollView scroll;
    private LinearLayout chatContainer;
    private EditText etInput;
    private ImageButton btnSend;
    private ComposerBar composer;
    /** 正在联网搜索（搜索完成前不进入流式状态） */
    private boolean searching = false;

    private boolean busy = false;
    private long lastRenderAt = 0;
    private boolean renderPending = false;
    /** 上次重渲染时正文字符数：文本越长，重建视图越贵，用它做增量门限 */
    private int lastRenderLen = 0;
    private StringBuilder streamBuf;
    /** 本轮模型推理内容累积（reasoning_content），流结束后折成「思考过程」块 */
    private StringBuilder thinkBuf;
    /**
     * 整轮（可能跨多个工具轮次）的完整思考内容。
     *
     * <p>thinkBuf 是给「思考过程」折叠块用的，首个正文到达就清空；
     * 而发给下一轮请求的 reasoning_content 必须是完整原文，所以单独留一份。
     */
    private StringBuilder thinkAll;
    /** 推理折叠块的挂载容器（在状态行下方、正文上方） */
    private LinearLayout thinkHost;
    /** 工具调用行的容器：独立一格，排在思考块上面 */
    private LinearLayout toolHost;
    /** 实时思考块：思考进行中就在场，正文随 reasoning 流式追加 */
    private View liveThinkBox;
    /** 回编译产生的修改清单块，模型没在回答里复述时自动补到结尾 */
    private String pendingChangeBlock;

    /** 当前流式渲染器，供「停止」按钮立即收尾用 */
    private MarkdownRenderer streamRenderer;
    private LinearLayout streamContent;
    /** 连续工具调用共用的容器：只露最后一步，其余折叠，避免气泡被一行行撑长 */
    private LinearLayout toolGroupBox;
    private TextView toolGroupHead;
    /**
     * 本轮的过程组：[组头(可空), 组容器]。
     * 一轮答完要整体收进「思考完成 ▾」，所以每建一组就登记一次。
     */
    private final java.util.List<View[]> procGroups = new java.util.ArrayList<>();
    private Message streamMessage;
    /** 流式请求已被作废（如切换/删除会话），回调结果一律丢弃 */
    private boolean streamStale = false;
    /** 思考状态行（正文上方）：小图标 + 「正在思考…」，结束后变「（用时 x 秒）」 */
    private TextView statusText;
    private ImageView statusIcon;
    /** 应用图标（小尺寸），懒加载缓存；执行中状态用它，比通用圆环更有辨识度 */
    private Drawable statusAppIcon;
    private ObjectAnimator spinAnim;
    private long thinkStartAt;
    /** 本轮累计思考耗时：多次工具轮次的思考相加，最后写进消息，历史回放才有秒数 */
    private long runThinkMs = 0;
    private boolean thinkDone = true;
    /** 正文气泡下方的运行横幅：卡片折叠了也能一眼看出还在跑还是已经停了 */
    private LinearLayout runBannerWrap;
    private View runBanner;   // 运行横幅胶囊（FrameLayout）
    private View runBannerHair;
    private TextView runBannerIcon;
    private TextView runBannerText;
    private TextView runBannerSub;      // 第二行次要提示（小字、更暗）
    private View runBannerFlow;         // 底部流光：一条半透明光带横向扫过
    private View runBannerDotWrap;      // 左侧竖直导轨容器（呼吸动画）
    private View runBannerRailBg;       // 导轨本体（一条 3dp 细竖条）
    private View runBannerRailDot;      // 导轨上滑动的高光点
    private ObjectAnimator slideAnim;   // 高光点上下滑动
    private TextView runBannerMeta;      // 并发计数（+2）
    private TextView runBannerTime;      // 右对齐等宽计时
    private com.nebula.aichat.ui.RabbitView runRabbit; // 兔子跑道：四帧矢量跑姿 + 横向循环位移，自绘
    private TextView runBannerTimeTag;   // 计时前的动词：执行 / 用时 / 停止
    private View runBannerLine;          // 底部 2dp 细进度（唯一动效）
    private android.widget.FrameLayout runBannerLineWrap;
    private ObjectAnimator lineAnim;
    private android.widget.FrameLayout runBannerBox;  // 胶囊的不裁剪容器（涟漪可以溢出）
    // 常驻运行条：位置固定、不随消息滚动、不闪，是"还在跑"的最终判据
    // 运行秒表：贴在发送键正上方的一颗小胶囊，浮层不占布局
    private TextView runSec;
    private boolean runSecOn;
    private long runSecStart;
    // 一轮运行的三种收尾结局（原来是 RunBarView 里的常量，条删了以后搬到这里）
    private static final int RUN_MODE_DONE = 3;
    private static final int RUN_MODE_FAIL = 4;
    private static final int RUN_MODE_ABORT = 5;
    private View[] ripples;                            // 结束涟漪（两道错开发射）
    private ObjectAnimator flowAnim;
    private ObjectAnimator pulseAnim;
    private int flowRetry = 0;
    private LinearLayout statusRow;     // 顶部思考状态行（带胶囊底）
    private GradientDrawable statusRowBg;

    private final SimpleDateFormat dayFmt = new SimpleDateFormat("M月d日 HH:mm", Locale.CHINA);
    private final SimpleDateFormat sameDayFmt = new SimpleDateFormat("HH:mm", Locale.CHINA);

    // ---- 底栏与附加页面 ----
    private FrameLayout contentHost;
    private LinearLayout pageChat;
    private BottomBar bottomBar;
    private volatile TerminalPage terminalPage;
    private ProjectPage projectPage;
    private SandboxPage sandboxPage;
    private EnvPage envPage;
    private final View[] pageViews = new View[5];
    private int currentTab = BottomBar.TAB_CHAT;

    // ---- 左边缘右滑：拉出会话抽屉 ----
    private boolean edgeTracking;
    private float edgeDownX, edgeDownY;

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                edgeTracking = ev.getX() <= dp(22);
                edgeDownX = ev.getX();
                edgeDownY = ev.getY();
                break;
            case MotionEvent.ACTION_MOVE:
                if (edgeTracking) {
                    float dx = ev.getX() - edgeDownX;
                    float dy = Math.abs(ev.getY() - edgeDownY);
                    if (dy > dp(56) || dx < -dp(16)) {
                        edgeTracking = false;
                    } else if (dx > dp(44) && dx > dy * 1.6f) {
                        edgeTracking = false;
                        try {
                            View dv = getWindow().getDecorView();
                            dv.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        } catch (Throwable ignored) {
                        }
                        showConversationList();
                        return true;
                    }
                }
                break;
            default:
                edgeTracking = false;
                break;
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.nebula.aichat.ui.Palette.apply(this);   // 主题/强调色必须在 setContentView 之前
        setContentView(R.layout.activity_main);

        // 供 ShellRunner 在非 Activity 上下文中取到 LinuxEnv
        com.nebula.aichat.shell.ShellRunner.init(this);
        // 任务现场要落盘，进程被系统回收后「继续」才不会变成重新开荒
        com.nebula.aichat.net.ChatClient.attach(this);

        store = ChatStore.get(this);
        settings = Settings.load(this);
        refreshFont();

        // 后台预热技能缓存：首个消息发送时 buildInjection() 就不用再在主线程读一遍 SKILL.md
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    com.nebula.aichat.skill.SkillStore.ensureSamples(MainActivity.this);
                    com.nebula.aichat.skill.SkillStore.load(MainActivity.this);
                } catch (Throwable ignored) {
                }
            }
        }, "nebula-skills-warm").start();

        // 签名和构建记录对不上（被改包重签）就提示一次。自检在后台线程跑，
        // 这里只挂个回调，探测完才可能弹，不挡启动。
        com.nebula.aichat.util.Guard.whenReady(() -> runOnUiThread(
                () -> com.nebula.aichat.util.Guard.showTamperWarning(MainActivity.this)));

        tvTitle = findViewById(R.id.tvTitle);
        tvSub = findViewById(R.id.tvSub);
        tvBalance = findViewById(R.id.tvBalance);
        if (tvBalance != null) tvBalance.setOnClickListener(v -> showBalanceDetail());
        tvUsage = findViewById(R.id.tvUsage);
        tvUsageCount = findViewById(R.id.tvUsageCount);
        usageRow = findViewById(R.id.usageRow);
        ivModelIcon = findViewById(R.id.ivModelIcon);
        scroll = findViewById(R.id.scroll);
        if (scroll != null) {
            scroll.setOnScrollChangeListener(
                    (v, x, y, ox, oy) -> lastScrollAt = SystemClock.uptimeMillis());
        }
        chatContainer = findViewById(R.id.chatContainer);
        // 正文气泡靠负 margin 铺满到屏幕两侧，会溢出容器自身边界；
        // 不关裁剪的话那 1dp 描边会被切掉一半
        chatContainer.setClipChildren(false);
        setupQuickNav();
        etInput = findViewById(R.id.etInput);
        // 极端保护：粘贴几十万字符时 EditText 布局会直接卡死（ANR），
        // 这里硬性截断到上限的 4 倍；正常超长内容由 sendMessage 给出明确提示
        etInput.setFilters(new android.text.InputFilter[]{
                new android.text.InputFilter.LengthFilter(MAX_FILE_CHARS * 4)});
        btnSend = findViewById(R.id.btnSend);

        findViewById(R.id.btnMenu).setOnClickListener(v -> showConversationList());
        setupThinkBadge();
        // 顶栏右侧 = 模型选择器：点一下换模型，长按进完整设置。
        // 设置入口在模型面板底部也留了一份，不会因为图标换语义就找不到了。
        android.view.View btnModel = findViewById(R.id.btnSettings);
        btnModel.setOnClickListener(v -> showModelSheet());
        btnModel.setOnLongClickListener(v -> {
            startActivityForResult(new Intent(this, SettingsActivity.class), 100);
            return true;
        });
        btnSend.setOnClickListener(v -> {
            if (busy) {
                stopStreaming();
            } else {
                sendMessage();
            }
        });

        // 输入框上方功能栏
        composer = new ComposerBar(this, findViewById(R.id.composerBar),
                new ComposerBar.Listener() {
                    @Override
                    public void onPickFile() {
                        pickFile();
                    }

                    @Override
                    public void onPickImage() {
                        pickImage();
                    }

                    @Override
                    public void onSkill() {
                        com.nebula.aichat.skill.SkillPanel.show(MainActivity.this);
                    }

                    @Override
                    public void onMcp() {
                        com.nebula.aichat.mcp.McpPanel.show(MainActivity.this);
                    }

                    @Override
                    public void onBrowserToggle(boolean on) {
                        settings.browserEnabled = on;
                        settings.save(MainActivity.this);
                        // 工具列表按开关重建：关掉时模型不应再看到 web_fetch
                        tools = null;
                        Toast.makeText(MainActivity.this,
                                on ? "浏览器自动化已开启：AI 可以自己打开网页读正文"
                                        : "浏览器自动化已关闭", Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onNetMenu() {
                        showNetMenu();
                    }


                    @Override
                    public void onNetToggle(boolean on) {
                        settings.searchEnabled = on;
                        settings.save(MainActivity.this);
                        Toast.makeText(MainActivity.this,
                                on ? "联网已开启：发送前会先检索最新资料"
                                        : "联网已关闭", Toast.LENGTH_SHORT).show();
                    }
                }, settings.searchEnabled, settings.browserEnabled);

        // 点空白处收起键盘不抢焦点
        scroll.setOnTouchListener((v, e) -> {
            v.getParent().requestDisallowInterceptTouchEvent(false);
            return false;
        });

        if (store.isEmpty()) {
            current = store.create();
        } else {
            current = store.list().get(0);
        }
        renderConversation(current);

        // 首次启动且未配置密钥时，直接引导到设置页
        android.content.SharedPreferences sp = getSharedPreferences("nebula_first_run", MODE_PRIVATE);
        if (!settings.isConfigured() && !sp.getBoolean("done", false)) {
            sp.edit().putBoolean("done", true).apply();
            startActivityForResult(new Intent(this, SettingsActivity.class), 100);
        }

        initTabs();
        autoProvisionOnce();

        // 等首屏渲染完再弹权限框，避免和主题应用/列表刷新抢主线程
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                ensureNotifPermission();
            }
        }, 600L);
    }

    /**
     * 首启静默开箱：基础命令包（≈5MB）+ Java 运行时（≈60MB，反编译工具依赖）。
     *
     * 主要触发点是「Linux 环境装完」那一刻（见 InstallDialog.autoProvision），
     * 这里是兜底：万一安装过程中 App 被杀了，下次启动接着补。
     * 只做一次、失败不打扰，环境页随时可手动重试。
     */
    private void autoProvisionOnce() {
        final android.content.SharedPreferences sp = getSharedPreferences("nebula_first_run", MODE_PRIVATE);
        if (sp.getBoolean("auto_provision", false)) return;
        if (!com.nebula.aichat.linux.LinuxEnv.get(this).isInstalled()) return;
        sp.edit().putBoolean("auto_provision", true).apply();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (com.nebula.aichat.linux.Toolchain.running) return;
                    if (com.nebula.aichat.linux.Toolchain.coreReady(MainActivity.this)
                            && com.nebula.aichat.linux.Toolchain.jreReady(MainActivity.this)) {
                        return;
                    }
                    String err = com.nebula.aichat.linux.Toolchain.provision(MainActivity.this, null);
                    if (err == null) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this,
                                        "环境已就绪：常用命令 + jadx/apktool 都能用了",
                                        Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                } catch (Throwable ignored) {
                }
            }
        }, "nebula-provision").start();
    }

    /** 界面是否在前台可见：任务完成提醒只在后台发，前台不打扰 */
    private boolean fgVisible;

    @Override
    protected void onPause() {
        fgVisible = false;
        stopFaceFloat();
        if (android.os.Build.VERSION.SDK_INT >= 28) stopAnimatedFace();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        fgVisible = true;
        Settings fresh = Settings.load(this);
        // 主题/强调色是在设置页改的，回来必须整体重建才有新配色
        if (settings != null && (fresh.theme != settings.theme || fresh.accent != settings.accent)) {
            recreate();
            return;
        }
        boolean changed = !TextUtils.equals(fresh.model, settings.model)
                || !TextUtils.equals(fresh.baseUrl, settings.baseUrl);
        int oldSp = msgSp;
        settings = fresh;
        refreshFont();
        // 回车发送是设置页里随时能改的，回前台就重新应用一次输入框的键盘行为
        applyEnterSendPref();
        syncAliveService();
        updateSubtitle();
        applyChatBackground();
        applyAiFace();
        // 会话在别处被清空（设置页「清空全部会话」）→ 回到全新会话
        if (current != null && !busy && store.get(current.id) == null) {
            newConversation();
            return;
        }
        if (current != null && !busy) {
            if (msgSp != oldSp) {
                // 改了字号：整段重绘，让历史消息也用新字号
                renderConversation(current);
            } else if (changed && current.messages.isEmpty()) {
                renderConversation(current);
            }
        }
    }

    /** 聊天背景图（无图时保持纯色）。设置变化后重算，避免反复解码大图。 */
    private String bgAppliedKey = null;

    void applyChatBackground() {
        View sc = findViewById(R.id.scroll);
        if (sc == null) return;
        String name = settings == null || settings.chatBg == null ? "" : settings.chatBg.trim();
        int alpha = settings == null ? 0 : settings.chatBgAlpha;
        int dim = settings == null ? 0 : settings.chatBgDim;
        int theme = settings == null ? 0 : settings.theme;
        String key = name + "|" + alpha + "|" + dim + "|" + theme;
        if (key.equals(bgAppliedKey)) return;
        bgAppliedKey = key;

        if (name.isEmpty() || !com.nebula.aichat.ui.ChatBackground.exists(this, name)) {
            sc.setBackground(null);   // 透明 → 露出 root 的纯色背景
            return;
        }
        try {
            sc.setBackground(com.nebula.aichat.ui.ChatBackground.build(this, name, alpha, dim));
        } catch (Throwable t) {
            sc.setBackground(null);
        }
    }

    /**
     * AI 本体形象：按设置应用可见性、大小、位置、不透明度，并驱动「自动情绪」。
     *
     * 形象画在消息列表下层，气泡会盖住一部分——这是故意的：
     * 它该像背景里的活物，而不是一张挡在字前面的贴纸。
     */
    /** 形象图：内置图（assets）或用户自选图，二选一 */
    private android.widget.ImageView aiFaceImg;
    /** 内置形象图，打包在 assets 里；用户没自选图时就显示它 */
    private static final String FACE_ASSET = "ai_face_default.png";
    private android.graphics.Bitmap aiFaceBmp;
    private String aiFaceImgPath;
    /** 图片文件的 lastModified+size 指纹，文件换了就重新解码 */
    private long aiFaceImgStamp;
    private android.graphics.drawable.Drawable aiFaceDrawable;
    private android.animation.Animator aiFaceAnim;

    void applyAiFace() {
        aiFaceImg = findViewById(R.id.aiFaceImg);
        if (aiFaceImg == null || settings == null) return;
        String img = settings.aiFaceImage == null ? "" : settings.aiFaceImage.trim();
        boolean hasImg = !img.isEmpty()
                && new java.io.File(new java.io.File(getFilesDir(), "aiface"), img).exists();
        if (!settings.aiFaceShow) {
            aiFaceImg.setVisibility(View.GONE);
            stopFaceFloat();
            return;
        }
        aiFaceImg.setVisibility(View.VISIBLE);
        // 自选图优先，没有就用内置形象图
        showFaceImage(hasImg ? img : null);
    }

    /** 内置形象图：长边超过 1400 先降采样，别为一张图吃掉几 MB 内存 */
    private android.graphics.Bitmap decodeFaceAsset() {
        try {
            android.graphics.BitmapFactory.Options b = new android.graphics.BitmapFactory.Options();
            b.inJustDecodeBounds = true;
            java.io.InputStream in0 = getAssets().open(FACE_ASSET);
            android.graphics.BitmapFactory.decodeStream(in0, null, b);
            in0.close();
            int sample = 1;
            int longSide = Math.max(b.outWidth, b.outHeight);
            while (longSide / sample > 1400) sample *= 2;
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888;
            java.io.InputStream in = getAssets().open(FACE_ASSET);
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(in, null, o);
            in.close();
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 形象图：name 为 null 用内置图，否则用自选图；高度取"大小"设置，宽度按原图比例 */
    private void showFaceImage(String name) {
        boolean builtin = name == null;
        java.io.File f = builtin ? null
                : new java.io.File(new java.io.File(getFilesDir(), "aiface"), name);
        boolean anim = !builtin && name.endsWith("_anim") && android.os.Build.VERSION.SDK_INT >= 28;
        int srcW, srcH;
        // 文件被替换（清除后重选同名图）也要失效缓存，否则会一直显示旧图
        String key = builtin ? ("assets:" + FACE_ASSET) : f.getAbsolutePath();
        long stamp = builtin ? 0L : (f.lastModified() * 31L + f.length());
        boolean dirty = !key.equals(aiFaceImgPath) || stamp != aiFaceImgStamp;

        if (anim) {
            if (dirty) {
                releaseFaceDrawable();
                try {
                    android.graphics.ImageDecoder.Source src =
                            android.graphics.ImageDecoder.createSource(f);
                    android.graphics.drawable.Drawable dr =
                            android.graphics.ImageDecoder.decodeDrawable(src,
                                    (decoder, info, s) -> {
                                        int iw = info.getSize().getWidth();
                                        int ih = info.getSize().getHeight();
                                        int ls = Math.max(iw, ih);
                                        if (ls > 1200) {
                                            float k = 1200f / ls;
                                            decoder.setTargetSize(Math.max(1, (int) (iw * k)),
                                                    Math.max(1, (int) (ih * k)));
                                        }
                                    });
                    if (dr instanceof android.graphics.drawable.AnimatedImageDrawable) {
                        android.graphics.drawable.AnimatedImageDrawable ad =
                                (android.graphics.drawable.AnimatedImageDrawable) dr;
                        ad.setRepeatCount(android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE);
                        aiFaceImg.setImageDrawable(ad);
                        aiFaceDrawable = ad;
                        ad.start();
                    } else {
                        aiFaceImg.setImageDrawable(dr);
                        aiFaceDrawable = dr;
                    }
                    aiFaceImgPath = f.getAbsolutePath();
                    aiFaceImgStamp = stamp;
                } catch (Throwable t) {
                    aiFaceImgPath = null;
                }
            }
            android.graphics.drawable.Drawable dr = aiFaceImg.getDrawable();
            if (dr == null) {
                aiFaceImg.setVisibility(View.GONE);
                return;
            }
            // 从后台回来 / 重新应用设置时，动图要重新 start 才会继续播
            if (dr instanceof android.graphics.drawable.AnimatedImageDrawable) {
                try {
                    ((android.graphics.drawable.AnimatedImageDrawable) dr).start();
                } catch (Throwable ignored) {}
            }
            srcW = Math.max(1, dr.getIntrinsicWidth());
            srcH = Math.max(1, dr.getIntrinsicHeight());
        } else {
            if (dirty) {
                releaseFaceDrawable();
                aiFaceBmp = builtin ? decodeFaceAsset()
                        : android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath());
                aiFaceImgPath = aiFaceBmp == null ? null : key;
                aiFaceImgStamp = stamp;
            }
            if (aiFaceBmp == null || aiFaceBmp.isRecycled()) {
                aiFaceImg.setVisibility(View.GONE);
                return;
            }
            aiFaceImg.setImageBitmap(aiFaceBmp);
            srcW = aiFaceBmp.getWidth();
            srcH = aiFaceBmp.getHeight();
        }

        aiFaceImg.setAlpha(Math.max(0.2f, Math.min(1f, settings.aiFaceAlpha / 100f)));
        float d = getResources().getDisplayMetrics().density;
        int h = Math.max(1, (int) (settings.aiFaceSizeDp * d));
        int w = Math.max(1, (int) (h * (srcW / (float) srcH)));
        int maxW = (int) (getResources().getDisplayMetrics().widthPixels * 0.8f);
        if (w > maxW) {
            w = maxW;
            h = Math.max(1, (int) (w * srcH / (float) srcW));
        }
        int g = aiGravity(settings.aiFacePos);
        android.widget.FrameLayout.LayoutParams lp =
                (android.widget.FrameLayout.LayoutParams) aiFaceImg.getLayoutParams();
        if (lp.width != w || lp.height != h || lp.gravity != g) {
            lp.width = w;
            lp.height = h;
            lp.gravity = g;
            aiFaceImg.setLayoutParams(lp);
        }
        // 动图自己会动，再叠浮动就晃得厉害；只有静态图才加浮动
        if (anim) {
            stopFaceFloat();
        } else {
            startFaceFloat();
        }
    }

    /** 静态图也给它一点"活着"的感觉：上下轻浮 5dp + 1.5% 呼吸缩放 */
    private void startFaceFloat() {
        if (aiFaceAnim != null) return;
        final float amp = 5f * getResources().getDisplayMetrics().density;
        android.animation.ValueAnimator va =
                android.animation.ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
        va.setDuration(4200);
        va.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        va.setInterpolator(new android.view.animation.LinearInterpolator());
        va.addUpdateListener(a -> {
            if (aiFaceImg == null) return;
            float t = (Float) a.getAnimatedValue();
            aiFaceImg.setTranslationY(-amp * (float) Math.sin(t));
            float s = 1f + 0.015f * (float) Math.sin(t);
            aiFaceImg.setScaleX(s);
            aiFaceImg.setScaleY(s);
        });
        va.start();
        aiFaceAnim = va;
    }

    private void stopFaceFloat() {
        if (aiFaceAnim != null) {
            aiFaceAnim.cancel();
            aiFaceAnim = null;
        }
        if (aiFaceImg != null) {
            aiFaceImg.setTranslationY(0f);
            aiFaceImg.setScaleX(1f);
            aiFaceImg.setScaleY(1f);
        }
    }

    /** AnimatedImageDrawable 是 API 28 才有的类：低版本连 instanceof 都不安全，隔离到这个方法里 */
    @android.annotation.TargetApi(28)
    private void stopAnimatedFace() {
        if (aiFaceDrawable instanceof android.graphics.drawable.AnimatedImageDrawable) {
            try {
                ((android.graphics.drawable.AnimatedImageDrawable) aiFaceDrawable).stop();
            } catch (Throwable ignored) {}
        }
    }

    private void releaseFaceDrawable() {
        if (android.os.Build.VERSION.SDK_INT >= 28) stopAnimatedFace();
        aiFaceDrawable = null;
        if (aiFaceBmp != null && !aiFaceBmp.isRecycled()) aiFaceBmp.recycle();
        aiFaceBmp = null;
    }

    private static int aiGravity(int pos) {
        switch (pos) {
            case 0: return android.view.Gravity.LEFT | android.view.Gravity.TOP;
            case 1: return android.view.Gravity.CENTER_HORIZONTAL | android.view.Gravity.TOP;
            case 2: return android.view.Gravity.RIGHT | android.view.Gravity.TOP;
            case 3: return android.view.Gravity.LEFT | android.view.Gravity.CENTER_VERTICAL;
            case 5: return android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL;
            case 6: return android.view.Gravity.LEFT | android.view.Gravity.BOTTOM;
            case 7: return android.view.Gravity.CENTER_HORIZONTAL | android.view.Gravity.BOTTOM;
            case 8: return android.view.Gravity.RIGHT | android.view.Gravity.BOTTOM;
            default: return android.view.Gravity.CENTER;
        }
    }

    /** 把 AI 的终端执行过程镜像到终端页（终端页未创建时先缓存，创建后回填） */
    private void bindShellMirror() {
        ShellToolProvider.setMirror(new ShellToolProvider.Mirror() {
            @Override
            public void begin(final String cmd) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (terminalPage != null) terminalPage.aiBegin(cmd);
                    }
                });
            }

            @Override
            public void line(final String line) {
                // 输出行可能一秒几百条：直接进终端缓冲，由它自己合并刷新，
                // 这里再 runOnUiThread 就会往主线程队列塞上千个空转任务
                TerminalPage p = terminalPage;
                if (p != null) p.aiLineAsync(line);
            }

            @Override
            public void end(final int code) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (terminalPage != null) terminalPage.aiEnd(code);
                    }
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        client.cancel();
        try {
            stopBannerAnim();
            runUiHide();
            ui.removeCallbacks(keepAliveTick);
            KeepAliveService.stop(this);
        } catch (Throwable ignored) {
        }
        stopSpeak();
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
        super.onDestroy();
    }

    // ---------------- 渲染 ----------------

    /**
     * 顶栏徽章统一底色：模型名与余额共用同一套（深蓝底 + 亮蓝字 + 8dp 圆角 + 粗体）。
     *
     * <p>之前模型名是纯文字、余额是带底色的徽章，同一行里一边有块一边没有，
     * 看着像两个不相干的东西。现在统一成徽章。
     *
     * <p>未配置/未选模型是「等你处理」的状态，用暖色底区分开，
     * 不然和正常模型名长得一样，反而看不出需要动手。
     */
    private void styleSubChip(boolean warn) {
        if (tvSub == null) return;
        tvSub.setTextColor(warn ? Palette.c(0xFFFFB38A) : Palette.c(0xFF9DC0FF));
        tvSub.setTypeface(Typeface.DEFAULT_BOLD);
        tvSub.setBackground(Ui.roundRect(this, warn ? Palette.c(0xFF43291E) : Palette.c(0xFF1B2A44), 0, 8f));
    }

    private void updateSubtitle() {
        if (!settings.isConfigured()) {
            if (ivModelIcon != null) ivModelIcon.setVisibility(View.GONE);
            tvSub.setText("未配置 API Key · 点右上角设置");
            styleSubChip(true);
        } else {
            // 顶栏显示真正会发出去的模型名
            String eff = Settings.baseModel(settings);
            if (eff.isEmpty()) {
                // 没有内置候选了：Key 填了但还没拉取过，顶栏直接说清楚下一步
                tvSub.setText("未选模型 · 点右侧图标拉取");
                styleSubChip(true);
                if (ivModelIcon != null) ivModelIcon.setVisibility(View.GONE);
            } else {
                tvSub.setText(eff);
                styleSubChip(false);
                // 只有 DeepSeek 系列模型才显示品牌小图标，换到其它厂商模型时自动隐藏
                String m = eff.toLowerCase(Locale.ROOT);
                if (ivModelIcon != null) {
                    ivModelIcon.setVisibility(m.contains("deepseek") ? View.VISIBLE : View.GONE);
                }
            }
        }
        // 顶栏是同步的集中出口：配置变化、切回前台都会走到这里，顺带刷新余额（内部有节流）
        syncBalance(false);
        // 回到会话就有数，不用等发一条消息才看到占用
        if (current != null && current.messages != null && !current.messages.isEmpty()) {
            refreshUsage(current.messages);
        } else if (usageRow != null) {
            usageRow.setVisibility(View.GONE);
        }
        fitSubtitle();
    }

    /**
     * 让模型名与余额各让一步：模型名最多占「这一行减去图标和余额之后」的宽度，超了就省略号。
     * 不写死 dp，换长模型名或余额位数变多时都不会重叠、也不会被挤出屏幕。
     */
    private void fitSubtitle() {
        if (tvSub == null || tvBalance == null) return;
        final View row = (View) tvSub.getParent();
        if (row == null) return;
        if (row.getWidth() > 0) {
            applySubtitleFit(row.getWidth());
        } else {
            // 首帧还没量到宽度，等一次布局再算
            row.post(() -> {
                if (row.getWidth() > 0) applySubtitleFit(row.getWidth());
            });
        }
    }

    private void applySubtitleFit(int rowW) {
        int used = 0;
        if (ivModelIcon != null && ivModelIcon.getVisibility() == View.VISIBLE) {
            used += ivModelIcon.getWidth() + dp(4);
        }
        if (tvBalance.getVisibility() == View.VISIBLE) {
            tvBalance.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            used += tvBalance.getMeasuredWidth() + dp(7);
        }
        int avail = rowW - used;
        // TextView 的 maxWidth 含自身 padding，不扣掉会多出 12dp 把余额挤出去
        avail -= tvSub.getPaddingStart() + tvSub.getPaddingEnd();
        if (avail < dp(36)) avail = (int) dp(36);
        if (tvSub.getMaxWidth() != avail) {
            tvSub.setMaxWidth(avail);
            tvSub.requestLayout();
        }
    }

    /**
     * 顶栏余额：只有 baseUrl 指向 DeepSeek 官方端点时才显示。
     * 任何失败都静默处理（保留旧值或保持隐藏），绝不打断聊天。
     */
    private void syncBalance(boolean force) {
        if (tvBalance == null) return;
        if (!BalanceClient.isSupported(settings)) {
            tvBalance.setVisibility(View.GONE);
            balance = null;
            fitSubtitle();
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && balance != null && now - balanceAt < BALANCE_TTL) {
            applyBalance(balance);
            return;
        }
        // 先亮出旧值再后台更新，避免每次切前台数字闪一下
        if (balance != null) applyBalance(balance);
        else tvBalance.setVisibility(View.GONE);
        balanceAt = now;
        BalanceClient.fetch(settings, r -> {
            if (!r.ok) return;
            balance = r;
            balanceAt = System.currentTimeMillis();
            applyBalance(r);
        });
    }

    private void applyBalance(BalanceClient.Result r) {
        if (tvBalance == null || r == null) return;
        if (TextUtils.isEmpty(r.total)) {
            tvBalance.setVisibility(View.GONE);
            return;
        }
        // 徽章样式：钱袋图标 + 圆角底色，一眼能看到又不抢按钮的注意力
        boolean low = !r.available;
        if (!low) {
            try {
                double d = Double.parseDouble(r.total.replaceAll("[^0-9.]", ""));
                low = d < 1.0;
            } catch (Throwable ignored) {
            }
        }
        tvBalance.setText((low ? "💸 " : "💰 ") + r.total);
        tvBalance.setTextColor(low ? Palette.c(0xFFFFB38A) : Palette.c(0xFF9DC0FF));
        tvBalance.setTypeface(Typeface.DEFAULT_BOLD);
        tvBalance.setBackground(Ui.roundRect(this, low ? Palette.c(0xFF43291E) : Palette.c(0xFF1B2A44), 0, 8f));
        tvBalance.setVisibility(View.VISIBLE);
        fitSubtitle();
    }

    /** 点余额看明细；这里强制重查一次，保证看到的是当前值 */
    private void showBalanceDetail() {
        if (!BalanceClient.isSupported(settings)) {
            Toast.makeText(this, "只有 DeepSeek 官方端点才支持余额查询", Toast.LENGTH_SHORT).show();
            return;
        }
        BalanceClient.fetch(settings, r -> {
            if (!r.ok) {
                Toast.makeText(this, "查不到余额：" + r.raw, Toast.LENGTH_LONG).show();
                return;
            }
            balance = r;
            balanceAt = System.currentTimeMillis();
            applyBalance(r);
            StringBuilder sb = new StringBuilder();
            sb.append("账户状态：").append(r.available ? "可用" : "不可用（余额不足或被限制）").append("\n");
            sb.append("总余额：").append(r.total.isEmpty() ? "—" : r.total).append("\n");
            sb.append("充值余额：").append(r.topUp.isEmpty() ? "—" : r.topUp).append("\n");
            sb.append("赠送余额：").append(r.granted.isEmpty() ? "—" : r.granted);
            new NebAlert.Builder(MainActivity.this)
                    .setTitle("DeepSeek 账户余额")
                    .setMessage(sb.toString())
                    .setPositiveButton("关闭", null)
                    .setNeutralButton("刷新", (d, w) -> syncBalance(true))
                    .show();
        });
    }

    private void renderConversation(Conversation conv) {
        if (busy) {
            streamStale = true;
            client.cancel();
            busy = false;
            setSendState(false);
            roundActive = false;
            stopToolTicker();
            concludeRunBar(RUN_MODE_ABORT, "");
            streamContent = null;
            streamMessage = null;
            streamBuf = null;
            statusText = null;
            statusIcon = null;
            if (spinAnim != null) {
                spinAnim.cancel();
                spinAnim = null;
            }
            thinkDone = true;
        }
        current = conv;
        if (composer != null) composer.clearAttachments();
        chatContainer.removeAllViews();
        String t1 = Conversation.cleanTitle(conv.title);
        tvTitle.setText(t1.isEmpty() ? "AgenCode" : t1);
        updateSubtitle();

        // 换会话才重置虚拟化窗口；同一会话里「展开更早」时要保留已展开的进度
        if (conv.id == null || !conv.id.equals(historyConvId)) {
            historyConvId = conv.id;
            historyLimit = HISTORY_PAGE;
        }

        if (conv.messages.isEmpty()) {
            buildEmptyState();
        } else {
            // 只实例化末尾一段：长会话全量建 View 时，光一次绘制就能把主线程拖到 5 秒
            // （现场堆栈就是 ScrollView -> dispatchDraw 嵌套到底），这是卡死的主因
            int fromIndex = Math.max(0, conv.messages.size() - historyLimit);
            int hiddenCount = 0;
            for (int hi = 0; hi < fromIndex; hi++) {
                Message hm = conv.messages.get(hi);
                if (hm.content != null && !hm.content.trim().isEmpty()) hiddenCount++;
            }
            if (hiddenCount > 0) addEarlierRow(hiddenCount);
            for (int mi = fromIndex; mi < conv.messages.size(); mi++) {
                Message m = conv.messages.get(mi);
                if (m.content == null || m.content.trim().isEmpty()) continue;
                if (m.isUser()) {
                    addUserBubble(m);
                } else {
                    LinearLayout content = addAiBubble(m, false);
                    ensureTasks(m);
                    String body = cleanBody(m.content);
                    if (TASK_CARD_ENABLED && m.tasks != null && !m.tasks.isEmpty()) {
                        body = TaskParser.strip(body);
                    } else {
                        // 卡片关闭：清单留在正文，去掉点不动的复选框
                        body = TaskParser.uncheck(body);
                    }
                    if (hasPersistedTools(m.steps)) {
                        // 这轮跑过工具：按存下来的顺序把「文字 / 工具行」重新交错铺开
                        renderTimeline(content, m, body);
                        attachTasks(content, m);
                    } else {
                        // 这轮没跑工具：没有折叠头承载秒数，就把耗时写在思考块标题上
                        if (m.reasoning != null && !m.reasoning.trim().isEmpty()) {
                            content.addView(buildThinkBox(m.reasoning,
                                    m.thinkMs > 0 ? fmtSecs(m.thinkMs) : null));
                        }
                        MarkdownRenderer mr = new MarkdownRenderer(this, content, msgSp);
                        mr.setAutoCollapse(true);
                        mr.setOnConfirmClick(rows -> openConfirmSheet(rows));
                        mr.setOnConfirmSubmit(msg -> submitConfirmCard(msg));
                        mr.render(body);
                        // 结论在上、产物在下：先读结论，再按需去点文件
                        addConclusionCard(content, m, false);
                        attachArtifacts(content, body);
                        attachTasks(content, m);
                        attachLongPress(content, m);
                    }
                }
            }
        }
        if (scrollLockForExpand) {
            scrollLockForExpand = false;   // 展开更早的消息：位置由 expandHistory 自己恢复
        } else {
            scrollToBottom(true);
        }
        // 上一次任务是不是被系统杀掉的？是的话在顶部留一张说明卡片（只弹一次）
        maybeShowRunResidue();
        // 空会话：给几条点一下就填好的入口，省得新用户对着空屏猜这货能干什么
        if (!scrollLockForExpand && current != null
                && (current.messages == null || current.messages.isEmpty()) && !busy) {
            addEmptyGuide();
        }
    }

    /**
     * 上次 AI 任务是被系统直接杀掉的：启动时给一次说明，并给出续跑入口。
     *
     * 为什么不写进消息列表：那是"对话内容"，会被持久化、还会作为上下文发给模型。
     * 这条只是本地事件通知，塞进历史会污染下一轮输入，还会让模型以为自己说过这话。
     * 所以做成会话顶上的一张卡片，看完即弃（RunGuard.consume 已经删档，不会再弹）。
     */
    private void maybeShowRunResidue() {
        if (runResidueShown) return;
        runResidueShown = true;
        final com.nebula.aichat.util.RunGuard.Residue r =
                com.nebula.aichat.util.RunGuard.consume(this);
        if (r == null || chatContainer == null) return;

        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (12 * d);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(pad, pad, pad, pad);
        card.setBackground(com.nebula.aichat.ui.Ui.box(this,
                com.nebula.aichat.ui.Palette.surface(this),
                com.nebula.aichat.ui.Palette.accentSoft(this, 0x66), 14f, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        int side = (int) (10 * d);
        lp.setMargins(side, side, side, 0);
        card.setLayoutParams(lp);

        TextView head = new TextView(this);
        head.setText("上次任务被系统中断");
        head.setTextColor(com.nebula.aichat.ui.Palette.accent(this));
        head.setTextSize(13.5f);
        head.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(head);

        TextView body = new TextView(this);
        body.setText(r.describe());
        body.setTextColor(com.nebula.aichat.ui.Palette.text(this));
        body.setTextSize(12.5f);
        body.setLineSpacing(0, 1.3f);
        body.setPadding(0, (int) (5 * d), 0, 0);
        card.addView(body);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, (int) (9 * d), 0, 0);

        TextView retry = residueChip("继续这个任务");
        retry.setTextColor(com.nebula.aichat.ui.Palette.accent(this));
        retry.setOnClickListener(v -> {
            chatContainer.removeView(card);
            String title = r.title == null ? "" : r.title.trim();
            if (title.isEmpty()) {
                etInput.setText("刚才那个任务被系统中断了，重新做一遍：");
            } else {
                etInput.setText("继续上次没做完的任务：" + title);
            }
            etInput.setSelection(etInput.getText().length());
            etInput.requestFocus();
        });
        row.addView(retry);

        TextView dismiss = residueChip("知道了");
        dismiss.setOnClickListener(v -> chatContainer.removeView(card));
        row.addView(dismiss);
        card.addView(row);

        // 插在会话最上方：它是"上一轮"的事，压在最新消息下面反而看不到
        chatContainer.addView(card, 0);
    }

    private TextView residueChip(String text) {
        float d = getResources().getDisplayMetrics().density;
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12f);
        tv.setGravity(android.view.Gravity.CENTER);
        int ph = (int) (12 * d), pv = (int) (6 * d);
        tv.setPadding(ph, pv, ph, pv);
        tv.setTextColor(com.nebula.aichat.ui.Palette.dim(this));
        tv.setBackground(com.nebula.aichat.ui.Ui.box(this,
                com.nebula.aichat.ui.Palette.surface2(this),
                com.nebula.aichat.ui.Palette.stroke(this), 999f, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = (int) (8 * d);
        tv.setLayoutParams(lp);
        return tv;
    }

    /** 新会话的能力引导：点一下就把半成品问题填进输入框，用户接着补就行 */
    private void addEmptyGuide() {        try {
            String[][] items = {
                    {"\uD83D\uDCC4", "整理长文", "贴材料 → 要点 + 结论",
                            "把下面这段内容整理成要点，先给结论再列条目：\n"},
                    {"\uD83D\uDCBB", "写或改代码", "说目标 → 直接出能跑的代码", "帮我写一个"},
                    {"\uD83D\uDD0D", "联网查最新", "要带来源的最新信息", "联网查一下最近的"},
                    {"\uD83D\uDDC2", "看文件", "左下角附件传进来再问",
                            "我上传了一份文件，先确认它是什么，再告诉我能怎么处理"},
            };
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(4), dp(10), dp(4), dp(10));

            TextView head = new TextView(this);
            head.setText("直接说要做的事就行，也可以点下面的例子");
            head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            head.setTextColor(Palette.c(0xFF8A97A6));
            head.setPadding(dp(10), 0, dp(10), dp(10));
            box.addView(head);

            for (final String[] it : items) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(12), dp(11), dp(12), dp(11));
                row.setBackground(Ui.roundRect(this, Palette.c(0x0EFFFFFF), 0, 14f));

                TextView icon = new TextView(this);
                icon.setText(it[0]);
                icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
                row.addView(icon);

                LinearLayout col = new LinearLayout(this);
                col.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                clp.leftMargin = dp(10);
                col.setLayoutParams(clp);

                TextView title = new TextView(this);
                title.setText(it[1]);
                title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
                title.setTextColor(Palette.c(0xFFE6EDF3));
                title.setTypeface(Typeface.DEFAULT_BOLD);
                col.addView(title);

                TextView desc = new TextView(this);
                desc.setText(it[2]);
                desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
                desc.setTextColor(Palette.c(0xFF8A97A6));
                desc.setPadding(0, dp(2), 0, 0);
                col.addView(desc);
                row.addView(col);

                row.setOnClickListener(v -> {
                    etInput.setText(it[3]);
                    EditText e = etInput;
                    e.setSelection(e.getText().length());
                    e.requestFocus();
                    showKeyboard();
                });

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = dp(8);
                box.addView(row, lp);
            }
            chatContainer.addView(box, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } catch (Throwable e) {
            android.util.Log.w("nebula", "空会话引导渲染失败", e);
        }
    }

    /** 历史被折叠时顶部的「展开更早」入口 */
    private void addEarlierRow(int count) {
        try {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            TextView t = new TextView(this);
            t.setText("\u2191 更早的 " + count + " 条");
            t.setTextSize(11.5f);
            t.setTextColor(Palette.c(0xFF9AA7B4));
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(14), dp(6), dp(14), dp(6));
            t.setBackground(Ui.roundRect(this, Palette.c(0x0EFFFFFF), 0, 99f));
            row.addView(t);
            row.setOnClickListener(v -> expandHistory());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(2);
            lp.bottomMargin = dp(6);
            chatContainer.addView(row, lp);
        } catch (Throwable e) {
            android.util.Log.w("nebula", "更早消息入口渲染失败", e);
        }
    }

    /** 再放一页历史出来，并让原本看得见的内容停在原地 */
    private void expandHistory() {
        if (current == null || busy) return;
        final int oldY = scroll.getScrollY();
        final int oldH = Math.max(1, chatContainer.getHeight());
        historyLimit += HISTORY_PAGE;
        scrollLockForExpand = true;
        renderConversation(current);
        scroll.post(() -> {
            int newH = Math.max(1, chatContainer.getHeight());
            scroll.scrollTo(0, Math.max(0, oldY + (newH - oldH)));
        });
    }

    /**
     * 把回答里的「- [ ] 步骤」解析成任务清单，回填到消息上。
     * 已经解析过（历史里存了 tasks）就不再重复解析，避免覆盖用户勾选出来的进度。
     */
    private void ensureTasks(Message m) {
        if (!TASK_CARD_ENABLED || m == null) return;
        if (m.tasks != null && !m.tasks.isEmpty()) return;
        try {
            java.util.List<String> t = TaskParser.parse(m.content);
            if (t != null && !t.isEmpty()) {
                m.tasks = t;
                m.taskDone = null;
            }
        } catch (Throwable e) {
            android.util.Log.w("nebula", "解析任务失败", e);
        }
    }

    /**
     * 在正文<b>上方</b>挂上可勾选的执行步骤卡。
     * 步骤是这轮要做的事的索引，读者先看到它再往下看「怎么做的」，
     * 所以插在 index 0，而不是像以前那样追加在结尾。
     */
    private void attachTasks(LinearLayout content, Message m) {
        if (!TASK_CARD_ENABLED || content == null || m == null) return;
        if (m.tasks == null || m.tasks.isEmpty()) return;
        try {
            // 流式中已经立过卡、且还挂在同一个气泡上：沿用那张，别再加第二张
            if (streamTaskCard != null && streamTaskCard.getParent() == content) {
                m.taskDone = streamTaskCard.snapshot();
                return;
            }
            TaskCardView card = new TaskCardView(this, m.tasks, m.taskDone, (idx, done) -> {
                try {
                    m.toggleTask(idx);
                    if (store != null) store.save();
                    com.nebula.aichat.util.CrashLog.d("task", "勾选第 " + (idx + 1)
                            + " 步 done=" + done + " 进度=" + m.taskDoneCount() + "/" + m.tasks.size());
                } catch (Throwable e) {
                    android.util.Log.e("nebula", "保存任务进度失败", e);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Ui.dp(this, 10);
            content.addView(card, 0, lp);
            streamTaskCard = card;
        } catch (Throwable e) {
            android.util.Log.e("nebula", "任务卡渲染失败", e);
        }
    }

    /** 存下来的时间线里有没有工具调用（只有文字的不必走交错渲染） */
    private static boolean hasPersistedTools(java.util.List<Message.Step> steps) {
        if (steps == null) return false;
        for (Message.Step st : steps) {
            if (st != null && st.isTool()) return true;
        }
        return false;
    }

    /**
     * 重开会话时按时间线还原过程：思考段、工具行、正文段按当时的顺序铺开。
     *
     * <p>思考段和工具行收进同一个"过程流"容器，容器内部严格按发生顺序排列，
     * 于是重开会话看到的就是 调用 → 该次调用后的思考 → 下一次调用 的交替结构，
     * 与实时界面同一套观感。正文段仍排在过程之后（实时也是过程在上、回答在下）。</p>
     */
    private void renderTimeline(LinearLayout host, Message m, String body) {
        boolean hasThinkSeg = false;
        if (m.steps != null) {
            for (Message.Step st : m.steps) {
                if (st != null && st.isThink()) {
                    hasThinkSeg = true;
                    break;
                }
            }
        }
        LinearLayout procHost = null;
        if (!hasThinkSeg && m.reasoning != null && !m.reasoning.trim().isEmpty()) {
            // 老记录：整轮 reasoning 只存了一坨，没有分段信息，只能整体显示
            procHost = newProcHost(host);
            procHost.addView(buildThinkBox(m.reasoning, null));
        }
        int calls = 0;
        for (Message.Step st : m.steps) {
            if (st == null) continue;
            if (st.isTool()) {
                if (procHost == null) procHost = newProcHost(host);
                calls++;
                // 每次调用各自一个框，点行尾箭头展开它自己的详情
                LinearLayout wrap = makeToolWrap(procHost);
                wrap.setTag(st);
                TextView row = toolBodyOf(wrap);
                if (row != null) {
                    fillToolRow(row, st.ok ? ST_OK : ST_FAIL,
                            st.text == null ? "" : st.text, null, st.res, st.ms);
                    updateToolExplain(row, toolExplain(st.text, st.arg, st.res, st.ok),
                            st.ok ? Palette.c(C_TEXT2) : Palette.c(C_FAIL));
                    // 重开会话时耗时也从 Step.ms 还原，否则历史里这一行会凭空少一块信息
                    setExplainMsOnRow(row, st.ms, st.ok ? ST_OK : ST_FAIL);
                }
            } else if (st.isThink()) {
                if (st.text == null || st.text.trim().isEmpty()) continue;
                if (procHost == null) procHost = newProcHost(host);
                // 这一段自己带耗时，标题直接写自己的，不写整轮的
                procHost.addView(buildThinkBox(st.text, st.ms > 0 ? fmtSecs(st.ms) : null));
            } else if (st.text != null && !st.text.trim().isEmpty()) {
                LinearLayout seg = new LinearLayout(this);
                seg.setOrientation(LinearLayout.VERTICAL);
                host.addView(seg, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                MarkdownRenderer mr = new MarkdownRenderer(this, seg, msgSp);
                // 与实时渲染保持一致：分段正文不做整篇折叠（整块渲染的纯文字回答仍折叠）
                mr.setAutoCollapse(false);
                mr.setOnConfirmClick(rows -> openConfirmSheet(rows));
                mr.setOnConfirmSubmit(msg -> submitConfirmCard(msg));
                // 存下来的是原文，可能还带着清单行；清单已渲染成顶部步骤卡，这里剥掉
                mr.render(stripListLines(st.text));
                // 历史回放：代码块只露预览，点开看全文
                mr.collapseCodeBlocks();
            }
        }
        // 有分段思考时秒数已经写在每段自己头上；老记录才需要补整轮耗时
        if (procHost != null && !hasThinkSeg) putThinkSecs(host, m.thinkMs);
        addConclusionCard(host, m, false);
        attachArtifacts(host, body);
        attachLongPress(host, m);
    }

    /** 过程流容器：第一次需要时挂在当前位置，后续按发生顺序往里追加 */
    private LinearLayout newProcHost(LinearLayout host) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        host.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    /**
     * 流式过程中：模型一旦把清单写出来，立刻在气泡顶部立起步骤卡。
     * 只建一次，建过就不再解析——否则每个渲染帧都要跑一遍正则。
     */
    private void maybeBuildTaskCard(String full) {
        if (!TASK_CARD_ENABLED) return;
        if (streamContent == null || streamStale || streamTaskCard != null) return;
        if (full == null || full.indexOf('[') < 0) return;
        try {
            java.util.List<String> ts = TaskParser.parse(full);
            if (ts == null || ts.isEmpty()) return;
            TaskCardView card = new TaskCardView(this, ts, null, (idx, done) -> {
                if (streamMessage == null) return;
                ensureTasks(streamMessage);
                streamMessage.taskDone = streamTaskCard == null ? null : streamTaskCard.snapshot();
                if (store != null) store.save();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Ui.dp(this, 10);
            streamContent.addView(card, 0, lp);
            streamTaskCard = card;
            com.nebula.aichat.util.CrashLog.d("task", "流式立卡 " + ts.size() + " 步");
        } catch (Throwable e) {
            android.util.Log.w("nebula", "流式建任务卡失败", e);
        }
    }

    /**
     * 工具调用结束后推进步骤进度：勾掉下一个未完成项。
     * 只在成功时勾——失败的那一步显然没做完，留着让用户看得见。
     */
    private void tickTaskProgress(boolean ok) {
        if (!ok || streamTaskCard == null) return;
        try {
            if (!streamTaskCard.tickNext()) return;
            if (streamMessage != null) streamMessage.taskDone = streamTaskCard.snapshot();
            com.nebula.aichat.util.CrashLog.d("task", "自动完成一步 "
                    + streamTaskCard.doneCount() + "/" + streamTaskCard.size());
        } catch (Throwable e) {
            android.util.Log.w("nebula", "推进任务进度失败", e);
        }
    }

    /**
     * 流式剥离清单行：只按行过滤，不做 trim、不做空行聚合。
     * 增量渲染靠长度游标推进，文本长度要是忽长忽短就会漏字或重字；
     * 末尾正在输出的半截清单行（"‐ [ ]" 还没写完）也先按住不渲染，
     * 免得正文里闪一下再消失。
     */
    private static String stripListLines(String s) {
        // 卡片关闭时不再剥清单行：正文里直接按列表显示，信息不丢
        if (!TASK_CARD_ENABLED) return s;
        if (s == null || s.isEmpty() || s.indexOf('[') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        int n = s.length();
        int start = 0;
        while (start <= n) {
            int nl = s.indexOf('\n', start);
            boolean tail = nl < 0;
            String line = tail ? s.substring(start) : s.substring(start, nl);
            boolean drop = TaskParser.parse(line) != null || (tail && looksHalfTaskLine(line));
            if (!drop) {
                sb.append(line);
                if (!tail) sb.append('\n');
            }
            if (tail) break;
            start = nl + 1;
        }
        return sb.toString();
    }

    /** 形如 "- [" / "- [ ]" / "1. [" 这种还没写完的清单行 */
    private static boolean looksHalfTaskLine(String line) {
        String t = line == null ? "" : line.trim();
        if (t.isEmpty()) return false;
        t = t.replaceFirst("^(?:[-*+]|\\d{1,3}[.)])\\s*", "");
        return t.startsWith("[");
    }

    /**
     * 在气泡最结尾挂上结论卡：每轮回答一块，用主题强调色做渐变。
     * animate 只给「刚生成完」的那一条用——历史里几十条同时淡入既费性能也没意义。
     */
    private void addConclusionCard(LinearLayout host, Message m, boolean animate) {
        if (host == null || m == null) return;
        String title = m.conclusionTitle;
        if (title == null || title.trim().isEmpty()) return;
        try {
            ConclusionCard card = new ConclusionCard(this, title.trim(), m.conclusionDetail);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(10);
            host.addView(card, lp);
            if (animate) {
                // 顶部线从左扫满一次：这就是「本轮结束」的那一下信号。
                // 文字本身不再淡入——正文已经在那儿了，再淡一次会显得整块在闪。
                card.playSweep();
            }
        } catch (Throwable t) {
            com.nebula.aichat.util.CrashLog.d("ui", "结论卡渲染失败 " + t);
        }
    }

    private void buildEmptyState() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int padTop = dp(70);
        box.setPadding(dp(12), padTop, dp(12), dp(20));

        TextView spark = new TextView(this);
        spark.setText("✦");
        spark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f);
        spark.setTextColor(Palette.accent(this));
        spark.setGravity(Gravity.CENTER);
        box.addView(spark);

        TextView title = new TextView(this);
        title.setText("AgenCode");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Palette.text(this));
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(10);
        box.addView(title, tlp);

        TextView desc = new TextView(this);
        desc.setText("写代码、改文档、解释概念、做计划——直接说你的需求。");
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        desc.setTextColor(Palette.dim(this));
        desc.setGravity(Gravity.CENTER);
        desc.setLineSpacing(dp(3), 1.3f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(8);
        box.addView(desc, dlp);

        String[] hints = {
                "帮我写一段 Python 批量重命名文件",
                "解释一下 RAG 是什么，有什么优缺点",
                "把这段话改得更简洁专业"
        };
        for (String h : hints) {
            TextView chip = new TextView(this);
            chip.setText(h);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            chip.setTextColor(Palette.text(this));
            chip.setBackgroundResource(R.drawable.card_bg);
            chip.setPadding(dp(14), dp(10), dp(14), dp(10));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.topMargin = dp(10);
            chip.setOnClickListener(v -> {
                etInput.setText(h);
                etInput.setSelection(h.length());
                sendMessage();
            });
            box.addView(chip, clp);
        }

        chatContainer.addView(box);
    }

    /** 浏览器自动化开启时追加：告诉 AI 它有「打开网页读正文」这个工具 */
    private static final String WEB_HINT =
            "【浏览器自动化】已开启，你可以调用 web_fetch 打开具体网页读正文（相当于无界面浏览器）：\n"
            + "1. 搜索摘要不够时（需要原文细节、数据、接口返回），先拿到候选链接，再对最相关的 1-3 个链接调用 web_fetch。\n"
            + "2. 不要一次打开很多链接，也不要对同一链接反复取；先看正文够不够，不够再换来源。\n"
            + "3. 需要页面里的下一层链接时，用 web_fetch 的 links=true 取链接列表。\n"
            + "4. 页面要登录或依赖 JS 渲染时取不到内容，直接说明并换来源，不要硬试。\n"
            + "5. 引用网页内容时给出链接；没读到的不要写成读到了。";

    /**
     * 输出风格约束，始终拼在系统提示最前面。
     * 目的是让回答像人话：先讲结论、不堆代码、不写自我解析的旁白。
     */
    private static final String STYLE_HINT =
            "【输出风格】\n"
            + "1. 先用一两句大白话直接回答，再按需补充细节。\n"
            + "2. 除非用户明确要代码或命令，否则不要输出代码块。\n"
            + "3. 必须给代码时，先说这段代码要解决什么（一句话），再给能跑的最短版本，"
            + "代码块后面紧跟一两句说明它做了什么、要注意什么。\n"
            + "4. 需要给多段代码时，按「说明 → 代码 → 说明 → 代码」交替推进，"
            + "每段代码只做一件事，不要把多段代码连续堆在一起再统一解释。\n"
            + "5. 不要写“解析”“分析过程”“让我想想”这类旁白，直接给答案。\n"
            + "6. 段落要短，能用小标题和要点就用，避免大段密集文字。";

    /**
     * 任务清单协议。
     *
     * App 会把「- [ ] 步骤」这类行从正文里剥出来，渲染成可勾选的执行步骤卡片
     * （见 TaskCard / TaskParser）。所以这里明确要求模型：只要涉及「要改什么、怎么改」，
     * 就必须先给清单，而不是写成一整段散文——用户才能一步步打勾、看到进度。
     */
    /**
     * 效率纪律：长任务真正的成本是「工具轮次 × 每轮重发的历史」，
     * 少跑几轮远比调小上下文省钱，也不会因为来回确认把任务拖散。
     */
    private static final String ECONOMY_HINT =
            "【做事效率】\n"
            + "1. 能用一次调用做完的，别拆成好几次：多个命令合成一条（a && b），读文件一次读够范围；\n"
            + "2. 同一个文件、同一条命令不要重复读/重复跑；前面的结果就在上下文里，回头看一眼即可；\n"
            + "3. 动手前先定位：用 ls / grep / find 找到目标，别整文件通读；大文件只读相关片段；\n"
            + "4. 不要把工具输出整段复述进回答，只说结论和下一步；\n"
            + "5. 已经验证过的事不要为了保险再验证一遍。";

    private static final String TASK_HINT =
            "【执行步骤清单】\n"
            + "只要这轮回答涉及「要做哪些改动 / 按什么顺序做」（改代码、修 bug、配置环境、"
            + "部署发布等多步骤的事），必须在回答里给出一个步骤清单，用 Markdown 任务列表写：\n"
            + "- [ ] 第一步具体做什么\n"
            + "- [ ] 第二步具体做什么\n"
            + "规则：\n"
            + "1. 每步一句话说清「改什么 + 怎么改」，能落地，不要写「优化一下」「处理问题」这种空话；\n"
            + "2. 按执行顺序排，单步不要合并成一大条，3～8 步为宜；\n"
            + "3. 清单只写步骤本身，不要在这些行里塞代码块或长解释，解释放在清单外的正文里；\n"
            + "4. 已经做完的步骤标成 - [x]，还没做的保持 - [ ]；\n"
            + "5. 纯问答、闲聊、单步就能答完的事不要硬凑清单。\n"
            + "（App 会把清单渲染成可勾选的步骤卡，所以正文里不要再重复列一遍这些步骤。）";

    /**
     * 确认块协议说明。模型需要用户拍板时输出 %%CONFIRM%% 块，
     * App 会把它渲染成卡片并弹出可勾选面板，用户点确认后选择自动发回。
     */
    private static final String CONFIRM_HINT =
            "【需要用户拍板时】\n"
            + "遇到必须由用户选择才能继续的情况（改哪个功能、保留哪些模块、目标包名、"
            + "签名方式、要不要继续下一步等），不要用普通文字问一句就结束，必须输出确认块：\n"
            + "%%CONFIRM%%\n"
            + "单选|要改哪个功能？|设置页|聊天页|启动图\n"
            + "多选|保留哪些模块？|登录|支付|广告\n"
            + "文本|包名改成什么？\n"
            + "%%END%%\n"
            + "规则：每行一项，格式是「模式|问题|选项1|选项2…」，模式只能是 单选/多选/文本；"
            + "选择题至少 2 个选项，填空项不写选项；一次最多 5 项，问题一行说清，"
            + "选项每个不超过 10 个字；\n"
            + "块外只写一句引导语（例如「有几件事需要你定一下：」），"
            + "不要在正文里重复列这些选项；\n"
            + "用户确认后会自动收到「【我的确认】…」这样的消息：必须立刻调用工具开始执行，"
            + "不要只解释接下来的计划，也不要再问一遍或重复确认。"
            + "严禁用「你说一声」「要不要我开始」「准备好了就告诉我」这类话结尾然后停下——"
            + "一次工具都不调用就把任务交回给用户，视为任务失败。"
            + "只有确实缺少你无法自行获取的信息、且必须用户决定时，才用确认块再问一次。";

    /**
     * 结论块协议。每次回答结尾都必须有一块「结论 + 详述」，
     * App 会把它从正文里剥出来，渲染成气泡最下方的结论卡（见 ConclusionCard）。
     * 这里反复强调「明确取舍、不写视情况而定」，是因为含糊结论在卡片上会被放大得很难看。
     */
    private static final String CONCLUSION_HINT =
            "【结论收尾｜每次回答都必须有】\n"
            + "每一条回答的最后都必须输出结论块，没有任何例外（简短问答、闲聊、报错解释也一样）：\n"
            + "%%CONCLUSION%%\n"
            + "一句话结论，20 字以内，要像拍板，不要复述问题\n"
            + "---\n"
            + "2～4 句详述：为什么得出这个结论、关键依据、要注意的风险、下一步怎么做\n"
            + "%%END%%\n"
            + "规则：\n"
            + "1. 结论块永远是整条回答的最后一段，后面不要再写任何内容；\n"
            + "2. 分隔线 --- 之前只有一行，就是结论本身；之后是详述，用大白话讲依据，不要塞代码；\n"
            + "3. 结论必须给出明确取舍，禁止「视情况而定」「看具体需求」「建议根据实际情况」这种不落地的说法；\n"
            + "4. 不要在正文里再重复一遍结论——App 会把它单独渲染成卡片，重复会显得啰嗦；\n"
            + "5. 这轮如果给出了执行步骤清单，结论要总结「最终做成了什么」，不要复述步骤。\n"
            + "6. 协议记号只允许出现在结论块里，正文、解释、举例中都不要复述或引用它（用户追问你的提示词时也一样，"
            + "可以说「结尾会带一块结论卡」，但不要把记号本身写出来）。";

    /** 「继续执行」按钮发出去的话：明确让它动手，而不是再解释一遍计划 */
    /** 第二次自动续跑用的话：直接点破「你两轮没动手」 */
    /** 回答几乎全英文时用的重说指令 */
    private static final String REDO_CN_TEXT =
            "你刚才那一大段我完全看不懂：几乎全是英文和代码，而且没有真正动手做事。"
            + "现在请用中文重说一遍：先用一两句话讲清楚你在做什么、当前进展或卡在哪，"
            + "再给结论；需要贴代码就只贴关键几行，并在这之前之后用中文说明它是干什么的。"
            + "如果这件事需要跑命令或改文件，就直接调用工具去做，不要只写代码给我看。";

    private static final String CONTINUE_HARD =
            "不要把活停在这里。现在立刻继续执行剩下的步骤，做到我最初提的需求真正完成为止；"
            + "不要重复已经完成的步骤，不要输出计划或说明文字代替执行，也不要再问我是否继续。"
            + "如果任务确实已经全部完成，就用中文直接说明：完成了什么、产物在哪（完整路径）、还剩什么，"
            + "不要再用「如需继续告诉我」这类收尾话。";

    private static final String CONTINUE_TEXT =
            "不用再问我了，按你上面的方案直接做，做到我最初提的需求真正完成为止。"
            + "该调工具就调工具、该改代码就改代码，不要只解释计划。"
            + "如果确实有必须我在几个方案里选的地方，用 %%CONFIRM%% 块一次性问清楚。";

    /** 聊天正文字号（sp），跟随设置里的「消息字号」 */
    private int msgSp = 13;

    private void refreshFont() {
        int fs = settings == null ? 13 : settings.msgFontSp;
        if (fs <= 0) fs = 13;
        msgSp = Math.max(12, Math.min(24, fs));
    }

    /** 用户气泡内的联网标记（渲染成小胶囊，不再占一整行文字） */
    private static final String NET_MARK = "\n\uFFFCnet";

    /**
     * 清掉消息正文里的内部标记：
     * \uFFFCnet 是联网提示用的隐藏记号，\uFFFC 在缺字形时会被画成方框，
     * 所以显示、复制、取标题之前都要先剥掉，且不限于出现在末尾。
     */
    private static String cleanBody(String raw) {
        if (raw == null) return "";
        String s = raw.replace(NET_MARK, "")
                .replace("\uFFFCnet", "")
                .replace("\uFFFC", "")
                .replace("\u200b", "");
        // 结论协议标记必须在这里清一次：历史消息是原文落盘的，
        // 早期版本中止/异常那几轮没剥干净，重进会话就会看到 %%CONCLUSION%%
        s = ConclusionParser.stripAll(s);
        s = s.replaceAll("[ \t]+\n", "\n").replaceAll("\n{3,}", "\n\n");
        return s.trim();
    }
    /** 本轮请求的起始时间与是否联网，用于回答下方的元信息胶囊 */
    private long reqStartAt = 0L;
    /** 整轮是否还活着：工具全跑完、模型在思考下一步，这段也必须继续读秒 */
    private boolean roundActive = false;
    private boolean reqNet = false;

    private void addUserBubble(Message m) {
        FrameLayout row = new FrameLayout(this);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(14);
        row.setLayoutParams(rowLp);

        String rawBody = m.content == null ? "" : m.content;
        boolean net = rawBody.contains(NET_MARK);
        String body = cleanBody(rawBody);

        // 气泡容器：文字 + 底部小标签，整体一个气泡
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.bubble_user);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));

        TextView tv = new TextView(this);
        tv.setText(body);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, msgSp);
        tv.setTextColor(Palette.c(0xFFFFFFFF));
        tv.setLineSpacing(dp(5), 1.5f);
        tv.setTextIsSelectable(false);
        box.addView(tv);

        if (net && body != null && body.isEmpty()) tv.setVisibility(View.GONE);


        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.END;
        // 外层容器内边距归零后（正文要铺满屏宽），用户气泡得自己留出右边距
        blp.rightMargin = dp(10);
        blp.leftMargin = dp(48);
        blp.leftMargin = dp(76);   // 左侧留白，让气泡靠右、不顶到字
        row.addView(box, blp);

        attachLongPress(box, m);
        chatContainer.addView(row);
    }

    /** 产物扩展名（小写）；没有扩展名返回空串 */
    private static String extOf(String path) {
        int d = path.lastIndexOf('.');
        return d > 0 ? path.substring(d + 1).toLowerCase(Locale.US) : "";
    }

    /**
     * 产物类型 → 矢量图标。
     * 原来用 emoji（📦 🖼️ 🎬 🗜️）：不同字体渲染出来胖瘦不一、在深色底上自带一圈彩色，
     * 更关键的是跟工具行那套细线图标完全不是一家人——同一屏里两种图形语言。
     * 现在统一成和工具行同源的 stroke 图标，只靠 setColorFilter 区分主次与类型。
     */
    private static int artifactIcon(String path) {
        switch (extOf(path)) {
            case "apk": return R.drawable.ic_call_apk;
            case "png": case "jpg": case "jpeg": case "webp": case "gif":
                return R.drawable.ic_art_image;
            case "mp4": case "webm": case "mov": return R.drawable.ic_art_video;
            case "mp3": case "wav": case "m4a": case "aac": return R.drawable.ic_art_audio;
            case "zip": case "tar": case "gz": case "7z": return R.drawable.ic_art_zip;
            case "html": case "htm": return R.drawable.ic_call_web;
            case "py": case "sh": case "java": case "kt": case "kts":
            case "json": case "xml": case "yaml": case "yml":
                return R.drawable.ic_art_code;
            default: return R.drawable.ic_call_file;
        }
    }

    /** 产物类型 → 中文说明 */
    private static String artifactLabel(String path) {
        switch (extOf(path)) {
            case "apk": return "Android 安装包";
            case "png": case "jpg": case "jpeg": case "webp": case "gif": return "图片";
            case "mp4": case "webm": case "mov": return "视频";
            case "mp3": case "wav": case "m4a": case "aac": return "音频";
            case "zip": case "tar": case "gz": case "7z": return "压缩包";
            case "json": return "JSON 数据";
            case "md": return "Markdown 文档";
            case "txt": case "log": return "文本文件";
            case "html": case "htm": return "网页";
            case "py": return "Python 脚本";
            case "sh": return "Shell 脚本";
            case "java": return "Java 源码";
            case "xml": return "XML 配置";
            default: return "文件";
        }
    }

    /** 未安装 APK 的"脸"：真实 launcher 图标 + 应用名 + 包名版本 */
    private static final class ApkFace {
        android.graphics.drawable.Drawable icon;
        String label;
        String pkg;
        String version;
    }

    /** 同一文件解析一次就够：读 resources.arsc 再解位图要几百毫秒，卡片一滚动就重解会卡顿 */
    private final java.util.HashMap<String, ApkFace> apkFaces = new java.util.HashMap<>();

    /**
     * 读未安装 APK 的应用身份（图标 + 名称 + 包名版本）。
     * <p>
     * 关键点：调 loadIcon / loadLabel 之前，必须把 {@code applicationInfo.sourceDir}
     * 和 {@code publicSourceDir} 指向 APK 文件本身。这两个字段是给"已安装应用"填的，
     * 直接对 getPackageArchiveInfo 的结果调 loadIcon，PackageManager 找不到资源，
     * 只会回一个系统默认的绿机器人图标，名字也退化成包名——那就跟安装后看到的完全不是一回事。
     */
    private ApkFace apkFace(java.io.File f) {
        if (f == null) return new ApkFace();
        String key = f.getAbsolutePath() + "|" + f.length() + "|" + f.lastModified();
        ApkFace hit = apkFaces.get(key);
        if (hit != null) return hit;
        ApkFace face = new ApkFace();
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.pm.PackageInfo pi = pm.getPackageArchiveInfo(
                    f.getAbsolutePath(), android.content.pm.PackageManager.GET_META_DATA);
            if (pi != null) {
                face.pkg = pi.packageName == null ? "" : pi.packageName;
                face.version = pi.versionName == null ? "" : pi.versionName.trim();
                if (pi.applicationInfo != null) {
                    pi.applicationInfo.sourceDir = f.getAbsolutePath();
                    pi.applicationInfo.publicSourceDir = f.getAbsolutePath();
                    try {
                        face.icon = pi.applicationInfo.loadIcon(pm);
                    } catch (Throwable ignored) {
                    }
                    try {
                        CharSequence lb = pi.applicationInfo.loadLabel(pm);
                        if (lb != null) face.label = lb.toString().trim();
                    } catch (Throwable ignored) {
                    }
                    // 没注册 label 的包，loadLabel 会回包名——那不是"安装后看到的名字"，丢掉
                    if (face.label != null && face.label.equals(face.pkg)) face.label = null;
                    if (face.label != null && face.label.isEmpty()) face.label = null;
                }
            }
        } catch (Throwable ignored) {
        }
        if (apkFaces.size() > 60) apkFaces.clear();
        apkFaces.put(key, face);
        return face;
    }

    /** APK 卡片第二行：包名 · 版本 · 大小，拿不到包信息就只报大小 */
    private String apkMeta(java.io.File f, long size) {
        ApkFace face = apkFace(f);
        String head = "";
        if (face.pkg != null && !face.pkg.isEmpty()) {
            head = face.pkg
                    + (face.version == null || face.version.isEmpty() ? "" : " · v" + face.version)
                    + " · ";
        }
        // 正经 APK 再小也有几十 KB。体积掉到这个量级，多半是空壳或拷贝被截断，
        // 直接在卡片上标出来，别让用户点下去才撞系统报错。
        String tail = (size > 0 && size < 8 * 1024) ? "⚠ 体积异常，点按体检" : "点按安装";
        return head + humanSize(size) + " · " + tail;
    }

    private static String humanSize(long bytes) {        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    /**
     * 扫回答里出现的本地文件路径，把「产物」做成一张张介绍卡片挂到气泡底部。
     * 这样正文里只留说明文字，用户不用自己去一行行找路径。
     */
    private void attachArtifacts(LinearLayout content, String text) {
        attachArtifacts(content, text, false);
    }

    /**
     * @param includeRecent true 表示这是本轮刚回答完：把 apk_repack 刚打出来的包也摆上来，
     *                      即便模型正文里没写出路径（它在正文里经常只写文件名）
     */
    private void attachArtifacts(LinearLayout content, String text, boolean includeRecent) {
        if (content == null) return;
        if (text == null) text = "";
        java.util.regex.Matcher mm = java.util.regex.Pattern.compile(
                "(?:/sdcard/|/storage/emulated/0/|/root/|/exchange/"
                        + "|/data/user/\\d+/|/data/data/)"
                        + "[^\\s\"'<>（）()，。；、`]+?"
                        + "\\.(?:apk|png|jpe?g|webp|gif|mp4|webm|mov|mp3|wav|m4a|aac"
                        + "|zip|tar|gz|7z|json|md|txt|log|html?|py|sh|java|xml|kts?)")
                .matcher(text);
        java.util.LinkedHashSet<String> paths = new java.util.LinkedHashSet<>();
        while (mm.find()) {
            String pth = mm.group().trim();
            if (pth.length() > 4) paths.add(pth);
        }

        java.util.List<String> exists = new java.util.ArrayList<>();
        java.util.List<Long> sizes = new java.util.ArrayList<>();
        for (String pth : paths) {
            java.io.File f = new java.io.File(pth);
            if (f.exists() && f.isFile()) {
                exists.add(pth);
                sizes.add(f.length());
            }
        }
        // 本轮刚打包出来的 APK 排最前面：这是用户最想点的东西
        if (includeRecent
                && com.nebula.aichat.apk.ApkInstaller.lastBuiltAt >= reqStartAt
                && com.nebula.aichat.apk.ApkInstaller.lastBuiltAt > 0) {
            java.util.List<java.io.File> fresh = com.nebula.aichat.apk.ApkInstaller.lastBuilt();
            for (int i = fresh.size() - 1; i >= 0; i--) {
                java.io.File bf = fresh.get(i);
                String p = bf.getAbsolutePath();
                if (!bf.isFile() || exists.contains(p)) continue;
                exists.add(0, p);
                sizes.add(0, bf.length());
            }
        }
        if (exists.isEmpty()) return;


        // 标题行：原来是「📎 产出物 · N」，emoji 回形针在深色底上是一坨实心色块，
        // 加粗 12sp 又比卡片标题还重——一个小标题压过了它要引出的一堆卡片。
        // 现在换成 13dp 叠片图标 + 11.5sp 常规字重，只做"引子"不做主角。
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView hic = new ImageView(this);
        hic.setImageResource(R.drawable.ic_art_stack);
        hic.setColorFilter(Palette.c(0xFF6E8098));
        LinearLayout.LayoutParams hiclp = new LinearLayout.LayoutParams(dp(13), dp(13));
        hiclp.rightMargin = dp(5);
        head.addView(hic, hiclp);
        TextView title = new TextView(this);
        title.setText("产出物 · " + exists.size());
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        title.setTextColor(Palette.c(0xFF8B9DB3));
        head.addView(title);
        LinearLayout.LayoutParams tlp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp2.topMargin = dp(10);
        tlp2.bottomMargin = dp(4);
        content.addView(head, tlp2);

        for (int i = 0; i < exists.size(); i++) {
            final String path = exists.get(i);
            int iconRes = artifactIcon(path);
            String label = artifactLabel(path);
            String name = path.substring(path.lastIndexOf('/') + 1);

            final boolean isApk = path.toLowerCase(Locale.US).endsWith(".apk");
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            // APK 用橙色描边强调：这是要装的东西，别和普通文件混成一片
            card.setBackground(isApk
                    ? Ui.box(this, Palette.c(0x1FFFB36B), Palette.c(0x66FFB36B), 12f, 1.2f)
                    : Ui.roundRect(this, Palette.c(0x14FFFFFF), Palette.c(0x3316293F), 12f));
            card.setPadding(dp(11), dp(9), dp(11), dp(9));
            card.setClickable(true);
            card.setOnClickListener(v -> {
                if (isApk) installApk(new java.io.File(path));
                else copyText(path, "路径已复制");
            });
            card.setOnLongClickListener(v -> {
                copyText(path, "路径已复制");
                return true;
            });

            // APK 卡片挂真实 launcher 图标，跟装完之后桌面上看到的那个一模一样；
            // 图标 + 间距恒为 30dp，正文左缘不因有没有图标而跳动。
            ImageView icon = new ImageView(this);
            LinearLayout.LayoutParams iclp;
            android.graphics.drawable.Drawable realIcon =
                    isApk ? apkFace(new java.io.File(path)).icon : null;
            if (realIcon != null) {
                icon.setImageDrawable(realIcon);
                iclp = new LinearLayout.LayoutParams(dp(22), dp(22));
                iclp.rightMargin = dp(8);
            } else {
                icon.setImageResource(iconRes);
                icon.setColorFilter(isApk ? Palette.c(0xFFFFB36B) : Palette.c(0xFF8FA3BA));
                iclp = new LinearLayout.LayoutParams(dp(20), dp(20));
                iclp.rightMargin = dp(10);
            }
            card.addView(icon, iclp);

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);

            // APK 的标题用 android:label（装完在桌面上显示的名字），
            // 不是文件名：用户认的是应用名，htstorm.apk 这种包名式文件名对不上号。
            String shownName = name;
            if (isApk) {
                String lb = apkFace(new java.io.File(path)).label;
                if (lb != null && !lb.isEmpty()) shownName = lb;
            }

            TextView nm = new TextView(this);
            nm.setText(shownName);
            nm.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            nm.setTypeface(Typeface.DEFAULT_BOLD);
            nm.setTextColor(Palette.c(0xFFE6EEFB));
            nm.setMaxLines(1);
            // 应用名截尾（"夜袭压测…"保留开头才是有效信息），文件名截中（保住 .apk 后缀）
            nm.setEllipsize(isApk ? android.text.TextUtils.TruncateAt.END
                    : android.text.TextUtils.TruncateAt.MIDDLE);
            col.addView(nm);

            TextView meta = new TextView(this);
            meta.setText(isApk
                    ? apkMeta(new java.io.File(path), sizes.get(i))
                    : label + " · " + humanSize(sizes.get(i)) + " · 点按复制路径");
            meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            meta.setTextColor(Palette.c(0xFF8896AB));
            col.addView(meta);

            TextView pt = new TextView(this);
            pt.setText(path);
            pt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            pt.setTextColor(Palette.c(0xFF5F6C80));
            pt.setMaxLines(1);
            pt.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            col.addView(pt);

            card.addView(col, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (isApk) {
                // 右侧实心「安装」按钮：一眼看得出这是可以点的
                TextView install = new TextView(this);
                install.setText("安 装");
                install.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
                install.setTypeface(Typeface.DEFAULT_BOLD);
                install.setTextColor(Palette.c(0xFF08131C));
                install.setGravity(Gravity.CENTER);
                install.setBackground(Ui.roundRect(this, Palette.c(0xFF35D98F), 0, 20f));
                install.setPadding(dp(15), dp(6), dp(15), dp(6));
                install.setClickable(true);
                install.setOnClickListener(v -> installApk(new java.io.File(path)));
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                ilp.leftMargin = dp(8);
                card.addView(install, ilp);
            }

            // 左右缝隙统一由外层容器给（12dp），卡片自己不再加 margin，
            // 否则会叠成 24dp，卡片比正文还往里缩，看着像错位
            LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp2.topMargin = dp(6);
            content.addView(card, clp2);
        }
    }

    /** 产物卡片点按 APK：直接交给系统安装器安装 */
    /**
     * 上次点安装的时刻。确认框还开着的时候又点一次，会开第二个安装会话，
     * 系统会把前一个判成「用户放弃」并回传 INSTALL_FAILED_ABORTED ——
     * 结果是包装好了，屏幕上却弹一句安装没成功。这里把重复触发挡掉。
     */
    private static long lastInstallTapAt = 0L;

    private void installApk(java.io.File f) {
        if (f == null || !f.isFile()) {
            Toast.makeText(this, "文件不存在，可能已被移动或删除", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!f.canRead()) {
            Toast.makeText(this, "没有读取权限，无法安装", Toast.LENGTH_SHORT).show();
            return;
        }
        long tapNow = android.os.SystemClock.uptimeMillis();
        if (tapNow - lastInstallTapAt < 2500L) {
            Toast.makeText(this, "上一次安装还没走完，稍等一下再点", Toast.LENGTH_SHORT).show();
            return;
        }
        lastInstallTapAt = tapNow;
        // Android 8+ 必须先允许「安装未知应用」，否则系统会静默拒绝
        if (android.os.Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "请先允许本应用安装应用，返回后再点一次", Toast.LENGTH_LONG).show();
            com.nebula.aichat.apk.ApkInstaller.openInstallPermission(this);
            return;
        }

        // 装之前先体检：签名冲突 / 版本降级 / ABI 不匹配 / targetSdk 太低，
        // 这些都能在点下去之前用中文讲清楚，不用对着系统的「应用未安装」猜。
        final com.nebula.aichat.apk.ApkInstaller.Check ck =
                com.nebula.aichat.apk.ApkInstaller.check(this, f);
        if (!ck.installable) {
            StringBuilder sb = new StringBuilder();
            for (String p : ck.problems) sb.append("· ").append(p).append("\n\n");
            if (!ck.notes.isEmpty()) {
                sb.append("本机情况：");
                for (int i = 0; i < ck.notes.size(); i++) {
                    if (i > 0) sb.append("；");
                    sb.append(ck.notes.get(i));
                }
            }
            showInstallIssue(f, "这个包装不上", sb.toString(), ck.signMismatch ? ck.pkg : null);
            return;
        }

        // 走安装会话：失败时系统会把 INSTALL_FAILED_xxx 回传，能翻成中文再给用户看
        String err = com.nebula.aichat.apk.ApkInstaller.installSession(this, f,
                new com.nebula.aichat.apk.ApkInstaller.Callback() {
                    @Override
                    public void onResult(final boolean ok, final String message) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (ok) {
                                    lastInstallTapAt = 0L;   // 这一单结束了，紧接着装下一个不该被挡
                                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                // 系统回传的状态不是终审判决：装完直接返回、确认框超时、
                                // 重复点开了第二个会话，都会把这次会话报成 ABORTED，
                                // 而包其实已经装上了。先查一遍真实安装记录再决定要不要报错。
                                if (com.nebula.aichat.apk.ApkInstaller.alreadyInstalled(
                                        MainActivity.this, ck)) {
                                    Toast.makeText(MainActivity.this,
                                            "已经装好了", Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                String low = message == null ? "" : message;
                                boolean needUninstall = low.contains("UPDATE_INCOMPATIBLE")
                                        || low.contains("签名") || low.contains("版本降级")
                                        || low.contains("VERSION_DOWNGRADE");
                                showInstallIssue(f, "安装没成功", message,
                                        needUninstall ? ck.pkg : null);
                            }
                        });
                    }
                });
        if (err != null) showInstallIssue(f, "安装没成功", err, ck.signMismatch ? ck.pkg : null);
    }

    /** 安装受挫时的统一出口：中文原因摆清楚，再给一个能立刻做的动作 */
    private void showInstallIssue(final java.io.File f, String title, String body,
                                  final String pkgToUninstall) {
        String text = body == null ? "" : body.trim();
        if (text.isEmpty()) text = "系统没有返回更具体的原因。";
        // 变量声明用父类型：链式 setXxx 返回的是 AlertDialog.Builder，
        // 运行时对象仍是 NebAlert.Builder，show() 的虚分派会走到子类覆盖版本
        AlertDialog.Builder b = new NebAlert.Builder(this)
                .setTitle(title)
                .setMessage(text)
                .setPositiveButton("知道了", null);
        if (pkgToUninstall != null && !pkgToUninstall.isEmpty()) {
            b.setNeutralButton("卸载原应用", (d, w) -> {
                try {
                    startActivity(new Intent(Intent.ACTION_DELETE,
                            android.net.Uri.parse("package:" + pkgToUninstall)));
                    Toast.makeText(this, "卸载完再点一次安装卡片", Toast.LENGTH_LONG).show();
                } catch (Throwable t) {
                    Toast.makeText(this, "打不开卸载界面：" + t.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            b.setNeutralButton("复制路径", (d, w) -> copyText(f.getAbsolutePath(), "路径已复制"));
        }
        b.show();
    }
    /** 优先共享原路径；不在 FileProvider 白名单内就先复制到缓存目录再共享 */
    private android.net.Uri uriForApk(java.io.File f) throws Exception {
        String auth = getPackageName() + ".fileprovider";
        try {
            return androidx.core.content.FileProvider.getUriForFile(this, auth, f);
        } catch (Throwable notShared) {
            java.io.File dir = new java.io.File(getCacheDir(), "install");
            if (!dir.exists()) dir.mkdirs();
            java.io.File dst = new java.io.File(dir, f.getName());
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            out.close();
            return androidx.core.content.FileProvider.getUriForFile(this, auth, dst);
        }
    }

    private void copyText(String s, String toast) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("nebula", s));
            Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    /** 创建一个空的 AI 气泡；withStatus 为 true 时在正文上方加「正在思考…」状态行 */
    private LinearLayout addAiBubble(Message m, boolean withStatus) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(18);
        // 对话框模式：气泡左右各留 10dp（chatContainer 自带内边距），
        // 与用户气泡同一套视觉——左边距不铺满，一眼能看出这是"一条消息"而不是整页文档

        row.setLayoutParams(rowLp);
        row.setClipChildren(false);
        row.setClipToPadding(false);

        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        // 正文不再套圆角卡片：一个 18dp 圆角 + 边框把整段回答框成一张"大卡"，
        // 屏宽被吃掉 24dp，长回答读起来像在看文档阅读器而不是聊天。
        // 现在直接铺满：背景取消、左右只留 2dp 呼吸位，文字行宽 = 屏宽
        bubble.setBackground(null);
        // 左右各留 12dp 缝隙：贴到屏幕边上时，长段文字的首尾字符直接顶着实屏边缘，
        // 视觉上又挤又憋。12dp 是"看得出留白但不显空"的量，正文仍然几乎占满整屏。
        bubble.setPadding(dp(12), dp(4), dp(12), dp(2));
        // 过程块用负 margin 略微外扩，关闭裁剪以免细框被切掉
        bubble.setClipChildren(false);
        bubble.setClipToPadding(false);

        if (withStatus) {
            runThinkMs = 0;          // 新一轮从零开始累计，别把上一轮的耗时带进来
            liveThinkBox = null;     // 上一轮的思考块已经随消息定格，引用要断开
            thinkHost = null;        // 思考段已并入过程流，不再有独立容器
            addStatusRow(bubble);
            // 排序：状态行 → 工具调用行 → 思考块 → 正文。
            // 工具行单独占一格是这轮改动的关键：以前它和正文共用 content，
            // 想把它排在思考块上面根本插不进去；现在先把「做了什么」摆在最上面，
            // 「想了什么」折叠着跟在它下面，正文收尾在最底下。
            toolHost = new LinearLayout(this);
            toolHost.setOrientation(LinearLayout.VERTICAL);
            toolHost.setClipChildren(false);
            bubble.addView(toolHost, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (withStatus) clp.topMargin = dp(9);
        bubble.addView(content, clp);

        // 气泡底部不再挂常驻横幅：监控信息已并进每个工具行下面那条“活翻译行”
        // （跑的时候光扫+秒数，跑完定格成翻译）。同一件事在两个位置说一遍，
        // 读的人得先判断这两处是不是同一件事，这本身就是成本。
        // “整轮跑到哪了 / 总用时”仍由顶部状态行负责。

        addAiActions(bubble, m);

        row.addView(bubble, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        chatContainer.addView(row);
        return content;
    }

    /**
     * 「思考过程」行：紧跟在触发它的那次工具调用下面，只占一行。
     *
     * <p>以前是把整段思考摊开铺出来，一轮任务里调用几次就多出几屏文字，
     * 把后面的调用行全挤到看不见的地方。现在压成一行：一行小标题 + 一行滚动文字，
     * 行尾一个 ▾，想看全文就点开。思考中这行文字一直在滚，滚完这轮就该下一次调用了，
     * 于是自然形成 调用 → 思考(滚) → 调用 → 思考(滚) 的循环。</p>
     */
    private View buildThinkBox(final String text, String costSecs) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        // 不再用左侧绿竖线做「思考」的标识：竖线要占 11dp 缩进，
        // 会让「思考」比「调用」往右缩一截，而且压住新加的矢量图标。
        // 现在两行都是 图标 + 标签 + 内容，只靠图标和文字颜色区分，左缘齐平。
        box.setBackground(null);
        box.setPadding(0, dp(1), 0, dp(1));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(3);
        blp.bottomMargin = dp(3);
        box.setLayoutParams(blp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        box.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 行首图标：和调用行同一尺寸（14dp）、同一右边距（6dp），保证两行左缘齐平
        ImageView thIc = new ImageView(this);
        thIc.setImageResource(R.drawable.ic_act_think);
        thIc.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams thlp = new LinearLayout.LayoutParams(dp(14), dp(14));
        thlp.rightMargin = dp(6);
        row.addView(thIc, thlp);

        // 小标签：只写「思考」，秒数等这轮结束再补上，收起态也放得下
        TextView label = new TextView(this);
        label.setTag(TAG_THINK_LABEL);
        label.setText((costSecs == null || costSecs.trim().isEmpty())
                ? "思考" : "思考 \u00b7 " + costSecs);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        label.setTextColor(Palette.c(0xB83FB950));
        label.setPadding(0, 0, dp(6), 0);
        label.setIncludeFontPadding(false);
        row.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView body = new TextView(this);
        body.setTag(TAG_THINK_BODY);
        body.setText(text);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        body.setTextColor(Palette.c(0xFF9DB3A4));
        body.setSingleLine(true);
        // 默认收起成一行：放不下就省略，想看全文点箭头
        body.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(body, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView arrow = new TextView(this);
        arrow.setTag(TAG_THINK_CHEVRON);
        arrow.setText("\u25be");
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        // 和调用行的箭头同一种橙色：两条过程行里，"能点开"的信号保持一致
        arrow.setTextColor(Palette.c(0xFFFFA44D));
        arrow.setPadding(dp(8), dp(1), dp(2), dp(1));
        row.addView(arrow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 点这一行展开全文，再点收回到一行
        row.setOnClickListener(v -> {
            boolean collapsed = body.getMaxLines() == 1;
            if (collapsed) {
                body.setSelected(false);
                body.setSingleLine(false);
                body.setHorizontallyScrolling(false);
                body.setMaxLines(Integer.MAX_VALUE);
                body.setEllipsize(null);
                body.setLineSpacing(dp(3), 1.4f);
                body.setPadding(0, dp(3), 0, dp(3));
                arrow.setText("\u25b4");
            } else {
                body.setPadding(0, 0, 0, 0);
                body.setLineSpacing(0, 1f);
                body.setSingleLine(true);
                body.setEllipsize(android.text.TextUtils.TruncateAt.END);
                arrow.setText("\u25be");
            }
        });
        return box;
    }

    /** 折叠箭头：展开朝下、收起朝右，跟标题行同色 */
    private void setThinkChevron(View box, boolean expanded) {
        if (box == null) return;
        View v = box.findViewWithTag(TAG_THINK_CHEVRON);
        if (v instanceof TextView) ((TextView) v).setText(expanded ? "\u25be" : "\u25b8");
    }

    /** 思考状态行：小图标（旋转中）+ 文案，完成后变为耗时 */
    private void addStatusRow(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(6), dp(13), dp(6));
        // 胶囊底：跟运行横幅同一套视觉语言，状态行不再是一行裸文字
        statusRow = row;
        statusRowBg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Palette.c(0x2E5B93FF), Palette.c(0x0A5B93FF)});
        statusRowBg.setCornerRadius(dp(11));
        statusRowBg.setStroke(Math.max(1, dp(1)), Palette.c(0x3D5B93FF));
        row.setBackground(statusRowBg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(9);

        // 旋转图标会溢出自身 bounds，父容器不裁剪才不会把四个角切掉
        row.setClipChildren(false);
        row.setClipToPadding(false);

        statusIcon = new ImageView(this);
        statusIcon.setImageResource(R.drawable.ic_spark);
        statusIcon.setColorFilter(Palette.c(0xFF5B93FF));
        statusIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);   // 等比缩放，绝不拉伸
        statusIcon.setContentDescription("思考中");
        row.addView(statusIcon, new LinearLayout.LayoutParams(dp(18), dp(18)));

        statusText = new TextView(this);
        statusText.setText("正在思考…");
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        statusText.setTextColor(Palette.c(0xFF8A97AA));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(7);
        row.addView(statusText, tlp);

        // 状态行与它下面那条分隔线都不再进视图树：思考中、执行中、完成，一律不在
        // 正文上方顶一行。反馈交给实时思考块和调用行本身，那两处已经写得很清楚了。
        //
        // 两个 View 仍然建出来：setStatusBusy / 计时器 / setStatusTint / markStatusFinal
        // 全都在往它们身上写字，不挂出去只是空写，不会 NPE，也不用把十几处调用点
        // 统统改成 null 判断。要恢复这行，把下面两段 addView 放回来即可。
        //
        // parent.addView(row, lp);
        //
        // // 思考区与正文之间的分隔线：1dp 两端渐隐实线（比虚线更干净，不会碎成噪点）
        // View hair = new View(this);
        // hair.setTag(TAG_THINK_HAIR);
        // hair.setBackground(new android.graphics.drawable.GradientDrawable(
        //         android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
        //         new int[]{Palette.c(0x003FB950), Palette.c(0x553FB950), Palette.c(0x003FB950)}));
        // parent.addView(hair, new LinearLayout.LayoutParams(
        //         ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));

        thinkStartAt = SystemClock.uptimeMillis();
        thinkDone = false;
        spinAnim = ObjectAnimator.ofFloat(statusIcon, View.ROTATION, 0f, 360f);
        spinAnim.setDuration(1600);
        spinAnim.setRepeatCount(ObjectAnimator.INFINITE);
        spinAnim.setInterpolator(new LinearInterpolator());
        spinAnim.start();
    }

    /** 思考结束（首个正文到达或请求收尾）：停止旋转，状态行显示耗时 */
    private void markThinkDone() {
        if (thinkDone || statusText == null) return;
        thinkDone = true;
        long cost = Math.max(0, SystemClock.uptimeMillis() - thinkStartAt);
        runThinkMs += cost;
        // 有推理内容就折成一块「思考过程」，默认收起，不占正文地方
        // 实时块已经边想边显示了：这里只停光、改标题，不再重复建一块
        boolean hadLive = liveThinkBox != null;
        stopLiveThink();
        if (!hadLive && thinkBuf != null && thinkBuf.length() > 0) {
            // 兜底：思考内容没能边想边显示（例如一次性整段返回）时，这里补一段进去
            LinearLayout h = toolHost != null ? toolHost : streamContent;
            if (h != null) h.addView(buildThinkBox(thinkBuf.toString(), null));
        }
        // 本段思考到此定格：后续思考另起一段，落在下一次调用行下面，
        // 不再往旧块里续写（那样又会变成"一整块思考"）
        liveThinkBox = null;
        // 这段思考已经显示完：存进时间线（历史回放靠它还原成一段一段）
        takeThinkStep();
        thinkSegStart = 0;
        thinkSegAt = SystemClock.uptimeMillis();
        if (thinkBuf != null) thinkBuf.setLength(0);
        // 命令还在跑的时候，状态行必须继续转，不能提前变成「思考完成」的静态灰字，
        // 否则用户看着一动不动的界面，根本分不清是执行中还是已经结束
        if (pendingToolCount() > 0) {
            setStatusBusy("正在执行 " + lastToolName() + " …");
            return;
        }
        statusText.setText("完成");
        statusText.setTextColor(Palette.c(C_OK));
        setStatusTint(Palette.c(C_OK));
        if (spinAnim != null) {
            spinAnim.cancel();
            spinAnim = null;
        }
        if (statusIcon != null) {
            statusIcon.setImageResource(R.drawable.ic_tool_stop);
            statusIcon.setColorFilter(Palette.c(C_OK));   // 单色矢量，这里染色是对的
            statusIcon.setRotation(0f);
            statusIcon.setAlpha(0.55f);
        }
    }

    /**
     * 应用图标（小尺寸用），懒加载一次后缓存。
     *
     * 直接用 PackageManager 返回的 launcher 图标：系统会按当前屏幕密度挑最合适的那张，
     * 自适应图标（API 26+）也是矢量化的，18dp 显示不会糊。
     */
    private Drawable appIconSmall() {
        if (statusAppIcon == null) {
            try {
                statusAppIcon = getPackageManager().getApplicationIcon(getPackageName());
            } catch (Throwable ignored) {
                statusAppIcon = null;
            }
        }
        return statusAppIcon;
    }

    private String lastToolName() {
        for (int i = toolRowNames.size() - 1; i >= 0; i--) {
            if (!toolRowDone.get(i)) return toolRowNames.get(i);
        }
        return "命令";
    }

    /** 状态行胶囊底随状态换色（运行琥珀 / 思考蓝 / 完成绿 / 停止橙） */
    private void setStatusTint(int color) {
        if (statusRowBg == null) return;
        try {
            int solid = color & 0x00FFFFFF;
            statusRowBg.setColors(new int[]{solid | Palette.c(0x2E000000), solid | Palette.c(0x0A000000)});
            statusRowBg.setStroke(Math.max(1, dp(1)), solid | Palette.c(0x3D000000));
            if (statusRow != null) statusRow.invalidate();
        } catch (Throwable ignored) {
        }
    }

    /** 让状态行重新进入「正在干活」：图标恢复旋转、文案可替换 */
    private void setStatusBusy(String text) {
        if (statusText == null) return;
        statusText.setText(text);
        statusText.setTextColor(Palette.c(C_RUN));
        setStatusTint(Palette.c(C_RUN));
        if (statusIcon != null) {
            // 执行态用应用图标（等比小图）：星形读作「在想」，应用的品牌图标配旋转读作
            // 「AgenCode 在替你跑」，比一个通用圆环更能让人知道是谁在干活。
            Drawable appIcon = appIconSmall();
            if (appIcon != null) {
                statusIcon.setImageDrawable(appIcon);
                statusIcon.setColorFilter(null);      // 彩色图标不能染色，否则糊成一团
            } else {
                // 兜底：万一取不到图标（极少数 ROM），退回原来的圆环，功能不断
                statusIcon.setImageResource(R.drawable.ic_tool_arc);
                statusIcon.setColorFilter(Palette.c(C_RUN));
            }
            statusIcon.setVisibility(View.VISIBLE);
            statusIcon.setAlpha(1f);
            statusIcon.setRotation(0f);
        }
        if (spinAnim == null && statusIcon != null) {
            spinAnim = ObjectAnimator.ofFloat(statusIcon, View.ROTATION, 0f, 360f);
            spinAnim.setDuration(1600);
            spinAnim.setRepeatCount(ObjectAnimator.INFINITE);
            spinAnim.setInterpolator(new LinearInterpolator());
            spinAnim.start();
        }
    }

    /**
     * 把整轮总耗时写到当前这条回复的动作条上。
     * 沿状态文字向上找宿主气泡，不依赖任何全局引用——
     * 回复可能在别的消息里流式生成，全局引用早就指错了也不会报错。
     */
    // ================= 正文右侧的快速上/下浏览 =================

    /** 悬浮的上下翻页条； 表示还没建（或这个页面没有聊天滚动区） */
    private View quickNav;

    /**
     * 在正文右侧挂一对「向上 / 向下」快速浏览按钮。
     *
     * 长对话里要回到上面看一段旧内容，得反复甩好几次屏幕；要回最新一条，
     * 又得一路往下甩。这两个按钮各管一个方向，一下翻大半屏。
     *
     * 挂在 ScrollView 的父容器上（不是 ScrollView 内部），所以它不跟着内容滚，
     * 永远停在视野里同一个位置。
     */
    private void setupQuickNav() {
        if (scroll == null || quickNav != null) return;
        if (!(scroll.getParent() instanceof ViewGroup)) return;
        ViewGroup host = (ViewGroup) scroll.getParent();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setClipChildren(false);
        box.setClipToPadding(false);

        ImageView up = makeQuickNavBtn(true);
        ImageView down = makeQuickNavBtn(false);
        // 尺寸必须在这里给死：addView(v, lp) 会用传进去的 lp 覆盖 view 自己的，
        // 若这里写 WRAP_CONTENT，就会把 42dp 冲掉。而 ImageView 在 wrap_content
        // 且 drawable 没有 intrinsic 尺寸时，量出来的只有 "padding + 1px"，
        // 内容区就剩 1 个像素——画出来的图形只有 1px，等于没画。
        LinearLayout.LayoutParams gapLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        gapLp.bottomMargin = dp(8);
        box.addView(up, gapLp);
        box.addView(down, new LinearLayout.LayoutParams(dp(42), dp(42)));

        up.setOnClickListener(v -> quickNavScroll(-1));
        down.setOnClickListener(v -> quickNavScroll(1));

        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        blp.rightMargin = dp(3);
        host.addView(box, blp);
        quickNav = box;

        // 滚动时立刻判断；内容变高（流式输出）、键盘弹出改变高度时也要重新判断，
        // 否则会出现「已经到底了按钮还挂着」或者「明明能滚却不显示」。
        // 原来那条只记 lastScrollAt 的监听合并到这里，别丢——它管着「要不要自动跟到底」。
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            lastScrollAt = SystemClock.uptimeMillis();
            updateQuickNav();
        });
        scroll.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> updateQuickNav());
        if (chatContainer != null) {
            chatContainer.addOnLayoutChangeListener(
                    (v, l, t, r, b, ol, ot, orr, ob) -> updateQuickNav());
        }
        scroll.post(this::updateQuickNav);
    }

    /** 圆形透明按钮，里面一枚实心三角：向上的尖端朝上，向下的尖端朝下 */
    private ImageView makeQuickNavBtn(boolean up) {
        ImageView iv = new ImageView(this);
        // 直接用箭头本身表明方向，不再靠旋转一个 V 形折线
        iv.setImageDrawable(new ArrowDrawable(Palette.c(0xFFF4F8FF), up));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setPadding(dp(11), dp(11), dp(11), dp(11));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        // 底子压到很淡，只留一圈细边交代位置：它是常驻的，不该比正文更抢眼
        bg.setColor(Palette.c(0x5C101A26));
        bg.setStroke(Math.max(1, dp(1)), Palette.c(0x59FFFFFF));
        iv.setBackground(bg);
        iv.setContentDescription(up ? "向上浏览" : "向下浏览");
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(42), dp(42)));
        return iv;
    }

    /** 按滚动位置刷新这组按钮：到底整组收起，到顶只留「向下」，不够一屏就不要出现 */
    private void updateQuickNav() {
        if (quickNav == null || scroll == null) return;
        View child = scroll.getChildAt(0);
        if (child == null) return;
        int viewH = scroll.getHeight();
        int contentH = child.getHeight();
        if (viewH <= 0) return;

        // 内容还没超过一屏：根本没有可翻的，出现只会挡字
        if (contentH <= viewH + dp(8)) {
            fadeQuickNav(false);
            return;
        }

        int y = scroll.getScrollY();
        boolean atTop = y <= dp(6);
        boolean atBottom = contentH - (y + viewH) <= dp(8);

        // 滑到底就把整组收起来：屏幕最下方正是你要看的地方，按钮不该压在那儿
        fadeQuickNav(!atBottom);

        // 已经在顶上了，向上的那个没有可翻的内容，先退场留出干净的一侧。
        // 用透明度而不是 INVISIBLE：隐藏不能改变占位，否则下面那个按钮会往上跳 8dp
        View up = quickNav instanceof ViewGroup ? ((ViewGroup) quickNav).getChildAt(0) : null;
        if (up != null) {
            float want = atTop ? 0f : 1f;
            if (Math.abs(up.getAlpha() - want) > 0.01f) {
                up.animate().cancel();
                up.animate().alpha(want).setDuration(130).start();
            }
            up.setClickable(!atTop);
        }
    }

    private void fadeQuickNav(boolean show) {
        if (quickNav == null) return;
        if (show) {
            if (quickNav.getVisibility() == View.VISIBLE && quickNav.getAlpha() > 0.99f) return;
            quickNav.animate().cancel();
            quickNav.setVisibility(View.VISIBLE);
            quickNav.animate().alpha(1f).setDuration(130).start();
        } else {
            if (quickNav.getVisibility() != View.VISIBLE) return;
            if (quickNav.getAlpha() < 0.02f) {
                quickNav.setVisibility(View.GONE);
                return;
            }
            quickNav.animate().cancel();
            quickNav.animate().alpha(0f).setDuration(130)
                    .withEndAction(() -> {
                        // 期间可能又被叫出来，别把刚显示的东西按回去
                        if (quickNav != null && quickNav.getAlpha() < 0.05f) {
                            quickNav.setVisibility(View.GONE);
                        }
                    })
                    .start();
        }
    }

    /** 点一下翻大半屏：留一点上一屏的尾巴，方便接上刚才读到的位置 */
    private void quickNavScroll(int dir) {
        if (scroll == null) return;
        View child = scroll.getChildAt(0);
        if (child == null) return;
        int step = Math.max(dp(200), (int) (scroll.getHeight() * 0.82f));
        int max = Math.max(0, child.getHeight() - scroll.getHeight());
        int target = scroll.getScrollY() + (dir > 0 ? step : -step);
        target = Math.max(0, Math.min(max, target));
        scroll.smoothScrollTo(0, target);
    }

    private void setMsgCost(long ms) {
        if (ms <= 0) return;
        // 锚点用正文容器：状态行已经不进视图树，没法再从它身上往上去找宿主气泡
        View p = streamContent != null ? streamContent : toolHost;
        if (p == null) return;
        for (int depth = 0; depth < 7 && p != null; depth++) {
            if (!(p.getParent() instanceof ViewGroup)) return;
            ViewGroup g = (ViewGroup) p.getParent();
            View hit = g.findViewWithTag(TAG_MSG_COST);
            if (hit instanceof TextView) {
                TextView tv = (TextView) hit;
                tv.setText(dur(ms));
                tv.setVisibility(View.VISIBLE);
                return;
            }
            p = g;
        }
    }

    /** 整轮结束：状态行给出总耗时，旋转停下，界面明确「不再动了」 */
    private void markStatusFinal(boolean ok) {
        roundActive = false;
        stopToolTicker();
        finishRunBanner(ok);
        if (statusText == null) return;
        long cost = Math.max(0, SystemClock.uptimeMillis() - reqStartAt);
        // 停止时把「还能接着做」说清楚：30 分钟内直接发消息，现场会自动接上，
        // 不用再记住「继续」两个字，也不用担心重新解包一遍。
        // 正常完成则不写「完成 · 总用时」：耗时已经落在动作条上，
        // 正文上方不该再多留一行，见下面的 hideStatusRow。
        if (!ok) {
            statusText.setText("已停止 · 总用时 " + dur(cost) + " · 直接发消息会接着做");
        }
        // 同一个数字、两个位置：状态行说一遍，动作条再留一份底。
        // 这不是重复：状态行会随滚动出屏，而动作条是回复自带的尾巴
        setMsgCost(cost);
        statusText.setTextColor(ok ? Palette.c(C_OK) : Palette.c(C_ABORT));
        setStatusTint(ok ? Palette.c(C_OK) : Palette.c(C_ABORT));
        if (spinAnim != null) {
            spinAnim.cancel();
            spinAnim = null;
        }
        if (statusIcon != null) {
            statusIcon.setRotation(0f);
            statusIcon.setAlpha(ok ? 0.55f : 0.9f);
            statusIcon.setImageResource(ok ? R.drawable.ic_tool_stop
                    : R.drawable.ic_tool_abort);
            statusIcon.setColorFilter(ok ? Palette.c(C_OK) : Palette.c(C_ABORT));
            statusIcon.setVisibility(View.VISIBLE);
        }
        // 正常跑完：正文上方那行「完成 · 总用时」连同它下面的分隔线一起撤掉，
        // 回复直接从顶部开始，不再头顶挂一条已结束的状态说明
        if (ok) hideStatusRow();
    }

    /**
     * 撤掉正文上方的状态胶囊行（含它下面那条 1dp 渐隐分隔线）。
     *
     * <p>只在整轮成功收尾时调用：耗时已经写到回复底部动作条上，状态行留着
     * 就是同一件事说两遍、还白占正文前面一行。停下来/出错的情况不撤——
     * 那一行承载「直接发消息会接着做」的提示，有信息量。</p>
     */
    private void hideStatusRow() {
        if (statusRow == null) return;
        ViewGroup par = (statusRow.getParent() instanceof ViewGroup)
                ? (ViewGroup) statusRow.getParent() : null;
        statusRow.setVisibility(View.GONE);
        if (par == null) return;
        // 只找它后面紧邻的、带 hair 标记的那一条，不误伤别的视图
        int from = par.indexOfChild(statusRow) + 1;
        for (int i = from; i < par.getChildCount(); i++) {
            View c = par.getChildAt(i);
            if (TAG_THINK_HAIR.equals(c.getTag())) {
                c.setVisibility(View.GONE);
                break;
            }
        }
    }

    // 每个操作按钮一个色系：图标 + 描边同色，背景同色 8% 淡染
    private static final int COLOR_COPY = 0xFF7C8DA6;
    private static final int COLOR_REGEN = 0xFF5B93FF;
    private static final int COLOR_LIKE = 0xFF22C55E;
    private static final int COLOR_BAD = 0xFFEF4444;
    private static final int COLOR_TTS = 0xFFA855F7;
    private static final int COLOR_SHARE = 0xFF14B8A6;

    /** 气泡内部底排：左侧彩色方框按钮组，右侧单独「重新生成」 */
    private void addAiActions(LinearLayout bubble, final Message m) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.topMargin = dp(11);

        ImageButton btnCopy = chipButton(R.drawable.ic_copy, "复制", Palette.c(COLOR_COPY));
        btnCopy.setOnClickListener(v -> copyToClipboard(cleanBody(m.content)));
        bar.addView(btnCopy, chipLp(false));

        final ImageButton btnUp = chipButton(R.drawable.ic_thumb_up, "有帮助", Palette.c(COLOR_LIKE));
        final ImageButton btnDown = chipButton(R.drawable.ic_thumb_down, "需改进", Palette.c(COLOR_BAD));
        applyChipState(btnUp, Palette.c(COLOR_LIKE), m.rating == 1);
        applyChipState(btnDown, Palette.c(COLOR_BAD), m.rating == -1);
        View.OnClickListener rate = v -> {
            int want = (v == btnUp) ? 1 : -1;
            m.rating = (m.rating == want) ? 0 : want;
            applyChipState(btnUp, Palette.c(COLOR_LIKE), m.rating == 1);
            applyChipState(btnDown, Palette.c(COLOR_BAD), m.rating == -1);
            String tip = m.rating == 1 ? "已标记：有帮助"
                    : m.rating == -1 ? "已标记：需改进" : "已取消标记";
            Toast.makeText(this, tip, Toast.LENGTH_SHORT).show();
            store.save();
        };
        btnUp.setOnClickListener(rate);
        btnDown.setOnClickListener(rate);
        bar.addView(btnUp, chipLp(true));
        bar.addView(btnDown, chipLp(true));

        final ImageButton btnSpeak = chipButton(R.drawable.ic_speak, "朗读", Palette.c(COLOR_TTS));
        btnSpeak.setOnClickListener(v -> toggleSpeak(m, btnSpeak));
        bar.addView(btnSpeak, chipLp(true));

        ImageButton btnShare = chipButton(R.drawable.ic_share, "分享", Palette.c(COLOR_SHARE));
        btnShare.setOnClickListener(v -> shareText(cleanBody(m.content)));
        bar.addView(btnShare, chipLp(true));

        // 弹性空隙，把「重新生成」推到最右，和其他按钮拉开
        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        // 整轮总耗时：9.5sp 等宽、对比度压到最低，紧贴操作区右边界。
        // 它只陈述一个事实（这条回复一共跑了多久），不参与强调：
        // 长回答把顶部状态行顶出屏幕之后，这里是准备看总账时唯一还在的地方
        TextView costTv = new TextView(this);
        costTv.setTag(TAG_MSG_COST);
        costTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        costTv.setTypeface(Typeface.MONOSPACE);
        costTv.setTextColor(Palette.c(0xFF5A6775));
        costTv.setGravity(Gravity.CENTER_VERTICAL);
        costTv.setPadding(dp(6), 0, dp(4), 0);
        costTv.setVisibility(View.GONE);
        bar.addView(costTv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ImageButton btnAgain = chipButton(R.drawable.ic_refresh, "重新生成", Palette.c(COLOR_REGEN));
        btnAgain.setOnClickListener(v -> regenerateFrom(m));
        LinearLayout.LayoutParams againLp = chipLp(false);
        againLp.leftMargin = dp(12);
        bar.addView(btnAgain, againLp);

        // 分隔线：正文和操作行之间拉一道细线，两端淡出，避免做成生硬的通栏横杠
        View sep = new View(this);
        android.graphics.drawable.GradientDrawable sepg =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[]{Palette.c(0x0AFFFFFF), Palette.c(0x26FFFFFF), Palette.c(0x0AFFFFFF)});
        sep.setBackground(sepg);
        LinearLayout.LayoutParams seplp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        seplp.topMargin = dp(10);
        bubble.addView(sep, seplp);

        bubble.addView(bar, barLp);
    }

    private LinearLayout.LayoutParams chipLp(boolean gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(30));
        if (gap) lp.leftMargin = dp(10);
        return lp;
    }

    /** 方形圆角描边按钮：图标与边框同色，底色同色淡染 */
    private ImageButton chipButton(int res, String desc, int color) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(res);
        b.setColorFilter(color);
        b.setContentDescription(desc);
        b.setTag(color);
        b.setBackground(chipBg(color, false));
        int pad = dp(8);
        b.setPadding(pad, pad, pad, pad);
        return b;
    }

    private void applyChipState(ImageButton b, int color, boolean active) {
        b.setTag(color);
        b.setColorFilter(active ? Palette.c(0xFFFFFFFF) : color);
        b.setBackground(chipBg(color, active));
    }

    private Drawable chipBg(int color, boolean active) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(9));
        if (active) {
            d.setColor(color);
        } else {
            d.setColor((color & 0x00FFFFFF) | Palette.c(0x1F000000));
            d.setStroke(dp(1), (color & 0x00FFFFFF) | Palette.c(0x66000000));
        }
        int rippleColor = active ? Palette.c(0x33FFFFFF) : ((color & 0x00FFFFFF) | Palette.c(0x33000000));
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), d, null);
    }

    // ---------------- 朗读 / 分享 ----------------

    private TextToSpeech tts;
    private boolean ttsReady;
    private String pendingSpeak;
    private ImageButton speakingBtn;
    private boolean ttsFailNotified;

    private void ensureTts() {
        if (tts != null) return;
        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) {
                notifyTtsUnavailable("语音引擎初始化失败");
                return;
            }
            try {
                List<TextToSpeech.EngineInfo> engines = tts.getEngines();
                if (engines == null || engines.isEmpty()) {
                    notifyTtsUnavailable("本机没有安装任何语音引擎");
                    return;
                }
            } catch (Exception ignored) {
            }
            int r = tts.setLanguage(Locale.CHINA);
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                r = tts.setLanguage(Locale.getDefault());
            }
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                notifyTtsUnavailable("系统缺少可用的中文语音包");
                return;
            }
            tts.setSpeechRate(1.0f);
            tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                @Override
                public void onStart(String id) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            "正在朗读…", Toast.LENGTH_SHORT).show());
                }

                @Override
                public void onDone(String id) {
                    runOnUiThread(MainActivity.this::stopSpeak);
                }

                @Override
                public void onError(String id) {
                    runOnUiThread(() -> notifyTtsUnavailable("朗读被系统中断"));
                }
            });
            ttsReady = true;
            if (pendingSpeak != null) {
                String t = pendingSpeak;
                pendingSpeak = null;
                speakNow(t);
            }
        });
    }

    private void speakNow(String text) {
        if (tts == null || !ttsReady) return;
        try {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nebula");
        } catch (Exception e) {
            notifyTtsUnavailable("调用语音引擎失败");
        }
    }

    /** 朗读不可用时给出明确原因，并提供跳转系统语音设置 */
    private void notifyTtsUnavailable(String why) {
        stopSpeak();
        if (ttsFailNotified) return;
        ttsFailNotified = true;
        new NebAlert.Builder(this)
                .setTitle("朗读不可用")
                .setMessage(why + "。\n\n本机可能未安装语音引擎（TTS）或中文语音包。"
                        + "可在系统「设置 → 无障碍 → 文字转语音输出」中选择引擎并下载中文语音数据。")
                .setPositiveButton("去设置", (d, w) -> {
                    try {
                        startActivity(new Intent("com.android.settings.TTS_SETTINGS"));
                    } catch (Exception e) {
                        try {
                            startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
                        } catch (Exception ignored) {
                        }
                    }
                })
                .setNegativeButton("知道了", null)
                .show();
    }

    private void toggleSpeak(Message m, ImageButton btn) {
        String text = cleanBody(m.content);
        if (text == null || text.isEmpty()) {
            Toast.makeText(this, "这条回答没有可朗读的文字", Toast.LENGTH_SHORT).show();
            return;
        }
        if (speakingBtn == btn) {
            stopSpeak();
            return;
        }
        ensureTts();
        stopSpeak();
        int max = TextToSpeech.getMaxSpeechInputLength();
        if (text.length() > max) text = text.substring(0, max);
        if (ttsReady) {
            speakNow(text);
        } else {
            pendingSpeak = text;
        }
        speakingBtn = btn;
        applyChipState(btn, Palette.c(COLOR_TTS), true);
        Toast.makeText(this, "开始朗读，再点一下停止", Toast.LENGTH_SHORT).show();
    }

    private void stopSpeak() {
        if (tts != null) {
            try {
                tts.stop();
            } catch (Exception ignored) {
            }
        }
        pendingSpeak = null;
        if (speakingBtn != null) applyChipState(speakingBtn, (int) speakingBtn.getTag(), false);
        speakingBtn = null;
    }

    private void shareText(String text) {
        if (text == null || text.isEmpty()) return;
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, text);
        try {
            startActivity(Intent.createChooser(i, "分享回答"));
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的分享应用", Toast.LENGTH_SHORT).show();
        }
    }

    /** 针对指定回答重新生成：删除该条及之后的消息，再重新请求 */
    private void regenerateFrom(Message target) {
        if (busy) {
            Toast.makeText(this, "正在生成中…", Toast.LENGTH_SHORT).show();
            return;
        }
        int idx = current.messages.indexOf(target);
        if (idx < 0) {
            regenerate();
            return;
        }
        while (current.messages.size() > idx) {
            current.messages.remove(current.messages.size() - 1);
        }
        renderConversation(current);
        reqNet = false;
        startRequest();
    }

    private void attachLongPress(View v, Message m) {
        if (v == null || m == null) return;
        v.setOnLongClickListener(view -> {
            // 长按这一刻 view 可能已经被流式重绘换掉（父容器 detach），
            // PopupMenu 依赖 anchor 的 WindowToken，这种时候会直接抛 BadTokenException 闪退。
            // 所以这里不再用 PopupMenu，改成不依赖 anchor 的对话框，并且整段兜底。
            try {
                com.nebula.aichat.util.CrashLog.d("ui", "长按菜单 role=" + m.role);
                final String body = cleanBody(m.content);
                boolean assistant = Message.ROLE_ASSISTANT.equals(m.role);
                java.util.List<String> items = new java.util.ArrayList<String>();
                items.add("复制");
                if (assistant) {
                    items.add("重新生成");
                    items.add("标记为有帮助");
                    items.add("标记为需改进");
                }
                items.add("删除这条");
                final String[] arr = items.toArray(new String[0]);
                new NebAlert.Builder(MainActivity.this)
                        .setTitle("这条消息")
                        .setItems(arr, (d, which) -> {
                            try {
                                String t = arr[which];
                                if (t.equals("复制")) {
                                    copyToClipboard(body);
                                } else if (t.equals("重新生成")) {
                                    regenerate();
                                } else if (t.startsWith("标记")) {
                                    m.rating = t.contains("有帮助") ? 1 : -1;
                                    if (store != null) store.save();
                                    Toast.makeText(this, "已标记：" + (m.rating == 1 ? "有帮助" : "需改进"),
                                            Toast.LENGTH_SHORT).show();
                                    renderConversation(current);
                                } else if (t.equals("删除这条")) {
                                    current.messages.remove(m);
                                    if (store != null) store.save();
                                    renderConversation(current);
                                }
                            } catch (Throwable e) {
                                android.util.Log.e("nebula", "长按菜单动作失败", e);
                                Toast.makeText(this, "操作失败：" + e, Toast.LENGTH_SHORT).show();
                            }
                        })
                        .show();
            } catch (Throwable e) {
                android.util.Log.e("nebula", "长按菜单弹出失败", e);
                Toast.makeText(this, "长按菜单打开失败：" + e, Toast.LENGTH_SHORT).show();
            }
            return true;
        });
    }

    private void copyToClipboard(String text) {
        if (text == null) text = "";
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("message", text));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------- 发送 / 流式 ----------------

    // ---------------- 附件：文件 / 图片 ----------------

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, REQ_PICK_FILE);
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    private void pickImage() {
        if (composer != null && !composer.canAddImage()) {
            Toast.makeText(this, "最多添加 " + ComposerBar.MAX_IMAGES + " 张图片",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String m = settings.model == null ? "" : settings.model.toLowerCase(Locale.ROOT);
        if (!m.contains("vl") && !m.contains("vision") && !m.contains("gpt-4o")
                && !m.contains("gemini") && !m.contains("claude")) {
            Toast.makeText(this, "提示：当前模型「" + settings.model
                    + "」可能不支持读图，请换用视觉模型", Toast.LENGTH_LONG).show();
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, REQ_PICK_IMAGE);
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的图片选择器", Toast.LENGTH_SHORT).show();
        }
    }

    private void handlePicked(final int requestCode, Intent data) {
        if (data == null) return;
        final List<Uri> uris = new ArrayList<>();
        ClipData cd = data.getClipData();
        if (cd != null) {
            for (int i = 0; i < cd.getItemCount(); i++) {
                Uri u = cd.getItemAt(i).getUri();
                if (u != null) uris.add(u);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;

        new Thread(() -> {
            int added = 0;
            int binAdded = 0;
            StringBuilder problems = new StringBuilder();
            for (Uri u : uris) {
                String name = displayName(u);
                if (requestCode == REQ_PICK_IMAGE) {
                    if (composer != null && !composer.canAddImage()) {
                        problems.append("图片最多 ").append(ComposerBar.MAX_IMAGES).append(" 张\n");
                        break;
                    }
                    String dataUrl = readImageDataUrl(u);
                    if (dataUrl == null) {
                        problems.append(name).append(" 图片读取失败\n");
                        continue;
                    }
                    final String n = name;
                    final String d = dataUrl;
                    ui.post(() -> {
                        if (composer != null) composer.addImage(d, n);
                    });
                    added++;
                } else {
                    // 从「文件」入口选了图片，仍按图片处理，别落成二进制附件
                    String mime = null;
                    try {
                        mime = getContentResolver().getType(u);
                    } catch (Exception ignored) {
                    }
                    if (mime != null && mime.startsWith("image/")
                            && composer != null && composer.canAddImage()) {
                        String durl = readImageDataUrl(u);
                        if (durl != null) {
                            final String n0 = name;
                            final String d0 = durl;
                            ui.post(() -> {
                                if (composer != null) composer.addImage(d0, n0);
                            });
                            added++;
                            continue;
                        }
                    }
                    String txt = readTextFile(u);
                    if (txt == null) {
                        // 二进制（APK/zip/图片原文件…）：不当文本读，复制进沙箱交给工具
                        File saved = saveToSandbox(u, name);
                        if (saved == null) {
                            problems.append(name).append(" 读取失败（可能有权限限制）\n");
                            continue;
                        }
                        final String n2 = name;
                        final String sp = saved.getAbsolutePath();
                        final long sz = saved.length();
                        ui.post(() -> {
                            if (composer != null) composer.addBinaryFile(n2, sp, sz);
                        });
                        added++;
                        binAdded++;
                        continue;
                    }
                    final String n = name;
                    final String t = txt;
                    final long size = txt.length();
                    ui.post(() -> {
                        if (composer != null) composer.addFile(n, t, size);
                    });
                    added++;
                }
            }
            final int a = added;
            final int b = binAdded;
            final String p = problems.toString().trim();
            ui.post(() -> {
                if (p.isEmpty()) {
                    Toast.makeText(this, b > 0
                                    ? ("已添加 " + a + " 个附件，" + b + " 个文件已存到「AgenCode/上传」，AI 可直接处理")
                                    : ("已添加 " + a + " 个附件"),
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, p, Toast.LENGTH_LONG).show();
                }
            });
        }, "nebula-attach").start();
    }

    /** 联网按钮：面板里放两个滑动开关（联网搜索 / 浏览器自动化）+ 搜索引擎入口 */
    private void showNetMenu() {
        if (composer == null) return;

        int pad = Ui.dp(this, 20);
        int inset = Ui.dp(this, 15);   // 卡片内每一行的左右缩进
        int rowPy = Ui.dp(this, 13);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, Ui.dp(this, 28), pad, Ui.dp(this, 4));
        Ui.dialogHeader(box, this, "联网与浏览",
                "联网搜索让 AI 在回答前检索最新资料；浏览器自动化可以让它自己打开网页读正文。");

        // ---- 第一组：两个能力开关，装进同一张卡片，中间用内缩分隔线断开 ----
        box.addView(Ui.sectionLabel(this, "检索能力"));

        LinearLayout caps = Ui.groupCard(this);
        caps.addView(switchRow("联网搜索",
                "发送前自动检索最新资料，把摘要一起交给 AI",
                composer.isNetOn(), (btn, on) -> composer.setNetOn(on, true), inset, rowPy));
        caps.addView(Ui.rowDivider(this, inset));
        caps.addView(switchRow("浏览器自动化",
                "AI 可自己打开网页读正文，摘要不够时用",
                composer.isBrowserOn(), (btn, on) -> composer.setBrowserOn(on, true), inset, rowPy));
        box.addView(caps);

        // ---- 第二组：引擎选择，单独一张卡片 ----
        box.addView(Ui.spacer(this, 20));
        box.addView(Ui.sectionLabel(this, "搜索引擎"));

        LinearLayout engGroup = Ui.groupCard(this);
        final String engName = WebSearch.ENGINE_DDG.equalsIgnoreCase(settings.searchEngine)
                ? "DuckDuckGo" : "Bing（国内可直连）";

        LinearLayout engRow = Ui.row(this);
        engRow.setPadding(inset, Ui.dp(this, 15), Ui.dp(this, 15), Ui.dp(this, 15));
        engRow.setClickable(true);
        engRow.setBackground(Ui.pressable(this, Ui.rect(this, 0x00000000, 0x00000000, 0f)));
        engRow.setOnClickListener(v -> chooseSearchEngine());

        TextView engNameView = Ui.text(this, engName, 14.5f, Palette.text(this));
        engRow.addView(engNameView, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView arrow = Ui.text(this, "›", 18f, Palette.dim(this));
        engRow.addView(arrow);
        engGroup.addView(engRow);
        box.addView(engGroup);

        new NebAlert.Builder(this)
                .setView(box)
                .setPositiveButton("完成", null)
                .show();
    }

    /** 一行「标题 + 说明 + 滑动开关」 */
    private View switchRow(String title, String sub, boolean checked,
                           CompoundButton.OnCheckedChangeListener l, int px, int py) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px, py, px, py);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15.5f);
        t.setTextColor(Palette.c(C_TEXT));

        TextView st = new TextView(this);
        st.setText(sub);
        st.setTextSize(12.5f);
        st.setTextColor(Palette.c(C_DIM));
        st.setLineSpacing(Ui.dp(this, 4f), 1.0f);
        st.setPadding(0, Ui.dp(this, 4), Ui.dp(this, 8), 0);

        texts.addView(t);
        texts.addView(st);

        SwitchCompat sw = new SwitchCompat(this);
        sw.setChecked(checked);
        if (l != null) sw.setOnCheckedChangeListener(l);

        row.addView(texts, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(sw, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** 选择搜索引擎 */
    private void chooseSearchEngine() {
        final String[] items = {"Bing（国内可直连，推荐）", "DuckDuckGo（国内通常无法直连）"};
        int cur = WebSearch.ENGINE_DDG.equalsIgnoreCase(settings.searchEngine) ? 1 : 0;
        new NebAlert.Builder(this)
                .setTitle("联网搜索引擎")
                .setSingleChoiceItems(items, cur, (d, which) -> {
                    settings.searchEngine = which == 1 ? WebSearch.ENGINE_DDG : WebSearch.ENGINE_BING;
                    settings.save(MainActivity.this);
                    Toast.makeText(MainActivity.this,
                            "搜索引擎：" + (which == 1 ? "DuckDuckGo" : "Bing"),
                            Toast.LENGTH_SHORT).show();
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {
        }
        if (name == null || name.trim().isEmpty()) {
            String s = uri.getLastPathSegment();
            name = s == null ? "附件" : s;
        }
        return name;
    }

    /** 真实路径 → 模型在沙箱（proot / 本地 shell）里都能直接用的路径 */
    private String toSandboxPath(File f) {
        String abs = f.getAbsolutePath();
        // 本地 shell 模式直接用真实路径；只有内置 Linux 模式才需要挂载点别名
        if (!"alpine".equals(settings == null ? "local" : settings.execMode)) return abs;
        try {
            File pub = android.os.Environment.getExternalStorageDirectory();
            if (pub != null) {
                String p = pub.getAbsolutePath();
                if (abs.equals(p)) return "/sdcard";
                if (abs.startsWith(p + "/")) return "/sdcard" + abs.substring(p.length());
            }
            File ext = getExternalFilesDir(null);
            if (ext != null) {
                String e = ext.getAbsolutePath();
                if (abs.equals(e)) return "/sdcard-app";
                if (abs.startsWith(e + "/")) return "/sdcard-app" + abs.substring(e.length());
            }
        } catch (Throwable ignored) {
        }
        return abs;
    }

    /** 二进制附件落盘到 「AgenCode/上传」，返回落地文件；失败返回 null */
    private File saveToSandbox(Uri uri, String name) {
        InputStream in = null;
        java.io.FileOutputStream out = null;
        try {
            File dir = com.nebula.aichat.apk.ArtifactStore.writableSub(this, "上传");
            if (dir == null) return null;
            if (!dir.exists() && !dir.mkdirs()) return null;
            String base = name == null ? "file" : name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
            if (base.isEmpty()) base = "file";
            if (base.length() > 80) base = base.substring(base.length() - 80);
            File f = new File(dir, base);
            if (f.exists()) {
                String stem = base, ext = "";
                int dot = base.lastIndexOf('.');
                if (dot > 0) {
                    stem = base.substring(0, dot);
                    ext = base.substring(dot);
                }
                int k = 2;
                while (f.exists()) {
                    f = new File(dir, stem + "-" + k + ext);
                    k++;
                }
            }
            in = getContentResolver().openInputStream(uri);
            if (in == null) return null;
            out = new java.io.FileOutputStream(f);
            byte[] buf = new byte[16384];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            out.flush();
            long wrote = f.length();
            if (wrote <= 0) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                return null;
            }
            return f;
        } catch (Exception e) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
            try {
                if (out != null) out.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 读取文本类附件；二进制文件返回 null */
    private String readTextFile(Uri uri) {
        InputStream in = null;
        try {
            in = getContentResolver().openInputStream(uri);
            if (in == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            boolean binary = false;
            while ((n = in.read(buf)) > 0) {
                for (int i = 0; i < n; i++) {
                    if (buf[i] == 0) {
                        binary = true;
                        break;
                    }
                }
                if (binary) break;
                bos.write(buf, 0, n);
                total += n;
                if (total > 256 * 1024) break;
            }
            if (binary) return null;
            String text = bos.toString("UTF-8");
            if (text.trim().isEmpty()) return null;
            if (text.length() > MAX_FILE_CHARS) {
                text = text.substring(0, MAX_FILE_CHARS)
                        + "\n…（文件过长，已截断，仅提供前 " + MAX_FILE_CHARS + " 个字符）";
            }
            return text;
        } catch (Exception e) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 图片压缩为 JPEG data URL（最长边 1280，质量 82） */
    private String readImageDataUrl(Uri uri) {
        InputStream in = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            in = getContentResolver().openInputStream(uri);
            if (in == null) return null;
            BitmapFactory.decodeStream(in, null, bounds);
            in.close();
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int maxSide = Math.max(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            while (maxSide / sample > 1280) sample *= 2;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            in = getContentResolver().openInputStream(uri);
            if (in == null) return null;
            Bitmap bmp = BitmapFactory.decodeStream(in, null, opts);
            if (bmp == null) return null;

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 82, bos);
            bmp.recycle();
            String b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
            return "data:image/jpeg;base64," + b64;
        } catch (Throwable e) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 应用「回车键发送消息」偏好。
     *
     * 输入框是多行的（maxLines=6），默认回车换行。开启后做两件事：
     * ① 软键盘右下角换成「发送」键（imeOptions=actionSend），按下即发；
     * ② 物理键盘 / 外接键盘的回车也拦下来直接发。
     * 两种情况都保留 Shift+回车 换行 —— 不然想写多行就没路走了。
     */
    private void applyEnterSendPref() {
        if (etInput == null) return;
        boolean on = settings != null && settings.enterToSend;
        final int noExtract = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI;

        etInput.setImeOptions(on
                ? (noExtract | android.view.inputmethod.EditorInfo.IME_ACTION_SEND)
                : noExtract);

        if (!on) {
            etInput.setOnEditorActionListener(null);
            etInput.setOnKeyListener(null);
            return;
        }

        etInput.setOnEditorActionListener((v, actionId, event) -> {
            boolean isEnter = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND
                    || (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER);
            if (!isEnter) return false;
            if (event != null
                    && (event.getMetaState() & android.view.KeyEvent.META_SHIFT_ON) != 0) {
                return false;   // Shift + 回车 = 换行，交给系统
            }
            if (!busy) sendMessage();
            return true;
        });

        etInput.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode != android.view.KeyEvent.KEYCODE_ENTER) return false;
            if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return true;
            if ((event.getMetaState() & android.view.KeyEvent.META_SHIFT_ON) != 0) return false;
            if (!busy) sendMessage();
            return true;
        });
    }

    private void sendMessage() {
        // 用户自己动手说话 = 新话题，续跑额度重置；
        // 按钮/自动续跑属于同一轮任务，不能重置，否则会无限自动续跑
        if (!viaAutoSend) {
            autoContinueCount = 0;
            awaitingExecution = false;
        }
        viaAutoSend = false;
        if (busy || searching) return;
        String text = etInput.getText().toString().trim();
        boolean hasAttach = composer != null && composer.attachmentCount() > 0;
        if (text.isEmpty() && !hasAttach) return;

        if (text.length() > MAX_FILE_CHARS) {
            Toast.makeText(this, "输入太长了（" + text.length() + " 字，上限 " + MAX_FILE_CHARS
                    + " 字）。长文档请用左下角附件发送，直接粘贴进输入框会拖慢界面。",
                    Toast.LENGTH_LONG).show();
            return;
        }

        if (!settings.isConfigured()) {
            Toast.makeText(this, "请先在设置里填写 API Key", Toast.LENGTH_SHORT).show();
            startActivityForResult(new Intent(this, SettingsActivity.class), REQ_SETTINGS);
            return;
        }

        // 模型全部来自账号拉取，没选过（或换了服务商还没拉）就先把面板打开，别发出去撞 400
        if (Settings.baseModel(settings).isEmpty()) {
            Toast.makeText(this, "还没选模型 · 从账号拉取后选一个", Toast.LENGTH_LONG).show();
            showModelSheet();
            return;
        }

        hideKeyboard();

        final List<ComposerBar.FileAttachment> files = composer == null
                ? new ArrayList<ComposerBar.FileAttachment>() : composer.files();
        final List<String> images = composer == null
                ? new ArrayList<String>() : composer.images();
        // 只有用户真的输入了内容才检索，避免拿空问题去搜。
        // 只开「浏览器自动化」时也先搜一次：AI 需要候选链接才能往下读，不能让它瞎猜 URL。
        final boolean useNet = composer != null
                && (composer.isNetOn() || composer.isBrowserOn()) && !text.isEmpty();
        final String askText;
        if (!text.isEmpty()) {
            askText = text;
        } else if (useNet) {
            askText = "请检索并总结相关的最新信息";
        } else if (!images.isEmpty()) {
            askText = "请描述并分析这张图片";
        } else {
            boolean hasBinary = false;
            for (ComposerBar.FileAttachment f : files) {
                if (f.path != null) hasBinary = true;
            }
            askText = hasBinary
                    ? "我上传了文件到沙箱（见附件路径），请先确认文件信息，再告诉我可以怎么处理"
                    : "请阅读并总结我上传的文件内容";
        }

        // 纯文本才记重试原文：带附件/图片的那轮重发会丢内容，宁可让用户自己再来一次
        lastUserAsk = (files.isEmpty() && images.isEmpty()) ? text : null;

        etInput.setText("");
        if (composer != null) composer.clearAttachments();

        if (useNet) {
            searching = true;
            btnSend.setEnabled(false);
            Toast.makeText(this, "正在联网检索…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                final String result = WebSearch.search(askText, settings.searchEngine);
                ui.post(() -> {
                    searching = false;
                    btnSend.setEnabled(true);
                    deliver(askText, files, images, result, true);
                });
            }, "nebula-web-search").start();
        } else {
            deliver(askText, files, images, null, false);
        }
    }

    /**
     * 组装「联网结果 + 附件全文 + 用户问题」为真正发给模型的内容，
     * 气泡里只展示用户问题与附件名称，避免聊天记录被全文淹没。
     */
    private void deliver(String text, List<ComposerBar.FileAttachment> files,
                         List<String> images, String searchResult, boolean useNet) {
        if (searchResult != null && searchResult.isEmpty()) searchResult = null;
        if (useNet && searchResult == null) {
            Toast.makeText(this, "联网检索没有结果，已直接发送", Toast.LENGTH_SHORT).show();
        }

        // 发之前确认附件还在：文件被删/权限变动时立刻告诉用户，而不是让 AI 去猜
        if (files != null) {
            for (ComposerBar.FileAttachment f : files) {
                if (f.path == null) continue;
                File chk = new File(f.path);
                if (!chk.exists() || !chk.isFile() || chk.length() <= 0) {
                    Toast.makeText(this, "附件「" + f.name + "」已不在了，请重新上传：\n" + f.path,
                            Toast.LENGTH_LONG).show();
                }
            }
        }

        StringBuilder api = new StringBuilder();
        StringBuilder brief = new StringBuilder();
        if (searchResult != null) {
            api.append("[联网搜索结果：请优先依据以下最新资料回答，资料不足时明确说明]\n").append(searchResult).append("\n\n");
        }
        for (ComposerBar.FileAttachment f : files) {
            if (f.path != null) {
                String sp = toSandboxPath(new File(f.path));
                brief.append("<file name=\"").append(f.name).append("\" path=\"").append(sp)
                        .append("\" binary=\"true\"/>\n");
                api.append("<file name=\"").append(f.name).append("\" path=\"")
                        .append(sp)
                        .append("\" bytes=\"").append(f.size).append("\" binary=\"true\"/>\n")
                        .append("（二进制文件，内容未内联。请用 bash_exec / read_file / apk_unpack 等工具按上面的路径实际操作，"
                                + "不要凭空猜测其内容。）\n");
                if (f.name.toLowerCase(Locale.ROOT).endsWith(".apk")) {
                    api.append("（这是一个 APK：需要看内部结构就用 apk_unpack 解包，改完用 apk_repack 回编译并自动签名。）\n");
                    api.append("（本机是 Android API ").append(android.os.Build.VERSION.SDK_INT)
                            .append("，装机前这几个坑必须一起处理，否则改出来装不上：\n")
                            .append("· versionCode 要比手机上已装的更大，否则算降级被拒；\n")
                            .append("· 带 intent-filter 的组件必须有 android:exported（API 31+ 硬要求，apk_repack 会自动补）；\n")
                            .append("· resources.arsc 不能压缩且 4 字节对齐（API 30+ 硬要求，apk_repack 会自动修）；\n")
                            .append("· targetSdk 不能低于 ")
                            .append(android.os.Build.VERSION.SDK_INT >= 35 ? "24" : "23")
                            .append("；签名会换成新密钥，覆盖安装会被判签名冲突，需要先卸载原应用。\n")
                            .append("改完必须真的跑通 apk_repack，不要只改 smali 反复试。）\n");
                }
                api.append("\n");
            } else {
                api.append("<file name=\"").append(f.name).append("\">\n")
                        .append(f.text).append("\n</file>\n\n");
            }
        }
        api.append(text);

        StringBuilder bubble = new StringBuilder(text);
        if (searchResult != null) bubble.append(NET_MARK);
        for (ComposerBar.FileAttachment f : files) {
            bubble.append(f.path != null ? "\n📦 " : "\n📄 ").append(f.name);
        }
        for (int i = 0; i < images.size(); i++) {
            bubble.append("\n🖼 图片").append(images.size() > 1 ? (" " + (i + 1)) : "");
        }

        if (current.messages.isEmpty()) {
            chatContainer.removeAllViews();
        }

        Message userMsg = new Message(Message.ROLE_USER, bubble.toString());
        userMsg.apiContent = api.toString();
        if (brief.length() > 0) userMsg.attachNote = brief.toString().trim();
        if (!images.isEmpty()) userMsg.imageUrls = new ArrayList<>(images);
        current.messages.add(userMsg);
        if (current.messages.size() == 1) {
            current.autoTitle();
            String t2 = Conversation.cleanTitle(current.title);
            tvTitle.setText(t2.isEmpty() ? "AgenCode" : t2);
        }
        addUserBubble(userMsg);
        current.touch();
        store.save();
        reqNet = (searchResult != null);
        updateSubtitle();

        startRequest();
    }

    /**
     * 把手机当前时间作为系统上下文注入。
     * 模型自身不知道"今天"是哪天，不注入就会把星期/日期答错。
     */
    private String timeContext() {
        TimeZone tz = TimeZone.getDefault();
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy年M月d日 EEEE HH:mm", Locale.CHINA);
        fmt.setTimeZone(tz);
        String zone;
        try {
            zone = tz.getDisplayName(false, TimeZone.SHORT, Locale.CHINA);
        } catch (Exception e) {
            zone = tz.getID();
        }
        return "【当前时间】现在是 " + fmt.format(new Date()) + "（" + zone + "）。"
                + "凡涉及今天几号、星期几、现在几点、以及需要判断时效性的问题，一律以此时间为准，"
                + "不要依据历史消息里的时间或你的训练数据推断。";
    }

    /**
     * 任务超过 3 秒才拉起前台服务：
     * 短问答不闪通知，长回答/长命令期间切到后台也不会被系统回收
     * （没有前台服务时，退到后台被回收 = 无堆栈的"自动退出"）。
     */
    private final Runnable keepAliveTick = new Runnable() {
        @Override
        public void run() {
            KeepAliveService.startBusy(MainActivity.this);
        }
    };

    /**
     * 让前台服务跟设置项的"后台常驻通知"保持一致。
     * 开启 → 空闲态常驻通知（状态栏能看到"后台运行中"）；关闭 → 撤掉（长任务时仍会临时拉起）。
     * busy 期间不插手，交给 keepAliveTick 管，避免把"正在处理"刷回"后台运行中"。
     */
    private void syncAliveService() {
        if (busy) return;
        try {
            if (settings != null && settings.aliveNotify) KeepAliveService.startIdle(this);
            else KeepAliveService.stop(this);
        } catch (Throwable ignored) {
        }
    }

    private static final int REQ_POST_NOTIF = 4101;

    /**
     * Android 13+ 的通知是运行时权限，不申请就一直是"拒绝"状态：
     * 前台服务照跑，但那条常驻通知会被系统直接丢掉 —— 用户看到的就是"后台在跑，状态栏什么都没有"。
     * 所以进应用时补一次申请（已授予/低版本直接跳过）。
     */
    private void ensureNotifPermission() {
        try {
            if (android.os.Build.VERSION.SDK_INT < 33) return;
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return;
            }
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_POST_NOTIF);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req != REQ_POST_NOTIF) return;
        boolean ok = res != null && res.length > 0
                && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (ok) {
            syncAliveService();
        } else {
            Toast.makeText(this, "没给通知权限：后台仍在跑，但状态栏不会显示（设置 → 权限与后台 → 通知设置 里可开）",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void startRequest() {
        busy = true;
        try {
            ui.removeCallbacks(keepAliveTick);
            ui.postDelayed(keepAliveTick, 3000L);
        } catch (Throwable ignored) {
        }
        reqStartAt = SystemClock.uptimeMillis();
        toolTraceBox = null;
        toolTraceList = null;
        toolScroll = null;
        toolTraceCount = null;
        toolRows.clear();
        procGroups.clear();
        toolRowNames.clear();
        toolRowDone.clear();
        toolRowStart.clear();
        toolRowArgs.clear();
        toolRowDetail.clear();
        toolRowState.clear();
        streamTaskCard = null;
        stopToolTicker();
        // 从发出请求这一刻就开始读秒：等首包的那几秒是最像"卡死"的时候，
        // 以前 ticker 在这里是停的，整块界面一动不动
        roundActive = true;
        startToolTicker();
        toolUserTouched = false;
        toolFoldBtn = null;
        toolTraceCollapsed = false;
        if (tools == null) {
            tools = new ToolRegistry(this);
            // 让 AI 能真的在终端里执行命令（bash_exec），执行过程同步回显到终端页
            ShellToolProvider.register(tools, this);
            bindShellMirror();
        }
        // 拉取 MCP 工具（无服务器时几乎零开销）；失败不影响聊天
        try {
            tools.loadMcp();
        } catch (Exception ignored) {
        }
        client.tools = tools;
        com.nebula.aichat.util.CrashLog.d("send", "发起请求 base=" + settings.baseUrl
                + " model=" + settings.model
                + " stream=" + settings.stream
                + " tools=" + (tools != null && tools.hasTools())
                + " 历史=" + current.messages.size() + "条"
                + " 输入=" + etInput.getText().length() + "字");
        streamStale = false;
        setSendState(true);
        scrollToBottom(true);

        // 每轮都允许重新自动弹确认面板：模型两轮问同样的问题也该再问一次，
        // 否则用户看到的就是「停下来没反应」
        lastAutoConfirmKey = null;
        turnToolCalls = 0;
        pendingChangeBlock = null;
        streamBuf = new StringBuilder();
        lastRenderLen = 0;
        thinkBuf = new StringBuilder();
        thinkHost = null;
        toolHost = null;
        runBannerWrap = null;
        runBanner = null;
        stopBannerAnim();
        runBannerHair = null;
        runBannerIcon = null;
        runBannerText = null;
        runBannerSub = null;
        runBannerFlow = null;
        runBannerDotWrap = null;
        runBannerRailBg = null;
        runBannerRailDot = null;
        runBannerBox = null;
        ripples = null;
        runBannerMeta = null;
        runBannerTime = null;
        runRabbit = null;
        runBannerTimeTag = null;
        runBannerLine = null;
        runBannerLineWrap = null;
        lineAnim = null;
        statusRow = null;
        statusRowBg = null;
        streamMessage = new Message(Message.ROLE_ASSISTANT, "");
        thinkAll = new StringBuilder();
        streamContent = addAiBubble(streamMessage, true);
        attachLongPress(streamContent, streamMessage);
        // 时间线复位：正文段与工具行都从零开始，按发生顺序交错往下排
        tlSeg = null;
        tlSegR = null;
        tlSegStart = 0;
        thinkSegStart = 0;
        thinkSegAt = SystemClock.uptimeMillis();
        tlSteps = new java.util.ArrayList<>();
        toolRowStep.clear();
        final MarkdownRenderer renderer = new MarkdownRenderer(this, streamContent, msgSp);
        renderer.setOnConfirmClick(rows -> openConfirmSheet(rows));
        renderer.setOnConfirmSubmit(msg -> submitConfirmCard(msg));
        streamRenderer = renderer;
        // 正文一生成就挂呼吸光标：工具行有光扫，写正文时一个工具都没跑，没有光标就分不清"还在写"和"写完了"
        renderer.setStreaming(true);

        // 传给模型的历史（占位 assistant 消息尚未加入列表，故取全部）
        List<Message> history = new ArrayList<>();
        int limit = current.messages.size();
        int start = Math.max(0, limit - 24);
        for (int i = start; i < limit; i++) history.add(current.messages.get(i));

        String skillInject = com.nebula.aichat.skill.SkillStore.buildIndex(this);
        client.extraSystem = STYLE_HINT + "\n\n" + ECONOMY_HINT + "\n\n" + TASK_HINT
                + "\n\n" + CONFIRM_HINT
                + "\n\n" + CONCLUSION_HINT
                + (settings.browserEnabled ? "\n\n" + WEB_HINT : "")
                + (skillInject == null || skillInject.isEmpty() ? "" : "\n\n" + skillInject)
                + "\n\n" + com.nebula.aichat.apk.SandboxBridge.describe(this);
        // 时间走 tailHint（挂在最后一条用户消息上），不进 system：
        // system 多一个字，后面所有历史的前缀缓存就全废了。
        client.tailHint = timeContext();
        // 发车前把这一轮的上下文占用刷给用户看
        refreshUsage(history);
        client.send(settings, history, new ChatClient.Listener() {
            @Override
            public void onStart() {
                if (streamStale) return;
                com.nebula.aichat.util.CrashLog.d("stream", "已连上，开始接收");
                renderStream(renderer, true);
            }

            @Override
            public void onDelta(String piece) {
                if (streamStale || streamBuf == null) return;
                streamBuf.append(piece);
                if (streamBuf.length() <= piece.length()) {
                    com.nebula.aichat.util.CrashLog.d("stream", "首块到达 len=" + piece.length());
                }
                // 每 1.5 秒把半成品落一次盘：切后台被系统回收也能把回答接回来
                long nowUp = SystemClock.uptimeMillis();
                if (current != null && current.id != null && nowUp - lastDraftAt > 1500) {
                    lastDraftAt = nowUp;
                    try {
                        store.saveDraft(current.id, streamBuf.toString());
                    } catch (Throwable ignored) {
                    }
                }
                scheduleRender(renderer);
            }

            @Override
            public void onReasoning(String piece) {
                if (streamStale) return;
                if (thinkBuf == null) thinkBuf = new StringBuilder();
                if (thinkBuf.length() < 20000) thinkBuf.append(piece);
                if (thinkAll == null) thinkAll = new StringBuilder();
                // 32000 字兜底：真到这个量说明思考已经失控，再攒下去每轮输入都要爆
                if (thinkAll.length() < 32000) thinkAll.append(piece);
                // 边想边显示：思考块立起来、标题行走光、正文跟着长
                appendLiveThink(piece);
                scrollToBottom(false);
            }

            @Override
            public void onContentReset(String clean) {
                // DSML 文本调用被识别成真调用后，把气泡里已经显示出来的
                // <｜DSML｜invoke ...> 标签抹掉，只留模型真正说出口的话
                if (streamStale || streamBuf == null) return;
                streamBuf.setLength(0);
                if (clean != null) streamBuf.append(clean);
                scheduleRender(renderer);
            }

            @Override
            public void onDone(String full) {
                com.nebula.aichat.util.CrashLog.d("stream", "完成 len="
                        + (full == null ? -1 : full.length())
                        + " 本地缓冲=" + (streamBuf == null ? -1 : streamBuf.length()));
                if (streamStale) return;
                String text = (full == null || full.isEmpty())
                        ? (streamBuf == null ? "" : streamBuf.toString()) : full;
                finishStream(true, text, null, renderer);
                // 用户已经切到别的应用时，回一条通知把他叫回来
                notifyIfBackground(false, text);
                // 这轮已经消耗了 token，顺手刷新顶栏余额
                syncBalance(true);
            }

            @Override
            public void onError(String message) {
                com.nebula.aichat.util.CrashLog.d("stream", "出错: " + message);
                if (streamStale) return;
                finishStream(false, streamBuf == null ? "" : streamBuf.toString(), message, renderer);
                notifyIfBackground(true, message);
            }

            @Override
            public void onToolCall(final String name, final String argsJson) {
                turnToolCalls++;
                com.nebula.aichat.util.CrashLog.d("tool", "调用 " + name
                        + " 参数=" + (argsJson == null ? 0 : argsJson.length()) + "字");
                ui.post(() -> addToolCall(name, argsJson));
            }

            @Override
            public void onToolResult(final String name, final boolean ok, final String result) {
                com.nebula.aichat.util.CrashLog.d("tool", "返回 " + name + " ok=" + ok
                        + " 结果=" + (result == null ? 0 : result.length()) + "字");
                ui.post(() -> {
                    addToolResult(name, ok, result);
                    if (ok && result != null && result.contains("%%CHANGES%%")) {
                        int st = result.indexOf("%%CHANGES%%");
                        int en = result.indexOf("%%END%%", st);
                        if (en > st) {
                            pendingChangeBlock = result.substring(st, en + "%%END%%".length());
                        }
                    }
                });
            }

            @Override
            public void onToolLimit(final String why) {
                com.nebula.aichat.util.CrashLog.d("tool", "轮次用尽，转收尾总结: " + why);
                ui.post(() -> pendingToolLimit = why);
            }

            @Override
            public void onToolRetryHint(final String note) {
                // 一轮工具全空手时，状态行要告诉用户「还在继续」，不是卡死
                com.nebula.aichat.util.CrashLog.d("tool", "自动续跑: " + note);
                ui.post(() -> {
                    // 已经自动接着跑了，就别再把「接着往下做」按钮挂出来让用户白点一次
                    pendingToolLimit = null;
                    if (note != null && !note.isEmpty()) setStatusBusy(note);
                });
            }

            @Override
            public void onRoundHint(final int round, final int max) {
                ui.post(() -> setStatusBusy("正在调用工具…"));
            }

            @Override
            public void onToolBeat(final String name, final long seconds) {
                ui.post(() -> setStatusBusy("正在执行 " + name + " · 已 " + seconds + " 秒"));
            }
        });
    }
    // 旧的「独立固定高度工具卡片」已废除：工具行现在直接内联在正文流里，
    // 与文字按发生顺序交错排列（见 newToolRow / renderTimeline）。

    /** 重排整条轨迹：谁不是第一条、谁是最后一条，决定竖线从哪起、到哪淡出 */
    private void refreshTrack() {
        int n = toolRows.size();
        for (int i = 0; i < n; i++) {
            Object o = toolRows.get(i).getTag();
            if (!(o instanceof View)) continue;
            if (!(((View) o).getBackground() instanceof com.nebula.aichat.ui.TrackBg)) continue;
            com.nebula.aichat.ui.TrackBg t =
                    (com.nebula.aichat.ui.TrackBg) ((View) o).getBackground();
            t.setHasPrev(i > 0);
            t.setTailFade(i == n - 1);
        }
    }

    private void addToolCall(String name, String argsJson) {
        if (streamStale) return;
        // 上一次思考到此为止：先按段落存进时间线，再定格，后面的思考另起一段落到本行下面
        takeThinkStep();
        stopLiveThink();
        liveThinkBox = null;
        // 关键一步：先把已写的正文收尾成独立一段。
        // 这样工具行才是插在「刚才那段文字」和「一会儿要写的文字」之间，
        // 形成 文字 → 工具行 → 文字 的交错流水，而不是全部堆在正文前面。
        sealTextSeg();
        String brief = briefText(argsJson);
        TextView row = newToolRow();
        toolRows.add(row);
        toolRowNames.add(name);
        refreshToolSeq(name);
        toolRowDone.add(Boolean.FALSE);
        toolRowStart.add(SystemClock.uptimeMillis());
        toolRowArgs.add(argsJson == null ? "" : argsJson);
        toolRowDetail.add("");
        toolRowState.add(ST_RUN);

        com.nebula.aichat.data.Message.Step st = new com.nebula.aichat.data.Message.Step();
        st.type = com.nebula.aichat.data.Message.Step.TOOL;
        st.text = name;
        st.arg = brief;
        toolRowStep.add(st);
        if (tlSteps != null) tlSteps.add(st);

        bindToolRowIndex(row, toolRows.size() - 1);
        fillToolRow(row, ST_RUN, name, brief, null, 0);
        updateToolExplain(row, toolExplain(name, argsJson, null, true) + " …",
                Palette.c(C_RUN), true);
        setExplainMsOnRow(row, 1, ST_RUN);
        trimToolRows();
        refreshTrack();
        refreshToolGroup();
        updateToolHead();
        updateRunBanner();
        setStatusBusy("正在执行 " + name + " …");
        scrollToBottom(false);
        startToolTicker();
    }

    private void addToolResult(String name, boolean ok, String result) {
        if (streamStale) return;
        int idx = -1;
        for (int k = toolRowDone.size() - 1; k >= 0; k--) {
            if (!toolRowDone.get(k) && name.equals(toolRowNames.get(k))) {
                idx = k;
                break;
            }
        }
        String rawDetail = (ok ? "" : "出错 · ") + (result == null ? "" : result);
        String detail = briefText(result);
        if (!ok) detail = "出错 · " + detail;
        if (idx >= 0) {
            toolRowDone.set(idx, Boolean.TRUE);
            if (idx < toolRowState.size()) toolRowState.set(idx, ok ? ST_OK : ST_FAIL);
            if (idx < toolRowDetail.size()) toolRowDetail.set(idx, rawDetail);
            long ms = SystemClock.uptimeMillis() - toolRowStart.get(idx);
            final int fi = idx;
            final boolean fok = ok;
            final String fdetail = detail;
            final long fms = ms;
            /*
             * 最小可见时长。本地工具（读文件、列目录、跑条命令）几十毫秒就结束，
             * addToolCall 的绿和 addToolResult 的灰几乎同时投递到 UI 线程，
             * 中间只隔一两帧——绿在屏幕上存在不到 0.1 秒，眼睛根本抓不到，
             * 于是每条都"一出生就是完成色"，看起来像从来没绿过。
             * 这里让运行色至少停留 RUN_MIN_MS：没跑满的先把绿留住，再翻成完成色。
             */
            long hold = Math.max(0, RUN_MIN_MS - ms);
            if (hold > 0) {
                ui.postDelayed(() -> {
                    if (streamStale || fi >= toolRows.size()) return;
                    fillToolRow(toolRows.get(fi), fok ? ST_OK : ST_FAIL, name, null, fdetail, fms);
                    updateToolExplain(toolRows.get(fi),
                            toolExplain(name, fi < toolRowArgs.size() ? toolRowArgs.get(fi) : "",
                                    fdetail, fok),
                            fok ? Palette.c(C_TEXT2) : Palette.c(C_FAIL));
                    setExplainMsOnRow(toolRows.get(fi), fms, fok ? ST_OK : ST_FAIL);
                }, hold);
            } else {
                fillToolRow(toolRows.get(idx), ok ? ST_OK : ST_FAIL, name, null, detail, ms);
                updateToolExplain(toolRows.get(idx),
                        toolExplain(name, toolRowArgs.get(idx), detail, ok),
                        ok ? Palette.c(C_TEXT2) : Palette.c(C_FAIL));
                setExplainMsOnRow(toolRows.get(idx), ms, ok ? ST_OK : ST_FAIL);
            }
            refreshToolDetailIfOpen(idx);
            updateStepResult(idx, ok, detail, ms);
            // 一个工具跑完 = 又做完一步：把顶部步骤卡的下一项勾上
            tickTaskProgress(ok);
        } else {
            TextView row = newToolRow();
            toolRows.add(row);
            toolRowNames.add(name);
            refreshToolSeq(name);
            toolRowDone.add(Boolean.TRUE);
            toolRowStart.add(SystemClock.uptimeMillis());
            toolRowArgs.add("");
            toolRowDetail.add(rawDetail);
            toolRowState.add(ok ? ST_OK : ST_FAIL);
            bindToolRowIndex(row, toolRows.size() - 1);
            fillToolRow(row, ok ? ST_OK : ST_FAIL, name, null, detail, 0);
            updateToolExplain(row, toolExplain(name, "", detail, ok),
                    ok ? Palette.c(C_TEXT2) : Palette.c(C_FAIL));
            // 没有对应的调用行（异常路径）也要补一条，时间线才不会缺口
            com.nebula.aichat.data.Message.Step st = new com.nebula.aichat.data.Message.Step();
            st.type = com.nebula.aichat.data.Message.Step.TOOL;
            st.text = name;
            st.res = detail;
            st.ok = ok;
            toolRowStep.add(st);
            if (tlSteps != null) tlSteps.add(st);
        }
        trimToolRows();
        refreshTrack();
        refreshToolGroup();
        updateToolHead();
        // 命令跑完了但整轮还没结束：状态行切到「整理结果」，让人知道还在等模型接着写
        if (pendingToolCount() == 0) setStatusBusy("命令已完成 · 整理回答中…");
        scrollToBottom(false);
    }

    /**
     * 单行渲染：状态符 + 工具名 + 参数/结果摘要 + 耗时，全部挤在一行里。
     * 窗口只有 3 行高，一旦换行就会把气泡重新撑长，所以这里绝不换行。
     */
    /**
     * 行首图标：状态 + 语义两层。
     *
     * <p>外层「轨道环」是自绘 Drawable，自己匀速转一圈 900ms；里层的核心图标按工具类型换
     * （终端 / 文件 / 搜索 / 网络 / 包裹 / 星芒 / 六边形），只做缩放呼吸、不参与旋转。
     * 语义图标一旦跟着转，转完就只剩一团线，看不出这一步到底在干什么，
     * 所以环放 background 层、图标留 foreground 层，两层各跑各的节奏。
     *
     * <p>结束态把环收成静止圆盘：转动停下＝进程结束。成功保留语义图标（配绿色），
     * 失败换叉、中止换横线，颜色仍由状态决定，扫一眼就知道这一步成没成。
     */
    private void updateToolMark(TextView tv, int st, int color, String toolName) {
        if (tv == null) return;
        View parent = (View) tv.getParent();
        if (!(parent instanceof ViewGroup)) return;
        ViewGroup head = (ViewGroup) parent;
        if (head.getChildCount() == 0) return;
        View v0 = head.getChildAt(0);
        if (!(v0 instanceof ImageView)) return;
        ImageView iv = (ImageView) v0;

        // 先收掉上一轮所有动画：旋转停在随机角度、呼吸停在半缩状态，
        // 静止态看着就像卡住了，比没有动画还糟
        Object t = iv.getTag();
        if (t instanceof ObjectAnimator[]) {
            for (ObjectAnimator a : (ObjectAnimator[]) t) {
                if (a != null) a.cancel();
            }
        }
        iv.setTag(null);
        iv.setRotation(0f);
        iv.setScaleX(1f);
        iv.setScaleY(1f);
        iv.setAlpha(1f);
        iv.animate().cancel();

        boolean running = st == ST_RUN;
        int icon = callIconFor(toolName);

        // 轨道环画在 background 层：它自己转，核心图标不跟着转
        RingDrawable ring = new RingDrawable(color);
        iv.setBackground(ring);
        iv.setColorFilter(color);

        if (running) {
            iv.setImageResource(icon);
            // 环匀速转属于"动"不是"闪"，留着；图标的缩放+透明度呼吸去掉了。
            // 之前卡片在闪、文字在扫光、图标在呼吸、外围在跑马灯，四五处同时动，
            // 眼睛累还看不出重点——现在"在跑"只由输入框上方那条运行条表达
            ring.startSpin();
            return;
        }

        // 结束态：环收成静止圆盘
        ring.stopSpin();
        ring.setShowArc(false);
        iv.setImageResource(st == ST_OK ? icon
                : st == ST_FAIL ? R.drawable.ic_tool_fail : R.drawable.ic_tool_abort);
        if (st == ST_OK) {
            // 成功时补一个回弹：比干切状态有回应，又不至于像失败那样抢眼
            iv.setScaleX(0.6f);
            iv.setScaleY(0.6f);
            iv.animate().scaleX(1f).scaleY(1f).setDuration(320)
                    .setInterpolator(new OvershootInterpolator(2.2f)).start();
        }
    }

    /**
     * 工具名 → 语义矢量图标。
     *
     * <p>顺序有讲究：「web_search」既含 web 又含 search，这里要的是放大镜而不是地球，
     * 所以检索分支必须排在地球分支前面。认不出的一律落六边形通用图标，绝不留空位。
     */
    private static int callIconFor(String name) {
        String n = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        if (n.contains("search") || n.contains("find") || n.contains("grep")
                || n.contains("query") || n.contains("lookup")) {
            return R.drawable.ic_call_search;
        }
        if (n.contains("shell") || n.contains("bash") || n.contains("term")
                || n.contains("exec") || n.contains("run") || n.contains("cmd")) {
            return R.drawable.ic_call_terminal;
        }
        if (n.contains("http") || n.contains("fetch") || n.contains("web")
                || n.contains("browse") || n.contains("url") || n.contains("net")) {
            return R.drawable.ic_call_web;
        }
        if (n.contains("apk") || n.contains("build") || n.contains("gradle")
                || n.contains("sign") || n.contains("install") || n.contains("pack")) {
            return R.drawable.ic_call_apk;
        }
        if (n.contains("skill") || n.contains("mcp") || n.contains("plugin")) {
            return R.drawable.ic_call_skill;
        }
        if (n.contains("file") || n.contains("read") || n.contains("write")
                || n.contains("edit") || n.contains("patch") || n.contains("cat")
                || n.contains("list") || n.contains("dir") || n.contains("doc")) {
            return R.drawable.ic_call_file;
        }
        return R.drawable.ic_call_default;
    }

    /**
     * 正文上方那一行：左边「⚡ 当前条数 ÷ 已总结条数」，右边「体积/上限 (百分比)」。
     * 两段分开摆——左边看对话长短，右边看这次请求还剩多少空间，各看各的不挤在一起。
     * 「当前条数」是这次真正发给模型的窗口条数，「已总结」是被窗口裁掉的历史条数。
     */
    /** 最近一次用量快照：长按用量行时用它出成本明细 */
    private int[] lastUsage = null;

    /**
     * 成本明细：把「这一轮到底发了多少、命中多少缓存、回来了多少」摊开给用户看。
     * 界面上那行 ⚡ 只是占用率，看不出花费，所以长按补一个明细。
     */
    private void showCostDetail() {
        try {
            int[] u = lastUsage;
            if (u == null || u.length < 9) {
                Toast.makeText(this, "还没有可统计的用量", Toast.LENGTH_SHORT).show();
                return;
            }
            int used = u[0], limit = u[1];
            int outT = u[5], inT = u[6], hit = u[7], miss = u[8];
            int cachePct = (hit + miss > 0) ? (hit * 100 / (hit + miss)) : -1;
            StringBuilder sb = new StringBuilder();
            sb.append("本轮输入  ").append(inT).append(" token\n");
            sb.append("  · 命中缓存  ").append(hit).append("\n");
            sb.append("  · 未命中    ").append(miss);
            if (cachePct >= 0) sb.append("   (").append(cachePct).append("% 命中)");
            sb.append("\n本轮输出  ").append(outT).append(" token\n");
            sb.append("上下文占用  ").append(used).append(" / ").append(limit)
              .append("  (").append(limit > 0 ? Math.min(100, used * 100 / limit) : 0).append("%)")
              .append(u.length > 4 && u[4] == 1 ? "  服务端真值" : "  本地估算").append("\n");
            if (inT + outT > 0) {
                // 按 deepseek-chat 公开价粗算：命中 0.5 / 未命中 2 / 输出 8（元每百万 token）
                double yuan = (hit * 0.5 + miss * 2.0 + outT * 8.0) / 1000000.0;
                sb.append("\n按 deepseek-chat 价粗算 ≈ ¥").append(String.format(java.util.Locale.US, "%.4f", yuan));
                sb.append("\n（单价以官方为准，命中缓存通常便宜 5~10 倍）");
            }
            if (cachePct >= 0 && cachePct < 30 && inT > 3000) {
                sb.append("\n\n缓存命中偏低：这一轮几乎全价计费，多出现在长时间中断后第一次对话。");
            }
            new android.app.AlertDialog.Builder(this)
                    .setTitle("本轮用量明细")
                    .setMessage(sb.toString())
                    .setPositiveButton("知道了", null)
                    .show();
        } catch (Throwable ignored) {
        }
    }

    private void refreshUsage(List<Message> history) {
        try {
            if (tvUsage == null || tvUsageCount == null) return;
            int[] u = client.usageSnapshot(history);
            int active = (u != null && u.length > 2) ? u[2] : 0;
            if (u == null || u.length < 2 || u[1] <= 0 || active <= 0) {
                if (usageRow != null) usageRow.setVisibility(View.GONE);
                fitSubtitle();
                return;
            }
            lastUsage = u;
            int dropped = u.length > 3 ? u[3] : 0;
            int pct = (int) Math.min(100, Math.round(u[0] * 100f / u[1]));

            // 左段青色（对话条数），右段灰/黄/橙（体积占比）：两条信息用两套颜色，扫一眼就能分开
            String cnt = "💬 " + active + "条";
            String sum = " ÷ 已总结 " + dropped;
            SpannableStringBuilder head = new SpannableStringBuilder(cnt + sum);
            head.setSpan(new ForegroundColorSpan(0xFF4FC3D9), 0, cnt.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            head.setSpan(new ForegroundColorSpan(0xFF2F7C8E), cnt.length(), head.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            tvUsageCount.setText(head);

            tvUsage.setText("⚡ " + u[0] + "/" + u[1] + " (" + pct + "%)");
            // 70% 开始泛黄、85% 转橙：快满了要能一眼看出来
            tvUsage.setTextColor(pct >= 85 ? 0xFFFFB020
                    : pct >= 70 ? 0xFFD8C08A : Palette.c(0xFF6E7A8C));

            if (usageRow != null) {
                usageRow.setVisibility(View.VISIBLE);
                // 长按看明细：只挂一次监听，避免每次刷新都新建对象
                if (!"cost_detail".equals(usageRow.getTag())) {
                    usageRow.setTag("cost_detail");
                    usageRow.setOnLongClickListener(new View.OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View v) {
                            showCostDetail();
                            return true;
                        }
                    });
                }
            }
            fitSubtitle();
        } catch (Throwable ignored) {
        }
    }

    /** 回复结束/出错：应用不在前台就发一条完成提醒，声音和振动按设置走 */
    private void notifyIfBackground(boolean fail, String text) {
        try {
            if (fgVisible) return;              // 前台当场就能看到结果，不打扰
            Settings now = Settings.load(this); // 读最新设置，改了开关立即生效
            if (now == null || !now.doneNotify) return;
            com.nebula.aichat.util.DoneNotify.send(this, fail, text,
                    now.notifySound, now.notifyVibrate);
        } catch (Throwable ignored) {
        }
    }

    /** 每行的中文句子：key 用视图本身，行被丢掉时随 WeakHashMap 自动回收 */
    private final java.util.WeakHashMap<View, String> toolRowZh = new java.util.WeakHashMap<>();

    private String zhOf(View row) {
        if (row == null) return "";
        String s = toolRowZh.get(row);
        return s == null ? "" : s;
    }

    private void setZh(View row, String zh) {
        if (row == null) return;
        if (zh == null || zh.isEmpty()) toolRowZh.remove(row);
        else toolRowZh.put(row, zh);
    }

    /** 中文或状态变了就原地重画这一行：沿用已存的工具名、参数、结果 */
    private void repaintToolRow(TextView tv) {
        if (tv == null) return;
        int i = toolRows.indexOf(tv);
        if (i < 0) return;
        int st = i < toolRowState.size() ? toolRowState.get(i) : ST_RUN;
        String name = i < toolRowNames.size() ? toolRowNames.get(i) : "";
        String args = i < toolRowArgs.size() ? toolRowArgs.get(i) : "";
        String res = i < toolRowDetail.size() ? toolRowDetail.get(i) : "";
        String detail = res == null ? "" : res;
        // 原始结果里带「出错 · 」前缀，行内摘要不要它，失败靠颜色表达
        if (detail.startsWith("出错 · ")) detail = detail.substring(4);
        detail = briefText(detail);
        fillToolRow(tv, st, name, args, detail, 0);
    }

    /** 秒级时间戳：日志窗里每条事件前面挂一下，滚动感立刻就有了 */
    private String hhmmssNow() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return String.format(java.util.Locale.US, "%02d:%02d:%02d",
                c.get(java.util.Calendar.HOUR_OF_DAY),
                c.get(java.util.Calendar.MINUTE),
                c.get(java.util.Calendar.SECOND));
    }

    /** 第二行：⎿ 后面接状态或结果，缩进对齐到工具名，像引用一段输出 */
    private void setSubline(TextView row, String text, int color, boolean visible) {
        // 调用行下面那条复述行彻底停用：
        // 上面一行已经写着「调用 · 工具名 · 命令摘要」，下面再摆一遍命令原文，
        // 等于每个调用占两行——而思考段现在也是一行，两种行混在一起就分不清
        // 哪行是"模型在想"、哪行是"它真的调了东西"。留一行，信息一点没少，
        // 命令原文完整内容仍在这一行点开后的详情里。
        if (row == null) return;
        View line = findSublineOf(row);
        if (line != null) line.setVisibility(View.GONE);
    }

    /** 找到这一行下面那条复述行（可能挂在 wrap 上，也可能挂在 head 上） */
    private View findSublineOf(TextView row) {
        if (row == null) return null;
        View t = row.findViewWithTag(TAG_SUBLINE);
        if (t != null) return t;
        Object tag = row.getTag();
        if (tag instanceof View) {
            return ((View) tag).findViewWithTag(TAG_SUBLINE);
        }
        return null;
    }

    /** 取这一行下面的实时日志窗 */
    private LinearLayout liveLogOf(TextView row) {
        Object o = row == null ? null : row.getTag();
        if (!(o instanceof View)) return null;
        View v = ((View) o).findViewWithTag(TAG_LIVE_LOG);
        return v instanceof LinearLayout ? (LinearLayout) v : null;
    }

    /** 开/关实时日志窗：跑起来才占位，跑完立刻收回去 */
    private void liveShow(TextView row, boolean on) {
        LinearLayout live = liveLogOf(row);
        if (live == null) return;
        if (on) {
            if (live.getVisibility() == View.VISIBLE) return;
            live.setVisibility(View.VISIBLE);
            live.setAlpha(0f);
            live.animate().alpha(1f).setDuration(150).start();
        } else {
            if (live.getVisibility() != View.VISIBLE) return;
            live.animate().cancel();
            live.animate().alpha(0f).setDuration(130).withEndAction(() -> {
                live.setVisibility(View.GONE);
                live.removeAllViews();
            }).start();
        }
    }

    /** 往日志窗顶一条：超过 3 条就把最旧的挤出去，视觉上就是内容在往上滚 */
    private void liveAppend(TextView row, String text, int color) {
        LinearLayout live = liveLogOf(row);
        if (live == null || text == null) return;
        liveShow(row, true);
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        t.setTypeface(Typeface.MONOSPACE);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setTextColor(color);
        t.setText(text);
        t.setPadding(0, dp(1), 0, dp(1));
        t.setAlpha(0f);
        live.addView(t);
        t.animate().alpha(1f).setDuration(150).start();
        while (live.getChildCount() > 3) live.removeViewAt(0);
    }

    /** 行尾光标闪烁：跑着时 0.9s 一个来回，收尾时按时停掉并隐藏 */
    private void blinkCaret(TextView row, boolean on) {
        if (row == null || !(row.getTag() instanceof View)) return;
        if (((View) row.getTag()).findViewWithTag(TAG_LIVE_CARET) == null) return;
        Object o = row == null ? null : row.getTag();
        if (!(o instanceof View)) return;
        View v = ((View) o).findViewWithTag(TAG_LIVE_CARET);
        if (!(v instanceof TextView)) return;
        TextView c = (TextView) v;
        Object t = c.getTag();
        if (t instanceof ObjectAnimator) ((ObjectAnimator) t).cancel();
        c.setTag(null);
        c.animate().cancel();
        if (on) {
            c.setVisibility(View.VISIBLE);
            ObjectAnimator a = ObjectAnimator.ofFloat(c, "alpha", 1f, 0.15f);
            a.setDuration(450L);
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setRepeatMode(ValueAnimator.REVERSE);
            a.start();
            c.setTag(a);
        } else {
            c.setAlpha(1f);
            c.setVisibility(View.GONE);
        }
    }

    /** 行首实时日志的第一条：把这一步实际在跑什么原样摆出来 */
    private String liveCmdOf(String name, String args) {
        String a = args == null ? "" : args.trim();
        if (a.isEmpty()) return "$ " + name;
        return "$ " + name + " " + briefText(a);
    }

    /**
     * 这次调用提交的代码有多少行。
     *
     * <p>只数真正的内容字段（command / code / content / script），不数 JSON 骨架——
     * 否则 {"command": ...} 这种外壳会白送一两行。参数里的换行有两种写法：
     * 真实换行符和转义的 \n 两个字，两种都要算，不然多行脚本会被数成 1 行。
     */
    private static int codeLines(String argsJson) {
        if (argsJson == null || argsJson.trim().isEmpty()) return 0;
        String body = argsJson;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\"(?:command|code|content|cmd|script|patch)\"\\s*:\\s*\""
                        + "((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(argsJson);
        if (m.find()) body = m.group(1);
        int lines = 1;
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (ch == '\n') {
                lines++;
            } else if (ch == '\\' && i + 1 < body.length() && body.charAt(i + 1) == 'n') {
                lines++;
                i++;
            }
        }
        return lines;
    }

    private void fillToolRow(TextView tv, int st, String name, String args, String result, long ms) {
        if (tv == null) return;
        boolean running = st == ST_RUN;
        int stColor = running ? 0xFF37E8A6
                : st == ST_FAIL ? 0xFFFF6B6B
                : st == ST_OK ? 0xFF56D96A : 0xFF6E7B8B;

        Object wrapTag = tv.getTag();
        View wrap = wrapTag instanceof View ? (View) wrapTag : null;
        if (wrap != null) {
            // 节点直接由状态驱动：空心环 → 发光环 → 实心打勾
            if (wrap.getBackground() instanceof com.nebula.aichat.ui.TrackBg) {
                ((com.nebula.aichat.ui.TrackBg) wrap.getBackground())
                        .setState(running ? com.nebula.aichat.ui.TrackBg.RUN
                                : st == ST_OK ? com.nebula.aichat.ui.TrackBg.OK
                                : st == ST_FAIL ? com.nebula.aichat.ui.TrackBg.FAIL
                                : com.nebula.aichat.ui.TrackBg.PENDING);
            }
            TextView badge = wrap.findViewWithTag(TAG_TOOL_BADGE);
            if (badge != null && name != null && !name.isEmpty()) {
                // 小写：跟终端里敲出来的写法一致，也让「调用 bash_exec」读起来是一句话
                badge.setText(name.toLowerCase(java.util.Locale.US));
                badge.setTextColor(soften(stColor, running ? 0.52f : 0.34f));
                badge.setBackground(Ui.roundRect(this,
                        (0x1C << 24) | (stColor & 0x00FFFFFF), 0, 4f));
            }
            TextView linesV = wrap.findViewWithTag(TAG_TOOL_LINES);
            if (linesV != null) {
                // 用原始参数算行数：这里传进来的 args 有时是给行内展示用的简述
                // （多行会被压成一行），拿它数会把 80 行的脚本数成 1 行
                int li = toolRows.indexOf(tv);
                String rawArgs = (li >= 0 && li < toolRowArgs.size())
                        ? toolRowArgs.get(li) : args;
                int n = codeLines(rawArgs);
                if (n > 0) {
                    linesV.setText(n + " 行");
                    linesV.setVisibility(View.VISIBLE);
                } else {
                    linesV.setVisibility(View.GONE);
                }
            }
            // 工具名和「N 行」在这之前刚换过文本，两者宽度都变了，命令的可用宽度必须
            // 跟着重算一次。少了这一步，行数从一位数变两位数时，行尾标记和箭头就会被
            // 挤到卡片外面——这正是调用一多、靠后的行看起来错位的原因。
            if (linesV != null && linesV.getParent() instanceof LinearLayout) {
                final LinearLayout headV = (LinearLayout) linesV.getParent();
                headV.post(new Runnable() {
                    @Override
                    public void run() {
                        applyToolHeadBudget(headV);
                    }
                });
            }
            View arrow = wrap.findViewWithTag(TAG_TOOL_ARROW);
            LinearLayout det = findDetailArea(wrap);
            if (arrow != null) {
                boolean open = det != null && det.getVisibility() == View.VISIBLE;
                arrow.setRotation(open ? 180f : 0f);
            }
            // 运行态用「银光闪烁」：一条银白光带 1.1s 扫一遍，
            // 字是冷银灰底上的银白高光。光还在走 = 这一步还在跑；光停下 = 已经结束。
            // 不再挂圆点读秒——那是行尾多出来的一坨符号，而光直接长在正文上：
            // 视线本来就在读这行字，跑没跑第一眼就撞上了
            View bodyV = wrap.findViewWithTag(TAG_TOOL_BODY);
            com.nebula.aichat.ui.ShimmerTextView bodySh =
                    bodyV instanceof com.nebula.aichat.ui.ShimmerTextView
                            ? (com.nebula.aichat.ui.ShimmerTextView) bodyV : null;
            View subLine = wrap.findViewWithTag(TAG_SUBLINE);
            View subBox = wrap.findViewWithTag(TAG_SUB_ROW);
            boolean subOn = subBox != null && subBox.getVisibility() == View.VISIBLE;
            // 「在跑」现在只由输入框上方那条常驻运行条表达（环在转、秒数在跳）。
            // 这里原先挂着银光扫 + 卡片呼吸，两层闪烁叠在一起整屏都在动，
            // 反而分不清是动画还是卡死了——工具行统一改成静态：不扫、不呼吸
            if (bodySh != null) bodySh.stopShimmer();
            if (subLine instanceof com.nebula.aichat.ui.ShimmerTextView) {
                ((com.nebula.aichat.ui.ShimmerTextView) subLine).stopShimmer();
            }
            // 卡片底色跟着读秒浅浅地亮一下暗一下。
            // 单个节点在动，扫一眼未必抓得住；整张卡片一起呼吸，余光都能看见。
            // 跑完所有行同时静下来，这片"安静"本身就是结束的信号
            View cardV = wrap.findViewWithTag(TAG_TOOL_CARD);
            if (cardV != null) {
                // 运行中整张卡片泛一层淡绿，跑完退干净。
                // 文字变色只覆盖几行字，卡片底色是整块面积——余光扫过去就能看出
                // "这块还亮着"。而且它是静态的：从亮到退只发生一次，不是来回闪
                cardV.setBackground(toolCardBg(running ? 0x1A3DDC97 : 0x0AFFFFFF));
            }
        }

        // 结果回填时只带了 result、没带 args，命令会凭空消失——从行记录里补回来。
        // 调用行必须自始至终都是同一条命令，它才是「这步干了什么」的唯一凭据
        String cmd = args;
        if (cmd == null || cmd.isEmpty()) {
            int i = toolRows.indexOf(tv);
            if (i >= 0 && i < toolRowArgs.size()) cmd = toolRowArgs.get(i);
        }

        if (cmd == null || cmd.trim().isEmpty()) {
            tv.setVisibility(View.GONE);
            return;
        }
        tv.setVisibility(View.VISIBLE);
        String one = briefText(cmd.trim());
        if (one.length() > 120) one = one.substring(0, 120) + "…";
        // 颜色即状态：还在跑是亮绿，跑完落回雾灰，失败柔红、中止暖橙。
        // 关键顺序——色必须先算出来、再交给 markKeyInfo：那一步会给路径/参数/数字
        // 逐个打 ForegroundColorSpan，而 span 的优先级高于 setTextColor。
        // 一行命令里这些片段往往占大半，用旧写法它们会一直停在固定的蓝灰上，
        // 于是"整行变色"实际只变了几个标点，肉眼看当然像没在跑
        int bodyColor = running ? C_RUN
                : st == ST_FAIL ? C_FAIL
                : st == ST_ABORT ? C_ABORT
                : C_TEXT2;
        SpannableStringBuilder sb = new SpannableStringBuilder(one);
        markKeyInfo(sb, 0, sb.length(), Palette.c(bodyColor), false);
        tv.setTextColor(Palette.c(bodyColor));
        if (tv instanceof com.nebula.aichat.ui.ShimmerTextView) {
            com.nebula.aichat.ui.ShimmerTextView bsh =
                    (com.nebula.aichat.ui.ShimmerTextView) tv;
            bsh.stopShimmer();
            bsh.stopWave();
            bsh.setColors(bodyColor, bodyColor);
        }
        tv.setText(sb);
    }

    /** 工具行卡片底：圆角/描边跟 makeToolWrap 完全一致，只换底色，否则呼吸时会跳框 */
    private android.graphics.drawable.GradientDrawable toolCardBg(int color) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(dp(9));
        g.setColor(Palette.c(color));
        g.setStroke(1, Palette.c(0x14FFFFFF));
        return g;
    }

    /** 新建一行：等宽字体、单行、超长省略；点一下弹窗看完整参数和结果 */
    /**
     * 一条工具调用的外框：上面一行摘要（工具名 + 参数/结果摘要 + 耗时 + 行尾 ▾），
     * 点这一行就在它自己下面展开，展开区与摘要行之间用一条横线隔开。
     * 每次调用各自一个框，不再把多次调用塞进同一个框里。
     */
    private LinearLayout makeToolWrap(LinearLayout host) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        // 不再画「星轨」：那条竖线 + 22dp 缩进 + 4% 白卡片，本意是让步骤连成轨迹，
        // 实际效果是每一行都被压成很淡的小字，一眼扫过去像"根本没有调用行"。
        // 现在就是平铺的一行：调用 + 工具名 + 命令，颜色直接给足
        wrap.setBackground(null);
        wrap.setPadding(0, dp(2), 0, dp(2));

        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = dp(1);
        host.setClipChildren(false);
        host.addView(wrap, wlp);

        // 卡片只包「做了什么」。结果那行移出卡片，
        // 这样它的图标才能落到星轨节点那一列上，两列各自成一条竖线
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setTag(TAG_TOOL_CARD);
        card.setBackground(null);   // 不要卡片底了：一层极淡的框只是让字更难看清
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = 0;         // 原来让开的 22dp 时间轴一起去掉，整行铺满
        wrap.addView(card, clp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setMinimumHeight(dp(28));
        head.setPadding(0, 0, 0, 0);

        // 行首：矢量图标 + 「调用」两个字。图标 14dp 与 11sp 标签同高、右距 6dp，
        // 和下面思考行的灯泡图标用同一套尺寸，所以两行的图标、文字左缘都在一条线上。
        ImageView actIc = new ImageView(this);
        actIc.setImageResource(R.drawable.ic_act_call);
        actIc.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams ailp = new LinearLayout.LayoutParams(dp(14), dp(14));
        ailp.rightMargin = dp(6);
        head.addView(actIc, ailp);

        TextView act = new TextView(this);
        act.setText("调用");
        act.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        act.setTextColor(Palette.c(0xFF8FA3B8));
        act.setPadding(0, 0, dp(6), 0);
        act.setIncludeFontPadding(false);
        head.addView(act, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 工具名方牌：等宽大写 + 放开字距 + 状态色低透底，和命令形成两个层级
        TextView badge = new TextView(this);
        badge.setTag(TAG_TOOL_BADGE);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        badge.setLetterSpacing(0.08f);
        badge.setTypeface(Typeface.MONOSPACE);
        badge.setGravity(Gravity.CENTER);
        // 工具名给宽度上限：BUILD_ENV_STATUS 这种长名字加字距后会横着长到 100dp 以上，
        // 把右边真正有用的命令内容挤成几个字符。宁可把名字截断，也要保住命令。
        badge.setSingleLine(true);
        badge.setEllipsize(TextUtils.TruncateAt.END);
        badge.setMaxWidth(dp(104));
        badge.setTextColor(Palette.c(0xFF7EE787));
        badge.setBackground(null);   // 去掉底色块，纯彩色等宽字更干净也更醒目
        badge.setPadding(0, 0, dp(6), 0);
        head.addView(badge, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        com.nebula.aichat.ui.ShimmerTextView body =
                new com.nebula.aichat.ui.ShimmerTextView(this);
        body.setTag(TAG_TOOL_BODY);
        body.setColors(0xFF9FB2C6, 0xFFDCEBFA);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        body.setTypeface(Typeface.MONOSPACE);
        body.setSingleLine(true);
        body.setEllipsize(TextUtils.TruncateAt.END);
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setTextColor(Palette.c(0xFFDCE7F2));   // 提亮：这行是主体信息，不该比思考字更弱
        // 不撑满整行（weight=1 会让短命令后面拖出一片空白，把「N 行」和箭头顶到屏幕最右），
        // 按内容自然宽度排版；超长时靠 maxWidth 截断。
        // 预算不能写死：左边「图标 + 调用 + 工具名」的宽度随工具名长短浮动，
        // 写死一个数字很容易算少，整行超宽后右边的「N 行」和箭头会被直接裁到屏幕外——
        // 所以等布局完，用同级子控件的真实宽度倒推出还能给命令多少位置。
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = dp(8);
        head.addView(body, blp);

        // 首帧兜底：精确预算要等一次布局才跑得到，在那之前先给个上限，
        // 免得特别长的命令在第一帧就把「N 行」和箭头顶出屏幕（随后会被重算，但会闪一下）
        int scrW = getResources().getDisplayMetrics().widthPixels;
        body.setMaxWidth(Math.max(dp(60), scrW - dp(176)));

        head.post(new Runnable() {
            @Override
            public void run() {
                applyToolHeadBudget(head);
            }
        });

        // 屏幕宽度变了（旋转、分屏、字号调整）整行可用宽度也变了，得重算。
        // 只在宽度真的变化时动手，避免 setMaxWidth → layout → 再重算的死循环。
        head.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> {
            if (r - l != orr - ol) applyToolHeadBudget(head);
        });

        // 行尾的「N 行」：这次调用真正提交了多少行代码/命令。
        // 单行命令不标（满屏都是「1 行」反而成噪音），两行以上才亮出来，
        // 一眼就能知道它刚塞进去的是三行还是八十行。
        TextView lines = new TextView(this);
        lines.setTag(TAG_TOOL_LINES);
        // 原来 10.5sp + 0xFF7C8D9E，屏幕上一眼扫过去基本看不见，等于白标；
        // 提到 11sp、换成更亮的蓝灰，并且前面留足 12dp，跟命令正文分开，不被当成命令的一部分
        lines.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        lines.setTextColor(Palette.c(0xFFA9BED2));
        lines.setSingleLine(true);
        lines.setIncludeFontPadding(false);
        lines.setPadding(dp(12), 0, dp(4), 0);
        lines.setVisibility(View.GONE);
        head.addView(lines, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ImageView arrow = new ImageView(this);
        arrow.setTag(TAG_TOOL_ARROW);
        // 橙色：这一行的深浅两档灰里，箭头原来是 0xFF44505E，几乎沉进背景里，
        // 没人看得出这里能点。换成暖橙之后它成了整行唯一的"可点"提示
        arrow.setImageDrawable(new ChevronDrawable(Palette.c(0xFFFFA44D)));
        arrow.setScaleType(ImageView.ScaleType.FIT_CENTER);
        // 放大到 20dp：这张 ▾ 是行上唯一的图形符号，16dp 时在高密度屏上偏小、点起来也费劲
        arrow.setPadding(dp(2), dp(2), dp(2), dp(2));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(18), dp(18));
        alp.leftMargin = dp(6);   // 紧贴「N 行」后面，不留空档
        head.addView(arrow, alp);

        // 翻译行（图标 + 中文解释 + 耗时）已整行移除：
        // 「这一步到底干了什么」由工具行摘要自己说清楚，下面再补一句中文属于复述；
        // 去掉后调用行下面直接就是思考块，纵向也少一层卡片。


        wrap.setOnClickListener(v -> toggleToolExpand(wrap));
        return wrap;
    }

    /**
     * 重新给调用行的命令正文算一次可用宽度。
     *
     * 这行是「图标 + 调用 + 工具名 + 命令 + N 行 + 箭头」横排，命令用 WRAP_CONTENT 自然宽，
     * 所以必须显式告诉它最多能占多少，否则长命令会把「N 行」和箭头顶出卡片边界。
     *
     * 原先这段预算只在建行时 post 跑一次，而且「N 行」还是 GONE 时按写死的 42dp 留位。
     * 行数到两位数（35 行、48 行）之后，真实宽度早就超过 42dp，等于多给了命令十几个 dp——
     * 整行超宽，「N 行」和箭头被挤出可见区；等到「N 行」真正显示出来，也没有第二次重算。
     * 调用越多、命令越长，靠后的行错位越明显，根子就在这里。
     *
     * 现在改成可以反复调用：建行时一次，「N 行」/工具名改完文本再一次。
     */
    private void applyToolHeadBudget(LinearLayout head) {
        if (head == null) return;
        int w = head.getWidth();
        if (w <= 0) return;
        View bv = head.findViewWithTag(TAG_TOOL_BODY);
        if (!(bv instanceof com.nebula.aichat.ui.ShimmerTextView)) return;
        com.nebula.aichat.ui.ShimmerTextView body =
                (com.nebula.aichat.ui.ShimmerTextView) bv;

        int fixed = 0;
        for (int k = 0; k < head.getChildCount(); k++) {
            View ch = head.getChildAt(k);
            if (ch == body) continue;
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) ch.getLayoutParams();
            int cw = ch.getMeasuredWidth();
            if (TAG_TOOL_LINES.equals(ch.getTag())
                    && (cw <= 0 || ch.getVisibility() != View.VISIBLE)) {
                // 还没填内容（或此刻隐藏）：按「两位行数」的真实文本宽预留。
                // 不能写死 dp——11sp 的两位数行数比 42dp 宽，少留一点就会溢出。
                cw = toolLinesReserveWidth();
            } else if (cw <= 0 && ch.getVisibility() == View.VISIBLE) {
                // 刚 addView 进来还没走过测量（典型就是事后插入的「×k」），
                // 直接读 measuredWidth 会得到 0，等于没给它留位。自己量一次。
                ch.measure(android.view.View.MeasureSpec.makeMeasureSpec(
                                0, android.view.View.MeasureSpec.UNSPECIFIED),
                        android.view.View.MeasureSpec.makeMeasureSpec(
                                0, android.view.View.MeasureSpec.UNSPECIFIED));
                cw = ch.getMeasuredWidth();
            }
            fixed += cw + lp.leftMargin + lp.rightMargin;
        }
        int avail = w - fixed - dp(4);
        body.setMaxWidth(Math.max(dp(56), avail));
    }

    private int toolLinesReservePx = -1;

    /** 「N 行」标注的最宽情形占位：两位行数 + 左右内边距，实测一次后缓存 */
    private int toolLinesReserveWidth() {
        if (toolLinesReservePx > 0) return toolLinesReservePx;
        TextView probe = new TextView(this);
        probe.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        probe.setIncludeFontPadding(false);
        probe.setSingleLine(true);
        probe.setText("888 行");
        probe.measure(android.view.View.MeasureSpec.makeMeasureSpec(
                        0, android.view.View.MeasureSpec.UNSPECIFIED),
                android.view.View.MeasureSpec.makeMeasureSpec(
                        0, android.view.View.MeasureSpec.UNSPECIFIED));
        toolLinesReservePx = probe.getMeasuredWidth() + dp(16);
        return toolLinesReservePx;
    }

    /** 从外框里取出摘要行 */
    private static TextView toolBodyOf(LinearLayout wrap) {
        if (wrap == null) return null;
        // 布局改成「外框 → 卡片 → 头行 → 正文」四层后，按下标取子 View 全部失效，
        // 干脆认 tag：这样以后再怎么调结构，这里都不用跟着改
        android.view.View v = wrap.findViewWithTag(TAG_TOOL_BODY);
        return v instanceof TextView ? (TextView) v : null;
    }

    /** 行尾箭头：按 tag 取（它现在是 ImageView，不再靠"最后一个 TextView"猜） */
    private static View toolArrowOf(LinearLayout wrap) {
        if (wrap == null) return null;
        return wrap.findViewWithTag(TAG_TOOL_ARROW);
    }

    /** 点一行：只展开这一行自己的详情，箭头跟着翻 */
    private void toggleToolExpand(LinearLayout wrap) {
        if (wrap == null) return;
        LinearLayout det = ensureDetailArea(wrap);
        boolean show = det.getVisibility() != View.VISIBLE;
        if (show) fillToolDetail(wrap, det);
        det.setVisibility(show ? View.VISIBLE : View.GONE);
        View arrow = toolArrowOf(wrap);
        if (arrow != null) arrow.animate().rotation(show ? 180f : 0f).setDuration(180).start();
        // 就地展开，不做任何自动滚动：页面不跳，鼠标点哪儿就还在哪儿
    }

    /** 翻译行的身份标记：挂在它的根容器 tag 上，用来和绿框配对 */
    private static final String TAG_TOOL_ARROW = "tool_arrow";
    private static final String TAG_TOOL_BADGE = "tool_badge";
    private static final String TAG_TOOL_LINES = "tool_lines";
    private static final String TAG_TOOL_BODY = "tool_body";
    private static final String TAG_TOOL_CARD = "tool_card";
    private static final String TAG_SUB_ROW = "tool_sub_row";
    private static final String TAG_SUB_ICON = "tool_sub_icon";
    /** 行尾「×N」调用次数标注 */
    private static final String TAG_TOOL_SEQ = "tool_seq";
    /** 翻译行右端的耗时标注，与上面的「×N」上下对齐 */
    private static final String TAG_SUB_COST = "tool_sub_cost";
    /** 翻译行尾部的展开箭头，与调用行的 ▾ 同一符号、同一尺寸 */
    private static final String TAG_SUB_ARROW = "tool_sub_arrow";

    /**
     * 同一工具被反复调用时，给这一组里的每一行补上「×1 ×2 ×3…」。
     * 只出现一次就不标——单个调用标个 ×1 纯属噪音。
     * 计数直接按现有的 toolRowNames 现算，不做额外状态，裁剪/重开也不会算错。
     */
    private void refreshToolSeq(String name) {
        if (name == null) return;
        int k = 0;
        for (int i = 0; i < toolRows.size() && i < toolRowNames.size(); i++) {
            if (!name.equals(toolRowNames.get(i))) continue;
            k++;
            setSeqOnRow(toolRows.get(i), k);
        }
    }

    /** 把「×k」塞进摘要行、紧挨着行尾箭头；没有就现建一个 */
    private void setSeqOnRow(TextView row, int k) {
        if (row == null) return;
        View parent = (View) row.getParent();
        if (!(parent instanceof ViewGroup)) return;
        ViewGroup head = (ViewGroup) parent;
        TextView tv = head.findViewWithTag(TAG_TOOL_SEQ);
        if (tv == null) {
            tv = new TextView(this);
            tv.setTag(TAG_TOOL_SEQ);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextColor(Palette.c(0xFF8A9AAD));
            tv.setGravity(Gravity.CENTER_VERTICAL);
            // 只给一点点底：次数要标出来，但它是位序信息，不该长成一块徽章
            tv.setBackground(Ui.roundRect(this, Palette.c(0x0DFFFFFF), 0, 4f));
            tv.setPadding(dp(5), dp(1), dp(5), dp(1));
            int at = head.getChildCount();
            View arrow = head.findViewWithTag(TAG_TOOL_ARROW);
            if (arrow != null) at = head.indexOfChild(arrow);
            head.addView(tv, at, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        tv.setText("\u00d7" + k);

        // 「×k」是行建好以后才插进来的孩子，这一行凭空多出 20dp 左右。
        // 不重算的话命令还按老预算铺开，整行超宽，「N 行」和箭头就被挤出卡片可见区——
        // 而且次数会从一位数涨到两位数，越到后面超得越多。
        // 这既是「调用多了靠后的行错位」的直接原因，也是它只在后段才明显的原因。
        if (head instanceof LinearLayout) {
            final LinearLayout hf = (LinearLayout) head;
            hf.post(new Runnable() {
                @Override
                public void run() {
                    applyToolHeadBudget(hf);
                }
            });
        }
    }

    /**
     * 把耗时写到翻译行右端（箭头左边）。
     *
     * <p>为什么从调用行搬下来：调用行要回答的是「做了什么、输出了什么」，
     * 时间属于结果层，挤在同一行末尾只会变成装饰；翻译行本来就是"这一步的产物"，
     * 耗时和那句中文解释同源，放一起才对得上。
     *
     * <p>运行中不显示：环已经在转，再挂一个跳动的秒数纯属噪音。
     */
    private void setExplainMsOnRow(View summaryRow, long ms, int st) {
        if (!(summaryRow instanceof TextView)) return;
        // 运行中也挂着：这格是一个一直在涨的事实数字，位置和上面的「×N」对齐。
        // 闪烁负责说"还在跑"，它负责说"已经跑了多久"——两件事不冲突
        if (ms <= 0) return;
        ViewGroup sub = subRowOf(summaryRow);
        if (sub == null) return;
        TextView tv = subCostOf(sub, true);
        if (tv == null) return;
        // 亮度跟着结果走：成功是工具名方牌那颗绿，失败红、中止琥珀。
        // 这一格是全行唯一的纯事实数字，值得亮过旁边那句解释
        int c = 0xFF8A9AAD;
        if (st == ST_OK) c = 0xFF7EE787;
        else if (st == ST_FAIL) c = 0xFFFF7B7B;
        else if (st == ST_ABORT) c = 0xFFD9A441;
        tv.setTextColor(Palette.c(c));
        tv.setText(fmtElapsed(ms));
        tv.setVisibility(View.VISIBLE);
    }

    /** 把耗时的毫秒数写成给人看的短字符串：秒内一位小数，超过一分钟换成 m:ss */
    private static String fmtElapsed(long ms) {
        if (ms < 60000L) {
            return String.format(java.util.Locale.US, "%.1fs", ms / 1000f);
        }
        return String.format(java.util.Locale.US, "%d:%02d", ms / 60000L, (ms % 60000L) / 1000L);
    }

    /** 从摘要行往上找翻译行那个容器；结构再怎么改，认 tag 总不会错 */
    private ViewGroup subRowOf(View any) {
        // 翻译行整行移除后（4.0.24），这里已经不可能找到容器：直接返回 null，
        // 上游 setExplainMsOnRow 拿到 null 立即退出。
        //
        // 必须在这里收住，不能保留"向上找父节点"的循环：旧实现每层都做
        // (View) v.getParent()，一旦走到窗口根，父节点是 ViewRootImpl（实现 ViewParent
        // 但不是 View），强转直接 ClassCastException —— 4.0.24 就是死在这一行，
        // 表现为第一次工具调用、也就是"思考中"闪退。
        return null;
    }

    /** 翻译行右端的耗时标签，样式与调用行的「×N」完全一致，两行尾巴才像一对 */
    private TextView subCostOf(ViewGroup subRow, boolean create) {
        View found = subRow.findViewWithTag(TAG_SUB_COST);
        if (found instanceof TextView) return (TextView) found;
        if (!create) return null;
        TextView tv = new TextView(this);
        tv.setTag(TAG_SUB_COST);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(Palette.c(0xFF8A9AAD));
        tv.setGravity(Gravity.CENTER_VERTICAL);
        // 和「×N」同一个框（4dp 圆角 + 5/1 内边距），底色提到 8% 白：
        // 次数是位序信息，耗时是本步的事实，值得比它重一档
        tv.setBackground(Ui.roundRect(this, Palette.c(0x14FFFFFF), 0, 4f));
        tv.setPadding(dp(5), dp(1), dp(5), dp(1));
        tv.setVisibility(View.GONE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6);   // 和左边那句解释留一点缝，别贴上去
        // 必须插在尾部 ▾ 前面：直接 addView 会排到箭头右边，把箭头挤到行中间
        View tail = subRow.findViewWithTag(TAG_SUB_ARROW);
        int at = tail == null ? -1 : subRow.indexOfChild(tail);
        if (at >= 0) subRow.addView(tv, at, lp); else subRow.addView(tv, lp);
        return tv;
    }

    /**
     * 中止时把还在扫光的解释行收干净：只停光、不动文字。
     * 不能走 updateToolExplain（），那会把已有的人话清成空字符串。
     */
    private void stopExplainShimmer(TextView row) {
        // 光扫现在跑在工具行本体上，直接停它自己
        if (row instanceof com.nebula.aichat.ui.ShimmerTextView) {
            com.nebula.aichat.ui.ShimmerTextView sh =
                    (com.nebula.aichat.ui.ShimmerTextView) row;
            sh.stopShimmer();
            sh.stopDrift();
            sh.stopWave();
            sh.setColors(0xFFC3D0DE, 0xFFE8F4FF);
        }
        // 翻译行也在闪，一起收干净——否则这一步结束了两行里还剩一行在动，
        // 那比完全不动更让人怀疑"是不是卡住了"
        View sub = row.findViewWithTag(TAG_SUBLINE);
        if (sub instanceof com.nebula.aichat.ui.ShimmerTextView) {
            com.nebula.aichat.ui.ShimmerTextView s =
                    (com.nebula.aichat.ui.ShimmerTextView) sub;
            s.stopShimmer();
            s.stopWave();
            s.setColors(0xFFC3D0DE, 0xFFFFFFFF);
        }
    }

    /**
     * 行尾下拉箭头：两条圆头线拼的 V，粗细按控件宽度自适应，缩放不糊。
     * 刻意不用 ▾/▴ 字形——不同字体粗细差太多，深浅背景下观感也不稳。
     */
    private static class ChevronDrawable extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        ChevronDrawable(int color) {
            p.setColor(color);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            p.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        }

        void setColor(int color) {
            p.setColor(color);
            invalidateSelf();
        }

        @Override
        public void draw(android.graphics.Canvas c) {
            float w = getBounds().width(), h = getBounds().height();
            float cx = getBounds().left + w / 2f, cy = getBounds().top + h / 2f;
            float half = w * 0.26f, dy = h * 0.16f;
            p.setStrokeWidth(Math.max(1.3f, w * 0.095f));
            c.drawLine(cx - half, cy - dy, cx, cy + dy, p);
            c.drawLine(cx + half, cy - dy, cx, cy + dy, p);
        }

        @Override
        public void setAlpha(int a) {
            p.setAlpha(a);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
            p.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * 实心三角：向上的尖端朝上，向下的尖端朝下。
     *
     * <p>正文右侧那对小圆钮原来用的是 ChevronDrawable，那是一条 V 形折线，
     * 线宽只有图标宽度的 9.5%，放进圆钮里不到 1.5px，叠在半透明底子上
     * 几乎看不出画了什么。换成实心三角：一整块面，方向只看尖头朝哪边，
     * 不带任何细线，小尺寸下也不会糊。
     */
    private static class ArrowDrawable extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint p =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();
        private final boolean up;

        ArrowDrawable(int color, boolean up) {
            p.setColor(color);
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            this.up = up;
        }

        @Override
        public void draw(android.graphics.Canvas c) {
            float l = getBounds().left, t = getBounds().top;
            float w = getBounds().width(), h = getBounds().height();
            if (w <= 0 || h <= 0) return;
            float cx = l + w / 2f, cy = t + h / 2f;
            // 三角尽量撑满图标区，但在上下左右各留一丝，免得贴着按钮圆弧发闷
            float triW = w * 0.94f, triH = h * 0.82f;
            float halfW = triW / 2f, halfH = triH / 2f;

            path.reset();
            if (up) {
                path.moveTo(cx, cy - halfH);           // 尖端朝上
                path.lineTo(cx + halfW, cy + halfH);
                path.lineTo(cx - halfW, cy + halfH);
            } else {
                path.moveTo(cx, cy + halfH);           // 尖端朝下
                path.lineTo(cx + halfW, cy - halfH);
                path.lineTo(cx - halfW, cy - halfH);
            }
            path.close();
            c.drawPath(path, p);
        }

        // 必须报告固有尺寸：ImageView 在 wrap_content 下拿不到尺寸时，
        // 量出来只剩 padding，内容区被压成 1px，图形就没了。
        @Override
        public int getIntrinsicWidth() {
            return 60;
        }

        @Override
        public int getIntrinsicHeight() {
            return 60;
        }

        @Override
        public void setAlpha(int a) {
            p.setAlpha(a);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
            p.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    private static final String TAG_EXPLAIN = "tool_explain";
    private static final String TAG_EXPLAIN_BODY = "tool_explain_body";
    private static final String TAG_EXPLAIN_ICON = "tool_explain_icon";
    private static final String TAG_EXPLAIN_ARROW = "tool_explain_arrow";
    /** 翻译行右端的耗时文字 */
    private static final String TAG_EXPLAIN_MS = "tool_explain_ms";
    private static final String TAG_LIVE_LOG = "tool_live_log";
    private static final String TAG_LIVE_CARET = "tool_live_caret";
    private static final String TAG_SUBLINE = "tool_subline";
    /** 动作条上那个极小的整轮总耗时 */
    private static final String TAG_MSG_COST = "msg_cost";

    /**
     * 绿框下面那条独立翻译行：机器动作在上面（命令原文），人话在下面（中文解析）。
     * 两框等宽并落在同一条竖线上，一行只说一句，读起来是"动作 → 解释"的垂直节奏。
     */
    /** 这一行当前挂着的原始命令（可能为空：结果回填时不一定带 args） */
    private String cmdOfRow(TextView tv) {
        int i = toolRows.indexOf(tv);
        if (i >= 0 && i < toolRowArgs.size()) {
            String a = toolRowArgs.get(i);
            return a == null ? "" : a;
        }
        return "";
    }

    /**
     * 把中文解释里与调用行重复的部分抠掉，只留新增信息。
     *
     * 例：「正在执行 gradle assembleRelease 构建」+ cmd=gradle assembleRelease
     *     → 「正在执行 构建」→「正在执行构建」
     * 如果抠完什么都不剩，说明这句翻译没有任何新信息，整行收掉，
     * 宁可少一行，也不要让同一件事在同一个卡片里说两遍。
     */
    private String dedupExplain(String zh, String cmd) {
        if (zh == null || zh.isEmpty()) return "";
        if (cmd == null || cmd.trim().isEmpty()) return zh;
        String out = zh;
        String whole = briefText(cmd.trim());

        if (whole.length() >= 4 && out.contains(whole)) {
            out = out.replace(whole, " ");
        } else {
            // 命令常被拆进句子里（「读取 app/build.gradle 的版本号」），按空白切词抠。
            // 但只允许抠一处：抠两处以上会把句子拆碎
            //（「在 /root/x 里搜索 Toolchain」会变成「在里搜索」），宁可留着重复
            String hit = null;
            int hits = 0;
            for (String tok : whole.split("\\s+")) {
                if (tok.length() >= 4 && out.contains(tok)) {
                    hits++;
                    hit = tok;
                }
            }
            if (hits == 1) out = out.replace(hit, " ");
        }

        out = out.replaceAll("\\s{2,}", " ").trim();
        // 去掉命令后，中文之间会留下一道缝（「读取 的版本号」），把它补回去
        out = out.replaceAll("(?<=[\\u4e00-\\u9fa5\\u00b7]) (?=[\\u4e00-\\u9fa5\\u00b7])", "");
        out = out.replaceAll("^[\\s，,、:：·—]+", "");
        out = out.replaceAll("[\\s，,、·—]+$", "");
        // 只剩标点或一两个字，就等于没说，收掉
        if (out.replaceAll("[\\p{Punct}\\s]", "").length() < 2) return "";
        return out;
    }

    private TextView updateToolExplain(TextView tv, String text, int color) {
        return updateToolExplain(tv, text, color, false);
    }

    /**
     * running=true：这一步还没跑完，文字上扫光、右端秒数跳动，充当监控行；
     * false：跑完了，光停、文字定格成翻译，秒数定格成最终耗时。
     */
    private TextView updateToolExplain(TextView tv, String text, int color, boolean running) {
        if (tv == null) return null;
        setZh(tv, text == null ? "" : text);

        // 翻译行不再翻译：直接摆这一步的代码原文。
        // 中文解释十次有八次是把命令重念一遍，扣掉重复段后经常只剩一个空行；
        // 而"到底跑了哪行命令"才是回头查问题时要盯的东西。
        // 运行状态不靠这行字表达——银色光扫在跑，光一停就是停了
        String code = cmdOfRow(tv);
        if (code == null || code.trim().isEmpty()) code = zhOf(tv);
        code = code.replace("\r", " ").replace("\n", " ").trim();
        setSubline(tv, code, color, !code.isEmpty());

        // 光扫的开关不在这里——这里只是换文字，换文字不代表状态变了。
        // 之前在这里按 running 停扫，而几乎所有调用点都不传 running（缺省 false），
        // 于是 fillToolRow 刚起的光，下一行文字刷新就被这里掐掉，界面上只剩一行死字。
        // 现在光扫只由 fillToolRow 按行的真实状态（st == ST_RUN）开关，这里只管颜色
        if (tv instanceof com.nebula.aichat.ui.ShimmerTextView) {
            com.nebula.aichat.ui.ShimmerTextView sh =
                    (com.nebula.aichat.ui.ShimmerTextView) tv;
            sh.stopShimmer();
            sh.stopWave();
            sh.setColors(color, color);
            sh.setTextColor(color);
        }
        return tv;
    }

    /**
     * 中文解释的排版：按「 —— 」切成「动作」和「结果」两段，
     * 动作加粗点题、结果降饱和收尾，长句也能一眼看到重点在哪。
     */
    private CharSequence styledExplain(String text, int color) {
        if (text == null) return "";
        // 「——」两个破折号在手机上太占地方，渲染成「·」，一句人话读起来紧一档
        String src = text.replace(" \u2014\u2014 ", " \u00b7 ");
        SpannableStringBuilder sb = new SpannableStringBuilder(src);
        int cut = src.indexOf(" \u00b7 ");
        int actEnd = cut < 0 ? sb.length() : cut;
        sb.setSpan(new StyleSpan(Typeface.BOLD), 0, actEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(soften(color, 0.28f)), 0, actEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (cut >= 0) {
            sb.setSpan(new ForegroundColorSpan(Palette.c(0x66FFFFFF)), cut, cut + 3,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            // 结果段整段定调：失败偏红、成功贴着状态色，"成没成"不用逐字读
            boolean bad = P_ERR.matcher(src.substring(cut + 3)).find();
            sb.setSpan(new ForegroundColorSpan(bad ? Palette.c(0xFFFF8080) : soften(color, 0.10f)),
                    cut + 3, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        // 路径、文件名、数字、命令参数、错误词各自标记：一句中文里也能直接跳到重点
        markKeyInfo(sb, 0, sb.length(), color, true);
        return sb;
    }

    /** 关键信息的正则：路径 / 文件名 / 数字+单位 / 命令参数 / 错误词 */
    private static final java.util.regex.Pattern P_PATH = java.util.regex.Pattern.compile(
            "(?<![\\w./])(?:/[\\w.\\-\\u4e00-\\u9fa5]+){2,}/?");
    private static final java.util.regex.Pattern P_FILE = java.util.regex.Pattern.compile(
            "[\\w\\-]+\\.(?:java|kt|xml|gradle|json|apk|png|jpg|jpeg|py|sh|txt|md|log|so|aar|yml)\\b");
    private static final java.util.regex.Pattern P_NUM = java.util.regex.Pattern.compile(
            "(?:\\u9000\\u51fa\\u7801\\s*)?\\d+(?:\\.\\d+)?\\s*(?:ms|s|\\u79d2|KB|MB|GB|\\u5b57\\u7b26|\\u9879|\\u884c|\\u6761|\\u6b21|%)?");
    private static final java.util.regex.Pattern P_FLAG = java.util.regex.Pattern.compile(
            "(?<![\\w\\-])(?:--?[A-Za-z][\\w\\-]*)");
    private static final java.util.regex.Pattern P_ERR = java.util.regex.Pattern.compile(
            "\\u5931\\u8d25|\\u9519\\u8bef|\\u62a5\\u9519|\\u627e\\u4e0d\\u5230|\\u4e0d\\u5b58\\u5728"
            + "|\\u4e0d\\u652f\\u6301|\\u8d85\\u65f6|\\u62d2\\u7edd|\\u65e0\\u6743\\u9650|\\u65e0\\u6cd5"
            + "|(?i:cannot|error|failed|not\\s+found|denied|exception|invalid)");

    /**
     * 给 start..end 这段文字里的关键信息打标记。
     * 优先级按「先铺底、后被覆盖」排：路径 → 文件 → 参数 → 数字 → 错误词，
     * 所以最终错误词一定是最醒目的那个；pathMono=false 时（工具行本来就是等宽）不再套等宽。
     */
    private void markKeyInfo(SpannableStringBuilder sb, int start, int end, int color, boolean pathMono) {
        if (sb == null || end <= start || start < 0) return;
        String sub = sb.subSequence(start, end).toString();
        final int EX = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;

        java.util.regex.Matcher mp = P_PATH.matcher(sub);
        while (mp.find()) {
            int a = start + mp.start(), b = start + mp.end();
            sb.setSpan(new ForegroundColorSpan(soften(color, 0.55f)), a, b, EX);
            if (pathMono) sb.setSpan(new android.text.style.TypefaceSpan("monospace"), a, b, EX);
            sb.setSpan(new BackgroundColorSpan((color & 0x00FFFFFF) | Palette.c(0x3A000000)), a, b, EX);
        }
        java.util.regex.Matcher mf = P_FILE.matcher(sub);
        while (mf.find()) {
            int a = start + mf.start(), b = start + mf.end();
            sb.setSpan(new ForegroundColorSpan(soften(color, 0.50f)), a, b, EX);
            if (pathMono) sb.setSpan(new android.text.style.TypefaceSpan("monospace"), a, b, EX);
            sb.setSpan(new BackgroundColorSpan((color & 0x00FFFFFF) | Palette.c(0x2E000000)), a, b, EX);
        }
        java.util.regex.Matcher mg = P_FLAG.matcher(sub);
        while (mg.find()) {
            int a = start + mg.start(), b = start + mg.end();
            sb.setSpan(new ForegroundColorSpan(soften(color, 0.38f)), a, b, EX);
            if (pathMono) sb.setSpan(new android.text.style.TypefaceSpan("monospace"), a, b, EX);
        }
        java.util.regex.Matcher mn = P_NUM.matcher(sub);
        while (mn.find()) {
            String g = mn.group();
            // 纯数字太短（如「1」）不值得标，避免整行花花绿绿
            if (g.trim().length() < 2) continue;
            int a = start + mn.start(), b = start + mn.end();
            sb.setSpan(new StyleSpan(Typeface.BOLD), a, b, EX);
            sb.setSpan(new ForegroundColorSpan(soften(color, 0.62f)), a, b, EX);
        }
        java.util.regex.Matcher me = P_ERR.matcher(sub);
        while (me.find()) {
            int a = start + me.start(), b = start + me.end();
            sb.setSpan(new StyleSpan(Typeface.BOLD), a, b, EX);
            sb.setSpan(new ForegroundColorSpan(Palette.c(C_FAIL)), a, b, EX);
            sb.setSpan(new BackgroundColorSpan(Palette.c(0x46FF5A5A)), a, b, EX);
        }
    }

    /** 把颜色往白里提 t 倍：保留色相的"状态感"，同时压低饱和度免得小字刺眼 */
    /** 把 over 按 t 混进 base。soften 是往白色提亮，只会让字更抢眼；
     *  注释层要的是相反方向——沉下去，所以单独一个朝灰蓝靠的函数 */
    private static int mix(int base, int over, float t) {
        int a = (base >>> 24) & 0xFF;
        int r = (base >> 16) & 0xFF, g = (base >> 8) & 0xFF, b = base & 0xFF;
        int r2 = (over >> 16) & 0xFF, g2 = (over >> 8) & 0xFF, b2 = over & 0xFF;
        r = Math.round(r + (r2 - r) * t);
        g = Math.round(g + (g2 - g) * t);
        b = Math.round(b + (b2 - b) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int soften(int color, float t) {
        int a = (color >>> 24) & 0xFF;
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        r = Math.round(r + (255 - r) * t);
        g = Math.round(g + (255 - g) * t);
        b = Math.round(b + (255 - b) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * 工具框下面那条中文翻译行。
     *
     * <p>宽度和外层工具框完全对齐（同样 -1dp 外扩），所以两框右左边缘是一条线；
     * 行首一枚「执行」小徽章，颜色跟着这一步的状态走，
     * 后面是一行中文，超长省略——一条动作、一句人话，高度不带二段。</p>
     */
    private LinearLayout makeExplainBox() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setTag(TAG_EXPLAIN);
        // 按下反馈只到"能感觉到"为止：翻译行是二级信息，底色一重就抢了工具行的戏
        android.graphics.drawable.StateListDrawable sld =
                new android.graphics.drawable.StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_pressed},
                Ui.roundRect(this, Palette.c(0x14FFFFFF), 0, 6f));
        sld.addState(new int[]{}, Ui.roundRect(this, 0x00000000, 0, 6f));
        row.setBackground(sld);

        // 行首一条引线，是「引用」不是「标记」。
        // 上一版只给了 15dp 高，而这一行本身有 28dp 左右——线短一截，
        // 看上去就是根断了的短棍；右边再留 19dp 空隙，它就彻底悬在半空。
        // 现在：高度贴着文字块（单行 22dp，展开时按实际高度拉伸），
        // 两端做透明度渐变自然淡出，右边距收到 11dp 紧挨着文字。
        View bar = new View(this);
        bar.setTag(TAG_EXPLAIN_ICON);
        bar.setBackground(quoteBar(Palette.c(C_TEXT2)));
        // 左 8 + 线 2 + 间距 11 = 21dp：正文比工具行正文(30dp)略微凸出。
        // 引用块本来就把边界放在正文外侧，这种悬垂缩进读起来才像"附注"，
        // 硬对齐到 30dp 反而会让竖线远离文字、凭空多出一段空白。
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(2), dp(22));
        blp.leftMargin = dp(8);
        blp.rightMargin = dp(11);
        row.addView(bar, blp);

        // 用 ShimmerTextView：这一行既是“翻译行”也是“监控行”——
        // 跑的时候光扫过文字（还活着），跑完光停、文字定格成翻译。
        // 两件事一处占位，不必在气泡底部再挂一条重复的横幅
        com.nebula.aichat.ui.ShimmerTextView t = new com.nebula.aichat.ui.ShimmerTextView(this);
        t.setColors(0xFF9FB2C6, 0xFFCFE4F5);
        t.setTag(TAG_EXPLAIN_BODY);
        // 12.5sp：比正文小半档，又不至于小到要凑近看。
        // 解释文字是"读得快"的文字，字号偏小反而顺，关键是行距要松。
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        t.setLineSpacing(dp(3), 1.3f);
        // 单行固定高度：每一步都一样高，滚动时不会忽高忽低地"波动"；
        // 内容长就省略尾，想看全点行尾箭头松开行数
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(dp(0), dp(6), dp(7), dp(6));
        t.setTextColor(Palette.c(0xFF9FB2C6));
        row.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 耗时贴在最右端、箭头左边：和这句中文是同一次调用的产物，挨着放才对得上。
        // 9.5sp 等宽 + 低对比色，读的时候是"存在但不打扰"。
        TextView msTv = new TextView(this);
        msTv.setTag(TAG_EXPLAIN_MS);
        msTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        msTv.setTypeface(Typeface.MONOSPACE);
        msTv.setTextColor(Palette.c(0xFF7C8A96));
        msTv.setGravity(Gravity.CENTER_VERTICAL);
        msTv.setPadding(dp(8), 0, dp(2), 0);
        msTv.setVisibility(View.GONE);
        row.addView(msTv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 行尾箭头：点一下把这一句中文从单行放成多行。
        // 收到 15dp、压到 0xFF5A6775 —— 它是个"提示还有内容"的符号，不该有存在感
        ImageView ar = new ImageView(this);
        ar.setTag(TAG_EXPLAIN_ARROW);
        ar.setImageDrawable(new ChevronDrawable(Palette.c(0xFF5A6775)));
        ar.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams arlp = new LinearLayout.LayoutParams(dp(15), dp(15));
        arlp.leftMargin = dp(1);
        row.addView(ar, arlp);

        row.setOnClickListener(v -> toggleExplainExpand(row));
        return row;
    }

    /** 翻译行左侧引线：两端透明、中段实色，上下自然淡出，不会像被硬切一刀 */
    private android.graphics.drawable.Drawable quoteBar(int color) {
        int rgb = color & 0x00FFFFFF;
        int mid = rgb | (0xA0 << 24);
        int end = rgb;
        android.graphics.drawable.GradientDrawable gd =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{end, mid, mid, mid, end});
        return gd;
    }

    /** 点翻译行：单行 ⇄ 多行，箭头跟着翻 */
    /** 点翻译行：单行 ⇄ 多行，箭头跟着翻 */
    private void toggleExplainExpand(LinearLayout row) {
        if (row == null) return;
        TextView body = (TextView) row.findViewWithTag(TAG_EXPLAIN_BODY);
        View arrow = row.findViewWithTag(TAG_EXPLAIN_ARROW);
        if (body == null) return;
        boolean expand = arrow != null && Math.abs(arrow.getRotation()) < 1f;
        if (expand) {
            body.setSingleLine(false);
            body.setMaxLines(Integer.MAX_VALUE);
            body.setEllipsize(null);
        } else {
            body.setSingleLine(true);
            body.setMaxLines(1);
            body.setEllipsize(TextUtils.TruncateAt.END);
        }
        // 引线要跟着文字长，否则展开成一屏后只剩顶上短短一截，像断了。
        // 高度取"文字块实测高度 - 两端的淡出段"，布局完成后再量一次最准。
        final View bar = (View) row.findViewWithTag(TAG_EXPLAIN_ICON);
        if (bar != null) {
            if (expand) {
                body.post(new Runnable() {
                    @Override
                    public void run() {
                        int h = body.getHeight();
                        if (h <= dp(12)) return;
                        LinearLayout.LayoutParams blp =
                                (LinearLayout.LayoutParams) bar.getLayoutParams();
                        blp.height = h - dp(6);
                        blp.topMargin = dp(3);
                        bar.setLayoutParams(blp);
                    }
                });
            } else if (bar.getLayoutParams() instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams blp = (LinearLayout.LayoutParams) bar.getLayoutParams();
                blp.height = dp(22);
                blp.topMargin = 0;
                bar.setLayoutParams(blp);
            }
        }
        row.setGravity(expand ? Gravity.TOP : Gravity.CENTER_VERTICAL);
        if (arrow != null) arrow.animate().rotation(expand ? 180f : 0f).setDuration(200).start();
    }

    /** 找已经建好的详情区。不能按下标取——中间还隔着一行中文解析 */
    private static LinearLayout findDetailArea(View wrap) {
        return wrap instanceof ViewGroup ? findDetailIn((ViewGroup) wrap) : null;
    }

    /** 展开区现在挂在卡片里，不再和头行平级，所以得往下找一层 */
    private static LinearLayout findDetailIn(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v instanceof LinearLayout && v.getTag() instanceof Detail) {
                return (LinearLayout) v;
            }
            if (v instanceof ViewGroup) {
                LinearLayout r = findDetailIn((ViewGroup) v);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** 卡片本体：展开区要挂进卡片，不能挂到外框上，否则会压到左侧时间轴 */
    private static ViewGroup cardOf(View wrap) {
        if (wrap instanceof ViewGroup) {
            View c = wrap.findViewWithTag(TAG_TOOL_CARD);
            if (c instanceof ViewGroup) return (ViewGroup) c;
        }
        return wrap instanceof ViewGroup ? (ViewGroup) wrap : null;
    }

    /** 从参数 JSON 里挑一个字段值；取不到返回空串，绝不让解析层抛异常 */
    private static String argOf(String json, String... keys) {
        if (json == null || json.isEmpty()) return "";
        for (String k : keys) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"" + k + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
                    .matcher(json);
            if (m.find()) {
                String v = m.group(1);
                v = v.replace("\\n", " ").replace("\\t", " ").replace("\\\"", "\"");
                if (!v.trim().isEmpty()) return v.trim();
            }
        }
        return "";
    }

    private static String cut(String s, int n) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        while (t.contains("  ")) t = t.replace("  ", " ");
        return t.length() <= n ? t : t.substring(0, n) + "…";
    }

    /** 路径只留最后两段，够认人又不占地方 */
    private static String shortPath(String p) {
        if (p == null || p.trim().isEmpty()) return "(未指定)";
        String[] seg = p.trim().split("/");
        if (seg.length >= 2) return seg[seg.length - 2] + "/" + seg[seg.length - 1];
        return seg[seg.length - 1];
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        String t = s.trim();
        int i = t.indexOf('\n');
        if (i > 0) t = t.substring(0, i).trim();
        if (t.startsWith("出错 · ")) t = t.substring(5).trim();
        return cut(t, 60);
    }

    /**
     * 工具名 + 参数 → 一句中文。
     * 覆盖全部 14 个工具，认不出来就只报工具名，不硬编不存在的动作。
     */
    private String toolExplain(String name, String argsJson, String detail, boolean ok) {
        String project = argOf(argsJson, "project");
        String path = argOf(argsJson, "path", "dir", "file");
        String cmd = argOf(argsJson, "command", "cmd");
        String q = argOf(argsJson, "query", "keyword", "pattern");
        String nm = argOf(argsJson, "name", "app_name", "new_name");
        String what;
        switch (name == null ? "" : name) {
            case "apk_read_file":
                what = "读取文件 " + shortPath(path);
                break;
            case "apk_write_file":
                what = "写入文件 " + shortPath(path);
                break;
            case "apk_search":
                what = "在工程里搜索「" + cut(q, 24) + "」";
                break;
            case "apk_list_files":
                what = "浏览目录 " + shortPath(path);
                break;
            case "apk_list_projects":
                what = "列出工作台里的 APK 工程";
                break;
            case "apk_list_apks":
                what = "查找已构建的 APK";
                break;
            case "apk_inspect":
                what = "查看 APK 基本信息";
                break;
            case "apk_unpack":
                what = "解包 APK";
                break;
            case "apk_repack":
                what = "重新打包 APK";
                break;
            case "apk_changes":
                what = "检查这一轮的改动";
                break;
            case "apk_set_app_name":
                what = "把应用名改成「" + cut(nm, 20) + "」";
                break;
            case "build_env_status":
                what = "检查构建环境是否就绪";
                break;
            case "build_apk":
                what = "编译工程" + (project.trim().isEmpty() ? "" : "「" + cut(project, 18) + "」");
                break;
            case "bash_exec":
            // 旧会话里的历史消息还叫 run_shell，得继续认得出来
            case "run_shell":
                what = cmd.trim().isEmpty() ? "执行一条命令" : "执行 " + cut(cmd, 46);
                break;
            default:
                what = name == null || name.isEmpty() ? "调用工具" : name;
        }
        String res = firstLine(detail);
        if (ok) return res.isEmpty() ? what : what + " —— " + res;
        return what + " —— 失败" + (res.isEmpty() ? "" : "：" + res);
    }

    /** 详情区懒创建：顶部一条横线与摘要行隔开，下面是可滚动的等宽文本 */
    private LinearLayout ensureDetailArea(LinearLayout wrap) {
        LinearLayout exist = findDetailArea(wrap);
        if (exist != null) return exist;
        LinearLayout det = new LinearLayout(this);
        det.setOrientation(LinearLayout.VERTICAL);
        det.setVisibility(View.GONE);
        // 外框取消了，展开区得自己有个淡底，不然内容会直接漂在气泡上
        det.setBackground(Ui.roundRect(this, Palette.c(0x0FFFFFFF), 0, 10f));

        View div = new View(this);
        div.setBackgroundColor(Palette.c(0x16FFFFFF));   // 与翻译框描边同档，别比自己的边框还亮
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dlp.leftMargin = dp(10);
        dlp.rightMargin = dp(8);
        det.addView(div, dlp);

        TextView tv = new TextView(this);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(Palette.c(0xFFA6B7CE));
        tv.setLineSpacing(dp(2), 1f);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(12), dp(8), dp(9), dp(2));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 「结果」标题行：标题靠左，橙色「复制」靠右
        LinearLayout resHead = new LinearLayout(this);
        resHead.setOrientation(LinearLayout.HORIZONTAL);
        resHead.setGravity(Gravity.CENTER_VERTICAL);
        resHead.setPadding(dp(12), dp(10), dp(6), 0);

        TextView lbl = new TextView(this);
        lbl.setText("结果");
        lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        lbl.setTypeface(Typeface.MONOSPACE);
        lbl.setTextColor(Palette.c(0xFF7E93AE));
        resHead.addView(lbl, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView copy = new TextView(this);
        copy.setText("复制");
        copy.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        copy.setTypeface(Typeface.MONOSPACE);
        copy.setTextColor(Palette.c(0xFFFFB36B));
        copy.setPadding(dp(12), dp(4), dp(6), dp(4));
        copy.setClickable(true);
        TypedValue ripple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        if (ripple.resourceId != 0) copy.setBackgroundResource(ripple.resourceId);
        resHead.addView(copy, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(resHead, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView result = new TextView(this);
        result.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        result.setTypeface(Typeface.MONOSPACE);
        result.setTextColor(Palette.c(0xFFA6B7CE));
        result.setLineSpacing(dp(2), 1f);
        result.setPadding(dp(12), dp(3), dp(9), dp(9));
        result.setTextIsSelectable(true);
        col.addView(result, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Detail d = new Detail(tv, result, copy);
        copy.setOnClickListener(v -> copyResult(d));

        ScrollView sc = new ScrollView(this);
        sc.setVerticalScrollBarEnabled(false);
        sc.addView(col, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        det.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        det.setTag(d);
        ViewGroup cardHost = cardOf(wrap);
        if (cardHost == null) cardHost = wrap;
        cardHost.addView(det, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return det;
    }

    /** 把这一行的参数与结果铺进详情区 */
    private void fillToolDetail(LinearLayout wrap, LinearLayout det) {
        Object t = det == null ? null : det.getTag();
        if (!(t instanceof Detail)) return;
        Detail d = (Detail) t;

        String name = "", args = "", res = "";
        boolean ok = true;
        Object tag = wrap.getTag();
        if (tag instanceof Integer) {
            int i = (Integer) tag;
            if (i >= 0) {
                if (i < toolRowNames.size()) name = toolRowNames.get(i);
                if (i < toolRowArgs.size()) args = toolRowArgs.get(i);
                if (i < toolRowDetail.size()) res = toolRowDetail.get(i);
                if (i < toolRowState.size()) ok = toolRowState.get(i) == ST_OK;
            }
        } else if (tag instanceof com.nebula.aichat.data.Message.Step) {
            com.nebula.aichat.data.Message.Step st =
                    (com.nebula.aichat.data.Message.Step) tag;
            name = st.text == null ? "" : st.text;
            args = st.arg == null ? "" : st.arg;
            res = st.res == null ? "" : st.res;
            ok = st.ok;
        }

        StringBuilder sb = new StringBuilder();
        // 展开后第一眼给结论：这一步干了什么、成没成。
        sb.append("说明\n  ").append(toolExplain(name, args, res, ok));
        String a = args == null ? "" : args.trim();
        sb.append("\n\n参数\n  ").append(a.isEmpty() ? "（无）" : prettyArgs(a));
        // 「说明/参数」当小标题用：压暗一档，跟着的正文才成为视觉主体
        SpannableStringBuilder brief = new SpannableStringBuilder(sb.toString());
        dimWord(brief, "说明");
        dimWord(brief, "参数");
        d.brief.setText(brief);

        String r = res == null ? "" : res.trim();
        boolean empty = r.isEmpty();
        d.result.setText(empty ? "（还没返回）" : r);
        // 没内容可复制时把按钮调暗，点下去不会像没反应
        d.copy.setTextColor(empty ? Palette.c(0xFF5A6B80) : Palette.c(0xFFFFB36B));
    }

    /** 把详情区里的小标题词压暗并加粗一点，让正文成为主角 */
    private void dimWord(SpannableStringBuilder sp, String word) {
        int i = sp.toString().indexOf(word);
        if (i < 0) return;
        int e = i + word.length();
        sp.setSpan(new ForegroundColorSpan(Palette.c(0xFF7E93AE)), i, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sp.setSpan(new StyleSpan(Typeface.BOLD), i, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** 详情区三段：说明/参数合一块，结果单独一块，右上角一个复制 */
    private static class Detail {
        final TextView brief;
        final TextView result;
        final TextView copy;

        Detail(TextView brief, TextView result, TextView copy) {
            this.brief = brief;
            this.result = result;
            this.copy = copy;
        }
    }

    /** 复制这一行的结果，方便粘去别处或拿去搜索 */
    private void copyResult(Detail d) {
        CharSequence cs = (d == null || d.result == null) ? null : d.result.getText();
        String text = cs == null ? "" : cs.toString().trim();
        if (text.isEmpty() || text.startsWith("（还没返回）")) {
            Toast.makeText(this, "还没有结果可以复制", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("结果", text));
            Toast.makeText(this, "结果已复制", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show();
        }
    }

    /** 参数键名的中文说法 */
    private static String keyLabel(String k) {
        switch (k == null ? "" : k) {
            case "project": return "工程";
            case "project_dir": return "工程目录";
            case "path": return "路径";
            case "dir": return "目录";
            case "file": return "文件";
            case "content": return "内容";
            case "command":
            case "cmd": return "命令";
            case "query":
            case "keyword":
            case "pattern": return "关键字";
            case "name": return "名称";
            case "app_name": return "应用名";
            case "new_name": return "新名称";
            case "max_bytes": return "最多字节";
            case "apk": return "APK";
            case "replace": return "是否替换";
            default: return k;
        }
    }

    /** 参数 JSON 拆成「工程 = xxx」这样一行一条，不让人去数大括号 */
    private static String prettyArgs(String json) {
        StringBuilder sb = new StringBuilder();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"([A-Za-z_]+)\"\\s*:\\s*(\"((?:\\\\.|[^\"\\\\])*)\"|[^,}\\s]+)")
                .matcher(json == null ? "" : json);
        while (m.find()) {
            String v = m.group(3) != null ? m.group(3) : m.group(2);
            v = v.replace("\\n", " ").replace("\\t", " ").replace("\\\"", "\"");
            sb.append(keyLabel(m.group(1))).append(" = ").append(cut(v, 80)).append("\n");
        }
        String s = sb.toString().trim();
        return s.isEmpty() ? cut(json, 200) : s;
    }

    /** 结果回来时，这一行如果正展开着，立刻刷新里面的内容 */
    private void refreshToolDetailIfOpen(int idx) {
        if (idx < 0 || idx >= toolRows.size()) return;
        Object w = toolRows.get(idx).getTag();
        if (!(w instanceof LinearLayout)) return;
        LinearLayout wrap = (LinearLayout) w;
        LinearLayout det = findDetailArea(wrap);
        if (det != null && det.getVisibility() == View.VISIBLE) fillToolDetail(wrap, det);
    }

    /** 把行在外框上挂好索引，展开详情时据此取参数与结果 */
    private void bindToolRowIndex(TextView row, int idx) {
        if (row == null || idx < 0) return;
        Object w = row.getTag();
        if (w instanceof LinearLayout) ((LinearLayout) w).setTag(Integer.valueOf(idx));
    }

    /**
     * 连续的工具调用都塞进同一个组：默认只露最后一步，前面几步折叠成一行提示。
     * 否则模型连着调研十几次，气泡就被一行行的工具调用拉到没边，根本没法读。
     */
    /**
     * 工具框的底：一层淡玻璃 + 1dp 细边，不再叠第二圈"发光"。
     * 之前那圈外扩描边是硬边套硬边，放大看两层边颜色对不齐，发脏。
     * 现在只有跑的时候边是绿的，跑完就退回中性灰白，列表里不会一片荧光绿。
     */
    /**
     * 工具框的皮：1dp 实色亮边压在 2dp 内侧柔光上，中间是极淡的同色渐变。
     * 亮的是「边框」，不是整块底——既跳得出来，又不会把里面的等宽命令文字淹掉。
     */
    private android.graphics.drawable.Drawable toolBoxBg(int state) {
        int accent, top, bottom;
        if (state == ST_RUN) {
            // 跑着：最亮的一档，一眼看到"现在在动"
            accent = Palette.c(0xFF5CF2A6); top = Palette.c(0x2E34D399); bottom = Palette.c(0x0F34D399);
        } else if (state == ST_FAIL) {
            accent = Palette.c(0xFFFF7070); top = Palette.c(0x2EFF6B6B); bottom = Palette.c(0x0FFF6B6B);
        } else if (state == ST_ABORT) {
            accent = Palette.c(0xFFC9A227); top = Palette.c(0x1AC9A227); bottom = Palette.c(0x08C9A227);
        } else {
            // 跑完：压一档薄荷绿，和"跑着"同族但不再抢眼
            accent = Palette.c(0xFF41D69B); top = Palette.c(0x1F34D399); bottom = Palette.c(0x0B34D399);
        }

        int r = dp(14);

        // 内层柔光：2dp 半透明 accent 描边，贴着边给一圈"通电"的晕
        android.graphics.drawable.GradientDrawable glow =
                new android.graphics.drawable.GradientDrawable();
        glow.setColor(Palette.c(0x00000000));
        glow.setStroke(dp(2), (accent & 0x00FFFFFF) | Palette.c(0x59000000));
        glow.setCornerRadius(r);

        // 本体：淡渐变底 + 1dp 实色 accent，亮线压在柔光内侧
        android.graphics.drawable.GradientDrawable core =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{top, bottom});
        core.setCornerRadius(r);
        core.setStroke(Math.max(1, dp(1)), accent);

        // 顶沿 1dp 高光：光从上方擦过，卡片边缘立起来
        android.graphics.drawable.GradientDrawable hi =
                new android.graphics.drawable.GradientDrawable();
        hi.setColor(Palette.c(0x24FFFFFF));
        hi.setCornerRadius(r);

        android.graphics.drawable.LayerDrawable ld =
                new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[]{glow, core, hi});
        int i1 = Math.max(1, dp(1));
        ld.setLayerInset(2, i1, i1, i1, 0);
        ld.setLayerHeight(2, i1);
        return ld;
    }

    private LinearLayout ensureToolGroup() {
        if (toolGroupBox != null) return toolGroupBox;
        LinearLayout host = toolHost != null ? toolHost
                : (streamContent != null ? streamContent : chatContainer);

        final TextView head = new TextView(this);
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        head.setTextColor(Palette.c(0xFF7E93AE));
        head.setPadding(dp(13), dp(5), dp(13), dp(5));
        head.setVisibility(View.GONE);
        head.setClickable(true);
        head.setBackground(Ui.roundRect(this, Palette.c(0x0DFFFFFF), Palette.c(0x1AFFFFFF), 20f));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.leftMargin = dp(4);
        hlp.topMargin = dp(4);
        hlp.bottomMargin = dp(2);
        host.addView(head, hlp);

        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        host.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        head.setOnClickListener(v -> toggleToolGroup(box, head));

        toolGroupBox = box;
        toolGroupHead = head;
        procGroups.add(new View[]{head, box});
        return box;
    }

    /** 思考块内部三件套的 tag：正文边想边长、标题承载秒数、扫光负责「还在思考」 */
    /** 思考进行中的标题文案：和结束后的「思考内容」区分开 */
    private static final String THINK_LIVE_TITLE = "思考中";
    private static final String TAG_THINK_BODY = "think_body";
    private static final String TAG_THINK_LABEL = "think_label";
    private static final String TAG_THINK_SWEEP = "think_sweep";
    private static final String TAG_THINK_CHEVRON = "think_chev";
    /** 状态行与正文之间那条 1dp 渐隐分隔线：撤状态行时得连它一起撤 */
    private static final String TAG_THINK_HAIR = "think_hair";

    /**
     * 平铺：组里每一步都露着。
     *
     * <p>以前这里只让最后一步露脸，其余收起来配一行「前面还有 N 步」，
     * 想法是省地方。实际用下来，被收掉的正是「它到底做了哪些事」，
     * 用户还得再点一次才看得见——干脆全展开，气泡里的过程按发生顺序
     * 从上往下读，不用二次操作。</p>
     *
     * @param open 保留参数只为调用点不动；现在无论传什么都全展开
     */
    private void applyToolGroup(LinearLayout box, TextView head, boolean open) {
        if (box == null) return;
        int n = box.getChildCount();
        for (int i = 0; i < n; i++) {
            View v = box.getChildAt(i);
            if (v != null) v.setVisibility(View.VISIBLE);
        }
        // 「前面还有 N 步」这条提示行本身没有存在意义了
        if (head != null) head.setVisibility(View.GONE);
    }

    private void refreshToolGroup() {
        LinearLayout box = toolGroupBox;
        if (box == null) return;
        applyToolGroup(box, toolGroupHead, Boolean.TRUE.equals(box.getTag()));
    }

    private void toggleToolGroup(LinearLayout box, TextView head) {
        boolean open = !Boolean.TRUE.equals(box.getTag());
        box.setTag(Boolean.valueOf(open));
        applyToolGroup(box, head, open);
    }

    /** 正文接上了，这一组工具到此为止；后面的调用另起一组 */
    /**
     * 把"当前这段思考"落成一条 step。
     *
     * <p>实时界面里思考是分段显示的（每次调用前一段、最终回答前一段），
     * 但过去只把整轮 reasoning 存成一整坨，重开会话就还原不出"哪段思考对应哪次调用"。
     * 这里在"这段思考结束"的时刻把它按段存进时间线，历史回放才能和实时一样交错。
     * 用 thinkSegStart 做游标，重复调用不会把一个字存两遍。</p>
     */
    private void takeThinkStep() {
        if (tlSteps == null || thinkBuf == null) return;
        int len = thinkBuf.length();
        if (len <= thinkSegStart) return;
        String part = thinkBuf.substring(thinkSegStart, len);
        thinkSegStart = len;
        if (part.trim().isEmpty()) return;
        com.nebula.aichat.data.Message.Step st = new com.nebula.aichat.data.Message.Step();
        st.type = com.nebula.aichat.data.Message.Step.THINK;
        st.text = part;
        long now = SystemClock.uptimeMillis();
        long from = thinkSegAt > 0 ? thinkSegAt : thinkStartAt;
        if (from > 0 && now > from) st.ms = now - from;
        thinkSegAt = now;
        tlSteps.add(st);
    }

    private void closeToolGroup() {
        if (toolGroupBox != null) refreshToolGroup();
        toolGroupBox = null;
        toolGroupHead = null;
    }

    // ------------------------------------------------------------ 过程收纳

    /**
     * 秒数的唯一落点：写在思考块的标题行上。
     *
     * <p>气泡里已经没有折叠头了——过程全部平铺，没有「一行标题」可以挂秒数，
     * 于是回到最自然的位置：思考块自己的标题。一轮里也只有这一处，
     * 不会出现两个数字打架。</p>
     *
     * @return 写进去了就 true；没有思考块（这轮没产生推理内容）就 false，交给调用方兜底
     */
    private boolean putThinkSecs(LinearLayout host, long thinkMs) {
        if (thinkMs <= 0) return false;
        TextView lb = null;
        // 实时的思考块挂在气泡里、和正文内容区平级，从内容区往下找是找不到的
        if (liveThinkBox != null) {
            View v = liveThinkBox.findViewWithTag(TAG_THINK_LABEL);
            if (v instanceof TextView) lb = (TextView) v;
        }
        if (lb == null && host != null) {
            View v = host.findViewWithTag(TAG_THINK_LABEL);
            if (v instanceof TextView) lb = (TextView) v;
        }
        if (lb == null) return false;
        lb.setText("思考 \u00b7 " + fmtSecs(thinkMs));
        return true;
    }

    /** 秒数文案：一分钟以内保留一位小数，超过换成「1 分 05 秒」 */
    private static String fmtSecs(long ms) {
        if (ms < 60000L) return String.format(Locale.US, "%.1f 秒", ms / 1000.0);
        long s = Math.round(ms / 1000.0);
        return (s / 60) + " 分 " + String.format(Locale.US, "%02d", s % 60) + " 秒";
    }

    /** 本轮答完：过程不再收起，只把秒数落到唯一该出现的位置 */
    private void collapseStreamProcess() {
        stopLiveThink();
        if (streamContent == null) return;
        // 有思考块就写在它标题上；没有（这轮压根没推理内容）退回状态行，
        // 保证「一轮里总有一处能看出思考花了多久」
        if (putThinkSecs(toolHost != null ? toolHost : streamContent, runThinkMs)) return;
        if (statusText != null && runThinkMs > 0) {
            statusText.setText("思考完成 \u00b7 用时 " + fmtSecs(runThinkMs));
        }
    }

    // ------------------------------------------------------------ 实时思考块

    /**
     * 思考进行中就把块立起来：标题整行走光、正文边想边长。
     *
     * <p>以前是等思考结束（首个正文到达）才一次性建块，中途界面上只剩状态行
     * 一个转圈的小图标，看不出模型在干嘛。现在边收边显示，那道扫光就是
     * 「正在思考」本身的可视化。</p>
     */
    private void ensureLiveThink() {
        if (liveThinkBox != null) return;
        // 思考段挂进"过程流"（工具行那个容器）：本轮所有调用行都追加在它前面，
        // 于是天然形成 调用 → 思考 → 调用 → 思考 的交替顺序。
        // 单独搞一个 thinkHost 的结果是所有调用堆在顶部、所有思考挤在下面一块，
        // 看不出对应关系，所以这里彻底并到同一个流里。
        LinearLayout host = toolHost != null ? toolHost
                : (streamContent != null ? streamContent : null);
        if (host == null) return;
        // 当前工具组先收口：下一次调用要另起一组，才会排在本文思考段之后
        closeToolGroup();
        View box = buildThinkBox("", null);
        TextView lb = box.findViewWithTag(TAG_THINK_LABEL);
        if (lb != null) lb.setText(THINK_LIVE_TITLE);
        // 思考中这行文字横向滚动：一行装不下就一直滚，滚完这轮就轮到下一次调用
        TextView b = thinkBodyOf(box);
        if (b != null) {
            b.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
            b.setMarqueeRepeatLimit(-1);
            b.setHorizontallyScrolling(true);
            b.setSelected(true);
        }
        host.addView(box);
        liveThinkBox = box;
    }

    /** 思考片段到了就追加一行字；不再重复建块 */
    private void appendLiveThink(String piece) {
        if (piece == null || piece.isEmpty()) return;
        ensureLiveThink();
        if (liveThinkBox == null) return;
        TextView body = thinkBodyOf(liveThinkBox);
        if (body == null) return;
        if (body.length() < 40000) body.append(piece);
        // 一轮任务里模型会来回思考好几次（每次工具返回都可能再想一遍）：
        // 标题要回到「思考中」，否则第二段思考看着像已经结束了
        TextView lb = liveThinkBox.findViewWithTag(TAG_THINK_LABEL);
        if (lb != null && !THINK_LIVE_TITLE.contentEquals(lb.getText())) {
            lb.setText(THINK_LIVE_TITLE);
        }
        // 展开看全文时内容会一直变长，视图要跟着走
        if (body.getMaxLines() != 1) scrollToBottom(false);
    }

    /** 思考结束：这一行停止滚动、收回到一行，标题从「思考中」换回「思考」 */
    private void stopLiveThink() {
        if (liveThinkBox == null) return;
        TextView lb = liveThinkBox.findViewWithTag(TAG_THINK_LABEL);
        if (lb != null) lb.setText("思考");
        TextView body = thinkBodyOf(liveThinkBox);
        if (body != null && body.getMaxLines() == 1) {
            body.setSelected(false);
            body.setEllipsize(android.text.TextUtils.TruncateAt.END);
        }
    }

    private TextView thinkBodyOf(View box) {
        if (box == null) return null;
        View v = box.findViewWithTag(TAG_THINK_BODY);
        return v instanceof TextView ? (TextView) v : null;
    }

    private com.nebula.aichat.ui.ShimmerSweep thinkSweepOf(View box) {
        if (box == null) return null;
        View v = box.findViewWithTag(TAG_THINK_SWEEP);
        return v instanceof com.nebula.aichat.ui.ShimmerSweep
                ? (com.nebula.aichat.ui.ShimmerSweep) v : null;
    }

    private TextView newToolRow() {
        LinearLayout host = ensureToolGroup();
        LinearLayout wrap = makeToolWrap(host);
        TextView body = toolBodyOf(wrap);
        if (body != null) body.setTag(wrap);   // body → 外框，裁剪时按外框整体移除
        return body;
    }

    /** 工具跑完：把结果写回时间线那一步，重开会话时也能看到当时到底发生了什么 */
    private void updateStepResult(int rowIdx, boolean ok, String detail, long ms) {
        if (rowIdx < 0 || rowIdx >= toolRowStep.size()) return;
        com.nebula.aichat.data.Message.Step st = toolRowStep.get(rowIdx);
        if (st == null) return;
        st.ok = ok;
        st.res = detail;
        st.ms = ms;
    }

    /** 行数超限就丢掉最早的，保证长任务下内存和渲染量不膨胀 */
    private void trimToolRows() {
        while (toolRows.size() > TOOL_MAX_ROWS) {
            TextView old = toolRows.remove(0);
            Object w = old.getTag();
            if (w instanceof LinearLayout) {
                LinearLayout wrap = (LinearLayout) w;
                if (wrap.getParent() instanceof android.view.ViewGroup) {
                    android.view.ViewGroup pg = (android.view.ViewGroup) wrap.getParent();
                    int at = pg.indexOfChild(wrap);
                    // 紧跟其后的翻译框是同一条记录的一部分，别留下孤儿
                    if (at >= 0 && at + 1 < pg.getChildCount()) {
                        View nx = pg.getChildAt(at + 1);
                        if (TAG_EXPLAIN.equals(nx.getTag())) pg.removeView(nx);
                    }
                    pg.removeView(wrap);
                }
            } else if (old.getParent() instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) old.getParent()).removeView(old);
            }
            if (!toolRowNames.isEmpty()) toolRowNames.remove(0);
            if (!toolRowDone.isEmpty()) toolRowDone.remove(0);
            if (!toolRowStart.isEmpty()) toolRowStart.remove(0);
            if (!toolRowArgs.isEmpty()) toolRowArgs.remove(0);
            if (!toolRowDetail.isEmpty()) toolRowDetail.remove(0);
            if (!toolRowState.isEmpty()) toolRowState.remove(0);
            if (!toolRowStep.isEmpty()) toolRowStep.remove(0);
        }
    }

    /** 让小窗口始终跟着最新一条，效果就像终端 tail -f */
    private void scrollToolTail() {
        if (toolScroll == null) return;
        toolScroll.post(() -> toolScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void showToolRowDetail(int i) {
        if (i < 0 || i >= toolRowNames.size()) return;
        String args = i < toolRowArgs.size() ? toolRowArgs.get(i) : "";
        String detail = i < toolRowDetail.size() ? toolRowDetail.get(i) : "";
        String body = "工具：" + toolRowNames.get(i)
                + "\n\n参数：\n" + (args == null || args.isEmpty() ? "（无）" : args)
                + "\n\n结果：\n" + (detail == null || detail.isEmpty() ? "（还没返回）" : detail);
        new NebAlert.Builder(this)
                .setTitle("工具调用详情")
                .setMessage(body)
                .setPositiveButton("复制", (dd, ww) -> copyText(body, "已复制"))
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 耗时显示：1 秒内用毫秒，10 秒内带一位小数，更长就取整秒 */
    private static String dur(long ms) {
        // 统一用秒：避免刚开跑时出现「0ms」「320ms」这种毫秒混排，
        // 一行里只有「执行 0.3s」这一种格式，读起来才不跳。
        if (ms < 10000) return String.format(Locale.ROOT, "%.1fs", Math.max(0, ms) / 1000f);
        return (ms / 1000) + "s";
    }

    private int pendingToolCount() {
        int n = 0;
        for (int i = 0; i < toolRowDone.size(); i++) if (!toolRowDone.get(i)) n++;
        return n;
    }

    /** 卡片标题右侧的总状态：有命令在跑时显示旋转符 + 已耗时，全部结束就回到安静的「N 次」 */
    private void updateToolHead() {
        if (toolTraceCount == null) return;
        if (toolRowDone.isEmpty()) {
            toolTraceCount.setText("");
            return;
        }
        int pending = 0;
        long oldest = 0;
        for (int i = 0; i < toolRowDone.size(); i++) {
            if (!toolRowDone.get(i)) {
                pending++;
                long st = toolRowStart.get(i);
                if (oldest == 0 || st < oldest) oldest = st;
            }
        }
        if (pending > 0) {
            toolTraceCount.setText("\u00b7 " + toolRows.size() + " 次  "
                    + SPIN[spinIdx % SPIN.length] + " 执行中 " + dur(SystemClock.uptimeMillis() - oldest));
            toolTraceCount.setTextColor(Palette.c(C_RUN));
        } else {
            toolTraceCount.setText("\u00b7 " + toolRows.size() + " 次");
            toolTraceCount.setTextColor(Palette.c(C_DIM));
        }
    }

    // ------------------------------------------------------------ 运行横幅

    /**
     * 运行横幅 = 一行元信息条。
     * 设计约束（有意为之，改之前先想清楚）：
     *  1. 单行，三个信息位：状态竖条 / 在做什么 / 计时（右对齐）；底边让出一条跑带给兔子。
     *  2. 底色只有一层 5% 白玻璃，没有描边、没有状态染色 —— 彩色块在深色气泡里必然显廉价。
     *  3. 颜色只出现在两处：左侧 2dp 竖条、计时数字。
     *  4. 动效只有两个：底部 2dp 细进度匀速滑过；踩着那条光跑过去的小兔子。不要呼吸、流光、
     *     涟漪、图标弹跳。兔子必须在横幅内部、和流光同一条线上 —— 单独占一行是把横幅变成
     *     两行，白吃竖直空间。
     *  5. 不写解释型文案（"没卡住""现在已经停了"），那是提示条的语气，不是产品。
     */
    /**
     * 运行横幅 ——「扫描线」款。
     *
     * <p>只留三层信息：状态标签（执行中/完成）、一句话说清在干什么、耗时。
     * 整条只有一处动效：底部一条同色光带以 2.2s 的节奏缓慢扫过。它表示"还在动"，
     * 不表示进度。工具调用的耗时不可预测，任何匀速推进的动画都在暗示一个假进度，
     * 跑完一圈又从头开始，看着比不动更焦虑——所以这里刻意不给进度感。</p>
     *
     * <p>去掉了胶囊底、兔子和并发动画。气泡底部本来就有工具行在报同样的工具名和耗时，
     * 横幅要是再堆动效，就是两个东西在抢同一句话的解释权。</p>
     */
    /**
     * 运行横幅 ——「流光」最终版。
     *
     * <p>整条只有一句话、一个标签、一个用时，和一个动效：光扫过文字。</p>
     *
     * <p>三条设计原则：</p>
     * <ol>
     *   <li><b>不假装进度。</b>工具调用的耗时不可预测，任何"跑满全宽"的动画都在暗示
     *       一个假百分比。所以底部跑马灯去掉了，改由文字自己发一道光——它只说"还活着"。</li>
     *   <li><b>说人话。</b>「读取 build.gradle」而不是 read_file，用户不需要认识工具名。</li>
     *   <li><b>不做多余的呼吸。</b>竖条、标签、秒数全部静止。整条横只有一个运动物体，
     *       视线才不会被三处动效拆散。</li>
     * </ol>
     */
    private LinearLayout buildRunBanner() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setVisibility(View.GONE);

        final android.widget.FrameLayout bar = new android.widget.FrameLayout(this);
        bar.setVisibility(View.GONE);
        bar.setClipChildren(true);
        bar.setClipToPadding(false);

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setGravity(Gravity.CENTER_VERTICAL);
        inner.setPadding(dp(9), dp(7), dp(9), dp(7));

        // 左侧 2dp 竖条：整条横幅唯一的色源。中段实色两端透明，像一根引线而不是分隔线
        runBannerRailBg = new View(this);
        runBannerRailBg.setBackground(fadeBar(Palette.c(C_RUN)));
        inner.addView(runBannerRailBg, new LinearLayout.LayoutParams(dp(2), dp(15)));

        // 状态标签在最前：先说"在执行"，再说"在干什么"。
        // 原来是「执行 1.2s」把动词贴在秒数前，读起来成了时间的修饰语，本末倒置。
        runBannerTimeTag = new TextView(this);
        runBannerTimeTag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        runBannerTimeTag.setTextColor(Palette.c(0xFF5C6B7E));
        runBannerTimeTag.setIncludeFontPadding(false);
        runBannerTimeTag.setSingleLine(true);
        // 小字加字距：排版上"看着高级"最省力的一招，几乎零成本
        runBannerTimeTag.setLetterSpacing(0.1f);
        LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tagLp.leftMargin = dp(9);
        inner.addView(runBannerTimeTag, tagLp);

        // 主信息：中文动作，带流光。
        // 12.5sp 不加粗 —— 重量交给颜色和那道光，靠字重撑起来会显得笨。
        com.nebula.aichat.ui.ShimmerTextView act = new com.nebula.aichat.ui.ShimmerTextView(this);
        act.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        act.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        act.setIncludeFontPadding(false);
        act.setSingleLine(true);
        act.setEllipsize(TextUtils.TruncateAt.END);
        act.setColors(0xFF6FCF97, 0xFFEAFFF4);
        runBannerText = act;
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nameLp.leftMargin = dp(7);
        inner.addView(act, nameLp);

        // 并发计数：多个同时跑才出现，压到最弱的一档灰
        runBannerMeta = new TextView(this);
        runBannerMeta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        runBannerMeta.setTextColor(Palette.c(0xFF5A6A7D));
        runBannerMeta.setIncludeFontPadding(false);
        runBannerMeta.setSingleLine(true);
        runBannerMeta.setVisibility(View.GONE);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.leftMargin = dp(6);
        inner.addView(runBannerMeta, metaLp);

        // 计时不加粗：工具名才该是最重的东西，秒数一加重，视线就先落在时间上。
        // 固定 46dp + 等宽 + tnum，9.9 跳到 10.0 时右边缘不抖。
        runBannerTime = new TextView(this);
        runBannerTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        runBannerTime.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
        runBannerTime.setTextColor(Palette.c(0xFF6E7D90));
        runBannerTime.setIncludeFontPadding(false);
        runBannerTime.setSingleLine(true);
        runBannerTime.setGravity(Gravity.END);
        try {
            runBannerTime.setFontFeatureSettings("tnum");
        } catch (Throwable ignored) {
        }
        LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(
                dp(46), ViewGroup.LayoutParams.WRAP_CONTENT);
        timeLp.leftMargin = dp(8);
        inner.addView(runBannerTime, timeLp);

        bar.addView(inner, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        runBanner = bar;
        wrap.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    /** 竖条：中段实色、两端透明。一刀切的色块看着像分隔符，渐隐才像"引线" */
    private android.graphics.drawable.Drawable fadeBar(int color) {
        int rgb = color & 0x00FFFFFF;
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{rgb | 0x00000000, rgb | 0xE0000000,
                        rgb | 0xE0000000, rgb | 0x00000000});
    }

    /** 兔子已从横幅撤下：它匀速横穿在暗示假进度。保留方法让旧调用点全部变成空操作 */
    private void startRabbit() {
        if (runRabbit == null) return;
        if (runRabbit.isRunning()) return;
        runRabbit.setTintColor(Palette.c(0xFF93F2C4));
        runRabbit.setVisibility(View.VISIBLE);
        runRabbit.start();
    }

    /** 任务收尾：兔子下班，跑道跟着收起 */
    private void stopRabbit() {
        if (runRabbit == null) return;
        runRabbit.stop();
        runRabbit.setVisibility(View.GONE);
    }

    /** 配色：状态色只出现在竖条和主文字上，其余全部中性 */
    private void paintBanner(int color, boolean running) {
        try {
            runBanner.setBackground(null);
            if (runBannerRailBg != null) runBannerRailBg.setBackground(fadeBar(color));

            if (runBannerText instanceof com.nebula.aichat.ui.ShimmerTextView) {
                com.nebula.aichat.ui.ShimmerTextView st =
                        (com.nebula.aichat.ui.ShimmerTextView) runBannerText;
                if (running) {
                    // 运行：中等亮度的绿做基色，近白的绿做高光。
                    // 基色不能太亮，否则光扫过时高光没有对比，等于没扫
                    st.setColors(0xFF6FCF97, 0xFFEAFFF4);
                } else {
                    // 收尾：定格成实色，高光只保留一点点，避免结束瞬间颜色突然变淡
                    st.setColors(soften(color, 0.10f), soften(color, 0.58f));
                }
            } else if (runBannerText != null) {
                runBannerText.setTextColor(running ? Palette.c(0xFF93F2C4) : soften(color, 0.18f));
            }
            if (runBannerMeta != null) runBannerMeta.setTextColor(Palette.c(0xFF5A6A7D));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 唯一动效：文字上扫过一道柔光。
     * 周期 1.9s，其中约三分之一的时间光在字上，剩下的时间画面完全静止——
     * 低频的动作比持续闪动更耐看，也更像"后台在干活"而不是"界面在表演"。
     */
    private void startBannerAnim() {
        if (runBannerText instanceof com.nebula.aichat.ui.ShimmerTextView) {
            com.nebula.aichat.ui.ShimmerTextView st =
                    (com.nebula.aichat.ui.ShimmerTextView) runBannerText;
            if (st.isShimmering()) return;
            st.startShimmer(1900);
        }
    }

    private void stopBannerAnim() {
        if (runBannerText instanceof com.nebula.aichat.ui.ShimmerTextView) {
            ((com.nebula.aichat.ui.ShimmerTextView) runBannerText).stopShimmer();
        }
        stopRabbit();
    }

    private void showRunBanner(String name, String meta, String time, int color, boolean running) {
        if (runBanner == null || runBannerText == null) return;
        runBannerText.setText(name);
        if (runBannerMeta != null) {
            boolean has = meta != null && meta.length() > 0;
            runBannerMeta.setText(has ? meta : "");
            runBannerMeta.setVisibility(has ? View.VISIBLE : View.GONE);
        }
        if (runBannerTime != null) runBannerTime.setText(time == null ? "" : time);
        if (runBannerTimeTag != null) {
            // 标签只报状态，不再报"执行/用时/停止"这种半截动词
            String tag;
            if (running) {
                tag = "执行中";
            } else if (color == Palette.c(C_OK)) {
                tag = "完成";
            } else if (color == Palette.c(C_FAIL)) {
                tag = "有失败";
            } else {
                tag = "已停止";
            }
            runBannerTimeTag.setText(tag);
            runBannerTimeTag.setTextColor(running ? Palette.c(0xFF5C6B7E) : soften(color, 0.32f));
        }
        paintBanner(color, running);
        if (runBannerWrap != null) {
            boolean firstShow = runBannerWrap.getVisibility() != View.VISIBLE;
            runBannerWrap.setVisibility(View.VISIBLE);
            if (firstShow) {
                // 淡入而不是硬出现：这条横幅是"突然开始工作"的信号，硬切换像闪屏
                runBannerWrap.setAlpha(0f);
                runBannerWrap.animate().alpha(1f).setDuration(180)
                        .setInterpolator(new DecelerateInterpolator()).start();
            } else {
                runBannerWrap.animate().cancel();
                runBannerWrap.setAlpha(1f);
            }
        }
        runBanner.setVisibility(View.VISIBLE);
        runBanner.setAlpha(1f);
        if (running) startRabbit();
        else stopRabbit();
    }

    /** 还有命令在跑：工具名 + 计时 + 底部细进度，信息密度高但不吵 */
    /**
     * 横幅上那句话。优先说人话——「读取 build.gradle」，而不是 read_file。
     * 用户不需要知道工具叫什么，只需要知道它在干什么；取不到才退回工具名。
     */
    private String bannerAct() {
        try {
            int i = toolRows.size() - 1;
            if (i >= 0) {
                String nm = i < toolRowNames.size() ? toolRowNames.get(i) : "";
                String arg = i < toolRowArgs.size() ? toolRowArgs.get(i) : "";
                String ex = toolExplain(nm, arg, null, true);
                if (ex != null) {
                    int cut = ex.indexOf(" —— ");
                    String act = (cut > 0 ? ex.substring(0, cut) : ex).trim();
                    if (act.length() > 0) return act;
                }
            }
        } catch (Throwable ignored) {
        }
        return lastToolName();
    }

    private void updateRunBanner() {
        // 横幅已拆，但顶部状态行还靠这里报“正在干什么”，
        // 不能因为 runBanner 为空就把整段跳过（否则长回答滚动时顶栏不动了）
        if (toolRows.isEmpty()) return;
        int pending = pendingToolCount();
        if (pending == 0) return;           // 终态交给 finishRunBanner
        long oldest = 0;
        for (int i = 0; i < toolRowDone.size(); i++) {
            if (!toolRowDone.get(i)) {
                long st = toolRowStart.get(i);
                if (oldest == 0 || st < oldest) oldest = st;
            }
        }
        long ms = oldest == 0 ? 0 : SystemClock.uptimeMillis() - oldest;
        String meta = pending > 1 ? "+" + (pending - 1) : "";
        String act = bannerAct();
        showRunBanner(act, meta, dur(ms), Palette.c(C_RUN), true);
        startBannerAnim();
        // 横幅那套视图早已拆掉（runBanner 恒为 null），所以真正让人"看见还在跑"的
        // 只剩这条固定位置的运行条：环在转、秒数在跳、整条在呼吸
        runUiBegin(reqStartAt);
        // 顶部状态行报同一句话：长回答把气泡底部顶出屏幕时，抬头也能看到还在干什么。
        // 两处文案必须一致：否则一个报 read_file、一个报“读取文件”，像两个系统在报同一件事
        setStatusBusy(act + " · " + dur(ms)
                + (pending > 1 ? " · 另有 " + (pending - 1) + " 个" : ""));
        if (statusText != null) statusText.setTextColor(Palette.c(C_RUN));
        setStatusTint(Palette.c(C_RUN));
    }

    /** 整轮结束：给结论 + 定格总用时，停留几秒后淡出 */
    /** 运行条收尾：切完成/失败/中止态并停留几秒淡出，边缘光立刻熄灭 */
    private void concludeRunBar(int mode, String txt) {
        runUiConclude(mode, txt);
    }

    private void finishRunBanner(boolean ok) {
        if (runBanner == null) return;
        if (toolRows.isEmpty()) {
            if (runBannerWrap != null) runBannerWrap.setVisibility(View.GONE);
            else if (runBanner != null) runBanner.setVisibility(View.GONE);
            concludeRunBar(ok ? RUN_MODE_DONE
                    : RUN_MODE_ABORT, "");
            return;
        }
        int fail = 0, abort = 0;
        for (int i = 0; i < toolRowState.size(); i++) {
            Integer st = toolRowState.get(i);
            if (st == null) continue;
            if (st == ST_FAIL) fail++;
            else if (st == ST_ABORT) abort++;
        }
        long total = SystemClock.uptimeMillis() - reqStartAt;
        String name;
        int color;
        if (!ok || abort > 0) {
            color = Palette.c(C_ABORT);
            name = "已中止 · " + toolRows.size() + " 次调用";
        } else if (fail > 0) {
            color = Palette.c(C_FAIL);
            name = toolRows.size() + " 次调用 · " + fail + " 次失败";
        } else {
            color = Palette.c(C_OK);
            name = toolRows.size() + " 次调用 · 全部成功";
        }
        stopBannerAnim();
        showRunBanner(name, "", dur(total), color, false);
        int barMode = (!ok || abort > 0) ? RUN_MODE_ABORT
                : (fail > 0 ? RUN_MODE_FAIL
                : RUN_MODE_DONE);
        concludeRunBar(barMode, toolRows.size() + " 次调用");

        // 竖条「落定」：轻轻压一下再弹回。幅度比原来小一半，
        // 高端感来自“几乎看不出来的动作”，弹太用力就变成了活泼。
        // 整条横幅的 scale 弹跳已取消：不透明的胶囊才需要那一下，现在背景是空的，弹起来只会像抖了一下。
        if (runBannerRailBg != null) {
            runBannerRailBg.animate().cancel();
            runBannerRailBg.setScaleY(0.62f);
            runBannerRailBg.animate().scaleY(1f).setDuration(320)
                    .setInterpolator(new OvershootInterpolator(1.2f)).start();
        }
        final View b = runBannerWrap != null ? runBannerWrap : runBanner;
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (b.getVisibility() != View.VISIBLE) return;
                b.animate().alpha(0f).setDuration(360).withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        b.setVisibility(View.GONE);
                        b.setAlpha(1f);
                    }
                }).start();
            }
        }, 3200);
    }

    /** 每个工具行「上次刷新时的耗时秒数」：秒没变就不用重建文本，省掉大量无谓 invalidate */
    private final java.util.ArrayList<Long> toolRowShownSec = new java.util.ArrayList<Long>();

    private void startToolTicker() {
        if (toolTick != null) return;
        toolTick = new Runnable() {
            @Override
            public void run() {
                spinIdx++;
                long now = SystemClock.uptimeMillis();
                for (int i = 0; i < toolRowDone.size(); i++) {
                    if (toolRowDone.get(i)) continue;
                    long ms = now - toolRowStart.get(i);
                    long sec = ms / 1000L;
                    while (toolRowShownSec.size() <= i) toolRowShownSec.add(-1L);
                    // 同一秒内重复刷只是把一样的字再摆一遍，纯属白烧主线程
                    if (toolRowShownSec.get(i) == sec) continue;
                    toolRowShownSec.set(i, sec);
                    fillToolRow(toolRows.get(i), ST_RUN, toolRowNames.get(i),
                            toolRowArgs.get(i), null, ms);
                    // 每秒顶一条：窗口只有 3 行，新的进来旧的被挤走，
                    // 看着就是终端在往下刷——这是"还活着"最硬的证据
                    // 监控秒数现在跟在翻译行右端，跟工具行同一节拍
                    setExplainMsOnRow(toolRows.get(i), ms, ST_RUN);
                }
                updateToolHead();
                updateRunBanner();
                int pending = pendingToolCount();
                if (pending > 0) {
                    ui.postDelayed(this, 500);
                } else if (roundActive) {
                    // 工具全跑完、模型在想下一步——这段最容易让人以为"卡住了"。
                    // 状态行继续读秒；数字还在涨，就说明整轮还活着
                    long el = SystemClock.uptimeMillis() - reqStartAt;
                    if (statusText != null && el > 0) {
                        statusText.setText("正在思考 · " + dur(el));
                        statusText.setTextColor(Palette.c(C_RUN));
                    }
                    // 「工具跑完了但模型还在想/还在写」这段最像卡死：运行条切紫、
                    // 秒数继续跳，边缘光继续呼吸，用户就不会把这段当成结束了
                    runUiBegin(reqStartAt);
                    ui.postDelayed(this, 1000);
                } else {
                    toolTick = null;
                }
            }
        };
        ui.postDelayed(toolTick, 500);
    }

    private void stopToolTicker() {
        if (toolTick != null) {
            ui.removeCallbacks(toolTick);
            toolTick = null;
        }
    }

    /** 收尾时把没返回结果的工具行标成「已中止」，避免界面停在执行态 */
    private void abortPendingToolRows() {
        for (int i = 0; i < toolRowDone.size(); i++) {
            if (!toolRowDone.get(i)) {
                toolRowDone.set(i, Boolean.TRUE);
                if (i < toolRowState.size()) toolRowState.set(i, ST_ABORT);
                long ms = SystemClock.uptimeMillis() - toolRowStart.get(i);
                fillToolRow(toolRows.get(i), ST_ABORT, toolRowNames.get(i),
                        toolRowArgs.get(i), null, ms);
                // 不收这一下，那条“活翻译行”会一直扫光下去，
                // 说明还在跑，而工具行已经标了已中止——两行互相矛盾
                stopExplainShimmer(toolRows.get(i));
                blinkCaret(toolRows.get(i), false);
                setExplainMsOnRow(toolRows.get(i), ms <= 0 ? 1 : ms, ST_ABORT);
            }
        }
        updateToolHead();
    }

    /** 绿体系的小胶囊按钮：思考块、工具卡片这些「过程类」折叠按钮统一用它 */
    private TextView greenPill(String text) {
        TextView tv = Ui.pill(this, text);
        tv.setTextColor(Palette.c(0xFF7EE787));
        tv.setBackground(Ui.roundRect(this, Palette.c(0x1A3FB950), Palette.c(0x4D3FB950), 9f));
        return tv;
    }

    /**
     * 工具窗口高度：折叠时锁定 3 行；展开时按真实行数撑高，
     * 最高不超过屏幕的 55%，超出部分交给 ScrollView 自己滚。
     */
    private void applyToolHeight() {
        if (toolScroll == null) return;
        ViewGroup.LayoutParams raw = toolScroll.getLayoutParams();
        if (!(raw instanceof LinearLayout.LayoutParams)) return;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) raw;

        int lineH = dp(24);
        int chrome = dp(12);
        int minH = TOOL_WINDOW_LINES * lineH + chrome;
        int h = minH;
        if (!toolTraceCollapsed) {
            int lines = Math.max(TOOL_WINDOW_LINES, toolRows.size());
            int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.55f);
            h = Math.min(lines * lineH + chrome, Math.max(minH, maxH));
            h = Math.max(h, minH);
        }
        if (lp.height != h) {
            lp.height = h;
            toolScroll.setLayoutParams(lp);
        }
    }

    private void setToolCollapsed(boolean collapsed) {
        toolTraceCollapsed = collapsed;
        if (toolScroll != null) {
            toolScroll.setVisibility(collapsed ? View.GONE : View.VISIBLE);
            applyToolHeight();
        } else if (toolTraceList != null) {
            toolTraceList.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        }
        if (toolFoldBtn != null) {
            toolFoldBtn.setText(collapsed ? "\u25be 展开" : "\u25b4 收起");
        }
    }

    private static String briefText(String s) {
        if (s == null) return "";
        // 成功码「退出码 0」是纯噪声：每条 shell 结果都带着它，永远不变，
        // 却占掉摘要里本可以显示真实输出的位置。只吃 0 —— 失败码留着，那个才有信息。
        String t = s.replaceAll("(?m)^\\s*\u9000\u51fa\u7801 0\\s*$", " ");
        t = t.replace("\r", " ").replace("\n", " ").trim();
        while (t.contains("  ")) t = t.replace("  ", " ");
        return t.length() <= 160 ? t : t.substring(0, 160) + "…";
    }

    /**
     * 节流重渲染。
     * 这里是整段 Markdown 重建（解析 + 建 View + 代码高亮），文本越长越贵：
     * 几万字的回答如果还按 70ms 刷一次，主线程会被吃干，界面就「点了没反应」。
     * 所以长文本自动放宽间隔，并要求新增足够多的字符才值得重建一次。
     */
    private void scheduleRender(MarkdownRenderer renderer) {
        final int len = streamBuf == null ? 0 : streamBuf.length();
        final long now = SystemClock.uptimeMillis();
        // 手指还在滑：先不重排，等停下来再刷。有大批新字（>400）时照常刷，保证不丢内容
        if (now - lastScrollAt < 160 && len - lastRenderLen < 400) return;
        final boolean longText = len > 8000;
        final long interval = longText ? 240 : 70;
        final int minGrow = longText ? 180 : 0;

        if (now - lastRenderAt >= interval && len - lastRenderLen >= minGrow) {
            lastRenderAt = now;
            lastRenderLen = len;
            renderStream(renderer, false);
            return;
        }
        if (renderPending) return;
        renderPending = true;
        ui.postDelayed(() -> {
            renderPending = false;
            lastRenderAt = SystemClock.uptimeMillis();
            lastRenderLen = streamBuf == null ? 0 : streamBuf.length();
            renderStream(renderer, false);
        }, interval);
    }

    private void renderStream(MarkdownRenderer renderer, boolean placeholder) {
        if (streamContent == null || streamStale) return;
        String text = streamBuf == null ? "" : streamBuf.toString();
        // 让气泡右侧的「复制」在生成过程中也能拿到已输出的内容
        if (streamMessage != null) streamMessage.content = text;
        if (text.isEmpty()) {
            if (tlSeg != null) tlSeg.removeAllViews();
        } else {
            markThinkDone();
            maybeBuildTaskCard(text);
            renderTextSeg(text);
        }
        scrollToBottom(false);
    }

    /** 本轮时间线里是否真的跑过工具（决定要不要保留分段交错结构） */
    private boolean hasToolSteps() {
        if (tlSteps == null) return false;
        for (com.nebula.aichat.data.Message.Step st : tlSteps) {
            if (st != null && st.isTool()) return true;
        }
        return false;
    }

    /**
     * 退回「一整块」渲染：纯文字回答走这条，分段容器拆掉，
     * 让长文折叠、代码折叠、复制等特性维持在原来的整篇形态。
     */
    private void renderSingleBlock(MarkdownRenderer renderer, String text) {
        if (streamContent == null) return;
        streamContent.removeAllViews();
        closeToolGroup();
        tlSeg = null;
        tlSegR = null;
        tlSegStart = 0;
        renderer.setAutoCollapse(true);
        renderer.render(text);
    }

    /** 收尾时间线：把最后那段正文渲染完并登记，然后把整条时间线存进消息 */
    private void finalizeTimeline(String full) {
        if (streamContent == null) return;
        // 兜底：最后一段思考还没落盘的话，必须排在尾部正文之前
        takeThinkStep();
        thinkSegStart = 0;
        String tail = (full != null && full.length() > tlSegStart)
                ? stripListLines(ConclusionParser.visibleForStream(full.substring(tlSegStart))) : "";
        if (!tail.trim().isEmpty()) {
            if (tlSeg == null) beginTextSeg();
            if (tlSegR != null) {
                tlSegR.render(tail);
                // 收尾：代码块从流式展开收成预览，避免一整段代码撑满屏幕
                tlSegR.collapseCodeBlocks();
            }
            if (tlSteps != null) {
                com.nebula.aichat.data.Message.Step st = new com.nebula.aichat.data.Message.Step();
                st.type = com.nebula.aichat.data.Message.Step.TEXT;
                st.text = tail;
                tlSteps.add(st);
            }
            tlSegStart = full.length();
        } else if (tlSeg != null && tlSeg.getChildCount() == 0) {
            // 最后一步是工具调用，尾部没文字：空壳去掉，别留一条空白
            streamContent.removeView(tlSeg);
        }
        tlSeg = null;
        tlSegR = null;
        if (streamMessage != null) streamMessage.steps = tlSteps;
    }

    /** 出错时在时间线末尾补一行红字，前面的过程记录不动 */
    private void addErrorLine(String msg) {
        if (streamContent == null) return;
        TextView tv = new TextView(this);
        tv.setText(msg);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tv.setTextColor(Palette.c(C_FAIL));
        tv.setPadding(dp(2), dp(7), dp(2), dp(3));
        streamContent.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 断网/超时/服务端 5xx 之后最常见的动作就是「再试一次」，
        // 直接把入口放这儿，不用用户把问题重打一遍
        final String retryText = lastUserAsk;
        if (retryText != null && !retryText.trim().isEmpty()) {
            final TextView retry = new TextView(this);
            retry.setText("↻ 重试");
            retry.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            retry.setTextColor(Palette.c(0xFF7FD4A8));
            retry.setPadding(dp(2), dp(2), dp(2), dp(6));
            retry.setOnClickListener(v -> {
                if (busy || searching) {
                    Toast.makeText(this, "正在生成中，稍后再试", Toast.LENGTH_SHORT).show();
                    return;
                }
                retry.setEnabled(false);
                retry.setText("重发中…");
                sendAsUser(retryText);
            });
            streamContent.addView(retry, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    /**
     * 只渲染「当前这段」还没固化的正文。
     * 之所以分段：工具调用要能插到文字中间，而整段全文渲染会把插进去的行冲掉。
     */
    private void renderTextSeg(String full) {
        if (tlSeg == null) beginTextSeg();
        if (tlSeg == null) return;
        // 结论块（含正在输出的半个标记）先藏起来：正文只渲染它之前的部分，
        // 收尾时再统一解析成卡片，这样流式过程中不会闪出 "%%CONCLU…" 这种半截字符
        full = ConclusionParser.visibleForStream(full);
        if (full.length() <= tlSegStart) return;
        // 清单行已单独渲染成顶部的步骤卡，正文里不能再出现一份
        String part = stripListLines(full.substring(tlSegStart));
        if (part.trim().isEmpty()) {
            if (tlSeg.getChildCount() > 0) tlSegR.render(part);
            return;
        }
        tlSegR.render(part);
    }

    /** 起一段新正文：新容器 + 新渲染器，接在时间线末尾 */
    private void beginTextSeg() {
        if (streamContent == null) return;
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.VERTICAL);
        streamContent.addView(seg, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        tlSeg = seg;
        tlSegR = new MarkdownRenderer(this, seg, msgSp);
        tlSegR.setAutoCollapse(false);
        tlSegR.setOnConfirmClick(rows -> openConfirmSheet(rows));
        tlSegR.setOnConfirmSubmit(msg -> submitConfirmCard(msg));
        tlSegStart = streamBuf == null ? 0 : streamBuf.length();
    }

    /** 收尾当前正文段：内容已经渲染好，登记进时间线，接着让工具行排在它后面 */
    private void sealTextSeg() {
        if (streamContent == null || tlSeg == null) return;
        String full = streamBuf == null ? "" : streamBuf.toString();
        String part = full.length() > tlSegStart ? full.substring(tlSegStart) : "";
        // 这段要存进 steps 供历史回放，必须先把协议标记剥掉：
        // 模型在解释「你的提示词」时会把 %%CONCLUSION%% 当例子写进正文
        part = com.nebula.aichat.ui.ConclusionParser.stripAll(part);
        if (part.trim().isEmpty()) {
            // 这段没文字（模型上来就调工具，或上一行工具紧跟在文字后）：别留空壳
            streamContent.removeView(tlSeg);
        } else if (tlSteps != null) {
            com.nebula.aichat.data.Message.Step st = new com.nebula.aichat.data.Message.Step();
            st.type = com.nebula.aichat.data.Message.Step.TEXT;
            st.text = part;
            tlSteps.add(st);
            tlSegStart = full.length();
            // 文字夹在中间了，后面的工具行另起一组
            closeToolGroup();
            }
        tlSeg = null;
        tlSegR = null;
    }

    private void finishStream(boolean ok, String text, String error, MarkdownRenderer renderer) {
        // 已经收尾过（比如用户先点了停止，后台线程随后回调），直接忽略
        if (streamMessage == null) return;
        // 整轮思考内容随消息落地：下一轮请求要把 reasoning_content 原样带回去，
        // 少了这个字段，DeepSeek thinking 模式会直接 400（工具链跑到第二轮的典型死法）。
        if (thinkAll != null && thinkAll.length() > 0) {
            streamMessage.reasoning = thinkAll.toString();
        }
        // 思考耗时随消息落地：不存的话，重开会话时折叠头会从「用时 12.3 秒」
        // 变成一个没有秒数的「思考完成」，和实时看到的不一样。
        streamMessage.thinkMs = runThinkMs;
        // 任务结束：有常驻通知就退回空闲态，否则整个撤掉
        try {
            ui.removeCallbacks(keepAliveTick);
            if (settings != null && settings.aliveNotify) KeepAliveService.startIdle(this);
            else KeepAliveService.stop(this);
        } catch (Throwable ignored) {
        }
        busy = false;
        setSendState(false);
        // 先摘光标再定稿：晚了会跟着定稿文本一起被 setText 冲掉，留下一条静止的竖线
        try {
            renderer.setStreaming(false);
            StreamCursor.releaseAll();
        } catch (Throwable ignored) {
        }
        markThinkDone();
        // 请求结束时仍在「执行中」的行要明确标成中止，不能永远转下去
        abortPendingToolRows();
        markStatusFinal(ok);
        // 窗口高度恒定，一轮结束不必再收起——留着才能回看这轮到底跑了哪些命令
        if (toolTraceBox != null) updateToolHead();

        if (ok) {
            // 模型有时会漏掉清单块，这里兜底补上，保证「改了什么」一定能看到
            if (pendingChangeBlock != null && (text == null || !text.contains("%%CHANGES%%"))) {
                text = (text == null ? "" : text) + "\n\n" + pendingChangeBlock;
            }
            pendingChangeBlock = null;
            // 结论块剥离：正文保持干净，结论单独存两段，历史回放直接渲染卡片
            ConclusionParser.Result cr = ConclusionParser.parse(text);
            if (cr.hasConclusion()) {
                streamMessage.conclusionTitle = cr.title;
                streamMessage.conclusionDetail = cr.detail;
            }
            // 无论有没有拆出结论，标记本身都不许留在正文里
            text = cr.body;
            // 定稿前抹掉 `- [ ]` 的复选框：卡片已停用，正文按普通列表显示
            text = TaskParser.uncheck(text);
            boolean blank = (text == null || text.trim().isEmpty());
            streamMessage.content = blank ? "（没有返回内容，换个说法再试一次？）" : text;
            current.messages.add(streamMessage);
            // 回答里带「- [ ] 步骤」的，把清单抽出来单独做成可勾选卡片
            ensureTasks(streamMessage);
            final boolean hasTaskList = streamMessage.tasks != null && !streamMessage.tasks.isEmpty();
            if (blank) {
                renderSingleBlock(renderer, streamMessage.content);
            } else if (hasToolSteps()) {
                // 跑过工具：保留「文字 / 工具行」交错结构，只把尾部正文收进最后一段
                finalizeTimeline(text);
            } else {
                // 纯文字回答：合成一整块，长文折叠、代码折叠等特性照旧。
                // 有任务清单时正文里把清单行剥掉，避免和下方卡片重复一遍。
                renderSingleBlock(renderer, hasTaskList ? TaskParser.strip(text) : text);
            }
            // 顺序定死：正文 → 结论 → 产物 → （步骤卡在 index 0 顶部）
            addConclusionCard(streamContent, streamMessage, true);
            attachArtifacts(streamContent, streamMessage.content, true);
            if (hasTaskList) attachTasks(streamContent, streamMessage);
        } else {
            // 中断/失败这一轮同样要剥：模型常常先吐了 %%CONCLUSION%% 才断流
            String shown = ConclusionParser.stripAll(text);
            streamMessage.error = true;
            current.messages.add(streamMessage);
            if (hasToolSteps()) {
                // 已经跑过工具：过程记录要留着，错误单独排一行
                streamMessage.content = (shown.isEmpty() ? "" : shown + "\n\n") + "⚠️ " + error;
                finalizeTimeline(shown);
                addErrorLine("⚠️ " + error);
            } else {
                String full = (shown.isEmpty() ? "" : shown + "\n\n") + "⚠️ " + error;
                streamMessage.content = full;
                renderSingleBlock(renderer, full);
            }
        }
        if (ok && streamMessage != null) {
            addAiMetaLine(streamMessage.content);
            // 这轮到底有没有问用户、有没有调工具，直接落盘，出问题不用猜
            logAnswer(streamMessage.content);
            // 模型在等用户拍板：直接弹面板，不用用户自己找入口
            autoOpenConfirm(streamMessage.content);
            // 自动续命都用完了还是没做完，才给一个手动的「接着往下做」按钮
            maybeAddToolLimitHint();
            // 没按协议走、只用一句问句结尾的，给个一键继续
            maybeAddContinueHint(streamMessage.content);
        }
        current.touch();
        store.save();
        // 正常收尾，草稿已无意义（异常中断的那条会留在草稿里，等下次启动接回来）
        if (store != null) store.clearDraft();
        updateSubtitle();
        // 过程（工具行 / 翻译行）全部收进「思考完成 ▾」，气泡里只留答案
        collapseStreamProcess();
        scrollToBottom(true);
        streamContent = null;
        streamMessage = null;
        streamBuf = null;
        streamRenderer = null;
    }


    /** 记录本轮回答的关键特征（长度、有无确认块、结尾原话） */
    private void logAnswer(String text) {
        try {
            String t = text == null ? "" : text;
            String tail = t.length() > 180 ? t.substring(t.length() - 180) : t;
            com.nebula.aichat.util.CrashLog.d("answer", "len=" + t.length()
                    + " 工具=" + turnToolCalls
                    + " 确认块=" + ConfirmSheet.hasBlock(t)
                    + " 问句结尾=" + asksBack(t)
                    + " 结尾=" + tail.replace("\n", "⏎"));
        } catch (Throwable ignored) {
        }
    }

    /** 模型常用、但不带问号的邀请语结尾 */
    private static final String[] ASK_HINTS = {
            "你说一声", "说一声", "告诉我", "回我就", "需要我", "要不要", "可以开始",
            "你确认", "确认后", "准备好了", "就开始", "等你", "要我继续"
    };

    /** 结尾是否在等用户回话（问号或那几种「你说一声」式邀请） */
    private static boolean asksBack(String text) {
        if (text == null) return false;
        String t = text.trim();
        if (t.isEmpty()) return false;
        String tail = t.length() > 300 ? t.substring(t.length() - 300) : t;
        if (tail.contains("？") || tail.contains("?")) return true;
        for (String h : ASK_HINTS) {
            if (tail.contains(h)) return true;
        }
        return false;
    }

    /**
     * 模型惯用的「做一半就想交差」话术。
     * 光看有没有调工具不够：它经常「调一个工具 → 写一段进度 → 说句如需继续告诉我」就收尾，
     * 用户看到的就是「处理一半停那儿了」。
     */
    private static final String[] MID_STOP_HINTS = {
            "如需继续", "如需要", "需要我继续", "要我继续", "是否继续", "需要继续",
            "如果你希望", "如果需要我", "你可以继续", "接下来你可以", "下一步你可以",
            "建议你", "请告诉我", "告诉我后", "确认后我", "随时告诉我", "随时叫我",
            "下一步将", "接下来我准备", "等你回复", "等你确认"
    };

    /** 这条回复是不是「活儿没干完就收手」 */
    private static boolean looksIncomplete(String text) {
        if (text == null) return false;
        String t = text.trim();
        if (t.isEmpty()) return false;
        String tail = t.length() > 400 ? t.substring(t.length() - 400) : t;
        for (String w : MID_STOP_HINTS) {
            if (tail.contains(w)) return true;
        }
        return false;
    }

    /**
     * 模型不按确认块协议、只丢一句问句就结束时，给一个「继续执行」按钮。
     * 有它兜底，用户至少不用自己把答案再打一遍。
     */
    /**
     * 工具轮次跑到上限、被强制转成收尾总结时，回答下面挂一个「继续跑下一段」。
     * 这样长任务（解包 → 改包 → 重签）不会因为一次最多 40 轮就彻底卡死。
     */
    private void maybeAddToolLimitHint() {
        if (pendingToolLimit == null) return;
        final String why = pendingToolLimit;
        pendingToolLimit = null;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        TextView tip = Ui.text(this, "这活还没收尾，先让它写了段进度 ", 11.5f, Palette.c(0xFF8B97A9));
        box.addView(tip);
        TextView go = Ui.chip(this, "接着往下做 ↗", Palette.c(0x1A3FB950), Palette.c(0xFF7EE787),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        v.setEnabled(false);
                        ((TextView) v).setText("已继续");
                        awaitingExecution = true;
                        sendAsUser("继续，上一段没做完就停了（" + why + "）。"
                                + "接着刚才的进度往下做，别重复已经完成的步骤，"
                                + "也别再问我行不行；"
                                + "如果确实做不下去，直接说卡在哪、要我做什么决定。");
                    }
                });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = dp(6);
        box.addView(go, clp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        chatContainer.addView(box, lp);
        scrollToBottom(true);
    }

    /**
     * 判断一条回答是不是「一长条英文/代码」——中文字符占比极低且篇幅不小。
     * 这种回答用户根本看不懂，也没真正干活，必须让它用中文重说。
     */
    private static boolean isForeignBlob(String t) {
        int total = t.length();
        if (total < 500) return false;
        int cn = 0;
        for (int i = 0; i < total; i++) {
            char c = t.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) cn++;
        }
        return cn * 100 < total * 3;   // 中文占比不到 3%
    }

    private void maybeAddContinueHint(final String text) {
        if (text == null) return;
        if (ConfirmSheet.hasBlock(text)) return;
        String t = text.trim();
        if (t.isEmpty()) return;
        // 关键判据：这一轮到底调没调工具。
        // 调了通常是在干活，不打扰；但如果它一边调工具一边用「如需继续告诉我」收尾，
        // 那就是半途停机，照样得推一把。
        final boolean midStop = looksIncomplete(t);
        if (turnToolCalls > 0 && !midStop) return;

        // 几乎全英文/纯代码、又没真正动手：判定为无效回答，要求用中文重述
        if (turnToolCalls == 0 && isForeignBlob(t) && autoContinueCount < 3) {
            autoContinueCount++;
            com.nebula.aichat.util.CrashLog.d("auto", "回答几乎全英文 len=" + t.length() + "，要求中文重述");
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    awaitingExecution = true;
                    sendAsUser(REDO_CN_TEXT);
                }
            }, 600);
            return;
        }
        com.nebula.aichat.util.CrashLog.d("auto", "本轮零工具调用 待执行="
                + awaitingExecution + " 续跑次数=" + autoContinueCount
                + " 工具=" + turnToolCalls + " 半途收手=" + midStop
                + " 等回话=" + asksBack(t));

        // 该干却没干、或做一半就收手：先自动推一把（最多三次，用尽才交回用户）
        if ((awaitingExecution || midStop) && autoContinueCount < 3) {
            final String msg = (awaitingExecution && autoContinueCount == 0) ? CONTINUE_TEXT : CONTINUE_HARD;
            autoContinueCount++;
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    awaitingExecution = true;
                    sendAsUser(msg);
                }
            }, 700);
            return;
        }
        // 闲聊式反问不给按钮（那是在正常对话）；任务被卡住或半途收手才给
        if (!awaitingExecution && !midStop && !asksBack(t)) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        TextView tip = Ui.text(this, midStop ? "这一步没做完就停了 " : "它停下来等你回话了 ",
                11.5f, Palette.c(0xFF8B97A9));
        box.addView(tip);
        TextView go = Ui.chip(this, "继续执行 ↗", Palette.c(0x225B93FF), Palette.c(0xFF7EB0FF),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        awaitingExecution = true;
                        sendAsUser(CONTINUE_TEXT);
                    }
                });
        box.addView(go);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        chatContainer.addView(box, lp);
        scrollToBottom(true);
    }

    /**
     * 模型输出确认块时的处理。
     *
     * <p>以前这里会自动弹出可选面板；现在确认项（问题/选项/自定义/补充/按钮）
     * 全部摊在聊天区的卡片里，再弹一层弹窗既多一步操作又会挡住选项，
     * 所以只记录一下最新块，顺手把视图滚到卡片位置。</p>
     */
    private void autoOpenConfirm(String text) {
        if (!ConfirmSheet.hasBlock(text)) return;
        final List<String> rows = ConfirmSheet.extractRows(text);
        if (rows.isEmpty()) return;
        StringBuilder k = new StringBuilder();
        for (String r : rows) k.append(r).append('|');
        lastAutoConfirmKey = k.toString();
        scrollToBottom(true);
    }

    /** 弹出可勾选确认面板；确认后把选择结果自动发回模型继续 */
    private void openConfirmSheet(List<String> rows) {
        if (busy || searching) {
            Toast.makeText(this, "正在生成中，等这一轮结束再确认", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<ConfirmSheet.Item> items = ConfirmSheet.parseRows(rows);
        if (items.isEmpty()) return;
        ConfirmSheet.show(this, items, new ConfirmSheet.Callback() {
            @Override
            public void onConfirmed(String text) {
                awaitingExecution = true;
                sendAsUser(text);
            }

            @Override
            public void onStop() {
                endTurnByUser();
            }
        });
    }

    /**
     * 确认卡里点了「确认」：把整段选择直接发回模型。
     * 之所以先判 busy：流式还没停时把消息吞掉，用户的勾选就白费了，
     * 卡片会保持可点状态，等 AI 停下再点一次即可。
     */
    private boolean submitConfirmCard(String message) {
        if (message == null || message.trim().isEmpty()) return false;
        long now = System.currentTimeMillis();
        if (message.equals(lastConfirmMsg) && now - lastConfirmAt < 20000) {
            Toast.makeText(this, "这条确认已经发出去了，正在处理", Toast.LENGTH_SHORT).show();
            return true;
        }
        if (busy || searching) {
            Toast.makeText(this, "AI 还在生成，等它停下来再点确认", Toast.LENGTH_SHORT).show();
            return false;
        }
        // 正文里的确认卡拿不到结构化选项，只能看拼好的文本：
        // 里面写着「不用继续」就按收尾处理，别再发回模型
        if (ConfirmSheet.textMeansStop(message)) {
            endTurnByUser();
            return true;
        }
        lastConfirmMsg = message;
        lastConfirmAt = now;
        awaitingExecution = true;   // 与面板路径一致：这一轮是被用户拍板打断的
        sendAsUser(message);
        return true;
    }

    /**
     * 用户在确认框里选了「不用继续」：就地收尾。
     *
     * <p>不发消息给模型——发了它只会再解释一遍「好的那我就不继续了」，
     * 白跑一轮 token。同时把续跑计数顶到上限，本轮之后不会再被自动拉起。</p>
     */
    private void endTurnByUser() {
        awaitingExecution = false;
        autoContinueCount = 3;
        markStatusFinal(false);
        Toast.makeText(this, "已结束本轮", Toast.LENGTH_SHORT).show();
    }

    /** 把一段文本当作输入直接发出（确认结果回传用） */
    private void sendAsUser(String text) {
        if (text == null || text.trim().isEmpty()) return;
        if (busy || searching) {
            Toast.makeText(this, "正在生成中，稍后再发", Toast.LENGTH_SHORT).show();
            return;
        }
        viaAutoSend = true;
        etInput.setText(text);
        etInput.setSelection(etInput.getText().length());
        sendMessage();
    }

    /**
     * 在 AI 回答气泡下方追加一条元信息胶囊：模型、耗时、字数、联网状态。
     * 用户反馈“回答下面的信息要带气泡”，因此这里用圆角小胶囊而不是裸文字。
     */
    /**
     * 回答时间戳：常驻显示「日期 · 时分秒」。
     * 用三层样式做标记——日期暗、分隔点更暗、时分秒亮且等宽，
     * 不用特殊符号（缺字形会变方框），靠颜色和字号分层就够辨识。
     */
    private CharSequence stampLabel(long ms) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ms);
        String date = String.format(java.util.Locale.US, "%02d-%02d",
                c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH));
        String hms = String.format(java.util.Locale.US, "%02d:%02d:%02d",
                c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE),
                c.get(java.util.Calendar.SECOND));

        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        int a = sb.length();
        sb.append(date);
        sb.setSpan(new android.text.style.ForegroundColorSpan(Palette.c(0xFF59667C)), a, sb.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new android.text.style.RelativeSizeSpan(0.94f), a, sb.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        // 分隔标记：比日期更暗，只起分段作用，不抢视觉
        int b = sb.length();
        sb.append(" \u00b7 ");
        sb.setSpan(new android.text.style.ForegroundColorSpan(Palette.c(0xFF36414F)), b, sb.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        int d = sb.length();
        sb.append(hms);
        sb.setSpan(new android.text.style.ForegroundColorSpan(Palette.c(0xFF9DB4D2)), d, sb.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private void addAiMetaLine(String text) {
        // 横向排列：左边元信息胶囊，右边单独放本地时间（不塞进胶囊里）
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(12);
        row.setLayoutParams(rowLp);

        StringBuilder sb = new StringBuilder();
        sb.append("⚡ ").append(settings.model == null ? "AI" : settings.model);
        long cost = reqStartAt > 0 ? SystemClock.uptimeMillis() - reqStartAt : 0;
        if (cost > 0) {
            sb.append(" · ");
            if (cost < 10000) sb.append(String.format(java.util.Locale.US, "%.1f", cost / 1000.0)).append("s");
            else sb.append(cost / 1000).append("s");
        }
        int len = text == null ? 0 : text.trim().length();
        if (len > 0) sb.append(" · ").append(len).append(" 字");
        if (reqNet) sb.append(" · 🌐 联网");

        TextView tag = new TextView(this);
        tag.setText(sb.toString());
        tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tag.setTextColor(Palette.c(0xFFA8BCDA));
        tag.setBackground(Ui.roundRect(this, Palette.c(0xFF18202C), Palette.c(0xFF2E3A4C), 12f));
        int px = dp(10), py = dp(5);
        tag.setPadding(px, py, px, py);

        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = 0;   // 与上方 AI 正文左边缘对齐
        row.addView(tag, tlp);

        // 弹性空隙，把时间顶到最右，胶囊再长也不会压到时间
        View spacer = new View(this);
        row.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView clock = new TextView(this);
        clock.setText(stampLabel(System.currentTimeMillis()));
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        // 等宽：秒数变化时宽度不跳；颜色由 span 分层，这里只给个兜底色
        clock.setTypeface(Typeface.MONOSPACE);
        clock.setIncludeFontPadding(false);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = dp(10);
        // 不贴着屏幕右缘，往内收一截
        clp.rightMargin = dp(26);
        row.addView(clock, clp);

        chatContainer.addView(row);
    }

    // ===== 运行秒表：跑的时候贴在发送键正上方的一颗小胶囊 =====
    // 三条：不占布局（浮层）、不闪（只有数字在跳）、点一下能停
    private final Runnable runSecTick = new Runnable() {
        @Override
        public void run() {
            if (!runSecOn || runSec == null) return;
            long sec = Math.max(0, SystemClock.uptimeMillis() - runSecStart) / 1000L;
            runSec.setText("\u25CF " + sec + "s");
            placeRunSec();
            runSec.postDelayed(this, 250);
        }
    };

    private final Runnable runSecFade = new Runnable() {
        @Override
        public void run() {
            if (runSec != null) runSec.setVisibility(View.GONE);
        }
    };

    private void runUiBegin(long startAt) {
        if (runSec == null) return;
        if (!runSecOn) {
            runSecOn = true;
            runSecStart = startAt;
            runSec.setAlpha(1f);
            runSec.setTextColor(0xFF35E0FF);
            runSec.setText("\u25CF 0s");
            runSec.setVisibility(View.VISIBLE);
        }
        runSec.removeCallbacks(runSecFade);
        runSec.removeCallbacks(runSecTick);
        // 先排一次定位：刚 setVisibility(VISIBLE) 时还没测量，宽高都是 0，
        // 不等这一帧就会先闪在布局默认位置再跳过去
        runSec.post(this::placeRunSec);
        runSec.post(runSecTick);
    }

    private void runUiConclude(int mode, String txt) {
        if (runSec == null) return;
        long sec = Math.max(0, SystemClock.uptimeMillis() - runSecStart) / 1000L;
        runSecOn = false;
        runSec.removeCallbacks(runSecTick);
        if (mode == RUN_MODE_DONE) {
            runSec.setTextColor(0xFF3DDC84);
            runSec.setText("\u2713 " + sec + "s");
        } else if (mode == RUN_MODE_FAIL) {
            runSec.setTextColor(0xFFFF6B6B);
            runSec.setText("\u5931\u8d25 " + sec + "s");
        } else {
            runSec.setTextColor(0xFFFFA657);
            runSec.setText("\u5df2\u505c " + sec + "s");
        }
        runSec.setVisibility(View.VISIBLE);
        placeRunSec();
        runSec.removeCallbacks(runSecFade);
        runSec.postDelayed(runSecFade, 1600);
    }

    private void runUiHide() {
        runSecOn = false;
        if (runSec == null) return;
        runSec.removeCallbacks(runSecTick);
        runSec.removeCallbacks(runSecFade);
        runSec.setVisibility(View.GONE);
    }

    /**
     * 把秒表贴到发送键正上方。
     * 发送键会跟着输入框行数、键盘高度上下走，所以每 250ms 重算一次位置；
     * 写死一个 margin 的话，输入框一换行它就飘到别处去了。
     */
    private void placeRunSec() {
        if (runSec == null || btnSend == null) return;
        // 容器必须是 contentHost（整页），不能是消息区那个 FrameLayout：
        // 后者底边就在输入框上方，算出来的 y 会落到容器外、被直接裁掉
        android.view.View host = findViewById(R.id.contentHost);
        if (host == null) return;
        int w = runSec.getWidth();
        int h = runSec.getHeight();
        if (w <= 0 || h <= 0 || host.getWidth() <= 0) return;
        int[] hw = new int[2];
        int[] bw = new int[2];
        host.getLocationInWindow(hw);
        btnSend.getLocationInWindow(bw);
        float bx = bw[0] - hw[0];
        float by = bw[1] - hw[1];
        float d = getResources().getDisplayMetrics().density;
        // 贴在发送键左侧、与键同高。这块是输入框自己的草稿区、不是消息正文，
        // 所以既不压内容，又正好落在用户盯着输入区的那片视线里。
        // 键会随输入框行数和键盘高度上下走，所以每 250ms 跟着重算一次
        runSec.setX(bx - w - 8 * d);
        runSec.setY(by + (btnSend.getHeight() - h) / 2f);
    }

    private void stopStreaming() {
        if (!busy) return;
        // 1) 置取消标志：网络流、工具轮次循环都会看到
        client.cancel();
        // 2) 杀掉正在跑的 proot 进程（解包/重打包可能几十秒）
        try {
            com.nebula.aichat.linux.LinuxEnv.abortCurrent();
        } catch (Throwable ignored) {
        }
        // 3) 立刻收尾并解锁输入框，不等后台线程自己退出
        if (streamMessage != null && streamRenderer != null) {
            finishStream(false, streamBuf == null ? "" : streamBuf.toString(), "已停止", streamRenderer);
        }
        // 兜底：有些停止路径没有消息体可收尾，运行条会一直挂在输入框上方，
        // 那就成了"明明停了却显示在跑"——比不显示更糟
        concludeRunBar(RUN_MODE_ABORT, "已停止");
    }

    private void setSendState(boolean streaming) {
        btnSend.setImageResource(streaming ? R.drawable.ic_stop : R.drawable.ic_send);
        // 忙碌时发送键整颗转红：这是全屏最容易被余光扫到的一个点，
        // 比在输入框里塞胶囊/秒表更省地方，也不会和输入区抢位置
        btnSend.setBackgroundResource(streaming ? R.drawable.btn_round_run : R.drawable.btn_round);
        // 红底必须配白图标。停止图标原本是主题色画的，压在实心红上会糊成一团，
        // 反而看不出那是停止键；非运行态置 null 交还给主题原来的着色
        btnSend.setImageTintList(streaming
                ? android.content.res.ColorStateList.valueOf(0xFFFFFFFF) : null);
        btnSend.setPadding(dp(13), dp(13), dp(13), dp(13));
        etInput.setEnabled(!streaming);
    }

    private void regenerate() {
        if (busy) {
            Toast.makeText(this, "正在生成中…", Toast.LENGTH_SHORT).show();
            return;
        }
        // 去掉最后一条 assistant 消息
        int idx = -1;
        for (int i = current.messages.size() - 1; i >= 0; i--) {
            if (Message.ROLE_ASSISTANT.equals(current.messages.get(i).role)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) return;
        current.messages.remove(idx);
        renderConversation(current);
        reqNet = false;
        startRequest();
    }

    /**
     * 流式跟随：只有用户本来就贴在底部时才自动滚动。
     * 用户手动上滑阅读时保持原位，不再每 70ms 把他拽回底部（那是之前「抖动」的元凶之一）。
     */
    private void scrollToBottom(boolean force) {
        if (!force) {
            View child = scroll.getChildAt(0);
            if (child != null) {
                int gap = child.getHeight() - (scroll.getHeight() + scroll.getScrollY());
                if (gap > dp(96)) return;   // 已经离底部有距离，说明在看上面的内容
            }
        }
        // 合并成一次：流式每几十毫秒触发一次，不去重就会堆一串 fullScroll，
        // 每次都带一次全树测量布局，滑起来就是一顿一顿的
        if (scrollPending) return;
        scrollPending = true;
        scroll.post(() -> {
            scrollPending = false;
            View child = scroll.getChildAt(0);
            if (child == null) return;
            int target = Math.max(0, child.getHeight() - scroll.getHeight());
            if (Math.abs(scroll.getScrollY() - target) > dp(2)) scroll.scrollTo(0, target);
        });
    }

    private void showKeyboard() {
        try {
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(etInput, InputMethodManager.SHOW_IMPLICIT);
        } catch (Throwable ignored) {
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etInput.getWindowToken(), 0);
    }

    // ---------------- 会话管理 ----------------

    private void newConversation() {
        if (busy) {
            Toast.makeText(this, "正在生成中，先停止再新建", Toast.LENGTH_SHORT).show();
            return;
        }
        // 若当前会话本来就是空的，直接复用
        if (current != null && current.messages.isEmpty()) {
            renderConversation(current);
            return;
        }
        current = store.create();
        renderConversation(current);
    }

    // ==================== 顶栏：思考强度 ====================

    private TextView tvThink;

    private void setupThinkBadge() {
        tvThink = findViewById(R.id.tvThink);
        if (tvThink == null) return;
        tvThink.setOnClickListener(v -> showThinkSheet());
        renderThinkBadge();
    }

    private void renderThinkBadge() {
        if (tvThink == null) return;
        int level = settings.thinkLevel;
        boolean on = level > 0;
        String label = on ? Settings.thinkLabel(level) : "默认";
        tvThink.setText("🧠 " + label);
        tvThink.setTextColor(on ? Palette.c(0xFF9DC0FF) : Palette.c(0xFF8A97A8));
        tvThink.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        tvThink.setBackground(Ui.roundRect(MainActivity.this,
                on ? Palette.c(0xFF1E3A6B) : Palette.c(0xFF1E2530),
                on ? Palette.c(0xFF5B93FF) : Palette.c(0xFF2A3240), 15f));
    }

    // ---------------- 模型选择器（顶栏右侧图标） ----------------

    private Dialog modelSheet;
    private LinearLayout modelListBox;
    private final ModelListClient modelListClient = new ModelListClient();
    /** 最近一次服务端返回的模型名：添加/删除自定义模型后要就地重建列表，不能又拉一次 */
    private java.util.List<String> lastServerModels;
    private TextView sheetRefreshBtn;
    private boolean fetchingModels;

    /**
     * 模型面板用的弹窗主题（按当前配色选深色/纯黑/浅色三套之一）。
     * 走普通 floating 主题而非 NebDialogRect：
     * 关掉 windowIsFloating 后窗口的 gravity / attrs.x 全部失效，
     * 弹窗永远贴左边，居中不了。透明窗口下 floating 的 gravity 正常工作。
     */
    private int rectDialogTheme() {
        if (settings != null && settings.theme == Palette.THEME_BLACK) return R.style.NebDialogBlack;
        if (settings != null && settings.theme == Palette.THEME_LIGHT) return R.style.NebDialogLight;
        return R.style.NebDialog;
    }

    private void showModelSheet() {
        if (modelSheet != null && modelSheet.isShowing()) return;

        // 直角：关掉 windowIsFloating 拿回系统圆角（见 NebDialogRect 注释）
        final Dialog d = new Dialog(this, rectDialogTheme());
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.getWindow().setGravity(Gravity.CENTER);
        modelSheet = d;
        // 上一轮若在拉取途中关掉弹窗，标志位会挂着不放，导致再次打开时「刷新」永远提示在拉取
        fetchingModels = false;
        sheetRefreshBtn = null;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.roundRect(this, Palette.surface(this), Palette.stroke(this), 0f));
        root.setPadding(dp(18), dp(18), dp(18), dp(16));
        root.setClipChildren(false);
        root.setClipToPadding(false);   // 主按钮已改为内部渐变，留着这行防将来加阴影再被裁

        // ---------- 头部：当前模型的字母徽标 + 名字 + 厂商，右上角关闭 ----------
        FrameLayout headWrap = new FrameLayout(this);
        root.addView(headWrap, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        String cur = Settings.baseModel(settings);
        boolean hasCur = cur != null && !cur.trim().isEmpty();

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        headWrap.addView(head, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        TextView badge = modelBadge(hasCur ? cur : "?", 46);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(46), dp(46));
        head.addView(badge, blp);

        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = dp(13);
        nlp.rightMargin = dp(30);          // 给右上角 ✕ 让位
        head.addView(names, nlp);

        TextView name = new TextView(this);
        name.setText(hasCur ? cur.trim() : "未选择模型");
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        name.setTextColor(Palette.c(0xFFB4C2D4));
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(name);

        TextView vendor = new TextView(this);
        // 设置入口只留这一处：未配置时这行文字本身就是可点的链接。
        // 原来底部还有一条通栏「完整设置」主按钮，和侧滑栏里的「设置」重复，
        // 且渐变实心比「添加模型」还重，两个都像主按钮，视觉打架。
        boolean needSetup = !settings.isConfigured();
        vendor.setText(needSetup
                ? "还没配 API Key · 点这里去设置"
                : shortHost(settings.baseUrl) + " · 选中后对下一条提问生效");
        vendor.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        vendor.setTextColor(needSetup ? Palette.accent(this) : Palette.c(0xFF7F8C9F));
        vendor.setSingleLine(true);
        vendor.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        vlp.topMargin = dp(4);
        if (needSetup) {
            vendor.setPadding(0, dp(3), dp(8), dp(3));
            vendor.setOnClickListener(v -> {
                d.dismiss();
                startActivityForResult(new Intent(MainActivity.this, SettingsActivity.class), 100);
            });
        }
        names.addView(vendor, vlp);

        TextView closeX = new TextView(this);
        closeX.setText("\u2715");
        closeX.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        closeX.setTextColor(Palette.c(0xFF6F7C8F));
        closeX.setGravity(Gravity.CENTER);
        closeX.setBackground(Ui.roundRect(this, Palette.c(0x14FFFFFF), 0, 11f));
        closeX.setOnClickListener(v -> d.dismiss());
        FrameLayout.LayoutParams xlp = new FrameLayout.LayoutParams(dp(28), dp(28));
        xlp.gravity = Gravity.TOP | Gravity.END;
        headWrap.addView(closeX, xlp);

        // ---------- 一条从蓝到透明的光条，把头部和列表分开 ----------
        View line = new View(this);
        android.graphics.drawable.GradientDrawable gd =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[]{Palette.c(0x4D5B93FF), Palette.c(0x142F6BE8), 0x00000000});
        gd.setCornerRadius(dp(1f));
        gd.setGradientType(android.graphics.drawable.GradientDrawable.LINEAR_GRADIENT);
        line.setBackground(gd);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1.5f));
        llp.topMargin = dp(15);
        root.addView(line, llp);

        // ---------- 小节标题：全部模型 · 右侧刷新 ----------
        LinearLayout sec = new LinearLayout(this);
        sec.setOrientation(LinearLayout.HORIZONTAL);
        sec.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams secLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        secLp.topMargin = dp(14);
        root.addView(sec, secLp);

        TextView secTitle = new TextView(this);
        secTitle.setText("全部模型");
        secTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        secTitle.setTextColor(Palette.c(0xFF6F7C8F));
        secTitle.setTypeface(Typeface.DEFAULT_BOLD);
        sec.addView(secTitle, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView refresh = new TextView(this);
        refresh.setText("\u21bb 刷新");
        refresh.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        refresh.setTextColor(Palette.c(0xFF7FB0FF));
        refresh.setGravity(Gravity.CENTER);
        // 之前 padding 只有 10/4/2/4dp，可点区域太窄，手指常常点空 → 补到 40dp 级
        refresh.setPadding(dp(14), dp(8), dp(8), dp(8));
        refresh.setMinHeight(dp(38));
        refresh.setMinWidth(dp(58));
        refresh.setOnClickListener(v -> fetchModelsForSheet());
        sheetRefreshBtn = refresh;
        sec.addView(refresh);

        // ---------- 列表 ----------
        // 限一个最大高度即可：内容少时弹窗跟着内容收紧，内容多时在弹窗内部滚动。
        // 之前这里是 weight=1，弹窗又固定 86% 屏高，模型只有六个时下方会撑出一大片空白。
        final int listMaxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.62f);
        // 再给一个下限：纯为上下留白匀称，内容偏少时把弹窗往下舒展一点，避免贴得太紧
        final int listMinH = (int) (getResources().getDisplayMetrics().heightPixels * 0.53f);
        ScrollView sv = new ScrollView(this) {
            @Override
            protected void onMeasure(int wSpec, int hSpec) {
                super.onMeasure(wSpec,
                        MeasureSpec.makeMeasureSpec(listMaxH, MeasureSpec.AT_MOST));
                if (getMeasuredHeight() < listMinH) {
                    setMeasuredDimension(getMeasuredWidth(), listMinH);
                }
            }
        };
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        modelListBox = new LinearLayout(this);
        modelListBox.setOrientation(LinearLayout.VERTICAL);
        // 列表和底部按钮包在同一层里，按钮跟着列表一起滚，不做固定底栏
        LinearLayout listWrap = new LinearLayout(this);
        listWrap.setOrientation(LinearLayout.VERTICAL);
        listWrap.addView(modelListBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        sv.addView(listWrap, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        svlp.topMargin = dp(10);
        root.addView(sv, svlp);

        // 列表底部留一点呼吸，别贴着弹窗下沿（不要太多，否则底部空一截）
        listWrap.setPadding(0, 0, 0, dp(14));

        rebuildModelList(composeModelList(lastServerModels), Settings.baseModel(settings),
                settings.isConfigured() ? "正在拉取服务器模型列表…" : null);

        if (settings.isConfigured()) fetchModelsForSheet();

        // 非 floating 窗口既不认 gravity 也不认 attrs.x（两者都会被忽略），
        // 所以窗口层怎么设都居中不了。只能在内容层做：
        // 包一层 FrameLayout，用 layout_gravity 让内容自己在窗口里居中。
        int wpx2 = getResources().getDisplayMetrics().widthPixels;
        int dw2 = (int) (wpx2 * 0.9f);
        d.setContentView(root);
        d.show();
        Window w = d.getWindow();
        if (w != null) {
            // 窗口透明，露出来的就是 root 自己那块直角卡片；
            // floating 主题下 gravity 生效，居中交给窗口做
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            w.setGravity(Gravity.CENTER);
            w.setLayout(dw2, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            WindowManager.LayoutParams attrs = w.getAttributes();
            attrs.y = dp(39);
            w.setAttributes(attrs);
        }
    }

    /** 模型列表行高：列表可视高度、滚动量都按它算，改这一处即可 */
    private static final int MODEL_ROW_H = 58;

    /** 徽标配色池：按模型名 hash 取，同一个模型每次打开都是同一个颜色 */
    private static final int[] MODEL_ACCENTS = {
            Palette.c(0xFF5B93FF), Palette.c(0xFF4CD7E8), Palette.c(0xFF7C6BFF), Palette.c(0xFF3FBF8F),
            Palette.c(0xFFE0A642), Palette.c(0xFFE06C8B), Palette.c(0xFF3FB6E0), Palette.c(0xFF9B7BFF) };

    private int accentOf(String model) {
        if (model == null || model.isEmpty()) return MODEL_ACCENTS[0];
        return MODEL_ACCENTS[Math.abs(model.hashCode()) % MODEL_ACCENTS.length];
    }

    /** 圆形（或大圆角）字母徽标：取模型名里第一个字母/数字，配一个固定颜色 */
    private TextView modelBadge(String model, int sizeDp) {
        String src = model == null ? "" : model.trim();
        String ch = "?";
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                ch = String.valueOf(Character.toUpperCase(c));
                break;
            }
        }
        int a = accentOf(src);
        int rgb = a & 0x00FFFFFF;

        TextView tv = new TextView(this);
        tv.setText(ch);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeDp >= 40 ? 18f : 13.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(a);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(Ui.box(this, rgb | Palette.c(0x26000000), rgb | Palette.c(0x59000000),
                sizeDp / 2f, 1f));
        return tv;
    }

    /** 模型归属只按接口地址判断——没有内置模型表可比对了 */
    private String providerOf(String model) {
        if (model == null || model.trim().isEmpty()) return "未选";
        return currentProviderName();
    }

    /**
     * 当前地址对应的服务商显示名。
     * 不能用 providerIndexOfCurrent()——它匹配不到时返回 0(DeepSeek)，
     * 自定义地址会被错标成 DeepSeek。这里匹配不到就退回域名，比「自定义」准确。
     */
    private String currentProviderName() {
        String cur = normBase(settings.baseUrl);
        if (cur.isEmpty()) cur = normBase(Settings.DEFAULT_BASE_URL);
        for (String[] p : MODEL_PROVIDERS) {
            String b = normBase(p[1]);
            if (!b.isEmpty() && (cur.equals(b) || cur.startsWith(b))) return p[0];
        }
        String host = shortHost(cur);
        return (host == null || host.isEmpty()) ? "当前地址" : host;
    }

    /** 弹窗底部主按钮：渐变底 + 一层外发光，和次级按钮不是一个量级 */
    private TextView sheetPrimary(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(Palette.c(0xFFC6D8FA));
        tv.setLetterSpacing(0.02f);
        tv.setBackground(sheetGhostButton());
        tv.setClickable(true);
        tv.setFocusable(true);
        // 按压时轻微内缩，代替被裁掉的外发光，手感靠动效找回来
        tv.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (a == android.view.MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.985f).scaleY(0.985f).setDuration(90).start();
            } else if (a == android.view.MotionEvent.ACTION_UP
                    || a == android.view.MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(130).start();
            }
            return false;
        });
        return tv;
    }

    /** 弹窗底部按钮：淡蓝染底 + 细描边 + 柔光文字。整块饱和蓝在深色弹窗里太抢眼，压一档 */
    private Drawable sheetGhostButton() {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(14));
        bg.setColor(Palette.c(0x1F4A86F7));
        bg.setStroke(Math.max(1, dp(1)), Palette.c(0x6B4A86F7));
        return new RippleDrawable(ColorStateList.valueOf(Palette.c(0x3D4A86F7)), bg, null);
    }

    /** 底部操作区上方的分隔线：中段微亮、两端透明，比通栏实线轻，不会割裂弹窗 */
    private View sheetSeparator() {
        View v = new View(this);
        android.graphics.drawable.GradientDrawable gd =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[]{0x00FFFFFF, Palette.c(0x3DFFFFFF), 0x00FFFFFF});
        v.setBackground(gd);
        return v;
    }

    private void fetchModelsForSheet() {
        // 之前这里直接 return，没配 Key 时点「刷新」毫无反应，被当成按钮坏了
        if (!settings.isConfigured()) {
            Toast.makeText(this, "还没填 API Key · 点顶部那行提示去设置里配好",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (fetchingModels) {
            Toast.makeText(this, "正在拉取，稍等一下", Toast.LENGTH_SHORT).show();
            return;
        }
        fetchingModels = true;
        final TextView btn = sheetRefreshBtn;
        if (btn != null) {
            btn.setText("拉取中…");
            btn.setEnabled(false);
            btn.setAlpha(0.45f);
        }
        modelListClient.fetch(settings, new ModelListClient.Callback() {
            @Override
            public void onSuccess(java.util.List<String> models) {
                runOnUiThread(() -> {
                    resetRefreshState();
                    lastServerModels = models;
                    int n = models == null ? 0 : models.size();
                    // 记下这批模型：思考强度只对「从账号拉回来的模型」下发
                    settings.pulledModels = new java.util.ArrayList<>();
                    if (models != null) settings.pulledModels.addAll(models);
                    settings.save(MainActivity.this);
                    // 偏好「拉取模型后自动移除无效模型」：本地留着、远端已经没有的条目，
                    // 选中必然报 "model does not exist"，趁这次拉取一并清掉
                    int pruned = 0;
                    if (settings.pruneInvalidModels && n > 0) {
                        pruned = settings.pruneAgainstRemote(models);
                        if (pruned > 0) settings.save(MainActivity.this);
                    }
                    rebuildModelList(composeModelList(models), Settings.baseModel(settings),
                            n == 0 ? "服务器没返回可用模型 · 检查 Key 权限或换个地址" : null);
                    String done = n == 0 ? "服务器没返回模型" : "已更新 " + n + " 个模型";
                    if (pruned > 0) done = done + "，清理 " + pruned + " 个失效条目";
                    Toast.makeText(MainActivity.this, done, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    resetRefreshState();
                    rebuildModelList(composeModelList(lastServerModels),
                            Settings.baseModel(settings), "拉取失败 · " + message);
                    Toast.makeText(MainActivity.this, "拉取失败：" + message,
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /** 拉取结束（无论成败）把「刷新」恢复成可点状态 */
    private void resetRefreshState() {
        fetchingModels = false;
        TextView btn = sheetRefreshBtn;
        if (btn != null) {
            btn.setText("\u21bb 刷新");
            btn.setEnabled(true);
            btn.setAlpha(1f);
        }
    }

    /**
     * 模型候选的合成顺序：当前 → 自定义 → 服务器 → 内置。
     * 用户自己加的最靠前，刚添加完就能在列表顶部看到它被选中，不用滚。
     */
    private java.util.List<String> composeModelList(java.util.List<String> server) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        String cur = Settings.baseModel(settings);
        if (cur != null && !cur.trim().isEmpty()) set.add(cur.trim());
        if (settings.customModels != null) {
            for (String m : settings.customModels) {
                String t = m == null ? "" : m.trim();
                if (!t.isEmpty()) set.add(t);
            }
        }
        if (server != null) {
            for (String m : server) {
                String t = m == null ? "" : m.trim();
                if (!t.isEmpty()) set.add(t);
            }
        }
        // 不塞任何内置模型名：列表里出现账号没有的模型，选中就是 400 model not exist。
        // 没拉到就显示空态提示，让用户去拉。
        // 隐藏名单最后统一过滤。当前选中的模型永远保留，
        // 否则界面上会出现「使用中的模型不在列表里」，想切都切不回来。
        java.util.List<String> out = new java.util.ArrayList<>(set.size());
        for (String m : set) {
            if (!m.equals(cur) && settings.hiddenModels != null
                    && settings.hiddenModels.contains(m)) {
                continue;
            }
            out.add(m);
        }
        return out;
    }

    private void rebuildModelList(java.util.List<String> models, String current, String note) {
        if (modelListBox == null) return;
        modelListBox.removeAllViews();
        boolean hasNote = note != null && !note.isEmpty();
        if (hasNote) {
            TextView tip = new TextView(this);
            tip.setText(note);
            tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            tip.setTextColor(Palette.c(0xFF7F8C9F));
            tip.setPadding(dp(2), dp(0), dp(2), dp(8));
            modelListBox.addView(tip);
        }
        if (models.isEmpty() && !hasNote) {
            TextView none = new TextView(this);
            none.setText("列表是空的 · 检查 Key 和地址后点「刷新」重新拉取");
            none.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            none.setTextColor(Palette.c(0xFF7F8C9F));
            none.setPadding(dp(4), dp(6), dp(4), dp(12));
            modelListBox.addView(none);
        }
        for (String m : models) addModelRow(m, m != null && m.equals(current));
        addCustomModelEntryRow();
        addUnhideEntryRow();
        View sv = (View) modelListBox.getParent();
        if (sv != null) {
            // 高度交给内容自适应：ScrollView 的 onMeasure 已经封了最大高度，内容再多也是它自己滚。
            // 原来这里按「min(行数+1, 4) 行」硬算高度，行数少时反而把列表撑出空白。
            android.view.ViewGroup.LayoutParams lp = sv.getLayoutParams();
            lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
            sv.setLayoutParams(lp);
        }
    }

    private void addModelRow(final String name, boolean current) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), 0, dp(12), 0);

        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_pressed},
                Ui.box(this, current ? Palette.c(0xFF22355A) : Palette.c(0x1F5B93FF), 0, 14f, 0f));
        // 选中行用「深蓝底 + 细蓝框」而不是高饱和实底：整屏只有一处发光（底部主按钮）
        bg.addState(new int[]{}, current
                ? Ui.box(this, Palette.c(0xFF1B2A47), Palette.c(0x665B93FF), 14f, 1f)
                : Ui.box(this, 0x00000000, 0, 14f, 0f));
        row.setBackground(bg);
        row.setOnClickListener(v -> pickModel(name));

        final boolean custom = settings.customModels != null && name != null
                && settings.customModels.contains(name);

        row.addView(modelBadge(name, 34),
                new LinearLayout.LayoutParams(dp(34), dp(34)));

        // 名称 + 一行小字（厂商 / 自定义），两行结构比单行更耐看，也塞下了更多信息
        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tclp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tclp.leftMargin = dp(12);
        row.addView(textCol, tclp);

        TextView tv = new TextView(this);
        tv.setText(name == null ? "" : name);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setMaxLines(1);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setTextColor(current ? Palette.c(0xFFDCE8FF) : Palette.c(0xFFC9D6E8));
        textCol.addView(tv);

        TextView sub = new TextView(this);
        // 来源三级判定：内置表 → 本次服务器返回 → 当前地址所属服务商。
        // 之前只查内置表，服务器拉回来的模型（如 deepseek-flash）一律落到「自定义」，
        // 行里显示「自己添加 · 长按删除」——其实是服务商自带模型，会误导用户去删。
        String prov = providerOf(name);
        if ("自定义".equals(prov)) {
            boolean fromServer = name != null && lastServerModels != null
                    && lastServerModels.contains(name);
            prov = fromServer ? currentProviderName() : "自定义";
        }
        sub.setText("自定义".equals(prov) ? "自己添加 · 长按删除" : prov);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        // 颜色跟标签走：只有真的「自己添加」才用蓝色提示，服务商自带模型保持灰
        sub.setTextColor("自定义".equals(prov) ? Palette.c(0xFF7FB0FF) : Palette.c(0xFF6F7C8F));
        sub.setSingleLine(true);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(3);
        textCol.addView(sub, slp);

        if (current) {
            TextView ok = new TextView(this);
            ok.setText("\u2713 使用中");
            ok.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            ok.setTextColor(Palette.c(0xFF8FB6FF));
            ok.setPadding(dp(9), dp(3), dp(9), dp(3));
            ok.setBackground(Ui.roundRect(this, Palette.c(0x265B93FF), Palette.c(0x4D5B93FF), 8f));
            LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            olp.leftMargin = dp(6);
            row.addView(ok, olp);
        }

        // 任何来源的模型都能长按隐藏：服务商全量返回的旧型号删不掉，只能遮蔽
        row.setOnLongClickListener(v -> {
            showModelActions(name, custom);
            return true;
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(MODEL_ROW_H));
        rlp.bottomMargin = dp(4);
        modelListBox.addView(row, rlp);
    }

    /** 列表末尾那行「＋ 添加自定义模型」：点了弹输入框 */
    private void addCustomModelEntryRow() {
        if (modelListBox == null) return;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), 0, dp(12), 0);
        android.graphics.drawable.StateListDrawable bg =
                new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_pressed},
                Ui.box(this, Palette.c(0x265B93FF), 0, 14f, 0f));
        bg.addState(new int[]{}, Ui.box(this, Palette.c(0x085B93FF), Palette.c(0x1F5B93FF), 14f, 1f));
        row.setBackground(bg);

        // 和模型行同宽同高的「＋」方块，保证列表左边缘是一条直线
        TextView plus = new TextView(this);
        plus.setText("\uff0b");
        plus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        plus.setTextColor(Palette.c(0xFF7FB0FF));
        plus.setGravity(Gravity.CENTER);
        plus.setBackground(Ui.roundRect(this, Palette.c(0x1F5B93FF), 0, 11f));
        row.addView(plus, new LinearLayout.LayoutParams(dp(34), dp(34)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = dp(12);
        row.addView(col, clp);

        TextView tv = new TextView(this);
        tv.setText("添加模型");
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tv.setTextColor(Palette.c(0xFF9CC2FF));
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(tv);

        TextView hint = new TextView(this);
        hint.setText("内置 6 家服务商 · 地址自动配好");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        hint.setTextColor(Palette.c(0xFF6F7C8F));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(3);
        col.addView(hint, hlp);

        row.setOnClickListener(v -> showAddModelDialog());
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(MODEL_ROW_H));
        addLp.topMargin = dp(8);
        modelListBox.addView(row, addLp);
    }

    /**
     * 服务商只提供「名字 + 接口地址」，一个模型名都不写死：
     * 各家 /models 返回的名字经常变（gpt-4o → gpt-4.1、qwen-max → qwen3-max…），
     * 写死的名字过期后选中就是 model not exist。模型一律现拉现选。
     */
    private static final String[][] MODEL_PROVIDERS = {
            {"DeepSeek", "https://api.deepseek.com/v1"},
            {"通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1"},
            {"Kimi", "https://api.moonshot.cn/v1"},
            {"智谱 GLM", "https://open.bigmodel.cn/api/paas/v4"},
            {"OpenAI", "https://api.openai.com/v1"},
            {"硅基流动", "https://api.siliconflow.cn/v1"},
    };

    /** 去掉首尾空白与尾部斜杠，只用来判断两个地址是不是同一家 */
    private static String normBase(String b) {
        String t = b == null ? "" : b.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }

    /** 当前设置落在哪个内置服务商上，匹配不到就用第一个 */
    private int providerIndexOfCurrent() {
        String cur = normBase(settings.baseUrl);
        if (cur.isEmpty()) cur = normBase(Settings.DEFAULT_BASE_URL);
        for (int i = 0; i < MODEL_PROVIDERS.length; i++) {
            String b = normBase(MODEL_PROVIDERS[i][1]);
            if (!b.isEmpty() && (cur.equals(b) || cur.startsWith(b))) return i;
        }
        return 0;
    }

    private String shortHost(String u) {
        String t = normBase(u).replace("https://", "").replace("http://", "");
        int s = t.indexOf('/');
        return s > 0 ? t.substring(0, s) : t;
    }

    /** 单选列表，点一下就回调并关掉 */
    private void pickOne(String title, String[] items, int checked,
                         android.content.DialogInterface.OnClickListener l) {
        if (items == null || items.length == 0) return;
        new NebAlert.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(items, Math.max(0, checked), l)
                .setNegativeButton("取消", null)
                .show();
    }

    /** 一行「标签   值 ▾」，整行可点 */
    private TextView valueRow(String label, String value) {
        TextView tv = new TextView(this);
        tv.setText(label + "    " + value + "  \u25be");
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tv.setTextColor(Palette.c(0xFF9CC2FF));
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setPadding(dp(12), dp(12), dp(12), dp(12));
        tv.setBackground(Ui.pressable(this, Ui.roundRect(this, Palette.c(0xFF0F151D), Palette.c(0xFF2E3A49), 10f)));
        return tv;
    }

    /**
     * 添加模型：选服务商 → 选模型，接口地址自动配好，Key 填一次以后复用。
     *
     * <p>以前这里只让手打模型名，地址和 Key 还得自己进设置页配，
     * 结果加了个名字却用不了。现在三件事在同一个框里一次做完。</p>
     */
    /** 小节标题：比正文小一号、加粗、弱色，用来分段而不抢眼 */
    private TextView sheetLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        tv.setTextColor(Palette.c(0xFF8FA0B5));
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setLetterSpacing(0.06f);
        return tv;
    }

    private LinearLayout.LayoutParams labelLp(boolean first) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(first ? 0 : 18);
        lp.bottomMargin = dp(8);
        return lp;
    }

    /** 弹窗里的输入框：深底 + 细边，比系统默认下划线输入框干净 */
    private EditText sheetInput(String hint) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        et.setTextColor(Palette.c(0xFFE6EDF7));
        et.setHintTextColor(Palette.c(0xFF5E6A7B));
        et.setTypeface(Typeface.MONOSPACE);
        et.setSingleLine(true);
        et.setBackground(Ui.box(this, Palette.c(0xFF0F151D), Palette.c(0xFF2B3644), 12f, 1f));
        et.setPadding(dp(13), dp(11), dp(13), dp(11));
        return et;
    }

    /** 服务商卡片：只画底和两行字，选中态由 styleProviderCard 统一改 */
    private LinearLayout providerCard(int idx) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(11), dp(9), dp(11), dp(9));

        TextView n = new TextView(this);
        n.setText(MODEL_PROVIDERS[idx][0]);
        n.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        n.setTypeface(Typeface.DEFAULT_BOLD);
        n.setSingleLine(true);
        c.addView(n);

        TextView h = new TextView(this);
        h.setText(shortHost(MODEL_PROVIDERS[idx][1]));
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        h.setSingleLine(true);
        h.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(4);
        c.addView(h, hlp);
        return c;
    }

    private void styleProviderCard(LinearLayout c, boolean on) {
        c.setBackground(on
                ? Ui.box(this, Palette.c(0xFF1B2A47), Palette.c(0x665B93FF), 13f, 1.2f)
                : Ui.box(this, Palette.c(0xFF10161F), Palette.c(0x1F2A3240), 13f, 1f));
        TextView n = (TextView) c.getChildAt(0);
        n.setTextColor(on ? Palette.c(0xFFDCE8FF) : Palette.c(0xFFB9C6D8));
        TextView h = (TextView) c.getChildAt(1);
        h.setTextColor(on ? Palette.c(0xFF8FB6FF) : Palette.c(0xFF6F7C8F));
    }

    private void showAddModelDialog() {
        final Dialog d = new Dialog(this, rectDialogTheme());
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.getWindow().setGravity(Gravity.CENTER);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.roundRect(this, Palette.surface(this), Palette.stroke(this), 0f));
        root.setPadding(dp(18), dp(18), dp(18), dp(16));
        root.setClipChildren(false);
        root.setClipToPadding(false);   // 主按钮已改为内部渐变，留着这行防将来加阴影再被裁

        // ---------- 头部 ----------
        FrameLayout headWrap = new FrameLayout(this);
        root.addView(headWrap, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams hlp0 = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        hlp0.rightMargin = dp(34);
        headWrap.addView(head, hlp0);

        TextView title = new TextView(this);
        title.setText("添加模型");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        title.setTextColor(Palette.c(0xFFEDF3FC));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title);

        TextView sub = new TextView(this);
        sub.setText("选服务商 · 选模型 · 填 Key，接口地址自动配好");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        sub.setTextColor(Palette.c(0xFF7F8C9F));
        LinearLayout.LayoutParams slp0 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp0.topMargin = dp(5);
        head.addView(sub, slp0);

        TextView closeX = new TextView(this);
        closeX.setText("\u2715");
        closeX.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        closeX.setTextColor(Palette.c(0xFF6F7C8F));
        closeX.setGravity(Gravity.CENTER);
        closeX.setBackground(Ui.roundRect(this, Palette.c(0x14FFFFFF), 0, 11f));
        closeX.setOnClickListener(v -> d.dismiss());
        FrameLayout.LayoutParams xlp = new FrameLayout.LayoutParams(dp(28), dp(28));
        xlp.gravity = Gravity.TOP | Gravity.END;
        headWrap.addView(closeX, xlp);

        // ---------- 光条 ----------
        View line = new View(this);
        android.graphics.drawable.GradientDrawable gd =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[]{Palette.c(0x805B93FF), Palette.c(0x332F6BE8), 0x00000000});
        gd.setCornerRadius(dp(1f));
        gd.setGradientType(android.graphics.drawable.GradientDrawable.LINEAR_GRADIENT);
        line.setBackground(gd);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1.5f));
        llp.topMargin = dp(15);
        root.addView(line, llp);

        // ---------- 内容区（可滚动，避免小屏把底部按钮挤出去） ----------
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(14), 0, 0);
        sv.addView(body, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(sv, svLp);

        final int[] sel = new int[]{providerIndexOfCurrent()};
        final String[] mdl = new String[]{""};
        /** 点「从账号拉取」后才有内容——模型不再有内置候选 */
        final java.util.List<String> fetched = new java.util.ArrayList<>();
        final ModelListClient pickClient = new ModelListClient();
        /** 卡片 / chips 共用同一个重绘入口，先声明，拉取按钮的回调要用 */
        final Runnable[] refresh = new Runnable[1];
        final LinearLayout[] cardViews = new LinearLayout[MODEL_PROVIDERS.length];
        final LinearLayout chipBox = new LinearLayout(this);

        // 这三个控件先创建、稍后再按顺序 addView：
        // 卡片点击和 Key 提示都要先引用到它们，而界面顺序要求它们排在卡片后面
        final EditText etManual = sheetInput("可留空，留空就用上面选中的");
        final EditText etKey = sheetInput("sk-...");
        etKey.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etKey.setPadding(dp(13), dp(11), dp(56), dp(11));
        final TextView keyHint = new TextView(this);
        keyHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        keyHint.setLineSpacing(dp(2), 1.25f);

        // 1) 服务商：3 列网格。原来 2 列要占 4 行，7 家直接把弹窗顶到屏幕边；
        //    改 3 列压成 3 行，一屏就能看全，域名仍以 9.5sp 小字挂在名字下面。
        body.addView(sheetLabel("服务商"), labelLp(true));
        for (int base = 0; base < MODEL_PROVIDERS.length; base += 3) {
            LinearLayout rowLine = new LinearLayout(this);
            rowLine.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rllp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (base > 0) rllp.topMargin = dp(8);
            for (int k = 0; k < 3; k++) {
                int idx = base + k;
                if (idx >= MODEL_PROVIDERS.length) {
                    View fill = new View(this);
                    LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(0, 1, 1f);
                    if (k < 2) flp.rightMargin = dp(8);
                    rowLine.addView(fill, flp);
                    continue;
                }
                final int fi = idx;
                LinearLayout card = providerCard(idx);
                cardViews[idx] = card;
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                if (k < 2) clp.rightMargin = dp(8);   // 3 列：前两列留缝，最后一列贴边
                rowLine.addView(card, clp);
            }
            body.addView(rowLine, rllp);
        }

        // 2) 模型：必须先从账号拉一次，chip 里只有这个账号真实可用的名字
        body.addView(sheetLabel("模型"), labelLp(false));
        final TextView btnFetch = new TextView(this);
        btnFetch.setText("\u21e3 从账号拉取模型");
        btnFetch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        btnFetch.setSingleLine(true);
        btnFetch.setTextColor(Palette.c(0xFF8FBBFF));
        btnFetch.setGravity(Gravity.CENTER);
        btnFetch.setPadding(dp(10), dp(9), dp(10), dp(9));
        btnFetch.setBackground(Ui.box(this, Palette.c(0x1F5B93FF), Palette.c(0x555B93FF), 11f, 1f));
        btnFetch.setOnClickListener(v -> {
            String base = MODEL_PROVIDERS[sel[0]][1];
            String keyIn = etKey.getText().toString().trim();
            String oldKey = settings.apiKey == null ? "" : settings.apiKey.trim();
            String useKey = keyIn.isEmpty()
                    ? (normBase(base).equals(normBase(settings.baseUrl)) ? oldKey : "")
                    : keyIn;
            if (useKey.isEmpty()) {
                Toast.makeText(MainActivity.this,
                        "先在下面填 " + MODEL_PROVIDERS[sel[0]][0] + " 的 API Key", Toast.LENGTH_SHORT).show();
                etKey.requestFocus();
                return;
            }
            btnFetch.setEnabled(false);
            btnFetch.setText("拉取中…");
            Settings tmp = new Settings();
            tmp.baseUrl = base;
            tmp.apiKey = useKey;
            pickClient.fetch(tmp, new ModelListClient.Callback() {
                @Override
                public void onSuccess(java.util.List<String> models) {
                    runOnUiThread(() -> {
                        btnFetch.setEnabled(true);
                        btnFetch.setText("\u21e3 重新拉取（" + models.size() + "）");
                        fetched.clear();
                        if (models != null) fetched.addAll(models);
                        // 这批也是账号真实返回的，强度同样可以对它们生效
                        settings.pulledModels = new java.util.ArrayList<>(fetched);
                        settings.save(MainActivity.this);
                        mdl[0] = fetched.isEmpty() ? "" : fetched.get(0);
                        refresh[0].run();
                        if (fetched.isEmpty()) {
                            Toast.makeText(MainActivity.this,
                                    "这个账号没返回模型，检查 Key 或接口地址", Toast.LENGTH_LONG).show();
                        }
                    });
                }

                @Override
                public void onError(String message) {
                    runOnUiThread(() -> {
                        btnFetch.setEnabled(true);
                        btnFetch.setText("\u21e3 从账号拉取模型");
                        Toast.makeText(MainActivity.this, "拉取失败：" + message,
                                Toast.LENGTH_LONG).show();
                    });
                }
            });
        });
        body.addView(btnFetch, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        chipBox.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setClipToPadding(false);
        hsv.addView(chipBox, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        body.addView(hsv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 3) 自定义模型名（可选）
        body.addView(sheetLabel("自定义模型名"), labelLp(false));
        body.addView(etManual, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 4) API Key + 显隐切换
        body.addView(sheetLabel("API Key"), labelLp(false));
        FrameLayout keyWrap = new FrameLayout(this);
        keyWrap.addView(etKey, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        final TextView eye = new TextView(this);
        eye.setText("显示");
        eye.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        eye.setTextColor(Palette.c(0xFF7FB0FF));
        eye.setGravity(Gravity.CENTER);
        eye.setPadding(dp(10), dp(6), dp(10), dp(6));
        FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        elp.gravity = Gravity.CENTER_VERTICAL | Gravity.END;
        elp.rightMargin = dp(8);
        keyWrap.addView(eye, elp);
        body.addView(keyWrap, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        final boolean[] keyShown = {false};
        eye.setOnClickListener(v -> {
            keyShown[0] = !keyShown[0];
            etKey.setInputType(keyShown[0]
                    ? android.text.InputType.TYPE_CLASS_TEXT
                    : android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            // setInputType 会把字体重置回默认，这里补回来
            etKey.setTypeface(Typeface.MONOSPACE);
            eye.setText(keyShown[0] ? "隐藏" : "显示");
            if (!etKey.getText().toString().isEmpty()) {
                etKey.setSelection(etKey.getText().length());
            }
        });

        LinearLayout.LayoutParams khlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        khlp.topMargin = dp(7);
        body.addView(keyHint, khlp);

        // ---------- 选中态刷新（卡片 + chips 由同一个 runnable 驱动） ----------
        final Runnable renderChips = new Runnable() {
            @Override
            public void run() {
                chipBox.removeAllViews();
                if (fetched.isEmpty()) {
                    TextView none = new TextView(MainActivity.this);
                    none.setText("还没拉取 · 上面点一下就能拿到这个账号的模型");
                    none.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
                    none.setTextColor(Palette.c(0xFF7F8C9F));
                    none.setPadding(dp(2), dp(8), dp(2), dp(8));
                    chipBox.addView(none);
                    return;
                }
                for (int i = 0; i < fetched.size(); i++) {
                    final String m = fetched.get(i);
                    boolean on = m.equals(mdl[0]);
                    TextView chip = new TextView(MainActivity.this);
                    chip.setText(m);
                    chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
                    chip.setTypeface(Typeface.MONOSPACE);
                    chip.setSingleLine(true);
                    chip.setTextColor(on ? Palette.c(0xFFDCE8FF) : Palette.c(0xFF9AA7BA));
                    chip.setPadding(dp(12), dp(8), dp(12), dp(8));
                    chip.setBackground(on
                            ? Ui.box(MainActivity.this, Palette.c(0x2E5B93FF), Palette.c(0x665B93FF), 11f, 1f)
                            : Ui.box(MainActivity.this, 0x00000000, Palette.c(0xFF2E3A4C), 11f, 1f));
                    chip.setOnClickListener(v -> {
                        mdl[0] = m;
                        refresh[0].run();
                    });
                    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
                    if (i > 0) clp.leftMargin = dp(8);
                    chipBox.addView(chip, clp);
                }
            }
        };

        refresh[0] = new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < cardViews.length; i++) {
                    if (cardViews[i] != null) styleProviderCard(cardViews[i], i == sel[0]);
                }
                renderChips.run();

                boolean same = normBase(MODEL_PROVIDERS[sel[0]][1]).equals(normBase(settings.baseUrl));
                String old = settings.apiKey == null ? "" : settings.apiKey.trim();
                if (same && !old.isEmpty()) {
                    keyHint.setText("这一家的 Key 已经存过，留空就直接用它。");
                    keyHint.setTextColor(Palette.c(0xFF4CD7A0));
                    etKey.setHint("留空 = 用已保存的 Key");
                } else if (!old.isEmpty()) {
                    keyHint.setText("换服务商要填这一家的 Key；填了会替换现在用的（当前 "
                            + shortHost(settings.baseUrl) + "）");
                    keyHint.setTextColor(Palette.c(0xFFE0A642));
                    etKey.setHint("sk-...");
                } else {
                    keyHint.setText("还没有 Key：去服务商后台建一个粘进来，只存在这台手机上。");
                    keyHint.setTextColor(Palette.c(0xFF66707F));
                    etKey.setHint("sk-...");
                }
                etKey.setTypeface(Typeface.MONOSPACE);
            }
        };
        // 卡片点击放在这里绑：切换服务商要顺带清空 Key 输入、重建 chips
        for (int i = 0; i < cardViews.length; i++) {
            final int fi = i;
            if (cardViews[i] == null) continue;
            cardViews[i].setOnClickListener(v -> {
                if (sel[0] == fi) return;
                sel[0] = fi;
                mdl[0] = "";
                fetched.clear();
                btnFetch.setText("\u21e3 从账号拉取模型");
                btnFetch.setEnabled(true);
                etKey.setText("");
                refresh[0].run();
            });
        }
        refresh[0].run();

        // ---------- 底部主按钮 ----------
        final Runnable submit = new Runnable() {
            @Override
            public void run() {
                String manual = etManual.getText().toString().trim();
                String v = manual.isEmpty() ? mdl[0] : manual;
                if (v.isEmpty()) {
                    Toast.makeText(MainActivity.this,
                            "先点「从账号拉取模型」选一个，或在下面手填模型名", Toast.LENGTH_LONG).show();
                    return;
                }
                if (v.indexOf(' ') >= 0 || v.indexOf('\n') >= 0) {
                    Toast.makeText(MainActivity.this, "模型名不能带空格", Toast.LENGTH_SHORT).show();
                    return;
                }
                String base = MODEL_PROVIDERS[sel[0]][1];
                String keyIn = etKey.getText().toString().trim();
                String old = settings.apiKey == null ? "" : settings.apiKey.trim();
                String key = keyIn.isEmpty()
                        ? (normBase(base).equals(normBase(settings.baseUrl)) ? old : "")
                        : keyIn;
                if (key.isEmpty()) {
                    Toast.makeText(MainActivity.this,
                            MODEL_PROVIDERS[sel[0]][0] + " 还没有 Key，填一个马上就能用",
                            Toast.LENGTH_SHORT).show();
                    etKey.requestFocus();
                    return;
                }
                settings.baseUrl = base;
                settings.apiKey = key;
                boolean added = settings.addCustomModel(v);
                settings.model = v;
                settings.save(MainActivity.this);
                // 换了接口地址，之前从旧服务商拉到的模型列表就作废了，必须清掉
                lastServerModels = null;
                updateSubtitle();
                syncBalance(true);
                rebuildModelList(composeModelList(null), Settings.baseModel(settings), null);
                Toast.makeText(MainActivity.this,
                        (added ? "已添加 " : "已切换 ") + v + " · " + MODEL_PROVIDERS[sel[0]][0],
                        Toast.LENGTH_SHORT).show();
                d.dismiss();
            }
        };

        TextView ok = sheetPrimary("添加并使用");
        ok.setOnClickListener(v -> submit.run());
        // 分隔线和主按钮都塞进滚动区：跟上面的字段一起上下滑，不做固定底栏
        LinearLayout.LayoutParams seplp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        seplp.topMargin = dp(16);
        body.addView(sheetSeparator(), seplp);

        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        flp.topMargin = dp(13);
        flp.bottomMargin = dp(6);
        body.addView(ok, flp);

        // 同「全部模型」：窗口层居中无效，改在内容层包 FrameLayout 居中。
        int wpx = getResources().getDisplayMetrics().widthPixels;
        int hpx = getResources().getDisplayMetrics().heightPixels;
        int dw = (int) (wpx * 0.92f);
        d.setContentView(root);
        d.show();
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            w.setGravity(Gravity.CENTER);
            // 高度跟内容走：字段就那么几项，固定 86% 屏高会在底部空出一大截。
            // 内容真超一屏时才封顶到 86%（body 不是 ScrollView，超了只能靠这个上限兜住）。
            root.measure(android.view.View.MeasureSpec.makeMeasureSpec(dw,
                            android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(0,
                            android.view.View.MeasureSpec.UNSPECIFIED));
            int contentH = root.getMeasuredHeight();
            // 只按内容高度走会太紧：按钮直接贴在弹窗底边，看着憋。留 22dp 呼吸，
            // 同时兜住 86% 屏高的上限（body 不是 ScrollView，超屏没法滚）。
            w.setLayout(dw, Math.min(contentH + dp(22), (int) (hpx * 0.86f)));
            WindowManager.LayoutParams attrs = w.getAttributes();
            attrs.y = dp(39);
            w.setAttributes(attrs);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    /** 长按自定义模型：删掉它（不影响当前正在用的设置） */
    /** 长按模型行：隐藏（任何来源）/ 删除（仅自己添加的） */
    private void showModelActions(final String name, final boolean custom) {
        if (name == null) return;
        final java.util.List<String> items = new java.util.ArrayList<>();
        items.add("从列表隐藏");
        if (custom) items.add("删除自定义模型");
        new NebAlert.Builder(this)
                .setTitle(name)
                .setItems(items.toArray(new String[0]), (dd, which) -> {
                    if (which == 0) {
                        settings.hideModel(name);
                        settings.save(this);
                        rebuildModelList(composeModelList(lastServerModels),
                                Settings.baseModel(settings), null);
                        Toast.makeText(this, "已隐藏 " + name, Toast.LENGTH_SHORT).show();
                    } else {
                        confirmRemoveModel(name);
                    }
                })
                .show();
    }

    /** 列表底部的「恢复已隐藏」入口：只有确实隐藏过才出现 */
    private void addUnhideEntryRow() {
        if (modelListBox == null || settings.hiddenModels == null
                || settings.hiddenModels.isEmpty()) return;
        final int n = settings.hiddenModels.size();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), 0, dp(12), 0);
        android.graphics.drawable.StateListDrawable bg =
                new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_pressed},
                Ui.box(this, Palette.c(0x1F5B93FF), 0, 14f, 0f));
        bg.addState(new int[]{}, Ui.box(this, 0x00000000, 0, 14f, 0f));
        row.setBackground(bg);

        TextView tv = new TextView(this);
        tv.setText("恢复已隐藏的 " + n + " 个模型");
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setTextColor(Palette.c(0xFF7FB0FF));
        tv.setSingleLine(true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(4);
        row.addView(tv, tlp);

        row.setOnClickListener(v -> {
            settings.unhideAll();
            settings.save(this);
            rebuildModelList(composeModelList(lastServerModels),
                    Settings.baseModel(settings), null);
            Toast.makeText(this, "已恢复隐藏的模型", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(MODEL_ROW_H));
        rlp.bottomMargin = dp(4);
        modelListBox.addView(row, rlp);
    }

    private void confirmRemoveModel(final String name) {
        new NebAlert.Builder(this)
                .setTitle("删除自定义模型")
                .setMessage("把「" + name + "」从列表里移除？\n只是不再显示，随时可以重新添加。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dd, w) -> {
                    settings.removeCustomModel(name);
                    settings.save(this);
                    rebuildModelList(composeModelList(lastServerModels),
                            Settings.baseModel(settings), null);
                    Toast.makeText(this, "已移除 " + name, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void pickModel(String m) {
        if (m == null || m.trim().isEmpty()) return;
        String picked = m.trim();
        if (picked.equals(settings.model)) {
            if (modelSheet != null && modelSheet.isShowing()) modelSheet.dismiss();
            return;
        }
        settings.model = picked;
        settings.save(this);
        updateSubtitle();
        syncBalance(true);
        if (modelSheet != null && modelSheet.isShowing()) modelSheet.dismiss();
        Toast.makeText(this, "已切换到 " + picked, Toast.LENGTH_SHORT).show();
    }

    private Dialog thinkSheet;

    /**
     * 思考强度面板：每档一个滑动开关（互斥，开一个自动关其它），不再用菜单打勾。
     */
    private void showThinkSheet() {
        if (thinkSheet != null && thinkSheet.isShowing()) return;

        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        thinkSheet = d;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.roundRect(this, Palette.c(0xFF161C26), Palette.c(0xFF2A3240), 18f));
        root.setPadding(dp(16), dp(16), dp(16), dp(12));

        TextView title = new TextView(this);
        title.setText("思考强度");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        title.setTextColor(Palette.c(0xFFE6EDF7));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("对下一条提问生效；拉取到的模型会额外带上 reasoning_effort 参数");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        sub.setTextColor(Palette.c(0xFF7F8C9F));
        sub.setLineSpacing(dp(3), 1.3f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(4);
        subLp.bottomMargin = dp(8);
        root.addView(sub, subLp);

        final int n = Settings.THINK_LABELS.length;
        final Switch[] switches = new Switch[n];
        final TextView[] names = new TextView[n];

        for (int i = 0; i < n; i++) {
            final int level = i;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(4), dp(9), dp(4), dp(9));
            row.setClickable(true);

            TextView name = new TextView(this);
            name.setText(Settings.THINK_LABELS[i]);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            row.addView(name, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            names[i] = name;

            Switch sw = new Switch(this);
            styleSwitch(sw);
            switches[i] = sw;
            row.addView(sw);

            if (i > 0) {
                View line = new View(this);
                line.setBackgroundColor(Palette.c(0x14FFFFFF));
                root.addView(line, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)));
            }
            root.addView(row);

            row.setOnClickListener(v -> applyThinkLevel(level, switches, names));
            sw.setOnCheckedChangeListener((btn, checked) -> {
                if (checked) {
                    applyThinkLevel(level, switches, names);
                } else if (settings.thinkLevel == level) {
                    // 当前档不允许关掉，保持「总有一档生效」
                    syncThinkSwitches(switches, names);
                }
            });
        }

        View line2 = new View(this);
        line2.setBackgroundColor(Palette.c(0x14FFFFFF));
        root.addView(line2, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)));

        // 强度只对「从账号拉取到的模型」下发；这里直接写明当前模型是否算数
        TextView effTip = new TextView(this);
        String curModelName = Settings.baseModel(settings);
        if (curModelName.isEmpty()) {
            effTip.setText("还没选模型 · 选一个从账号拉取的模型，强度才会生效");
        } else if (Settings.isPulledModel(settings, curModelName)) {
            effTip.setText("当前模型来自账号拉取，档位会以 reasoning_effort 下发");
        } else {
            effTip.setText("当前模型是手填的 · 强度不会下发，从账号拉取的模型才生效");
        }
        effTip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        effTip.setTextColor(Palette.c(0xFF7F8C9F));
        effTip.setLineSpacing(dp(2), 1.25f);
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        etLp.topMargin = dp(10);
        root.addView(effTip, etLp);

        // 参数后端可能不认，提示词一定生效——把这条差别说清楚
        TextView effTip2 = new TextView(this);
        effTip2.setText("重度/极度同时会追加更严格的思考要求（找反例、核对细节、自检漏项），"
                + "并放宽输出额度；后端忽略参数时，效果就来自这里");
        effTip2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        effTip2.setTextColor(Palette.c(0xFF6B7889));
        effTip2.setLineSpacing(dp(2), 1.25f);
        LinearLayout.LayoutParams et2Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        et2Lp.topMargin = dp(6);
        root.addView(effTip2, et2Lp);

        syncThinkSwitches(switches, names);

        d.setContentView(root);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0x00000000));
            w.setGravity(Gravity.BOTTOM);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            w.setAttributes(lp);
        }
        d.show();
    }

    /** 统一的开关配色（深色主题下系统默认样式对比度太低） */
    private void styleSwitch(Switch sw) {
        sw.setShowText(false);
        sw.setThumbTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{Palette.c(0xFFFFFFFF), Palette.c(0xFFC7D3E4)}));
        sw.setTrackTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{Palette.c(0xFF5B93FF), Palette.c(0xFF39424F)}));
    }

    private void applyThinkLevel(int level, Switch[] switches, TextView[] names) {
        if (settings.thinkLevel != level) {
            settings.thinkLevel = level;
            settings.save(MainActivity.this);
            renderThinkBadge();
            updateSubtitle();
            if (level > 0 && !Settings.isPulledModel(settings, Settings.baseModel(settings))) {
                Toast.makeText(this, "强度只对从账号拉取到的模型生效，手填模型不会下发",
                        Toast.LENGTH_LONG).show();
            }
        }
        syncThinkSwitches(switches, names);
    }

    /** 刷新各开关状态；先摘掉监听，避免程序性的 setChecked 触发回调 */
    private void syncThinkSwitches(Switch[] switches, TextView[] names) {
        for (int i = 0; i < switches.length; i++) {
            Switch sw = switches[i];
            sw.setOnCheckedChangeListener(null);
            sw.setChecked(i == settings.thinkLevel);
            final int level = i;
            sw.setOnCheckedChangeListener((btn, checked) -> {
                if (checked) {
                    applyThinkLevel(level, switches, names);
                } else if (settings.thinkLevel == level) {
                    syncThinkSwitches(switches, names);
                }
            });
            if (names[i] != null) {
                names[i].setTextColor(i == settings.thinkLevel ? Palette.c(0xFF9DC0FF) : Palette.c(0xFFDCE5F2));
            }
        }
    }

    /** 抽屉遮罩不透明度 */
    private static final float DRAWER_DIM = 0.45f;
    /** 会话列表每批渲染条数：几百条一次性 inflate 会把主线程卡住 */
    private static final int DRAWER_CHUNK = 40;

    /** 侧滑抽屉：会话按天分组 + 底部设置入口；会话多时分批渲染，滚到底自动续载 */
    private void showConversationList() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View root = LayoutInflater.from(this).inflate(R.layout.drawer_conversations, null);

        final Window w = dialog.getWindow();
        final int drawerW;
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0x00000000));
            w.setGravity(Gravity.START);
            // 窗口铺到真正的全屏（含状态栏/导航栏区域），内容再用 padding 让出安全区，
            // 这样抽屉上下边正好夹在状态栏与系统导航栏之间
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            w.setWindowAnimations(R.style.DrawerAnim);
            WindowManager.LayoutParams lp = w.getAttributes();
            int sw = getResources().getDisplayMetrics().widthPixels;
            lp.width = Math.min(dp(330), (int) (sw * 0.86f));
            lp.height = WindowManager.LayoutParams.MATCH_PARENT;
            lp.gravity = Gravity.START;
            lp.dimAmount = DRAWER_DIM;
            w.setAttributes(lp);
            w.setLayout(lp.width, WindowManager.LayoutParams.MATCH_PARENT);
            drawerW = lp.width;
        } else {
            drawerW = dp(300);
        }

        // 抽屉内左滑跟手收起：窗口跟着手指走，松手过 30% 就直接滑走
        final float[] curX = {0f};
        DrawerHost host = new DrawerHost(this, new DrawerHost.SlideListener() {
            @Override
            public void onSlide(float offset, float progress) {
                curX[0] = offset;
                setDrawerOffset(w, (int) offset, progress);
            }

            @Override
            public void onRelease(boolean commit) {
                if (commit) {
                    dialog.dismiss();   // 余下的位移交给抽屉退出动画，视觉上是连续的
                } else {
                    animateDrawerBack(w, curX[0], drawerW);
                }
                curX[0] = 0f;
            }
        });
        host.addView(root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        dialog.setContentView(host);

        // 让出状态栏与系统导航栏（XML 里原本的 10dp 顶部留白保留）
        root.setPadding(root.getPaddingLeft(), root.getPaddingTop() + Ui.statusBarHeight(this),
                root.getPaddingRight(), Ui.navBarHeight(this));

        final LinearLayout listBox = root.findViewById(R.id.listContainer);
        final ScrollView sc = root.findViewById(R.id.drawerScroll);
        final List<Conversation> items = store.list();

        TextView sub = root.findViewById(R.id.tvDrawerSub);
        sub.setText(items.isEmpty() ? "还没有会话记录"
                : items.size() + " 个会话 · 最近的排在最前");
        TextView ver = root.findViewById(R.id.tvDrawerVer);
        String vn = appVersion();
        ver.setText(vn.isEmpty() ? "" : "v" + vn);

        root.findViewById(R.id.btnDrawerNew).setOnClickListener(v -> {
            dialog.dismiss();
            newConversation();
        });
        root.findViewById(R.id.rowDrawerBrowser).setOnClickListener(v -> {
            dialog.dismiss();
            try {
                startActivity(new Intent(MainActivity.this, BrowserActivity.class));
            } catch (Throwable t) {
                Toast.makeText(this, "打不开浏览器：" + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
        root.findViewById(R.id.rowDrawerSettings).setOnClickListener(v -> {
            dialog.dismiss();
            startActivityForResult(new Intent(MainActivity.this, SettingsActivity.class), REQ_SETTINGS);
        });

        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有会话\n右上角 ＋ 新建一个开始聊");
            empty.setGravity(Gravity.CENTER);
            empty.setTextColor(Palette.dim(this));
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            empty.setLineSpacing(dp(5), 1f);
            empty.setPadding(0, dp(56), 0, dp(56));
            listBox.addView(empty);
        }

        final DrawerState st = new DrawerState();
        st.dialog = dialog;
        st.box = listBox;
        st.scroll = sc;
        st.items = items;
        appendDrawerRows(st);
        // 当前会话排在很后面时，把首批一直铺到它那，打开就能定位
        int curIdx = indexOfConversation(items, current);
        while (curIdx >= st.next && st.next < items.size()) appendDrawerRows(st);

        // 滚到接近底部再补下一批，避免一次 inflate 几百行
        sc.setOnScrollChangeListener((v, sx, sy, ox, oy) -> fillDrawerIfNeeded(st));

        // 会话多时自动定位到当前正在聊的那条，省得往下翻
        dialog.setOnShowListener(d -> {
            final View av = st.active;
            if (av == null) return;
            sc.post(() -> sc.smoothScrollTo(0, Math.max(0, av.getTop() - dp(56))));
        });

        dialog.show();
    }

    private int indexOfConversation(List<Conversation> items, Conversation cur) {
        if (cur == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id.equals(cur.id)) return i;
        }
        return -1;
    }

    /** 再铺一批会话行；日期分组标题跨批次延续，不会重复 */
    private void appendDrawerRows(final DrawerState st) {
        int end = Math.min(st.items.size(), st.next + DRAWER_CHUNK);
        for (int i = st.next; i < end; i++) {
            final Conversation c = st.items.get(i);
            String g = dayGroup(c.updatedAt);
            if (!g.equals(st.group)) {
                st.box.addView(groupHeader(g));
                st.group = g;
            }

            View item = LayoutInflater.from(this).inflate(R.layout.item_conversation, st.box, false);
            TextView t = item.findViewById(R.id.tvConvTitle);
            TextView meta = item.findViewById(R.id.tvConvMeta);
            String ct = Conversation.cleanTitle(c.title);
            t.setText(ct.isEmpty() ? "新对话" : ct);
            meta.setText(new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(c.updatedAt))
                    + " · " + c.messageCount() + " 条消息");

            // 最后一条不画分隔线，避免和底部设置栏之间出现双线
            View divider = item.findViewById(R.id.convDivider);
            if (divider != null && i == st.items.size() - 1) divider.setVisibility(View.GONE);

            if (c.id.equals(current.id)) {
                t.setTextColor(Palette.accent(this));
                item.setBackgroundColor(Palette.accentSoft(this, 0x1F));
                st.active = item;
            }
            item.setOnClickListener(v -> {
                st.dialog.dismiss();
                if (!c.id.equals(current.id)) renderConversation(c);
            });
            item.findViewById(R.id.btnDel).setOnClickListener(v -> confirmDelete(st.dialog, c));
            st.box.addView(item);
        }
        st.next = end;
    }

    /** 滚到接近底部就补下一批；补完仍不到一屏高时继续补 */
    private void fillDrawerIfNeeded(final DrawerState st) {
        if (st.next >= st.items.size()) return;
        View child = st.scroll.getChildAt(0);
        if (child == null) return;
        if (st.scroll.getScrollY() + st.scroll.getHeight() >= child.getBottom() - dp(160)) {
            appendDrawerRows(st);
            st.scroll.post(() -> fillDrawerIfNeeded(st));
        }
    }

    /** 抽屉窗口横向偏移：progress=0 完全展开，1 完全滑出屏幕 */
    private void setDrawerOffset(Window w, int x, float progress) {
        if (w == null) return;
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.x = x;
        lp.dimAmount = Math.max(0f, DRAWER_DIM * (1f - progress));
        w.setAttributes(lp);
    }

    /** 松手没滑够：弹回原位 */
    private void animateDrawerBack(final Window w, float fromX, final int drawerW) {
        ValueAnimator a = ValueAnimator.ofFloat(fromX, 0f);
        a.setDuration(190);
        a.setInterpolator(new DecelerateInterpolator());
        a.addUpdateListener(an -> {
            float x = (Float) an.getAnimatedValue();
            float p = drawerW <= 0 ? 0f : Math.max(0f, Math.min(1f, -x / drawerW));
            setDrawerOffset(w, (int) x, p);
        });
        a.start();
    }

    /** 抽屉列表分批渲染的游标状态 */
    private static class DrawerState {
        Dialog dialog;
        LinearLayout box;
        ScrollView scroll;
        List<Conversation> items;
        int next;
        String group;
        View active;
    }

    /**
     * 抽屉容器：只接管「左滑」一个手势，竖向滑动一律放行给里面的 ScrollView，
     * 否则会话一多就滚不动了。
     */
    private static class DrawerHost extends FrameLayout {
        interface SlideListener {
            void onSlide(float offset, float progress);

            void onRelease(boolean commit);
        }

        private final SlideListener listener;
        private final VelocityTracker vt = VelocityTracker.obtain();
        private float downX;
        private float downY;
        private boolean dragging;
        private boolean decided;

        DrawerHost(Context c, SlideListener l) {
            super(c);
            this.listener = l;
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            final int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
            final float density = getResources().getDisplayMetrics().density;
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getX();
                    downY = ev.getY();
                    dragging = false;
                    decided = false;
                    vt.clear();
                    break;
                case MotionEvent.ACTION_MOVE: {
                    vt.addMovement(ev);
                    float dx = ev.getX() - downX;
                    float dy = ev.getY() - downY;
                    if (!decided) {
                        // 1.4 太苛刻：手指稍微斜着滑就被判成竖向、手势整个失效，放到 1.1
                        if (dx < -slop && Math.abs(dx) > Math.abs(dy) * 1.1f) {
                            decided = true;
                            dragging = true;
                            // 已经被子 View 吃掉的 DOWN 得补一个 CANCEL，
                            // 否则按钮会卡在按下高亮状态
                            MotionEvent cancel = MotionEvent.obtain(ev);
                            cancel.setAction(MotionEvent.ACTION_CANCEL);
                            super.dispatchTouchEvent(cancel);
                            cancel.recycle();
                        } else if (Math.abs(dy) > slop) {
                            decided = true;      // 竖向：让 ScrollView 正常滚
                        }
                    }
                    if (dragging) {
                        int wd = getWidth();
                        float off = Math.max(-wd, Math.min(0f, dx));
                        listener.onSlide(off, wd <= 0 ? 0f : -off / wd);
                        return true;
                    }
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    boolean was = dragging;
                    boolean canceled = ev.getActionMasked() == MotionEvent.ACTION_CANCEL;
                    float dx = ev.getX() - downX;
                    dragging = false;
                    decided = false;
                    if (was) {
                        vt.addMovement(ev);
                        vt.computeCurrentVelocity(1000);
                        float vx = canceled ? 0f : vt.getXVelocity();
                        int wd = getWidth();
                        // 两条路都收：拖过抽屉宽 15%（原来 30%，得拖 90dp 才响应），
                        // 或者向左快速甩一下（400dp/s）——后者才是「灵敏」的关键
                        boolean commit = !canceled
                                && (dx < -wd * 0.15f || vx < -400f * density);
                        listener.onRelease(commit);
                        return true;
                    }
                    break;
                }
            }
            return super.dispatchTouchEvent(ev);
        }
    }

    /** 日期分组标题：今天 / 昨天 / 10月23日 周四 */
    private String dayGroup(long ts) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        Calendar now = Calendar.getInstance();
        String k = c.get(Calendar.YEAR) + "-" + c.get(Calendar.DAY_OF_YEAR);
        if (k.equals(now.get(Calendar.YEAR) + "-" + now.get(Calendar.DAY_OF_YEAR))) return "今天";
        Calendar y = Calendar.getInstance();
        y.add(Calendar.DAY_OF_YEAR, -1);
        if (k.equals(y.get(Calendar.YEAR) + "-" + y.get(Calendar.DAY_OF_YEAR))) return "昨天";
        SimpleDateFormat f = new SimpleDateFormat(
                c.get(Calendar.YEAR) == now.get(Calendar.YEAR) ? "M月d日 EEE" : "yyyy年M月d日",
                Locale.CHINA);
        return f.format(new Date(ts));
    }

    private TextView groupHeader(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setTextColor(Palette.dim(this));
        tv.setLetterSpacing(0.06f);
        tv.setPadding(dp(18), dp(14), dp(18), dp(6));
        return tv;
    }

    private String appVersion() {
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return v == null ? "" : v.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private void confirmDelete(final Dialog host, final Conversation c) {
        new NebAlert.Builder(this)
                .setTitle("删除会话")
                .setMessage("「" + (Conversation.cleanTitle(c.title).isEmpty()
                        ? "新对话" : Conversation.cleanTitle(c.title)) + "」删除后无法恢复，确定吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, which) -> {
                    store.delete(c.id);
                    if (c.id.equals(current.id)) {
                        if (store.isEmpty()) {
                            current = store.create();
                        } else {
                            current = store.list().get(0);
                        }
                        renderConversation(current);
                    }
                    if (host != null && host.isShowing()) {
                        host.dismiss();
                        showConversationList();
                    }
                })
                .show();
    }

    // ---------------- 底栏与页面 ----------------

    private void initTabs() {
        contentHost = findViewById(R.id.contentHost);
        pageChat = findViewById(R.id.pageChat);
        pageViews[BottomBar.TAB_CHAT] = pageChat;

        bottomBar = new BottomBar(this, (LinearLayout) findViewById(R.id.bottomBar),
                new BottomBar.Listener() {
                    @Override
                    public void onTabSelected(int index) {
                        showTab(index);
                    }
                });
        bottomBar.select(BottomBar.TAB_CHAT);

        // 空闲时预建其余页面：第一次点 tab 不再现建（现建会有明显停顿）
        contentHost.postDelayed(new Runnable() {
            @Override
            public void run() {
                for (int i = 1; i < pageViews.length; i++) {
                    if (pageViews[i] != null) continue;
                    try {
                        pageOf(i);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, 1200);
    }

    /** 懒加载页面 */
    private View pageOf(int index) {
        if (index == BottomBar.TAB_CHAT) return pageChat;
        if (pageViews[index] != null) return pageViews[index];

        View v = null;
        if (index == BottomBar.TAB_PROJECT) {
            projectPage = new ProjectPage(this, new ProjectPage.Callback() {
                @Override
                public void onOpenTerminal(File dir) {
                    pageOf(BottomBar.TAB_TERMINAL);
                    showTab(BottomBar.TAB_TERMINAL);
                    if (terminalPage != null) terminalPage.setCwd(dir.getAbsolutePath());
                }

                @Override
                public void onBrowseSandbox(File dir) {
                    pageOf(BottomBar.TAB_SANDBOX);
                    showTab(BottomBar.TAB_SANDBOX);
                    if (sandboxPage != null) sandboxPage.openDir(dir);
                }
            });
            v = projectPage.getView();
        } else if (index == BottomBar.TAB_TERMINAL) {
            terminalPage = new TerminalPage(this);
            v = terminalPage.getView();
        } else if (index == BottomBar.TAB_SANDBOX) {
            sandboxPage = new SandboxPage(this);
            v = sandboxPage.getView();
        } else if (index == BottomBar.TAB_ENV) {
            envPage = new EnvPage(this, new EnvPage.Callback() {
                @Override
                public void onOpenSettings() {
                    startActivityForResult(new Intent(MainActivity.this, SettingsActivity.class), REQ_SETTINGS);
                }

                @Override
                public void onOpenMcp() {
                    com.nebula.aichat.mcp.McpPanel.show(MainActivity.this);
                }

                @Override
                public void onOpenSkills() {
                    com.nebula.aichat.skill.SkillPanel.show(MainActivity.this);
                }

                @Override
                public void onOpenApkTools() {
                    com.nebula.aichat.apk.ApkToolsPanel.show(MainActivity.this);
                }

                @Override
                public void onOpenRemoteConfig() {
                    pageOf(BottomBar.TAB_TERMINAL);
                    showTab(BottomBar.TAB_TERMINAL);
                    if (terminalPage != null) terminalPage.askRemoteUrl();
                }
            });
            v = envPage.getView();
        }

        if (v != null) {
            contentHost.addView(v, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            // 新建页面先隐藏：可见性统一由 showTab 决定（也让后台预建不会盖住聊天页）
            v.setVisibility(View.GONE);
            pageViews[index] = v;
        }
        return v;
    }

    private void showTab(final int index) {
        // 先给点击反馈：底栏高亮立刻变化，再做页面构建/切换，避免"点了没反应"的错觉
        if (bottomBar != null) bottomBar.select(index);
        View target = pageOf(index);
        for (int i = 0; i < pageViews.length; i++) {
            View p = pageViews[i];
            if (p != null) p.setVisibility(i == index ? View.VISIBLE : View.GONE);
        }
        if (target == null) return;
        currentTab = index;
        if (index != BottomBar.TAB_CHAT) hideKeyboard();
        // 页面自身的刷新延后一帧：先把点击 → 切页这一帧画出来，重活放到下一帧
        if (contentHost != null) {
            contentHost.post(new Runnable() {
                @Override
                public void run() {
                    notifyShown(index);
                }
            });
        }
    }

    /** 页面显示时的轻量刷新 */
    private void notifyShown(int index) {
        if (currentTab != index) return;
        if (index == BottomBar.TAB_TERMINAL && terminalPage != null) terminalPage.onShown();
        else if (index == BottomBar.TAB_PROJECT && projectPage != null) projectPage.onShown();
        else if (index == BottomBar.TAB_SANDBOX && sandboxPage != null) sandboxPage.onShown();
        else if (index == BottomBar.TAB_ENV && envPage != null) envPage.onShown();
    }

    @Override
    public void onBackPressed() {
        if (currentTab != BottomBar.TAB_CHAT) {
            showTab(BottomBar.TAB_CHAT);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SETTINGS) {
            // 设置里可能改了执行方式 / API Key，环境页需要重建
            if (envPage != null) envPage.invalidate();
            if (terminalPage != null) terminalPage.onShown();
            return;
        }
        if (requestCode == REQ_PICK_FILE || requestCode == REQ_PICK_IMAGE) {
            if (resultCode == RESULT_OK) handlePicked(requestCode, data);
            return;
        }
        if (sandboxPage != null) sandboxPage.onActivityResult(requestCode, resultCode, data);
        if (com.nebula.aichat.skill.SkillPanel.onActivityResult(requestCode, resultCode, data)) return;
        com.nebula.aichat.apk.ApkToolsPanel.onActivityResult(requestCode, resultCode, data);
        com.nebula.aichat.apk.ApkWorkbenchPanel.onActivityResult(requestCode, resultCode, data);
    }

    private String dayText(long ts) {
        Date d = new Date(ts);
        Date now = new Date();
        String a = dayFmt.format(d);
        String b = dayFmt.format(now);
        if (a.substring(0, a.indexOf(' ')).equals(b.substring(0, b.indexOf(' ')))) {
            return "今天 " + sameDayFmt.format(d);
        }
        return a;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
