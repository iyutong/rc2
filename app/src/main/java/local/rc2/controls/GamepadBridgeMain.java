package local.rc2.controls;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Process;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

public final class GamepadBridgeMain {
    private static final Object lock = new Object();
    private static BufferedWriter hid;
    private static volatile long last;
    private static volatile boolean active;
    private static Object inputManager;
    private static Method inject;
    private static int deviceId = -1;
    private static int priorButtons;
    private static float[] priorAxes;
    private static long lastAxisSend;
    private static final long[] keyDownAt = new long[16];
    private static final int[] XBOX_BITS = new int[]{
            12, 13, 14, 15, 8, 9, 5, 4, 10, 6, 7
    };
    private static final int[] KEY_CODES = new int[]{
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_MODE,
            KeyEvent.KEYCODE_BUTTON_THUMBL,
            KeyEvent.KEYCODE_BUTTON_THUMBR
    };
    private static final int[] DESC = new int[]{
            0x05, 0x01, 0x09, 0x05, 0xa1, 0x01,
            0x15, 0x00, 0x26, 0xff, 0x7f, 0x16, 0x01, 0x80,
            0x75, 0x10, 0x95, 0x04, 0x09, 0x30, 0x09, 0x31, 0x09, 0x33, 0x09, 0x34, 0x81, 0x02,
            0x15, 0x00, 0x26, 0xff, 0x00, 0x75, 0x08, 0x95, 0x02, 0x09, 0x32, 0x09, 0x35, 0x81, 0x02,
            0x15, 0x00, 0x25, 0x07, 0x35, 0x00, 0x46, 0x3b, 0x01, 0x65, 0x14, 0x09, 0x39, 0x95, 0x01, 0x75, 0x08, 0x81, 0x42, 0x65, 0x00,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x10, 0x15, 0x00, 0x25, 0x01, 0x95, 0x10, 0x75, 0x01, 0x81, 0x02,
            0xc0
    };

    public static void main(String[] args) throws Exception {
        int allowedUid = Integer.parseInt(args[0]);
        LocalServerSocket server = new LocalServerSocket("rc2.xbox.global.v1");
        new FileOutputStream("/data/local/tmp/rc2-xbox.pid")
                .write(Integer.toString(Process.myPid()).getBytes("UTF-8"));
        ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "/system/bin/hid", "-");
        pb.redirectErrorStream(true);
        java.lang.Process proc = pb.start();
        new Thread(() -> {
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                String line;
                while ((line = r.readLine()) != null) {
                    System.err.println(line);
                }
            } catch (Exception ignored) {
            }
        }).start();
        hid = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), "UTF-8"));
        JSONArray desc = new JSONArray();
        for (int b : DESC) {
            desc.put(b);
        }
        write(new JSONObject()
                .put("id", 1)
                .put("command", "register")
                .put("name", "RC2 Virtual Xbox Layout")
                .put("vid", 0x045e)
                .put("pid", 0x028e)
                .put("bus", "usb")
                .put("descriptor", desc));
        Class<?> imClass = Class.forName("android.hardware.input.InputManager");
        inputManager = imClass.getMethod("getInstance").invoke(null);
        inject = imClass.getMethod("injectInputEvent", InputEvent.class, int.class);
        for (int i = 0; i < 40 && deviceId < 0; i++) {
            for (int id : InputDevice.getDeviceIds()) {
                InputDevice dev = InputDevice.getDevice(id);
                if (dev != null && dev.getName().equals("RC2 Virtual Xbox Layout")) {
                    deviceId = id;
                }
            }
            if (deviceId < 0) {
                SystemClock.sleep(100);
            }
        }
        if (deviceId < 0) {
            throw new IOException("Virtual controller registration timed out");
        }
        System.out.println("Android dispatcher ready: device=" + deviceId);
        report(new JSONObject());
        new Thread(() -> {
            while (true) {
                try {
                    if (active && SystemClock.uptimeMillis() - last > 250) {
                        report(new JSONObject());
                        active = false;
                    }
                    Thread.sleep(25);
                } catch (Exception e) {
                    System.err.println(e);
                }
            }
        }, "neutral-watchdog").start();
        while (true) {
            LocalSocket client = server.accept();
            if (client.getPeerCredentials().getUid() != allowedUid
                    && client.getPeerCredentials().getUid() != 2000) {
                client.close();
                continue;
            }
            try {
                BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), "UTF-8"));
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.length() > 2048) {
                        break;
                    }
                    JSONObject j = new JSONObject(line);
                    last = SystemClock.uptimeMillis();
                    active = j.optBoolean("active", false);
                    report(j);
                }
            } catch (Exception e) {
                System.err.println(e);
            } finally {
                report(new JSONObject());
                active = false;
                client.close();
            }
        }
    }

    private static void write(JSONObject msg) throws Exception {
        synchronized (lock) {
            hid.write(msg.toString() + "\n");
            hid.flush();
        }
    }

    private static float val(JSONObject j, String k) {
        double v = j.optDouble(k, 0.0);
        return Double.isNaN(v) ? 0f : (float) Math.max(-1.0, Math.min(1.0, v));
    }

    private static synchronized void report(JSONObject j) throws Exception {
        boolean on = j.optBoolean("active", false);
        int buttons = on ? j.optInt("buttons", 0) : 0;
        int hat = on ? j.optInt("hat", 8) : 8;
        float hatX = (hat == 1 || hat == 2 || hat == 3) ? 1f : ((hat == 5 || hat == 6 || hat == 7) ? -1f : 0f);
        float hatY = (hat == 3 || hat == 4 || hat == 5) ? 1f : ((hat == 7 || hat == 0 || hat == 1) ? -1f : 0f);
        float[] axes = new float[]{
                on ? val(j, "lx") : 0f,
                on ? val(j, "ly") : 0f,
                on ? val(j, "rx") : 0f,
                on ? val(j, "ry") : 0f,
                on ? Math.max(0f, val(j, "lt")) : 0f,
                on ? Math.max(0f, val(j, "rt")) : 0f,
                hatX,
                hatY
        };
        long now = SystemClock.uptimeMillis();
        for (int i = 0; i < XBOX_BITS.length; i++) {
            int mask = 1 << XBOX_BITS[i];
            if ((buttons & mask) == (priorButtons & mask)) {
                continue;
            }
            boolean down = (buttons & mask) != 0;
            if (down) {
                keyDownAt[i] = now;
            }
            KeyEvent ev = new KeyEvent(
                    keyDownAt[i],
                    now,
                    down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP,
                    KEY_CODES[i],
                    0,
                    0,
                    deviceId,
                    0,
                    0,
                    InputDevice.SOURCE_GAMEPAD);
            if (!Boolean.TRUE.equals(inject.invoke(inputManager, ev, 0))) {
                throw new IOException("Key dispatch rejected");
            }
        }
        priorButtons = buttons;
        if (priorAxes == null || !Arrays.equals(priorAxes, axes) || (on && now - lastAxisSend >= 100)) {
            MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
            pp.id = 0;
            pp.toolType = MotionEvent.TOOL_TYPE_UNKNOWN;
            MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
            int[] codes = new int[]{
                    MotionEvent.AXIS_X,
                    MotionEvent.AXIS_Y,
                    MotionEvent.AXIS_Z,
                    MotionEvent.AXIS_RZ,
                    MotionEvent.AXIS_LTRIGGER,
                    MotionEvent.AXIS_RTRIGGER,
                    MotionEvent.AXIS_HAT_X,
                    MotionEvent.AXIS_HAT_Y
            };
            for (int i = 0; i < 8; i++) {
                pc.setAxisValue(codes[i], axes[i]);
            }
            pc.setAxisValue(MotionEvent.AXIS_BRAKE, axes[4]);
            pc.setAxisValue(MotionEvent.AXIS_GAS, axes[5]);
            MotionEvent ev = MotionEvent.obtain(
                    now,
                    now,
                    MotionEvent.ACTION_MOVE,
                    1,
                    new MotionEvent.PointerProperties[]{pp},
                    new MotionEvent.PointerCoords[]{pc},
                    0,
                    0,
                    1f,
                    1f,
                    deviceId,
                    0,
                    InputDevice.SOURCE_JOYSTICK,
                    0);
            try {
                if (!Boolean.TRUE.equals(inject.invoke(inputManager, ev, 0))) {
                    throw new IOException("Axis dispatch rejected");
                }
                priorAxes = axes;
                lastAxisSend = now;
            } finally {
                ev.recycle();
            }
        }
    }
}
