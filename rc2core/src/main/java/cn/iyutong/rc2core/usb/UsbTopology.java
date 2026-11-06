package cn.iyutong.rc2core.usb;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * USB 侧的能力普查。只读，不打开任何设备，因此在任何机型上都能安全调用。
 *
 * <p>它要回答的是四个问题，这也是 USB 方案能不能成立的四个前提：
 * <ol>
 *   <li>系统有没有暴露 USB Host（{@link UsbManager#getDeviceList()} 非空）；</li>
 *   <li>遥控器内部那条总线上的设备，应用能不能看见（USB 枚举 vs 仅内核节点）；</li>
 *   <li>看见的设备有没有可读的 IN 端点（没有就只能看，不能听）；</li>
 *   <li><b>内核层到底有没有 KEY_FOCUS</b>——{@code /proc/bus/input/devices} 的
 *       {@code B: KEY=} 位图里查 bit 80 / bit 211。这一条是终局判据：
 *       内核都没有，说明半按连 HID 报告都没进，USB 以上任何层都不可能拿到。</li>
 * </ol>
 */
public final class UsbTopology {

    /** DJI 的 USB Vendor ID。RC2 内部的 MCU 常见于该 VID 下。 */
    public static final int VID_DJI = 0x2CA3;

    /** Android 标准键值：半按对焦 / 全按快门。用于查内核 keybit。 */
    public static final int KEY_FOCUS = 80;
    public static final int KEY_CAMERA = 211;

    private UsbTopology() {
    }

    // ───────────────────────── 候选设备 ─────────────────────────

    /**
     * 可能承载输入的设备，按「像不像遥控器」排序。
     *
     * <p>不信任 VID：RC2 内部的 MCU 未必挂在 DJI 的 VID 下。
     *
     * <p><b>刻意不按「有没有 IN 端点」过滤。</b>未授权时系统可能读不到配置描述符，
     * {@code getInterfaceCount()} 返回 0——若据此剔除，设备会在授权之前就被判死，
     * 报出来的原因是「没有可读端点」，而真实原因是「还没授权」。
     * 端点是否存在，等拿到权限后再判断。
     */
    public static List<UsbDevice> candidates(Context context) {
        List<UsbDevice> out = new ArrayList<>();
        UsbManager mgr = manager(context);
        if (mgr == null) {
            return out;
        }
        try {
            for (UsbDevice d : mgr.getDeviceList().values()) {
                if (isHub(d)) {
                    continue;
                }
                // 有 IN 端点的排前面，没有的也留着（可能只是没权限读描述符）
                if (hasInEndpoint(d) || d.getVendorId() == VID_DJI) {
                    out.add(0, d);
                } else {
                    out.add(d);
                }
            }
        } catch (Exception ignored) {
            // getDeviceList 在极少数机型上会抛 SecurityException，视为无设备
        }
        return out;
    }

    /**
     * 授权后重新取一次设备对象。
     *
     * <p>授权前构造的 {@link UsbDevice} 可能没有接口/端点信息，直接拿它去
     * {@code claimInterface} 会失败。授权完成后必须从 UsbManager 重新枚举。
     */
    public static UsbDevice refetch(Context context, UsbDevice target) {
        if (target == null) {
            return null;
        }
        UsbManager mgr = manager(context);
        if (mgr == null) {
            return target;
        }
        try {
            for (UsbDevice d : mgr.getDeviceList().values()) {
                if (d.getDeviceId() == target.getDeviceId()
                        || (d.getVendorId() == target.getVendorId()
                        && d.getProductId() == target.getProductId())) {
                    return d;
                }
            }
        } catch (Exception ignored) {
        }
        return target;
    }

    public static UsbManager manager(Context context) {
        if (context == null) {
            return null;
        }
        try {
            return (UsbManager) context.getSystemService(Context.USB_SERVICE);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isHub(UsbDevice d) {
        return d != null && d.getDeviceClass() == UsbConstants.USB_CLASS_HUB;
    }

    /** 设备上有没有可读端点（IN 方向）。 */
    public static boolean hasInEndpoint(UsbDevice d) {
        if (d == null) {
            return false;
        }
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface itf = d.getInterface(i);
            for (int j = 0; j < itf.getEndpointCount(); j++) {
                if (itf.getEndpoint(j).getDirection() == UsbConstants.USB_DIR_IN) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 设备上所有 IN 端点的数量。 */
    public static int inEndpointCount(UsbDevice d) {
        int n = 0;
        if (d == null) {
            return 0;
        }
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface itf = d.getInterface(i);
            for (int j = 0; j < itf.getEndpointCount(); j++) {
                if (itf.getEndpoint(j).getDirection() == UsbConstants.USB_DIR_IN) {
                    n++;
                }
            }
        }
        return n;
    }

    // ───────────────────────── 描述 ─────────────────────────

    public static String describe(UsbDevice d, UsbManager mgr) {
        if (d == null) {
            return "—";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "%04X:%04X", d.getVendorId(), d.getProductId()));
        String name = safeName(d);
        if (!name.isEmpty()) {
            sb.append(' ').append(name);
        }
        sb.append(String.format(Locale.US, "  class=%d/%d/%d  %s",
                d.getDeviceClass(), d.getDeviceSubclass(), d.getDeviceProtocol(),
                permission(mgr, d)));
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface itf = d.getInterface(i);
            sb.append(String.format(Locale.US, "%n    接口%d class=%d sub=%d proto=%d 端点%d",
                    i, itf.getInterfaceClass(), itf.getInterfaceSubclass(),
                    itf.getInterfaceProtocol(), itf.getEndpointCount()));
            for (int j = 0; j < itf.getEndpointCount(); j++) {
                UsbEndpoint ep = itf.getEndpoint(j);
                sb.append(String.format(Locale.US, "%n      %s 0x%02X %s max=%d",
                        ep.getDirection() == UsbConstants.USB_DIR_IN ? "IN " : "OUT",
                        ep.getAddress(), typeName(ep.getType()), ep.getMaxPacketSize()));
            }
        }
        return sb.toString();
    }

    public static String safeName(UsbDevice d) {
        StringBuilder sb = new StringBuilder();
        try {
            String m = d.getManufacturerName();
            String p = d.getProductName();
            if (m != null && !m.isEmpty()) {
                sb.append(m);
            }
            if (p != null && !p.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(p);
            }
        } catch (Exception ignored) {
            // 没有权限时 getName 系接口会抛异常，退化成空串
        }
        return sb.toString();
    }

    public static String permission(UsbManager mgr, UsbDevice d) {
        if (mgr == null || d == null) {
            return "权限?";
        }
        try {
            return mgr.hasPermission(d) ? "已授权" : "未授权";
        } catch (Exception e) {
            return "权限?";
        }
    }

    public static String typeName(int type) {
        switch (type) {
            case UsbConstants.USB_ENDPOINT_XFER_CONTROL:
                return "CTRL";
            case UsbConstants.USB_ENDPOINT_XFER_ISOC:
                return "ISOC";
            case UsbConstants.USB_ENDPOINT_XFER_BULK:
                return "BULK";
            case UsbConstants.USB_ENDPOINT_XFER_INT:
                return "INT ";
            default:
                return "?   ";
        }
    }

    // ───────────────────────── 完整报告 ─────────────────────────

    /** 一份可以整屏贴出来的自查报告。 */
    public static String report(Context context) {
        StringBuilder sb = new StringBuilder(2048);
        UsbManager mgr = manager(context);

        sb.append("═══ USB Host ═══\n");
        if (mgr == null) {
            sb.append("USB 服务不可用（Context 为空或系统未提供）\n");
        } else {
            int n = 0;
            try {
                n = mgr.getDeviceList().size();
            } catch (Exception e) {
                sb.append("枚举失败：").append(e.getClass().getSimpleName()).append('\n');
            }
            sb.append(n == 0
                    ? "getDeviceList 为空 —— 本机没有作为 USB 主机的可枚举设备\n"
                    : "getDeviceList 报告 " + n + " 个设备\n");
            int i = 0;
            for (UsbDevice d : candidates(context)) {
                sb.append("  [").append(i++).append("] ").append(describe(d, mgr)).append('\n');
            }
        }

        sb.append("\n═══ /sys/bus/usb/devices ═══\n");
        sb.append(sysUsb());

        sb.append("\n═══ /dev/bus/usb 节点 ═══\n");
        sb.append(devBusUsb());

        sb.append("\n═══ /dev/hidraw* ═══\n");
        sb.append(hidraw());

        sb.append("\n═══ 输入设备（内核 keybit）═══\n");
        sb.append(inputDevices());

        sb.append("\n═══ 结论 ═══\n");
        sb.append(verdict(context));
        return sb.toString();
    }

    /**
     * 一句话结论：USB 方案卡在哪一步。
     *
     * <p>三种结局对应三条完全不同的后续动作，所以放在报告最后单独说。
     */
    public static String verdict(Context context) {
        UsbManager mgr = manager(context);
        int managed = 0;
        try {
            managed = mgr == null ? 0 : mgr.getDeviceList().size();
        } catch (Exception ignored) {
        }
        int sys = countSysDevices();

        boolean focusKey = inputDeclaresFocus();

        StringBuilder sb = new StringBuilder();
        if (managed > 0) {
            sb.append("✓ 应用层可枚举到 ").append(managed)
                    .append(" 个 USB 设备 —— 可以尝试打开端点读流。\n");
        } else if (sys > 0) {
            sb.append("△ 内核有 ").append(sys)
                    .append(" 个 USB 设备，但 UsbManager 看不到：它们已被内核驱动或厂商服务接管，")
                    .append("应用层拿不到端点。这是最常见的一种「看得到摸不到」。\n");
        } else {
            sb.append("✗ 内核与应用层都没有 USB 设备 —— 本机的按键输入不经过 USB，")
                    .append("这条方案在物理层就不成立。\n");
        }
        sb.append(focusKey
                ? "内核有设备声明了 KEY_FOCUS：半按确实进过 HID/输入层，只是没往上派发。\n"
                : "内核没有任何设备声明 KEY_FOCUS：半按连输入层都没进，MCU 内部就消化了。\n");
        return sb.toString();
    }

    private static boolean inputDeclaresFocus() {
        File f = new File("/proc/bus/input/devices");
        if (!f.canRead()) {
            return false;
        }
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("B: KEY=")
                        && hasKeyBit(line.substring(7).trim(), KEY_FOCUS)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static int countSysDevices() {
        File dir = new File("/sys/bus/usb/devices");
        File[] list = dir.listFiles();
        if (list == null) {
            return 0;
        }
        int n = 0;
        for (File d : list) {
            if (d.isDirectory()
                    && (!read(d, "idVendor").isEmpty() || !read(d, "idProduct").isEmpty())) {
                n++;
            }
        }
        return n;
    }

    private static String sysUsb() {
        File dir = new File("/sys/bus/usb/devices");
        File[] list = dir.listFiles();
        if (list == null || list.length == 0) {
            return "不可读或为空（内核未暴露 USB 设备树）\n";
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (File d : list) {
            if (!d.isDirectory()) {
                continue;
            }
            String vid = read(d, "idVendor");
            String pid = read(d, "idProduct");
            if (vid.isEmpty() && pid.isEmpty()) {
                continue;
            }
            if (shown++ >= 12) {
                sb.append("  …\n");
                break;
            }
            sb.append(String.format(Locale.US, "  %s  %s:%s  %s %s  if=%s ep=%s\n",
                    d.getName(), vid, pid,
                    read(d, "manufacturer"), read(d, "product"),
                    read(d, "bNumInterfaces"), read(d, "bNumEndpoints")));
        }
        return sb.length() == 0 ? "无带 VID/PID 的设备节点\n" : sb.toString();
    }

    private static String devBusUsb() {
        File root = new File("/dev/bus/usb");
        File[] buses = root.listFiles();
        if (buses == null || buses.length == 0) {
            return "不可读（普通应用通常无权遍历此目录）\n";
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (File bus : buses) {
            File[] nodes = bus.listFiles();
            if (nodes == null) {
                continue;
            }
            for (File node : nodes) {
                if (shown++ >= 12) {
                    sb.append("  …\n");
                    return sb.toString();
                }
                sb.append(String.format(Locale.US, "  %s  可读=%s\n",
                        node.getAbsolutePath(), node.canRead() ? "是" : "否"));
            }
        }
        return sb.length() == 0 ? "无常驻节点\n" : sb.toString();
    }

    private static String hidraw() {
        File dir = new File("/dev");
        File[] list = dir.listFiles((d, name) -> name.startsWith("hidraw"));
        if (list == null || list.length == 0) {
            return "没有 hidraw 节点\n";
        }
        StringBuilder sb = new StringBuilder();
        for (File f : list) {
            sb.append(String.format(Locale.US, "  %s  可读=%s\n",
                    f.getAbsolutePath(), f.canRead() ? "是" : "否"));
        }
        return sb.toString();
    }

    /** 解析 /proc/bus/input/devices，标出哪些设备声明了 FOCUS / CAMERA 键。 */
    public static String inputDevices() {
        File f = new File("/proc/bus/input/devices");
        if (!f.canRead()) {
            return "不可读\n";
        }
        StringBuilder sb = new StringBuilder();
        String name = "";
        String handlers = "";
        String keys = "";
        boolean focusAny = false;
        boolean cameraAny = false;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("N: Name=")) {
                    name = line.substring(8).replace("\"", "").trim();
                } else if (line.startsWith("H: Handlers=")) {
                    handlers = line.substring(12).trim();
                } else if (line.startsWith("B: KEY=")) {
                    keys = line.substring(7).trim();
                } else if (line.isEmpty()) {
                    if (!keys.isEmpty()) {
                        boolean focus = hasKeyBit(keys, KEY_FOCUS);
                        boolean camera = hasKeyBit(keys, KEY_CAMERA);
                        focusAny |= focus;
                        cameraAny |= camera;
                        sb.append(String.format(Locale.US, "  %-28s %-22s FOCUS=%s CAMERA=%s%n",
                                name.isEmpty() ? "(无名)" : name, handlers,
                                focus ? "是" : "否", camera ? "是" : "否"));
                    }
                    name = "";
                    handlers = "";
                    keys = "";
                }
            }
        } catch (Exception e) {
            return "解析失败：" + e.getClass().getSimpleName() + "\n";
        }
        if (sb.length() == 0) {
            return "没有带按键能力的输入设备\n";
        }
        if (!focusAny) {
            sb.append("  → 内核层没有任何设备声明 KEY_FOCUS(80)\n");
        }
        if (!cameraAny) {
            sb.append("  → 内核层没有任何设备声明 KEY_CAMERA(211)\n");
        }
        return sb.toString();
    }

    /**
     * 判断 keybit 位图里第 {@code code} 位是否为 1。
     *
     * <p>格式是「按 64 位分组、从高组到低组、空格分隔」，所以组下标要倒过来算。
     */
    public static boolean hasKeyBit(String bitmap, int code) {
        if (bitmap == null || bitmap.isEmpty()) {
            return false;
        }
        String[] parts = bitmap.trim().split("\\s+");
        int group = code / 64;
        int bit = code % 64;
        int idx = parts.length - 1 - group;
        if (idx < 0 || idx >= parts.length) {
            return false;
        }
        try {
            long v = Long.parseUnsignedLong(parts[idx], 16);
            return ((v >>> bit) & 1L) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ───────────────────────── 小工具 ─────────────────────────

    private static String read(File dir, String name) {
        File f = new File(dir, name);
        if (!f.canRead()) {
            return "";
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f)))) {
            String s = r.readLine();
            return s == null ? "" : s.trim();
        } catch (Exception e) {
            return "";
        }
    }
}
