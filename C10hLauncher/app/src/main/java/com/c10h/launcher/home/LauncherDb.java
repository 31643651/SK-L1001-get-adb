package com.c10h.launcher;

import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AOSP LauncherProvider / DatabaseHelper 的等价数据层（M9）。
 * 表：favorites + workspace；单进程写入；所有写操作用事务。
 */
public class LauncherDb extends SQLiteOpenHelper {

    public static final String DB_NAME = "launcher.db";
    private static final int VERSION = 1;
    public static final String TABLE = "favorites";
    public static final String TABLE_WORKSPACE = "workspace";

    private static LauncherDb instance;

    public static synchronized LauncherDb get(Context c) {
        if (instance == null) {
            instance = new LauncherDb(c.getApplicationContext());
        }
        return instance;
    }

    private LauncherDb(Context c) {
        super(c, DB_NAME, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "container INTEGER NOT NULL DEFAULT -1,"
                + "screen INTEGER NOT NULL DEFAULT -1,"
                + "cellX INTEGER NOT NULL DEFAULT -1,"
                + "cellY INTEGER NOT NULL DEFAULT -1,"
                + "spanX INTEGER NOT NULL DEFAULT 1,"
                + "spanY INTEGER NOT NULL DEFAULT 1,"
                + "itemType INTEGER NOT NULL,"
                + "appWidgetId INTEGER NOT NULL DEFAULT -1,"
                + "icon BLOB,"
                + "title TEXT,"
                + "intent TEXT,"
                + "profileId INTEGER NOT NULL DEFAULT 0,"
                + "rank INTEGER NOT NULL DEFAULT 0,"
                + "restored INTEGER NOT NULL DEFAULT 0,"
                + "options INTEGER NOT NULL DEFAULT 0"
                + ");");
        db.execSQL("CREATE INDEX idx_fav_container ON " + TABLE + "(container);");
        db.execSQL("CREATE INDEX idx_fav_screen ON " + TABLE + "(screen);");
        db.execSQL("CREATE TABLE " + TABLE_WORKSPACE + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "screenRank INTEGER NOT NULL DEFAULT -1);");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v1：无历史版本；保留升级入口，后续按版本迁移。
    }

    public boolean isEmpty() {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + TABLE, null);
            return !c.moveToFirst() || c.getLong(0) == 0;
        } catch (Throwable t) {
            return true;
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    public static class Loaded {
        public final List<Item> workspace = new ArrayList<Item>();
        public final List<Item> fixed = new ArrayList<Item>();
        public int screens = 1;
    }

    public Loaded load() {
        return load(Item.CONTAINER_DESKTOP);
    }

    /** 只加载指定桌面容器（-100 抽屉 / -102 经典），两套布局互不影响。 */
    public Loaded load(int desktopContainer) {
        Loaded out = new Loaded();
        Cursor c = null;
        try {
            c = getReadableDatabase().query(TABLE, null, null, null,
                    null, null, "container,screen,cellY,cellX,rank");
            Map<Long, Item> folders = new HashMap<Long, Item>();
            while (c != null && c.moveToNext()) {
                long id = c.getLong(c.getColumnIndex("_id"));
                int container = c.getInt(c.getColumnIndex("container"));
                int itemType = c.getInt(c.getColumnIndex("itemType"));
                Item it;
                if (itemType == Item.TYPE_FOLDER) {
                    it = Item.folder(c.getString(c.getColumnIndex("title")));
                    folders.put(id, it);
                } else {
                    String intent = c.getString(c.getColumnIndex("intent"));
                    String[] pc = split(intent);
                    it = Item.app(pc[0], pc[1]);
                }
                it.id = id;
                it.container = container;
                it.screen = c.getInt(c.getColumnIndex("screen"));
                it.cellX = c.getInt(c.getColumnIndex("cellX"));
                it.cellY = c.getInt(c.getColumnIndex("cellY"));
                it.spanX = c.getInt(c.getColumnIndex("spanX"));
                it.spanY = c.getInt(c.getColumnIndex("spanY"));
                it.rank = c.getInt(c.getColumnIndex("rank"));
                if (it.isFolder()) {
                    // 顶层文件夹稍后加入
                } else if (folders.containsKey((long) container)) {
                    folders.get((long) container).members.add(it);
                } else if (container == Item.CONTAINER_FIXED) {
                    out.fixed.add(it);
                } else if (container == desktopContainer) {
                    out.workspace.add(it);
                }
            }
            for (Item f : folders.values()) {
                if (f.container == desktopContainer) {
                    out.workspace.add(f);
                }
            }
            sortWorkspace(out.workspace);
            Collections.sort(out.fixed, new Comparator<Item>() {
                @Override
                public int compare(Item a, Item b) {
                    return a.rank - b.rank;
                }
            });
            Cursor w = getReadableDatabase().rawQuery(
                    "SELECT COUNT(*) FROM " + TABLE_WORKSPACE, null);
            if (w != null && w.moveToFirst()) {
                out.screens = Math.max(1, w.getInt(0));
            }
            if (w != null) {
                w.close();
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return out;
    }

    private static String[] split(String intent) {
        if (intent == null) {
            return new String[]{"", ""};
        }
        int i = intent.indexOf('/');
        if (i < 0) {
            return new String[]{intent, ""};
        }
        return new String[]{intent.substring(0, i), intent.substring(i + 1)};
    }

    private static void sortWorkspace(List<Item> list) {
        Collections.sort(list, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                if (a.screen != b.screen) {
                    return a.screen - b.screen;
                }
                if (a.cellY != b.cellY) {
                    return a.cellY - b.cellY;
                }
                return a.cellX - b.cellX;
            }
        });
    }

    /** 全量重写指定桌面容器 + 固定栏；单事务。 */
    public void saveAll(List<Item> workspace, List<Item> fixed) {
        saveAll(workspace, fixed, Item.CONTAINER_DESKTOP);
    }

    public void saveAll(List<Item> workspace, List<Item> fixed, int desktopContainer) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            // 只清掉当前桌面容器（含其文件夹成员）与固定栏，另一套桌面保持不动。
            List<String> folderIds = new ArrayList<String>();
            Cursor fc = null;
            try {
                fc = db.rawQuery("SELECT _id FROM " + TABLE
                                + " WHERE container=? AND itemType=?",
                        new String[]{String.valueOf(desktopContainer),
                                String.valueOf(Item.TYPE_FOLDER)});
                while (fc != null && fc.moveToNext()) {
                    folderIds.add(String.valueOf(fc.getLong(0)));
                }
            } catch (Throwable ignored) {
            } finally {
                if (fc != null) {
                    fc.close();
                }
            }
            for (String fid : folderIds) {
                db.delete(TABLE, "container=?", new String[]{fid});
            }
            db.delete(TABLE, "container=?", new String[]{String.valueOf(desktopContainer)});
            db.delete(TABLE, "container=?", new String[]{String.valueOf(Item.CONTAINER_FIXED)});
            db.delete(TABLE_WORKSPACE, null, null);
            int screens = 1;
            if (workspace != null) {
                for (Item it : workspace) {
                    screens = Math.max(screens, it.screen + 1);
                    it.container = desktopContainer;
                    if (it.isFolder()) {
                        long fid = insert(db, it, desktopContainer,
                                it.screen, it.cellX, it.cellY, it.rank, it.name);
                        it.id = fid;
                        for (int i = 0; i < it.members.size(); i++) {
                            Item m = it.members.get(i);
                            insert(db, m, (int) fid, it.screen, -1, -1, i, null);
                        }
                    } else {
                        it.id = insert(db, it, desktopContainer,
                                it.screen, it.cellX, it.cellY, it.rank, null);
                    }
                }
            }
            if (fixed != null) {
                for (int i = 0; i < fixed.size(); i++) {
                    Item it = fixed.get(i);
                    if (it.isApp()) {
                        if (it.rank < 0) {
                            it.rank = i;
                        }
                        it.id = insert(db, it, Item.CONTAINER_FIXED, 0, -1, -1, it.rank, null);
                    }
                }
            }
            for (int i = 0; i < screens; i++) {
                ContentValues cv = new ContentValues();
                cv.put("screenRank", i);
                db.insert(TABLE_WORKSPACE, null, cv);
            }
            db.setTransactionSuccessful();
        } catch (Throwable ignored) {
        } finally {
            db.endTransaction();
        }
    }

    private long insert(SQLiteDatabase db, Item it, int container, int screen,
                        int cellX, int cellY, int rank, String title) {
        ContentValues cv = new ContentValues();
        cv.put("container", container);
        cv.put("screen", screen);
        cv.put("cellX", cellX);
        cv.put("cellY", cellY);
        cv.put("spanX", it.spanX);
        cv.put("spanY", it.spanY);
        cv.put("itemType", it.itemType());
        cv.put("appWidgetId", -1);
        cv.put("title", title == null ? "" : title);
        cv.put("intent", it.isFolder() ? "" : (it.pkg + "/" + it.cls));
        cv.put("profileId", 0);
        cv.put("rank", rank);
        cv.put("restored", 0);
        cv.put("options", 0);
        return db.insert(TABLE, null, cv);
    }

    /** 首次 seed：优先导入旧 JSON，否则按已装应用生成 6×5 工作区 + 7 格固定栏。 */
    public void seed(Context c, List<ResolveInfo> apps) {
        if (!isEmpty()) {
            return;
        }
        if (seedAosp(c, apps)) {
            return;
        }
        List<Item> workspace = new ArrayList<Item>();
        List<Item> fixed = new ArrayList<Item>();
        List<Item> old = ItemsStore.load(c);
        if (old != null && !old.isEmpty()) {
            int i = 0;
            for (Item it : old) {
                it.screen = i / 30;
                it.cellX = i % 6;
                it.cellY = (i % 30) / 6;
                if (it.isFolder()) {
                    it.screen = 0;
                    it.cellX = i % 6;
                    it.cellY = (i % 30) / 6;
                }
                workspace.add(it);
                i++;
            }
        } else if (apps != null) {
            int i = 0;
            for (ResolveInfo ri : apps) {
                if (ri.activityInfo == null) {
                    continue;
                }
                if (c.getPackageName().equals(ri.activityInfo.packageName)) {
                    continue;
                }
                Item it = Item.app(ri.activityInfo.packageName, ri.activityInfo.name);
                it.screen = i / 30;
                it.cellX = i % 6;
                it.cellY = (i % 30) / 6;
                workspace.add(it);
                i++;
                if (i >= 30) {
                    break;
                }
            }
        }
        if (apps != null) {
            List<ResolveInfo> pool = new ArrayList<ResolveInfo>(apps);
            Collections.sort(pool, new Comparator<ResolveInfo>() {
                @Override
                public int compare(ResolveInfo a, ResolveInfo b) {
                    return score(b.activityInfo == null ? "" : b.activityInfo.packageName)
                            - score(a.activityInfo == null ? "" : a.activityInfo.packageName);
                }
            });
            java.util.HashSet<String> used = new java.util.HashSet<String>();
            for (ResolveInfo ri : pool) {
                if (ri.activityInfo == null || fixed.size() >= 7) {
                    break;
                }
                String p = ri.activityInfo.packageName;
                if (c.getPackageName().equals(p) || used.contains(p)) {
                    continue;
                }
                used.add(p);
                Item it = Item.app(p, ri.activityInfo.name);
                it.rank = fixed.size();
                fixed.add(it);
            }
        }
        // 固定栏应用从工作区排除（R8）
        for (int i = workspace.size() - 1; i >= 0; i--) {
            Item w = workspace.get(i);
            if (w.isApp()) {
                for (Item f : fixed) {
                    if (w.key().equals(f.key())) {
                        workspace.remove(i);
                        break;
                    }
                }
            }
        }
        saveAll(workspace, fixed);
    }

    /** AOSP Launcher3 1280×800 默认布局：工作区仅 Contacts(0,4)，固定栏 6 应用（第 3 槽空）。 */
    private boolean seedAosp(Context c, List<ResolveInfo> apps) {
        if (apps == null || apps.isEmpty()) {
            return false;
        }
        Map<String, String> byPkg = new HashMap<String, String>();
        for (ResolveInfo ri : apps) {
            if (ri.activityInfo != null) {
                byPkg.put(ri.activityInfo.packageName, ri.activityInfo.name);
            }
        }
        Item contacts = null;
        for (ResolveInfo ri : apps) {
            if (ri.activityInfo != null
                    && "com.android.contacts".equals(ri.activityInfo.packageName)) {
                contacts = Item.app(ri.activityInfo.packageName, ri.activityInfo.name);
                break;
            }
        }
        if (contacts == null) {
            return false;
        }
        contacts.screen = 0;
        contacts.cellX = 0;
        contacts.cellY = 4;
        List<Item> workspace = new ArrayList<Item>();
        workspace.add(contacts);

        String[][] slots = new String[][]{
                {"com.android.messaging", "0"},
                {"com.android.email", "1"},
                {"org.chromium.webview_shell", "2"},
                {null, "3"},
                {"com.android.music", "4"},
                {"com.android.gallery3d", "5"},
                {"com.android.camera2", "6"}
        };
        List<Item> fixed = new ArrayList<Item>();
        for (String[] s : slots) {
            if (s[0] == null) {
                continue;
            }
            String cls = byPkg.get(s[0]);
            if (cls == null) {
                continue;
            }
            Item it = Item.app(s[0], cls);
            it.rank = Integer.parseInt(s[1]);
            fixed.add(it);
        }
        saveAll(workspace, fixed);
        return true;
    }

    private static int score(String pkg) {
        String p = pkg == null ? "" : pkg.toLowerCase(java.util.Locale.US);
        if (p.contains("dialer") || p.contains("phone") || p.contains("telecom")) {
            return 100;
        }
        if (p.contains("mms") || p.contains("messag") || p.contains("sms")) {
            return 95;
        }
        if (p.contains("browser") || p.contains("chrome") || p.contains("webkit")) {
            return 90;
        }
        if (p.contains("camera")) {
            return 85;
        }
        if (p.contains("gallery") || p.contains("photos")) {
            return 80;
        }
        if (p.contains("contacts")) {
            return 70;
        }
        if (p.contains("settings")) {
            return 60;
        }
        return 50;
    }
}
