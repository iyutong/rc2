package local.rc2.xbox.page;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.TextView;
import java.util.Locale;
import cn.iyutong.rc2core.ProtocolSniffer;
import cn.iyutong.rc2core.ShutterProbe;
import local.rc2.xbox.R;
import local.rc2.xbox.ShutterKeyService;

/**
 * 半按嗅探页。
 *
 * <p>只做三件事：把 {@link ProtocolSniffer} 的状态画出来、把按钮接到它身上、
 * 把学到的位持久化。所有判断逻辑都在核心库，这里不碰协议。
 */
public final class SniffPage {

    private static final String PREFS = "rc2_probe";
    private static final String KEY_HALF = "half_bit";

    private final Context context;
    private final ProtocolSniffer sniffer;
    private final TextView state;
    private final TextView hint;
    private final TextView text;
    private String savedSig;

    public SniffPage(View root, ProtocolSniffer sniffer) {
        this.sniffer = sniffer;
        this.context = root.getContext();
        state = root.findViewById(R.id.sniffState);
        hint = root.findViewById(R.id.sniffHint);
        text = root.findViewById(R.id.sniffText);

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        savedSig = prefs.getString(KEY_HALF, null);
        if (savedSig != null) {
            sniffer.restore(savedSig);
        }

        root.findViewById(R.id.btnStartSniff).setOnClickListener(v -> {
            sniffer.start();
            refresh();
        });
        root.findViewById(R.id.btnStopSniff).setOnClickListener(v -> {
            sniffer.stop();
            refresh();
        });
        root.findViewById(R.id.btnLearn).setOnClickListener(v -> {
            if (!sniffer.isRunning()) {
                sniffer.start();
            }
            sniffer.startLearn();
            refresh();
        });
        root.findViewById(R.id.btnLearnCancel).setOnClickListener(v -> {
            sniffer.cancelLearn();
            refresh();
        });
        root.findViewById(R.id.btnClearLock).setOnClickListener(v -> {
            sniffer.clearAdopted();
            savedSig = null;
            prefs.edit().remove(KEY_HALF).apply();
            refresh();
        });
    }

    /** 页面每次显示/需要重画时调用。 */
    public void refresh() {
        boolean run = sniffer.isRunning();
        state.setText(run ? "● 嗅探中" : "● 未启动");
        state.setTextColor(run ? 0xff88ffe2 : 0xffb6c8e6);
        hint.setText(sniffer.hint());

        ProtocolSniffer.Candidate c = sniffer.adopted();
        if (c != null && !c.signature().equals(savedSig)) {
            savedSig = c.signature();
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_HALF, savedSig).apply();
        }

        StringBuilder sb = new StringBuilder();
        sb.append(sniffer.report());
        sb.append('\n');
        sb.append("无障碍按键过滤：")
                .append(ShutterKeyService.CONNECTED ? "已开启" : "未开启")
                .append(" · 已收 ").append(ShutterKeyService.SEEN).append(" 键（最近 ")
                .append(ShutterKeyService.LAST).append("）\n");
        sb.append("快门状态：").append(ShutterProbe.GLOBAL.summary());
        if (ShutterProbe.GLOBAL.viaProtocol()) {
            sb.append("（协议位驱动）");
        }
        sb.append('\n');
        sb.append(String.format(Locale.US, "半按×%d 全按×%d · 上次跃迁 %s",
                ShutterProbe.GLOBAL.halfCount(), ShutterProbe.GLOBAL.fullCount(),
                ShutterProbe.GLOBAL.lastTransition()));
        sb.append("\n\n说明：学习期间会对 RC(6)/相机(1)/飞控(3)/OSD(14) 四条命令集轮流查询，仅在地面进行。\n");
        sb.append("若「只在按压时出现的命令」里有条目，那是上行指令（例如对焦命令本身），\n");
        sb.append("看它的 6/xx 与报文头就能判断到底是发给相机的哪条命令。\n");
        sb.append("若一个位、一条命令都没有，说明半按在 MCU 固件里被直接转成对焦指令，应用层与协议层都拿不到。");
        CharSequence old = text.getText();
        if (!sb.toString().contentEquals(old == null ? "" : old)) {
            text.setText(sb.toString());
        }
    }
}
