package cn.iyutong.rc2core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import cn.iyutong.rc2core.internal.ButtonCodec;
import org.junit.Test;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 快照与编解码的边界行为。
 *
 * <p>{@link Rc2State} 刻意不碰 {@code SystemClock}，所以能在纯 JVM 单测里直接构造；
 * 这里钉住的是「快照必须和内部状态脱钩」——宿主拿到了可变集合/数组也不能反过来影响库内部。
 */
public class StateTest {

    private static Rc2State state(int mask, Set<String> held, byte[] raw) {
        return new Rc2State(true, "已连接", Sticks.center(), FlightMode.N, 0x01, 1, "6/174 t2",
                mask, held, 12, 34, raw, "首帧", 100);
    }

    @Test
    public void snapshotCopiesHeldLabels() {
        Set<String> held = new LinkedHashSet<>();
        held.add("C1");
        Rc2State s = state(0x40, held, new byte[]{0x40});

        held.add("C2");   // 外部继续改原集合
        assertTrue(s.isHeld("C1"));
        assertFalse(s.isHeld("C2"));
        assertEquals(1, s.heldLabels.size());
        // 不可变视图：想改会抛 UnsupportedOperationException
        boolean threw = false;
        try {
            s.heldLabels.add("X");
        } catch (UnsupportedOperationException e) {
            threw = true;
        }
        assertTrue(threw);
    }

    @Test
    public void snapshotCopiesRawBytes() {
        byte[] raw = new byte[]{0x40, 0x00, 0x00};
        Rc2State s = state(0x40, new LinkedHashSet<>(), raw);
        raw[0] = 0x00;
        assertEquals(0x40, s.rawButtons[0] & 0xff);
    }

    @Test
    public void snapshotToleratesNullButton() {
        Rc2State s = state(0x000400, new LinkedHashSet<>(), new byte[0]);
        assertTrue(s.isPressed(RcButton.C1));
        assertFalse(s.isPressed(null));   // 旧实现直接 NPE
        assertTrue(s.anyPressed());

        Rc2State idle = state(0, new LinkedHashSet<>(), new byte[0]);
        assertFalse(idle.anyPressed());
        assertFalse(idle.isHeld(null));
    }

    @Test
    public void hexHandlesNonPositiveMax() {
        byte[] b = new byte[]{0x01, 0x02, 0x03};
        assertEquals("01 02 …", ButtonCodec.hex(b, 2));
        assertEquals("", ButtonCodec.hex(b, 0));
        assertEquals("", ButtonCodec.hex(b, -3));   // 旧实现会返回 " …"
        assertEquals("", ButtonCodec.hex(null, 4));
        assertEquals("01 02 03", ButtonCodec.hex(b, 99));
    }

    @Test
    public void diffToleratesMissingCurrent() {
        assertEquals("首帧", ButtonCodec.diff(null, new byte[0]));
        assertEquals("当前帧缺失", ButtonCodec.diff(new byte[0], null));
    }
}
