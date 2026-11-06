package cn.iyutong.rc2core;

import androidx.annotation.NonNull;

import java.util.Locale;

/**
 * 一帧摇杆/拨轮数据（6 个 16 位通道）。
 *
 * <p>通道顺序是遥控器 MCU 的原始顺序，实测映射为：
 * <pre>
 *   ch0 → 右摇杆 X      ch1 → 右摇杆 Y
 *   ch2 → 左摇杆 Y      ch3 → 左摇杆 X
 *   ch4 → 左拨轮        ch5 → 右拨轮
 * </pre>
 * 原始值以 1024 为中位。{@link #raw(int)} 返回原始值，命名方法（{@link #leftX()} 等）
 * 返回归一化到 -1～1 的值。Y 轴已按屏幕坐标取反（向下为正），
 * 可以直接喂给 {@code Canvas} 系的 {@code setPosition(x, y)}。
 *
 * <p>本类不可变，可以放心跨线程传递或长期持有。
 */
public final class Sticks {

    /** 通道数量。 */
    public static final int CHANNELS = 6;
    /** 中位值。 */
    public static final int CENTER = 1024;
    /** 从 1024 到满偏的差值，用于归一化。 */
    public static final int SPAN = 660;

    private static final Sticks CENTERED = new Sticks(
            new int[]{CENTER, CENTER, CENTER, CENTER, CENTER, CENTER});

    private final int[] raw;

    private Sticks(int[] raw) {
        this.raw = raw;
    }

    /** 从中位构造一帧全中位数据。 */
    public static Sticks center() {
        return CENTERED;
    }

    /** 从 MCU 的 6 个通道值构造，长度不足时补中位。 */
    public static Sticks of(int[] channels) {
        int[] v = new int[CHANNELS];
        for (int i = 0; i < CHANNELS; i++) {
            v[i] = channels != null && i < channels.length ? channels[i] : CENTER;
        }
        return new Sticks(v);
    }

    /** 原始通道值（未归一化）。 */
    public int raw(int channel) {
        return channel >= 0 && channel < CHANNELS ? raw[channel] : CENTER;
    }

    /** 指定通道的归一化值，-1～1。 */
    public float normalized(int channel) {
        return norm(raw(channel));
    }

    public float rightX() {
        return norm(raw[0]);
    }

    /** 右摇杆 Y，已按屏幕坐标取反（向下为正）。 */
    public float rightY() {
        return -norm(raw[1]);
    }

    /** 左摇杆 Y，已按屏幕坐标取反（向下为正）。 */
    public float leftY() {
        return -norm(raw[2]);
    }

    public float leftX() {
        return norm(raw[3]);
    }

    public float dialLeft() {
        return norm(raw[4]);
    }

    public float dialRight() {
        return norm(raw[5]);
    }

    /** 全部原始通道值的副本，下标即通道号。 */
    public int[] rawChannels() {
        return raw.clone();
    }

    /** 把原始值按 1024 中位、660 满偏归一化到 -1～1。 */
    public static float norm(int value) {
        float f = (value - CENTER) / (float) SPAN;
        return f < -1f ? -1f : (Math.min(f, 1f));
    }

    /** 原始值 → 0～255 的字节量，用于拨轮的十六进制显示。 */
    public int byteValue(int channel) {
        return Math.max(0, Math.min(255, Math.round((normalized(channel) + 1f) / 2f * 255f)));
    }

    @NonNull
    @Override
    public String toString() {
        return String.format(Locale.US, "RX %d RY %d LY %d LX %d LT %d RT %d",
                raw[0], raw[1], raw[2], raw[3], raw[4], raw[5]);
    }
}
