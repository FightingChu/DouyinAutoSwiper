package com.example.douyinautoswiper;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 简单的偏好存储封装：自动滑动开关、基础间隔、随机抖动。
 * 服务与界面都从这里读写，保证参数一致。
 */
public final class AppPreferences {

    private static final String NAME = "douyin_autoswiper_prefs";
    private static final String KEY_ENABLED = "auto_swipe_enabled";
    private static final String KEY_INTERVAL = "interval_sec";
    private static final String KEY_JITTER = "jitter_sec";

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static boolean isAutoSwipeEnabled(Context c) {
        return sp(c).getBoolean(KEY_ENABLED, false);
    }

    public static void setAutoSwipeEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_ENABLED, v).apply();
    }

    /** 基础滑动间隔（秒）。视频未播完时的兜底触发间隔。最小 3 秒。 */
    public static int getIntervalSec(Context c) {
        return sp(c).getInt(KEY_INTERVAL, 15);
    }

    public static void setIntervalSec(Context c, int v) {
        sp(c).edit().putInt(KEY_INTERVAL, Math.max(3, v)).apply();
    }

    /** 随机抖动（秒）。每次实际间隔 = 基础间隔 + [0, jitter) 的随机数。最小 0。 */
    public static int getJitterSec(Context c) {
        return sp(c).getInt(KEY_JITTER, 5);
    }

    public static void setJitterSec(Context c, int v) {
        sp(c).edit().putInt(KEY_JITTER, Math.max(0, v)).apply();
    }

    /** 拟人化随机滑动：随机坐标/轨迹弧度/时长 + 小概率停顿。默认开。 */
    private static final String KEY_HUMANIZE = "humanize";
    public static boolean isHumanizeEnabled(Context c) {
        return sp(c).getBoolean(KEY_HUMANIZE, true);
    }
    public static void setHumanizeEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_HUMANIZE, v).apply();
    }

    /** 悬浮状态窗：抖音上层角落显示实时状态（不影响滑动）。默认关。 */
    private static final String KEY_OVERLAY = "overlay";
    public static boolean isOverlayEnabled(Context c) {
        return sp(c).getBoolean(KEY_OVERLAY, false);
    }
    public static void setOverlayEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_OVERLAY, v).apply();
    }
}
