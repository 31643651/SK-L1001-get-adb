package com.c10h.launcher;

import android.util.DisplayMetrics;

/**
 * AOSP Launcher3 InvariantDeviceProfile / DeviceProfile 的等价实现（M9）。
 * 按屏幕较短边/较长边选择最近档位，给出列、行、图标、文字、固定栏槽位。
 */
public final class DeviceProfile {

    public final int minWidthDp;
    public final int minHeightDp;
    public final int cols;
    public final int rows;
    public final int folderCols;
    public final int folderRows;
    public final int hotseatCount;
    public final int iconSizeDp;
    public final float textSizeSp;
    public final String name;

    private DeviceProfile(String name, int minW, int minH, int cols, int rows,
                          int fCols, int fRows, int hotseat, int icon, float text) {
        this.name = name;
        this.minWidthDp = minW;
        this.minHeightDp = minH;
        this.cols = cols;
        this.rows = rows;
        this.folderCols = fCols;
        this.folderRows = fRows;
        this.hotseatCount = hotseat;
        this.iconSizeDp = icon;
        this.textSizeSp = text;
    }

    private static final DeviceProfile[] PROFILES = new DeviceProfile[]{
            new DeviceProfile("Super Short Stubby", 255, 300, 3, 2, 3, 2, 3, 48, 13f),
            new DeviceProfile("Shorter Stubby", 255, 400, 3, 3, 3, 3, 3, 48, 13f),
            new DeviceProfile("Short Stubby", 275, 420, 4, 3, 4, 3, 5, 48, 13f),
            new DeviceProfile("Stubby", 255, 450, 4, 3, 4, 3, 5, 48, 13f),
            new DeviceProfile("Nexus S", 296, 491, 4, 4, 4, 4, 5, 48, 13f),
            new DeviceProfile("Nexus 4", 359, 567, 4, 4, 4, 4, 5, 54, 13f),
            new DeviceProfile("Nexus 5", 335, 567, 4, 4, 4, 4, 5, 54, 13f),
            new DeviceProfile("Large Phone", 406, 694, 5, 5, 4, 4, 5, 56, 14.4f),
            new DeviceProfile("Nexus 7", 575, 904, 6, 5, 5, 4, 7, 64, 14.4f),
            new DeviceProfile("Nexus 10", 727, 1207, 6, 5, 5, 4, 7, 76, 14f),
            new DeviceProfile("20-inch Tablet", 1527, 2527, 7, 7, 6, 6, 7, 100, 20f)
    };

    public static DeviceProfile forDisplay(DisplayMetrics dm) {
        int w = (int) (dm.widthPixels / dm.density);
        int h = (int) (dm.heightPixels / dm.density);
        int min = Math.min(w, h);
        int max = Math.max(w, h);
        DeviceProfile best = PROFILES[PROFILES.length - 1];
        int bestDist = Integer.MAX_VALUE;
        for (DeviceProfile p : PROFILES) {
            int dist = Math.abs(min - p.minWidthDp) + Math.abs(max - p.minHeightDp);
            if (dist < bestDist) {
                bestDist = dist;
                best = p;
            }
        }
        return best;
    }

    /** 目标 1280×800 的固定档；测试/真机一致。 */
    public static DeviceProfile target() {
        return PROFILES[9];
    }
}
