package cn.iyutong.rc2core.usb;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbManager;
import android.os.SystemClock;

/**
 * 接收 {@link UsbPermission} 的授权结果。
 *
 * <p>做成 <b>manifest 静态注册</b>而不是动态注册，原因有三个：
 * <ul>
 *   <li>{@link UsbManager#requestPermission} 的 {@code PendingIntent} 可以显式指向
 *       {@link #class}，广播走「组件广播」路径，不受动态注册时 Android 13+
 *       必须声明 {@code RECEIVER_EXPORTED/NOT_EXPORTED} 的限制；</li>
 *   <li>即使等待期间 Activity 被回收，静态接收器仍会拉起进程投递这条广播；</li>
 *   <li>可以干净地区分「广播根本没来」和「来了但没带结果字段」——
 *       后者几乎总是 {@code PendingIntent} 被声明成了不可变（immutable），
 *       系统无法把 {@code EXTRA_PERMISSION_GRANTED} 填进去。</li>
 * </ul>
 *
 * <p>状态放在静态字段里，因为广播到达时和等待方通常在同一个进程；这里刻意不做跨进程，
 * 跨进程需要额外加 AIDL，对「等一次授权」这种几十毫秒的事不值得。
 */
public final class UsbPermissionReceiver extends BroadcastReceiver {

    /** 私有 action，配合显式组件使用，别的应用无法伪造有效结果。 */
    public static final String ACTION = "cn.iyutong.rc2core.action.USB_PERMISSION";

    private static final Object GATE = new Object();

    private static boolean seen;
    private static boolean granted;
    private static boolean extrasPresent;
    private static boolean cancelled;
    private static long stamp;

    @Override
    public void onReceive(Context context, Intent intent) {
        deliver(intent);
    }

    /**
     * 把一条广播写进共享状态。
     *
     * <p>抽成静态方法，是为了让动态注册的兜底接收器也能走同一条路径，
     * 而不必再 new 一个实例。
     */
    static void deliver(Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) {
            return;
        }
        // hasExtra 与 getBooleanExtra 必须分开看：前者为 false 说明结果字段压根没被写进来
        boolean has = intent.hasExtra(UsbManager.EXTRA_PERMISSION_GRANTED);
        boolean ok = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
        synchronized (GATE) {
            seen = true;
            granted = ok;
            extrasPresent = has;
            stamp = SystemClock.uptimeMillis();
            GATE.notifyAll();
        }
    }

    // ───────────────────────── 给等待方用的静态接口 ─────────────────────────

    /** 清空上一次的结果，必须在发起新请求前调用。 */
    static void reset() {
        synchronized (GATE) {
            seen = false;
            granted = false;
            extrasPresent = false;
            cancelled = false;
            stamp = 0L;
        }
    }

    /**
     * 等结果。
     *
     * @return {@code true} 表示授权通过；超时、被拒绝、被取消都返回 {@code false}，
     *         具体原因用 {@link #seen()} / {@link #extrasPresent()} 区分
     */
    static boolean await(long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + Math.max(0L, timeoutMs);
        synchronized (GATE) {
            while (!seen && !cancelled) {
                long left = deadline - SystemClock.uptimeMillis();
                if (left <= 0) {
                    return false;
                }
                try {
                    GATE.wait(Math.min(left, 400L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return seen && granted;
        }
    }

    /** 广播是否到达过。为 false 即「30 秒无响应」的真实含义。 */
    static boolean seen() {
        synchronized (GATE) {
            return seen;
        }
    }

    /** 广播到达时是否带 {@code EXTRA_PERMISSION_GRANTED}。为 false 说明 PendingIntent 不可变。 */
    static boolean extrasPresent() {
        synchronized (GATE) {
            return extrasPresent;
        }
    }

    static long stamp() {
        synchronized (GATE) {
            return stamp;
        }
    }

    /** 提前唤醒等待方，用于「用户点了停止」。 */
    static void poke() {
        synchronized (GATE) {
            cancelled = true;
            GATE.notifyAll();
        }
    }
}
