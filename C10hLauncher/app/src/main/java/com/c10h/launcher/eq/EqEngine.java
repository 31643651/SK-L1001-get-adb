package com.c10h.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.audiofx.DynamicsProcessing;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 全局 EQ 引擎（规格 §5，参考 docs/userlaunchereq.md）。
 *
 * - DynamicsProcessing 挂 session 0（全局 mix）→ 对所有应用、所有输出（含蓝牙
 *   A2DP）生效；需要 MODIFY_AUDIO_ROUTING，由 deploy_privapp.py 的 priv-app
 *   白名单授予，授不到时 attach() 降级为 dp=null（面板红字提示，绝不闪退）；
 * - 生命周期独立于 Activity（LauncherApp 单例持有，桌面 onPause 不影响）；
 * - AudioDeviceCallback：蓝牙连/断、输出设备变化自动重挂；
 * - 参数持久化 c10h_launcher prefs（eq_ 前缀），与 MainActivity 的 nav 键同文件。
 *
 * 2026-11-24 改版：频段从 10 段扩到 11 段（新增 30Hz）。部分 ROM 的
 * DynamicsProcessing 对 postEq 段数有上限，因此 attach 时先试 11 段，
 * 失败自动退到 10 段（30Hz 保留、去掉 12kHz），并把实际频段表写入
 * {@link #FREQS} 供面板按实际段数绘制。
 *
 * API 签名以 javap 实测为准（android-36 android.jar）：
 *   Config.Builder(int,int,boolean,int,boolean,int,boolean,int,boolean)
 *   DynamicsProcessing(int priority, int session, Config)
 *   setPostEqBandByChannelIndex(int ch, int band, EqBand)
 *   EqBand(boolean useBand, float centerFreq, float gain)
 */
public class EqEngine {

    private static final String TAG = "C10HEq";
    private static final String PREFS = "c10h_launcher"; // 与 MainActivity.KEY_NAV 同文件

    /** 11 段：新增 30Hz（首选）。 */
    public static final float[] FREQS_11 =
            {30, 60, 120, 250, 500, 1000, 2000, 4000, 8000, 12000, 16000};
    /** 10 段回退：保留 30Hz，去掉 12kHz（API/ROM 只允许 10 段时）。 */
    public static final float[] FREQS_10 =
            {30, 60, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
    /** 旧版 10 段（60..16k），用于兼容已保存的旧预设。 */
    private static final float[] FREQS_LEGACY =
            {60, 120, 250, 500, 1000, 2000, 4000, 8000, 12000, 16000};

    /**
     * 当前生效频段表。不设 final：attach 探测到设备支持的实际段数后赋值，
     * 面板与预设都按此数组长度工作（默认先按 11 段，attach 在 Application
     * onCreate 完成，早于任何面板创建）。
     */
    public static float[] FREQS = FREQS_11;

    // ---- 出厂预设（常驻，不入 prefs；用户同名保存时以用户版本优先）----
    public static final String[] PRESET_NAMES =
            {"平直", "优化", "低音增强", "人声", "摇滚", "夜间"};
    /** 与 FREQS_11 一一对应的出厂曲线。 */
    private static final float[][] PRESET_GAINS = {
            {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},              // 平直
            {-2, -1, -2, -3, -2, 0, +2, +3, +4, +3, +2},    // 优化（默认曲线）
            {+7, +6, +5, +3, +1, 0, 0, 0, 0, 0, 0},         // 低音增强
            {-6, -3, -2, -1, +2, +4, +4, +3, +1, 0, -1},    // 人声
            {+5, +4, +3, +1, -1, -1, +1, +3, +4, +4, +3},   // 摇滚
            {-8, -6, -4, -2, 0, +1, +1, 0, -1, -3, -6},     // 夜间（压轰鸣与尖锐）
    };

    private static final String KEY_PRESET_SET = "eq_presets";   // 用户预设名 StringSet
    private static final String KEY_PRESET_PFX = "eq_pset_";     // eq_pset_<名> -> "g0,g1,..."
    private static final String KEY_LINK = "eq_link_bands";      // 附近频段联动开关

    private final Context ctx;
    private final AudioManager am;
    private final AudioDeviceCallback devCb;
    private DynamicsProcessing dp;

    public EqEngine(Context c) {
        ctx = c.getApplicationContext();
        am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        devCb = new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] devices) {
                attach();
            }

            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] devices) {
                attach();
            }
        };
        try {
            am.registerAudioDeviceCallback(devCb, null);
        } catch (Throwable t) {
            Log.e(TAG, "registerAudioDeviceCallback failed: " + t);
        }
        attach();
    }

    /** 挂载/重挂到 session 0；任何失败只降级（dp=null），绝不抛出。 */
    public synchronized void attach() {
        try {
            if (dp != null) {
                dp.release();
                dp = null;
            }
        } catch (Throwable ignored) {
        }
        if (tryAttach(FREQS_11)) {
            return;
        }
        if (tryAttach(FREQS_10)) {
            return;
        }
        Log.e(TAG, "attach failed (need priv-app + MODIFY_AUDIO_ROUTING)");
        dp = null;
    }

    /** 尝试用指定频段表建立 effect；成功返回 true。 */
    private boolean tryAttach(float[] freqs) {
        DynamicsProcessing d = null;
        try {
            SharedPreferences p = prefs();
            DynamicsProcessing.Config cfg = new DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    2,               // 立体声
                    false, 0,        // preEq 不用
                    false, 0,        // MBC 不用
                    true, freqs.length, // postEq（11 段优先，失败退 10 段）
                    true             // limiter
            ).build();
            d = new DynamicsProcessing(0, 0, cfg); // priority=0, session=0 全局
            FREQS = freqs;
            // 10 段（旧版）→ 11 段：老值按频率迁移，避免所有频段错位一格。
            migrateLegacyGains(p, freqs);
            for (int i = 0; i < freqs.length; i++) {
                applyBandTo(d, freqs, i, p.getFloat("eq_gain_" + i, defaultGain(i)));
            }
            d.setEnabled(p.getBoolean("eq_enabled", false));
            dp = d;
            Log.i(TAG, "attached session0 bands=" + freqs.length
                    + " enabled=" + d.getEnabled());
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "attach " + freqs.length + " bands failed: " + t);
            try {
                if (d != null) {
                    d.release();
                }
            } catch (Throwable ignored) {
            }
            return false;
        }
    }

    /** 写单段增益到 effect；dp 为 null 时静默（参数已由 setBand 存进 prefs）。 */
    private void applyBand(int i, float db) {
        applyBandTo(dp, FREQS, i, db);
    }

    /**
     * 旧版 10 段 prefs 按"索引"存增益；升级到 11 段后索引整体右移一格。
     * 这里按频率把旧曲线采样到当前频段表（只做一次），保证用户的调音不变味。
     */
    private static void migrateLegacyGains(SharedPreferences p, float[] freqs) {
        final String marker = "eq_bands_migrated_v2";
        if (p.getBoolean(marker, false)) {
            return;
        }
        boolean any = false;
        float[] legacy = new float[FREQS_LEGACY.length];
        float[] defaults = sampleGains(FREQS_11, PRESET_GAINS[1], FREQS_LEGACY);
        for (int j = 0; j < legacy.length; j++) {
            legacy[j] = p.getFloat("eq_gain_" + j, defaults[j]);
            any |= p.contains("eq_gain_" + j);
        }
        if (any) {
            float[] sampled = sampleGains(FREQS_LEGACY, legacy, freqs);
            SharedPreferences.Editor e = p.edit();
            for (int i = 0; i < freqs.length; i++) {
                e.putFloat("eq_gain_" + i, sampled[i]);
            }
            // 清掉多余索引（11 段回退到 10 段时）
            for (int i = freqs.length; i < FREQS_11.length; i++) {
                e.remove("eq_gain_" + i);
            }
            e.apply();
            Log.i(TAG, "migrated legacy gains to bands=" + freqs.length);
        }
        p.edit().putBoolean(marker, true).apply();
    }

    private static void applyBandTo(DynamicsProcessing d, float[] freqs, int i, float db) {
        if (d == null || i < 0 || i >= freqs.length) {
            return;
        }
        try {
            for (int ch = 0; ch < 2; ch++) {
                d.setPostEqBandByChannelIndex(ch, i,
                        new DynamicsProcessing.EqBand(true, freqs[i], db));
            }
        } catch (Throwable t) {
            Log.e(TAG, "setPostEqBandByChannelIndex " + i + " failed: " + t);
        }
    }

    public synchronized void setBand(int i, float db) {
        if (i < 0 || i >= FREQS.length) {
            return;
        }
        prefs().edit().putFloat("eq_gain_" + i, db).apply();
        applyBand(i, db);
    }

    /** 拖动过程中的实时生效（不写盘），抬手时再由 setBand 持久化。 */
    public synchronized void setBandLive(int i, float db) {
        if (i < 0 || i >= FREQS.length) {
            return;
        }
        applyBand(i, db);
    }

    /** EQ 总开关（写 prefs + effect）。 */
    public synchronized void setEnabled(boolean on) {
        prefs().edit().putBoolean("eq_enabled", on).apply();
        try {
            if (dp != null) {
                dp.setEnabled(on);
            }
        } catch (Throwable ignored) {
        }
    }

    public float getBand(int i) {
        if (i < 0 || i >= FREQS.length) {
            return 0f;
        }
        return prefs().getFloat("eq_gain_" + i, defaultGain(i));
    }

    public boolean isEnabled() {
        return prefs().getBoolean("eq_enabled", false);
    }

    /** 调节某段时是否联动附近频段（面板开关）。 */
    public boolean linkBands() {
        return prefs().getBoolean(KEY_LINK, false);
    }

    public void setLinkBands(boolean on) {
        prefs().edit().putBoolean(KEY_LINK, on).apply();
    }

    /** effect 是否挂上；false = 未授权全局音效（面板红字提示）。 */
    public synchronized boolean isAttached() {
        return dp != null;
    }

    /**
     * 是否持有全局音效所需权限（MODIFY_AUDIO_ROUTING）。
     *
     * 与 isAttached() 分开判断的原因：部分 AVD/ROM 对 session-0 effect 不强制该权限，
     * 会出现"attach 成功但权限未授"的状态。规格 §5 要求"授不到权限时红字提示"，
     * 因此面板红字以本方法为准（权限未授即提示），attach 失败同样触发。
     * API 23 才有 checkSelfPermission，低版本按已授处理（priv-app 场景下无运行时授权）。
     */
    public boolean hasRoutingPermission() {
        try {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) {
                return true;
            }
            // MODIFY_AUDIO_ROUTING 是 @SystemApi，android.Manifest.permission 中无此常量
            return ctx.checkSelfPermission("android.permission.MODIFY_AUDIO_ROUTING")
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================== 预设（eq_ 前缀分区，规格 §3）====================

    /** 全部预设名：出厂预设在前，用户预设在后（同名时用户版本优先）。 */
    public synchronized List<String> presetNames() {
        List<String> out = new ArrayList<>();
        Set<String> user = prefs().getStringSet(KEY_PRESET_SET, new HashSet<String>());
        for (String n : PRESET_NAMES) {
            if (!user.contains(n)) {
                out.add(n);
            }
        }
        out.addAll(user);
        return out;
    }

    /** 是否出厂预设（决定删除入口是否显示）。 */
    public static boolean isBuiltinPreset(String name) {
        for (String n : PRESET_NAMES) {
            if (n.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取预设增益（长度与当前 FREQS 一致）。优先用户同名预设，其次出厂预设；
     * 找不到返回 null。
     */
    public synchronized float[] presetGains(String name) {
        String raw = prefs().getString(KEY_PRESET_PFX + name, null);
        if (raw != null) {
            float[] g = parseGains(raw, FREQS.length);
            if (g != null) {
                return g;
            }
            // 旧版 11 段 / 10 段用户预设：按频率采样到当前频段表。
            float[] g11 = parseGains(raw, FREQS_11.length);
            if (g11 != null) {
                return sampleGains(FREQS_11, g11, FREQS);
            }
            float[] g10 = parseGains(raw, FREQS_LEGACY.length);
            if (g10 != null) {
                return sampleGains(FREQS_LEGACY, g10, FREQS);
            }
        }
        for (int i = 0; i < PRESET_NAMES.length; i++) {
            if (PRESET_NAMES[i].equals(name)) {
                return sampleGains(FREQS_11, PRESET_GAINS[i], FREQS);
            }
        }
        return null;
    }

    /** 保存/覆盖用户预设（同名出厂预设被用户版本覆盖）。 */
    public synchronized void savePreset(String name, float[] gains) {
        if (name == null || name.trim().isEmpty() || gains == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < gains.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(gains[i]);
        }
        Set<String> set = new HashSet<>(prefs().getStringSet(KEY_PRESET_SET, new HashSet<String>()));
        set.add(name);
        prefs().edit()
                .putStringSet(KEY_PRESET_SET, set)
                .putString(KEY_PRESET_PFX + name, sb.toString())
                .apply();
    }

    /** 删除用户预设（出厂预设不可删，返回 false）。 */
    public synchronized boolean deletePreset(String name) {
        if (isBuiltinPreset(name)) {
            return false;
        }
        Set<String> set = new HashSet<>(prefs().getStringSet(KEY_PRESET_SET, new HashSet<String>()));
        if (!set.remove(name)) {
            return false;
        }
        prefs().edit()
                .putStringSet(KEY_PRESET_SET, set)
                .remove(KEY_PRESET_PFX + name)
                .apply();
        return true;
    }

    /** 应用预设：全部段写入 prefs + effect 并启用。找不到预设返回 false。 */
    public synchronized boolean applyPreset(String name) {
        float[] g = presetGains(name);
        if (g == null) {
            return false;
        }
        int n = Math.min(g.length, FREQS.length);
        for (int i = 0; i < n; i++) {
            setBand(i, g[i]);
        }
        setEnabled(true);
        return true;
    }

    /** 当前各段增益快照（用于"另存为预设"）。 */
    public synchronized float[] currentGains() {
        float[] g = new float[FREQS.length];
        for (int i = 0; i < g.length; i++) {
            g[i] = getBand(i);
        }
        return g;
    }

    private static float[] parseGains(String raw, int expect) {
        try {
            String[] parts = raw.split(",");
            if (parts.length != expect) {
                return null;
            }
            float[] g = new float[parts.length];
            for (int i = 0; i < parts.length; i++) {
                g[i] = Float.parseFloat(parts[i].trim());
            }
            return g;
        } catch (Exception e) {
            return null;
        }
    }

    /** 把某个频段表上的曲线按"最近频率"采样到目标频段表。 */
    private static float[] sampleGains(float[] srcFreqs, float[] srcGains, float[] dstFreqs) {
        float[] out = new float[dstFreqs.length];
        for (int i = 0; i < dstFreqs.length; i++) {
            int best = 0;
            float bestDist = Float.MAX_VALUE;
            for (int j = 0; j < srcFreqs.length && j < srcGains.length; j++) {
                float d = Math.abs(srcFreqs[j] - dstFreqs[i]);
                if (d < bestDist) {
                    bestDist = d;
                    best = j;
                }
            }
            out[i] = srcGains[best];
        }
        return out;
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 针对该机"雾蒙蒙"音质的开箱优化曲线（规格 §5）。 */
    private static float defaultGain(int i) {
        float[] d = sampleGains(FREQS_11, PRESET_GAINS[1], FREQS);
        return (i >= 0 && i < d.length) ? d[i] : 0f;
    }
}
