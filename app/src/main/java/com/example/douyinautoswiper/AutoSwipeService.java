package com.example.douyinautoswiper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 抖音极速版自动刷服务（v2.1）。
 *
 * 相对 v2 的改进：
 *  - 拟人化随机滑动（默认开）：每次上滑的起始 X、终点 X（带横向偏移）、
 *    Y 区间、滑动时长、轨迹弧度均随机化；小概率「本次停顿跳过」模拟看入神；
 *    小概率「手势末段微回弹」模拟手指抖动。整体节奏不再规律。
 *  - 可选悬浮状态窗（默认关）：在抖音上层角落显示实时状态文字，
 *    不拦截触摸、不影响滑动；抖音本身无感。需 SYSTEM_ALERT_WINDOW 权限。
 *  - 保留 v2 的修复：激活判断靠事件包名、定时必跑、状态栏通知诊断。
 *
 * 风控边界说明：纯无障碍方案读不到抖音播放进度（自绘 View），抖音侧
 * 也无法感知本 App。拟人化仅降低「行为规律性」被模型识别的概率，不保证
 * 绝对安全；请合规使用、风险自负。
 */
public class AutoSwipeService extends AccessibilityService {

    private static final String TAG = "AutoSwipe";

    /** 抖音极速版包名。普通版抖音请改成 com.ss.android.ugc.aweme（见 README）。 */
    private static final String TARGET_PACKAGE = "com.ss.android.ugc.aweme.lite";

    private static final long POLL_MS = 1000;
    /** 两次上滑最小间隔（毫秒），防止手势重叠 / 切换过快。 */
    private static final long MIN_SWIPE_INTERVAL_MS = 2500;
    /** 进度达到该比例即视为「播完」，立即上滑。 */
    private static final float DONE_THRESHOLD = 0.95f;

    /** 拟人化：本次「随机停顿」概率（0~1）。 */
    private static final double PAUSE_PROB = 0.08;
    /** 拟人化：手势末段「微回弹」概率（0~1）。 */
    private static final double REBOUND_PROB = 0.25;

    private static final Pattern PROGRESS_PATTERN =
            Pattern.compile("(\\d{1,2}:\\d{2})\\s*/\\s*(\\d{1,2}:\\d{2})");

    private static final String CHANNEL_ID = "douyin_autoswiper_channel";
    private static final int NOTIF_ID = 1001;

    private Handler handler;
    private Runnable pollRunnable;
    private long lastSwipeTime = 0;
    private long nextSwipeAfter = 0;
    private boolean targetActive = false;
    private int swipeCount = 0;
    private NotificationManager nm;

    private DisplayMetrics dm;
    private WindowManager wm;
    private TextView overlayView;
    private WindowManager.LayoutParams overlayLp;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        handler = new Handler(Looper.getMainLooper());
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        dm = getResources().getDisplayMetrics();
        createChannel();
        if (AppPreferences.isOverlayEnabled(this)) {
            initOverlay();
        }
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
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            targetActive = TARGET_PACKAGE.equals(p);
        }
    }

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

        if (!canSwipe) {
            return; // 节流中，保持静默避免刷屏
        }
        if (!(progressDone || timeUp)) {
            if (progress > 0) {
                postNotify("进度 " + (int) (progress * 100) + "%  下次滑："
                        + Math.max(0, (nextSwipeAfter - now) / 1000) + "s");
            } else {
                postNotify("运行中（读不到进度）下次滑："
                        + Math.max(0, (nextSwipeAfter - now) / 1000) + "s");
            }
            return;
        }

        // 到了该滑动的时机
        boolean humanize = AppPreferences.isHumanizeEnabled(this);
        if (humanize && Math.random() < PAUSE_PROB) {
            // 拟人化：本次「看入神」随机停顿，延后一小段再判，不计数
            nextSwipeAfter = now + 1200 + (long) (Math.random() * 1500);
            postNotify("运行中·本次随机停顿(拟人化)");
            return;
        }

        boolean ok = performSwipeUp(humanize);
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
    }

    /**
     * 模拟一段上滑手势。humanize=true 时坐标/轨迹/时长全部随机化。
     */
    private boolean performSwipeUp(boolean humanize) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;

        int w = dm.widthPixels;
        int h = dm.heightPixels;

        int startX, endX, startY, endY, duration;
        if (humanize) {
            startX = (int) (w * (0.35 + Math.random() * 0.30));          // 0.35~0.65
            endX = clamp(startX + (int) ((Math.random() - 0.5) * w * 0.10),
                    (int) (w * 0.20), (int) (w * 0.80));                  // 带横向偏移
            startY = (int) (h * (0.70 + Math.random() * 0.15));           // 0.70~0.85
            endY = (int) (h * (0.15 + Math.random() * 0.15));             // 0.15~0.30
            duration = 260 + (int) (Math.random() * 260);                 // 260~520ms
        } else {
            startX = w / 2;
            endX = w / 2;
            startY = (int) (h * 0.78);
            endY = (int) (h * 0.22);
            duration = 320;
        }

        Path path = new Path();
        path.moveTo(startX, startY);
        // 中段控制点带随机横向偏移，形成轻微弧度（真人手指不会绝对笔直）
        int midX = (startX + endX) / 2 + (humanize ? (int) ((Math.random() - 0.5) * w * 0.06) : 0);
        int midY = (startY + endY) / 2;
        path.quadTo(midX, midY, endX, endY);
        // 小概率末段微回弹：往回退几像素，模拟手指抬起前抖动
        if (humanize && Math.random() < REBOUND_PROB) {
            path.lineTo(endX + (int) ((Math.random() - 0.5) * 24),
                    endY + (int) (h * 0.02));
        }

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, duration));
        return dispatchGesture(builder.build(), null, null);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

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

    // ---------- 悬浮状态窗 ----------

    private void initOverlay() {
        if (wm == null) return;
        try {
            overlayView = new TextView(this);
            overlayView.setTextSize(12);
            overlayView.setTextColor(0xFFFFFFFF);
            overlayView.setBackgroundColor(0x66000000);
            overlayView.setPadding(12, 6, 12, 6);
            overlayLp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            overlayLp.gravity = Gravity.TOP | Gravity.START;
            overlayLp.x = 16;
            overlayLp.y = 90;
            wm.addView(overlayView, overlayLp);
        } catch (Exception e) {
            Log.e(TAG, "overlay init failed (权限?)", e);
            overlayView = null;
        }
    }

    private void updateOverlay(String msg) {
        if (overlayView != null) {
            try {
                overlayView.setText(msg);
            } catch (Exception ignore) {
            }
        }
    }

    private void removeOverlay() {
        if (wm != null && overlayView != null) {
            try {
                wm.removeView(overlayView);
            } catch (Exception ignore) {
            }
            overlayView = null;
        }
    }

    // ---------- 通知 ----------

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "抖音自动刷", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("自动刷运行状态");
            nm.createNotificationChannel(ch);
        }
    }

    private void postNotify(String msg) {
        updateOverlay(msg);
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
        removeOverlay();
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
