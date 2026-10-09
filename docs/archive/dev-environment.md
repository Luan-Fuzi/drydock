# 开发环境现状（macOS / Apple Silicon）

2026-09-27 配齐并逐项验证。后续动工直接用，环境变化时更新本文件。

**Git 远端**：`github.com/Luan-Fuzi/drydock`（私有，2026-09-27 建立）；CI 在 x86_64 runner 跑（AGP 8.13 的 AAPT2 maven 工件无 linux-arm64 变体，见 ci.yml 注释）。

## 组件清单

| 组件 | 位置 | 状态 |
|---|---|---|
| Android Studio | `/Applications/Android Studio.app` | ✅ 原有 |
| JDK 17 | Homebrew（openjdk 17.0.20） | ✅ |
| SDK 根目录 | `~/Library/Android/sdk` | ✅ |
| 平台 | android-36.1 | ✅ |
| build-tools | 36.0.0 / 36.1.0 / 37.0.0 | ✅ |
| platform-tools | adb（`~/Library/Android/sdk/platform-tools/adb`） | ✅ |
| **NDK r29** | `~/Library/Android/sdk/ndk/29.0.14206865` | ✅ 新装，3.1 GB；clang 21 `aarch64-linux-android` 交叉编译实测可跑（`toolchains/llvm/prebuilt/darwin-x86_64/`） |
| cmdline-tools | brew cask `android-commandlinetools` + 软链到 `~/Library/Android/sdk/cmdline-tools/latest` | ✅ 新装 |

## AVD 分工

| AVD | 系统镜像 | 用途 |
|---|---|---|
| `medium_phone` | android-36 google_apis_playstore arm64（**4KB**） | 日常开发回路（UI、ttyd/xterm.js、通知流转） |
| `Pixel_9` | android-37 google_apis_playstore **ps16k**（16KB page size） | Q4 页尺寸兼容专项预检 |

另有一套手动安装的 4KB 备用镜像 `system-images;android-36.1;google_apis;arm64-v8a`。

## 注意事项

- **SDK 管理统一用新 `android` CLI**（`~/Library/Android/sdk/cmdline-tools/latest/bin/android`）：旧 `sdkmanager`/`avdmanager` 在此环境已弃用且有本地仓库枚举 bug（卡在 "Loading local repository... 25%"）。装 SDK：`android sdk ...`；管 AVD：`android emulator list/create/start`。
- `dl.google.com` 偶发 IO 断流（本次 NDK 与镜像各中一次，NDK 曾装成 4KB 空壳）。**安装后必须验货**（查目录体积与二进制可执行），失败清目录重装。
- 环境变量惯例：`ANDROID_HOME`/`ANDROID_SDK_ROOT` 指向 `~/Library/Android/sdk`。

## 待办

- [x] **真机接入（小米13，骁龙 8 Gen 2，HyperOS 3）**：2026-10-01 完成接入并采 S1/S2 正式样本（见 prototype-plan 步骤 6 与 decisions D22），本清单留档作流程参考。
