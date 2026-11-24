package com.c10h.launcher;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 文件管理器（M9，AOSP DocumentsUI 行为）：
 * 根列表 → 多级目录 → 面包屑；列表排序/搜索/隐藏文件；新建/重命名/删除/详情；
 * 行本身可点击，修复旧版 ListView 子视图拦截点击导致“进不去目录”。
 */
public class FileBrowserActivity extends Activity {

    private static final int SORT_NAME = 0;
    private static final int SORT_DATE = 1;
    private static final int SORT_SIZE = 2;

    private File dir;
    private boolean atRoots = true;
    private boolean hidden;
    private boolean apkOnly;
    private boolean pickMode;
    private int sort = SORT_NAME;
    private boolean asc = true;

    private TextView crumb;
    private EditText search;
    private ListView list;
    private TextView empty;
    private final List<Object> rows = new ArrayList<Object>();
    private Adapter adapter;
    private String query = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            pickMode = getIntent() != null && getIntent().getBooleanExtra("pick", false);
            build();
        } catch (Throwable t) {
            TextView tv = new TextView(this);
            tv.setText("文件管理打开失败\n" + t);
            tv.setPadding(dp(20), dp(20), dp(20), dp(20));
            setContentView(tv);
        }
    }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF10161C);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF132433);
        int pad = dp(10);
        bar.setPadding(pad, dp(4), pad, dp(4));

        TextView back = new TextView(this);
        back.setText("←");
        back.setTextColor(0xFFE6EDF3);
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                up();
            }
        });
        bar.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));

        TextView title = new TextView(this);
        title.setText(pickMode ? "选择规则文件" : "文件");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setPadding(dp(6), 0, dp(10), 0);
        bar.addView(title);

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("搜索当前目录");
        search.setHintTextColor(0x88FFFFFF);
        search.setTextColor(0xFFFFFFFF);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        search.setPadding(dp(10), 0, dp(10), 0);
        search.setBackground(Ui.round(0x22FFFFFF, dp(16)));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
                query = s == null ? "" : s.toString().trim();
                refresh();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        bar.addView(search, new LinearLayout.LayoutParams(0, dp(38), 1f));

        TextView more = new TextView(this);
        more.setText("⋮");
        more.setTextColor(0xFFE6EDF3);
        more.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        more.setGravity(Gravity.CENTER);
        more.setFocusable(false);
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu(v);
            }
        });
        bar.addView(more, new LinearLayout.LayoutParams(dp(40), dp(40)));
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        crumb = new TextView(this);
        crumb.setTextColor(0xFF9FB3C8);
        crumb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        crumb.setPadding(pad, dp(4), pad, dp(4));
        crumb.setSingleLine(true);
        crumb.setEllipsize(TextUtils.TruncateAt.START);
        root.addView(crumb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));

        list = new ListView(this);
        list.setCacheColorHint(0x00000000);
        list.setDivider(null);
        list.setPadding(0, 0, 0, dp(8));
        list.setClipToPadding(false);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Object o = rows.get(pos);
                if (o instanceof Root) {
                    Root r = (Root) o;
                    atRoots = false;
                    dir = r.file;
                    apkOnly = r.apk;
                    refresh();
                } else if (o instanceof File) {
                    enter((File) o);
                }
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        empty = new TextView(this);
        empty.setText("此文件夹为空");
        empty.setTextColor(0x88FFFFFF);
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        empty.setGravity(Gravity.CENTER);
        empty.setVisibility(View.GONE);
        root.addView(empty, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

        setContentView(root);
        ensurePermission();
    }

    private void ensurePermission() {
        if (Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            refresh();
            return;
        }
        requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 101);
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] p, int[] g) {
        if (rc != 101) {
            return;
        }
        if (g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) {
            refresh();
        } else {
            empty.setText("未获得存储读取权限，点此重试");
            empty.setVisibility(View.VISIBLE);
            empty.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    requestPermissions(
                            new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 101);
                }
            });
        }
    }

    private void refresh() {
        try {
            rows.clear();
            if (atRoots) {
                buildRoots();
            } else if (dir != null) {
                buildFiles();
            }
            adapter.notifyDataSetChanged();
            list.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
            empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
            updateCrumb();
        } catch (Throwable t) {
            toast("刷新失败：" + t);
        }
    }

    private void buildRoots() {
        File internal = Environment.getExternalStorageDirectory();
        if (internal == null) {
            internal = new File("/storage/emulated/0");
        }
        rows.add(new Root("内部存储", internal, false));
        File dl = new File(internal, "Download");
        if (dl.isDirectory()) {
            rows.add(new Root("下载", dl, false));
        }
        rows.add(new Root("APK", internal, true));
        File storage = new File("/storage");
        File[] vols = storage.listFiles();
        if (vols != null) {
            for (File v : vols) {
                String n = v.getName();
                if ("emulated".equals(n) || "self".equals(n)) {
                    continue;
                }
                if (v.isDirectory() && v.canRead()) {
                    rows.add(new Root("USB · " + n, v, false));
                }
            }
        }
    }

    private void buildFiles() {
        File[] fs = dir.listFiles();
        List<File> out = new ArrayList<File>();
        if (fs != null) {
            for (File f : fs) {
                String n = f.getName();
                if (!hidden && n.startsWith(".")) {
                    continue;
                }
                if (query.length() > 0
                        && !n.toLowerCase(Locale.getDefault())
                        .contains(query.toLowerCase(Locale.getDefault()))) {
                    continue;
                }
                if (f.isDirectory()) {
                    out.add(f);
                } else if (!apkOnly
                        || n.toLowerCase(Locale.US).endsWith(".apk")) {
                    out.add(f);
                }
            }
        }
        final boolean byDate = sort == SORT_DATE;
        final boolean bySize = sort == SORT_SIZE;
        final boolean ascending = asc;
        Collections.sort(out, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                if (a.isDirectory() != b.isDirectory()) {
                    return a.isDirectory() ? -1 : 1;
                }
                int r;
                if (byDate) {
                    r = Long.compare(a.lastModified(), b.lastModified());
                } else if (bySize) {
                    r = Long.compare(a.length(), b.length());
                } else {
                    r = a.getName().toLowerCase(Locale.US)
                            .compareTo(b.getName().toLowerCase(Locale.US));
                }
                return ascending ? r : -r;
            }
        });
        rows.addAll(out);
    }

    private void updateCrumb() {
        if (atRoots) {
            crumb.setText("根目录");
            return;
        }
        String p = dir == null ? "/" : dir.getAbsolutePath();
        crumb.setText(p + (rows.isEmpty() ? "（空）" : ""));
    }

    private void enter(File f) {
        if (f.isDirectory()) {
            atRoots = false;
            dir = f;
            query = "";
            search.setText("");
            refresh();
            return;
        }
        if (pickMode) {
            // 规则导入的选文件模式：直接回传 file:// URI。
            try {
                setResult(RESULT_OK, new Intent().setData(Uri.fromFile(f)));
            } catch (Throwable ignored) {
            }
            finish();
            return;
        }
        String n = f.getName().toLowerCase(Locale.US);
        if (n.endsWith(".apk")) {
            try {
                Intent i = new Intent(this, InstallerActivity.class);
                i.putExtra("apk_path", f.getAbsolutePath());
                startActivity(i);
            } catch (Throwable t) {
                toast("安装器打开失败：" + t);
            }
        } else {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(Uri.fromFile(f), "*/*");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Throwable t) {
                toast("无应用可打开该文件");
            }
        }
    }

    private void up() {
        if (atRoots) {
            finish();
            return;
        }
        boolean internal = dir != null
                && dir.getAbsolutePath().equals(
                Environment.getExternalStorageDirectory().getAbsolutePath());
        if (dir == null || dir.getParentFile() == null || internal) {
            atRoots = true;
            dir = null;
            apkOnly = false;
            query = "";
            search.setText("");
            refresh();
            return;
        }
        dir = dir.getParentFile();
        refresh();
    }

    @Override
    public void onBackPressed() {
        up();
    }

    private void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "新建文件夹");
        pm.getMenu().add(0, 2, 1, "排序：名称");
        pm.getMenu().add(0, 3, 2, "排序：日期");
        pm.getMenu().add(0, 4, 3, "排序：大小");
        pm.getMenu().add(0, 5, 4, asc ? "降序" : "升序");
        pm.getMenu().add(0, 6, 5, hidden ? "隐藏隐藏文件" : "显示隐藏文件");
        pm.getMenu().add(0, 7, 6, apkOnly ? "显示所有文件" : "仅显示 APK");
        pm.getMenu().add(0, 8, 7, "刷新");
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(android.view.MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        newFolder();
                        return true;
                    case 2:
                        sort = SORT_NAME;
                        refresh();
                        return true;
                    case 3:
                        sort = SORT_DATE;
                        refresh();
                        return true;
                    case 4:
                        sort = SORT_SIZE;
                        refresh();
                        return true;
                    case 5:
                        asc = !asc;
                        refresh();
                        return true;
                    case 6:
                        hidden = !hidden;
                        refresh();
                        return true;
                    case 7:
                        apkOnly = !apkOnly;
                        refresh();
                        return true;
                    case 8:
                        refresh();
                        return true;
                    default:
                        return false;
                }
            }
        });
        pm.show();
    }

    private void newFolder() {
        if (atRoots || dir == null) {
            toast("请先进入一个目录");
            return;
        }
        final EditText input = new EditText(this);
        input.setHint("文件夹名");
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(20), dp(10), dp(20), 0);
        wrap.addView(input);
        new AlertDialog.Builder(this)
                .setTitle("新建文件夹")
                .setView(wrap)
                .setPositiveButton("创建", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String n = input.getText().toString().trim();
                        if (n.isEmpty()) {
                            toast("名称不能为空");
                            return;
                        }
                        File f = new File(dir, n);
                        if (f.exists()) {
                            toast("同名已存在");
                        } else if (f.mkdirs()) {
                            refresh();
                        } else {
                            toast("创建失败（权限/只读）");
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void fileMenu(final File f) {
        String[] items = f.isDirectory()
                ? new String[]{"打开", "重命名", "删除", "详情"}
                : new String[]{"打开", "重命名", "删除", "详情"};
        new AlertDialog.Builder(this)
                .setTitle(f.getName())
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (w == 0) {
                            enter(f);
                        } else if (w == 1) {
                            rename(f);
                        } else if (w == 2) {
                            delete(f);
                        } else {
                            details(f);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void rename(final File f) {
        final EditText input = new EditText(this);
        input.setText(f.getName());
        input.setSelection(f.getName().length());
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(20), dp(10), dp(20), 0);
        wrap.addView(input);
        new AlertDialog.Builder(this)
                .setTitle("重命名")
                .setView(wrap)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String n = input.getText().toString().trim();
                        if (n.isEmpty()) {
                            toast("名称不能为空");
                            return;
                        }
                        File to = new File(f.getParentFile(), n);
                        if (to.exists()) {
                            toast("同名已存在");
                        } else if (f.renameTo(to)) {
                            refresh();
                        } else {
                            toast("重命名失败（权限/只读）");
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void delete(final File f) {
        new AlertDialog.Builder(this)
                .setTitle("删除")
                .setMessage("删除「" + f.getName() + "」？不可恢复。")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (deleteRec(f)) {
                            refresh();
                        } else {
                            toast("删除失败（权限/占用）");
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private boolean deleteRec(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    if (!deleteRec(k)) {
                        return false;
                    }
                }
            }
        }
        return f.delete();
    }

    private void details(File f) {
        String msg = "路径：" + f.getAbsolutePath()
                + "\n类型：" + (f.isDirectory() ? "文件夹" : ext(f.getName()))
                + "\n大小：" + (f.isDirectory() ? "-" : human(f.length()))
                + "\n修改：" + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                .format(new Date(f.lastModified()))
                + "\n可读：" + f.canRead() + "  可写：" + f.canWrite();
        new AlertDialog.Builder(this)
                .setTitle(f.getName())
                .setMessage(msg)
                .setPositiveButton("关闭", null)
                .show();
    }

    private String ext(String n) {
        int i = n.lastIndexOf('.');
        return i < 0 ? "文件" : n.substring(i + 1).toUpperCase(Locale.US);
    }

    private String human(long b) {
        if (b < 1024) {
            return b + " B";
        }
        if (b < 1048576) {
            return String.format(Locale.US, "%.1f KB", b / 1024f);
        }
        if (b < 1073741824L) {
            return String.format(Locale.US, "%.1f MB", b / 1048576f);
        }
        return String.format(Locale.US, "%.2f GB", b / 1073741824f);
    }

    private final class Adapter extends BaseAdapter {

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
            } else {
                row = new LinearLayout(FileBrowserActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(16), dp(10), dp(10), dp(10));
                row.setMinimumHeight(dp(56));
                row.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);

                TextView kind = new TextView(FileBrowserActivity.this);
                kind.setGravity(Gravity.CENTER);
                kind.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                kind.setTextColor(0xFF64B5F6);
                row.addView(kind, new LinearLayout.LayoutParams(dp(48), dp(48)));

                LinearLayout mid = new LinearLayout(FileBrowserActivity.this);
                mid.setOrientation(LinearLayout.VERTICAL);
                TextView name = new TextView(FileBrowserActivity.this);
                name.setTextColor(0xFFE8EEF4);
                name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                mid.addView(name);
                TextView meta = new TextView(FileBrowserActivity.this);
                meta.setTextColor(0xFF7E8C99);
                meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                mid.addView(meta);
                row.addView(mid, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView more = new TextView(FileBrowserActivity.this);
                more.setText("⋮");
                more.setTextColor(0xFF9FB3C8);
                more.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
                more.setGravity(Gravity.CENTER);
                more.setFocusable(false);
                more.setClickable(true);
                more.setPadding(dp(10), 0, dp(10), 0);
                row.addView(more, new LinearLayout.LayoutParams(dp(44), dp(44)));
            }

            Object o = rows.get(position);
            TextView kind = (TextView) row.getChildAt(0);
            LinearLayout mid = (LinearLayout) row.getChildAt(1);
            TextView name = (TextView) mid.getChildAt(0);
            TextView meta = (TextView) mid.getChildAt(1);
            TextView more = (TextView) row.getChildAt(2);

            if (o instanceof Root) {
                final Root r = (Root) o;
                kind.setText("▣");
                kind.setTextColor(0xFF64B5F6);
                name.setText(r.name);
                meta.setText(r.file.getAbsolutePath());
                more.setVisibility(View.GONE);
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        atRoots = false;
                        dir = r.file;
                        apkOnly = r.apk;
                        query = "";
                        if (search != null) {
                            search.setText("");
                        }
                        refresh();
                    }
                });
                row.setOnLongClickListener(null);
            } else {
                final File f = (File) o;
                kind.setText(f.isDirectory() ? "▣" : ext(f.getName()));
                kind.setTextColor(f.isDirectory() ? 0xFF64B5F6 : 0xFFFFB74D);
                name.setText(f.getName());
                meta.setText((f.isDirectory()
                        ? (f.list() == null ? 0 : f.list().length) + " 项"
                        : human(f.length()))
                        + " · " + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                        .format(new Date(f.lastModified())));
                more.setVisibility(View.VISIBLE);
                more.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        fileMenu(f);
                    }
                });
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        enter(f);
                    }
                });
                row.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        fileMenu(f);
                        return true;
                    }
                });
            }
            return row;
        }
    }

    private static final class Root {
        final String name;
        final File file;
        final boolean apk;

        Root(String n, File f, boolean a) {
            this.name = n;
            this.file = f;
            this.apk = a;
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }
}
