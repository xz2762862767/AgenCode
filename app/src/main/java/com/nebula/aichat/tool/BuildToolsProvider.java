package com.nebula.aichat.tool;

import android.content.Context;

import com.nebula.aichat.apk.ApkWorkbench;
import com.nebula.aichat.build.BuildKit;
import com.nebula.aichat.linux.LinuxEnv;

import org.json.JSONObject;

/**
 * 源码构建工具：让模型能把「一堆 .java + AndroidManifest.xml」直接编成可安装 APK，
 * 不需要 Gradle，也不需要 Android SDK。
 * <p>
 * 依赖 {@link BuildKit}：内置 aapt2 / d8 / apksigner / android.jar，JDK 首次自动安装。
 */
final class BuildToolsProvider {

    private BuildToolsProvider() {
    }

    static void register(ToolRegistry reg, final Context c) {

        // ------------------------------------------------ 工具链状态
        reg.add(new ToolDef("build_env_status",
                "检查「源码编译 APK」的工具链是否就绪：Alpine 环境、JDK(javac)、android.jar、aapt2。"
                        + "在决定用 build_apk 之前先调一次，可以避免白等。",
                ToolDef.schema(ToolDef.props(), new String[0]),
                new ToolDef.Executor() {
                    @Override
                    public String run(JSONObject a) {
                        LinuxEnv env = LinuxEnv.get(c);
                        boolean installed = env != null && env.isInstalled();
                        StringBuilder sb = new StringBuilder();
                        sb.append("Alpine 环境：").append(installed ? "已安装" : "未安装（需先到「环境」页安装）").append('\n');
                        if (!installed) return sb.toString();
                        String assets = BuildKit.ensureAssets(c);
                        sb.append("内置资产(android.jar/d8/apksigner/aapt2)：")
                                .append(assets == null ? "就绪" : "失败 - " + assets).append('\n');
                        boolean javac = BuildKit.hasJavac(c);
                        sb.append("javac(JDK)：").append(javac ? "可用" : "缺失（build_apk 会自动 apk add openjdk17-jdk，约 180MB）").append('\n');
                        sb.append("可否直接编译：").append(assets == null && javac ? "可以" : "首次编译需要先补齐").append('\n');
                        sb.append("构建目录：").append(BuildKit.P_DIR).append("（Android 侧 ")
                                .append(BuildKit.root(c).getAbsolutePath()).append("）");
                        return sb.toString();
                    }
                }));

        // ------------------------------------------------ 从源码构建
        reg.add(new ToolDef("build_apk",
                "把一份 Java 源码工程直接编译成可安装的 APK（javac + d8 + aapt2 + apksigner，不用 Gradle/SDK）。\n"
                        + "工程目录要求：\n"
                        + "  AndroidManifest.xml  必需，放根目录\n"
                        + "  src/                 必需，Java 源码根（包名对应子目录）\n"
                        + "  res/                 可选，资源目录（values/、layout/、drawable/ 等）\n"
                        + "构建产物输出到 /exchange/buildkit/<name>.apk，终端里可直接 cp 走。\n"
                        + "注意：首次调用如果缺 JDK 会自动联网安装 openjdk17-jdk（约 180MB，较慢）。",
                ToolDef.schema(ToolDef.props(
                        "project", ToolDef.prop("string",
                                "工程目录。proot 内路径（如 /root/myapp、/exchange/myapp）或 App 私有路径都可以，后者会自动复制到构建区"),
                        "min_sdk", ToolDef.prop("integer", "最低支持版本，默认 24"),
                        "target_sdk", ToolDef.prop("integer", "目标版本，默认 34"),
                        "name", ToolDef.prop("string", "产物文件名（不含 .apk），默认用工程目录名")),
                        "project"),
                new ToolDef.Executor() {
                    @Override
                    public String run(JSONObject a) throws Exception {
                        String proj = a.optString("project", "");
                        int minSdk = a.has("min_sdk") ? a.optInt("min_sdk", 24) : 24;
                        int target = a.has("target_sdk") ? a.optInt("target_sdk", 34) : 34;
                        String name = a.optString("name", "");
                        if (minSdk < 21) minSdk = 21;
                        if (target < minSdk) target = minSdk;
                        BuildKit.Result r = BuildKit.build(c, proj, minSdk, target, name,
                                new ApkWorkbench.Progress() {
                                    @Override
                                    public void onStep(String step) {
                                    }

                                    @Override
                                    public void onLog(String line) {
                                    }
                                });
                        StringBuilder sb = new StringBuilder();
                        sb.append(r.ok ? "✅ " : "❌ ").append(r.message);
                        if (r.ok && r.apkPath != null) {
                            sb.append("\nAPK 大小：").append(ApkWorkbench.fmtSize(r.apkSize));
                            sb.append("\nAndroid 侧路径：").append(r.apkPath);
                            sb.append("\n终端里取走：cp ").append(BuildKit.P_DIR).append("/")
                                    .append(new java.io.File(r.apkPath).getName()).append(" /exchange/");
                            // 复制一份到「星云产物/APK」并记下，回答完界面直接给安装卡片
                            try {
                                java.io.File af = new java.io.File(r.apkPath);
                                String pub = af.isFile()
                                        ? com.nebula.aichat.apk.ApkExporter
                                        .exportToDownload(c, af, af.getName())
                                        : null;
                                com.nebula.aichat.apk.ApkInstaller.rememberBuilt(af, pub);
                                if (pub != null) sb.append("\n已导出：").append(pub);
                            } catch (Exception ignored) {
                            }
                        }
                        if (!r.ok && r.log != null && r.log.trim().length() > 0) {
                            sb.append("\n--- 构建日志（尾部）---\n").append(BuildKit.tail(r.log, 2500));
                        }
                        return sb.toString();
                    }
                }));
    }
}
