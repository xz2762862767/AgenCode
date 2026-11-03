package com.nebula.aichat.net;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.nebula.aichat.data.Message;
import com.nebula.aichat.data.Settings;
import com.nebula.aichat.data.WorkbenchStore;
import com.nebula.aichat.tool.ToolRegistry;
import com.nebula.aichat.util.JsonOpt;
import com.nebula.aichat.util.RunGuard;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

/**
 * OpenAI 兼容的 Chat Completions 客户端（支持 SSE 流式）。
 * 不依赖第三方网络库，手写解析，保证在受限环境下也能编译运行。
 *
 * 兼容性处理：
 * · 响应被 gzip 压缩时自动解压（部分网关/CDN 会无视 Accept-Encoding）
 * · 服务忽略 stream 参数、直接返回整段 JSON 时自动兜底解析
 * · 支持 delta.content / delta.reasoning_content / message.content / text 多种字段
 * · 返回 HTML 错误页时给出可读提示
 */
public class ChatClient {

    private static final String TAG = "ChatClient";
    private static final int MAX_RAW_KEEP = 20000;

    public interface Listener {
        void onStart();

        void onDelta(String piece);

        void onDone(String full);

        void onError(String message);

        /** 模型输出的一段推理内容（reasoning_content），用于「思考过程」折叠区 */
        default void onReasoning(String piece) {
        }

        /** 模型请求调用工具（name 工具名，argsJson 原始参数 JSON） */
        default void onToolCall(String name, String argsJson) {
        }

        /** 工具执行完成，result 为给模型看的结果文本 */
        default void onToolResult(String name, boolean ok, String result) {
        }

        /**
         * 工具轮次用尽（或卡在重复调用），即将进入「收尾模式」：
         * 摘掉 tools 再请求一次，让模型用文字交代进度和卡点。
         * UI 收到后可在这条回答下面挂一个「继续跑下一段」按钮。
         */
        default void onToolLimit(String why) {
        }

        /** 这一轮工具没拿到结果，正在自动换方式继续（不结束） */
        default void onToolRetryHint(String note) {
        }

        /** 第几轮工具调用（让用户看到进度，而不是对着一条不动的工具行干等） */
        default void onRoundHint(int round, int max) {
        }

        /** 单个工具还在跑（解包/打包可能几分钟），用来刷新「已用 N 秒」心跳 */
        default void onToolBeat(String name, long seconds) {
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled = false;
    private volatile HttpURLConnection conn;
    /** 部分网关不认识 reasoning_effort 会返回 400；按网关 host 记住，换网关会重新尝试 */
    private static final java.util.Set<String> thinkBlockedHosts =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /**
     * 取网关 host 作为「后端能力记忆」的键。
     *
     * <p>这些记忆必须跟着网关走而不是跟着进程走：同一个 App 里用户可能切好几个网关，
     * 用整进程一个布尔值会让「A 网关不支持」永久波及 B 网关，思考强度静默失效到杀进程为止。
     */
    private static String gatewayKey(Settings s) {
        String u = (s == null || s.baseUrl == null) ? "" : s.baseUrl.trim();
        if (u.isEmpty()) return "";
        try {
            java.net.URI uri = java.net.URI.create(u);
            String h = uri.getHost();
            if (h != null && !h.isEmpty()) return h.toLowerCase(java.util.Locale.ROOT);
        } catch (Exception ignored) {
        }
        return u.toLowerCase(java.util.Locale.ROOT);
    }
    /**
     * 服务端不接受 tools 参数时记下来，本次进程内不再下发。
     * 与模型类型无关：任何后端拒一次就关，避免每轮都白试一遍。
     */
    private static volatile boolean toolsDisabled = false;
    /** onStart 只回调一次（降级重试时不要重复） */
    private boolean startSent = false;

    /** 额外的系统提示（如启用中的技能），会拼接在用户设置之后 */
    public volatile String extraSystem = null;

    /** 工具注册表：为 null 表示本次对话不使用工具 */
    public volatile ToolRegistry tools = null;

    /** 一轮用户提问内最多允许多少轮工具调用，防止死循环 */
    /** 工具调用轮次上限：改 APK 这类任务十几步很常见，8 轮必然被掐断 */
    private static final int MAX_TOOL_ROUNDS = 28;
    /**
     * 停滞判定阈值：不是「总时长到点就砍」，而是「连续这么久没有任何实质进展才砍」。
     *
     * <p>原来的口径是总时长 4 分钟一刀切——改 APK 这种活（解包、改 smali、回编译、
     * 签名、装回设备），单是回编译就经常超过 4 分钟，于是每次都在关键节点被掐断，
     * 用户看到的就是「跑到一半就停了」，而且下次续跑又从零摸索一遍。
     * 现在改看「有没有进展」：只要有新产物、新输出，就一直让它跑下去。
     */
    private static final long STALL_MS = 120_000L;
    /** 兜底总时长：真碰上死循环的活，20 分钟后强制进入收尾（在此之前全靠续命继续跑） */
    private static final long HARD_MS = 1_500_000L;
    /**
     * 预算到点后的「续命」次数。
     *
     * <p>轮次或时间预算撞顶时先别收尾——把计数清零、重新计时，让它接着干，
     * 最多续这么多次。收尾对用户来说等于「任务失败」，续命才有机会一口气做完。
     */
    private static final int MAX_REVIVE = 2;
    /** 阶段小结后自动接着干的最大次数（不把「继续」两个字甩回给用户） */
    private static final int MAX_AUTO_RESUME = 1;
    /** 「只说不做」催办次数：模型光写字不动手时，催它动手 */
    private static final int MAX_NUDGE = 2;

    /** 收尾模式：摘掉 tools 参数，逼模型把进度/卡点用文字讲清楚 */
    private boolean summarizeMode = false;
    /** 同一批工具调用连续重复这么多次，就当作在原地打转 */
    private static final int MAX_SAME_CALL = 3;
    /**
     * 单个工具结果回传给模型时的最大长度。
     * 原来是 8000 字符（≈4000 token），一轮调 5 个工具就是 2 万 token 起步，
     * 而且这些内容在后续每轮工具调用里都会重复回传，这是「余额掉得比别家快」的头号原因。
     * 现在收到 4000 字符，并且保留头部 60% + 尾部 40%（报错和结论基本都在尾部）。
     */
    // 每次工具输出都进上下文、而且随后每轮都要跟着历史重发，
    // 4000 字一条在长任务里很快就撑爆窗口，还会把模型的注意力稀释掉。
    private static final int MAX_TOOL_RESULT = 2600;
    private int toolRound = 0;
    /**
     * 被 max_tokens 截断后的自动续写次数。
     * 长 smali/长代码很容易撞到 max_tokens，原来 App 不解析 finish_reason，
     * 把「说到一半被砍断」当成正常结束，用户看到的就是「一堆英文代码然后突然停了」。
     */
    private static final int MAX_CONTINUE = 4;
    private int contCount = 0;
    /** 思考把输出额度烧光后的补救次数（只救一次，避免反复烧 token） */
    private int thinkRetry = 0;
    /**
     * 上一次请求把额度全烧在思考上、正文为空 → 本次请求强制关掉思考强度。
     *
     * <p>关三样：reasoning_effort 参数、思考提示词、max_tokens 放大。
     * 只是不发参数是不够的——真正把额度吃光的是我们自己注入的「列出三种思路、找反例」
     * 那段提示词，所以必须一起停掉，否则重试等于再烧一次。
     */
    private boolean thinkOff = false;
    /** 续写前已经输出的内容，续写成功后要拼回去，否则界面上只剩后半段 */
    private String contPrefix = "";
    /** 工具结果上下文预算，撞到「上下文超限」时会自动下调 */
    private int ctxBudget = MAX_TOOL_CONTEXT_CHARS;
    /** 上下文超限收缩档位（0=正常，1=压工具结果，2=再丢旧历史） */
    private int ctxShrink = 0;
    /** 大于 0 时只带最后 N 条历史，用于上下文超限时的紧急瘦身 */
    private int ctxHistoryKeep = -1;
    /**
     * 历史消息的总字符预算（中文约 1 字≈0.7 token）。
     * 原来 ctxHistoryKeep=-1 意味着「每次请求都把全部历史重发一遍」：
     * 聊到第 30 轮，第 30 次请求光历史就是几万 token，累计是 O(n²)，
     * 这才是「同样问法在这儿比别家掉得快」的真正根因，不是模型贵。
     */
    private static final int MAX_HISTORY_CHARS = 18000;
    private int historyBudget = MAX_HISTORY_CHARS;

    /**
     * 单条消息硬上限。
     *
     * <p>历史窗口原来是「最后一条无条件保留」：用户粘一份上万行的日志或源码，
     * 这一条本身就把模型的上下文吃光了，请求必然 400，重试多少次还是同一条。
     * 所以每条消息在进请求前先按这个上限截断（头尾都留），
     * 保证任何单条消息都不可能自己把上下文撑爆。
     */
    private static final int MAX_SINGLE_MSG_CHARS = 9000;
    /** 上下文收缩时单条上限跟着降，兜住「截了还是超」的极端情况 */
    private int singleMsgLimit = MAX_SINGLE_MSG_CHARS;
    /** 整次请求（历史 + 工具结果）的字符安全线，超了就在发出去之前先瘦身 */
    private static final int MAX_REQUEST_CHARS = 90000;

    // ================= 用量统计 =================
    // 之前界面上那个「5121/90000」其实是「字符数 / 硬编码字符上限」，却被当成 token 展示，
    // 所以永远对不上服务端的账单。现在一律以接口回报的 usage 为准；
    // 只有拿不到真值时才退回估算，且界面会标上 ≈ 让人知道是估算。

    /** 服务端没给上下文长度、模型名也推断不出来时的保底窗口（token） */
    public static final int DEFAULT_CTX_TOKENS = 128000;

    /** 上一次请求服务端回报的真实输入 token；0 = 还没拿到 */
    private volatile int lastPromptTokens = 0;
    /** 上一次请求服务端回报的真实输出 token */
    private volatile int lastCompletionTokens = 0;
    private volatile boolean lastUsageReal = false;
    /** 拿到真值那次的历史条数：历史一变真值就过期，得改回估算 */
    private volatile int lastUsageHistorySize = -1;
    /** 上次实际发出的消息条数（含 system 消息） */
    private volatile int lastSentCount = 0;
    /** 上次实际发出的估算 token（拿不到真值时用它） */
    private volatile int lastSentEstTokens = 0;
    /** 被窗口裁掉、没随请求发出的历史条数 */
    private volatile int lastDroppedCount = 0;
    /** 服务端不认 stream_options：记下来，下次不再带，免得一直 400 */
    private volatile boolean usageOff = false;
    /** 最近一次请求用的设置，供统计按模型推上下文窗口 */
    private volatile Settings lastSettings;
    /** 最近一次请求用的历史列表（同一个 List 对象），用来判断真值是不是还属于当前会话 */
    private volatile java.util.List<Message> lastHistoryRef;

    /** 整轮任务起点，用于兜底总时长 */
    private long runStartAt = 0L;
    /** 上次「有实质进展」的时间戳（拿到新结果、产出新文件都算），停滞判定用 */
    private long lastProgressAt = 0L;
    /** 预算到点后已经续命几次 */
    private int reviveCount = 0;
    /** 还剩几次「小结完自动接着干」 */
    private int autoResume = MAX_AUTO_RESUME;
    /** 已经催办过几次「只说不做」 */
    private int nudgeCount = 0;
    /** 兜底总时长上限，每次续命都会往后延一截 */
    private long hardLimitMs = HARD_MS;
    /** 本次执行中摸到过的产物/工作路径，收尾时汇总成「现场」留给下一次继续用 */
    private final Set<String> artifacts = new LinkedHashSet<>();
    /** 上一次动手留下的现场（路径 + 命令），用户手动说「继续」时注入回去 */
    private volatile String lastWorkbench = "";
    /**
     * 最后几次动手的「工具名 + 入参 + 结果首行」，最多 {@link #MAX_LAST_CALLS} 条。
     *
     * <p>路径清单只告诉模型东西在哪，这一行才告诉它上一步干了什么、拿回了什么。
     * 没有这个，续跑时它只能靠 ls 把状态重新猜一遍。
     */
    private final java.util.List<String> lastCalls = new java.util.ArrayList<>();
    private static final int MAX_LAST_CALLS = 3;
    /** 被「停止」掐断时正在跑的那个工具（名字 + 入参摘要），续跑时明确告诉模型断在哪 */
    private volatile String interruptAt = "";
    /** 中断时刻；0 表示上一次没有被中断过。只用一次，带进现场后立刻清零 */
    private volatile long interruptTs = 0L;
    /** 停止后多久之内再发消息，默认当作「接着刚才那件事」处理，不必等用户说「继续」 */
    private static final long RESUME_WINDOW_MS = 30 * 60 * 1000L;
    /** 正在执行的工具描述，供 cancel() 记录中断点 */
    private volatile String currentTool = "";

    /**
     * 应用上下文，只用来给 {@link WorkbenchStore} 落盘，不参与任何业务逻辑。
     * 由 MainActivity 启动时挂上；没挂上时落盘静默跳过，功能降级但不崩。
     */
    private static Context appCtx;

    /** 挂上 application context，任务现场才能扛住进程回收 */
    public static void attach(Context c) {
        if (c != null) appCtx = c.getApplicationContext();
    }

    /** 把当前现场写盘：进程被杀之后还能接着做 */
    private void persistWorkbench() {
        if (appCtx == null) return;
        WorkbenchStore.save(appCtx, lastWorkbench, lastCalls, interruptAt, interruptTs);
    }

    /**
     * 连接代际号。每次进入 doSend 自增，用来解决「老调用栈退货时把新连接杀掉」的问题。
     *
     * <p>工具轮次是一轮接一轮递归推进的，外层帧在 finally 里会去 disconnect 当时看到的 conn。
     * 一旦某个后进来的请求（新线程）已经换上了新连接，外层那次 disconnect 就会把别人的连接剁掉，
     * 表现为「明明还在跑，突然报网络错误」。带上代际号后，只有自己这一代还活着才允许动 conn。
     */
    private long connGen = 0;

    /**
     * 当前这串递归已经叠了多少层。
     *
     * <p>工具轮次每推进一轮就递归调用一次 doSend，续命又把轮次清零重来，
     * 理论上能叠到几百层，后台线程 1MB 栈撑不住会直接 StackOverflow 崩掉。
     * 到阈值就换一个干净线程从零栈接着跑，长任务照样能继续，只是先「换个地基」。
     */
    private int chainDepth = 0;
    private static final int MAX_CHAIN_DEPTH = 60;
    private String lastCallSig = "";
    private int sameCallRepeat = 0;
    /** 最近这么多次工具调用的签名，用来抓 A→B→A 这种交替打转 */
    private static final int SIG_WINDOW = 12;
    private final java.util.ArrayDeque<String> recentSigs = new java.util.ArrayDeque<>();
    /** 工具轮次中产生的临时消息（assistant.tool_calls / tool 结果），不写入历史 */
    private final List<JSONObject> toolMsgs = new ArrayList<>();

    // ===== 「没拿到结果就不许收工」相关状态 =====
    /** 同一工具连续失败/无结果的次数，到阈值就临时禁用，避免原地打转 */
    private final Map<String, Integer> toolFailCount = new HashMap<>();
    private final Set<String> blockedTools = new HashSet<>();
    /** 本轮（一次工具调用批次）里没有拿到有效结果的调用数 */
    private int noResultCalls = 0;
    /** 连续多少轮工具调用全部没有结果 */
    private int emptyRoundStreak = 0;
    private int noProgressStreak = 0;
    private String lastRoundFp = "";
    /** 失败记录，用于最后兜底写一段能看的结论 */
    private final List<String> failedNotes = new ArrayList<>();
    /** 同一工具连续失败这么多次就临时摘掉它 */
    private static final int EMPTY_BLOCK_AFTER = 2;
    /** 连续这么多轮全部无结果，才转入收尾模式 */
    private static final int MAX_EMPTY_ROUNDS = 6;
    /** 连续几轮工具输出与上一轮逐字相同，就是原地打转，别再把同样的请求发一遍 */
    private static final int MAX_NO_PROGRESS = 2;

    public boolean isRunning() {
        return !cancelled;
    }

    public void cancel() {
        cancelled = true;
        // 记下断在哪：续跑时要明确告诉模型这一步没跑完，先查有没有半成品。
        // 否则它只知道「在某个目录里干过活」，会把改了一半的文件当没动过。
        if (!currentTool.isEmpty()) {
            interruptAt = currentTool;
            interruptTs = System.currentTimeMillis();
            currentTool = "";
            // 立刻落盘：用户很可能接着就去后台待着，App 随时可能被回收
            persistWorkbench();
        }
        HttpURLConnection c = conn;
        if (c != null) {
            try {
                c.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    private void post(Runnable r) {
        main.post(r);
    }

    /** 任务摘要：取最后一条用户消息，落盘用于"上次任务没跑完"的提示 */
    private static String summarizeTask(List<Message> history) {
        if (history == null || history.isEmpty()) return "";
        for (int i = history.size() - 1; i >= 0; i--) {
            Message m = history.get(i);
            if (m == null || m.content == null) continue;
            if (!Message.ROLE_USER.equals(m.role)) continue;
            String s = m.content;
            // 附件正文很长，摘要只要人话部分
            int cut = s.indexOf("\n\n【附件");
            if (cut > 0) s = s.substring(0, cut);
            s = s.replace('\n', ' ').trim();
            if (s.isEmpty()) continue;
            return s.length() > 60 ? s.substring(0, 60) + "…" : s;
        }
        return "";
    }

    public void send(final Settings settings, final List<Message> history, final Listener listener) {
        cancelled = false;
        toolRound = 0;
        contCount = 0;
        thinkRetry = 0;
        thinkOff = false;
        contPrefix = "";
        ctxBudget = MAX_TOOL_CONTEXT_CHARS;
        ctxShrink = 0;
        ctxHistoryKeep = -1;
        historyBudget = MAX_HISTORY_CHARS;
        singleMsgLimit = MAX_SINGLE_MSG_CHARS;
        runStartAt = 0L;
        lastProgressAt = 0L;
        hardLimitMs = HARD_MS;
        artifacts.clear();
        chainDepth = 0;
        reviveCount = 0;
        autoResume = MAX_AUTO_RESUME;
        nudgeCount = 0;
        summarizeMode = false;
        lastCallSig = "";
        sameCallRepeat = 0;
        recentSigs.clear();
        toolMsgs.clear();
        // 把上一次干活留下的现场带回去，避免「点了停止再继续」变成重新开荒。
        //
        // 触发条件有两条，命中任一即可：
        //   1. 上一次是被「停止」掐断的，且间隔在 RESUME_WINDOW_MS 之内 —— 不必等用户说「继续」；
        //   2. 用户这次的话里有「继续 / 接着 / 没做完」这类词。
        // 原来只认第 2 条，用户随手打一句「再试一次」就退化成了白纸，
        // 于是重新解包 200MB、重新找文件，看着像从头再来一遍。
        // 进程被回收过的话，上面这些内存字段全是空的，用户说「继续」也只能拿到白纸。
        // 所以内存彻底没线索时先看磁盘：上次跑到哪、动过哪些文件都还存在那儿。
        // 只在内存全空时才读，避免旧存档盖掉当前这一轮正在积累的现场。
        if (lastWorkbench.isEmpty() && lastCalls.isEmpty() && interruptTs == 0L) {
            WorkbenchStore.Snapshot snap = WorkbenchStore.load(appCtx);
            if (snap != null && !snap.isEmpty()) {
                lastWorkbench = snap.paths;
                java.util.List<String> restored = WorkbenchStore.splitCalls(snap.calls);
                lastCalls.clear();
                lastCalls.addAll(restored);
                interruptAt = snap.tool;
                interruptTs = snap.interruptTs;
                com.nebula.aichat.util.CrashLog.d("resume",
                        "从磁盘恢复任务现场：" + restored.size() + " 次动手，路径 "
                                + (snap.paths.isEmpty() ? "无" : "有"));
            }
        }

        boolean recentInterrupt = interruptTs > 0L
                && System.currentTimeMillis() - interruptTs < RESUME_WINDOW_MS;
        boolean saysContinue = wantsContinue(history);
        // 只要手里有任何一条线索就接上：路径清单可能一条都没抓到（AI 用的是相对路径、
        // 或在脚本里 cd 过去做），但「断在哪个工具 + 最后几次动手」照样能救命。
        boolean hasTrace = !lastWorkbench.isEmpty() || !lastCalls.isEmpty();
        if (hasTrace && (recentInterrupt || saysContinue)) {
            try {
                StringBuilder wb = new StringBuilder();
                wb.append("【上次执行的现场】这件活之前被中断过，以下是上次干到的地方，")
                        .append("直接接着用，不要重头再来。\n");
                if (!interruptAt.isEmpty()) {
                    wb.append("\n中断时正在执行：").append(interruptAt)
                            .append("\n这一步没跑完。重新执行前先确认它有没有留下半成品")
                            .append("（半个产物、改了一半的文件），别当它没发生过。\n");
                }
                if (!lastWorkbench.isEmpty()) {
                    wb.append("\n用过的工作路径（解包目录 / 改动过的文件 / 产物位置）：\n")
                            .append(lastWorkbench).append('\n');
                }
                if (!lastCalls.isEmpty()) {
                    wb.append("\n最后几次动手（命令与结果首行）：\n");
                    for (String c : lastCalls) wb.append(c).append('\n');
                }
                wb.append("\n先用一条命令确认上面这些路径现在还在，再从中断处往下做；")
                        .append("确认不在了才重新创建。已经成功过的步骤不要重复执行。\n")
                        .append("如果用户这条消息其实是在说一件新的事，忽略以上内容，按新任务走。");
                if (recentInterrupt && !saysContinue) {
                    wb.append("\n（本次用户没有明说「继续」，是系统按时间判断把现场接上的。）");
                }
                toolMsgs.add(new JSONObject().put("role", "user").put("content", wb.toString()));
            } catch (Exception ignored) {
            }
        }
        // 现场只能用一次：带上之后立刻作废，避免几轮之后还在拿旧现场干扰新任务。
        interruptTs = 0L;
        interruptAt = "";
        persistWorkbench();
        toolFailCount.clear();
        blockedTools.clear();
        failedNotes.clear();
        noResultCalls = 0;
        emptyRoundStreak = 0;
        noProgressStreak = 0;
        lastRoundFp = "";
        // 任务现场落盘：这一轮从开始到结束都会被 RunGuard 记录。
        // 进程若在这个过程中被系统 SIGKILL（LMK 回收），finally 不会执行，
        // 记录残留下来，下次启动就能明确告知用户"上次任务是被系统终止的"，
        // 而不是让任务静默消失、让人以为是自己点错了。
        RunGuard.begin(summarizeTask(history));
        Thread t = new Thread(() -> {
            try {
                doSend(settings, history, listener);
            } catch (Throwable ignored) {
                // 线程顶层异常无需再抛：doSend 内部已经把错误转成 onError
            } finally {
                RunGuard.end();
            }
        }, "chat-client");
        t.setDaemon(true);
        t.start();
    }

    /** 流式解析过程中的累积状态 */
    private static class StreamState {
        final StringBuilder content = new StringBuilder();
        final StringBuilder reasoning = new StringBuilder();
        final StringBuilder raw = new StringBuilder();
        boolean sawSse = false;
        boolean finished = false;
        /** 服务端给的结束原因：stop=正常说完 / length=撞到 max_tokens 被截断 / tool_calls=要调工具 */
        String finishReason = "";
        /** 流式 tool_calls 按 index 累积（TreeMap 保证顺序） */
        final Map<Integer, JSONObject> toolCalls = new TreeMap<>();

        void keepRaw(String line) {
            if (raw.length() < MAX_RAW_KEEP) {
                raw.append(line).append('\n');
            }
        }
    }

    private void doSend(Settings s, List<Message> history, Listener l) {
        final long myGen = ++connGen;
        if (s == null || !s.isConfigured()) {
            post(() -> l.onError("还没有配置 API Key。\n请点右上角 ⚙ 进入设置填写密钥。"));
            return;
        }

        final String url = s.endpoint();
        if (!startSent) {
            startSent = true;
            post(l::onStart);
        }

        try {
            final String curModel = pickModel(s);
            JSONObject body = buildBody(s, history);
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);

            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            conn = c;
            c.setRequestMethod("POST");
            c.setConnectTimeout(30000);
            c.setReadTimeout(180000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Accept", bodyStreaming(s) ? "text/event-stream" : "application/json");
            // 明确要求不压缩；万一服务端仍然压缩，下面 wrap() 会按魔数自动解压
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setRequestProperty("Authorization", "Bearer " + s.apiKey.trim());
            c.setRequestProperty("User-Agent", "AgenCode/1.0 (Android)");

            try (OutputStream os = c.getOutputStream()) {
                os.write(payload);
                os.flush();
            }

            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                String err = readAll(openError(c));
                final String gwKey = gatewayKey(s);
                // 该模型/网关不接受工具调用 → 永久关掉工具后重试一次
                boolean toolsSent = tools != null && tools.hasTools() && !summarizeMode && !toolsDisabled;
                if (toolsSent && isToolsRejected(err)) {
                    toolsDisabled = true;
                    Log.w(TAG, "该模型不接受 tools，关掉工具重试");
                    try {
                        c.disconnect();
                    } catch (Exception ignored) {
                    }
                    conn = null;
                    if (!cancelled) doSend(s, history, l);
                    return;
                }
                // 网关不认 stream_options → 去掉重试一次。拿不到 usage 只是统计退回估算，
                // 绝不能因为这个可选字段把整次请求搞失败。
                if (!usageOff && bodyStreaming(s) && !summarizeMode
                        && err != null && err.toLowerCase().contains("stream_option")) {
                    usageOff = true;
                    Log.w(TAG, "网关不认 stream_options，去掉后重试，用量统计改为估算");
                    try {
                        c.disconnect();
                    } catch (Exception ignored) {
                    }
                    conn = null;
                    if (!cancelled) doSend(s, history, l);
                    return;
                }
                // 后端不认识思考参数 → 去掉后静默重试一次，保证「思考强度」不会把请求搞挂。
                // thinkOff 时必须一起算进去：那时发的是强制救援值（minimal），
                // 而不是 reasoningEffort(s.thinkLevel)，只按档位判断会漏掉它，
                // 结果就是救援请求撞上 400 后不去重试，直接把错误甩给用户。
                boolean thinkSent = !thinkBlockedHosts.contains(gwKey)
                        && (thinkOff || Settings.reasoningEffort(s.thinkLevel) != null);
                if (thinkSent && isParamRejected(err)) {
                    Log.w(TAG, "后端不接受 reasoning_effort，去除后重试（仅对本网关记忆）");
                    thinkBlockedHosts.add(gwKey);
                    try {
                        c.disconnect();
                    } catch (Exception ignored) {
                    }
                    conn = null;
                    if (!cancelled) doSend(s, history, l);
                    return;
                }
                // 上下文超限：长任务的必然终点（历史 + 几十轮工具结果 + 长正文）。
                // 不处理就会直接报错失败（用户看到的就是「改到一半突然崩了」），
                // 这里自动分两档瘦身重试，让它能把活干完。
                if (isContextOverflow(err) && ctxShrink < 3) {
                    ctxShrink++;
                    if (ctxShrink == 1) {
                        ctxBudget = 12000;
                        historyBudget = 12000;
                    } else if (ctxShrink == 2) {
                        ctxBudget = 2000;
                        historyBudget = 6000;
                        ctxHistoryKeep = 4;
                        singleMsgLimit = 6000;
                    } else {
                        // 最后一档：历史只带 2 条、工具结果只留最近一条、单条再砍一半。
                        // 到这里还超，问题就出在「单条消息本身过大」，减少条数救不了，必须砍单条。
                        ctxBudget = 800;
                        historyBudget = 3000;
                        ctxHistoryKeep = 2;
                        singleMsgLimit = 3000;
                    }
                    trimToolContext();
                    Log.w(TAG, "上下文超限，收缩到第 " + ctxShrink + " 档重试");
                    try {
                        c.disconnect();
                    } catch (Exception ignored) {
                    }
                    conn = null;
                    if (!cancelled) {
                        final int n = ctxShrink;
                        post(() -> l.onToolRetryHint("上下文快满了，正在精简后继续（第 " + n + " 档）…"));
                        doSend(s, history, l);
                    }
                    return;
                }
                // max_tokens 设得比模型上限还大：临时降到保守值重试
                if (isMaxTokensTooLarge(err) && s.maxTokens > 4096) {
                    s.maxTokens = 4096;
                    Log.w(TAG, "max_tokens 超出模型上限，降到 4096 重试");
                    try {
                        c.disconnect();
                    } catch (Exception ignored) {
                    }
                    conn = null;
                    if (!cancelled) doSend(s, history, l);
                    return;
                }
                final String msg = friendlyError(code, err);
                if (!cancelled) {
                    post(() -> l.onError(msg));
                }
                return;
            }

            InputStream in = wrap(c.getInputStream());

            if (!bodyStreaming(s)) {
                String text = readWhole(in);
                if (!cancelled) {
                    post(() -> {
                        l.onDelta(text);
                        l.onDone(text);
                    });
                }
                return;
            }

            // ---- 流式 ----
            StreamState st = readStream(in, l);

            // 模型请求调用工具 → 执行后带着结果再请求一次
            // 思考超长 = 额度吃紧。本轮后续请求自动关掉思考强度：
            // 长任务（写项目、多轮工具）每一轮都烧两万字思考，迟早把额度吃光，
            // 表现出来就是「写到一半突然停了 / 思考完没正文」。宁可这次想浅一点，
            // 也要把任务跑完——中途停下的代价远大于少想几层。
            if (st.reasoning.length() > 8000 && !thinkOff) {
                thinkOff = true;
                Log.w(TAG, "思考长度 " + st.reasoning.length() + "，本轮后续请求自动关闭思考强度");
                post(() -> l.onToolRetryHint("思考过长，后续步骤自动降低思考强度以保证任务跑完…"));
            }
            if (!st.toolCalls.isEmpty() && !cancelled) {
                runToolRound(s, history, l, st);
                return;
            }
            String text = st.content.toString();

            // 服务端忽略了 stream，返回的是整段 JSON
            if (text.isEmpty()) {
                String raw = st.raw.toString().trim();
                if (!raw.isEmpty()) {
                    if (looksLikeHtml(raw)) {
                        if (!cancelled) {
                            post(() -> l.onError("接口返回的是网页（HTML），不是接口数据。\n请检查 Base URL，通常应形如 https://api.deepseek.com/v1"));
                        }
                        return;
                    }
                    String fromJson = tryParseWholeJson(raw);
                    if (fromJson != null) text = fromJson;
                }
            }

            // 推理模型把输出额度全烧在思考上、正文一个字都没有。
            // 修复前这里会把思考原文当正文塞满屏幕；现在不冒充答案，
            // 但也不能直接判死——先自动救一次：明确要求它停止长篇推理，直接产出正文。
            if (text.isEmpty() && st.reasoning.length() > 0) {
                final int thinkLen = st.reasoning.length();
                // 第一层：明确要求它别再长篇推理，直接产出正文（多数情况这一招就够）
                if (!cancelled && thinkRetry < 1) {
                    thinkRetry++;
                    // 关键：不只是催它快写，而是把「思考强度」整体关掉再发一次。
                    // 上一轮烧掉两万多字思考，几乎都是被高强度提示词推着去罗列思路，
                    // 不关掉就会原样再烧一遍。
                    thinkOff = true;
                    try {
                        toolMsgs.add(new JSONObject().put("role", "user").put("content",
                                "【系统】你上一轮把全部输出额度都用在了内部思考上（思考 "
                                        + thinkLen + " 字），没有产出任何正文，用户什么都没看到。\n"
                                        + "已为你关闭深度思考要求。这一次：**不要写任何内部推理过程**，"
                                        + "直接产出可交付的内容——要动手就立刻调用工具，"
                                        + "正文第一个字就是结论或结果，写短一点也比空着好。"));
                    } catch (Exception ignored) {
                    }
                    post(() -> l.onToolRetryHint("模型只思考没写正文，正在要求它直接作答…"));
                    doSend(s, history, l);
                    return;
                }
                post(() -> l.onError("模型思考了 " + thinkLen
                        + " 字，但没有生成正文回答（思考完直接结束了）。\n"
                        + "已自动关掉思考强度重试过一次仍没出来，这次不再重试——"
                        + "再试一次大概率又是几万字思考，白烧 token。\n"
                        + "建议：把「思考强度」调到中度或轻度；如果用「极度」，"
                        + "碰到本身带深度思考的模型（如各类 reasoner）会叠加放大，必然把额度吃光。\n"
                        + "思考原文在「思考过程」折叠区里，可以点开看。"));
                return;
            }

            if (text.isEmpty()) {
                if (!cancelled) {
                    if (toolRound > 0 || !toolMsgs.isEmpty()) {
                        // 跑过工具却一句话都不说：不能就这么空着收场
                        localWrapUp(l, "模型在工具执行完之后没有返回任何内容");
                    } else {
                        post(() -> l.onError("模型没有返回任何内容。\n可能是这个模型不支持对话接口，或服务端返回格式不兼容。"));
                    }
                }
                return;
            }

            if (looksGarbled(text)) {
                if (!cancelled) {
                    post(() -> l.onError("回答出现乱码：服务器返回的不是 UTF-8 编码。\n"
                            + "请到设置里关闭「流式输出」后重试（关闭后会自动识别 GBK 编码），"
                            + "或把接口地址换成服务商的标准 OpenAI 兼容地址。"));
                }
                return;
            }

            // 有些网关干脆不返回 finish_reason，此时按内容形状兜底：
            // 代码围栏成对出现，出现奇数个说明有一段代码没写完就被砍断了。
            boolean cutByShape = st.finishReason.isEmpty() && looksTruncated(text);
            // ---- 撞到长度上限被截断：接着往下写，而不是把半截回答当成品交出去 ----
            if (("length".equals(st.finishReason) || cutByShape)
                    && !cancelled && contCount < MAX_CONTINUE) {
                contCount++;
                contPrefix = contPrefix + text;
                try {
                    toolMsgs.add(new JSONObject().put("role", "assistant").put("content", text));
                    toolMsgs.add(new JSONObject().put("role", "user").put("content",
                            "【系统】你上一条回答在输出中途被长度上限截断了（max_tokens="
                                    + s.maxTokens + " 用完），断在半句话/代码中间。\n"
                                    + "现在请**紧接着中断的位置继续写**：不要重复已经写过的内容，"
                                    + "不要重新解释一遍，不要加开场白，直接接着往下写。\n"
                                    + "如果你这次只是想把某段代码贴给我看，那没必要——"
                                    + "代码请直接写进文件（用工具调用），正文里只讲改了什么。"));
                } catch (Exception ignored) {
                }
                final int n = contCount;
                post(() -> l.onToolRetryHint("上一条被长度上限截断，正在接着往下写（第 " + n + " 次）…"));
                doSend(s, history, l);
                return;
            }
            if (!contPrefix.isEmpty()) {
                text = contPrefix + text;
                contPrefix = "";
            }

            // 收场之前先问一句：这次收场该不该拦？
            if (!cancelled && maybeContinueAfterText(s, history, l, text)) {
                return;
            }

            final String done = text;
            if (!cancelled) {
                post(() -> l.onDone(done));
            }
        } catch (Exception e) {
            if (cancelled) {
                return;
            }
            Log.w(TAG, "请求失败", e);
            final String msg = "网络请求失败：" + (e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage());
            post(() -> l.onError(msg));
        } finally {
            // 只有「自己这一代」还没被接管时才收拾连接：
            // 深栈换线程接着跑之后，老帧逐层返回时 conn 已经是别人的了，不能碰。
            if (connGen == myGen) {
                HttpURLConnection c = conn;
                if (c != null) c.disconnect();
                conn = null;
            }
        }
    }

    /**
     * 回答疑似被截断：代码围栏（```）数量为奇数，说明最后一段代码没闭合，
     * 基本可以确定是被长度上限砍断的。只在网关不给 finish_reason 时才用。
     */
    private static boolean looksTruncated(String text) {
        if (text == null || text.length() < 2000) return false;
        int fences = 0;
        int idx = 0;
        while (true) {
            idx = text.indexOf("```", idx);
            if (idx < 0) break;
            fences++;
            idx += 3;
        }
        return fences % 2 == 1;
    }

    /** 上下文/提示词超长（各家措辞不同，都归到这） */
    private static boolean isContextOverflow(String err) {
        if (err == null) return false;
        String e = err.toLowerCase(Locale.ROOT);
        if (isMaxTokensTooLarge(err)) return false;
        return e.contains("context length") || e.contains("context_length")
                || e.contains("maximum context") || e.contains("too many tokens")
                || e.contains("reduce the length") || e.contains("prompt is too long")
                || e.contains("input is too long") || e.contains("exceeds the maximum")
                || e.contains("context window");
    }

    /** max_tokens 超过模型允许的输出上限 */
    private static boolean isMaxTokensTooLarge(String err) {
        if (err == null) return false;
        String e = err.toLowerCase(Locale.ROOT);
        if (!e.contains("max_tokens")) return false;
        return e.contains("too large") || e.contains("too big") || e.contains("exceed")
                || e.contains("greater than") || e.contains("less than or equal");
    }

    /** 判断错误是不是「这个模型不存在/不支持」 */
    private static boolean isModelRejected(String err) {
        if (err == null) return false;
        String e = err.toLowerCase(Locale.ROOT);
        if (!e.contains("model")) return false;
        return e.contains("not found") || e.contains("not exist") || e.contains("does not exist")
                || e.contains("invalid") || e.contains("unsupported") || e.contains("unknown");
    }

    /** 判断 400 错误是否由不认识的参数导致 */
    private static boolean isParamRejected(String err) {
        if (err == null) return false;
        String e = err.toLowerCase(Locale.ROOT);
        if (!(e.contains("reasoning") || e.contains("unknown") || e.contains("unrecognized")
                || e.contains("unsupported") || e.contains("invalid"))) {
            return false;
        }
        return e.contains("reasoning_effort") || e.contains("reasoning")
                || e.contains("unknown parameter") || e.contains("unrecognized")
                || e.contains("unsupported parameter") || e.contains("extra fields");
    }

    /** 判断错误是否因为「该模型不接受工具调用」——是的话关掉工具重试 */
    private static boolean isToolsRejected(String err) {
        if (err == null) return false;
        String e = err.toLowerCase(Locale.ROOT);
        boolean mentions = e.contains("tool") || e.contains("function_call") || e.contains("functions")
                || e.contains("tool_choice");
        if (!mentions) return false;
        return e.contains("not support") || e.contains("unsupported") || e.contains("does not support")
                || e.contains("invalid") || e.contains("unrecognized") || e.contains("unknown")
                || e.contains("not allowed") || e.contains("must be") || e.contains("cannot");
    }

    /** 启用工具时强制走流式：非流式无法可靠拿到分片 tool_calls */
    private boolean bodyStreaming(Settings s) {
        return s.stream || (tools != null && tools.hasTools());
    }

    /** 实际使用的模型：用户选的是哪个就发哪个，不再自动替换成别的模型名 */
    private String pickModel(Settings s) {
        return Settings.baseModel(s);
    }

    /**
     * 工具上下文瘦身：一次提问里几十轮工具调用会让 toolMsgs 累积到几百 KB，
     * 请求体越来越大、每轮越来越慢，最后容易超时或被网关拒掉（表现为「跑很久没结果」）。
     * tool 消息必须与 assistant.tool_calls 成对，不能删，所以只把较早结果的正文换成占位。
     */
    private static final int MAX_TOOL_CONTEXT_CHARS = 18000;

    private void trimToolContext() {
        int total = 0;
        int kept = 0;
        for (int i = toolMsgs.size() - 1; i >= 0; i--) {
            JSONObject o = toolMsgs.get(i);
            if (o == null) continue;
            if (!"tool".equals(o.optString("role"))) continue;
            String c = o.optString("content", "");
            int len = c.length();
            // 最近 3 条一律原文；更早的只要还在预算内也原文
            if (kept < 3 || total + len <= ctxBudget) {
                total += len;
                kept++;
                continue;
            }
            if (c.startsWith("【较早结果已压缩")) continue;
            try {
                // 不要一刀切成一句占位：模型看不到细节就会把同一个工具重跑一遍，重跑比留着更贵。
                // 保留头 700 + 尾 200 字（报错信息、结尾结论通常在尾部），中间省略。
                String head;
                if (len > 900) {
                    String h = c.substring(0, 700);
                    if (Character.isHighSurrogate(h.charAt(h.length() - 1))) {
                        h = h.substring(0, h.length() - 1);
                    }
                    String t = c.substring(len - 200);
                    if (Character.isLowSurrogate(t.charAt(0))) t = t.substring(1);
                    head = h + "\n…（中间省略）…\n" + t;
                } else {
                    head = c;
                }
                o.put("content", "【较早结果已压缩，原 " + len + " 字】\n" + head
                        + "\n（这一步已完成，不要重复执行；要看完整内容就重新读取当前文件）");
            } catch (Exception ignored) {
            }
        }
    }

    /** 一条消息真正发给模型的文本（兼容旧会话的附件说明） */
    private String apiText(Message m) {
        if (m == null) return "";
        if (m.apiContent != null && !m.apiContent.trim().isEmpty()) return m.apiContent;
        if (m.attachNote != null && !m.attachNote.trim().isEmpty()) {
            // 旧会话：完整内容没存盘，但附件路径还在，续接上下文仍能找到文件
            return m.attachNote + "\n" + (m.content == null ? "" : m.content);
        }
        return m.content == null ? "" : m.content;
    }

    /**
     * 单条消息截断：超长就保留头 60% + 尾 40%，中间标注省略。
     * 头尾都留，是因为粘贴的内容通常「开头是代码和背景、结尾是报错」，掐哪一头都丢关键信息。
     */
    private String clampMsg(String text) {
        return clampMsg(text, singleMsgLimit);
    }

    private static String clampMsg(String text, int limit) {
        if (text == null) return "";
        if (limit <= 0 || text.length() <= limit) return text;
        int headLen = (int) (limit * 0.6);
        int tailLen = limit - headLen - 40;
        if (headLen < 0) headLen = 0;
        if (tailLen < 0) tailLen = 0;
        String head = text.substring(0, headLen);
        if (!head.isEmpty() && Character.isHighSurrogate(head.charAt(head.length() - 1))) {
            head = head.substring(0, head.length() - 1);
        }
        String tail = text.substring(text.length() - tailLen);
        if (!tail.isEmpty() && Character.isLowSurrogate(tail.charAt(0))) {
            tail = tail.substring(1);
        }
        int cut = text.length() - head.length() - tail.length();
        return head + "\n…（这条内容太长，中间省略了 " + cut + " 字，原文共 "
                + text.length() + " 字）…\n" + tail;
    }

    /**
     * 本地瘦身用的字符安全线：按模型真实窗口换算，不再所有模型都套一个 90000。
     * 中文 1 字 ≈ 1 token、英文 4 字符 ≈ 1 token，另给输出留 4K 余量。
     */
    private int charSafeLine() {
        int lim = ctxLimitTokens() - 4096;
        return Math.max(MAX_REQUEST_CHARS / 3, lim);   // 下限保底，别把小窗口模型砍得太狠
    }

    /**
     * 发请求前的主动瘦身：不等后端报「上下文超限」，本地先把体积压到安全线内。
     * 顺序是先压工具结果（信息密度最低、可重跑），再砍历史条数与单条上限。
     */
    private void preflightShrink(List<Message> history) {
        final int limit = charSafeLine();
        int total = requestChars(history);
        if (total <= limit) return;
        ctxBudget = 1500;
        trimToolContext();
        if (requestChars(history) > limit) {
            singleMsgLimit = Math.max(4000, singleMsgLimit / 2);
            historyBudget = Math.max(6000, historyBudget / 2);
        }
        Log.w(TAG, "请求体积 " + total + " 字超过安全线，已提前瘦身到 " + requestChars(history) + " 字");
    }

    /** 估算这次请求大致会发出去多少字（按截断后的长度算） */
    private int requestChars(List<Message> history) {
        long total = 0;
        if (history != null) {
            for (Message m : history) {
                if (m == null || Message.ROLE_SYSTEM.equals(m.role)) continue;
                total += Math.min(apiText(m).length(), singleMsgLimit);
            }
        }
        for (JSONObject o : toolMsgs) {
            if (o == null) continue;
            total += o.optString("content", "").length();
        }
        return (int) Math.min(total, Integer.MAX_VALUE);
    }

    /**
     * 粗估一段文本的 token：中文按「1 字 1 token」，其余按「4 字符 1 token」。
     * 只是拿不到服务端 usage 时的兜底，界面上会带 ≈ 前缀。
     */
    public static int estTokens(String s) {
        if (s == null || s.isEmpty()) return 0;
        int wide = 0, other = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean isWide = (c >= 0x2E80 && c <= 0x9FFF)      // CJK 部首 ~ 汉字
                    || (c >= 0xAC00 && c <= 0xD7AF)           // 韩文
                    || (c >= 0xF900 && c <= 0xFAFF)           // 兼容汉字
                    || (c >= 0xFF00 && c <= 0xFFEF)           // 全角符号
                    || (c >= 0x3000 && c <= 0x303F);          // 中文标点
            if (isWide) wide++;
            else other++;
        }
        return wide + (other + 3) / 4;
    }

    /** 一条 message 的估算 token：除正文外还有 role、分隔符等框架开销，按 4 个计 */
    private static int estMsgTokens(JSONObject m) {
        if (m == null) return 0;
        Object c = m.opt("content");
        String t = c == null ? "" : c.toString();
        return estTokens(t) + 4;
    }

    /** 上下文窗口上限（token）：用户填的 > 按模型名推断 > 保底值 */
    public int ctxLimitTokens() {
        Settings st = lastSettings;
        if (st != null && st.ctxTokens > 0) return st.ctxTokens;
        return inferCtxTokens(st == null ? null : pickModel(st));
    }

    /** 按模型名推断上下文窗口；推断不出来给保底值，别假装精确 */
    public static int inferCtxTokens(String model) {
        String m = model == null ? "" : model.toLowerCase();
        if (m.isEmpty()) return DEFAULT_CTX_TOKENS;
        if (m.contains("gemini")) return 1048576;
        if (m.contains("claude")) return 200000;
        if (m.contains("qwen") || m.contains("kimi") || m.contains("moonshot")) return 131072;
        if (m.contains("glm-4")) return 128000;
        if (m.contains("deepseek")) return 128000;
        if (m.contains("gpt-3.5")) return 16385;
        if (m.contains("gpt-4") || m.contains("gpt-5")
                || m.contains("o1") || m.contains("o3") || m.contains("o4")) return 128000;
        return DEFAULT_CTX_TOKENS;
    }

    /** 当前窗口内容估算多少 token（含 system、任务锚点、工具消息，和真正发出去的口径一致） */
    private int estimateRequestTokens(java.util.List<Message> history) {
        // 历史条数没变就直接复用上次请求算过的结果，免得每条消息刷一次界面都重扫一遍全文
        if (history != null && history.size() == lastUsageHistorySize && lastSentEstTokens > 0) {
            return lastSentEstTokens;
        }
        Settings st = lastSettings;
        int total = 0;
        if (st != null) {
            // system 除用户提示词外还拼了几段内置约束，固定开销按 400 粗估
            total += estTokens(st.systemPrompt) + 400;
        }
        if (history != null) {
            int from = historyWindowStart(history);
            for (int i = from; i < history.size(); i++) {
                Message m = history.get(i);
                if (m == null || Message.ROLE_SYSTEM.equals(m.role)) continue;
                total += estTokens(apiText(m)) + 4;
                // 图片按 token 计费，一张图粗估 800
                if (m.imageUrls != null) total += m.imageUrls.size() * 800;
            }
        }
        for (JSONObject o : toolMsgs) total += estMsgTokens(o);
        return total;
    }

    /**
     * 界面统计快照：{已用 token, 上限 token, 实发对话条数, 没发出的历史条数,
     * 是否服务端真值(1/0), 输出 token}。纯只读，不改任何状态。
     */
    public int[] usageSnapshot(java.util.List<Message> history) {
        try {
            int total = history == null ? 0 : history.size();
            int start = historyWindowStart(history);
            int dropped = Math.max(0, Math.min(start, total));
            int active = Math.max(0, total - dropped);
            int limit = ctxLimitTokens();

            // 真值能否沿用：必须是同一个会话的历史，且条数只差本轮回答那一条。
            // 回答落库后历史会多 1 条，若要求「条数完全相等」，真值就永远算过期。
            // 此时正确的占用 = 本轮 prompt + 本轮输出，也就是下一轮要发出去的量。
            boolean sameSession = lastHistoryRef != null && lastHistoryRef == history;
            boolean fresh = lastUsageReal && lastPromptTokens > 0 && sameSession
                    && (total == lastUsageHistorySize || total == lastUsageHistorySize + 1);
            int used = fresh ? lastPromptTokens + Math.max(0, lastCompletionTokens)
                    : estimateRequestTokens(history);
            if (used < 0) used = 0;
            return new int[]{used, limit, active, dropped, fresh ? 1 : 0,
                    Math.max(0, lastCompletionTokens)};
        } catch (Throwable e) {
            return new int[]{0, DEFAULT_CTX_TOKENS, 0, 0, 0, 0};
        }
    }

    /** 接住服务端回报的真实用量：界面统计唯一可信的来源 */
    private void absorbUsage(JSONObject u) {
        if (u == null) return;
        int pt = u.optInt("prompt_tokens", u.optInt("input_tokens", 0));
        int ct = u.optInt("completion_tokens", u.optInt("output_tokens", 0));
        // 有的网关只给 total，也认；极少数把输入明细放在 *_tokens_details 里
        if (pt <= 0) {
            JSONObject d = u.optJSONObject("input_tokens_details");
            if (d != null) pt = d.optInt("total_tokens", 0);
        }
        if (pt > 0) lastPromptTokens = pt;
        if (ct > 0) lastCompletionTokens = ct;
        if (pt > 0 || ct > 0) lastUsageReal = true;
    }

    /** 从最新一条往回凑够 historyBudget，返回窗口起始下标 */
    private int historyWindowStart(List<Message> history) {
        if (history == null || history.isEmpty()) return 0;
        int acc = 0;
        int i = history.size() - 1;
        for (; i >= 0; i--) {
            Message m = history.get(i);
            if (m == null || Message.ROLE_SYSTEM.equals(m.role)) continue;
            // 按「截断后」的长度估算，否则一条超长消息会把预算算穿，窗口直接塌成 1 条
            int len = Math.min(apiText(m).length(), singleMsgLimit);
            // 至少留最后一条，避免预算算出来是空窗口
            if (acc > 0 && acc + len > historyBudget) break;
            acc += len;
        }
        int start = i + 1;
        return Math.max(0, Math.min(start, history.size()));
    }

    /** 被裁掉的那段里最早的一条用户消息，用于当作任务锚点 */
    private Message firstUserBefore(List<Message> history, int end) {
        if (history == null) return null;
        int lim = Math.min(end, history.size());
        for (int i = 0; i < lim; i++) {
            Message m = history.get(i);
            if (m == null || !Message.ROLE_USER.equals(m.role)) continue;
            if (!apiText(m).isEmpty()) return m;
        }
        return null;
    }

    private JSONObject buildBody(Settings s, List<Message> history) throws Exception {
        preflightShrink(history);
        trimToolContext();
        final String model = pickModel(s);

        JSONArray msgs = new JSONArray();
        StringBuilder sys = new StringBuilder();
        if (s.systemPrompt != null && !s.systemPrompt.trim().isEmpty()) {
            sys.append(s.systemPrompt.trim());
        }
        String extra = extraSystem;
        if (extra != null && !extra.trim().isEmpty()) {
            if (sys.length() > 0) sys.append("\n\n");
            sys.append(extra.trim());
        }
        if (sys.length() > 0) sys.append("\n\n");
        sys.append("【用户文件】用户上传的文件会在消息里以 <file name=\"…\" path=\"…\"/> 给出真实路径。\n"
                + "1. 必须直接使用该 path，不要改写成 /sdcard/Download/… 之类你猜测的路径。\n"
                + "2. 工具报「文件不存在」时，先按消息里给的 path 原样重试一次；仍失败就把你用的 path 原样告诉用户请他确认，"
                + "不要反复换目录去猜。\n"
                + "3. 用户说「改一下/优化/修改」某个上传文件时，指的是 path 指向的那个文件，先用工具确认真实存在再动手。");
        if (tools != null && tools.hasTool("run_shell") && !toolsDisabled) {
            if (sys.length() > 0) sys.append("\n\n");
            sys.append("【终端执行】你可以用 run_shell 在用户手机的内置终端里真实执行 shell 命令。\n"
                    + "1. 用户让你「跑一下/执行/运行/测试/验证」代码或命令时，直接调用 run_shell 真实执行，"
                    + "把真实输出讲给用户，不要只贴命令让他自己跑。\n"
                    + "2. 用户要你先写好程序再验证时，可以先写文件再执行，中间用简短一句话说明在做什么。\n"
                    + "3. 命令失败就读输出定位原因，修正后重试，最多试几次；仍失败就把报错原样告诉用户。\n"
                    + "4. 不要执行破坏性命令（rm -rf /、mkfs、清空用户数据等）；不要在没有用户意图时擅自改他的文件。\n"
                    + "5. 内置反编译链直接当命令用：jadx（dex/APK → Java）、apktool（解包/回打包）、"
                    + "baksmali 与 smali（dex ↔ smali）、apkinfo（看清单与体积构成）。"
                    + "大 APK 先用 apkinfo 看规模再只反编译目标 dex，全量反编译在手机上会被内存限制打断。\n"
                    + "6. 排查闪退/卡顿/任务中断时直接执行 `dumpsys`（返回内存水位、进程优先级、电池限制、"
                    + "保活状态等现场快照）与 `logcat`（返回应用自己记账的崩溃堆栈、主线程卡死现场）。"
                    + "没 root 的手机读不到系统日志，这两条命令已由内置采集接管，一定有内容返回，"
                    + "不要因为「普通应用没有日志权限」就放弃排查。");
        }
        // —— 以下两条放在最后：越靠后的指令权重越高 ——
        if (sys.length() > 0) sys.append("\n\n");
        sys.append("【先判断，再动手】\n"
                + "1. 先判断用户这句话属于哪一类，然后只做这一类该做的事，不要把几套流程混着走：\n"
                + "   · 直答（问知识/概念/建议）→ 直接答，不调工具；只有需要实时信息才联网查。\n"
                + "   · 产出（要文案/代码/方案/整理结果）→ 按对应结构直接给成品，不讲方法论。\n"
                + "   · 改东西（改代码/文件/App）→ 先看清现状再动手，改完必须验证。\n"
                + "   · 排查（说不对/报错/为什么）→ 先定位原因再给修复，不要上来就列一堆可能原因。\n"
                + "   · 对话（随口一问/确认/聊一句）→ 一两句回完，别铺开写。\n"
                + "2. 说法就是需求边界：说「简单说」就简短，说「详细」才展开，没说就按上面分类的默认尺寸；不要自己加戏扩大范围。\n"
                + "3. 做不对的原因是缺关键信息（缺文件、口径不清、目标模糊）时，一次问清 2-3 个关键点就停，不要边猜边做。\n"
                + "4. 拿不准用户真实意图时，先给最可能的那一个答案，末尾用一句话确认，而不是先反问。");
        if (sys.length() > 0) sys.append("\n\n");
        sys.append("【表达：必须说中文】\n"
                + "1. 每条回复的主体必须是中文，包括解释、结论、总结、报错分析、进度说明。"
                + "只有代码、命令、文件路径、标识符、专有名词才保留英文原文。\n"
                + "2. 严禁只丢一堆代码/命令/英文日志就结束。代码块或命令的前面或后面必须有中文说明："
                + "这段是做什么的、执行后看到了什么、接下来准备做什么。\n"
                + "3. 工具跑完不要沉默，用一两句中文讲清「做了什么、结果如何」；失败了就说失败原因和你的应对。\n"
                + "4. 遇到英文报错，先用中文讲结论和原因，再只贴关键几行原文，不要把整段英文日志倾倒给用户。\n"
                + "5. 用户看不懂英文术语时，用中文解释它是什么意思。");
        if (sys.length() > 0) sys.append("\n\n");
        sys.append("【只给成品，不吐过程】\n"
                + "1. 不要把内部推演、方案对比、逐行自问自答写成正文。用户要的是结果，不是草稿纸。\n"
                + "2. 严禁长篇英文思考（例如 computed v5 before loop — fine 这类）。要权衡就先用一两句中文给结论。\n"
                + "3. 贴代码只贴「要交付/要修改」的最终版本：不要半成品、不要同一段重复贴、不要大段样板。\n"
                + "4. 说明部分尽量简短，篇幅留给结论、成品和下一步动作；需要长内容时先给一句中文摘要。");
        if (sys.length() > 0) sys.append("\n\n");
        sys.append("【动手方式：批量做完，别小步磨】\n"
                + "1. 改文件不要「读一行 → 改一行 → 再读回来」这样来回磨："
                + "先一次性看清全貌，再用一条脚本或一次批量替换改完，最后统一验证一遍。\n"
                + "2. 同一个文件、同一个位置不要反复读取；改完确认一次就够。\n"
                + "3. 反编译产物（smali、dex 反汇编）和第三方库源码不要抄进正文，"
                + "只说清改了什么、为什么改；需要贴就最多贴关键三五行。\n"
                + "4. 工具调用尽量少而准：能一条命令/一个脚本解决的，不要拆成十几次调用。");
        if (sys.length() > 0) sys.append("\n\n");
        sys.append("【别重复：重复调用是最贵的错误】\n"
                + "1. 同一个工具、同一套参数，不要发第二次。上一轮已经拿到结果（包括报错），"
                + "那就是已知信息，直接基于它往下走。中间插了别的调用再绕回来，同样算重复——"
                + "交替打转和连续打转一样烧钱。\n"
                + "2. 确实要重跑，必须满足其一：参数变了、目标变了、或已确认上次的失败原因被消除。"
                + "动手前先用一句话说清「这次和上次有什么不同」。\n"
                + "3. 报错要读完再动手：先抓关键词定位原因，不要靠「换个参数再试一次」盲试。"
                + "同一类报错连续两次，就停下换思路，不要有第三次。\n"
                + "4. 耗时操作（解包、编译、打包、签名、下载大文件）动手前先用一句话说明"
                + "「这一步做什么、大概要多久」；跑完先看结果再决定下一步，"
                + "不要闷头连着跑十几轮什么都不说。\n"
                + "5. 每完成一个阶段，用一两句话交代：改了什么、产物在哪、下一步做什么。"
                + "这样即使中途断了，下一轮也不用从头开荒。\n"
                + "6. 刚读过的文件内容就在上下文里，不要为了「再确认一下」重复读取；"
                + "要确认状态就查目录/mtime，别重读全文。\n"
                + "7. 每次调用工具之前先问自己一句：这次会拿到什么新信息？"
                + "答不上来，就说明不该调。");
        if (sys.length() > 0) sys.append("\n\n");
        sys.append("【连续推进：不许做一半就停】\n"
                + "1. 用户给的是一件事，就一路做到底：理解 → 计划 → 执行 → 验证 → 汇报。"
                + "不允许做完中间一步就结束回复、把剩下的丢给用户。\n"
                + "2. 一步做完自动进下一步，不要问「要我继续吗」「需要我接着做吗」——"
                + "除非涉及不可逆操作（删数据、覆盖用户文件、清空目录）、花钱、或缺少必要信息/权限。\n"
                + "3. 工具报错不等于任务结束：读报错 → 定位原因 → 换方案重试。"
                + "实在绕不过去，才停下来明确告诉用户卡在哪、缺什么，并给可执行的替代方案。\n"
                + "4. 只要还有能自己动手做的下一步，就继续调用工具做，不要写「建议你执行以下步骤」把活推回给用户。\n"
                + "5. 收尾只有一个条件：任务真的完成，或确实无路可走。"
                + "收尾必须用中文说明三件事：完成了什么、产物在哪（给完整路径）、还剩什么没做。\n"
                + "6. 产物要自己验收，别把验收甩给用户：改完安装包就自己回编译、签名、"
                + "装一遍确认能起来；改完代码就自己运行一次。没验证过的结果等于没做完，"
                + "不要用「你试试看行不行」当交付。\n"
                + "7. 「建议你这样做」不算完成。凡是你自己能动手的部分，就不要留给用户；"
                + "只有真要人来拍板的（删数据、花钱、缺权限）才允许交出去。");
        // ===== 历史裁剪（省 token 的核心）=====
        // 从最新一条往回凑预算，凑不下就丢掉更早的，保证单次请求的历史长度有上限。
        int hFrom;
        if (ctxHistoryKeep > 0) {
            hFrom = Math.max(0, history.size() - ctxHistoryKeep);
        } else {
            hFrom = historyWindowStart(history);
        }
        if (history.size() > 2) hFrom = Math.min(hFrom, history.size() - 2);
        if (hFrom < 0) hFrom = 0;
        final boolean clipped = hFrom > 0;
        if (clipped) {
            // 固定文案（不带变化的条数）：system 是每次请求的前缀，
            // 一旦这里带上轮次数字，DeepSeek 的前缀缓存就整段失效，等于白花钱。
            sys.append("\n\n【历史裁剪】为控制开销，较早的中间对话没有带入上下文（最初的需求会单独保留）。\n")
                    .append("1. 需要旧细节就调用工具重新查看当前文件/目录状态，不要凭记忆猜。\n")
                    .append("2. 不要让用户重复已经确认过的信息。");
        }
        // ===== 思考强度指令放最后：越靠后的指令权重越高 =====
        // 放在中段时会被后面「只给成品，不吐过程」「连续推进」等大段要求稀释，等于没生效。
        // 档位要按"实际生效值"取：极度碰上推理模型会被压到中度，
        // 提示词这边不跟着压，就等于一边关参数一边用文字催它狂想，白压。
        String thinkHint = (summarizeMode || thinkOff) ? ""
                : Settings.thinkHint(Settings.effectiveThinkLevel(s, model));
        if (!thinkHint.isEmpty()) {
            if (sys.length() > 0) sys.append("\n\n");
            sys.append(thinkHint);
        }
        if (sys.length() > 0) {
            msgs.put(new JSONObject().put("role", "system").put("content", sys.toString()));
        }
        if (clipped) {
            // 任务锚点：最早那条用户需求，防止长任务中忘掉最初目标
            Message anchor = firstUserBefore(history, hFrom);
            String ac = anchor == null ? "" : clampMsg(apiText(anchor), 3000);
            if (!ac.isEmpty()) {
                msgs.put(new JSONObject().put("role", "user")
                        .put("content", "【最开始的需求】\n" + ac));
            }
        }
        for (int hi = hFrom; hi < history.size(); hi++) {
            Message m = history.get(hi);
            String c = clampMsg(apiText(m));
            if (c == null || c.trim().isEmpty()) continue;
            if (Message.ROLE_SYSTEM.equals(m.role)) continue;
            if (m.imageUrls != null && !m.imageUrls.isEmpty()) {
                // 多模态：仅 assistant 之外的 user 消息带图，且图片放在文本之后
                JSONArray parts = new JSONArray();
                parts.put(new JSONObject().put("type", "text").put("text", c));
                for (String url : m.imageUrls) {
                    if (url == null || url.isEmpty()) continue;
                    parts.put(new JSONObject()
                            .put("type", "image_url")
                            .put("image_url", new JSONObject().put("url", url)));
                }
                msgs.put(new JSONObject().put("role", m.role).put("content", parts));
            } else {
                msgs.put(new JSONObject().put("role", m.role).put("content", c));
            }
        }
        // 工具轮次中的临时消息（assistant tool_calls 与 tool 结果）
        for (JSONObject o : toolMsgs) {
            if (o != null) msgs.put(o);
        }
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("messages", msgs);
        final boolean streamingNow = bodyStreaming(s);
        body.put("stream", streamingNow);
        // 明确要服务端在流末尾回报 token 用量：界面上的统计就靠这个真值。
        // 个别网关不认这个字段会直接 400，那种情况在报错里认出来，之后不再发。
        if (streamingNow && !usageOff && !summarizeMode) {
            body.put("stream_options", new JSONObject().put("include_usage", true));
        }
        body.put("temperature", (double) s.temperature);
        // 深度档给更多输出额度：额度紧时模型会为了不截断而草草收尾，
        // 「想得更多」最后变成「写得更赶」。
        long outTokens = s.maxTokens;
        final int lvNow = thinkOff ? 0 : Settings.effectiveThinkLevel(s, model);
        if (lvNow >= 4) outTokens = outTokens * 3 / 2;
        else if (lvNow == 3) outTokens = outTokens * 6 / 5;
        body.put("max_tokens", (int) Math.min(65536L, Math.max(256L, outTokens)));

        // 工具注入：内置 APK 工具 + MCP 工具。
        // 一律下发，只有服务端真拒绝过一次才由 toolsDisabled 关掉。
        if (tools != null && !toolsDisabled && !summarizeMode) {
            JSONArray schema = filterBlockedTools(tools.toSchema());
            if (schema != null && schema.length() > 0) {
                body.put("tools", schema);
                body.put("tool_choice", "auto");
            }
        }

        // 思考强度：只对「从账号 /models 拉回来的模型」下发。
        // 手填的模型名无法确认后端认不认这个参数，发了只会换来 400。
        // 救援 / 思考超长（thinkOff）时不能走默认档的「不发」——不发等于回到服务端
        // 默认强度，对内置推理的模型等于没关，重试会原样再烧几万字。这里强制发 minimal。
        final int effLv = Settings.effectiveThinkLevel(s, model);
        if (effLv != s.thinkLevel) {
            Log.w(TAG, "「极度」对自带推理的模型 " + model + " 按中度发送（避免思考吃光输出额度）");
        }
        String effort = thinkOff ? Settings.RESCUE_EFFORT : Settings.reasoningEffort(effLv);
        if (!thinkBlockedHosts.contains(gatewayKey(s)) && effort != null
                && Settings.isPulledModel(s, model)) {
            body.put("reasoning_effort", effort);
        }

        // ---- 记账：这次真正发出去多少条、大概多少 token ----
        // 界面上的「条数 / 未发」全部以这里为准，不再各处自己猜一遍。
        lastSettings = s;
        lastHistoryRef = history;
        lastSentCount = msgs.length();
        lastDroppedCount = Math.max(0, (history == null ? 0 : history.size()) - hFrom);
        int estTok = 0;
        for (int i = 0; i < msgs.length(); i++) {
            estTok += estMsgTokens(msgs.optJSONObject(i));
        }
        lastSentEstTokens = estTok;
        // 这次请求对应的历史条数：拿到 usage 后靠它判断真值有没有过期
        lastUsageHistorySize = history == null ? 0 : history.size();
        return body;
    }

    /**
     * 逐行解析 SSE。按字节切行后再逐行智能解码，
     * 这样即使服务端用 GBK 编码，中文也能正确还原。
     */
    private StreamState readStream(InputStream rawIn, Listener l) throws Exception {
        StreamState st = new StreamState();
        BufferedInputStream bis = new BufferedInputStream(rawIn, 8192);
        ByteArrayOutputStream lineBuf = new ByteArrayOutputStream(256);
        int b;
        while ((b = bis.read()) != -1) {
            if (cancelled) break;
            if (b == '\n') {
                if (handleLine(decodeSmart(lineBuf.toByteArray()), st, l)) break;
                lineBuf.reset();
            } else if (b != '\r') {
                lineBuf.write(b);
            }
        }
        if (!st.finished && lineBuf.size() > 0 && !cancelled) {
            handleLine(decodeSmart(lineBuf.toByteArray()), st, l);
        }
        bis.close();
        return st;
    }

    /** 处理一行 SSE，返回 true 表示流结束 */
    private boolean handleLine(String line, StreamState st, Listener l) {
        String t = line.trim();
        if (t.isEmpty() || t.startsWith(":")) return false;

        if (!t.startsWith("data:")) {
            st.keepRaw(line);
            return false;
        }
        st.sawSse = true;
        String data = t.substring(5).trim();
        if (data.isEmpty()) return false;
        if ("[DONE]".equals(data)) {
            st.finished = true;
            return true;
        }
        try {
            handleChunk(new JSONObject(data), st, l);
        } catch (Exception parseErr) {
            st.keepRaw(line);
            Log.d(TAG, "跳过无法解析的 SSE 行");
        }
        return false;
    }

    private void handleChunk(JSONObject o, StreamState st, Listener l) {
        JSONObject err = o.optJSONObject("error");
        if (err != null) {
            final String m = JsonOpt.str(err, "message", "服务返回错误");
            post(() -> l.onError(m));
            return;
        }
        // usage 一般跟着最后那个 chunk 来，而那种 chunk 的 choices 是空数组，
        // 所以必须在判断 choices 之前先接住，否则永远读不到真值。
        JSONObject ug = o.optJSONObject("usage");
        if (ug != null) absorbUsage(ug);

        JSONArray choices = o.optJSONArray("choices");
        if (choices == null || choices.length() == 0) return;
        JSONObject ch = choices.optJSONObject(0);
        if (ch == null) return;
        String fr = JsonOpt.str(ch, "finish_reason");
        if (!fr.isEmpty()) st.finishReason = fr;

        String piece = null;
        JSONObject delta = ch.optJSONObject("delta");
        if (delta != null) {
            String rc = JsonOpt.str(delta, "reasoning_content");
            if (rc.isEmpty()) rc = JsonOpt.str(delta, "reasoning");
            if (!rc.isEmpty()) {
                st.reasoning.append(rc);
                final String rpiece = rc;
                post(() -> l.onReasoning(rpiece));
            }

            String c = JsonOpt.str(delta, "content");
            if (!c.isEmpty()) {
                piece = c;
                st.content.append(c);
            }
            JSONArray tcs = delta.optJSONArray("tool_calls");
            if (tcs != null) accumulateToolCalls(tcs, st);
        } else {
            JSONObject msg = ch.optJSONObject("message");
            String c = msg != null ? JsonOpt.str(msg, "content") : JsonOpt.str(ch, "text");
            if (!c.isEmpty()) {
                piece = c;
                st.content.append(c);
            }
            if (msg != null) {
                JSONArray tcs = msg.optJSONArray("tool_calls");
                if (tcs != null) accumulateToolCalls(tcs, st);
            }
        }

        if (piece != null) {
            final String p = piece;
            post(() -> l.onDelta(p));
        }
    }

    /** 把分片到达的 tool_calls 累积成完整调用 */
    private void accumulateToolCalls(JSONArray tcs, StreamState st) {
        for (int i = 0; i < tcs.length(); i++) {
            JSONObject tc = tcs.optJSONObject(i);
            if (tc == null) continue;
            int idx = tc.optInt("index", i);
            JSONObject acc = st.toolCalls.get(idx);
            if (acc == null) {
                acc = new JSONObject();
                st.toolCalls.put(idx, acc);
            }
            try {
                String id = JsonOpt.str(tc, "id");
                if (!id.isEmpty()) acc.put("id", id);
                if (!acc.has("type")) acc.put("type", "function");

                JSONObject fn = tc.optJSONObject("function");
                if (fn == null) continue;
                JSONObject af = acc.optJSONObject("function");
                if (af == null) {
                    af = new JSONObject();
                    acc.put("function", af);
                }
                String name = JsonOpt.str(fn, "name");
                if (!name.isEmpty()) af.put("name", name);
                String args = JsonOpt.str(fn, "arguments");
                if (!args.isEmpty()) {
                    af.put("arguments", JsonOpt.str(af, "arguments") + args);
                }
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 切到「收尾模式」：这次不再下发 tools，让模型只能用文字交代。
     * 目的很单纯——工具轮次耗尽时界面不能空着，必须给用户一个结论：
     * 做到了哪、卡在哪、接下来怎么办。
     */
    /**
     * 从工具参数和结果里捡出用过的工作路径，攒成「现场」。
     *
     * <p>改 APK 这类活，真正值钱的是「在哪个目录解开、改的是哪个文件、产物落在哪」。
     * 这些信息原来只活在内存的 toolMsgs 里，一旦这一轮结束就没了，
     * 用户再说「继续」时模型面对的是一张白纸，只能重新解包、重新找文件——
     * 看起来跑了很多趟，实际一步都没往前走。
     */
    private void collectArtifacts(String name, String args, String result) {
        // 「最后几次动手」：命令 + 结果首行。这是续跑时最值钱的一条线索，
        // 比一串目录名管用——它直接说明上一步做到哪、拿到了什么。
        if (lastCalls.size() >= MAX_LAST_CALLS) lastCalls.remove(0);
        String cmd = briefLine(args);
        if (cmd.length() > 160) cmd = cmd.substring(0, 160) + "…";
        String head = briefLine(result);
        if (head.length() > 120) head = head.substring(0, 120) + "…";
        lastCalls.add("- " + name + " " + cmd + (head.isEmpty() ? "" : "\n  → " + head));

        StringBuilder buf = new StringBuilder();
        if (args != null) buf.append(args).append('\n');
        if (result != null) buf.append(result);
        if (buf.length() == 0) return;
        java.util.regex.Matcher m = PATTERN_PATH.matcher(buf);
        while (m.find()) {
            String p = m.group();
            if (p.length() < 6 || p.length() > 160) continue;
            if (artifacts.size() >= 24) break;
            artifacts.add(p);
        }
        if (!artifacts.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String p : artifacts) {
                if (sb.length() > 0) sb.append('\n');
                sb.append("- ").append(p);
            }
            lastWorkbench = sb.toString();
        }
        persistWorkbench();
    }

    /** /sdcard、/root 这些实打实的工作路径，够用且不会把普通 URL 混进来 */
    private static final java.util.regex.Pattern PATTERN_PATH =
            java.util.regex.Pattern.compile("(?:/sdcard|/storage/emulated/0|/root|/data/user/0|/tmp)"
                    + "/[A-Za-z0-9._\\-/]+");

    /**
     * 用户这轮是不是在说「接着刚才那个继续」。
     *
     * <p>只有明确要继续的意思才带现场回去；普通提问一律不带，
     * 免得把上次改包留下的路径硬塞进无关对话里。
     */
    private static boolean wantsContinue(List<Message> history) {
        if (history == null || history.isEmpty()) return false;
        String t = null;
        for (int i = history.size() - 1; i >= 0; i--) {
            Message m = history.get(i);
            if (m != null && Message.ROLE_USER.equals(m.role)) {
                t = m.content;
                break;
            }
        }
        if (t == null) return false;
        String s = t.replace(" ", "").trim();
        if (s.isEmpty()) return false;
        String[] keys = {"继续", "接着", "还没", "没做完", "没搞定", "再做", "做完它",
                "接着干", "往下做", "重来", "continue",
                // 用户点完停止后的口头说法远不止「继续」，这些都是同一个意思
                "没成功", "没弄完", "做一半", "断在", "刚才那", "上次那", "重新做", "resume"};
        for (String k : keys) {
            if (s.contains(k)) return true;
        }
        return false;
    }

    /**
     * 续命：预算撞顶时先别收尾。
     *
     * <p>把轮次/停滞计数清零、把兜底总时长往后延，然后往上下文里塞一句话，
     * 要求模型「一句话交代进度 + 立刻继续动手」。
     *
     * <p>关键在于 {@code toolMsgs} 一条都不清：模型手上的路径、报错、已经改过的内容
     * 全都还在，所以续命后的这一轮是「接着刚才那一步往下走」，而不是从头再来。
     * 这正是原来最容易出问题的地方——一到点就收尾，用户再点继续，等于重新开荒。
     *
     * @return true 表示已经续命，调用方继续执行本轮工具调用；false 表示续命次数用完
     */
    private boolean revive(Listener l, String why) {
        if (reviveCount >= MAX_REVIVE) return false;
        reviveCount++;
        toolRound = 0;
        sameCallRepeat = 0;
        recentSigs.clear();
        lastCallSig = "";
        emptyRoundStreak = 0;
        noProgressStreak = 0;
        lastRoundFp = "";
        long now = android.os.SystemClock.elapsedRealtime();
        lastProgressAt = now;
        // 每次续命多给 10 分钟兜底额度，避免兜底总时长一到就每轮都撞墙
        hardLimitMs += 300_000L;
        try {
            JSONObject m = new JSONObject();
            m.put("role", "user");
            m.put("content", "【系统】" + why + "，但任务并没有做完。预算已经延长，"
                    + "工具调用通道保持开启，你现在必须继续动手，不要收尾、不要写总结、"
                    + "不要问我「要不要继续」。\n"
                    + "要求：\n"
                    + "1) 要交代进度就最多写一行，写完立刻调用工具做下一步；\n"
                    + "2) 不要重复已经成功执行过的步骤——上面的工具结果里，路径、报错、输出都还在；\n"
                    + "3) 优先做能把任务真正推到终点的那一步（编译、打包、签名、安装、实际验证），"
                    + "而不是继续读文件、试探性查询；\n"
                    + "4) 如果卡在某个报错上，换个等效做法（换工具、换命令、换参数）继续，"
                    + "不要停下来把问题写成建议交给我。");
            toolMsgs.add(m);
        } catch (Exception ignored) {
        }
        final int n = reviveCount;
        post(() -> l.onToolRetryHint("预算到点，已续命继续往下做（第 " + n + " 次）…"));
        return true;
    }

    private void startSummarize(Settings s, List<Message> history, Listener l, String why) {
        if (summarizeMode) {
            // 连「收尾」都失败了：不再指望模型，自己写一段有信息量的结论
            localWrapUp(l, why + "，模型也没有给出文字总结");
            return;
        }
        summarizeMode = true;
        post(() -> l.onToolLimit(why));

        try {
            JSONObject m = new JSONObject();
            m.put("role", "user");
            m.put("content", "【系统】" + why + "，现在不允许再调用任何工具，"
                    + "工具调用通道已关闭，你必须只用文字回答。请用中文简明交代三件事：\n"
                    + "1) 已经成功完成的步骤（有产物就把完整路径写出来）\n"
                    + "2) 目前卡在哪一步，具体报错或缺什么信息\n"
                    + "3) 我下一步该怎么做，给出可以直接执行的具体命令或操作\n"
                    + "不要请求工具，不要只回答做不到，也不要重复原计划。");
            toolMsgs.add(m);
        } catch (Exception ignored) {
        }

        // 先断掉当前连接，避免 finally 误杀新请求
        restartConnection();
        chainDepth++;
        continueRun(s, history, l);
    }

    /**
     * 执行模型请求的工具调用，然后把结果回灌给模型继续生成。
     * 工具轮次使用的消息只存在内存里，不污染对话历史。
     */
    private void runToolRound(Settings s, List<Message> history, Listener l, StreamState st) {
        final ToolRegistry reg = tools;
        if (reg == null) return;

        // 已经进入收尾模式，模型却还在要工具：忽略调用，把已有文字当最终回答
        if (summarizeMode) {
            final String partial = st.content.toString();
            if (partial.trim().isEmpty()) {
                localWrapUp(l, "工具轮次已经用尽，模型也没能给出文字总结");
            } else {
                post(() -> l.onDone(partial));
            }
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (runStartAt == 0L) runStartAt = now;
        if (lastProgressAt == 0L) lastProgressAt = now;

        // 撞预算时不收尾，先续命：清计数、延预算，工具现场原样保留，模型接着干。
        boolean outOfRounds = toolRound >= MAX_TOOL_ROUNDS;
        boolean stalled = now - lastProgressAt > STALL_MS;
        boolean tooLong = now - runStartAt > hardLimitMs;
        if (outOfRounds || stalled || tooLong) {
            String why = outOfRounds
                    ? "工具调用已经跑了 " + toolRound + " 轮"
                    : (stalled
                       ? "已经 " + ((now - lastProgressAt) / 1000) + " 秒没有任何新进展"
                       : "整轮任务已经跑了 " + ((now - runStartAt) / 1000) + " 秒");
            if (!revive(l, why)) {
                startSummarize(s, history, l, why + "，仍未结束，先停下来汇报进度");
                return;
            }
        }
        toolRound++;
        noResultCalls = 0;
        final int rn = toolRound;
        post(() -> l.onRoundHint(rn, MAX_TOOL_ROUNDS));
        // 每进一轮工具调用刷一次现场（内部有限流，不会造成磁盘抖动）
        RunGuard.beat(toolRound);

        // 1) 把 assistant 的 tool_calls 消息加入上下文
        JSONArray calls = new JSONArray();
        List<String> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<String> argsList = new ArrayList<>();
        for (Map.Entry<Integer, JSONObject> e : st.toolCalls.entrySet()) {
            JSONObject acc = e.getValue();
            if (acc == null) continue;
            String id;
            try {
                id = JsonOpt.str(acc, "id");
            } catch (Exception ex) {
                id = "";
            }
            if (id.isEmpty()) id = "call_" + e.getKey();
            JSONObject fn = acc.optJSONObject("function");
            String name = fn == null ? "" : JsonOpt.str(fn, "name");
            String args = fn == null ? "{}" : JsonOpt.str(fn, "arguments", "{}");
            if (name.isEmpty()) continue;
            try {
                JSONObject one = new JSONObject();
                one.put("id", id);
                one.put("type", "function");
                JSONObject f = new JSONObject();
                f.put("name", name);
                f.put("arguments", args);
                one.put("function", f);
                calls.put(one);
            } catch (Exception ignored) {
            }
            ids.add(id);
            names.add(name);
            argsList.add(args);
        }
        if (calls.length() == 0) {
            post(() -> l.onError("模型请求了工具，但工具名为空，已停止。"));
            return;
        }

        // 原地打转检测：连续重复同一批调用，说明模型卡住了，别再耗下去
        StringBuilder sigb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            sigb.append(names.get(i)).append('|').append(argsList.get(i)).append(';');
        }
        String sig = sigb.toString();
        recentSigs.addLast(sig);
        while (recentSigs.size() > SIG_WINDOW) recentSigs.removeFirst();
        int repeated = 0;
        for (String seen : recentSigs) if (seen.equals(sig)) repeated++;
        sameCallRepeat = repeated;
        lastCallSig = sig;
        if (repeated >= MAX_SAME_CALL) {
            final String stuckTool = names.get(0);
            startSummarize(s, history, l,
                    "「" + stuckTool + "」在最近 " + SIG_WINDOW + " 次调用里重复了 "
                            + repeated + " 次、没有进展");
            return;
        }
        if (repeated == 2) {
            // 第二次就提醒，别等第三次才收尾：重跑多半拿不到新信息，白烧一轮上下文
            try {
                JSONObject warn = new JSONObject();
                warn.put("role", "user");
                warn.put("content", "【系统提醒】这条工具调用和参数刚刚执行过，结果已经在历史里。"
                        + "直接基于已有输出往下走，不要等一次重跑；"
                        + "确实要重跑，先用一句话说明这次和上次有什么不同、要拿到什么新信息。");
                toolMsgs.add(warn);
            } catch (Exception ignored) {
            }
        }

        try {
            JSONObject asMsg = new JSONObject();
            asMsg.put("role", "assistant");
            String partial = st.content.toString();
            asMsg.put("content", partial.isEmpty() ? JSONObject.NULL : partial);
            asMsg.put("tool_calls", calls);
            toolMsgs.add(asMsg);
        } catch (Exception ignored) {
        }

        // 2) 串行执行每个工具（APK 操作常有先后依赖）
        final StringBuilder roundFp = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (cancelled) return;
            final String name = names.get(i);
            final String rawArgs = argsList.get(i);
            final String callId = ids.get(i);

            post(() -> l.onToolCall(name, rawArgs));

            final JSONObject[] argHolder = new JSONObject[1];
            try {
                String a = rawArgs == null ? "" : rawArgs.trim();
                argHolder[0] = a.isEmpty() ? new JSONObject() : new JSONObject(a);
            } catch (Exception ex) {
                argHolder[0] = new JSONObject();
            }

            // 工具可能跑几十秒（解包/重打包），放到独立线程执行，
            // 这里持续观察取消标志：点「停止」能立刻中断，不用等它跑完
            final String[] resHolder = new String[1];
            final boolean[] okHolder = new boolean[]{true};
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String r = reg.call(name, argHolder[0]);
                        resHolder[0] = r == null ? "（无输出）" : r;
                    } catch (Throwable t) {
                        okHolder[0] = false;
                        String m = t.getMessage();
                        resHolder[0] = "工具执行失败：" + (m == null ? t.getClass().getSimpleName() : m);
                    }
                }
            }, "nebula-tool-" + name);
            worker.setDaemon(true);
            currentTool = name + " " + briefLine(argHolder[0].toString());
            if (currentTool.length() > 120) currentTool = currentTool.substring(0, 120) + "…";
            worker.start();
            final long toolT0 = android.os.SystemClock.elapsedRealtime();
            long beat = 0;
            while (worker.isAlive() && !cancelled) {
                try {
                    worker.join(150);
                } catch (InterruptedException ignored) {
                }
                // 解包/重打包可能跑好几分钟：每 5 秒给界面一次心跳。
                // 否则用户只看到一条不动的工具行，以为卡死了，其实在正常工作。
                long el = android.os.SystemClock.elapsedRealtime() - toolT0;
                if (el - beat >= 5000) {
                    beat = el;
                    final long sec = el / 1000;
                    post(() -> l.onToolBeat(name, sec));
                }
            }
            if (cancelled) {
                // 杀掉 proot 里正在跑的进程，别让它继续吃 CPU
                com.nebula.aichat.linux.LinuxEnv.abortCurrent();
                post(() -> l.onToolResult(name, false, "已被用户停止"));
                return;
            }

            // 工具已经退出，中断点作废：用户这时候点停止不该被记成「断在某个工具上」
            currentTool = "";

            String result = resHolder[0] == null ? "" : resHolder[0];
            boolean ok = okHolder[0];

            // ---- 没拿到结果：先自己重试一次，绝不把「空手」直接丢给模型让它收工 ----
            boolean empty = isEmptyResult(result);
            if (empty && ok && !cancelled) {
                Log.w(TAG, "工具 " + name + " 没返回任何结果，自动重试一次");
                String again = retryToolOnce(reg, name, argHolder[0]);
                if (again == null) {                    // 重试期间用户点了停止
                    post(() -> l.onToolResult(name, false, "已被用户停止"));
                    return;
                }
                if (!isEmptyResult(again)) {
                    result = again;
                    ok = true;
                    empty = false;
                    Log.i(TAG, "工具 " + name + " 重试后拿到结果");
                }
            }
            if (cancelled) return;

            if (empty || !ok) {
                int n = bumpToolFailure(name);
                boolean blocked = n >= EMPTY_BLOCK_AFTER;
                if (blocked) blockedTools.add(name);
                noteFailure(name, empty ? "没有返回任何结果" : briefLine(result));
                noResultCalls++;
                if (empty) {
                    result = "【无结果】" + name + " 这次没有返回任何输出，也没有报错，任务还没完成。\n"
                            + (blocked
                            ? "该工具已连续 " + n + " 次拿不到结果，现已临时禁用，不要再调用它。"
                            : "请立刻重试（可调整参数），或换一种做法继续做完；现在不许结束。");
                } else {
                    result = "【失败】" + briefLine(result) + "\n"
                            + (blocked
                            ? "该工具已连续 " + n + " 次失败，现已临时禁用。请换其他方式继续做完，现在不许结束。"
                            : "请按上面的报错修正后重试，或换工具继续做完；现在不许结束。");
                }
            } else {
                clearToolFailure(name);
            }

            if (result.length() > MAX_TOOL_RESULT) {
                // 头部常是编译/运行的噪音，尾部才是报错和结论，所以头 60% + 尾 40%
                int head = (int) (MAX_TOOL_RESULT * 0.6);
                int tail = MAX_TOOL_RESULT - head;
                result = result.substring(0, head)
                        + "\n…（中间省略 " + (result.length() - MAX_TOOL_RESULT) + " 字）…\n"
                        + result.substring(result.length() - tail);
            }

            final boolean fOk = ok;
            final String fRes = result;
            post(() -> l.onToolResult(name, fOk, fRes));

            roundFp.append(name).append('|').append(fpOf(result)).append(';');
            collectArtifacts(name, rawArgs, result);
            try {
                JSONObject tm = new JSONObject();
                tm.put("role", "tool");
                tm.put("tool_call_id", callId);
                tm.put("content", result);
                toolMsgs.add(tm);
            } catch (Exception ignored) {
            }
        }

        if (cancelled) return;

        // ---- 闸门：这一轮一个有效结果都没拿到，就不许收工，逼它换方式继续 ----
        if (names.size() > 0 && noResultCalls >= names.size()) {
            emptyRoundStreak++;
            if (emptyRoundStreak >= MAX_EMPTY_ROUNDS) {
                startSummarize(s, history, l,
                        "连续 " + MAX_EMPTY_ROUNDS + " 轮工具调用都没有拿到任何有效结果");
                return;
            }
            try {
                JSONObject m = new JSONObject();
                m.put("role", "user");
                m.put("content", "【系统】上一轮的工具调用全部没有拿到结果，任务并没有完成。"
                        + "你现在不能停止，也不能只用一句话解释失败。必须继续推进："
                        + "调整参数重试、换用其他工具、或改用终端直接执行等效命令。"
                        + "只有在确实无法完成时，才允许用文字写清卡在哪，并给出可直接执行的下一步。");
                toolMsgs.add(m);
            } catch (Exception ignored) {
            }
            final String hint = "上一轮没拿到结果，正在换方式继续…";
            post(() -> l.onToolRetryHint(hint));
        } else {
            emptyRoundStreak = 0;
            // 这一轮确实拿到了结果 —— 算「有进展」，停滞计时归零，别把还在推进的任务砍掉
            lastProgressAt = android.os.SystemClock.elapsedRealtime();

            // 拿到结果 ≠ 有进展：反复跑同一条命令、输出逐字一样，就是原地打转。
            // 这种轮次最烧钱——每轮都是一次完整请求，却带不来任何新信息。
            String fp = roundFp.toString();
            if (!fp.isEmpty() && fp.equals(lastRoundFp)) {
                noProgressStreak++;
                if (noProgressStreak >= MAX_NO_PROGRESS) {
                    startSummarize(s, history, l, "连续 " + noProgressStreak
                            + " 轮工具调用返回的结果与上一轮逐字相同，在原地打转");
                    return;
                }
                post(() -> l.onToolRetryHint("这一步结果和上一轮一模一样，正在换方式…"));
            } else {
                noProgressStreak = 0;
                lastRoundFp = fp;
            }
        }

        // 3) 再请求一次，让模型基于工具结果继续
        //    先清掉当前连接，避免外层 finally 误断开新请求
        restartConnection();
        chainDepth++;
        continueRun(s, history, l);
    }

    /**
     * 模型只输出文字、准备收场时，判断这次收场该不该拦下来。
     *
     * <p>两种该拦的情况：
     * <ol>
     *   <li><b>阶段小结后自动续跑</b>：工具预算撞顶 → 让它写小结 → 小结写完不该就此结束，
     *       而应该按它自己写的「下一步」接着做。把「继续」两个字从用户手里拿回来，
     *       否则用户只能一遍遍点继续，每一遍都重新开荒；</li>
     *   <li><b>只说不做</b>：已经动过手（toolRound &gt; 0），这一轮却一个工具都没调，
     *       文字里全是「你可以执行」「建议这样」——典型的手动交差，催它自己动手。</li>
     * </ol>
     *
     * @return true 表示已经拦下并继续发起请求，调用方不要再报完成
     */
    private boolean maybeContinueAfterText(Settings s, List<Message> history,
                                          Listener l, String text) {
        if (text == null || text.trim().isEmpty()) return false;

        if (summarizeMode) {
            if (autoResume <= 0) return false;
            // 小结里明说做完了，就别硬续跑，否则用户会看到「已完成」之后又莫名跑一轮
            if (looksFinished(text)) return false;
            autoResume--;
            summarizeMode = false;
            toolRound = 0;
            lastProgressAt = android.os.SystemClock.elapsedRealtime();
            try {
                toolMsgs.add(new JSONObject().put("role", "assistant").put("content", text));
                toolMsgs.add(new JSONObject().put("role", "user").put("content",
                        "【系统】上面那段只是阶段小结，任务还没结束。现在按你自己写的「下一步」"
                        + "立刻动手执行：直接调用工具，不要再总结、不要再列计划、不要问我。\n"
                        + "如果小结里提到缺某个信息，先用工具自己去找（读文件、跑命令、看报错），"
                        + "只有确实需要我做决定时才停下来问。"));
            } catch (Exception ignored) {
            }
            post(() -> l.onToolRetryHint("接着上一段的进度继续做…"));
            restartConnection();
            chainDepth++;
            continueRun(s, history, l);
            return true;
        }

        // 一次工具都没跑过，说明这本来就是普通问答，不该拦
        if (toolRound <= 0) return false;
        if (nudgeCount >= MAX_NUDGE) return false;
        if (!looksLikeHandoff(text)) return false;
        nudgeCount++;
        try {
            toolMsgs.add(new JSONObject().put("role", "assistant").put("content", text));
            toolMsgs.add(new JSONObject().put("role", "user").put("content",
                    "【系统】你刚才只写了说明，没有调用任何工具，但这件活还没干完。"
                    + "不要把手上的活推给我——现在就调用工具，把你自己刚描述的那一步做掉。\n"
                    + "该跑命令就直接跑，该改文件就直接改。做完自己验证产物"
                    + "（文件在不在、能不能用、输出对不对、签名对不对），验证通过再用文字收尾。"
                    + "\n注意：改设备状态的动作（装 APK、卸载、拉起系统安装界面、改系统设置）"
                    + "一律不要自己做，检查完把结论说清楚，由用户点界面上的按钮完成。"));
        } catch (Exception ignored) {
        }
        post(() -> l.onToolRetryHint("你刚才只讲了做法，正在让它直接动手…"));
        restartConnection();
        chainDepth++;
        continueRun(s, history, l);
        return true;
    }

    /** 工具结果指纹：长度 + 每 7 个字符采样一次做散列，足以判断两次输出是否相同 */
    private static String fpOf(String r) {
        if (r == null || r.isEmpty()) return "0";
        int h = 1;
        for (int i = 0; i < r.length(); i += 7) h = h * 31 + r.charAt(i);
        return r.length() + ":" + Integer.toHexString(h);
    }

    /** 小结里是不是明确说了「已经做完」——这种情况下不该再自动续跑 */
    private static boolean looksFinished(String text) {
        if (text == null) return false;
        String t = text.replace(" ", "");
        boolean done = t.contains("全部完成") || t.contains("已完成") || t.contains("已经做完")
                || t.contains("修改完成") || t.contains("已经完成");
        boolean unfinished = t.contains("未完成") || t.contains("没完成") || t.contains("还没")
                || t.contains("卡在") || t.contains("还剩") || t.contains("剩下");
        return done && !unfinished;
    }

    /**
     * 这段文字是不是「把活推回给用户」的口吻。
     *
     * <p>只认明确的交差句式，不靠泛泛的关键词：模型正经汇报结论时也会出现
     * 「下一步」，误拦的代价是用户拿不到答案，比漏拦严重得多。
     */
    private static boolean looksLikeHandoff(String text) {
        if (text == null) return false;
        if (text.length() > 3000) return false;   // 长回答多半是完整结论，不要硬拦
        String t = text.replace(" ", "").replace("\u3000", "");
        String[] keys = {
                "你可以执行", "你可以运行", "你可以用下面", "建议你执行", "建议你运行",
                "需要你执行", "需要你手动", "请你执行", "请手动执行", "由你执行",
                "接下来你可以", "下一步你可以", "你自己执行", "你手动操作",
                "youcanrun", "youshouldrun", "pleaserun"
        };
        for (String k : keys) {
            if (t.contains(k)) return true;
        }
        return false;
    }

    /** 断开当前连接，避免 finally 误杀紧接着发起的新请求 */
    /**
     * 接着往下跑：正常情况下就在当前栈上递归，栈太深了才换线程。
     *
     * <p>长任务（解包 → 改包 → 重签 → 装）可能连续推进几十上百轮，
     * 每轮都会递归一层 doSend。一旦触到 {@link #MAX_CHAIN_DEPTH}，
     * 就先断开当前连接、把链深归零，再起一个干净线程从零栈继续，
     * 避免 StackOverflow 把整个 App 崩掉——对用户来说那比「停下来」更糟。
     */
    private void continueRun(final Settings s, final List<Message> history, final Listener l) {
        if (chainDepth < MAX_CHAIN_DEPTH) {
            doSend(s, history, l);
            return;
        }
        Log.i(TAG, "工具链已递归 " + chainDepth + " 层，换干净线程继续，避免栈溢出");
        chainDepth = 0;
        restartConnection();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                doSend(s, history, l);
            }
        }, "nebula-run-continue");
        t.setDaemon(true);
        t.start();
    }

    private void restartConnection() {
        HttpURLConnection old = conn;
        conn = null;
        if (old != null) {
            try {
                old.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    // ===================== 「没拿到结果就不许收工」的支撑方法 =====================

    /** 工具这次算不算「什么都没拿到」 */
    private boolean isEmptyResult(String r) {
        if (r == null) return true;
        String t = r.trim();
        if (t.isEmpty()) return true;
        if (t.equals("（无输出）") || t.equals("(无输出)")) return true;
        if (t.equalsIgnoreCase("") || t.equals("无")) return true;
        return false;
    }

    /**
     * 空结果时自己再跑一次。
     * 很多失败是瞬时的（进程刚起、文件句柄没释放、端口没就绪），
     * 重试一次往往就过去了；退了这一步，模型只能回一句「没拿到结果」然后收工。
     * 返回 null 表示期间用户点了停止。
     */
    private String retryToolOnce(ToolRegistry reg, String name, JSONObject args) {
        final String[] resHolder = new String[1];
        final boolean[] okHolder = new boolean[]{true};
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String r = reg.call(name, args);
                    resHolder[0] = r == null ? "" : r;
                } catch (Throwable t) {
                    okHolder[0] = false;
                    String m = t.getMessage();
                    resHolder[0] = "工具执行失败："
                            + (m == null ? t.getClass().getSimpleName() : m);
                }
            }
        }, "nebula-tool-retry-" + name);
        worker.setDaemon(true);
        worker.start();
        while (worker.isAlive() && !cancelled) {
            try {
                worker.join(150);
            } catch (InterruptedException ignored) {
            }
        }
        if (cancelled) return null;
        return resHolder[0] == null ? "" : resHolder[0];
    }

    private int bumpToolFailure(String name) {
        Integer v = toolFailCount.get(name);
        int n = (v == null ? 0 : v) + 1;
        toolFailCount.put(name, n);
        return n;
    }

    private void clearToolFailure(String name) {
        toolFailCount.remove(name);
        blockedTools.remove(name);
    }

    private void noteFailure(String name, String why) {
        if (failedNotes.size() >= 20) return;
        failedNotes.add(name + " · " + why);
    }

    /** 一行摘要，给回灌文本和兜底总结用 */
    private String briefLine(String r) {
        if (r == null) return "";
        String t = r.trim().replace('\n', ' ');
        return t.length() > 90 ? t.substring(0, 90) + "…" : t;
    }

    /** 把连续失败、已被临时禁用的工具从本轮 schema 里摘掉 */
    private JSONArray filterBlockedTools(JSONArray schema) {
        if (schema == null || blockedTools.isEmpty()) return schema;
        JSONArray out = new JSONArray();
        for (int i = 0; i < schema.length(); i++) {
            JSONObject o = schema.optJSONObject(i);
            if (o == null) continue;
            JSONObject fn = o.optJSONObject("function");
            String nm = fn == null ? "" : JsonOpt.str(fn, "name");
            if (blockedTools.contains(nm)) continue;
            out.put(o);
        }
        return out;
    }

    /**
     * 本地兜底结论：走到这里说明模型连一句收尾的话都没给出。
     * 那就别再指望它了——自己写一段有信息量的东西，用户永远不会看到空白结束。
     */
    private void localWrapUp(Listener l, String why) {
        StringBuilder sb = new StringBuilder();
        sb.append("**没有拿到明确结果：**")
                .append(why == null || why.trim().isEmpty() ? "工具调用没有返回有效内容" : why)
                .append("\n\n");
        if (!failedNotes.isEmpty()) {
            sb.append("**过程中没能完成的事**\n");
            int n = Math.min(failedNotes.size(), 8);
            for (int i = 0; i < n; i++) sb.append("- ").append(failedNotes.get(i)).append('\n');
            sb.append('\n');
        }
        if (!blockedTools.isEmpty()) {
            StringBuilder bs = new StringBuilder();
            for (String b : blockedTools) {
                if (bs.length() > 0) bs.append('、');
                bs.append(b);
            }
            sb.append("**已临时禁用的工具：**").append(bs).append("\n\n");
        }
        sb.append("**建议下一步**\n")
                .append("- 把任务拆成更小的一步重发（例如先只说「解开安装包并列出目录」）\n")
                .append("- 或先检查环境（网络／存储／权限），再让我重试同一件事\n");
        final String out = sb.toString();
        post(() -> l.onDone(out));
    }

    /** 非流式：按字节读取，先试 UTF-8 严格解码，失败自动按 GB18030 解码 */
    private String readWhole(InputStream in) throws Exception {
        String raw = decodeSmart(readBytes(in));
        if (looksLikeHtml(raw)) {
            throw new Exception("接口返回的是网页（HTML），不是接口数据，请检查 Base URL");
        }
        String text = tryParseWholeJson(raw);
        if (text != null) return text;
        JSONObject o = new JSONObject(raw);
        JSONObject err = o.optJSONObject("error");
        if (err != null) throw new Exception(JsonOpt.str(err, "message", "服务返回错误"));
        return "";
    }

    private String tryParseWholeJson(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(raw);
            // 非流式同样带 usage，这里一并接住，否则关掉流式后统计就散架
            JSONObject ug = o.optJSONObject("usage");
            if (ug != null) absorbUsage(ug);
            JSONArray choices = o.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                JSONObject ch = choices.optJSONObject(0);
                if (ch == null) return null;
                JSONObject msg = ch.optJSONObject("message");
                if (msg != null) {
                    String c = JsonOpt.str(msg, "content");
                    if (!c.isEmpty()) return c;
                }
                String t = JsonOpt.str(ch, "text");
                if (!t.isEmpty()) return t;
            }
            // 有些兼容服务把内容放在 data.content / result
            String c = JsonOpt.str(o, "content");
            if (!c.isEmpty()) return c;
            String r = JsonOpt.str(o, "result");
            if (!r.isEmpty()) return r;
        } catch (Exception ignored) {
        }
        return null;
    }

    private boolean looksLikeHtml(String s) {
        if (s == null) return false;
        String head = s.length() > 400 ? s.substring(0, 400) : s;
        String low = head.toLowerCase(Locale.US);
        return low.contains("<!doctype html") || low.contains("<html") || low.startsWith("<?xml");
    }

    /**
     * 按魔数判断是否 gzip 并自动解压：不依赖 Content-Encoding 头，最稳。
     */
    private InputStream wrap(InputStream in) throws Exception {
        PushbackInputStream pb = new PushbackInputStream(in, 2);
        byte[] b = new byte[2];
        int n = pb.read(b);
        if (n > 0) pb.unread(b, 0, n);
        if (n == 2 && (b[0] & 0xFF) == 0x1F && (b[1] & 0xFF) == 0x8B) {
            return new GZIPInputStream(pb);
        }
        return pb;
    }

    private InputStream openError(HttpURLConnection c) {
        try {
            InputStream in = c.getErrorStream();
            return in == null ? null : wrap(in);
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] readBytes(InputStream in) throws Exception {
        if (in == null) return new byte[0];
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    /**
     * 智能解码：严格 UTF-8 失败时回退 GB18030（兼容 GBK/GB2312）。
     * 部分中转网关会用 GBK 返回，直接按 UTF-8 读会出现 «é”™è¯¯» 这类乱码。
     */
    private String decodeSmart(byte[] data) {
        if (data == null || data.length == 0) return "";
        String utf8 = strictDecode(data, StandardCharsets.UTF_8);
        if (utf8 != null) return fixMojibake(utf8);
        String gb = strictDecode(data, Charset.forName("GB18030"));
        if (gb != null) return fixMojibake(gb);
        return fixMojibake(new String(data, StandardCharsets.UTF_8));
    }

    /**
     * 修复 «é”™è¯¯» 型乱码：网关把 UTF-8 字节当成 windows-1252 字符再发出来时，
     * 把字符串按 windows-1252 取回原始字节，再按 UTF-8 重新解码。
     */
    private String fixMojibake(String s) {
        if (s == null || s.isEmpty()) return s;
        int latin = 0, cjk = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x80) latin++;
            if (c >= 0x4E00 && c <= 0x9FFF) cjk++;
        }
        // 只有在"拉丁扩展字符明显多、几乎没有中文"时才尝试回滚
        if (latin < 4 || cjk > 2) return s;
        try {
            byte[] raw = s.getBytes(Charset.forName("windows-1252"));
            String fixed = new String(raw, StandardCharsets.UTF_8);
            if (fixed.indexOf('\uFFFD') >= 0) return s;
            int cjk2 = 0;
            for (int i = 0; i < fixed.length(); i++) {
                char c = fixed.charAt(i);
                if (c >= 0x4E00 && c <= 0x9FFF) cjk2++;
            }
            if (cjk2 > cjk) return fixed;
        } catch (Exception ignored) {
        }
        return s;
    }

    private String strictDecode(byte[] data, Charset cs) {
        try {
            CharsetDecoder dec = cs.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return dec.decode(ByteBuffer.wrap(data)).toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 文本里出现较多替换字符 → 编码不对 */
    private boolean looksGarbled(String s) {
        if (s == null || s.isEmpty()) return false;
        int bad = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\uFFFD') bad++;
        }
        return bad >= 3 && bad * 100 / s.length() > 3;
    }

    private String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        in.close();
        return bos.toString("UTF-8");
    }

    private String friendlyError(int code, String raw) {
        String detail = "";
        if (raw != null && !raw.isEmpty()) {
            try {
                JSONObject o = new JSONObject(raw);
                JSONObject e = o.optJSONObject("error");
                if (e != null) detail = JsonOpt.str(e, "message");
                if (detail.isEmpty()) detail = JsonOpt.str(o, "message");
            } catch (Exception ignored) {
                if (!looksLikeHtml(raw)) {
                    detail = raw;
                }
            }
        }
        // 上下文超限是长任务的常见终点，这里给中文处置建议。
        // 原来会把后端那句英文原文（甚至整段 JSON）直接甩进对话框，
        // 用户看到的就是「突然崩了 + 一屏英文代码」，完全不知道发生了什么。
        if (isContextOverflow(raw)) {
            return "这轮对话太长，超出了模型能记住的上限。\n"
                    + "已自动精简历史后重试；如果还是不行，点「新对话」开一轮更清爽的继续。";
        }
        // 网关错误页、SSE 片段这类原始内容对用户没有意义：压成一行短提示，
        // 免得一屏英文代码把对话框顶飞（完整原文只写日志，不占界面）。
        if (detail.length() > 120) {
            detail = detail.replaceAll("\\s+", " ").trim();
            if (detail.length() > 120) detail = detail.substring(0, 120) + "…";
        }
        String prefix;
        switch (code) {
            case 400:
                prefix = "请求参数有误（400）";
                break;
            case 401:
                prefix = "API Key 无效或未授权（401）";
                break;
            case 402:
                prefix = "账户余额不足（402）";
                break;
            case 403:
                prefix = "无权限访问（403）";
                break;
            case 404:
                prefix = "接口地址不存在（404），请检查 Base URL";
                break;
            case 429:
                prefix = "请求太频繁，请稍后再试（429）";
                break;
            default:
                prefix = code >= 500 ? "服务端错误（" + code + "）" : "请求失败（" + code + "）";
        }
        return detail.isEmpty() ? prefix : prefix + "\n" + detail;
    }
}
