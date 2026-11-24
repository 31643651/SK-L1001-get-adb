package com.c10h.launcher.block;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 规则单一写者（补充文档 §4，评审 S4）：仅 :block 进程读写 c10h_block；
 * 设置页只经广播带值——BLK_CMD 下发指令，BLK_STATE 回传全量 JSON。
 * 首次运行从 assets/seed_rules.json 导入种子（D4）。
 */
public final class RuleStore {

    public static final String PREFS = "c10h_block";
    public static final String ACTION_CMD = "com.c10h.launcher.action.BLK_CMD";
    public static final String ACTION_STATE = "com.c10h.launcher.action.BLK_STATE";
    public static final String EXTRA_STATE = "state";
    public static final String K_VPN = "vpn_enabled";

    public static final class Rule {
        public String type;
        public String value;
        public boolean on;
        public String note;
    }

    private RuleStore() {
    }

    static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized void ensureSeed(Context c) {
        try {
            if (p(c).contains("rules")) {
                return;
            }
            InputStream in = c.getAssets().open("seed_rules.json");
            byte[] b = new byte[Math.max(64, in.available())];
            int n = 0;
            int r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) {
                n += r;
            }
            in.close();
            JSONObject o = new JSONObject(new String(b, 0, Math.max(0, n), "UTF-8"));
            p(c).edit().putString("rules", o.getJSONArray("rules").toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    public static synchronized List<Rule> load(Context c) {
        List<Rule> out = new ArrayList<Rule>();
        try {
            JSONArray a = new JSONArray(p(c).getString("rules", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                Rule r = new Rule();
                r.type = o.optString("type", "domain");
                r.value = o.optString("value", "");
                r.on = o.optBoolean("on", false);
                r.note = o.optString("note", "");
                if (r.value.length() > 0) {
                    out.add(r);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static synchronized void save(Context c, List<Rule> rs) {
        try {
            JSONArray a = new JSONArray();
            for (Rule r : rs) {
                JSONObject o = new JSONObject();
                o.put("type", r.type);
                o.put("value", r.value);
                o.put("on", r.on);
                o.put("note", r.note == null ? "" : r.note);
                a.put(o);
            }
            p(c).edit().putString("rules", a.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 命中判定：domain 后缀匹配；ip 精确匹配。 */
    public static boolean blocked(Context c, String hostOrIp) {
        if (hostOrIp == null) {
            return false;
        }
        String h = hostOrIp.toLowerCase();
        for (Rule r : load(c)) {
            if (!r.on) {
                continue;
            }
            String v = r.value.toLowerCase();
            if ("ip".equals(r.type)) {
                if (h.equals(v)) {
                    recordHit(c, h);
                    return true;
                }
            } else if (h.equals(v) || h.endsWith("." + v)) {
                recordHit(c, h);
                return true;
            }
        }
        return false;
    }

    static void recordHit(Context c, String host) {
        try {
            JSONArray a = new JSONArray(p(c).getString("hits", "[]"));
            a.put(new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA)
                    .format(new java.util.Date()) + "  " + host);
            while (a.length() > 50) {
                a.remove(0);
            }
            p(c).edit().putString("hits", a.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    public static void broadcast(Context c) {
        broadcast(c, -1);
    }

    /** imported >= 0 时额外携带"本次合并进多少条"，供设置页提示。 */
    public static void broadcast(Context c, int imported) {
        try {
            JSONObject o = new JSONObject();
            o.put("vpn", p(c).getBoolean(K_VPN, false));
            o.put("guard", AdbGuard.enabled(c));
            o.put("adbd", AdbGuard.adbdState());
            o.put("log", AdbGuard.recentLog(c));
            o.put("rules", new JSONArray(p(c).getString("rules", "[]")));
            o.put("hits", new JSONArray(p(c).getString("hits", "[]")));
            Intent i = new Intent(ACTION_STATE);
            i.setPackage(c.getPackageName());
            i.putExtra(EXTRA_STATE, o.toString());
            if (imported >= 0) {
                i.putExtra("imported", imported);
            }
            c.sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }
}
