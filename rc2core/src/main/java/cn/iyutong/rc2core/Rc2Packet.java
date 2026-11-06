package cn.iyutong.rc2core;

import java.util.Locale;

/**
 * 一条协议推送包（诊断用）。只在 {@link Rc2Listener#onPacket(Rc2Packet)} 与
 * {@link Rc2Controller#packets()} 里出现，正常业务逻辑不需要关心。
 */
public final class Rc2Packet {

    /** cmdSet，一般 6。 */
    public final int cmdSet;
    /** cmdId，按键报文是 174。 */
    public final int cmdId;
    /** 报文的类型字段，2 表示周期性上报。 */
    public final int type;
    /** 负载字节数。 */
    public final int size;
    /** 负载的十六进制形式，最多前 {@value #HEX_LIMIT} 字节。 */
    public final String hex;
    /** 到达时间，{@code SystemClock.uptimeMillis()}。 */
    public final long at;

    static final int HEX_LIMIT = 24;

    Rc2Packet(int cmdSet, int cmdId, int type, byte[] payload, long at) {
        this.cmdSet = cmdSet;
        this.cmdId = cmdId;
        this.type = type;
        this.size = payload == null ? 0 : payload.length;
        this.hex = toHex(payload, HEX_LIMIT);
        this.at = at;
    }

    /** "6/174"。 */
    public String cmd() {
        return cmdSet + "/" + cmdId;
    }

    /** 签名：cmd + 类型 + 长度相同即视为同一类报文。 */
    public String signature() {
        return cmdSet + "/" + cmdId + "/" + type + "/" + size;
    }

    static String toHex(byte[] b, int max) {
        if (b == null || b.length == 0) {
            return "";
        }
        int n = Math.min(b.length, max);
        StringBuilder sb = new StringBuilder(n * 3);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format(Locale.US, "%02X", b[i] & 0xff));
        }
        if (b.length > n) {
            sb.append(" …");
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return cmd() + " t" + type + " " + size + "B " + hex;
    }
}
