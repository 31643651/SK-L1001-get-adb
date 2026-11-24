package com.c10h.launcher;

import android.content.Context;
import android.content.pm.ResolveInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ws_items 唯一事实源（评审 S4 主进程单写者）：
 *  - 首次/存储损坏 → 由已装应用列表全量重建（降级不崩）；
 *  - 有存储 → 存储为准，已装新应用追加到尾，已卸载应用从 items 剔除；
 *  - 「从桌面移除」= ws_removed 集合（M3 已有），抽屉关态忽略该集合（规格 §6.5）。
 */
public final class ItemsStore {

    private static final String KEY = "ws_items";

    private ItemsStore() {
    }

    public static void save(Context c, List<Item> items) {
        try {
            JSONArray a = new JSONArray();
            for (Item i : items) {
                a.put(i.toJson());
            }
            c.getSharedPreferences("c10h_launcher", Context.MODE_PRIVATE)
                    .edit().putString(KEY, a.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 读存储条目；null/损坏 → 返回 null（调用方走重建）。 */
    public static List<Item> load(Context c) {
        String s = c.getSharedPreferences("c10h_launcher", Context.MODE_PRIVATE)
                .getString(KEY, null);
        if (s == null) {
            return null;
        }
        try {
            JSONArray a = new JSONArray(s);
            List<Item> out = new ArrayList<Item>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) {
                    out.add(Item.fromJson(o));
                }
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 合并：存储 + 已装应用差集（新应用追尾、卸载剔除）；供 refreshApps 用。 */
    public static List<Item> merge(Context c, List<ResolveInfo> installed) {
        Set<String> have = new LinkedHashSet<String>();
        for (ResolveInfo ri : installed) {
            if (ri.activityInfo != null) {
                have.add(WorkspaceStore.itemKey(
                        ri.activityInfo.packageName, ri.activityInfo.name));
            }
        }
        List<Item> items = load(c);
        if (items == null) {
            items = new ArrayList<Item>();
            for (ResolveInfo ri : installed) {
                if (ri.activityInfo != null) {
                    items.add(Item.app(ri.activityInfo.packageName, ri.activityInfo.name));
                }
            }
            save(c, items);
            return items;
        }
        // 剔除已卸载（app 条目与 folder 成员都查）
        List<Item> pruned = new ArrayList<Item>();
        for (Item i : items) {
            if (i.isFolder()) {
                List<Item> keep = new ArrayList<Item>();
                for (Item m : i.members) {
                    if (have.contains(m.key())) {
                        keep.add(m);
                    }
                }
                i.members.clear();
                i.members.addAll(keep);
                if (i.members.isEmpty()) {
                    continue;   // 空文件夹不保留
                }
                pruned.add(i);
            } else if (have.contains(i.key())) {
                pruned.add(i);
            }
        }
        // 追加新安装（have 里不在任何条目中的）
        Set<String> placed = new LinkedHashSet<String>();
        for (Item i : pruned) {
            if (i.isFolder()) {
                for (Item m : i.members) {
                    placed.add(m.key());
                }
            } else {
                placed.add(i.key());
            }
        }
        boolean added = false;
        for (String k : have) {
            if (!placed.contains(k)) {
                String[] pc = k.split("/", 2);
                if (pc.length == 2) {
                    pruned.add(Item.app(pc[0], pc[1]));
                    added = true;
                }
            }
        }
        if (added) {
            save(c, pruned);
        }
        return pruned;
    }

    /** 兼容旧调用：工作区“已移除”集合由 LauncherModel 对账时处理。 */
    public static boolean isHidden(Context c, String pkg, String cls) {
        return WorkspaceStore.removed(c).contains(WorkspaceStore.itemKey(pkg, cls));
    }
}
