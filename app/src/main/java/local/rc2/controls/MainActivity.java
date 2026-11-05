package local.rc2.controls;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.OutputStream;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private final Handler handler = new Handler();
    private final Runnable tick = () -> update();
    private DashboardView dashboard;
    private LinearLayout body;
    private TextView status;
    private TextView mapping;
    private boolean settingsScreen;
    private final String[] targets = new String[]{
            "A", "B", "X", "Y", "LB", "RB", "LT", "RT",
            "BACK", "START", "GUIDE", "LS", "RS", "UP", "DOWN", "LEFT", "RIGHT"
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getSharedPreferences("controls", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("local", true)
                .putBoolean("pc", false)
                .apply();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        showDashboard();
        if (savedInstanceState == null) {
            new AlertDialog.Builder(this)
                    .setTitle("免费软件声明")
                    .setMessage("此软件为免费软件，如果你是花钱购买的，说明你被骗了")
                    .setPositiveButton("我知道了", null)
                    .show();
        }
    }

    private void showDashboard() {
        settingsScreen = false;
        status = null;
        mapping = null;
        dashboard = new DashboardView(this, a -> action(a));
        setContentView(dashboard);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void action(String a) {
        if (a.equals("settings")) {
            showSettings();
            return;
        }
        if (a.equals("export")) {
            export();
            return;
        }
        if (a.equals("test")) {
            openTest();
            return;
        }
        if (a.equals("about")) {
            about();
            return;
        }
        if (a.equals("toggle")) {
            toggle();
            return;
        }
        String[] items = new String[targets.length + 2];
        items[0] = "仅识别这个实体按键";
        for (int i = 0; i < targets.length; i++) {
            items[i + 1] = "绑定 Xbox  " + targets[i];
        }
        items[items.length - 1] = "删除这个按键的关联";
        String id = ControlService.labelId(this, a);
        String cur;
        if (id == null) {
            cur = "";
        } else {
            try {
                cur = new JSONObject(getSharedPreferences("controls", Context.MODE_PRIVATE).getString("buttons", "{}"))
                        .optString(id);
            } catch (Exception e) {
                cur = "";
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(a + (cur.isEmpty() ? "" : "  →  " + cur))
                .setItems(items, (d, w) -> {
                    if (w == items.length - 1) {
                        removeLabel(a);
                        return;
                    }
                    beginLearn(a, w == 0 ? null : targets[w - 1]);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void beginLearn(String label, String target) {
        ControlService.output = false;
        ControlService.clear();
        ControlService.learnLabel = label;
        ControlService.learnTarget = target;
        ControlService.learnUntil = SystemClock.uptimeMillis() + 10000;
        toast("请在 10 秒内按一下实体 " + label + (target == null ? "" : "，绑定到 " + target));
    }

    private void removeLabel(String label) {
        ControlService.output = false;
        try {
            String id = ControlService.labelId(this, label);
            JSONObject labels = new JSONObject(getSharedPreferences("controls", Context.MODE_PRIVATE).getString("labels", "{}"));
            JSONObject buttons = new JSONObject(getSharedPreferences("controls", Context.MODE_PRIVATE).getString("buttons", "{}"));
            if (id != null) {
                labels.remove(id);
                buttons.remove(id);
            }
            getSharedPreferences("controls", Context.MODE_PRIVATE)
                    .edit()
                    .putString("labels", labels.toString())
                    .putString("buttons", buttons.toString())
                    .apply();
        } catch (Exception ignored) {
        }
    }

    private void toggle() {
        if (ControlService.instance == null) {
            toast("请先在连接设置中开启本工具无障碍服务");
            return;
        }
        if (!ControlService.output && !ControlService.localStatus.equals("已连接虚拟手柄")) {
            toast("本机手柄正在激活，请稍等；若一直未连接，请查看连接设置中的状态");
            return;
        }
        ControlService.output = !ControlService.output;
        ControlService.clear();
    }

    private void openTest() {
        startActivity(new Intent(this, GamepadTestActivity.class));
    }

    private void showSettings() {
        settingsScreen = true;
        dashboard = null;
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xffedf3fb);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(40, 24, 40, 48);
        scroll.addView(body);
        setContentView(scroll);
        button("← 返回可视化面板", () -> showDashboard());
        text("全局手柄 · 连接设置", 25);
        status = text("", 16);
        button("开启本工具的无障碍服务", () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        button("启用 / 暂停手柄输出", () -> toggle());
        check("本机虚拟手柄（使用已有本机调试接口自动激活）", "local", true);
        text("在遥控器本机生成标准安卓手柄输入。启用后直接切换到支持手柄的游戏或应用；不支持手柄的应用需要另做触控映射。本机调试可用时自动激活和恢复。", 16);
        button("检测系统收到的手柄输入", () -> openTest());
        text("在面板点击 C1、C2、拍照、录像、返航/暂停设置 Xbox 绑定。默认 C1→A、C2→B、拍照→RB、录像→LB、返航/暂停→START。", 16);
        mapping = text("", 14);
        button("清空 Xbox 按键映射", () -> new AlertDialog.Builder(this)
                .setMessage("清空已保存的 Xbox 按键绑定？")
                .setPositiveButton("清空", (d, w) -> {
                    ControlService.output = false;
                    getSharedPreferences("controls", Context.MODE_PRIVATE).edit().putString("buttons", "{}").apply();
                })
                .setNegativeButton("取消", null)
                .show());
        button("取消正在进行的按键识别", () -> {
            ControlService.learnLabel = null;
            ControlService.learnTarget = null;
            ControlService.learnUntil = 0;
        });
        button("查看按键码记录", () -> {
            StringBuilder sb = new StringBuilder();
            synchronized (ControlService.class) {
                for (JSONObject j : ControlService.observed.values()) {
                    sb.append(j.optString("id")).append("  ").append(j.optString("keyName")).append("\n");
                }
            }
            new AlertDialog.Builder(this)
                    .setTitle("已识别按键")
                    .setMessage(sb.length() == 0 ? "尚未按下实体按键" : sb.toString())
                    .setPositiveButton("关闭", null)
                    .show();
        });
        button("导出按键与通道 JSON", () -> export());
        button("关于软件", () -> about());
    }

    private void export() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, "RC2-Xbox-mapping.json");
        startActivityForResult(i, 1);
    }

    private void about() {
        new AlertDialog.Builder(this)
                .setTitle("RC2 全局手柄")
                .setMessage("作者 QQ：2458106563\n抖音：01001010100001.\n\n此软件为免费软件，如果你是花钱购买的，说明你被骗了")
                .setPositiveButton("知道了", null)
                .show();
    }

    private TextView text(String s, int size) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(0xff233956);
        t.setPadding(0, 10, 0, 10);
        body.addView(t);
        return t;
    }

    private void button(String label, Runnable r) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(v -> r.run());
        body.addView(b);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void check(String label, String key, boolean def) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setChecked(getSharedPreferences("controls", Context.MODE_PRIVATE).getBoolean(key, def));
        c.setOnCheckedChangeListener((b, v) -> {
            ControlService.output = false;
            getSharedPreferences("controls", Context.MODE_PRIVATE).edit().putBoolean(key, v).apply();
        });
        body.addView(c);
    }

    private void edit(String label, String key, String def) {
        text(label, 15);
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(getSharedPreferences("controls", Context.MODE_PRIVATE).getString(key, def));
        body.addView(input);
        button("保存" + label, () -> {
            ControlService.output = false;
            getSharedPreferences("controls", Context.MODE_PRIVATE)
                    .edit()
                    .putString(key, input.getText().toString().trim())
                    .apply();
            toast("已保存");
        });
    }

    private void update() {
        if (ControlService.learnUntil > 0 && SystemClock.uptimeMillis() > ControlService.learnUntil) {
            ControlService.learnLabel = null;
            ControlService.learnTarget = null;
            ControlService.learnUntil = 0;
        }
        if (status != null) {
            String s = (ControlService.instance == null ? "未开启本工具无障碍服务" : ControlService.status)
                    + " · 样本 " + ControlService.samples
                    + "\n输出：" + (ControlService.output ? "启用" : "暂停")
                    + "\n本机：" + ControlService.localStatus;
            if (!s.contentEquals(status.getText())) {
                status.setText(s);
            }
        }
        if (mapping != null) {
            String m = getSharedPreferences("controls", Context.MODE_PRIVATE).getString("buttons", "{}");
            if (!m.contentEquals(mapping.getText())) {
                mapping.setText(m);
            }
        }
        handler.postDelayed(tick, 200);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (ControlService.instance == null) {
            ControlService.record(this, event);
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (settingsScreen) {
            showDashboard();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1 && resultCode == RESULT_OK && data != null) {
            try {
                OutputStream out = getContentResolver().openOutputStream(data.getData());
                out.write(ControlService.export(this).toString(2).getBytes("UTF-8"));
                out.close();
                toast("已导出");
            } catch (Exception e) {
                toast(e.toString());
            }
        }
    }
}
