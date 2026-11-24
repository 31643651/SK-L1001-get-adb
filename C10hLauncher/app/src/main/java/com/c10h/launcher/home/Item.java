package com.c10h.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 工作区条目（规格 §6.7）：type=app|folder；folder 有 members（app 条目列表）。 */
public class Item {

    public static final int CONTAINER_DESKTOP = -100;
    public static final int CONTAINER_FIXED = -101;
    /** 经典桌面（所有应用平铺）独立容器，与抽屉桌面 -100 完全隔离。 */
    public static final int CONTAINER_CLASSIC = -102;
    public static final int TYPE_APPLICATION = 0;
    public static final int TYPE_FOLDER = 2;

    public long id = -1;
    public int container = CONTAINER_DESKTOP;
    public int screen = 0;
    public int cellX = -1;
    public int cellY = -1;
    public int spanX = 1;
    public int spanY = 1;
    public int rank = 0;

    public String type;      // "app" | "folder"
    public String pkg;
    public String cls;
    public String name;      // 文件夹显示名（app 条目不用）
    public final List<Item> members = new ArrayList<Item>();

    public static Item app(String pkg, String cls) {
        Item i = new Item();
        i.type = "app";
        i.pkg = pkg;
        i.cls = cls;
        return i;
    }

    public static Item folder(String name) {
        Item i = new Item();
        i.type = "folder";
        i.name = (name == null || name.isEmpty()) ? "文件夹" : name;
        return i;
    }

    public boolean isFolder() {
        return "folder".equals(type);
    }

    public boolean isApp() {
        return !isFolder();
    }

    public int itemType() {
        return isFolder() ? TYPE_FOLDER : TYPE_APPLICATION;
    }

    public void copyPosition(Item o) {
        this.id = o.id;
        this.container = o.container;
        this.screen = o.screen;
        this.cellX = o.cellX;
        this.cellY = o.cellY;
        this.spanX = o.spanX;
        this.spanY = o.spanY;
        this.rank = o.rank;
    }

    public String key() {
        return WorkspaceStore.itemKey(pkg, cls);
    }

    public JSONObject toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("t", type);
            if (isFolder()) {
                o.put("n", name);
                JSONArray m = new JSONArray();
                for (Item c : members) {
                    m.put(c.toJson());
                }
                o.put("m", m);
            } else {
                o.put("p", pkg);
                o.put("c", cls);
            }
            return o;
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    public static Item fromJson(JSONObject o) {
        Item i = new Item();
        i.type = o.optString("t", "app");
        if (i.isFolder()) {
            i.name = o.optString("n", "文件夹");
            JSONArray m = o.optJSONArray("m");
            if (m != null) {
                for (int k = 0; k < m.length(); k++) {
                    JSONObject c = m.optJSONObject(k);
                    if (c != null) {
                        i.members.add(fromJson(c));
                    }
                }
            }
        } else {
            i.pkg = o.optString("p", "");
            i.cls = o.optString("c", "");
        }
        return i;
    }
}
