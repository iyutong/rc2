package cn.iyutong.rc2core;

/**
 * 飞行模式挡位。
 *
 * <p>挡位在 6/174 报文的 b2 低 3 位上报。编号与挡位的对应关系是实测得到的
 * （原始映射 C/S 颠倒，已按真机结果对调）：
 * <pre>
 *   0、4 → S      1、5 → N      2、6 → C
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

    /** 由 b2 原始字节解析挡位（内部取低 3 位）。 */
    public static FlightMode fromByte(int b2) {
        return fromCode(b2 & 0x07);
    }

    /** 由 0～7 的挡位编码解析。 */
    public static FlightMode fromCode(int code) {
        switch (code) {
            case 0:
            case 4:
                return S;
            case 1:
            case 5:
                return N;
            case 2:
            case 6:
                return C;
            default:
                return UNKNOWN;
        }
    }
}
