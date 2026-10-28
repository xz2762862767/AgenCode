package com.nebula.aichat.tool;

import android.content.Context;

import com.nebula.aichat.apk.ApkExporter;
import com.nebula.aichat.apk.ApkTools;
import com.nebula.aichat.apk.ChangeLog;
import com.nebula.aichat.apk.ApkWorkbench;

import org.json.JSONObject;

import java.io.File;
import java.util.List;

/**
 * 内置 APK 工具：反编译、读写 smali/资源、回编译、签名、导出。
 * 这些工具直接调用 {@link ApkWorkbench}，模型据此完成"改包"。
 */
final class ApkToolsProvider {

    private ApkToolsProvider() {
    }

    static void register(ToolRegistry reg, final Context c) {

        // ------------------------------------------------ 列出工程
        reg.add(new ToolDef("apk_list_projects",
                "列出当前已经反编译好、可以直接修改的 APK 工程名。开始任何改包前先调用它看看有没有现成工程。",
                ToolDef.schema(ToolDef.props(), new String[0]),
                new ToolDef.Executor() {
                    @Override
                    public String run(JSONObject a) {
                        List<String> ps = ApkWorkbench.listProjects(c);
                        if (ps.isEmpty()) {
                            return "还没有任何工程。请先用 apk_unpack 反编译一个 APK。\n"
                                    + "Linux 环境：" + (ApkWorkbench.linuxReady(c) ? "已安装" : "未安装")
                                    + "；Java：" + (ApkWorkbench.javaIssue(c) == null ? "可用" : "缺失");
                        }
                        StringBuilder sb = new StringBuilder("工程列表（" + ps.size() + "）：\n");
                        for (String p : ps) sb.append("- ").append(p).append('\n');
                        return sb.toString().trim();
                    }
                }));

        // ------------------------------------------------ 扫描可改的 APK
        reg.add(new ToolDef("apk_list_apks",
                "列出手机上（下载目录等）可以被修改的 APK 安装包，返回完整路径与大小。"
                        + "用户说「改某个 App」时先用它找到文件。",
                ToolDef.schema(ToolDef.props(), new String[0]),
                new ToolDef.Executor() {
                    @Override
                    public String run(JSONObject a) {
                        return listApks();
                    }
                }));

        // ------------------------------------------------ 查看 APK
        reg.add(new ToolDef("apk_inspect",
                "查看一个 APK 安装包的基本信息：包名、版本号、应用名、minSdk、权限、大小。",
                ToolDef.schema(ToolDef.props(
                        "path", ToolDef.prop("string", "APK 文件路径，如 /sdcard/Download/app.apk；只给文件名时会自动到 Download 目录找")),
                        "path"),
                new ToolDef.Executor() {
                    @Override
                    public String run(JSONObject a) throws Exception {
                        File f = resolveApk(c, a.optString("path", ""));
                        ApkTools.Info info = ApkTools.inspect(c, f);
                        if (info == null) return "无法解析：" + f.getAbsolutePath();
                        StringBuilder sb = new StringBuilder();
                        sb.append("文件：").append(f.getAbsolutePath()).append('\n');
                        sb.append("包名：").append(info.pkgName).append('\n');
                        sb.append("应用名：").append(info.label).append('\n');
                        sb.append("版本：").append(info.versionName).append(" (").append(info.versionCode).append(")\n");
                        sb.append("minSdk=").append(info.minSdk).append(" targetSdk=").append(info.targetSdk).append('\n');
                        sb.append("大小：").append(ApkWorkbench.fmtSize(info.size)).append('\n');
                        sb.append("Activity 数：").append(info.activityCount).append('\n');
                        if (info.permissions != null && info.permissions.length > 0) {
                            sb.append("权限（").append(info.permissions.length).append("）：");
                            for (int i = 0; i < Math.min(8, info.permissions.length); i++) {
                                if (i > 0) sb.append(", ");
                                sb.append(info.permissions[i]);
                            }
                        }
                        return sb.toString();
                    }
                }));

        // ------------------------------------------------ 反编译
        reg.add(new ToolDef("apk_unpack",
                "把一个 APK 反编译成可编辑的工程（smali 代码 + res 资源 + AndroidManifest.xml）。"
                        + "耗时可能几分钟。成功后返回工程名，后续用工程名操作文件。",
                ToolDef.schema(ToolDef.props(
                        "path", ToolDef.prop("string", "APK 路径，如 /sdcard/Download/app.apk"),
                        "name", ToolDef.prop("string", "工程名（可选，英文/数字），留空则按文件名自动生成"),
                        "fast", ToolDef.prop("boolean", "快速模式：只反编译资源不处理 dex，快 3~4 倍。只改应用名/图标/文案时用 true；要改 smali 代码必须为 false")),
                        "path"),
                new ToolDef.Executor() {
                    @Override
                    public String run(JSONObject a) throws Exception {
                        File f = resolveApk(c, a.optString("path", ""));
                        String name = a.optString("name", "");
                        if (name.trim().isEmpty()) {
                            name = f.getName().replaceAll("\\.apk$", "");
                        }
                        final StringBuilder logBuf = new StringBuilder();
                        boolean fast = a.optBoolean("fast", false);
                        String dir = ApkWorkbench.unpack(c, f, name, fast, new ApkWorkbench.Progress() {
                            @Override
                            public void onStep(String step) {
                                logBuf.append(step).append('\n');
                            }

                            @Override
                            public void onLog(String line) {
                                if (line != null && !line.trim().isEmpty()) logBuf.append(line).append('\n');
                            }
                        });
                        String proj = new File(dir).getName();
                        ChangeLog.record(c, proj, ChangeLog.KIND_UNPACK, f.getName(),
                                "反编译出工程，源包 " + ApkWorkbench.fmtSize(f.length()));
                        return "反编译成功。\n工程名：" + proj + "\n"
                                + "目录：" + dir + "\n"
                                + "常用文件：\n"
                                + "  AndroidManifest.xml（应用名/包名/权限/组件）\n"
                                + "  res/values/strings.xml（界面文案）\n"
                                + "  res/layout/*.xml（布局）\n"
                                + "  smali*/（Java 字节码）\n"
                                + "修改完记得调用 apk_repack 重新打包。\n\n"
                                + "--- 过程日志 ---\n" + ApkWorkbench.tail(logBuf.toString(), 800);
                    }
                }));

        // ------------------------------------------------ 列目录
        reg.add(new ToolDef("apk_list_files",
                "列出工程内某个目录的文件和子目录。目录为空表示工程根目录。",
                ToolDef.schema(ToolDef.props(
                        "project", ToolDef.prop("string", "工程名（apk_unpack 返回的）"),
                        "dir", ToolDef.prop("string", "工程内相对目录，如 res/values、smali，留空为根目录")),
                        "project"),
                new ToolDef.Executor() {
                    @Override
                    public String run(JSONObject a) throws Exception {
                        String project = a.optString("project", "");
                        String dir = a.optString("dir", "");
                        requireProject(c, project);
                        File d = ApkWorkbench.resolveInProject(c, project, dir);
                        if (!d.exists()) return "目录不存在：" + dir;
                        if (d.isFile()) return "这是一个文件（" + ApkWorkbench.fmtSize(d.length()) + "），用 apk_read_file 读取。";
                        File[] fs = d.listFiles();
                        if (fs == null || fs.length == 0) return "目录为空：" + dir;
                        java.util.Arrays.sort(fs, new java.util.Comparator<File>() {
                            @Override
                            public int compare(File x, File y) {
                                if (x.isDirectory() != y.isDirectory()) return x.isDirectory() ? -1 : 1;
                                return x.getName().compareToIgnoreCase(y.getName());
                            }
                        });
                        StringBuilder sb = new StringBuilder("目录 " + (dir.isEmpty() ? "/" : dir)
                                + "（" + fs.length + " 项）：\n");
                        int shown = 0;
                        for (File f : fs) {
                            if (shown++ >= 120) {
                                sb.append("…（还有 ").append(fs.length - 120).append(" 项未显示）\n");
                                break;
                            }
                            sb.append(f.isDirectory() ? "[目录] " : "       ").append(f.getName());
                            if (f.isFile()) sb.append("  ").append(ApkWorkbench.fmtSize(f.length()));
                            sb.append('\n');
                        }
                        return sb.toString();
                    }
                }));

        // ------------------------------------------------ 读文件
        reg.add(new ToolDef("apk_read_file",
                "读取工程内的文本文件（xml / smali / yml 等）。大文件会被截断。",
                ToolDef.schema(ToolDef.props(
                        "project", ToolDef.prop("string", "工程名"),
                        "path", ToolDef.prop("string", "工程内相对路径，如 res/values/strings.xml"),
                        "max_bytes", ToolDef.prop("integer", "最多读取字节数，默认 60000")),
                        "project", "path"),
                new ToolDef.Executor() {
                    @Override
                    public String run(JSONObject a) throws Exception {
                        requireProject(c, a.optString("project", ""));
                        File f = ApkWorkbench.resolveInProject(c, a.optString("project", ""),
                                a.optString("path", ""));
                        int max = a.optInt("max_bytes", 60000);
                        if (max < 1000) max = 1000;
                        if (max > 200000) max = 200000;
                        return ApkWorkbench.readText(f, max);
                    }
                }));

        // ------------------------------------------------ 写文件
        reg.add(new ToolDef("apk_write_file",
                "把内容写入工程内文件（覆盖写，UTF-8）。改 smali / xml 都用它。"
                        + "写之前一定要先 apk_read_file 看原文，保持格式与转义正确。",
                ToolDef.schema(ToolDef.props(
                        "project", ToolDef.prop("string", "工程名"),
                        "path", ToolDef.prop("string", "工程内相对路径，如 res/values/strings.xml"),
                        "content", ToolDef.prop("string", "完整的新文件内容")),
                        "project", "path", "content"),
                new ToolDef.Executor() {
                    @Override
                    public String run(JSONObject a) throws Exception {
                        File f = ApkWorkbench.resolveInProject(c, a.optString("project", ""),
                                a.optString("path", ""));
                        String content = a.optString("content", "");
                        String old = f.exists() ? ApkWorkbench.readText(f, 200000) : null;
                        ApkWorkbench.writeText(f, content);
                        String projName = a.optString("project", "");
                        String relPath = a.optString("path", "");
                        if (old == null) {
                            ChangeLog.record(c, projName, ChangeLog.KIND_CREATE, relPath,
                                    "新建文件，" + content.length() + " 字符");
                        } else {
                            ChangeLog.record(c, projName, ChangeLog.KIND_WRITE, relPath,
                                    "覆盖写入，" + old.length() + " → " + content.length() + " 字符");
                        }
                        return "已写入 " + a.optString("path") + "（" + content.length() + " 字符）"
                                + (old == null ? "，新建文件" : "，覆盖原有 " + old.length() + " 字符");
                    }
                }));

        // ------------------------------------------------ 搜索
        reg.add(new ToolDef("apk_search",
                "在工程内按关键字搜索文本（相当于 grep），返回 文件:行号: 内容。"
                        + "用来定位某个字符串、类名、ID 在哪里定义。",
                ToolDef.schema(ToolDef.props(
                        "project", ToolDef.prop("string", "工程名"),
                        "query", ToolDef.prop("string", "要搜索的关键字"),
                        "dir", ToolDef.prop("string", "限定子目录（可选），如 res 或 smali")),
                        "project", "query"),
                new ToolDef.Executor() {
                    @Override
                    public String run(JSONObject a) throws Exception {
                        return search(c, a.optString("project", ""), a.optString("query", ""),
                                a.optString("dir", ""));
                    }
                }));

        // ------------------------------------------------ 改应用名
        reg.add(new ToolDef("apk_set_app_name",
                "修改 APK 的应用名称（桌面显示名）。会自动处理 AndroidManifest 的 label 与 strings.xml。",
                ToolDef.schema(ToolDef.props(
                        "project", ToolDef.prop("string", "工程名"),
                        "label", ToolDef.prop("string", "新的应用名")),
                        "project", "label"),
                new ToolDef.Executor() {
                    @Override
                    public String run(JSONObject a) throws Exception {
                        return setAppName(c, a.optString("project", ""), a.optString("label", ""));
                    }
                }));

        // ------------------------------------------------ 回编译
        reg.add(new ToolDef("apk_repack",
                "把工程重新编译成 APK 并自动签名，然后导出到下载目录。改完文件必须调用它。",
                ToolDef.schema(ToolDef.props(
                        "project", ToolDef.prop("string", "工程名")),
                        "project"),
                new ToolDef.Executor() {
                    @Override
                    public String run(JSONObject a) throws Exception {
                        final StringBuilder logBuf = new StringBuilder();
                        File signed = ApkWorkbench.repack(c, a.optString("project", ""),
                                new ApkWorkbench.Progress() {
                                    @Override
                                    public void onStep(String step) {
                                        logBuf.append(step).append('\n');
                                    }

                                    @Override
                                    public void onLog(String line) {
                                        if (line != null && !line.trim().isEmpty()) logBuf.append(line).append('\n');
                                    }
                                });
                        File dest = signed;
                        String exportNote = "";
                        String exportPath = null;
                        try {
                            String where = com.nebula.aichat.apk.ApkExporter
                                    .exportToDownload(c, signed, signed.getName());
                            exportNote = "\n导出位置（AgenCode）：" + where;
                            exportPath = where;
                        } catch (Exception e) {
                            exportNote = "\n（导出到「AgenCode」失败：" + e.getMessage()
                                    + "\n文件在应用私有目录，可用工作台「安装产物」处理）";
                        }
                        // 记下这一份，界面回答完会在正文末尾直接挂「安装」卡片
                        com.nebula.aichat.apk.ApkInstaller.rememberBuilt(dest, exportPath);
                        String repackProj = a.optString("project", "");
                        ChangeLog.record(c, repackProj, ChangeLog.KIND_REPACK, dest.getName(),
                                ApkWorkbench.fmtSize(dest.length()) + "，已签名");
                        ChangeLog.writeMarkdown(c, repackProj);
                        return "打包并签名成功。\n输出：" + dest.getAbsolutePath() + exportNote
                                + "\n大小：" + ApkWorkbench.fmtSize(dest.length())
                                + "\n（已用内置证书签名，可直接安装；覆盖安装原版会因签名不同失败，需先卸载）"
                                + ChangeLog.report(c, repackProj)
                                + "\n--- 过程日志 ---\n" + ApkWorkbench.tail(logBuf.toString(), 600);
                    }
                }));

        registerChanges(c, reg);
    }

    /** 修改清单：列出该工程改过哪些地方（回编译时也会自动附带） */
    private static void registerChanges(final android.content.Context c, ToolRegistry reg) {
        reg.add(new ToolDef("apk_changes",
                "查看这个工程做过的全部改动，返回一张「修改清单」表格：时间、类型、涉及文件、改成了什么。"
                        + "用户问「你改了什么」时用它。",
                ToolDef.schema(ToolDef.props(
                        "project", ToolDef.prop("string", "工程名")),
                        "project"),
                new ToolDef.Executor() {
                    @Override
                    public String run(JSONObject a) throws Exception {
                        String proj = a.optString("project", "");
                        int n = ChangeLog.count(c, proj);
                        if (n == 0) return "工程「" + proj + "」还没有记录到任何改动。";
                        return "工程「" + proj + "」共 " + n + " 处改动：\n\n" + ChangeLog.render(c, proj);
                    }
                }));
    }

    // ------------------------------------------------------------ 实现细节

    /** 扫描常见目录下的 APK */
    private static String listApks() {
        String[] dirs = new String[]{"/sdcard/Download", "/sdcard", "/sdcard/Documents",
                "/storage/emulated/0/Download"};
        java.util.LinkedHashMap<String, File> found = new java.util.LinkedHashMap<String, File>();
        for (String d : dirs) {
            File dir = new File(d);
            File[] fs = dir.listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                if (f.isFile() && f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".apk") && f.length() > 4096) {
                    found.put(f.getAbsolutePath(), f);
                }
            }
        }
        if (found.isEmpty()) {
            return "没有找到 APK 文件。\n请把要改的安装包放到「下载」目录，或告诉我它的完整路径。";
        }
        StringBuilder sb = new StringBuilder("找到 " + found.size() + " 个 APK：\n");
        int n = 0;
        for (File f : found.values()) {
            if (n++ >= 40) break;
            sb.append("- ").append(f.getAbsolutePath())
                    .append("  (").append(ApkWorkbench.fmtSize(f.length())).append(")\n");
        }
        sb.append("\n用 apk_inspect 查看详情，或用 apk_unpack 反编译。");
        return sb.toString();
    }

    /**
     * 把模型/用户给的路径落成一个真实存在的 File。
     * Android 11 起没有「所有文件访问」权限时，连 /sdcard 下的 .apk 都读不到，
     * 这时 must 明确告诉用户去开权限，而不是干巴巴一句「找不到」。
     */
    /** 沙箱挂载别名 → 真实路径（模型可能给 /sdcard-app/x、/exchange/x、/sdcard/x） */
    private static File aliasToReal(Context c, String p) {
        try {
            if (p.equals("/sdcard-app") || p.startsWith("/sdcard-app/")) {
                File ext = c.getExternalFilesDir(null);
                if (ext != null) return new File(ext, p.length() > 11 ? p.substring(12) : "");
            }
            if (p.equals("/exchange") || p.startsWith("/exchange/")) {
                File ex = new File(c.getFilesDir(), "exchange");
                return new File(ex, p.length() > 9 ? p.substring(10) : "");
            }
            if (p.equals("/sdcard") || p.startsWith("/sdcard/")) {
                File pub = android.os.Environment.getExternalStorageDirectory();
                if (pub != null) return new File(pub, p.length() > 7 ? p.substring(8) : "");
            }
        } catch (Throwable ignored) {
        }
        return new File(p);
    }

    private static File resolveApk(Context c, String path) throws Exception {
        if (path == null || path.trim().isEmpty()) {
            throw new Exception("请提供 APK 路径，例如 /sdcard/Download/app.apk；"
                    + "也可以只给文件名，工具会到下载目录里找。");
        }
        String p = path.trim();
        // 分享/用系统选择器选来的文件常常是 content:// URI，File 是解析不了的
        if (p.startsWith("content://") || p.startsWith("file:///")) {
            if (p.startsWith("content://")) {
                File copied = copyFromUri(c, p);
                if (copied != null) return copied;
            } else {
                p = p.substring("file://".length());
            }
        }
        if (p.startsWith("file://")) p = p.substring("file://".length());
        try {
            p = java.net.URLDecoder.decode(p, "UTF-8");
        } catch (Throwable ignored) {
        }
        p = p.replace("\\", "/");
        if (p.startsWith("~")) p = "/sdcard" + p.substring(1);
        while (p.contains("//")) p = p.replace("//", "/");

        File direct = aliasToReal(c, p);
        if (direct.exists() && direct.isFile()) return direct;

        // 相对写法：Download/app.apk、app.apk
        if (!p.startsWith("/")) {
            File a = new File("/sdcard/" + p);
            if (a.exists() && a.isFile()) return a;
            File b = new File("/sdcard/Download/" + p);
            if (b.exists() && b.isFile()) return b;
        }

        // 路径猜错了但文件名对得上：到上传/产物/下载等目录里按同名找
        String name = new File(p).getName();
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        String[] tries = lower.endsWith(".apk") ? new String[]{name} : new String[]{name, name + ".apk"};
        String[] dirs = uploadDirs(c);
        for (String d : dirs) {
            for (String t : tries) {
                File cand = new File(d, t);
                if (cand.exists() && cand.isFile()) return cand;
            }
        }

        // Android 11+ 分区存储：没有「所有文件访问」时，File.exists() 会把别的应用写下的
        // APK 判成「不存在」，于是明明路径合法却报找不到。这里改走 MediaStore 再找一遍。
        File viaMedia = findApkViaMediaStore(c, name);
        if (viaMedia != null) return viaMedia;

        // 都没命中 → 给出能立刻行动的诊断
        boolean noAllFiles = ApkExporter.needAllFilesAccess();
        StringBuilder sb = new StringBuilder();
        if (noAllFiles) {
            // 先说结论再给路径：绝大多数「路径没错却说找不到」都是这条
            sb.append("读不到这个 APK，最可能的原因是缺「所有文件访问」权限（不是路径写错）。\n");
            sb.append("要读的路径：").append(p).append("\n\n");
        } else {
            sb.append("找不到 APK：").append(p).append("\n\n");
        }
        if (noAllFiles) {
            sb.append("\n\n【多半是权限问题】Android 11 起，没有「所有文件访问」权限时，应用连 /sdcard 下的 .apk 都读不到"
                    + "（READ_EXTERNAL_STORAGE 只管图片和音频，不管 APK）。\n"
                    + "请手动开启：设置 → 应用 → AgenCode → 权限 → 所有文件访问 → 允许，然后重试。");
        }
        List<String> found = listApksInCommonDirs(c);
        if (found.isEmpty()) {
            sb.append("\n\n常用目录里也没扫到任何 .apk。如果刚下载完，先确认下载确实完成了。");
        } else {
            sb.append("\n\n常用目录里扫到这些 APK，可以换下面任意一个路径重试：");
            for (String f : found) sb.append("\n  ").append(f);
        }
        throw new Exception(sb.toString());
    }

    /** 项目内工具的前置校验：把「工程不存在」翻译成能照着做的下一步 */
    private static void requireProject(Context c, String project) throws Exception {
        File base = ApkWorkbench.projectDir(c, project);
        if (base != null && base.isDirectory()) return;
        StringBuilder sb = new StringBuilder();
        sb.append("工程「").append(project == null ? "" : project).append("」不存在，还没解包。\n")
                .append("apk_read_file / apk_write_file / apk_list_files / apk_search 只接受【工程内相对路径】，")
                .append("不能直接读 /sdcard/... 这样的绝对路径。\n")
                .append("正确顺序：先用 apk_unpack（path 传 APK 的绝对路径）解包出工程，再用这里的工具读改工程内文件。");
        try {
            java.util.List<String> names = ApkWorkbench.listProjects(c);
            if (names != null && !names.isEmpty()) {
                sb.append("\n现有工程：");
                for (int i = 0; i < Math.min(8, names.size()); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(names.get(i));
                }
            }
        } catch (Throwable ignored) {
        }
        throw new Exception(sb.toString());
    }

    /** 找文件时的候选目录：用户刚上传的排最前 */
    private static String[] uploadDirs(Context c) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        try {
            set.add(com.nebula.aichat.apk.ArtifactStore.writableSub(c, "上传").getAbsolutePath());
            set.add(new File(com.nebula.aichat.apk.ArtifactStore.publicDir(), "上传").getAbsolutePath());
            set.add(com.nebula.aichat.apk.ArtifactStore.publicSub(com.nebula.aichat.apk.ArtifactStore.SUB_APK).getAbsolutePath());
            set.add(com.nebula.aichat.apk.ArtifactStore.writableDir(c).getAbsolutePath());
        } catch (Throwable ignored) {
        }
        set.add("/sdcard/Download");
        set.add("/storage/emulated/0/Download");
        set.add("/sdcard/Documents");
        set.add("/sdcard/Download/apk");
        set.add("/sdcard/APK");
        set.add("/sdcard/apk");
        set.add("/sdcard");
        set.add("/storage/emulated/0");
        set.add("/sdcard/tencent/MicroMsg/Download");
        set.add("/sdcard/Android/data/com.tencent.mm/MicroMsg/Download");
        return set.toArray(new String[0]);
    }

    /** 把 content:// 指向的内容复制成 app 私有目录里的实体文件 */
    /**
     * 用 MediaStore 按文件名找 APK，命中就复制到私有目录返回。
     * 这条路绕开了分区存储对 File API 的限制，是「路径没错却读不到」的兜底。
     */
    private static File findApkViaMediaStore(Context c, String name) {
        if (c == null || name == null || name.trim().isEmpty()) return null;
        android.database.Cursor cur = null;
        try {
            android.net.Uri base = android.provider.MediaStore.Files.getContentUri("external");
            String[] proj = {android.provider.MediaStore.Files.FileColumns._ID};
            // Downloads 集合里同名的可能不止一个，取最新那个
            String order = android.provider.MediaStore.Files.FileColumns.DATE_ADDED + " DESC";
            cur = c.getContentResolver().query(base, proj,
                    android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME + "=?",
                    new String[]{name}, order);
            if (cur != null && cur.moveToFirst()) {
                long id = cur.getLong(0);
                android.net.Uri fileUri = android.content.ContentUris.withAppendedId(base, id);
                File copied = copyFromUri(c, fileUri.toString());
                if (copied != null && copied.exists() && copied.length() > 0) return copied;
            }
        } catch (Throwable ignored) {
            // MediaStore 不可用（个别机型/权限全禁）就老实退回去给诊断
        } finally {
            if (cur != null) {
                try { cur.close(); } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static File copyFromUri(Context c, String uriStr) {
        java.io.InputStream is = null;
        java.io.FileOutputStream os = null;
        try {
            android.net.Uri u = android.net.Uri.parse(uriStr);
            is = c.getContentResolver().openInputStream(u);
            if (is == null) return null;
            File tmp = new File(c.getCacheDir(), "from_uri.apk");
            os = new java.io.FileOutputStream(tmp, false);
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
            os.close();
            os = null;
            if (tmp.length() < 4096) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return null;
            }
            return tmp;
        } catch (Throwable t) {
            return null;
        } finally {
            if (is != null) {
                try { is.close(); } catch (Exception ignored) { }
            }
            if (os != null) {
                try { os.close(); } catch (Exception ignored) { }
            }
        }
    }

    /** 扫一遍常见目录里的 apk，用于「找不到」时给候选 */
    private static List<String> listApksInCommonDirs(Context c) {
        List<String> out = new java.util.ArrayList<>();
        for (String d : uploadDirs(c)) {
            File dir = new File(d);
            File[] fs;
            try {
                fs = dir.listFiles();
            } catch (Throwable t) {
                continue;
            }
            if (fs == null) continue;
            for (File f : fs) {
                if (f == null || !f.isFile()) continue;
                if (!f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) continue;
                String ap = f.getAbsolutePath();
                if (out.contains(ap)) continue;
                out.add(ap);
                if (out.size() >= 12) return out;
            }
        }
        return out;
    }

    private static String search(Context c, String project, String query, String dir) throws Exception {
        if (query == null || query.trim().isEmpty()) throw new Exception("请提供搜索关键字");
        requireProject(c, project);
        File base = ApkWorkbench.resolveInProject(c, project, dir);
        if (!base.exists()) throw new Exception("目录不存在：" + dir);
        StringBuilder sb = new StringBuilder();
        int[] count = new int[]{0};
        walkSearch(base, ApkWorkbench.resolveInProject(c, project, ""), query, sb, count);
        if (count[0] == 0) return "没有找到包含「" + query + "」的内容";
        if (count[0] >= 80) sb.append("…（结果过多，已截断，请换更精确的关键字或指定目录）\n");
        return "找到 " + count[0] + " 处匹配：\n" + sb;
    }

    private static void walkSearch(File f, File root, String query, StringBuilder sb, int[] count) {
        if (count[0] >= 80) return;
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs == null) return;
            for (File x : fs) {
                if (count[0] >= 80) return;
                walkSearch(x, root, query, sb, count);
            }
            return;
        }
        String name = f.getName().toLowerCase(java.util.Locale.ROOT);
        if (!(name.endsWith(".xml") || name.endsWith(".smali") || name.endsWith(".yml")
                || name.endsWith(".txt") || name.endsWith(".json") || name.endsWith(".properties"))) {
            return;
        }
        if (f.length() > 3 * 1024 * 1024) return;
        try {
            String text = ApkWorkbench.readText(f, 400000);
            String[] lines = text.split("\n");
            String rel = f.getAbsolutePath().substring(root.getAbsolutePath().length());
            for (int i = 0; i < lines.length; i++) {
                if (count[0] >= 80) return;
                if (lines[i].contains(query)) {
                    count[0]++;
                    String t = lines[i].trim();
                    if (t.length() > 200) t = t.substring(0, 200) + "…";
                    sb.append(rel).append(':').append(i + 1).append(": ").append(t).append('\n');
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** 改应用名：manifest 的 label 可能是 @string/xxx，也可能是字面量 */
    private static String setAppName(Context c, String project, String label) throws Exception {
        if (label == null || label.trim().isEmpty()) throw new Exception("请提供新的应用名");
        File manifest = ApkWorkbench.resolveInProject(c, project, "AndroidManifest.xml");
        if (!manifest.exists()) throw new Exception("工程里没有 AndroidManifest.xml");
        String xml = ApkWorkbench.readText(manifest, 500000);

        // 找 <application ... android:label="...">
        int appIdx = xml.indexOf("<application");
        if (appIdx < 0) throw new Exception("manifest 里没有 <application> 标签");
        int appEnd = xml.indexOf('>', appIdx);
        if (appEnd < 0) throw new Exception("manifest 格式异常");
        String appTag = xml.substring(appIdx, appEnd);

        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("android:label\\s*=\\s*\"([^\"]*)\"").matcher(appTag);
        if (!m.find()) {
            // 没有 label 属性 → 直接插入
            String newTag = appTag + " android:label=\"" + escapeXml(label) + "\"";
            xml = xml.substring(0, appIdx) + newTag + xml.substring(appEnd);
            ApkWorkbench.writeText(manifest, xml);
            ChangeLog.record(c, project, ChangeLog.KIND_APPNAME, "AndroidManifest.xml",
                    "原来没有应用名，新增「" + label + "」");
            return "manifest 原本没有 android:label，已直接写入「" + label + "」。请调用 apk_repack 打包。";
        }

        String cur = m.group(1);
        StringBuilder report = new StringBuilder();
        if (cur.startsWith("@string/")) {
            String resName = cur.substring("@string/".length());
            int changed = 0;
            File res = ApkWorkbench.resolveInProject(c, project, "res");
            File[] vals = res.listFiles();
            if (vals != null) {
                for (File d : vals) {
                    if (!d.isDirectory() || !d.getName().startsWith("values")) continue;
                    File sf = new File(d, "strings.xml");
                    if (!sf.exists()) continue;
                    String sx = ApkWorkbench.readText(sf, 500000);
                    java.util.regex.Matcher sm = java.util.regex.Pattern.compile(
                            "<string\\s+name=\"" + java.util.regex.Pattern.quote(resName) + "\"\\s*>(.*?)</string>",
                            java.util.regex.Pattern.DOTALL).matcher(sx);
                    if (sm.find()) {
                        String repl = "<string name=\"" + resName + "\">" + escapeXml(label) + "</string>";
                        sx = sm.replaceFirst(java.util.regex.Matcher.quoteReplacement(repl));
                        ApkWorkbench.writeText(sf, sx);
                        changed++;
                        report.append("已改 ").append(d.getName()).append("/strings.xml 里的 ").append(resName).append('\n');
                    }
                }
            }
            if (changed == 0) {
                // 值资源可能不在 strings.xml，回退到直接改 manifest
                xml = xml.substring(0, appIdx) + appTag.replace(cur, label)
                        + xml.substring(appEnd);
                ApkWorkbench.writeText(manifest, xml);
                ChangeLog.record(c, project, ChangeLog.KIND_APPNAME, "AndroidManifest.xml",
                        "应用名「" + cur + "」改为「" + label + "」（字面量）");
                return "未在 strings.xml 找到 " + resName + "，已把 manifest 的 label 直接改成字面量「" + label + "」。";
            }
        } else {
            String newTag = appTag.replace(cur, escapeXml(label));
            xml = xml.substring(0, appIdx) + newTag + xml.substring(appEnd);
            ApkWorkbench.writeText(manifest, xml);
            report.append("已把 manifest 的 android:label 从「").append(cur).append("」改为「").append(label).append("」\n");
        }
        ChangeLog.record(c, project, ChangeLog.KIND_APPNAME, "AndroidManifest.xml / strings.xml",
                "应用名「" + cur + "」改为「" + label + "」");
        report.append("请调用 apk_repack 重新打包。");
        return report.toString();
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
