package cn.iyutong.rc2core;

import android.view.KeyEvent;
import java.util.Locale;

/**
 * 快门键（拍照键）半按探针。
 *
 * <p>RC2 的 MCU 上报 {@code 6/174} 按键报文里只有「全按」位，半按不会让这条报文产生任何字节变化
 * （已实测）。半按在系统层只可能以 Android 按键事件的形式出现：
 * <ul>
 *   <li>半按 → {@link KeyEvent#KEYCODE_FOCUS}（80）</li>
 *   <li>全按 → {@link KeyEvent#KEYCODE_CAMERA}（27），部分机型仍是 FOCUS</li>
 * </ul>
 * 所以这里不碰协议，只做「按键事件 → 三段状态」的状态机，事件来源由宿主决定：
 * Activity 的 {@code dispatchKeyEvent}（只在本应用前台时有效）或
 * 无障碍服务的按键过滤（全局有效，需要在系统设置里手动开启）。
 *
 * <p>全进程共用一个实例 {@link #GLOBAL}，这样无障碍服务和界面天然共享同一份状态。
 * 所有读取方法都是零分配的 volatile 读，可以每帧调用。
 */
public final class ShutterProbe {

    /** 快门三段状态。 */
    public enum Stage {
        /** 没按下。 */
        IDLE,
        /** 半按：对焦。 */
        HALF,
        /** 全按：拍照 / 录像。 */
        FULL
    }

    /** 全进程共享的探针。 */
    public static final ShutterProbe GLOBAL = new ShutterProbe();

    /** 半按对焦，Android 标准键值。 */
    public static final int KEY_FOCUS = KeyEvent.KEYCODE_FOCUS;
    /** 全按快门，Android 标准键值。 */
    public static final int KEY_CAMERA = KeyEvent.KEYCODE_CAMERA;

    private volatile Stage stage = Stage.IDLE;
    private volatile int lastCode = -1;
    private volatile int lastAction = -1;
    private volatile int lastScanCode = -1;
    private volatile int lastDeviceId = -1;
    private volatile String lastSource = "—";
    private volatile long lastAt;
    private volatile int halfCount;
    private volatile int fullCount;
    /** 有没有出现过「先全按、后补半按」——说明半按事件被谁吞了。 */
    private volatile boolean fullWithoutHalf;
    /** 半按信号是否来自协议位（而非 Android 按键）。 */
    private volatile boolean viaProtocol;
    private volatile String lastTransition = "—";

    private ShutterProbe() {
    }

    /** 这个 keyCode 是否属于快门（半按或全按）。 */
    public static boolean isShutterKey(int keyCode) {
        return keyCode == KEY_FOCUS || keyCode == KEY_CAMERA;
    }

    /**
     * 喂一个 Android 按键事件。
     *
     * @param event  按键事件，可为 null
     * @param source 来源标记，例如 {@code "window"} / {@code "a11y"}，仅用于诊断显示
     * @return 状态是否发生了变化
     */
    public boolean feed(KeyEvent event, String source) {
        if (event == null) {
            return false;
        }
        return feed(event.getKeyCode(), event.getAction(), event.getScanCode(),
                event.getDeviceId(), source);
    }

    /** 喂一个裸按键（没有 KeyEvent 对象时用，例如从 getevent 解析出来）。 */
    public boolean feed(int keyCode, int action, int scanCode, int deviceId, String source) {
        lastCode = keyCode;
        lastAction = action;
        lastScanCode = scanCode;
        lastDeviceId = deviceId;
        lastSource = source == null ? "—" : source;
        lastAt = android.os.SystemClock.uptimeMillis();

        if (!isShutterKey(keyCode)) {
            return false;
        }
        boolean down = action == KeyEvent.ACTION_DOWN;
        Stage before = stage;
        Stage next = before;

        if (keyCode == KEY_FOCUS) {
            if (down) {
                if (before == Stage.FULL) {
                    // 全按还没松就来了半按，方向不对，忽略
                    return false;
                }
                next = Stage.HALF;
                halfCount++;
            } else if (before == Stage.HALF) {
                next = Stage.IDLE;
            }
        } else { // KEY_CAMERA
            if (down) {
                if (before != Stage.HALF) {
                    fullWithoutHalf = true;
                }
                next = Stage.FULL;
                fullCount++;
            } else if (before == Stage.FULL) {
                next = Stage.IDLE;
            }
        }

        if (next == before) {
            return false;
        }
        stage = next;
        lastTransition = String.format(Locale.US, "%s → %s（%s %s · scan %d · dev %d · %s）",
                before, next, keyName(keyCode), down ? "DOWN" : "UP",
                scanCode, deviceId, lastSource);
        return true;
    }

    /** 当前状态。 */
    public Stage stage() {
        return stage;
    }

    /**
     * 从协议位喂一个半按状态。
     *
     * <p>Android 层根本没有半按事件的机型（RC2 实测如此），只能靠
     * {@link ProtocolSniffer} 学习出「哪一位跟着半按翻转」，再把它接到这里。
     *
     * @param down   该位是否为「按下」电平
     * @param source 来源描述，例如 {@code "协议 6/81 b3.5"}
     * @return 状态是否变化
     */
    public boolean feedHalf(boolean down, String source) {
        Stage before = stage;
        Stage next = before;
        if (down) {
            if (before == Stage.IDLE) {
                next = Stage.HALF;
                halfCount++;
                viaProtocol = true;
            }
        } else if (before == Stage.HALF) {
            next = Stage.IDLE;
        }
        if (next == before) {
            return false;
        }
        lastSource = source == null ? "proto" : source;
        lastAt = android.os.SystemClock.uptimeMillis();
        stage = next;
        lastTransition = String.format(Locale.US, "%s → %s（%s %s）",
                before, next, lastSource, down ? "DOWN" : "UP");
        return true;
    }

    /**
     * 从 MCU 按键位喂一个全按状态（{@code 6/174} 的拍照位）。
     *
     * @return 状态是否变化
     */
    public boolean feedFull(boolean down, String source) {
        Stage before = stage;
        Stage next = before;
        if (down) {
            if (before != Stage.HALF) {
                fullWithoutHalf = true;
            }
            next = Stage.FULL;
            fullCount++;
        } else if (before == Stage.FULL) {
            next = Stage.IDLE;
        }
        if (next == before) {
            return false;
        }
        lastSource = source == null ? "mcu" : source;
        lastAt = android.os.SystemClock.uptimeMillis();
        stage = next;
        lastTransition = String.format(Locale.US, "%s → %s（%s %s）",
                before, next, lastSource, down ? "DOWN" : "UP");
        return true;
    }

    /** 半按是不是从协议位来的（而不是 Android 按键）。 */
    public boolean viaProtocol() {
        return viaProtocol;
    }

    public boolean isHalfPressed() {
        return stage == Stage.HALF;
    }

    public boolean isFullPressed() {
        return stage == Stage.FULL;
    }

    public int halfCount() {
        return halfCount;
    }

    public int fullCount() {
        return fullCount;
    }

    /** 是否出现过「没半按就直接全按」。是则说明半按根本没有到达应用层。 */
    public boolean fullWithoutHalf() {
        return fullWithoutHalf;
    }

    public int lastKeyCode() {
        return lastCode;
    }

    public int lastScanCode() {
        return lastScanCode;
    }

    public int lastDeviceId() {
        return lastDeviceId;
    }

    public String lastSource() {
        return lastSource;
    }

    public long lastAt() {
        return lastAt;
    }

    /** 最近一次状态跃迁的可读描述。 */
    public String lastTransition() {
        return lastTransition;
    }

    /** 一行摘要，直接上屏。 */
    public String summary() {
        switch (stage) {
            case HALF:
                return "半按 · 对焦";
            case FULL:
                return "全按 · 拍摄";
            default:
                return "空闲";
        }
    }

    /** 诊断行：来源 / 键值 / 计数。 */
    public String detail() {
        return String.format(Locale.US, "%s · %s · 半按×%d 全按×%d",
                lastSource, keyName(lastCode), halfCount, fullCount);
    }

    public void reset() {
        stage = Stage.IDLE;
        lastCode = -1;
        lastAction = -1;
        lastScanCode = -1;
        lastDeviceId = -1;
        lastSource = "—";
        lastAt = 0;
        halfCount = 0;
        fullCount = 0;
        fullWithoutHalf = false;
        viaProtocol = false;
        lastTransition = "—";
    }

    /** 键值的可读名，只覆盖快门相关和几个常见键。 */
    public static String keyName(int code) {
        switch (code) {
            case KEY_FOCUS:
                return "FOCUS(半按)";
            case KEY_CAMERA:
                return "CAMERA(全按)";
            case KeyEvent.KEYCODE_VOLUME_UP:
                return "VOL+";
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                return "VOL-";
            case KeyEvent.KEYCODE_BACK:
                return "BACK";
            case KeyEvent.KEYCODE_ENTER:
                return "ENTER";
            default:
                return code < 0 ? "—" : "KEY " + code;
        }
    }
}
