package local.rc2.xbox.page;

import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
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
 *
 * <p>顶部可以自己填 {@code cmdSet / cmdId} 追加订阅 —— 主通道只有 6/174，
 * 想确认别的通道有没有数据，就把它订上再看这张表会不会多一行。
 */
public final class ProbePage {

    private static final int MAX_SIGNATURES = 32;
    private static final int RING_LINES = 60;

    private final TextView summary;
    private final TextView sigs;
    private final TextView ring;
    private final ScrollView scroll;

    private final EditText inputSet;
    private final EditText inputCmd;
    private final TextView subState;
    private final LinearLayout chips;

    private Rc2Controller rc2;
    private long seen = -1;
    private String chipKey = "";

    public ProbePage(View root) {
        summary = root.findViewById(R.id.probeSummary);
        sigs = root.findViewById(R.id.probeSigs);
        ring = root.findViewById(R.id.probeRing);
        scroll = root.findViewById(R.id.probeScroll);

        inputSet = root.findViewById(R.id.probeCmdSet);
        inputCmd = root.findViewById(R.id.probeCmdId);
        subState = root.findViewById(R.id.probeSubState);
        chips = root.findViewById(R.id.probeSubChips);

        // 默认给个和主通道相邻的 cmd，省得每次手打
        inputSet.setText("6");
        inputCmd.setText("175");
        inputSet.setInputType(InputType.TYPE_CLASS_NUMBER);
        inputCmd.setInputType(InputType.TYPE_CLASS_NUMBER);

        root.findViewById(R.id.btnProbeSub).setOnClickListener(v -> subscribe());
        root.findViewById(R.id.btnProbeUnsubAll).setOnClickListener(v -> unsubscribeAll());
    }

    public void refresh(Rc2Controller rc2) {
        this.rc2 = rc2;
        refreshChips(false);

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
        int subs = rc2.customSubscriptions().size();
        summary.setText(String.format(Locale.US, "总推送 %d · 签名 %d 类 · 缓存 %d 条 · 自定义订阅 %d 路",
                rev, stats.size(), all.size(), subs));

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

    // ───────────────────────── 自定义订阅 ─────────────────────────

    private void subscribe() {
        if (rc2 == null) {
            subState.setText("未连接");
            return;
        }
        int set = parse(inputSet);
        int cmd = parse(inputCmd);
        if (set < 0 || cmd < 0) {
            subState.setText("cmdSet / cmdId 要填 0～65535 的数字");
            return;
        }
        boolean ok = rc2.subscribe(set, cmd);
        subState.setText(ok
                ? "已订阅 " + set + "/" + cmd + " → 看左栏是否新增签名"
                : "订阅未生效 " + set + "/" + cmd + "（看仪表盘日志）");
        refreshChips(true);
    }

    private void unsubscribeAll() {
        if (rc2 == null) {
            return;
        }
        int n = rc2.customSubscriptions().size();
        rc2.unsubscribeAll();
        subState.setText(n == 0 ? "当前没有自定义订阅" : "已退订 " + n + " 路");
        refreshChips(true);
    }

    private void drop(String sig) {
        if (rc2 == null) {
            return;
        }
        int slash = sig.indexOf('/');
        if (slash <= 0) {
            return;
        }
        try {
            int set = Integer.parseInt(sig.substring(0, slash));
            int cmd = Integer.parseInt(sig.substring(slash + 1));
            rc2.unsubscribe(set, cmd);
            subState.setText("已退订 " + sig);
        } catch (NumberFormatException ignored) {
            return;
        }
        refreshChips(true);
    }

    /** 只在订阅列表变化时重建标签，避免每 250ms 拆一次视图。 */
    private void refreshChips(boolean force) {
        if (rc2 == null) {
            return;
        }
        List<String> subs = rc2.customSubscriptions();
        String key = subs.toString();
        if (!force && key.equals(chipKey)) {
            return;
        }
        chipKey = key;
        chips.removeAllViews();
        if (subs.isEmpty()) {
            chips.addView(chip("无自定义订阅 · 只收主通道 6/174", false));
            return;
        }
        for (String sig : subs) {
            TextView t = chip(sig + "  ×", true);
            t.setOnClickListener(v -> drop(sig));
            chips.addView(t);
        }
    }

    private TextView chip(String text, boolean active) {
        TextView t = new TextView(chips.getContext());
        t.setText(text);
        t.setTextSize(20);
        t.setIncludeFontPadding(false);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        int padH = dp(14);
        int padV = dp(8);
        t.setPadding(padH, padV, padH, padV);
        if (active) {
            t.setBackgroundResource(R.drawable.bg_chip);
            t.setTextColor(0xff88ffe2);
        } else {
            t.setTextColor(0xffb6c8e6);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(8));
        t.setLayoutParams(lp);
        return t;
    }

    private int dp(int value) {
        return Math.round(value * chips.getResources().getDisplayMetrics().density);
    }

    private static int parse(EditText e) {
        String s = e.getText() == null ? "" : e.getText().toString().trim();
        if (s.isEmpty()) {
            return -1;
        }
        try {
            int v = Integer.parseInt(s);
            return v < 0 || v > 0xFFFF ? -1 : v;
        } catch (NumberFormatException ex) {
            return -1;
        }
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
