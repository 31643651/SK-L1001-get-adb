package com.c10h.helper;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Activity 列表：枚举全部已安装应用及其 Activity，点击显式启动。
 * 支持"仅 factory"过滤，列表支持遥控器上下滚动 + 确认键启动。
 * 启动失败时把异常类名和 message 显示在界面上，绝不静默崩溃。
 */
public class AppListActivity extends Activity {

    private PackageManager pm;
    private TextView status;
    private ListView listView;

    private boolean factoryOnly = true;
    private final ArrayList<String> display = new ArrayList<String>();
    /** 每项: [0]=包名, [1]=Activity 完整类名 */
    private final ArrayList<String[]> data = new ArrayList<String[]>();
    private ArrayAdapter<String> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (12 * density);

        pm = getPackageManager();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        status.setText("枚举中…");
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button toggle = new Button(this);
        toggle.setText("过滤: 仅 factory (按此切换)");
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        toggle.setFocusable(true);
        toggle.setFocusableInTouchMode(true);
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                factoryOnly = !factoryOnly;
                ((Button) v).setText(factoryOnly
                        ? "过滤: 仅 factory (按此切换)"
                        : "过滤: 全部应用 (按此切换)");
                loadList();
            }
        });
        root.addView(toggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, display);
        listView = new ListView(this);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                launch(position);
            }
        });
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        setContentView(root);

        loadList();
    }

    private void loadList() {
        try {
            data.clear();
            display.clear();

            List<PackageInfo> pkgs = pm.getInstalledPackages(PackageManager.GET_ACTIVITIES);
            if (pkgs != null) {
                for (PackageInfo pi : pkgs) {
                    if (pi.packageName == null || pi.activities == null) continue;
                    for (ActivityInfo ai : pi.activities) {
                        if (ai.name == null) continue;
                        if (factoryOnly && !matches(pi.packageName, ai.name)) continue;
                        data.add(new String[]{pi.packageName, ai.name});
                        display.add(ai.name + (ai.exported ? "  [导出]" : "  [未导出]")
                                + "\n[" + pi.packageName + "]");
                    }
                }
            }
            adapter.notifyDataSetChanged();
            status.setText("共 " + data.size() + " 个 Activity（上下滚动，OK 键启动）");
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            status.setText("❌ 枚举失败\n" + e.getClass().getName() + "\n" + msg);
        }
    }

    private boolean matches(String pkg, String actName) {
        String low = actName == null ? "" : actName.toLowerCase();
        String p = pkg == null ? "" : pkg.toLowerCase();
        return p.contains("factory") || low.contains("factory") || low.contains("testlocation");
    }

    private void launch(int position) {
        if (position < 0 || position >= data.size()) return;
        String[] d = data.get(position);
        try {
            Intent i = new Intent();
            i.setClassName(d[0], d[1]);
            startActivity(i);
            status.setText("已发送启动: " + d[1]);
        } catch (Exception e) {
            String msg = e.getMessage() == null
                    ? "(message 为空。未导出 exported=false 或找不到类时常见)"
                    : e.getMessage();
            status.setText("❌ 启动失败\n目标: " + d[0] + "/" + d[1] + "\n"
                    + e.getClass().getName() + "\n" + msg);
        } catch (Throwable t) {
            status.setText("❌ 启动失败(Throwable)\n" + t.getClass().getName());
        }
    }
}
