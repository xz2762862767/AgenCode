package com.nebula.aichat.data;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 任务现场的落盘存档。
 *
 * <p>为什么需要它：{@code ChatClient} 里的 lastWorkbench / lastCalls / interruptAt
 * 全是内存字段。改 APK 这种活一动就是几分钟，用户切出去干别的，App 很容易被系统回收；
 * 进程一死这三个字段直接归零，用户回来点「继续」，模型面对的是一张白纸，
 * 只能重新解包 200MB、重新找文件——看起来跑了很多趟，实际一步都没往前走。
 *
 * <p>所以这里把现场按「磁盘为准、内存为缓存」保存一份：
 * 每次工具跑完写一次，进程重启后由 ChatClient 读回来接着用。
 */
public final class WorkbenchStore {

    private static final String SP = "nebula_workbench";
    private static final String K_PATHS = "paths";     // 用过的工作路径，换行分隔
    private static final String K_CALLS = "calls";     // 最后几次动手，\u0001 分隔
    private static final String K_TOOL = "tool";       // 中断时正在跑的工具
    private static final String K_TS = "interrupt_ts"; // 中断时刻，0 表示没被中断过
    private static final String K_SAVED = "saved_at";  // 落盘时刻

    /** calls 内部的条目分隔符：单条内容本身是多行的，不能用换行拼 */
    private static final String SEP = "\u0001";

    /**
     * 现场最多留一天。再久就不可信了——解包目录多半已经被用户清掉、
     * 或者被别的任务覆盖过，硬拿旧路径去续反而会把新任务带偏。
     */
    public static final long MAX_AGE_MS = 24L * 60 * 60 * 1000;

    private WorkbenchStore() {
    }

    /** 从磁盘读回来的一份现场 */
    public static final class Snapshot {
        public final String paths;
        public final String calls;
        public final String tool;
        public final long interruptTs;
        public final long savedAt;

        Snapshot(String paths, String calls, String tool, long interruptTs, long savedAt) {
            this.paths = paths == null ? "" : paths;
            this.calls = calls == null ? "" : calls;
            this.tool = tool == null ? "" : tool;
            this.interruptTs = interruptTs;
            this.savedAt = savedAt;
        }

        public boolean isEmpty() {
            return paths.isEmpty() && calls.isEmpty();
        }
    }

    /** 工具每跑完一次就调一次；{@code apply()} 是异步写，不会卡住工具线程 */
    public static void save(Context c, String paths, java.util.List<String> calls,
                            String tool, long interruptTs) {
        if (c == null) return;
        StringBuilder sb = new StringBuilder();
        if (calls != null) {
            for (String one : calls) {
                if (one == null || one.isEmpty()) continue;
                if (sb.length() > 0) sb.append(SEP);
                sb.append(one);
            }
        }
        try {
            c.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
                    .putString(K_PATHS, paths == null ? "" : paths)
                    .putString(K_CALLS, sb.toString())
                    .putString(K_TOOL, tool == null ? "" : tool)
                    .putLong(K_TS, interruptTs)
                    .putLong(K_SAVED, System.currentTimeMillis())
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    /** 读回现场；过期、为空、或从来没过盘都返回 null */
    public static Snapshot load(Context c) {
        if (c == null) return null;
        try {
            SharedPreferences sp = c.getSharedPreferences(SP, Context.MODE_PRIVATE);
            long savedAt = sp.getLong(K_SAVED, 0L);
            if (savedAt <= 0L) return null;
            if (System.currentTimeMillis() - savedAt > MAX_AGE_MS) {
                // 过期就顺手清掉，别留着误导下一次
                clear(c);
                return null;
            }
            Snapshot s = new Snapshot(
                    sp.getString(K_PATHS, ""),
                    sp.getString(K_CALLS, ""),
                    sp.getString(K_TOOL, ""),
                    sp.getLong(K_TS, 0L),
                    savedAt);
            return s.isEmpty() ? null : s;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 按 SEP 拆回条目列表 */
    public static java.util.List<String> splitCalls(String calls) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (calls == null || calls.isEmpty()) return out;
        for (String one : calls.split(SEP)) {
            if (one != null && !one.isEmpty()) out.add(one);
        }
        return out;
    }

    public static void clear(Context c) {
        if (c == null) return;
        try {
            c.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().clear().apply();
        } catch (Throwable ignored) {
        }
    }
}
