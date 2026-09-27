# Drydock（暂定名）

> 跑在安卓手机上的 coding agent 宿主（host）：免 root 的本地 Linux 环境 + agent 任务调度 + 密钥保管 + 文件桥。TUI 为主屏，GUI 为投影。

- **状态**：构想与决策阶段（2026-09-26 完成第一轮产品定义），尚未开始写代码。
- **性质**：非商业项目（明确不以赚钱为目标），学习/作品/自用导向。
- **名字**：暂定 Drydock，可中途更换。重名检查见 [docs/name-check.md](docs/name-check.md)。

## 一句话定位

**手机上的 agent 宿主，不是"更好的 Termux"，也不是云端沙箱的替代品。**

用户在自己的安卓手机上两次点击获得一个 Linux 环境，下载安装官方 coding agent（Claude Code / Codex CLI / OpenCode 等），配一个 API key 开始干活；产品负责环境生命周期、任务存活、密钥安全、文件落点这四件"管家事务"。95% 的时间用户停在 GUI 驾驶舱（发起任务、批准、看 diff、收成果），极客随时一键进入完整终端。

## 为什么成立（相对两个假想敌）

- **vs Termux**：Termux 把"跑 Linux"解决得很好，但它是终端模拟器 + 包管理器，不是工作负载管理器。环境生命周期、任务存活、密钥、文件落点四件事它结构上不做或做不了，极客手动绕过，普通人绕不过。
- **vs 云端沙箱**（Claude web / Replit / Cloud Studio）：云端赢算力和省心，本地赢四件事——代码不出设备（隐私）、永不下班的持久环境、真机文件闭环（agent 直接读写手机里的文件）、国内网络现实。定位是云端的**补集**，不是替代。

## 目标用户

没有 PC / 不想开电脑、想在手机上用 agent 干活的人。会用终端的人装得动 Termux 不需要本产品；本产品的门面是"对话 + 批准 + 成果"，终端退到底层作为逃生舱。

## 文档索引

| 文档 | 内容 |
|---|---|
| [docs/discussion-log.md](docs/discussion-log.md) | 从"手机 vibe coding 调研"到产品定义的完整讨论历程 |
| [docs/decisions.md](docs/decisions.md) | 决策记录（做什么 / 不做什么 / 理由 / 被否方案） |
| [docs/open-questions.md](docs/open-questions.md) | 待验证问题与验证计划（动手前先看这个） |
| [docs/name-check.md](docs/name-check.md) | Drydock 重名检查结果（2026-09-26） |
| [docs/engineering-plan.md](docs/engineering-plan.md) | 工程准备：性能探针、测试策略、安全不变量、前端设计语言（2026-09-27） |
| [docs/dev-environment.md](docs/dev-environment.md) | 开发环境现状：macOS 工具链、AVD 分工、注意事项（2026-09-27） |
| `draft/` | 构想草稿与临时文件（git 忽略，见 `.gitignore`；定型后整理进 docs/） |

## 下一步

见 [docs/open-questions.md](docs/open-questions.md)：第一个动作是 2–3 周的丢弃型原型，验证"锁屏挂机干活"这条核心链路。
