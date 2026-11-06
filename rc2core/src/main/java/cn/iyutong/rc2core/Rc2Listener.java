package cn.iyutong.rc2core;

/**
 * 回调集合，全部是 default 方法：按需覆写，不用搭空实现。
 *
 * <p><b>线程</b>：默认在内部线程回调（Binder 线程 / 轮询线程）。
 * 想在主线程收回调，创建控制器时传 {@code Rc2Config.builder().callbackHandler(
 * new Handler(Looper.getMainLooper()))}。
 *
 * <pre>
 * rc2.addListener(new Rc2Listener() {
 *     &#64;Override public void onSticks(Sticks s) {  // 约 25Hz
 *         pad.setPosition(s.leftX(), s.leftY());
 *     }
 *     &#64;Override public void onButton(RcButton b, boolean pressed) {
 *         if (b == RcButton.SHUTTER &amp;&amp; pressed) shoot();
 *     }
 *     &#64;Override public void onFlightMode(FlightMode mode, int rawByte) {
 *         hud.setText(mode.label());
 *     }
 * });
 * rc2.start();
 * </pre>
 */
public interface Rc2Listener {

    /** 协议服务可用性变化。{@code ready == false} 时 {@code detail} 给出原因。 */
    default void onReady(boolean ready, String detail) {
    }

    /** 摇杆/拨轮采样，频率等于 {@link Rc2Config#pollIntervalMs}。 */
    default void onSticks(Sticks sticks) {
    }

    /** 按键状态变化。<b>只在变化时</b>回调，不像 onSticks 那样周期性触发。 */
    default void onButton(RcButton button, boolean pressed) {
    }

    /**
     * 挡位变化。<b>只在挡位码真正变化时</b>回调。
     *
     * <p>{@code rawByte} 是报文 b2 的原始值，便于校验编码；注意它的 bit2-3 是 C1/C2 按键位，
     * 所以同一个挡位下这个值也可能不同 —— 判断挡位请用 {@code mode} 或
     * {@link FlightMode#code(int)}，不要直接比 rawByte。
     */
    default void onFlightMode(FlightMode mode, int rawByte) {
    }

    /** 收到一条 Android 层按键事件。 */
    default void onKey(KeySample key) {
    }

    /** 出错，多数情况下 Controller 会继续重试。 */
    default void onError(String message) {
    }
}
