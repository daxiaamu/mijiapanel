# 米家中控模式 / Xiaomi Home Control Panel

使米家 APP 在手机上也可以显示中控入口和开启中控模式。

已在米家 11.5.705 测试通过。

功能：

- 普通米家模式保持系统原始 DPI、手机布局和背景尺寸。
- 保持米家设置中的“全屋中控”入口可见，但不会自动开启或进入中控模式。
- 仅当米家自身的 `pad_mode_enable=true` 时，将界面调整为约 600dp 的平板尺寸并启用平板判断。
- 进入中控模式及右上角退出均使用米家自身逻辑。
- 中控界面自动隐藏状态栏，边缘下滑时可临时显示。
- 使用 Modern Xposed API 102。
- 通过 `META-INF/xposed/scope.list` 自动推荐并同步唯一作用域 `com.xiaomi.smarthome`。
- `staticScope=true`，避免把模块误选到无关应用。

## 使用

安装 APK 后，在支持 Modern Xposed API 102 的 LSPosed/Vector 实现中启用模块。作用域应自动选择
“米家”；强制停止米家后重新打开。

## 免责声明

本项目为非官方开源模块，与小米或米家无关。使用前请自行评估兼容性与风险。
