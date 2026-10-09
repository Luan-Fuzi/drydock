# Drydock（暂定名）

> 跑在安卓手机上的 coding agent 宿主（host）：免 root 的本地 Linux 环境 + agent 任务调度 + 密钥保管 + 文件桥。TUI 为主屏，GUI 为投影。

- **状态**：原型验证完成，产品功能期（2026-10-09）。
- **性质**：非商业项目（明确不以赚钱为目标），学习/作品/自用导向。
- **名字**：暂定 Drydock，可中途更换。
- **许可证**：GPL-3.0（第三方组件声明见 [NOTICE.md](NOTICE.md)）。

## 一句话定位

**手机上的 agent 宿主，不是"更好的 Termux"，也不是云端沙箱的替代品。**

用户在自己的安卓手机上两次点击获得一个 Linux 环境，选择安装一个 coding agent（OpenCode、pi 等开源配方，或自行安装其他 agent），配一个 API key 开始干活；产品负责环境生命周期、任务存活、密钥安全、文件边界这四件「管家事务」。日常主界面就是终端：agent 的原生 TUI 直接在里面运行，宿主不做对话包装层，也不绑定任何 agent。

## 快速开始

前置：JDK 17、Android SDK（本机 `local.properties` 指向即可）；运行目标为 arm64 真机或模拟器（Android 10+）。

```bash
git clone https://github.com/Luan-Fuzi/drydock.git
cd drydock
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

CI（`.github/workflows/ci.yml`）在每次 push 到 main/dev 时构建 APK 并上传 artifact，可从 Actions 页直接取包。

首次使用流程：

1. 打开 app，主页点「新建会话」——首启自动部署环境（下载 Ubuntu rootfs 并配置，默认走国内镜像，需联网，耗时取决于网络）。
2. 向导里选择安装 agent 配方（OpenCode / pi，npm 源默认国内镜像），或跳过后在终端里自行安装任何 CLI agent。
3. 把 API key 写进 `~/.drydock/env.sh`（终端里自己编辑，或让 agent 代写）。
4. 进终端，直接运行 `opencode` / `pi` 开始干活；锁屏挂机任务存活、断开后接回都是默认行为。

## 仓库结构

| 路径 | 内容 |
|---|---|
| `app/` | 宿主 Android 应用（Kotlin，原生 View 体系）；`jniLibs/` 为 proot 引擎二进制（GPL，重建见 `scripts/build-proot.sh`） |
| `scripts/` | 验收与仪器：夜批全链回归（night-b.py）、CDP 驱动、各判据脚本、proot 交叉编译 |
| `docs/` | 项目文档 |
| `.github/workflows/` | CI：assembleDebug + jniLibs 引擎断言 + artifact 上传 |
