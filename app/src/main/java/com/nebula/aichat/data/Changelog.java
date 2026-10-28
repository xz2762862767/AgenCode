package com.nebula.aichat.data;

/**
 * 更新日志。
 *
 * <p>放在代码里而不是远程拉取：这是离线可用的应用，为了一个"我改了什么"的列表
 * 去联网请求，属于给自己找故障点。发版时在数组头部加一条即可。
 *
 * <p>只记录能对应到实际改动的条目，不写"优化体验"这种放之四海皆准的空话。
 */
public final class Changelog {

    private Changelog() {
    }

    public static final class Entry {
        public final String version;
        public final String[] items;

        Entry(String version, String... items) {
            this.version = version;
            this.items = items;
        }
    }

    /** 新的在最前面 */
    public static final Entry[] ENTRIES = {
            new Entry("1.8.98",
                    "新增内置浏览器：侧滑栏「浏览器」入口，在应用内直接看文档、查报错，不用跳出对话",
                    "AI 翻译：整页正文译成中文并回填到原位置，排版、链接、滚动都保留；英文页面自动触发",
                    "翻译可随时点「原」切回原文；代码块与命令行内容不会被翻译",
                    "关于页新增本更新日志"),

            new Entry("1.8.97",
                    "清理界面残留的旧品牌名",
                    "系统提示词自动迁移到 AgenCode：老版本存下的旧内置文案会被静默替换，自己改过的不动",
                    "工程页顶部徽标改为品牌首字母 A"),

            new Entry("1.8.92",
                    "后台保活通知改用独立渠道，静音不打扰，默认开启",
                    "运行诊断页可区分通知权限被拒 / 渠道被关 / ROM 拦截 / 服务未运行"),

            new Entry("1.8.54",
                    "稳定性与体验治理：闪退、线程安全、会话文件损坏、长文本截断等问题集中修复",
                    "工具调用记录一轮结束后默认收起为「思考完成 ▾」"),

            new Entry("1.8.50",
                    "添加模型改为服务商预设：选服务商 → 选模型，接口地址自动填好，Key 只需填一次",
                    "修复切换服务商后旧模型列表残留"),
    };
}
