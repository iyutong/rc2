package local.rc2.controls;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;

public final class GamepadTestActivity extends Activity {
    private final Handler handler = new Handler();
    private TextView values;
    private long motions;
    private long keys;
    private int source;
    private int device = -1;
    private final float[] axes = new float[8];
    private final LinkedHashSet<String> held = new LinkedHashSet<>();
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, 100);
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 18, 28, 18);
        root.setBackgroundColor(0xff182a43);
        Button back = new Button(this);
        back.setText("返回映射面板");
        back.setOnClickListener(v -> finish());
        root.addView(back);
        values = new TextView(this);
        values.setTextColor(0xffeaf5ff);
        values.setTextSize(20f);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(values);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
            int[] codes = new int[]{
                    MotionEvent.AXIS_X, MotionEvent.AXIS_Y,
                    MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
                    MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER,
                    MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y
            };
            for (int i = 0; i < 8; i++) {
                axes[i] = event.getAxisValue(codes[i]);
            }
            motions++;
            source = event.getSource();
            device = event.getDeviceId();
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_DPAD)) {
            String name = KeyEvent.keyCodeToString(event.getKeyCode());
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                held.add(name);
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                held.remove(name);
            }
            keys++;
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void render() {
        String state = ControlService.output ? "映射已启用" : "映射已暂停，请返回面板启用";
        values.setText(state + " · " + ControlService.localStatus
                + "\n\n本页显示 Android 实际派发的输入。数值 -1～1，扳机 0～1。\n\n"
                + String.format(Locale.US,
                        "左杆  X: %+.3f   Y: %+.3f\n右杆  X: %+.3f   Y: %+.3f\n扳机  LT: %.3f   RT: %.3f\n方向  X: %+.0f   Y: %+.0f\n",
                        axes[0], axes[1], axes[2], axes[3], axes[4], axes[5], axes[6], axes[7])
                + "\n按住的按键：" + (held.isEmpty() ? "无" : held.toString())
                + "\n轴事件：" + motions + "   按键事件：" + keys
                + "\n设备 ID：" + device + "   来源：0x" + Integer.toHexString(source)
                + "\n\n支持标准手柄的游戏可直接接收全局输入；具体应用仍需实测。");
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        held.clear();
        Arrays.fill(axes, 0f);
        super.onPause();
    }
}
