# 双机互联 OmniLink（Android ↔ iPhone 通知同步与来电接听）

对标「O+互联」核心双持痛点设计的**免 Root / 免越狱、纯近场无服务器**跨生态双机协同工具，同时包含 **Android 原生端 (Kotlin + Jetpack Compose)** 与 **iOS 原生端 (Swift + SwiftUI + CallKit + CoreBluetooth)**。

---

## 核心功能矩阵

| 跨端方向 | 通知实时同步 | 来电显示与接听/挂断控制 | 底层通信协议 |
| :--- | :--- | :--- | :--- |
| **iPhone → 安卓** | 实时弹出微信、短信、钉钉等所有系统通知（显示中文应用名） | 锁屏唤醒全屏来电界面 + 状态栏横幅，一键控制 **接听 / 拒接** iPhone 电话 | **Apple ANCS**（苹果系统原生蓝牙通知中心服务）+ TLV 分片重组器 |
| **安卓 → iPhone** | `NotificationListenerService` 实时捕获通知，唤醒 iOS 后台弹出横幅（同步至 Apple Watch） | 唤醒 **iOS 原生 CallKit 全屏锁屏电话界面**，右滑直接控制安卓 `TelecomManager` 接通/挂断/开免提 | **BLE 自定义分片双工通道** + **Wi-Fi Bonjour (`_omnilink._tcp`) 高速直连** |

---

## 目录结构

```text
OmniLink/
├── .github/workflows/
│   └── build-packages.yml     # GitHub Actions 自动编译产出 APK + 未签名 IPA
├── android/                   # Android 原生工程 (Kotlin + Jetpack Compose, API 28-34)
│   └── app/src/main/java/com/omnilink/bridge/
│       ├── ble/               # BLE 外设广播 + GATT Server + Apple ANCS 客户端订阅与分包重组
│       ├── lan/               # Bonjour/NSD 零配置发现 + TCP 高速帧通道
│       ├── telephony/         # 安卓来电监听、联系人姓名匹配、远程接听/挂断与自动免提
│       ├── service/           # 常驻前台保活服务、通知监听服务、全屏来电唤醒
│       └── ui/                # Material 3 互联主页、全屏来电页、通知中心与联调面板
└── ios/                       # iOS 原生工程 (Swift 5 + SwiftUI + XcodeGen, iOS 16+)
    ├── project.yml            # XcodeGen 工程描述文件（自动生成 OmniLink.xcodeproj）
    └── OmniLink/Sources/
        ├── Protocol/          # 跨端统一 JSON 报文与 23B-MTU 安全 BLE 分包/重组编解码器
        ├── Services/          # CoreBluetooth 后台恢复、Bonjour 直连、CallKit 来电接管、本地通知
        ├── Store/             # 全局双通道去重与状态机调度
        └── Views/             # SwiftUI 互联控制台、通知流、联调测试与设置页
```

---

## 一键云端获取 `.apk` 和 `.ipa` 安装包（GitHub Actions）

无需在本地电脑安装几十 GB 的 Xcode 与 Android Studio：

1. 在 GitHub 新建一个仓库（私有或公开均可），在当前 `OmniLink` 目录执行：
   ```bash
   git remote add origin https://github.com/<你的用户名>/<仓库名>.git
   git branch -M main
   git push -u origin main
   ```
2. 打开 GitHub 仓库顶部 **Actions** 标签页，等待 `Build Android APK & iOS IPA` 工作流自动跑完（约 3~5 分钟）。
3. 点击构建记录底部的 **Artifacts** 即可直接下载两个安装包：
   - **`OmniLink-Android-APK` (`OmniLink-Android-v1.0.0.apk`)**：已签名，发到安卓手机直接点击安装。
   - **`OmniLink-iOS-IPA` (`OmniLink-iOS-v1.0.0-unsigned.ipa`)**：使用 **爱思助手（应用游戏 -> 导入安装 -> Apple ID 签名）**、**Sideloadly**、**AltStore** 或 **SideStore** 输入你自己的免费 Apple ID 签名即可安装到 iPhone 真机。

---

## 首次双机配对 3 步走

1. **安卓端授权**：打开安卓端「双机互联」，在首页体检卡片依次点开授权 **蓝牙权限**、**通知读取权限**、**电话识别与接听权限**、**电池无限制保活**。
2. **iPhone 端扫描配对**：打开 iPhone 端「双机互联」，自动连上安卓蓝牙后，在弹出的系统提示框点击 **【配对】** → **【允许显示通知】**（若错过弹窗，点击页面上的「触发蓝牙配对与共享系统通知」按钮，或前往 iPhone `设置 -> 蓝牙 -> 已连安卓设备右侧 ⓘ -> 开启【共享系统通知】`）。
3. **验证与日常使用**：切换到「联调测试」页点击模拟通知/模拟来电即可立即验证双向弹窗与接听！日常随身双持时，搭配一副**双设备蓝牙耳机**（或开启安卓端「远程接听自动打开免提」）即可获得最顺畅的接听通话体验。
