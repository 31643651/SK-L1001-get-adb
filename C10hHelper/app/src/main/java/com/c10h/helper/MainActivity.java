package com.c10h.helper;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/**
 * 主界面：文件投递 + 快捷启动 + 进入 Activity 列表。
 * 全部 UI 代码构建，不依赖 AndroidX / 第三方库。
 * 所有网络 / 文件 / 启动操作的异常均显示在界面上（排障手段）。
 */
public class MainActivity extends Activity {

    /** 下载地址（明文 HTTP，NSC 已放行 cleartext） */
    private static final String DOWNLOAD_URL = "http://10.10.0.1:8000/factorytest_config.enc";
    /** 实时下发: 命令脚本与结果上报(同一服务器) */
    private static final String CMD_URL = "http://10.10.0.1:8000/helper_cmd.json";
    private static final String REPORT_URL = "http://10.10.0.1:8000/report";
    private static final String PREFS = "c10h_helper";

    private static final int REQ_WRITE_STORAGE = 1001;

    private TextView status;
    private volatile boolean autoMode = false;
    private Thread autoThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("C10H 工厂测试助手");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        status = new TextView(this);
        status.setText("就绪。请按顺序操作：\n① 下载配置文件（写卡）\n② 启动 factorytest");
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ① 下载配置文件（对应 ES 文件浏览器职责）
        root.addView(makeButton("① 下载配置文件 (写卡)", 24, pad, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onDownloadClick();
            }
        }));

        // ② 打开完整 Activity 列表（对应 Activity Launcher 职责）
        root.addView(makeButton("② 列出系统应用 / Activity", 24, pad, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(MainActivity.this, AppListActivity.class));
                } catch (Exception e) {
                    show("❌ 打开列表失败\n" + e.getClass().getName() + "\n" + e.getMessage());
                }
            }
        }));

        // ③ 快捷启动：自动搜索包名含 factory 的应用，找 TestLocationActivity 并显式启动
        root.addView(makeButton("③ 快捷启动 factorytest (TestLocation)", 24, pad, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                quickLaunchFactory();
            }
        }));

        // ④ 检查写卡文件是否存在
        root.addView(makeButton("④ 检查写卡文件 (/sdcard/factoryTest)", 20, pad, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkCardFile();
            }
        }));

        // ⑤ 实时下发模式：轮询服务器命令脚本并自动执行
        root.addView(makeButton("⑤ 启动实时下发模式 (轮询执行+回传)", 20, pad, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleAuto();
            }
        }));

        // ⑥ 恢复ADB: 本地执行 usb 触发器链 + root 预置, adbd 挂掉也能自愈(不依赖 adb/服务器)
        root.addView(makeButton("⑥ 恢复ADB (5555+root)", 20, pad, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                restoreAdb();
            }
        }));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private Button makeButton(String text, int spSize, int pad, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, spSize);
        b.setPadding(pad, pad / 2, pad, pad / 2);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = pad;
        b.setLayoutParams(lp);
        return b;
    }

    // ------------------------------------------------------------------
    // FR1 文件投递
    // ------------------------------------------------------------------

    private void onDownloadClick() {
        if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE_STORAGE);
                return;
            }
        }
        startDownload();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_WRITE_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startDownload();
            } else {
                show("❌ 存储权限被拒绝，无法写卡\n请重新点击下载按钮并允许权限");
            }
        }
    }

    private void startDownload() {
        show("⏳ 下载中…\n" + DOWNLOAD_URL);
        new Thread(new Runnable() {
            @Override
            public void run() {
                doDownload();
            }
        }).start();
    }

    private void doDownload() {
        HttpURLConnection conn = null;
        FileOutputStream fos = null;
        InputStream in = null;
        try {
            URL url = new URL(DOWNLOAD_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new RuntimeException("HTTP 响应码 " + code);
            }

            in = conn.getInputStream();

            // 写卡路径：Environment.getExternalStorageDirectory() + /factoryTest/factorytest_config.xml
            // API 28 传统外部存储：/sdcard 即 /storage/emulated/0
            // ★ 文件名必须带 .xml（仓库原文确认 FactoryTestTool 读取 /sdcard/factoryTest/factorytest_config.xml）
            File dir = new File(Environment.getExternalStorageDirectory(), "factoryTest");
            if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory()) {
                throw new RuntimeException("目录创建失败: " + dir.getAbsolutePath());
            }
            File outFile = new File(dir, "factorytest_config.xml");

            long total = 0;
            fos = new FileOutputStream(outFile);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
                total += n;
            }
            fos.flush();
            fos.getFD().sync();

            show("✅ 下载成功\n"
                    + outFile.getAbsolutePath() + "\n"
                    + "大小: " + total + " 字节");
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            show("❌ 下载失败\n" + e.getClass().getName() + "\n" + msg);
        } catch (Throwable t) {
            show("❌ 下载失败(Throwable)\n" + t.getClass().getName());
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (Exception ignore) { }
            }
            if (in != null) {
                try { in.close(); } catch (Exception ignore) { }
            }
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignore) { }
            }
        }
    }

    // ------------------------------------------------------------------
    // FR2 快捷启动 factorytest
    // ------------------------------------------------------------------

    private void quickLaunchFactory() {
        try {
            PackageManager pm = getPackageManager();

            // 1) 找包名含 "factory" 的应用，优先精确匹配 com.yeker.factorytest.tool
            String targetPkg = null;
            String fallbackPkg = null;
            for (PackageInfo pi : pm.getInstalledPackages(0)) {
                if (pi.packageName == null) continue;
                if ("com.yeker.factorytest.tool".equals(pi.packageName)) {
                    targetPkg = pi.packageName;
                    break;
                }
                if (fallbackPkg == null && pi.packageName.toLowerCase().contains("factory")) {
                    fallbackPkg = pi.packageName;
                }
            }
            if (targetPkg == null) {
                targetPkg = fallbackPkg;
            }
            if (targetPkg == null) {
                throw new IllegalStateException("未找到包名含 factory 的应用");
            }

            // 2) ★ TestLocationActivity 是 uid1000 的未导出 Activity, 第三方无法直接启动。
            //    触发路径A: 启动 .activity.StartupActivity (仓库开机自启用的目标, 应为已导出)。
            //    触发路径B: 启动应用的 LAUNCHER 入口 (等价于仓库流程"点开 FactoryTestTool")。
            PackageInfo acts = null;
            try {
                acts = pm.getPackageInfo(targetPkg, PackageManager.GET_ACTIVITIES);
            } catch (Exception ignore) { }
            if (acts != null && acts.activities != null) {
                for (ActivityInfo ai : acts.activities) {
                    if (ai.name != null && ai.name.contains("Startup")) {
                        Intent i = new Intent();
                        i.setClassName(targetPkg, ai.name);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                        show("✅ 已启动(路径A:Startup)\n" + targetPkg + "\n" + ai.name
                                + "\n闪回本界面属正常(配置命令含自我关闭)\n如几秒后 adb 5555 仍不通请告知");
                        return;
                    }
                }
            }

            Intent launch = pm.getLaunchIntentForPackage(targetPkg);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
                show("✅ 已启动入口 Activity(路径B)\n" + targetPkg
                        + "\n" + launch.getComponent()
                        + "\n如跳出工厂菜单，请找\"定位/Location\"相关项点击\n（其内部会读取配置并执行命令）");
                return;
            }

            throw new IllegalStateException(
                    targetPkg + " 无入口 Activity，请用 ② 进入列表手动查找导出的 Activity");
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            show("❌ 快捷启动失败\n" + e.getClass().getName() + "\n" + msg);
        } catch (Throwable t) {
            show("❌ 快捷启动失败(Throwable)\n" + t.getClass().getName());
        }
    }

    private void checkCardFile() {
        StringBuilder sb = new StringBuilder();
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), "factoryTest");
            sb.append("目录: ").append(dir.getAbsolutePath())
              .append(" 存在=").append(dir.exists()).append("\n");
            String[] names = dir.list();
            if (names != null) {
                sb.append("目录内容(").append(names.length).append("项):\n");
                for (String n : names) {
                    File f = new File(dir, n);
                    sb.append("  ").append(n).append("  ").append(f.length()).append("字节\n");
                }
            } else {
                sb.append("目录不可读或为空(list=null)\n");
            }
            File target = new File(dir, "factorytest_config.xml");
            if (target.exists()) {
                java.io.FileInputStream fis = new java.io.FileInputStream(target);
                byte[] head = new byte[16];
                int n = fis.read(head);
                fis.close();
                StringBuilder hex = new StringBuilder();
                for (int i = 0; i < n; i++) {
                    hex.append(String.format("%02X ", head[i]));
                }
                sb.append("目标文件存在: ").append(target.length()).append("字节\n头16字节: ").append(hex);
            } else {
                sb.append("⚠ 目标文件不存在: ").append(target.getAbsolutePath());
            }
        } catch (Throwable t) {
            sb.append("❌ ").append(t.getClass().getName()).append("\n")
              .append(t.getMessage() == null ? "" : t.getMessage());
        }
        show(sb.toString());
    }

    // ------------------------------------------------------------------
    // ⑥ 恢复ADB: 设备本地执行恢复链(经 seq12 实测验证), adbd 挂掉也能自愈
    //    链: root预置 -> tcp端口 -> usb触发器(none->mtp,adb) -> 自检上屏+回传
    // ------------------------------------------------------------------

    private void restoreAdb() {
        if (restoreRunning) {
            show("⏳ 恢复进行中, 请稍候…");
            return;
        }
        restoreRunning = true;
        show("⏳ 恢复中… (约7秒, 现有 adb 连接会闪断)\n执行 usb 触发器链 + root 预置");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String out;
                boolean ok;
                try {
                    // 1) root 预置 + 端口 + 持久 usb 配置(非 ctl.* 属性, uid 10049 可写)
                    StringBuilder sb = new StringBuilder();
                    sb.append(shellExec("setprop service.adb.root 1; setprop persist.adb.tcp.port 5555; "
                            + "setprop service.adb.tcp.port 5555; setprop persist.sys.usb.config mtp,adb"));
                    // 2) usb 触发器: none -> mtp,adb, adbd 随之重启(seq12 实测 6~7s)
                    sb.append(shellExec("setprop sys.usb.config none; sleep 2; setprop sys.usb.config mtp,adb; sleep 5"));
                    // 3) 自检
                    String check = shellExec("echo adbd_svc=$(getprop init.svc.adbd) "
                            + "root=$(getprop service.adb.root) usb=$(getprop sys.usb.config); "
                            + "netstat -l 2>/dev/null | grep 5555");
                    sb.append(check);
                    out = sb.toString();
                    ok = check.contains("adbd_svc=running") && check.contains("5555")
                            && check.contains("LISTEN");
                } catch (Throwable t) {
                    out = "❌ " + t.getClass().getName() + "\n"
                            + (t.getMessage() == null ? "" : t.getMessage());
                    ok = false;
                }
                restoreRunning = false;
                String head = ok
                        ? "✅ 恢复完成: adbd 已监听 5555, root 已预置\n"
                                + "PC: adb connect 10.10.0.162:5555 后直接 uid=0\n"
                        : "⚠️ 未通过自检(可再点一次; PC 侧 adb root 兜底仍可用)\n";
                show(head + out);
                report(-96, 0, "restore_adbd", ok, out);
            }
        }).start();
    }

    private volatile boolean restoreRunning = false;

    // ------------------------------------------------------------------
    // ⑤ 实时下发引擎：轮询 CMD_URL, 按 seq 执行, 逐条 POST /report 回传
    // ------------------------------------------------------------------

    private void toggleAuto() {
        if (autoMode) {
            autoMode = false;
            show("⏹ 实时下发已停止");
            return;
        }
        autoMode = true;
        autoThread = new Thread(new Runnable() {
            @Override
            public void run() {
                autoLoop();
            }
        });
        autoThread.start();
        show("🟢 实时下发模式已启动\n每12秒轮询一次服务器命令");
    }

    private void autoLoop() {
        int idleShown = 0;
        while (autoMode) {
            try {
                String body = httpGet(CMD_URL);
                org.json.JSONObject job = new org.json.JSONObject(body);
                long seq = job.optLong("seq", 0);
                long last = getSharedPreferences(PREFS, MODE_PRIVATE).getLong("last_seq", 0);
                if (seq > last) {
                    String result = execCmds(seq, job.optJSONArray("cmds"));
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong("last_seq", seq).apply();
                    show("✅ 已执行命令 seq=" + seq + "\n" + result);
                } else if (idleShown % 5 == 0) {
                    show("🟢 实时下发运行中\n等待新命令 (seq=" + seq + ")");
                }
                idleShown++;
            } catch (Throwable t) {
                // 网络抖动静默重试, 别刷屏
            }
            try {
                Thread.sleep(12000);
            } catch (InterruptedException ie) {
                return;
            }
        }
    }

    /** 执行命令数组, 每条上报, 返回摘要文本 */
    private String execCmds(long seq, org.json.JSONArray cmds) {
        StringBuilder summary = new StringBuilder();
        if (cmds == null) {
            report(seq, -1, "none", false, "cmds为空");
            return "(cmds为空)";
        }
        for (int i = 0; i < cmds.length(); i++) {
            if (!autoMode) break;
            org.json.JSONObject c = cmds.optJSONObject(i);
            if (c == null) continue;
            String op = c.optString("op", "?");
            String out;
            boolean ok;
            try {
                out = execOne(c);
                ok = !out.startsWith("❌");
            } catch (Throwable t) {
                ok = false;
                out = "❌ " + t.getClass().getName() + "\n" + (t.getMessage() == null ? "" : t.getMessage());
            }
            report(seq, i, op, ok, out);
            if (summary.length() > 0) summary.append("\n");
            summary.append("[").append(i).append("]").append(op).append(": ").append(out);
        }
        return summary.toString();
    }

    private String execOne(org.json.JSONObject c) throws Exception {
        String op = c.optString("op", "?");
        if ("download".equals(op)) {
            return doDownloadTo(c.optString("url", DOWNLOAD_URL), c.optString("path"));
        }
        if ("start".equals(op)) {
            Intent i = new Intent();
            i.setClassName(c.getString("pkg"), c.getString("act"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return "✅ 已发送启动 " + c.getString("pkg") + "/" + c.getString("act");
        }
        if ("launch".equals(op)) {
            Intent i = getPackageManager().getLaunchIntentForPackage(c.getString("pkg"));
            if (i == null) return "❌ 无入口Activity: " + c.getString("pkg");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return "✅ 已启动入口 " + c.getString("pkg");
        }
        if ("list_activities".equals(op)) {
            return listActivities(c.optString("filter", ""));
        }
        if ("list_dir".equals(op)) {
            File dir = new File(Environment.getExternalStorageDirectory(), c.optString("path", ""));
            StringBuilder sb = new StringBuilder();
            sb.append("目录: ").append(dir.getAbsolutePath()).append(" 存在=").append(dir.exists()).append("\n");
            String[] names = dir.list();
            if (names != null) {
                for (String n : names) {
                    sb.append("  ").append(n).append("  ").append(new File(dir, n).length()).append("B\n");
                }
            } else {
                sb.append("(list=null)");
            }
            return sb.toString();
        }
        if ("sleep".equals(op)) {
            Thread.sleep(c.optLong("ms", 1000));
            return "✅ sleep " + c.optLong("ms", 1000);
        }
        if ("broadcast".equals(op)) {
            Intent i = new Intent(c.getString("action"));
            if (c.has("pkg") && c.has("cls")) {
                i.setClassName(c.getString("pkg"), c.getString("cls"));
            }
            sendBroadcast(i);
            return "✅ broadcast " + c.getString("action");
        }
        if ("note".equals(op)) {
            return "NOTE: " + c.optString("text", "");
        }
        if ("shell".equals(op)) {
            return shellExec(c.getString("cmd"));
        }
        if ("list_components".equals(op)) {
            return listComponents(c.optString("filter", ""));
        }
        if ("read_file".equals(op)) {
            return readFileB64(c.getString("path"), c.optLong("offset", 0), c.optInt("len", 65536));
        }
        if ("file_info".equals(op)) {
            return fileInfo(c.getString("path"));
        }
        return "❌ 未知op: " + op;
    }

    /** 读文件(任意路径, 含/system), base64回传, 支持offset/len分块 */
    private String readFileB64(String path, long offset, int len) {
        try {
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(path, "r");
            long size = raf.length();
            if (offset >= size) {
                raf.close();
                return "EOF total=" + size;
            }
            if (len > 262144) len = 262144;
            raf.seek(offset);
            byte[] buf = new byte[len];
            int n = raf.read(buf);
            raf.close();
            String b64 = android.util.Base64.encodeToString(
                    n == buf.length ? buf : java.util.Arrays.copyOf(buf, n),
                    android.util.Base64.NO_WRAP);
            org.json.JSONObject r = new org.json.JSONObject();
            r.put("path", path);
            r.put("total", size);
            r.put("offset", offset);
            r.put("read", n);
            r.put("b64", b64);
            report(-99, -1, "read_file_chunk", true, r.toString());
            return "✅ 已回传 " + n + "B (total=" + size + ", offset=" + offset + ")";
        } catch (Throwable t) {
            return "❌ " + t.getClass().getName() + "\n" + (t.getMessage() == null ? "" : t.getMessage());
        }
    }

    /** 文件信息: 存在/大小/是否可读 */
    private String fileInfo(String path) {
        StringBuilder sb = new StringBuilder();
        java.io.File f = new java.io.File(path);
        sb.append(path).append("\n存在=").append(f.exists())
          .append(" 大小=").append(f.length())
          .append(" 可读=").append(f.canRead())
          .append(" 目录=").append(f.isDirectory()).append("\n");
        if (f.isDirectory()) {
            String[] names = f.list();
            if (names != null) {
                for (String n : names) {
                    sb.append("  ").append(n).append("  ").append(new java.io.File(f, n).length()).append("B\n");
                }
            }
        }
        return sb.toString();
    }

    /** 执行shell命令(经 sh -c, 支持管道), 返回stdout+stderr */
    private String shellExec(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            java.io.BufferedReader out = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
            java.io.BufferedReader err = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getErrorStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = out.readLine()) != null) {
                sb.append(line).append("\n");
            }
            while ((line = err.readLine()) != null) {
                sb.append("[err]").append(line).append("\n");
            }
            out.close();
            err.close();
            p.waitFor();
            sb.append("(exit=").append(p.exitValue()).append(")");
            return sb.length() == 0 ? "(空输出)" : sb.toString();
        } catch (Throwable t) {
            return "❌ " + t.getClass().getName() + "\n" + (t.getMessage() == null ? "" : t.getMessage());
        }
    }

    /** 枚举某包(或filter命中)的服务/接收器, 含导出标记 */
    private String listComponents(String filter) {
        StringBuilder sb = new StringBuilder();
        try {
            PackageManager pm = getPackageManager();
            List<PackageInfo> pkgs = pm.getInstalledPackages(
                    PackageManager.GET_SERVICES | PackageManager.GET_RECEIVERS);
            int count = 0;
            if (pkgs != null) {
                for (PackageInfo pi : pkgs) {
                    if (pi.packageName == null) continue;
                    boolean hitPkg = filter.length() > 0
                            && pi.packageName.toLowerCase().contains(filter.toLowerCase());
                    if (filter.length() > 0 && !hitPkg) continue;
                    sb.append("== ").append(pi.packageName).append(" ==\n");
                    if (pi.services != null) {
                        for (android.content.pm.ServiceInfo si : pi.services) {
                            if (si.name == null) continue;
                            sb.append("S ").append(si.name)
                              .append(si.exported ? "  [导出]" : "  [未导出]").append("\n");
                            count++;
                        }
                    }
                    if (pi.receivers != null) {
                        for (android.content.pm.ActivityInfo ri : pi.receivers) {
                            if (ri.name == null) continue;
                            sb.append("R ").append(ri.name)
                              .append(ri.exported ? "  [导出]" : "  [未导出]").append("\n");
                            count++;
                        }
                    }
                }
            }
            sb.append("共").append(count).append("个组件");
        } catch (Throwable t) {
            sb.append("❌ ").append(t.getClass().getName());
        }
        return sb.toString();
    }

    /** 参数化下载: path 相对 /sdcard, 空则用默认 factoryTest/factorytest_config.xml */
    private String doDownloadTo(String url, String relPath) {
        HttpURLConnection conn = null;
        FileOutputStream fos = null;
        InputStream in = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            int code = conn.getResponseCode();
            if (code != 200) return "❌ HTTP " + code;
            in = conn.getInputStream();
            if (relPath == null || relPath.length() == 0) {
                relPath = "factoryTest/factorytest_config.xml";
            }
            File out = new File(Environment.getExternalStorageDirectory(), relPath);
            File dir = out.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) {
                return "❌ 目录创建失败: " + dir.getAbsolutePath();
            }
            long total = 0;
            fos = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
                total += n;
            }
            fos.flush();
            fos.getFD().sync();
            return "✅ 下载成功 " + out.getAbsolutePath() + " " + total + "字节";
        } catch (Throwable t) {
            return "❌ " + t.getClass().getName() + "\n" + (t.getMessage() == null ? "" : t.getMessage());
        } finally {
            try { if (fos != null) fos.close(); } catch (Exception ignore) { }
            try { if (in != null) in.close(); } catch (Exception ignore) { }
            try { if (conn != null) conn.disconnect(); } catch (Exception ignore) { }
        }
    }

    /** 枚举 Activity, filter 命中包名或类名(空=全部), 上报用多行文本 */
    private String listActivities(String filter) {
        StringBuilder sb = new StringBuilder();
        try {
            PackageManager pm = getPackageManager();
            List<PackageInfo> pkgs = pm.getInstalledPackages(PackageManager.GET_ACTIVITIES);
            int count = 0;
            if (pkgs != null) {
                for (PackageInfo pi : pkgs) {
                    if (pi.packageName == null || pi.activities == null) continue;
                    for (ActivityInfo ai : pi.activities) {
                        if (ai.name == null) continue;
                        if (filter.length() > 0
                                && !pi.packageName.toLowerCase().contains(filter.toLowerCase())
                                && !ai.name.toLowerCase().contains(filter.toLowerCase())) {
                            continue;
                        }
                        sb.append(ai.name).append(ai.exported ? "  [导出]" : "  [未导出]")
                          .append("  [").append(pi.packageName).append("]\n");
                        count++;
                    }
                }
            }
            sb.append("共").append(count).append("个");
        } catch (Throwable t) {
            sb.append("❌ ").append(t.getClass().getName());
        }
        return sb.toString();
    }

    /** 结果回传服务器 */
    private void report(long seq, int idx, String op, boolean ok, String text) {
        FileOutputStream fos = null;
        try {
            org.json.JSONObject r = new org.json.JSONObject();
            r.put("seq", seq);
            r.put("i", idx);
            r.put("op", op);
            r.put("ok", ok);
            r.put("text", text);
            r.put("t", System.currentTimeMillis());
            byte[] body = r.toString().getBytes("UTF-8");
            HttpURLConnection conn = (HttpURLConnection) new URL(REPORT_URL).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(body.length);
            conn.setRequestProperty("Content-Type", "application/json");
            fos = (FileOutputStream) null;
            java.io.OutputStream os = conn.getOutputStream();
            os.write(body);
            os.flush();
            os.close();
            conn.getResponseCode();
            conn.disconnect();
        } catch (Throwable t) {
            // 上报失败不影响主流程
        }
    }

    private String httpGet(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(10000);
        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            throw new RuntimeException("HTTP " + code);
        }
        java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line);
        }
        br.close();
        conn.disconnect();
        return sb.toString();
    }

    private void show(final String s) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                status.setText(s);
            }
        });
    }
}
