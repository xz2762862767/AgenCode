import io, sys

P = 'app/src/main/java/com/nebula/aichat/net/ChatClient.java'
s = io.open(P, encoding='utf-8').read()
orig = s

def rep(old, new, cnt=1):
    global s
    if s.count(old) < cnt:
        print('MISS:', old[:70].replace('\n', '\\n'))
        sys.exit(1)
    s = s.replace(old, new, cnt)

# 1) 单条工具结果上限
rep('''    /** 单个工具结果回传给模型时的最大长度 */
    private static final int MAX_TOOL_RESULT = 8000;''',
'''    /**
     * 单个工具结果回传给模型时的最大长度。
     * 原来是 8000 字符（≈4000 token），一轮调 5 个工具就是 2 万 token 起步，
     * 而且这些内容在后续每轮工具调用里都会重复回传，这是「余额掉得比别家快」的头号原因。
     * 现在收到 4000 字符，并且保留头部 60% + 尾部 40%（报错和结论基本都在尾部）。
     */
    private static final int MAX_TOOL_RESULT = 4000;''')

# 2) 续写次数
rep('''    private static final int MAX_CONTINUE = 3;''',
'''    private static final int MAX_CONTINUE = 2;''')

# 3) 工具上下文总预算
rep('''    private static final int MAX_TOOL_CONTEXT_CHARS = 60000;''',
'''    private static final int MAX_TOOL_CONTEXT_CHARS = 24000;''')

# 4) 历史预算字段
rep('''    /** 大于 0 时只带最后 N 条历史，用于上下文超限时的紧急瘦身 */
    private int ctxHistoryKeep = -1;''',
'''    /** 大于 0 时只带最后 N 条历史，用于上下文超限时的紧急瘦身 */
    private int ctxHistoryKeep = -1;
    /**
     * 历史消息的总字符预算（中文约 1 字≈0.7 token）。
     * 原来 ctxHistoryKeep=-1 意味着「每次请求都把全部历史重发一遍」：
     * 聊到第 30 轮，第 30 次请求光历史就是几万 token，累计是 O(n²)，
     * 这才是「同样问法在这儿比别家掉得快」的真正根因，不是模型贵。
     */
    private static final int MAX_HISTORY_CHARS = 24000;
    private int historyBudget = MAX_HISTORY_CHARS;''')

# 5) send() 重置
rep('''        ctxShrink = 0;
        ctxHistoryKeep = -1;''',
'''        ctxShrink = 0;
        ctxHistoryKeep = -1;
        historyBudget = MAX_HISTORY_CHARS;''')

# 6) 超限收缩时同步压历史预算
rep('''                    if (ctxShrink == 1) {
                        ctxBudget = 12000;
                    } else {
                        ctxBudget = 2000;
                        ctxHistoryKeep = 4;
                    }''',
'''                    if (ctxShrink == 1) {
                        ctxBudget = 12000;
                        historyBudget = 12000;
                    } else {
                        ctxBudget = 2000;
                        historyBudget = 6000;
                        ctxHistoryKeep = 4;
                    }''')

# 7) 历史裁剪主体
rep('''        if (sys.length() > 0) {
            msgs.put(new JSONObject().put("role", "system").put("content", sys.toString()));
        }
        int hFrom = ctxHistoryKeep > 0 ? Math.max(0, history.size() - ctxHistoryKeep) : 0;
        for (int hi = hFrom; hi < history.size(); hi++) {
            Message m = history.get(hi);
            String c;
            if (m.apiContent != null && !m.apiContent.trim().isEmpty()) {
                c = m.apiContent;
            } else if (m.attachNote != null && !m.attachNote.trim().isEmpty()) {
                // 旧会话：完整内容没存盘，但附件路径还在，续接上下文仍能找到文件
                c = m.attachNote + "\\n" + (m.content == null ? "" : m.content);
            } else {
                c = m.content;
            }
            if (c == null || c.trim().isEmpty()) continue;''',
'''        // ===== 历史裁剪（省 token 的核心）=====
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
            sys.append("\\n\\n【历史裁剪】为控制开销，中间 ").append(hFrom)
                    .append(" 条较早消息没有带入上下文（最初的需求会单独保留）。\\n")
                    .append("1. 需要旧细节就调用工具重新查看当前文件/目录状态，不要凭记忆猜。\\n")
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
                        .put("content", "【最开始的需求】\\n" + ac));
            }
        }
        for (int hi = hFrom; hi < history.size(); hi++) {
            Message m = history.get(hi);
            String c = apiText(m);
            if (c == null || c.trim().isEmpty()) continue;''')

# 8) 截断保留头尾
rep('''            if (result.length() > MAX_TOOL_RESULT) {
                result = result.substring(0, MAX_TOOL_RESULT) + "\\n…（结果过长已截断）";
            }''',
'''            if (result.length() > MAX_TOOL_RESULT) {
                // 头部常是编译/运行的噪音，尾部才是报错和结论，所以头 60% + 尾 40%
                int head = (int) (MAX_TOOL_RESULT * 0.6);
                int tail = MAX_TOOL_RESULT - head;
                result = result.substring(0, head)
                        + "\\n…（中间省略 " + (result.length() - MAX_TOOL_RESULT) + " 字）…\\n"
                        + result.substring(result.length() - tail);
            }''')

# 9) 辅助方法，插在 buildBody 之前
rep('''    private JSONObject buildBody(Settings s, List<Message> history) throws Exception {''',
'''    /** 一条消息真正发给模型的文本（兼容旧会话的附件说明） */
    private String apiText(Message m) {
        if (m == null) return "";
        if (m.apiContent != null && !m.apiContent.trim().isEmpty()) return m.apiContent;
        if (m.attachNote != null && !m.attachNote.trim().isEmpty()) {
            // 旧会话：完整内容没存盘，但附件路径还在，续接上下文仍能找到文件
            return m.attachNote + "\\n" + (m.content == null ? "" : m.content);
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

    private JSONObject buildBody(Settings s, List<Message> history) throws Exception {''')

io.open(P, 'w', encoding='utf-8').write(s)
print('OK, %d -> %d bytes' % (len(orig), len(s)))
