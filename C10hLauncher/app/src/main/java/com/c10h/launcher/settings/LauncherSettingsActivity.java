package com.c10h.launcher.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.c10h.launcher.WorkspaceStore;

/**
 * AOSP Launcher3 HomeSettings 对齐的桌面设置页：
 * 56dp 顶栏（返回 + “主屏幕设置”）+ 分组标题 + preference 行；
 * 开关行整行可点，改动即时写入并广播回桌面（无搜索栏，与 Launcher3 一致）。
 */
public class LauncherSettingsActivity extends Activity {

    private LinearLayout content;
    private boolean lastWasRow;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            build();
        } catch (Throwable t) {
            TextView tv = new TextView(this);
            tv.setText("设置打开失败\n" + t);
            tv.setTextColor(0xFFD32F2F);
            tv.setPadding(SettingsUi.dp(this, 24), SettingsUi.dp(this, 24), 0, 0);
            setContentView(tv);
        }
    }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(SettingsUi.BG);

        root.addView(SettingsUi.appBar(this, "主屏幕设置", new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(SettingsUi.BG);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        render();
    }

    private void render() {
        content.removeAllViews();
        lastWasRow = false;

        header("主屏幕");
        addRow(SettingsUi.switchPref(this, -1, "将图标添加到主屏幕",
                "新安装的应用自动加入工作区", WorkspaceStore.addIconToHome(this),
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean on) {
                        WorkspaceStore.setAddIconToHome(LauncherSettingsActivity.this, on);
                        notifyChanged();
                    }
                }));
        addRow(SettingsUi.switchPref(this, -1, "允许旋转",
                "关闭时固定横屏", WorkspaceStore.allowRotation(this),
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean on) {
                        WorkspaceStore.setAllowRotation(LauncherSettingsActivity.this, on);
                        notifyChanged();
                    }
                }));
        addRow(SettingsUi.switchPref(this, -1, "通知圆点",
                "在应用图标上显示未读通知", WorkspaceStore.notificationDots(this),
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean on) {
                        WorkspaceStore.setNotificationDots(LauncherSettingsActivity.this, on);
                        notifyChanged();
                    }
                }));
        addRow(SettingsUi.pref(this, -1, "网格大小",
                com.c10h.launcher.DeviceProfile
                        .forDisplay(getResources().getDisplayMetrics()).cols
                        + " 列自适应（按屏幕高度增减行数）", null, null));
        addRow(SettingsUi.pref(this, -1, "图标文字",
                "始终显示，固定图标下方单行", null, null));
        addRow(SettingsUi.pref(this, -1, "桌面模式",
                WorkspaceStore.classicMode(this)
                        ? "经典：所有应用都放在桌面上"
                        : "抽屉：桌面只放常用应用，上滑打开全部应用",
                null, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        chooseMode();
                    }
                }));
        addRow(SettingsUi.pref(this, -1, "导航键布局",
                WorkspaceStore.navReversed(this)
                        ? "多任务 | 主页 | 返回"
                        : "返回 | 主页 | 多任务",
                null, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        chooseNavLayout();
                    }
                }));
        addRow(SettingsUi.pref(this, -1, "导航方式",
                WorkspaceStore.gestureNav(this)
                        ? "手势：左右边缘内滑返回、底部上滑主页、上滑悬停多任务"
                        : "三键导航",
                null, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        chooseNavMode();
                    }
                }));

        header("设备");
        addRow(SettingsUi.pref(this, -1, "设备设置",
                "网络、显示、声音、应用、存储、系统", null, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            startActivity(new Intent(LauncherSettingsActivity.this,
                                    SettingsActivity.class));
                        } catch (Throwable ignored) {
                        }
                    }
                }));

        header("关于");
        addRow(SettingsUi.pref(this, -1, "版本",
                "C10H Launcher 2.0（AOSP 对齐版）", null, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        new AlertDialog.Builder(LauncherSettingsActivity.this)
                                .setTitle("C10H Launcher")
                                .setMessage("版本 2.0\n桌面布局：6 × 5 + 固定栏 7 槽\n"
                                        + "AOSP Launcher3 对齐（Android 9）")
                                .setPositiveButton("确定", null)
                                .show();
                    }
                }));
        addRow(SettingsUi.pref(this, -1, "恢复默认设置",
                "仅重置桌面设置项，不删除布局与应用", null, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        new AlertDialog.Builder(LauncherSettingsActivity.this)
                                .setTitle("恢复默认设置")
                                .setMessage("将自动加图标、通知圆点、导航条恢复为开启，"
                                        + "旋转恢复为关闭。桌面布局不变。")
                                .setPositiveButton("恢复默认",
                                        new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface d, int w) {
                                                WorkspaceStore.resetToDefaults(
                                                        LauncherSettingsActivity.this);
                                                notifyChanged();
                                                render();
                                            }
                                        })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                }));
        addRow(SettingsUi.pref(this, -1, "开源许可",
                "系统组件与自研实现说明", null, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        new AlertDialog.Builder(LauncherSettingsActivity.this)
                                .setTitle("开源许可")
                                .setMessage("本桌面为自研实现，不包含 AOSP Launcher3 二进制；"
                                        + "交互与尺寸参照 Android 9 AOSP Settings / Launcher3。")
                                .setPositiveButton("确定", null)
                                .show();
                    }
                }));

        View spacer = new View(this);
        content.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, SettingsUi.dp(this, 16)));
    }

    private void chooseMode() {
        final String[] items = {"抽屉（桌面只放常用应用）", "经典（所有应用在桌面）"};
        new AlertDialog.Builder(this)
                .setTitle("桌面模式")
                .setSingleChoiceItems(items,
                        WorkspaceStore.classicMode(this) ? 1 : 0,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                WorkspaceStore.setClassicMode(
                                        LauncherSettingsActivity.this, w == 1);
                                d.dismiss();
                                notifyChanged();
                                render();
                            }
                        })
                .show();
    }

    private void chooseNavLayout() {
        final String[] items = {"返回 | 主页 | 多任务", "多任务 | 主页 | 返回"};
        new AlertDialog.Builder(this)
                .setTitle("导航键布局")
                .setSingleChoiceItems(items,
                        WorkspaceStore.navReversed(this) ? 1 : 0,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                WorkspaceStore.setNavReversed(
                                        LauncherSettingsActivity.this, w == 1);
                                d.dismiss();
                                notifyChanged();
                                render();
                            }
                        })
                .show();
    }

    private void chooseNavMode() {
        final String[] items = {"三键导航", "手势导航"};
        new AlertDialog.Builder(this)
                .setTitle("导航方式")
                .setSingleChoiceItems(items,
                        WorkspaceStore.gestureNav(this) ? 1 : 0,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                WorkspaceStore.setGestureNav(
                                        LauncherSettingsActivity.this, w == 1);
                                d.dismiss();
                                notifyChanged();
                                render();
                            }
                        })
                .show();
    }

    private void header(String text) {
        content.addView(SettingsUi.header(this, text), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        lastWasRow = false;
    }

    private void addRow(View v) {
        if (lastWasRow) {
            content.addView(SettingsUi.divider(this));
        }
        content.addView(v, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        lastWasRow = true;
    }

    private void notifyChanged() {
        try {
            Intent i = new Intent("com.c10h.launcher.action.SETTINGS_CHANGED");
            i.putExtra("add", WorkspaceStore.addIconToHome(this));
            i.putExtra("dots", WorkspaceStore.notificationDots(this));
            i.putExtra("nav", WorkspaceStore.navEnabled(this));
            i.putExtra("rotate", WorkspaceStore.allowRotation(this));
            i.putExtra("classic", WorkspaceStore.classicMode(this));
            i.putExtra("navReversed", WorkspaceStore.navReversed(this));
            i.putExtra("gesture", WorkspaceStore.gestureNav(this));
            sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }
}
