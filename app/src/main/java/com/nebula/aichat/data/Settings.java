package com.nebula.aichat.data;

import android.content.Context;
import android.content.SharedPreferences;

/** 应用设置（SharedPreferences 封装） */
public class Settings {

    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com/v1";
    public static final String DEFAULT_MODEL = "deepseek-chat";
    public static final String DEFAULT_SYSTEM_PROMPT =
            "你是「星云 AI」，一个说话自然、回答准确的中文助手。\n"
            + "1. 先用一两句话把结论讲清楚，再补充必要的细节。\n"
            + "2. 除非用户明确需要，否则不要输出代码。\n"
            + "3. 不确定的内容直接说明，不要编造事实或链接。\n"
            + "4. 涉及步骤时按 1/2/3 简短分步。";

    private static final String PREF = "nebula_settings";

    public String apiKey;
    public String baseUrl;
    public String model;
    public String systemPrompt;
    public float temperature;
    public int maxTokens;
    public boolean stream;
    /** 用户自己添加的模型（模型选择器里的「自定义」，只存 model 名） */
    public java.util.List<String> customModels = new java.util.ArrayList<>();

    /** 聊天正文与 AI 回答的字号（sp），13~24，默认 16 */
    public int msgFontSp;

    /** 联网搜索开关（输入框上方 🌐 按钮持久化） */
    public boolean searchEnabled;
    /** 搜索引擎：bing / ddg */
    public String searchEngine;

    /**
     * 思考强度：
     * 0 默认（不发送任何思考参数，兼容性最好）
     * 1 轻度 / 2 中度 / 3 重度 / 4 极度
     */
    public int thinkLevel;

    /** 重度/极度时自动把 deepseek-chat 切到 deepseek-reasoner（真正开思维链） */
    public boolean thinkAutoModel;

    /** 外观主题：0 = 深色，1 = 纯黑（OLED） */
    public int theme;
    /** 强调色档位：0 蓝 / 1 紫 / 2 绿 / 3 橙 / 4 青 */
    public int accent;
    /** 聊天背景图文件名（应用私有目录 chat_bg/ 下），空 = 不设背景图 */
    public String chatBg;
    /** 背景图不透明度 10~90（%），越高越明显 */
    public int chatBgAlpha;
    /** 背景图压暗 0~80（%），越高文字越清晰 */
    public int chatBgDim;

    /** 终端执行方式：local（手机本地 shell）/ remote（远程执行服务） */
    public String execMode;
    /** 远程执行服务地址，例如 http://192.168.1.10:8080 */
    public String remoteExecUrl;
    /** 远程执行服务密钥（可选，放在 X-Auth-Token 头） */
    public String remoteExecToken;

    public static Settings load(Context ctx) {
        SharedPreferences sp = ctx.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);
        Settings s = new Settings();
        s.apiKey = sp.getString("apiKey", "");
        s.baseUrl = sp.getString("baseUrl", DEFAULT_BASE_URL);
        s.model = sp.getString("model", DEFAULT_MODEL);
        s.systemPrompt = sp.getString("systemPrompt", DEFAULT_SYSTEM_PROMPT);
        s.temperature = sp.getFloat("temperature", 0.7f);
        s.maxTokens = sp.getInt("maxTokens", 8192);
        // 旧默认 4096 太小：长代码/长回答会撞到上限被截断（界面表现为只说一半就停）。
        // 只在用户没手动改过（仍等于旧默认 4096）时提到 8192。
        if (!sp.getBoolean("maxTokRev2", false) && s.maxTokens == 4096) s.maxTokens = 8192;
        sp.edit().putBoolean("maxTokRev2", true).apply();
        s.stream = sp.getBoolean("stream", true);
        s.customModels = splitModels(sp.getString("customModels", ""));

        s.msgFontSp = sp.getInt("msgFontSp", 13);
        // 旧版本默认 16sp 偏大，首次升级时收窄到 14sp（用户手动改过的不动）
        if (!sp.getBoolean("fontRev2", false) && s.msgFontSp == 16) s.msgFontSp = 14;
        sp.edit().putBoolean("fontRev2", true).apply();
        // 正文再收一档：旧默认 14sp 首次升级时降到 13sp（用户手动改过的不动）
        if (!sp.getBoolean("fontRev3", false) && s.msgFontSp == 14) s.msgFontSp = 13;
        sp.edit().putBoolean("fontRev3", true).apply();
        s.searchEnabled = sp.getBoolean("searchEnabled", false);
        s.searchEngine = sp.getString("searchEngine", "bing");
        s.thinkLevel = sp.getInt("thinkLevel", 0);
        s.thinkAutoModel = sp.getBoolean("thinkAutoModel", true);
        s.theme = sp.getInt("theme", 0);
        s.accent = sp.getInt("accent", 0);
        s.chatBg = sp.getString("chatBg", "");
        s.chatBgAlpha = sp.getInt("chatBgAlpha", 45);
        s.chatBgDim = sp.getInt("chatBgDim", 35);
        s.execMode = sp.getString("execMode", "local");
        s.remoteExecUrl = sp.getString("remoteExecUrl", "");
        s.remoteExecToken = sp.getString("remoteExecToken", "");
        return s;
    }

    public void save(Context ctx) {
        SharedPreferences.Editor e = ctx.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE).edit();
        e.putString("apiKey", apiKey == null ? "" : apiKey.trim());
        e.putString("baseUrl", baseUrl == null ? "" : baseUrl.trim());
        e.putString("model", model == null ? "" : model.trim());
        e.putString("systemPrompt", systemPrompt == null ? "" : systemPrompt);
        e.putFloat("temperature", temperature);
        e.putInt("maxTokens", maxTokens);
        e.putBoolean("stream", stream);
        e.putString("customModels", joinModels(customModels));
        e.putInt("msgFontSp", msgFontSp <= 0 ? 13 : Math.max(12, Math.min(24, msgFontSp)));
        e.putBoolean("searchEnabled", searchEnabled);
        e.putString("searchEngine", searchEngine == null ? "bing" : searchEngine.trim());
        e.putInt("thinkLevel", thinkLevel);
        e.putBoolean("thinkAutoModel", thinkAutoModel);
        e.putInt("theme", theme);
        e.putInt("accent", accent);
        e.putString("chatBg", chatBg == null ? "" : chatBg);
        e.putInt("chatBgAlpha", chatBgAlpha);
        e.putInt("chatBgDim", chatBgDim);
        e.putString("execMode", execMode == null ? "local" : execMode);
        e.putString("remoteExecUrl", remoteExecUrl == null ? "" : remoteExecUrl.trim());
        e.putString("remoteExecToken", remoteExecToken == null ? "" : remoteExecToken);
        e.apply();
    }

    /** 是否已配置可用的密钥 */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    public String endpoint() {
        String b = baseUrl == null ? "" : baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.isEmpty()) b = DEFAULT_BASE_URL;
        if (b.endsWith("/chat/completions")) return b;
        return b + "/chat/completions";
    }

    // ==================== 思考强度 ====================

    public static final String[] THINK_LABELS = {"默认", "轻度", "中度", "重度", "极度"};

    public static String thinkLabel(int level) {
        if (level < 0 || level >= THINK_LABELS.length) return THINK_LABELS[0];
        return THINK_LABELS[level];
    }

    /** 模型名是否属于「自带思维链」的推理模型（不认 reasoning_effort，也不支持工具调用） */
    public static boolean isReasonerModel(String model) {
        return model != null && model.toLowerCase(java.util.Locale.ROOT).contains("reasoner");
    }

    /** 基础模型名 */
    public static String baseModel(Settings s) {
        return (s.model == null || s.model.trim().isEmpty()) ? DEFAULT_MODEL : s.model.trim();
    }

    /**
     * 实际发给后端的模型名。
     * 重度(3)/极度(4) 且基础模型正好是 deepseek-chat 时，自动升级到 deepseek-reasoner，
     * 这样「思考强度」才真的有推理算力差异；其它模型（自建网关、OpenAI 等）不动，避免用户配的模型被覆盖。
     */
    public static String effectiveModel(Settings s) {
        String base = baseModel(s);
        if (!s.thinkAutoModel || s.thinkLevel < 3) return base;
        if ("deepseek-chat".equalsIgnoreCase(base)) return "deepseek-reasoner";
        return base;
    }

    /** 当前是否因为思考强度而自动切到了推理模型 */
    public static boolean isAutoReasoner(Settings s) {
        return !baseModel(s).equalsIgnoreCase(effectiveModel(s));
    }

    /** 映射到 OpenAI 兼容的 reasoning_effort；默认档返回 null 表示不发送 */
    public static String reasoningEffort(int level) {
        switch (level) {
            case 1:
                return "minimal";
            case 2:
                return "low";
            case 3:
                return "medium";
            case 4:
                return "high";
            default:
                return null;
        }
    }

    /**
     * 给系统提示追加的强化语句。
     * 这样即使后端不认 reasoning_effort 参数，每档也有可感知的差异。
     */
    public static String thinkHint(int level) {
        switch (level) {
            case 1:
                return "请直接给出结论，保持简洁，不要展开推理过程。";
            case 2:
                return "回答前请先在心里梳理清楚思路再作答。";
            case 3:
                return "回答前请先在内部充分推理：拆解问题、检验假设、核对结论，再给出答案。";
            case 4:
                return "回答前请进行尽可能深入的推理：逐步拆解问题、列出多个候选思路、逐一检验并排除错误假设，"
                        + "必要时自我反驳后再给出最终结论。";
            default:
                return "";
        }
    }

    /** 自定义模型：一行一个存进 prefs，空行与重复自动去掉（跨版本升级也不会存坏） */
    private static String joinModels(java.util.List<String> list) {
        if (list == null || list.isEmpty()) return "";
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        for (String m : list) {
            if (m == null) continue;
            String t = m.trim();
            if (!t.isEmpty()) set.add(t);
        }
        StringBuilder sb = new StringBuilder();
        for (String m : set) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(m);
        }
        return sb.toString();
    }

    private static java.util.List<String> splitModels(String raw) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        for (String line : raw.split("\n")) {
            String t = line == null ? "" : line.trim();
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return out;
    }

    /** 加一个自定义模型（已存在就忽略），返回是否真的加了 */
    public boolean addCustomModel(String m) {
        String t = m == null ? "" : m.trim();
        if (t.isEmpty()) return false;
        if (customModels == null) customModels = new java.util.ArrayList<>();
        if (customModels.contains(t)) return false;
        customModels.add(t);
        return true;
    }

    public boolean removeCustomModel(String m) {
        return customModels != null && m != null && customModels.remove(m.trim());
    }
}
