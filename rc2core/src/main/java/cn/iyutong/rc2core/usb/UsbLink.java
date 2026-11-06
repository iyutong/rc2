package cn.iyutong.rc2core.usb;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.SystemClock;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * USB 输入通道：找到设备、拿到授权、打开连接、在每个 IN 端点上起读线程。
 *
 * <p>这是整个方案唯一与 {@code android.hardware.usb} 打交道的地方。上层只看到三条回调：
 * 打开了 / 来了一帧 / 出错了。
 *
 * <h3>权限</h3>
 * 免 root 的唯一正规途径是 {@link UsbManager#requestPermission}，系统会弹一次授权框
 * （若该设备此前已被系统预授权，则直接通过）。授权结果通过一个动态注册的
 * {@link BroadcastReceiver} 收回。没有这条广播，{@code openDevice} 会返回 null。
 *
 * <h3>为什么可能打不开</h3>
 * <ul>
 *   <li><b>接口被占</b>：遥控器内部的 MCU 通道通常已经被厂商的 {@code protocol} 服务
 *       独占 claim。此时 {@code claimInterface} 返回 false，我们拿不到端点。</li>
 *   <li><b>无 Host 特性</b>：{@code getDeviceList()} 为空，说明系统根本没把 USB 控制器
 *       暴露给应用层。</li>
 *   <li><b>设备不是 USB 设备</b>：MCU 走的是 UART/SPI，USB 口只做充电与外设，
 *       那 USB 方案从物理上就不成立。</li>
 * </ul>
 * 这三种情况会分别给出不同的状态文案，方便一眼定位。
 */
public final class UsbLink {

    private static final String TAG = "UsbLink";
    /** 等用户点授权框的最长时间。系统若没有授权界面，这里就是纯等待，所以不宜过长。 */
    private static final long PERMISSION_WAIT_MS = 15_000L;
    /** 单次 bulk 读超时。越短越省电，越长越容易一次拿到多包。 */
    private static final int READ_TIMEOUT_MS = 200;

    /** 上层接收点，全部在内部线程回调。 */
    public interface Sink {
        /** 通道是否可用。{@code detail} 是给用户看的中文原因。 */
        void onReady(boolean ready, String detail);

        /** 一个端点上来了一帧原始数据。{@code len} 是有效字节数。 */
        void onFrame(int epAddr, String epName, byte[] data, int len, long at);

        /** 端点已经打开但没有数据，用于区分「连上了」和「有数据」。 */
        void onIdle(long frames, String detail);

        void onError(String message);
    }

    private final Context context;
    private final Sink sink;
    private final List<Reader> readers = new ArrayList<>();
    private final Object lock = new Object();

    private volatile UsbManager manager;
    private volatile UsbDeviceConnection connection;
    private volatile UsbDevice device;
    private volatile boolean running;
    private volatile boolean open;
    private volatile String status = "未启动";
    private volatile String epSummary = "—";
    private volatile long frames;
    private volatile long openedAt;

    public UsbLink(Context context, Sink sink) {
        this.context = context == null ? null : context.getApplicationContext();
        this.sink = sink;
    }

    // ───────────────────────── 生命周期 ─────────────────────────

    /** 异步打开。失败不会抛异常，只会走 {@link Sink#onReady(false, String)}。 */
    public void start() {
        synchronized (lock) {
            if (running) {
                return;
            }
            running = true;
        }
        new Thread(this::openAsync, "usb-open").start();
    }

    public void stop() {
        synchronized (lock) {
            if (!running) {
                return;
            }
            running = false;
        }
        UsbPermission.abort();
        closeQuietly();
        open = false;
        status = "已停止";
    }

    public boolean isOpen() {
        return open;
    }

    public String status() {
        return status;
    }

    public String endpoints() {
        return epSummary;
    }

    public long frames() {
        return frames;
    }

    public long openedAt() {
        return openedAt;
    }

    public UsbDevice device() {
        return device;
    }

    // ───────────────────────── 打开流程 ─────────────────────────

    private void openAsync() {
        if (context == null) {
            fail("没有 Context，无法访问 USB 服务");
            return;
        }
        manager = UsbTopology.manager(context);
        if (manager == null) {
            fail("系统没有提供 USB 服务");
            return;
        }

        List<UsbDevice> list;
        try {
            list = UsbTopology.candidates(context);
        } catch (Exception e) {
            fail("枚举 USB 设备失败：" + e.getClass().getSimpleName());
            return;
        }
        if (list.isEmpty()) {
            int total = 0;
            try {
                total = manager.getDeviceList().size();
            } catch (Exception ignored) {
            }
            fail(total == 0
                    ? "没有枚举到任何 USB 设备 —— 本机未暴露 USB Host，或设备走的不是 USB"
                    : "枚举到 " + total + " 个 USB 设备，但全部是 hub，没有可用的输入设备");
            return;
        }

        // 优先已经授权的设备，避免无谓弹窗
        UsbDevice target = null;
        for (UsbDevice d : list) {
            if (UsbPermission.hasPermission(manager, d)) {
                target = d;
                break;
            }
        }
        if (target == null) {
            target = list.get(0);
        }

        // 授权链：已授权 → 直接尝试打开 → 请求授权 → 超时复核
        status = "检查 USB 授权…";
        safeReady(false, status);
        UsbPermission.Result pr = UsbPermission.ensure(context, manager, target, PERMISSION_WAIT_MS);
        if (!pr.granted) {
            fail("USB 打不开：" + pr.summary());
            return;
        }
        // 成功路径也记一笔，否则出问题时看不出是哪一步过的
        sink.onIdle(0, "授权通过：" + pr.summary());
        // 关键：授权前拿到的 UsbDevice 可能没有接口描述，必须重新枚举一次
        UsbDevice fresh = UsbTopology.refetch(context, target);
        if (fresh != null && fresh != target) {
            target = fresh;
        }
        openDevice(target, pr.connection);
    }

    /**
     * 打开设备并起读线程。
     *
     * @param preset 授权阶段已经打开好的连接；非空就直接复用，省掉一次 openDevice
     */
    private void openDevice(UsbDevice d, UsbDeviceConnection preset) {
        UsbDeviceConnection conn = preset;
        if (conn == null) {
            try {
                conn = manager.openDevice(d);
            } catch (Exception e) {
                fail("打开 USB 设备抛异常：" + e.getMessage());
                return;
            }
        }
        if (conn == null) {
            // 这一条比授权失败更常见：授权有了，但设备句柄在别人手里
            fail("openDevice 返回 null —— 设备已被其他进程（多半是厂商 protocol 服务）独占");
            return;
        }

        device = d;
        connection = conn;

        int claimed = 0;
        int skipped = 0;
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface itf = d.getInterface(i);
            if (itf.getInterfaceClass() == UsbConstants.USB_CLASS_HUB) {
                continue;
            }
            boolean ok;
            try {
                ok = conn.claimInterface(itf, true);
            } catch (Exception e) {
                ok = false;
            }
            if (!ok) {
                skipped++;
                continue;
            }
            claimed++;
            for (int j = 0; j < itf.getEndpointCount(); j++) {
                UsbEndpoint ep = itf.getEndpoint(j);
                if (ep.getDirection() != UsbConstants.USB_DIR_IN) {
                    continue;
                }
                Reader r = new Reader(conn, ep, i);
                readers.add(r);
                r.start();
            }
        }

        if (readers.isEmpty()) {
            closeQuietly();
            fail(claimed == 0
                    ? "所有接口都被占用（claimInterface 失败）—— 多半已被厂商的 protocol 服务独占"
                    : "接口已 claim，但没有 IN 端点可读");
            return;
        }

        open = true;
        openedAt = SystemClock.uptimeMillis();
        status = String.format(Locale.US, "已连接 %s · 端点 %d", UsbTopology.safeName(d), readers.size());
        epSummary = summarize(readers, skipped);
        sink.onReady(true, status);
        startWatchdog();
    }

    private static String summarize(List<Reader> rs, int skipped) {
        StringBuilder sb = new StringBuilder();
        for (Reader r : rs) {
            if (sb.length() > 0) {
                sb.append("  ");
            }
            sb.append(r.name);
        }
        if (skipped > 0) {
            sb.append("  （").append(skipped).append(" 个接口被占用）");
        }
        return sb.toString();
    }

    /** 打开后 5 秒仍无数据，说明端点通着但没有内容，单独提示一次。 */
    private void startWatchdog() {
        new Thread(() -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                return;
            }
            if (!running || !open) {
                return;
            }
            long f = frames;
            if (f == 0) {
                sink.onIdle(0, "端点已打开，5 秒内没有任何数据 —— 设备可能在等其他握手，或该端点不是输入通道");
            }
        }, "usb-watchdog").start();
    }

    private void fail(String message) {
        running = false;
        open = false;
        status = message;
        safeReady(false, message);
    }

    private void safeReady(boolean ready, String detail) {
        try {
            sink.onReady(ready, detail);
        } catch (Exception ignored) {
        }
    }

    private void closeQuietly() {
        for (Reader r : new ArrayList<>(readers)) {
            r.halt();
        }
        readers.clear();
        UsbDeviceConnection c = connection;
        connection = null;
        if (c != null) {
            try {
                for (int i = 0; i < (device == null ? 0 : device.getInterfaceCount()); i++) {
                    try {
                        c.releaseInterface(device.getInterface(i));
                    } catch (Exception ignored) {
                    }
                }
            } finally {
                try {
                    c.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ───────────────────────── 端点读线程 ─────────────────────────

    private final class Reader extends Thread {

        final UsbDeviceConnection conn;
        final UsbEndpoint ep;
        final int iface;
        final String name;
        private volatile boolean alive = true;
        private int errors;

        Reader(UsbDeviceConnection conn, UsbEndpoint ep, int iface) {
            super("usb-ep-" + Integer.toHexString(ep.getAddress()));
            this.conn = conn;
            this.ep = ep;
            this.iface = iface;
            this.name = String.format(Locale.US, "0x%02X/%s/%dB",
                    ep.getAddress(), UsbTopology.typeName(ep.getType()).trim(),
                    ep.getMaxPacketSize());
        }

        void halt() {
            alive = false;
        }

        @Override
        public void run() {
            int size = Math.max(64, ep.getMaxPacketSize() * 16);
            byte[] buf = new byte[size];
            while (alive && running) {
                int n;
                try {
                    n = conn.bulkTransfer(ep, buf, size, READ_TIMEOUT_MS);
                } catch (Exception e) {
                    alive = false;
                    sink.onError("端点 " + name + " 读取出错：" + e.getClass().getSimpleName());
                    return;
                }
                if (n > 0) {
                    errors = 0;
                    frames++;
                    byte[] copy = new byte[n];
                    System.arraycopy(buf, 0, copy, 0, n);
                    try {
                        sink.onFrame(ep.getAddress(), name, copy, n, SystemClock.uptimeMillis());
                    } catch (Exception ignored) {
                    }
                } else if (n < 0) {
                    // -1 多数是超时（正常），只有「一次都没读到还连续失败」才当成故障
                    if (++errors == 60 && frames() == 0) {
                        sink.onIdle(0, "端点 " + name + " 连续超时 —— 该端点可能只支持 interrupt 事务，或速率与预期不符");
                    }
                }
            }
        }
    }

    // ───────────────────────── 供界面直接读的诊断 ─────────────────────────

    /** 权限侧取证：说明为什么打不开。 */
    public String diagnose() {
        return UsbPermission.diagnose(context);
    }

    /** 一行状态，直接上屏。 */
    public String summary() {
        return String.format(Locale.US, "%s · 帧 %,d · 端点 %s",
                status, frames, epSummary);
    }
}
