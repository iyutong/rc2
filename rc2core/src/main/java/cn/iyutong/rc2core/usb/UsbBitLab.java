package cn.iyutong.rc2core.usb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * USB 位学习器：在原始字节流里自动定位「随半按翻转的那一位」。
 *
 * <p>USB 端点给的是裸字节，没有任何字段说明。硬猜字段偏移不可靠，但有一个稳定的判据：
 * <b>半按会让某一位按时序从 0 变 1、保持一段时间、再变回 0</b>。于是把它做成一次受控实验：
 *
 * <pre>
 *   基线 3s（别碰快门）  →  按压 10s（半按两次，每次按住 4s 以上）  →  复核 8s（再半按一次）
 *         ↓                          ↓                                    ↓
 *   记录哪些位本来就在抖          筛「恰好 2 次上升沿 + 脉宽合理」        只留「恰好 1 次上升沿」
 * </pre>
 *
 * <p>基线期会翻转的位（心跳、计数器、电量、信号强度）在第一轮就被排除；
 * 按压两次是为了确认可重复；复核一次是为了排除「正好在按压窗口里翻了一次」的巧合。
 *
 * <p>按帧长分桶：不同长度的报文含义不同，同一位索引在不同帧长下必须分开统计。
 */
public final class UsbBitLab {

    public enum Phase {
        /** 没开始。 */
        IDLE,
        /** 记基线，此时不要碰快门。 */
        BASELINE,
        /** 正在等用户按压。 */
        PRESS,
        /** 已出候选，做最后一次确认。 */
        VERIFY,
        /** 锁定成功。 */
        LOCKED,
        /** 没找到。 */
        FAILED
    }

    private static final long BASELINE_MS = 3000;
    private static final long PRESS_MS = 12000;
    private static final long VERIFY_MS = 9000;
    /** 超过这个长度的报文不参与位分析（按键帧都很短，长包多半是图传/日志）。 */
    private static final int MAX_ANALYZE = 128;
    /** 脉宽合理区间：太短是毛刺，太长是别的常驻状态。 */
    private static final int MIN_PULSE_MS = 150;
    private static final int MAX_PULSE_MS = 9500;

    /** 单个位的变化统计。 */
    private static final class Bit {
        int value;
        int baseValue;
        int baselineRises;
        int rises;
        int verifyRises;
        long riseAt;
        long lastFallAt;
        int maxWidthMs;
        boolean candidate;

        int index;
    }

    /** 一个帧长对应的统计桶。 */
    private static final class Bucket {
        final int len;
        long frames;
        final List<Bit> bits = new ArrayList<>();

        Bucket(int len) {
            this.len = len;
        }

        Bit bit(int i) {
            while (bits.size() <= i) {
                Bit b = new Bit();
                b.index = bits.size();
                bits.add(b);
            }
            return bits.get(i);
        }
    }

    private final Map<Integer, Bucket> buckets = new LinkedHashMap<>();

    private volatile Phase phase = Phase.IDLE;
    private volatile long phaseAt;
    private volatile long phaseUntil;
    private volatile String hint = "点「开始学习」，然后按提示动作";
    private volatile long startedAt;

    private int lockedLen = -1;
    private int lockedBit = -1;
    private volatile String lockedLabel = "—";
    private volatile boolean locked;
    private volatile boolean halfDown;

    private final List<String[]> candidateRows = new ArrayList<>();

    // ───────────────────────── 控制 ─────────────────────────

    /** 开始一轮学习。会清空上一轮的所有统计。 */
    public synchronized void start() {
        buckets.clear();
        candidateRows.clear();
        locked = false;
        lockedLen = -1;
        lockedBit = -1;
        lockedLabel = "—";
        halfDown = false;
        startedAt = now();
        enter(Phase.BASELINE, BASELINE_MS,
                "基线 3 秒：现在别碰快门键");
    }

    public synchronized void cancel() {
        if (phase == Phase.LOCKED) {
            return;
        }
        phase = Phase.IDLE;
        hint = "已取消";
    }

    /**
     * 按时间推进相位。
     *
     * <p>{@link #feed} 里也会推进，但那只在有数据时发生。USB 断流时学习流程不能永远停在
     * 「基线期」，所以界面需要定时调这个方法来兜底——这样失败时给出的原因才是
     * 「一帧数据都没收到」，而不是无限转圈。
     */
    public synchronized void tick() {
        advance(now());
    }

    /** 解除锁定，回到未学习状态。 */
    public synchronized void unlock() {
        locked = false;
        lockedLen = -1;
        lockedBit = -1;
        lockedLabel = "—";
        halfDown = false;
        phase = Phase.IDLE;
        hint = "已解除锁定";
    }

    private void enter(Phase p, long ms, String text) {
        phase = p;
        phaseAt = now();
        phaseUntil = phaseAt + ms;
        hint = text;
    }

    private static long now() {
        return android.os.SystemClock.uptimeMillis();
    }

    // ───────────────────────── 数据入口 ─────────────────────────

    /**
     * 喂一帧。
     *
     * @param key  帧长（或原始包长），用于分桶
     * @param data 字节
     * @param len  有效长度
     */
    public synchronized void feed(int key, byte[] data, int len, long at) {
        if (data == null || len <= 0 || len > MAX_ANALYZE) {
            return;
        }
        Bucket b = buckets.get(key);
        if (b == null) {
            b = new Bucket(key);
            buckets.put(key, b);
        }
        b.frames++;

        int n = Math.min(len, data.length);
        int bitCount = n * 8;
        for (int i = 0; i < bitCount; i++) {
            int byteIdx = i >> 3;
            int bitIdx = i & 7;
            int v = (data[byteIdx] >> bitIdx) & 1;
            Bit bit = b.bit(i);
            if (bit.value == v) {
                continue;
            }
            boolean rise = v == 1;
            bit.value = v;
            if (rise) {
                bit.riseAt = at;
                switch (phase) {
                    case BASELINE:
                        bit.baselineRises++;
                        break;
                    case PRESS:
                        bit.rises++;
                        break;
                    case VERIFY:
                        bit.verifyRises++;
                        break;
                    default:
                        break;
                }
            } else if (bit.riseAt > 0) {
                int w = (int) Math.min(Integer.MAX_VALUE, at - bit.riseAt);
                if (w > bit.maxWidthMs) {
                    bit.maxWidthMs = w;
                }
                bit.lastFallAt = at;
            }
        }
        advance(at);
        applyLock(b, data, at);
    }

    /** 锁定后，每一帧都跟着刷新半按电平。 */
    private void applyLock(Bucket b, byte[] data, long at) {
        if (!locked || b.len != lockedLen || lockedBit < 0) {
            return;
        }
        int byteIdx = lockedBit >> 3;
        int bitIdx = lockedBit & 7;
        if (byteIdx >= data.length) {
            return;
        }
        boolean down = ((data[byteIdx] >> bitIdx) & 1) != 0;
        if (down == halfDown) {
            return;
        }
        halfDown = down;
        cn.iyutong.rc2core.ShutterProbe.GLOBAL.feedHalf(down, "USB " + lockedLabel);
    }

    // ───────────────────────── 阶段推进 ─────────────────────────

    private void advance(long at) {
        if (phase == Phase.IDLE || phase == Phase.LOCKED || phase == Phase.FAILED) {
            return;
        }
        if (at < phaseUntil) {
            return;
        }
        switch (phase) {
            case BASELINE:
                snapshotBaseline();
                enter(Phase.PRESS, PRESS_MS,
                        "现在半按快门：按住 4 秒以上 → 松开 → 停顿 1 秒 → 再按住 4 秒以上");
                break;
            case PRESS:
                collectCandidates();
                if (candidateRows.isEmpty()) {
                    phase = Phase.FAILED;
                    hint = describeFailure();
                } else {
                    enter(Phase.VERIFY, VERIFY_MS,
                            "再半按一次（约 4 秒）做复核，只保留这次恰好翻转 1 次的位");
                }
                break;
            case VERIFY:
                finalizeLock();
                break;
            default:
                break;
        }
    }

    /** 基线结束：把当前电平记为「未按下」，清空上升沿计数。 */
    private void snapshotBaseline() {
        for (Bucket b : buckets.values()) {
            for (Bit bit : b.bits) {
                bit.baseValue = bit.value;
                bit.rises = 0;
                bit.verifyRises = 0;
                bit.maxWidthMs = 0;
                bit.riseAt = 0;
            }
        }
    }

    private void collectCandidates() {
        candidateRows.clear();
        for (Bucket b : buckets.values()) {
            if (b.frames < 10) {
                continue;
            }
            for (Bit bit : b.bits) {
                bit.candidate = bit.rises == 2
                        && bit.baselineRises == 0
                        && bit.maxWidthMs >= MIN_PULSE_MS
                        && bit.maxWidthMs <= MAX_PULSE_MS;
                if (bit.candidate) {
                    candidateRows.add(new String[]{
                            label(b.len, bit.index),
                            String.valueOf(bit.maxWidthMs),
                            String.valueOf(b.frames)
                    });
                }
            }
        }
        if (candidateRows.isEmpty()) {
            // 再放宽一次：脉宽未知（按住到学习结束还没松）也算
            for (Bucket b : buckets.values()) {
                if (b.frames < 10) {
                    continue;
                }
                for (Bit bit : b.bits) {
                    bit.candidate = bit.rises == 2 && bit.baselineRises == 0;
                    if (bit.candidate) {
                        candidateRows.add(new String[]{
                                label(b.len, bit.index),
                                "未闭合",
                                String.valueOf(b.frames)
                        });
                    }
                }
            }
        }
    }

    private void finalizeLock() {
        int bestLen = -1;
        int bestBit = -1;
        long bestFrames = -1;
        int ties = 0;

        for (Bucket b : buckets.values()) {
            for (Bit bit : b.bits) {
                if (!bit.candidate) {
                    continue;
                }
                if (bit.verifyRises != 1) {
                    continue;
                }
                if (b.frames > bestFrames) {
                    bestFrames = b.frames;
                    bestLen = b.len;
                    bestBit = bit.index;
                    ties = 1;
                } else if (b.frames == bestFrames) {
                    ties++;
                }
            }
        }

        if (bestBit < 0) {
            phase = Phase.FAILED;
            hint = "复核没通过：候选位在这 9 秒里没有恰好翻转 1 次。可重学一次，按住时间再长些";
            return;
        }

        lockedLen = bestLen;
        lockedBit = bestBit;
        lockedLabel = label(bestLen, bestBit);
        locked = true;
        phase = Phase.LOCKED;
        hint = ties > 1
                ? "已锁定 " + lockedLabel + "（有 " + ties + " 个候选并列，用的是帧数最多的那个）"
                : "已锁定 " + lockedLabel + "，半按现在会实时驱动快门状态";
        halfDown = false;
    }

    private String describeFailure() {
        long maxFrames = 0;
        for (Bucket b : buckets.values()) {
            maxFrames = Math.max(maxFrames, b.frames);
        }
        if (maxFrames == 0) {
            return "学习期间一帧数据都没收到 —— USB 端点没通，先看上面的通道状态";
        }
        return "没有任何位出现「2 次上升沿」。三种可能："
                + "①按住时间太短（不足 150ms 的脉冲会被当毛刺丢掉）；"
                + "②半按被 MCU 直接转成对焦指令，根本不在这条总线上；"
                + "③按键状态走在另一个端点，需要换端点再试";
    }

    // ───────────────────────── 报告 ─────────────────────────

    private static String label(int len, int bitIndex) {
        return String.format(Locale.US, "len%d b%d.%d", len, bitIndex >> 3, bitIndex & 7);
    }

    /** 给界面用的多行报告。 */
    public synchronized String report() {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("学习阶段：").append(phaseName()).append('\n');
        if (phase == Phase.BASELINE || phase == Phase.PRESS || phase == Phase.VERIFY) {
            long left = Math.max(0, phaseUntil - now());
            sb.append("剩余 ").append(String.format(Locale.US, "%.1fs", left / 1000.0)).append('\n');
        }
        sb.append("提示：").append(hint).append('\n');

        if (locked) {
            sb.append("已锁定：").append(lockedLabel)
                    .append(halfDown ? "  · 当前半按" : "  · 当前松开").append('\n');
        }

        sb.append("\n帧长分布：\n");
        if (buckets.isEmpty()) {
            sb.append("  （无数据）\n");
        } else {
            int shown = 0;
            for (Bucket b : buckets.values()) {
                if (shown++ >= 8) {
                    sb.append("  …\n");
                    break;
                }
                sb.append(String.format(Locale.US, "  %dB × %,d%s%n",
                        b.len, b.frames, b.len == lockedLen ? "  ← 已锁定" : ""));
            }
        }

        if (!candidateRows.isEmpty()) {
            sb.append("\n候选位（按压期恰好翻转 2 次）：\n");
            for (String[] row : candidateRows) {
                sb.append(String.format(Locale.US, "  %-14s 脉宽 %sms  帧 %,d%n",
                        row[0], row[1], Long.parseLong(row[2])));
            }
        }
        return sb.toString();
    }

    private String phaseName() {
        switch (phase) {
            case BASELINE:
                return "基线（别碰快门）";
            case PRESS:
                return "按压中";
            case VERIFY:
                return "复核中";
            case LOCKED:
                return "已锁定";
            case FAILED:
                return "未找到";
            default:
                return "未开始";
        }
    }

    public boolean locked() {
        return locked;
    }

    public boolean halfDown() {
        return halfDown;
    }

    public String lockedLabel() {
        return lockedLabel;
    }

    public Phase phase() {
        return phase;
    }

    public String hint() {
        return hint;
    }

    public long startedAt() {
        return startedAt;
    }

    // ───────────────────────── 持久化 ─────────────────────────

    /** 形如 {@code "17:13"}，表示帧长 17、位 13。用于存 SharedPreferences。 */
    public String signature() {
        return locked ? lockedLen + ":" + lockedBit : "";
    }

    /** 从签名恢复锁定（下次启动直接生效）。 */
    public synchronized boolean restore(String sig) {
        if (sig == null || sig.isEmpty()) {
            return false;
        }
        int c = sig.indexOf(':');
        if (c <= 0) {
            return false;
        }
        try {
            lockedLen = Integer.parseInt(sig.substring(0, c));
            lockedBit = Integer.parseInt(sig.substring(c + 1));
        } catch (NumberFormatException e) {
            return false;
        }
        locked = true;
        lockedLabel = label(lockedLen, lockedBit);
        phase = Phase.LOCKED;
        hint = "已从上次学习结果恢复：" + lockedLabel;
        return true;
    }
}
