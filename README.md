# Drydock（暂定名）

> 跑在安卓手机上的 coding agent 宿主（host）：免 root 的本地 Linux 环境 + agent 任务调度 + 密钥保管 + 文件桥。TUI 为主屏，GUI 为投影。

- **状态**：原型验证完成，产品功能期（2026-10-09）。六步原型计划判据全绿，真机验证周（2026-10-01–10-08）结论为 continue（S1 锁屏挂机 / S2 会话接回 / S3 热负载全过）。证据与判据见各 git tag message（`step-1` `av1` `av2` `av3` `probe-ready` `q1-verdict` `ux-1`…`ux-6`）。
- **性质**：非商业项目（明确不以赚钱为目标），学习/作品/自用导向。
- **名字**：暂定 Drydock，可中途更换。重名检查见 [docs/name-check.md](docs/name-check.md)。
- **许可证**：GPL-3.0（第三方组件声明见 [NOTICE.md](NOTICE.md)）。

## 一句话定位

**手机上的 agent 宿主，不是"更好的 Termux"，也不是云端沙箱的替代品。**

用户在自己的安卓手机上两次点击获得一个 Linux 环境，选择安装一个 coding agent（OpenCode、pi 等开源配方，或自行安装其他 agent），配一个 API key 开始干活；产品负责环境生命周期、任务存活、密钥安全、文件边界这四件「管家事务」。日常主界面就是终端：agent 的原生 TUI 直接在里面运行，宿主不做对话包装层，也不绑定任何 agent。

## 为什么成立（相对两个假想敌）

- **vs Termux**：Termux 把"跑 Linux"解决得很好，但它是终端模拟器 + 包管理器，不是工作负载管理器。环境生命周期、任务存活、密钥、文件落点四件事它结构上不做或做不了，极客手动绕过，普通人绕不过。
- **vs 云端沙箱**（Claude web / Replit / Cloud Studio）：云端赢算力和省心，本地赢四件事——代码不出设备（隐私）、永不下班的持久环境、真机文件闭环（agent 直接读写手机里的文件）、国内网络现实。定位是云端的**补集**，不是替代。

## 目标用户

没有 PC / 不想开电脑、想在手机上用 agent 干活的人。会用终端的人自己装 Termux 也能干活，但环境安装维护、锁屏挂机存活、密钥保管与文件互通都要自己操心；本产品把这几件事做成默认可用，用户进终端就能让 agent 干活。

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
3. 把 API key 写进 `~/.drydock/env.sh`（终端里自己编辑，或让 agent 代写；各 agent 的变量名约定见 [docs/decisions.md](docs/decisions.md)）。
4. 进终端，直接运行 `opencode` / `pi` 开始干活；锁屏挂机任务存活、断开后接回都是默认行为。

## 仓库结构

| 路径 | 内容 |
|---|---|
| `app/` | 宿主 Android 应用（Kotlin，原生 View 体系，无 Compose UI 依赖的页面骨架）；`jniLibs/` 为 proot 引擎二进制（GPL，重建见 `scripts/build-proot.sh`） |
| `scripts/` | 验收与仪器：夜批全链回归（night-b.py）、CDP 驱动、各判据脚本、proot 交叉编译 |
| `docs/` | 全部正式文档（见下方索引） |
| `.github/workflows/` | CI：assembleDebug + jniLibs 引擎断言 + artifact 上传 |

## 文档索引

| 文档 | 内容 |
|---|---|
| [docs/discussion-log.md](docs/discussion-log.md) | 从"手机 vibe coding 调研"到产品定义的完整讨论历程 |
| [docs/decisions.md](docs/decisions.md) | 决策记录（做什么 / 不做什么 / 理由 / 被否方案） |
| [docs/open-questions.md](docs/open-questions.md) | 待验证问题与验证计划（动手前先看这个） |
| [docs/name-check.md](docs/name-check.md) | Drydock 重名检查结果（2026-09-26） |
| [docs/engineering-plan.md](docs/engineering-plan.md) | 工程准备：性能探针、测试策略、安全不变量、前端设计语言（2026-09-27） |
| [docs/prototype-plan.md](docs/prototype-plan.md) | 原型实施计划：六步路线、判据、时间盒、风险止损（2026-09-27） |
| [docs/product-roadmap.md](docs/product-roadmap.md) | 产品阶段 roadmap：功能先行、集中测试，五阶段与判据（2026-10-03） |
| [docs/backlog.md](docs/backlog.md) | 需求池（工作文档）：并行排程、冲突分析与判据 |
| [docs/dev-environment.md](docs/dev-environment.md) | 开发环境现状：macOS 工具链、AVD 分工、注意事项 |

## 参与开发

- 分支纪律：`main` 稳定线、`dev` 日常工作线，合并跟判据走（详见 [AGENTS.md](AGENTS.md)）；发真机的 APK 只从 main 的 tag 构建。
- 动手前先读 [docs/open-questions.md](docs/open-questions.md)（勿重开已否方案）与 [docs/decisions.md](docs/decisions.md)。
- 验收以命令行为主（adb / CDP / 脚本退出码），夜批回归见 `scripts/night-b.py`。

## 下一步

按 [docs/product-roadmap.md](docs/product-roadmap.md) 推进——功能先行（首页与向导 → 输入 → 文件边界与端口），集中测试殿后；需求池余项见 [docs/backlog.md](docs/backlog.md)。
