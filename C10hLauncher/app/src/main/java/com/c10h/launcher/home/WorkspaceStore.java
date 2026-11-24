package com.c10h.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/**
 * 桌面设置/移除集合的单写者（M9）。
 * 固定栏条目已迁移到 SQLite favorites（container=HOTSEAT），不再存 JSON。
 */
public final class WorkspaceStore {

    private static final String PREFS = "c10h_launcher";
    private static final String K_REMOVED = "ws_removed";
    /** 经典桌面的"已移除"独立存储：抽屉里移除过的应用不应在经典桌面消失。 */
    private static final String K_REMOVED_CLASSIC = "ws_removed_classic";
    private static final String K_NAV = "nav_bar_enabled";
    private static final String K_ADD_ICON = "set_add_icon";
    private static final String K_DOTS = "set_notification_dots";
    private static final String K_ROTATE = "set_allow_rotation";
    private static final String K_BASELINE = "auto_add_baseline";
    private static final String K_APPS_SYSTEM = "set_apps_show_system";
    private static final String K_CLASSIC = "set_classic_mode";
    private static final String K_NAV_REVERSED = "set_nav_reversed";
    private static final String K_GESTURE = "set_gesture_nav";
    private static final String K_CLASSIC_SEEDED = "classic_folder_seeded";

    // 设置页在 :settings 进程写；主进程通过广播拿到新值后写入运行时覆盖，
    // 避免 SharedPreferences 跨进程缓存导致开关“写了不生效”。
    private static Boolean rtAddIcon;
    private static Boolean rtDots;
    private static Boolean rtNav;
    private static Boolean rtRotate;
    private static Boolean rtClassic;
    private static Boolean rtNavReversed;
    private static Boolean rtGesture;

    private WorkspaceStore() {
    }

    private static SharedPreferences prefs(Context c) {
        // 设置页在 :settings 进程写，桌面在默认进程读；MULTI_PROCESS 保证跨进程可见
        return c.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS);
    }

    public static Set<String> removed(Context c) {
        Set<String> s = prefs(c).getStringSet(K_REMOVED, null);
        return s == null ? new HashSet<String>() : new HashSet<String>(s);
    }

    /** 经典桌面专用的"已移除"集合（与抽屉互不影响）。 */
    public static Set<String> removedClassic(Context c) {
        Set<String> s = prefs(c).getStringSet(K_REMOVED_CLASSIC, null);
        return s == null ? new HashSet<String>() : new HashSet<String>(s);
    }

    public static void addRemoved(Context c, String key) {
        addRemoved(c, key, false);
    }

    public static void addRemoved(Context c, String key, boolean classic) {
        Set<String> s = classic ? removedClassic(c) : removed(c);
        s.add(key);
        prefs(c).edit()
                .putStringSet(classic ? K_REMOVED_CLASSIC : K_REMOVED, s)
                .apply();
    }

    public static void removeRemoved(Context c, String key) {
        Set<String> s = removed(c);
        s.remove(key);
        prefs(c).edit().putStringSet(K_REMOVED, s).apply();
    }

    public static String itemKey(String pkg, String cls) {
        return pkg + "/" + cls;
    }

    public static boolean navEnabled(Context c) {
        return rtNav != null ? rtNav : prefs(c).getBoolean(K_NAV, true);
    }

    public static void setNavEnabled(Context c, boolean on) {
        rtNav = on;
        prefs(c).edit().putBoolean(K_NAV, on).apply();
    }

    public static boolean addIconToHome(Context c) {
        return rtAddIcon != null ? rtAddIcon : prefs(c).getBoolean(K_ADD_ICON, true);
    }

    public static void setAddIconToHome(Context c, boolean on) {
        rtAddIcon = on;
        prefs(c).edit().putBoolean(K_ADD_ICON, on).apply();
    }

    public static boolean notificationDots(Context c) {
        return rtDots != null ? rtDots : prefs(c).getBoolean(K_DOTS, true);
    }

    public static void setNotificationDots(Context c, boolean on) {
        rtDots = on;
        prefs(c).edit().putBoolean(K_DOTS, on).apply();
    }

    public static boolean allowRotation(Context c) {
        return rtRotate != null ? rtRotate : prefs(c).getBoolean(K_ROTATE, false);
    }

    public static void setAllowRotation(Context c, boolean on) {
        rtRotate = on;
        prefs(c).edit().putBoolean(K_ROTATE, on).apply();
    }

    /** 主进程收到设置广播后调用，写入运行时覆盖。 */
    public static void applyRuntime(boolean add, boolean dots, boolean nav, boolean rotate,
                                    boolean classic, boolean navReversed, boolean gesture) {
        rtAddIcon = add;
        rtDots = dots;
        rtNav = nav;
        rtRotate = rotate;
        rtClassic = classic;
        rtNavReversed = navReversed;
        rtGesture = gesture;
    }

    /** 跨进程广播可能被后台限制拦截；onResume 时直接从 prefs 重新同步。 */
    public static void reload(Context c) {
        SharedPreferences p = prefs(c);
        rtAddIcon = p.getBoolean(K_ADD_ICON, true);
        rtDots = p.getBoolean(K_DOTS, true);
        rtNav = p.getBoolean(K_NAV, true);
        rtRotate = p.getBoolean(K_ROTATE, false);
        rtClassic = p.getBoolean(K_CLASSIC, true);
        rtNavReversed = p.getBoolean(K_NAV_REVERSED, false);
        rtGesture = p.getBoolean(K_GESTURE, true);
    }

    /** 经典桌面：所有应用都放在工作区；关闭时是抽屉模式。默认开启。 */
    public static boolean classicMode(Context c) {
        return rtClassic != null ? rtClassic : prefs(c).getBoolean(K_CLASSIC, true);
    }

    public static void setClassicMode(Context c, boolean on) {
        rtClassic = on;
        prefs(c).edit().putBoolean(K_CLASSIC, on).apply();
    }

    /** 导航键顺序：false=返回|主页|多任务，true=多任务|主页|返回。 */
    public static boolean navReversed(Context c) {
        return rtNavReversed != null ? rtNavReversed
                : prefs(c).getBoolean(K_NAV_REVERSED, false);
    }

    public static void setNavReversed(Context c, boolean on) {
        rtNavReversed = on;
        prefs(c).edit().putBoolean(K_NAV_REVERSED, on).apply();
    }

    /** 手势导航：边缘内滑返回、底部上滑主页、上滑悬停多任务。默认开启。 */
    public static boolean gestureNav(Context c) {
        return rtGesture != null ? rtGesture : prefs(c).getBoolean(K_GESTURE, true);
    }

    public static void setGestureNav(Context c, boolean on) {
        rtGesture = on;
        prefs(c).edit().putBoolean(K_GESTURE, on).apply();
    }

    /** 经典桌面是否已从抽屉模板补入过文件夹（只补一次，之后完全独立）。 */
    public static boolean classicFolderSeeded(Context c) {
        return prefs(c).getBoolean(K_CLASSIC_SEEDED, false);
    }

    public static void setClassicFolderSeeded(Context c, boolean v) {
        // 同步写：避免并发 load 时两次补入文件夹。
        prefs(c).edit().putBoolean(K_CLASSIC_SEEDED, v).commit();
    }

    /** 恢复默认设置：只重置 set_ 键，不删数据库与用户条目（规格 §4）。 */
    public static void resetToDefaults(Context c) {
        rtAddIcon = true;
        rtDots = true;
        rtNav = true;
        rtRotate = false;
        rtClassic = true;
        rtNavReversed = false;
        rtGesture = true;
        prefs(c).edit()
                .putBoolean(K_ADD_ICON, true)
                .putBoolean(K_DOTS, true)
                .putBoolean(K_NAV, true)
                .putBoolean(K_ROTATE, false)
                .putBoolean(K_CLASSIC, true)
                .putBoolean(K_NAV_REVERSED, false)
                .putBoolean(K_GESTURE, true)
                .apply();
    }

    /** 应用列表是否显示系统应用（false = 仅用户应用）。 */
    public static boolean showSystemApps(Context c) {
        return prefs(c).getBoolean(K_APPS_SYSTEM, true);
    }

    public static void setShowSystemApps(Context c, boolean on) {
        prefs(c).edit().putBoolean(K_APPS_SYSTEM, on).apply();
    }

    /** 首启自动加图标的基线应用集（只对之后新装的应用生效）。 */
    public static Set<String> autoAddBaseline(Context c) {        Set<String> s = prefs(c).getStringSet(K_BASELINE, null);
        return s == null ? null : new HashSet<String>(s);
    }

    public static void setAutoAddBaseline(Context c, Set<String> keys) {
        prefs(c).edit().putStringSet(K_BASELINE, new HashSet<String>(keys)).apply();
    }
}
