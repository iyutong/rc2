package cn.iyutong.rc2core;

import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import cn.iyutong.rc2core.internal.RawProbe;

/**
 * 协议层「半按猎手」。
 *
 * <p>背景：RC2 的快门半按不产生 Android 按键事件（无障碍按键过滤实测为空），
 * 也不会改变 {@code 6/174} 按键位图的任何字节。那么在应用层能碰到的唯一通道就是
 * DJI {@code protocol} 服务本身——它除了业务用的 6/174，还跑着一整套命令。
 * 本类干三件事：
 *
 * <ol>
 *   <li><b>广撒网订阅</b>：用几种过滤形态（含 cmd 传 -1 的通配写法）各订一次，
 *       看哪一形态能收到 6/174 以外的帧；</li>
 *   <li><b>逐条深扫</b>：对 cmdSet 6 的 cmd 0..255 轮流发查询，把每条命令的返回值缓存下来，
 *       这是订阅不通时的兜底；</li>
 *   <li><b>一键学习</b>：先记基线，再让用户「半按并保持」，统计每个 (命令, 字节, 位) 的上升沿。
 *       只有「基线期零翻转 + 恰好 N 次上升沿 + 脉冲宽度合理」的位才会被采纳，
 *       采纳后实时喂给 {@link ShutterProbe}，全 App 就能拿到半按了。</li>
 * </ol>
 *
 * <p>线程：{@link RawProbe} 的回调线程写，UI 线程读，全部走 {@code synchronized (this)}。
 */
public final class ProtocolSniffer implements RawProbe.Listener {

    /** 学习流程所处阶段。 */
    public enum Phase {
        /** 没开始。 */
        OFF,
        /** 记基线：这段时间内翻过的位直接判为噪声。 */
        BASELINE,
        /** 第一次按压：要求按 2 次，找到「翻转 2 次」的位。 */
        PRESS,
        /** 复核：要求按 1 次，只有「翻转 1 次」的位才算通过。 */
        VERIFY,
        /** 找到并存下了。 */
        DONE,
        /** 没找到。 */
        FAILED
    }

    /** 一条命令的缓存与统计。 */
    public static final class Entry {
        public final int cmdSet;
        public final int cmd;
        byte[] last;
        byte[] base;
        int frames;
        /** 服务主动推送的帧数（不含查询应答）——一次性命令只能靠它抓。 */
        int pushFrames;
        /** 基线期结束时的推送帧数快照。 */
        int baseFrames;
        /** 按压窗口内新增的推送帧数。 */
        int pressFrames;
        int[] header;
        long firstAt;
        long lastAt;
        int diffBytes;
        int[] changeCount = new int[0];
        Bit[] bits = new Bit[0];

        Entry(int cmdSet, int cmd) {
            this.cmdSet = cmdSet;
            this.cmd = cmd;
        }

        public int length() {
            return last == null ? 0 : last.length;
        }

        public int frames() {
            return frames;
        }

        public int diffBytes() {
            return diffBytes;
        }

        public String key() {
            return cmdSet + "/" + cmd;
        }

        public String hex() {
            if (last == null || last.length == 0) {
                return "—";
            }
            StringBuilder sb = new StringBuilder(last.length * 3);
            for (byte b : last) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(String.format(Locale.US, "%02X", b & 0xff));
            }
            return sb.toString();
        }

        /** 15 个 int 的报文头，用来看发送方 / 接收方 / 命令号。 */
        public String headerHex() {
            if (header == null) {
                return "—";
            }
            StringBuilder sb = new StringBuilder();
            for (int v : header) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(String.format(Locale.US, "%X", v));
            }
            return sb.toString();
        }

        /** 服务主动推上来的帧数（不含查询应答）。 */
        public int pushFrames() {
            return pushFrames;
        }
    }

    /** 单个位的统计。 */
    public static final class Bit {
        public final int index;
        int value;
        int baseValue;
        int rises;
        int baseRises;
        long riseAt;
        int lastWidth = -1;
        int maxWidth;

        Bit(int index) {
            this.index = index;
        }

        public int byteIndex() {
            return index >> 3;
        }

        public int bitIndex() {
            return index & 7;
        }

        public String label() {
            return "b" + byteIndex() + "." + bitIndex();
        }
    }

    /** 学习出来的候选位。 */
    public static final class Candidate {
        final int cmdSet;
        final int cmd;
        final int index;
        int rises;

        Candidate(int cmdSet, int cmd, int index, int rises) {
            this.cmdSet = cmdSet;
            this.cmd = cmd;
            this.index = index;
            this.rises = rises;
        }

        public String key() {
            return cmdSet + "/" + cmd;
        }

        public String label() {
            return key() + " b" + (index >> 3) + "." + (index & 7);
        }

        /** 持久化用的紧凑签名：{@code 6/81/3/5}。 */
        public String signature() {
            return cmdSet + "/" + cmd + "/" + (index >> 3) + "/" + (index & 7);
        }
    }

    /** 订阅形态：先试已知形态做对照，再试几种通配写法。 */
    private static final int[][] VARIANTS = {
            {4, 2, 0, 6, 6, 174},
            {4, 2, 0, 6, 6, -1},
            {4, 2, 0, 6, 6, 0xFFFF},
            {4, 2, 0, 0, 255, -1},
            // 相机(1) / 中心(5) 的通配：试试能不能收到「发往飞机」的那一侧
            {4, 2, 0, 1, 1, -1},
            {4, 2, 0, 5, 5, -1},
    };

    /** 深扫表：先扫满 RC（device 6），再扫相机（device 1，DEVICE$CAMERA.TYPE=1）。 */
    private static final int[][] SWEEP = buildSweep();
    private static final int SWEEP_GAP_MS = 8;

    private static int[][] buildSweep() {
        // 设备号即命令集号：DEVICE$RC=6、CAMERA=1、FLYC(飞控)=3、OSD=14
        int[][] plan = {{6, 256}, {1, 64}, {3, 32}, {14, 32}};
        int total = 0;
        for (int[] p : plan) {
            total += p[1];
        }
        int[][] table = new int[total][2];
        int i = 0;
        for (int[] p : plan) {
            for (int c = 0; c < p[1]; c++) {
                table[i][0] = p[0];
                table[i][1] = c;
                i++;
            }
        }
        return table;
    }
    /** 分析窗口最多保存 48 字节，够覆盖所有控制类报文。 */
    private static final int MAX_KEEP = 48;
    private static final int MAX_ENTRIES = 160;
    /** 连发这么多条查询仍没有回音，说明服务端跟不上，本轮降速。 */
    private static final int MAX_SILENT = 40;

    private static final long BASELINE_MS = 3000;
    private static final long PRESS_MS = 18000;
    private static final long VERIFY_MS = 14000;
    /** 按压期的脉冲宽度上限：超过它多半是别的状态位（要求按住 5 秒，留足余量）。 */
    private static final int MAX_PULSE_MS = 9000;

    private final RawProbe probe;
    private final HandlerThread sweeper = new HandlerThread("rc2-sweep");
    private final Handler sweep;
    private final Map<Integer, Entry> entries = new LinkedHashMap<>();

    private volatile boolean running;
    private Rc2Log logger = Rc2Log.ANDROID;
    private int sweepCmd;
    /** 上一次收到帧之后又连发了多少条查询，用来发现「服务端没响应」。 */
    private int silent;
    private int variantRetry;

    private String variantReport = "探测中";
    private String lastNote = "—";
    private String bestVariant = "—";
    private int bestVariantFrames;
    private int liveFrames;

    private Phase phase = Phase.OFF;
    private long phaseUntil;
    private long learnStart;
    private String hintText = "点「一键学习」开始";
    private final List<Candidate> candidates = new ArrayList<>();
    /** 只在按压窗口出现过的命令。 */
    private final List<Entry> events = new ArrayList<>();
    private Candidate adopted;

    public ProtocolSniffer() {
        probe = new RawProbe(this);
        sweeper.start();
        sweep = new Handler(sweeper.getLooper());
    }

    // ───────────────────────── 生命周期 ─────────────────────────

    /** 幂等启动：连服务 + 依次探测订阅形态。 */
    public void start() {
        synchronized (this) {
            if (running) {
                return;
            }
            running = true;
        }
        probe.start();
        sweep.postDelayed(this::tryVariant, 300);
    }

    /** 幂等停止：退订并停线程。 */
    public void stop() {
        synchronized (this) {
            if (!running) {
                return;
            }
            running = false;
            phase = Phase.OFF;
        }
        probe.stop();
    }

    public boolean isRunning() {
        return running;
    }

    // ───────────────────────── 订阅形态探测 ─────────────────────────

    private void tryVariant() {
        tryVariant(0);
    }

    private void tryVariant(final int index) {
        if (!running) {
            return;
        }
        if (index >= VARIANTS.length) {
            synchronized (this) {
                variantReport = "最佳形态 " + bestVariant + " · " + bestVariantFrames + " 帧";
            }
            return;
        }
        if (!probe.serviceReady()) {
            // 服务是异步连的，前几百毫秒可能还没拿到 IBinder
            if (variantRetry < 20) {
                variantRetry++;
                sweep.postDelayed(() -> tryVariant(index), 500);
            } else {
                synchronized (this) {
                    variantReport = "protocol 服务不可用";
                }
            }
            return;
        }
        variantRetry = 0;
        final int[] filter = VARIANTS[index];
        final String name = filter[3] + "/" + filter[5];
        synchronized (this) {
            liveFrames = 0;
        }
        final Binder binder = probe.subscribe(filter);
        sweep.postDelayed(() -> {
            if (binder != null) {
                probe.unsubscribe(binder);
            }
            int got;
            synchronized (this) {
                got = liveFrames;
                if (got > bestVariantFrames) {
                    bestVariantFrames = got;
                    bestVariant = name;
                }
            }
            if (got > 0) {
                log("SUB", "形态 " + name + " 收到 " + got + " 帧");
            }
            tryVariant(index + 1);
        }, 1300);
    }

    // ───────────────────────── 深扫 ─────────────────────────

    /** 逐条查询一圈 cmdSet 6，只在学习期间跑，避免平时刷链路。 */
    private void sweepLoop() {
        if (!running) {
            return;
        }
        boolean active;
        synchronized (this) {
            active = phase == Phase.BASELINE || phase == Phase.PRESS || phase == Phase.VERIFY;
        }
        if (!active) {
            sweep.postDelayed(this::sweepLoop, 250);
            return;
        }
        int silentNow;
        synchronized (this) {
            silentNow = silent;
        }
        if (silentNow < MAX_SILENT) {
            int set;
            int cmd;
            synchronized (this) {
                int[] pair = SWEEP[sweepCmd];
                set = pair[0];
                cmd = pair[1];
                sweepCmd = (sweepCmd + 1) % SWEEP.length;
                silent++;
            }
            probe.query(set, cmd);
        }
        long gap = silentNow < MAX_SILENT ? SWEEP_GAP_MS : 120;
        sweep.postDelayed(this::sweepLoop, gap);
    }

    // ───────────────────────── 学习流程 ─────────────────────────

    /** 开始学习：3 秒基线 → 按 2 次 → 复核 1 次。 */
    public void startLearn() {
        synchronized (this) {
            if (phase == Phase.PRESS || phase == Phase.VERIFY) {
                return;
            }
            candidates.clear();
            for (Entry e : entries.values()) {
                for (Bit b : e.bits) {
                    b.rises = 0;
                    b.baseRises = 0;
                    b.riseAt = 0;
                    b.lastWidth = -1;
                    b.maxWidth = 0;
                    b.baseValue = b.value;
                }
            }
            phase = Phase.BASELINE;
            learnStart = SystemClock.uptimeMillis();
            phaseUntil = learnStart + BASELINE_MS;
            hintText = "记基线中：请不要碰快门";
        }
        sweep.post(this::sweepLoop);
    }

    /** 放弃学习。 */
    public void cancelLearn() {
        synchronized (this) {
            phase = Phase.OFF;
            candidates.clear();
            hintText = "已停止";
        }
    }

    /** 由界面每 250ms 调一次，推进阶段。 */
    public void tick() {
        long now = SystemClock.uptimeMillis();
        synchronized (this) {
            if (phase == Phase.BASELINE && now >= phaseUntil) {
                for (Entry e : entries.values()) {
                    e.baseFrames = e.pushFrames;
                    for (Bit b : e.bits) {
                        b.baseValue = b.value;
                        b.baseRises = 0;
                        b.rises = 0;
                        b.riseAt = 0;
                        b.maxWidth = 0;
                    }
                }
                phase = Phase.PRESS;
                phaseUntil = now + PRESS_MS;
                hintText = "半按快门并保持约 5 秒，松开停顿一下；一共来 2 次";
            } else if (phase == Phase.PRESS && now >= phaseUntil) {
                candidates.clear();
                events.clear();
                int noisy = 0;
                int near = 0;
                for (Entry e : entries.values()) {
                    e.pressFrames = e.pushFrames - e.baseFrames;
                    // 基线期一声不响、按压时才冒出来的，是「指令」而不是「状态」
                    if (e.baseFrames == 0 && e.pressFrames > 0) {
                        events.add(e);
                    }
                    for (Bit b : e.bits) {
                        if (b.rises == 2 && b.maxWidth > 0 && b.maxWidth <= MAX_PULSE_MS) {
                            candidates.add(new Candidate(e.cmdSet, e.cmd, b.index, b.rises));
                        } else if (b.rises > 0) {
                            if (b.baseRises > 0) {
                                noisy++;
                            } else {
                                near++;
                            }
                        }
                    }
                }
                if (candidates.isEmpty() && !events.isEmpty()) {
                    phase = Phase.FAILED;
                    hintText = "发现 " + events.size() + " 条「只在按压时出现的命令」，见下方事件列表";
                } else if (candidates.isEmpty()) {
                    phase = Phase.FAILED;
                    hintText = near == 0
                            ? "没有任何位跟着半按变化 —— 半按在 MCU 里就被吃掉了，协议层也看不到"
                            : "有 " + near + " 个位变化但次数/宽度对不上，建议重试一次（按得干脆些）";
                } else {
                    for (Candidate c : candidates) {
                        c.rises = 0;
                    }
                    phase = Phase.VERIFY;
                    phaseUntil = now + VERIFY_MS;
                    hintText = "找到 " + candidates.size() + " 个候选，复核：再半按 1 次并保持约 5 秒";
                }
            } else if (phase == Phase.VERIFY && now >= phaseUntil) {
                Candidate win = null;
                for (Candidate c : candidates) {
                    if (c.rises == 1) {
                        win = c;
                        break;
                    }
                }
                if (win == null) {
                    phase = Phase.FAILED;
                    hintText = "复核没通过：候选位不是半按（可能是别的状态位）";
                } else {
                    adopted = win;
                    phase = Phase.DONE;
                    hintText = "已锁定 " + win.label() + " 作为半按信号，已开始实时生效";
                }
            }
        }
    }

    // ───────────────────────── 结果读取 ─────────────────────────

    public Phase phase() {
        synchronized (this) {
            return phase;
        }
    }

    public String hint() {
        long now = SystemClock.uptimeMillis();
        synchronized (this) {
            if (phase == Phase.BASELINE || phase == Phase.PRESS || phase == Phase.VERIFY) {
                long left = Math.max(0, phaseUntil - now);
                return hintText + String.format(Locale.US, "（%d.%ds）", left / 1000, (left / 100) % 10);
            }
            return hintText;
        }
    }

    public String variantReport() {
        synchronized (this) {
            return variantReport;
        }
    }

    public List<Entry> entries() {
        List<Entry> out;
        synchronized (this) {
            out = new ArrayList<>(entries.values());
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                if (a.diffBytes != b.diffBytes) {
                    return b.diffBytes - a.diffBytes;
                }
                return b.frames - a.frames;
            }
        });
        return out;
    }

    /** 当前锁定的半按位，没有则为 null。 */
    public Candidate adopted() {
        synchronized (this) {
            return adopted;
        }
    }

    /** 恢复上次锁定的位（从持久化里读）。 */
    public boolean restore(String signature) {
        if (signature == null) {
            return false;
        }
        String[] p = signature.split("/");
        if (p.length != 4) {
            return false;
        }
        try {
            int cmdSet = Integer.parseInt(p[0]);
            int cmd = Integer.parseInt(p[1]);
            int by = Integer.parseInt(p[2]);
            int bi = Integer.parseInt(p[3]);
            synchronized (this) {
                adopted = new Candidate(cmdSet, cmd, (by << 3) | bi, 0);
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public void clearAdopted() {
        synchronized (this) {
            adopted = null;
            if (phase == Phase.DONE) {
                phase = Phase.OFF;
                hintText = "已解除锁定";
            }
        }
    }

    /** 给界面用的一整页文本。 */
    public String report() {
        StringBuilder sb = new StringBuilder();
        long now = SystemClock.uptimeMillis();
        synchronized (this) {
            sb.append("订阅形态：").append(variantReport).append('\n');
            sb.append("最近一条：").append(lastNote).append('\n');
            sb.append("冷场查询：").append(silent).append(" · 命令数：").append(entries.size()).append('\n');
            sb.append("半按锁定：").append(adopted == null ? "未锁定" : adopted.label()).append("\n\n");

            int shown = 0;
            for (Entry e : sortedLocked()) {
                if (shown >= 18) {
                    break;
                }
                if (e.frames == 0) {
                    continue;
                }
                long age = e.lastAt == 0 ? 0 : now - e.lastAt;
                sb.append(String.format(Locale.US, "%s  %dB  %d帧  %dms前",
                        e.key(), e.length(), e.frames, age));
                if (e.diffBytes > 0) {
                    sb.append("  变").append(e.diffBytes).append("B");
                }
                sb.append('\n');
                if (e.diffBytes > 0 || shown < 4) {
                    sb.append("    ").append(shorten(e.hex(), 90)).append('\n');
                }
                shown++;
            }
            if (shown == 0) {
                sb.append("还没收到任何命令数据\n");
            }

            if (!events.isEmpty()) {
                sb.append("\n只在按压时出现的命令（疑似上行指令）：\n");
                int n = 0;
                for (Entry e : events) {
                    if (n++ >= 10) {
                        break;
                    }
                    sb.append(String.format(Locale.US, "  %s  %dB  推送%d帧  %s%n",
                            e.key(), e.length(), e.pressFrames, e.headerHex()));
                }
            }

            sb.append("\n候选位：");
            if (candidates.isEmpty()) {
                sb.append("无");
            } else {
                for (Candidate c : candidates) {
                    sb.append(c.label()).append(' ');
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private List<Entry> sortedLocked() {
        List<Entry> out = new ArrayList<>(entries.values());
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                if (a.diffBytes != b.diffBytes) {
                    return b.diffBytes - a.diffBytes;
                }
                return b.frames - a.frames;
            }
        });
        return out;
    }

    private static String shorten(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // ───────────────────────── RawProbe 回调 ─────────────────────────

    @Override
    public void onFrame(int cmdSet, int cmd, int type, byte[] payload,
                        int[] header, boolean fromQuery) {
        if (payload == null || payload.length == 0) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        synchronized (this) {
            liveFrames++;
            silent = 0;
            int key = key(cmdSet, cmd);
            Entry e = entries.get(key);
            if (e == null) {
                if (entries.size() >= MAX_ENTRIES) {
                    return;
                }
                e = new Entry(cmdSet, cmd);
                e.header = header == null ? null : header.clone();
                entries.put(key, e);
            }
            int len = Math.min(payload.length, MAX_KEEP);
            if (e.last == null || e.last.length != len) {
                e.last = new byte[len];
                e.base = new byte[len];
                e.changeCount = new int[len];
                e.bits = new Bit[len * 8];
                for (int i = 0; i < e.bits.length; i++) {
                    e.bits[i] = new Bit(i);
                }
                System.arraycopy(payload, 0, e.last, 0, len);
                System.arraycopy(payload, 0, e.base, 0, len);
                for (Bit b : e.bits) {
                    b.value = bit(e.last, b.index);
                    b.baseValue = b.value;
                }
                e.firstAt = now;
                e.lastAt = now;
                e.frames = 1;
                return;
            }

            e.frames++;
            if (!fromQuery) {
                e.pushFrames++;
            }
            e.lastAt = now;
            int diff = 0;
            for (int i = 0; i < len; i++) {
                byte v = payload[i];
                if (v != e.last[i]) {
                    e.changeCount[i]++;
                    for (int k = 0; k < 8; k++) {
                        int idx = (i << 3) | k;
                        int nowBit = (v >> k) & 1;
                        Bit b = e.bits[idx];
                        if (b.value != nowBit) {
                            b.value = nowBit;
                            if (phase == Phase.BASELINE) {
                                if (nowBit == 1) {
                                    b.baseRises++;
                                }
                            } else if (phase == Phase.PRESS || phase == Phase.VERIFY) {
                                if (nowBit == 1) {
                                    b.rises++;
                                    b.riseAt = now;
                                    Candidate c = find(cmdSet, cmd, idx);
                                    if (c != null) {
                                        c.rises++;
                                    }
                                } else if (b.riseAt > 0) {
                                    int w = (int) (now - b.riseAt);
                                    b.lastWidth = w;
                                    if (w > b.maxWidth) {
                                        b.maxWidth = w;
                                    }
                                }
                            }
                        }
                    }
                    e.last[i] = v;
                }
                if (e.base[i] != v) {
                    diff++;
                }
            }
            e.diffBytes = diff;
            applyAdopted(cmdSet, cmd, e);
        }
    }

    /** 锁定的位一旦翻转，立刻喂给全局快门探针。 */
    private void applyAdopted(int cmdSet, int cmd, Entry e) {
        Candidate c = adopted;
        if (c == null || c.cmdSet != cmdSet || c.cmd != cmd) {
            return;
        }
        int by = c.index >> 3;
        if (by >= e.last.length) {
            return;
        }
        boolean high = ((e.last[by] >> (c.index & 7)) & 1) == 1;
        ShutterProbe.GLOBAL.feedHalf(high, "协议 " + c.label());
    }

    private Candidate find(int cmdSet, int cmd, int index) {
        for (Candidate c : candidates) {
            if (c.cmdSet == cmdSet && c.cmd == cmd && c.index == index) {
                return c;
            }
        }
        return null;
    }

    @Override
    public void onNote(String tag, String msg) {
        synchronized (this) {
            lastNote = tag + " · " + msg;
        }
        logger.log('I', tag, msg);
    }

    private void log(String tag, String msg) {
        onNote(tag, msg);
    }

    /** 让宿主的日志系统也能看到嗅探过程。 */
    public void setLogger(Rc2Log l) {
        if (l != null) {
            logger = l;
        }
    }

    private static int bit(byte[] data, int index) {
        int by = index >> 3;
        if (by >= data.length) {
            return 0;
        }
        return (data[by] >> (index & 7)) & 1;
    }

    private static int key(int cmdSet, int cmd) {
        return (cmdSet << 16) | (cmd & 0xffff);
    }
}
