# 抖音自动刷（抖音极速版自动上滑辅助工具）

一个基于 Android「无障碍服务（AccessibilityService）」的本地工具，自动在**抖音极速版**中模拟上滑手势切换视频。它不修改抖音 APK，而是作为一个独立 App 运行在手机上，通过系统无障碍能力控制抖音。

> ⚠️ **合规与风险提醒**
> 抖音极速版的用户协议通常禁止自动化脚本；若用于刷金币/奖励，可能被判定为异常操作、影响账号。本工具仅用于技术研究与个人自动化，请合规使用，风险自负。作者不对任何账号后果负责。

---

## 一、实现原理

| 能力 | 实现方式 |
|------|----------|
| 切换视频 | 通过 `AccessibilityService.dispatchGesture()` 模拟一段垂直上滑手势（屏幕中部由下往上），与真人上滑一致 |
| 「播完即下一个」 | 轮询读取抖音窗口中的进度文本（形如 `0:15/0:30`），进度 ≥ 95% 视为播完，立即上滑 |
| 兜底 | 当进度文本读不到（取决于抖音版本实现）时，按「基础间隔 + 随机抖动」定时上滑 |
| 防检测 | 每次实际间隔 = 基础间隔 + `[0, 随机抖动)` 秒，节奏不固定 |
| 节流 | 两次上滑之间至少间隔 2.5 秒，避免手势重叠 / 切换过快 |

> 抖音不会对外暴露「视频播完」回调，进度文本是否可读取依赖其版本；因此**定时兜底是必要的保底**。如果你发现它总是按固定间隔切而不是播完立刻切，多半是该版本没暴露进度文本，属正常现象。

---

## 二、环境要求

- **Android Studio**（Hedgehog / Iguana / Jellyfish 等较新版本均可）
- 手机：Android 7.0（API 24）及以上（模拟手势需要 `dispatchGesture`）
- 手机已开启「开发者选项 → USB 调试」（用于安装；也可用 AS 的无线调试）
- 已安装**抖音极速版**（包名 `com.ss.android.ugc.aweme.lite`）

---

## 三、编译与安装

### 方式 A：Android Studio（推荐）
1. 打开 Android Studio → `File → Open`，选择本工程目录 `DouyinAutoSwiper`。
2. 等待 Gradle 同步完成（首次会下载依赖，需联网）。
3. 连上手机，确认已授权 USB 调试。
4. 点击工具栏「Run ▶」或 `Build → Make Project` 后 `Run 'app'`。
5. 首次构建若提示选择设备，选择你的手机即可；APK 会自动安装并启动。

> 若想生成可独立分享的安装包：`Build → Build Bundle(s) / APK(s) → Build APK(s)`，产物在 `app/build/outputs/apk/debug/app-debug.apk`，拷贝到手机安装即可。

### 方式 B：命令行（可选）
若你习惯命令行，需先在本目录生成 Gradle Wrapper（要求本机已装 Gradle）：
```bash
gradle wrapper --gradle-version 8.0
./gradlew assembleDebug
```
再用 `adb install app/build/outputs/apk/debug/app-debug.apk` 安装。

---

## 四、开启权限与运行

1. 打开刚装好的「抖音自动刷」App。
2. 点 **「前往系统设置开启无障碍权限」**，在系统「无障碍」列表里找到 **「抖音自动刷」** 并**开启**。
   - 系统会弹警告，确认即可（本工具只在本地模拟手势，不上传任何数据）。
3. 回到 App，打开 **「自动滑动开关」**。
4. 打开**抖音极速版**，进入任意视频播放页，即可看到自动上滑切换。
5. 想停止：回到本 App 关闭开关，或在系统无障碍里关掉服务。

### 参数说明
- **基础滑动间隔（秒）**：视频没播完时的兜底切换间隔，默认 15 秒，最小 3 秒。
- **随机抖动（秒）**：每次实际间隔会额外随机加 `0~N` 秒，默认 5 秒，用于让节奏不规律、降低被识别为脚本的概率。设为 0 即完全固定间隔。

---

## 五、适配普通版抖音（或其他包名）

本工具默认只监听抖音极速版。若要用于**普通版抖音**或其它版本：

1. 修改 `app/src/main/res/xml/accessibility_service_config.xml` 的
   `android:packageNames="com.ss.android.ugc.aweme.lite"`
   改为 `"com.ss.android.ugc.aweme"`（普通版）。
2. 修改 `app/src/main/java/com/example/douyinautoswiper/AutoSwipeService.java` 中的
   `private static final String TARGET_PACKAGE = "com.ss.android.ugc.aweme.lite";`
   改成同样的包名。
3. 重新编译安装。

> 也可把 `packageNames` 留空（监听所有应用），但那样会在任意 App 里都尝试滑动，不推荐。

---

## 六、常见问题

- **开了开关但没反应？** 确认系统无障碍里本服务已开启，且抖音极速版在前台播放页（非首页/个人页）。
- **总是固定间隔切，不「播完即切」？** 当前抖音版本可能未暴露进度文本，正常走兜底逻辑；可把「基础间隔」调小一点逼近视频时长。
- **Android 版本低于 7.0？** 无法使用，模拟手势需要 API 24+。
- **耗电？** 轮询为 1 秒一次、几乎无感；抖音本身耗电远大于此。

---

## 七、项目结构

```
DouyinAutoSwiper/
├── app/
│   ├── build.gradle
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/douyinautoswiper/
│       │   ├── MainActivity.java        # 界面：开关、参数、跳转设置
│       │   ├── AutoSwipeService.java     # 核心：无障碍服务 + 滑动逻辑
│       │   └── AppPreferences.java       # 参数读写
│       └── res/
│           ├── layout/activity_main.xml
│           ├── values/{strings,colors,themes}.xml
│           ├── drawable/ic_launcher.xml
│           └── xml/accessibility_service_config.xml
├── build.gradle
├── settings.gradle
└── gradle.properties
```
