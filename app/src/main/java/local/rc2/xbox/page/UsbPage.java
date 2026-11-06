package local.rc2.xbox.page;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.TextView;
import cn.iyutong.rc2core.Rc2Controller;
import cn.iyutong.rc2core.ShutterProbe;
import cn.iyutong.rc2core.UsbInput;
import cn.iyutong.rc2core.usb.UsbBitLab;
import cn.iyutong.rc2core.usb.UsbPermission;
import cn.iyutong.rc2core.usb.UsbTopology;
import local.rc2.xbox.R;
import local.rc2.xbox.ui.UiLog;

/**
 * USB 实验室页面：把 USB 通道的全部黑箱摊开给人看。
 *
 * <p>页面回答四个问题，顺序就是「通道 → 帧 → 学习 → 硬件」：
 * <ol>
 *   <li>通道开没开、开在哪个端点、收了多少帧；</li>
 *   <li>帧被识别成按键帧还是摇杆帧，识别率多少；</li>
 *   <li>半按位学到没有、学的是哪一位；</li>
 *   <li>内核层到底有没有 FOCUS 键（这一条决定 USB 之上还有没有戏）。</li>
 * </ol>
 *
 * <p>只做「读状态 → 拼字符串 → 上屏」，不碰任何 USB 细节。
 */
public final class UsbPage {

    private static final String PREFS = "rc2_usb";
    private static final String KEY_HALF = "half_bit";

    private final TextView stateText;
    private final TextView hintText;
    private final TextView body;
    private final SharedPreferences prefs;
    private final Context context;

    private Rc2Controller rc2;
    private String topology = "点「重扫拓扑」生成";
    private String permReport = "";
    private String lastBody = "";

    public UsbPage(View root, Context context) {
        this.context = context;
        this.stateText = root.findViewById(R.id.usbState);
        this.hintText = root.findViewById(R.id.usbHint);
        this.body = root.findViewById(R.id.usbText);
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        // 长按整块报告即复制：设备上没有方便的取日志手段，剪贴板是最短路径
        body.setOnLongClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("RC2 USB 诊断", body.getText()));
                UiLog.i("USB", "诊断报告已复制到剪贴板");
            }
            return true;
        });
        root.findViewById(R.id.btnUsbStart).setOnClickListener(v -> restartChannel());
        root.findViewById(R.id.btnUsbScan).setOnClickListener(v -> rescan());
        root.findViewById(R.id.btnUsbPerm).setOnClickListener(v -> {
            permReport = UsbPermission.diagnose(context);
            UiLog.i("USB", "已生成权限诊断");
            refresh();
        });
        root.findViewById(R.id.btnUsbLearn).setOnClickListener(v -> startLearn());
        root.findViewById(R.id.btnUsbCancel).setOnClickListener(v -> {
            if (rc2 != null) {
                rc2.lab().cancel();
                refresh();
            }
        });
        root.findViewById(R.id.btnUsbUnlock).setOnClickListener(v -> {
            if (rc2 != null) {
                rc2.lab().unlock();
                prefs.edit().remove(KEY_HALF).apply();
                UiLog.i("USB", "已解除半按位锁定");
                refresh();
            }
        });
    }

    /** 由宿主在控制器建好之后调用，顺便恢复上次学到的位。 */
    public void attach(Rc2Controller controller) {
        this.rc2 = controller;
        String sig = prefs.getString(KEY_HALF, null);
        if (sig != null && !sig.isEmpty() && controller.lab().restore(sig)) {
            UiLog.i("USB", "已恢复上次学习结果：" + controller.lab().lockedLabel());
        }
    }

    // ───────────────────────── 操作 ─────────────────────────

    private void restartChannel() {
        if (rc2 == null) {
            UiLog.w("USB", "控制器还没就绪");
            return;
        }
        UiLog.i("USB", "重开 USB 通道");
        rc2.stop();
        rc2.start();
        refresh();
    }

    private void rescan() {
        UsbInput u = rc2 == null ? null : rc2.usb();
        topology = u != null ? u.topologyReport() : UsbTopology.report(context);
        UiLog.i("USB", "拓扑已重扫");
        refresh();
    }

    private void startLearn() {
        if (rc2 == null) {
            UiLog.w("USB", "控制器还没就绪");
            return;
        }
        UsbInput u = rc2.usb();
        if (u == null || !u.isReady()) {
            UiLog.w("USB", "USB 通道还没打开，先看拓扑报告");
            return;
        }
        UiLog.i("USB", "开始学习半按位：先记 3 秒基线，别碰快门");
        rc2.lab().start();
        refresh();
    }

    // ───────────────────────── 渲染 ─────────────────────────

    public void refresh() {
        if (rc2 == null) {
            stateText.setText("● 未就绪");
            stateText.setTextColor(0xffffc46e);
            return;
        }
        UsbInput u = rc2.usb();
        boolean ready = u != null && u.isReady();
        boolean live = u != null && u.isLive();

        stateText.setText(live ? "● USB 实时" : ready ? "● 已连接" : "● 未连接");
        stateText.setTextColor(live ? 0xff88ffe2 : ready ? 0xffb6c8e6 : 0xffffc46e);

        UsbBitLab lab = rc2.lab();
        // 时间兜底：USB 断流时也要让学习流程走到结论，而不是停在基线期
        lab.tick();
        hintText.setText(lab.hint());

        // 学习刚锁定就落地保存，下次启动直接生效
        if (lab.locked()) {
            String sig = lab.signature();
            if (!sig.isEmpty() && !sig.equals(prefs.getString(KEY_HALF, null))) {
                prefs.edit().putString(KEY_HALF, sig).apply();
                UiLog.i("USB", "半按位已保存：" + lab.lockedLabel());
            }
        }
        if (topology.startsWith("点「重扫拓扑」")) {
            // 首次进页面自动扫一次，省一次点击
            topology = u != null ? u.topologyReport() : UsbTopology.report(context);
        }
        if (permReport.isEmpty() && !ready) {
            // 通道没打开，大概率卡在授权上：直接把取证摆出来，不用再点一次
            permReport = UsbPermission.diagnose(context);
        }

        StringBuilder sb = new StringBuilder(2048);
        sb.append(u == null ? "通道未创建（控制器未启动）\n" : u.report());
        sb.append('\n');
        sb.append("快门探针：").append(ShutterProbe.GLOBAL.summary())
                .append("  ·  ").append(ShutterProbe.GLOBAL.detail()).append('\n');
        sb.append("最近跃迁：").append(ShutterProbe.GLOBAL.lastTransition()).append('\n');
        if (!permReport.isEmpty()) {
            sb.append("\n═══ 权限诊断 ═══\n").append(permReport).append('\n');
        }
        sb.append("\n═══ 硬件普查 ═══\n").append(topology);

        String text = sb.toString();
        if (!text.equals(lastBody)) {
            lastBody = text;
            body.setText(text);
        }
    }
}
