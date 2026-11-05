package local.rc2.controls;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

public class ControlService extends AccessibilityService {
    public static volatile ControlService instance;
    public static volatile int[] channels = new int[]{1024, 1024, 1024, 1024, 1024, 1024};
    public static volatile long sampleAt;
    public static volatile long samples;
    public static volatile String status = "等待开启本工具无障碍服务";
    public static volatile String localStatus = "未连接";
    public static volatile boolean output;
    public static volatile String learnLabel;
    public static volatile String learnTarget;
    public static volatile long learnUntil;
    public static volatile long keyRevision;
    public static final LinkedHashMap<String, JSONObject> observed = new LinkedHashMap<>();
    public static final HashSet<String> pressed = new HashSet<>();
    public static final ArrayList<JSONObject> history = new ArrayList<>();

    private McuReader reader;
    private Thread sender;
    private volatile boolean running;
    private final HandlerThread archiveThread = new HandlerThread("rc2-key-archive");
    private Handler archiveHandler;
    private final Runnable archive = () -> persistRecords();
    private final HashSet<String> captured = new HashSet<>();
    private int priorMcu = -1;
    private volatile long mcuButtonsAt;

    @Override
    public void onServiceConnected() {
        instance = this;
        running = true;
        archiveThread.start();
        archiveHandler = new Handler(archiveThread.getLooper());
        status = "本机 MCU 已连接";
        keyRevision++;
        if (!getSharedPreferences("controls", Context.MODE_PRIVATE).contains("rc2Defaults")) {
            getSharedPreferences("controls", Context.MODE_PRIVATE)
                    .edit()
                    .putString("buttons", "{\"mcu:C1\":\"A\",\"mcu:C2\":\"B\",\"mcu:拍照\":\"RB\",\"mcu:录像\":\"LB\",\"mcu:返航/暂停\":\"START\"}")
                    .putString("labels", "{\"mcu:C1\":\"C1\",\"mcu:C2\":\"C2\",\"mcu:拍照\":\"拍照\",\"mcu:录像\":\"录像\",\"mcu:返航/暂停\":\"返航/暂停\"}")
                    .putBoolean("rc2Defaults", true)
                    .apply();
        }
        synchronized (ControlService.class) {
            try {
                JSONArray arr = new JSONArray(getSharedPreferences("controls", Context.MODE_PRIVATE).getString("observed", "[]"));
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject j = arr.getJSONObject(i);
                    observed.put(j.getString("id"), j);
                }
            } catch (Exception ignored) {
            }
        }
        reader = new McuReader(new McuReader.Listener() {
            @Override
            public void values(int[] ch) {
                channels = ch;
                sampleAt = SystemClock.uptimeMillis();
                samples++;
            }

            @Override
            public void buttons(byte[] payload) {
                mcuButtons(payload);
            }

            @Override
            public void error(String msg) {
                status = msg;
            }
        });
        sender = new Thread(() -> sendLoop(), "rc2-gamepad-output");
        sender.start();
    }

    private void mcuButtons(byte[] b) {
        if (b.length < 5) {
            return;
        }
        mcuButtonsAt = SystemClock.uptimeMillis();
        int mask = (b[0] & 0x70) | ((b[2] & 0x0c) << 8) | ((b[1] & 0x01) << 16);
        int prev = priorMcu;
        priorMcu = mask;
        if (prev < 0) {
            return;
        }
        String[] names = new String[]{"C1", "C2", "拍照", "录像", "返航/暂停"};
        int[] bits = new int[]{0x400, 0x800, 0x40, 0x10000, 0x30};
        for (int i = 0; i < 5; i++) {
            if ((prev & bits[i]) != (mask & bits[i])) {
                mcuRecord(names[i], (mask & bits[i]) != 0);
            }
        }
    }

    private void mcuRecord(String name, boolean down) {
        synchronized (ControlService.class) {
            try {
                String id = "mcu:" + name;
                JSONObject j = new JSONObject()
                        .put("id", id)
                        .put("keyCode", -1)
                        .put("keyName", name)
                        .put("scanCode", 0)
                        .put("device", "RC2 MCU")
                        .put("action", down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP)
                        .put("repeat", 0)
                        .put("uptime", SystemClock.uptimeMillis());
                observed.put(id, j);
                history.add(j);
                if (history.size() > 500) {
                    history.remove(0);
                }
                if (down) {
                    pressed.add(id);
                } else {
                    pressed.remove(id);
                }
                if (down && learnLabel != null) {
                    JSONObject labels = new JSONObject(getSharedPreferences("controls", Context.MODE_PRIVATE).getString("labels", "{}"));
                    Iterator<String> it = labels.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        if (learnLabel.equals(labels.optString(k))) {
                            it.remove();
                        }
                    }
                    labels.put(id, learnLabel);
                    getSharedPreferences("controls", Context.MODE_PRIVATE).edit().putString("labels", labels.toString()).apply();
                    learnLabel = null;
                }
                if (down && learnTarget != null) {
                    JSONObject map = mapping();
                    map.put(id, learnTarget);
                    getSharedPreferences("controls", Context.MODE_PRIVATE).edit().putString("buttons", map.toString()).apply();
                    learnTarget = null;
                }
                keyRevision++;
                if (archiveHandler != null) {
                    archiveHandler.removeCallbacks(archive);
                    archiveHandler.postDelayed(archive, 150);
                }
            } catch (Exception e) {
                Log.e("RC2Uu", "MCU button", e);
            }
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
        output = false;
        clear();
    }

    @Override
    public void onDestroy() {
        output = false;
        running = false;
        if (archiveHandler != null) {
            archiveHandler.removeCallbacksAndMessages(null);
            archiveHandler.post(() -> {
                persistRecords();
                archiveThread.quitSafely();
            });
        }
        if (reader != null) {
            reader.stop();
        }
        clear();
        instance = null;
        super.onDestroy();
    }

    public static String identity(KeyEvent e) {
        return e.getScanCode() > 0 ? "scan:" + e.getScanCode() : "key:" + e.getKeyCode();
    }

    public static boolean safe(KeyEvent e) {
        return e.getKeyCode() != KeyEvent.KEYCODE_POWER
                && e.getKeyCode() != KeyEvent.KEYCODE_HOME
                && e.getKeyCode() != KeyEvent.KEYCODE_BACK
                && e.getKeyCode() != KeyEvent.KEYCODE_VOLUME_UP
                && e.getKeyCode() != KeyEvent.KEYCODE_VOLUME_DOWN;
    }

    public static synchronized void clear() {
        pressed.clear();
        keyRevision++;
    }

    public static synchronized void record(Context ctx, KeyEvent e) {
        if (e.getDevice() == null) {
            return;
        }
        String devName = e.getDevice().getName();
        if (!devName.contains("DJI") && !devName.equals("gpio-keys") && !devName.contains("qpnp")) {
            return;
        }
        String id = identity(e);
        try {
            JSONObject j = new JSONObject()
                    .put("id", id)
                    .put("keyCode", e.getKeyCode())
                    .put("keyName", KeyEvent.keyCodeToString(e.getKeyCode()))
                    .put("scanCode", e.getScanCode())
                    .put("device", e.getDevice() == null ? "unknown" : e.getDevice().getName())
                    .put("action", e.getAction())
                    .put("repeat", e.getRepeatCount())
                    .put("uptime", SystemClock.uptimeMillis());
            observed.put(id, j);
            history.add(j);
            if (history.size() > 500) {
                history.remove(0);
            }
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                pressed.add(id);
            } else if (e.getAction() == KeyEvent.ACTION_UP) {
                pressed.remove(id);
            }
            if (learnLabel != null && e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0 && safe(e)) {
                JSONObject labels = new JSONObject(ctx.getSharedPreferences("controls", Context.MODE_PRIVATE).getString("labels", "{}"));
                Iterator<String> it = labels.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    if (learnLabel.equals(labels.optString(k))) {
                        it.remove();
                    }
                }
                labels.put(id, learnLabel);
                ctx.getSharedPreferences("controls", Context.MODE_PRIVATE).edit().putString("labels", labels.toString()).apply();
                learnLabel = null;
            }
            if (learnTarget != null && e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0 && safe(e)) {
                JSONObject map = new JSONObject(ctx.getSharedPreferences("controls", Context.MODE_PRIVATE).getString("buttons", "{}"));
                map.put(id, learnTarget);
                ctx.getSharedPreferences("controls", Context.MODE_PRIVATE).edit().putString("buttons", map.toString()).apply();
                learnTarget = null;
            }
            keyRevision++;
            if (instance != null && instance.archiveHandler != null) {
                instance.archiveHandler.removeCallbacks(instance.archive);
                instance.archiveHandler.postDelayed(instance.archive, 150);
            }
        } catch (Exception ignored) {
        }
    }

    private void persistRecords() {
        try {
            JSONObject exp = export(this);
            getSharedPreferences("controls", Context.MODE_PRIVATE)
                    .edit()
                    .putString("observed", exp.getJSONArray("buttons").toString())
                    .apply();
            File dir = getExternalFilesDir(null);
            if (dir != null) {
                try (FileOutputStream out = new FileOutputStream(new File(dir, "latest-input.json"))) {
                    out.write(exp.toString(2).getBytes("UTF-8"));
                }
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        if (e.getScanCode() == 310 || e.getScanCode() == 311) {
            return output;
        }
        if (e.getDevice() == null) {
            return false;
        }
        String devName = e.getDevice().getName();
        if (!devName.contains("DJI") && !devName.equals("gpio-keys") && !devName.contains("qpnp")) {
            return false;
        }
        if (learnUntil > 0 && SystemClock.uptimeMillis() > learnUntil) {
            learnLabel = null;
            learnTarget = null;
            learnUntil = 0;
        }
        boolean learning = (learnTarget != null || learnLabel != null) && safe(e);
        String id = identity(e);
        record(this, e);
        boolean consume = safe(e) && (learning || (output && mapping().has(id)) || captured.contains(id));
        if (consume && e.getAction() == KeyEvent.ACTION_DOWN) {
            captured.add(id);
        }
        if (e.getAction() == KeyEvent.ACTION_UP) {
            captured.remove(id);
        }
        return consume;
    }

    public JSONObject mapping() {
        try {
            return new JSONObject(getSharedPreferences("controls", Context.MODE_PRIVATE).getString("buttons", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static synchronized JSONObject export(Context ctx) throws Exception {
        return new JSONObject()
                .put("device", Build.DEVICE)
                .put("timestamp", System.currentTimeMillis())
                .put("channels", new JSONArray(channels))
                .put("buttons", new JSONArray(observed.values()))
                .put("events", new JSONArray(history))
                .put("mapping", new JSONObject(ctx.getSharedPreferences("controls", Context.MODE_PRIVATE).getString("buttons", "{}")))
                .put("labels", new JSONObject(ctx.getSharedPreferences("controls", Context.MODE_PRIVATE).getString("labels", "{}")));
    }

    public static String defaultLabel(int keyCode) {
        switch (keyCode) {
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
            case KeyEvent.KEYCODE_MEDIA_RECORD:
                return "录像";
            default:
                return "";
        }
    }

    public static synchronized String labelId(Context ctx, String label) {
        try {
            JSONObject labels = new JSONObject(ctx.getSharedPreferences("controls", Context.MODE_PRIVATE).getString("labels", "{}"));
            Iterator<String> it = labels.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (label.equals(labels.optString(k))) {
                    return k;
                }
            }
            for (Map.Entry<String, JSONObject> e : observed.entrySet()) {
                if (label.equals(defaultLabel(e.getValue().optInt("keyCode", -1)))) {
                    return e.getKey();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static synchronized boolean labelDown(Context ctx, String label) {
        String id = labelId(ctx, label);
        return id != null && pressed.contains(id);
    }

    public static synchronized String lastKey() {
        if (history.isEmpty()) {
            return "等待按下实体按键";
        }
        JSONObject j = history.get(history.size() - 1);
        return (j.optInt("action") == KeyEvent.ACTION_DOWN ? "按下  " : "松开  ")
                + j.optString("id") + "  ·  " + j.optString("keyName");
    }

    private float norm(int v) {
        float f = Math.max(-1f, Math.min(1f, (v - 1024) / 660f));
        return Math.abs(f) < 0.06f ? 0f : f;
    }

    private JSONObject report() throws Exception {
        boolean active = output && SystemClock.uptimeMillis() - sampleAt < 300;
        if (SystemClock.uptimeMillis() - mcuButtonsAt > 700) {
            synchronized (ControlService.class) {
                if (pressed.removeIf(id -> id.startsWith("mcu:"))) {
                    keyRevision++;
                }
            }
        }
        int[] ch = channels;
        float lt = 0f;
        float rt = 0f;
        int buttons = 0;
        int hat = 8;
        if (active) {
            lt = Math.abs(norm(ch[4]));
            rt = Math.abs(norm(ch[5]));
            JSONObject map = mapping();
            synchronized (ControlService.class) {
                for (String id : pressed) {
                    String target = map.optString(id, "");
                    buttons |= buttonBit(target);
                    if (target.equals("LT")) {
                        lt = 1f;
                    }
                    if (target.equals("RT")) {
                        rt = 1f;
                    }
                }
            }
            boolean up = (buttons & 1) != 0;
            boolean down = (buttons & 2) != 0;
            boolean left = (buttons & 4) != 0;
            boolean right = (buttons & 8) != 0;
            if (up && !down) {
                hat = (right && !left) ? 1 : ((left && !right) ? 7 : 0);
            } else if (down && !up) {
                hat = (right && !left) ? 3 : ((left && !right) ? 5 : 4);
            } else if (right && !left) {
                hat = 2;
            } else if (left && !right) {
                hat = 6;
            } else {
                hat = 8;
            }
        }
        return new JSONObject()
                .put("lx", active ? (double) norm(ch[3]) : 0.0)
                .put("ly", active ? (double) (-norm(ch[2])) : 0.0)
                .put("rx", active ? (double) norm(ch[0]) : 0.0)
                .put("ry", active ? (double) (-norm(ch[1])) : 0.0)
                .put("lt", (double) lt)
                .put("rt", (double) rt)
                .put("buttons", buttons)
                .put("hat", hat)
                .put("active", active);
    }

    private int buttonBit(String name) {
        String[] order = new String[]{
                "UP", "DOWN", "LEFT", "RIGHT", "START", "BACK", "LS", "RS",
                "LB", "RB", "GUIDE", "", "A", "B", "X", "Y"
        };
        for (int i = 0; i < 16; i++) {
            if (!name.isEmpty() && name.equals(order[i])) {
                return 1 << i;
            }
        }
        return 0;
    }

    private void sendLoop() {
        LocalSocket local = null;
        BufferedWriter localOut = null;
        long nextConnect = 0;
        long lastLog = 0;
        long lastActivate = -10000;
        while (running) {
            try {
                boolean useLocal = getSharedPreferences("controls", Context.MODE_PRIVATE).getBoolean("local", true);
                if (!useLocal && local != null) {
                    try {
                        localOut.write("{\"active\":false}\n");
                        localOut.flush();
                    } finally {
                        local.close();
                        local = null;
                        localOut = null;
                        localStatus = "本机输出已关闭";
                        output = false;
                        clear();
                    }
                }
                if (useLocal && local == null && SystemClock.uptimeMillis() > nextConnect) {
                    nextConnect = SystemClock.uptimeMillis() + 2000;
                    try {
                        local = new LocalSocket();
                        local.connect(new LocalSocketAddress("rc2.xbox.global.v1", LocalSocketAddress.Namespace.ABSTRACT));
                        localOut = new BufferedWriter(new OutputStreamWriter(local.getOutputStream(), "UTF-8"));
                        localStatus = "已连接虚拟手柄";
                    } catch (Exception e) {
                        if (local != null) {
                            local.close();
                        }
                        local = null;
                        localOut = null;
                        localStatus = "未激活本机手柄";
                        if (SystemClock.uptimeMillis() - lastActivate > 8000) {
                            lastActivate = SystemClock.uptimeMillis();
                            localStatus = LocalAdbStarter.start(this);
                            Log.i("RC2Gamepad", "activation=" + localStatus);
                        }
                    }
                }
                String payload = report().toString() + "\n";
                if (SystemClock.uptimeMillis() - lastLog > 2000) {
                    lastLog = SystemClock.uptimeMillis();
                    Log.i("RC2Gamepad", "local=" + localStatus + " sampleAge=" + (SystemClock.uptimeMillis() - sampleAt) + " state=" + payload.trim());
                }
                if (localOut != null) {
                    try {
                        localOut.write(payload);
                        localOut.flush();
                    } catch (Exception e) {
                        try {
                            local.close();
                        } catch (Exception ignored) {
                        }
                        local = null;
                        localOut = null;
                        output = false;
                        clear();
                        localStatus = "本机连接断开，恢复后请重新启用输出";
                    }
                }
                Thread.sleep(20);
            } catch (Exception e) {
                status = e.toString();
                try {
                    Thread.sleep(100);
                } catch (Exception ignored) {
                }
            }
        }
        try {
            if (localOut != null) {
                localOut.write("{\"active\":false}\n");
                localOut.flush();
            }
            if (local != null) {
                local.close();
            }
        } catch (Exception ignored) {
        }
    }
}
