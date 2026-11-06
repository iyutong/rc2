package local.rc2.xbox.page;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;
import java.util.Locale;
import cn.iyutong.rc2core.FlightMode;
import cn.iyutong.rc2core.Rc2Controller;
import cn.iyutong.rc2core.RcButton;
import cn.iyutong.rc2core.ShutterProbe;
import cn.iyutong.rc2core.Sticks;
import local.rc2.xbox.R;
import local.rc2.xbox.ui.DialView;
import local.rc2.xbox.ui.StickView;
import local.rc2.xbox.ui.UiLog;

/**
 * 仪表盘页：把 {@link Rc2Controller} 的实时状态画到控件上。
 *
 * <p>只做「读状态 → 写控件」，不持有协议细节，也不做任何解析。
 * 摇杆做指数平滑（时间常数 20ms），避免 25Hz 数据在 60Hz 刷新下抖动。
 */
public final class DashboardPage {

    private static final int CH = Sticks.CHANNELS;
    private static final int LOG_LINES = 150;

    private final TextView stateText;
    private final TextView shutterText;
    private final TextView rateText;
    private final TextView statusText;
    private final TextView keyC1;
    private final TextView keyC2;
    private final TextView keyRecord;
    private final TextView keyShutter;
    private final TextView keyRth;
    private final StickView stickLeft;
    private final StickView stickRight;
    private final TextView stickLeftValue;
    private final TextView stickRightValue;
    private final DialView dialLeft;
    private final DialView dialRight;
    private final TextView dialLeftValue;
    private final TextView dialRightValue;
    private final View modeC;
    private final View modeN;
    private final View modeS;
    private final TextView modeRaw;
    private final TextView channelText;
    private final TextView logText;
    private final ScrollView logScroll;

    private final float[] smooth = new float[CH];
    private final float[] targets = new float[CH];
    private boolean primed;
    private long drawWindow;
    private long sampleWindow;
    private long priorSamples;
    private int draws;
    private float fps;
    private float inputHz;
    private long logRevision = -1;
    private FlightMode lastMode;
    private String lastStickL = "";
    private String lastStickR = "";
    private String lastDialL = "";
    private String lastDialR = "";
    private String lastChannel = "";
    private String lastModeRaw = "";
    private String lastShutter = "";
    private int lastShutterColor = 0;

    public DashboardPage(View root) {
        stateText = root.findViewById(R.id.stateText);
        shutterText = root.findViewById(R.id.shutterText);
        rateText = root.findViewById(R.id.rateText);
        statusText = root.findViewById(R.id.statusText);
        keyC1 = root.findViewById(R.id.keyC1);
        keyC2 = root.findViewById(R.id.keyC2);
        keyRecord = root.findViewById(R.id.keyRecord);
        keyShutter = root.findViewById(R.id.keyShutter);
        keyRth = root.findViewById(R.id.keyRth);
        stickLeft = root.findViewById(R.id.stickLeft);
        stickRight = root.findViewById(R.id.stickRight);
        stickLeftValue = root.findViewById(R.id.stickLeftValue);
        stickRightValue = root.findViewById(R.id.stickRightValue);
        dialLeft = root.findViewById(R.id.dialLeft);
        dialRight = root.findViewById(R.id.dialRight);
        dialLeftValue = root.findViewById(R.id.dialLeftValue);
        dialRightValue = root.findViewById(R.id.dialRightValue);
        modeC = root.findViewById(R.id.modeC);
        modeN = root.findViewById(R.id.modeN);
        modeS = root.findViewById(R.id.modeS);
        modeRaw = root.findViewById(R.id.modeRaw);
        channelText = root.findViewById(R.id.channelText);
        logText = root.findViewById(R.id.logText);
        logScroll = root.findViewById(R.id.logScroll);
    }

    /** 每帧调用（只在仪表盘可见时调，避免无谓的控件赋值）。 */
    public void tick(Rc2Controller rc2, long now, float dt) {
        float alpha = 1f - (float) Math.exp(-dt / 0.02f);
        Sticks s = rc2.sticks();
        // 0 右X 1 右Y 2 左Y 3 左X 4 左拨轮 5 右拨轮，全部是屏幕坐标
        targets[0] = s.rightX();
        targets[1] = s.rightY();
        targets[2] = s.leftY();
        targets[3] = s.leftX();
        targets[4] = s.dialLeft();
        targets[5] = s.dialRight();
        for (int i = 0; i < CH; i++) {
            float target = targets[i];
            if (!primed) {
                smooth[i] = target;
                continue;
            }
            smooth[i] += (target - smooth[i]) * alpha;
            if (Math.abs(target - smooth[i]) < 0.0005f) {
                smooth[i] = target;
            }
        }
        primed = true;

        tickRate(rc2, now);
        stateText.setText(rc2.isLive() ? "● 本机实时输入" : "● 等待 MCU");
        stateText.setTextColor(rc2.isLive() ? 0xff88ffe2 : 0xffffc46e);
        tickShutter(rc2);

        String status = rc2.status();
        if (!status.equals(statusText.getText().toString())) {
            statusText.setText(status);
        }

        stickLeft.setPosition(smooth[3], smooth[2]);
        stickRight.setPosition(smooth[0], smooth[1]);
        String l = String.format(Locale.US, "X %s  Y %s", signed(smooth[3]), signed(smooth[2]));
        if (!l.equals(lastStickL)) {
            lastStickL = l;
            stickLeftValue.setText(l);
        }
        String r = String.format(Locale.US, "X %s  Y %s", signed(smooth[0]), signed(smooth[1]));
        if (!r.equals(lastStickR)) {
            lastStickR = r;
            stickRightValue.setText(r);
        }

        dialLeft.setValue(smooth[4]);
        dialRight.setValue(smooth[5]);
        String dl = dialText(s, 4);
        if (!dl.equals(lastDialL)) {
            lastDialL = dl;
            dialLeftValue.setText(dl);
        }
        String dr = dialText(s, 5);
        if (!dr.equals(lastDialR)) {
            lastDialR = dr;
            dialRightValue.setText(dr);
        }

        String ch = s.toString();
        if (!ch.equals(lastChannel)) {
            lastChannel = ch;
            channelText.setText(ch);
        }

        tickKeys(rc2);
        tickMode(rc2);
    }

    private void tickRate(Rc2Controller rc2, long now) {
        if (drawWindow == 0) {
            drawWindow = now;
            sampleWindow = now;
            priorSamples = rc2.samples();
        }
        draws++;
        if (now - drawWindow < 1000) {
            return;
        }
        fps = draws * 1000f / (now - drawWindow);
        draws = 0;
        drawWindow = now;
        inputHz = (rc2.samples() - priorSamples) * 1000f / Math.max(1L, now - sampleWindow);
        priorSamples = rc2.samples();
        sampleWindow = now;
        rateText.setText(String.format(Locale.US, "%.0f FPS / %.0f Hz", fps, inputHz));
    }

    private void tickKeys(Rc2Controller rc2) {
        keyC1.setSelected(held(rc2, RcButton.C1));
        keyC2.setSelected(held(rc2, RcButton.C2));
        keyRecord.setSelected(held(rc2, RcButton.RECORD));
        keyShutter.setSelected(held(rc2, RcButton.SHUTTER));
        keyRth.setSelected(held(rc2, RcButton.RTH));
    }

    /** MCU 报文的按键位，或者 Android 层同名的按键，都算按下。 */
    private boolean held(Rc2Controller rc2, RcButton button) {
        return rc2.isPressed(button) || rc2.isHeld(button.label());
    }

    private void tickMode(Rc2Controller rc2) {
        FlightMode mode = rc2.flightMode();
        if (mode != lastMode) {
            lastMode = mode;
            modeC.setSelected(mode == FlightMode.C);
            modeN.setSelected(mode == FlightMode.N);
            modeS.setSelected(mode == FlightMode.S);
        }
        String text = rc2.flightModeByte() < 0
                ? "码 —"
                : String.format(Locale.US, "码 %d · b2 %02X · %s",
                        rc2.flightModeCode(), rc2.flightModeByte(), rc2.modeSightings());
        if (!text.equals(lastModeRaw)) {
            lastModeRaw = text;
            modeRaw.setText(text);
        }
    }

    /** 日志区每 250ms 刷一次，只在内容变化时重建 Spannable。 */
    public void refreshLog() {
        long rev = UiLog.revision;
        if (rev == logRevision) {
            return;
        }
        logRevision = rev;
        List<UiLog.Entry> all = UiLog.snapshot();
        int start = Math.max(0, all.size() - LOG_LINES);
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

    /** 带符号两位小数，顺带把 -0.00 归一成 +0.00。 */
    private static String signed(float v) {
        if (Math.abs(v) < 0.005f) {
            v = 0f;
        }
        return String.format(Locale.US, "%+.2f", v);
    }

    private static String dialText(Sticks s, int channel) {
        int byteVal = s.byteValue(channel);
        return String.format(Locale.US, "0x%02X (%d)  MCU %d", byteVal, byteVal, s.raw(channel));
    }

    /** 快门探针：只认 Android 层的 FOCUS / CAMERA 两个键码。 */
    private void tickShutter(Rc2Controller rc2) {
        ShutterProbe probe = rc2.shutter();
        String text;
        int color;
        switch (probe.stage()) {
            case HALF:
                text = "快门 半按";
                color = 0xff88ffe2;
                break;
            case FULL:
                text = "快门 全按";
                color = 0xffffc46e;
                break;
            default:
                text = "快门 —";
                color = 0xffb6c8e6;
        }
        String last = lastShutter;
        if (!text.equals(last)) {
            lastShutter = text;
            shutterText.setText(text);
        }
        if (color != lastShutterColor) {
            lastShutterColor = color;
            shutterText.setTextColor(color);
        }
    }
}
