# 米家中控模式 / Xiaomi Home Control Panel

使米家 APP 在手机上也可以显示中控入口和开启中控模式。

已在米家 11.5.705 测试通过。

功能：

- 保持米家设置中的“全屋中控”入口可见，但不会自动开启或进入中控模式。
- 使用 Modern Xposed API 102。
- 通过 `META-INF/xposed/scope.list` 自动推荐并同步唯一作用域 `com.xiaomi.smarthome`。

## 使用

安装 APK 后，在支持 Modern Xposed API 102 的 LSPosed/Vector 实现中启用模块。作用域应自动选择
“米家”；强制停止米家后重新打开。

## 免责声明

本项目为非官方开源模块，与小米或米家无关。使用前请自行评估兼容性与风险。
