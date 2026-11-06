package local.rc2.xbox.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 拨轮：横向刻度条 + 填充 + 中心指针，按控件尺寸自适应。
 */
public final class DialView extends View {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final RectF bar = new RectF();
    private float v;
    private boolean has;

    public DialView(Context c) {
        this(c, null);
    }

    public DialView(Context c, AttributeSet a) {
        super(c, a);
    }

    /** 归一化值，-1 ～ 1。 */
    public void setValue(float value) {
        if (has && v == value) {
            return;
        }
        has = true;
        v = value;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth();
        float h = getHeight();
        float radius = Math.max(2f, h * 0.28f);
        float mid = h / 2f;
        bar.set(0f, mid - h * 0.3f, w, mid + h * 0.3f);

        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xff284775);
        c.drawRoundRect(bar, radius, radius, p);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, h * 0.04f));
        p.setColor(0xff82a9dc);
        c.drawRoundRect(bar, radius, radius, p);

        float frac = (v + 1f) / 2f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xffc7f3ff);
        c.drawRoundRect(bar.left, bar.top, bar.left + frac * w, bar.bottom, radius, radius, p);

        p.setColor(0xff88ffe2);
        c.drawRect(w / 2f - 1.5f, bar.top - h * 0.06f, w / 2f + 1.5f, bar.bottom + h * 0.06f, p);
    }
}
