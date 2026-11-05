package local.rc2.controls;

import android.os.SystemClock;
import android.view.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

/**
 * 进程内输入状态中心：只负责采集遥控器摇杆通道与实体按键，
 * 供仪表盘读取。不做映射、不做输出、不依赖无障碍服务。
 */
public final class InputMonitor {
    public static final int CHANNELS = 6;
    public static volatile int[] channels = new int[]{1024, 1024, 1024, 1024, 1024, 1024};
    public static volatile long sampleAt;
    public static volatile long samples;
    public static volatile String status = "未连接 MCU";
    public static volatile long keyRevision;
    /** 最近一帧 MCU 按键原始报文的十六进制形式，仅供面板显示。 */
    public static volatile String lastMcuFrame = "—";
    /** 最新一帧完整按键报文，供原始报文页逐字节查看。 */
    public static volatile byte[] lastPayload = new byte[0];
    /** 与上一变化帧相比的差异位，例如 "b2.4 b1.0"。 */
    public static volatile String lastDiff = "—";
    public static volatile int frameCount;
    public static volatile long payloadRevision;

    private static final int FRAME_HISTORY = 16;
    private static final ArrayList<byte[]> frames = new ArrayList<>(FRAME_HISTORY);
    private static byte[] priorPayload;

    public static final LinkedHashMap<String, JSONObject> observed = new LinkedHashMap<>();
    public static final HashSet<String> pressed = new HashSet<>();
    public static final ArrayList<JSONObject> history = new ArrayList<>();

    private static McuReader reader;
    private static boolean running;
    private static int priorMcu = -1;
    private static volatile long mcuButtonsAt;

    private InputMonitor() {
    }

    public static synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        status = "MCU 连接中";
        keyRevision++;
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
                UiLog.e("MCU", msg);
            }
        });
        UiLog.i("Input", "开始监听 MCU");
    }

    public static synchronized void stop() {
        running = false;
        if (reader != null) {
            reader.stop();
            reader = null;
        }
        pressed.clear();
        priorMcu = -1;
        keyRevision++;
        UiLog.i("Input", "停止监听");
    }

    public static boolean running() {
        return running;
    }

    public static boolean live() {
        return running && SystemClock.uptimeMillis() - sampleAt < 500;
    }

    private static void mcuButtons(byte[] b) {
        if (b.length < 5) {
            return;
        }
        trackPayload(b);
        lastMcuFrame = hex(b, 64);
        mcuButtonsAt = SystemClock.uptimeMillis();
        int mask = (b[0] & 0x70) | ((b[2] & 0x0c) << 8) | ((b[1] & 0x01) << 16);
        int prev = priorMcu;
        priorMcu = mask;
        if (prev < 0) {
            UiLog.i("MCU", "已收到按键推送");
            return;
        }
        String[] names = new String[]{"C1", "C2", "拍照", "录像", "返航/暂停"};
        int[] bits = new int[]{0x400, 0x800, 0x40, 0x10000, 0x30};
        for (int i = 0; i < names.length; i++) {
            if ((prev & bits[i]) != (mask & bits[i])) {
                recordMcu(names[i], (mask & bits[i]) != 0);
            }
        }
    }

    private static synchronized void recordMcu(String name, boolean down) {
        String id = "mcu:" + name;
        JSONObject j = new JSONObject();
        try {
            j.put("id", id)
                    .put("keyCode", -1)
                    .put("keyName", name)
                    .put("scanCode", 0)
                    .put("device", "RC2 MCU")
                    .put("action", down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP)
                    .put("repeat", 0)
                    .put("uptime", SystemClock.uptimeMillis());
        } catch (Exception ignored) {
        }
        observed.put(id, j);
        history.add(j);
        if (history.size() > 500) {
            history.remove(0);
        }
        if (down) {
            pressed.add(id);
            UiLog.i("MCU", "按键 " + name + " 按下");
        } else {
            pressed.remove(id);
        }
        keyRevision++;
    }

    /** 记录一条系统派发的实体按键事件（仅在 Activity 前台有效）。 */
    public static synchronized void record(KeyEvent e) {
        if (e.getDevice() == null) {
            return;
        }
        String devName = e.getDevice().getName();
        if (!devName.contains("DJI") && !devName.equals("gpio-keys") && !devName.contains("qpnp")) {
            return;
        }
        String id = e.getScanCode() > 0 ? "scan:" + e.getScanCode() : "key:" + e.getKeyCode();
        JSONObject j = new JSONObject();
        try {
            j.put("id", id)
                    .put("keyCode", e.getKeyCode())
                    .put("keyName", KeyEvent.keyCodeToString(e.getKeyCode()))
                    .put("scanCode", e.getScanCode())
                    .put("device", devName)
                    .put("action", e.getAction())
                    .put("repeat", e.getRepeatCount())
                    .put("uptime", SystemClock.uptimeMillis());
        } catch (Exception ignored) {
        }
        observed.put(id, j);
        history.add(j);
        if (history.size() > 500) {
            history.remove(0);
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            pressed.add(id);
            if (e.getRepeatCount() == 0) {
                UiLog.i("Key", devName + " · " + KeyEvent.keyCodeToString(e.getKeyCode())
                        + " · scan " + e.getScanCode());
            }
        } else if (e.getAction() == KeyEvent.ACTION_UP) {
            pressed.remove(id);
        }
        keyRevision++;
    }

    /** MCU 按键帧停了就释放按下状态，避免仪表盘一直高亮。 */
    public static synchronized void expireMcu() {
        if (mcuButtonsAt == 0 || SystemClock.uptimeMillis() - mcuButtonsAt <= 700) {
            return;
        }
        if (pressed.removeIf(id -> id.startsWith("mcu:"))) {
            keyRevision++;
        }
    }

    /** 每帧都计数，只有内容变化时才入历史并记录差异位。 */
    private static synchronized void trackPayload(byte[] b) {
        frameCount++;
        if (priorPayload != null && Arrays.equals(priorPayload, b)) {
            return;
        }
        lastDiff = diff(priorPayload, b);
        priorPayload = b.clone();
        lastPayload = priorPayload;
        frames.add(b.clone());
        if (frames.size() > FRAME_HISTORY) {
            frames.remove(0);
        }
        payloadRevision++;
        UiLog.i("MCU", "报文变化 " + b.length + "B · " + lastDiff);
    }

    /** 当前帧副本，避免 UI 侧被后续写入影响。 */
    public static synchronized byte[] payload() {
        return lastPayload.clone();
    }

    /** 面板里用的短十六进制（前 8 字节）。 */
    public static synchronized String shortFrame() {
        return hex(lastPayload, 8);
    }

    /** 内容发生变化的帧，按时间升序，最多 16 条。 */
    public static synchronized List<byte[]> frameHistory() {
        return new ArrayList<>(frames);
    }

    private static String diff(byte[] a, byte[] b) {
        if (a == null) {
            return "首帧";
        }
        StringBuilder sb = new StringBuilder();
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = (a[i] ^ b[i]) & 0xff;
            for (int bit = 0; bit < 8; bit++) {
                if ((x & (1 << bit)) != 0) {
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append('b').append(i).append('.').append(bit);
                }
            }
        }
        if (a.length != b.length) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append("长度 ").append(a.length).append('→').append(b.length);
        }
        return sb.length() == 0 ? "无变化" : sb.toString();
    }

    private static String hex(byte[] b, int max) {
        int n = Math.min(b.length, max);
        StringBuilder sb = new StringBuilder(n * 3);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format(Locale.US, "%02X", b[i] & 0xff));
        }
        if (b.length > n) {
            sb.append(" …");
        }
        return sb.toString();
    }

    /** 最近一条按键事件，供仪表盘读取原始字段。 */
    public static synchronized JSONObject lastEvent() {
        return history.isEmpty() ? null : history.get(history.size() - 1);
    }

    public static synchronized String lastKey() {
        if (history.isEmpty()) {
            return "等待按下实体按键";
        }
        JSONObject j = history.get(history.size() - 1);
        return (j.optInt("action") == KeyEvent.ACTION_DOWN ? "按下  " : "松开  ")
                + j.optString("id") + "  ·  " + j.optString("keyName");
    }

    public static String defaultLabel(int keyCode) {
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
