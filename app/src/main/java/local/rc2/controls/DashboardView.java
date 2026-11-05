package local.rc2.controls;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

/**
 * 只读仪表盘：绘制左右摇杆、拨轮、五个功能键以及运行日志。
 * 底部日志区支持上下拖动翻看历史。
 */
public final class DashboardView extends View implements Choreographer.FrameCallback {

    private static final int LOG_LINES = 3;
    private static final float LOG_TOP = 1002f;
    private static final float LOG_STEP = 22f;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface fixed = Typeface.MONOSPACE;
    private final HashSet<String> held = new HashSet<>();
    private final float[] smooth = new float[6];
    private final List<UiLog.Entry> logEntries = new ArrayList<>();
    private long seenRevision = -1;
    private Bitmap backdrop;
    private float scale = 1f;
    private float ox;
    private float oy;
    private boolean animating;
    private boolean dirty = true;
    private long lastFrame;
    private long drawWindow;
    private long logWindow;
    private int draws;
    private float fps;
    private long priorSamples;
    private long sampleWindow;
    private float inputHz;
    private long logRevision = -1;
    private int logOffset;
    private int logOffsetSeen = -1;
    private int logSize;
    private boolean draggingLog;
    private float dragY;
    private boolean rawPage;
    private Bitmap rawBackdrop;
    private long rawRevision = -1;
    private byte[] rawPayload = new byte[0];
    private String rawDiff = "—";
    private List<byte[]> rawHistory = new ArrayList<>();
    private final HashSet<Integer> changedBytes = new HashSet<>();
    private final int white = 0xffeff5ff;
    private final int muted = 0xffacbfdf;
    private final int mint = 0xff88ffe2;

    public DashboardView(Context ctx) {
        super(ctx);
        setFocusable(true);
        setContentDescription("实时按键与摇杆面板");
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startFrames();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopFrames();
        if (backdrop != null) {
            backdrop.recycle();
            backdrop = null;
        }
        if (rawBackdrop != null) {
            rawBackdrop.recycle();
            rawBackdrop = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE && isAttachedToWindow()) {
            startFrames();
        } else {
            stopFrames();
        }
    }

    private void startFrames() {
        if (!animating) {
            animating = true;
            lastFrame = 0;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    private void stopFrames() {
        animating = false;
        Choreographer.getInstance().removeFrameCallback(this);
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!animating) {
            return;
        }
        float dt = lastFrame == 0 ? (1f / 60f) : Math.min(0.05f, (frameTimeNanos - lastFrame) / 1e9f);
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
        invalidate();
        Choreographer.getInstance().postFrameCallback(this);
    }

    private float norm(int v) {
        return Math.max(-1f, Math.min(1f, (v - 1024) / 660f));
    }

    private void text(Canvas c, String s, float x, float y, float size, int color, Paint.Align align) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        p.setTextAlign(align);
        p.setTextSize(size);
        p.setTypeface(regular);
        c.drawText(s, x, y, p);
    }

    private void mono(Canvas c, String s, float x, float y, float size) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(white);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(size);
        p.setTypeface(fixed);
        c.drawText(s, x, y, p);
    }

    private void round(Canvas c, float l, float t, float r, float b, float radius, int color) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawRoundRect(l, t, r, b, radius, radius, p);
    }

    private void line(Canvas c, float x1, float y1, float x2, float y2, int color, float width) {
        p.setShader(null);
        p.setColor(color);
        p.setStrokeWidth(width);
        p.setStyle(Paint.Style.STROKE);
        c.drawLine(x1, y1, x2, y2, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void metadata() {
        long rev = InputMonitor.keyRevision;
        if (!dirty && rev == seenRevision) {
            return;
        }
        seenRevision = rev;
        dirty = false;
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
    }

    private void key(Canvas c, String label, float cx, float cy, float w, float h) {
        boolean down = held.contains(label);
        float l = cx - w / 2f;
        float t = cy - h / 2f;
        float r = cx + w / 2f;
        float b = cy + h / 2f;
        round(c, l, t, r, b, 16f, down ? mint : 0xff42629c);
        p.setColor(down ? 0xffcafff3 : 0xff7192c5);
        p.setStrokeWidth(1.5f);
        p.setStyle(Paint.Style.STROKE);
        c.drawRoundRect(l, t, r, b, 16f, 16f, p);
        p.setStyle(Paint.Style.FILL);
        text(c, label, cx, cy + 10f, 32f, down ? 0xff133648 : white, Paint.Align.CENTER);
    }

    private void allKeys(Canvas c) {
        key(c, "C1", 420f, 265f, 220f, 89f);
        key(c, "C2", 1500f, 265f, 220f, 89f);
        key(c, "录像", 960f, 268f, 172f, 91f);
        key(c, "拍照", 960f, 388f, 172f, 91f);
        key(c, "返航/暂停", 960f, 650f, 235f, 100f);
    }

    private void disk(Canvas c, String title, float cx) {
        text(c, title, cx, 369f, 29f, white, Paint.Align.CENTER);
        p.setShader(new RadialGradient(
                cx - 55f, 515f, 310f,
                new int[]{0xff6588c4, 0xff36588e},
                null,
                Shader.TileMode.CLAMP));
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, 575f, 170f, p);
        p.setShader(null);
        p.setStrokeWidth(2f);
        p.setColor(0xff9bbcf0);
        p.setStyle(Paint.Style.STROKE);
        c.drawCircle(cx, 575f, 170f, p);
        p.setColor(0x607fa2d7);
        c.drawCircle(cx, 575f, 85f, p);
        p.setStyle(Paint.Style.FILL);
        line(c, cx - 170f, 575f, cx + 170f, 575f, 0x808fb4eb, 1.5f);
        line(c, cx, 405f, cx, 745f, 0x808fb4eb, 1.5f);
    }

    private void dialBase(Canvas c, String title, float cx) {
        text(c, title, cx, 879f, 26f, white, Paint.Align.CENTER);
        float l = cx - 180f;
        float r = cx + 180f;
        round(c, l, 898f, r, 927f, 5f, 0xff284775);
        p.setColor(0xff82a9dc);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f);
        c.drawRoundRect(l, 898f, r, 927f, 5f, 5f, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void buildBackdrop() {
        if (backdrop != null) {
            backdrop.recycle();
        }
        backdrop = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(backdrop);
        p.setShader(new LinearGradient(
                0f, 0f, 1920f, 1080f,
                new int[]{0xff283f6e, 0xff34598f, 0xff20385e},
                null,
                Shader.TileMode.CLAMP));
        c.drawRect(0f, 0f, 1920f, 1080f, p);
        p.setShader(null);
        text(c, "RC2  /  输入监视", 65f, 73f, 31f, white, Paint.Align.LEFT);
        line(c, 65f, 122f, 1855f, 122f, 0x407ba3df, 1f);
        line(c, 65f, LOG_TOP, 1855f, LOG_TOP, 0x407ba3df, 1f);
        round(c, 90f, 160f, 750f, 1000f, 28f, 0x0aebf5ff);
        round(c, 1170f, 160f, 1830f, 1000f, 28f, 0x0aebf5ff);
        text(c, "左侧按键", 420f, 195f, 28f, white, Paint.Align.CENTER);
        text(c, "功能按键", 960f, 195f, 28f, white, Paint.Align.CENTER);
        text(c, "右侧按键", 1500f, 195f, 28f, white, Paint.Align.CENTER);
        allKeys(c);
        disk(c, "左摇杆", 420f);
        disk(c, "右摇杆", 1500f);
        round(c, 765f, 726f, 1155f, 992f, 18f, 0x12ebf5ff);
        text(c, "原始数据 · 点按查看报文", 960f, 758f, 20f, muted, Paint.Align.CENTER);
        line(c, 795f, 782f, 1125f, 782f, 0x3a7ba3df, 1f);
        line(c, 800f, 872f, 1120f, 872f, 0x267ba3df, 1f);
        dialBase(c, "左拨轮", 420f);
        dialBase(c, "右拨轮", 1500f);
    }

    private void stick(Canvas c, float cx, float x, float y, int rawX, int rawY) {
        float dx = x * 148f;
        float dy = y * 148f;
        float len = (float) Math.hypot(dx, dy);
        if (len > 148f) {
            float s = 148f / len;
            dx *= s;
            dy *= s;
        }
        p.setColor(0x2088ffe2);
        c.drawCircle(cx + dx, 575f + dy, 31f, p);
        p.setColor(mint);
        c.drawCircle(cx + dx, 575f + dy, 14f, p);
        p.setColor(white);
        c.drawCircle(cx + dx - 3f, 572f + dy, 5f, p);
        mono(c, String.format(Locale.US, "X: %+.2f    Y: %+.2f", x, y), cx, 793f, 25f);
        text(c, "原始 " + rawX + " / " + rawY, cx, 825f, 19f, muted, Paint.Align.CENTER);
    }

    private void dial(Canvas c, float cx, float v, int raw) {
        float frac = (v + 1f) / 2f;
        int byteVal = Math.max(0, Math.min(255, (int) (((norm(raw) + 1f) / 2f) * 255f)));
        float l = cx - 180f;
        round(c, l, 898f, l + frac * 360f, 927f, 5f, 0xffc7f3ff);
        line(c, cx, 895f, cx, 931f, mint, 2f);
        mono(c, String.format(Locale.US, "0x%02X  (%d)  ·  MCU %d", byteVal, byteVal, raw), cx, 962f, 22f);
    }

    private void raw(Canvas c, int[] ch) {
        String[] names = new String[]{"RX", "RY", "LY", "LX", "LT", "RT"};
        for (int i = 0; i < names.length; i++) {
            float x = 815f + (i % 3) * 140f;
            float y = 812f + (i / 3) * 34f;
            mono(c, String.format(Locale.US, "%s %4d", names[i], ch[i]), x, y, 19f);
        }
        JSONObject j = InputMonitor.lastEvent();
        if (j == null) {
            text(c, "尚未收到按键", 960f, 912f, 18f, muted, Paint.Align.CENTER);
            mono(c, "MCU " + InputMonitor.shortFrame(), 960f, 958f, 14f);
            return;
        }
        text(c, "键 " + j.optString("keyName"), 960f, 900f, 18f, white, Paint.Align.CENTER);
        text(c, "code " + j.optInt("keyCode", -1) + "  ·  scan " + j.optInt("scanCode", 0),
                960f, 926f, 18f, muted, Paint.Align.CENTER);
        text(c, j.optString("device"), 960f, 950f, 17f, muted, Paint.Align.CENTER);
        mono(c, "MCU " + InputMonitor.shortFrame(), 960f, 976f, 14f);
    }

    private void refreshLog() {
        List<UiLog.Entry> all = UiLog.snapshot();
        if (logOffset > 0 && all.size() > logSize) {
            logOffset += all.size() - logSize;
        }
        logSize = all.size();
        int max = Math.max(0, all.size() - LOG_LINES);
        logOffset = Math.max(0, Math.min(logOffset, max));
        int end = Math.max(0, all.size() - logOffset);
        int start = Math.max(0, end - LOG_LINES);
        logEntries.clear();
        for (int i = start; i < end; i++) {
            logEntries.add(all.get(i));
        }
    }

    private void logPanel(Canvas c) {
        if (logRevision != UiLog.revision || logOffsetSeen != logOffset) {
            logRevision = UiLog.revision;
            logOffsetSeen = logOffset;
            refreshLog();
        }
        if (logEntries.isEmpty()) {
            text(c, "暂无日志", 65f, 1024f, 17f, muted, Paint.Align.LEFT);
            return;
        }
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(fixed);
        p.setTextSize(16f);
        p.setTextAlign(Paint.Align.LEFT);
        for (int i = 0; i < logEntries.size(); i++) {
            UiLog.Entry e = logEntries.get(i);
            p.setColor(e.level == 'E' ? 0xffff9a9a : (e.level == 'W' ? 0xffffd48a : muted));
            c.drawText(clip(e.line, 110), 65f, 1024f + i * LOG_STEP, p);
        }
        if (logOffset > 0) {
            text(c, "已上翻 " + logOffset + " 条 · 向下拖动回到底部",
                    1845f, 992f, 15f, 0xffffd48a, Paint.Align.RIGHT);
        }
    }

    private String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = (e.getX() - ox) / scale;
        float y = (e.getY() - oy) / scale;
        if (rawPage) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP && x > 1650f && y < 115f) {
                rawPage = false;
                invalidate();
                return true;
            }
            return true;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (y > 950f) {
                    draggingLog = true;
                    dragY = y;
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    return true;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (draggingLog) {
                    int steps = (int) ((y - dragY) / LOG_STEP);
                    if (steps != 0) {
                        logOffset -= steps;
                        dragY = y;
                        invalidate();
                    }
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
                if (draggingLog) {
                    draggingLog = false;
                    dragY = 0f;
                    return true;
                }
                if (x > 765f && x < 1155f && y > 726f && y < 950f) {
                    rawPage = true;
                    rawRevision = -1;
                    invalidate();
                    return true;
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                if (draggingLog) {
                    draggingLog = false;
                    dragY = 0f;
                    return true;
                }
                break;
            default:
                break;
        }
        return super.onTouchEvent(e);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        InputMonitor.expireMcu();
        metadata();
        c.drawColor(0xff2c4b7b);
        scale = Math.min(getWidth() / 1920f, getHeight() / 1080f);
        ox = (getWidth() - scale * 1920f) / 2f;
        oy = (getHeight() - scale * 1080f) / 2f;
        c.save();
        c.translate(ox, oy);
        c.scale(scale, scale);
        p.setShader(null);
        p.setColor(white);
        p.setStyle(Paint.Style.FILL);
        if (rawPage) {
            drawRawPage(c);
        } else {
            drawDashboard(c);
        }
        c.restore();
    }

    private void drawDashboard(Canvas c) {
        if (backdrop == null) {
            buildBackdrop();
        }
        c.drawBitmap(backdrop, 0f, 0f, p);

        long now = SystemClock.uptimeMillis();
        draws++;
        if (drawWindow == 0) {
            drawWindow = now;
        }
        if (now - drawWindow >= 1000) {
            fps = draws * 1000f / (now - drawWindow);
            draws = 0;
            drawWindow = now;
            inputHz = (InputMonitor.samples - priorSamples) * 1000f / Math.max(1L, now - sampleWindow);
            priorSamples = InputMonitor.samples;
            sampleWindow = now;
            if (now - logWindow >= 5000) {
                logWindow = now;
                UiLog.i("Frames", String.format(Locale.US, "%.0f FPS · %.0f Hz", fps, inputHz));
            }
        }

        boolean live = InputMonitor.live();
        p.setColor(live ? mint : 0xffffc46e);
        c.drawCircle(661f, 63f, 6f, p);
        text(c, live ? "本机实时输入" : "等待 MCU", 680f, 71f, 22f, muted, Paint.Align.LEFT);
        text(c, String.format(Locale.US, "%.0f FPS  /  %.0f Hz", fps, inputHz), 1120f, 71f, 20f, muted, Paint.Align.RIGHT);
        text(c, clip(InputMonitor.status, 26), 1845f, 71f, 19f, muted, Paint.Align.RIGHT);

        allKeys(c);
        int[] ch = InputMonitor.channels;
        stick(c, 420f, smooth[3], -smooth[2], ch[3], ch[2]);
        stick(c, 1500f, smooth[0], -smooth[1], ch[0], ch[1]);
        dial(c, 420f, smooth[4], ch[4]);
        dial(c, 1500f, smooth[5], ch[5]);
        raw(c, ch);
        logPanel(c);
    }

    private void buildRawBackdrop() {
        if (rawBackdrop != null) {
            rawBackdrop.recycle();
        }
        rawBackdrop = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(rawBackdrop);
        p.setShader(new LinearGradient(
                0f, 0f, 1920f, 1080f,
                new int[]{0xff283f6e, 0xff34598f, 0xff20385e},
                null,
                Shader.TileMode.CLAMP));
        c.drawRect(0f, 0f, 1920f, 1080f, p);
        p.setShader(null);
        text(c, "RC2  /  按键原始报文", 65f, 73f, 31f, white, Paint.Align.LEFT);
        text(c, "返回仪表盘", 1845f, 71f, 22f, mint, Paint.Align.RIGHT);
        line(c, 65f, 122f, 1855f, 122f, 0x407ba3df, 1f);
        text(c, "当前帧 · 未解析字节", 65f, 170f, 24f, muted, Paint.Align.LEFT);
        text(c, "变化帧（最新在上）", 980f, 170f, 24f, muted, Paint.Align.LEFT);
        line(c, 65f, 186f, 1855f, 186f, 0x267ba3df, 1f);
        text(c, "黄色 = 与上一条变化帧不同的字节；b<索引>.<位> 中位 0 为最低位", 65f, 998f, 18f, muted, Paint.Align.LEFT);
        text(c, "现有解析 mask = b0&0x70 | (b2&0x0C)<<8 | (b1&0x01)<<16，拍照位 0x40", 65f, 1030f, 18f, muted, Paint.Align.LEFT);
    }

    private void refreshRaw() {
        rawPayload = InputMonitor.payload();
        rawDiff = InputMonitor.lastDiff;
        rawHistory = InputMonitor.frameHistory();
        changedBytes.clear();
        if (rawHistory.size() >= 2) {
            byte[] prev = rawHistory.get(rawHistory.size() - 2);
            byte[] cur = rawHistory.get(rawHistory.size() - 1);
            int n = Math.min(prev.length, cur.length);
            for (int i = 0; i < n; i++) {
                if (prev[i] != cur[i]) {
                    changedBytes.add(i);
                }
            }
        }
    }

    private void drawRawPage(Canvas c) {
        if (rawBackdrop == null) {
            buildRawBackdrop();
        }
        c.drawBitmap(rawBackdrop, 0f, 0f, p);
        if (rawRevision != InputMonitor.payloadRevision) {
            rawRevision = InputMonitor.payloadRevision;
            refreshRaw();
        }
        text(c, String.format(Locale.US, "总帧 %d · %d 字节 · 最近变化 %s",
                InputMonitor.frameCount, rawPayload.length, rawDiff),
                65f, 214f, 20f, white, Paint.Align.LEFT);
        if (rawPayload.length == 0) {
            text(c, "尚未收到按键报文", 65f, 270f, 22f, muted, Paint.Align.LEFT);
            return;
        }
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(fixed);
        p.setTextSize(20f);
        p.setTextAlign(Paint.Align.LEFT);
        int perRow = 12;
        int rows = (rawPayload.length + perRow - 1) / perRow;
        for (int r = 0; r < rows; r++) {
            float y = 264f + r * 34f;
            p.setColor(muted);
            c.drawText(String.format(Locale.US, "%02X", r * perRow), 65f, y, p);
            for (int col = 0; col < perRow; col++) {
                int i = r * perRow + col;
                if (i >= rawPayload.length) {
                    break;
                }
                p.setColor(changedBytes.contains(i) ? 0xffffd48a : white);
                c.drawText(String.format(Locale.US, "%02X", rawPayload[i] & 0xff),
                        125f + col * 46f, y, p);
            }
        }
        float binTop = 264f + rows * 34f + 18f;
        text(c, "逐位（低位在右）", 65f, binTop, 20f, muted, Paint.Align.LEFT);
        p.setTypeface(fixed);
        p.setTextSize(18f);
        p.setTextAlign(Paint.Align.LEFT);
        int n = Math.min(rawPayload.length, 12);
        for (int i = 0; i < n; i++) {
            int v = rawPayload[i] & 0xff;
            String bits = String.format(Locale.US, "%8s", Integer.toBinaryString(v | 0x100)).substring(1);
            p.setColor(changedBytes.contains(i) ? 0xffffd48a : white);
            c.drawText(String.format(Locale.US, "b%-2d  %s  %02X", i, bits, v),
                    65f, binTop + 26f + i * 28f, p);
        }
        p.setTypeface(fixed);
        p.setTextSize(19f);
        int shown = Math.min(rawHistory.size(), 14);
        for (int r = 0; r < shown; r++) {
            byte[] f = rawHistory.get(rawHistory.size() - 1 - r);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < f.length && i < 10; i++) {
                if (i > 0) {
                    sb.append(' ');
                }
                sb.append(String.format(Locale.US, "%02X", f[i] & 0xff));
            }
            if (f.length > 10) {
                sb.append(" …");
            }
            p.setColor(r == 0 ? white : muted);
            c.drawText(sb.toString(), 980f, 220f + r * 30f, p);
        }
        if (rawHistory.isEmpty()) {
            text(c, "还没有变化帧", 980f, 224f, 19f, muted, Paint.Align.LEFT);
        }
    }
}
