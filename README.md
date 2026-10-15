# ModemTest

面向芯片 modem 测试的 Android 日常工具：**自动拨号**、**ping 时延**、**双卡轮流拨打**。
无需 root，普通应用权限即可运行。

- 包名：`com.example.modemtest`
- minSdk 26（Android 8.0）/ targetSdk 30（Android 11）
- 已验证可运行：Android 8 ~ 14

---

## ⚠️ 关于本项目：全程由 AI 开发

**这个仓库的每一行代码、每一份脚本、这份 README，全部由 AI 编写。**

| 角色 | 谁 |
|---|---|
| 需求方 / 验收 | 人（提出功能需求、真机测试、反馈问题） |
| 设计 / 编码 / 构建 / 调试 | AI Agent（Hermes，跑在本地 NAS 上的容器里） |

人没有手写过一行 Java、没开过 Android Studio、没敲过一条构建命令。
迭代过程是「提需求 → AI 改代码 → 出 APK → 真机试 → 再提需求」的循环，
共 8 个版本（v1.6 → v2.8）在一天内完成。

**为什么写这段**：这个项目是「AI 能不能独立完成一个真实工程」的一个样本 ——
包括一开始踩的坑（SDK 下载被墙、`resources.arsc` 对齐、`javac -source 8` 不认 lambda）
都是 AI 自己查出来、自己修的。把它标出来，是因为诚实比好看重要。

提交记录里的 `Blackstone <blackstone@local>` 就是那个 AI Agent 的身份。

---

## 下载

成品 APK 见右侧 [Releases](https://github.com/kar-liang/ModemTest/releases)，
或直接取仓库内 [`releases/`](releases/) 目录。

---

## 功能

### 1. 自动拨号
| 能力 | 说明 |
|---|---|
| 自定义次数 / 通话时长 / 间隔 | 拨号 → 通话 N 秒 → 挂断 → 间隔 N 秒 → 下一通 |
| 两个号码轮流 | 号码1 → 号码2 → 号码1 … |
| 双卡绑定轮流 | SIM1 拨号码1、SIM2 拨号码2，ABAB 轮转 |
| 到点自动挂断 | 四级兜底：`endCall()` → `ITelephony` 反射 → `getITelephony` 反射 → 无障碍点击 |

**自动挂断依赖无障碍服务**：首次使用点界面上的「开无障碍」开启
`ModemTest 自动挂断`。Android 9 上 `endCall()` 通常直接可用；Android 10+
多数需要无障碍兜底。

### 2. ping 时延
- 多目标同时测，实时显示 `avg / max / 丢包率`
- 目标填 IP 或域名均可，点「添加」加入列表

---

## 构建

不需要 Android Studio、不需要 Gradle。只要 JDK 17 + Android SDK（platform 30 + build-tools 34）。

```bash
# 1. 放好 SDK 组件（目录结构见 build.sh 顶部注释）
#    platforms/android-30/android.jar
#    build-tools/34.0.0/{aapt,d8,zipalign,apksigner}
#    debug.keystore（没有会自动生成）

# 2. 一键构建 + 对齐 + 签名 + 自校验
./build.sh
```

`build.sh` 全流程：`aapt` 打包资源 → `javac` 编译 → `d8` dex → `aapt add` 合包
→ `zipalign -p 4` → `apksigner` 签名 → `zipalign -c` + `apksigner verify` 自检。

产物：`out/ModemTest.apk`

### 构建踩坑记录（都是实测踩过的）

| 现象 | 原因 / 解法 |
|---|---|
| `INSTALL_FAILED_DEPRECATED_SDK_VERSION: ... found 0` | manifest 缺 `<uses-sdk>`，targetSdk 被当成 0 → 显式声明 `minSdkVersion=26 targetSdkVersion=30` |
| `Targeting R+ requires the resources.arsc ... uncompressed and aligned on a 4-byte boundary` | ① 必须 `zipalign -f -p 4`；② **不能用 `jar uf` 往包里加 dex**（会重写 zip 头破坏对齐），要用 `aapt add`；③ 顺序必须是**先 align 再签名** |
| `javac -source 8` 报 lambda 错误 | `-source 8` 不支持 lambda → 全部改写为匿名 `Runnable` |
| `aapt -m -J` 无资源时不建 gen 目录 | 先 `mkdir -p gen` |
| `dl.google.com` 下载 SDK 被代理断 | 改用腾讯云镜像 `https://mirrors.cloud.tencent.com/AndroidSDK/` |
| 输入框点了不弹键盘 | **ScrollView 子树里给子 View 用 `weight` 宽度，部分 ROM 会算成 0 宽** → 改成 `-1` 占满 |

---

## 图标

`res/mipmap-*/ic_launcher.png` 由 `make_icon.py` 纯 Python 生成（无第三方依赖、无图片素材）：

```bash
python3 make_icon.py     # 输出 mdpi ~ xxxhdpi 五个密度
```

---

## 目录结构

```
├── AndroidManifest.xml
├── build.sh                 一键构建（含对齐/签名/自检）
├── make_icon.py             图标生成器
├── LICENSE                  MIT
├── src/com/example/modemtest/
│   ├── MainActivity.java    UI + 拨号调度 + ping
│   └── HangupService.java   无障碍挂断服务
├── res/
│   ├── mipmap-*/ic_launcher.png
│   ├── values/strings.xml
│   └── xml/accessibility_config.xml
└── releases/                各版本成品 APK
```

---

## 已知限制

- **不能自动切换网络模式**（NR/LTE/WCDMA/GSM）。普通应用无 root、无系统签名时三条路
  （carrier 特权 / `WRITE_SECURE_SETTINGS` 被 MDM 改回 / `MODIFY_PHONE_STATE`）全堵死，
  只能跳系统 SIM 设置页让手动人改。该功能已从代码中移除。
- 部分厂商 ROM 会拦 `getActiveSubscriptionInfoList()`（双卡枚举），导致识别不到 SIM。
  点「刷新卡」重试，或检查应用权限里的电话/定位权限。
- 挂断依赖无障碍服务；若被系统杀掉需重新开启。

---

## 版本

| 版本 | 变更 |
|---|---|
| v2.8 | 深色 UI；按钮按压/禁用态 + 震动反馈；应用图标；整页可滚动 |
| v2.7 | 按钮点击反馈与状态互斥 |
| v2.6 | 修 ScrollView 下 weight=0 宽导致输入框点不到 |
| v2.0 | 移除「切换网络模式」 |
| v1.6 | 双卡绑定轮流（SIM1→号码1、SIM2→号码2） |

---

## License

[MIT](LICENSE)
