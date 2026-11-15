package local.rc2.xbox.page;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.TextView;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import cn.iyutong.rc2core.Rc2Controller;
import local.rc2.xbox.R;

/**
 * 原始报文页：把按键报文按十六进制 + 逐位展开，变化过的字节高亮。
 *
 * <p>纯粹是诊断视图，数据全部来自 {@link Rc2Controller#rawButtons()} 与
 * {@link Rc2Controller#changedFrames()}，不参与任何业务判断。
 */
public final class RawPage {

    private static final int PER_ROW = 16;
    private static final int WARM = 0xffffd48a;

    private final TextView summary;
    private final TextView body;
    private long seen = -1;

    public RawPage(View root) {
        summary = root.findViewById(R.id.rawSummary);
        body = root.findViewById(R.id.rawText);
    }

    public void refresh(Rc2Controller rc2) {
        long rev = rc2.packetCount();
        if (rev == seen) {
            return;
        }
        seen = rev;

        byte[] payload = rc2.rawButtons();
        summary.setText(String.format(Locale.US, "推送 %d · %d 字节 · 变化 %s",
                rev, payload.length, rc2.lastDiff()));
        if (payload.length == 0) {
            body.setText("尚未收到按键报文");
            return;
        }

        List<byte[]> frames = rc2.changedFrames();
        HashSet<Integer> changed = new HashSet<>();
        if (frames.size() >= 2) {
            byte[] prev = frames.get(frames.size() - 2);
            byte[] cur = frames.get(frames.size() - 1);
            int n = Math.min(prev.length, cur.length);
            for (int i = 0; i < n; i++) {
                if (prev[i] != cur[i]) {
                    changed.add(i);
                }
            }
        }

        SpannableStringBuilder sb = new SpannableStringBuilder();

        sb.append("十六进制\n");
        for (int r = 0; r * PER_ROW < payload.length; r++) {
            sb.append(String.format(Locale.US, "%03X  ", r * PER_ROW));
            for (int col = 0; col < PER_ROW; col++) {
                int i = r * PER_ROW + col;
                if (i >= payload.length) {
                    break;
                }
                int from = sb.length();
                sb.append(String.format(Locale.US, "%02X ", payload[i] & 0xff));
                if (changed.contains(i)) {
                    sb.setSpan(new ForegroundColorSpan(WARM), from, sb.length(),
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
                sb.setSpan(new ForegroundColorSpan(WARM), from, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        sb.append("\n变化帧（最新在上）\n");
        int shown = Math.min(frames.size(), 16);
        for (int r = 0; r < shown; r++) {
            byte[] f = frames.get(frames.size() - 1 - r);
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < f.length; i++) {
                if (i > 0) {
                    line.append(' ');
                }
                line.append(String.format(Locale.US, "%02X", f[i] & 0xff));
            }
            sb.append(line).append('\n');
        }

        sb.append("\n按键位：b0&0x70 | (b2&0x0C)<<8 | (b1&0x01)<<16，拍照位 0x40\n");
        sb.append("飞行模式取 17 字节报文的 b2 低 2 位（bit2-3 是 C1/C2）\n");
        body.setText(sb);
    }
}
