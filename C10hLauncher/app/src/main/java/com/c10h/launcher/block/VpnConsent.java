package com.c10h.launcher.block;

import android.app.AppOpsManager;
import android.content.Context;
import android.os.Process;
import android.util.Log;

/**
 * VPN 预授权（2026-11-24）。
 *
 * 本机 ROM 删除了系统 VPN 授权弹窗（com.android.vpndialogs 不存在），
 * 导致 VpnService.prepare() 启动确认框必然失败（桌面提示“开关失败”）。
 * Android 9 的 Vpn 服务在 prepare 时会检查 ACTIVATE_VPN appop：
 * 只要该 appop 为 MODE_ALLOWED，就视为用户已同意，不再弹确认框。
 *
 * 我们用 MANAGE_APP_OPS_MODES（platform 签名已授予）把自己包的
 * ACTIVATE_VPN 设为 allow；进程/包一致，主进程、:settings、:block 共用。
 */
public final class VpnConsent {

    private static final String TAG = "C10HVpn";
    /** AOSP OP_ACTIVATE_VPN = 47（本机实测：47 ignore 后 ACTIVATE_VPN 变 ignore）。 */
    private static final int OP_ACTIVATE_VPN = 47;

    private VpnConsent() {
    }

    public static void grant(Context c) {
        try {
            AppOpsManager aom = (AppOpsManager)
                    c.getSystemService(Context.APP_OPS_SERVICE);
            if (aom == null) {
                return;
            }
            java.lang.reflect.Method m = AppOpsManager.class.getMethod(
                    "setMode", int.class, int.class, String.class, int.class);
            m.invoke(aom, OP_ACTIVATE_VPN, Process.myUid(), c.getPackageName(),
                    AppOpsManager.MODE_ALLOWED);
            Log.i(TAG, "ACTIVATE_VPN granted");
        } catch (Throwable t) {
            Log.w(TAG, "grant ACTIVATE_VPN failed: " + t);
        }
    }
}
