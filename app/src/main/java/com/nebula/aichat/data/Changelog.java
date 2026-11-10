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

    /**
     * 新的在最前面。
     *
     * <p>只保留最近两三个版本：关于页是给人"我刚更新了什么"看的，
     * 不是版本博物馆，堆十几条旧记录只会把重点淹掉。
     */
    public static final Entry[] ENTRIES = {
            new Entry("3.0",
                    "版本号从 3.0 重新起算，更新日志也只留这一版",
                    "这一版把翻页按钮彻底修好了。之前右上角那对小圆钮里其实什么都没有："
                            + "按钮的宽高在往容器里添加时被写成了「按内容自适应」，覆盖掉原本给的 42dp，"
                            + "而图形自己又没报告固有尺寸，于是量出来的可绘区域只剩一个像素，三角被压成了一个点。"
                            + "现在尺寸在添加时就写死，图形也补上固有尺寸，向上、向下各一枚实心三角，一眼能分清方向。",
                    "更新日志从这一版起只保留当前版本，不再堆历史记录——"
                            + "它是给人看「这次更新了什么」的，不是版本博物馆。")
    };
}
