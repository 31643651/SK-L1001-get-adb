package com.c10h.launcher;

import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 应用启动管理（2026-11-24）。
 *
 * Android 9 没有国产 ROM 那种“自启动/关联启动”专用开关，这里用系统现成能力实现：
 *  - 自启动：启用/停用该应用的 BOOT_COMPLETED（及 LOCKED_BOOT/QUICKBOOT）广播接收器；
 *  - 关联启动：START_FOREGROUND appop（禁止其在后台拉起前台服务），
 *              Android 9 无 START_ACTIVITIES_FROM_BACKGROUND，这是最接近的系统级限制；
 *  - 后台运行：RUN_IN_BACKGROUND + RUN_ANY_IN_BACKGROUND appop。
 *
 * 只管理“非系统应用”，且跳过桌面自身。所有操作失败都只返回 false，不抛出。
 */
public final class AppStartupManager {

    private static final String TAG = "C10HStartup";

    /** 本机 ROM 没有 BOOT_COMPLETED appop，只能操作组件启用状态。 */
    private static final String[] BOOT_ACTIONS = {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
    };

    private static final int FLAG_INCLUDE_DISABLED = 0x00000020; // GET_DISABLED_COMPONENTS

    /**
     * 系统应用白名单：这些是厂商预装的系统应用，但用户需要像第三方应用一样管理启动行为。
     * 喜马拉雅、网易云音乐。
     */
    private static final Set<String> SYSTEM_WHITELIST = new HashSet<String>();

    static {
        SYSTEM_WHITELIST.add("com.ximalayaos.pad");
        SYSTEM_WHITELIST.add("com.netease.cloudmusic.iot");
    }

    /*
     * API 28 的 appop 编号（已用 `cmd appops set <pkg> <num> ignore` + 名称查询实测确认）：
     *   76 = START_FOREGROUND
     *   63 = RUN_IN_BACKGROUND
     *   70 = RUN_ANY_IN_BACKGROUND
     * 名称重载在这台 ROM 上不可用（Unknown operation string），所以用数字 + 反射。
     */
    private static final int OP_START_FOREGROUND = 76;
    private static final int OP_RUN_IN_BACKGROUND = 63;
    private static final int OP_RUN_ANY_IN_BACKGROUND = 70;

    public static final class AppEntry {
        public String pkg;
        public String label;
        public Drawable icon;
        public int uid;
        public boolean hasBoot;
        public boolean auto;
        public boolean assoc;
        public boolean bg;
    }

    private AppStartupManager() {
    }

    /** 读取全部可管理的第三方应用及其当前状态。 */
    public static List<AppEntry> load(Context c) {
        List<AppEntry> out = new ArrayList<AppEntry>();
        try {
            PackageManager pm = c.getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            for (ApplicationInfo ai : apps) {
                if (ai == null || ai.packageName == null) {
                    continue;
                }
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        || (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
                    // 系统应用默认不管理；只有白名单里的系统应用放行。
                    if (!SYSTEM_WHITELIST.contains(ai.packageName)) {
                        continue;
                    }
                }
                if (!ai.enabled) {
                    continue;
                }
                if (c.getPackageName().equals(ai.packageName)) {
                    continue; // 不管理桌面自己
                }
                AppEntry e = new AppEntry();
                e.pkg = ai.packageName;
                e.uid = ai.uid;
                try {
                    CharSequence l = ai.loadLabel(pm);
                    e.label = l == null ? e.pkg : l.toString();
                } catch (Throwable t) {
                    e.label = e.pkg;
                }
                try {
                    e.icon = ai.loadIcon(pm);
                } catch (Throwable ignored) {
                }
                List<ComponentName> boot = bootReceivers(c, e.pkg);
                e.hasBoot = !boot.isEmpty();
                e.auto = isAutoOn(c, boot);
                e.assoc = isOpAllowed(c, OP_START_FOREGROUND, e.uid, e.pkg);
                e.bg = isOpAllowed(c, OP_RUN_IN_BACKGROUND, e.uid, e.pkg)
                        && isOpAllowed(c, OP_RUN_ANY_IN_BACKGROUND, e.uid, e.pkg);
                out.add(e);
            }
            Collections.sort(out, new Comparator<AppEntry>() {
                @Override
                public int compare(AppEntry a, AppEntry b) {
                    return String.CASE_INSENSITIVE_ORDER.compare(a.label, b.label);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "load failed: " + t);
        }
        return out;
    }

    /** 该应用所有与开机启动相关的接收器（含已停用的）。 */
    public static List<ComponentName> bootReceivers(Context c, String pkg) {
        List<ComponentName> list = new ArrayList<ComponentName>();
        Set<String> seen = new HashSet<String>();
        try {
            PackageManager pm = c.getPackageManager();
            for (String action : BOOT_ACTIONS) {
                Intent i = new Intent(action);
                i.setPackage(pkg);
                List<ResolveInfo> rs = pm.queryBroadcastReceivers(i, FLAG_INCLUDE_DISABLED);
                if (rs == null) {
                    continue;
                }
                for (ResolveInfo ri : rs) {
                    if (ri == null || ri.activityInfo == null
                            || !pkg.equals(ri.activityInfo.packageName)) {
                        continue;
                    }
                    ComponentName cn = new ComponentName(pkg, ri.activityInfo.name);
                    if (seen.add(cn.flattenToString())) {
                        list.add(cn);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "bootReceivers " + pkg + " failed: " + t);
        }
        return list;
    }

    private static boolean isAutoOn(Context c, List<ComponentName> boot) {
        if (boot.isEmpty()) {
            return false;
        }
        try {
            PackageManager pm = c.getPackageManager();
            for (ComponentName cn : boot) {
                int st = pm.getComponentEnabledSetting(cn);
                if (st == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                    return true;
                }
                if (st == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                    ActivityInfo ai = pm.getReceiverInfo(cn, 0);
                    if (ai != null && ai.enabled) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static boolean setAuto(Context c, AppEntry e, boolean on) {
        if (e == null || !e.hasBoot) {
            return false;
        }
        boolean ok = false;
        try {
            PackageManager pm = c.getPackageManager();
            for (ComponentName cn : bootReceivers(c, e.pkg)) {
                try {
                    pm.setComponentEnabledSetting(cn,
                            on ? PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP);
                    ok = true;
                } catch (Throwable t) {
                    Log.w(TAG, "setAuto " + cn + " failed: " + t);
                }
            }
            e.auto = on;
        } catch (Throwable ignored) {
        }
        return ok;
    }

    public static boolean setAssoc(Context c, AppEntry e, boolean on) {
        if (e == null) {
            return false;
        }
        boolean ok = setOp(c, OP_START_FOREGROUND, e.uid, e.pkg, on);
        if (ok) {
            e.assoc = on;
        }
        return ok;
    }

    public static boolean setBackground(Context c, AppEntry e, boolean on) {
        if (e == null) {
            return false;
        }
        boolean a = setOp(c, OP_RUN_IN_BACKGROUND, e.uid, e.pkg, on);
        boolean b = setOp(c, OP_RUN_ANY_IN_BACKGROUND, e.uid, e.pkg, on);
        boolean ok = a || b;
        if (ok) {
            e.bg = on;
        }
        return ok;
    }

    /** 一键全部开启/关闭；返回是否至少成功一项。 */
    public static boolean setAll(Context c, List<AppEntry> apps, boolean on) {
        boolean any = false;
        if (apps == null) {
            return false;
        }
        for (AppEntry e : apps) {
            try {
                if (e.hasBoot) {
                    any |= setAuto(c, e, on);
                }
                any |= setAssoc(c, e, on);
                any |= setBackground(c, e, on);
            } catch (Throwable ignored) {
            }
        }
        return any;
    }

    private static boolean isOpAllowed(Context c, int op, int uid, String pkg) {
        try {
            AppOpsManager aom = (AppOpsManager)
                    c.getSystemService(Context.APP_OPS_SERVICE);
            if (aom == null || op < 0) {
                return true;
            }
            java.lang.reflect.Method m = AppOpsManager.class.getMethod(
                    "checkOpNoThrow", int.class, int.class, String.class);
            Object r = m.invoke(aom, op, uid, pkg);
            int mode = r instanceof Integer ? (Integer) r : AppOpsManager.MODE_ALLOWED;
            return mode != AppOpsManager.MODE_IGNORED
                    && mode != AppOpsManager.MODE_ERRORED;
        } catch (Throwable t) {
            return true;
        }
    }

    private static boolean setOp(Context c, int op, int uid, String pkg, boolean on) {
        try {
            AppOpsManager aom = (AppOpsManager)
                    c.getSystemService(Context.APP_OPS_SERVICE);
            if (aom == null || op < 0) {
                return false;
            }
            int mode = on ? AppOpsManager.MODE_ALLOWED : AppOpsManager.MODE_IGNORED;
            // setMode 在公开 SDK 里是 @hide（@SystemApi），只能反射调用。
            java.lang.reflect.Method m = AppOpsManager.class.getMethod(
                    "setMode", int.class, int.class, String.class, int.class);
            m.invoke(aom, op, uid, pkg, mode);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "setOp op=" + op + " pkg=" + pkg + " on=" + on + " failed: " + t);
            return false;
        }
    }
}
