package local.rc2.xbox;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ViewFlipper;
import cn.iyutong.rc2core.ProtocolSniffer;
import cn.iyutong.rc2core.Rc2Config;
import cn.iyutong.rc2core.Rc2Controller;
import local.rc2.xbox.page.DashboardPage;
import local.rc2.xbox.page.RawPage;
import local.rc2.xbox.page.SniffPage;
import local.rc2.xbox.ui.Ui;
import local.rc2.xbox.ui.UiLog;

/**
 * 宿主：只负责页面切换、生命周期和帧循环。
 *
 * <p>数据来自 {@link Rc2Controller}（{@code cn.iyutong.rc2core}），界面拆成两个 page 类：
 * 仪表盘 / 原始报文。三层之间只通过 Rc2Controller 的 getter 耦合，
 * 想换 UI 不用碰协议，想换协议不用碰 UI。
 */
public class MainActivity extends Activity implements Choreographer.FrameCallback {

    private static final int PAGE_DASH = 0;
    private static final int PAGE_RAW = 1;
    private static final int PAGE_SNIFF = 2;

    private ViewFlipper pager;
    private Rc2Controller rc2;
    private DashboardPage dashboard;
    private RawPage rawPage;
    private SniffPage sniffPage;
    private ProtocolSniffer sniffer;

    private boolean animating;
    private long lastFrame;
    private long logWindow;
    private long pageWindow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 必须在 setContentView 之前：把 density 归一化成 1dp = 1 设计像素，
        // XML 里的数值就是 1920×1080 画布上的像素，任何屏幕密度都不会变形。
        Ui.normalizeDensity(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        pager = findViewById(R.id.pager);
        pager.setInAnimation(null);
        pager.setOutAnimation(null);
        applyFullscreen();

        dashboard = new DashboardPage(findViewById(R.id.pager));
        rawPage = new RawPage(findViewById(R.id.pager));
        sniffer = new ProtocolSniffer();
        sniffPage = new SniffPage(findViewById(R.id.pager), sniffer);

        rc2 = Rc2Controller.create(Rc2Config.builder()
                .logger(UiLog.SINK)
                .build());

        // 半按探针依赖无障碍按键过滤：点「快门」徽标直接跳到开启页面
        findViewById(R.id.shutterText).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.btnRaw).setOnClickListener(v -> show(PAGE_RAW));
        findViewById(R.id.btnBackFromRaw).setOnClickListener(v -> show(PAGE_DASH));
        findViewById(R.id.btnHunt).setOnClickListener(v -> show(PAGE_SNIFF));
        findViewById(R.id.btnBackFromSniff).setOnClickListener(v -> show(PAGE_DASH));
    }

    private void applyFullscreen() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void show(int page) {
        pager.setDisplayedChild(page);
        if (page == PAGE_RAW) {
            rawPage.refresh(rc2);
        } else if (page == PAGE_SNIFF) {
            sniffer.tick();
            sniffPage.refresh();
        }
    }

    @Override
    public void onBackPressed() {
        if (pager.getDisplayedChild() != PAGE_DASH) {
            show(PAGE_DASH);
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // 记录系统层按键（含半按快门可能走的 KEYCODE_FOCUS），不拦截
        rc2.handleKeyEvent(event);
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onStart() {
        super.onStart();
        rc2.start();
        sniffer.start();
    }

    @Override
    protected void onStop() {
        sniffer.stop();
        rc2.stop();
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!animating) {
            animating = true;
            lastFrame = 0;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    @Override
    protected void onPause() {
        animating = false;
        Choreographer.getInstance().removeFrameCallback(this);
        super.onPause();
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!animating) {
            return;
        }
        float dt = lastFrame == 0 ? (1f / 60f)
                : Math.min(0.05f, (frameTimeNanos - lastFrame) / 1e9f);
        lastFrame = frameTimeNanos;

        long now = android.os.SystemClock.uptimeMillis();
        int page = pager.getDisplayedChild();
        if (page == PAGE_DASH) {
            dashboard.tick(rc2, now, dt);
        }
        if (now - logWindow >= 250) {
            logWindow = now;
            dashboard.refreshLog();
        }
        if (now - pageWindow >= 250) {
            pageWindow = now;
            if (page == PAGE_RAW) {
                rawPage.refresh(rc2);
            } else if (page == PAGE_SNIFF) {
                sniffer.tick();
                sniffPage.refresh();
            }
        }
        Choreographer.getInstance().postFrameCallback(this);
    }
}
