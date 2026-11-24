package com.c10h.launcher;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * C10H 桌面（M9）：AOSP Launcher3 结构。
 * QSB → PagedWorkspaceView（6×5）→ FixedBar（7 格）→ 导航浮层；
 * 应用抽屉覆盖层；长按空白选项；无自绘顶栏。
 */
public class MainActivity extends Activity implements DragController.Host {

    private static final String PREFS = "c10h_launcher";

    private FrameLayout root;
    private LinearLayout column;
    private QsbView qsb;
    private MaxHeightScrollView widgetScroll;
    private LinearLayout widgetCol;
    private FrameLayout wsBox;
    private PagedWorkspaceView workspaceView;
    private Workspace workspace;
    private PageIndicatorView pageIndicator;
    private LinearLayout fixedRow;
    private FixedBar fixedBar;
    private FrameLayout navHost;
    private NavBarView navBar;
    private AllAppsView allApps;
    private DragController dragController;

    private FloatingNav floatingNav;
    private static FloatingNav sFloatingNav;
    private static int sResumedCount;

    private QsPanel qsPanel;
    private WidgetArea widgetArea;

    private DeviceProfile profile;
    private int iconPx;
    private float textSp;

    private final List<Item> workspaceItems = new ArrayList<Item>();
    private final List<Item> fixedItems = new ArrayList<Item>();
    private final List<Item> allAppsItems = new ArrayList<Item>();
    private final Map<String, String> labelCache = new HashMap<String, String>();
    private final Map<String, Drawable> iconCache = new HashMap<String, Drawable>();
    private final Map<String, Integer> badgeMap = new HashMap<String, Integer>();
    private final Map<String, Drawable> folderMinis = new HashMap<String, Drawable>();

    private final Handler handler = new Handler();
    private PopupMenu popup;
    private View optionsOverlay;
    private View navSpacer;
    private SwipeUpGesture swipeDesktop;
    private boolean swipeBottom;
    private float swipeBottomY;

    private BroadcastReceiver pkgReceiver;
    private boolean pkgRegistered;
    private BroadcastReceiver badgeReceiver;
    private boolean badgeRegistered;
    private BroadcastReceiver settingsReceiver;
    private boolean settingsRegistered;
    private boolean allAppsDirty = true;
    private boolean loading;
    private boolean pendingReload;
    private float upX;
    private float upY;
    private boolean upTracking;
    private boolean lastClassic;
    private boolean lastNavReversed;
    private boolean lastGesture;
    private boolean swipeConsumed;
    private SharedPreferences prefs;
    private FolderOverlay folderOverlay;
    private View appsButton;
    private android.widget.ImageView wallpaperView;
    private android.graphics.Bitmap wallpaperBmp;
    private android.graphics.Bitmap blurredWallpaper;
    private boolean wpReceiverRegistered;
    private BroadcastReceiver wpReceiver;
    private Item folderDragSrc;

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
            tv.setText("桌面初始化失败\n" + String.valueOf(t));
            tv.setTextColor(0xFFFF6B6B);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            tv.setGravity(Gravity.CENTER);
            err.addView(tv);
            setContentView(err);
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 23
                    && checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        android.Manifest.permission.READ_EXTERNAL_STORAGE}, 1001);
            }
        } catch (Throwable ignored) {
        }
        try {
            android.content.IntentFilter f = new android.content.IntentFilter();
            f.addAction(android.content.Intent.ACTION_WALLPAPER_CHANGED);
            f.addAction("com.c10h.launcher.action.DEBUG_SET_WALLPAPER");
            wpReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    try {
                        if (intent != null && "com.c10h.launcher.action.DEBUG_SET_WALLPAPER"
                                .equals(intent.getAction())) {
                            int color = intent.getIntExtra("color", 0xFF3F51B5);
                            int w = getResources().getDisplayMetrics().widthPixels;
                            int h = getResources().getDisplayMetrics().heightPixels;
                            android.graphics.Bitmap bmp = android.graphics.Bitmap
                                    .createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
                            android.graphics.Canvas cv = new android.graphics.Canvas(bmp);
                            cv.drawColor(color);
                            android.app.WallpaperManager wpm =
                                    android.app.WallpaperManager.getInstance(MainActivity.this);
                            try {
                                wpm.setBitmap(bmp);
                                android.util.Log.i("C10HWP", "setBitmap ok " + Integer.toHexString(color));
                            } catch (Throwable t) {
                                android.util.Log.e("C10HWP", "setBitmap fail " + t);
                            }
                        }
                        refreshWallpaper();
                    } catch (Throwable t) {
                        android.util.Log.e("C10HWP", "receiver fail " + t);
                    }
                }
            };
            registerReceiver(wpReceiver, f);
            wpReceiverRegistered = true;
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == 1001) {
            try {
                recreate();
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------ UI

    private void buildUi() {
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        // 本机 ROM 的"休眠=相册屏保"会让屏幕只变暗不息屏，首次运行纠正一次
        // （设置读写放后台，避免拖慢冷启动）。
        try {
            final android.content.Context app = getApplicationContext();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        PowerActions.ensureSleepNotHijacked(app);
                    } catch (Throwable ignored) {
                    }
                }
            }, "c10h-screenfix").start();
        } catch (Throwable ignored) {
        }
        profile = DeviceProfile.forDisplay(getResources().getDisplayMetrics());
        // 首次运行把过短的屏幕超时提升到 2 分钟（用户已设更长或"永不"时不覆盖）。
        try {
            final android.content.Context app = getApplicationContext();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        PowerActions.ensureScreenTimeoutDefault(app);
                        // v2：系统超时始终同步为用户选择值（不再用 MAX 来“防睡”）。
                        ScreenKeepAlive.applySystemTimeout(app);
                    } catch (Throwable ignored) {
                    }
                }
            }, "c10h-timeout-default").start();
        } catch (Throwable ignored) {
        }
        iconPx = dp(profile.iconSizeDp);
        textSp = profile.textSizeSp;

        root = new FrameLayout(this);
        root.setBackgroundColor(0x00000000);
        root.setClipChildren(false);

        try {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
            getWindow().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
            getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(0x00000000));
        } catch (Throwable ignored) {
        }

        // 壁纸层：自绘系统静态壁纸，支持换壁纸后即时刷新。
        wallpaperView = new android.widget.ImageView(this);
        wallpaperView.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        root.addView(wallpaperView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        refreshWallpaper();

        // AOSP Launcher Pie 的顶部状态栏 scrim 与底部热区 scrim（渐变黑）。
        try {
            getWindow().setStatusBarColor(0x00000000);
        } catch (Throwable ignored) {
        }
        try {
            View topScrim = new View(this);
            topScrim.setBackground(new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x57000000, 0x00000000}));
            root.addView(topScrim, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(72),
                    Gravity.TOP));
            View bottomScrim = new View(this);
            bottomScrim.setBackground(new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x00000000, 0x26000000}));
            root.addView(bottomScrim, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(200),
                    Gravity.BOTTOM));
        } catch (Throwable ignored) {
        }

        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);

        View topSpacer = new View(this);
        column.addView(topSpacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));

        widgetScroll = new MaxHeightScrollView(this);
        widgetScroll.setMaxHeightFraction(0.26f);
        widgetCol = new LinearLayout(this);
        widgetCol.setOrientation(LinearLayout.VERTICAL);
        widgetScroll.addView(widgetCol, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        widgetScroll.setVisibility(View.GONE);
        column.addView(widgetScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        wsBox = new FrameLayout(this);
        workspaceView = new PagedWorkspaceView(this);
        workspaceView.setGrid(profile.cols, profile.rows);
        workspaceView.setBinder(new PagedWorkspaceView.Binder() {
            @Override
            public View bind(Item item) {
                return createIcon(item, "workspace");
            }
        });
        workspace = new Workspace();
        workspace.attach(workspaceView, new Workspace.Callback() {
            @Override
            public void onPageChanged(int page, int pageCount) {
                pageIndicator.update(pageCount, page);
            }

            @Override
            public void onPageProgress(float pageFloat) {
                pageIndicator.setProgress(pageFloat);
            }

            @Override
            public void onEmptyLongPress() {
                showOptions();
            }

            @Override
            public void onSwipeUp() {
                showAllApps(false);
            }
        });
        wsBox.addView(workspaceView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        pageIndicator = new PageIndicatorView(this);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                dp(240), dp(20), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        ilp.bottomMargin = dp(2);
        wsBox.addView(pageIndicator, ilp);
        pageIndicator.setVisibility(View.GONE);
        column.addView(wsBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        fixedRow = new LinearLayout(this);
        fixedBar = new FixedBar(this, fixedRow, new FixedBar.Listener() {
            @Override
            public void onAppClick(Item item, View anchor) {
                openItem(item, anchor);
            }

            @Override
            public void onAppLongClick(Item item, View anchor) {
                dragController.start((IconView) anchor, item, "fixed");
            }

            @Override
            public void onAppMenu(Item item, View anchor) {
                showItemMenu(item, anchor);
            }
        }, profile.hotseatCount, iconPx, textSp);
        column.addView(fixedRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(96)));

        // 导航区占位：navHost 作为 root 的独立底栏，避免全屏抽屉/浮层遮挡，
        // 也不参与工作区的 LinearLayout 高度计算（规格 01 §5）。
        navSpacer = new View(this);
        column.addView(navSpacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 全部应用入口：AOSP 风格底部 chevron（无背景、无圆钮）。
        TextView appsBtn = new TextView(this);
        appsBtn.setText("︿");
        appsBtn.setGravity(Gravity.CENTER);
        appsBtn.setTextColor(0xCCFFFFFF);
        appsBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        appsBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAllApps(false);
            }
        });
        FrameLayout.LayoutParams appsLp = new FrameLayout.LayoutParams(
                dp(80), dp(32), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        appsLp.bottomMargin = dp(156);
        root.addView(appsBtn, appsLp);
        appsButton = appsBtn;

        allApps = new AllAppsView(this, new AllAppsView.Listener() {            @Override
            public void onAppClick(Item item, View anchor) {
                openItem(item, anchor);
            }

            @Override
            public void onAppLongClick(Item item, View anchor) {
                dragController.start((IconView) anchor, item, "allapps");
                allApps.close(false);
                restoreWorkspaceFromAllApps();
            }

            @Override
            public void onAppMenu(Item item, View anchor) {
                showItemMenu(item, anchor);
            }

            @Override
            public void onClosed() {
                restoreWorkspaceFromAllApps();
            }
        }, profile.cols, dp(56), 12f);
        FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        // 给自研导航条留出 48dp：抽屉全屏时导航仍可用。
        alp.bottomMargin = Ui.dp(this, 48);
        root.addView(allApps, alp);

        // 自研导航条：root 独立底栏，位于抽屉之上，始终可点。
        navHost = new FrameLayout(this);
        navBar = new NavBarView(this);
        navHost.setBackgroundColor(0xFF101B26);
        navHost.addView(navBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(navHost, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48), Gravity.BOTTOM));

        setContentView(root);
        hideSystemNav();

        dragController = new DragController(this, workspace, fixedBar, root);

        wsBox.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or, int ob) {
                computeGrid();
            }
        });
        wsBox.post(new Runnable() {
            @Override
            public void run() {
                computeGrid();
            }
        });

        widgetArea = new WidgetArea(this, widgetCol);
        try {
            if (Settings.canDrawOverlays(this)) {
                if (sFloatingNav == null) {
                    sFloatingNav = new FloatingNav(this);
                }
                floatingNav = sFloatingNav;
            }
        } catch (Throwable ignored) {
        }
        applyNavVisibility(false);
        loadApps();
    }

    private void hideSystemNav() {
        try {
            boolean on = WorkspaceStore.navEnabled(this);
            // 禁用系统导航：设备级隐藏（与真机“没有系统导航条”保持一致），
            // 桌面用自带导航条；不再创建任何悬浮导航窗口。
            try {
                android.provider.Settings.Global.putString(getContentResolver(),
                        "policy_control", on ? "immersive.navigation=*" : null);
            } catch (Throwable ignored) {
            }
            int flags = getWindow().getDecorView().getSystemUiVisibility();
            if (!on) {
                // 自研导航关闭时恢复系统导航条。
                flags &= ~(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                getWindow().getDecorView().setSystemUiVisibility(flags);
                return;
            }
            // 自研导航开启：隐藏系统导航条，桌面内容延伸到屏幕底部。
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemNav();
        }
    }

    private void computeGrid() {
        try {
            if (wsBox == null || workspaceView == null) {
                return;
            }
            int hDp = (int) (wsBox.getHeight() / getResources().getDisplayMetrics().density);
            int cell = profile.iconSizeDp + 28;
            int rows = Math.max(2, Math.min(profile.rows, hDp / Math.max(1, cell)));
            int oldRows = workspaceView.rows();
            workspaceView.setGrid(profile.cols, rows);
            if (oldRows != rows && !workspaceItems.isEmpty()) {
                // 行数变化会改变 pageSize，旧格子必须重新校正，否则末行图标
                // 会被 CellLayout clamp 到同一格重叠（看起来少应用）。
                if (reflow()) {
                    persist();
                }
                render();
            }
        } catch (Throwable ignored) {
        }
    }

    private IconView createIcon(final Item item, final String source) {
        IconView v = new IconView(this, iconPx, textSp);
        v.setItem(item, labelOf(item), badgeOf(item));
        if (item.isFolder()) {
            Drawable[] minis = new Drawable[Math.min(4, item.members.size())];
            for (int i = 0; i < minis.length; i++) {
                minis[i] = iconForPkg(item.members.get(i).pkg);
            }
            v.setFolderIcons(minis);
        } else {
            v.setIcon(iconForPkg(item.pkg));
        }
        v.setListener(new IconView.Listener() {
            @Override
            public void onIconClick(IconView view, Item it) {
                openItem(it, view);
            }

            @Override
            public void onIconLongClick(IconView view, Item it) {
                dragController.start(view, it, source);
            }

            @Override
            public void onIconMenu(IconView view, Item it) {
                showItemMenu(it, view);
            }
        });
        return v;
    }

    // ------------------------------------------------------------ data

    private void loadApps() {
        if (loading) {
            // 加载中再次请求（典型：启动加载还没回、用户已切换桌面模式）不能直接丢弃，
            // 否则会一直显示上一套容器的条目，直到下次 resume。
            pendingReload = true;
            return;
        }
        loading = true;
        final int want = desktopContainer();
        LauncherModel.loadAsync(this, new LauncherModel.Callback() {
            @Override
            public void onLoaded(List<Item> ws, List<Item> fixed, List<Item> all,
                                 List<android.content.pm.ResolveInfo> apps) {
                loading = false;
                if (desktopContainer() != want) {
                    // 加载期间用户切换了桌面模式：这批数据属于另一套容器，
                    // 直接丢弃，避免 persist() 把 A 容器条目写进 B 容器覆盖布局。
                    pendingReload = true;
                } else {
                    workspaceItems.clear();
                    workspaceItems.addAll(ws);
                    fixedItems.clear();
                    fixedItems.addAll(fixed);
                    allAppsItems.clear();
                    allAppsItems.addAll(all);
                    buildCaches(apps);
                    // 载入后先校正格子：DB 里可能因早期 6×5/当前网格不一致
                    // 出现越界或重复 cell，直接渲染会让部分图标重叠“消失”。
                    if (reflow()) {
                        persist();
                    }
                    render();
                }
                if (pendingReload) {
                    pendingReload = false;
                    loadApps();
                }
            }
        });
    }

    /** 包级：安装/卸载/设置变化后刷新。 */
    void refreshApps() {
        loadApps();
    }

    /** 读取系统壁纸并绘制；换壁纸后调用即可即时刷新（后台解码，避免启动卡顿）。 */
    void refreshWallpaper() {
        refreshWallpaperAsync();
    }

    private void refreshWallpaperAsync() {
        final android.widget.ImageView iv = wallpaperView;
        if (iv == null) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final android.graphics.Bitmap bmp = loadWallpaperBitmap();
                if (bmp == null) {
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        applyWallpaper(iv, bmp);
                    }
                });
            }
        }, "c10h-wallpaper").start();
    }

    private android.graphics.Bitmap loadWallpaperBitmap() {
        int sw = 0;
        int sh = 0;
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            sw = Math.max(1, dm.widthPixels);
            sh = Math.max(1, dm.heightPixels);
        } catch (Throwable ignored) {
        }
        // 1) 按屏幕尺寸采样解码系统壁纸文件，RGB_565，单副本。
        try {
            android.app.WallpaperManager wpm =
                    android.app.WallpaperManager.getInstance(this);
            android.os.ParcelFileDescriptor pfd =
                    wpm.getWallpaperFile(android.app.WallpaperManager.FLAG_SYSTEM);
            if (pfd != null) {
                android.graphics.BitmapFactory.Options o =
                        new android.graphics.BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFileDescriptor(
                        pfd.getFileDescriptor(), null, o);
                int sample = 1;
                while (o.outWidth / (sample * 2) >= sw
                        && o.outHeight / (sample * 2) >= sh) {
                    sample *= 2;
                }
                o.inJustDecodeBounds = false;
                o.inSampleSize = sample;
                o.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
                android.graphics.Bitmap bmp =
                        android.graphics.BitmapFactory.decodeFileDescriptor(
                                pfd.getFileDescriptor(), null, o);
                pfd.close();
                if (bmp != null) {
                    android.util.Log.i("C10HWP", "decoded " + bmp.getWidth() + "x"
                            + bmp.getHeight() + " sample=" + sample);
                    return fitToScreen(bmp, sw, sh);
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("C10HWP", "file decode fail " + t);
        }
        // 2) 回退：drawable 只画到屏幕尺寸的 RGB_565 位图，不持有原图。
        try {
            android.app.WallpaperManager wpm =
                    android.app.WallpaperManager.getInstance(this);
            android.graphics.drawable.Drawable wp = wpm.getDrawable();
            if (wp != null) {
                android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                        sw, sh, android.graphics.Bitmap.Config.RGB_565);
                android.graphics.Canvas cv = new android.graphics.Canvas(bmp);
                float iw = wp.getIntrinsicWidth();
                float ih = wp.getIntrinsicHeight();
                if (iw <= 0 || ih <= 0) {
                    iw = sw;
                    ih = sh;
                }
                float scale = Math.max(sw / iw, sh / ih);
                int w = Math.max(1, (int) (iw * scale));
                int h = Math.max(1, (int) (ih * scale));
                int dx = (sw - w) / 2;
                int dy = (sh - h) / 2;
                wp.setBounds(dx, dy, dx + w, dy + h);
                wp.draw(cv);
                return bmp;
            }
        } catch (Throwable t) {
            android.util.Log.e("C10HWP", "load fail " + t);
        }
        return null;
    }

    private android.graphics.Bitmap fitToScreen(android.graphics.Bitmap bmp,
                                                int sw, int sh) {
        if (bmp == null) {
            return null;
        }
        if (bmp.getWidth() <= sw && bmp.getHeight() <= sh) {
            return bmp;
        }
        try {
            float scale = Math.max(sw / (float) bmp.getWidth(),
                    sh / (float) bmp.getHeight());
            int w = Math.max(1, (int) (bmp.getWidth() * scale));
            int h = Math.max(1, (int) (bmp.getHeight() * scale));
            android.graphics.Bitmap out =
                    android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true);
            if (out != bmp) {
                bmp.recycle();
            }
            return out;
        } catch (Throwable t) {
            return bmp;
        }
    }

    android.graphics.Bitmap wallpaperBitmap() {
        return wallpaperBmp;
    }

    /** 手势导航背景用的模糊壁纸，只生成一次。 */
    synchronized android.graphics.Bitmap blurredWallpaper() {
        if (blurredWallpaper == null && wallpaperBmp != null) {
            try {
                blurredWallpaper = Blur.blur(wallpaperBmp, 18);
            } catch (Throwable ignored) {
            }
        }
        return blurredWallpaper;
    }

    private void applyWallpaper(android.widget.ImageView iv, android.graphics.Bitmap bmp) {
        try {
            if (iv != wallpaperView) {
                return;
            }
            wallpaperBmp = bmp;
            iv.setImageBitmap(bmp);
            android.util.Log.i("C10HWP", "refresh " + bmp.getWidth() + "x"
                    + bmp.getHeight());
        } catch (Throwable t) {
            android.util.Log.e("C10HWP", "apply fail " + t);
        }
    }

    /** 文件夹成员移出到工作区尾部；空文件夹删除（AOSP Folder 行为）。 */
    void moveMemberOut(Item folder, Item member) {
        try {
            if (folder == null || member == null) {
                return;
            }
            folder.members.remove(member);
            if (folder.members.isEmpty()) {
                workspaceItems.remove(folder);
            }
            Item app = Item.app(member.pkg, member.cls);
            for (int i = workspaceItems.size() - 1; i >= 0; i--) {
                Item w = workspaceItems.get(i);
                if (w != null && !w.isFolder() && w.key().equals(app.key())) {
                    workspaceItems.remove(i);
                }
            }
            workspaceItems.add(app);
            reflow();
            persist();
            render();
            try {
                if (folderOverlay != null) {
                    root.removeView(folderOverlay);
                    folderOverlay = null;
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    /** 文件夹内长按成员：关闭面板，在 Activity 窗口内重新发起拖拽（AOSP 拖出文件夹）。 */
    void startFolderDrag(final Item folder, final Item member, View anchor) {
        try {
            folderDragSrc = folder;
            final int[] loc = new int[2];
            if (anchor != null) {
                anchor.getLocationOnScreen(loc);
            }
            if (folderOverlay != null) {
                root.removeView(folderOverlay);
                folderOverlay = null;
            }
            root.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        final IconView ghost = new IconView(MainActivity.this,
                                dp(60), 12f);
                        ghost.setItem(member, labelCache.containsKey(member.key())
                                ? labelCache.get(member.key()) : member.pkg,
                                badgeOf(member));
                        ghost.setIcon(iconForPkg(member.pkg));
                        ghost.setAlpha(0.01f);
                        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                                dp(60), dp(60));
                        lp.leftMargin = Math.max(0, loc[0] - dp(30));
                        lp.topMargin = Math.max(0, loc[1] - dp(30));
                        root.addView(ghost, lp);
                        if (dragController != null) {
                            dragController.start(ghost, member, "folder");
                        }
                        root.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    root.removeView(ghost);
                                } catch (Throwable ignored) {
                                }
                            }
                        }, 1500);
                    } catch (Throwable ignored) {
                    }
                }
            }, 180);
        } catch (Throwable t) {
            toast("拖动失败：" + t);
        }
    }

    /** 落点成功后，把成员从来源文件夹摘除；空文件夹一并消失。 */
    private void detachFromFolderSource(Item member) {
        try {
            Item src = folderDragSrc;
            folderDragSrc = null;
            if (src == null || member == null) {
                return;
            }
            for (int i = src.members.size() - 1; i >= 0; i--) {
                if (src.members.get(i).key().equals(member.key())) {
                    src.members.remove(i);
                    break;
                }
            }
            if (src.members.isEmpty()) {
                workspaceItems.remove(src);
            }
        } catch (Throwable ignored) {
        }
    }

    private void buildCaches(List<android.content.pm.ResolveInfo> infos) {
        try {
            labelCache.clear();
            iconCache.clear();
            folderMinis.clear();
            if (infos == null) {
                return;
            }
            android.content.pm.PackageManager pm = getPackageManager();
            for (android.content.pm.ResolveInfo ri : infos) {
                if (ri.activityInfo == null) {
                    continue;
                }
                String key = ri.activityInfo.packageName + "/" + ri.activityInfo.name;
                try {
                    CharSequence l = ri.loadLabel(pm);
                    labelCache.put(key, l == null
                            ? ri.activityInfo.packageName : l.toString());
                } catch (Throwable ignored) {
                }
            }
            // 图标不再预加载：iconForPkg() 首次使用时按包懒加载并缓存，
            // 冷启动只加载桌面实际显示的十几个图标。
        } catch (Throwable ignored) {
        }
    }

    private void render() {
        try {
            Set<String> pinned = new HashSet<String>();
            for (Item f : fixedItems) {
                pinned.add(f.key());
            }
            List<Item> visible = new ArrayList<Item>();
            if (WorkspaceStore.classicMode(this)) {
                // 经典桌面使用独立容器（CONTAINER_CLASSIC），完整布局由
                // LauncherModel 首次生成；这里不再平铺 allApps，避免与抽屉共享数据。
                visible.addAll(workspaceItems);
                if (visible.isEmpty()) {
                    visible.addAll(allAppsItems);
                }
            } else {
                for (Item it : workspaceItems) {
                    if (it.isFolder() || !pinned.contains(it.key())) {
                        visible.add(it);
                    }
                }
            }
            workspaceView.setItems(visible);
            fixedBar.setItems(fixedItems);
            if (appsButton != null) {
                appsButton.setVisibility(WorkspaceStore.classicMode(this)
                        ? View.GONE : View.VISIBLE);
            }
            if (allApps.isOpen()) {
                allApps.setItems(allAppsItems);
                allAppsDirty = false;
            } else {
                allAppsDirty = true;
            }
            workspaceView.setBadges(badgeMap);
        } catch (Throwable t) {
            toast("桌面刷新失败：" + t);
        }
    }

    private String labelOf(Item it) {
        if (it == null) {
            return "";
        }
        if (it.isFolder()) {
            return it.name == null ? "文件夹" : it.name;
        }
        String s = labelCache.get(it.pkg + "/" + it.cls);
        return s == null ? it.pkg : s;
    }

    private int badgeOf(Item it) {
        if (!WorkspaceStore.notificationDots(this) || it == null || it.pkg == null) {
            return 0;
        }
        Integer n = badgeMap.get(it.pkg);
        return n == null ? 0 : n;
    }

    private Drawable iconForPkg(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return getDrawable(R.drawable.ic_launcher);
        }
        Drawable d = iconCache.get(pkg);
        if (d == null) {
            try {
                d = getPackageManager().getApplicationIcon(pkg);
            } catch (Throwable t) {
                d = getDrawable(R.drawable.ic_launcher);
            }
            if (d != null) {
                iconCache.put(pkg, d);
            }
        }
        return d;
    }

    /**
     * 全局上滑判断：工作区、固定栏、页面指示器、导航条上方的空白都算，
     * 不再只依赖 PagedWorkspaceView 的 56dp 阈值（实机上很难触发）。
     * 只旁路观察，不消费事件，点击/翻页/拖动行为不变。
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        try {
            if (WorkspaceStore.gestureNav(this)) {
                int h = getResources().getDisplayMetrics().heightPixels;
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (ev.getY() > h - dp(24)) {
                            // 只记录起点，不消费事件：底部区域里的热区图标仍需可点。
                            swipeBottom = true;
                            swipeConsumed = false;
                            swipeBottomY = ev.getY();
                        }
                        break;
                    case MotionEvent.ACTION_MOVE: {
                        if (!swipeBottom) {
                            break;
                        }
                        float up = swipeBottomY - ev.getY();
                        if (!swipeConsumed && up > dp(14)
                                && Math.abs(up) > Math.abs(ev.getX() - upX)) {
                            swipeConsumed = true;
                            if (swipeDesktop != null) {
                                swipeDesktop.begin();
                            }
                        }
                        if (swipeConsumed) {
                            if (swipeDesktop != null) {
                                swipeDesktop.update(Math.max(0f, up));
                            }
                            return true;
                        }
                        break;
                    }
                    case MotionEvent.ACTION_UP:
                        if (swipeBottom) {
                            swipeBottom = false;
                            if (swipeConsumed) {
                                swipeConsumed = false;
                                boolean middle = ev.getY() > h * 0.2f
                                        && ev.getY() < h * 0.8f;
                                if (swipeDesktop != null) {
                                    swipeDesktop.finish(middle);
                                }
                                return true;
                            }
                        }
                        break;
                    case MotionEvent.ACTION_CANCEL:
                        if (swipeBottom) {
                            swipeBottom = false;
                            if (swipeConsumed) {
                                swipeConsumed = false;
                                if (swipeDesktop != null) {
                                    swipeDesktop.finish(false);
                                }
                                return true;
                            }
                        }
                        break;
                    default:
                        break;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    upX = ev.getX();
                    upY = ev.getY();
                    upTracking = true;
                    break;
                case MotionEvent.ACTION_UP:
                    if (upTracking) {
                        upTracking = false;
                        float dy = upY - ev.getY();
                        float dx = Math.abs(ev.getX() - upX);
                        if (dy > dp(28) && dy > dx * 1.15f
                                && (allApps == null || !allApps.isOpen())
                                && optionsOverlay == null) {
                            showAllApps(false);
                        }
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    upTracking = false;
                    break;
                default:
                    break;
            }
        } catch (Throwable ignored) {
        }
        return super.dispatchTouchEvent(ev);
    }

    // ------------------------------------------------------------ actions

    private void openItem(Item it, View anchor) {
        if (it == null) {
            return;
        }
        if (it.isFolder()) {
            try {
                if (folderOverlay != null) {
                    root.removeView(folderOverlay);
                }
                try {
                    column.setVisibility(View.INVISIBLE);
                    if (appsButton != null) {
                        appsButton.setVisibility(View.INVISIBLE);
                    }
                } catch (Throwable ignored) {
                }
                folderOverlay = new FolderOverlay(this, it, anchor, new Runnable() {
                    @Override
                    public void run() {
                        folderOverlay = null;
                        try {
                            column.setVisibility(View.VISIBLE);
                            if (appsButton != null) {
                                appsButton.setVisibility(WorkspaceStore.classicMode(
                                        MainActivity.this) ? View.GONE : View.VISIBLE);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
                root.addView(folderOverlay, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                folderOverlay.bringToFront();
                navHost.bringToFront();
            } catch (Throwable t) {
                toast("文件夹打开失败：" + t);
            }
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setClassName(it.pkg, it.cls);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (anchor != null && anchor.getWidth() > 0) {
                ActivityOptions opts = ActivityOptions.makeScaleUpAnimation(
                        anchor, 0, 0, anchor.getWidth(), anchor.getHeight());
                startActivity(i, opts.toBundle());
            } else {
                startActivity(i);
            }
            if (allApps != null && allApps.isOpen()) {
                allApps.close(true);
            } else {
                restoreWorkspaceFromAllApps();
            }
        } catch (Throwable t) {
            toast("无法启动 " + it.pkg);
        }
    }

    private void showAllApps(boolean focusSearch) {
        // 经典桌面没有抽屉：所有应用已经在桌面上。
        if (WorkspaceStore.classicMode(this)) {
            return;
        }
        if (allApps == null || allApps.isOpen()) {
            return;
        }
        closeOptions();
        // 先清掉上一次可能残留的缩小/变淡状态，再开始新的打开动画。
        resetWorkspaceNow();
        if (allAppsDirty) {
            // 启动时不再预先构建抽屉（90 个应用的拼音排序很慢），首次打开才绑定。
            allApps.setItems(allAppsItems);
            allAppsDirty = false;
        }
        allApps.open(focusSearch);
        try {
            navHost.setBackgroundColor(0xFF101B26);
            workspaceView.animate().scaleX(0.9f).scaleY(0.9f).alpha(0.6f)
                    .setDuration(280).setInterpolator(new DecelerateInterpolator(2f)).start();
            pageIndicator.animate().alpha(0.3f).setDuration(240).start();
        } catch (Throwable ignored) {
        }
    }

    private void restoreWorkspaceFromAllApps() {
        try {
            workspaceView.animate().cancel();
            navHost.setBackgroundColor(0xFF101B26);
            workspaceView.animate().scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(200).setInterpolator(new DecelerateInterpolator(2f)).start();
            pageIndicator.animate().alpha(1f).setDuration(200).start();
            workspaceView.requestFocus();
        } catch (Throwable ignored) {
        }
    }

    /** 立即复位工作区缩放/透明度：用于从抽屉/其他应用返回时消除残留。 */
    private void resetWorkspaceNow() {
        try {
            workspaceView.animate().cancel();
            workspaceView.setScaleX(1f);
            workspaceView.setScaleY(1f);
            workspaceView.setAlpha(1f);
            pageIndicator.animate().cancel();
            pageIndicator.setAlpha(1f);
        } catch (Throwable ignored) {
        }
    }

    private void showOptions() {
        if (optionsOverlay != null) {
            return;
        }
        try {
            final FrameLayout overlay = new FrameLayout(this);
            overlay.setBackgroundColor(0x66000000);
            overlay.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    closeOptions();
                }
            });
            LinearLayout sheet = new LinearLayout(this);
            sheet.setOrientation(LinearLayout.HORIZONTAL);
            sheet.setGravity(Gravity.CENTER);
            sheet.setPadding(dp(10), dp(10), dp(10), dp(10));
            sheet.setBackground(Ui.roundStroke(0xF216222E, dp(16),
                    dp(1), 0x22FFFFFF));
            sheet.setClickable(true);

            sheet.addView(optionChip("壁纸", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    closeOptions();
                    try {
                        startActivity(new Intent(Intent.ACTION_SET_WALLPAPER));
                    } catch (Throwable t) {
                        toast("无壁纸选择器");
                    }
                }
            }));
            sheet.addView(optionChip("小组件", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    closeOptions();
                    try {
                        widgetArea.showPicker();
                    } catch (Throwable t) {
                        toast("小组件不可用");
                    }
                }
            }));
            sheet.addView(optionChip("桌面设置", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    closeOptions();
                    try {
                        startActivity(new Intent(MainActivity.this,
                                com.c10h.launcher.settings.LauncherSettingsActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Throwable t) {
                        toast("桌面设置打开失败");
                    }
                }
            }));
            sheet.addView(optionChip("均衡器", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    closeOptions();
                    try {
                        new com.c10h.launcher.EqPanel(MainActivity.this).show();
                    } catch (Throwable t) {
                        toast("均衡器不可用");
                    }
                }
            }));
            sheet.addView(optionChip("息屏", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    closeOptions();
                    com.c10h.launcher.PowerActions.sleep(MainActivity.this);
                }
            }));

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            lp.bottomMargin = dp(164);
            overlay.addView(sheet, lp);
            root.addView(overlay, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            navHost.bringToFront();
            optionsOverlay = overlay;
            sheet.setTranslationY(dp(40));
            sheet.setAlpha(0f);
            sheet.animate().translationY(0f).alpha(1f).setDuration(220)
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
        } catch (Throwable t) {
            toast("选项打开失败：" + t);
        }
    }

    private TextView optionChip(String text, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        t.setGravity(Gravity.CENTER);
        // 电视用遥控/触控点击都要求 >48dp 的热区：旧版 20dp 内边距太小。
        t.setPadding(dp(26), dp(14), dp(26), dp(14));
        t.setMinWidth(dp(104));
        t.setMinHeight(dp(52));
        t.setBackground(Ui.ripple(this, 0x33FFFFFF, dp(12),
                Ui.round(0x22FFFFFF, dp(12))));
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(8);
        lp.rightMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private void closeOptions() {
        if (optionsOverlay == null) {
            return;
        }
        final View v = optionsOverlay;
        optionsOverlay = null;
        try {
            v.animate().alpha(0f).setDuration(160).withEndAction(new Runnable() {
                @Override
                public void run() {
                    root.removeView(v);
                }
            }).start();
        } catch (Throwable t) {
            root.removeView(v);
        }
    }

    // ------------------------------------------------------------ drag host

    @Override
    public void onDropWorkspace(Item item, String from, int page, int cell) {
        try {
            int pageSize = workspace.pageSize();
            int target = cell >= 0
                    ? Math.max(0, page * pageSize + cell)
                    : workspaceItems.size();
            // 命中已有图标：应用→建文件夹 / 文件夹→加入成员（AOSP 行为）
            int colsNow = workspace.view() == null ? 6
                    : Math.max(1, workspace.view().columns());
            if (cell >= 0) {
                int cx = cell % colsNow;
                int cy = cell / colsNow;
                Item hitItem = null;
                for (Item w : workspaceItems) {
                    if (w.screen == page && w.cellX == cx && w.cellY == cy) {
                        hitItem = w;
                        break;
                    }
                }
                if (hitItem != null && !hitItem.key().equals(item.key())) {
                    if (hitItem.isFolder() && item.isApp()) {
                        hitItem.members.add(item);
                        if ("folder".equals(from)) {
                            detachFromFolderSource(item);
                        }
                        Item pinned2 = indexOf(fixedItems, item);
                        if (pinned2 != null) {
                            fixedItems.remove(pinned2);
                        }
                        int si2 = indexOfIndex(item);
                        if (si2 >= 0) {
                            workspaceItems.remove(si2);
                        }
                        reflow();
                        persist();
                        render();
                        return;
                    }
                    if (hitItem.isApp() && item.isApp()) {
                        Item folder = Item.folder("文件夹");
                        folder.screen = hitItem.screen;
                        folder.cellX = hitItem.cellX;
                        folder.cellY = hitItem.cellY;
                        folder.members.add(hitItem);
                        folder.members.add(item);
                        int hitIdx = workspaceItems.indexOf(hitItem);
                        workspaceItems.remove(hitItem);
                        if ("folder".equals(from)) {
                            detachFromFolderSource(item);
                        }
                        Item pinned3 = indexOf(fixedItems, item);
                        if (pinned3 != null) {
                            fixedItems.remove(pinned3);
                        }
                        int si3 = indexOfIndex(item);
                        if (si3 >= 0) {
                            workspaceItems.remove(si3);
                        }
                        workspaceItems.add(Math.max(0, Math.min(hitIdx, workspaceItems.size())), folder);
                        reflow();
                        persist();
                        render();
                        return;
                    }
                }
            }
            int srcIndex = -1;
            if ("workspace".equals(from)) {
                srcIndex = indexOfIndex(item);
                if (srcIndex == target) {
                    showItemMenu(item, root);
                    return;
                }
            }
            if (srcIndex >= 0) {
                workspaceItems.remove(srcIndex);
                if (srcIndex < target) {
                    target--;
                }
            }
            Item pinned = indexOf(fixedItems, item);
            if (pinned != null) {
                fixedItems.remove(pinned);
            }
            if ("folder".equals(from)) {
                detachFromFolderSource(item);
            }
            if (target > workspaceItems.size()) {
                target = workspaceItems.size();
            }
            if (target < workspaceItems.size()) {
                Item dst = workspaceItems.get(target);
                if (dst != null && dst.key().equals(item.key())) {
                    render();
                    showItemMenu(item, root);
                    return;
                }
                if (dst != null && dst.isFolder() && item.isApp()) {
                    dst.members.add(item);
                    reflow();
                    persist();
                    render();
                    return;
                }
                if (dst != null && dst.isApp() && item.isApp()) {
                    Item folder = Item.folder("文件夹");
                    folder.screen = dst.screen;
                    folder.cellX = dst.cellX;
                    folder.cellY = dst.cellY;
                    folder.members.add(dst);
                    folder.members.add(item);
                    workspaceItems.remove(dst);
                    workspaceItems.add(Math.min(target, workspaceItems.size()), folder);
                    reflow();
                    persist();
                    render();
                    return;
                }
            }
            // 落点单元格写回条目坐标（否则 reflow 会保留旧位置）
            if (cell >= 0) {
                item.screen = page;
                item.cellX = cell % colsNow;
                item.cellY = cell / colsNow;
            }
            workspaceItems.add(Math.min(target, workspaceItems.size()), item);
            reflow();
            persist();
            render();
        } catch (Throwable t) {
            toast("拖动落点失败：" + t);
        }
    }

    @Override
    public void onDropFixed(Item item, String from, int slot) {
        try {
            slot = Math.max(0, Math.min(slot, Math.max(0, profile.hotseatCount - 1)));
            Item old = indexOf(fixedItems, item);
            int oldRank = old != null ? old.rank : -1;
            if (old != null) {
                fixedItems.remove(old);
            }
            Item ws = indexOf(workspaceItems, item);
            if (ws != null) {
                workspaceItems.remove(ws);
            }
            // 占用槽位：与原槽交换；来自工作区则把占用者移到空槽或回工作区
            Item occupant = null;
            for (Item f : fixedItems) {
                if (f.rank == slot) {
                    occupant = f;
                    break;
                }
            }
            if (occupant != null) {
                if (oldRank >= 0) {
                    occupant.rank = oldRank;
                } else {
                    int free = -1;
                    boolean[] used = new boolean[Math.max(1, profile.hotseatCount)];
                    for (Item f : fixedItems) {
                        if (f.rank >= 0 && f.rank < used.length) {
                            used[f.rank] = true;
                        }
                    }
                    for (int i = 0; i < used.length; i++) {
                        if (!used[i] && i != slot) {
                            free = i;
                            break;
                        }
                    }
                    if (free >= 0) {
                        occupant.rank = free;
                    } else {
                        fixedItems.remove(occupant);
                        workspaceItems.add(occupant);
                    }
                }
            }
            item.rank = slot;
            fixedItems.add(item);
            if ("folder".equals(from)) {
                detachFromFolderSource(item);
            }
            reflow();
            persist();
            render();
        } catch (Throwable t) {
            toast("固定栏落点失败：" + t);
        }
    }

    @Override
    public void onDragEndedNoDrop(Item item, View source) {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (item != null) {
                    if (source instanceof IconView) {
                        ((IconView) source).clearLift();
                    }
                    showItemMenu(item, source != null ? source : root);
                }
            }
        }, 80);
    }

    private Item indexOf(List<Item> list, Item item) {
        if (item == null) {
            return null;
        }
        for (Item x : list) {
            if (x.key().equals(item.key())) {
                return x;
            }
        }
        return null;
    }

    private int indexOfIndex(Item item) {
        for (int i = 0; i < workspaceItems.size(); i++) {
            if (workspaceItems.get(i).key().equals(item.key())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 校正工作区格子。关键点：PagedWorkspaceView 是按"列表下标 / pageSize"分页的，
     * 不是按 Item.screen，所以这里必须以 i/pageSize 作为页号，才能保证
     * 数据坐标和实际渲染页一致；否则条目会被 clamp 到同一格，看起来"应用消失"。
     *
     * 仅在越界/重复/页号不一致时改坐标，正常的空位（用户故意留白）保留。
     */
    private boolean reflow() {
        int cols = workspace.view() == null ? 6 : Math.max(1, workspace.view().columns());
        int rows = workspace.view() == null ? 5 : Math.max(1, workspace.view().rows());
        int ps = cols * rows;
        if (ps <= 0) {
            return false;
        }
        int n = workspaceItems.size();
        boolean changed = false;
        java.util.HashSet<Integer> used = new java.util.HashSet<Integer>();
        boolean[] need = new boolean[n];
        for (int i = 0; i < n; i++) {
            Item it = workspaceItems.get(i);
            int page = i / ps;
            if (it.screen != page) {
                it.screen = page;
                changed = true;
            }
            boolean ok = it.cellX >= 0 && it.cellX < cols && it.cellY >= 0 && it.cellY < rows;
            if (ok) {
                ok = used.add(page * ps + it.cellY * cols + it.cellX);
            }
            need[i] = !ok;
        }
        for (int i = 0; i < n; i++) {
            if (!need[i]) {
                continue;
            }
            Item it = workspaceItems.get(i);
            it.container = desktopContainer();
            int page = i / ps;
            boolean placed = false;
            for (int sl = page * ps; sl < (page + 1) * ps && !placed; sl++) {
                if (used.contains(sl)) {
                    continue;
                }
                int cell = sl - page * ps;
                it.cellX = cell % cols;
                it.cellY = cell / cols;
                used.add(sl);
                placed = true;
                changed = true;
            }
            if (!placed) {
                // 理论不可达（同页最多 ps 个唯一位）；极端数据下按序落格，绝不丢失。
                int cell = i % ps;
                it.cellX = cell % cols;
                it.cellY = cell / cols;
                changed = true;
            }
        }
        return changed;
    }

    private int desktopContainer() {
        return WorkspaceStore.classicMode(this)
                ? Item.CONTAINER_CLASSIC : Item.CONTAINER_DESKTOP;
    }

    private void persist() {
        LauncherModel.saveAsync(this, workspaceItems, fixedItems, null, desktopContainer());
    }

    // ------------------------------------------------------------ menus

    void openItemMenu(Item it, View anchor) {
        showItemMenu(it, anchor);
    }

    private void showItemMenu(final Item it, View anchor) {
        if (it == null) {
            return;
        }
        try {
            PopupMenu pm = new PopupMenu(
                    new android.view.ContextThemeWrapper(this,
                            android.R.style.Theme_DeviceDefault_Light),
                    anchor);
            if (it.isFolder()) {
                pm.getMenu().add(0, 1, 0, "打开");
                pm.getMenu().add(0, 2, 1, "重命名");
                pm.getMenu().add(0, 3, 2, "删除");
            } else {
                pm.getMenu().add(0, 10, 0, "应用信息");
                pm.getMenu().add(0, 11, 1, "卸载");
                if (indexOf(fixedItems, it) != null) {
                    pm.getMenu().add(0, 12, 2, "从固定栏移除");
                }
                if (indexOf(workspaceItems, it) != null) {
                    pm.getMenu().add(0, 13, 3, "从桌面移除");
                }
            }
            pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(android.view.MenuItem item) {
                    switch (item.getItemId()) {
                        case 1:
                            openItem(it, root);
                            return true;
                        case 2:
                            renameFolder(it);
                            return true;
                        case 3:
                            workspaceItems.remove(it);
                            persist();
                            render();
                            return true;
                        case 10:
                            openAppInfo(it.pkg);
                            return true;
                        case 11:
                            uninstall(it.pkg);
                            return true;
                        case 12: {
                            Item x = indexOf(fixedItems, it);
                            fixedItems.remove(x);
                            persist();
                            render();
                            return true;
                        }
                        case 13: {
                            Item x = indexOf(workspaceItems, it);
                            workspaceItems.remove(x);
                            WorkspaceStore.addRemoved(MainActivity.this, it.key(),
                                    WorkspaceStore.classicMode(MainActivity.this));
                            persist();
                            render();
                            return true;
                        }
                        default:
                            return false;
                    }
                }
            });
            popup = pm;
            pm.show();
        } catch (Throwable t) {
            toast("菜单打开失败：" + t);
        }
    }

    private void renameFolder(final Item folder) {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setText(folder.name);
        input.setSelection(folder.name.length());
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(20), dp(10), dp(20), 0);
        wrap.addView(input);
        new android.app.AlertDialog.Builder(this)
                .setTitle("重命名文件夹")
                .setView(wrap)
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        String s = input.getText().toString().trim();
                        folder.name = s.isEmpty() ? "文件夹" : s;
                        persist();
                        render();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void openAppInfo(String pkg) {
        try {
            // 自研应用信息页：不依赖 com.android.settings，禁用系统设置后仍可用。
            startActivity(new Intent(MainActivity.this,
                    com.c10h.launcher.settings.AppInfoActivity.class)
                    .putExtra(com.c10h.launcher.settings.AppInfoActivity.EXTRA_PKG, pkg));
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + pkg)));
            } catch (Throwable t2) {
                toast("无法打开应用信息");
            }
        }
    }

    private void uninstall(String pkg) {
        try {
            startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)));
        } catch (Throwable t) {
            toast("无法卸载");
        }
    }

    // ------------------------------------------------------------ nav / lifecycle

    private void applyNavVisibility(boolean animate) {
        boolean gesture = WorkspaceStore.gestureNav(this);
        boolean on = WorkspaceStore.navEnabled(this) && !gesture;
        navBar.setVisibility(on ? View.VISIBLE : View.GONE);
        navHost.setVisibility(gesture ? View.GONE : View.VISIBLE);
        // 手势模式没有三键条：去掉桌面底部 48dp 占位、抽屉底部 48dp 留白，
        // chevron 同步下移，避免出现“为三键导航预留”的空缺。
        try {
            if (navSpacer != null) {
                LinearLayout.LayoutParams lp =
                        (LinearLayout.LayoutParams) navSpacer.getLayoutParams();
                int h = gesture ? 0 : dp(48);
                if (lp.height != h) {
                    lp.height = h;
                    navSpacer.setLayoutParams(lp);
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            if (allApps != null) {
                ViewGroup.LayoutParams lp = allApps.getLayoutParams();
                if (lp instanceof FrameLayout.LayoutParams) {
                    FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) lp;
                    int bm = gesture ? 0 : dp(48);
                    if (flp.bottomMargin != bm) {
                        flp.bottomMargin = bm;
                        allApps.setLayoutParams(flp);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            if (appsButton != null) {
                ViewGroup.LayoutParams lp = appsButton.getLayoutParams();
                if (lp instanceof FrameLayout.LayoutParams) {
                    FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) lp;
                    int bm = gesture ? dp(108) : dp(156);
                    if (flp.bottomMargin != bm) {
                        flp.bottomMargin = bm;
                        appsButton.setLayoutParams(flp);
                    }
                }
                appsButton.setVisibility(WorkspaceStore.classicMode(this)
                        ? View.GONE : View.VISIBLE);
            }
        } catch (Throwable ignored) {
        }
        try {
            if (gesture) {
                if (swipeDesktop == null) {
                    swipeDesktop = new SwipeUpGesture(this, false, false);
                    swipeDesktop.attach();
                }
            } else if (swipeDesktop != null) {
                swipeDesktop.detach();
                swipeDesktop = null;
            }
        } catch (Throwable ignored) {
        }
        hideSystemNav();
        if (animate && on) {
            navBar.setAlpha(0f);
            navBar.animate().alpha(1f).setDuration(180).start();
        }
    }

    private void rebuildNavBar() {
        try {
            navHost.removeAllViews();
            navBar = new NavBarView(this);
            navHost.addView(navBar, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            applyNavVisibility(false);
        } catch (Throwable ignored) {
        }
    }

    void toggleNav() {
        boolean on = !WorkspaceStore.navEnabled(this);
        WorkspaceStore.setNavEnabled(this, on);
        applyNavVisibility(true);
    }

    void dispatchHome() {
        closeOptions();
        if (allApps != null && allApps.isOpen()) {
            allApps.close(true);
        }
        try {
            navHost.setBackgroundColor(0xFF101B26);
        } catch (Throwable ignored) {
        }
        if (popup != null) {
            try {
                popup.dismiss();
            } catch (Throwable ignored) {
            }
            popup = null;
        }
        workspace.setPage(0, true);
    }

    @Override
    public void onBackPressed() {
        if (folderOverlay != null) {
            try {
                folderOverlay.closeOverlay();
            } catch (Throwable ignored) {
            }
            folderOverlay = null;
            return;
        }
        if (optionsOverlay != null) {
            closeOptions();
            return;
        }
        if (allApps != null && allApps.isOpen()) {
            allApps.close(true);
            return;
        }
        if (popup != null) {
            try {
                popup.dismiss();
            } catch (Throwable ignored) {
            }
            popup = null;
            return;
        }
        // 桌面不退出
    }

    private void applySettings() {
        try {
            setRequestedOrientation(WorkspaceStore.allowRotation(this)
                    ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        } catch (Throwable ignored) {
        }
        if (!WorkspaceStore.notificationDots(this)) {
            badgeMap.clear();
            if (workspaceView != null) {
                workspaceView.setBadges(badgeMap);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        sResumedCount++;
        // 设置页在 :settings 进程写 prefs；自定义广播可能被 Android 9 后台限制拦截，
        // 回桌面时直接从 prefs 重新同步一次。
        WorkspaceStore.reload(this);
        boolean classic = WorkspaceStore.classicMode(this);
        boolean navReversed = WorkspaceStore.navReversed(this);
        boolean gesture = WorkspaceStore.gestureNav(this);
        final boolean classicChanged = classic != lastClassic;
        boolean changed = classicChanged
                || navReversed != lastNavReversed || gesture != lastGesture;
        lastClassic = classic;
        lastNavReversed = navReversed;
        lastGesture = gesture;
        applySettings();
        if (changed) {
            rebuildNavBar();
            if (classicChanged) {
                // 经典/抽屉是两套独立容器：切换后必须重新从 LauncherModel 载入
                // 对应容器的完整布局，直接 render() 会把另一套的少量条目当成本模式。
                loadApps();
            } else {
                render();
            }
        } else {
            applyNavVisibility(false);
        }
        // 桌面内只用自带导航，避免出现第二条。
        if (floatingNav != null) {
            try {
                floatingNav.detach();
            } catch (Throwable ignored) {
            }
        }
        // 已有内存数据时不再重建桌面：每次 onResume 全量 loadApps 会重新
        // 查询并重建 30+ 个图标，是点 Home 后"要等好几秒"的主因。
        // 包安装/卸载、设置变更都有广播单独触发 loadApps。
        if (workspaceItems.isEmpty()) {
            loadApps();
        }
        // 上一次打开抽屉后可能残留缩小/变淡（例如直接从抽屉启动应用），
        // 回到桌面时无条件复位，避免整页图标又小又淡。
        if (allApps == null || !allApps.isOpen()) {
            resetWorkspaceNow();
        }
        if (widgetArea != null) {
            widgetArea.restore();
        }
        if (!pkgRegistered) {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_PACKAGE_ADDED);
            f.addAction(Intent.ACTION_PACKAGE_REMOVED);
            f.addAction(Intent.ACTION_PACKAGE_CHANGED);
            f.addDataScheme("package");
            try {
                pkgReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        loadApps();
                    }
                };
                registerReceiver(pkgReceiver, f);
                pkgRegistered = true;
            } catch (Throwable ignored) {
            }
        }
        if (!badgeRegistered) {
            try {
                badgeReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        String pkg = intent.getStringExtra("pkg");
                        int count = intent.getIntExtra("count", 0);
                        if (pkg != null) {
                            if (count > 0) {
                                badgeMap.put(pkg, count);
                            } else {
                                badgeMap.remove(pkg);
                            }
                            workspaceView.setBadges(badgeMap);
                        }
                    }
                };
                registerReceiver(badgeReceiver,
                        new IntentFilter("com.c10h.launcher.action.BADGE_UPDATE"));
                badgeRegistered = true;
            } catch (Throwable ignored) {
            }
        }
        if (!settingsRegistered) {
            try {
                settingsReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        try {
                            WorkspaceStore.applyRuntime(
                                    intent.getBooleanExtra("add",
                                            WorkspaceStore.addIconToHome(MainActivity.this)),
                                    intent.getBooleanExtra("dots",
                                            WorkspaceStore.notificationDots(MainActivity.this)),
                                    intent.getBooleanExtra("nav",
                                            WorkspaceStore.navEnabled(MainActivity.this)),
                                    intent.getBooleanExtra("rotate",
                                            WorkspaceStore.allowRotation(MainActivity.this)),
                                    intent.getBooleanExtra("classic",
                                            WorkspaceStore.classicMode(MainActivity.this)),
                                    intent.getBooleanExtra("navReversed",
                                            WorkspaceStore.navReversed(MainActivity.this)),
                                    intent.getBooleanExtra("gesture",
                                            WorkspaceStore.gestureNav(MainActivity.this)));
                        } catch (Throwable ignored) {
                        }
                        applySettings();
                        rebuildNavBar();
                        loadApps();
                    }
                };
                IntentFilter sf =
                        new IntentFilter("com.c10h.launcher.action.SETTINGS_CHANGED");
                registerReceiver(settingsReceiver, sf);
                settingsRegistered = true;
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 供同进程的最近任务页调用：先卸掉悬浮导航，避免与最近任务自带底栏叠成两条。
     */
    public static void detachFloatingNav() {
        if (sFloatingNav != null) {
            try {
                sFloatingNav.detach();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 最近任务页在前台期间禁止 MainActivity.onPause 的延迟 attach。 */
    public static void setFloatingNavSuppressed(boolean suppressed) {
        FloatingNav.setSuppressed(suppressed);
    }

    @Override
    protected void onPause() {
        super.onPause();
        sResumedCount = Math.max(0, sResumedCount - 1);
        // 离开桌面后挂载“上滑唤出”的半透明导航，3s 无操作自动隐藏。
        try {
            getWindow().getDecorView().postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (sFloatingNav != null && sResumedCount <= 0
                                && WorkspaceStore.navEnabled(MainActivity.this)) {
                            sFloatingNav.attach();
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }, 250);
        } catch (Throwable ignored) {
        }
        if (popup != null) {
            try {
                popup.dismiss();
            } catch (Throwable ignored) {
            }
            popup = null;
        }
        if (pkgRegistered && pkgReceiver != null) {
            try {
                unregisterReceiver(pkgReceiver);
            } catch (Throwable ignored) {
            }
            pkgReceiver = null;
            pkgRegistered = false;
        }
        if (badgeRegistered && badgeReceiver != null) {
            try {
                unregisterReceiver(badgeReceiver);
            } catch (Throwable ignored) {
            }
            badgeReceiver = null;
            badgeRegistered = false;
        }
        if (settingsRegistered && settingsReceiver != null) {
            try {
                unregisterReceiver(settingsReceiver);
            } catch (Throwable ignored) {
            }
            settingsReceiver = null;
            settingsRegistered = false;
        }
        if (widgetArea != null) {
            widgetArea.stopListening();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (wpReceiverRegistered && wpReceiver != null) {
                unregisterReceiver(wpReceiver);
            }
        } catch (Throwable ignored) {
        }
        try {
            if (qsPanel != null) {
                qsPanel.detach();
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ utils

    WidgetArea widgetArea() {
        return widgetArea;
    }

    int dp(int v) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return (int) (v * dm.density + 0.5f);
    }

    private void toast(String msg) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }
}
