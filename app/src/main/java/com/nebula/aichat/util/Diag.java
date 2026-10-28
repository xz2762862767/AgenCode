package com.nebula.aichat.util;

import android.app.ActivityManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Debug;
import android.os.Environment;
import android.os.PowerManager;
import android.os.StatFs;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 现场采集：给"没有 root、读不到系统 logcat / dumpsys"的手机补一套替代方案。
 *
 * 为什么要自己造：
 *  - logcat 从 Android 10 起对普通应用关门（读 /dev/socket/logdr 需要 READ_LOGS），
 *    `logcat` 命令能跑起来但只会返回权限错误；
 *  - dumpsys 同理，非系统应用拿不到输出；
 *  - 但排查闪退真正需要的（内存水位、进程优先级、是否被电池优化限制、被 trim 的记录）
 *    全都能用公开 API + /proc 读到，不需要 root。
 *
 * 所以这里不假装自己是 logcat，而是把等价信息直接采出来，
 * 既给界面用，也给 run_shell 里的 `logcat` / `dumpsys` 内置命令兜底。
 */
public final class Diag {

    private Diag() {
    }

    // ------------------------------------------------------------ 日志文件

    /** 私有 diag 目录（无需任何权限，一定读得到） */
    public static File privateDir(Context c) {
        File d = c.getExternalFilesDir("diag");
        if (d != null && !d.exists()) d.mkdirs();
        return d;
    }

    /** 下载目录里的对外日志（用户可在文件管理器直接翻） */
    public static File publicDir() {
        return new File(Environment.getExternalStorageDirectory(), "Download/AgenCode");
    }

    /** 所有日志文件，新的在前 */
    public static List<File> logFiles(Context c) {
        List<File> out = new ArrayList<File>();
        collect(privateDir(c), out);
        collect(publicDir(), out);
        Collections.sort(out, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        return out;
    }

    private static void collect(File dir, List<File> out) {
        if (dir == null) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isFile() && f.getName().endsWith(".txt") && f.length() > 0) out.add(f);
        }
    }

    /** 读文件尾部（日志可能很长，只取最后 maxBytes 字节，避免一次读爆内存） */
    public static String read(File f, int maxBytes) {
        if (f == null || !f.exists() || !f.canRead()) return "(无法读取: " + (f == null ? "?" : f.getName()) + ")";
        FileInputStream in = null;
        try {
            long len = f.length();
            long skip = Math.max(0, len - maxBytes);
            in = new FileInputStream(f);
            long skipped = 0;
            while (skipped < skip) {
                long n = in.skip(skip - skipped);
                if (n <= 0) break;
                skipped += n;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            boolean first = true;
            while ((line = r.readLine()) != null) {
                if (first && skip > 0) {
                    // 跳过被截断的半行
                    first = false;
                    continue;
                }
                first = false;
                sb.append(line).append('\n');
            }
            r.close();
            in = null;
            if (skip > 0) sb.insert(0, "…（只显示最后 " + (maxBytes / 1024) + "KB，文件共 "
                    + (len / 1024) + "KB）\n\n");
            return sb.toString();
        } catch (Throwable t) {
            return "(读取失败: " + t.getClass().getSimpleName() + ")";
        } finally {
            if (in != null) try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 全部日志合并成一段文本（给 run_shell 的 logcat 命令用） */
    public static String allLogs(Context c, int perFileBytes) {
        List<File> fs = logFiles(c);
        if (fs.isEmpty()) return "(暂无日志文件)";
        StringBuilder sb = new StringBuilder();
        for (File f : fs) {
            sb.append("===== ").append(f.getName()).append("  ")
                    .append(new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
                            .format(new Date(f.lastModified())))
                    .append("  ").append(f.length() / 1024).append("KB =====\n");
            sb.append(read(f, perFileBytes)).append('\n');
        }
        return sb.toString();
    }

    public static void clearAll(Context c) {
        for (File f : logFiles(c)) {
            try {
                f.delete();
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------ 系统真实 logcat

    /**
     * 试一次真正的 logcat。
     * 10 以上基本都会被拒绝，这里如实返回原因，而不是假装成功给一片空白——
     * 排查时"为什么读不到"本身就是结论。
     */
    public static String rawLogcat(int lines) {
        StringBuilder sb = new StringBuilder();
        Process p = null;
        try {
            p = new ProcessBuilder("/system/bin/logcat", "-d", "-t", String.valueOf(lines))
                    .redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            int n = 0;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
                if (++n > lines + 50) break;
            }
            r.close();
            p.waitFor();
        } catch (Throwable t) {
            sb.append("logcat 启动失败: ").append(t.getClass().getSimpleName()).append('\n');
        } finally {
            if (p != null) try {
                p.destroy();
            } catch (Throwable ignored) {
            }
        }
        String s = sb.toString().trim();
        if (s.isEmpty()) {
            return "logcat 无输出。\n\n"
                    + "Android 10 起普通应用被禁止读取系统日志（需要 READ_LOGS 签名权限），\n"
                    + "所以这条命令在没 root / 没装 Shizuku 的机器上拿不到任何内容。\n"
                    + "改用内置诊断：设置 → 运行诊断，或 shell 命令 `dumpsys`。\n";
        }
        if (s.contains("Permission denied") || s.contains("permission")) {
            return "logcat 被系统拒绝（未 root，无 READ_LOGS 权限）：\n\n" + s
                    + "\n改用内置诊断：设置 → 运行诊断，或 shell 命令 `dumpsys`。\n";
        }
        return s;
    }

    // ------------------------------------------------------------ 快照

    /** 一份可直接贴进问题的文本快照，等价于精简版 dumpsys */
    public static String snapshot(Context c) {
        StringBuilder sb = new StringBuilder();
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());

        sb.append("===== AgenCode 诊断快照 =====\n");
        sb.append("时间: ").append(ts).append('\n');
        sb.append("版本: ").append(ver(c)).append('\n');
        sb.append("机型: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append("  (").append(Build.DEVICE).append(")\n");
        sb.append("系统: Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT)
                .append(")  ROM=").append(Build.DISPLAY).append('\n');
        sb.append("ABI: ").append(Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?").append('\n');
        sb.append("pid: ").append(android.os.Process.myPid()).append('\n');

        sb.append('\n').append(memory(c));
        sb.append('\n').append(power(c));
        sb.append('\n').append(storage(c));
        sb.append('\n').append(fgs(c));

        List<File> logs = logFiles(c);
        sb.append("\n----- 日志文件（共 ").append(logs.size()).append(" 个）-----\n");
        if (logs.isEmpty()) sb.append("(无)\n");
        for (int i = 0; i < logs.size() && i < 12; i++) {
            File f = logs.get(i);
            sb.append("  ").append(f.getName()).append("  ")
                    .append(f.length() / 1024).append("KB  ")
                    .append(new SimpleDateFormat("MM-dd HH:mm", Locale.US)
                            .format(new Date(f.lastModified())))
                    .append('\n');
        }
        if (logs.size() > 12) sb.append("  …还有 ").append(logs.size() - 12).append(" 个\n");
        return sb.toString();
    }

    /** 内存段：判断闪退是不是被 OOM / LMK 干掉的关键证据 */
    public static String memory(Context c) {
        StringBuilder sb = new StringBuilder("----- 内存 -----\n");
        Runtime r = Runtime.getRuntime();
        long used = (r.totalMemory() - r.freeMemory()) / 1048576L;
        long max = r.maxMemory() / 1048576L;
        long total = r.totalMemory() / 1048576L;
        sb.append("Java 堆: 已用 ").append(used).append("MB / 已申请 ").append(total)
                .append("MB / 上限 ").append(max).append("MB")
                .append("  (").append(max > 0 ? used * 100 / max : 0).append("%)\n");

        try {
            Debug.MemoryInfo mi = new Debug.MemoryInfo();
            Debug.getMemoryInfo(mi);
            sb.append("进程 PSS: 总计 ").append(mi.getTotalPss() / 1024).append("MB");
            if (Build.VERSION.SDK_INT >= 23) {
                sb.append("  Java=").append(mi.getMemoryStat("summary.java-heap")).append("KB")
                        .append("  Native=").append(mi.getMemoryStat("summary.native-heap")).append("KB")
                        .append("  Graphics=").append(mi.getMemoryStat("summary.graphics")).append("KB")
                        .append("  Code=").append(mi.getMemoryStat("summary.code")).append("KB")
                        .append("  Stack=").append(mi.getMemoryStat("summary.stack")).append("KB");
            }
            sb.append('\n');
        } catch (Throwable ignored) {
        }

        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo m = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(m);
            sb.append("系统内存: 可用 ").append(m.availMem / 1048576L).append("MB")
                    .append(" / 共 ").append(m.totalMem / 1048576L).append("MB")
                    .append("  低内存阈值 ").append(m.threshold / 1048576L).append("MB")
                    .append("  lowMemory=").append(m.lowMemory).append('\n');
            sb.append("largeHeap=").append((c.getApplicationInfo().flags
                    & android.content.pm.ApplicationInfo.FLAG_LARGE_HEAP) != 0).append('\n');
        } catch (Throwable ignored) {
        }

        sb.append("oom_score_adj: ").append(procLine("/proc/self/oom_score_adj", "?"))
                .append("   (0=普通后台, 负数=前台/服务, 越高越先被杀)\n");
        sb.append("线程数: ").append(procLine("/proc/self/status", "Threads:"))
                .append("  VmRSS: ").append(procLine("/proc/self/status", "VmRSS:"))
                .append('\n');
        return sb.toString();
    }

    /** 电池与后台限制：常驻被杀的第一嫌疑人 */
    public static String power(Context c) {
        StringBuilder sb = new StringBuilder("----- 电池 / 后台 -----\n");
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            boolean ok = pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
            sb.append("电池优化: ").append(ok ? "已设为「不限制」✓" : "受系统限制（长任务可能被掐断）").append('\n');
            if (pm != null && Build.VERSION.SDK_INT >= 23) {
                sb.append("省电模式: ").append(pm.isPowerSaveMode()).append('\n');
            }
        } catch (Throwable t) {
            sb.append("电池优化: 读取失败 ").append(t.getClass().getSimpleName()).append('\n');
        }
        try {
            Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                int lv = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int sc = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                int tp = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
                sb.append("电量: ").append(sc > 0 ? (lv * 100 / sc) : -1).append("%")
                        .append("  充电中: ").append(st == BatteryManager.BATTERY_STATUS_CHARGING
                                || st == BatteryManager.BATTERY_STATUS_FULL)
                        .append("  温度: ").append(tp > 0 ? (tp / 10f) : -1).append("°C\n");
            }
        } catch (Throwable ignored) {
        }
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            sb.append("通知权限: ").append(nm != null && nm.areNotificationsEnabled() ? "已开" : "已关（提醒看不到）")
                    .append('\n');
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    public static String storage(Context c) {
        StringBuilder sb = new StringBuilder("----- 存储 -----\n");
        try {
            StatFs data = new StatFs(Environment.getDataDirectory().getAbsolutePath());
            long free = data.getAvailableBytes();
            long all = data.getTotalBytes();
            sb.append("内部存储: 可用 ").append(free / 1048576L).append("MB / 共 ")
                    .append(all / 1048576L).append("MB\n");
            if (free < 500L * 1048576L) {
                sb.append("⚠ 剩余空间偏少，安装/解压 APK 可能失败\n");
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    public static String fgs(Context c) {
        StringBuilder sb = new StringBuilder("----- 组件 -----\n");
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningServiceInfo> rs = am.getRunningServices(30);
            boolean alive = false;
            if (rs != null) {
                for (ActivityManager.RunningServiceInfo s : rs) {
                    if (s.service != null && s.service.getPackageName().equals(c.getPackageName())) {
                        alive = true;
                        sb.append("保活服务: ").append(s.service.getClassName())
                                .append("  foreground=").append(s.foreground).append('\n');
                    }
                }
            }
            if (!alive) sb.append("保活服务: 未运行\n");
            boolean always = com.nebula.aichat.data.Settings.load(c).aliveNotify;
            sb.append("后台常驻通知: ").append(always ? "开（进应用即拉起）" : "关（仅长任务时出现）")
                    .append('\n');
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ 工具

    private static String procLine(String path, String key) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(path)));
            String line;
            while ((line = r.readLine()) != null) {
                if ("?".equals(key)) return line.trim();
                if (line.startsWith(key)) return line.substring(key.length()).trim();
            }
        } catch (Throwable ignored) {
        } finally {
            if (r != null) try {
                r.close();
            } catch (Throwable ignored) {
            }
        }
        return "?";
    }

    private static String ver(Context c) {
        try {
            android.content.pm.PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            long vc = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
            return pi.versionName + "(" + vc + ")";
        } catch (Throwable t) {
            return "?";
        }
    }
}
