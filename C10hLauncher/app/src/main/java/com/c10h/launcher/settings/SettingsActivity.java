package com.c10h.launcher.settings;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.net.VpnService;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Debug;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.c10h.launcher.WorkspaceStore;
import com.c10h.launcher.block.BootReceiver;
import com.c10h.launcher.block.RuleStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AOSP Settings（android-9.0.0_r61）对齐的自研设置。
 *
 * 层级与布局：
 * - 顶层：grey_100 容器内的 48dp 搜索卡片 + 72dp 仪表盘磁贴；无标题栏、无返回箭头，
 *   返回键直接退出（与 AOSP dashboard 一致）。
 * - 二级：56dp 顶栏（48dp 返回箭头 + 20sp 标题）+ preference 列表；
 *   主开关页（WLAN/蓝牙/DNS 屏蔽）带 48dp SwitchBar，关闭时列表置灰。
 * - 搜索：点击搜索卡片进入真实搜索态，实时过滤全部设置项，结果点击直达对应页面。
 *
 * 交互纪律：整行可点、开关本身 clickable=false（AOSP master switch）、
 * 返回逐级上退、危险操作二次确认、权限/硬件缺失在行内降级不崩溃。
 */
public class SettingsActivity extends Activity {

    private static final String PAGE_HOME = "home";

    private LinearLayout barHost;
    private LinearLayout pageBarHost;
    private ScrollView scroll;
    private LinearLayout content;

    private String page = PAGE_HOME;
    private String pageTitle = "设置";
    private boolean searchMode;
    private String searchQuery = "";
    private EditText searchInput;
    private final ArrayList<String[]> stack = new ArrayList<String[]>();
    private boolean lastWasRow;
    private float pageAlpha = 1f;

    private boolean vpnOn;
    private String vpnState = "读取中…";
    private String guardState = "读取中…";
    private String guardLog = "";
    private TextView blockStateView;
    private TextView ruleCountView;
    private LinearLayout rulesListBox;
    private TextView guardStateView;
    private TextView guardLogView;
    private Switch guardSwitch;
    private SettingsUi.SwitchBar blockBar;
    private boolean guardOn = true;
    private BroadcastReceiver stateRx;

    // 应用启动管理页
    private LinearLayout startListBox;
    private TextView startCountView;
    private final List<com.c10h.launcher.AppStartupManager.AppEntry> startApps =
            new ArrayList<com.c10h.launcher.AppStartupManager.AppEntry>();

    // 内存占用页
    private LinearLayout memListBox;
    private TextView memSummaryView;
    private TextView memCountView;
    private ProgressBar memBar;

    private static final int REQ_IMPORT_FILE = 7101;

    // ---------------- 设置索引（搜索用） ----------------

    private static final Entry[] INDEX = {
            new Entry("net", "网络和互联网", "WLAN", "开关、扫描、连接", SettingsIcons.WIFI),
            new Entry("net", "网络和互联网", "扫描", "刷新可用网络", SettingsIcons.RESET),
            new Entry("bt", "已连接的设备", "蓝牙", "开关、已配对、可发现", SettingsIcons.BT),
            new Entry("apps", "应用", "应用", "已安装列表：详情/卸载/强行停止", SettingsIcons.APPS),
            new Entry("appstart", "应用启动管理", "应用启动管理",
                    "自启动、关联启动、后台运行", SettingsIcons.APPS),
            new Entry("mem", "内存占用", "内存占用",
                    "查看进程 PSS、结束进程", SettingsIcons.STORAGE),
            new Entry("notif", "通知访问", "通知访问", "角标授权", SettingsIcons.NOTIFY),
            new Entry("battery", "电池", "电量", "容量、充电状态、温度", SettingsIcons.BATTERY),
            new Entry("display", "显示", "亮度", "屏幕亮度", SettingsIcons.DISPLAY),
            new Entry("display", "显示", "屏幕超时", "自动休眠时间", SettingsIcons.CLOCK),
            new Entry("display", "显示", "壁纸", "更换桌面与锁屏壁纸", SettingsIcons.WALLPAPER),
            new Entry("sound", "声音", "音量", "媒体、铃声、通知、闹钟", SettingsIcons.SOUND),
            new Entry("sound", "声音", "均衡器", "10 段 EQ 与预设", SettingsIcons.EQ),
            new Entry("storage", "存储", "内部存储", "容量与可用空间", SettingsIcons.STORAGE),
            new Entry("storage", "存储", "文件管理器", "浏览内部与 USB 存储", SettingsIcons.FILE),
            new Entry("system", "系统", "开发者选项", "动画缩放、指针位置", SettingsIcons.DEV),
            new Entry("block", "DNS 屏蔽", "DNS 屏蔽", "域名/IP 屏蔽与命中日志", SettingsIcons.BLOCK),
            new Entry("guard", "ADB 守护", "ADB 守护", "adbd 自愈与拉回记录", SettingsIcons.GUARD),
            new Entry("reset", "重置选项", "恢复默认设置", "重置桌面设置项", SettingsIcons.RESET),
            new Entry("about", "关于手机", "型号", "设备型号与版本", SettingsIcons.ABOUT),
    };

    private static final class Entry {
        final String page;
        final String pageTitle;
        final String title;
        final String summary;
        final int icon;

        Entry(String page, String pageTitle, String title, String summary, int icon) {
            this.page = page;
            this.pageTitle = pageTitle;
            this.title = title;
            this.summary = summary;
            this.icon = icon;
        }
    }

    // ---------------- 生命周期 ----------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            buildShell();
            registerStateReceiver();
            String p = getIntent() != null ? getIntent().getStringExtra("page") : null;
            if (p != null && !PAGE_HOME.equals(p)) {
                openPage(p, pageTitleOf(p));
            } else {
                showHome();
            }
        } catch (Throwable t) {
            LinearLayout err = new LinearLayout(this);
            err.setOrientation(LinearLayout.VERTICAL);
            err.setBackgroundColor(SettingsUi.BG);
            err.setGravity(Gravity.CENTER);
            TextView tv = new TextView(this);
            tv.setText("设置打开失败\n" + t);
            tv.setTextColor(0xFFD32F2F);
            err.addView(tv);
            setContentView(err);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        try {
            setIntent(intent);
            String p = intent != null ? intent.getStringExtra("page") : null;
            if (p != null && !PAGE_HOME.equals(p)) {
                stack.clear();
                openPage(p, pageTitleOf(p));
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        try {
            if (stateRx != null) {
                unregisterReceiver(stateRx);
            }
        } catch (Throwable ignored) {
        }
        super.onDestroy();
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
        scroll.setClipToPadding(false);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    // ---------------- 层与导航 ----------------

    private void setBar(View v) {
        barHost.removeAllViews();
        if (v != null) {
            barHost.addView(v, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private void clearPageBar() {
        pageBarHost.removeAllViews();
    }

    private void showHome() {
        page = PAGE_HOME;
        pageTitle = "设置";
        searchMode = false;
        stack.clear();
        hideIme();
        blockStateView = null;
        ruleCountView = null;
        rulesListBox = null;
        guardStateView = null;
        guardLogView = null;
        guardSwitch = null;
        blockBar = null;
        startListBox = null;
        startCountView = null;
        memListBox = null;
        memSummaryView = null;
        memCountView = null;
        memBar = null;
        clearPageBar();
        setBar(SettingsUi.searchCard(this, new Runnable() {
            @Override
            public void run() {
                showSearch();
            }
        }));
        content.removeAllViews();
        lastWasRow = false;
        pageAlpha = 1f;

        addTile("网络和互联网", "WLAN、网络状态", SettingsIcons.WIFI, "net");
        addTile("已连接的设备", "蓝牙、可发现性", SettingsIcons.BT, "bt");
        addTile("应用和通知", "已安装应用、通知访问", SettingsIcons.APPS, "appsNotify");
        addTile("电池", "电量、状态、温度", SettingsIcons.BATTERY, "battery");
        addTile("显示", "亮度、休眠、壁纸", SettingsIcons.DISPLAY, "display");
        addTile("声音", "媒体、铃声、通知、闹钟", SettingsIcons.SOUND, "sound");
        addTile("存储", "内部存储、文件管理器", SettingsIcons.STORAGE, "storage");
        addTile("系统", "开发者选项、DNS 屏蔽、ADB 守护", SettingsIcons.SYSTEM, "system");
        addTile("关于手机", "型号、版本、内核", SettingsIcons.ABOUT, "about");

        addPlain(SettingsUi.divider(this));
        addRow(SettingsUi.pref(this, SettingsIcons.SYSTEM, "打开原厂设置",
                "系统设置不可用时的兜底入口", null, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openFactorySettings();
                    }
                }));
        addPlain(spacer(8));
        animateIn();
    }

    private void addTile(final String title, String summary, int icon, final String id) {
        content.addView(SettingsUi.tile(this, icon, title, summary,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openPage(id, title);
                    }
                }), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void showSearch() {
        showSearch("");
    }

    private void showSearch(final String initial) {
        searchMode = true;
        page = PAGE_HOME;
        pageTitle = "设置";
        clearPageBar();
        final SettingsUi.SearchField sf = SettingsUi.searchField(this, new Runnable() {
            @Override
            public void run() {
                showHome();
            }
        }, new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                renderSearchResults(s == null ? "" : s.toString());
            }
        });
        setBar(sf.root);
        searchInput = sf.input;
        pageAlpha = 1f;
        renderSearchResults(initial);
        if (initial != null && initial.length() > 0) {
            sf.input.setText(initial);
            sf.input.setSelection(initial.length());
        }
        sf.input.post(new Runnable() {
            @Override
            public void run() {
                try {
                    searchInput.requestFocus();
                    InputMethodManager imm = (InputMethodManager)
                            getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private void renderSearchResults(String q) {
        content.removeAllViews();
        lastWasRow = false;
        try {
            content.setAlpha(1f);
            content.setTranslationX(0f);
        } catch (Throwable ignored) {
        }
        String key = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        searchQuery = key;
        if (key.isEmpty()) {
            addPlain(SettingsUi.body(this, "输入关键词搜索全部设置项。"));
            return;
        }
        int shown = 0;
        for (final Entry e : INDEX) {
            String hay = (e.title + " " + e.summary + " " + e.pageTitle).toLowerCase(Locale.ROOT);
            if (!hay.contains(key)) {
                continue;
            }
            addRow(SettingsUi.pref(this, e.icon, e.title,
                    e.pageTitle + " · " + e.summary, null, new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            // AOSP：从搜索结果进入后，返回键回到搜索结果。
                            stack.add(new String[]{"__search__", searchQuery});
                            openPageInternal(e.page, e.pageTitle);
                        }
                    }));
            if (++shown >= 30) {
                break;
            }
        }
        if (shown == 0) {
            addPlain(SettingsUi.body(this, "无匹配结果。"));
        }
    }

    private void openPage(String id, String title) {
        if (!PAGE_HOME.equals(page)) {
            stack.add(new String[]{page, pageTitle});
        }
        openPageInternal(id, title);
    }

    private void openPageInternal(String id, String title) {
        if (PAGE_HOME.equals(id)) {
            showHome();
            return;
        }
        page = id;
        pageTitle = title;
        searchMode = false;
        hideIme();
        blockStateView = null;
        ruleCountView = null;
        rulesListBox = null;
        guardStateView = null;
        guardLogView = null;
        guardSwitch = null;
        blockBar = null;
        startListBox = null;
        startCountView = null;
        memListBox = null;
        memSummaryView = null;
        memCountView = null;
        memBar = null;
        clearPageBar();
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
            if ("net".equals(id)) {
                buildNet(content);
            } else if ("bt".equals(id)) {
                buildBluetooth(content);
            } else if ("appsNotify".equals(id)) {
                buildAppsNotify(content);
            } else if ("apps".equals(id)) {
                buildApps(content);
            } else if ("appstart".equals(id)) {
                buildAppStart(content);
            } else if ("mem".equals(id)) {
                buildMemory(content);
            } else if ("battery".equals(id)) {
                buildBattery(content);
            } else if ("display".equals(id)) {
                buildDisplay(content);
            } else if ("sound".equals(id)) {
                buildSound(content);
            } else if ("storage".equals(id)) {
                buildStorage(content);
            } else if ("system".equals(id)) {
                buildSystem(content);
            } else if ("dev".equals(id)) {
                buildDeveloper(content);
            } else if ("block".equals(id)) {
                buildBlock(content);
            } else if ("guard".equals(id)) {
                buildGuard(content);
            } else if ("notif".equals(id)) {
                buildNotify(content);
            } else if ("reset".equals(id)) {
                buildReset(content);
            } else if ("about".equals(id)) {
                buildAbout(content);
            }
        } catch (Throwable t) {
            content.removeAllViews();
            lastWasRow = false;
            addPlain(SettingsUi.body(this, "此设备不可用\n（" + t + "）"));
        }
        if (content.getChildCount() == 0) {
            addPlain(SettingsUi.body(this, "此设备不可用。"));
        }
        animateIn();
    }

    private void goUp() {
        if (stack.isEmpty()) {
            showHome();
            return;
        }
        String[] prev = stack.remove(stack.size() - 1);
        if ("__search__".equals(prev[0])) {
            showSearch(prev[1]);
            return;
        }
        openPageInternal(prev[0], prev[1]);
    }

    @Override
    public void onBackPressed() {
        if (searchMode) {
            showHome();
            return;
        }
        if (!PAGE_HOME.equals(page)) {
            goUp();
            return;
        }
        super.onBackPressed();
    }

    private String pageTitleOf(String id) {
        for (Entry e : INDEX) {
            if (e.page.equals(id) && e.pageTitle.equals(e.title)) {
                return e.pageTitle;
            }
        }
        if ("net".equals(id)) {
            return "网络和互联网";
        } else if ("bt".equals(id)) {
            return "已连接的设备";
        } else if ("appsNotify".equals(id)) {
            return "应用和通知";
        } else if ("apps".equals(id)) {
            return "应用";
        } else if ("appstart".equals(id)) {
            return "应用启动管理";
        } else if ("mem".equals(id)) {
            return "内存占用";
        } else if ("battery".equals(id)) {
            return "电池";
        } else if ("display".equals(id)) {
            return "显示";
        } else if ("sound".equals(id)) {
            return "声音";
        } else if ("storage".equals(id)) {
            return "存储";
        } else if ("system".equals(id)) {
            return "系统";
        } else if ("dev".equals(id)) {
            return "开发者选项";
        } else if ("block".equals(id)) {
            return "DNS 屏蔽";
        } else if ("guard".equals(id)) {
            return "ADB 守护";
        } else if ("notif".equals(id)) {
            return "通知访问";
        } else if ("reset".equals(id)) {
            return "重置选项";
        } else if ("about".equals(id)) {
            return "关于手机";
        }
        return "设置";
    }

    // ---------------- 行/栏辅助 ----------------

    private void addRow(View v) {
        if (lastWasRow) {
            content.addView(SettingsUi.divider(this));
        }
        content.addView(v, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        lastWasRow = true;
    }

    private void addRow(LinearLayout c, View v) {
        if (lastWasRow) {
            c.addView(SettingsUi.divider(this));
        }
        c.addView(v, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        lastWasRow = true;
    }

    private void addPlain(View v) {
        content.addView(v, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        lastWasRow = false;
    }

    private void addPlain(LinearLayout c, View v) {
        c.addView(v, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        lastWasRow = false;
    }

    private void header(String text) {
        addPlain(SettingsUi.header(this, text));
    }

    private View spacer(int dpValue) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, SettingsUi.dp(this, dpValue)));
        return v;
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

    private void hideIme() {
        try {
            InputMethodManager imm = (InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);
            View focus = getCurrentFocus();
            if (imm != null && focus != null) {
                imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
            }
        } catch (Throwable ignored) {
        }
    }

    private void setEnabledRecursive(View v, boolean enabled) {
        v.setEnabled(enabled);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                setEnabledRecursive(g.getChildAt(i), enabled);
            }
        }
    }

    /** 主开关关闭时列表置灰不可用（AOSP 行为）。 */
    private void applyDisabled(View c, boolean disabled) {
        try {
            c.setAlpha(disabled ? 0.4f : 1f);
            setEnabledRecursive(c, !disabled);
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 网络和互联网 ----------------

    private void buildNet(LinearLayout c) {
        final WifiManager w = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (w == null) {
            throw new IllegalStateException("WifiManager 不可用");
        }
        boolean on = false;
        try {
            on = w.isWifiEnabled();
        } catch (Throwable ignored) {
        }
        SettingsUi.SwitchBar bar = SettingsUi.switchBar(this, "WLAN", on,
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean checked) {
                        try {
                            boolean ok = w.setWifiEnabled(checked);
                            if (!ok) {
                                toast("系统拒绝 WLAN 开关");
                                b.setChecked(!checked);
                                return;
                            }
                        } catch (Throwable t) {
                            toast("WLAN 开关失败：" + t);
                            b.setChecked(!checked);
                            return;
                        }
                        // 系统开关是异步的：先按用户意图置灰，再延迟重读真实状态。
                        applyDisabled(c, !checked);
                        c.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if ("net".equals(page)) {
                                    openPageInternal("net", "网络和互联网");
                                }
                            }
                        }, 900);
                    }
                });
        pageBarHost.addView(bar.root, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        header("可用网络");
        addRow(SettingsUi.pref(this, -1, "扫描",
                "点按刷新可用网络", null, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            w.startScan();
                        } catch (Throwable t) {
                            toast("扫描失败");
                        }
                        openPageInternal("net", "网络和互联网");
                    }
                }));

        boolean listed = false;
        try {
            List<ScanResult> rs = w.getScanResults();
            if (rs != null) {
                int shown = 0;
                for (final ScanResult r : rs) {
                    if (shown++ >= 12) {
                        break;
                    }
                    String ssid = (r.SSID == null || r.SSID.isEmpty()) ? "（隐藏网络）" : r.SSID;
                    addRow(SettingsUi.pref(SettingsActivity.this, -1, ssid,
                            "信号 " + r.level + " dBm", null,
                            new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    connectWifi(w, r.SSID);
                                }
                            }));
                    listed = true;
                }
            }
        } catch (Throwable t) {
            addPlain(c, SettingsUi.body(this, "扫描结果读取失败（需要位置权限）。"));
            listed = true;
        }
        if (!listed) {
            addPlain(c, SettingsUi.body(this, "暂无可用网络。先开启 WLAN，再点按“扫描”。"));
        }
        if (!on) {
            pageAlpha = 0.4f;
            setEnabledRecursive(c, false);
        }
    }

    private void connectWifi(final WifiManager w, final String ssid) {
        final EditText pw = new EditText(this);
        pw.setHint("密码（开放网络留空）");
        pw.setSingleLine(true);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(SettingsUi.dp(this, 20), SettingsUi.dp(this, 8),
                SettingsUi.dp(this, 20), 0);
        wrap.addView(pw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("连接到 " + ssid)
                .setView(wrap)
                .setPositiveButton("连接", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        try {
                            WifiConfiguration cfg = new WifiConfiguration();
                            cfg.SSID = "\"" + ssid + "\"";
                            String p = pw.getText().toString();
                            if (p.isEmpty()) {
                                cfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                            } else {
                                cfg.preSharedKey = "\"" + p + "\"";
                            }
                            int id = w.addNetwork(cfg);
                            if (id < 0) {
                                toast("添加网络失败");
                                return;
                            }
                            w.disconnect();
                            toast(w.enableNetwork(id, true) ? "连接中…" : "启用网络被拒");
                        } catch (Throwable t) {
                            toast("连接失败：" + t);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- 已连接的设备 ----------------

    private void buildBluetooth(LinearLayout c) {
        final BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null) {
            throw new IllegalStateException("无蓝牙硬件");
        }
        boolean on = false;
        try {
            on = a.isEnabled();
        } catch (Throwable ignored) {
        }
        SettingsUi.SwitchBar bar = SettingsUi.switchBar(this, "蓝牙", on,
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean checked) {
                        try {
                            boolean ok = checked ? a.enable() : a.disable();
                            if (!ok) {
                                toast("系统拒绝蓝牙开关");
                                b.setChecked(!checked);
                                return;
                            }
                        } catch (Throwable t) {
                            toast("蓝牙开关失败：" + t);
                            b.setChecked(!checked);
                            return;
                        }
                        applyDisabled(c, !checked);
                        c.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if ("bt".equals(page)) {
                                    openPageInternal("bt", "已连接的设备");
                                }
                            }
                        }, 900);
                    }
                });
        pageBarHost.addView(bar.root, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        header("本机");
        addRow(SettingsUi.pref(this, -1, "可发现性",
                "请求 60 秒内可被其他设备发现", null, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 60));
                        } catch (Throwable t) {
                            toast("可发现请求失败");
                        }
                    }
                }));

        header("已配对的设备");
        boolean any = false;
        try {
            java.util.Set<BluetoothDevice> bonded = a.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    String n = d.getName();
                    addRow(SettingsUi.pref(SettingsActivity.this, -1,
                            (n == null || n.isEmpty()) ? "未命名设备" : n,
                            d.getAddress(), null, null));
                    any = true;
                }
            }
        } catch (Throwable ignored) {
        }
        if (!any) {
            addPlain(c, SettingsUi.body(this, "暂无已配对设备。"));
        }
        if (!on) {
            pageAlpha = 0.4f;
            setEnabledRecursive(c, false);
        }
    }

    // ---------------- 应用和通知 ----------------

    private void buildAppsNotify(LinearLayout c) {
        header("常规");
        addRow(SettingsUi.pref(this, -1, "应用",
                "已安装的应用、应用信息、卸载、强行停止", null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openPage("apps", "应用");
                    }
                }));
        addRow(SettingsUi.pref(this, -1, "应用启动管理",
                "自启动 / 关联启动 / 后台运行（含白名单系统应用）", null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openPage("appstart", "应用启动管理");
                    }
                }));
        addRow(SettingsUi.pref(this, -1, "内存占用",
                "查看各进程 PSS，并可结束进程", null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openPage("mem", "内存占用");
                    }
                }));
        addRow(SettingsUi.pref(this, -1, "通知访问",
                "允许本应用读取通知以显示桌面角标", null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openPage("notif", "通知访问");
                    }
                }));
    }

    private void buildApps(LinearLayout c) {
        final boolean showSystem = WorkspaceStore.showSystemApps(this);
        PackageManager pm = getPackageManager();
        List<PackageInfo> list = pm.getInstalledPackages(0);
        final ArrayList<Object[]> rows = new ArrayList<Object[]>();
        for (PackageInfo pi : list) {
            if (rows.size() >= 200) {
                break;
            }
            boolean sys = (pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            boolean updated = (pi.applicationInfo.flags
                    & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            if (!showSystem && sys && !updated) {
                continue;
            }
            final String pkg = pi.packageName;
            String label;
            try {
                label = pm.getApplicationLabel(pi.applicationInfo).toString();
            } catch (Throwable t) {
                label = pkg;
            }
            Drawable icon = null;
            try {
                icon = pm.getApplicationIcon(pkg);
            } catch (Throwable ignored) {
            }
            rows.add(new Object[]{label, pkg, sys, icon});
        }
        try {
            final java.text.Collator coll = java.text.Collator.getInstance(Locale.CHINA);
            java.util.Collections.sort(rows, new java.util.Comparator<Object[]>() {
                @Override
                public int compare(Object[] a, Object[] b) {
                    return coll.compare(String.valueOf(a[0]), String.valueOf(b[0]));
                }
            });
        } catch (Throwable ignored) {
        }

        // 列表工具条：左侧计数，右侧一个按钮切换“全部应用 / 剔除系统应用”。
        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.setMinimumHeight(SettingsUi.dp(this, 48));
        tools.setPadding(SettingsUi.dp(this, 16), 0, SettingsUi.dp(this, 12), 0);
        tools.setBackgroundColor(SettingsUi.BG);
        TextView count = new TextView(this);
        count.setText((showSystem ? "全部应用" : "用户应用") + " · " + rows.size() + " 个");
        count.setTextColor(SettingsUi.SUB);
        count.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tools.addView(count, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView toggle = new TextView(this);
        toggle.setText(showSystem ? "剔除系统应用" : "显示全部应用");
        toggle.setTextColor(SettingsUi.ACCENT);
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        toggle.setTypeface(SettingsUi.medium(this));
        toggle.setPadding(SettingsUi.dp(this, 12), SettingsUi.dp(this, 8),
                SettingsUi.dp(this, 12), SettingsUi.dp(this, 8));
        SettingsUi.makeSelectable(this, toggle);
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                WorkspaceStore.setShowSystemApps(SettingsActivity.this, !showSystem);
                openPageInternal("apps", "应用");
            }
        });
        tools.addView(toggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pageBarHost.addView(tools, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pageBarHost.addView(SettingsUi.divider(this));

        for (Object[] r : rows) {
            final String pkg = (String) r[1];
            boolean sys = (Boolean) r[2];
            addRow(SettingsUi.prefBig(this, (Drawable) r[3], String.valueOf(r[0]),
                    pkg + (sys ? " · 系统应用" : ""), null,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            openAppInfo(pkg);
                        }
                    }));
        }
        if (rows.isEmpty()) {
            addPlain(c, SettingsUi.body(this, "没有符合当前筛选条件的应用。"));
        }
    }

    /** 进入自研应用信息页（不依赖系统设置）。 */
    private void openAppInfo(String pkg) {
        try {
            startActivity(new Intent(this, AppInfoActivity.class)
                    .putExtra(AppInfoActivity.EXTRA_PKG, pkg));
        } catch (Throwable t) {
            toast("应用信息打开失败：" + t);
        }
    }

    // ---------------- 应用启动管理 ----------------

    private void buildAppStart(LinearLayout c) {
        header("批量操作");
        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.setMinimumHeight(SettingsUi.dp(this, 56));
        tools.setPadding(SettingsUi.dp(this, 16), 0, SettingsUi.dp(this, 16), 0);
        tools.setBackgroundColor(SettingsUi.BG);

        startCountView = new TextView(this);
        startCountView.setText("应用 · 读取中…");
        startCountView.setTextColor(SettingsUi.SUB);
        startCountView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tools.addView(startCountView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        tools.addView(startActionChip("全部开启", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyAllStart(true);
            }
        }));
        tools.addView(startActionChip("全部关闭", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyAllStart(false);
            }
        }));
        addRow(tools);

        addPlain(c, SettingsUi.body(this,
                "显示非系统应用，外加白名单系统应用（喜马拉雅、网易云音乐）。"
                        + "自启动 = 启用/停用开机广播接收器；"
                        + "关联启动 = 限制后台拉起前台服务（START_FOREGROUND）；"
                        + "后台运行 = 限制后台执行（RUN_IN_BACKGROUND / RUN_ANY_IN_BACKGROUND）。"
                        + "没有开机接收器的应用，自启动显示“—”。"));

        header("应用");
        startListBox = new LinearLayout(this);
        startListBox.setOrientation(LinearLayout.VERTICAL);
        addPlain(c, startListBox);
        loadStartApps();
    }

    private TextView startActionChip(String text, boolean accent, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setTextColor(accent ? 0xFFFFFFFF : 0xFF424242);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(accent ? 0xFF1976D2 : 0xFFE0E0E0);
        bg.setCornerRadius(SettingsUi.dp(this, 10));
        tv.setBackground(bg);
        tv.setPadding(SettingsUi.dp(this, 18), SettingsUi.dp(this, 8),
                SettingsUi.dp(this, 18), SettingsUi.dp(this, 8));
        tv.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, SettingsUi.dp(this, 44));
        lp.leftMargin = SettingsUi.dp(this, 8);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void loadStartApps() {
        final LinearLayout box = startListBox;
        if (box == null) {
            return;
        }
        box.removeAllViews();
        box.addView(SettingsUi.body(this, "正在读取应用…"));
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<com.c10h.launcher.AppStartupManager.AppEntry> list =
                        com.c10h.launcher.AppStartupManager.load(getApplicationContext());
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || box != startListBox) {
                            return;
                        }
                        startApps.clear();
                        startApps.addAll(list);
                        renderStartApps();
                    }
                });
            }
        }, "c10h-startup-load").start();
    }

    private void renderStartApps() {
        if (startListBox == null) {
            return;
        }
        startListBox.removeAllViews();
        if (startCountView != null) {
            startCountView.setText("应用 · " + startApps.size() + " 个");
        }
        if (startApps.isEmpty()) {
            startListBox.addView(SettingsUi.body(this, "没有可管理的第三方应用。"));
            return;
        }
        for (com.c10h.launcher.AppStartupManager.AppEntry e : startApps) {
            startListBox.addView(buildStartRow(e));
            startListBox.addView(SettingsUi.divider(this));
        }
    }

    private View buildStartRow(final com.c10h.launcher.AppStartupManager.AppEntry e) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(SettingsUi.dp(this, 88));
        row.setPadding(SettingsUi.dp(this, 16), SettingsUi.dp(this, 8),
                SettingsUi.dp(this, 16), SettingsUi.dp(this, 8));
        row.setBackgroundColor(SettingsUi.BG);

        ImageView icon = new ImageView(this);
        if (e.icon != null) {
            icon.setImageDrawable(e.icon);
        }
        row.addView(icon, new LinearLayout.LayoutParams(
                SettingsUi.dp(this, 40), SettingsUi.dp(this, 40)));

        TextView name = new TextView(this);
        name.setText(e.label);
        name.setTextColor(SettingsUi.TEXT);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = SettingsUi.dp(this, 12);
        row.addView(name, nlp);

        row.addView(startChip(e, 0));
        row.addView(startChip(e, 1));
        row.addView(startChip(e, 2));
        return row;
    }

    /**
     * 三个开关之一：0=自启动，1=关联启动，2=后台运行。
     */
    private TextView startChip(final com.c10h.launcher.AppStartupManager.AppEntry e,
                               final int which) {
        final boolean enabled = which != 0 || e.hasBoot;
        final TextView tv = new TextView(this);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setSingleLine(true);
        tv.setMinWidth(SettingsUi.dp(this, 104));
        tv.setPadding(SettingsUi.dp(this, 12), 0, SettingsUi.dp(this, 12), 0);
        styleStartChip(tv, which, which == 0 ? e.auto : which == 1 ? e.assoc : e.bg,
                enabled);
        tv.setEnabled(enabled);
        tv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean cur = which == 0 ? e.auto : which == 1 ? e.assoc : e.bg;
                boolean next = !cur;
                boolean ok;
                if (which == 0) {
                    ok = com.c10h.launcher.AppStartupManager.setAuto(
                            SettingsActivity.this, e, next);
                } else if (which == 1) {
                    ok = com.c10h.launcher.AppStartupManager.setAssoc(
                            SettingsActivity.this, e, next);
                } else {
                    ok = com.c10h.launcher.AppStartupManager.setBackground(
                            SettingsActivity.this, e, next);
                }
                if (!ok) {
                    toast("设置失败（需要系统权限）");
                    return;
                }
                styleStartChip(tv, which, next, enabled);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, SettingsUi.dp(this, 46));
        lp.leftMargin = SettingsUi.dp(this, 8);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void styleStartChip(TextView tv, int which, boolean on, boolean enabled) {
        String label = which == 0 ? "自启动" : which == 1 ? "关联启动" : "后台运行";
        if (!enabled) {
            tv.setText(label + " —");
            tv.setTextColor(0xFF9E9E9E);
            GradientDrawable g = new GradientDrawable();
            g.setColor(0xFFF5F5F5);
            g.setCornerRadius(SettingsUi.dp(this, 10));
            tv.setBackground(g);
            tv.setAlpha(0.6f);
            return;
        }
        tv.setText(label + (on ? " 开" : " 关"));
        tv.setTextColor(on ? 0xFFFFFFFF : 0xFF424242);
        GradientDrawable g = new GradientDrawable();
        g.setColor(on ? 0xFF1976D2 : 0xFFE0E0E0);
        g.setCornerRadius(SettingsUi.dp(this, 10));
        tv.setBackground(g);
        tv.setAlpha(1f);
    }

    private void applyAllStart(final boolean on) {
        if (startApps.isEmpty()) {
            toast("没有可管理的应用");
            return;
        }
        toast(on ? "正在全部开启…" : "正在全部关闭…");
        final List<com.c10h.launcher.AppStartupManager.AppEntry> snapshot =
                new ArrayList<com.c10h.launcher.AppStartupManager.AppEntry>(startApps);
        new Thread(new Runnable() {
            @Override
            public void run() {
                com.c10h.launcher.AppStartupManager.setAll(
                        getApplicationContext(), snapshot, on);
                final List<com.c10h.launcher.AppStartupManager.AppEntry> fresh =
                        com.c10h.launcher.AppStartupManager.load(getApplicationContext());
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || startListBox == null) {
                            return;
                        }
                        startApps.clear();
                        startApps.addAll(fresh);
                        renderStartApps();
                        toast(on ? "已全部开启" : "已全部关闭");
                    }
                });
            }
        }, "c10h-startup-all").start();
    }

    // ---------------- 内存占用 ----------------

    private static final class MemProc {
        int pid;
        String pkg;
        String label;
        Drawable icon;
        long pssKb;
        boolean critical;
    }

    private void buildMemory(LinearLayout c) {
        header("内存");
        memSummaryView = new TextView(this);
        memSummaryView.setText("读取中…");
        memSummaryView.setTextColor(SettingsUi.TEXT);
        memSummaryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        memSummaryView.setPadding(SettingsUi.dp(this, 16), SettingsUi.dp(this, 10),
                SettingsUi.dp(this, 16), SettingsUi.dp(this, 6));
        addPlain(c, memSummaryView);

        memBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        memBar.setMax(100);
        LinearLayout barWrap = new LinearLayout(this);
        barWrap.setPadding(SettingsUi.dp(this, 16), 0, SettingsUi.dp(this, 16),
                SettingsUi.dp(this, 8));
        barWrap.addView(memBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, SettingsUi.dp(this, 10)));
        addPlain(c, barWrap);

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.setMinimumHeight(SettingsUi.dp(this, 52));
        tools.setPadding(SettingsUi.dp(this, 16), 0, SettingsUi.dp(this, 16), 0);
        tools.setBackgroundColor(SettingsUi.BG);
        memCountView = new TextView(this);
        memCountView.setText("进程 · 读取中…");
        memCountView.setTextColor(SettingsUi.SUB);
        memCountView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tools.addView(memCountView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tools.addView(startActionChip("刷新", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openPageInternal("mem", "内存占用");
            }
        }));
        addRow(tools);

        addPlain(c, SettingsUi.body(this,
                "PSS 为该进程实际占用的物理内存。「结束」会对非关键应用执行强制停止；"
                        + "系统界面、电话、桌面等关键进程不允许结束。"));

        header("进程");
        memListBox = new LinearLayout(this);
        memListBox.setOrientation(LinearLayout.VERTICAL);
        addPlain(c, memListBox);
        loadMemory();
    }

    private void loadMemory() {
        final LinearLayout box = memListBox;
        if (box == null) {
            return;
        }
        box.removeAllViews();
        box.addView(SettingsUi.body(this, "正在读取进程…"));
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<MemProc> procs = new ArrayList<MemProc>();
                long total = 0;
                long avail = 0;
                try {
                    ActivityManager am = (ActivityManager)
                            getSystemService(Context.ACTIVITY_SERVICE);
                    ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                    if (am != null) {
                        am.getMemoryInfo(mi);
                        total = mi.totalMem;
                        avail = mi.availMem;
                    }
                    List<ActivityManager.RunningAppProcessInfo> ps =
                            am == null ? null : am.getRunningAppProcesses();
                    ArrayList<ActivityManager.RunningAppProcessInfo> sel =
                            new ArrayList<ActivityManager.RunningAppProcessInfo>();
                    if (ps != null) {
                        for (ActivityManager.RunningAppProcessInfo p : ps) {
                            if (p.pkgList != null && p.pkgList.length > 0) {
                                sel.add(p);
                            }
                        }
                    }
                    int[] pids = new int[sel.size()];
                    for (int i = 0; i < sel.size(); i++) {
                        pids[i] = sel.get(i).pid;
                    }
                    Debug.MemoryInfo[] infos = pids.length == 0 ? null
                            : am.getProcessMemoryInfo(pids);
                    PackageManager pm = getPackageManager();
                    for (int i = 0; i < sel.size(); i++) {
                        ActivityManager.RunningAppProcessInfo p = sel.get(i);
                        MemProc m = new MemProc();
                        m.pid = p.pid;
                        m.pkg = p.pkgList[0];
                        if (infos != null && i < infos.length && infos[i] != null) {
                            m.pssKb = infos[i].getTotalPss();
                        }
                        try {
                            ApplicationInfo ai = pm.getApplicationInfo(m.pkg, 0);
                            CharSequence l = pm.getApplicationLabel(ai);
                            m.label = l == null ? m.pkg : l.toString();
                            m.icon = ai.loadIcon(pm);
                        } catch (Throwable t) {
                            m.label = m.pkg;
                        }
                        m.critical = criticalProcess(m.pkg);
                        procs.add(m);
                    }
                    java.util.Collections.sort(procs, new java.util.Comparator<MemProc>() {
                        @Override
                        public int compare(MemProc a, MemProc b) {
                            return Long.compare(b.pssKb, a.pssKb);
                        }
                    });
                } catch (Throwable ignored) {
                }
                final long tTotal = total;
                final long tAvail = avail;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || box != memListBox) {
                            return;
                        }
                        long used = Math.max(0, tTotal - tAvail);
                        if (memSummaryView != null) {
                            memSummaryView.setText("总 " + fmtMem(tTotal)
                                    + " · 已用 " + fmtMem(used)
                                    + " · 可用 " + fmtMem(tAvail));
                        }
                        if (memBar != null && tTotal > 0) {
                            memBar.setProgress((int) Math.min(100,
                                    used * 100 / tTotal));
                        }
                        if (memCountView != null) {
                            memCountView.setText("进程 · " + procs.size() + " 个");
                        }
                        box.removeAllViews();
                        if (procs.isEmpty()) {
                            box.addView(SettingsUi.body(SettingsActivity.this,
                                    "没有可显示的进程。"));
                            return;
                        }
                        for (MemProc m : procs) {
                            box.addView(buildMemRow(m));
                            box.addView(SettingsUi.divider(SettingsActivity.this));
                        }
                    }
                });
            }
        }, "c10h-mem").start();
    }

    private View buildMemRow(final MemProc m) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(SettingsUi.dp(this, 72));
        row.setPadding(SettingsUi.dp(this, 16), SettingsUi.dp(this, 6),
                SettingsUi.dp(this, 16), SettingsUi.dp(this, 6));
        row.setBackgroundColor(SettingsUi.BG);

        ImageView icon = new ImageView(this);
        if (m.icon != null) {
            icon.setImageDrawable(m.icon);
        }
        row.addView(icon, new LinearLayout.LayoutParams(
                SettingsUi.dp(this, 36), SettingsUi.dp(this, 36)));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(SettingsUi.dp(this, 12), 0, 0, 0);
        TextView name = new TextView(this);
        name.setText(m.label);
        name.setTextColor(SettingsUi.TEXT);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mid.addView(name);
        TextView sub = new TextView(this);
        sub.setText(m.pkg + " · PID " + m.pid);
        sub.setTextColor(SettingsUi.SUB);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mid.addView(sub);
        row.addView(mid, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView pss = new TextView(this);
        pss.setText(fmtMem(m.pssKb * 1024L));
        pss.setTextColor(SettingsUi.SUB);
        pss.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        pss.setPadding(SettingsUi.dp(this, 12), 0, SettingsUi.dp(this, 4), 0);
        row.addView(pss);

        TextView kill = startActionChip(m.critical ? "关键" : "结束", false,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        killProcess(m);
                    }
                });
        if (m.critical) {
            kill.setEnabled(false);
            kill.setAlpha(0.4f);
        }
        row.addView(kill);
        return row;
    }

    private void killProcess(final MemProc m) {
        if (m == null || m.critical) {
            toast("系统关键进程，不可结束");
            return;
        }
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) {
            return;
        }
        try {
            java.lang.reflect.Method method = ActivityManager.class
                    .getMethod("forceStopPackage", String.class);
            method.invoke(am, m.pkg);
            toast("已结束 " + m.label);
        } catch (Throwable t) {
            try {
                am.killBackgroundProcesses(m.pkg);
                toast("已结束后台进程 " + m.label);
            } catch (Throwable t2) {
                toast("结束失败：" + t2);
                return;
            }
        }
        if (memListBox != null) {
            memListBox.postDelayed(new Runnable() {
                @Override
                public void run() {
                    loadMemory();
                }
            }, 800);
        }
    }

    private boolean criticalProcess(String pkg) {
        if (pkg == null) {
            return true;
        }
        if (pkg.equals(getPackageName())) {
            return true;
        }
        return pkg.equals("android")
                || pkg.equals("com.android.systemui")
                || pkg.equals("com.android.settings")
                || pkg.equals("com.android.phone")
                || pkg.equals("com.android.bluetooth")
                || pkg.equals("com.android.speaker.settings")
                || pkg.startsWith("com.android.providers.");
    }

    private static String fmtMem(long bytes) {
        if (bytes >= 1073741824L) {
            return String.format(Locale.US, "%.1f GB", bytes / 1073741824.0);
        }
        return String.format(Locale.US, "%d MB", Math.max(0, bytes) / 1048576L);
    }

    // ---------------- 电池 ----------------

    private void buildBattery(LinearLayout c) {
        int level = -1;
        try {
            BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
            level = bm != null ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;
        } catch (Throwable ignored) {
        }
        addRow(SettingsUi.pref(this, -1, "电量",
                level >= 0 ? level + "%" : "未知", null, null));

        Intent i = null;
        try {
            i = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        } catch (Throwable ignored) {
        }
        if (i != null) {
            int st = i.getIntExtra("status", 1);
            addRow(SettingsUi.pref(this, -1, "状态",
                    st == 2 ? "充电中" : "放电", null, null));
            addRow(SettingsUi.pref(this, -1, "温度",
                    String.format(Locale.US, "%.1f℃",
                            i.getIntExtra("temperature", 0) / 10f), null, null));
        }
        addPlain(c, SettingsUi.body(this, "电量与温度来自系统电池服务，只读展示。"));
    }

    // ---------------- 显示 ----------------

    private void buildDisplay(LinearLayout c) {
        header("亮度");
        int cur = 128;
        try {
            cur = Settings.System.getInt(getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, 128);
        } catch (Throwable ignored) {
        }
        final SettingsUi.Slider sl = SettingsUi.slider(this, "屏幕亮度", 255, cur);
        sl.setValueText(Math.round(cur * 100f / 255f) + "%");
        sl.bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                sl.setValueText(Math.round(p * 100f / 255f) + "%");
                if (fromUser) {
                    try {
                        Settings.System.putInt(getContentResolver(),
                                Settings.System.SCREEN_BRIGHTNESS, p);
                    } catch (Throwable ignored) {
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        addPlain(c, sl.root);

        final long[] vals = {15000, 30000, 60000, 120000, 300000, 600000, -1};
        final String[] names = {"15 秒", "30 秒", "1 分钟", "2 分钟", "5 分钟",
                "10 分钟", "永不"};
        addRow(SettingsUi.pref(this, -1, "屏幕超时",
                timeoutName(), null, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        int checked = -1;
                        for (int i = 0; i < vals.length; i++) {
                            if (vals[i] == timeoutValue()
                                    || (vals[i] < 0 && timeoutValue() < 0)) {
                                checked = i;
                            }
                        }
                        new AlertDialog.Builder(SettingsActivity.this)
                                .setTitle("屏幕超时")
                                .setSingleChoiceItems(names, checked,
                                        new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface d, int w) {
                                                // 统一写 c10h 的 blankTimeout，由模式决定
                                                // 系统超时（普通=该值；保持后台=-1）。
                                                com.c10h.launcher.ScreenKeepAlive
                                                        .setBlankTimeout(
                                                                SettingsActivity.this,
                                                                vals[w]);
                                                d.dismiss();
                                                openPageInternal("display", "显示");
                                            }
                                        })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                }));
        addRow(SettingsUi.pref(this, -1, "壁纸",
                "从系统壁纸选择器更换", null, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            startActivity(new Intent(Intent.ACTION_SET_WALLPAPER));
                        } catch (Throwable t) {
                            toast("无壁纸选择器");
                        }
                    }
                }));
        // 本机 ROM 默认把"休眠"接到相册屏保：超时只变暗不息屏。
        // 这里给出显式开关，默认关闭，让超时真正熄屏。
        final boolean scOn = com.c10h.launcher.PowerActions.screensaverEnabled(this);
        addRow(SettingsUi.pref(this, -1, "休眠屏保",
                scOn ? "已开启：休眠后只显示相册屏保（不会熄屏）" : "已关闭：屏幕超时后正常熄屏",
                null, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        boolean now = com.c10h.launcher.PowerActions
                                .screensaverEnabled(SettingsActivity.this);
                        if (!com.c10h.launcher.PowerActions
                                .setScreensaverEnabled(SettingsActivity.this, !now)) {
                            toast("写入失败（需 WRITE_SECURE_SETTINGS）");
                            return;
                        }
                        toast(!now ? "已关闭休眠屏保" : "已开启休眠屏保");
                        openPageInternal("display", "显示");
                    }
                }));
        addRow(SettingsUi.pref(this, -1, "立即息屏",
                "关闭屏幕，等效电源键", null, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        com.c10h.launcher.PowerActions.sleep(SettingsActivity.this);
                    }
                }));
        addPlain(c, SettingsUi.body(this,
                "亮度与屏幕超时写入系统设置，需 WRITE_SETTINGS；失败时保持原值。"
                        + "屏幕超时到点后系统真正熄屏并进入休眠，网络与后台随系统暂停；"
                        + "按电源键唤醒（唤醒耗时由 ROM/面板决定）。"));
    }

    private long timeoutValue() {
        return com.c10h.launcher.ScreenKeepAlive.blankTimeout(this);
    }

    private String timeoutName() {
        long v = timeoutValue();
        if (v <= 0 || v >= 86400000L) {
            return "永不";
        }
        if (v == 15000) {
            return "15 秒";
        } else if (v == 30000) {
            return "30 秒";
        } else if (v == 60000) {
            return "1 分钟";
        } else if (v == 120000) {
            return "2 分钟";
        } else if (v == 300000) {
            return "5 分钟";
        } else if (v == 600000) {
            return "10 分钟";
        }
        return Math.max(1, Math.round(v / 60000f)) + " 分钟";
    }

    // ---------------- 声音 ----------------

    private void buildSound(LinearLayout c) {
        final AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            throw new IllegalStateException("AudioManager 不可用");
        }
        int[] streams = {AudioManager.STREAM_MUSIC, AudioManager.STREAM_RING,
                AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_ALARM};
        String[] names = {"媒体", "铃声", "通知", "闹钟"};
        header("音量");
        for (int i = 0; i < streams.length; i++) {
            final int st = streams[i];
            final SettingsUi.Slider sl = SettingsUi.slider(this, names[i],
                    am.getStreamMaxVolume(st), am.getStreamVolume(st));
            sl.setValueText(String.valueOf(am.getStreamVolume(st)));
            sl.bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                    sl.setValueText(String.valueOf(p));
                    if (fromUser) {
                        am.setStreamVolume(st, p, 0);
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar s) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar s) {
                }
            });
            addPlain(c, sl.root);
        }
        header("音效");
        addRow(SettingsUi.pref(this, -1, "均衡器",
                "10 段 EQ、预设与自定义曲线", null, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            // :settings 进程没有 EqEngine，转到主进程的 EqActivity 打开面板。
                            startActivity(new Intent(SettingsActivity.this,
                                    com.c10h.launcher.EqActivity.class));
                        } catch (Throwable t) {
                            toast("均衡器不可用：" + t);
                        }
                    }
                }));
    }

    // ---------------- 存储 ----------------

    private void buildStorage(LinearLayout c) {
        header("内部存储");
        addRow(cap("内部存储", Environment.getDataDirectory()));
        addRow(cap("共享存储", Environment.getExternalStorageDirectory()));
        File usb = new File("/mnt/media_rw");
        File[] vols = usb.listFiles();
        boolean any = false;
        if (vols != null) {
            for (File v : vols) {
                if (v.isDirectory()) {
                    addRow(cap("USB 卷 " + v.getName(), v));
                    any = true;
                }
            }
        }
        if (!any) {
            addPlain(c, SettingsUi.body(this, "未检测到 USB 存储卷。"));
        }
        addRow(SettingsUi.pref(this, -1, "文件管理器",
                "浏览内部存储与 USB 设备", null, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            Intent i = new Intent();
                            i.setClassName(getPackageName(),
                                    "com.c10h.launcher.FileBrowserActivity");
                            startActivity(i);
                        } catch (Throwable t) {
                            toast("文件管理器不可用");
                        }
                    }
                }));
    }

    private View cap(String name, File dir) {
        String summary;
        try {
            StatFs s = new StatFs(dir.getAbsolutePath());
            long total = s.getBlockCountLong() * s.getBlockSizeLong();
            long free = s.getAvailableBlocksLong() * s.getBlockSizeLong();
            summary = gb(total - free) + " / " + gb(total) + "（可用 " + gb(free) + "）";
        } catch (Throwable t) {
            summary = "读取失败";
        }
        return SettingsUi.pref(this, -1, name, summary, null, null);
    }

    private String gb(long b) {
        return String.format(Locale.US, "%.1f GB", b / 1073741824f);
    }

    // ---------------- 系统 ----------------

    private void buildSystem(LinearLayout c) {
        addRow(SettingsUi.pref(this, -1, "应用更新",
                "下载 APK 或从 U 盘/SD 卡选择，无需 adb", null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            startActivity(new Intent(SettingsActivity.this,
                                    UpdateActivity.class));
                        } catch (Throwable t) {
                            toast("应用更新打开失败：" + t);
                        }
                    }
                }));
        addRow(SettingsUi.pref(this, -1, "开发者选项",
                "动画缩放、指针位置（不提供 USB 调试开关）", null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openPage("dev", "开发者选项");
                    }
                }));
        addRow(SettingsUi.pref(this, -1, "DNS 屏蔽",
                "域名/IP 屏蔽、命中日志、种子规则", null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openPage("block", "DNS 屏蔽");
                    }
                }));
        addRow(SettingsUi.pref(this, -1, "ADB 守护",
                "adbd 自愈与拉回记录", null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openPage("guard", "ADB 守护");
                    }
                }));
        addRow(SettingsUi.pref(this, -1, "重置选项",
                "恢复默认设置", null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openPage("reset", "重置选项");
                    }
                }));
    }

    private static final float[] SCALES = {0.5f, 1f, 1.5f, 2f};

    private void buildDeveloper(LinearLayout c) {
        addPlain(c, SettingsUi.body(this,
                "⚠ 本机定制陷阱：写入 Settings global（含下方动画缩放）约 180 秒后会杀 adbd；"
                        + "ADB 守护在位时 ≤60 秒恢复（闪断属预期）。USB 调试开关刻意不提供。"));

        header("绘图");
        scaleSlider(c, "窗口动画缩放", Settings.Global.WINDOW_ANIMATION_SCALE);
        scaleSlider(c, "过渡动画缩放", Settings.Global.TRANSITION_ANIMATION_SCALE);
        scaleSlider(c, "Animator 时长缩放", Settings.Global.ANIMATOR_DURATION_SCALE);

        header("输入");
        boolean ptrOn = false;
        try {
            ptrOn = Settings.System.getInt(getContentResolver(),
                    "pointer_location", 0) == 1;
        } catch (Throwable ignored) {
        }
        addRow(SettingsUi.switchPref(this, -1, "指针位置",
                "显示触摸坐标（调试用）", ptrOn,
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean on) {
                        try {
                            Settings.System.putInt(getContentResolver(),
                                    "pointer_location", on ? 1 : 0);
                        } catch (Throwable t) {
                            toast("指针位置写入失败（需 WRITE_SETTINGS）");
                        }
                    }
                }));
    }

    private void scaleSlider(LinearLayout c, String name, final String key) {
        float cur = 1f;
        try {
            cur = Settings.Global.getFloat(getContentResolver(), key, 1f);
        } catch (Throwable ignored) {
        }
        int idx = 1;
        for (int i = 0; i < SCALES.length; i++) {
            if (Math.abs(SCALES[i] - cur) < 0.01f) {
                idx = i;
            }
        }
        final SettingsUi.Slider sl = SettingsUi.slider(this, name, 3, idx);
        sl.setValueText(SCALES[idx] + "x");
        sl.bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                sl.setValueText(SCALES[p] + "x");
                if (!fromUser) {
                    return;
                }
                try {
                    Settings.Global.putFloat(getContentResolver(), key, SCALES[p]);
                } catch (Throwable t) {
                    toast("写入失败（需 WRITE_SECURE_SETTINGS）");
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        addPlain(c, sl.root);
    }

    // ---------------- 屏蔽 ----------------

    private void buildBlock(LinearLayout c) {
        SettingsUi.SwitchBar bar = SettingsUi.switchBar(this, "DNS 屏蔽", vpnOn,
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean on) {
                        try {
                            // 本机没有 VPN 授权弹窗，先用 ACTIVATE_VPN appop 预授权。
                            com.c10h.launcher.block.VpnConsent.grant(SettingsActivity.this);
                            Intent consent = VpnService.prepare(SettingsActivity.this);
                            if (consent != null) {
                                startActivity(consent);
                                toast("已弹系统授权，点「允许」后再点一次");
                                b.setChecked(!on);
                                return;
                            }
                            sendBroadcast(new Intent(RuleStore.ACTION_CMD)
                                    .setClass(SettingsActivity.this, BootReceiver.class)
                                    .putExtra("cmd", "toggle_vpn"));
                        } catch (Throwable t) {
                            toast("开关失败：" + t);
                            b.setChecked(!on);
                        }
                    }
                });
        pageBarHost.addView(bar.root, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        blockBar = bar;

        header("规则");
        blockStateView = new TextView(this);        blockStateView.setText("屏蔽状态：" + vpnState);
        blockStateView.setTextColor(SettingsUi.SUB);
        blockStateView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        blockStateView.setPadding(SettingsUi.dp(this, 16), SettingsUi.dp(this, 10),
                SettingsUi.dp(this, 16), SettingsUi.dp(this, 10));
        addPlain(c, blockStateView);

        addRow(SettingsUi.pref(this, -1, "添加规则",
                "输入域名或 IP，命中后返回空响应", null, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        addRuleDialog();
                    }
                }));
        addRow(SettingsUi.pref(this, -1, "从文件导入",
                "选择 hosts 或域名列表（# 注释、0.0.0.0 域名 均可）", null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        pickRuleFile();
                    }
                }));
        addRow(SettingsUi.pref(this, -1, "从 URL 导入",
                "下载远端 hosts / 域名列表并合并去重", null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        importFromUrlDialog();
                    }
                }));
        header("规则列表");
        ruleCountView = new TextView(this);
        ruleCountView.setText("规则列表：读取中…");
        ruleCountView.setTextColor(SettingsUi.SUB);
        ruleCountView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        ruleCountView.setPadding(SettingsUi.dp(this, 16), SettingsUi.dp(this, 8),
                SettingsUi.dp(this, 16), SettingsUi.dp(this, 8));
        addPlain(c, ruleCountView);
        rulesListBox = new LinearLayout(this);
        rulesListBox.setOrientation(LinearLayout.VERTICAL);
        addPlain(c, rulesListBox);
        addPlain(c, SettingsUi.body(this,
                "屏蔽使用本机 VPN 稀疏路由，仅在开启时拦截命中规则的请求；"
                        + "规则与命中日志保存在本机，不上传。\n"
                        + "导入支持：hosts 行（0.0.0.0 ads.example.com）、纯域名列表、"
                        + "*.example.com 通配、纯 IPv4；# ; // 注释与行内 #；自动去重。"));
        sendBroadcast(new Intent(RuleStore.ACTION_CMD)
                .setClass(this, BootReceiver.class).putExtra("cmd", "query"));
    }

    /** 用状态广播里的 rules 数组渲染可查看/编辑的规则列表。 */
    private void renderRules(JSONArray rules) {
        if (rulesListBox == null) {
            return;
        }
        rulesListBox.removeAllViews();
        int n = rules == null ? 0 : rules.length();
        if (ruleCountView != null) {
            ruleCountView.setText("规则列表 · " + n + " 条");
        }
        if (n == 0) {
            rulesListBox.addView(SettingsUi.body(this, "暂无规则，可点击上方添加或导入。"));
            return;
        }
        for (int i = 0; i < n; i++) {
            JSONObject o = rules.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String type = o.optString("type", "domain");
            String value = o.optString("value", "");
            String note = o.optString("note", "");
            boolean on = o.optBoolean("on", true);
            if (value.isEmpty()) {
                continue;
            }
            rulesListBox.addView(buildRuleRow(type, value, note, on));
            rulesListBox.addView(SettingsUi.divider(this));
        }
    }

    private View buildRuleRow(final String type, final String value,
                              final String note, final boolean on) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(SettingsUi.dp(this, 72));
        row.setPadding(SettingsUi.dp(this, 16), SettingsUi.dp(this, 6),
                SettingsUi.dp(this, 16), SettingsUi.dp(this, 6));
        row.setBackgroundColor(SettingsUi.BG);

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(SettingsUi.TEXT);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mid.addView(v);
        TextView sub = new TextView(this);
        String kind = "ip".equals(type) ? "IP" : "域名";
        sub.setText(kind + (note == null || note.isEmpty() ? "" : " · " + note));
        sub.setTextColor(SettingsUi.SUB);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mid.addView(sub);
        row.addView(mid, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(startActionChip(on ? "已启用" : "已停用", on,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        sendBlockCmd("toggle", value);
                    }
                }));
        row.addView(startActionChip("编辑", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editRuleDialog(type, value, note);
            }
        }));
        TextView del = startActionChip("删除", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDeleteRule(value);
            }
        });
        row.addView(del);
        return row;
    }

    private void sendBlockCmd(String cmd, String value) {
        try {
            sendBroadcast(new Intent(RuleStore.ACTION_CMD)
                    .setClass(this, BootReceiver.class)
                    .putExtra("cmd", cmd)
                    .putExtra("value", value));
        } catch (Throwable t) {
            toast("操作失败：" + t);
        }
    }

    private void confirmDeleteRule(final String value) {
        new AlertDialog.Builder(this)
                .setTitle("删除规则")
                .setMessage("删除「" + value + "」？")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        sendBlockCmd("del", value);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void editRuleDialog(final String oldType, final String oldValue,
                                final String oldNote) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(SettingsUi.dp(this, 20), SettingsUi.dp(this, 8),
                SettingsUi.dp(this, 20), 0);
        final EditText v = new EditText(this);
        v.setText(oldValue);
        v.setSingleLine(true);
        v.setHint("example.com 或 1.2.3.4");
        wrap.addView(v, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final EditText n = new EditText(this);
        n.setText(oldNote == null ? "" : oldNote);
        n.setSingleLine(true);
        n.setHint("备注（可选）");
        wrap.addView(n, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("编辑规则")
                .setView(wrap)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String nv = v.getText().toString().trim();
                        if (nv.isEmpty()) {
                            toast("内容不能为空");
                            return;
                        }
                        String type = nv.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")
                                ? "ip" : "domain";
                        sendBroadcast(new Intent(RuleStore.ACTION_CMD)
                                .setClass(SettingsActivity.this, BootReceiver.class)
                                .putExtra("cmd", "edit")
                                .putExtra("oldValue", oldValue)
                                .putExtra("type", type)
                                .putExtra("value", nv)
                                .putExtra("note", n.getText().toString().trim()));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 文件选择：优先系统选择器；本机没有 DocumentsUI 时用自带文件管理器。 */
    private void pickRuleFile() {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("*/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            if (i.resolveActivity(getPackageManager()) != null) {
                startActivityForResult(i, REQ_IMPORT_FILE);
                return;
            }
        } catch (Throwable ignored) {
        }
        try {
            Intent i = new Intent();
            i.setClassName(this, "com.c10h.launcher.FileBrowserActivity");
            i.putExtra("pick", true);
            startActivityForResult(i, REQ_IMPORT_FILE);
        } catch (Throwable t) {
            toast("没有可用的文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT_FILE || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        importFromUri(data.getData());
    }

    private void importFromUri(final Uri uri) {
        toast("正在读取文件…");
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                int n = -1;
                java.io.InputStream in = null;
                try {
                    if (uri != null && "file".equals(uri.getScheme())
                            && uri.getPath() != null) {
                        // 自带文件管理器回传的 file:// URI（无 DocumentsUI 的设备）
                        in = new java.io.FileInputStream(uri.getPath());
                    } else {
                        in = getContentResolver().openInputStream(uri);
                    }
                    n = com.c10h.launcher.block.RuleImporter.parseToFile(app, in);
                } catch (Throwable ignored) {
                } finally {
                    try {
                        if (in != null) {
                            in.close();
                        }
                    } catch (Throwable ignored) {
                    }
                }
                finishImport(n, "文件");
            }
        }, "c10h-rule-file").start();
    }

    private void importFromUrlDialog() {
        final EditText in = new EditText(this);
        in.setHint("https://example.com/hosts.txt");
        in.setSingleLine(true);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(SettingsUi.dp(this, 20), SettingsUi.dp(this, 8),
                SettingsUi.dp(this, 20), 0);
        wrap.addView(in, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("从 URL 导入规则")
                .setView(wrap)
                .setPositiveButton("下载并导入", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        importFromUrl(in.getText().toString().trim());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void importFromUrl(final String url) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            toast("请输入 http:// 或 https:// 开头的地址");
            return;
        }
        toast("正在下载…");
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                int n = -1;
                java.net.HttpURLConnection conn = null;
                try {
                    conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    conn.setInstanceFollowRedirects(true);
                    conn.setRequestProperty("User-Agent", "C10H-Launcher");
                    int code = conn.getResponseCode();
                    if (code >= 200 && code < 300) {
                        n = com.c10h.launcher.block.RuleImporter
                                .parseToFile(app, conn.getInputStream());
                    }
                } catch (Throwable ignored) {
                } finally {
                    try {
                        if (conn != null) {
                            conn.disconnect();
                        }
                    } catch (Throwable ignored) {
                    }
                }
                finishImport(n, "URL");
            }
        }, "c10h-rule-url").start();
    }

    /** 解析完成（后台线程）→ 主线程判断并广播给 :block 进程合并。 */
    private void finishImport(final int n, final String source) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) {
                    return;
                }
                if (n < 0) {
                    toast(source + " 读取失败");
                    return;
                }
                if (n == 0) {
                    toast(source + " 中没有可导入的域名/IP");
                    return;
                }
                sendBroadcast(new Intent(RuleStore.ACTION_CMD)
                        .setClass(SettingsActivity.this, BootReceiver.class)
                        .putExtra("cmd", "import")
                        .putExtra("path",
                                com.c10h.launcher.block.RuleImporter
                                        .tmpFile(getApplicationContext()).getAbsolutePath()));
                toast(source + " 解析到 " + n + " 条，正在合并…");
            }
        });
    }

    private void addRuleDialog() {
        final EditText in = new EditText(this);
        in.setHint("example.com 或 1.2.3.4");
        in.setSingleLine(true);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(SettingsUi.dp(this, 20), SettingsUi.dp(this, 8),
                SettingsUi.dp(this, 20), 0);
        wrap.addView(in, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("添加规则")
                .setView(wrap)
                .setPositiveButton("添加", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String v = in.getText().toString().trim();
                        if (v.isEmpty()) {
                            return;
                        }
                        String type = v.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+$") ? "ip" : "domain";
                        sendBroadcast(new Intent(RuleStore.ACTION_CMD)
                                .setClass(SettingsActivity.this, BootReceiver.class)
                                .putExtra("cmd", "add")
                                .putExtra("type", type)
                                .putExtra("value", v)
                                .putExtra("note", "手动"));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- ADB 守护 ----------------

    private void buildGuard(LinearLayout c) {
        header("自愈");
        LinearLayout row = SettingsUi.switchPref(this, -1, "自愈守护",
                "每 60 秒检查 adbd，被杀自动拉回", guardOn,
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean on) {
                        guardOn = on;
                        sendBroadcast(new Intent("com.c10h.launcher.action.ADB_CMD")
                                .setClass(SettingsActivity.this, BootReceiver.class)
                                .putExtra("cmd", on ? "guard_on" : "guard_off"));
                    }
                });
        guardSwitch = (Switch) row.getChildAt(row.getChildCount() - 1);
        addRow(row);

        header("状态");
        guardStateView = new TextView(this);
        guardStateView.setText(guardState);
        guardStateView.setTextColor(SettingsUi.SUB);
        guardStateView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        guardStateView.setPadding(SettingsUi.dp(this, 16), SettingsUi.dp(this, 10),
                SettingsUi.dp(this, 16), SettingsUi.dp(this, 4));
        addPlain(c, guardStateView);

        guardLogView = new TextView(this);
        guardLogView.setText(guardLog);
        guardLogView.setTextColor(SettingsUi.SUB);
        guardLogView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        guardLogView.setPadding(SettingsUi.dp(this, 16), SettingsUi.dp(this, 4),
                SettingsUi.dp(this, 16), SettingsUi.dp(this, 12));
        addPlain(c, guardLogView);

        addPlain(c, SettingsUi.body(this,
                "180s 陷阱：写 Settings global 会点火，adbd 届时被杀——"
                        + "守护在位时 ≤60s 自动恢复，ADB 闪断属预期。"));
        sendBroadcast(new Intent("com.c10h.launcher.action.ADB_CMD")
                .setClass(this, BootReceiver.class).putExtra("cmd", "query"));
    }

    // ---------------- 通知访问 ----------------

    private static final String NLS =
            "com.c10h.launcher/com.c10h.launcher.badge.BadgeListenerService";

    private void buildNotify(LinearLayout c) {
        addRow(SettingsUi.switchPref(this, -1, "允许通知访问",
                "授权后桌面图标显示未读圆点（:badge 进程监听通知）", notifyGranted(),
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean on) {
                        if (!applyNotify(on)) {
                            b.setChecked(!on);
                        }
                    }
                }));
        addRow(SettingsUi.pref(this, -1, "系统授权页",
                "打开系统的通知访问设置", null, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            startActivity(new Intent(
                                    "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                        } catch (Throwable t) {
                            try {
                                startActivity(new Intent(Settings.ACTION_SETTINGS));
                            } catch (Throwable t2) {
                                toast("系统页不可用");
                            }
                        }
                    }
                }));
        addPlain(c, SettingsUi.body(this,
                "关闭只停止角标渲染，不影响通知监听服务本身。"));
    }

    private boolean notifyGranted() {
        try {
            String cur = Settings.Secure.getString(getContentResolver(),
                    "enabled_notification_listeners");
            return cur != null && cur.contains(NLS);
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean applyNotify(boolean on) {
        try {
            String cur = Settings.Secure.getString(getContentResolver(),
                    "enabled_notification_listeners");
            String next = on ? (cur == null || cur.isEmpty() ? NLS : cur + ":" + NLS) : NLS;
            if (!on && cur != null) {
                next = cur.replace(NLS, "").replace("::", ":").replaceAll("^:|:$", "");
            }
            Settings.Secure.putString(getContentResolver(),
                    "enabled_notification_listeners", next);
            return true;
        } catch (Throwable t) {
            toast("直写失败（需 WRITE_SECURE_SETTINGS），已转到系统页");
            try {
                startActivity(new Intent(
                        "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
            } catch (Throwable t2) {
                toast("系统页也不可用");
            }
            return false;
        }
    }

    // ---------------- 重置 ----------------

    private void buildReset(LinearLayout c) {
        addRow(SettingsUi.pref(this, -1, "恢复默认设置",
                "将桌面设置项恢复默认：自动加图标、通知圆点、导航条、旋转", null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        new AlertDialog.Builder(SettingsActivity.this)
                                .setTitle("恢复默认设置")
                                .setMessage("只重置桌面设置项，不会删除桌面布局、文件夹或应用数据。")
                                .setPositiveButton("恢复默认",
                                        new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface d, int w) {
                                                WorkspaceStore.resetToDefaults(
                                                        SettingsActivity.this);
                                                Intent i = new Intent(
                                                        "com.c10h.launcher.action.SETTINGS_CHANGED");
                                                i.putExtra("add", true);
                                                i.putExtra("dots", true);
                                                i.putExtra("nav", true);
                                                i.putExtra("rotate", false);
                                                sendBroadcast(i);
                                                toast("已恢复默认设置");
                                            }
                                        })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                }));
        addPlain(c, SettingsUi.body(this,
                "该操作不会清除已安装应用、桌面布局或屏蔽规则。"));
    }

    // ---------------- 关于手机 ----------------

    private void buildAbout(LinearLayout c) {
        header("设备信息");
        addRow(SettingsUi.pref(this, -1, "型号",
                android.os.Build.MODEL, null, null));
        addRow(SettingsUi.pref(this, -1, "Android 版本",
                android.os.Build.VERSION.RELEASE + "（API "
                        + android.os.Build.VERSION.SDK_INT + "）", null, null));
        addRow(SettingsUi.pref(this, -1, "系统版本",
                android.os.Build.DISPLAY, null, null));
        addRow(SettingsUi.pref(this, -1, "硬件",
                android.os.Build.HARDWARE, null, null));
        addRow(SettingsUi.pref(this, -1, "指纹",
                android.os.Build.FINGERPRINT, null, null));
        addRow(SettingsUi.pref(this, -1, "本应用",
                "C10H Launcher v2（平台钥签名）", null, null));
    }

    // ---------------- 屏蔽/守护状态广播 ----------------

    private void registerStateReceiver() {
        stateRx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                try {
                    String json = intent.getStringExtra(RuleStore.EXTRA_STATE);
                    if (json == null) {
                        return;
                    }
                    JSONObject o = new JSONObject(json);
                    if (o.has("vpn")) {
                        vpnOn = o.optBoolean("vpn", false);
                        vpnState = vpnOn ? "运行中（稀疏路由 TUN）" : "未开启";
                        if (blockStateView != null) {
                            blockStateView.setText("屏蔽状态：" + vpnState);
                        }
                        if (blockBar != null) {
                            blockBar.setSilently(vpnOn);
                        }
                    }
                    if (o.has("guard")) {
                        guardOn = o.optBoolean("guard", true);
                        if (guardSwitch != null) {
                            guardSwitch.setChecked(guardOn);
                        }
                        String adbd = o.optString("adbd", "");
                        guardState = "adbd 状态：" + (adbd.isEmpty() ? "未知" : adbd)
                                + (guardOn ? "  ·  守护：运行中" : "  ·  守护：已关闭");
                        if (guardStateView != null) {
                            guardStateView.setText(guardState);
                        }
                        JSONArray log = o.optJSONArray("log");
                        StringBuilder sb = new StringBuilder("最近拉回记录：");
                        if (log == null || log.length() == 0) {
                            sb.append("\n（暂无）");
                        } else {
                            for (int i = log.length() - 1; i >= 0 && i >= log.length() - 5; i--) {
                                sb.append("\n· ").append(log.optString(i));
                            }
                        }
                        guardLog = sb.toString();
                        if (guardLogView != null) {
                            guardLogView.setText(guardLog);
                        }
                    }
                    if (o.has("rules")) {
                        renderRules(o.optJSONArray("rules"));
                    }
                    int imported = intent.getIntExtra("imported", -1);
                    if (imported >= 0) {
                        toast(imported > 0
                                ? "已合并 " + imported + " 条新规则"
                                : "没有新增规则（已存在）");
                    }
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            IntentFilter f = new IntentFilter(RuleStore.ACTION_STATE);
            f.addAction("com.c10h.launcher.action.ADB_STATE");
            registerReceiver(stateRx, f);
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 兜底 ----------------

    private void openFactorySettings() {
        try {
            Intent i = new Intent();
            i.setClassName("com.android.speaker.settings",
                    "com.android.speaker.settings.MainActivity");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return;
        } catch (Throwable ignored) {
        }
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        } catch (Throwable ignored) {
        }
        toast("无法打开原厂设置");
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }
}
