# Astra Launcher

Astra Launcher 是一个面向 Android 8+ 横屏设备的轻量桌面原型。当前版本专注于桌面本体，不包含原厂桌面接管、无障碍服务或悬浮控件；网络仅用于地点与天气。

![Astra Launcher 主页截图](docs/images/astra-launcher-home.png)

## 当前功能

- Kotlin + 传统 View/XML，最低支持 API 26。
- 声明 `HOME`、普通 `LAUNCHER` 和 `LEANBACK_LAUNCHER` 入口。
- 同一套响应式主页同时支持直接触摸和方向键焦点，不提供设备模式选择。
- 合并枚举普通与 Leanback 应用入口，并按 Activity 组件去重。
- 2×4 触摸收藏网格和 6 列方向焦点网格。
- 全部应用、应用启动、收藏切换和系统应用信息入口。
- 公网 IP 粗略地点与当前天气，支持多数据源降级和本地缓存。
- 每日图片主页背景，依次尝试 Bing、Wikimedia Commons POTD 和 NASA APOD。
- 主页右上角直接提供纯图标的 Wi-Fi、蓝牙、信号源、文件和 Astra 设置入口。
- Wi-Fi、蓝牙只打开系统页面，不直接修改配置；系统设置入口位于 Astra 设置页。
- 通过 Android TV Input Framework 枚举并打开系统报告的外部信号源。
- 全部二级页面均有明确返回按钮；主页不会因 Back 而退出。
- 只申请网络权限，不申请定位、悬浮窗或无障碍权限。

## 地点与天气

网络请求不进入首屏启动路径：主页先显示本地缓存，再在后台刷新。

- 地点：优先 IPWhois；没有可用坐标时备用 wttr.in。
- 天气：优先 Open-Meteo；备用 wttr.in。
- 离线：所有网络源失败时继续显示最近一次成功结果。
- 刷新：地点最多每 24 小时一次，天气最多每 30 分钟一次。
- 隐私：不读取 GPS，不保存公网 IP；IP 定位可能落在运营商或 VPN 的出口城市。

Open-Meteo 免费接口仅适用于非商业用途并要求署名；完整的数据来源、许可和隐私说明统一放在 Astra 的设置页。

## 每日图片

主页先显示最近一次成功图片，再在后台按 `Bing → Wikimedia Commons POTD → NASA APOD` 的顺序刷新当天图片。候选文件下载完成并通过尺寸与格式校验后才会替换旧缓存；全部来源失败时继续使用最近缓存。当前不附带内置壁纸，没有缓存时保留 Astra 的纯色渐变背景。

设置页显示当前图片的标题、来源、作者或版权及许可，并提供来源页面入口。NASA APOD 当天为视频时会被跳过。

## 快捷设置与信号源

主页只有在系统能解析对应标准 Intent 时才显示 Wi-Fi 或蓝牙入口。信号源页只显示 `TvInputManager` 报告的 HDMI、AV 等透传输入；点击信号源会使用标准 TV 内容 URI 交给系统播放器处理。由于部分电视不会在应用列表中公开系统设置，Astra 设置页始终保留一个可用时显示的“打开系统设置”入口。

“文件”入口优先打开系统声明的文件管理应用；旧系统或没有完整文件管理器时，降级到 Storage Access Framework 文档选择器。选择器可以浏览系统暴露的 U 盘等存储位置，选中文件后 Astra 会将其交给对应的播放器或查看器。整个流程不申请存储权限。

当前版本不检查品牌、型号或厂商包名，也不内置小米、联发科等私有输入面板。普通手机模拟器没有 TV 输入服务，因此会显示“没有报告可用的外部信号源”；需要在真实电视上验证切换行为。

## 构建

推荐使用 Android Studio 自带的 JDK 21：

```bash
./gradlew testDebugUnitTest assembleDebug
```

Debug APK 输出到：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 使用手机模拟器预览

普通手机模拟器足够验证横屏布局、触摸、应用抽屉、返回链路和方向键焦点。项目的 `LauncherActivity` 固定为横屏。

需要精确模拟 1024×600 时，可只在测试模拟器中执行：

```bash
adb shell wm size 1024x600
adb shell wm density 160
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n org.astrasec.launcher/.LauncherActivity
```

方向键可通过模拟器键盘或 ADB 验证：

```bash
adb shell input keyevent KEYCODE_DPAD_LEFT
adb shell input keyevent KEYCODE_DPAD_RIGHT
adb shell input keyevent KEYCODE_DPAD_UP
adb shell input keyevent KEYCODE_DPAD_DOWN
adb shell input keyevent KEYCODE_DPAD_CENTER
adb shell input keyevent KEYCODE_BACK
```

恢复模拟器显示设置：

```bash
adb shell wm size reset
adb shell wm density reset
```

## 设计边界

代码不检查厂商品牌、机型、厂商包名或固定物理分辨率。触摸与方向键始终同时可用：触摸输入保持直接操作，方向键输入进入标准焦点导航。网格只根据当前窗口可用宽度响应式计算列数。外部应用中的系统级返回不属于 Launcher 本体。
