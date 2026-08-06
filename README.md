# 米家中控模式 / Xiaomi Home Control Panel

使米家 APP 在手机上也可以显示中控入口和开启中控模式。

已适配米家 11.5.705、11.6.501、11.6.625、11.6.701、11.6.703、11.6.705。其他版本会尝试自动识别关键 Hook 点，但不保证完全兼容。

功能：

- 保持米家设置中的“全屋中控”入口可见，但不会自动开启或进入中控模式。
- 使用 Modern Xposed API 102。
- 通过 `META-INF/xposed/scope.list` 推荐“米家”和“系统框架”作用域。

## 使用

安装 APK 后，在支持 Modern Xposed API 102 的 LSPosed/Vector 实现中启用模块。基础功能需要勾选
“米家”；使用“人在检测”时还需勾选“系统框架”并重启设备。

## 免责声明

本项目为非官方开源模块，与小米或米家无关。使用前请自行评估兼容性与风险。

## 许可证

本项目以 GNU General Public License v3.0 或更高版本（`GPL-3.0-or-later`）发布，详见 [LICENSE](LICENSE)。
