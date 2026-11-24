package com.c10h.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * AOSP LauncherModel / LoaderTask 的等价实现（M9）：
 * 后台查询全量应用 → 读 DB → 对账（卸载/新增/空文件夹）→ 回主线程绑定。
 * 写库仍由 LauncherDb 单进程事务完成。
 */
public final class LauncherModel {

    public interface Callback {
        void onLoaded(List<Item> workspace, List<Item> fixed, List<Item> allApps,
                      List<ResolveInfo> apps);
    }

    private LauncherModel() {
    }

    public static List<ResolveInfo> queryApps(Context c) {
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = c.getPackageManager().queryIntentActivities(main, 0);
        if (list == null) {
            return new ArrayList<ResolveInfo>();
        }
        final Collator collator = Collator.getInstance(Locale.CHINA);
        final android.content.pm.PackageManager pm = c.getPackageManager();
        Collections.sort(list, new Comparator<ResolveInfo>() {
            @Override
            public int compare(ResolveInfo a, ResolveInfo b) {
                return collator.compare(labelOf(a, pm), labelOf(b, pm));
            }
        });
        return list;
    }

    private static String labelOf(ResolveInfo ri, android.content.pm.PackageManager pm) {
        try {
            CharSequence s = ri.loadLabel(pm);
            return s == null ? "" : s.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    public static void loadAsync(final Context c, final Callback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Item> ws = new ArrayList<Item>();
                final List<Item> fixed = new ArrayList<Item>();
                final List<Item> all = new ArrayList<Item>();
                final List<ResolveInfo> apps = new ArrayList<ResolveInfo>();
                try {
                    apps.addAll(queryApps(c));
                    // 系统启动早期 PackageManager 可能只返回少量应用；等待并重试，
                    // 避免把“不完整的应用列表”当成卸载结果写回数据库。
                    int tries = 0;
                    while (apps.size() < 10 && tries < 4) {
                        Thread.sleep(1200);
                        List<ResolveInfo> again = queryApps(c);
                        if (again.size() > apps.size()) {
                            apps.clear();
                            apps.addAll(again);
                        }
                        tries++;
                    }
                    android.util.Log.i("C10HModel", "apps=" + apps.size()
                            + " classic=" + WorkspaceStore.classicMode(c));
                    // 桌面自身不作为条目出现（AOSP Launcher 不在 AllApps/工作区列出自己）
                    for (int i = apps.size() - 1; i >= 0; i--) {
                        ResolveInfo ri = apps.get(i);
                        if (ri.activityInfo != null
                                && c.getPackageName().equals(ri.activityInfo.packageName)
                                && "com.c10h.launcher.MainActivity"
                                .equals(ri.activityInfo.name)) {
                            apps.remove(i);
                        }
                    }
                    for (ResolveInfo ri : apps) {
                        if (ri.activityInfo != null) {
                            all.add(Item.app(ri.activityInfo.packageName,
                                    ri.activityInfo.name));
                        }
                    }
                    LauncherDb db = LauncherDb.get(c);
                    db.seed(c, apps);
                    final boolean classic = WorkspaceStore.classicMode(c);
                    final int container = classic
                            ? Item.CONTAINER_CLASSIC : Item.CONTAINER_DESKTOP;
                    LauncherDb.Loaded loaded = db.load(container);
                    ws.addAll(loaded.workspace);
                    fixed.addAll(loaded.fixed);
                    boolean changed = false;
                    if (classic && ws.isEmpty()) {
                        // 首次切到经典桌面：以抽屉桌面的文件夹为初始模板，
                        // 其余全部应用平铺；之后两套布局完全独立。
                        LauncherDb.Loaded drawer = db.load(Item.CONTAINER_DESKTOP);
                        Set<String> inFolders = new HashSet<String>();
                        for (Item f : drawer.workspace) {
                            if (!f.isFolder()) {
                                continue;
                            }
                            Item nf = Item.folder(f.name);
                            nf.screen = f.screen;
                            nf.cellX = f.cellX;
                            nf.cellY = f.cellY;
                            for (Item m : f.members) {
                                nf.members.add(Item.app(m.pkg, m.cls));
                                inFolders.add(m.pkg);
                            }
                            ws.add(nf);
                        }
                        for (ResolveInfo ri : apps) {
                            if (ri.activityInfo == null) {
                                continue;
                            }
                            if (c.getPackageName().equals(ri.activityInfo.packageName)
                                    && "com.c10h.launcher.MainActivity"
                                    .equals(ri.activityInfo.name)) {
                                continue;
                            }
                            if (inFolders.contains(ri.activityInfo.packageName)) {
                                continue;
                            }
                            Item it = Item.app(ri.activityInfo.packageName,
                                    ri.activityInfo.name);
                            placeFirstFree(c, ws, it);
                        }
                        changed = true;
                    }
                    if (classic && !ws.isEmpty()) {
                        // 经典桌面 = 所有应用。补齐缺失项（启动早期/旧版本可能只存了部分），
                        // 固定栏应用与用户主动移除的仍不重复平铺。
                        Set<String> present = new HashSet<String>();
                        for (Item f : fixed) {
                            present.add(f.key());
                        }
                        for (Item it : ws) {
                            if (it.isFolder()) {
                                for (Item m : it.members) {
                                    present.add(m.key());
                                }
                            } else {
                                present.add(it.key());
                            }
                        }
                        Set<String> removed = WorkspaceStore.classicMode(c)
                                ? WorkspaceStore.removedClassic(c)
                                : WorkspaceStore.removed(c);
                        for (ResolveInfo ri : apps) {
                            if (ri.activityInfo == null) {
                                continue;
                            }
                            if (c.getPackageName().equals(ri.activityInfo.packageName)
                                    && "com.c10h.launcher.MainActivity"
                                    .equals(ri.activityInfo.name)) {
                                continue;
                            }
                            String key = ri.activityInfo.packageName + "/"
                                    + ri.activityInfo.name;
                            if (present.contains(key) || removed.contains(key)) {
                                continue;
                            }
                            Item it = Item.app(ri.activityInfo.packageName,
                                    ri.activityInfo.name);
                            placeFirstFree(c, ws, it);
                            present.add(key);
                            changed = true;
                        }
                    }
                    if (classic && !ws.isEmpty()
                            && !WorkspaceStore.classicFolderSeeded(c)) {
                        // 兼容 1.17 已生成的经典布局：从抽屉模板补一次文件夹，
                        // 之后经典/抽屉各改各的，不再同步。
                        boolean hasFolder = false;
                        for (Item it : ws) {
                            if (it.isFolder()) {
                                hasFolder = true;
                                break;
                            }
                        }
                        if (!hasFolder) {
                            LauncherDb.Loaded drawer = db.load(Item.CONTAINER_DESKTOP);
                            Set<String> folderKeys = new HashSet<String>();
                            boolean copied = false;
                            for (Item f : drawer.workspace) {
                                if (!f.isFolder()) {
                                    continue;
                                }
                                Item nf = Item.folder(f.name);
                                nf.screen = 0;
                                nf.cellX = -1;
                                nf.cellY = -1;
                                for (Item m : f.members) {
                                    nf.members.add(Item.app(m.pkg, m.cls));
                                    folderKeys.add(m.key());
                                }
                                ws.add(nf);
                                copied = true;
                            }
                            if (copied) {
                                for (int i = ws.size() - 1; i >= 0; i--) {
                                    Item it = ws.get(i);
                                    if (!it.isFolder() && folderKeys.contains(it.key())) {
                                        ws.remove(i);
                                    }
                                }
                                changed = true;
                            }
                        }
                        WorkspaceStore.setClassicFolderSeeded(c, true);
                    }
                    if (reconcile(c, ws, fixed, apps)) {
                        changed = true;
                    }
                    if (changed) {
                        db.saveAll(ws, fixed, container);
                    }
                } catch (Throwable ignored) {
                }
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        if (cb != null) {
                            cb.onLoaded(ws, fixed, all, apps);
                        }
                    }
                });
            }
        }, "launcher-model").start();
    }

    private static boolean reconcile(Context c, List<Item> ws, List<Item> fixed,
                                     List<ResolveInfo> apps) {
        // 系统启动早期 queryIntentActivities 可能只返回少量应用；
        // 此时直接把桌面条目当“已卸载”删除会造成布局丢失。
        if (apps == null || apps.size() < 5) {
            return false;
        }
        boolean changed = false;
        Set<String> installed = new HashSet<String>();
        java.util.Map<String, ResolveInfo> byPkg =
                new java.util.HashMap<String, ResolveInfo>();
        for (ResolveInfo ri : apps) {
            if (ri.activityInfo != null) {
                installed.add(ri.activityInfo.packageName + "/" + ri.activityInfo.name);
                if (!byPkg.containsKey(ri.activityInfo.packageName)) {
                    byPkg.put(ri.activityInfo.packageName, ri);
                }
            }
        }
        Set<String> removed = WorkspaceStore.classicMode(c)
                ? WorkspaceStore.removedClassic(c)
                : WorkspaceStore.removed(c);
        // 查询结果可能只是"启动早期不完整"或"应用被临时停用"，
        // 删除前用 PackageManager 复核包是否真的不存在，避免误删经典桌面条目。
        java.util.Map<String, Boolean> pkgCache = new java.util.HashMap<String, Boolean>();

        // 工作区：卸载剔除、空文件夹剔除。
        // 应用升级后 launcher activity 类名可能变化：包还在时更新 cls，
        // 不能把文件夹成员/桌面图标当成“已卸载”删掉。
        for (int i = ws.size() - 1; i >= 0; i--) {
            Item it = ws.get(i);
            if (it.isFolder()) {
                for (int k = it.members.size() - 1; k >= 0; k--) {
                    Item m = it.members.get(k);
                    if (!installed.contains(m.key())) {
                        ResolveInfo ri = byPkg.get(m.pkg);
                        if (ri != null && ri.activityInfo != null) {
                            m.cls = ri.activityInfo.name;
                            changed = true;
                        } else if (!packageExists(c, pkgCache, m.pkg)) {
                            it.members.remove(k);
                            changed = true;
                        }
                    }
                }
                if (it.members.isEmpty()) {
                    ws.remove(i);
                    changed = true;
                }
            } else if (!installed.contains(it.key())) {
                ResolveInfo ri = byPkg.get(it.pkg);
                if (ri != null && ri.activityInfo != null) {
                    it.cls = ri.activityInfo.name;
                    changed = true;
                } else if (!packageExists(c, pkgCache, it.pkg)) {
                    ws.remove(i);
                    changed = true;
                }
            }
        }
        // 固定栏：卸载剔除（留空槽，不补位）
        for (int i = fixed.size() - 1; i >= 0; i--) {
            Item f = fixed.get(i);
            if (!installed.contains(f.key())) {
                ResolveInfo ri = byPkg.get(f.pkg);
                if (ri != null && ri.activityInfo != null) {
                    f.cls = ri.activityInfo.name;
                    changed = true;
                } else if (!packageExists(c, pkgCache, f.pkg)) {
                    fixed.remove(i);
                    changed = true;
                }
            }
        }
        // 固定栏应用不得在工作区重复（R8）
        Set<String> fixedKeys = new HashSet<String>();
        for (Item f : fixed) {
            fixedKeys.add(f.pkg);
        }
        for (int i = ws.size() - 1; i >= 0; i--) {
            Item it = ws.get(i);
            if (it.isFolder()) {
                for (int k = it.members.size() - 1; k >= 0; k--) {
                    if (fixedKeys.contains(it.members.get(k).pkg)) {
                        it.members.remove(k);
                        changed = true;
                    }
                }
                if (it.members.isEmpty()) {
                    ws.remove(i);
                    changed = true;
                }
            } else if (fixedKeys.contains(it.pkg)) {
                ws.remove(i);
                changed = true;
            }
        }

        // 硬约束：一个应用只能出现一次（文件夹优先），一个格子只能放一个条目。
        Set<String> placedKeys = new HashSet<String>();
        for (int i = ws.size() - 1; i >= 0; i--) {
            Item it = ws.get(i);
            if (!it.isFolder()) {
                continue;
            }
            for (int k = it.members.size() - 1; k >= 0; k--) {
                if (!placedKeys.add(it.members.get(k).pkg)) {
                    it.members.remove(k);
                    changed = true;
                }
            }
            if (it.members.isEmpty()) {
                ws.remove(i);
                changed = true;
            }
        }
        for (int i = ws.size() - 1; i >= 0; i--) {
            Item it = ws.get(i);
            if (it.isFolder()) {
                continue;
            }
            if (!placedKeys.add(it.pkg) || fixedKeys.contains(it.pkg)) {
                ws.remove(i);
                changed = true;
            }
        }
        Set<String> cells = new HashSet<String>();
        for (Item it : ws) {
            if (it.cellX < 0 || it.cellY < 0
                    || !cells.add(it.screen + ":" + it.cellY + ":" + it.cellX)) {
                assignFreeCell(c, it, cells);
                changed = true;
            }
        }

        // 新增应用：自动进工作区（受“自动添加图标”开关控制）；
        // 首次运行以当前已装应用为基线，只把之后新装的应用加入，避免首启灌满工作区。
        if (WorkspaceStore.addIconToHome(c)) {
            Set<String> baseline = WorkspaceStore.autoAddBaseline(c);
            if (baseline == null) {
                baseline = new HashSet<String>();
                for (ResolveInfo ri : apps) {
                    if (ri.activityInfo != null) {
                        baseline.add(ri.activityInfo.packageName + "/" + ri.activityInfo.name);
                    }
                }
                WorkspaceStore.setAutoAddBaseline(c, baseline);
            }
            Set<String> present = new HashSet<String>();
            for (Item it : ws) {
                if (it.isFolder()) {
                    for (Item m : it.members) {
                        present.add(m.pkg);
                    }
                } else {
                    present.add(it.pkg);
                }
            }
            for (ResolveInfo ri : apps) {
                if (ri.activityInfo == null) {
                    continue;
                }
                String key = ri.activityInfo.packageName + "/" + ri.activityInfo.name;
                if (present.contains(ri.activityInfo.packageName) || removed.contains(key)
                        || fixedKeys.contains(ri.activityInfo.packageName)
                        || baseline.contains(key)) {
                    continue;
                }
                Item it = Item.app(ri.activityInfo.packageName, ri.activityInfo.name);
                placeFirstFree(c, ws, it);
                present.add(ri.activityInfo.packageName);
                changed = true;
            }
        }
        // 固定栏保持槽位：rank 有效则不动，无效才补到第一个空槽（不压缩空槽）。
        int slotCount = 7;
        boolean[] occupied = new boolean[slotCount];
        for (Item f : fixed) {
            if (f.rank >= 0 && f.rank < slotCount) {
                occupied[f.rank] = true;
            } else {
                f.rank = -1;
                changed = true;
            }
        }
        for (Item f : fixed) {
            if (f.rank < 0) {
                for (int i = 0; i < slotCount; i++) {
                    if (!occupied[i]) {
                        f.rank = i;
                        occupied[i] = true;
                        changed = true;
                        break;
                    }
                }
            }
        }
        return changed;
    }

    /** 包是否仍然安装（包被停用/隐藏时 queryIntentActivities 查不到，但包还在）。 */
    private static boolean packageExists(Context c, java.util.Map<String, Boolean> cache,
                                         String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        Boolean hit = cache.get(pkg);
        if (hit != null) {
            return hit;
        }
        boolean ok;
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            ok = true;
        } catch (Throwable t) {
            ok = false;
        }
        cache.put(pkg, ok);
        return ok;
    }

    private static void assignFreeCell(Context c, Item it, Set<String> used) {
        DeviceProfile p = DeviceProfile.forDisplay(c.getResources().getDisplayMetrics());
        int cols = Math.max(1, p.cols);
        int rows = Math.max(1, p.rows);
        for (int screen = 0; screen < 100; screen++) {
            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < cols; x++) {
                    String key = screen + ":" + y + ":" + x;
                    if (!used.contains(key)) {
                        it.screen = screen;
                        it.cellX = x;
                        it.cellY = y;
                        used.add(key);
                        return;
                    }
                }
            }
        }
    }

    private static void placeFirstFree(Context c, List<Item> ws, Item it) {
        Set<String> used = new HashSet<String>();
        for (Item x : ws) {
            used.add(x.screen + ":" + x.cellY + ":" + x.cellX);
        }
        DeviceProfile p = DeviceProfile.forDisplay(c.getResources().getDisplayMetrics());
        int cols = Math.max(1, p.cols);
        int rows = Math.max(1, p.rows);
        for (int screen = 0; screen < 100; screen++) {
            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < cols; x++) {
                    String key = screen + ":" + y + ":" + x;
                    if (!used.contains(key)) {
                        it.screen = screen;
                        it.cellX = x;
                        it.cellY = y;
                        ws.add(it);
                        return;
                    }
                }
            }
        }
    }

    public static void saveAsync(final Context c, final List<Item> ws,
                                 final List<Item> fixed, final Runnable done) {
        saveAsync(c, ws, fixed, done, WorkspaceStore.classicMode(c)
                ? Item.CONTAINER_CLASSIC : Item.CONTAINER_DESKTOP);
    }

    public static void saveAsync(final Context c, final List<Item> ws,
                                 final List<Item> fixed, final Runnable done,
                                 final int container) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    LauncherDb.get(c).saveAll(ws, fixed, container);
                } catch (Throwable ignored) {
                }
                if (done != null) {
                    new Handler(Looper.getMainLooper()).post(done);
                }
            }
        }, "launcher-save").start();
    }
}
