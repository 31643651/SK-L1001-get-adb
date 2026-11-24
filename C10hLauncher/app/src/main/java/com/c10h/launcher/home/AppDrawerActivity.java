package com.c10h.launcher;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 兼容保留的旧全屏应用抽屉：新桌面使用 MainActivity 内的 AllAppsView；
 * 全部应用、字母序 Collator.CHINA（与桌面同一排序来源）；长按 → 卸载/应用信息。
 * 主进程 Activity（无 android:process），与 MainActivity 同 prefs 写者，无跨进程问题。
 */
public class AppDrawerActivity extends Activity {

    private GridView grid;
    private AppAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            buildUi();
        } catch (Throwable t) {
            LinearLayout err = new LinearLayout(this);
            err.setOrientation(LinearLayout.VERTICAL);
            err.setBackgroundColor(0xFF0E1620);
            err.setGravity(Gravity.CENTER);
            TextView tv = new TextView(this);
            tv.setText("抽屉打开失败\n" + String.valueOf(t));
            tv.setTextColor(0xFFFF6B6B);
            tv.setGravity(Gravity.CENTER);
            setContentView(err);
        }
    }

    private void buildUi() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int widthDp = (int) (dm.widthPixels / dm.density);
        int cols = Math.max(3, Math.min(8, Math.round(widthDp / 240f)));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0E1620);

        TextView title = new TextView(this);
        title.setText("全部应用");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(12);
        title.setPadding(pad, pad / 2, pad, pad / 2);
        title.setBackgroundColor(0xFF132433);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        grid = new GridView(this);
        grid.setNumColumns(cols);
        grid.setVerticalSpacing(dp(8));
        grid.setHorizontalSpacing(dp(4));
        grid.setPadding(pad, pad / 2, pad, pad / 2);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setCacheColorHint(0x00000000);

        adapter = new AppAdapter(this);
        grid.setAdapter(adapter);

        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                startApp(adapter.getItem(position));
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view,
                                           int position, long id) {
                showMenu(adapter.getItem(position), view);
                return true;
            }
        });

        root.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        loadApps();
    }

    private void loadApps() {
        try {
            Intent main = new Intent(Intent.ACTION_MAIN);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> list = getPackageManager().queryIntentActivities(main, 0);
            final Collator collator = Collator.getInstance(Locale.CHINA);
            Collections.sort(list, new Comparator<ResolveInfo>() {
                @Override
                public int compare(ResolveInfo a, ResolveInfo b) {
                    return collator.compare(labelOf(a), labelOf(b));
                }
            });
            List<Item> items = new java.util.ArrayList<Item>();
            for (ResolveInfo ri : list) {
                if (ri.activityInfo != null) {
                    items.add(Item.app(ri.activityInfo.packageName, ri.activityInfo.name));
                }
            }
            adapter.setData(items);
        } catch (Throwable t) {
            toast("加载应用失败：" + t);
        }
    }

    private String labelOf(ResolveInfo ri) {
        try {
            CharSequence s = ri.loadLabel(getPackageManager());
            return s == null ? "" : s.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private void startApp(Item it) {
        if (it == null || it.pkg == null || it.cls == null) {
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(new ComponentName(it.pkg, it.cls));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            toast("无法启动 " + it.pkg);
        }
    }

    private void showMenu(final Item it, View anchor) {
        if (it == null || it.pkg == null) {
            return;
        }
        try {
            PopupMenu pm = new PopupMenu(this, anchor);
            pm.getMenu().add(0, 1, 0, getString(R.string.menu_uninstall));
            pm.getMenu().add(0, 2, 1, getString(R.string.menu_appinfo));
            pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(android.view.MenuItem item) {
                    try {
                        if (item.getItemId() == 1) {
                            Intent i = new Intent(Intent.ACTION_DELETE);
                            i.setData(android.net.Uri.parse("package:" + it.pkg));
                            startActivity(i);
                        } else {
                            Intent i = new Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                            i.setData(android.net.Uri.parse("package:" + it.pkg));
                            startActivity(i);
                        }
                    } catch (Throwable t) {
                        toast("操作失败");
                    }
                    return true;
                }
            });
            pm.show();
        } catch (Throwable t) {
            toast("菜单打开失败");
        }
    }

    private int dp(int v) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return (int) (v * dm.density + 0.5f);
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }
}
