package cn.iyutong.rc2core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;
import cn.iyutong.rc2core.usb.DjiFrame;

/**
 * 帧切分器的行为测试。
 *
 * <p>USB 端点给的是字节流，粘包/拆包/垃圾前缀都是常态。没有真机时，这里就是唯一的
 * 回归保障：只要能把这三种情形处理对，接上设备后才有资格谈「解出按键」。
 */
public class DjiFrameTest {

    private static final int HEADER = 10;

    /** 按 V1 格式造一帧：0x55 + len + (ver&lt;&lt;2|lenHi) + 头 + 负载 + CRC16。 */
    private static byte[] build(int payloadLen, int salt) {
        int total = HEADER + payloadLen + 2;
        byte[] f = new byte[total];
        f[0] = 0x55;
        f[1] = (byte) (total & 0xff);
        f[2] = (byte) (((total >> 8) & 0x03) | (1 << 2));
        for (int i = 3; i < HEADER + payloadLen; i++) {
            f[i] = (byte) ((i * 7 + salt) & 0xff);
        }
        int crc = DjiFrame.crc16(f, 0, total - 2);
        f[total - 2] = (byte) (crc & 0xff);
        f[total - 1] = (byte) ((crc >> 8) & 0xff);
        return f;
    }

    @Test
    public void splitsSingleFrame() {
        DjiFrame.Splitter sp = new DjiFrame.Splitter();
        byte[] f = build(17, 1);
        List<DjiFrame.Frame> out = sp.feed(f, f.length);
        assertEquals(1, out.size());
        assertTrue("完整帧应通过 CRC 校验", out.get(0).crcOk);
        assertEquals(f.length, out.get(0).length());
        assertEquals(17, out.get(0).payload().length);
    }

    @Test
    public void splitsThreeFramesInOneRead() {
        DjiFrame.Splitter sp = new DjiFrame.Splitter();
        byte[] a = build(17, 1);
        byte[] b = build(12, 2);
        byte[] c = build(17, 3);
        byte[] all = new byte[a.length + b.length + c.length];
        System.arraycopy(a, 0, all, 0, a.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        System.arraycopy(c, 0, all, a.length + b.length, c.length);

        List<DjiFrame.Frame> out = sp.feed(all, all.length);
        assertEquals("一次读到三帧必须全部切出来", 3, out.size());
        for (DjiFrame.Frame f : out) {
            assertTrue(f.crcOk);
        }
    }

    @Test
    public void reassemblesAcrossReads() {
        DjiFrame.Splitter sp = new DjiFrame.Splitter();
        byte[] f = build(17, 9);
        int cut = 6; // 故意从帧中间切开
        assertTrue(sp.feed(f, cut).isEmpty());
        List<DjiFrame.Frame> out = sp.feed(slice(f, cut, f.length), f.length - cut);
        assertEquals("半个帧跨两次读取必须拼回来", 1, out.size());
        assertTrue(out.get(0).crcOk);
    }

    @Test
    public void skipsGarbageBeforeFrame() {
        DjiFrame.Splitter sp = new DjiFrame.Splitter();
        byte[] junk = {0x11, 0x22, 0x33, 0x55, 0x00, (byte) 0xee};
        byte[] f = build(17, 4);
        byte[] all = new byte[junk.length + f.length];
        System.arraycopy(junk, 0, all, 0, junk.length);
        System.arraycopy(f, 0, all, junk.length, f.length);

        List<DjiFrame.Frame> out = sp.feed(all, all.length);
        assertEquals(1, out.size());
        assertTrue(out.get(0).crcOk);
    }

    @Test
    public void neverThrowsOnNoise() {
        DjiFrame.Splitter sp = new DjiFrame.Splitter();
        byte[] noise = new byte[512];
        for (int i = 0; i < noise.length; i++) {
            noise[i] = (byte) (i * 31);
        }
        List<DjiFrame.Frame> out = sp.feed(noise, noise.length);
        // 噪声里可能碰巧切出「宽松模式」的假帧，但绝不能抛异常、也不能把缓冲撑爆
        assertTrue(out.size() < 64);
    }

    @Test
    public void crcRejectsCorruptedFrame() {
        DjiFrame.Splitter sp = new DjiFrame.Splitter();
        byte[] f = build(17, 5);
        f[12] ^= 0x40; // 破坏负载，CRC 必然不符
        List<DjiFrame.Frame> out = sp.feed(f, f.length);
        // 单帧被篡改时不应被当作有效帧接受（切出也会标 crcOk=false）
        for (DjiFrame.Frame got : out) {
            assertFalse("被篡改的帧不能标记为 CRC 通过", got.crcOk);
        }
    }

    private static byte[] slice(byte[] src, int from, int to) {
        byte[] out = new byte[to - from];
        System.arraycopy(src, from, out, 0, out.length);
        return out;
    }
}
