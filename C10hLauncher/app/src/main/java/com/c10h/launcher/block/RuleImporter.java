package com.c10h.launcher.block;

import android.content.Context;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 规则导入解析器（2026-11-24）：
 *
 * 设置页（:settings 进程）负责把"用户选择的文件/URL 内容"解析成规范条目，
 * 写入应用私有目录的临时文件；只把文件路径广播给 :block 进程，
 * 由 BlockVpnService 合并去重后落库。这样避免大 hosts 文件走 Binder extras
 * 超出 1MB 限制，也避免两个进程同时写 c10h_block prefs。
 *
 * 支持格式：
 *   - hosts：`0.0.0.0 ads.example.com`、`127.0.0.1 tracker.example.com`
 *   - 纯域名列表：`ads.example.com`
 *   - 通配写法：`*.example.com` / `.example.com` → 归一为 `example.com`
 *   - 纯 IP 列表：`1.2.3.4`
 *   - 注释：整行 `#`、`;`、`//`，以及行内 `#` 之后
 */
public final class RuleImporter {

    public static final String TMP_NAME = "import_rules.txt";
    /** 单次导入上限（防止误选超大文件拖垮 DNS 判定）。 */
    public static final int MAX_RULES = 50000;

    private static final Set<String> SAFE = new HashSet<String>();

    static {
        SAFE.add("localhost");
        SAFE.add("localhost.localdomain");
        SAFE.add("broadcasthost");
        SAFE.add("ip6-localhost");
        SAFE.add("ip6-loopback");
        SAFE.add("ip6-allnodes");
        SAFE.add("ip6-allrouters");
    }

    private RuleImporter() {
    }

    public static File tmpFile(Context c) {
        return new File(c.getFilesDir(), TMP_NAME);
    }

    /**
     * 解析输入流并写入临时文件。
     *
     * @return 去重后的条目数；0 = 没有可导入内容；-1 = 读写失败
     */
    public static int parseToFile(Context c, InputStream in) {
        if (in == null) {
            return -1;
        }
        BufferedReader r = null;
        BufferedWriter w = null;
        try {
            r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            File f = tmpFile(c);
            w = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(f), "UTF-8"));
            Set<String> seen = new HashSet<String>();
            int n = 0;
            String line;
            while ((line = r.readLine()) != null) {
                if (n >= MAX_RULES) {
                    break;
                }
                String entry = normalize(line);
                if (entry == null) {
                    continue;
                }
                if (!seen.add(entry)) {
                    continue;
                }
                w.write(entry);
                w.write('\n');
                n++;
            }
            w.flush();
            return n;
        } catch (Throwable t) {
            return -1;
        } finally {
            try {
                if (r != null) {
                    r.close();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (w != null) {
                    w.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 读取临时文件（:block 进程用）。每项 = {type, value}。 */
    public static List<String[]> readTmp(Context c) {
        List<String[]> out = new ArrayList<String[]>();
        BufferedReader r = null;
        try {
            File f = tmpFile(c);
            if (!f.isFile()) {
                return out;
            }
            r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab <= 0 || tab >= line.length() - 1) {
                    continue;
                }
                String type = line.substring(0, tab);
                String value = line.substring(tab + 1).trim();
                if (value.isEmpty()) {
                    continue;
                }
                if (!"ip".equals(type) && !"domain".equals(type)) {
                    continue;
                }
                out.add(new String[]{type, value});
                if (out.size() >= MAX_RULES) {
                    break;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                if (r != null) {
                    r.close();
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    public static void clearTmp(Context c) {
        try {
            File f = tmpFile(c);
            if (f.exists() && !f.delete()) {
                f.deleteOnExit();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 单行归一化：返回 "domain\texample.com" / "ip\t1.2.3.4"，不可用返回 null。
     * 兼容 hosts（取最后一个字段）、行内注释、通配前缀。
     */
    static String normalize(String line) {
        try {
            String s = line == null ? "" : line.trim();
            if (s.isEmpty()) {
                return null;
            }
            if (s.startsWith("#") || s.startsWith(";") || s.startsWith("//")) {
                return null;
            }
            int hash = s.indexOf('#');
            if (hash >= 0) {
                s = s.substring(0, hash);
            }
            int slashes = s.indexOf("//");
            if (slashes >= 0) {
                s = s.substring(0, slashes);
            }
            s = s.trim();
            if (s.isEmpty()) {
                return null;
            }
            String[] toks = s.split("\\s+");
            String cand = toks.length >= 2 ? toks[toks.length - 1] : toks[0];
            if (cand.startsWith("*.")) {
                cand = cand.substring(2);
            }
            while (cand.startsWith(".")) {
                cand = cand.substring(1);
            }
            if (cand.endsWith(".")) {
                cand = cand.substring(0, cand.length() - 1);
            }
            cand = cand.toLowerCase(Locale.US);
            if (cand.isEmpty() || cand.length() > 253) {
                return null;
            }
            if (cand.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) {
                return "ip\t" + cand;
            }
            if (!cand.contains(".") || cand.contains("/") || cand.contains(":")) {
                return null;
            }
            if (!cand.matches("^[a-z0-9]([a-z0-9-]{0,62})?"
                    + "(\\.[a-z0-9]([a-z0-9-]{0,62})?)+$")) {
                return null;
            }
            if (SAFE.contains(cand)) {
                return null;
            }
            return "domain\t" + cand;
        } catch (Throwable t) {
            return null;
        }
    }
}
