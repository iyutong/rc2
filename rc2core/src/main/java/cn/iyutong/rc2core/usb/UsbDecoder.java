package cn.iyutong.rc2core.usb;

/**
 * 把切好的帧翻译成「摇杆 / 按键 / 挡位」。
 *
 * <p>这里刻意不做花哨的字段猜测，只用一条经验：遥控器主机上报的按键负载是 <b>17 字节</b>、
 * 摇杆负载是 <b>12 字节</b>（6 通道 × int16 小端），这两点是之前用协议通道实测过的。
 * 帧头长度未知，所以从帧尾倒推：负载紧挨着末尾 2 字节 CRC。
 *
 * <p>倒推出来的头长必须落在合理区间（4–20 字节），否则说明这条帧不是这两类负载，
 * 直接记为「未知」，交给 {@link UsbBitLab} 用位学习去定位。
 */
public final class UsbDecoder {

    /** 按键负载长度（6/174 实测）。 */
    public static final int LEN_BUTTONS = 17;
    /** 摇杆负载长度（6/4 实测，6 通道 × 2 字节）。 */
    public static final int LEN_STICKS = 12;
    /** 经验头长，用于在多个解释里挑最像的那个。 */
    private static final int TYPICAL_HEADER = 10;

    public interface Sink {
        void onButtons(byte[] payload, int mask, String source);

        void onSticks(int[] channels, String source);

        void onMode(int modeByte, String source);
    }

    private final Sink sink;

    private long buttonFrames;
    private long stickFrames;
    private long unknownFrames;
    private String lastKind = "—";

    public UsbDecoder(Sink sink) {
        this.sink = sink;
    }

    /** 解码一帧。 */
    public void feed(DjiFrame.Frame f) {
        if (f == null || f.raw.length < 6) {
            return;
        }
        int len = f.raw.length - 2; // 去掉 CRC
        int kind = pick(len);
        String src = "USB帧" + f.raw.length + (f.crcOk ? "" : "?");

        if (kind == LEN_BUTTONS) {
            int off = len - LEN_BUTTONS;
            byte[] p = new byte[LEN_BUTTONS];
            System.arraycopy(f.raw, off, p, 0, LEN_BUTTONS);
            buttonFrames++;
            lastKind = src;
            int mask = cn.iyutong.rc2core.internal.ButtonCodec.mask(p);
            sink.onButtons(p, mask, src);
            sink.onMode(p[2] & 0xff, src);
        } else if (kind == LEN_STICKS) {
            int off = len - LEN_STICKS;
            int[] ch = new int[6];
            for (int i = 0; i < 6; i++) {
                ch[i] = (f.raw[off + i * 2] & 0xff) | ((f.raw[off + i * 2 + 1] & 0xff) << 8);
            }
            stickFrames++;
            lastKind = src;
            sink.onSticks(ch, src);
        } else {
            unknownFrames++;
        }
    }

    /** 在可用解释里挑最合理的一个，返回负载长度；都不合理返回 -1。 */
    private static int pick(int bodyLen) {
        int best = -1;
        int bestScore = Integer.MAX_VALUE;
        for (int cand : new int[]{LEN_BUTTONS, LEN_STICKS}) {
            int header = bodyLen - cand;
            if (header < 4 || header > 20) {
                continue;
            }
            int score = Math.abs(header - TYPICAL_HEADER);
            if (score < bestScore) {
                bestScore = score;
                best = cand;
            }
        }
        return best;
    }

    public long buttonFrames() {
        return buttonFrames;
    }

    public long stickFrames() {
        return stickFrames;
    }

    public long unknownFrames() {
        return unknownFrames;
    }

    public String lastKind() {
        return lastKind;
    }

    /** 一行识别率。 */
    public String stats() {
        long total = buttonFrames + stickFrames + unknownFrames;
        if (total == 0) {
            return "尚未解出任何负载";
        }
        return String.format(java.util.Locale.US,
                "按键帧 %,d · 摇杆帧 %,d · 未识别 %,d（识别率 %.0f%%）",
                buttonFrames, stickFrames, unknownFrames,
                (buttonFrames + stickFrames) * 100.0 / total);
    }
}
