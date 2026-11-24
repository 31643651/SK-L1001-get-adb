package com.c10h.launcher;

import android.app.Activity;
import android.content.DialogInterface;
import android.os.Bundle;
import android.widget.Toast;

/**
 * 均衡器宿主（主进程）：设置页位于 :settings 进程，拿不到主进程的 EqEngine，
 * 因此由本 Activity 在主进程打开 EqPanel，保证滑条真正作用在全局音效上。
 */
public class EqActivity extends Activity {

    private EqPanel panel;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            panel = new EqPanel(this);
            panel.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface d) {
                    finish();
                }
            });
            panel.show();
        } catch (Throwable t) {
            Toast.makeText(this, "均衡器不可用：" + t, Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        try {
            if (panel != null && panel.isShowing()) {
                panel.dismiss();
            }
        } catch (Throwable ignored) {
        }
        panel = null;
        super.onDestroy();
    }
}
