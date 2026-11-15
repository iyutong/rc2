package cn.iyutong.rc2core;

import android.os.SystemClock;
import android.view.KeyEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import cn.iyutong.rc2core.internal.ButtonCodec;
import cn.iyutong.rc2core.internal.DjiProtocol;

/**
 * RC2 遥控器输入的唯一入口。
 *
 * <p>对外只暴露「摇杆数据 + 按键事件 + 挡位 + 连接状态」，协议细节（Binder 反射、
 * 报文解码）全部收在内部。零第三方依赖，只用到 {@code android.os} / {@code android.view} /
 * {@code android.util}，可以直接把 {@code cn.iyutong.rc2core} 包搬到别的工程里用。
 *
 * <h3>最小用法</h3>
 * <pre>
 * Rc2Controller rc2 = Rc2Controller.create();
 * rc2.addListener(new Rc2Listener() {
 *     &#64;Override public void onSticks(Sticks s) { pad.setPosition(s.leftX(), s.leftY()); }
 *     &#64;Override public void onButton(RcButton b, boolean down) { ... }
 *     &#64;Override public void onFlightMode(FlightMode m, int raw) { ... }
 * });
 * rc2.start();   // onStart
 * rc2.stop();    // onStop
 * </pre>
 *
 * <h3>两种取数方式</h3>
 * <ul>
 *   <li><b>回调</b>：{@link Rc2Listener}，事件驱动，按键只报变化。</li>
 *   <li><b>轮询</b>：{@link #sticks()}、{@link #isPressed(RcButton)} 等 getter
 *       直接读最新值，零分配，适合每帧刷界面。</li>
 * </ul>
 *
 * <h3>线程</h3>
 * 默认回调发生在内部线程（Binder / 轮询线程），改 UI 需要自己 post。
 * 传 {@code Rc2Config.builder().callbackHandler(new Handler(Looper.getMainLooper()))}
 * 就自动切到主线程。getter 可以在任意线程调用。
 *
 * <h3>生命周期</h3>
 * {@link #start()} / {@link #stop()} 都是幂等的，可以反复调用（对应 Activity 的
 * onStart/onStop）。每次 start 会新建一条协议线程，stop 时把它退订并退出；
 * 旧会话里迟到的回调会被 epoch 拦掉，不会污染新一轮的状态。
 */
public final class Rc2Controller {

    private static final String TAG = "Input";
    /** 变化帧环形缓冲的容量。 */
    private static final int MAX_FRAMES = 16;
    /** 挡位观测表最多记几种编码。 */
    private static final int MAX_MODE_SIGHTINGS = 8;

    private final Rc2Config config;
    private final CopyOnWriteArrayList<Rc2Listener> listeners = new CopyOnWriteArrayList<>();

    /** 以下状态全部可以被任意线程读，写入只发生在内部线程。 */
    private volatile DjiProtocol protocol;
    private volatile boolean started;
    /**
     * 会话代号：每次 start/stop 自增，Sink 回调带着创建时的代号回来比对。
     *
     * <p>没有它会有两类脏数据：stop 之后仍在途的推送把 {@code pressedMask} 又写回非 0
     * （按键永远亮着）；以及快速 stop→start 时旧连接的回调被当成新会话的事件。
     */
    private volatile int epoch;
    private volatile boolean connected;
    private volatile String status = "未启动";
    private volatile Sticks sticks = Sticks.center();
    private volatile long sampleAt;
    /** 计数器会在 Binder 线程池里并发累加，用原子类型避免 x++ 的读-改-写丢计数。 */
    private final AtomicLong samples = new AtomicLong();
    private final AtomicLong packets = new AtomicLong();
    private volatile FlightMode mode = FlightMode.UNKNOWN;
    private volatile int modeByte = -1;
    /** 挡位码（b2 低 2 位）。判定变更只认它，不认原始字节，见 handleMode。 */
    private volatile int modeCode = -1;
    private volatile String modeSource = "—";
    private volatile int pressedMask;
    private volatile byte[] rawButtons = new byte[0];
    private volatile String lastDiff = "—";

    /**
     * 需要跨字段一致的写入用这把锁。
     *
     * <p>按键报文（推送线程）与摇杆报文（查询回调线程）是两条不同的 Binder 线程，
     * 都可能去改 {@code pressedMask} / {@code buttonAt}，所以读-改-写必须串行化，
     * 否则会丢按键事件或者出现「按住却报松开」。
     */
    private final Object lock = new Object();
    private final LinkedHashSet<String> keyHeld = new LinkedHashSet<>();
    private final ArrayDeque<byte[]> changedFrames = new ArrayDeque<>();
    private final LinkedHashMap<Integer, Integer> modeSightings = new LinkedHashMap<>();
    private byte[] priorButtons;
    private long buttonAt;

    private Rc2Controller(Rc2Config config) {
        this.config = config;
    }

    public static Rc2Controller create() {
        return new Rc2Controller(Rc2Config.defaults());
    }

    public static Rc2Controller create(Rc2Config config) {
        return new Rc2Controller(config == null ? Rc2Config.defaults() : config);
    }

    /** 全进程共用一个实例；适合「一个遥控器只被一个界面用」的常规场景。 */
    public static Rc2Controller shared() {
        return Holder.INSTANCE;
    }

    private static final class Holder {
        private static final Rc2Controller INSTANCE = create();
    }

    // ───────────────────────── 生命周期 ─────────────────────────

    /** 幂等启动：连不上 protocol 服务时不会抛异常，只会走 {@code onReady(false, 原因)}。 */
    public void start() {
        DjiProtocol p;
        synchronized (lock) {
            if (started) {
                return;
            }
            started = true;
            epoch++;
            status = "连接中";
            // 新一轮会话从干净状态开始：旧会话的按键帧不能拿来做 diff 基准
            priorButtons = null;
            rawButtons = new byte[0];
            lastDiff = "—";
            buttonAt = 0;
            pressedMask = 0;
            p = new DjiProtocol(new Sink(epoch), config.pollIntervalMs);
            protocol = p;
        }
        // 建线程 + 订阅放在锁外：协议层一启动就会开始回调，没必要占着控制器锁
        p.start();
        log('I', TAG, "开始监听遥控器");
    }

    /** 幂等停止：退订、停线程、把按住的键补一个松开事件。 */
    public void stop() {
        DjiProtocol p;
        List<RcButton> released = null;
        synchronized (lock) {
            if (!started) {
                return;
            }
            started = false;
            epoch++;
            p = protocol;
            protocol = null;
            connected = false;
            status = "已停止";
            keyHeld.clear();
            priorButtons = null;
            rawButtons = new byte[0];
            int mask = pressedMask;
            if (mask != 0) {
                pressedMask = 0;
                released = new ArrayList<>(2);
                for (RcButton b : RcButton.values()) {
                    if (b.isDown(mask)) {
                        released.add(b);
                    }
                }
            }
        }
        if (p != null) {
            p.stop();
        }
        // 回调放在锁外发，避免宿主在回调里反调本类造成死锁
        if (released != null) {
            for (RcButton b : released) {
                emitButton(b, false);
            }
        }
        log('I', TAG, "停止监听");
    }

    public boolean isRunning() {
        return started;
    }

    /** protocol 服务是否已订阅成功。 */
    public boolean isConnected() {
        return connected;
    }

    /** 面向人的连接状态文案，可直接显示。 */
    public String status() {
        return status;
    }

    public Rc2Config config() {
        return config;
    }

    // ───────────────────────── 监听器 ─────────────────────────

    public void addListener(Rc2Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Rc2Listener listener) {
        listeners.remove(listener);
    }

    // ───────────────────────── 实时取值（零分配） ─────────────────────────

    /** 最近一帧摇杆数据，尚未收到时是中位。 */
    public Sticks sticks() {
        return sticks;
    }

    /** 最近一帧摇杆数据的时刻，0 表示还没收到。 */
    public long sampledAt() {
        return sampleAt;
    }

    /** 累计摇杆采样数，可用于算 Hz。 */
    public long samples() {
        return samples.get();
    }

    /** 最近一秒内是否有数据，判断「活的」比 isConnected 更可靠。 */
    public boolean isLive() {
        return started && sampleAt != 0 && SystemClock.uptimeMillis() - sampleAt < 500;
    }

    public FlightMode flightMode() {
        return mode;
    }

    /** 挡位报文 b2 原始值，未收到为 -1。注意 bit2-3 是 C1/C2，会随按键变化。 */
    public int flightModeByte() {
        return modeByte;
    }

    /** 挡位码（b2 低 2 位）：0→S，1→N，2→C，3→未知；未收到为 -1。 */
    public int flightModeCode() {
        return modeCode;
    }

    /** 挡位来源，例如 "6/174 t2"。 */
    public String flightModeSource() {
        return modeSource;
    }

    /** 观察到的挡位码与出现次数，形如 "0×12 1×3"，用于核对挡位编码。 */
    public String modeSightings() {
        synchronized (lock) {
            if (modeSightings.isEmpty()) {
                return "—";
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<Integer, Integer> e : modeSightings.entrySet()) {
                if (sb.length() > 0) {
                    sb.append("  ");
                }
                sb.append(e.getKey()).append('×').append(e.getValue());
            }
            return sb.toString();
        }
    }

    public boolean isPressed(RcButton button) {
        return button != null && button.isDown(pressedMask);
    }

    public int pressedMask() {
        return pressedMask;
    }

    /** 任意来源（MCU 位或 Android 按键）上该标签是否处于按下状态。 */
    public boolean isHeld(String label) {
        if (label == null) {
            return false;
        }
        RcButton button = RcButton.byLabel(label);
        if (button != null && button.isDown(pressedMask)) {
            return true;
        }
        synchronized (lock) {
            return keyHeld.contains(label);
        }
    }

    /** 所有按住的东西（MCU 按键 + Android 按键），返回副本。 */
    public Set<String> heldLabels() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        int mask = pressedMask;
        for (RcButton b : RcButton.values()) {
            if (b.isDown(mask)) {
                out.add(b.label());
            }
        }
        synchronized (lock) {
            out.addAll(keyHeld);
        }
        return out;
    }

    /** 最近一帧按键报文的副本。 */
    public byte[] rawButtons() {
        return rawButtons.clone();
    }

    /** 与上一变化帧的差异位，例如 "b2.4 b1.0"。 */
    public String lastDiff() {
        return lastDiff;
    }

    /**
     * 内容发生过变化的按键帧，时间升序；每个元素都是独立副本。
     *
     * <p>返回的是深拷贝：内部环形缓冲里的数组同时被 {@code priorButtons} 引用着，
     * 直接漏出去的话调用方改一个字节就会把下一帧的差异计算带偏。
     */
    public List<byte[]> changedFrames() {
        synchronized (lock) {
            List<byte[]> out = new ArrayList<>(changedFrames.size());
            for (byte[] f : changedFrames) {
                out.add(f.clone());
            }
            return out;
        }
    }

    /** 累计推送包数。 */
    public long packetCount() {
        return packets.get();
    }

    /**
     * 把宿主收到的 Android 按键事件转进来（用于抓 KEYCODE_FOCUS 这类系统层按键）。
     *
     * <pre>
     * public boolean dispatchKeyEvent(KeyEvent e) {
     *     rc2.handleKeyEvent(e);
     *     return super.dispatchKeyEvent(e);
     * }
     * </pre>
     */
    public void handleKeyEvent(KeyEvent e) {
        if (e == null) {
            return;
        }
        final KeySample sample = new KeySample(e, SystemClock.uptimeMillis());
        final int action = e.getAction();
        if (action == KeyEvent.ACTION_DOWN) {
            synchronized (lock) {
                keyHeld.add(sample.shortLabel());
            }
            if (e.getRepeatCount() == 0) {
                log('I', "Key", sample.toString());
            }
        } else if (action == KeyEvent.ACTION_UP) {
            synchronized (lock) {
                keyHeld.remove(sample.shortLabel());
            }
        }
        for (Rc2Listener l : listeners) {
            emit(() -> l.onKey(sample));
        }
    }

    /** 取一份冻结快照。界面每帧刷新请改用上面的 getter，避免分配。 */
    public Rc2State snapshot() {
        synchronized (lock) {
            return new Rc2State(connected, status, sticks, mode, modeByte, modeCode, modeSource,
                    pressedMask, heldLabels(), packets.get(), samples.get(), rawButtons, lastDiff,
                    SystemClock.uptimeMillis());
        }
    }

    // ───────────────────────── 内部：协议回调 ─────────────────────────

    /**
     * 协议层回调的接收点。
     *
     * <p>每个回调先比一次 epoch：{@link #stop()} 之后仍在途的推送、以及快速
     * stop→start 时旧连接的残留回调，都不该再改任何状态。
     */
    private final class Sink implements DjiProtocol.Sink {

        private final int session;

        Sink(int session) {
            this.session = session;
        }

        /** 是否为当前会话；false 表示这条回调来自已经停止的那一轮。 */
        private boolean alive() {
            return session == epoch && started;
        }

        @Override
        public void onSticks(int[] channels) {
            if (!alive()) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            Sticks s = Sticks.of(channels);
            sticks = s;
            sampleAt = now;
            samples.incrementAndGet();
            expireButtonsIfIdle(now);
            for (Rc2Listener l : listeners) {
                emit(() -> l.onSticks(s));
            }
        }

        @Override
        public void onPush(int[] header, byte[] payload) {
            if (payload == null || !alive()) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            int cmdSet = header.length > 13 ? header[13] : -1;
            int cmdId = header.length > 14 ? header[14] : -1;
            int type = header.length > 5 ? header[5] : -1;

            packets.incrementAndGet();

            // 只处理主通道：按键在 type 2 的负载里，挡位在同一条 17 字节报文的 b2。
            boolean main = cmdSet == DjiProtocol.MAIN_CMD_SET && cmdId == DjiProtocol.MAIN_CMD_ID;
            if (main && type == 2) {
                handleButtons(payload, now);
            }
            if (main && payload.length == 17) {
                handleMode(cmdSet, cmdId, type, payload);
            }
        }

        @Override
        public void onReady(boolean ready, String detail) {
            if (!alive()) {
                return;
            }
            connected = ready;
            status = detail;
            if (ready) {
                log('I', "MCU", detail);
            } else {
                log('E', "MCU", detail);
            }
            for (Rc2Listener l : listeners) {
                emit(() -> l.onReady(ready, detail));
            }
        }

        @Override
        public void onError(String message) {
            if (!alive()) {
                return;
            }
            status = message;
            log('W', "MCU", message);
            for (Rc2Listener l : listeners) {
                emit(() -> l.onError(message));
            }
        }
    }

    /** 按键报文 → 位掩码差异 → 逐个按键事件。 */
    private void handleButtons(byte[] payload, long now) {
        int mask;
        int prev;
        List<RcButton> changed = null;
        synchronized (lock) {
            mask = ButtonCodec.mask(payload);
            prev = pressedMask;

            boolean frameChanged = priorButtons == null
                    || !Arrays.equals(priorButtons, payload);
            if (frameChanged) {
                lastDiff = ButtonCodec.diff(priorButtons, payload);
                priorButtons = payload.clone();
                rawButtons = priorButtons;
                changedFrames.addLast(priorButtons);
                while (changedFrames.size() > MAX_FRAMES) {
                    changedFrames.removeFirst();
                }
            }
            if (mask != 0) {
                buttonAt = now;
            }
            if (prev != mask) {
                pressedMask = mask;
                changed = new ArrayList<>(2);
                for (RcButton b : RcButton.values()) {
                    if (b.isDown(prev) != b.isDown(mask)) {
                        changed.add(b);
                    }
                }
            }
        }
        if (changed == null) {
            return;
        }
        // 事件在锁外发：宿主回调里读 isPressed 能拿到已更新的值，也不会卡住推送线程
        for (RcButton b : changed) {
            emitButton(b, b.isDown(mask));
        }
    }

    private void emitButton(RcButton b, boolean pressed) {
        log('I', "MCU", "按键 " + b.label() + (pressed ? " 按下" : " 松开"));
        for (Rc2Listener l : listeners) {
            emit(() -> l.onButton(b, pressed));
        }
    }

    /**
     * 挡位报文 → FlightMode 变化事件。
     *
     * <p>判定「变没变」只看 b2 的低 2 位（{@link FlightMode#code(int)}），不看原始字节：
     * b2 的 bit2-3 是 C1/C2 按键位，按这两个键会让 b2 从 0x00 变 0x04/0x08，
     * 挡位其实没动。之前按原始字节比较，于是每按一次 C1/C2 就刷一条「S → S」的假日志。
     */
    private void handleMode(int cmdSet, int cmdId, int type, byte[] payload) {
        int b2 = payload[2] & 0xff;
        int code = FlightMode.code(b2);
        synchronized (lock) {
            modeSightings.compute(code, (k, seen) -> seen == null ? 1 : seen + 1);
            while (modeSightings.size() > MAX_MODE_SIGHTINGS) {
                Integer oldest = modeSightings.keySet().iterator().next();
                modeSightings.remove(oldest);
            }
        }
        if (code == modeCode) {
            // 挡位没变，但 b2 可能因 C1/C2 变了，静默更新原始值供诊断。
            modeByte = b2;
            return;
        }
        FlightMode prev = mode;
        FlightMode next = FlightMode.fromCode(code);
        modeByte = b2;
        modeCode = code;
        mode = next;
        modeSource = cmdSet + "/" + cmdId + " t" + type;
        log('I', "Mode", "飞行模式 " + prev.label() + " → " + next.label()
                + String.format(Locale.US, "（码 %d · b2=0x%02X）", code, b2));
        for (Rc2Listener l : listeners) {
            final int raw = b2;
            emit(() -> l.onFlightMode(next, raw));
        }
    }

    /**
     * 可选的按键超时释放：报文丢了也不会一直亮着。默认关闭。
     *
     * <p>只在收到摇杆帧时检查 —— 摇杆是周期性的，正好可以当心跳；按键链路彻底断掉时
     * 摇杆也会断，这时候宿主该看 {@link #isLive()} 而不是指望这里兜底。
     */
    private void expireButtonsIfIdle(long now) {
        final int timeout = config.buttonTimeoutMs;
        if (timeout <= 0) {
            return;
        }
        List<RcButton> released = null;
        synchronized (lock) {
            if (pressedMask == 0 || buttonAt == 0) {
                return;
            }
            if (now - buttonAt <= timeout) {
                return;
            }
            int mask = pressedMask;
            pressedMask = 0;
            buttonAt = 0;
            released = new ArrayList<>(2);
            for (RcButton b : RcButton.values()) {
                if (b.isDown(mask)) {
                    released.add(b);
                }
            }
        }
        log('W', "MCU", "按键报文超时，释放全部按键");
        for (RcButton b : released) {
            emitButton(b, false);
        }
    }

    // ───────────────────────── 内部：工具 ─────────────────────────

    private void emit(Runnable action) {
        if (config.callbackHandler == null) {
            action.run();
        } else {
            config.callbackHandler.post(action);
        }
    }

    void log(char level, String tag, String message) {
        config.logger.log(level, tag, message);
    }
}
