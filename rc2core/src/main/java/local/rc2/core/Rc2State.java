package local.rc2.core;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 某一瞬间的完整状态快照。需要「一次读全、不受后续更新影响」时用
 * {@link Rc2Controller#snapshot()} 取一份；只是每帧刷界面则直接读
 * {@link Rc2Controller} 上的实时 getter，可以避免分配。
 */
public final class Rc2State {

    public final boolean connected;
    public final String status;
    public final Sticks sticks;
    public final FlightMode flightMode;
    /** 挡位报文的 b2 原始值，未收到为 -1。 */
    public final int flightModeByte;
    /** 挡位来源，例如 "6/174 t2"。 */
    public final String flightModeSource;
    /** MCU 按键位掩码。 */
    public final int pressedMask;
    /** 所有按住的东西的标签（MCU 按键 + Android 按键）。 */
    public final Set<String> heldLabels;
    /** 累计推送包数。 */
    public final long packets;
    /** 累计摇杆采样数。 */
    public final long samples;
    /** 最近一帧按键报文，未收到为空数组。 */
    public final byte[] rawButtons;
    /** 与上一变化帧的差异位，例如 "b2.4 b1.0"。 */
    public final String lastDiff;
    /** 快照时间，{@code SystemClock.uptimeMillis()}。 */
    public final long at;

    Rc2State(boolean connected, String status, Sticks sticks, FlightMode flightMode,
             int flightModeByte, String flightModeSource, int pressedMask, Set<String> held,
             long packets, long samples, byte[] rawButtons, String lastDiff, long at) {
        this.connected = connected;
        this.status = status;
        this.sticks = sticks;
        this.flightMode = flightMode;
        this.flightModeByte = flightModeByte;
        this.flightModeSource = flightModeSource;
        this.pressedMask = pressedMask;
        this.heldLabels = Collections.unmodifiableSet(new LinkedHashSet<>(held));
        this.packets = packets;
        this.samples = samples;
        this.rawButtons = rawButtons.clone();
        this.lastDiff = lastDiff;
        this.at = at;
    }

    public boolean isPressed(RcButton button) {
        return button.isDown(pressedMask);
    }

    public boolean isHeld(String label) {
        return label != null && heldLabels.contains(label);
    }

    @Override
    public String toString() {
        return "Rc2State{connected=" + connected + ", mode=" + flightMode
                + ", pressed=0x" + Integer.toHexString(pressedMask)
                + ", held=" + heldLabels + ", sticks=" + sticks + "}";
    }
}
