package cn.iyutong.rc2core.usb;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * DJI 帧切分与 CRC 校验。
 *
 * <p>USB 端点上来的是<b>字节流</b>，不是报文。一次 {@code bulkTransfer} 可能拿到半帧、
 * 一帧半，或者十帧粘在一起。这里负责把它切成一条条独立帧。
 *
 * <h3>帧格式（V1，握手协议通用）</h3>
 * <pre>
 *   +0  0x55                       魔数
 *   +1  length[7:0]                整帧总长（含魔数与 CRC）
 *   +2  length[9:8] | version&lt;&lt;2   bit0-1 是长度高位，bit2-5 是版本
 *   +3  …                          头属性
 *   ..  头 CRC8                    位置随版本变化，这里不做强校验
 *   ..  负载
 *   ..  CRC16（小端，覆盖除自身外的整帧）
 * </pre>
 *
 * <h3>为什么要留「宽松模式」</h3>
 * 长度字段的高位到底在 {@code b2} 的低两位还是高两位，不同版本/不同固件有过差异。
 * 所以这里对每处 {@code 0x55} 依次尝试三种长度解释 + 两种边界（含/不含魔数），
 * 谁能让 CRC16 通过就用谁。若连续很多次都过不了，说明 CRC 口径不对（或这段数据根本不是
 * DJI 帧），自动降级为「只按长度切、不校验」，宁可按错也不空转——界面上会把 CRC 通过率
 * 显示出来，一眼能看出是哪种情况。
 */
public final class DjiFrame {

    /** 一帧。 */
    public static final class Frame {
        /** 整帧字节（含魔数与 CRC），只读。 */
        public final byte[] raw;
        public final boolean crcOk;
        public final int version;
        /** 头部长度，用于取负载。 */
        public final int headerLen;

        Frame(byte[] raw, boolean crcOk, int version, int headerLen) {
            this.raw = raw;
            this.crcOk = crcOk;
            this.version = version;
            this.headerLen = headerLen;
        }

        public int length() {
            return raw.length;
        }

        /** 负载：去掉头与末尾 2 字节 CRC。 */
        public byte[] payload() {
            int from = Math.min(headerLen, raw.length);
            int to = Math.max(from, raw.length - 2);
            if (to <= from) {
                return new byte[0];
            }
            byte[] out = new byte[to - from];
            System.arraycopy(raw, from, out, 0, out.length);
            return out;
        }

        public String hex(int max) {
            int n = Math.min(max, raw.length);
            StringBuilder sb = new StringBuilder(n * 3);
            for (int i = 0; i < n; i++) {
                if (i > 0) {
                    sb.append(' ');
                }
                sb.append(String.format(Locale.US, "%02X", raw[i] & 0xff));
            }
            if (raw.length > n) {
                sb.append(" …");
            }
            return sb.toString();
        }
    }

    /** 流式切分器。非线程安全，一个端点一个实例。 */
    public static final class Splitter {

        private static final int MAX_PENDING = 8192;
        /** 连续这么多次 CRC 失败后，降级为只按长度切。 */
        private static final int LENIENT_AFTER = 64;
        /** 前面已有垃圾时，剩余字节超过这个数才值得为一个可疑帧头等待。 */
        private static final int WAIT_MIN_BYTES = 64;

        private byte[] pending = new byte[0];
        private int crcFails;
        private long frames;
        private long crcPass;
        private long dropped;
        private long lenientSince;
        /** 上一次 {@link #tryFrame} 是否因为「数据还没收全」而失败。 */
        private boolean needMore;

        /** 喂一段字节，返回本次切出的完整帧（可能 0 条或多条）。 */
        public List<Frame> feed(byte[] data, int len) {
            List<Frame> out = new ArrayList<>();
            if (data == null || len <= 0) {
                return out;
            }
            append(data, len);

            int pos = 0;
            int n = pending.length;
            while (pos < n) {
                if ((pending[pos] & 0xff) != 0x55) {
                    pos++;
                    dropped++;
                    continue;
                }
                Frame f = tryFrame(pos, n);
                if (f == null) {
                    if (needMore && worthWaiting(pos, n)) {
                        // 帧还没收全，保留尾巴等下一段字节；不能当成坏数据丢掉
                        break;
                    }
                    // 噪声里也有 0x55：它声明的长度可能很大，绝不为此无限等待
                    pos++;
                    dropped++;
                    continue;
                }
                out.add(f);
                frames++;
                if (f.crcOk) {
                    crcPass++;
                }
                pos += f.raw.length;
            }
            if (pos > 0) {
                pending = slice(pending, pos, n);
            }
            if (pending.length > MAX_PENDING) {
                // 找不到帧头时不能无限攒内存，丢前半段
                pending = slice(pending, pending.length - MAX_PENDING / 2, pending.length);
            }
            return out;
        }

        /** 清空缓冲（例如切换端点时）。 */
        public void reset() {
            pending = new byte[0];
            crcFails = 0;
            lenientSince = 0;
        }

        public long frames() {
            return frames;
        }

        public long dropped() {
            return dropped;
        }

        /** CRC 通过率，0..1；从未切出帧时为 -1。 */
        public double crcRatio() {
            return frames == 0 ? -1 : (double) crcPass / frames;
        }

        public boolean lenient() {
            return crcFails >= LENIENT_AFTER;
        }

        private void append(byte[] data, int len) {
            byte[] next = new byte[pending.length + len];
            System.arraycopy(pending, 0, next, 0, pending.length);
            System.arraycopy(data, 0, next, pending.length, len);
            pending = next;
        }

        /**
         * 判定「这个还没收全的候选值得等」。
         *
         * <p>噪声里同样会出现 {@code 0x55}，它后面两个字节可能凑出一个很大的长度，
         * 如果无条件等待，整条流会被这一个假帧头堵死。所以只在两种情形下等：
         * <ul>
         *   <li>候选就在缓冲开头（{@code pos == 0}）——正常的跨包拆帧都长这样；</li>
         *   <li>后面已经攒了足够多的字节（≥{@value #WAIT_MIN_BYTES}）——多半真是个大包。</li>
         * </ul>
         * 其余情况一律当成假帧头跳过，宁可漏一帧也不能卡住。
         */
        private boolean worthWaiting(int pos, int end) {
            if (pos == 0) {
                return true;
            }
            return end - pos >= WAIT_MIN_BYTES;
        }

        /**
         * 在 {@code pos} 处尝试切一帧。
         *
         * <p>返回 null 时用 {@link #needMore} 区分两种情况：需要更多字节（保留缓冲）
         * 还是这里根本不是帧头（跳过 1 字节）。
         */
        private Frame tryFrame(int pos, int end) {
            needMore = false;
            if (end - pos < 8) {
                needMore = true;
                return null;
            }
            int b1 = pending[pos + 1] & 0xff;
            int b2 = pending[pos + 2] & 0xff;
            int version = (b2 >> 2) & 0x0f;

            int[] candidates = {
                    b1 | ((b2 & 0x03) << 8),          // 高位在 b2 低 2 位
                    b1 | ((b2 & 0xc0) >> 6 << 8),     // 高位在 b2 高 2 位
                    b1,                                // 没有高位
            };

            boolean lenient = lenient();

            for (int c : candidates) {
                if (c < 8 || c > 1500) {
                    continue;
                }
                // 长度字段可能含魔数也可能不含，两种都试
                for (int total : new int[]{c, c + 1}) {
                    if (total < 8) {
                        continue;
                    }
                    if (end - pos < total) {
                        needMore = true;
                        continue;
                    }
                    if (lenient) {
                        return new Frame(slice(pending, pos, pos + total), false, version,
                                guessHeader(version, total));
                    }
                    if (crc16(pending, pos, total - 2) == u16(pending, pos + total - 2)) {
                        return new Frame(slice(pending, pos, pos + total), true, version,
                                guessHeader(version, total));
                    }
                    crcFails++;
                }
            }
            return null;
        }

        /** 头部长度的经验值：V1 常见 10 字节，其余按版本粗估。 */
        private static int guessHeader(int version, int total) {
            if (version <= 1) {
                return Math.min(10, total - 2);
            }
            return Math.min(12, total - 2);
        }

        private static byte[] slice(byte[] src, int from, int to) {
            byte[] out = new byte[to - from];
            System.arraycopy(src, from, out, 0, out.length);
            return out;
        }

        private static int u16(byte[] b, int off) {
            return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
        }
    }

    // ───────────────────────── CRC ─────────────────────────

    /** DJI 头 CRC8：poly 0x77，初值 0x77。 */
    public static int crc8(byte[] data, int off, int len) {
        int crc = 0x77;
        for (int i = 0; i < len; i++) {
            crc ^= data[off + i] & 0xff;
            for (int b = 0; b < 8; b++) {
                crc = ((crc & 0x80) != 0) ? ((crc << 1) ^ 0x77) & 0xff : (crc << 1) & 0xff;
            }
        }
        return crc;
    }

    /** DJI 负载 CRC16：poly 0x8408（反向），初值 0x3692。 */
    public static int crc16(byte[] data, int off, int len) {
        int crc = 0x3692;
        for (int i = 0; i < len; i++) {
            crc ^= data[off + i] & 0xff;
            for (int b = 0; b < 8; b++) {
                crc = ((crc & 1) != 0) ? ((crc >> 1) ^ 0x8408) : (crc >> 1);
            }
        }
        return crc & 0xffff;
    }

    private DjiFrame() {
    }
}
