# 归档：protocol Binder 方案（已废弃）

这四个文件是「通过 DJI `protocol` Binder 服务拿遥控器按键」的完整实现，**已从构建中移除**，
仅作参考保留。

## 为什么废弃

真机实测（2024 版 RC2 固件）逐条排除：

| 通道 | 结果 |
|---|---|
| 无障碍按键过滤（`FLAG_REQUEST_FILTER_KEY_EVENTS`） | 半按**没有任何事件**。该过滤发生在 `PhoneWindowManager` 之前，说明系统根本没生成 `KEYCODE_FOCUS` |
| `6/174` 按键报文 | 半按时整帧字节不变 |
| 深扫 `cmdSet 6` 全命令 + 通配订阅 | 无可复现的半按位 |

结论：半按在 MCU 固件里被直接转成对焦指令发给飞机相机，不上报给 Android 侧任何一层。

## 各文件作用

- `DjiProtocol.java` — `transact(2)` 订阅 / `transact(4)` 查询摇杆，15 int 头 + 负载解包
- `RawProbe.java` — 通用通道：任意 filter 订阅、任意 `(cmdSet, cmd)` 查询
- `ProtocolSniffer.java` — 5 种订阅形态轮试 + 深扫 + 位学习状态机
- `SniffPage.java` / `page_sniff.xml` — 嗅探界面

## 如果要复用

`ProtocolSniffer` 的位学习思路被新方案的 `rc2core/usb/UsbBitLab.java` 继承并改进
（按帧长分桶、时间兜底推进、复核去伪）。换数据源即可。
