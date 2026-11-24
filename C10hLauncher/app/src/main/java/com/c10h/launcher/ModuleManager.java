package com.c10h.launcher;

import android.content.Context;
import android.util.Log;

/**
 * 模块注册与隔离边界（规格 §3）。
 *
 * 规则：
 * 1. 按进程只初始化该进程应有的模块；
 * 2. 每个模块独立 try-catch —— 一个模块抛任何 Throwable 只禁用它自己；
 * 2b. SharedPreferences 单写者 + 广播带值（评审 S4）：每组前缀只允许一个进程写
 *     （eq_/ws_/nav_=主、inst_=:installer、set_=:settings、角标计数=:badge 自有文件）；
 *     任何进程不得读他人写的 SP（每进程独立缓存，跨进程读必现旧值）。需要同步的
 *     变更发显式广播（extra 带新值），接收方只更新内存态 + 写自己拥有的 key。
 * 3. 模块间禁止互相 import 内部类，跨进程只走 Intent/广播/prefs。
 *
 * 部署路线（评审 S1）：AOSP platform testkey 平台签名 + /data 普通安装，
 * 6 项 signature|privileged 权限按签名匹配自动授予；priv-app/remount/白名单已废除。
 *
 * 本计划（M1/M2）只有主进程的 EQ 模块；:badge / :installer / :settings
 * 的初始化分支随各自模块（M4/M6/M7）加入。
 */
public final class ModuleManager {

    private static final String TAG = "C10H";
    private static boolean sMainInited;

    private ModuleManager() {
    }

    /** 由 LauncherApp.onCreate 调用；process 为 null 时按主进程处理。 */
    public static synchronized void initFor(Context app, String process) {
        boolean isMain = (process == null) || process.equals(app.getPackageName());
        Log.i(TAG, "ModuleManager init process=" + process + " main=" + isMain);
        if (isMain) {
            initMain(app);
        }
        // 非主进程分支随模块扩展：
        // ":badge".equals(process)   -> BadgeListenerService 自管理（M4）
        // ":installer".equals(process) -> installer 模块（M6）
        // ":settings".equals(process)  -> settings 模块（M7）
    }

    private static void initMain(Context app) {
        if (sMainInited) {
            return;
        }
        sMainInited = true;
        Log.i(TAG, "main process ready");
        // 模块 A：EQ 引擎。独立 try-catch —— 失败只禁用音效，桌面其他模块不受影响。
        try {
            LauncherApp.setEq(new EqEngine(app));
            Log.i(TAG, "EqEngine ready attached=" + LauncherApp.eq().isAttached());
        } catch (Throwable t) {
            Log.e(TAG, "EqEngine init failed: " + t);
            LauncherApp.setEq(null);
        }
    }
}
