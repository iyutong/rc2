package cn.iyutong.rc2core;

import android.os.SystemClock;
import android.view.KeyEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
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
 */
public final class Rc2Controller {

    private static final String TAG = "Input";

    private final Rc2Config config;
    private final CopyOnWriteArrayList<Rc2Listener> listeners = new CopyOnWriteArrayList<>();

    /** 以下状态全部可以被任意线程读，写入只发生在内部线程。 */
    private volatile DjiProtocol protocol;
    private volatile boolean started;
    private volatile boolean connected;
    private volatile String status = "未启动";
    private volatile Sticks sticks = Sticks.center();
    private volatile long sampleAt;
    private volatile long samples;
    private volatile long packets;
    private volatile FlightMode mode = FlightMode.UNKNOWN;
    private volatile int modeByte = -1;
    /** 挡位码（b2 低 2 位）。判定变更只认它，不认原始字节，见 handleMode。 */
    private volatile int modeCode = -1;
    private volatile String modeSource = "—";
    private volatile int pressedMask;
    private volatile byte[] rawButtons = new byte[0];
    private volatile String lastDiff = "—";

    /** 需要跨字段一致的写入用这把锁。 */
    private final Object lock = new Object();
    private final LinkedHashSet<String> keyHeld = new LinkedHashSet<>();
    private final ArrayDeque<Rc2Packet> packetRing = new ArrayDeque<>();
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
        synchronized (lock) {
            if (started) {
                return;
            }
            started = true;
            status = "连接中";
            DjiProtocol p = new DjiProtocol(new Sink(), config.pollIntervalMs);
            protocol = p;
            p.start();
        }
        log('I', TAG, "开始监听遥控器");
    }

    /** 幂等停止：退订、停线程、把按住的键补一个松开事件。 */
    public void stop() {
        DjiProtocol p;
        synchronized (lock) {
            if (!started) {
                return;
            }
            started = false;
            p = protocol;
            protocol = null;
            connected = false;
            status = "已停止";
            keyHeld.clear();
        }
        if (p != null) {
            p.stop();
        }
        int mask = pressedMask;
        if (mask != 0) {
            pressedMask = 0;
            for (RcButton b : RcButton.values()) {
                if (b.isDown(mask)) {
                    emitButton(b, false);
                }
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
        return samples;
    }

    /** 最近一秒内是否有数据，判断「活的」比 isConnected 更可靠。 */
    public boolean isLive() {
        return started && SystemClock.uptimeMillis() - sampleAt < 500;
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
            for (java.util.Map.Entry<Integer, Integer> e : modeSightings.entrySet()) {
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

    /** 内容发生过变化的按键帧，时间升序。 */
    public List<byte[]> changedFrames() {
        synchronized (lock) {
            return new ArrayList<>(changedFrames);
        }
    }

    /** 累计推送包数。 */
    public long packetCount() {
        return packets;
    }

    /** 推送包缓存（最新的在末尾）。 */
    public List<Rc2Packet> packets() {
        synchronized (lock) {
            return new ArrayList<>(packetRing);
        }
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
        KeySample sample = new KeySample(e, SystemClock.uptimeMillis());
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            synchronized (lock) {
                keyHeld.add(sample.shortLabel());
            }
            if (e.getRepeatCount() == 0) {
                log('I', "Key", sample.toString());
            }
        } else if (e.getAction() == KeyEvent.ACTION_UP) {
            synchronized (lock) {
                keyHeld.remove(sample.shortLabel());
            }
        }
        for (Rc2Listener l : listeners) {
            final KeySample k = sample;
            emit(() -> l.onKey(k));
        }
    }

    /** 取一份冻结快照。界面每帧刷新请改用上面的 getter，避免分配。 */
    public Rc2State snapshot() {
        synchronized (lock) {
            return new Rc2State(connected, status, sticks, mode, modeByte, modeCode, modeSource,
                    pressedMask, heldLabels(), packets, samples, rawButtons, lastDiff,
                    SystemClock.uptimeMillis());
        }
    }

    // ───────────────────────── 内部：协议回调 ─────────────────────────

    private final class Sink implements DjiProtocol.Sink {

        @Override
        public void onSticks(int[] channels) {
            long now = SystemClock.uptimeMillis();
            Sticks s = Sticks.of(channels);
            sticks = s;
            sampleAt = now;
            samples++;
            expireButtonsIfIdle(now);
            for (Rc2Listener l : listeners) {
                emit(() -> l.onSticks(s));
            }
        }

        @Override
        public void onPush(int[] header, byte[] payload) {
            if (payload == null) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            int cmdSet = header.length > 13 ? header[13] : -1;
            int cmdId = header.length > 14 ? header[14] : -1;
            int type = header.length > 5 ? header[5] : -1;

            packets++;
            if (config.packetRing > 0) {
                Rc2Packet p = new Rc2Packet(cmdSet, cmdId, type, payload, now);
                synchronized (lock) {
                    packetRing.addLast(p);
                    while (packetRing.size() > config.packetRing) {
                        packetRing.removeFirst();
                    }
                }
                for (Rc2Listener l : listeners) {
                    emit(() -> l.onPacket(p));
                }
            }

            if (cmdSet == 6 && cmdId == 174 && type == 2) {
                handleButtons(payload, now);
            }
            if (payload.length == 17) {
                handleMode(cmdSet, cmdId, type, payload);
            }
        }

        @Override
        public void onReady(boolean ready, String detail) {
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
            status = message;
            log('W', "MCU", message);
            for (Rc2Listener l : listeners) {
                emit(() -> l.onError(message));
            }
        }
    }

    /** 按键报文 → 位掩码差异 → 逐个按键事件。 */
    private void handleButtons(byte[] payload, long now) {
        int mask = ButtonCodec.mask(payload);
        int prev;
        synchronized (lock) {
            prev = pressedMask;
            boolean changed = priorButtons == null
                    || !java.util.Arrays.equals(priorButtons, payload);
            if (changed) {
                lastDiff = ButtonCodec.diff(priorButtons, payload);
                priorButtons = payload.clone();
                rawButtons = priorButtons;
                changedFrames.addLast(priorButtons);
                while (changedFrames.size() > 16) {
                    changedFrames.removeFirst();
                }
            }
            if (mask != 0) {
                buttonAt = now;
            }
        }
        if (prev == mask) {
            return;
        }
        pressedMask = mask;
        for (RcButton b : RcButton.values()) {
            boolean was = b.isDown(prev);
            boolean is = b.isDown(mask);
            if (was != is) {
                emitButton(b, is);
            }
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
            Integer seen = modeSightings.get(code);
            modeSightings.put(code, seen == null ? 1 : seen + 1);
            while (modeSightings.size() > 8) {
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

    /** 可选的按键超时释放：报文丢了也不会一直亮着。默认关闭。 */
    private void expireButtonsIfIdle(long now) {
        if (config.buttonTimeoutMs <= 0 || pressedMask == 0 || buttonAt == 0) {
            return;
        }
        if (now - buttonAt <= config.buttonTimeoutMs) {
            return;
        }
        int mask = pressedMask;
        pressedMask = 0;
        log('W', "MCU", "按键报文超时，释放全部按键");
        for (RcButton b : RcButton.values()) {
            if (b.isDown(mask)) {
                emitButton(b, false);
            }
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
