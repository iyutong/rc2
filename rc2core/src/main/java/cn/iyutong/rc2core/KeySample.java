package cn.iyutong.rc2core;

import android.view.KeyEvent;

import androidx.annotation.NonNull;

/**
 * 一条 Android 按键事件（走系统输入层，不是 MCU 报文）。
 *
 * <p>需要宿主在 Activity/Fragment 里把事件转进来：
 * <pre>
 * public boolean dispatchKeyEvent(KeyEvent e) {
 *     rc2.dispatchKeyEvent(e);
 *     return super.dispatchKeyEvent(e);
 * }
 * </pre>
 */
public final class KeySample {

    public final int keyCode;
    public final int scanCode;
    public final int action;
    /** 设备名，可能是 null。 */
    public final String device;
    /** 键名的原始字符串，例如 KEYCODE_CAMERA。 */
    public final String keyName;
    /** 短标签，例如「拍照」；没有约定时等于 {@link #keyName}。 */
    public final String label;
    public final long at;

    KeySample(KeyEvent e, long at) {
        this.keyCode = e.getKeyCode();
        this.scanCode = e.getScanCode();
        this.action = e.getAction();
        this.device = e.getDevice() == null ? null : e.getDevice().getName();
        this.keyName = KeyEvent.keyCodeToString(keyCode);
        this.label = KeyLabels.of(keyCode);
        this.at = at;
    }

    public boolean isDown() {
        return action == KeyEvent.ACTION_DOWN;
    }

    /** 短标签，无约定时退化成 KEYCODE_xxx。 */
    public String shortLabel() {
        return label.isEmpty() ? keyName : label;
    }

    @NonNull
    @Override
    public String toString() {
        return (isDown() ? "按下 " : "松开 ") + shortLabel() + " (" + keyName
                + " · scan " + scanCode + " · " + device + ")";
    }
}
