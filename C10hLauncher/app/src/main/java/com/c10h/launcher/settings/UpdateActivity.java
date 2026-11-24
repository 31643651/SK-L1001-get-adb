package com.c10h.launcher.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 应用更新（自研，不依赖 adb）：
 *  - 在线：填写 APK 直链，下载到私有目录，校验包名后交给自研安装器；
 *  - 本地：打开自研文件管理器，点 APK 即安装（U 盘/SD 卡均可）；
 *  - 安装使用 PackageInstaller.Session；平台签名 + INSTALL_PACKAGES 时静默，
 *    否则系统会弹自己的安装确认页（本机安装确认页正常，坏的是卸载页）。
 */
public class UpdateActivity extends Activity {

    private static final String PREFS = "c10h_launcher";
    private static final String KEY_URL = "update_url";

    private final Handler ui = new Handler(Looper.getMainLooper());

    private EditText urlEdit;
    private ProgressBar progress;
    private TextView status;
    private TextView downloadBtn;
    private boolean busy;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        build();
    }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(SettingsUi.BG);
        root.addView(SettingsUi.appBar(this, "应用更新", new Runnable() {
            @Override
            public void run() {
                if (!busy) {
                    finish();
                }
            }
        }), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(SettingsUi.BG);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, SettingsUi.dp(this, 24));
        scroll.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 当前版本
        content.addView(SettingsUi.header(this, "当前版本"));
        content.addView(SettingsUi.pref(this, -1, "已安装版本",
                versionText(), null, null));

        // 在线更新
        content.addView(SettingsUi.header(this, "在线更新"));
        content.addView(SettingsUi.body(this,
                "填写 APK 的直链地址（http/https）。下载完成后会校验包名，"
                        + "再打开自研安装器；平台签名下安装是静默的。"));

        urlEdit = new EditText(this);
        urlEdit.setSingleLine(true);
        urlEdit.setHint("http://192.168.0.170:8000/C10hLauncher.apk");
        urlEdit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        urlEdit.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_URI);
        urlEdit.setText(prefs().getString(KEY_URL, ""));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius(SettingsUi.dp(this, 8));
        bg.setStroke(1, 0xFFDADCE0);
        urlEdit.setBackground(bg);
        urlEdit.setPadding(SettingsUi.dp(this, 12), SettingsUi.dp(this, 8),
                SettingsUi.dp(this, 12), SettingsUi.dp(this, 8));
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ulp.leftMargin = SettingsUi.dp(this, 18);
        ulp.rightMargin = SettingsUi.dp(this, 18);
        ulp.topMargin = SettingsUi.dp(this, 8);
        content.addView(urlEdit, ulp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(SettingsUi.dp(this, 18), SettingsUi.dp(this, 12),
                SettingsUi.dp(this, 18), 0);
        downloadBtn = SettingsUi.actionButton(this, "下载并安装", true,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        download();
                    }
                });
        row.addView(downloadBtn);
        TextView save = SettingsUi.flatButton(this, "保存地址", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs().edit().putString(KEY_URL,
                        urlEdit.getText().toString().trim()).apply();
                toast("地址已保存");
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = SettingsUi.dp(this, 10);
        row.addView(save, slp);
        content.addView(row);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, SettingsUi.dp(this, 4));
        plp.leftMargin = SettingsUi.dp(this, 18);
        plp.rightMargin = SettingsUi.dp(this, 18);
        plp.topMargin = SettingsUi.dp(this, 12);
        content.addView(progress, plp);

        status = SettingsUi.body(this, "");
        status.setPadding(SettingsUi.dp(this, 18), SettingsUi.dp(this, 8),
                SettingsUi.dp(this, 18), 0);
        content.addView(status);

        // 本地更新
        content.addView(SettingsUi.header(this, "本地更新"));
        content.addView(SettingsUi.body(this,
                "把 APK 放到 U 盘或 SD 卡，点下方按钮用自研文件管理器选择文件；"
                        + "点击 APK 后进入自研安装器。"));
        LinearLayout localRow = new LinearLayout(this);
        localRow.setPadding(SettingsUi.dp(this, 18), SettingsUi.dp(this, 10),
                SettingsUi.dp(this, 18), 0);
        localRow.addView(SettingsUi.actionButton(this, "选择本地 APK", false,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            Intent i = new Intent();
                            i.setClassName(UpdateActivity.this,
                                    "com.c10h.launcher.FileBrowserActivity");
                            startActivity(i);
                        } catch (Throwable t) {
                            toast("文件管理器不可用：" + t);
                        }
                    }
                }));
        content.addView(localRow);

        setContentView(root);
    }

    private String versionText() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return (pi.versionName == null ? "?" : pi.versionName)
                    + "（versionCode " + pi.versionCode + "）";
        } catch (Throwable t) {
            return "未知";
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void download() {
        if (busy) {
            return;
        }
        final String url = urlEdit.getText().toString().trim();
        if (url.isEmpty()) {
            toast("请先填写 APK 地址");
            return;
        }
        prefs().edit().putString(KEY_URL, url).apply();
        setBusy(true);
        status.setText("连接中…");
        progress.setProgress(0);
        progress.setVisibility(View.VISIBLE);

        new Thread(new Runnable() {
            @Override
            public void run() {
                File out = new File(getFilesDir(), "update.apk");
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    conn.setInstanceFollowRedirects(true);
                    conn.connect();
                    int code = conn.getResponseCode();
                    if (code != HttpURLConnection.HTTP_OK) {
                        throw new java.io.IOException("HTTP " + code);
                    }
                    final int total = conn.getContentLength();
                    InputStream in = conn.getInputStream();
                    FileOutputStream fo = new FileOutputStream(out);
                    byte[] buf = new byte[65536];
                    long done = 0;
                    int n;
                    int lastPct = -1;
                    while ((n = in.read(buf)) > 0) {
                        fo.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            final int pct = (int) (done * 100 / total);
                            if (pct != lastPct && pct % 2 == 0) {
                                lastPct = pct;
                                ui.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        progress.setProgress(pct);
                                        status.setText("下载中 " + pct + "%");
                                    }
                                });
                            }
                        }
                    }
                    fo.flush();
                    fo.close();
                    in.close();

                    final PackageInfo pi = getPackageManager()
                            .getPackageArchiveInfo(out.getAbsolutePath(), 0);
                    if (pi == null) {
                        throw new java.io.IOException("下载内容不是有效 APK");
                    }
                    if (!getPackageName().equals(pi.packageName)) {
                        throw new java.io.IOException("包名不匹配：" + pi.packageName);
                    }
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            setBusy(false);
                            status.setText("下载完成：" + (pi.versionName == null
                                    ? "?" : pi.versionName));
                            confirmInstall(out, pi);
                        }
                    });
                } catch (final Throwable t) {
                    if (conn != null) {
                        try {
                            conn.disconnect();
                        } catch (Throwable ignored) {
                        }
                    }
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            setBusy(false);
                            status.setText("下载失败：" + t);
                        }
                    });
                }
            }
        }, "c10h-update").start();
    }

    private void confirmInstall(final File apk, PackageInfo pi) {
        String ver = pi.versionName == null ? "?" : pi.versionName;
        new AlertDialog.Builder(this)
                .setTitle("安装更新")
                .setMessage("已下载版本 " + ver + "（versionCode " + pi.versionCode
                        + "）。\n安装会重启桌面，完成后自动回到主屏幕。")
                .setPositiveButton("安装", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        launchInstaller(apk);
                    }
                })
                .setNegativeButton("稍后", null)
                .show();
    }

    @Override
    protected void onPause() {
        hideKeyboard();
        super.onPause();
    }

    private void hideKeyboard() {
        try {
            if (urlEdit != null) {
                urlEdit.clearFocus();
            }
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(
                            INPUT_METHOD_SERVICE);
            if (urlEdit != null) {
                imm.hideSoftInputFromWindow(urlEdit.getWindowToken(), 0);
            }
        } catch (Throwable ignored) {
        }
    }

    private void launchInstaller(File apk) {
        hideKeyboard();
        try {
            Intent i = new Intent();
            i.setClassName(this, "com.c10h.launcher.InstallerActivity");
            i.putExtra("apk_path", apk.getAbsolutePath());
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            toast("已交给安装器");
        } catch (Throwable t) {
            toast("安装器不可用：" + t);
        }
    }

    private void setBusy(boolean b) {
        busy = b;
        if (downloadBtn != null) {
            downloadBtn.setEnabled(!b);
            downloadBtn.setAlpha(b ? 0.5f : 1f);
        }
        if (!b) {
            progress.setVisibility(View.GONE);
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
