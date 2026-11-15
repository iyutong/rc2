# rc2core · DJI RC2 遥控器输入核心库

> 一个零依赖的 Android 库：把 DJI RC2 遥控器的**摇杆 / 拨轮 / 实体按键 / 飞行挡位**变成
> 干净的回调与 getter。协议细节（Binder 反射、报文解码、位掩码）全部封在内部，
> 上层只管画界面。

| 项 | 值 |
| --- | --- |
| 包名 | `cn.iyutong.rc2core` |
| 模块 | `:rc2core`（Android Library） |
| 语言 / 版本 | Java 11，`minSdk 29`，`compileSdk 37` |
| 第三方依赖 | **无**（只用 `android.os` / `android.view` / `android.util` + `androidx.annotation`） |
| 线程安全 | getter 任意线程可读；回调线程可配置 |
| 测试 | `:rc2core:testDebugUnitTest`（12 个纯 JVM 单测） |

---

## 目录

1. [快速开始](#1-快速开始)
2. [架构与数据流](#2-架构与数据流)
3. [线程模型](#3-线程模型)
4. [完整 API 参考](#4-完整-api-参考)
5. [协议说明](#5-协议说明)
6. [调用实例](#6-调用实例)
7. [本轮修复的缺陷](#7-本轮修复的缺陷)
8. [测试与构建](#8-测试与构建)
9. [排错清单](#9-排错清单)
10. [迁移与打包](#10-迁移与打包)

---

## 1. 快速开始

### 1.1 引入

`settings.gradle.kts`：

```kotlin
include(":rc2core")
```

`app/build.gradle.kts`：

```kotlin
dependencies {
    implementation(project(":rc2core"))
}
```

也可以把 `cn/iyutong/rc2core/**` 整个包拷到别的工程里直接编译——除了
`androidx.annotation` 之外没有任何 import 是外部的。

### 1.2 最小可用示例

```java
public class MainActivity extends Activity {

    private Rc2Controller rc2;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        rc2 = Rc2Controller.create();          // 默认配置
        rc2.addListener(new Rc2Listener() {
            @Override public void onSticks(Sticks s) {
                // 约 25Hz，注意这里不在主线程
                Log.d("RC2", "LX=" + s.leftX() + " LY=" + s.leftY());
            }
            @Override public void onButton(RcButton btn, boolean down) {
                Log.d("RC2", btn.label() + (down ? " 按下" : " 松开"));
            }
            @Override public void onFlightMode(FlightMode m, int raw) {
                Log.d("RC2", "挡位 → " + m.label());
            }
        });
    }

    @Override protected void onStart() { super.onStart(); rc2.start(); }
    @Override protected void onStop()  { rc2.stop(); super.onStop(); }
}
```

三行关键代码：`create()` → `start()` / `stop()`。连不上协议服务也不会抛异常，
只会走 `onReady(false, 原因)`。

### 1.3 想在主线程收回调

```java
rc2 = Rc2Controller.create(Rc2Config.builder()
        .callbackHandler(new Handler(Looper.getMainLooper()))   // 回调自动切主线程
        .logger((level, tag, msg) -> myUiLog.append(level, tag, msg))
        .buttonTimeoutMs(1500)                                  // 按键丢包自动释放
        .build());
```

---

## 2. 架构与数据流

```
┌──────────────────── 系统 / DJI 进程 ────────────────────┐
│  binder service "protocol"                              │
│  (com.dji.protocol.IProtocolManager)                    │
└───────▲───────────────────────────────┬─────────────────┘
        │ transact(2) 订阅               │ transact(1) 推送 6/174
        │ transact(4) 查询摇杆           │ （按键 + 挡位）
        │ transact(5) 退订               ▼
┌───────┴─────────────────────────────────────────────────────────────────┐
│  internal.DjiProtocol      HandlerThread "rc2-protocol"                 │
│    · 反射 ServiceManager.getService("protocol")                         │
│    · 订阅 / 轮询 / 退订，只负责把字节拿回来                               │
│    · Sink: onSticks(int[6]) / onPush(int[15], byte[]) / onReady / onError│
└───────┬─────────────────────────────────────────────────────────────────┘
        ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  internal.ButtonCodec      报文 → 位掩码、差异位（纯函数，可单测）        │
└───────┬─────────────────────────────────────────────────────────────────┘
        ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  Rc2Controller             唯一门面                                     │
│    · epoch 会话守卫：丢弃 stop 之后迟到的回调                            │
│    · 状态聚合：sticks / pressedMask / flightMode / 计数器                │
│    · 分发：回调（Rc2Listener） + 轮询（getter）                          │
└───────┬─────────────────────────────────────────────────────────────────┘
        ▼
   宿主 UI（Activity / View / 业务）
```

对外只有 11 个公开类型：

| 类型 | 作用 |
| --- | --- |
| `Rc2Controller` | 唯一入口（门面） |
| `Rc2Config` | 配置 + Builder |
| `Rc2Listener` | 回调集合（全 default 方法） |
| `Sticks` | 一帧 6 通道摇杆数据（不可变） |
| `RcButton` | 实体按键枚举 + 位掩码 |
| `FlightMode` | 飞行挡位枚举 |
| `Rc2State` | 冻结快照 |
| `KeySample` | 一条 Android 按键事件 |
| `KeyLabels` | 键码 → 中文短标签 |
| `Rc2Log` | 日志出口接口 |
| `internal.DjiProtocol` / `internal.ButtonCodec` | 内部实现，宿主不需要碰 |

---

## 3. 线程模型

| 线程 | 做什么 |
| --- | --- |
| 调用线程（通常是主线程） | `start()` / `stop()` / 所有 getter / `handleKeyEvent()` |
| `rc2-protocol`（每会话一条） | 绑定服务、订阅、轮询、退订、退出 |
| Binder 线程池 | 推送回调 `onPush`、查询回调（摇杆解码） |

要点：

* **状态字段全部 `volatile`**，getter 可以在任意线程读，不需要加锁。
* **读-改-写的字段（`pressedMask`、`buttonAt`、`changedFrames`）统一由一把内部锁保护**，
  因为按键推送和摇杆查询落在两条不同的 Binder 线程上，可能同时发生。
* **回调默认在内部线程执行**。要改 UI 就配 `callbackHandler`，或者自己 `view.post()`。
* **事件回调都在锁外发出**，宿主在回调里反调 `isPressed()` 之类的 getter 不会死锁。

---

## 4. 完整 API 参考

### 4.1 `Rc2Controller`

```java
public final class Rc2Controller
```

#### 创建

| 方法 | 说明 |
| --- | --- |
| `static Rc2Controller create()` | 用默认配置新建 |
| `static Rc2Controller create(Rc2Config config)` | 自定义配置；传 `null` 等价于默认 |
| `static Rc2Controller shared()` | 全进程单例（懒加载，默认配置） |

> `shared()` 只适合「一个遥控器只被一个界面用」。两个实例同时 `start()` 会各自建立一份订阅，
> 事件会重复——常规做法是 `create()` 一个实例，用依赖注入传下去。

#### 生命周期

| 方法 | 说明 |
| --- | --- |
| `void start()` | 幂等启动。新建协议线程 → 绑定 → 订阅 → 轮询。失败不抛异常 |
| `void stop()` | 幂等停止。退订、停线程、给按住的键补发 `onButton(..., false)` |
| `boolean isRunning()` | `start()` 之后到 `stop()` 之前为 `true` |
| `boolean isConnected()` | 订阅是否成功（协议服务是否可用） |
| `String status()` | 面向人的状态文案：`未启动` / `连接中` / `已连接 · 按键订阅 6/174` / `已停止` / 错误原因 |
| `Rc2Config config()` | 取回创建时的配置 |

#### 监听器

| 方法 | 说明 |
| --- | --- |
| `void addListener(Rc2Listener l)` | 去重添加；`null` 忽略 |
| `void removeListener(Rc2Listener l)` | 移除 |

#### 摇杆 / 拨轮

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `Sticks sticks()` | 最近一帧，未收到时是全中位 | 零分配 |
| `long sampledAt()` | `SystemClock.uptimeMillis()`，`0` = 还没收到 | |
| `long samples()` | 累计采样数 | 两帧相减 / 时间差 = Hz |
| `boolean isLive()` | 500ms 内有数据 | 比 `isConnected()` 更能反映「真的活着」 |

#### 按键

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `boolean isPressed(RcButton b)` | MCU 报文里的按下状态 | `null` 安全，返回 `false` |
| `int pressedMask()` | 原始位掩码 | 见 [5.2](#52-按键位掩码) |
| `boolean isHeld(String label)` | MCU 位 **或** Android 键按住 | 合并两个来源 |
| `Set<String> heldLabels()` | 所有按住项的标签副本 | 只读副本 |
| `byte[] rawButtons()` | 最近一帧按键报文副本 | 未收到为空数组 |
| `String lastDiff()` | 与上一变化帧的差异位，如 `b2.4 b1.0` | 首帧为 `首帧` |
| `List<byte[]> changedFrames()` | 最近 16 个变化帧，**深拷贝** | 时间升序 |

#### 挡位

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `FlightMode flightMode()` | `S` / `N` / `C` / `UNKNOWN` | 未收到为 `UNKNOWN` |
| `int flightModeByte()` | 报文 b2 原始值 | 未收到 `-1`；bit2-3 会随 C1/C2 变 |
| `int flightModeCode()` | b2 低 2 位 | `0→S 1→N 2→C 3→未知`，未收到 `-1` |
| `String flightModeSource()` | 来源，如 `6/174 t2` | 诊断用 |
| `String modeSightings()` | 编码出现次数，如 `0×12 1×3` | 核对挡位编码 |

#### 统计 / 快照 / 系统按键

| 方法 | 说明 |
| --- | --- |
| `long packetCount()` | 累计推送包数；可当「版本号」判断要不要刷新 |
| `Rc2State snapshot()` | 一次性冻结全部状态（会分配，别每帧调） |
| `void handleKeyEvent(KeyEvent e)` | 把宿主的按键事件转进来，抓 `KEYCODE_FOCUS` 这类系统层键 |

### 4.2 `Rc2Config`

不可变配置对象，`Builder` 链式构建。

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `pollIntervalMs` | `40`（≈25Hz） | 摇杆轮询间隔，最小 16 |
| `buttonTimeoutMs` | `0`（关闭） | 超过该时长没收到按键报文就强制释放。确认设备会周期重发按键帧时可设 1500 |
| `callbackHandler` | `null` | `null` = 内部线程回调；传主线程 `Handler` 则自动切线程 |
| `logger` | `Rc2Log.ANDROID` | 日志出口，传 `null` 回落 logcat |

```java
Rc2Config cfg = Rc2Config.builder()
        .pollIntervalMs(33)                              // ≈30Hz
        .buttonTimeoutMs(1500)
        .callbackHandler(new Handler(Looper.getMainLooper()))
        .logger((level, tag, message) -> Log.println(level == 'E' ? Log.ERROR : Log.INFO, tag, message))
        .build();

Rc2Config defaults = Rc2Config.defaults();
```

### 4.3 `Rc2Listener`

全部是 `default` 方法，只覆写需要的即可。

| 方法 | 触发时机 | 线程 |
| --- | --- | --- |
| `onReady(boolean ready, String detail)` | 订阅成功 / 失败 / 服务断开 | 协议线程 |
| `onSticks(Sticks s)` | 每帧摇杆（≈`pollIntervalMs`） | 查询回调线程 |
| `onButton(RcButton b, boolean pressed)` | **只在变化时**，不重复 | 推送线程 |
| `onFlightMode(FlightMode m, int rawByte)` | 挡位码真正变化时（首次收到也会报） | 推送线程 |
| `onKey(KeySample k)` | 收到 `handleKeyEvent()` 转进来的系统按键 | 调用 `handleKeyEvent` 的线程 |
| `onError(String message)` | 单次失败，通常会自动继续 | 视来源 |

> `onFlightMode` 的 `rawByte` 是 b2 原始值，**不能**直接当挡位判据——
> bit2-3 是 C1/C2。判挡位请用 `m` 或 `FlightMode.code(rawByte)`。

### 4.4 `Sticks`

一帧 6 通道数据，**不可变**，可跨线程传递、可长期持有。

| 成员 | 说明 |
| --- | --- |
| `static final int CHANNELS = 6` | 通道数 |
| `static final int CENTER = 1024` | 中位 |
| `static final int SPAN = 660` | 中位到满偏的差值（范围 364～1684） |
| `static Sticks center()` | 全中位（共享常量实例） |
| `static Sticks of(int[] channels)` | 构造；长度不足补中位 |
| `int raw(int ch)` | 原始值；越界返回 `CENTER` |
| `float normalized(int ch)` | 归一化 -1～1 |
| `static float norm(int value)` | 静态归一化，含钳位 |
| `float rightX()` | 右摇杆 X |
| `float rightY()` | 右摇杆 Y，**已按屏幕坐标取反**（向下为正） |
| `float leftX()` | 左摇杆 X |
| `float leftY()` | 左摇杆 Y，**已取反** |
| `float dialLeft()` | 左拨轮 |
| `float dialRight()` | 右拨轮 |
| `int[] rawChannels()` | 6 个原始值副本 |
| `int byteValue(int ch)` | 原始值 → 0～255，用于 `%02X` 显示 |
| `String toString()` | `RX 1024 RY 1024 LY 1684 LX 364 LT 1024 RT 1024` |

通道映射（MCU 原始顺序）：

| 通道 | 0 | 1 | 2 | 3 | 4 | 5 |
| --- | --- | --- | --- | --- | --- | --- |
| 含义 | 右摇杆 X | 右摇杆 Y | 左摇杆 Y | 左摇杆 X | 左拨轮 | 右拨轮 |

### 4.5 `RcButton`

```java
public enum RcButton { C1, C2, SHUTTER, RECORD, RTH }
```

| 常量 | `label()` | `mask()` | 位置 |
| --- | --- | --- | --- |
| `C1` | `C1` | `0x000400` | b2 bit2 |
| `C2` | `C2` | `0x000800` | b2 bit3 |
| `SHUTTER` | `拍照` | `0x000040` | b0 bit6（只有全按有报文） |
| `RECORD` | `录像` | `0x010000` | b1 bit0 |
| `RTH` | `返航/暂停` | `0x000030` | b0 bit4-5（相邻两位） |

| 方法 | 说明 |
| --- | --- |
| `String label()` | 中文短标签，与仪表盘文案一致 |
| `int mask()` | 位掩码 |
| `boolean isDown(int mask)` | 该键在掩码里是否按下 |
| `static RcButton byLabel(String)` | 按标签反查，找不到返回 `null` |

### 4.6 `FlightMode`

```java
public enum FlightMode { S, N, C, UNKNOWN }
```

| 方法 | 说明 |
| --- | --- |
| `String label()` | `S` / `N` / `C` / `?` |
| `static int code(int b2)` | 取 b2 低 2 位（`b2 & 0x03`） |
| `static FlightMode fromByte(int b2)` | 由原始字节解析 |
| `static FlightMode fromCode(int code)` | 由挡位码解析，内部 `& 0x03`，兼容旧的 4/5/6 |

编码：`0 → S`，`1 → N`，`2 → C`，`3 → UNKNOWN`。

### 4.7 `KeySample`

| 成员 | 说明 |
| --- | --- |
| `int keyCode` | 如 `KEYCODE_CAMERA` |
| `int scanCode` | 扫描码 |
| `int action` | `ACTION_DOWN` / `ACTION_UP` / `ACTION_MULTIPLE` |
| `String device` | 设备名，可能为 `null` |
| `String keyName` | `KeyEvent.keyCodeToString()` |
| `String label` | 短标签，未收录为空串 |
| `long at` | `uptimeMillis()` |
| `boolean isDown()` | 是否 `ACTION_DOWN` |
| `String shortLabel()` | 短标签，未收录退化成 `KEYCODE_xxx` |

### 4.8 `Rc2State`

`snapshot()` 的返回，全部为 `public final` 字段。

`connected` · `status` · `sticks` · `flightMode` · `flightModeByte` · `flightModeCode` ·
`flightModeSource` · `pressedMask` · `heldLabels`（不可变） · `packets` · `samples` ·
`rawButtons`（副本） · `lastDiff` · `at`

方法：`isPressed(RcButton)`（`null` 安全）、`anyPressed()`、`isHeld(String)`、`toString()`。

### 4.9 `Rc2Log`

```java
public interface Rc2Log {
    void log(char level, String tag, String message);   // level: 'I' / 'W' / 'E'
    Rc2Log ANDROID = ...;                               // 默认写 logcat
}
```

### 4.10 `KeyLabels`

```java
static String of(int keyCode)   // 未收录返回空串
```

覆盖 A/B/X/Y、方向键、`CAMERA`、`FOCUS`、L1/L2/R1/R2、L3/R3、START/SELECT/MODE、`MEDIA_RECORD`。
只影响显示文案，不参与逻辑。

---

## 5. 协议说明

### 5.1 报文通道

| 事务 | code | 内容 |
| --- | --- | --- |
| 订阅 | `2` | `writeInt(1), writeInt(1), filter[6]={4,2,0,6,6,174}, IBinder` |
| 查询摇杆 | `4` | `writeInt(1), pack[20], IBinder` |
| 退订 | `5` | `writeStrongBinder` |

* 服务名 `protocol`，描述符 `com.dji.protocol.IProtocolManager`，回调接口 `com.dji.protocol.IPackListener`。
* 推送包头固定 **15 个 int**，其中 `header[5]` 是 type，`header[13]` 是 cmdSet，`header[14]` 是 cmdId。
* 只处理主通道 **cmdSet 6 / cmd 174**：
  * `type == 2` 的负载 → 按键报文
  * 长度 17 的负载 → `payload[2]` 里含飞行挡位
* 查询返回 12 字节 = 6 个 `int16`（小端）。

### 5.2 按键位掩码

```java
mask = (b0 & 0x70) | ((b2 & 0x0C) << 8) | ((b1 & 0x01) << 16)
```

展开：

| 报文位 | 掩码位 | 按键 |
| --- | --- | --- |
| `b0.6` | `0x000040` | 拍照（全按） |
| `b0.4-5` | `0x000030` | 返航 / 暂停 |
| `b2.2` | `0x000400` | C1 |
| `b2.3` | `0x000800` | C2 |
| `b1.0` | `0x010000` | 录像 |

### 5.3 挡位与 C1/C2 共用 b2

```
b2:  bit7 bit6 bit5 ... bit3 bit2 | bit1 bit0
                        C2   C1    └─ 挡位码 ─┘
```

所以取挡位必须 `b2 & 0x03`。用低 3 位或直接比较原始字节，都会把「按 C1」当成
「挡位从 S 变到 S」。这是本库历史上踩过的坑，现在有单测钉住
（`CodecTest.c1c2BitsDoNotChangeFlightMode`）。

---

## 6. 调用实例

### 6.1 摇杆画到自绘 View（每帧轮询，零分配）

```java
@Override public void doFrame(long t) {
    Sticks s = rc2.sticks();
    pad.setPosition(s.leftX(), s.leftY());      // Y 已是屏幕坐标，直接喂
    padR.setPosition(s.rightX(), s.rightY());
    dialL.setValue(s.byteValue(4));
    dialR.setValue(s.byteValue(5));
    invalidate();
    Choreographer.getInstance().postFrameCallback(this);
}
```

### 6.2 主线程回调 + 直接改 UI

```java
rc2 = Rc2Controller.create(Rc2Config.builder()
        .callbackHandler(new Handler(Looper.getMainLooper()))
        .build());

rc2.addListener(new Rc2Listener() {
    @Override public void onSticks(Sticks s) { pad.setPosition(s.leftX(), s.leftY()); }
    @Override public void onFlightMode(FlightMode m, int raw) { modeText.setText(m.label()); }
});
```

### 6.3 用按键触发业务

```java
@Override public void onButton(RcButton b, boolean down) {
    if (!down) return;                       // 只关心按下
    switch (b) {
        case SHUTTER: shoot();      break;
        case RECORD:  toggleRec();  break;
        case C1:      cycleSpeed(); break;
        case C2:      toggleMap();  break;
        case RTH:     confirmRth(); break;
    }
}
```

> 长按请自己计时：`onButton` 只在**变化**时触发，按住期间不会有重复回调。
> 需要长按语义就配合 `isPressed(b)` 在帧循环里累计时长。

### 6.4 只在状态变化时刷新界面（版本号模式）

```java
private long seen = -1;

public void refresh(Rc2Controller rc2) {
    long rev = rc2.packetCount();
    if (rev == seen) return;                 // 没新包，跳过
    seen = rev;
    rawText.setText(hex(rc2.rawButtons()) + "  " + rc2.lastDiff());
}
```

### 6.5 抓系统层按键（半按快门等）

```java
@Override public boolean dispatchKeyEvent(KeyEvent e) {
    rc2.handleKeyEvent(e);        // 只记录，不拦截
    return super.dispatchKeyEvent(e);
}

// 然后
@Override public void onKey(KeySample k) {
    if (k.isDown() && k.keyCode == KeyEvent.KEYCODE_FOCUS) focusStart();
}
```

### 6.6 统一判断「某键是否按着」（MCU + 系统键）

```java
boolean held = rc2.isPressed(RcButton.C1) || rc2.isHeld(RcButton.C1.label());

// 或者一次拿全
Set<String> all = rc2.heldLabels();          // 例如 [C1, 拍照, 半按/对焦]
```

### 6.7 冻结快照（跨线程传状态）

```java
Rc2State st = rc2.snapshot();                // 一次读全，之后怎么变都不影响它
renderer.submit(st);                         // 丢给渲染线程

if (st.connected && st.anyPressed()) { ... }
```

### 6.8 诊断：看原始报文怎么变

```java
for (byte[] frame : rc2.changedFrames()) {   // 深拷贝，随便改不会污染库内部
    Log.d("RAW", hex(frame));
}
Log.d("RAW", "挡位编码观测：" + rc2.modeSightings());   // 0×12 1×3 2×5
Log.d("RAW", "来源：" + rc2.flightModeSource());
```

### 6.9 采样率监控

```java
long windowAt, prior;
public void tick() {
    long now = SystemClock.uptimeMillis();
    if (now - windowAt >= 1000) {
        float hz = (rc2.samples() - prior) * 1000f / (now - windowAt);
        hzText.setText(String.format(Locale.US, "%.1f Hz", hz));
        prior = rc2.samples();
        windowAt = now;
    }
}
```

### 6.10 完整 Activity 骨架

```java
public class MainActivity extends Activity implements Choreographer.FrameCallback {

    private Rc2Controller rc2;
    private boolean running;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        rc2 = Rc2Controller.create(Rc2Config.builder()
                .logger((lv, tag, msg) -> uiLog.append(lv, tag, msg))
                .build());
        rc2.addListener(listener);
    }

    @Override protected void onStart()   { super.onStart(); rc2.start(); }
    @Override protected void onStop()    { rc2.stop(); super.onStop(); }

    @Override protected void onResume() {
        super.onResume();
        running = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override protected void onPause() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
        super.onPause();
    }

    @Override public void doFrame(long t) {
        if (!running) return;
        Sticks s = rc2.sticks();
        pad.setPosition(s.leftX(), s.leftY());
        statusText.setText(rc2.isLive() ? "实时" : "等待 MCU");
        Choreographer.getInstance().postFrameCallback(this);
    }
}
```

---

## 7. 本轮修复的缺陷

按影响面排序。所有修改都保持公开 API **完全不变**（唯一新增是便利方法 `Rc2State.anyPressed()`）。

### 高危

| # | 位置 | 问题 | 修复 |
| --- | --- | --- | --- |
| 1 | `DjiProtocol.stop()` | **订阅泄漏竞态**。退订直接在调用线程执行，若 `bind()` 正在协议线程上跑（还没发出 `transact(2)`），退订会先到服务端，订阅随后建立且永不撤销。结果：`system_server` 里留着一个强引用 + 重复推送 + 后续实例事件翻倍 | 退订动作排进同一条串行队列（`worker.post`），保证「订阅 → 退订」的先后 |
| 2 | `Rc2Controller` 全类 | **停止后状态回灌**。`stop()` 之后仍在途的 Binder 回调会把 `pressedMask` 写回非 0，按键永远亮着；`onReady/onError` 还会把 `status` 从「已停止」改回别的内容 | 引入 `epoch` 会话代号，每次 start/stop 自增，`Sink` 回调带代号回来比对，过期直接丢弃 |
| 3 | `Rc2Controller.handleButtons / expireButtonsIfIdle` | **跨线程读-改-写竞争**。按键推送线程与摇杆查询回调线程同时改 `pressedMask`，会丢按键事件或产生「按住却报松开」 | 掩码比较与写入全部收进 `lock`，事件收集后在锁外分发 |
| 4 | `Rc2Controller.changedFrames()` | **浅拷贝泄漏内部数组**。返回的 `byte[]` 与内部 `priorButtons` 是同一个对象，调用方改一个字节就把下一帧的差异计算带偏，`lastDiff` 从此错乱 | 逐个 `clone()` 后再返回 |

### 中危

| # | 位置 | 问题 | 修复 |
| --- | --- | --- | --- |
| 5 | `DjiProtocol` 构造/stop/start | 构造即建线程（只 new 不 start 就漏一条线程）；`stop()` 后 `start()` 静默无效（往已退出的 Looper 投任务） | 线程改到 `start()` 里创建；加 `started/stopped` 守卫，明确单次语义 |
| 6 | `DjiProtocol.service` | 非 `volatile`，`bind()` 写在协议线程、`stop()` 读在调用线程，可能读到 `null` 而漏退订 | 加 `volatile` |
| 7 | `DjiProtocol.poll()` | `stop()` 之后仍会 `postDelayed` 下一个轮询（静默失败），`SecurityException` 分支也没清 `waiting` | 续期前检查 `running`；异常分支复位 `waiting` |
| 8 | `DjiProtocol.onTransact` | `data.enforceInterface()` 在 try 之外，报文截断时异常会逃出 `onTransact`，污染同线程后续事务 | 移进 try |
| 9 | `Rc2Controller.start()` | 重启时不重置 `priorButtons / rawButtons / lastDiff / buttonAt`，上一轮的报文成为新一轮的 diff 基准，首个变化帧报错 | 启动时清空，首帧重新走 `首帧` |

### 低危 / 健壮性

| # | 位置 | 问题 | 修复 |
| --- | --- | --- | --- |
| 10 | `Rc2State.isPressed` | 传 `null` 直接 NPE | `null` 安全，返回 `false` |
| 11 | `ButtonCodec.hex(b, max)` | `max <= 0` 时算出负长度，返回一个孤零零的 `" …"` | 直接返回空串 |
| 12 | `ButtonCodec.diff(a, null)` | NPE；且注释写「返回 null」而实现返回字符串 | 加 `null` 分支，修正注释 |
| 13 | `Rc2Controller.isLive()` | 依赖 `sampleAt` 默认值，语义不够明确 | 显式要求 `sampleAt != 0` |
| 14 | `Rc2Controller.stop()` | 超时释放后 `buttonAt` 残留 | 归零 |
| 15 | 文档 | `KeySample` 注释写 `rc2.dispatchKeyEvent()`，方法实际叫 `handleKeyEvent()`；`RawPage` 提示「挡位取低 3 位」，实际是低 2 位 | 改正 |

### 回归测试

新增 `StateTest`（5 个用例）+ 保留 `CodecTest`（7 个用例），覆盖：

* 快照与内部状态脱钩（集合不可变、字节数组被拷贝、`null` 安全）
* `hex` / `diff` 的边界输入
* 按键位布局、C1/C2 不影响挡位码、摇杆归一化与钳位

---

## 8. 测试与构建

```bash
./gradlew :rc2core:testDebugUnitTest   # 12 个纯 JVM 单测
./gradlew :rc2core:assembleRelease     # 出 AAR
./gradlew :app:assembleDebug           # 宿主 APK
```

纯 JVM 单测的口径：凡是需要 `android.os.SystemClock` 的逻辑（轮询、超时）
都不在单测覆盖范围内，因为默认 `testOptions` 没有 `returnDefaultValues`。
这些靠真机验证 + 诊断界面（RawPage）核对。

---

## 9. 排错清单

| 现象 | 排查方向 |
| --- | --- |
| `status` 一直是「未启动」 | 忘了 `start()` |
| `onReady(false, "protocol 服务不可用")` | 不在 RC2 遥控器环境；或 `ServiceManager` 被 ROM 屏蔽（反射失败）。确认该方法在真机上跑，模拟器必然失败 |
| `onReady(false, "按键订阅被拒绝")` | 权限 / 服务端拒绝 `transact(2)`。看 logcat 里 `按键订阅失败：xxx` |
| 有 `onReady(true)` 但没有 `onSticks` | 摇杆查询失败，看 `onError` 里的「摇杆查询异常」。也可能是写入被拒（`SecurityException`，此时会永久停止轮询） |
| 摇杆有数据、按键没反应 | 确认报文头 `cmdSet=6 cmdId=174`、`type=2`；用 `rawButtons().length` 看是否为空、`lastDiff()` 看有没有变化 |
| `onButton` 只报按下不报松开 | 松开报文丢了，打开 `buttonTimeoutMs(1500)` 兜底 |
| 挡位乱跳 / 刷「S → S」 | 你用了原始字节比较。必须用 `FlightMode.code(b2)`（低 2 位） |
| 按 C1/C2 时挡位误变 | 同上，bit2-3 属于按键 |
| 退出界面后按键一直是按下 | 老版本问题，本轮已修（`stop()` 补发释放 + epoch 拦截） |
| 摇杆方向上下颠倒 | `Sticks.leftY()/rightY()` 已按屏幕坐标取反。做 3D 控制时记得再取反回去 |
| 回调里改 UI 崩溃 | 默认在 Binder 线程回调。配 `callbackHandler` 或自己 `post` |
| 采样率低 | `pollIntervalMs` 默认 40ms；低于 16 会被夹到 16 |

---

## 10. 迁移与打包

### 10.1 只用不改

拷贝 `rc2core/src/main/java/cn/iyutong/rc2core/` 整个目录。外部依赖只有
`androidx.annotation`（`@NonNull` / `@Nullable`，可删可留）。

### 10.2 出 AAR

```bash
./gradlew :rc2core:assembleRelease
# → rc2core/build/outputs/aar/rc2core-release.aar
```

宿主侧：

```kotlin
dependencies { implementation(files("libs/rc2core-release.aar")) }
```

### 10.3 移植到别的遥控器

需要改的只有两处：

1. `internal.DjiProtocol` 里的 `SERVICE_NAME` / `DESCRIPTOR` / 事务号 / `QUERY_PACK`；
2. `internal.ButtonCodec.mask()` 的位布局与 `RcButton` 的掩码常量。

`Rc2Controller` 与所有对外类型都不用动。

---

## 附：一句话总结

> `create()` 出来 → `start()` → 回调或 getter 取数据 → `stop()`。
> 摇杆用 `leftX/leftY/rightX/rightY`（Y 已翻转），按键用 `onButton`（只报变化），
> 挡位用 `flightMode()`（别碰原始字节），诊断用 `rawButtons()` / `changedFrames()`。
