package com.example.douyinautoswiper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
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
 * 抖音极速版自动刷服务。
 *
 * 工作方式：
 *  - 通过系统无障碍服务拿到抖音极速版窗口内容；
 *  - 轮询检测视频进度文本（形如 "0:15/0:30"），进度≥95% 视为播完，立即上滑；
 *  - 读不到进度时，按「基础间隔 + 随机抖动」兜底定时上滑；
 *  - 每次上滑之间有最小节流间隔，避免手势重叠 / 切换过快。
 *
 * 注意：抖音极速版不会对外暴露"播完"回调，进度文本是否可读取依赖其版本实现，
 * 因此定时兜底是必要的保底策略。
 */
public class AutoSwipeService extends AccessibilityService {

    private static final String TAG = "AutoSwipeService";

    /** 监听的包名：抖音极速版。普通版请改成 com.ss.android.ugc.aweme（见 README）。 */
    private static final String TARGET_PACKAGE = "com.ss.android.ugc.aweme.lite";

    /** 轮询间隔（毫秒）。用于检查进度与定时兜底。 */
    private static final long POLL_MS = 1000;

    /** 两次上滑之间的最小间隔（毫秒），防止手势重叠。 */
    private static final long MIN_SWIPE_INTERVAL_MS = 2500;

    /** 进度达到该比例即视为"播完"，立即上滑。 */
    private static final float DONE_THRESHOLD = 0.95f;

    /** 匹配 "0:15/0:30" 形式的进度文本。 */
    private static final Pattern PROGRESS_PATTERN =
            Pattern.compile("(\\d{1,2}:\\d{2})\\s*/\\s*(\\d{1,2}:\\d{2})");

    private Handler handler;
    private Runnable pollRunnable;
    private long lastSwipeTime = 0;
    /** 下一次定时兜底上滑的目标时间戳。 */
    private long nextSwipeAfter = 0;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        handler = new Handler(Looper.getMainLooper());
        nextSwipeAfter = System.currentTimeMillis() + computeDelay();
        startLoop();
        Log.d(TAG, "service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!AppPreferences.isAutoSwipeEnabled(this)) return;
        if (event.getPackageName() == null) return;
        // 只处理目标包，其余忽略（服务在 manifest 中也已限制，双重保险）。
        if (!TARGET_PACKAGE.equals(event.getPackageName().toString())) return;
        // 实际滑动逻辑在轮询中统一处理，这里仅确认事件可达。
    }

    /** 启动轮询循环：每隔 POLL_MS 检查一次是否需要滑动。 */
    private void startLoop() {
        pollRunnable = new Runnable() {
            @Override
            public void run() {
                if (AppPreferences.isAutoSwipeEnabled(AutoSwipeService.this)
                        && TARGET_PACKAGE.equals(getCurrentPackage())) {
                    tick();
                }
                if (handler != null) {
                    handler.postDelayed(this, POLL_MS);
                }
            }
        };
        handler.postDelayed(pollRunnable, POLL_MS);
    }

    private String getCurrentPackage() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        CharSequence pkg = root.getPackageName();
        root.recycle();
        return pkg == null ? null : pkg.toString();
    }

    /** 一次轮询：决定本 tick 是否上滑。 */
    private void tick() {
        long now = System.currentTimeMillis();

        float progress = detectProgress();
        boolean progressDone = progress >= DONE_THRESHOLD;
        boolean timeUp = now >= nextSwipeAfter;

        boolean canSwipe = (now - lastSwipeTime) >= MIN_SWIPE_INTERVAL_MS;

        if (canSwipe && (progressDone || timeUp)) {
            if (performSwipeUp()) {
                lastSwipeTime = now;
                // 滑动成功后，重新计算带随机抖动的兜底间隔。
                nextSwipeAfter = now + computeDelay();
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

    /** 计算下一次兜底间隔（基础间隔 + [0, jitter) 随机抖动）。 */
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
        // 起点(start) 0ms，滑动持续 320ms，模拟人手上滑节奏。
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 320));

        return dispatchGesture(builder.build(), null, null);
    }

    @Override
    public void onInterrupt() {
        stopLoop();
    }

    @Override
    public void onDestroy() {
        stopLoop();
        super.onDestroy();
    }

    private void stopLoop() {
        if (handler != null && pollRunnable != null) {
            handler.removeCallbacks(pollRunnable);
        }
    }
}
