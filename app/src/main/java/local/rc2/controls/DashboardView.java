package local.rc2.controls;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.json.JSONObject;

public final class DashboardView extends View
        implements Choreographer.FrameCallback, SharedPreferences.OnSharedPreferenceChangeListener {

    public interface Actions {
        void action(String id);
    }

    private final Actions actions;
    private final SharedPreferences prefs;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface fixed = Typeface.MONOSPACE;
    private final LinkedHashMap<String, RectF> hits = new LinkedHashMap<>();
    private final HashMap<String, String> labelIds = new HashMap<>();
    private final HashMap<String, String> targets = new HashMap<>();
    private final HashSet<String> held = new HashSet<>();
    private final String[] labels = new String[]{"C1", "C2", "录像", "拍照", "返航/暂停"};
    private final float[] smooth = new float[6];
    private JSONObject storedLabels = new JSONObject();
    private JSONObject storedBindings = new JSONObject();
    private long seenRevision = -1;
    private Bitmap backdrop;
    private boolean baseOutput;
    private float scale = 1f;
    private float ox;
    private float oy;
    private boolean animating;
    private boolean dirty = true;
    private long lastFrame;
    private long drawWindow;
    private int draws;
    private float fps;
    private long priorSamples;
    private long sampleWindow;
    private float inputHz;
    private String lastKey = "等待按下实体按键";
    private final int white = 0xffeff5ff;
    private final int muted = 0xffacbfdf;
    private final int mint = 0xff88ffe2;

    public DashboardView(Context ctx, Actions actions) {
        super(ctx);
        this.actions = actions;
        prefs = ctx.getSharedPreferences("controls", 0);
        loadPrefs();
        prefs.registerOnSharedPreferenceChangeListener(this);
        setFocusable(true);
        setContentDescription("实时按键与摇杆面板");
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key.equals("labels") || key.equals("buttons")) {
            loadPrefs();
            dirty = true;
        }
    }

    private void loadPrefs() {
        try {
            storedLabels = new JSONObject(prefs.getString("labels", "{}"));
            storedBindings = new JSONObject(prefs.getString("buttons", "{}"));
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        prefs.registerOnSharedPreferenceChangeListener(this);
        startFrames();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopFrames();
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        if (backdrop != null) {
            backdrop.recycle();
            backdrop = null;
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
        int[] ch = ControlService.channels;
        for (int i = 0; i < 6; i++) {
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
        long rev = ControlService.keyRevision;
        if (!dirty && rev == seenRevision) {
            return;
        }
        seenRevision = rev;
        dirty = false;
        labelIds.clear();
        targets.clear();
        held.clear();
        Iterator<String> it = storedLabels.keys();
        while (it.hasNext()) {
            String k = it.next();
            labelIds.put(storedLabels.optString(k), k);
        }
        synchronized (ControlService.class) {
            for (Map.Entry<String, JSONObject> e : ControlService.observed.entrySet()) {
                String def = ControlService.defaultLabel(e.getValue().optInt("keyCode", -1));
                if (!def.isEmpty() && !labelIds.containsKey(def)) {
                    labelIds.put(def, e.getKey());
                }
            }
            for (Map.Entry<String, String> e : labelIds.entrySet()) {
                if (ControlService.pressed.contains(e.getValue())) {
                    held.add(e.getKey());
                }
                String target = storedBindings.optString(e.getValue(), "");
                if (!target.isEmpty()) {
                    targets.put(e.getKey(), target);
                }
            }
            lastKey = ControlService.lastKey();
        }
    }

    private void nav(Canvas c, String id, String label, float cx, float w, int bg) {
        float l = cx - w / 2f;
        float r = cx + w / 2f;
        hits.put(id, new RectF(l, 35f, r, 92f));
        round(c, l, 35f, r, 92f, 14f, bg);
        text(c, label, cx, 72f, 24f, white, Paint.Align.CENTER);
    }

    private void key(Canvas c, String label, float cx, float cy, float w, float h, boolean base) {
        boolean down = held.contains(label);
        boolean learning = label.equals(ControlService.learnLabel);
        String target = targets.get(label);
        if (!base && !down && !learning && target == null) {
            return;
        }
        if (base) {
            hits.put(label, new RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f));
        }
        float l = cx - w / 2f;
        float t = cy - h / 2f;
        float r = cx + w / 2f;
        float b = cy + h / 2f;
        int fill = (!base && down) ? mint : ((!base && learning) ? 0xff406ead : 0xff42629c);
        round(c, l, t, r, b, 16f, fill);
        p.setColor((!base && down) ? 0xffcafff3 : ((!base && learning) ? mint : 0xff7192c5));
        p.setStrokeWidth((!base && learning) ? 3f : 1.5f);
        p.setStyle(Paint.Style.STROKE);
        c.drawRoundRect(l, t, r, b, 16f, 16f, p);
        p.setStyle(Paint.Style.FILL);
        boolean hasTarget = !base && target != null;
        text(c, label, cx, cy + (hasTarget ? 0 : 10), 32f, (!base && down) ? 0xff133648 : white, Paint.Align.CENTER);
        if (hasTarget) {
            text(c, "→ " + target, cx, cy + 29f, 20f, down ? 0xff255a65 : 0xffadf0e4, Paint.Align.CENTER);
        }
    }

    private void allKeys(Canvas c, boolean base) {
        key(c, "C1", 420f, 265f, 220f, 89f, base);
        key(c, "C2", 1500f, 265f, 220f, 89f, base);
        key(c, "录像", 960f, 268f, 172f, 91f, base);
        key(c, "拍照", 960f, 388f, 172f, 91f, base);
        key(c, "返航/暂停", 960f, 650f, 235f, 100f, base);
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
        hits.clear();
        p.setShader(new LinearGradient(
                0f, 0f, 1920f, 1080f,
                new int[]{0xff283f6e, 0xff34598f, 0xff20385e},
                null,
                Shader.TileMode.CLAMP));
        c.drawRect(0f, 0f, 1920f, 1080f, p);
        p.setShader(null);
        text(c, "RC2  /  全局手柄", 65f, 73f, 31f, white, Paint.Align.LEFT);
        baseOutput = ControlService.output;
        nav(c, "toggle", baseOutput ? "暂停输出" : "启用输出", 1200f, 165f, baseOutput ? 0xff1b8c76 : 0xff476798);
        nav(c, "settings", "连接设置", 1433f, 164f, 0xff3e5c8d);
        nav(c, "test", "手柄检测", 1611f, 126f, 0xff3e5c8d);
        nav(c, "about", "关于", 1754f, 126f, 0xff3e5c8d);
        line(c, 65f, 122f, 1855f, 122f, 0x407ba3df, 1f);
        round(c, 90f, 160f, 750f, 1000f, 28f, 0x0aebf5ff);
        round(c, 1170f, 160f, 1830f, 1000f, 28f, 0x0aebf5ff);
        text(c, "左侧按键", 420f, 195f, 28f, white, Paint.Align.CENTER);
        text(c, "功能按键", 960f, 195f, 28f, white, Paint.Align.CENTER);
        text(c, "右侧按键", 1500f, 195f, 28f, white, Paint.Align.CENTER);
        allKeys(c, true);
        disk(c, "左摇杆", 420f);
        disk(c, "右摇杆", 1500f);
        text(c, "默认 C1→A / C2→B", 960f, 540f, 24f, muted, Paint.Align.CENTER);
        text(c, "点击按键设置 Xbox 绑定", 960f, 957f, 21f, muted, Paint.Align.CENTER);
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

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        metadata();
        if (backdrop == null || baseOutput != ControlService.output) {
            buildBackdrop();
        }
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
            inputHz = (ControlService.samples - priorSamples) * 1000f / Math.max(1L, now - sampleWindow);
            priorSamples = ControlService.samples;
            sampleWindow = now;
            Log.i("RC2Frames", "draw_fps=" + fps + " input_hz=" + inputHz);
        }

        boolean live = ControlService.instance != null && now - ControlService.sampleAt < 500;
        p.setColor(live ? mint : 0xffffc46e);
        c.drawCircle(661f, 63f, 6f, p);
        text(c, live ? "本机实时输入" : "等待连接", 680f, 71f, 22f, muted, Paint.Align.LEFT);
        text(c, String.format(Locale.US, "%.0f FPS  /  %.0f Hz", fps, inputHz), 1000f, 71f, 20f, muted, Paint.Align.RIGHT);

        allKeys(c, false);
        int[] ch = ControlService.channels;
        stick(c, 420f, smooth[3], -smooth[2], ch[3], ch[2]);
        stick(c, 1500f, smooth[0], -smooth[1], ch[0], ch[1]);
        dial(c, 420f, smooth[4], ch[4]);
        dial(c, 1500f, smooth[5], ch[5]);
        held.contains("右");
        held.contains("左");
        held.contains("下");
        held.contains("上");
        text(c, "拍照→RB / 录像→LB", 960f, 810f, 23f, muted, Paint.Align.CENTER);
        String learn = ControlService.learnLabel;
        text(c, learn == null ? lastKey : "请按实体 " + learn + "  ·  等待识别",
                65f, 1042f, 22f, learn == null ? muted : mint, Paint.Align.LEFT);
        text(c, (ControlService.output ? "全局输出中 · " : "已暂停 · ") + ControlService.localStatus,
                1845f, 1042f, 21f, ControlService.output ? mint : muted, Paint.Align.RIGHT);
        c.restore();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            float x = (e.getX() - ox) / scale;
            float y = (e.getY() - oy) / scale;
            for (Map.Entry<String, RectF> entry : hits.entrySet()) {
                if (entry.getValue().contains(x, y)) {
                    actions.action(entry.getKey());
                    performClick();
                    break;
                }
            }
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
