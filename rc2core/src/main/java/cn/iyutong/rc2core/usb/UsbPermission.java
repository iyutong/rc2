package cn.iyutong.rc2core.usb;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.SystemClock;
import android.system.Os;
import android.system.StructStat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Locale;

/**
 * USB 授权：一条「能过就过」的策略链，外加一份把失败原因钉死的诊断。
 *
 * <h3>为什么不能只调一次 requestPermission</h3>
 * 实测在 RC2 上，{@code requestPermission} 返回后 30 秒内收不到任何广播。原因可能是三种，
 * 而它们给出的表象完全一样（都是「等超时」），必须分别处理：
 * <ol>
 *   <li><b>系统没有授权界面</b>：定制固件裁掉了 {@code UsbPermissionActivity}，
 *       {@code requestPermission} 内部起不来 Activity，于是永远不发广播。此时只能靠
 *       预授权、或让用户从「默认应用」那条路进来。</li>
 *   <li><b>广播发到了但结果字段被丢</b>：{@code PendingIntent} 若被声明为不可变，
 *       系统无法把 {@code EXTRA_PERMISSION_GRANTED} 填进去，接收方读到的永远是 false。
 *       本类强制用可变 PendingIntent，并把「收到但没字段」单独报出来。</li>
 *   <li><b>设备已被别的进程独占</b>：系统判断无需再问，直接走拒绝路径。</li>
 * </ol>
 *
 * <h3>策略链</h3>
 * <pre>
 * S1 hasPermission()        系统已有授权记录，直接用
 * S2 openDevice() 试探      有些系统对白名单应用静默放行，hasPermission 却仍报 false
 * S3 requestPermission()   显式/隐式双路接收 + 可变 PendingIntent，等 timeout
 * S4 二次复核              授权常有延迟；超时后再查一次 hasPermission 并试开
 * </pre>
 */
public final class UsbPermission {

    /** {@code PendingIntent.FLAG_MUTABLE} 的数值；API 31 才有该常量，低版本按位忽略。 */
    private static final int FLAG_MUTABLE = 1 << 25;

    private UsbPermission() {
    }

    // ───────────────────────── 结果 ─────────────────────────

    /** 一次授权尝试的全部信息，界面可以直接摊开给人看。 */
    public static final class Result {

        public final boolean granted;
        /** 哪一步成功的：hasPermission / openDevice / requestPermission / recheck。 */
        public final String strategy;
        /** 给用户看的中文说明。 */
        public final String detail;
        /** 若打开成功，这里带着可复用的连接；调用方不用了要 close。 */
        public final UsbDeviceConnection connection;
        /** 授权广播是否到达过。 */
        public final boolean broadcastSeen;
        /** 广播到了但没带结果字段（PendingIntent 不可变的典型症状）。 */
        public final boolean extrasMissing;
        public final long waitedMs;

        Result(boolean granted, String strategy, String detail,
               UsbDeviceConnection connection, boolean broadcastSeen,
               boolean extrasMissing, long waitedMs) {
            this.granted = granted;
            this.strategy = strategy;
            this.detail = detail;
            this.connection = connection;
            this.broadcastSeen = broadcastSeen;
            this.extrasMissing = extrasMissing;
            this.waitedMs = waitedMs;
        }

        /** 一行摘要，日志与状态栏用。 */
        public String summary() {
            return String.format(Locale.US, "%s（%s · %dms%s%s）",
                    detail, strategy, waitedMs,
                    broadcastSeen ? " · 广播已到" : " · 无广播",
                    extrasMissing ? " · 缺结果字段" : "");
        }
    }

    // ───────────────────────── 授权链 ─────────────────────────

    /**
     * 尽最大努力让 {@code dev} 变成「可打开」状态。
     *
     * @param timeoutMs 等用户点授权框的时长；传 0 表示不弹框、只做 S1/S2
     */
    public static Result ensure(Context context, UsbManager mgr, UsbDevice dev, long timeoutMs) {
        long started = SystemClock.uptimeMillis();
        if (context == null || mgr == null) {
            return new Result(false, "前置", "没有 Context 或 USB 服务", null, false, false, 0);
        }
        if (dev == null) {
            return new Result(false, "前置", "目标设备为空", null, false, false, 0);
        }

        // S1：系统已授权
        if (hasPermission(mgr, dev)) {
            UsbDeviceConnection c = tryOpen(mgr, dev);
            return new Result(true, "hasPermission", "系统已有授权记录", c, false, false,
                    SystemClock.uptimeMillis() - started);
        }

        // S2：直接试探。白名单/系统权限下 hasPermission 可能不准。
        UsbDeviceConnection probe = tryOpen(mgr, dev);
        if (probe != null) {
            return new Result(true, "openDevice", "直接打开成功（系统静默放行）", probe, false, false,
                    SystemClock.uptimeMillis() - started);
        }

        if (timeoutMs <= 0) {
            return new Result(false, "openDevice", "未授权，且本次不允许弹框", null, false, false,
                    SystemClock.uptimeMillis() - started);
        }

        // S3：正式请求授权
        UsbPermissionReceiver.reset();
        BroadcastReceiver dynamic = registerDynamic(context);
        if (!request(context, mgr, dev)) {
            unregisterQuietly(context, dynamic);
            return new Result(false, "requestPermission",
                    "requestPermission 抛异常（系统拒绝受理）", null, false, false,
                    SystemClock.uptimeMillis() - started);
        }

        boolean ok = UsbPermissionReceiver.await(timeoutMs);
        unregisterQuietly(context, dynamic);

        boolean seen = UsbPermissionReceiver.seen();
        boolean noExtras = seen && !UsbPermissionReceiver.extrasPresent();
        long waited = SystemClock.uptimeMillis() - started;

        if (ok && hasPermission(mgr, dev)) {
            UsbDeviceConnection c = tryOpen(mgr, dev);
            return new Result(true, "requestPermission", "用户已允许", c, true, false, waited);
        }

        // S4：授权常有延迟。超时后再查一次，并再试开一次。
        if (hasPermission(mgr, dev)) {
            UsbDeviceConnection c = tryOpen(mgr, dev);
            return new Result(true, "recheck", "授权延迟到达", c, seen, noExtras, waited);
        }
        UsbDeviceConnection late = tryOpen(mgr, dev);
        if (late != null) {
            return new Result(true, "recheck", "超时后设备已可打开", late, seen, noExtras, waited);
        }

        return new Result(false, "requestPermission", explain(seen, noExtras),
                null, seen, noExtras, waited);
    }

    /** 把「等超时」拆成能指导后续动作的三句话。 */
    private static String explain(boolean seen, boolean noExtras) {
        if (!seen) {
            return "授权广播始终没来。最可能是系统的授权界面不存在（定制固件常见），"
                    + "其次是被策略拦下。对策：拔插一次 USB 设备，在系统询问时勾选「默认使用本应用」";
        }
        if (noExtras) {
            return "广播到了但没带结果字段 —— PendingIntent 不可变，系统无法回填授权结果";
        }
        return "系统明确拒绝了授权（设备多半已被其他进程独占，或策略禁止第三方打开）";
    }

    private static boolean request(Context context, UsbManager mgr, UsbDevice dev) {
        try {
            Intent base = new Intent(UsbPermissionReceiver.ACTION)
                    .setPackage(context.getPackageName());
            int flags = PendingIntent.FLAG_CANCEL_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) {
                flags |= PendingIntent.FLAG_MUTABLE;
            } else {
                // 低版本没有该常量，但位值仍然可用；不加则在部分 30 上会被视为可变，行为一致
                flags |= FLAG_MUTABLE;
            }
            PendingIntent pi = PendingIntent.getBroadcast(context, 0, base, flags);
            mgr.requestPermission(dev, pi);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 动态接收器作为兜底：万一 manifest 里的静态声明在合并或裁剪中丢了，
     * 这条还能接住同一条广播。
     */
    private static BroadcastReceiver registerDynamic(Context context) {
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                // 与静态接收器共用同一个状态源，避免两套结果打架
                UsbPermissionReceiver.deliver(i);
            }
        };
        try {
            IntentFilter f = new IntentFilter(UsbPermissionReceiver.ACTION);
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(r, f);
            }
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    private static void unregisterQuietly(Context context, BroadcastReceiver r) {
        if (r == null || context == null) {
            return;
        }
        try {
            context.unregisterReceiver(r);
        } catch (Exception ignored) {
        }
    }

    public static boolean hasPermission(UsbManager mgr, UsbDevice dev) {
        try {
            return mgr != null && dev != null && mgr.hasPermission(dev);
        } catch (Exception e) {
            return false;
        }
    }

    private static UsbDeviceConnection tryOpen(UsbManager mgr, UsbDevice dev) {
        try {
            return mgr.openDevice(dev);
        } catch (Exception e) {
            return null;
        }
    }

    /** 让正在等待的授权请求立刻返回，用于「停止通道」。 */
    public static void abort() {
        UsbPermissionReceiver.poke();
    }

    // ───────────────────────── 诊断 ─────────────────────────

    /**
     * 权限侧全量取证。
     *
     * <p>它要回答的只有一件事：<b>为什么打不开</b>。所以每一项都对应一种具体的失败模式，
     * 而不是罗列信息。
     */
    public static String diagnose(Context context) {
        StringBuilder sb = new StringBuilder(2048);

        sb.append("═══ 进程身份 ═══\n");
        int uid = android.os.Process.myUid();
        sb.append(String.format(Locale.US, "uid=%d (%s)  pid=%d  Android %s (API %d)\n",
                uid, uid == 1000 ? "system" : (uid < 10000 ? "特权" : "普通应用"),
                android.os.Process.myPid(), Build.VERSION.RELEASE,
                Build.VERSION.SDK_INT));
        sb.append("SELinux: ").append(readFile("/proc/self/attr/current")).append('\n');
        sb.append("ABI: ").append(Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?")
                .append('\n');

        sb.append("\n═══ USB Host ═══\n");
        try {
            boolean host = context.getPackageManager()
                    .hasSystemFeature(PackageManager.FEATURE_USB_HOST);
            sb.append(host ? "FEATURE_USB_HOST = true\n" : "FEATURE_USB_HOST = false（无主机能力）\n");
        } catch (Exception e) {
            sb.append("特性查询失败：").append(e.getClass().getSimpleName()).append('\n');
        }

        UsbManager mgr = UsbTopology.manager(context);
        if (mgr == null) {
            sb.append("USB 服务不可用\n");
            return sb.toString();
        }

        int total = 0;
        java.util.List<UsbDevice> all = devices(mgr);
        total = all.size();
        sb.append("getDeviceList: ").append(total).append(" 个\n");

        int i = 0;
        for (UsbDevice d : all) {
            sb.append("\n  [").append(i++).append("] ")
                    .append(String.format(Locale.US, "%04X:%04X",
                            d.getVendorId(), d.getProductId()));
            sb.append("  ").append(UsbTopology.safeName(d));
            sb.append('\n');
            sb.append("      ").append(UsbTopology.permission(mgr, d));
            sb.append("  class=").append(d.getDeviceClass())
                    .append(" 接口=").append(d.getInterfaceCount())
                    .append(" IN端点=").append(UsbTopology.inEndpointCount(d));
            sb.append('\n');
            // 序列号 / 厂商名需要权限才能读，读不到就是「没授权」的旁证
            sb.append("      序列号=").append(safe(() -> d.getSerialNumber()))
                    .append("  厂商=").append(safe(d::getManufacturerName))
                    .append("  产品=").append(safe(d::getProductName));
            sb.append('\n');
        }

        sb.append("\n═══ USB Accessory（从机侧）═══\n");
        sb.append(accessory(mgr));

        sb.append("\n═══ /dev/bus/usb 节点权限 ═══\n");
        sb.append(devNodes());

        sb.append("\n═══ 结论 ═══\n");
        sb.append(conclude(context, mgr, total));
        return sb.toString();
    }

    /**
     * 看 USB 控制器是不是以「从机」模式工作。
     *
     * <p>如果这里非空而 {@code getDeviceList()} 为空，说明本机的 USB 口只做外设，
     * 没有主机侧总线可读——USB 方案在物理层就不成立，再折腾授权也没意义。
     */
    private static String accessory(UsbManager mgr) {
        try {
            android.hardware.usb.UsbAccessory[] list = mgr.getAccessoryList();
            if (list == null || list.length == 0) {
                return "  无（USB 口未工作在外设模式）\n";
            }
            StringBuilder sb = new StringBuilder();
            for (android.hardware.usb.UsbAccessory a : list) {
                sb.append("  ").append(a.getManufacturer()).append(' ')
                        .append(a.getModel()).append("  ver=")
                        .append(a.getVersion()).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return "  查询失败：" + e.getClass().getSimpleName() + "\n";
        }
    }

    private static String conclude(Context context, UsbManager mgr, int total) {
        StringBuilder sb = new StringBuilder();
        if (total == 0) {
            sb.append("✗ UsbManager 一个设备都看不到。\n");
            if (hasAccessory(mgr)) {
                sb.append("  但 accessory 列表非空：USB 口只工作在从机（外设）模式，")
                        .append("系统压根没有主机侧总线，读不到任何输入流。\n")
                        .append("  → 这不是授权问题，授权再成功也没用。\n");
            } else {
                sb.append("  遥控器内部的 MCU 通道没有以「USB 设备」的形式暴露给应用层，")
                        .append("或者本机 USB 控制器没开主机模式。\n")
                        .append("  → USB 方案在这台机器上不成立，不用再试授权。\n");
            }
            return sb.toString();
        }

        int granted = 0;
        int openable = 0;
        for (UsbDevice d : devices(mgr)) {
            if (hasPermission(mgr, d)) {
                granted++;
                UsbDeviceConnection c = tryOpen(mgr, d);
                if (c != null) {
                    openable++;
                    try {
                        c.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        sb.append("看到 ").append(total).append(" 个设备，其中已授权 ").append(granted)
                .append(" 个，可实际打开 ").append(openable).append(" 个。\n");

        if (openable > 0) {
            sb.append("✓ 至少有一个设备能打开 —— 授权这条过了，问题在后面（接口被占 / 端点无数据）。\n");
        } else if (granted == 0) {
            sb.append("△ 全部未授权。若点「重开通道」时系统不弹框，就是授权界面缺失。三条替代路径：\n")
                    .append("  1) 让本应用成为该 USB 设备的默认应用（需设备重新插拔触发一次）；\n")
                    .append("  2) 把它装进 /system/priv-app 并授予 android.permission.MANAGE_USB；\n")
                    .append("  3) 用 root 直接改 /dev/bus/usb 节点权限（仅调试用）。\n");
        } else {
            sb.append("△ 有授权记录但打不开 —— 设备句柄已被别的进程（多半是厂商 protocol 服务）持有。\n")
                    .append("  这种情况下即便授权通过，claimInterface 也会失败。\n");
        }
        return sb.toString();
    }

    /** 逐个 /dev/bus/usb 节点报权限位。普通应用通常只能看到 0660 root:root。 */
    private static String devNodes() {
        File root = new File("/dev/bus/usb");
        File[] buses = root.listFiles();
        if (buses == null || buses.length == 0) {
            return "  目录不可读（SELinux 或权限位限制）\n";
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (File bus : buses) {
            File[] nodes = bus.listFiles();
            if (nodes == null) {
                continue;
            }
            for (File node : nodes) {
                if (shown++ >= 10) {
                    sb.append("  …\n");
                    return sb.toString();
                }
                sb.append("  ").append(node.getAbsolutePath()).append(' ')
                        .append(mode(node)).append('\n');
            }
        }
        return sb.length() == 0 ? "  无常驻节点\n" : sb.toString();
    }

    /** 把 st_mode/st_uid/st_gid 拼成 ls 风格的一行。 */
    private static String mode(File f) {
        try {
            StructStat st = Os.stat(f.getAbsolutePath());
            char type;
            int fmt = st.st_mode & 0xF000;
            if (fmt == 0x2000) {
                type = 'c';
            } else if (fmt == 0x4000) {
                type = 'd';
            } else if (fmt == 0x8000) {
                type = '-';
            } else {
                type = '?';
            }
            StringBuilder p = new StringBuilder();
            p.append(type);
            int[] bits = {0400, 0200, 0100, 0040, 0020, 0010, 0004, 0002, 0001};
            char[] chars = {'r', 'w', 'x', 'r', 'w', 'x', 'r', 'w', 'x'};
            for (int i = 0; i < bits.length; i++) {
                p.append((st.st_mode & bits[i]) != 0 ? chars[i] : '-');
            }
            return String.format(Locale.US, "%s  uid=%d gid=%d", p, st.st_uid, st.st_gid);
        } catch (Exception e) {
            return "stat 失败：" + e.getClass().getSimpleName();
        }
    }

    private interface Str {
        String get();
    }

    private static boolean hasAccessory(UsbManager mgr) {
        try {
            android.hardware.usb.UsbAccessory[] list = mgr.getAccessoryList();
            return list != null && list.length > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 枚举设备，异常一律降级成空表——诊断本身不能因为一次异常就断掉。 */
    private static java.util.List<UsbDevice> devices(UsbManager mgr) {
        java.util.List<UsbDevice> out = new java.util.ArrayList<>();
        if (mgr == null) {
            return out;
        }
        try {
            out.addAll(mgr.getDeviceList().values());
        } catch (Exception ignored) {
        }
        return out;
    }

    private static String safe(Str s) {
        try {
            String v = s.get();
            return v == null || v.isEmpty() ? "—" : v;
        } catch (SecurityException e) {
            // 无权限时 getName/SerialNumber 抛 SecurityException，这本身就是有力证据
            return "不可读(无权限)";
        } catch (Exception e) {
            return "—";
        }
    }

    private static String readFile(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String s = r.readLine();
            return s == null ? "—" : s.trim();
        } catch (Exception e) {
            return "不可读";
        }
    }
}
