package cn.iyutong.rc2core;

/**
 * 遥控器上的实体按键。{@link #mask} 是 USB 按键帧解码后的位掩码，
 * 与 {@link #values()} 的下标无关，用于批量比对。
 *
 * <p>位掩码取自实测报文：
 * <pre>
 *   mask = (b0 &amp; 0x70) | ((b2 &amp; 0x0C) &lt;&lt; 8) | ((b1 &amp; 0x01) &lt;&lt; 16)
 * </pre>
 */
public enum RcButton {

    C1("C1", 0x000400),
    C2("C2", 0x000800),
    /** 拍照（快门）。全按在按键帧里；半按要靠 USB 位学习定位。 */
    SHUTTER("拍照", 0x000040),
    RECORD("录像", 0x010000),
    /** 返航 / 暂停，占相邻两位。 */
    RTH("返航/暂停", 0x000030);

    private final String label;
    private final int mask;

    RcButton(String label, int mask) {
        this.label = label;
        this.mask = mask;
    }

    /** 中文短标签，和仪表盘上显示的文案一致。 */
    public String label() {
        return label;
    }

    public int mask() {
        return mask;
    }

    /** 该键在 mask 里是否处于按下状态。 */
    public boolean isDown(int mask) {
        return (mask & this.mask) != 0;
    }

    /** 按标签查按键，找不到返回 null。 */
    public static RcButton byLabel(String text) {
        if (text == null) {
            return null;
        }
        for (RcButton b : values()) {
            if (b.label.equals(text)) {
                return b;
            }
        }
        return null;
    }
}
