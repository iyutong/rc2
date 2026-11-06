package cn.iyutong.rc2core;

/**
 * 飞行模式挡位。
 *
 * <p>挡位在 6/174 报文的 b2 <b>低 2 位</b>上报。注意 b2 的 bit2-3 是 C1/C2 两个按键位
 * （见 {@code ButtonCodec.mask}），所以取挡位时必须用 {@code b2 &amp; 0x03}；
 * 早前用低 3 位会把「按 C1」当成挡位变化，产生 S → S 这类假日志。
 *
 * <p>编号与挡位的对应关系是实测得到的（原始映射 C/S 颠倒，已按真机结果对调）：
 * <pre>
 *   0 → S      1 → N      2 → C      3 → 未知
 * </pre>
 */
public enum FlightMode {

    S("S"),
    N("N"),
    C("C"),
    /** 还没收到报文，或编码无法识别。 */
    UNKNOWN("?");

    private final String label;

    FlightMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * 从 b2 原始字节里取挡位码（0～3）。
     *
     * <p>只取 bit0-1：bit2-3 属于 C1/C2 按键，按这两个键会翻转 bit2/bit3，
     * 若一并纳入就会误判成挡位变更。
     */
    public static int code(int b2) {
        return b2 & 0x03;
    }

    /** 由 b2 原始字节解析挡位（内部取低 2 位）。 */
    public static FlightMode fromByte(int b2) {
        return fromCode(code(b2));
    }

    /**
     * 由挡位编码解析。
     *
     * <p>兼容旧调用方传入的 4/5/6 —— 那些值等于「挡位码 + 4（C1 位）」，
     * 按低 2 位折叠后仍是同一个挡位。
     */
    public static FlightMode fromCode(int code) {
        switch (code & 0x03) {
            case 0:
                return S;
            case 1:
                return N;
            case 2:
                return C;
            default:
                return UNKNOWN;
        }
    }
}
