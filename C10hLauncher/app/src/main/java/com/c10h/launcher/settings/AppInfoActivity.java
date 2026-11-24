package com.c10h.launcher.settings;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Debug;
import android.os.PowerManager;
import android.os.Process;
import android.os.storage.StorageManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 自研应用信息页（AOSP App info 对齐），不依赖 com.android.settings：
 * 通知、权限、存储与缓存、数据用量、电池/后台限制、内存、默认打开、
 * 特殊访问（悬浮窗 / 修改系统设置 / 使用情况访问 / 安装未知应用）、
 * 打开 / 强制停止 / 停用 / 卸载，以及完整应用元信息。
 *
 * 系统设置（com.android.settings）被禁用时本页仍可独立打开并完成全部操作。
 */
public class AppInfoActivity extends Activity {

    public static final String EXTRA_PKG = "pkg";

    private static final int GET_INTENT_FILTERS = 0x00000800;

    private PackageManager pm;
    private AppOpsManager ops;
    private String pkg;
    private ApplicationInfo ai;
    private PackageInfo pi;

    private LinearLayout barHost;
    private LinearLayout pageBarHost;
    private ScrollView scroll;
    private LinearLayout content;

    private String page = "home";
    private String pageTitle = "应用信息";
    private final ArrayList<String[]> stack = new ArrayList<String[]>();
    private boolean lastWasRow;
    private float pageAlpha = 1f;

    private boolean storageLoaded;
    private long appBytes;
    private long dataBytes;
    private long cacheBytes;
    private boolean usageDenied;

    // ---------------- 生命周期 ----------------

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            pkg = getIntent() != null ? getIntent().getStringExtra(EXTRA_PKG) : null;
            if (pkg == null && getIntent() != null && getIntent().getData() != null) {
                pkg = getIntent().getData().getSchemeSpecificPart();
            }
            pm = getPackageManager();
            ops = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            ai = pm.getApplicationInfo(pkg, 0);
            pi = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS
                    | PackageManager.GET_SIGNATURES);
            buildShell();
            showHome();
        } catch (Throwable t) {
            LinearLayout err = new LinearLayout(this);
            err.setOrientation(LinearLayout.VERTICAL);
            err.setBackgroundColor(SettingsUi.BG);
            err.setGravity(Gravity.CENTER);
            TextView tv = new TextView(this);
            tv.setText("应用信息打开失败\n" + t);
            tv.setTextColor(0xFFD32F2F);
            err.addView(tv);
            setContentView(err);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String p = intent != null ? intent.getStringExtra(EXTRA_PKG) : null;
        if (p == null && intent != null && intent.getData() != null) {
            p = intent.getData().getSchemeSpecificPart();
        }
        if (p == null || p.equals(pkg)) {
            return;
        }
        try {
            pkg = p;
            ai = pm.getApplicationInfo(pkg, 0);
            pi = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS
                    | PackageManager.GET_SIGNATURES);
            page = "home";
            pageTitle = "应用信息";
            stack.clear();
            buildShell();
            showHome();
        } catch (Throwable t) {
            toast("应用信息打开失败：" + t);
        }
    }

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(SettingsUi.BG);

        barHost = new LinearLayout(this);
        barHost.setOrientation(LinearLayout.VERTICAL);
        root.addView(barHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        pageBarHost = new LinearLayout(this);
        pageBarHost.setOrientation(LinearLayout.VERTICAL);
        root.addView(pageBarHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(SettingsUi.BG);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    // ---------------- 导航 ----------------

    private void setBar(View v) {
        barHost.removeAllViews();
        if (v != null) {
            barHost.addView(v, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private void showHome() {
        page = "home";
        pageTitle = "应用信息";
        stack.clear();
        pageBarHost.removeAllViews();
        setBar(SettingsUi.appBar(this, "应用信息", new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }));
        content.removeAllViews();
        lastWasRow = false;
        pageAlpha = 1f;
        buildHeader(content);
        buildActionRow(content);
        buildMainRows(content);
        content.addView(new View(this), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, SettingsUi.dp(this, 16)));
        animateIn();
    }

    private void openPage(String id, String title) {
        if (!"home".equals(page)) {
            stack.add(new String[]{page, pageTitle});
        }
        openPageInternal(id, title);
    }

    private void openPageInternal(String id, String title) {
        page = id;
        pageTitle = title;
        pageBarHost.removeAllViews();
        setBar(SettingsUi.appBar(this, title, new Runnable() {
            @Override
            public void run() {
                goUp();
            }
        }));
        content.removeAllViews();
        lastWasRow = false;
        pageAlpha = 1f;
        try {
            if ("perm".equals(id)) {
                buildPermissions(content);
            } else if ("storage".equals(id)) {
                buildStorage(content);
            } else if ("data".equals(id)) {
                buildData(content);
            } else if ("battery".equals(id)) {
                buildBattery(content);
            } else if ("memory".equals(id)) {
                buildMemory(content);
            } else if ("defaults".equals(id)) {
                buildDefaults(content);
            } else if ("about".equals(id)) {
                buildAbout(content);
            }
        } catch (Throwable t) {
            content.removeAllViews();
            lastWasRow = false;
            addPlain(SettingsUi.body(this, "此页面不可用\n（" + t + "）"));
        }
        animateIn();
    }

    private void goUp() {
        if (stack.isEmpty()) {
            showHome();
            return;
        }
        String[] prev = stack.remove(stack.size() - 1);
        openPageInternal(prev[0], prev[1]);
    }

    @Override
    public void onBackPressed() {
        if (!"home".equals(page)) {
            goUp();
            return;
        }
        super.onBackPressed();
    }

    private void animateIn() {
        try {
            content.setAlpha(0f);
            content.setTranslationX(SettingsUi.dp(this, 16));
            content.animate().alpha(pageAlpha).translationX(0f)
                    .setDuration(200)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(2f))
                    .start();
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 行辅助 ----------------

    private void addRow(View v) {
        if (lastWasRow) {
            content.addView(SettingsUi.divider(this));
        }
        content.addView(v, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        lastWasRow = true;
    }

    private void addPlain(View v) {
        content.addView(v, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        lastWasRow = false;
    }

    private void header(String text) {
        addPlain(SettingsUi.header(this, text));
    }

    // ---------------- 主页面 ----------------

    private String label() {
        try {
            return pm.getApplicationLabel(ai).toString();
        } catch (Throwable t) {
            return pkg;
        }
    }

    private String versionName() {
        try {
            return pi.versionName == null ? "" : pi.versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    private void buildHeader(LinearLayout c) {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(SettingsUi.dp(this, 16), SettingsUi.dp(this, 16),
                SettingsUi.dp(this, 16), SettingsUi.dp(this, 8));

        ImageView icon = new ImageView(this);
        try {
            icon.setImageDrawable(pm.getApplicationIcon(pkg));
        } catch (Throwable ignored) {
        }
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                SettingsUi.dp(this, 56), SettingsUi.dp(this, 56));
        ilp.rightMargin = SettingsUi.dp(this, 16);
        head.addView(icon, ilp);

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(label());
        name.setTextColor(SettingsUi.TEXT);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        name.setTypeface(SettingsUi.medium(this));
        mid.addView(name);
        TextView ver = new TextView(this);
        String v = versionName();
        ver.setText("版本 " + (v.isEmpty() ? "未知" : v) + "（" + pi.versionCode + "）");
        ver.setTextColor(SettingsUi.SUB);
        ver.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        ver.setPadding(0, SettingsUi.dp(this, 3), 0, 0);
        mid.addView(ver);
        TextView pn = new TextView(this);
        pn.setText(pkg);
        pn.setTextColor(SettingsUi.SUB);
        pn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        pn.setPadding(0, SettingsUi.dp(this, 2), 0, 0);
        mid.addView(pn);
        head.addView(mid, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        c.addView(head);
        lastWasRow = false;
    }

    private boolean isSystemFixed() {
        return (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
    }

    private boolean isRemovable() {
        return !isSystemFixed();
    }

    private boolean isDisabled() {
        try {
            int st = pm.getApplicationEnabledSetting(pkg);
            return st == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || st == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    || st == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
        } catch (Throwable t) {
            return !ai.enabled;
        }
    }

    private void buildActionRow(LinearLayout c) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(SettingsUi.dp(this, 16), SettingsUi.dp(this, 4),
                SettingsUi.dp(this, 16), SettingsUi.dp(this, 8));

        final Intent launch = pm.getLaunchIntentForPackage(pkg);
        if (launch != null && !isDisabled()) {
            View open = SettingsUi.actionButton(this, "打开", true,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            try {
                                startActivity(launch);
                            } catch (Throwable t) {
                                toast("无法打开：" + t);
                            }
                        }
                    });
            row.addView(open, marginRight(8));
        }
        View stop = SettingsUi.actionButton(this, "强制停止", false,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        new AlertDialog.Builder(AppInfoActivity.this)
                                .setTitle("强制停止")
                                .setMessage("强制停止「" + label() + "」？可能丢失未保存的数据。")
                                .setPositiveButton("强制停止",
                                        new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface d, int w) {
                                                forceStop();
                                            }
                                        })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                });
        row.addView(stop, marginRight(8));

        // AOSP 对系统应用也提供“停用”，但持久化应用与自身不可停用。
        boolean canToggleEnabled = !pkg.equals(getPackageName())
                && (ai.flags & ApplicationInfo.FLAG_PERSISTENT) == 0;
        if (canToggleEnabled) {
            View enable = SettingsUi.actionButton(this, isDisabled() ? "启用" : "停用", false,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            toggleEnabled();
                        }
                    });
            row.addView(enable, marginRight(8));
        }
        if (isRemovable()) {
            View un = SettingsUi.actionButton(this, "卸载", false,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            uninstall();
                        }
                    });
            row.addView(un, marginRight(8));
        }
        c.addView(row);
        lastWasRow = false;
    }

    private LinearLayout.LayoutParams marginRight(int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = SettingsUi.dp(this, dp);
        return lp;
    }

    private void buildMainRows(LinearLayout c) {
        header("应用信息");

        LinearLayout notifRow = SettingsUi.switchPref(this, -1, "通知",
                isNotifEnabled() ? "已允许发送通知" : "通知已关闭",
                isNotifEnabled(), null);
        final TextView notifSummary = rowSummary(notifRow);
        Switch notifSwitch = (Switch) notifRow.getChildAt(notifRow.getChildCount() - 1);
        notifSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                if (!setOp("android:post_notification", on)) {
                    b.setChecked(!on);
                    toast("通知开关写入失败");
                    return;
                }
                if (notifSummary != null) {
                    notifSummary.setText(on ? "已允许发送通知" : "通知已关闭");
                }
            }
        });
        addRow(notifRow);

        int[] permCount = dangerousPermCount();
        addRow(SettingsUi.pref(this, -1, "权限",
                permCount[1] == 0 ? "无运行时权限"
                        : ("已允许 " + permCount[0] + " / " + permCount[1] + " 项"), null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openPage("perm", "权限");
                    }
                }));

        loadStorage();
        String storageSummary;
        if (storageLoaded) {
            storageSummary = "数据 " + fmtBytes(dataBytes) + " · 缓存 " + fmtBytes(cacheBytes);
        } else {
            storageSummary = "点击查看存储与缓存";
        }
        addRow(SettingsUi.pref(this, -1, "存储和缓存", storageSummary, null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openPage("storage", "存储和缓存");
                    }
                }));

        addRow(SettingsUi.pref(this, -1, "移动数据与 WLAN",
                usageDenied ? "需要用量访问权限" : "查看最近 30 天用量", null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openPage("data", "移动数据与 WLAN");
                    }
                }));

        addRow(SettingsUi.pref(this, -1, "电池",
                isBackgroundRestricted() ? "后台活动：已限制" : "后台活动：未限制", null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openPage("battery", "电池");
                    }
                }));

        addRow(SettingsUi.pref(this, -1, "内存", "查看进程内存占用", null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openPage("memory", "内存");
                    }
                }));

        addRow(SettingsUi.pref(this, -1, "默认打开", defaultSummary(), null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openPage("defaults", "默认打开");
                    }
                }));

        boolean hasSpecial = requests("android.permission.SYSTEM_ALERT_WINDOW")
                || requests("android.permission.WRITE_SETTINGS")
                || requests("android.permission.PACKAGE_USAGE_STATS")
                || requests("android.permission.REQUEST_INSTALL_PACKAGES");
        if (hasSpecial) {
            header("特殊访问");
            if (requests("android.permission.SYSTEM_ALERT_WINDOW")) {
                addOpSwitch("悬浮窗", "android:system_alert_window", false);
            }
            if (requests("android.permission.WRITE_SETTINGS")) {
                addOpSwitch("修改系统设置", "android:write_settings", false);
            }
            if (requests("android.permission.PACKAGE_USAGE_STATS")) {
                addOpSwitch("使用情况访问", "android:get_usage_stats", true);
            }
            if (requests("android.permission.REQUEST_INSTALL_PACKAGES")) {
                addOpSwitch("安装未知应用", "android:request_install_packages", false);
            }
        }

        header("关于");
        addRow(SettingsUi.pref(this, -1, "包名", pkg, null, null));
        addRow(SettingsUi.pref(this, -1, "版本号", pi.versionCode + "（"
                + (versionName().isEmpty() ? "未知" : versionName()) + "）", null, null));
        addRow(SettingsUi.pref(this, -1, "UID", String.valueOf(ai.uid), null, null));
        addRow(SettingsUi.pref(this, -1, "目标 SDK",
                "target " + ai.targetSdkVersion + " / min " + ai.minSdkVersion, null, null));
        addRow(SettingsUi.pref(this, -1, "安装时间",
                fmtTime(pi.firstInstallTime), null, null));
        addRow(SettingsUi.pref(this, -1, "更新时间",
                fmtTime(pi.lastUpdateTime), null, null));
        addRow(SettingsUi.pref(this, -1, "存储位置",
                (ai.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0 ? "外部存储" : "内部存储",
                null, null));
        addRow(SettingsUi.pref(this, -1, "APK 路径", ai.sourceDir, null, null));
        addRow(SettingsUi.pref(this, -1, "签名",
                signatureHash(), null, null));
        addRow(SettingsUi.pref(this, -1, "详细应用信息", "完整系统字段与应用状态", null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openPage("about", "应用详情");
                    }
                }));
    }

    private void addOpSwitch(String title, final String op, boolean defaultOn) {
        // 大部分特殊访问默认“未允许”；使用情况访问对签名应用默认放行。
        final boolean on = defaultOn
                ? opMode(op) != AppOpsManager.MODE_IGNORED
                : opMode(op) == AppOpsManager.MODE_ALLOWED;
        LinearLayout row = SettingsUi.switchPref(this, -1, title,
                on ? "已允许" : "已拒绝", on, null);
        final TextView summary = rowSummary(row);
        Switch sw = (Switch) row.getChildAt(row.getChildCount() - 1);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (!setOp(op, checked)) {
                    b.setChecked(!checked);
                    toast("写入失败（需要管理应用操作权限）");
                    return;
                }
                if (summary != null) {
                    summary.setText(checked ? "已允许" : "已拒绝");
                }
            }
        });
        addRow(row);
    }

    /** 取 switchPref 行里的摘要 TextView（无图标时 row[0] 即文字列）。 */
    private TextView rowSummary(LinearLayout row) {
        try {
            LinearLayout mid = (LinearLayout) row.getChildAt(0);
            return (TextView) mid.getChildAt(1);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------- 权限页 ----------------

    private boolean requests(String perm) {
        if (pi == null || pi.requestedPermissions == null) {
            return false;
        }
        for (String p : pi.requestedPermissions) {
            if (perm.equals(p)) {
                return true;
            }
        }
        return false;
    }

    private boolean isDangerous(String perm) {
        try {
            PermissionInfo info = pm.getPermissionInfo(perm, 0);
            return (info.protectionLevel & PermissionInfo.PROTECTION_DANGEROUS) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 权限中文名（设备语言为英文时框架标签也是英文，这里给常用危险权限兜底）。 */
    private String permLabel(String p) {
        int dot = p.lastIndexOf('.');
        String n = dot >= 0 ? p.substring(dot + 1) : p;
        String zh = null;
        if ("READ_EXTERNAL_STORAGE".equals(n) || "WRITE_EXTERNAL_STORAGE".equals(n)) {
            zh = "存储";
        } else if ("CAMERA".equals(n)) {
            zh = "相机";
        } else if ("RECORD_AUDIO".equals(n)) {
            zh = "麦克风";
        } else if ("ACCESS_FINE_LOCATION".equals(n)) {
            zh = "位置（精确）";
        } else if ("ACCESS_COARSE_LOCATION".equals(n)) {
            zh = "位置（大致）";
        } else if ("READ_CONTACTS".equals(n) || "WRITE_CONTACTS".equals(n)) {
            zh = "通讯录";
        } else if ("GET_ACCOUNTS".equals(n)) {
            zh = "账户";
        } else if ("READ_CALENDAR".equals(n) || "WRITE_CALENDAR".equals(n)) {
            zh = "日历";
        } else if ("READ_SMS".equals(n) || "RECEIVE_SMS".equals(n)
                || "SEND_SMS".equals(n)) {
            zh = "短信";
        } else if ("READ_CALL_LOG".equals(n) || "WRITE_CALL_LOG".equals(n)) {
            zh = "通话记录";
        } else if ("CALL_PHONE".equals(n)) {
            zh = "电话";
        } else if ("READ_PHONE_STATE".equals(n) || "READ_PHONE_NUMBERS".equals(n)) {
            zh = "电话状态";
        } else if ("ADD_VOICEMAIL".equals(n)) {
            zh = "语音信箱";
        } else if ("PROCESS_OUTGOING_CALLS".equals(n)
                || "ANSWER_PHONE_CALLS".equals(n)) {
            zh = "拨出/接听电话";
        } else if ("BODY_SENSORS".equals(n)) {
            zh = "身体传感器";
        }
        if (zh != null) {
            return zh;
        }
        try {
            return pm.getPermissionInfo(p, 0).loadLabel(pm).toString();
        } catch (Throwable t) {
            return n;
        }
    }

    private int[] dangerousPermCount() {        int granted = 0;
        int total = 0;
        if (pi.requestedPermissions == null) {
            return new int[]{0, 0};
        }
        for (String p : pi.requestedPermissions) {
            if (!isDangerous(p)) {
                continue;
            }
            total++;
            if (pm.checkPermission(p, pkg) == PackageManager.PERMISSION_GRANTED) {
                granted++;
            }
        }
        return new int[]{granted, total};
    }

    private void buildPermissions(LinearLayout c) {
        int shown = 0;
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        if (pi.requestedPermissions != null) {
            for (final String p : pi.requestedPermissions) {
                if (!isDangerous(p)) {
                    continue;
                }
                String title = permLabel(p);
                if (!seen.add(title)) {
                    continue;
                }
                final boolean granted = pm.checkPermission(p, pkg)
                        == PackageManager.PERMISSION_GRANTED;
                addRow(SettingsUi.switchPref(this, -1, title,
                        granted ? "已允许" : "已拒绝", granted,
                        new CompoundButton.OnCheckedChangeListener() {
                            @Override
                            public void onCheckedChanged(CompoundButton b, boolean on) {
                                try {
                                    setRuntimePermission(p, on);
                                } catch (Throwable t) {
                                    toast("权限修改失败：" + t);
                                    b.setChecked(!on);
                                }
                            }
                        }));
                shown++;
            }
        }
        if (shown == 0) {
            addPlain(SettingsUi.body(this, "该应用没有可切换的运行时权限。"));
        }
        addPlain(SettingsUi.body(this,
                "仅列出危险权限（相机、存储、位置等）；特殊权限在“特殊访问”中管理。"));
    }

    // ---------------- 存储页 ----------------

    private void loadStorage() {
        if (storageLoaded) {
            return;
        }
        try {
            StorageStatsManager ssm = (StorageStatsManager)
                    getSystemService(Context.STORAGE_STATS_SERVICE);
            StorageStats st = ssm.queryStatsForUid(StorageManager.UUID_DEFAULT, ai.uid);
            appBytes = st.getAppBytes();
            dataBytes = st.getDataBytes();
            cacheBytes = st.getCacheBytes();
            storageLoaded = true;
        } catch (Throwable t) {
            storageLoaded = false;
        }
    }

    private void buildStorage(LinearLayout c) {
        loadStorage();
        if (storageLoaded) {
            addRow(SettingsUi.pref(this, -1, "应用大小", fmtBytes(appBytes), null, null));
            addRow(SettingsUi.pref(this, -1, "数据", fmtBytes(dataBytes), null, null));
            addRow(SettingsUi.pref(this, -1, "缓存", fmtBytes(cacheBytes), null, null));
        } else {
            addPlain(SettingsUi.body(this,
                    "存储统计不可用（被系统拒绝）。可尝试在“使用情况访问”中授权后重试。"));
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(SettingsUi.dp(this, 16), SettingsUi.dp(this, 12),
                SettingsUi.dp(this, 16), SettingsUi.dp(this, 12));
        View cache = SettingsUi.actionButton(this, "清除缓存", false,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (invokePackageManager("deleteApplicationCacheFiles")) {
                            toast("已请求清除缓存");
                            refreshLater();
                        } else {
                            toast("清除缓存被系统拒绝");
                        }
                    }
                });
        row.addView(cache, marginRight(12));
        View data = SettingsUi.actionButton(this, "清除数据", false,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        new AlertDialog.Builder(AppInfoActivity.this)
                                .setTitle("清除数据")
                                .setMessage("删除「" + label() + "」的全部数据？"
                                        + "此操作不可撤销。")
                                .setPositiveButton("清除", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int w) {
                                        if (invokePackageManager(
                                                "clearApplicationUserData")) {
                                            toast("已请求清除数据");
                                            refreshLater();
                                        } else {
                                            toast("清除数据被系统拒绝");
                                        }
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                });
        row.addView(data, marginRight(12));
        addPlain(row);
        addPlain(SettingsUi.body(this,
                "清除缓存只删除临时文件；清除数据会删除账号、设置与本地文件，"
                        + "并可能使应用退出登录。"));
    }

    private boolean invokePackageManager(String name) {
        try {
            int userId = 0; // 单用户平板；AsUser 接口的 user id
            for (java.lang.reflect.Method m : pm.getClass().getMethods()) {
                String n = m.getName();
                if (!n.equals(name) && !n.equals(name + "AsUser")) {
                    continue;
                }
                Class<?>[] ts = m.getParameterTypes();
                if (ts.length < 1 || ts[0] != String.class) {
                    continue;
                }
                Object[] args = new Object[ts.length];
                for (int i = 0; i < ts.length; i++) {
                    if (ts[i] == String.class) {
                        args[i] = pkg;
                    } else if (ts[i] == int.class) {
                        args[i] = userId;
                    } else {
                        args[i] = null;
                    }
                }
                m.setAccessible(true);
                m.invoke(pm, args);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void refreshLater() {
        content.postDelayed(new Runnable() {
            @Override
            public void run() {
                storageLoaded = false;
                if ("storage".equals(page)) {
                    openPageInternal("storage", "存储和缓存");
                } else {
                    showHome();
                }
            }
        }, 1200);
    }

    // ---------------- 数据用量 ----------------

    private long[] usage(int type) {
        long[] out = new long[]{0, 0};
        try {
            NetworkStatsManager nsm = (NetworkStatsManager)
                    getSystemService(Context.NETWORK_STATS_SERVICE);
            long now = System.currentTimeMillis();
            NetworkStats ns = nsm.queryDetailsForUid(type, null,
                    now - 30L * 24 * 3600 * 1000, now, ai.uid);
            NetworkStats.Bucket bucket = new NetworkStats.Bucket();
            while (ns.hasNextBucket()) {
                ns.getNextBucket(bucket);
                out[0] += bucket.getRxBytes();
                out[1] += bucket.getTxBytes();
            }
            ns.close();
            usageDenied = false;
        } catch (Throwable t) {
            usageDenied = true;
        }
        return out;
    }

    private void buildData(LinearLayout c) {
        long[] wifi = usage(ConnectivityManager.TYPE_WIFI);
        long[] mobile = usage(ConnectivityManager.TYPE_MOBILE);
        if (usageDenied) {
            addPlain(SettingsUi.body(this,
                    "读取应用流量需要“使用情况访问”权限。"));
            addRow(SettingsUi.pref(this, -1, "授予用量访问",
                    "允许本应用读取流量统计", null, new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (setOp("android:get_usage_stats", true)) {
                                usageDenied = false;
                                openPageInternal("data", "移动数据与 WLAN");
                            } else {
                                toast("授权失败");
                            }
                        }
                    }));
            return;
        }
        addRow(SettingsUi.pref(this, -1, "WLAN",
                "接收 " + fmtBytes(wifi[0]) + " · 发送 " + fmtBytes(wifi[1]), null, null));
        addRow(SettingsUi.pref(this, -1, "移动数据",
                "接收 " + fmtBytes(mobile[0]) + " · 发送 " + fmtBytes(mobile[1]), null, null));
        addRow(SettingsUi.pref(this, -1, "刷新", "重新统计最近 30 天", null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openPageInternal("data", "移动数据与 WLAN");
                    }
                }));
        addPlain(SettingsUi.body(this, "统计口径为系统 NetworkStats，最近 30 天，按 UID 汇总。"));
    }

    // ---------------- 电池 ----------------

    private boolean isBackgroundRestricted() {
        return opMode("android:run_any_in_background") == AppOpsManager.MODE_IGNORED;
    }

    private void buildBattery(LinearLayout c) {
        boolean restricted = isBackgroundRestricted();
        LinearLayout row = SettingsUi.switchPref(this, -1, "限制后台活动",
                restricted ? "已限制：仅前台可运行" : "未限制：允许后台运行", restricted, null);
        final TextView summary = rowSummary(row);
        Switch sw = (Switch) row.getChildAt(row.getChildCount() - 1);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                if (!setOp("android:run_any_in_background", !on)) {
                    b.setChecked(!on);
                    toast("写入失败");
                    return;
                }
                if (summary != null) {
                    summary.setText(on ? "已限制：仅前台可运行" : "未限制：允许后台运行");
                }
            }
        });
        addRow(row);

        boolean optimized = true;
        try {
            PowerManager pw = (PowerManager) getSystemService(Context.POWER_SERVICE);
            optimized = !pw.isIgnoringBatteryOptimizations(pkg);
        } catch (Throwable ignored) {
        }
        addRow(SettingsUi.pref(this, -1, "电池优化",
                optimized ? "已优化（系统可限制后台）" : "未优化（允许后台）", null, null));
        addPlain(SettingsUi.body(this,
                "后台限制会立即生效；电池优化状态由系统省电策略维护，本页只读展示。"));
    }

    // ---------------- 内存 ----------------

    private void buildMemory(LinearLayout c) {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> ps = am.getRunningAppProcesses();
            ArrayList<Integer> pids = new ArrayList<Integer>();
            ArrayList<String> names = new ArrayList<String>();
            if (ps != null) {
                for (ActivityManager.RunningAppProcessInfo p : ps) {
                    if (p.uid == ai.uid) {
                        pids.add(p.pid);
                        names.add(p.processName);
                    }
                }
            }
            if (pids.isEmpty()) {
                addPlain(SettingsUi.body(this, "应用当前未运行，没有可读取的内存占用。"));
                return;
            }
            int[] arr = new int[pids.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = pids.get(i);
            }
            Debug.MemoryInfo[] infos = am.getProcessMemoryInfo(arr);
            long total = 0;
            for (int i = 0; i < infos.length && i < names.size(); i++) {
                long pss = infos[i].getTotalPss() * 1024L;
                total += pss;
                addRow(SettingsUi.pref(this, -1, shortProcess(names.get(i)),
                        "PSS " + fmtBytes(pss) + " · PID " + arr[i], null, null));
            }
            addPlain(SettingsUi.body(this, "合计 PSS " + fmtBytes(total)
                    + "。PSS 为按比例分摊的共享内存，是 AOSP 内存页的同一口径。"));
        } catch (Throwable t) {
            addPlain(SettingsUi.body(this, "内存读取失败：" + t));
        }
    }

    private String shortProcess(String name) {
        if (name == null) {
            return pkg;
        }
        if (name.startsWith(pkg + ":")) {
            return name.substring(pkg.length() + 1) + "（子进程）";
        }
        return name;
    }

    // ---------------- 默认打开 ----------------

    private String defaultSummary() {
        try {
            List<IntentFilter> filters = new ArrayList<IntentFilter>();
            List<ComponentName> comps = new ArrayList<ComponentName>();
            int n = pm.getPreferredActivities(filters, comps, pkg);
            return n > 0 ? "已设置默认打开（" + n + " 项）" : "未设置默认打开";
        } catch (Throwable t) {
            return "未设置默认打开";
        }
    }

    private void buildDefaults(LinearLayout c) {
        List<String> domains = new ArrayList<String>();
        try {
            PackageInfo links = pm.getPackageInfo(pkg,
                    PackageManager.GET_ACTIVITIES | GET_INTENT_FILTERS);
            if (links.activities != null) {
                for (ActivityInfo a : links.activities) {
                    IntentFilter[] filters = null;
                    try {
                        java.lang.reflect.Field f = ActivityInfo.class
                                .getField("intentFilters");
                        filters = (IntentFilter[]) f.get(a);
                    } catch (Throwable ignored) {
                    }
                    if (filters == null) {
                        continue;
                    }
                    for (IntentFilter f : filters) {
                        if (!f.hasAction(Intent.ACTION_VIEW)
                                || !f.hasCategory(Intent.CATEGORY_BROWSABLE)) {
                            continue;
                        }
                        int n = f.countDataAuthorities();
                        for (int i = 0; i < n; i++) {
                            IntentFilter.AuthorityEntry e = f.getDataAuthority(i);
                            if (e != null && e.getHost() != null
                                    && !domains.contains(e.getHost())) {
                                domains.add(e.getHost());
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        addRow(SettingsUi.pref(this, -1, "默认打开", defaultSummary(), null, null));
        if (domains.isEmpty()) {
            addPlain(SettingsUi.body(this, "该应用未声明可打开的网页链接。"));
        } else {
            header("支持的链接");
            for (String d : domains) {
                addRow(SettingsUi.pref(this, -1, d, "可在浏览器中打开", null, null));
            }
        }
        addRow(SettingsUi.pref(this, -1, "清除默认设置",
                "撤销本应用已设置的默认打开", null, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            pm.clearPackagePreferredActivities(pkg);
                            toast("已清除默认设置");
                            openPageInternal("defaults", "默认打开");
                        } catch (Throwable t) {
                            toast("清除失败：" + t);
                        }
                    }
                }));
    }

    // ---------------- 应用详情 ----------------

    private void buildAbout(LinearLayout c) {
        header("基本信息");
        addRow(SettingsUi.pref(this, -1, "应用名称", label(), null, null));
        addRow(SettingsUi.pref(this, -1, "包名", pkg, null, null));
        addRow(SettingsUi.pref(this, -1, "版本", versionName() + "（"
                + pi.versionCode + "）", null, null));
        addRow(SettingsUi.pref(this, -1, "UID", String.valueOf(ai.uid), null, null));
        addRow(SettingsUi.pref(this, -1, "进程",
                ai.processName == null ? pkg : ai.processName, null, null));
        addRow(SettingsUi.pref(this, -1, "目标 SDK",
                String.valueOf(ai.targetSdkVersion), null, null));
        addRow(SettingsUi.pref(this, -1, "最低 SDK",
                String.valueOf(ai.minSdkVersion), null, null));

        header("安装与存储");
        addRow(SettingsUi.pref(this, -1, "安装时间", fmtTime(pi.firstInstallTime), null, null));
        addRow(SettingsUi.pref(this, -1, "更新时间", fmtTime(pi.lastUpdateTime), null, null));
        addRow(SettingsUi.pref(this, -1, "存储位置",
                (ai.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0 ? "外部存储" : "内部存储",
                null, null));
        addRow(SettingsUi.pref(this, -1, "APK 路径", ai.sourceDir, null, null));
        addRow(SettingsUi.pref(this, -1, "数据目录", ai.dataDir, null, null));

        header("状态");
        addRow(SettingsUi.pref(this, -1, "应用状态",
                isDisabled() ? "已停用" : "已启用", null, null));
        addRow(SettingsUi.pref(this, -1, "系统应用",
                isSystemFixed() ? "是（不可卸载/停用）" : "否", null, null));
        addRow(SettingsUi.pref(this, -1, "签名 SHA-256", signatureHash(), null, null));
        String[] req = pi.requestedPermissions;
        addRow(SettingsUi.pref(this, -1, "声明权限数",
                String.valueOf(req == null ? 0 : req.length), null, null));
    }

    // ---------------- 通用操作 ----------------

    private boolean isNotifEnabled() {
        return opMode("android:post_notification") != AppOpsManager.MODE_IGNORED;
    }

    private int opMode(String op) {
        try {
            return ops.checkOpNoThrow(op, ai.uid, pkg);
        } catch (Throwable t) {
            return AppOpsManager.MODE_ERRORED;
        }
    }

    private boolean setOp(String op, boolean allowed) {
        try {
            // setMode(String,...) 为 @hide；公开的 int 重载需要数字 op，改用反射。
            java.lang.reflect.Method m = AppOpsManager.class.getMethod("setMode",
                    String.class, int.class, String.class, int.class);
            m.invoke(ops, op, ai.uid, pkg, allowed
                    ? AppOpsManager.MODE_ALLOWED : AppOpsManager.MODE_IGNORED);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 运行时权限授予/撤销（PackageManager 方法为 @hide，用反射调用）。 */
    private void setRuntimePermission(String perm, boolean grant) throws Exception {
        Object user = Class.forName("android.os.UserHandle")
                .getMethod("getUserHandleForUid", int.class)
                .invoke(null, Process.myUid());
        java.lang.reflect.Method m = PackageManager.class.getMethod(
                grant ? "grantRuntimePermission" : "revokeRuntimePermission",
                String.class, String.class, Class.forName("android.os.UserHandle"));
        m.invoke(pm, pkg, perm, user);
    }

    private void uninstall() {
        new AlertDialog.Builder(this)
                .setTitle("卸载应用")
                .setMessage("确定卸载「" + label() + "」？应用数据会一并删除。")
                .setPositiveButton("卸载", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        silentUninstall();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 本机 packageinstaller 的 UninstallerActivity 对所有包都报
     * "Package not found for originating uid" 并立即 finish，导致点卸载无反应。
     * 平台签名已授予 DELETE_PACKAGES：
     *   1) 先试 PackageManager.deletePackage（部分 ROM 会裁掉该方法）；
     *   2) 再试 /system/bin/pm uninstall（pm 以本应用 uid 发 Binder，权限同样命中）；
     * 失败时给出系统返回的具体原因，而不是"点了没反应"。
     */
    private void silentUninstall() {
        final String target = pkg;
        toast("正在卸载…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String reason = null;
                // 本机所有常规卸载通道（PackageInstaller / pm / PackageManager）
                // 都被裁掉或直接空实现；只保留 IPackageManager 的 5 参数版本，
                // 2.5 秒内没有消失就给出“停用”兜底，避免用户等十几秒。
                try {
                    reason = callDeletePackage(target);
                    android.util.Log.i("C10HUninstall", "deletePackage invoked");
                } catch (Throwable t) {
                    android.util.Log.w("C10HUninstall", "deletePackage unavailable: " + t);
                    reason = String.valueOf(t);
                }
                boolean gone = waitGone(target, 2500);
                if (gone) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            toast("已卸载");
                            finish();
                        }
                    });
                    return;
                }
                // 本机 adbd 可直接连：应用自己连 127.0.0.1:5555 执行 pm uninstall，
                // 不需要 PC/Armbian（adbd 5555 由 persist.adb.tcp.port 常开）。
                if (com.c10h.launcher.LocalAdb.uninstall(target)) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            toast("已卸载");
                            finish();
                        }
                    });
                    return;
                }
                final String why = reason;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showDisableFallback(why == null ? "本机 adbd 5555 不可用" : why);
                    }
                });
            }
        }, "c10h-uninstall").start();
    }

    /**
     * 直接调用 IPackageManager.deletePackageAsUser：
     * ApplicationPackageManager.deletePackage 在本机是空实现；pm uninstall 又会
     * 在 PackageInstallerService 里因 callerPackageName=null 抛 NPE。
     */
    private String callDeletePackage(String target) throws Throwable {
        Object ipm = null;
        try {
            java.lang.reflect.Field f = pm.getClass().getDeclaredField("mPM");
            f.setAccessible(true);
            ipm = f.get(pm);
        } catch (Throwable t) {
            android.util.Log.w("C10HUninstall", "mPM not found: " + t);
        }
        if (ipm != null) {
            // 本机实测签名：deletePackageAsUser(String, int versionCode,
            // IPackageDeleteObserver, int userId, int flags)
            try {
                Class<?> obs = Class.forName("android.content.pm.IPackageDeleteObserver");
                java.lang.reflect.Method m = ipm.getClass().getMethod("deletePackageAsUser",
                        String.class, int.class, obs, int.class, int.class);
                int vc = -1;
                try {
                    vc = pm.getPackageInfo(target, 0).versionCode;
                } catch (Throwable ignored) {
                }
                m.invoke(ipm, target, vc, null, 0, 0);
                android.util.Log.i("C10HUninstall", "deletePackageAsUser(5) invoked vc=" + vc);
                return null;
            } catch (Throwable t) {
                android.util.Log.w("C10HUninstall", "deletePackageAsUser(5): " + t);
            }
            // Android 9 现代接口
            try {
                Class<?> vp = Class.forName("android.content.pm.VersionedPackage");
                Object versioned = vp.getConstructor(String.class, int.class)
                        .newInstance(target, -1);
                Class<?> obs2 = Class.forName("android.content.pm.IPackageDeleteObserver2");
                java.lang.reflect.Method m = ipm.getClass().getMethod("deletePackageVersioned",
                        vp, obs2, int.class, int.class);
                m.invoke(ipm, versioned, null, 0, 0);
                android.util.Log.i("C10HUninstall", "deletePackageVersioned invoked");
                return null;
            } catch (Throwable t) {
                android.util.Log.w("C10HUninstall", "deletePackageVersioned: " + t);
            }
        }
        Class<?> obs = Class.forName("android.content.pm.IPackageDeleteObserver");
        java.lang.reflect.Method m = PackageManager.class.getMethod(
                "deletePackage", String.class, obs, int.class);
        m.invoke(pm, target, null, 0);
        return "只调用了 PackageManager.deletePackage";
    }

    /**
     * 本机 PackageInstaller 的卸载页对所有包都报 "Package not found"，
     * pm / deletePackage / deletePackageAsUser 又都被裁掉，应用内无法真正卸载。
     * 失败时给出可执行的兜底：停用（图标消失、后台能力移除，数据保留）。
     */
    private void showDisableFallback(String why) {
        String detail = why == null ? "系统拒绝" : why;
        if (detail.length() > 160) {
            detail = detail.substring(0, 160) + "…";
        }
        new AlertDialog.Builder(this)
                .setTitle("卸载失败")
                .setMessage("本机系统卸载器已损坏（" + detail + "）。\n\n"
                        + "可以改为停用「" + label() + "」：桌面图标与后台能力会移除，"
                        + "数据保留。")
                .setPositiveButton("停用", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        applyEnabled(false);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private boolean waitGone(String target, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            try {
                pm.getPackageInfo(target, 0);
            } catch (PackageManager.NameNotFoundException e) {
                return true;
            } catch (Throwable ignored) {
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
            }
        }
        return false;
    }

    private void forceStop() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            java.lang.reflect.Method m = ActivityManager.class
                    .getMethod("forceStopPackage", String.class);
            m.invoke(am, pkg);
            toast("已强制停止");
        } catch (Throwable t) {
            toast("强制停止失败：" + t);
        }
    }

    private void toggleEnabled() {
        final boolean disable = !isDisabled();
        if (disable) {
            new AlertDialog.Builder(this)
                    .setTitle("停用应用")
                    .setMessage("停用「" + label() + "」会移除其桌面图标与后台能力，"
                            + "但不会删除数据。")
                    .setPositiveButton("停用", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            applyEnabled(false);
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } else {
            applyEnabled(true);
        }
    }

    private void applyEnabled(boolean enable) {
        try {
            pm.setApplicationEnabledSetting(pkg,
                    enable ? PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0);
            toast(enable ? "已启用" : "已停用");
            showHome();
        } catch (Throwable t) {
            toast("修改应用状态失败：" + t);
        }
    }

    // ---------------- 格式化 ----------------

    private String fmtBytes(long b) {
        if (b < 1024) {
            return b + " B";
        } else if (b < 1024L * 1024) {
            return String.format(Locale.US, "%.1f KB", b / 1024f);
        } else if (b < 1024L * 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB", b / (1024f * 1024f));
        }
        return String.format(Locale.US, "%.2f GB", b / (1024f * 1024f * 1024f));
    }

    private String fmtTime(long t) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                    .format(new Date(t));
        } catch (Throwable e) {
            return "未知";
        }
    }

    private String signatureHash() {
        try {
            if (pi.signatures != null && pi.signatures.length > 0
                    && pi.signatures[0] != null) {
                byte[] d = MessageDigest.getInstance("SHA-256")
                        .digest(pi.signatures[0].toByteArray());
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < d.length && i < 16; i++) {
                    sb.append(String.format(Locale.US, "%02x", d[i]));
                }
                return sb.toString();
            }
        } catch (Throwable ignored) {
        }
        return "未知";
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }
}
