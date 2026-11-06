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
