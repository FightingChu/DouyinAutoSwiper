package com.example.douyinautoswiper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 抖音极速版自动刷服务（v2）。
 *
 * 关键修正（相对 v1）：
 *  - 激活判断改用 onAccessibilityEvent 的「事件包名」，不再依赖
 *    getRootInActiveWindow()（v1 因 canRetrieveWindowContent 未开启导致
 *    getRootInActiveWindow 返回 null，整个轮询空转、一次都不滑）。
 *  - 定时滑动保证「必跑」：只要处于抖音窗口且开关开启，到时间就一定上滑，
 *    不依赖进度文本是否可读。
 *  - 进度检测降级为「增强项」：能读到进度文本就「播完即切」，读不到就走定时。
 *  - 状态栏通知：实时显示「已滑动 N 次 / 原因 / 当前进度」，便于排查。
 *
 * 关于「播完即切」的边界：抖音播放页进度条多为自定义 View，无障碍 often
 * 读不到文本，因此纯无障碍方案下「精确播完即切」不可靠。本服务以定时滑动
 * 为主，进度检测为辅。若你手机上抖音确实暴露了 "0:15/0:30" 类文本则会更准。
 */
public class AutoSwipeService extends AccessibilityService {

    private static final String TAG = "AutoSwipe";

    /** 抖音极速版包名。普通版抖音请改成 com.ss.android.ugc.aweme（见 README）。 */
    private static final String TARGET_PACKAGE = "com.ss.android.ugc.aweme.lite";

    /** 轮询间隔（毫秒）。 */
    private static final long POLL_MS = 1000;

    /** 两次上滑最小间隔（毫秒），防止手势重叠 / 切换过快。 */
    private static final long MIN_SWIPE_INTERVAL_MS = 2500;

    /** 进度达到该比例即视为「播完」，立即上滑。 */
    private static final float DONE_THRESHOLD = 0.95f;

    /** 匹配 "0:15/0:30" 或 "00:15/00:30" 形式的进度文本。 */
    private static final Pattern PROGRESS_PATTERN =
            Pattern.compile("(\\d{1,2}:\\d{2})\\s*/\\s*(\\d{1,2}:\\d{2})");

    private static final String CHANNEL_ID = "douyin_autoswiper_channel";
    private static final int NOTIF_ID = 1001;

    private Handler handler;
    private Runnable pollRunnable;
    private long lastSwipeTime = 0;
    /** 下一次定时兜底上滑的目标时间戳。 */
    private long nextSwipeAfter = 0;

    /** 当前是否处于抖音极速版窗口（由事件包名推断，不依赖 root）。 */
    private boolean targetActive = false;

    private int swipeCount = 0;
    private NotificationManager nm;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        handler = new Handler(Looper.getMainLooper());
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel();
        nextSwipeAfter = System.currentTimeMillis() + computeDelay();
        startLoop();
        postNotify("服务已连接，等待抖音窗口");
        Log.d(TAG, "service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        String p = pkg.toString();
        // 只在「窗口状态/内容变化」时更新激活状态（避免滚动事件频繁抖动）。
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            targetActive = TARGET_PACKAGE.equals(p);
        }
        // 实际滑动在轮询中统一处理，这里仅维护 targetActive 标志。
    }

    /** 启动轮询循环。 */
    private void startLoop() {
        pollRunnable = new Runnable() {
            @Override
            public void run() {
                tick();
                if (handler != null) {
                    handler.postDelayed(this, POLL_MS);
                }
            }
        };
        handler.postDelayed(pollRunnable, POLL_MS);
    }

    /** 一次轮询：决定是否上滑。 */
    private void tick() {
        if (!AppPreferences.isAutoSwipeEnabled(this)) {
            postNotify("已停止：App 内开关已关闭");
            return;
        }
        if (!targetActive) {
            postNotify("未在抖音极速版（请在抖音播放页）");
            return;
        }

        long now = System.currentTimeMillis();
        float progress = detectProgress();
        boolean progressDone = progress >= DONE_THRESHOLD;
        boolean timeUp = now >= nextSwipeAfter;
        boolean canSwipe = (now - lastSwipeTime) >= MIN_SWIPE_INTERVAL_MS;

        if (canSwipe && (progressDone || timeUp)) {
            boolean ok = performSwipeUp();
            if (ok) {
                swipeCount++;
                lastSwipeTime = now;
                nextSwipeAfter = now + computeDelay();
                String reason = progressDone ? "进度满" : "定时";
                postNotify("已滑动 " + swipeCount + " 次（" + reason + "）");
                Log.d(TAG, "swipe #" + swipeCount + " reason=" + reason);
            } else {
                postNotify("手势失败：请确认系统无障碍已启用本服务");
                Log.e(TAG, "dispatchGesture returned false");
            }
        } else {
            // 未到滑动时机，刷新状态（诊断用）。
            if (progress > 0) {
                postNotify("进度 " + (int) (progress * 100) + "%  下次滑："
                        + Math.max(0, (nextSwipeAfter - now) / 1000) + "s");
            } else {
                postNotify("运行中（读不到进度文本）下次滑："
                        + Math.max(0, (nextSwipeAfter - now) / 1000) + "s");
            }
        }
    }

    /**
     * 尝试从当前窗口解析视频进度比例。
     * @return 0~1 的进度；读不到返回 -1。
     */
    private float detectProgress() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return -1f;
        try {
            return traverseForProgress(root);
        } finally {
            root.recycle();
        }
    }

    private float traverseForProgress(AccessibilityNodeInfo node) {
        if (node == null) return -1f;
        CharSequence text = node.getText();
        if (text != null) {
            Matcher m = PROGRESS_PATTERN.matcher(text);
            if (m.find()) {
                long cur = parseTime(m.group(1));
                long total = parseTime(m.group(2));
                if (total > 0) return (float) cur / total;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            float r = traverseForProgress(node.getChild(i));
            if (r >= 0) return r;
        }
        return -1f;
    }

    private long parseTime(String t) {
        try {
            String[] p = t.split(":");
            long min = Long.parseLong(p[0]);
            long sec = Long.parseLong(p[1]);
            return min * 60 + sec;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 下一次兜底间隔 = 基础间隔 + [0, 抖动) 随机秒。 */
    private long computeDelay() {
        int base = AppPreferences.getIntervalSec(this) * 1000;
        int jitter = AppPreferences.getJitterSec(this) * 1000;
        return base + (long) (Math.random() * jitter);
    }

    /** 在屏幕中部模拟一段垂直上滑手势。 */
    private boolean performSwipeUp() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;

        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        int x = w / 2;
        int startY = (int) (h * 0.78f);
        int endY = (int) (h * 0.22f);

        Path path = new Path();
        path.moveTo(x, startY);
        path.lineTo(x, endY);

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 320));

        return dispatchGesture(builder.build(), null, null);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "抖音自动刷", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("自动刷运行状态");
            nm.createNotificationChannel(ch);
        }
    }

    /** 更新状态栏通知（诊断用，尽力而为，失败忽略）。 */
    private void postNotify(String msg) {
        if (nm == null) return;
        try {
            Notification n = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("抖音自动刷")
                    .setContentText(msg)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setOngoing(true)
                    .build();
            nm.notify(NOTIF_ID, n);
        } catch (Exception e) {
            Log.e(TAG, "notify fail", e);
        }
    }

    @Override
    public void onInterrupt() {
        stopLoop();
    }

    @Override
    public void onDestroy() {
        stopLoop();
        if (nm != null) {
            try { nm.cancel(NOTIF_ID); } catch (Exception ignore) {}
        }
        super.onDestroy();
    }

    private void stopLoop() {
        if (handler != null && pollRunnable != null) {
            handler.removeCallbacks(pollRunnable);
        }
    }
}
