package local.rc2.xbox.page;

import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import cn.iyutong.rc2core.Rc2Controller;
import cn.iyutong.rc2core.Rc2Packet;
import local.rc2.xbox.R;

/**
 * 推送探针页：把最近收到的协议包按「cmd / 类型 / 长度」归类，并列出流水。
 *
 * <p>用于判断某个动作（例如半按快门）到底有没有报文：出现新签名 = 事件在别的包里，
 * 行数增加但 hex 相同 = 同包重复上报，完全不动 = 根本不经过 protocol 服务。
 */
public final class ProbePage {

    private static final int MAX_SIGNATURES = 32;
    private static final int RING_LINES = 60;

    private final TextView summary;
    private final TextView sigs;
    private final TextView ring;
    private final ScrollView scroll;
    private long seen = -1;

    public ProbePage(View root) {
        summary = root.findViewById(R.id.probeSummary);
        sigs = root.findViewById(R.id.probeSigs);
        ring = root.findViewById(R.id.probeRing);
        scroll = root.findViewById(R.id.probeScroll);
    }

    public void refresh(Rc2Controller rc2) {
        long rev = rc2.packetCount();
        if (rev == seen) {
            return;
        }
        seen = rev;

        List<Rc2Packet> all = rc2.packets();
        LinkedHashMap<String, Stat> stats = new LinkedHashMap<>();
        for (Rc2Packet p : all) {
            Stat s = stats.get(p.signature());
            if (s == null) {
                s = new Stat(p);
                stats.put(p.signature(), s);
            }
            s.count++;
            s.hex = p.hex;
        }
        summary.setText(String.format(Locale.US, "总推送 %d · 签名 %d 类 · 缓存 %d 条",
                rev, stats.size(), all.size()));

        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Stat s : stats.values()) {
            if (shown++ >= MAX_SIGNATURES) {
                sb.append("…\n");
                break;
            }
            sb.append(String.format(Locale.US, "%-6s t%-3d %2dB ×%-4d\n      %s\n",
                    s.cmd, s.type, s.len, s.count, s.hex));
        }
        sigs.setText(sb.length() == 0 ? "尚未收到任何推送包" : sb);

        StringBuilder r = new StringBuilder();
        int start = Math.max(0, all.size() - RING_LINES);
        for (int i = start; i < all.size(); i++) {
            Rc2Packet p = all.get(i);
            r.append(String.format(Locale.US, "%7.1f %-6s t%-3d %2dB\n      %s\n",
                    p.at / 1000f, p.cmd(), p.type, p.size, p.hex));
        }
        ring.setText(r);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    /** 一类签名的统计。 */
    private static final class Stat {
        final String cmd;
        final int type;
        final int len;
        int count;
        String hex;

        Stat(Rc2Packet p) {
            this.cmd = p.cmd();
            this.type = p.type;
            this.len = p.size;
            this.hex = p.hex;
        }
    }
}
