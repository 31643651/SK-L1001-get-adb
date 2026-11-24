package com.c10h.launcher.block;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.widget.Toast;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.List;

/**
 * 屏蔽服务（补充文档 §4，:block 进程）：稀疏路由 TUN（假 DNS 段 + 黑名单 /32 + 0.0.0.0/32）；
 * 未命中路由流量不进 TUN 直连网卡（零转发）；崩溃/撤销 = fail-open 不断网。
 */
public class BlockVpnService extends VpnService {

    private static final String CH = "c10h_block";

    private ParcelFileDescriptor tun;
    private TunReader reader;
    /** 本次命令的导入条数；-1 表示非导入命令。 */
    private int lastImported = -1;

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        try {
            RuleStore.ensureSeed(this);
            foreground();
            lastImported = -1;
            if (i != null) {
                handle(i.getStringExtra("cmd"), i);
            }
            // 进程被系统重建/被覆盖安装杀掉后：按上次的期望状态恢复隧道。
            if (!isUp() && RuleStore.p(this).getBoolean(RuleStore.K_VPN, false)) {
                up();
            }
            // 广播实际状态，避免“开关显示开但 tun0 不存在”。
            RuleStore.p(this).edit().putBoolean(RuleStore.K_VPN, isUp()).apply();
            RuleStore.broadcast(this, lastImported);
        } catch (Throwable t) {
            Toast.makeText(this, "屏蔽服务异常：" + t, Toast.LENGTH_SHORT).show();
        }
        return START_STICKY;
    }

    private void foreground() {
        try {
            NotificationManager nm = (NotificationManager)
                    getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(
                        CH, "C10H 屏蔽", NotificationManager.IMPORTANCE_MIN));
            }
            PendingIntent pi = PendingIntent.getActivity(this, 0,
                    new Intent(this, com.c10h.launcher.settings.SettingsActivity.class)
                            .putExtra("page", "block"),
                    PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CH)
                    : new Notification.Builder(this);
            b.setSmallIcon(com.c10h.launcher.R.drawable.ic_launcher)
                    .setContentTitle("C10H 屏蔽运行中")
                    .setContentText("域名/IP 屏蔽与 ADB 守护")
                    .setContentIntent(pi)
                    .setOngoing(true);
            startForeground(1, b.build());
        } catch (Throwable ignored) {
        }
    }

    private void handle(String cmd, Intent i) {
        try {
            if ("on".equals(cmd)) {
                up();
            } else if ("off".equals(cmd)) {
                down();
            } else if ("toggle_vpn".equals(cmd)) {
                if (isUp()) {
                    down();
                } else {
                    up();
                }
            } else if ("add".equals(cmd)) {
                List<RuleStore.Rule> rs = RuleStore.load(this);
                RuleStore.Rule r = new RuleStore.Rule();
                r.type = i.getStringExtra("type");
                r.value = i.getStringExtra("value");
                r.note = i.getStringExtra("note");
                r.on = true;
                if ("ip".equals(r.type) || "domain".equals(r.type)) {
                    rs.add(r);
                    RuleStore.save(this, rs);
                    if (isUp()) {
                        rebuild();
                    }
                }
            } else if ("del".equals(cmd)) {
                List<RuleStore.Rule> rs = RuleStore.load(this);
                String v = i.getStringExtra("value");
                for (int k = rs.size() - 1; k >= 0; k--) {
                    if (rs.get(k).value.equalsIgnoreCase(v)) {
                        rs.remove(k);
                    }
                }
                RuleStore.save(this, rs);
                if (isUp()) {
                    rebuild();
                }
            } else if ("toggle".equals(cmd)) {
                List<RuleStore.Rule> rs = RuleStore.load(this);
                String v = i.getStringExtra("value");
                for (RuleStore.Rule r : rs) {
                    if (r.value.equalsIgnoreCase(v)) {
                        r.on = !r.on;
                    }
                }
                RuleStore.save(this, rs);
                if (isUp()) {
                    rebuild();
                }
            } else if ("import".equals(cmd)) {
                importRules();
            } else if ("edit".equals(cmd)) {
                editRule(i.getStringExtra("oldValue"), i.getStringExtra("type"),
                        i.getStringExtra("value"), i.getStringExtra("note"));
            }
        } catch (Throwable ignored) {
        }
    }

    /** 编辑单条规则：按 oldValue 找到并替换内容/类型/备注。 */
    private void editRule(String oldValue, String type, String value, String note) {
        try {
            if (oldValue == null || value == null || value.trim().isEmpty()) {
                return;
            }
            List<RuleStore.Rule> rs = RuleStore.load(this);
            boolean changed = false;
            for (RuleStore.Rule r : rs) {
                if (r.value.equalsIgnoreCase(oldValue)) {
                    r.value = value.trim();
                    r.type = "ip".equals(type) ? "ip" : "domain";
                    r.note = note == null ? "" : note;
                    changed = true;
                }
            }
            if (changed) {
                RuleStore.save(this, rs);
                if (isUp()) {
                    rebuild();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 合并导入：:settings 已把文件/URL 内容解析到私有临时文件，
     * 这里只做去重、落库、必要时重建 TUN。
     */
    private void importRules() {
        int added = 0;
        try {
            List<String[]> entries = RuleImporter.readTmp(this);
            List<RuleStore.Rule> rs = RuleStore.load(this);
            java.util.Set<String> have = new java.util.HashSet<String>();
            for (RuleStore.Rule r : rs) {
                have.add(r.type + "|" + r.value.toLowerCase(java.util.Locale.US));
            }
            for (String[] e : entries) {
                if (rs.size() >= RuleImporter.MAX_RULES) {
                    break;
                }
                String key = e[0] + "|" + e[1].toLowerCase(java.util.Locale.US);
                if (!have.add(key)) {
                    continue;
                }
                RuleStore.Rule r = new RuleStore.Rule();
                r.type = e[0];
                r.value = e[1];
                r.on = true;
                r.note = "导入";
                rs.add(r);
                added++;
            }
            if (added > 0) {
                RuleStore.save(this, rs);
                if (isUp()) {
                    rebuild();
                }
            }
        } catch (Throwable ignored) {
        } finally {
            RuleImporter.clearTmp(this);
            lastImported = added;
        }
    }

    public boolean isUp() {
        return tun != null;
    }

    private void up() {
        if (isUp()) {
            return;
        }
        try {
            VpnConsent.grant(this);
            Builder b = new Builder();
            b.setSession("C10H 屏蔽")
                    .addAddress("10.7.0.2", 24)
                    .addRoute("10.7.0.0", 24)
                    .addDnsServer("10.7.0.1")
                    .addRoute("0.0.0.0", 32);
            for (RuleStore.Rule r : RuleStore.load(this)) {
                if (r.on && "ip".equals(r.type)) {
                    try {
                        b.addRoute(r.value, 32);
                    } catch (Throwable ignored) {
                    }
                }
            }
            tun = b.establish();
            if (tun == null) {
                throw new IllegalStateException("VPN 未授权或被拒（需在系统弹窗允许）");
            }
            reader = new TunReader(this,
                    new FileInputStream(tun.getFileDescriptor()),
                    new FileOutputStream(tun.getFileDescriptor()));
            new Thread(reader, "c10h-tun").start();
            RuleStore.p(this).edit().putBoolean(RuleStore.K_VPN, true).apply();
        } catch (Throwable t) {
            down();
            Toast.makeText(this, "屏蔽启动失败：" + t, Toast.LENGTH_LONG).show();
        }
    }

    private void down() {
        down(true);
    }

    /**
     * @param clearDesired true=用户主动关闭/被撤销，期望状态置 false；
     *                     false=进程销毁（覆盖安装、被杀），保留期望状态供下次恢复。
     */
    private void down(boolean clearDesired) {
        try {
            if (reader != null) {
                reader.stop();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (tun != null) {
                tun.close();
            }
        } catch (Throwable ignored) {
        }
        tun = null;
        reader = null;
        if (clearDesired) {
            RuleStore.p(this).edit().putBoolean(RuleStore.K_VPN, false).apply();
        }
    }

    private void rebuild() {
        down();
        up();
    }

    @Override
    public void onRevoke() {
        down();
        RuleStore.broadcast(this);
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        // 覆盖安装/进程被系统回收：保留期望状态，下次 onStartCommand 自动恢复隧道。
        down(false);
        super.onDestroy();
    }
}
