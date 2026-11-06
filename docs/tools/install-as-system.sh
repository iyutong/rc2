#!/usr/bin/env bash
#
# 把「RC2 输入监视」装成 priv-app 并授予 MANAGE_USB。
#
# 用途：当系统根本不弹 USB 授权框（定制固件裁掉了 UsbPermissionActivity），
# 拔插设备也不会问「用哪个应用打开」时，这是免 root 改装里唯一能给普通应用
# 补上 USB 权的办法 —— 拿到 MANAGE_USB 后 hasPermission() 恒为 true，
# 完全不需要授权对话框。
#
# 前提：设备已 root（adb root 能成功），且 /system 可 remount。
# 风险：改 /system 会动到系统分区；先确认能进 recovery 再动手。
#
# 用法：
#   bash docs/tools/install-as-system.sh [apk路径]
#
set -euo pipefail

ADB="${ADB:-adb}"
APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
PKG="local.rc2.xbox"
DIR="/system/priv-app/Rc2Input"
PERM_FILE="/system/etc/permissions/privapp-permissions-rc2.xml"

if [ ! -f "$APK" ]; then
    echo "找不到 APK：$APK" >&2
    echo "先执行 ./gradlew :app:assembleDebug" >&2
    exit 1
fi

echo "== 1/7 检查设备与 root =="
"$ADB" wait-for-device
"$ADB" root || { echo "adb root 失败：设备未 root，此路不通" >&2; exit 1; }
sleep 1
"$ADB" wait-for-device

echo "== 2/7 卸载普通安装的同包名应用（priv-app 与 data/app 不能共存）=="
"$ADB" uninstall "$PKG" >/dev/null 2>&1 || true

echo "== 3/7 重新挂载 /system 为可写 =="
"$ADB" remount || {
    echo "adb remount 失败，尝试手动重挂" >&2
    "$ADB" shell 'mount -o rw,remount /' || {
        echo "无法挂载可写。/system 是只读的（AVB/dm-verity），需要先 disable-verity 再重启。" >&2
        echo "  adb disable-verity && adb reboot && adb root && adb remount" >&2
        exit 1
    }
}

echo "== 4/7 推送 APK 到 priv-app =="
"$ADB" shell "mkdir -p $DIR"
"$ADB" push "$APK" "$DIR/Rc2Input.apk"
"$ADB" shell "chmod 644 $DIR/Rc2Input.apk"

echo "== 5/7 写 privapp-permissions 白名单 =="
# Android 9+ 强制白名单：priv-app 里声明了特权权限却没进白名单，开机时该应用会被拒载
cat > /tmp/privapp-permissions-rc2.xml <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<permissions>
    <privapp-permissions package="local.rc2.xbox">
        <permission name="android.permission.MANAGE_USB" />
    </privapp-permissions>
</permissions>
XML
"$ADB" push /tmp/privapp-permissions-rc2.xml "$PERM_FILE"
"$ADB" shell "chmod 644 $PERM_FILE"

echo "== 6/7 重启 =="
"$ADB" reboot
"$ADB" wait-for-device

echo "== 7/7 验证 =="
sleep 3
"$ADB" shell "dumpsys package $PKG | grep -E 'codePath|MANAGE_USB' || true"
echo
echo "装好了。应用现在应该出现在 /system/priv-app/Rc2Input，"
echo "且 dumpsys 里能看到 android.permission.MANAGE_USB。"
echo "打开应用的「USB 实验室」，权限诊断里应当显示设备已授权。"
