package com.c10h.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 应用/文件夹网格适配器（M9 重写）：产出 IconView（图标上、标签下、两行固定）。
 * 供应用抽屉与文件夹面板复用；不带点击监听，由 GridView 统一分发。
 */
public class AppAdapter extends BaseAdapter {

    private final Activity ctx;
    private final PackageManager pm;
    private List<Item> data = new ArrayList<Item>();
    private final HashMap<String, Drawable> iconCache = new HashMap<String, Drawable>();
    private final HashMap<String, String> labelCache = new HashMap<String, String>();
    private final Map<String, Integer> badges = new HashMap<String, Integer>();
    private final int iconPx;

    public AppAdapter(Activity ctx) {
        this.ctx = ctx;
        this.pm = ctx.getPackageManager();
        this.iconPx = Ui.dp(ctx, 56);
    }

    public void setData(List<Item> d) {
        this.data = (d == null) ? new ArrayList<Item>() : d;
        notifyDataSetChanged();
    }

    public void setBadge(String pkg, int count) {
        if (count > 0) {
            badges.put(pkg, count);
        } else {
            badges.remove(pkg);
        }
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return data.size();
    }

    @Override
    public Item getItem(int position) {
        return (position < 0 || position >= data.size()) ? null : data.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        IconView v = (convertView instanceof IconView)
                ? (IconView) convertView : new IconView(ctx, iconPx);
        Item it = getItem(position);
        if (it == null) {
            v.setItem(null, "", 0);
            return v;
        }
        try {
            int n = badges.containsKey(it.pkg) ? badges.get(it.pkg) : 0;
            v.setItem(it, labelOf(it), n);
            if (it.isFolder()) {
                Drawable[] minis = new Drawable[Math.min(4, it.members.size())];
                for (int i = 0; i < minis.length; i++) {
                    minis[i] = iconOf(it.members.get(i).pkg);
                }
                v.setFolderIcons(minis);
            } else {
                v.setIcon(iconOf(it.pkg));
            }
        } catch (Throwable t) {
            v.setItem(it, it.pkg, 0);
        }
        return v;
    }

    private Drawable iconOf(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return null;
        }
        Drawable d = iconCache.get(pkg);
        if (d == null) {
            try {
                d = pm.getApplicationIcon(pkg);
            } catch (Throwable t) {
                d = null;
            }
            if (d != null) {
                iconCache.put(pkg, d);
            }
        }
        return d;
    }

    private String labelOf(Item it) {
        if (it.isFolder()) {
            return it.name;
        }
        String key = it.pkg + "/" + it.cls;
        String cached = labelCache.get(key);
        if (cached != null) {
            return cached;
        }
        String s = it.pkg;
        try {
            ResolveInfo ri = queryItem(it.pkg, it.cls);
            if (ri != null) {
                CharSequence cs = ri.loadLabel(pm);
                s = cs == null ? it.pkg : cs.toString();
            }
        } catch (Throwable ignored) {
        }
        labelCache.put(key, s);
        return s;
    }

    private ResolveInfo queryItem(String pkg, String cls) {
        if (pkg == null || cls == null) {
            return null;
        }
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        i.setClassName(pkg, cls);
        List<ResolveInfo> l = pm.queryIntentActivities(i, 0);
        return (l == null || l.isEmpty()) ? null : l.get(0);
    }
}
