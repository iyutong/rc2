# RC2 三份 APK 分析与快门「半按」定位报告

> 分析对象：`DJI Fly 1.21.10`、`DJI Simulator 1.4.1`、`开发助手 com.dpad.fuli 1.1.02.23`
> 分析方式：静态逆向（aapt2 清单解析 + DEX 反汇编 + IL2CPP 元数据 + 原生库字符串），未在真机运行这三份软件。

---

## 一、先说结论

| 问题 | 结论 |
|---|---|
| 半按有没有产生 `6/174` 按键报文？ | **没有**。真机实测整帧字节无变化（历史记录已确认），本次逆向也印证：DJI 应用的按键模型里根本没有「半按」这个状态。 |
| 半按状态在系统里存不存在？ | **存在，但不在应用层**。DJI Simulator 的 IL2CPP 元数据里明确有 `ButtonStatus.FocusButtonDown`，与 `ShutterButtonDown` 并列——说明遥控器固件（MCU）确实知道半按。 |
| 那它走哪条通道？ | 走 **RC 控制 / 模拟器模式** 的私有 USB 通道（`RCCommandSet.PUSH_BUTTON_STATUS`），不是飞行时 DJI Fly 用的 `protocol` 服务（`6/174`）。 |
| 第三方应用能不能读？ | 能「尝试」，但三条路各有硬限制：无障碍按键过滤（无 root，依赖厂商是否把半按映射成 `KEYCODE_FOCUS`）、`/dev/input` 直读（要 shell/root）、RC 控制模式（会切断飞行链路）。**没有一条能在飞行中稳定拿到半按。** |

一句话：**半按在 MCU → 相机这条链路上就被消化了，没有翻成 Android 事件，也没有翻成 `6/174` 的位。**

---

## 二、三个 APK 各自是什么

### 1. DJI Fly 1.21.10 —— 主机位飞行 App

| 项 | 值 |
|---|---|
| 包名 | `dji.go.v5` |
| 版本 | 1.21.10（versionCode 3115981） |
| SDK | minSdk 24 / targetSdk 34 / compileSdk 34 |
| 体积 | 468 MB，22 个 dex，dex 合计约 199 MB |
| ABI | 只有 `armeabi-v7a`（RC2 是 32 位系统，与项目里用 `app_process32` 的现象吻合） |

结构上是 DJI 新一代「Lua 模型 + 原生 SDK」架构：

- `Luav/infra/flymodel/...`：自动生成的摄像机/遥控器数据模型（`CameraFocusModel`、`ShutterSpeedStopModel`、`RcButtonAction` 等），由 Lua VM 驱动；
- `com.uav.service.RcButtonService`：遥控器按键 → 动作的总调度（自定义按键、C1/C2 映射、拨轮动作）；
- `com.uav.component.fpv.mvi.slots.widgets.ShutterButtonWidget`：屏幕上的快门按钮组件，监听 `getRCShutterButtonClickEventObservable`；
- 原生层 `libsdk_key_value.so`（键值 schema）、`libilink2.so`（图传链路）、`libuav_keys.so`；
- 与 MCU 的通信**不经过** `com.dji.protocol`（全 dex 里没有该包的引用），走的是它自己的 `FlyBinderObservable` / 原生链路。

**角色**：飞行、图传、相机控制的唯一入口。所有按键的「业务含义」由它定义。

### 2. DJI Simulator 1.4.1 —— 飞行模拟训练 App

| 项 | 值 |
|---|---|
| 包名 | `com.fly.simulator` |
| 版本 | 1.4.1（versionCode 101041） |
| 引擎 | Unity + IL2CPP（`libil2cpp.so` 75 MB，`global-metadata.dat` 13 MB） |
| 组件 | 单个 `SimulatorActivity`，其余全是 SDK |
| 关键权限 | `android.hardware.usb.host`、`android.hardware.usb.accessory`、`USB_PERMISSION` |

元数据里写明：`DJI Simulator is a safe and efficient flight training app ... Supports RC 2, RC Pro 2, RC-N3 remote controllers`，并且有一条提示语 **“DJI Fly is running on remote controller. End background process to use remote controller”**。

**角色**：把遥控器当「USB 手柄/数据源」接过来做模拟飞行。它必须让 DJI Fly 让出遥控器，所以会抢 USB accessory。

**它是本次分析的关键证据源**——它是三份里唯一直接描述「遥控器按键结构」的样本。

### 3. 开发助手（`com.dpad.fuli`）—— 工厂/研发调试工具

| 项 | 值 |
|---|---|
| 包名 | `com.dpad.fuli`（“dpad” 是 DJI 对带屏遥控器产品线的内部代号） |
| 版本 | 1.1.02.23-0bd5aa34 |
| 特权 | `android:sharedUserId="android.uid.system"`、`coreApp=true` |
| 常驻 | `dji.system.share.DpadShareService`（persistent，exported，内含一个 goshare 的简易 HTTP 服务） |
| 组件 | 40+ 个调试 Activity：`ProtocalActivity`（手发协议包）、`ShellCommandActivity`（跑 shell）、`McuStickActivity`（摇杆校准）、`McuUpdateActivity`（MCU 升级）、`RoleActivity`、`TemperatureActivity`、`McuStickAbnormalActivity`、`SdrDevActivity` 等 |

它**没有**把 `com.dji.protocol` 打进 APK，而是直接引用系统里的框架库（`ProtocolManager` / `Pack.Builder` / `IPackListener` / `DataConfig`），所以它是三份里唯一能拿到完整协议字典的样本：

```
ProtocolDict$CMD$Keys      CMDSET=6, CMDID=174      ← 我们正在用的按键通道
ProtocolDict$CMD$RC        BUTTON_PHY_STATUS = 174
                           DPAD_STATUS_SET = 100, ANDROID_SYSTEM_STATUS = 119
                           BEEP_SET/GET = 98/99, WHEEL_MAP_SET/GET = 108/109
                           DPAD_ROLE_SET/GET = 130/131, CP_SCAN_PUSH = 132
ProtocolDict$CMD$FIVE_KEYS KEYCODE_STATUS = 81（UP=1 DOWN=2 MIDDLE=4 LEFT=8 RIGHT=16 C1=32 C2=64）
ProtocolDict$DEVICE$RC     TYPE = 6
```

**角色**：产线/研发用。它证明了一件事：`protocol` 这个 Binder 服务是 RC2 上 MCU 与 Android 之间的正规通道，只要有 system UID（或进程名/权限放行）就能收发任意 cmdSet/cmdId。

### 4. 三者的关系

```
                    ┌──────────────────────────────┐
                    │        RC2 硬件（MCU）        │
                    │  摇杆/拨轮/拍照/录像/返航/C1C2 │
                    │  ★ 快门两段：半按对焦 / 全按拍摄 │
                    └───────┬──────────────┬───────┘
             USB/私有链路    │              │  USB accessory（被独占）
                    ┌───────▼───────┐   ┌──▼─────────────────┐
                    │ protocol 服务 │   │ RC ctrl / 模拟器通道 │
                    │ (Binder)      │   │ PUSH_BUTTON_STATUS  │
                    └───┬───────┬───┘   └──┬─────────────────┘
           6/174 按键推送 │       │ transact(4) 摇杆轮询
        ┌───────────────▼──┐  ┌─▼──────────────┐   ┌──────────────────────┐
        │ DJI Fly          │  │ 本项目 rc2core │   │ DJI Simulator        │
        │ 飞行/图传/相机    │  │ 按键+摇杆监视  │   │ 训练（要 DJI Fly 让位）│
        └──────────────────┘  └────────────────┘   └──────────────────────┘
                    ▲
                    │ 同 UID=system，直接调 ProtocolManager
        ┌───────────┴──────────┐
        │ 开发助手 com.dpad.fuli│  ← 产线工具，可手发协议包/跑 shell
        └──────────────────────┘
```

三者互不调用，共用同一套硬件与同一套底层协议；**开发助手是「特权旁路」，模拟器是「另一条协议支路」，DJI Fly 是「正规业务方」**。

---

## 三、半按到底丢在哪：四步证据链

### 证据 1：DJI Fly 的遥控器按键模型里没有「半按」

`libsdk_key_value.so` 与 dex 字符串里，遥控器按键的全部键名是：

```
KeyRCShutterButtonDown        KeyRCRecordButtonDown      KeyRCGohomeButtonDown
KeyRCShutterButtonLongPress   KeyRCPauseButtonDown       KeyRCPlaybackButtonDown
KeyRCCustomButton1..4Down     KeyRCDialButtonDown        KeyRCDialValue
KeyRCFlightModeSwitchState    KeyRCStickLeft/Right...
```

快门只有 **Down** 和 **LongPress** 两个语义，全局搜索 `half` / `HalfPress` / `half_press` **零命中**。

也就是说：DJI Fly 只关心「按了」和「长按了」，它从不需要半按。

### 证据 2：`6/174` 字节在半个按下去时纹丝不动

真机实测（历史记录）：半按期间 `6/174` 整帧 payload 完全不变，只有全按才会翻转快门位。当前解码：

| 位 | 含义 | 来源 |
|---|---|---|
| `b0 & 0x70` | 快门(bit6) / 返航暂停(bit4-5) 等 | 实测 |
| `(b2 & 0x0C) << 8` | C1 / C2 | 实测 |
| `(b1 & 0x01) << 16` | 录像 | 实测 |
| `b2 & 0x03` | 飞行挡位 S/N/C | 实测 |

如果半按在 MCU 的按键位图里，`b0` 必然会出现一个新的位——实测没有。

### 证据 3：模拟器里却有 `FocusButtonDown`

`assets/bin/Data/Managed/Metadata/global-metadata.dat` 里，`Assembly-CSharp` 的 `ButtonStatus` 类字段（连同它的 `ToString` 模板）完整可见：

```
",PauseButtonDown:{0}, GoHomeButtonDown:{1}, ShutterButtonDown:{2},
  FocusButtonDown:{3}, RecordButtonDown:{4}, FnButtonDown:{5}, LEDButtonDown:{6}"
```

字段列表：`CH0..CH5 / ModeSwitch / C1..C4 / PauseButtonDown / GoHomeButtonDown /
ShutterButtonDown / FocusButtonDown / RecordButtonDown / FnButtonDown / LEDButtonDown /
FDButtonDown / FDRight / FDLeft / FDBottom / FDUp / WheelButtonDown / WheelChanged /
WheelRollClockwise / WheelRollOffset`

同区的命令枚举：

```
RCCommandSet:
  PUSH_RC_CHANNEL_PARAM
  PUSH_BUTTON_STATUS      ← 携带上面那个 ButtonStatus
  SET_RC_CTRL_MODE / GET_RC_CTRL_MODE
  SET_SIMULATOR_MODE / GET_SIMULATOR_MODE
  SET_ANDROID_SYSTEM_STATUS
```

**这是全案最硬的一条证据：硬件层有半按，字段名叫 `FocusButtonDown`，而且它只出现在「RC 控制 / 模拟器」这条命令集里。**

### 证据 4：开发助手的协议字典里也没有「半按」类命令

`ProtocolDict$CMD$RC` 一共就十来条（见上表），没有 focus/half 相关项；`ProtocalActivity` 也只是个手输 cmdSet/cmdId/hex 的测试页。

### 结论

半按在 MCU 内部就被转换成「对焦指令」直接发给飞机相机了，**既不进 `6/174` 的上报位图，也不生成 Android 按键事件**。它只在「遥控器被当作外设」的 RC 控制模式下，作为 `ButtonStatus.FocusButtonDown` 暴露给主机。

> 这也解释了为什么 DJI Fly 没有半按逻辑：对焦根本不需要 App 参与。

---

## 四、能不能读到？四条可选路径

| 方案 | 前提/权限 | 接口 | 能否在飞行中 | 成功率判断 |
|---|---|---|---|---|
| **A. 无障碍按键过滤** | 用户手动开启无障碍服务，声明 `canRequestFilterKeyEvents` | `AccessibilityService.onKeyEvent()` | 能 | 低（依赖半按是否变成 `KEYCODE_FOCUS`） |
| **B. 直读 `/dev/input`** | shell(2000)/root，或系统签名 | `getevent -lt`、`EventHub` | 能 | 低-中（能看到内核原始键值，前提同上） |
| **C. `protocol` 6/174** | 现有能力 | Binder transact(2) 订阅 | 能 | 只能拿全按，**拿不到半按** |
| **D. RC 控制模式** | USB host/accessory 独占，`MANAGE_USB`（系统） | `SET_RC_CTRL_MODE` + `PUSH_BUTTON_STATUS` | **不能**（会顶掉 DJI Fly） | 高（唯一确有该字段的通道），但代价是断链路 |

### 方案 A（已实现）：无障碍按键过滤

- **权限**：服务侧 `android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"`；能力侧 `android:canRequestFilterKeyEvents="true"`；运行时 `FLAG_REQUEST_FILTER_KEY_EVENTS`。用户需在「设置 → 无障碍」手动开启一次。
- **接口**：`AccessibilityService.onKeyEvent(KeyEvent)` —— 系统在把按键分发给前台应用之前**复制**一份给所有申请了过滤的服务，返回 `false` 不拦截，DJI Fly 照常收到。
- **实现步骤**（本轮已落地）：
  1. `ShutterKeyService` 在 `onServiceConnected` 里给 `AccessibilityServiceInfo` 打上 `FLAG_REQUEST_FILTER_KEY_EVENTS`；
  2. `onKeyEvent` 只挑 `KEYCODE_FOCUS(80)` / `KEYCODE_CAMERA(27)` 喂给 `ShutterProbe`；
  3. 仪表盘顶栏「快门」徽标实时显示 空闲 / 半按 / 全按，点一下直接跳无障碍设置页。
- **限制 / 失败情形**：
  - 若 RC2 的 keylayout 把半按映射成别的键码（或根本不映射），这里永远只有「全按」；
  - 部分 ROM 会限制无障碍与前台应用同时收键；
  - 系统「无障碍快捷方式」被占用、或服务被系统优化杀掉（本应用是 persistent 进程可缓解）。

**判定方法**：装上后连按几次快门，若日志里只出现 `FOCUS → FULL` 而从不出现 `IDLE → HALF`，并且 `fullWithoutHalf == true`，就说明半按没有到达 Android 层——此时方案 A/B 都可以放弃。

### 方案 B：直读 `/dev/input`（诊断用，建议只做验证）

- **权限**：Android 没有对应权限可申请。要么 uid 2000（shell，本项目早期用 127.0.0.1:5555 的本地 adbd 干过）、要么 root、要么系统签名拿 `INJECT_EVENTS`。
- **命令**：
  ```bash
  adb shell getevent -lt            # 记下设备节点
  adb shell getevent -lt /dev/input/event2 | grep -i -E "0050|00d4"   # KEY_FOCUS / KEY_CAMERA 的 (type,code)
  ```
  `getevent` 输出形如 `type 1 (EV_KEY), code 80 (KEY_FOCUS), value 1`。
- **失败情形**：`Permission denied`（SELinux/权限位）、根本没有对应 event 节点（说明按键走的是非标准 HID 或 MCU 内部）。
- **价值**：如果你只想知道「半按到底有没有变成内核事件」，这一条 30 秒就能给出终局答案。

### 方案 C：`protocol` 6/174（现成，但只有全按）

保持现状即可：`Rc2Controller` 已经在解 `6/174`，`RcButton.SHUTTER` 拿的是全按。**不要再指望从这条报文里挖半按**——字节层面已经验证过。

> 顺带记录：`6/81`（`FIVE_KEYS KEYCODE_STATUS`）是另一条五向键推送，与快门无关，但如果你要排查「某些键跑到别处去了」，可以用 `transact(2)` 单独订 `{4,2,0,6,6,81}` 验证。

### 方案 D：RC 控制模式（唯一有 `FocusButtonDown` 的通道）

- **权限**：`android.hardware.usb.host` + `UsbManager`（普通应用可申请 USB 权限对话框）；若要免弹窗需 `MANAGE_USB`（`signature|privileged`，只有开发助手这类系统应用有）。
- **流程**：
  1. 停止 DJI Fly（它会占着 USB/链路，模拟器就明确要求这么做）；
  2. 打开 RC 的 USB accessory，`SET_RC_CTRL_MODE`（或 `SET_SIMULATOR_MODE`）切模式；
  3. 循环读 `PUSH_BUTTON_STATUS`，按 `ButtonStatus` 结构解出 `FocusButtonDown`；
  4. 结束时 `SET_RC_CTRL_MODE=0` 并把 USB 还给 DJI Fly。
- **限制**：**飞行中不可用**（切模式=断图传/断控制），只适合地面验证；命令枚举值需要继续逆向 `libil2cpp.so` 才能确定；不同固件版本字段可能位移。
- **失败情形**：USB 被占用、MCU 拒绝模式切换、结构体版本不匹配（模拟器自身也做了版本握手 `GET_VERSION_CONFIG`）。

---

## 五、本轮已经写进项目的代码

| 文件 | 作用 |
|---|---|
| `rc2core/.../ShutterProbe.java` | 新增。`IDLE/HALF/FULL` 状态机，全进程单例 `GLOBAL`，零分配 getter；`fullWithoutHalf()` 用来判定「半按被吞」 |
| `rc2core/.../Rc2Controller.java` | `handleKeyEvent` 现在同时喂探针；状态跃迁时打 `Shutter` 日志并回调；新增 `shutter()` |
| `rc2core/.../Rc2Listener.java` | 新增 `onShutterStage(Stage, KeySample)` |
| `app/.../ShutterKeyService.java` | 新增。无障碍按键过滤服务，只挑 FOCUS/CAMERA，不消费事件 |
| `app/res/xml/accessibility_shutter.xml` | 新增。`canRequestFilterKeyEvents=true`，不申请读取窗口内容 |
| `app/.../AndroidManifest.xml` | 注册 `ShutterKeyService`（`BIND_ACCESSIBILITY_SERVICE`） |
| `app/res/layout/page_dashboard.xml` | 顶栏新增「快门」徽标（可点击 → 无障碍设置） |
| `app/.../page/DashboardPage.java` | 每帧刷新徽标：空闲灰 / 半按薄荷 / 全按橙 |
| `app/.../MainActivity.java` | 徽标点击跳 `Settings.ACTION_ACCESSIBILITY_SETTINGS` |

编译：`./gradlew :app:assembleDebug` 通过（仅 `setSystemUiVisibility` 的既有 deprecation 提示）。

### 真机验证清单

1. 安装 APK → 打开应用 → 点顶栏「快门 —」→ 在无障碍里开启「RC2 全局手柄」；
2. 回到仪表盘，观察顶栏徽标与底部日志；
3. **只半按**（不按到底）：期望出现 `Shutter  IDLE → HALF（FOCUS(半按) DOWN · scan xx · dev xx · a11y）`；
4. 按到底：期望 `HALF → FULL`，松开 `FULL → IDLE`；
5. 若只看到 `IDLE → FULL`（并伴随 `fullWithoutHalf`），说明半按没进 Android 层 → 执行方案 B 的 `getevent` 做最终判定；
6. 再打开 DJI Fly，重复步骤 3，验证「DJI Fly 前台时无障碍仍能收到按键」。

---

## 六、建议

1. **如果你的目标是「用半按当触发器」**（比如映射成 Xbox 手柄的某个键）：按现有证据，这条路在飞行中基本走不通。可行替代是**用全按 + 时间窗**做伪半按（按下 <150ms 且未松开 → 视为对焦意图），或者用 C1/C2 自定义键承担对焦。
2. **如果你的目标是「验证硬件是否支持半按」**：直接跑方案 B 的 `getevent`，比改代码快。
3. **如果只是想让对焦发生**：不需要做任何事——半按本来就会由 MCU 直接触发相机对焦，App 插手反而可能打断它。
4. **不要依赖开发助手的 `ShellCommandActivity` / system UID 走捷径**：它是产线工具，滥用会破坏系统分区与升级链，且不同固件版本随时可能移除。

---

## 七、第二轮（实测反馈）：无障碍没收获，改走协议嗅探

### 7.1 实测结果

用户真机反馈：开启无障碍按键过滤后，半按**没有任何事件**。这个否定结论本身很有价值，它把方案 A 排除了：

- 无障碍过滤发生在 `PhoneWindowManager` 之前（`AccessibilityManagerService.EventsDispatcher.notifyKey`），
  连这里都收不到，就说明**系统压根没生成 `KEYCODE_FOCUS`**；
- 配套的自检也补上了：`ShutterKeyService.SEEN` 统计服务看到过的**所有**按键。
  按两下音量键，数字涨 → 服务是活的，「抓不到半按」才是有效结论；数字不动 → 服务没开或被杀。

于是只剩两条路：内核 `getevent`（需要 shell，只能定性）和 `protocol` 协议层（应用内可做）。
本轮把后者做成了可执行的工具。

### 7.2 新增的嗅探链路

```
protocol 服务 ──┬─ 通配订阅（{4,2,0,6,6,-1} 等 5 种形态轮试）──┐
                └─ 逐条查询（cmdSet 6，cmd 0..255 轮流）────────┴─→ 按 (cmdSet,cmd) 归档
                                                                      ↓
                                                       逐字节 / 逐位比对 + 上升沿统计
                                                                      ↓
                                            一键学习：基线 3s → 按 2 次 → 复核 1 次
                                                                      ↓
                                       锁定位 → ShutterProbe.feedHalf() → 全 App 可用
```

| 文件 | 作用 |
|---|---|
| `rc2core/.../internal/RawProbe.java` | 新增。可以订阅任意过滤条件、查询任意 `(cmdSet, cmd)`，与产品用的 6/174 通道互不干扰 |
| `rc2core/.../ProtocolSniffer.java` | 新增。5 种订阅形态轮试 + 深扫 + 学习状态机 + 候选位判定 + 锁定后实时喂探针 |
| `rc2core/.../ShutterProbe.java` | 新增 `feedHalf()` / `feedFull()` / `viaProtocol()`，让「协议位」和「Android 按键」汇到同一状态机 |
| `rc2core/.../Rc2Controller.java` | `emitButton` 里把 `RcButton.SHUTTER` 的全按同步喂给探针，徽标能走 `半按 → 全按 → 空闲` |
| `app/.../page/SniffPage.java` | 新增。嗅探页，含无障碍自检、命令列表、候选位、学习倒计时 |
| `app/res/layout/page_sniff.xml` | 新增。1920×1080 画布，与现有两页同风格 |
| `app/.../ShutterKeyService.java` | 新增 `CONNECTED` / `SEEN` / `LAST` 三个静态自检量 |

### 7.3 怎么用

1. 仪表盘底部 →「半按嗅探 ›」；
2. 点「开始嗅探」：页面顶部会显示 5 种订阅形态各收到多少帧。
   若出现 `6/-1` 或 `0/-1` 收到成百上千帧，说明**通配订阅成功**，后面几乎一定能找到半按位；
   若只有 `6/174` 有帧，就依赖于深扫；
3. 点「一键学习」：
   - 前 3 秒 **不要碰快门**（记基线，这期间翻过的位一律当噪声丢掉）；
   - 提示出现后，**半按并保持约 5 秒**，松开，停顿一下，再重复一次（共 2 次）；
   - 系统会筛出「恰好 2 个上升沿、脉冲宽度合理、基线期零翻转」的位；
   - 进入复核：**再半按 1 次并保持约 5 秒**，只有「恰好 1 个上升沿」的位才被采纳。
4. 采纳后自动写进 `SharedPreferences("rc2_probe"/"half_bit")`，下次启动直接生效；
   仪表盘「快门」徽标会跟着半按变薄荷色，日志出现 `Shutter  IDLE → HALF（协议 6/xx bY.Z DOWN）`。

> 学习期间会对 `cmdSet 6` 的 `cmd 0..255` 轮流发查询（8ms 一条，一圈约 2 秒），
> **务必在地面做**，不要起飞。查询是只读 GET，但没必要给链路加压。

### 7.4 三种结果与对策

| 学习结果 | 含义 | 下一步 |
|---|---|---|
| 找到并复核通过 | 半按确实作为一个位在协议层流动 | 直接用 `ShutterProbe.GLOBAL.isHalfPressed()` 触发对焦；若要在 DJI Fly 前台时也生效，把 `ProtocolSniffer` 挪进常驻 Service |
| 有若干位翻转但次数对不上 | 采样太稀（一圈 2 秒，按得不够久） | 重学一次，每次按住 5 秒以上；或把 `SWEEP_GAP_MS` 调到 4ms、`SWEEP_MAX` 缩到 64 条常用命令 |
| 一个位都不动 | 半按在 MCU 固件里被直接转成对焦指令，**协议层也没有** | 到此为止：改「全按 + 150ms 时间窗」伪半按，或把对焦挂到 C1/C2 |

### 7.5 仍然建议顺手做的一次性验证

`adb shell getevent -lt /dev/input/event*` 里找 `code 80 (KEY_FOCUS)` / `code 211 (KEY_CAMERA)`。
若内核层也没有，上面第 3 行就是最终答案，不必再投入。

---

## 八、能不能监听「飞机指令」拿到半按？

### 8.1 原理上，半按确实会变成一条指令

链路是这样的：

```
快门半按 ──> RC 的 MCU ──> 封成「相机命令」──> 空中链路 ──> 飞机相机（开始对焦）
                              ↑
                    接收方 = CAMERA（设备号 1），不是 App
```

`ProtocolDict$DEVICE$*` 给出了这套寻址：`RC=6 / CAMERA=1 / FLYC(飞控)=3 / GIMBAL=4 / CENTER=5 / OSD=14 / BATTERY=11`。
**设备号就是命令集号**（RC 的按键包是 `6/174`，正好对上 `DEVICE$RC.TYPE=6`、`CMD$Keys.CMDID=174`）。
所以「半按对焦」一定是 **cmdSet = 1** 里某条 cmd，接收方 1。

问题只剩一个：**我们能不能看到它。**

### 8.2 三条可监听的信号，从强到弱

| 信号 | 位置 | 能不能拿到 | 备注 |
|---|---|---|---|
| **A. 上行相机命令本体** | cmdSet 1，receiver=CAMERA | 取决于 `protocol` 服务是否把「过路包」也分发给订阅者 | 这是"真正监听飞机指令"。如果服务只分发「接收方是本机」的包，就永远看不到 |
| **B. 相机 / OSD 回报** | cmdSet 1 的状态字段、`PUSH_*_OSD` | 可拿到（接收方是 RC/App） | OSD 结构里有 **`lastest_cmd`（最近执行的命令）**，是飞机侧最直接的公开字段；对焦状态也可能在相机状态包里 |
| **C. RC 侧的 `CameraSender`** | `ProtocolDict$DATA$RC$CameraSender`：`APP=2 / RC=1 / NO=0` | 可拿到（在 RC 自己的状态包里） | **最可能的突破口**：它记录「最近一次相机指令是谁发的」，硬件快门发的是 `RC=1`，App 按钮发的是 `APP=2`。半按时若该字段从 `NO` 变 `RC`，就是一个可靠的间接信号 |

### 8.3 为此补的能力

- `RawProbe` 现在把 **完整 15 int 报文头**交出来，并且区分「服务主动推送」与「查询应答」——
  一次性命令只能靠推送抓，查询是抓不到的；
- `ProtocolSniffer` 深扫从「只有 RC」扩到四条命令集：`RC(6) 0..255 / 相机(1) 0..63 / 飞控(3) 0..31 / OSD(14) 0..31`；
- 订阅形态新增 `{4,2,0,1,1,-1}`、`{4,2,0,5,5,-1}`，专门试「发往相机 / 中心」的那一侧；
- 学习流程新增**事件型候选**：基线期一声不响、按压窗口才冒出来的命令，会单列成
  「只在按压时出现的命令（疑似上行指令）」，并附 15 int 报文头，能直接看出 sender/receiver/cmd。

### 8.4 现实判断

- **A 线成功率偏低**：DJI 的 `protocol` 服务是给应用订阅「与自己相关的状态」的，不像抓包器。
  但 filter 里 `{4,2,0,6,6,174}` 这种「6,6」双设备写法说明它确实带寻址，换成 `1,1` 值得一试。
- **B/C 线成功率中高**：它们都是「结果态」，接收方是 RC，订阅合法。代价是**有延迟**（一帧链路 50–150ms），
  而且**无法区分「半按对焦」与「点屏幕对焦」**——如果只想要"对焦发生了"这个信号，够用。
- **兜底**：如果三条都空，结论就固定了——半按在 MCU 固件里直达相机，Android 侧任何一层都观测不到，
  只能改用「全按 + 时间窗」伪半按，或把对焦挂到 C1/C2。

---

## 九、USB 方案：监听链路完全重写

### 9.1 为什么推倒重来

前八节的所有尝试都基于同一个假设：**数据在 Android 的应用层能拿到**。实测把这个假设打掉了——
不管是无障碍按键过滤、`6/174` 按键报文，还是深扫整个 `cmdSet 6`，半按都不留痕迹。

于是只剩一种可能：半按发生在**比应用层更靠下的地方**。而遥控器内部 Android 与 MCU 之间的
物理通道，最可能就是 USB。所以本轮把整个监听链路换成从 USB 端点直接读原始字节流，
不再是「向某个服务要数据」，而是「自己从总线上捡数据」。

### 9.2 新通路

```
快门按下
   ↓
RC 的 MCU ──USB 端点──> UsbLink（bulk/interrupt 读线程）
                              ↓ 原始字节流（可能半帧/粘包）
                        DjiFrame.Splitter（0x55 魔数 + 长度 + CRC16）
                              ↓ 一条条完整帧
                 ┌────────────┴────────────┐
            UsbDecoder                 UsbBitLab
        （17B 按键帧 / 12B 摇杆帧）   （逐位上升沿统计）
                 ↓                          ↓
        摇杆 / 按键 / 挡位           ShutterProbe → 半按状态
                 ↓                          ↓
            Rc2Controller ──────────────► 仪表盘 / 任何宿主代码
```

关键差别：**这条链路上没有任何一处依赖 DJI 的私有 Binder 服务**。只要内核把端点暴露给应用，
数据就能到手；拿不到，也在报告里写清楚拿不到的原因。

### 9.3 新增的六个类

| 文件 | 职责 |
|---|---|
| `usb/UsbTopology.java` | 只读普查：`UsbManager` 设备表、`/sys/bus/usb/devices`、`/dev/bus/usb`、`/dev/hidraw*`、`/proc/bus/input/devices` 的 `B: KEY=` 位图。最后给一句结论 |
| `usb/UsbLink.java` | 申请授权 → `openDevice` → `claimInterface` → 每个 IN 端点一个读线程。区分「超时」和「故障」 |
| `usb/DjiFrame.java` | 流式切帧：三种长度解释 × 两种边界，用 CRC16 挑对的那个；CRC 连续失败自动降级为只按长度切 |
| `usb/UsbDecoder.java` | 帧尾倒推负载：17 字节当按键帧、12 字节当摇杆帧，并校验推出来的头长是否合理 |
| `usb/UsbBitLab.java` | 位学习器：基线 3s → 按压 12s（按两次）→ 复核 9s（按一次），按帧长分桶统计 |
| `UsbInput.java` | 把上面五个串起来，对 `Rc2Controller` 只暴露语义事件 |

旧的 `DjiProtocol` / `RawProbe` / `ProtocolSniffer` / `SniffPage` 已移出构建，归档在
`docs/legacy/protocol/`，附废弃原因。

### 9.4 权限与前提（这是唯一会卡住的地方）

| 项 | 内容 |
|---|---|
| **系统特性** | `android.hardware.usb.host`。manifest 里声明 `required=false`，没有也能装，只是通道打不开 |
| **运行时授权** | `UsbManager.requestPermission(device, PendingIntent)`，系统弹一次框。已预授权的设备直接通过 |
| **接口占用** | 必须 `claimInterface(itf, true)`。若厂商的 `protocol` 服务已独占该接口，这一步会失败——这是最可能的拦路虎 |
| **免 root 边界** | 只能走 `UsbManager`。直接 `open()` `/dev/bus/usb/00X/00Y` 需要节点权限，普通应用拿不到 |

不需要的权限：`USB_PERMISSION` 不是真权限，不用在 manifest 里声明；也不需要 root。

### 9.5 半按怎么定位

原始字节流没有字段说明，所以用**受控实验**代替猜测：

1. **基线 3 秒**：不要碰快门。这期间翻过的位（心跳、计数器、电量）全部记入 `baselineRises`，后面一律排除；
2. **按压 12 秒**：半按 → 保持 4 秒以上 → 松开 → 停 1 秒 → 再来一次。判据是**恰好 2 次上升沿**；
3. **复核 9 秒**：再半按一次。只保留这 9 秒里**恰好 1 次上升沿**的位。

三道筛子分别对应三种误判：常驻抖动、偶发变化、时间上的巧合。脉宽还要落在 150–9500ms，
太短是毛刺，太长是别的状态位。

锁定后把签名（`帧长:位序号`）存进 `SharedPreferences("rc2_usb"/"half_bit")`，
下次启动直接恢复；此后每来一帧就读这一位，电平一变就喂 `ShutterProbe.feedHalf()`。

### 9.6 五种结果与对策

| 报告里的结论 | 含义 | 下一步 |
|---|---|---|
| 应用层可枚举到 USB 设备 | 最理想，端点可读 | 直接跑学习流程 |
| 内核有设备、`UsbManager` 看不到 | 被内核驱动或厂商服务接管 | 换思路：只能靠 `adb shell` + `getevent`，应用内无解 |
| 内核与应用层都没有 USB 设备 | 输入不走 USB | USB 方案在物理层不成立，回退到「全按 + 时间窗」或 C1/C2 |
| 端点打开了但 5 秒无数据 | 端点通着但没内容 | 换端点重试；或在设备上做一次握手动作 |
| 学习跑完一个位都没动 | 半按不进这条总线 | 到此为止 |

### 9.7 使用步骤

1. 装 APK，进「USB 实验室」；
2. 首屏自动生成普查报告，**先看最后的「结论」三条**——它直接告诉你是哪一种情形；
3. 若显示可枚举：点「重开通道」看端点是否 claim 成功；
4. 「开始学习半按」→ 3 秒别动 → 半按两次（各 4 秒以上）→ 再按一次复核；
5. 锁定后回仪表盘，「快门」徽标会跟着半按变薄荷色，日志出现 `IDLE → HALF（USB len17 b2.5）`。

> 学习期间只是被动收数据，不发任何查询包，所以**不像旧方案那样会给链路加压**。
> 但仍建议在地面做第一次。

### 9.8 与旧方案对比

| 维度 | protocol 方案（已废） | USB 方案（当前） |
|---|---|---|
| 数据来源 | 厂商 Binder 服务分发 | 自己读 USB 端点 |
| 依赖 | DJI 私有接口、system UID 更好 | 仅 `android.hardware.usb` |
| 半按 | 实测拿不到 | 有理论可能，靠位学习定位 |
| 失败时的信息量 | 「没数据」 | 分层报告：无 Host / 无设备 / 接口被占 / 端点无数据 / 位不翻转 |
| 抗固件升级 | 差（接口签名会变） | 好（物理层协议稳定） |
| 成本 | 低（只读） | 中（要处理切帧、CRC、位学习） |

---

## 十、「授权未通过 / 30 秒无响应」怎么破

### 10.1 先纠正一个误导：那条文案是硬编码的

旧实现里失败文案写死了「USB 授权未通过（30 秒无响应或被拒绝）」，**但代码可能在 1 秒内就走到这里**。
所以「30 秒无响应」并不代表真的等了 30 秒，它把三种完全不同的失败混成了一句话：

| 真实情况 | 旧代码的表现 | 真实原因 |
|---|---|---|
| 广播根本没发 | 等满 30 秒，`granted=false` | 系统没有授权界面，`requestPermission` 内部起不来 Activity |
| 广播发了但没带结果字段 | **立刻**返回 `granted=false` | `PendingIntent` 用了 `FLAG_IMMUTABLE`，系统无法回填 `EXTRA_PERMISSION_GRANTED` |
| 系统明确拒绝 | 立刻或很快返回 | 设备已被其他进程独占，或策略禁止第三方打开 |

第二种是**纯代码 bug**，而且症状和第三种一模一样。旧代码用的是：

```java
PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE   // ← 错在这
```

系统是这样回填结果的：`pi.send(ctx, 0, intent)`，其中 `intent` 里带着 `EXTRA_PERMISSION_GRANTED`。
`FLAG_IMMUTABLE` 会让这次 fill-in 被丢弃，接收方读到的永远是默认值 `false`。
**广播照样会到**，所以看起来像「授权被拒」，实际上用户可能压根没看到弹框，或者已经点了允许。

### 10.2 本次改了什么

| 改动 | 解决什么 |
|---|---|
| `PendingIntent` 改用可变（API 31+ 加 `FLAG_MUTABLE`，更低版本默认即可变） | 结果能真正回填，不再假失败 |
| 授权结果接收器改为 **manifest 静态注册** + 动态注册双保险 | 静态那个走组件广播，不受 Android 13+ 动态注册 flag 限制，进程被杀也能拉起 |
| 区分「广播没来」/「来了但缺字段」/「明确拒绝」三种结局 | 看到的不再是一句笼统的「30 秒无响应」 |
| 等待时长 30s → 15s，并在等待前先回调一次状态 | 界面能显示当前卡在哪一步 |
| 授权链：`hasPermission` → **`openDevice` 直接试探** → `requestPermission` → 超时后二次复核 | 系统静默放行、或授权延迟到达的情况都能兜住 |
| 授权成功后 **重新枚举一次 `UsbDevice`** | 授权前拿到的对象可能没有接口描述，直接 `claimInterface` 必失败 |
| 候选设备不再按「有没有 IN 端点」过滤 | 未授权时 `getInterfaceCount()` 返回 0，会把真实设备误判成「没有可读端点」而提前判死 |
| 新增 `UsbPermission.diagnose()` 与「权限诊断」按钮 | 把 uid / SELinux / 每个设备的授权态 / `/dev/bus/usb` 节点权限一次摆出来 |
| 新增 `device_filter.xml` + `USB_DEVICE_ATTACHED` 过滤 | 「默认打开」路线：勾选一次，永不再问 |
| 报告区**长按即复制** | 设备上取日志不便，剪贴板是最短路径 |

### 10.3 现在装上应该看到什么

进「USB 实验室」，先看**权限诊断**最后那段「结论」，它会给出四种之一：

1. **至少有一个设备能打开** → 授权这关过了，问题在后面（接口被占 / 端点无数据）；
2. **全部未授权 + 系统不弹框** → 授权界面缺失，走 10.4 的路线；
3. **有授权记录但打不开** → 句柄被厂商 `protocol` 服务持有，授权再成功也白搭；
4. **一个设备都看不到** → 分两种：若有 accessory 列表，说明 USB 口只做从机，物理层不成立；
   若连 accessory 都没有，说明 MCU 通道压根不走 USB。

### 10.4 四条出路，按可行性排序

**路线 A：拔插一次，让它成为「默认应用」（推荐先试）**

本版已在 `MainActivity` 注册了 `USB_DEVICE_ATTACHED` 过滤。把 USB 设备拔掉再插上（或让遥控器重新枚举该设备），
系统会问「用哪个应用打开此 USB 设备」——勾选「默认使用本应用」。

这条走的是 PackageManager 的默认应用机制，**不经过 `UsbPermissionActivity`**，
所以在授权对话框被裁掉的固件上照样能用。之后 `hasPermission()` 直接为 true。

**路线 B：装成 priv-app，拿 `MANAGE_USB`**

`MANAGE_USB` 是 `signature|privileged` 权限，装进 `/system/priv-app` 并加白名单即可获得，
拿到之后 `hasPermission()` 恒为 true，不需要任何对话框。需要 root + `/system` 可写。

```bash
bash docs/tools/install-as-system.sh
```

脚本会做完卸载 → remount → push → 写 `privapp-permissions-rc2.xml` → 重启 → 验证。
如果 `adb remount` 报只读，先 `adb disable-verity && adb reboot`。

**路线 C：用 adb 看系统到底为什么拒**

```bash
adb logcat -c && adb logcat -s UsbService UsbHostManager UsbUserPermissionManager
```

然后点一次「重开通道」，看有没有这几类行：

- `Unable to start UsbPermissionActivity` / `ActivityNotFoundException`
  → 授权界面不存在，**确认走路线 A 或 B**；
- `Ignoring requestPermission for ... from ...`
  → 系统策略层直接吞掉，路线 B 才行；
- `granted=true` 但应用仍读到 false
  → PendingIntent 可变性问题（本版已修）。

**路线 D：确认设备到底在不在**

```bash
adb shell ls -l /dev/bus/usb/*/          # 有没有节点、权限位是什么
adb shell cat /sys/bus/usb/devices/*/idVendor
adb shell getevent -lt                   # 半按有没有进内核（终局判据）
```

如果 `/dev/bus/usb` 里根本没有对应节点，或 `getevent` 里压快门什么也不出，
那么结论就固定了：半按在 MCU 固件内部直达相机，**Android 侧任何一层都观测不到**。
此时不该继续在 USB 上加投入，应转「全按 + 150ms 时间窗」伪半按，或把对焦挂到 C1/C2。

### 10.5 一句话总结当前状态

授权这条已经修掉了两个确定的 bug（不可变 PendingIntent、授权后未重新枚举设备），
并补了「默认打开」和「priv-app」两条绕过路径。
但**如果系统里根本没有可枚举的 USB 设备，以上全部无效**——
所以先看诊断结论的第 4 种情形，那是决定要不要继续投入的分水岭。
