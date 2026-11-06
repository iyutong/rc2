package cn.iyutong.rc2core;

import android.view.KeyEvent;

/**
 * 常见游戏手柄 / 遥控器键码 → 中文短标签。只影响显示文案，不参与任何逻辑判断。
 */
public final class KeyLabels {

    private KeyLabels() {
    }

    /** 返回短标签，未收录时返回空串。 */
    public static String of(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A:
                return "A";
            case KeyEvent.KEYCODE_BUTTON_B:
                return "B";
            case KeyEvent.KEYCODE_BUTTON_X:
                return "X";
            case KeyEvent.KEYCODE_BUTTON_Y:
                return "Y";
            case KeyEvent.KEYCODE_DPAD_UP:
                return "上";
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return "下";
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return "左";
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return "右";
            case KeyEvent.KEYCODE_DPAD_CENTER:
                return "中";
            case KeyEvent.KEYCODE_CAMERA:
                return "拍照";
            case KeyEvent.KEYCODE_FOCUS:
                return "半按/对焦";
            case KeyEvent.KEYCODE_BUTTON_L1:
                return "L1";
            case KeyEvent.KEYCODE_BUTTON_R1:
                return "R1";
            case KeyEvent.KEYCODE_BUTTON_L2:
                return "L2";
            case KeyEvent.KEYCODE_BUTTON_R2:
                return "R2";
            case KeyEvent.KEYCODE_BUTTON_THUMBL:
                return "L3";
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
                return "R3";
            case KeyEvent.KEYCODE_BUTTON_START:
                return "START";
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                return "BACK";
            case KeyEvent.KEYCODE_BUTTON_MODE:
                return "GUIDE";
            case KeyEvent.KEYCODE_MEDIA_RECORD:
                return "录像";
            default:
                return "";
        }
    }
}
