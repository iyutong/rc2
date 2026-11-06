package cn.iyutong.rc2core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import cn.iyutong.rc2core.internal.ButtonCodec;
import org.junit.Test;

/**
 * 纯逻辑校验：按键位、挡位映射、摇杆归一化。
 * 这几条是迁移时最容易改错的地方，用测试钉住。
 */
public class CodecTest {

    @Test
    public void buttonMaskMapsToEnumBits() {
        // b0 bit6 → 拍照 0x40；b1 bit0 → 录像 0x10000；b2 bit2 → C1 0x400
        byte[] payload = new byte[]{0x40, 0x01, 0x04, 0, 0, 0};
        int mask = ButtonCodec.mask(payload);
        assertEquals(0x40 | 0x10000 | 0x400, mask);
        assertTrue(RcButton.SHUTTER.isDown(mask));
        assertTrue(RcButton.RECORD.isDown(mask));
        assertTrue(RcButton.C1.isDown(mask));
        assertFalse(RcButton.C2.isDown(mask));
    }

    @Test
    public void buttonMaskHandlesShortPayload() {
        assertEquals(0, ButtonCodec.mask(null));
        assertEquals(0, ButtonCodec.mask(new byte[]{0x40}));
    }

    @Test
    public void diffReportsChangedBits() {
        byte[] a = new byte[]{0, 0, 0};
        byte[] b = new byte[]{0x04, 0, 0};
        assertEquals("b0.2", ButtonCodec.diff(a, b));
        assertEquals("无变化", ButtonCodec.diff(a, a.clone()));
        assertEquals("首帧", ButtonCodec.diff(null, a));
    }

    @Test
    public void flightModeMatchesFieldObservation() {
        // 实测：0/4 → S，1/5 → N，2/6 → C（4/5/6 是同一挡位叠加了 C1 位）
        assertEquals(FlightMode.S, FlightMode.fromByte(0));
        assertEquals(FlightMode.N, FlightMode.fromByte(1));
        assertEquals(FlightMode.C, FlightMode.fromByte(2));
        assertEquals(FlightMode.S, FlightMode.fromByte(4));
        assertEquals(FlightMode.N, FlightMode.fromByte(5));
        assertEquals(FlightMode.C, FlightMode.fromByte(6));
        assertEquals(FlightMode.UNKNOWN, FlightMode.fromByte(3));
        // 高位是别的标志，不应影响挡位
        assertEquals(FlightMode.C, FlightMode.fromByte(0xfa));
    }

    /**
     * 回归：C1/C2 与挡位共用 b2 —— bit2-3 是 C1/C2，bit0-1 才是挡位。
     * 按 C1/C2 只能改变原始字节，不能改变挡位码，否则会刷出「S → S」的假变更日志。
     */
    @Test
    public void c1c2BitsDoNotChangeFlightMode() {
        for (int mode = 0; mode <= 3; mode++) {
            assertEquals(mode, FlightMode.code(mode));
            // bit2 = C1，bit3 = C2，单独或同时按下都不该影响挡位码
            assertEquals(mode, FlightMode.code(mode | 0x04));
            assertEquals(mode, FlightMode.code(mode | 0x08));
            assertEquals(mode, FlightMode.code(mode | 0x0C));
        }

        // 三个挡位在 C1/C2 的四种组合下都必须稳定
        int[] base = {0, 1, 2};
        FlightMode[] expect = {FlightMode.S, FlightMode.N, FlightMode.C};
        for (int i = 0; i < base.length; i++) {
            for (int c = 0; c <= 3; c++) {
                assertEquals(expect[i], FlightMode.fromByte(base[i] | (c << 2)));
            }
        }

        // 原始字节确实从 0x00 变成 0x04，挡位码却都是 0 —— 过去就是拿原始字节当
        // 「变更」判据，才误报为一次挡位切换
        assertEquals(0, FlightMode.code(0x00));
        assertEquals(0, FlightMode.code(0x04));
    }

    @Test
    public void sticksNormalizeAroundCenter() {
        Sticks centered = Sticks.center();
        assertEquals(0f, centered.leftX(), 1e-6);
        assertEquals(0f, centered.rightY(), 1e-6);

        Sticks s = Sticks.of(new int[]{1024, 1024, 1684, 364, 1024, 1024});
        assertEquals(-1f, s.leftX(), 1e-6);   // ch3 364 → 左摇杆最左
        assertEquals(-1f, s.leftY(), 1e-6);   // ch2 1684 → 屏幕坐标最上（Y 取反）
        assertEquals(128, s.byteValue(4));    // 拨轮中位 → 0x80
        assertEquals("RX 1024 RY 1024 LY 1684 LX 364 LT 1024 RT 1024", s.toString());
    }

    @Test
    public void sticksClampBeyondSpan() {
        Sticks s = Sticks.of(new int[]{4000, 0, 0, 0, 0, 0});
        assertEquals(1f, s.rightX(), 1e-6);
        assertEquals(1f, s.rightY(), 1e-6);
    }
}
