package com.c10h.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.widget.Toast;

import java.lang.reflect.Method;

/**
 * 三键行为单一来源（规格 §7 双容器复用）：
 *  - home：CATEGORY_HOME Intent（悬浮/应用内场景）；桌面内由宿主回调回第一页；
 *  - recents：v1 三策略回退；
 *  - back：应用内 = 反射 InputManager.injectInputEvent 注入 BACK（需 INJECT_EVENTS），
 *          授不了 → toast 明因（桌面内由宿主回调 doBack 语义）。
 */
public final class NavActions {

    public interface HostBack {
        void hostBack();
    }

    private NavActions() {
    }

    public static void back(Context c, HostBack inLauncher) {
        if (inLauncher != null) {
            try {
                inLauncher.hostBack();
            } catch (Throwable ignored) {
            }
            return;
        }
        injectKey(c, KeyEvent.KEYCODE_BACK);
    }

    /** 反射注入按键（INJECT_EVENTS 平台钥已授；失败明确提示）。返回是否注入成功。 */
    public static boolean injectKey(Context c, int keyCode) {
        try {
            Class<?> imClz = Class.forName("android.hardware.input.InputManager");
            Object im = imClz.getMethod("getInstance").invoke(null);
            Method inj = imClz.getMethod("injectInputEvent",
                    Class.forName("android.view.InputEvent"), int.class);
            KeyEvent down = new KeyEvent(SystemClock.uptimeMillis(),
                    SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, keyCode, 0);
            KeyEvent up = new KeyEvent(SystemClock.uptimeMillis(),
                    SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0);
            inj.invoke(im, down, 0);
            inj.invoke(im, up, 0);
            return true;
        } catch (Throwable t) {
            Toast.makeText(c, "按键注入不可用（需 INJECT_EVENTS）", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    public static void home(Context c, Runnable inLauncherHome) {
        try {
            if (inLauncherHome != null) {
                inLauncherHome.run();
                return;
            }
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_HOME);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(c, "主页键失败", Toast.LENGTH_SHORT).show();
        }
    }

    public static void recents(Context c) {
        // 优先使用自研最近任务页：不依赖 SystemUI/Launcher3 的 RecentsActivity。
        try {
            Intent i = new Intent();
            i.setClassName(c, "com.c10h.launcher.settings.RecentsActivity");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return;
        } catch (Throwable ignored) {
        }
        // 回退：注入 APP_SWITCH。
        try {
            Class<?> imClz = Class.forName("android.hardware.input.InputManager");
            Object im = imClz.getMethod("getInstance").invoke(null);
            Method inj = imClz.getMethod("injectInputEvent",
                    Class.forName("android.view.InputEvent"), int.class);
            KeyEvent down = new KeyEvent(SystemClock.uptimeMillis(),
                    SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_APP_SWITCH, 0);
            KeyEvent up = new KeyEvent(SystemClock.uptimeMillis(),
                    SystemClock.uptimeMillis(), KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_APP_SWITCH, 0);
            inj.invoke(im, down, 0);
            inj.invoke(im, up, 0);
            return;
        } catch (Throwable ignored) {
        }
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory("android.intent.category.RECENTS");
            i.setPackage("com.android.systemui");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return;
        } catch (Throwable ignored) {
        }
        try {
            Intent i = new Intent("com.android.systemui.recents.action.TOGGLE_RECENTS");
            i.setClassName("com.android.systemui",
                    "com.android.systemui.recents.RecentsActivity");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return;
        } catch (Throwable ignored) {
        }
        Toast.makeText(c, "无法调起最近任务", Toast.LENGTH_SHORT).show();
    }
}
