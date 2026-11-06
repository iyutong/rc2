package cn.iyutong.rc2core;

import android.content.Context;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import cn.iyutong.rc2core.internal.ButtonCodec;
import cn.iyutong.rc2core.usb.DjiFrame;
import cn.iyutong.rc2core.usb.UsbBitLab;
import cn.iyutong.rc2core.usb.UsbDecoder;
import cn.iyutong.rc2core.usb.UsbLink;
import cn.iyutong.rc2core.usb.UsbTopology;

/**
 * USB 输入通道的完整流水线，对外只有「开 / 关 / 读最新值 / 看报告」四个动作。
 *
 * <pre>
 *   UsbLink ──原始字节──> DjiFrame.Splitter ──帧──> UsbBitLab（位学习）
 *      │                        │                      └─> ShutterProbe（半按）
 *      │                        └──> UsbDecoder ──> 摇杆 / 按键 / 挡位
 *      └── 拓扑、端点、帧率、错误 ──> 报告
 * </pre>
 *
 * <p>与之前的协议通道最大的不同：数据源是 {@code android.hardware.usb}，不依赖任何厂商
 * Binder 服务。代价是字节流没有字段说明，所以配了位学习器来定位半按。
 */
public final class UsbInput {

    /** 上层接收点。 */
    public interface Sink {
        void onSticks(int[] channels);

        void onButtons(byte[] payload, int mask);

        void onMode(int modeByte);

        void onReady(boolean ready, String detail);

        void onError(String message);
    }

    private final Context context;
    private final Sink sink;
    /** 学习器由控制器持有，通道重开时保留已锁定的位。 */
    private final UsbBitLab lab;
    private final Map<Integer, DjiFrame.Splitter> splitters = new HashMap<>();
    private final List<String> log = new ArrayList<>();

    private volatile UsbLink link;
    private volatile UsbDecoder decoder;
    private volatile boolean running;
    private volatile String status = "未启动";
    private volatile String lastFrame = "—";
    private volatile long frames;
    private volatile long lastFrameAt;
    private volatile boolean ready;

    public UsbInput(Context context, Sink sink) {
        this(context, sink, new UsbBitLab());
    }

    public UsbInput(Context context, Sink sink, UsbBitLab lab) {
        this.context = context;
        this.sink = sink;
        this.lab = lab == null ? new UsbBitLab() : lab;
        this.decoder = new UsbDecoder(new DecoderSink());
    }

    // ───────────────────────── 生命周期 ─────────────────────────

    public void start() {
        if (running) {
            return;
        }
        running = true;
        status = "打开 USB…";
        UsbLink l = new UsbLink(context, new LinkSink());
        link = l;
        l.start();
    }

    public void stop() {
        running = false;
        UsbLink l = link;
        link = null;
        if (l != null) {
            l.stop();
        }
        ready = false;
        status = "已停止";
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isReady() {
        return ready;
    }

    public String status() {
        return status;
    }

    public UsbBitLab lab() {
        return lab;
    }

    public long frames() {
        return frames;
    }

    /** 最近一帧的十六进制，最多 32 字节。 */
    public String lastFrame() {
        return lastFrame;
    }

    public long lastFrameAt() {
        return lastFrameAt;
    }

    /** 最近一秒有没有数据。 */
    public boolean isLive() {
        return running && android.os.SystemClock.uptimeMillis() - lastFrameAt < 1000;
    }

    // ───────────────────────── 数据通路 ─────────────────────────

    private final class LinkSink implements UsbLink.Sink {

        @Override
        public void onReady(boolean ok, String detail) {
            ready = ok;
            status = detail;
            note((ok ? "" : "✗ ") + detail);
            UsbInput.this.sink.onReady(ok, detail);
        }

        @Override
        public void onFrame(int epAddr, String epName, byte[] data, int len, long at) {
            frames++;
            lastFrameAt = at;
            if (frames <= 1 || frames % 60 == 0) {
                lastFrame = hex(data, len);
            }
            DjiFrame.Splitter sp = splitters.get(epAddr);
            if (sp == null) {
                sp = new DjiFrame.Splitter();
                splitters.put(epAddr, sp);
            }
            List<DjiFrame.Frame> got;
            try {
                got = sp.feed(data, len);
            } catch (Exception e) {
                note("切分出错：" + e.getClass().getSimpleName());
                return;
            }
            if (got.isEmpty()) {
                // 切不出帧时，原始包也参与位学习（有些通道就是裸结构，没有 0x55）
                lab.feed(len, data, len, at);
                return;
            }
            for (DjiFrame.Frame f : got) {
                lab.feed(f.raw.length, f.raw, f.raw.length, at);
                try {
                    decoder.feed(f);
                } catch (Exception e) {
                    note("解码出错：" + e.getClass().getSimpleName());
                }
            }
        }

        @Override
        public void onIdle(long f, String detail) {
            note(detail);
        }

        @Override
        public void onError(String message) {
            note("✗ " + message);
            UsbInput.this.sink.onError(message);
        }
    }

    private final class DecoderSink implements UsbDecoder.Sink {

        @Override
        public void onButtons(byte[] payload, int mask, String source) {
            sink.onButtons(payload, mask);
        }

        @Override
        public void onSticks(int[] channels, String source) {
            sink.onSticks(channels);
        }

        @Override
        public void onMode(int modeByte, String source) {
            sink.onMode(modeByte);
        }
    }

    private void note(String line) {
        if (line == null || line.isEmpty()) {
            return;
        }
        synchronized (log) {
            log.add(line);
            while (log.size() > 40) {
                log.remove(0);
            }
        }
    }

    // ───────────────────────── 报告 ─────────────────────────

    /** USB 实验室整屏内容：拓扑 + 通道 + 帧 + 学习。 */
    public String report() {
        StringBuilder sb = new StringBuilder(4096);
        UsbLink l = link;

        sb.append("═══ 通道 ═══\n");
        if (l == null) {
            sb.append("未启动\n");
        } else {
            sb.append(l.status()).append('\n');
            sb.append("端点：").append(l.endpoints()).append('\n');
            sb.append(String.format(Locale.US, "累计帧 %,d · 最近一帧 %s%n", frames, lastFrame));
            sb.append("解出：").append(decoder.stats()).append('\n');
            if (l.device() != null) {
                sb.append("设备：").append(UsbTopology.safeName(l.device()))
                        .append(String.format(Locale.US, " %04X:%04X%n",
                                l.device().getVendorId(), l.device().getProductId()));
            }
        }

        sb.append("\n═══ 最近事件 ═══\n");
        synchronized (log) {
            if (log.isEmpty()) {
                sb.append("（无）\n");
            } else {
                int from = Math.max(0, log.size() - 12);
                for (int i = from; i < log.size(); i++) {
                    sb.append("  ").append(log.get(i)).append('\n');
                }
            }
        }

        sb.append('\n').append(lab.report());
        return sb.toString();
    }

    private static String hex(byte[] b, int len) {
        int n = Math.min(Math.min(len, b.length), 32);
        StringBuilder sb = new StringBuilder(n * 3);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format(Locale.US, "%02X", b[i] & 0xff));
        }
        if (len > n) {
            sb.append(" …(").append(len).append("B)");
        }
        return sb.toString();
    }

    /** 一次性的完整普查，含内核 keybit。开销约几十毫秒，只在进入页面时调。 */
    public String topologyReport() {
        return UsbTopology.report(context);
    }
}
