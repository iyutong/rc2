package cn.iyutong.rc2core;

import android.os.Handler;

/**
 * 核心库的可调参数。全部有默认值，一般情况下 {@code Rc2Controller.create()} 就够了。
 */
public final class Rc2Config {

    /** 摇杆轮询间隔，默认 40ms（≈25Hz，与遥控器上报节奏一致）。 */
    public final int pollIntervalMs;
    /**
     * 按键自动释放超时，默认 0 表示关闭。
     *
     * <p>按键报文是"变化才上报"的，如果一条松开报文丢了，按键会一直保持按下状态。
     * 打开后，超过该毫秒数没有新的按键报文就认为全部松开，并补发释放回调。
     * 若确认设备会周期性重发按键报文，可以设成 1500 之类的值。
     */
    public final int buttonTimeoutMs;
    /** 推送包环形缓存容量，供 {@link Rc2Controller#packets()} 查询。 */
    public final int packetRing;
    /** 回调线程：null 表示在 Binder/轮询线程直接回调；设为主线程 Handler 则自动切线程。 */
    public final Handler callbackHandler;
    /** 日志出口，默认写 logcat。 */
    public final Rc2Log logger;

    private Rc2Config(Builder b) {
        this.pollIntervalMs = b.pollIntervalMs;
        this.buttonTimeoutMs = b.buttonTimeoutMs;
        this.packetRing = b.packetRing;
        this.callbackHandler = b.callbackHandler;
        this.logger = b.logger;
    }

    public static Rc2Config defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int pollIntervalMs = 40;
        private int buttonTimeoutMs = 0;
        private int packetRing = 128;
        private Handler callbackHandler;
        private Rc2Log logger = Rc2Log.ANDROID;

        /** 轮询间隔，最小 16ms。 */
        public Builder pollIntervalMs(int ms) {
            this.pollIntervalMs = Math.max(16, ms);
            return this;
        }

        /** 按键自动释放超时，0 关闭。 */
        public Builder buttonTimeoutMs(int ms) {
            this.buttonTimeoutMs = Math.max(0, ms);
            return this;
        }

        /** 推送包缓存条数，0 表示不缓存。 */
        public Builder packetRing(int size) {
            this.packetRing = Math.max(0, size);
            return this;
        }

        /** 把回调切到指定线程（例如主线程），null 表示不切换。 */
        public Builder callbackHandler(Handler handler) {
            this.callbackHandler = handler;
            return this;
        }

        public Builder logger(Rc2Log log) {
            this.logger = log == null ? Rc2Log.ANDROID : log;
            return this;
        }

        public Rc2Config build() {
            return new Rc2Config(this);
        }
    }
}
