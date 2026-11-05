package local.rc2.controls;

import android.content.Context;
import android.util.DisplayMetrics;

/**
 * 设计画布归一化。
 *
 * <p>本应用只跑在固定 1920×1080 的遥控器面板上，但系统 density 是厂商随意设置的，
 * sp/dp 换算出来的实际像素不可控，导致同一份 XML 在不同机器上要么字太占地方、
 * 要么被控件高度裁掉。
 *
 * <p>这里把 density 改写为「1dp = 1 个设计像素」的比例，于是 XML 里写的
 * 1920×1080 画布坐标（字号、内边距、圆角）在任何屏幕上都是等比缩放，
 * 相当于把布局当成一张固定画布来排版。
 */
final class Ui {

    static final float DESIGN_W = 1920f;
    static final float DESIGN_H = 1080f;

    private Ui() {
    }

    /**
     * 在 setContentView 之前调用，改写本进程的 density。
     */
    static void normalizeDensity(Context c) {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        if (dm.widthPixels <= 0 || dm.heightPixels <= 0) {
            return;
        }
        float k = Math.min(dm.widthPixels / DESIGN_W, dm.heightPixels / DESIGN_H);
        if (!(k > 0f) || !Float.isFinite(k)) {
            return;
        }
        dm.density = k;
        dm.scaledDensity = k;
        dm.densityDpi = Math.round(k * 160f);
    }

    /**
     * 当前设计像素缩放比，仅用于需要在代码里按画布坐标计算的场合。
     */
    static float scale(Context c) {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        if (dm.widthPixels <= 0 || dm.heightPixels <= 0) {
            return 1f;
        }
        float k = Math.min(dm.widthPixels / DESIGN_W, dm.heightPixels / DESIGN_H);
        return k > 0f && Float.isFinite(k) ? k : 1f;
    }
}
