package local.rc2.controls;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.ViewFlipper;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import local.rc2.xbox.R;
import org.json.JSONObject;

/**
 * 仪表盘宿主：布局由 XML 声明，这里只负责把 InputMonitor 的状态写进控件。
 * 三页共用 ViewFlipper：仪表盘 / 原始报文 / 推送探针。
 */
public class MainActivity extends Activity implements Choreographer.FrameCallback {

    private static final int PAGE_DASH = 0;
    private static final int PAGE_RAW = 1;
    private static final int PAGE_PROBE = 2;
    private static final int PAGE_SCAN = 3;

    private ViewFlipper pager;
    private TextView stateText;
    private TextView rateText;
    private TextView statusText;
    private TextView keyC1;
    private TextView keyC2;
    private TextView keyRecord;
    private TextView keyShutter;
    private TextView keyRth;
    private StickView stickLeft;
    private StickView stickRight;
    private TextView stickLeftValue;
    private TextView stickRightValue;
    private DialView dialLeft;
    private DialView dialRight;
    private TextView dialLeftValue;
    private TextView dialRightValue;
    private View modeC;
    private View modeN;
    private View modeS;
    private TextView modeRaw;
    private TextView channelText;
    private TextView logText;
    private ScrollView logScroll;
    private TextView rawSummary;
    private TextView rawText;
    private TextView probeSummary;
    private TextView probeSigs;
    private TextView probeRing;
    private ScrollView probeScroll;
    private TextView cmdSummary;
    private TextView scanText;
    private ScrollView scanScroll;
    private TextView btnScanToggle;

    private final float[] smooth = new float[6];
    private final HashSet<String> held = new HashSet<>();
    private boolean animating;
    private long lastFrame;
    private long drawWindow;
    private long logWindow;
    private long pageWindow;
    private int draws;
    private long priorSamples;
    private long sampleWindow;
    private float fps;
    private float inputHz;
    private long seenKeyRevision = -1;
    private long logRevision = -1;
    private long rawRevision = -1;
    private long probeSeen = -1;
    private long modeSeen = -1;
    private long cmdSeen = -1;
    private String lastStickL = "";
    private String lastStickR = "";
    private String lastDialL = "";
    private String lastDialR = "";
    private String lastChannel = "";
    private String lastMode = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 必须在 setContentView 之前：把 density 归一化成 1dp = 1 设计像素，
        // XML 里的数值就是 1920×1080 画布上的像素，任何屏幕密度都不会变形。
        Ui.normalizeDensity(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        bind();
        pager.setInAnimation(null);
        pager.setOutAnimation(null);
        applyFullscreen();

        findViewById(R.id.btnRaw).setOnClickListener(v -> show(PAGE_RAW));
        findViewById(R.id.btnProbe).setOnClickListener(v -> show(PAGE_PROBE));
        findViewById(R.id.btnBackFromRaw).setOnClickListener(v -> show(PAGE_DASH));
        findViewById(R.id.btnBackFromProbe).setOnClickListener(v -> show(PAGE_DASH));
        findViewById(R.id.btnBackToRaw).setOnClickListener(v -> show(PAGE_RAW));
        findViewById(R.id.btnToScan).setOnClickListener(v -> show(PAGE_SCAN));
        findViewById(R.id.btnBackToProbe).setOnClickListener(v -> show(PAGE_PROBE));
        findViewById(R.id.btnBackFromScan).setOnClickListener(v -> show(PAGE_DASH));
        findViewById(R.id.btnScanToggle).setOnClickListener(v -> {
            if (InputMonitor.cmdProbeRunning) {
                InputMonitor.stopCmdProbe();
            } else {
                InputMonitor.startCmdProbe();
            }
            cmdSeen = -1;
            refreshCmd();
        });
    }

    private void bind() {
        pager = findViewById(R.id.pager);
        stateText = findViewById(R.id.stateText);
        rateText = findViewById(R.id.rateText);
        statusText = findViewById(R.id.statusText);
        keyC1 = findViewById(R.id.keyC1);
        keyC2 = findViewById(R.id.keyC2);
        keyRecord = findViewById(R.id.keyRecord);
        keyShutter = findViewById(R.id.keyShutter);
        keyRth = findViewById(R.id.keyRth);
        stickLeft = findViewById(R.id.stickLeft);
        stickRight = findViewById(R.id.stickRight);
        stickLeftValue = findViewById(R.id.stickLeftValue);
        stickRightValue = findViewById(R.id.stickRightValue);
        dialLeft = findViewById(R.id.dialLeft);
        dialRight = findViewById(R.id.dialRight);
        dialLeftValue = findViewById(R.id.dialLeftValue);
        dialRightValue = findViewById(R.id.dialRightValue);
        modeC = findViewById(R.id.modeC);
        modeN = findViewById(R.id.modeN);
        modeS = findViewById(R.id.modeS);
        modeRaw = findViewById(R.id.modeRaw);
        channelText = findViewById(R.id.channelText);
        logText = findViewById(R.id.logText);
        logScroll = findViewById(R.id.logScroll);
        rawSummary = findViewById(R.id.rawSummary);
        rawText = findViewById(R.id.rawText);
        probeSummary = findViewById(R.id.probeSummary);
        probeSigs = findViewById(R.id.probeSigs);
        probeRing = findViewById(R.id.probeRing);
        probeScroll = findViewById(R.id.probeScroll);
        cmdSummary = findViewById(R.id.scanSummary);
        scanText = findViewById(R.id.scanText);
        scanScroll = findViewById(R.id.scanScroll);
        btnScanToggle = findViewById(R.id.btnScanToggle);
    }
    private void applyFullscreen() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void show(int page) {
        pager.setDisplayedChild(page);
        if (page == PAGE_SCAN) {
            cmdSeen = -1;
            refreshCmd();
        }
        if (page == PAGE_RAW) {
            rawRevision = -1;
            refreshRaw();
        } else if (page == PAGE_PROBE) {
            probeSeen = -1;
            refreshProbe();
        }
    }

    @Override
    public void onBackPressed() {
        if (pager.getDisplayedChild() != PAGE_DASH) {
            show(PAGE_DASH);
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        InputMonitor.record(event);
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onStart() {
        super.onStart();
        InputMonitor.start();
        cmdSeen = -1;
    }

    @Override
    protected void onStop() {
        InputMonitor.stop();
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!animating) {
            animating = true;
            lastFrame = 0;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    @Override
    protected void onPause() {
        animating = false;
        Choreographer.getInstance().removeFrameCallback(this);
        super.onPause();
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!animating) {
            return;
        }
        float dt = lastFrame == 0 ? (1f / 60f)
                : Math.min(0.05f, (frameTimeNanos - lastFrame) / 1e9f);
        lastFrame = frameTimeNanos;
        float alpha = 1f - (float) Math.exp(-dt / 0.02f);

        int[] ch = InputMonitor.channels;
        for (int i = 0; i < InputMonitor.CHANNELS; i++) {
            float target = norm(ch[i]);
            smooth[i] += (target - smooth[i]) * alpha;
            if (Math.abs(target - smooth[i]) < 0.0005f) {
                smooth[i] = target;
            }
        }

        long now = SystemClock.uptimeMillis();
        int page = pager.getDisplayedChild();
        if (page == PAGE_DASH) {
            updateDashboard(ch, now);
        }
        if (now - logWindow >= 250) {
            logWindow = now;
            refreshLog();
        }
        if (now - pageWindow >= 250) {
            pageWindow = now;
            if (page == PAGE_RAW) {
                refreshRaw();
            } else if (page == PAGE_PROBE) {
                refreshProbe();
            } else if (page == PAGE_SCAN) {
                refreshCmd();
            }
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void updateDashboard(int[] ch, long now) {
        if (drawWindow == 0) {
            drawWindow = now;
            sampleWindow = now;
            priorSamples = InputMonitor.samples;
        }
        draws++;
        if (now - drawWindow >= 1000) {
            fps = draws * 1000f / (now - drawWindow);
            draws = 0;
            drawWindow = now;
            inputHz = (InputMonitor.samples - priorSamples) * 1000f
                    / Math.max(1L, now - sampleWindow);
            priorSamples = InputMonitor.samples;
            sampleWindow = now;
            rateText.setText(String.format(Locale.US, "%.0f FPS / %.0f Hz", fps, inputHz));
        }

        boolean live = InputMonitor.live();
        stateText.setText(live ? "● 本机实时输入" : "● 等待 MCU");
        stateText.setTextColor(live ? 0xff88ffe2 : 0xffffc46e);
        String st = InputMonitor.status;
        if (!st.equals(statusText.getText().toString())) {
            statusText.setText(st);
        }

        stickLeft.setPosition(smooth[3], -smooth[2]);
        stickRight.setPosition(smooth[0], -smooth[1]);
        String l = String.format(Locale.US, "X %s  Y %s", signed(smooth[3]), signed(-smooth[2]));
        if (!l.equals(lastStickL)) {
            lastStickL = l;
            stickLeftValue.setText(l);
        }
        String r = String.format(Locale.US, "X %s  Y %s", signed(smooth[0]), signed(-smooth[1]));
        if (!r.equals(lastStickR)) {
            lastStickR = r;
            stickRightValue.setText(r);
        }

        dialLeft.setValue(smooth[4]);
        dialRight.setValue(smooth[5]);
        String dl = dialText(ch[4]);
        if (!dl.equals(lastDialL)) {
            lastDialL = dl;
            dialLeftValue.setText(dl);
        }
        String dr = dialText(ch[5]);
        if (!dr.equals(lastDialR)) {
            lastDialR = dr;
            dialRightValue.setText(dr);
        }

        String chText = String.format(Locale.US, "RX %d RY %d LY %d LX %d LT %d RT %d",
                ch[0], ch[1], ch[2], ch[3], ch[4], ch[5]);
        if (!chText.equals(lastChannel)) {
            lastChannel = chText;
            channelText.setText(chText);
        }

        refreshKeys();
        refreshMode();
    }

    /** 带符号两位小数，顺带把 -0.00 归一成 +0.00。 */
    private String signed(float v) {
        if (Math.abs(v) < 0.005f) {
            v = 0f;
        }
        return String.format(Locale.US, "%+.2f", v);
    }

    private String dialText(int raw) {        int byteVal = Math.max(0, Math.min(255, (int) (((norm(raw) + 1f) / 2f) * 255f)));
        return String.format(Locale.US, "0x%02X (%d)  MCU %d", byteVal, byteVal, raw);
    }

    private void refreshKeys() {
        long rev = InputMonitor.keyRevision;
        if (rev == seenKeyRevision) {
            return;
        }
        seenKeyRevision = rev;
        held.clear();
        synchronized (InputMonitor.class) {
            for (JSONObject j : InputMonitor.observed.values()) {
                if (!InputMonitor.pressed.contains(j.optString("id"))) {
                    continue;
                }
                String name = j.optString("keyName");
                if (!name.isEmpty()) {
                    held.add(name);
                }
                String def = InputMonitor.defaultLabel(j.optInt("keyCode", -1));
                if (!def.isEmpty()) {
                    held.add(def);
                }
            }
        }
        keyC1.setSelected(held.contains("C1"));
        keyC2.setSelected(held.contains("C2"));
        keyRecord.setSelected(held.contains("录像"));
        keyShutter.setSelected(held.contains("拍照"));
        keyRth.setSelected(held.contains("返航/暂停"));
    }

    private void refreshMode() {
        long rev = InputMonitor.modeRevision;
        if (rev == modeSeen) {
            return;
        }
        modeSeen = rev;
        String m = InputMonitor.modeLabel;
        if (!m.equals(lastMode)) {
            lastMode = m;
            modeC.setSelected("C".equals(m));
            modeN.setSelected("N".equals(m));
            modeS.setSelected("S".equals(m));
        }
        if (InputMonitor.modeByte < 0) {
            modeRaw.setText("b2 —");
        } else {
            modeRaw.setText(String.format(Locale.US, "b2 %02X · %s",
                    InputMonitor.modeByte, InputMonitor.modeSeen()));
        }
    }

    private void refreshLog() {
        long rev = UiLog.revision;
        if (rev == logRevision) {
            return;
        }
        logRevision = rev;
        List<UiLog.Entry> all = UiLog.snapshot();
        int start = Math.max(0, all.size() - 150);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = start; i < all.size(); i++) {
            UiLog.Entry e = all.get(i);
            int from = sb.length();
            sb.append(e.line).append('\n');
            int color;
            if (e.level == 'E') {
                color = 0xffff9a9a;
            } else if (e.level == 'W') {
                color = 0xffffc46e;
            } else if ("Key".equals(e.tag) || "MCU".equals(e.tag) || "Mode".equals(e.tag)) {
                color = 0xff88ffe2;
            } else {
                color = 0xffb6c8e6;
            }
            sb.setSpan(new ForegroundColorSpan(color), from, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        boolean atBottom = !logScroll.canScrollVertically(1);
        logText.setText(sb);
        if (atBottom) {
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void refreshRaw() {
        long rev = InputMonitor.payloadRevision;
        if (rev == rawRevision) {
            return;
        }
        rawRevision = rev;
        byte[] payload = InputMonitor.payload();
        rawSummary.setText(String.format(Locale.US, "总帧 %d · %d 字节 · 变化 %s",
                InputMonitor.frameCount, payload.length, InputMonitor.lastDiff));
        if (payload.length == 0) {
            rawText.setText("尚未收到按键报文");
            return;
        }

        List<byte[]> history = InputMonitor.frameHistory();
        HashSet<Integer> changed = new HashSet<>();
        if (history.size() >= 2) {
            byte[] prev = history.get(history.size() - 2);
            byte[] cur = history.get(history.size() - 1);
            int n = Math.min(prev.length, cur.length);
            for (int i = 0; i < n; i++) {
                if (prev[i] != cur[i]) {
                    changed.add(i);
                }
            }
        }

        SpannableStringBuilder sb = new SpannableStringBuilder();
        final int warm = 0xffffd48a;

        sb.append("十六进制\n");
        int perRow = 16;
        for (int r = 0; r * perRow < payload.length; r++) {
            sb.append(String.format(Locale.US, "%03X  ", r * perRow));
            for (int col = 0; col < perRow; col++) {
                int i = r * perRow + col;
                if (i >= payload.length) {
                    break;
                }
                int from = sb.length();
                sb.append(String.format(Locale.US, "%02X ", payload[i] & 0xff));
                if (changed.contains(i)) {
                    sb.setSpan(new ForegroundColorSpan(warm), from, sb.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            sb.append('\n');
        }

        sb.append("\n逐位（低位在右）\n");
        for (int i = 0; i < payload.length; i++) {
            int v = payload[i] & 0xff;
            String bits = String.format(Locale.US, "%8s",
                    Integer.toBinaryString(v | 0x100)).substring(1);
            int from = sb.length();
            sb.append(String.format(Locale.US, "b%-2d  %s  %02X\n", i, bits, v));
            if (changed.contains(i)) {
                sb.setSpan(new ForegroundColorSpan(warm), from, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        sb.append("\n变化帧（最新在上）\n");
        int shown = Math.min(history.size(), 16);
        for (int r = 0; r < shown; r++) {
            byte[] f = history.get(history.size() - 1 - r);
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < f.length; i++) {
                if (i > 0) {
                    line.append(' ');
                }
                line.append(String.format(Locale.US, "%02X", f[i] & 0xff));
            }
            sb.append(line).append('\n');
        }
        sb.append("\n现有解析 mask = b0&0x70 | (b2&0x0C)<<8 | (b1&0x01)<<16，拍照位 0x40\n");
        sb.append("飞行模式取 17 字节报文的 b2 低位\n");

        rawText.setText(sb);
    }

    private void refreshProbe() {
        long rev = InputMonitor.probeRevision;
        if (rev == probeSeen) {
            return;
        }
        probeSeen = rev;
        List<InputMonitor.Sig> sigs = InputMonitor.signatures();
        List<InputMonitor.Push> ring = InputMonitor.pushes();
        probeSummary.setText(String.format(Locale.US, "总推送 %d · 签名 %d 类 · 缓存 %d 条",
                InputMonitor.pushTotal, sigs.size(), ring.size()));

        StringBuilder sb = new StringBuilder();
        for (InputMonitor.Sig s : sigs) {
            sb.append(String.format(Locale.US, "%-6s t%-3d %2dB ×%-4d\n      %s\n",
                    s.cmd(), s.type, s.len, s.count, s.hex));
        }
        probeSigs.setText(sb.length() == 0 ? "尚未收到任何推送包" : sb);

        StringBuilder r = new StringBuilder();
        int start = Math.max(0, ring.size() - 60);
        for (int i = start; i < ring.size(); i++) {
            InputMonitor.Push u = ring.get(i);
            r.append(String.format(Locale.US, "%7.1f %s t%-3d %2dB\n      %s\n",
                    u.at / 1000f, u.cmd(), u.type, u.len, u.hex));
        }
        probeRing.setText(r);
        probeScroll.post(() -> probeScroll.fullScroll(View.FOCUS_DOWN));
    }

    /**
     * cmd 枚举页：逐个试探 cmdSet=6 下的 cmdId，显示进度与命中的 cmd。
     * 仅在 revision 变化时重绘，避免每帧重建网格文本。
     */
    private void refreshCmd() {
        long rev = InputMonitor.cmdRevision;
        if (rev == cmdSeen) {
            return;
        }
        cmdSeen = rev;

        List<Integer> hits = InputMonitor.cmdHits();
        int total = InputMonitor.PROBE_TO - InputMonitor.PROBE_FROM + 1;
        int done = Math.max(0, Math.min(total,
                InputMonitor.cmdCursor - InputMonitor.PROBE_FROM + 1));

        cmdSummary.setText(String.format(Locale.US, "%s · %d/%d · 命中 %d · %d ms",
                InputMonitor.cmdState, done, total, hits.size(), InputMonitor.cmdElapsed));
        btnScanToggle.setText(InputMonitor.cmdProbeRunning ? "停止" : "开始扫描");

        if (hits.isEmpty()) {
            scanText.setText(InputMonitor.cmdProbeRunning
                    ? "正在逐个试探 cmdSet=6 的 cmdId，请稍候…"
                    : "尚未命中。点右上「开始扫描」枚举 cmdSet=6 / cmdId 0–4095。");
            return;
        }

        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            int start = sb.length();
            sb.append(String.format(Locale.US, "%-11s", InputMonitor.PROBE_CMD_SET + "/" + hits.get(i)));
            sb.setSpan(new ForegroundColorSpan(0xff88ffe2), start, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if ((i + 1) % 6 == 0) {
                sb.append('\n');
            }
        }
        if (hits.size() % 6 != 0) {
            sb.append('\n');
        }
        if (hits.size() >= total) {
            sb.append("\n全部 cmdId 都被接受，说明服务端不校验 cmd 是否存在，\n此路无法区分真实通道。");
        }
        scanText.setText(sb);
    }

    private float norm(int v) {
        return Math.max(-1f, Math.min(1f, (v - 1024) / 660f));
    }
}
