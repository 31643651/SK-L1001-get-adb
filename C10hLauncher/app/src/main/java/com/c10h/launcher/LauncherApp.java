package com.c10h.launcher;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.os.Process;
import android.util.Log;

import java.util.List;

/**
 * 应用级入口：按当前进程名分发模块初始化。
 *
 * 隔离规则（规格 §3）：每个模块独立 try-catch，一个模块初始化失败只禁用该模块，
 * 绝不影响桌面主体；本 Application 在所有进程（主/:badge/:installer/:settings）都会走
 * onCreate，因此必须先判进程再初始化。
 */
public class LauncherApp extends Application {

    private static final String TAG = "C10H";

    private static LauncherApp sInstance;
    private static EqEngine sEq; // 主进程 EQ 引擎；其他进程恒为 null

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        try {
            ModuleManager.initFor(this, processName());
        } catch (Throwable t) {
            // 最外层兜底：模块分发失败也不能影响进程启动
            Log.e(TAG, "ModuleManager init failed: " + t);
        }
    }

    /** 全局 Context（仅主进程模块使用）。 */
    public static Context app() {
        return sInstance;
    }

    /** 主进程 EQ 引擎；非主进程或初始化失败返回 null。 */
    public static EqEngine eq() {
        return sEq;
    }

    /** 仅 ModuleManager 主进程分支调用。 */
    static void setEq(EqEngine e) {
        sEq = e;
    }

    /** minSdk 21 无 Application.getProcessName()，走 ActivityManager 匹配本进程 pid。 */
    private String processName() {
        try {
            int pid = Process.myPid();
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> list = am.getRunningAppProcesses();
            if (list != null) {
                for (ActivityManager.RunningAppProcessInfo p : list) {
                    if (p.pid == pid) {
                        return p.processName;
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "processName failed: " + t);
        }
        return null;
    }
}
