package com.nebula.aichat.net;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.nebula.aichat.data.Message;
import com.nebula.aichat.data.Settings;
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
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled = false;
    private volatile HttpURLConnection conn;
    /** 部分网关不认识 reasoning_effort 会返回 400，此时自动去掉该参数重试一次 */
    private boolean allowThinkParam = true;
    /** 网关没有 deepseek-reasoner 时会在这里记住，之后不再尝试，静默用基础模型 */
    private static volatile boolean reasonerUnavailable = false;
    /**
     * 老版本推理模型/部分网关不接受 tools 参数。
     * 现在默认给推理模型也下发工具（新版 reasoner 已支持 function calling），
     * 只有真被服务端拒绝过一次，才在这里记住并永久关掉，避免每次都白试一轮。
     */
    private static volatile boolean reasonerNoTools = false;
    /** onStart 只回调一次（降级重试时不要重复） */
    private boolean startSent = false;

    /** 额外的系统提示（如启用中的技能），会拼接在用户设置之后 */
    public volatile String extraSystem = null;

    /** 工具注册表：为 null 表示本次对话不使用工具 */
    public volatile ToolRegistry tools = null;

    /** 一轮用户提问内最多允许多少轮工具调用，防止死循环 */
    /** 工具调用轮次上限：改 APK 这类任务十几步很常见，8 轮必然被掐断 */
    private static final int MAX_TOOL_ROUNDS = 120;
    /**
     * 工具阶段的时间预算（毫秒）。轮次上限管不住「慢循环」——
     * 实测出现过一轮跑 47 次工具、5 分钟还停不下来的情况（每个来回都读一次文件、
     * 改一行、再读回来），屏幕全程刷英文代码。到点就收尾，进度不丢，可一键继续。
     */
    private static final long MAX_TOOL_MS = 240_000L;

    /** 收尾模式：摘掉 tools 参数，逼模型把进度/卡点用文字讲清楚 */
    private boolean summarizeMode = false;
    /** 同一批工具调用连续重复这么多次，就当作在原地打转 */
    private static final int MAX_SAME_CALL = 5;
    /**
     * 单个工具结果回传给模型时的最大长度。
     * 原来是 8000 字符（≈4000 token），一轮调 5 个工具就是 2 万 token 起步，
     * 而且这些内容在后续每轮工具调用里都会重复回传，这是「余额掉得比别家快」的头号原因。
     * 现在收到 4000 字符，并且保留头部 60% + 尾部 40%（报错和结论基本都在尾部）。
     */
    private static final int MAX_TOOL_RESULT = 4000;
    private int toolRound = 0;
    /**
     * 被 max_tokens 截断后的自动续写次数。
     * 长 smali/长代码很容易撞到 max_tokens，原来 App 不解析 finish_reason，
     * 把「说到一半被砍断」当成正常结束，用户看到的就是「一堆英文代码然后突然停了」。
     */
    private static final int MAX_CONTINUE = 2;
    private int contCount = 0;
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
    private static final int MAX_HISTORY_CHARS = 24000;
    private int historyBudget = MAX_HISTORY_CHARS;

    /** 本次提问里第一次工具调用开始的时间戳，用于工具阶段时间预算 */
    private long toolPhaseStart = 0L;
    private String lastCallSig = "";
    private int sameCallRepeat = 0;
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
    /** 失败记录，用于最后兜底写一段能看的结论 */
    private final List<String> failedNotes = new ArrayList<>();
    /** 同一工具连续失败这么多次就临时摘掉它 */
    private static final int EMPTY_BLOCK_AFTER = 2;
    /** 连续这么多轮全部无结果，才转入收尾模式 */
    private static final int MAX_EMPTY_ROUNDS = 6;

    public boolean isRunning() {
        return !cancelled;
    }

    public void cancel() {
        cancelled = true;
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
        contPrefix = "";
        ctxBudget = MAX_TOOL_CONTEXT_CHARS;
        ctxShrink = 0;
        ctxHistoryKeep = -1;
        historyBudget = MAX_HISTORY_CHARS;
        toolPhaseStart = 0L;
        summarizeMode = false;
        lastCallSig = "";
        sameCallRepeat = 0;
        toolMsgs.clear();
        toolFailCount.clear();
        blockedTools.clear();
        failedNotes.clear();
        noResultCalls = 0;
        emptyRoundStreak = 0;
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
                // 网关没有推理模型 → 退回基础模型重试一次
                if (!reasonerUnavailable && Settings.isReasonerModel(curModel) && isModelRejected(err)) {
                    reasonerUnavailable = true;
                    Log.w(TAG, "网关不支持 " + curModel + "，退回 " + Settings.baseModel(s) + " 重试");
                    try {
                        c.disconnect();
                    } catch (Exception ignored) {
                    }
                    conn = null;
                    if (!cancelled) doSend(s, history, l);
                    return;
                }
                // 该模型/网关不接受工具调用 → 永久关掉工具后重试一次
                boolean toolsSent = tools != null && tools.hasTools() && !summarizeMode && toolsUsable(curModel);
                if (toolsSent && isToolsRejected(err)) {
                    reasonerNoTools = true;
                    Log.w(TAG, "该模型不接受 tools，关掉工具重试");
                    try {
                        c.disconnect();
                    } catch (Exception ignored) {
                    }
                    conn = null;
                    if (!cancelled) doSend(s, history, l);
                    return;
                }
                // 后端不认识思考参数 → 去掉后静默重试一次，保证「思考强度」不会把请求搞挂
                boolean thinkSent = allowThinkParam && Settings.reasoningEffort(s.thinkLevel) != null;
                if (thinkSent && isParamRejected(err)) {
                    Log.w(TAG, "后端不接受 reasoning_effort，去除后重试");
                    allowThinkParam = false;
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
                if (isContextOverflow(err) && ctxShrink < 2) {
                    ctxShrink++;
                    if (ctxShrink == 1) {
                        ctxBudget = 12000;
                        historyBudget = 12000;
                    } else {
                        ctxBudget = 2000;
                        historyBudget = 6000;
                        ctxHistoryKeep = 4;
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

            // 只有思考内容（reasoning 模型）时，兜底展示思考文本
            if (text.isEmpty() && st.reasoning.length() > 0) {
                text = st.reasoning.toString();
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
            HttpURLConnection c = conn;
            if (c != null) c.disconnect();
            conn = null;
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

    /** 本次请求能不能带工具：普通模型始终能；推理模型默认能，被拒过才关 */
    private static boolean toolsUsable(String model) {
        if (!Settings.isReasonerModel(model)) return true;
        return !reasonerNoTools;
    }

    /** 启用工具时强制走流式：非流式无法可靠拿到分片 tool_calls */
    private boolean bodyStreaming(Settings s) {
        return s.stream || (tools != null && tools.hasTools());
    }

    /** 实际使用的模型：思考强度升级 + 网关不支持时自动退回 */
    private String pickModel(Settings s) {
        String m = Settings.effectiveModel(s);
        if (reasonerUnavailable && Settings.isReasonerModel(m)) return Settings.baseModel(s);
        return m;
    }

    /**
     * 工具上下文瘦身：一次提问里几十轮工具调用会让 toolMsgs 累积到几百 KB，
     * 请求体越来越大、每轮越来越慢，最后容易超时或被网关拒掉（表现为「跑很久没结果」）。
     * tool 消息必须与 assistant.tool_calls 成对，不能删，所以只把较早结果的正文换成占位。
     */
    private static final int MAX_TOOL_CONTEXT_CHARS = 24000;

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

    /** 从最新一条往回凑够 historyBudget，返回窗口起始下标 */
    private int historyWindowStart(List<Message> history) {
        if (history == null || history.isEmpty()) return 0;
        int acc = 0;
        int i = history.size() - 1;
        for (; i >= 0; i--) {
            Message m = history.get(i);
            if (m == null || Message.ROLE_SYSTEM.equals(m.role)) continue;
            int len = apiText(m).length();
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
        trimToolContext();
        final String model = pickModel(s);
        // 推理模型自带思维链：不吃 temperature，也不支持 function calling
        final boolean reasoner = Settings.isReasonerModel(model);

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
        if (tools != null && tools.hasTool("run_shell") && toolsUsable(model)) {
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
        String hint = Settings.thinkHint(s.thinkLevel);
        if (!hint.isEmpty()) {
            if (sys.length() > 0) sys.append("\n\n");
            sys.append(hint);
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
        sys.append("【连续推进：不许做一半就停】\n"
                + "1. 用户给的是一件事，就一路做到底：理解 → 计划 → 执行 → 验证 → 汇报。"
                + "不允许做完中间一步就结束回复、把剩下的丢给用户。\n"
                + "2. 一步做完自动进下一步，不要问「要我继续吗」「需要我接着做吗」——"
                + "除非涉及不可逆操作（删数据、覆盖用户文件、清空目录）、花钱、或缺少必要信息/权限。\n"
                + "3. 工具报错不等于任务结束：读报错 → 定位原因 → 换方案重试。"
                + "实在绕不过去，才停下来明确告诉用户卡在哪、缺什么，并给可执行的替代方案。\n"
                + "4. 只要还有能自己动手做的下一步，就继续调用工具做，不要写「建议你执行以下步骤」把活推回给用户。\n"
                + "5. 收尾只有一个条件：任务真的完成，或确实无路可走。"
                + "收尾必须用中文说明三件事：完成了什么、产物在哪（给完整路径）、还剩什么没做。");
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
        if (sys.length() > 0) {
            msgs.put(new JSONObject().put("role", "system").put("content", sys.toString()));
        }
        if (clipped) {
            // 任务锚点：最早那条用户需求，防止长任务中忘掉最初目标
            Message anchor = firstUserBefore(history, hFrom);
            String ac = anchor == null ? "" : apiText(anchor);
            if (!ac.isEmpty()) {
                msgs.put(new JSONObject().put("role", "user")
                        .put("content", "【最开始的需求】\n" + ac));
            }
        }
        for (int hi = hFrom; hi < history.size(); hi++) {
            Message m = history.get(hi);
            String c = apiText(m);
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
        body.put("stream", bodyStreaming(s));
        if (!reasoner) {
            body.put("temperature", (double) s.temperature);
        }
        body.put("max_tokens", s.maxTokens);

        // 工具注入：内置 APK 工具 + MCP 工具。
        // 推理模型（deepseek-reasoner 新版）同样支持 function calling，一律下发；
        // 只有真被网关拒绝过的才由 toolsUsable() 关掉。
        if (tools != null && toolsUsable(model) && !summarizeMode) {
            JSONArray schema = filterBlockedTools(tools.toSchema());
            if (schema != null && schema.length() > 0) {
                body.put("tools", schema);
                body.put("tool_choice", "auto");
            }
        }

        // 思考强度：reasoner 本身就在思考，不需要再发 effort；其余模型按档位发
        String effort = Settings.reasoningEffort(s.thinkLevel);
        if (allowThinkParam && effort != null && !reasoner) {
            body.put("reasoning_effort", effort);
        }
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
        HttpURLConnection old = conn;
        conn = null;
        if (old != null) {
            try {
                old.disconnect();
            } catch (Exception ignored) {
            }
        }
        doSend(s, history, l);
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
        if (toolRound >= MAX_TOOL_ROUNDS) {
            startSummarize(s, history, l,
                    "工具调用已经跑了 " + MAX_TOOL_ROUNDS + " 轮仍未结束");
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (toolPhaseStart == 0L) toolPhaseStart = now;
        if (now - toolPhaseStart > MAX_TOOL_MS) {
            startSummarize(s, history, l,
                    "工具调用已连续跑了 " + ((now - toolPhaseStart) / 1000) + " 秒、"
                            + toolRound + " 轮，先停下来汇报进度");
            return;
        }
        toolRound++;
        noResultCalls = 0;
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
        if (sig.equals(lastCallSig)) {
            sameCallRepeat++;
        } else {
            lastCallSig = sig;
            sameCallRepeat = 1;
        }
        if (sameCallRepeat >= MAX_SAME_CALL) {
            final String stuckTool = names.get(0);
            startSummarize(s, history, l,
                    "「" + stuckTool + "」连续 " + MAX_SAME_CALL + " 次重复调用、没有进展");
            return;
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
            worker.start();
            while (worker.isAlive() && !cancelled) {
                try {
                    worker.join(150);
                } catch (InterruptedException ignored) {
                }
            }
            if (cancelled) {
                // 杀掉 proot 里正在跑的进程，别让它继续吃 CPU
                com.nebula.aichat.linux.LinuxEnv.abortCurrent();
                post(() -> l.onToolResult(name, false, "已被用户停止"));
                return;
            }

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
        }

        // 3) 再请求一次，让模型基于工具结果继续
        //    先清掉当前连接，避免外层 finally 误断开新请求
        HttpURLConnection old = conn;
        conn = null;
        if (old != null) {
            try {
                old.disconnect();
            } catch (Exception ignored) {
            }
        }
        doSend(s, history, l);
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
                    detail = raw.length() > 300 ? raw.substring(0, 300) : raw;
                }
            }
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
