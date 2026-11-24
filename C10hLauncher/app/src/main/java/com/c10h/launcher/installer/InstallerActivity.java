package com.c10h.launcher;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * AOSP 风格安装器（规格 §8.1，:installer 进程）：确认页 → 进度页 → 结果页。
 * 入口：Intent VIEW(content://|file://) + archive mime；或文件管理 file Uri。
 * 内核：PackageInstaller.Session（open→write→fsync→commit），状态广播回调。
 */
public class InstallerActivity extends Activity {

    private static final String PKG = "com.c10h.launcher";

    private LinearLayout body;
    private TextView stage;
    private String srcPath;
    private PackageInfo pi;
    private boolean receiverOn;

    private final BroadcastReceiver commitReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                        PackageInstaller.STATUS_FAILURE);
                if (status == PackageInstaller.STATUS_SUCCESS) {
                    showResult(true, null);
                } else if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent ca = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (ca != null) {
                        ca.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(ca);
                    }
                } else {
                    showResult(false, intent.getStringExtra(
                            PackageInstaller.EXTRA_STATUS_MESSAGE));
                }
            } catch (Throwable t) {
                showResult(false, String.valueOf(t));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            buildShell();
            resolveSource();
            registerCommitReceiver();
        } catch (Throwable t) {
            fail("安装器初始化失败：" + t);
        }
    }

    private void registerCommitReceiver() {
        if (!receiverOn) {
            try {
                registerReceiver(commitReceiver,
                        new IntentFilter(PackageInstaller.ACTION_SESSION_COMMITTED));
                receiverOn = true;
            } catch (Throwable ignored) {
            }
        }
    }

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFFFFFFF);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(0xFF2196F3);
        int pad = dp(14);
        top.setPadding(pad, dp(8), pad, dp(8));
        TextView title = new TextView(this);
        title.setText("安装包");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        top.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView cancel = new TextView(this);
        cancel.setText("✕");
        cancel.setTextColor(0xFFFFFFFF);
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        cancel.setPadding(dp(14), dp(4), dp(14), dp(4));
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        top.addView(cancel);
        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView sv = new ScrollView(this);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(14), dp(18), dp(14));
        sv.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        stage = new TextView(this);
        stage.setTextColor(0xFF666666);
        stage.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        stage.setPadding(dp(18), dp(4), dp(18), dp(10));
        root.addView(stage);
        setContentView(root);
    }

    private void resolveSource() {
        if (getIntent() != null) {
            String p = getIntent().getStringExtra("apk_path");
            if (p != null && !p.isEmpty()) {
                srcPath = p;
                parseApk();
                return;
            }
        }
        Uri uri = getIntent() != null ? getIntent().getData() : null;
        if (uri == null) {
            fail("未提供 APK 来源（需 VIEW intent、file Uri 或 apk_path）");
            return;
        }
        try {
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                srcPath = uri.getPath();
                parseApk();
                return;
            }
            srcPath = getCacheDir() + "/incoming.apk";
            InputStream in = getContentResolver().openInputStream(uri);
            OutputStream out = new java.io.FileOutputStream(srcPath);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.close();
            in.close();
            parseApk();
        } catch (Throwable t) {
            fail("读取安装包失败：" + t);
        }
    }

    private void parseApk() {
        try {
            pi = getPackageManager().getPackageArchiveInfo(srcPath, 0);
            if (pi == null) {
                fail("无法解析 APK（文件损坏或非安装包）");
                return;
            }
            ApplicationInfo ai = pi.applicationInfo;
            ai.sourceDir = srcPath;
            ai.publicSourceDir = srcPath;
            showConfirm();
        } catch (Throwable t) {
            fail("解析失败：" + t);
        }
    }

    private String srcLabel() {
        try {
            CharSequence s = pi.applicationInfo.loadLabel(getPackageManager());
            return s == null ? String.valueOf(pi.packageName) : s.toString();
        } catch (Throwable t) {
            return String.valueOf(pi.packageName);
        }
    }

    private void showConfirm() {
        body.removeAllViews();
        stage.setText("来源：本机文件");

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView ic = new ImageView(this);
        try {
            ic.setImageDrawable(pi.applicationInfo.loadIcon(getPackageManager()));
        } catch (Throwable ignored) {
        }
        head.addView(ic, new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView nm = new TextView(this);
        nm.setText(srcLabel());
        nm.setTextColor(0xFF212121);
        nm.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        info.addView(nm);
        TextView ver = new TextView(this);
        ver.setText("版本 " + (pi.versionName == null ? "?" : pi.versionName)
                + "  ·  " + pi.packageName);
        ver.setTextColor(0xFF757575);
        ver.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        info.addView(ver);
        head.addView(info, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        body.addView(head);

        TextView q = new TextView(this);
        q.setText("是否安装此应用？");
        q.setTextColor(0xFF424242);
        q.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        q.setPadding(0, dp(14), 0, dp(6));
        body.addView(q);

        String[] perms = pi.requestedPermissions != null ? pi.requestedPermissions : new String[0];
        TextView pl = new TextView(this);
        pl.setText(perms.length == 0 ? "此应用不请求任何权限"
                : "请求的权限（" + perms.length + "）：");
        pl.setTextColor(0xFF757575);
        pl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        body.addView(pl);
        StringBuilder sb = new StringBuilder();
        for (String p : perms) {
            sb.append("  · ").append(p).append("\n");
        }
        TextView plist = new TextView(this);
        plist.setText(sb.toString());
        plist.setTextColor(0xFF9E9E9E);
        plist.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        body.addView(plist);

        if (!canInstallPackages()) {
            TextView warn = new TextView(this);
            warn.setText("未授权安装未知应用：将先跳转系统授权页，返回后点安装继续。");
            warn.setTextColor(0xFFE65100);
            warn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            warn.setPadding(0, dp(10), 0, 0);
            body.addView(warn);
        }

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        btns.setPadding(0, dp(16), 0, 0);
        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setTextColor(0xFF2196F3);
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        cancel.setPadding(dp(16), dp(8), dp(16), dp(8));
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        btns.addView(cancel);
        TextView ok = new TextView(this);
        ok.setText("安装");
        ok.setTextColor(0xFFFFFFFF);
        ok.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        ok.setBackgroundColor(0xFF2196F3);
        ok.setPadding(dp(22), dp(8), dp(22), dp(8));
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                beginInstall();
            }
        });
        btns.addView(ok);
        body.addView(btns);
    }

    private boolean canInstallPackages() {
        try {
            if (Build.VERSION.SDK_INT < 26) {
                return true;
            }
            return getPackageManager().canRequestPackageInstalls();
        } catch (Throwable t) {
            return false;
        }
    }

    private void beginInstall() {
        if (pi == null || srcPath == null) {
            fail("无安装源");
            return;
        }
        if (!canInstallPackages()) {
            try {
                startActivityForResult(new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + PKG)), 1003);
            } catch (Throwable t) {
                fail("无法打开「未知来源」授权页：" + t);
            }
            return;
        }
        showProgress();
        new Thread(new Runnable() {
            @Override
            public void run() {
                PackageInstaller.Session s = null;
                try {
                    PackageInstaller installer = getPackageManager().getPackageInstaller();
                    PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(
                            PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                    p.setAppPackageName(pi.packageName);
                    int sessionId = installer.createSession(p);
                    s = installer.openSession(sessionId);
                    OutputStream out = s.openWrite("base.apk", 0, -1);
                    InputStream in = new FileInputStream(srcPath);
                    byte[] buf = new byte[65536];
                    int n;
                    long total = 0;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        total += n;
                        final long ft = total;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                stage.setText("写入 " + humanSize(ft) + " …");
                            }
                        });
                    }
                    in.close();
                    s.fsync(out);
                    out.close();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            stage.setText("提交安装…");
                        }
                    });
                    s.commit(createPendingIntent().getIntentSender());
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            stage.setText("安装已提交，等待系统完成…");
                            body.removeAllViews();
                            TextView t = new TextView(InstallerActivity.this);
                            t.setText("正在由系统安装 " + srcLabel()
                                    + "…\n若桌面短暂重启，属正常现象。");
                            t.setTextColor(0xFF424242);
                            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                            t.setPadding(0, dp(30), 0, 0);
                            body.addView(t);
                        }
                    });
                } catch (Throwable t) {
                    final String msg = String.valueOf(t);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            showResult(false, msg);
                        }
                    });
                } finally {
                    if (s != null) {
                        try {
                            s.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }, "apk-install").start();
    }

    private PendingIntent createPendingIntent() {
        Intent i = new Intent(PackageInstaller.ACTION_SESSION_COMMITTED);
        i.setPackage(PKG);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        return PendingIntent.getBroadcast(this, 1004, i, flags);
    }

    private void showProgress() {
        body.removeAllViews();
        stage.setText("准备安装…");
        TextView t = new TextView(this);
        t.setText("正在安装 " + srcLabel() + "…");
        t.setTextColor(0xFF424242);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setPadding(0, dp(30), 0, 0);
        body.addView(t);
    }

    private void showResult(final boolean ok, String detail) {
        body.removeAllViews();
        stage.setText("");
        TextView t = new TextView(this);
        t.setTextColor(ok ? 0xFF2E7D32 : 0xFFD32F2F);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        t.setText(ok ? "应用安装完成：" + srcLabel()
                : "安装失败\n" + (detail == null ? "未知原因" : detail));
        t.setPadding(0, dp(24), 0, dp(8));
        body.addView(t);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        btns.setPadding(0, dp(18), 0, 0);
        TextView done = new TextView(this);
        done.setText(ok ? "完成" : "关闭");
        done.setTextColor(0xFFFFFFFF);
        done.setBackgroundColor(0xFF2196F3);
        done.setPadding(dp(24), dp(8), dp(24), dp(8));
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        btns.addView(done);
        if (ok) {
            TextView open = new TextView(this);
            open.setText("打开");
            open.setTextColor(0xFF2196F3);
            open.setPadding(dp(18), dp(8), dp(18), dp(8));
            open.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        Intent i = getPackageManager().getLaunchIntentForPackage(pi.packageName);
                        if (i != null) {
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(i);
                            finish();
                        } else {
                            toast("该应用无启动入口");
                        }
                    } catch (Throwable t2) {
                        toast("打开失败：" + t2);
                    }
                }
            });
            btns.addView(open);
        }
        body.addView(btns);
    }

    private void fail(String msg) {
        try {
            body.removeAllViews();
            TextView tv = new TextView(this);
            tv.setText(msg);
            tv.setTextColor(0xFFD32F2F);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            tv.setPadding(dp(18), dp(30), dp(18), 0);
            body.addView(tv);
            stage.setText("");
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        if (receiverOn) {
            try {
                unregisterReceiver(commitReceiver);
            } catch (Throwable ignored) {
            }
            receiverOn = false;
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1003 && canInstallPackages()) {
            beginInstall();
        }
    }

    private String humanSize(long b) {
        if (b < 1048576) {
            return String.format(Locale.US, "%.1f KB", b / 1024f);
        }
        return String.format(Locale.US, "%.1f MB", b / 1048576f);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }
}
