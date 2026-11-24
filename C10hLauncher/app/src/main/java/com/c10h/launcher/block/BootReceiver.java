package com.c10h.launcher.block;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

/**
 * :block 进程入口：BOOT_COMPLETED → 启动守护+15s 补发；ADB_TICK → check；
 * BLK_CMD → 转投 BlockVpnService（服务未跑也能拉起）；ADB_CMD → 守护开关/查询。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context c, Intent i) {
        try {
            String a = i == null ? null : i.getAction();
            if (Intent.ACTION_BOOT_COMPLETED.equals(a)) {
                RuleStore.ensureSeed(c);
                AdbGuard.start(c);
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        AdbGuard.check(c);
                    }
                }, 15000L);
            } else if ("com.c10h.launcher.action.ADB_TICK".equals(a)) {
                AdbGuard.check(c);
            } else if (RuleStore.ACTION_CMD.equals(a)) {
                Intent s = new Intent(c, BlockVpnService.class);
                s.putExtras(i);
                c.startService(s);
            } else if ("com.c10h.launcher.action.ADB_CMD".equals(a)) {
                String cmd = i.getStringExtra("cmd");
                if ("guard_on".equals(cmd)) {
                    AdbGuard.setEnabled(c, true);
                } else if ("guard_off".equals(cmd)) {
                    AdbGuard.setEnabled(c, false);
                } else {
                    RuleStore.broadcast(c);
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
