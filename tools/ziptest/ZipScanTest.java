package com.nebula.aichat.skill;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

public class ZipScanTest {

    public static void main(String[] args) throws Exception {
        String[] cases = {"case1_single.zip", "case2_multi.zip", "case3_github.zip",
                "case4_gbk.zip", "case5_nomd.zip", "case6_slip.zip"};
        for (String c : cases) {
            byte[] data = Files.readAllBytes(new File(c).toPath());
            System.out.println("=== " + c + " (" + data.length + " bytes)");
            try {
                List<ZipSkillScanner.Pack> packs = ZipSkillScanner.scan(data);
                System.out.println("  识别到技能 " + packs.size() + " 个");
                for (ZipSkillScanner.Pack p : packs) {
                    String name = "(无 frontmatter 名)";
                    int i = p.text.indexOf("name:");
                    if (i >= 0) {
                        int e = p.text.indexOf('\n', i);
                        name = p.text.substring(i + 5, e < 0 ? p.text.length() : e).trim();
                    }
                    System.out.println("  - dir=[" + p.dir + "] id=" + p.baseName()
                            + " name=" + name + " md=" + p.mdName
                            + " extras=" + p.extras.keySet());
                }
            } catch (Exception e) {
                System.out.println("  ✗ 失败：" + e.getMessage());
            }
        }
    }
}
