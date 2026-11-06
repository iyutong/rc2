package local.rc2.xbox;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import cn.iyutong.rc2core.ShutterProbe;
import local.rc2.xbox.ui.UiLog;

/**
 * 全局按键过滤服务：只为了抓快门半按。
 *
 * <p>普通应用在前台之外拿不到按键，唯一的免 root 办法是开一个带
 * {@code FLAG_REQUEST_FILTER_KEY_EVENTS} 的无障碍服务——系统会把<b>全部</b>按键在分发前
 * 复制一份给这个服务（{@link #onKeyEvent}），包括 {@code KEYCODE_FOCUS}（半按）与
 * {@code KEYCODE_CAMERA}（全按）。
 *
 * <p>这里不消费事件（返回 {@code false}），DJI Fly 照常收到按键，互不影响。
 * 需要用户在「设置 → 无障碍 → RC2 输入监视」里手动开启一次。
 */
public class ShutterKeyService extends AccessibilityService {

    private static final String TAG = "A11y";

    /** 服务是否已连上（用户有没有在设置里打开）。 */
    public static volatile boolean CONNECTED;
    /** 累计看到的按键数——用音量键按几下就能确认服务是活的。 */
    public static volatile long SEEN;
    /** 最近一个按键的可读描述。 */
    public static volatile String LAST = "—";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        CONNECTED = true;
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) {
            info = new AccessibilityServiceInfo();
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        }
        info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        info.notificationTimeout = 0;
        setServiceInfo(info);
        UiLog.i(TAG, "按键过滤已启用 · 等待 KEYCODE_FOCUS(80) / CAMERA(27)");
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        if (event == null) {
            return false;
        }
        // 先记账：只要这个数在涨，就说明服务是活的，「没抓到半按」才是有效结论
        SEEN++;
        LAST = ShutterProbe.keyName(event.getKeyCode())
                + (event.getAction() == KeyEvent.ACTION_DOWN ? " ↓" : " ↑");
        if (ShutterProbe.isShutterKey(event.getKeyCode())) {
            if (ShutterProbe.GLOBAL.feed(event, "a11y")) {
                UiLog.i(TAG, ShutterProbe.GLOBAL.lastTransition());
            }
        }
        // 不消费：事件继续交给当前前台应用（DJI Fly）
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 只做按键过滤，不关心界面事件
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        CONNECTED = false;
        UiLog.w(TAG, "按键过滤已关闭");
        return super.onUnbind(intent);
    }
}
