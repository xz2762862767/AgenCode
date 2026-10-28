package com.nebula.aichat;

import com.nebula.aichat.ui.Palette;

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

    /** 思考过程折叠时露出多少行 */
    private static final int THINK_FOLD_LINES = 6;
    /** 用户亲手折叠过卡片后，就不再自动展开打扰他 */
    private boolean toolUserTouched = false;
    private TextView toolFoldBtn;
    /** 执行中状态的定时刷新（旋转符号 + 计时） */
    private Runnable toolTick;
    private int spinIdx = 0;
    private static final String[] SPIN = {"\u25d0", "\u25d3", "\u25d1", "\u25d2"};
    private static final int ST_RUN = 0, ST_OK = 1, ST_FAIL = 2, ST_ABORT = 3;

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

    private TextView tvTitle, tvSub, tvBalance;
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
    /** 推理折叠块的挂载容器（在状态行下方、正文上方） */
    private LinearLayout thinkHost;
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
    private TextView runBannerTimeTag;   // 计时前的动词：执行 / 用时 / 停止
    private View runBannerLine;          // 底部 2dp 细进度（唯一动效）
    private android.widget.FrameLayout runBannerLineWrap;
    private ObjectAnimator lineAnim;
    private android.widget.FrameLayout runBannerBox;  // 胶囊的不裁剪容器（涟漪可以溢出）
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

        tvTitle = findViewById(R.id.tvTitle);
        tvSub = findViewById(R.id.tvSub);
        tvBalance = findViewById(R.id.tvBalance);
        if (tvBalance != null) tvBalance.setOnClickListener(v -> showBalanceDetail());
        ivModelIcon = findViewById(R.id.ivModelIcon);
        scroll = findViewById(R.id.scroll);
        if (scroll != null) {
            scroll.setOnScrollChangeListener(
                    (v, x, y, ox, oy) -> lastScrollAt = SystemClock.uptimeMillis());
        }
        chatContainer = findViewById(R.id.chatContainer);
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

    @Override
    protected void onResume() {
        super.onResume();
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
        syncAliveService();
        updateSubtitle();
        applyChatBackground();
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

    private void updateSubtitle() {
        if (!settings.isConfigured()) {
            if (ivModelIcon != null) ivModelIcon.setVisibility(View.GONE);
            tvSub.setText("未配置 API Key · 点右上角设置");
            tvSub.setTextColor(Palette.accent(this));
        } else {
            // 思考强度可能把 deepseek-chat 自动升级成 deepseek-reasoner，这里显示真正在用的模型
            String eff = Settings.effectiveModel(settings);
            tvSub.setText(Settings.isAutoReasoner(settings) ? eff + " ·思考" : eff);
            tvSub.setTextColor(Palette.dim(this));
            // 只有 DeepSeek 系列模型才显示品牌小图标，换到其它厂商模型时自动隐藏
            String m = eff.toLowerCase(Locale.ROOT);
            if (ivModelIcon != null) {
                ivModelIcon.setVisibility(m.contains("deepseek") ? View.VISIBLE : View.GONE);
            }
        }
        // 顶栏是同步的集中出口：配置变化、切回前台都会走到这里，顺带刷新余额（内部有节流）
        syncBalance(false);
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
            stopToolTicker();
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

    /** 重开会话时按时间线还原过程：正文段与工具行按当时的顺序交错显示 */
    private void renderTimeline(LinearLayout host, Message m, String body) {
        // 历史回放的工具记录也统一收进一条「思考完成 ▾」，和实时渲染保持一致
        LinearLayout procBox = null;
        int calls = 0;
        for (Message.Step st : m.steps) {
            if (st == null) continue;
            if (st.isTool()) {
                if (procBox == null) {
                    procBox = new LinearLayout(this);
                    procBox.setOrientation(LinearLayout.VERTICAL);
                    procBox.setClipChildren(false);
                    host.addView(procBox, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                calls++;
                // 每次调用各自一个框，点行尾箭头展开它自己的详情
                LinearLayout wrap = makeToolWrap(procBox);
                wrap.setTag(st);
                TextView row = toolBodyOf(wrap);
                if (row != null) {
                    fillToolRow(row, st.ok ? ST_OK : ST_FAIL,
                            st.text == null ? "" : st.text, null, st.res, st.ms);
                    updateToolExplain(row, toolExplain(st.text, st.arg, st.res, st.ok),
                            st.ok ? Palette.c(0xFF9DB2CC) : Palette.c(0xFFFF9BA0));
                }
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
        if (procBox != null) {
            java.util.List<View[]> one = new java.util.ArrayList<>(1);
            one.add(new View[]{null, procBox});
            foldProcess(host, one, calls);
        }
        addConclusionCard(host, m, false);
        attachArtifacts(host, body);
        attachLongPress(host, m);
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
            + "5. 这轮如果给出了执行步骤清单，结论要总结「最终做成了什么」，不要复述步骤。";

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
        blp.leftMargin = dp(76);   // 左侧留白，让气泡靠右、不顶到字
        row.addView(box, blp);

        attachLongPress(box, m);
        chatContainer.addView(row);
    }

    /** 产物类型 → 图标 + 中文说明 */
    private static String[] artifactInfo(String path) {
        int d = path.lastIndexOf('.');
        String ext = d > 0 ? path.substring(d + 1).toLowerCase(Locale.US) : "";
        switch (ext) {
            case "apk": return new String[]{"📦", "Android 安装包"};
            case "png": case "jpg": case "jpeg": case "webp": case "gif":
                return new String[]{"🖼️", "图片"};
            case "mp4": case "webm": case "mov": return new String[]{"🎬", "视频"};
            case "mp3": case "wav": case "m4a": case "aac": return new String[]{"🎵", "音频"};
            case "zip": case "tar": case "gz": case "7z": return new String[]{"🗜️", "压缩包"};
            case "json": return new String[]{"🧾", "JSON 数据"};
            case "md": return new String[]{"📝", "Markdown 文档"};
            case "txt": case "log": return new String[]{"📄", "文本文件"};
            case "html": case "htm": return new String[]{"🌐", "网页"};
            case "py": return new String[]{"🐍", "Python 脚本"};
            case "sh": return new String[]{"⚙️", "Shell 脚本"};
            case "java": return new String[]{"☕", "Java 源码"};
            case "xml": return new String[]{"🧩", "XML 配置"};
            default: return new String[]{"📎", "文件"};
        }
    }

    /** APK 卡片第二行：包名 · 版本 · 大小，拿不到包信息就只报大小 */
    private String apkMeta(java.io.File f, long size) {
        String head = "";
        try {
            android.content.pm.PackageInfo pi = getPackageManager()
                    .getPackageArchiveInfo(f.getAbsolutePath(), 0);
            if (pi != null) {
                String pkg = pi.packageName == null ? "" : pi.packageName;
                String vn = pi.versionName == null ? "" : pi.versionName.trim();
                if (!pkg.isEmpty()) head = pkg + (vn.isEmpty() ? "" : " · v" + vn) + " · ";
            }
        } catch (Throwable ignored) {
        }
        return head + humanSize(size) + " · 点按安装";
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


        TextView title = new TextView(this);
        title.setText("📎 产出物 · " + exists.size());
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Palette.c(0xFFAFC2DC));
        LinearLayout.LayoutParams tlp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp2.topMargin = dp(10);
        tlp2.bottomMargin = dp(4);
        content.addView(title, tlp2);

        for (int i = 0; i < exists.size(); i++) {
            final String path = exists.get(i);
            String[] info = artifactInfo(path);
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

            TextView icon = new TextView(this);
            icon.setText(info[0]);
            icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
            card.addView(icon, new LinearLayout.LayoutParams(dp(30),
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);

            TextView nm = new TextView(this);
            nm.setText(name);
            nm.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            nm.setTypeface(Typeface.DEFAULT_BOLD);
            nm.setTextColor(Palette.c(0xFFE6EEFB));
            nm.setMaxLines(1);
            nm.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            col.addView(nm);

            TextView meta = new TextView(this);
            meta.setText(isApk
                    ? apkMeta(new java.io.File(path), sizes.get(i))
                    : info[1] + " · " + humanSize(sizes.get(i)) + " · 点按复制路径");
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

            LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp2.topMargin = dp(6);
            content.addView(card, clp2);
        }
    }

    /** 产物卡片点按 APK：直接交给系统安装器安装 */
    private void installApk(java.io.File f) {
        if (f == null || !f.isFile()) {
            Toast.makeText(this, "文件不存在，可能已被移动或删除", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!f.canRead()) {
            Toast.makeText(this, "没有读取权限，无法安装", Toast.LENGTH_SHORT).show();
            return;
        }
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
                                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
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
        // 气泡左右直接撑到容器内边距，比原来再宽 8dp，但不贴边
        rowLp.leftMargin = 0;
        rowLp.rightMargin = 0;
        row.setLayoutParams(rowLp);
        row.setClipChildren(false);
        row.setClipToPadding(false);

        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setBackgroundResource(R.drawable.bubble_ai);
        bubble.setPadding(dp(14), dp(13), dp(14), dp(10));
        // 过程块用负 margin 略微外扩，关闭裁剪以免细框被切掉
        bubble.setClipChildren(false);
        bubble.setClipToPadding(false);

        if (withStatus) {
            addStatusRow(bubble);
            thinkHost = new LinearLayout(this);
            thinkHost.setOrientation(LinearLayout.VERTICAL);
            bubble.addView(thinkHost, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (withStatus) clp.topMargin = dp(9);
        bubble.addView(content, clp);

        // 常驻横幅挂在正文下方、按钮条上方：不展开工具卡片也知道现在是死是活
        if (withStatus) {
            runBannerWrap = buildRunBanner();
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(8);
            bubble.addView(runBannerWrap, rlp);
        }

        addAiActions(bubble, m);

        row.addView(bubble, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        chatContainer.addView(row);
        return content;
    }

    /** 「思考过程」折叠块：默认收起，点开才显示模型推理原文 */
    private View buildThinkBox(final String text, String costSecs) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.think_bg);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(8);
        blp.bottomMargin = dp(8);
        // 细框之后再把块向左右各外扩 6dp：比正文宽一点，又不至于顶到气泡边缘
        blp.leftMargin = -dp(6);
        blp.rightMargin = -dp(6);
        box.setLayoutParams(blp);

        final TextView body = new TextView(this);
        body.setText(text);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        body.setTextColor(Palette.c(0xFF9DB3A4));
        body.setLineSpacing(dp(4), 1.45f);
        body.setPadding(dp(12), dp(2), dp(12), dp(11));
        // 折叠时只露 6 行（末尾省略号），展开后不设上限、整段显示
        body.setMaxLines(THINK_FOLD_LINES);
        body.setEllipsize(TextUtils.TruncateAt.END);
        body.setVisibility(View.GONE);

        final TextView label = com.nebula.aichat.ui.Ui.foldTitle(this,
                "思考过程 · 用时 " + costSecs + " 秒");
        label.setTextColor(Palette.c(0xFF7EE787));

        final TextView chip = greenPill("\u25be 展开");

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(12), dp(8), dp(12), dp(8));

        // 绿色小圆点：一眼认出这是「思考」而不是普通折叠块
        View dot = new View(this);
        dot.setBackground(Ui.roundRect(this, Palette.c(0xFF3FB950), 0, 3f));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(6), dp(6));
        dotLp.rightMargin = dp(7);
        head.addView(dot, dotLp);

        head.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(chip);

        final boolean[] open = {false};
        View.OnClickListener tg = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                open[0] = !open[0];
                body.setVisibility(open[0] ? View.VISIBLE : View.GONE);
                body.setMaxLines(open[0] ? Integer.MAX_VALUE : THINK_FOLD_LINES);
                body.setEllipsize(open[0] ? null : TextUtils.TruncateAt.END);
                chip.setText(open[0] ? "\u25b4 收起" : "\u25be 展开");
                // 展开后让外层的滚动容器重新按内容量布局，否则可能停在旧高度
                box.requestLayout();
                android.view.ViewParent vp = box.getParent();
                if (vp instanceof View) ((View) vp).requestLayout();
            }
        };
        head.setOnClickListener(tg);
        chip.setOnClickListener(tg);

        box.addView(head);
        box.addView(body);
        return box;
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

        parent.addView(row, lp);

        // 思考区与正文之间的分隔线：1dp 两端渐隐实线（比虚线更干净，不会碎成噪点）
        View hair = new View(this);
        hair.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Palette.c(0x003FB950), Palette.c(0x553FB950), Palette.c(0x003FB950)}));
        parent.addView(hair, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));

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
        // 有推理内容就折成一块「思考过程」，默认收起，不占正文地方
        if (thinkHost != null && thinkBuf != null && thinkBuf.length() > 0) {
            thinkHost.addView(buildThinkBox(thinkBuf.toString(),
                    String.format(Locale.US, "%.1f", cost / 1000.0)));
            thinkBuf.setLength(0);
        }
        // 命令还在跑的时候，状态行必须继续转，不能提前变成「思考完成」的静态灰字，
        // 否则用户看着一动不动的界面，根本分不清是执行中还是已经结束
        if (pendingToolCount() > 0) {
            setStatusBusy("正在执行 " + lastToolName() + " …");
            return;
        }
        statusText.setText("思考完成（用时 " + String.format(Locale.US, "%.1f", cost / 1000.0) + " 秒）");
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

    /** 整轮结束：状态行给出总耗时，旋转停下，界面明确「不再动了」 */
    private void markStatusFinal(boolean ok) {
        stopToolTicker();
        finishRunBanner(ok);
        if (statusText == null) return;
        long cost = Math.max(0, SystemClock.uptimeMillis() - reqStartAt);
        statusText.setText(ok ? "完成 · 总用时 " + dur(cost) : "已停止 · 总用时 " + dur(cost));
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
        box.setPadding(pad, pad, pad, Ui.dp(this, 4));
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
                        .append("（二进制文件，内容未内联。请用 run_shell / read_file / apk_unpack 等工具按上面的路径实际操作，"
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
        toolUserTouched = false;
        toolFoldBtn = null;
        toolTraceCollapsed = false;
        if (tools == null) {
            tools = new ToolRegistry(this);
            // 让 AI 能真的在终端里执行命令（run_shell），执行过程同步回显到终端页
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
        runBannerTimeTag = null;
        runBannerLine = null;
        runBannerLineWrap = null;
        lineAnim = null;
        statusRow = null;
        statusRowBg = null;
        streamMessage = new Message(Message.ROLE_ASSISTANT, "");
        streamContent = addAiBubble(streamMessage, true);
        attachLongPress(streamContent, streamMessage);
        // 时间线复位：正文段与工具行都从零开始，按发生顺序交错往下排
        tlSeg = null;
        tlSegR = null;
        tlSegStart = 0;
        tlSteps = new java.util.ArrayList<>();
        toolRowStep.clear();
        final MarkdownRenderer renderer = new MarkdownRenderer(this, streamContent, msgSp);
        renderer.setOnConfirmClick(rows -> openConfirmSheet(rows));
        renderer.setOnConfirmSubmit(msg -> submitConfirmCard(msg));
        streamRenderer = renderer;

        // 传给模型的历史（占位 assistant 消息尚未加入列表，故取全部）
        List<Message> history = new ArrayList<>();
        int limit = current.messages.size();
        int start = Math.max(0, limit - 24);
        for (int i = start; i < limit; i++) history.add(current.messages.get(i));

        String skillInject = com.nebula.aichat.skill.SkillStore.buildIndex(this);
        client.extraSystem = STYLE_HINT + "\n\n" + TASK_HINT + "\n\n" + CONFIRM_HINT
                + "\n\n" + CONCLUSION_HINT + "\n\n" + timeContext()
                + (settings.browserEnabled ? "\n\n" + WEB_HINT : "")
                + (skillInject == null || skillInject.isEmpty() ? "" : "\n\n" + skillInject)
                + "\n\n" + com.nebula.aichat.apk.SandboxBridge.describe(this);
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
                // 这轮已经消耗了 token，顺手刷新顶栏余额
                syncBalance(true);
            }

            @Override
            public void onError(String message) {
                com.nebula.aichat.util.CrashLog.d("stream", "出错: " + message);
                if (streamStale) return;
                finishStream(false, streamBuf == null ? "" : streamBuf.toString(), message, renderer);
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
                com.nebula.aichat.util.CrashLog.d("tool", "无结果，自动续跑: " + note);
                ui.post(() -> {
                    if (note != null && !note.isEmpty()) setStatusBusy(note);
                });
            }
        });
    }
    // 旧的「独立固定高度工具卡片」已废除：工具行现在直接内联在正文流里，
    // 与文字按发生顺序交错排列（见 newToolRow / renderTimeline）。

    private void addToolCall(String name, String argsJson) {
        if (streamStale) return;
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
        updateToolExplain(row, toolExplain(name, argsJson, null, true) + " …", Palette.c(0xFF7E93AE));
        trimToolRows();
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
            fillToolRow(toolRows.get(idx), ok ? ST_OK : ST_FAIL, name, null, detail, ms);
            updateToolExplain(toolRows.get(idx),
                    toolExplain(name, toolRowArgs.get(idx), detail, ok),
                    ok ? Palette.c(0xFF9DB2CC) : Palette.c(0xFFFF9BA0));
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
                    ok ? Palette.c(0xFF9DB2CC) : Palette.c(0xFFFF9BA0));
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
     * 行首图标的状态切换：
     * 运行态是 3/4 圆环，转一个 900ms 的无限循环（属性动画，走硬件加速，不占主线程循环）；
     * 结束态换成对勾/叉/横线并停转；成功时补一个 0.55→1 的回弹，比干切有回应。
     * tv 的父容器就是 head，第 0 个子 View 是图标。
     */
    private void updateToolMark(TextView tv, int st, int color) {
        if (tv == null) return;
        View parent = (View) tv.getParent();
        if (!(parent instanceof ViewGroup)) return;
        ViewGroup head = (ViewGroup) parent;
        if (head.getChildCount() == 0) return;
        View v0 = head.getChildAt(0);
        if (!(v0 instanceof ImageView)) return;
        ImageView iv = (ImageView) v0;

        // 先收掉上一轮的旋转，否则 cancel 后角度停在随机位置
        Object t = iv.getTag();
        if (t instanceof ObjectAnimator) {
            ((ObjectAnimator) t).cancel();
            iv.setTag(null);
        }
        iv.setRotation(0f);
        iv.setColorFilter(color);
        // 圆底只给一丝同色，靠它把图标和文字分到两个层次
        android.graphics.drawable.GradientDrawable chip =
                new android.graphics.drawable.GradientDrawable();
        chip.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        chip.setColor((color & 0x00FFFFFF) | Palette.c(0x24000000));
        iv.setBackground(chip);

        if (st == ST_RUN) {
            iv.setImageResource(R.drawable.ic_tool_arc);
            ObjectAnimator rot = ObjectAnimator.ofFloat(iv, "rotation", 0f, 360f);
            rot.setDuration(900L);
            rot.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            rot.setInterpolator(new android.view.animation.LinearInterpolator());
            iv.setTag(rot);
            rot.start();
            return;
        }

        // 成功态用「停止符」而不是对勾：这一步跑完了、收尾了
        iv.setImageResource(st == ST_OK ? R.drawable.ic_tool_stop
                : st == ST_FAIL ? R.drawable.ic_tool_fail : R.drawable.ic_tool_abort);
        if (st == ST_OK) {
            iv.setScaleX(0.55f);
            iv.setScaleY(0.55f);
            iv.animate().scaleX(1f).scaleY(1f).setDuration(320)
                    .setInterpolator(new OvershootInterpolator(2.2f)).start();
        } else {
            iv.setScaleX(1f);
            iv.setScaleY(1f);
        }
    }

    private void fillToolRow(TextView tv, int st, String name, String args, String result, long ms) {
        boolean running = st == ST_RUN;
        // 边和底跟着状态走：跑着泛绿，跑完收成中性，失败才红
        Object wrapTag = tv.getTag();
        if (wrapTag instanceof View) {
            View w = (View) wrapTag;
            w.setBackground(null);
            // 状态刷新会重跑这一行，箭头得跟着详情区的开合走，不能被复位成朝下
            View aw = w.findViewWithTag(TAG_TOOL_ARROW);
            if (aw != null) {
                LinearLayout det = w instanceof LinearLayout
                        ? findDetailArea((LinearLayout) w) : null;
                boolean open = det != null && det.getVisibility() == View.VISIBLE;
                aw.setRotation(open ? 180f : 0f);
            }
        }
        int markColor = running ? Palette.c(C_RUN) : st == ST_OK ? Palette.c(C_OK)
                : st == ST_FAIL ? Palette.c(C_FAIL) : Palette.c(C_ABORT);
        // 行首图标交给 head 里那个独立 ImageView：文本里不再塞字符，
        // 图标才能自己跑平滑旋转，不受 ticker 的 500ms 节奏拖累。
        updateToolMark(tv, st, markColor);
        SpannableStringBuilder sb = new SpannableStringBuilder();

        int s0 = sb.length();
        sb.append(name);
        int nameColor = running ? Palette.c(0xFFB9C6D6) : st == ST_FAIL ? C_FAIL
                : st == ST_ABORT ? Palette.c(C_ABORT) : Palette.c(C_TEXT);
        sb.setSpan(new StyleSpan(Typeface.BOLD), s0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(nameColor), s0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        // 终端回显感：执行中跟在命令后面的参数，完成后直接给结果摘要
        String detail = running ? args : result;
        if (detail != null && !detail.isEmpty()) {
            String one = briefText(detail);
            if (one.length() > 40) one = one.substring(0, 40) + "\u2026";
            // 用「·」当分隔，比两个空格更能看出「工具名 / 参数」的层级
            int sep = sb.length();
            sb.append(" \u00b7 ");
            sb.setSpan(new ForegroundColorSpan(Palette.c(0x66FFFFFF)), sep, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            int d0 = sb.length();
            sb.append(one);
            // 失败行的摘要偏红，扫一眼就知道这条不是正常结果
            sb.setSpan(new ForegroundColorSpan(running ? Palette.c(0xFF8FA0B4)
                    : st == ST_FAIL ? Palette.c(0xFFE8A0A0) : C_TEXT2), d0, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            markKeyInfo(sb, d0, sb.length(), st == ST_FAIL ? Palette.c(0xFFFF9B9B) : Palette.c(0xFFBFD6F2), false);
        }

        // 耗时改成右端的小胶囊：前面的点线是"过程"，它是"结果"，单独一块更清楚
        int t0 = sb.length();
        sb.append(" ");
        int c0 = sb.length();
        sb.append(st == ST_ABORT ? "\u5df2\u4e2d\u6b62" : dur(ms));
        sb.setSpan(new ForegroundColorSpan(running ? Palette.c(C_DIM) : Palette.c(0xFF93A2B4)), c0, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new RelativeSizeSpan(0.92f), c0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new BackgroundColorSpan(Palette.c(0x1AFFFFFF)), c0, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int t1 = sb.length();
        sb.append(" ");

        tv.setTextColor(Palette.c(0xFFC9D4E2));
        tv.setText(sb);
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
        // 框比之前更实：淡绿底 + 更深的绿边，一眼能划出「这是一步工具」
        // 亮绿描边 + 外发光：本体线 #4ADE80 1.6dp，外面再套一层 33% 的绿晕（外扩 1.5dp）。
        // 底色只给 12% 深绿做衬托，亮的是「边」而不是整块，看着像通了电的边框。
        // host 必须 clipChildren=false，否则那圈外发光会被父容器裁掉。
        // 无边框：工具行直接融进正文流，靠图标/字重/箭头分层；
        // 展开后的详情区自带淡底，负责把内容托住，不需要外框再画一圈。
        wrap.setBackground(null);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = dp(6);
        wlp.bottomMargin = dp(0);
        // 左右各外扩 1dp：配合外发光，让这一步工具比正文段落「胖」一点点，
        // 视觉上更靠前，也不会因为发光溢出而显得边被压掉
        host.setClipChildren(false);
        host.addView(wrap, wlp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        // 框已外扩到气泡边缘，内容留 10/8 的内边距，别贴着绿边
        head.setPadding(dp(0), 0, dp(0), 0);

        // 行首状态图标：独立 ImageView，才能跑真正的旋转动画（ticker 500ms 太慢）
        ImageView mark = new ImageView(this);
        mark.setScaleType(ImageView.ScaleType.FIT_XY);
        // 图标坐进一个同色系的小圆底里，比裸图标多一层"徽章"的感觉
        mark.setPadding(dp(3), dp(3), dp(3), dp(3));
        LinearLayout.LayoutParams markLp = new LinearLayout.LayoutParams(dp(20), dp(20));
        markLp.rightMargin = dp(10);
        head.addView(mark, markLp);

        TextView body = new TextView(this);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        body.setTypeface(Typeface.MONOSPACE);
        body.setSingleLine(true);
        body.setEllipsize(TextUtils.TruncateAt.END);
        // 上下 padding 加到 9，框整体「厚」一档；配合 CENTER_VERTICAL，
        // 15dp 的图标和等宽正文在同一条水平中线上，不会一个偏高一个偏低
        body.setPadding(0, dp(9), 0, dp(9));
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setTextColor(Palette.c(0xFFD3DDE9));
        head.addView(body, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView arrow = new ImageView(this);
        arrow.setTag(TAG_TOOL_ARROW);
        arrow.setImageDrawable(new ChevronDrawable(Palette.c(0xFF8593A6)));
        arrow.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(18), dp(18));
        alp.leftMargin = dp(4);
        head.addView(arrow, alp);

        wrap.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 按下有淡绿反馈，暗示这一行可以点开
        android.graphics.drawable.StateListDrawable bg =
                new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_pressed},
                Ui.roundRect(this, Palette.c(0x1CFFFFFF), 0, 8f));
        bg.addState(new int[]{}, Ui.roundRect(this, 0x00000000, 0, 7f));
        head.setBackground(bg);

        head.setOnClickListener(v -> toggleToolExpand(wrap));
        return wrap;
    }

    /** 从外框里取出摘要行 */
    private static TextView toolBodyOf(LinearLayout wrap) {
        if (wrap == null || wrap.getChildCount() == 0) return null;
        View head = wrap.getChildAt(0);
        if (!(head instanceof LinearLayout)) return null;
        LinearLayout h = (LinearLayout) head;
        // 不能再写死 index 0：head 里先是状态图标（ImageView），接着才是正文，
        // 最后是展开箭头。按类型取第一个 TextView，body 永远排在 arrow 前面。
        for (int i = 0; i < h.getChildCount(); i++) {
            View v = h.getChildAt(i);
            if (v instanceof TextView) return (TextView) v;
        }
        return null;
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
    /** 行尾「×N」调用次数标注 */
    private static final String TAG_TOOL_SEQ = "tool_seq";

    /**
     * 同一工具被反复调用时，给这一组里的每一行补上「×1 ×2 ×3…」。
     * 只出现一次就不标——单个调用标个 ×1 纯属噪音。
     * 计数直接按现有的 toolRowNames 现算，不做额外状态，裁剪/重开也不会算错。
     */
    private void refreshToolSeq(String name) {
        if (name == null) return;
        int total = 0;
        for (int i = 0; i < toolRowNames.size(); i++) {
            if (name.equals(toolRowNames.get(i))) total++;
        }
        if (total < 2) return;
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
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextColor(Palette.c(0xFF7C8B9E));
            tv.setGravity(Gravity.CENTER_VERTICAL);
            tv.setPadding(dp(6), 0, dp(6), 0);
            int at = head.getChildCount();
            View arrow = head.findViewWithTag(TAG_TOOL_ARROW);
            if (arrow != null) at = head.indexOfChild(arrow);
            head.addView(tv, at, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        tv.setText("\u00d7" + k);
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
    private static final String TAG_EXPLAIN = "tool_explain";
    private static final String TAG_EXPLAIN_BODY = "tool_explain_body";
    private static final String TAG_EXPLAIN_ICON = "tool_explain_icon";
    private static final String TAG_EXPLAIN_ARROW = "tool_explain_arrow";

    /**
     * 绿框下面那条独立翻译行：机器动作在上面（命令原文），人话在下面（中文解析）。
     * 两框等宽并落在同一条竖线上，一行只说一句，读起来是"动作 → 解释"的垂直节奏。
     */
    private TextView updateToolExplain(TextView tv, String text, int color) {
        if (tv == null) return null;
        Object o = tv.getTag();
        if (!(o instanceof View)) return null;
        View wrap = (View) o;
        if (!(wrap.getParent() instanceof LinearLayout)) return null;
        LinearLayout parent = (LinearLayout) wrap.getParent();

        int at = parent.indexOfChild(wrap);
        LinearLayout box = null;
        if (at >= 0 && at + 1 < parent.getChildCount()) {
            View nx = parent.getChildAt(at + 1);
            if (TAG_EXPLAIN.equals(nx.getTag()) && nx instanceof LinearLayout) {
                box = (LinearLayout) nx;
            }
        }
        if (box == null) {
            box = makeExplainBox();
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            // 无框方案下两者都以气泡内边距为基准，天然对齐，不用再外扩
            lp.topMargin = dp(4);
            lp.bottomMargin = dp(9);
            parent.addView(box, at + 1, lp);
        }

        TextView body = (TextView) box.findViewWithTag(TAG_EXPLAIN_BODY);
        if (body == null) return null;
        if (text != null && text.contentEquals(body.getText())) return body;
        // 动作段加粗提亮、结果段降一档：一句话里也有主次，不是一坨同色小字
        body.setText(styledExplain(text, color));
        // 「译」章吃状态色：这一行唯一的高饱和落点，扫一眼就知道这步跑成没成
        View tagView = box.findViewWithTag(TAG_EXPLAIN_ICON);
        if (tagView instanceof TextView) {
            TextView tg = (TextView) tagView;
            tg.setTextColor(soften(color, 0.85f));
            tg.setBackground(Ui.roundRect(this, (color & 0x00FFFFFF) | Palette.c(0x4A000000), 0, 4f));
        }
        return body;
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
        sb.setSpan(new ForegroundColorSpan(soften(color, 0.86f)), 0, actEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (cut >= 0) {
            sb.setSpan(new ForegroundColorSpan(Palette.c(0x66FFFFFF)), cut, cut + 3,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            // 结果段整段定调：失败偏红、成功贴着状态色，"成没成"不用逐字读
            boolean bad = P_ERR.matcher(src.substring(cut + 3)).find();
            sb.setSpan(new ForegroundColorSpan(bad ? Palette.c(0xFFFFB9B9) : soften(color, 0.72f)),
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
            sb.setSpan(new ForegroundColorSpan(soften(color, 0.95f)), a, b, EX);
            if (pathMono) sb.setSpan(new android.text.style.TypefaceSpan("monospace"), a, b, EX);
            sb.setSpan(new BackgroundColorSpan((color & 0x00FFFFFF) | Palette.c(0x3A000000)), a, b, EX);
        }
        java.util.regex.Matcher mf = P_FILE.matcher(sub);
        while (mf.find()) {
            int a = start + mf.start(), b = start + mf.end();
            sb.setSpan(new ForegroundColorSpan(soften(color, 0.92f)), a, b, EX);
            if (pathMono) sb.setSpan(new android.text.style.TypefaceSpan("monospace"), a, b, EX);
            sb.setSpan(new BackgroundColorSpan((color & 0x00FFFFFF) | Palette.c(0x2E000000)), a, b, EX);
        }
        java.util.regex.Matcher mg = P_FLAG.matcher(sub);
        while (mg.find()) {
            int a = start + mg.start(), b = start + mg.end();
            sb.setSpan(new ForegroundColorSpan(soften(color, 0.80f)), a, b, EX);
            if (pathMono) sb.setSpan(new android.text.style.TypefaceSpan("monospace"), a, b, EX);
        }
        java.util.regex.Matcher mn = P_NUM.matcher(sub);
        while (mn.find()) {
            String g = mn.group();
            // 纯数字太短（如「1」）不值得标，避免整行花花绿绿
            if (g.trim().length() < 2) continue;
            int a = start + mn.start(), b = start + mn.end();
            sb.setSpan(new StyleSpan(Typeface.BOLD), a, b, EX);
            sb.setSpan(new ForegroundColorSpan(Palette.c(0xFFFFFFFF)), a, b, EX);
        }
        java.util.regex.Matcher me = P_ERR.matcher(sub);
        while (me.find()) {
            int a = start + me.start(), b = start + me.end();
            sb.setSpan(new StyleSpan(Typeface.BOLD), a, b, EX);
            sb.setSpan(new ForegroundColorSpan(Palette.c(0xFFFF9A9A)), a, b, EX);
            sb.setSpan(new BackgroundColorSpan(Palette.c(0x46FF5A5A)), a, b, EX);
        }
    }

    /** 把颜色往白里提 t 倍：保留色相的"状态感"，同时压低饱和度免得小字刺眼 */
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
        // 比工具框的 14dp 收紧一档：二级信息不该和一级卡片同样"厚"
        // 无边框：不再画描边和渐变底，只留按下反馈
        android.graphics.drawable.StateListDrawable sld =
                new android.graphics.drawable.StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_pressed},
                Ui.roundRect(this, Palette.c(0x1CFFFFFF), 0, 8f));
        sld.addState(new int[]{}, Ui.roundRect(this, 0x00000000, 0, 8f));
        row.setBackground(sld);

        // 行首「译」方章：上面工具行是圆形状态图标（转圈/勾/叉），这里是方块字章，
        // 形状完全不同，眼睛不会把「执行状态」和「中文解释」看成一回事。
        TextView tag = new TextView(this);
        tag.setTag(TAG_EXPLAIN_ICON);
        tag.setText("译");
        tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        tag.setTypeface(Typeface.DEFAULT_BOLD);
        tag.setGravity(Gravity.CENTER);
        tag.setTextColor(Palette.c(0xFFB6C7D9));
        tag.setBackground(Ui.roundRect(this, Palette.c(0x2E9DB2CC), 0, 4f));
        // 18 + 12 = 30dp，与工具行「20dp 图标 + 10dp 间距」的正文左缘对齐
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(20), dp(18));
        ilp.leftMargin = dp(0);
        ilp.rightMargin = dp(10);
        row.addView(tag, ilp);

        TextView t = new TextView(this);
        t.setTag(TAG_EXPLAIN_BODY);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        // 展开成多行时要好看：行距放宽一点，底色块之间不互相挤
        t.setLineSpacing(dp(2), 1.25f);
        // 单行固定高度：每一步都一样高，滚动时不会忽高忽低地"波动"；
        // 内容长就省略尾，想看全点行尾箭头松开行数
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(dp(0), dp(7), dp(6), dp(7));
        t.setTextColor(Palette.c(0xFFC6D5E6));
        row.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 行尾下拉箭头：点了把这一句中文从单行放成多行
        ImageView ar = new ImageView(this);
        ar.setTag(TAG_EXPLAIN_ARROW);
        ar.setImageDrawable(new ChevronDrawable(Palette.c(0xFF6E7C8E)));
        ar.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams arlp = new LinearLayout.LayoutParams(dp(17), dp(17));
        arlp.leftMargin = dp(2);
        row.addView(ar, arlp);

        row.setOnClickListener(v -> toggleExplainExpand(row));
        return row;
    }

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
        if (arrow != null) arrow.animate().rotation(expand ? 180f : 0f).setDuration(180).start();
    }

    /** 找已经建好的详情区。不能按下标取——中间还隔着一行中文解析 */
    private static LinearLayout findDetailArea(LinearLayout wrap) {
        if (wrap == null) return null;
        for (int i = 0; i < wrap.getChildCount(); i++) {
            View v = wrap.getChildAt(i);
            if (v instanceof LinearLayout && v.getTag() instanceof Detail) {
                return (LinearLayout) v;
            }
        }
        return null;
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
        wrap.addView(det, new LinearLayout.LayoutParams(
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
        LinearLayout host = streamContent != null ? streamContent : chatContainer;

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

    /** 一个组里只让最后一步露脸，其余 GONE；点一下提示行就全摊开 */
    private void applyToolGroup(LinearLayout box, TextView head, boolean open) {
        if (box == null) return;
        int n = box.getChildCount();
        // 找出最后一绿框（工具行），它和紧跟其后的翻译框一起露脸
        int lastTool = -1;
        int tools = 0;
        for (int i = 0; i < n; i++) {
            View v = box.getChildAt(i);
            if (!TAG_EXPLAIN.equals(v.getTag())) {
                lastTool = i;
                tools++;
            }
        }
        if (lastTool < 0) lastTool = n - 1;
        for (int i = 0; i < n; i++) {
            View v = box.getChildAt(i);
            boolean show = open || i == lastTool
                    || (i == lastTool + 1 && TAG_EXPLAIN.equals(v.getTag()));
            v.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (head == null) return;
        if (tools <= 1) {
            head.setVisibility(View.GONE);
            return;
        }
        head.setVisibility(View.VISIBLE);
        head.setText(open ? "收起上面的 " + (tools - 1) + " 步"
                : "前面还有 " + (tools - 1) + " 步 · 点此展开");
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
    private void closeToolGroup() {
        if (toolGroupBox != null) refreshToolGroup();
        toolGroupBox = null;
        toolGroupHead = null;
    }

    // ------------------------------------------------------------ 过程收纳

    /** 过程区折叠头：一轮答完只剩这一行，点箭头才摊开过程 */
    private static final String TAG_PROC_FOLD = "proc_fold";

    /**
     * 把一组过程视图收进「思考完成 ▾」。
     *
     * <p>为什么收：工具行和翻译行是内联在正文流里的（不然看不到
     * 「先说一句 → 跑个命令 → 接着说」这个顺序），代价是答案写完以后，
     * 读者眼前还散着一堆执行记录。收起来之后气泡里就是干净的一段答案，
     * 想复盘的人点开箭头，全都还在。</p>
     *
     * @param host   过程视图所在的容器（实时是 streamContent，历史是气泡内容区）
     * @param groups 过程组，每项 = [组头(可空), 组容器]
     * @param calls  本轮调用次数，写进折叠头给个量感
     */
    private void foldProcess(LinearLayout host, java.util.List<View[]> groups, int calls) {
        if (host == null || groups == null || groups.isEmpty()) return;

        // 组内先摊平并把状态位写回 tag：外层展开一次就能看全，也不会被后续 refresh 又收回去
        for (View[] g : groups) {
            if (g != null && g.length > 1 && g[1] instanceof LinearLayout) {
                LinearLayout box = (LinearLayout) g[1];
                box.setTag(Boolean.TRUE);
                applyToolGroup(box, (TextView) g[0], true);
            }
        }

        // 同一气泡只留一个折叠头（重试或补渲染会再调一次）
        View stale = host.findViewWithTag(TAG_PROC_FOLD);
        if (stale != null) host.removeView(stale);

        final TextView head = new TextView(this);
        head.setTag(TAG_PROC_FOLD);
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        head.setTextColor(Palette.c(0xFF8A9CB4));
        head.setPadding(dp(2), dp(4), dp(2), dp(6));
        head.setIncludeFontPadding(false);
        head.setClickable(true);
        head.setSelected(false);          // selected 兼作「已展开」状态位
        head.setText(procFoldLabel(calls, false));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(2);
        hlp.bottomMargin = dp(2);

        // 插到第一个过程视图之前：正文段不会被挤到折叠头上面去
        int at = 0;
        for (View[] g : groups) {
            int i = (g != null && g.length > 1 && g[1] != null) ? host.indexOfChild(g[1]) : -1;
            if (i >= 0) {
                at = i;
                break;
            }
        }
        host.addView(head, at, hlp);

        final java.util.List<View[]> gs = groups;
        head.setOnClickListener(v -> {
            boolean open = !v.isSelected();
            v.setSelected(open);
            ((TextView) v).setText(procFoldLabel(calls, open));
            for (View[] g : gs) setProcVisible(g, open);
            if (!open) scrollToBottom(false);   // 收起后高度变矮，贴回底部不跳
        });

        // 默认收起：一轮答完，读者要看的是答案
        for (View[] g : gs) setProcVisible(g, false);
    }

    private static String procFoldLabel(int calls, boolean open) {
        String arrow = open ? "\u25b4" : "\u25be";   // ▴ / ▾
        return calls > 0 ? "思考完成 \u00b7 " + calls + " 次调用 " + arrow
                : "思考完成 " + arrow;
    }

    private void setProcVisible(View[] g, boolean visible) {
        if (g == null || g.length < 2) return;
        if (g[1] != null) g[1].setVisibility(visible ? View.VISIBLE : View.GONE);
        // 组头（「前面还有 N 步」那条）收起时一并藏掉；展开时交回 applyToolGroup 决定
        if (!visible && g[0] != null) g[0].setVisibility(View.GONE);
    }

    /** 本轮答完：把过程整体收进「思考完成 ▾」 */
    private void collapseStreamProcess() {
        if (streamContent == null || procGroups.isEmpty()) return;
        foldProcess(streamContent, new java.util.ArrayList<>(procGroups), toolRows.size());
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
     *  1. 单行，34dp 高，三个信息位：状态竖条 / 在做什么 / 计时（右对齐）。
     *  2. 底色只有一层 5% 白玻璃，没有描边、没有状态染色 —— 彩色块在深色气泡里必然显廉价。
     *  3. 颜色只出现在两处：左侧 2dp 竖条、计时数字。
     *  4. 唯一动效是底部 2dp 细进度匀速滑过。不要呼吸、流光、涟漪、图标弹跳。
     *  5. 不写解释型文案（"没卡住""现在已经停了"），那是提示条的语气，不是产品。
     */
    private LinearLayout buildRunBanner() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setVisibility(View.GONE);

        final android.widget.FrameLayout bar = new android.widget.FrameLayout(this);
        bar.setVisibility(View.GONE);
        bar.setClipChildren(true);          // 进度条滑出边界要裁掉
        bar.setClipToPadding(false);

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setGravity(Gravity.CENTER_VERTICAL);
        inner.setPadding(dp(11), dp(9), dp(12), dp(9));

        // 左侧短竖条：2dp × 13dp，整条横幅唯一的色源
        runBannerRailBg = new View(this);
        GradientDrawable railBg = new GradientDrawable();
        railBg.setCornerRadius(dp(1));
        railBg.setColor(Palette.c(C_RUN));
        runBannerRailBg.setBackground(railBg);
        inner.addView(runBannerRailBg, new LinearLayout.LayoutParams(dp(2), dp(13)));

        // 主信息：运行时是工具名（等宽），结束时是中文结论
        runBannerText = new TextView(this);
        runBannerText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        runBannerText.setTextColor(Palette.c(0xFFA9B7C9));
        runBannerText.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
        runBannerText.setIncludeFontPadding(false);
        runBannerText.setSingleLine(true);
        runBannerText.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nameLp.leftMargin = dp(10);
        inner.addView(runBannerText, nameLp);

        // 并发计数：只有多个同时跑时才出现
        runBannerMeta = new TextView(this);
        runBannerMeta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        runBannerMeta.setTextColor(Palette.c(0xFF66768A));
        runBannerMeta.setIncludeFontPadding(false);
        runBannerMeta.setSingleLine(true);
        runBannerMeta.setVisibility(View.GONE);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.leftMargin = dp(8);
        inner.addView(runBannerMeta, metaLp);

        // 时间前缀：光秃秃一个「1.2s」看不出是还在跑还是已经停了，
        // 前面补一个动词（执行 / 用时 / 停止），数字才有着落。
        runBannerTimeTag = new TextView(this);
        runBannerTimeTag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        runBannerTimeTag.setTextColor(Palette.c(0xFF8FA0B4));
        runBannerTimeTag.setIncludeFontPadding(false);
        runBannerTimeTag.setSingleLine(true);
        runBannerTimeTag.setGravity(Gravity.END);
        LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tagLp.leftMargin = dp(8);
        inner.addView(runBannerTimeTag, tagLp);

        // 计时：等宽 + 固定宽度，秒数刷新时不会左右跳
        runBannerTime = new TextView(this);
        runBannerTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        runBannerTime.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        runBannerTime.setTextColor(Palette.c(0xFFEDF3FA));
        runBannerTime.setIncludeFontPadding(false);
        runBannerTime.setSingleLine(true);
        runBannerTime.setGravity(Gravity.END);
        LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(
                dp(52), ViewGroup.LayoutParams.WRAP_CONTENT);
        timeLp.leftMargin = dp(8);
        inner.addView(runBannerTime, timeLp);

        bar.addView(inner, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 底部细进度：一条两端渐隐的同色光，运行中匀速滑过
        runBannerLineWrap = new android.widget.FrameLayout(this);
        runBannerLineWrap.setClipChildren(true);
        android.widget.FrameLayout.LayoutParams lwLp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(2, dp(2)));
        lwLp.gravity = Gravity.BOTTOM;
        bar.addView(runBannerLineWrap, lwLp);

        runBannerLine = new View(this);
        runBannerLineWrap.addView(runBannerLine, new android.widget.FrameLayout.LayoutParams(
                dp(60), ViewGroup.LayoutParams.MATCH_PARENT));
        runBannerLine.setAlpha(0f);

        runBanner = bar;
        wrap.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    /** 配色：玻璃底恒定为 5% 白，状态色只用于竖条与计时数字 */
    private void paintBanner(int color, boolean running) {
        try {
            int solid = color & 0x00FFFFFF;
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(10));
            g.setColor(Palette.c(0x0DFFFFFF));
            runBanner.setBackground(g);

            if (runBannerRailBg != null) {
                GradientDrawable rail = new GradientDrawable();
                rail.setCornerRadius(dp(1));
                rail.setColor(solid | (running ? Palette.c(0xE6000000) : Palette.c(0xA6000000)));
                runBannerRailBg.setBackground(rail);
            }
            if (runBannerText != null) {
                runBannerText.setTextColor(running ? Palette.c(0xFF93F2C4) : Palette.c(0xFFDCE6F2));
            }
            if (runBannerTime != null) {
                runBannerTime.setTextColor(running ? Palette.c(0xFFC2F7DC) : (solid | 0xFF000000));
            }
            if (runBannerMeta != null) runBannerMeta.setTextColor(Palette.c(0xFF66768A));
            if (runBannerLine != null) {
                runBannerLine.setBackground(new GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[]{solid | 0x00000000, solid | Palette.c(0x66000000),
                            solid | 0xFF000000, solid | Palette.c(0x66000000),
                            solid | 0x00000000}));
            }
        } catch (Throwable ignored) {
        }
    }

    /** 进度条匀速滑过：运行时唯一在动的东西 */
    private void startBannerAnim() {
        if (runBannerLine == null || runBannerLineWrap == null) return;
        if (lineAnim != null) return;
        int w = runBannerLineWrap.getWidth();
        if (w <= 0) {
            if (flowRetry < 3) {
                flowRetry++;
                runBannerLineWrap.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        startBannerAnim();
                    }
                }, 80);
            }
            return;
        }
        int lw = Math.max(dp(52), (int) (w * 0.42f));
        ViewGroup.LayoutParams lp = runBannerLine.getLayoutParams();
        lp.width = lw;
        runBannerLine.setLayoutParams(lp);
        runBannerLine.setAlpha(1f);
        runBannerLine.setTranslationX((float) -lw);
        lineAnim = ObjectAnimator.ofFloat(runBannerLine, View.TRANSLATION_X,
                (float) -lw, (float) w);
        lineAnim.setDuration(1200);
        lineAnim.setRepeatCount(ObjectAnimator.INFINITE);
        lineAnim.setInterpolator(new LinearInterpolator());
        lineAnim.start();
    }

    private void stopBannerAnim() {
        if (lineAnim != null) {
            lineAnim.cancel();
            lineAnim = null;
        }
        if (runBannerLine != null) {
            runBannerLine.animate().cancel();
            runBannerLine.animate().alpha(0f).setDuration(220).start();
        }
    }

    private void showRunBanner(String name, String meta, String time, int color, boolean running) {
        if (runBanner == null || runBannerText == null) return;
        runBannerText.setTypeface(Typeface.create(
                running ? "monospace" : "sans-serif", Typeface.NORMAL));
        runBannerText.setText(name);
        if (runBannerMeta != null) {
            boolean has = meta != null && meta.length() > 0;
            runBannerMeta.setText(has ? meta : "");
            runBannerMeta.setVisibility(has ? View.VISIBLE : View.GONE);
        }
        if (runBannerTime != null) runBannerTime.setText(time == null ? "" : time);
        if (runBannerTimeTag != null) {
            // 三态各用一个动词：跑着=执行，正常收尾=用时，异常收尾=停止
            String tag = running ? "执行" : (color == Palette.c(C_OK) ? "用时" : "停止");
            runBannerTimeTag.setText(tag);
            // 运行时动词保持弱化灰，结束态跟着状态色走（绿 / 红 / 灰）
            runBannerTimeTag.setTextColor(running ? Palette.c(0xFF8FA0B4) : color);
        }
        paintBanner(color, running);
        if (runBannerWrap != null) {
            runBannerWrap.setAlpha(1f);
            if (runBannerWrap.getVisibility() != View.VISIBLE) {
                runBannerWrap.setVisibility(View.VISIBLE);
            }
        }
        runBanner.setAlpha(1f);
        if (runBanner.getVisibility() != View.VISIBLE) runBanner.setVisibility(View.VISIBLE);
    }

    /** 还有命令在跑：工具名 + 计时 + 底部细进度，信息密度高但不吵 */
    private void updateRunBanner() {
        if (runBanner == null || toolRows.isEmpty()) return;
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
        showRunBanner(lastToolName(), meta, dur(ms), Palette.c(C_RUN), true);
        startBannerAnim();
        // 顶部状态行同步秒数：长回答把气泡底部顶出屏幕时，抬头也能看到还在动
        setStatusBusy(lastToolName() + " 执行中 " + dur(ms)
                + (pending > 1 ? " · 另有 " + (pending - 1) + " 个" : ""));
        if (statusText != null) statusText.setTextColor(Palette.c(C_RUN));
        setStatusTint(Palette.c(C_RUN));
    }

    /** 整轮结束：给结论 + 定格总用时，停留几秒后淡出 */
    private void finishRunBanner(boolean ok) {
        if (runBanner == null) return;
        if (toolRows.isEmpty()) {
            if (runBannerWrap != null) runBannerWrap.setVisibility(View.GONE);
            else runBanner.setVisibility(View.GONE);
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

        // 竖条「落定」：压一下再弹回，克制的一点回应
        if (runBannerRailBg != null) {
            runBannerRailBg.animate().cancel();
            runBannerRailBg.setScaleY(0.42f);
            runBannerRailBg.animate().scaleY(1f).setDuration(420)
                    .setInterpolator(new OvershootInterpolator(2f)).start();
        }
        if (runBanner != null) {
            runBanner.animate().cancel();
            runBanner.setScaleX(0.994f);
            runBanner.setScaleY(0.994f);
            runBanner.animate().scaleX(1f).scaleY(1f).setDuration(240)
                    .setInterpolator(new DecelerateInterpolator()).start();
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
        }, 3800);
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
                }
                updateToolHead();
                updateRunBanner();
                if (pendingToolCount() > 0) ui.postDelayed(this, 500);
                else toolTick = null;
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
        String t = s.replace("\r", " ").replace("\n", " ").trim();
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
        tv.setTextColor(Palette.c(0xFFFF9BA0));
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
        // 任务结束：有常驻通知就退回空闲态，否则整个撤掉
        try {
            ui.removeCallbacks(keepAliveTick);
            if (settings != null && settings.aliveNotify) KeepAliveService.startIdle(this);
            else KeepAliveService.stop(this);
        } catch (Throwable ignored) {
        }
        busy = false;
        setSendState(false);
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
            // 工具轮次用尽被迫收尾的，给一个「继续跑下一段」的按钮
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
        TextView tip = Ui.text(this, "已用满工具轮次，任务可能没做完 ", 11.5f, Palette.c(0xFF8B97A9));
        box.addView(tip);
        TextView go = Ui.chip(this, "继续跑下一段 ↗", Palette.c(0x1A3FB950), Palette.c(0xFF7EE787),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        v.setEnabled(false);
                        ((TextView) v).setText("已继续");
                        awaitingExecution = true;
                        sendAsUser("继续，上一段工具轮次已经用满（" + why + "）。"
                                + "接着刚才的进度往下做，别重复已经完成的步骤；"
                                + "如果确实做不下去了，直接说卡点和我要做的动作。");
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
    }

    private void setSendState(boolean streaming) {
        btnSend.setImageResource(streaming ? R.drawable.ic_stop : R.drawable.ic_send);
        btnSend.setBackgroundResource(streaming ? R.drawable.btn_round_stop : R.drawable.btn_round);
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
        if (Settings.isAutoReasoner(settings)) label += "·R";
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
    /** 离线/未配置时的兜底候选；拉到 /models 后会与之合并去重 */
    private static final String[] BUILTIN_MODELS = {"deepseek-chat", "deepseek-reasoner"};
    /** 最近一次服务端返回的模型名：添加/删除自定义模型后要就地重建列表，不能又拉一次 */
    private java.util.List<String> lastServerModels;
    private TextView sheetRefreshBtn;
    private boolean fetchingModels;

    private void showModelSheet() {
        if (modelSheet != null && modelSheet.isShowing()) return;

        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        modelSheet = d;
        // 上一轮若在拉取途中关掉弹窗，标志位会挂着不放，导致再次打开时「刷新」永远提示在拉取
        fetchingModels = false;
        sheetRefreshBtn = null;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.roundRect(this, Palette.c(0xFF141A23), Palette.c(0xFF2A3240), 20f));
        root.setPadding(dp(18), dp(18), dp(18), dp(18));
        root.setClipChildren(false);
        root.setClipToPadding(false);   // 主按钮已改为内部渐变，留着这行防将来加阴影再被裁

        // ---------- 头部：当前模型的字母徽标 + 名字 + 厂商，右上角关闭 ----------
        FrameLayout headWrap = new FrameLayout(this);
        root.addView(headWrap, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        String cur = Settings.effectiveModel(settings);
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
        name.setTextColor(Palette.c(0xFFEDF3FC));
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(name);

        TextView vendor = new TextView(this);
        vendor.setText(settings.isConfigured()
                ? shortHost(settings.baseUrl) + " · 选中后对下一条提问生效"
                : "还没配 API Key，先去完整设置里填好");
        vendor.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        vendor.setTextColor(Palette.c(0xFF7F8C9F));
        vendor.setSingleLine(true);
        vendor.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        vlp.topMargin = dp(4);
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
                        new int[]{Palette.c(0x805B93FF), Palette.c(0x332F6BE8), 0x00000000});
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
        secTitle.setTextColor(Palette.c(0xFF8FA0B5));
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

        // ---------- 底部：一条通栏主按钮，比摆两三个小胶囊利落 ----------
        TextView settingsBtn = sheetPrimary("\u2699 完整设置 · 地址 / Key / 提示词");
        settingsBtn.setOnClickListener(v -> {
            d.dismiss();
            startActivityForResult(new Intent(MainActivity.this, SettingsActivity.class), 100);
        });
        LinearLayout.LayoutParams seplp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        seplp.topMargin = dp(14);
        listWrap.addView(sheetSeparator(), seplp);

        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        flp.topMargin = dp(13);
        listWrap.addView(settingsBtn, flp);

        rebuildModelList(composeModelList(lastServerModels), Settings.effectiveModel(settings),
                settings.isConfigured() ? "正在拉取服务器模型列表…" : null);

        if (settings.isConfigured()) fetchModelsForSheet();

        d.setContentView(root);
        d.show();
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            // 宽度固定 90%，高度交给内容（列表自己封顶），不再固定 86% 屏高
            w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.9f),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
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

    /** 模型属于哪家内置服务商；不在内置表里就是用户自己加的 */
    private String providerOf(String model) {
        if (model == null) return "自定义";
        for (String[] p : MODEL_PROVIDERS) {
            for (int i = 2; i < p.length; i++) {
                if (p[i].equals(model)) return p[0];
            }
        }
        return "自定义";
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
            Toast.makeText(this, "还没填 API Key · 点下面「完整设置」配好就能拉取",
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
                    rebuildModelList(composeModelList(models), Settings.effectiveModel(settings),
                            n == 0 ? "服务器没返回可用模型，先用内置候选" : null);
                    Toast.makeText(MainActivity.this,
                            n == 0 ? "服务器没返回模型" : "已更新 " + n + " 个模型",
                            Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    resetRefreshState();
                    rebuildModelList(composeModelList(lastServerModels),
                            Settings.effectiveModel(settings), "拉取失败 · " + message);
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
        String cur = Settings.effectiveModel(settings);
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
        // 内置 DeepSeek 模型只在地址确实指向 DeepSeek 时才补进来，
        // 否则换了服务商，列表里还混着 deepseek-chat，切过去必然 404
        String nb = normBase(settings.baseUrl);
        if (nb.isEmpty() || nb.contains("deepseek")) {
            for (String m : BUILTIN_MODELS) set.add(m);
        }
        return new java.util.ArrayList<>(set);
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
        for (String m : models) addModelRow(m, m != null && m.equals(current));
        addCustomModelEntryRow();
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
        String prov = providerOf(name);
        sub.setText("自定义".equals(prov) ? "自己添加 · 长按删除" : prov);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        sub.setTextColor(custom ? Palette.c(0xFF7FB0FF) : Palette.c(0xFF6F7C8F));
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

        if (custom) row.setOnLongClickListener(v -> {
            confirmRemoveModel(name);
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
        bg.addState(new int[]{}, Ui.box(this, Palette.c(0x0F5B93FF), Palette.c(0x2E5B93FF), 14f, 1f));
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
        modelListBox.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(MODEL_ROW_H)));
    }

    /** 内置服务商：{显示名, OpenAI 兼容地址, 模型…}，地址都支持 /chat/completions */
    private static final String[][] MODEL_PROVIDERS = {
            {"DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", "deepseek-reasoner"},
            {"通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                    "qwen-max", "qwen-plus", "qwen-turbo"},
            {"Kimi", "https://api.moonshot.cn/v1",
                    "moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k"},
            {"智谱 GLM", "https://open.bigmodel.cn/api/paas/v4",
                    "glm-4-plus", "glm-4-flash", "glm-4-air"},
            {"OpenAI", "https://api.openai.com/v1", "gpt-4o-mini", "gpt-4o", "gpt-4.1-mini"},
            {"硅基流动", "https://api.siliconflow.cn/v1",
                    "deepseek-ai/DeepSeek-V3", "Qwen/Qwen2.5-72B-Instruct"},
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

    /** 某个服务商下的模型名 */
    private String[] modelsOf(int idx) {
        String[] p = MODEL_PROVIDERS[idx];
        String[] out = new String[Math.max(0, p.length - 2)];
        System.arraycopy(p, 2, out, 0, out.length);
        return out;
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
        c.setPadding(dp(12), dp(10), dp(12), dp(10));

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
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.roundRect(this, Palette.c(0xFF141A23), Palette.c(0xFF2A3240), 20f));
        root.setPadding(dp(18), dp(18), dp(18), dp(18));
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
        final String[] mdl = new String[]{modelsOf(sel[0])[0]};
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

        // 1) 服务商：2 列网格，一屏看全 6 家，不用再点开二级对话框
        body.addView(sheetLabel("服务商"), labelLp(true));
        for (int base = 0; base < MODEL_PROVIDERS.length; base += 2) {
            LinearLayout rowLine = new LinearLayout(this);
            rowLine.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rllp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (base > 0) rllp.topMargin = dp(8);
            for (int k = 0; k < 2; k++) {
                int idx = base + k;
                if (idx >= MODEL_PROVIDERS.length) {
                    View fill = new View(this);
                    LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(0, 1, 1f);
                    if (k == 0) flp.rightMargin = dp(8);
                    rowLine.addView(fill, flp);
                    continue;
                }
                final int fi = idx;
                LinearLayout card = providerCard(idx);
                cardViews[idx] = card;
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                if (k == 0) clp.rightMargin = dp(8);
                rowLine.addView(card, clp);
            }
            body.addView(rowLine, rllp);
        }

        // 2) 模型：横向 chip，点一下就选中；数量多也不会把弹窗顶长
        body.addView(sheetLabel("模型"), labelLp(false));
        chipBox.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setClipToPadding(false);
        hsv.addView(chipBox, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        body.addView(hsv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 3) 自定义模型名（可选）
        body.addView(sheetLabel("列表里没有？直接填模型名"), labelLp(false));
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
        final Runnable[] refresh = new Runnable[1];
        final Runnable renderChips = new Runnable() {
            @Override
            public void run() {
                chipBox.removeAllViews();
                String[] ms = modelsOf(sel[0]);
                for (int i = 0; i < ms.length; i++) {
                    final String m = ms[i];
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
                mdl[0] = modelsOf(fi)[0];
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
                    Toast.makeText(MainActivity.this, "先选一个模型", Toast.LENGTH_SHORT).show();
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
                rebuildModelList(composeModelList(null), Settings.effectiveModel(settings), null);
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

        d.setContentView(root);
        d.show();
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            // 铺满：高度按屏幕比例固定，字段多就在弹窗内部滚动
            w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.92f),
                    (int) (getResources().getDisplayMetrics().heightPixels * 0.86f));
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    /** 长按自定义模型：删掉它（不影响当前正在用的设置） */
    private void confirmRemoveModel(final String name) {
        new NebAlert.Builder(this)
                .setTitle("删除自定义模型")
                .setMessage("把「" + name + "」从列表里移除？\n只是不再显示，随时可以重新添加。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dd, w) -> {
                    settings.removeCustomModel(name);
                    settings.save(this);
                    rebuildModelList(composeModelList(lastServerModels),
                            Settings.effectiveModel(settings), null);
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
        sub.setText("对下一条提问生效；支持推理的后端会收到 reasoning_effort，其余后端自动忽略");
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

        // 模型联动：重度/极度时把 deepseek-chat 换成 deepseek-reasoner，否则「强度」只是换个说法
        View line2 = new View(this);
        line2.setBackgroundColor(Palette.c(0x14FFFFFF));
        root.addView(line2, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)));

        LinearLayout autoRow = new LinearLayout(this);
        autoRow.setOrientation(LinearLayout.HORIZONTAL);
        autoRow.setGravity(Gravity.CENTER_VERTICAL);
        autoRow.setPadding(dp(4), dp(12), dp(4), dp(8));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView autoName = new TextView(this);
        autoName.setText("重度/极度切到推理模型");
        autoName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        autoName.setTextColor(Palette.c(0xFFDCE5F2));
        textCol.addView(autoName);

        TextView autoDesc = new TextView(this);
        final String baseModelName = Settings.baseModel(settings);
        final boolean dsChat = "deepseek-chat".equalsIgnoreCase(baseModelName);
        autoDesc.setText(dsChat
                ? "自动改用 deepseek-reasoner 真正开启思维链；工具照常可用"
                : "仅当模型是 deepseek-chat 时生效，当前为 " + baseModelName);
        autoDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        autoDesc.setTextColor(Palette.c(0xFF7F8C9F));
        autoDesc.setLineSpacing(dp(2), 1.25f);
        textCol.addView(autoDesc);

        autoRow.addView(textCol, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Switch autoSw = new Switch(this);
        styleSwitch(autoSw);
        autoSw.setChecked(settings.thinkAutoModel);
        autoSw.setOnCheckedChangeListener((btn, checked) -> {
            settings.thinkAutoModel = checked;
            settings.save(MainActivity.this);
            renderThinkBadge();
            updateSubtitle();
        });
        autoRow.addView(autoSw);
        root.addView(autoRow);

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
            boolean wasAuto = Settings.isAutoReasoner(settings);
            settings.thinkLevel = level;
            settings.save(MainActivity.this);
            renderThinkBadge();
            updateSubtitle();
            boolean nowAuto = Settings.isAutoReasoner(settings);
            if (!wasAuto && nowAuto) {
                Toast.makeText(this, "已切换模型到 deepseek-reasoner（自带思维链，工具照常可用）",
                        Toast.LENGTH_LONG).show();
            } else if (wasAuto && !nowAuto) {
                Toast.makeText(this, "已切回 " + Settings.baseModel(settings), Toast.LENGTH_SHORT).show();
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
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getX();
                    downY = ev.getY();
                    dragging = false;
                    decided = false;
                    break;
                case MotionEvent.ACTION_MOVE: {
                    float dx = ev.getX() - downX;
                    float dy = ev.getY() - downY;
                    if (!decided) {
                        if (dx < -slop && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                            decided = true;
                            dragging = true;
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
                    dragging = false;
                    decided = false;
                    if (was) {
                        listener.onRelease(ev.getX() - downX < -getWidth() * 0.3f);
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
