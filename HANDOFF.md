# HANDOFF · claude-chan-android

## 现在是什么状态

2026-10-23：从 `E:\cc_workplace\claude-chan-pet`（Electron）移植到 Android，
产物 `dist/claude-chan-pet-android-0.1.0.apk`（18.07 MB，debug 签名）。

已在 Android 15（API 35）x86_64 模拟器、1080×2400 / 420 dpi 上跑通：

- 权限引导、悬浮窗、通知
- 透明悬浮窗贴底、拖拽、甩抛、落地回弹、左右边界
- 长按菜单：碎碎念 / 换表情 / 动作 / 回到右下角 / 退出
- 5 段透明 WebP 动作、点击 Q 弹、随机碎碎念
- 横竖屏切换：横屏贴右侧工作区，转回竖屏回到底部
- 停止 / 再次启动、通知栏常驻通知

## 关键实现决定

- **WebView + `TYPE_APPLICATION_OVERLAY` 前台服务**：复用桌面版 HTML/CSS/JS 的立绘与气泡，
  窗口位置由 `PetService` 原生管，避免 WebView 移动时触摸坐标跳变。
- **触摸全部由原生接**：`WebView.setOnTouchListener` 返回 true，原生处理点击 / 长按 / 拖拽 / 抛掷，
  再通过 `PetHost.tap/drag/dragEnd` 通知渲染层做 Q 弹。
- **VP9 alpha WebM 不能用**：Android MediaCodec 会丢 alpha；`tools/convert_anims.py`
  用 ffmpeg 解出 `yuva420p`，再编码成 animated WebP，Skia 能正确显示透明。
- **每次播放新建 `<img>`**：animated WebP 在同一元素上重设 src 不会从头播；
  新建元素能保证从第 0 帧开始。
- **标准 debug keystore**：命令行和 Android Studio 都用 `%USERPROFILE%\.android\debug.keystore`，
  两边签名一致，可以互相覆盖安装。

## 构建

```powershell
# 无 Gradle
pwsh -NoProfile -File tools\build-apk.ps1 `
  -JdkHome E:\android-toolchain\jdk-17 `
  -SdkRoot E:\android-toolchain\sdk

# Android Studio / Gradle
.\gradlew.bat assembleDebug
```

重新生成动作资源：

```powershell
python -m pip install imageio-ffmpeg
python tools\convert_anims.py
```

## 已知问题 / 边界

- 矩形悬浮窗没有逐像素命中；立绘周围的透明角仍会吃触摸。窗口已经压到 240×240 dp，
  影响不大，但压在设置页上时要注意。
- 目前是**独立桌宠**，不读 Claude Code hooks。局域网事件桥还没做，设计写在 README 末尾。
- 横屏设置页右侧没有额外 inset padding；宠物服务的窗口已经按 `workLeft/workRight` 避开侧边导航栏。
- 开机自启、电池白名单、通知栏“停止”按钮未在真机验证；国产 ROM 后台策略差异大。
- 动作 WebP 共约 17 MB；要更小改 `tools/convert_anims.py` 的 `HEIGHT` / `QUALITY`。

## 下一次接手

1. 真机插上后 `adb install -r dist\...apk`，确认悬浮窗、通知、后台存活。
2. 若要继续桌面联动：先做电脑端 SSE（`0.0.0.0:8765`），再做 Android `HttpURLConnection` 客户端。
3. 改 UI 时优先看 `MainActivity.java`（纯代码布局）和 `PetService.java`（窗口/物理/菜单）。
