package local.rc2.xbox.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * 摇杆盘：按控件实际尺寸自适应绘制，不再依赖固定 1920×1080 坐标系。
 */
public final class StickView extends View {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private float nx;
    private float ny;

    public StickView(Context c) {
        this(c, null);
    }

    public StickView(Context c, AttributeSet a) {
        super(c, a);
    }

    /** 归一化坐标，-1 ～ 1。 */
    public void setPosition(float x, float y) {
        if (nx == x && ny == y) {
            return;
        }
        nx = x;
        ny = y;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float r = Math.min(w, h) / 2f - 2f;
        if (r <= 2f) {
            return;
        }

        p.setShader(new RadialGradient(
                cx - r * 0.35f, cy - r * 0.35f, r * 1.9f,
                new int[]{0xff6588c4, 0xff36588e},
                null,
                Shader.TileMode.CLAMP));
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy, r, p);
        p.setShader(null);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.5f, r * 0.02f));
        p.setColor(0xff9bbcf0);
        c.drawCircle(cx, cy, r, p);
        p.setColor(0x607fa2d7);
        c.drawCircle(cx, cy, r * 0.5f, p);
        p.setColor(0x808fb4eb);
        c.drawLine(cx - r, cy, cx + r, cy, p);
        c.drawLine(cx, cy - r, cx, cy + r, p);

        float dx = nx * r * 0.82f;
        float dy = ny * r * 0.82f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x2088ffe2);
        c.drawCircle(cx + dx, cy + dy, r * 0.22f, p);
        p.setColor(0xff88ffe2);
        c.drawCircle(cx + dx, cy + dy, r * 0.1f, p);
        p.setColor(0xccffffff);
        c.drawCircle(cx + dx - r * 0.025f, cy + dy - r * 0.025f, r * 0.035f, p);
    }
}
