package cn.iyutong.rc2core.internal;

import java.util.Locale;

/**
 * 按键报文的解码与差异计算。内部实现，宿主不需要直接使用。
 */
public final class ButtonCodec {

    private ButtonCodec() {
    }

    /**
     * 把按键报文解成位掩码。
     *
     * <p>实测布局：b0 的高 3 位（C1/C2 之外的键）、b2 的 bit2-3（C1/C2）、
     * b1 的 bit0（录像）。
     *
     * @return 位掩码；报文不足 3 字节时返回 0
     */
    public static int mask(byte[] payload) {
        if (payload == null || payload.length < 3) {
            return 0;
        }
        return (payload[0] & 0x70)
                | ((payload[2] & 0x0c) << 8)
                | ((payload[1] & 0x01) << 16);
    }

    /**
     * 两帧之间的差异位，形如 {@code "b2.4 b1.0"}。
     *
     * @return {@code "首帧"} 表示前一帧不存在；{@code "无变化"} 表示完全相同
     */
    public static String diff(byte[] prior, byte[] current) {
        if (prior == null) {
            return "首帧";
        }
        if (current == null) {
            return "当前帧缺失";
        }
        StringBuilder sb = new StringBuilder();
        int n = Math.min(prior.length, current.length);
        for (int i = 0; i < n; i++) {
            int x = (prior[i] ^ current[i]) & 0xff;
            for (int bit = 0; bit < 8; bit++) {
                if ((x & (1 << bit)) != 0) {
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append('b').append(i).append('.').append(bit);
                }
            }
        }
        if (prior.length != current.length) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append("长度 ").append(prior.length).append('→').append(current.length);
        }
        return sb.length() == 0 ? "无变化" : sb.toString();
    }

    /** 十六进制串，最多 max 字节；max 不为正时返回空串（旧实现会算出负长度，白拼一个 " …"）。 */
    public static String hex(byte[] b, int max) {
        if (b == null || b.length == 0 || max <= 0) {
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
}
