# 决策记录

记录截至 2026-09-26 的产品与技术决策。格式：决策 → 理由 → 被否方案。

## 产品定位

### D1 产品定义：手机上的 agent host
agent 宿主 = 环境生命周期管理 + 任务调度 + 密钥保管 + 文件落点四件管家事务，底下跑各家官方 agent。
理由：这是 Termux（终端模拟器 + 包管理器，不是工作负载管理器）结构上不做、云端沙箱物理上做不到的位置；四件事均被 ZeroTermux（备份恢复）、Happy/Omnara（投影 GUI）等单点产品部分验证。
被否：①"更好的 Termux"（直接和十年社区竞争，且夹缝悖论无解）；②自建云端沙箱（零服务器成本优势没了，正面对撞 Anthropic/OpenAI）。

### D2 与两个假想敌的关系
- vs Termux：不竞争能力，只抹平"能力到产品"的距离（渠道/onboarding/维护/体验）。
- vs 云端沙箱：定位为**补集**（隐私、持久环境、真机文件闭环、国内网络），不试图替代重算力场景。

### D3 非商业目标
不以赚钱为目的。判断标准从"市场大不大"改为"是否有一类人非它不可"。

### D4 目标用户与形态
没有 PC / 不想开电脑、想在手机上用 agent 干活的人。产品门面是"对话 + 批准 + 成果预览"（agent-first），不是 `user@localhost:~$`。极客一键进终端逃生舱。

## 技术路线

### D5 免 root 的 proot 路线
proot（ptrace 假 chroot）跑 arm64 发行版，二进制原生执行，CLI/agent 场景性能足够。
被否：①AVF/pKVM（系统应用专属，第三方无权限，且仅 Tensor 芯片）；②QEMU 全模拟（慢到不可用）；③root + chroot（受众太小）。

### D6 终端层：ttyd + xterm.js 自建，不 fork Termux
环境内 ttyd（MIT）挂 PTY → WebSocket → WebView 里 xterm.js（MIT，VS Code 同款渲染器，转义序列/真彩/鼠标全覆盖）。
理由：绕开自研终端仿真器的正确性问题；WebView 输入事件链的 IME 组合输入远好于原生 View 方案；license 干净，宿主可闭源。
被否：复用 Termux terminal-view（GPLv3 传染，闭源不可行）；fork ZeroTermux（同为 GPLv3）。

### D7 存储与文件策略：不做文件管理器，做边界桥
- 项目文件住环境内私有存储（FUSE noexec 会坏 npm 工具链，小文件 I/O 慢）；
- 实现 SAF DocumentsProvider，让全安卓的文件管理器浏览/编辑环境内文件；
- "成果落袋"规则：产物自动落到 /sdcard 可见目录（下载/文档），复用用户已有云同步；
- 一键导出环境 tar + 卸载强提醒（私有目录随卸载蒸发）。
被否：通用文件管理器（红海 + 用户习惯强 + 纯分心）；内置编辑器（用户是审阅者不是编辑者，手动编辑 intent 甩给外部 App）。

### D8 agent 分发：下载式安装 + 预选 + 自定义
镜像预装开源 agent（Codex CLI / Gemini CLI / aider，均 Apache-2.0；OpenCode、Crush MIT）开箱即用；Claude Code 为专有软件，首启从 npm 一键下载（可 pin 验证过的版本），支持自定义 npm 包名/安装命令。
理由：打包专有二进制 = 无授权重分发；下载式是标准做法且天然"最新版"；与 GLM Coding Plan 等兼容端点用法被模型方公开背书。

### D9 交互架构：TUI 主屏（~85%）+ GUI 投影
原则：会话之内归 TUI，会话之外归 host。GUI 只做五个交互：发起任务（输入框/语音/分享菜单）、批准/拒绝（通知栏一键）、看 diff、知道进度、接收成果。批准流双路径（前台 TUI / 锁屏通知按钮，作用于同一会话）。
被否：聊天气泡复刻 agent 对话（版本追赶地狱的起点）；GUI 镜像 TUI 全部功能（同上）。

### D10 集成分层：L0/L1/L2，允许烂
- L0 保底：PTY + 终端 + 管家，对一切 CLI 永远工作，零适配维护（退化下限 = Termux++）；
- L1 通知：PTY 静默/退出启发式 → 系统通知，全 CLI 通用；
- L2 适配：基于官方扩展点（Claude Code hooks / Codex notify / MCP）的深度 GUI，每 CLI 独立适配器，允许烂、烂了禁用。
被否：解析 CLI 输出流（界面级耦合，agent 每次改版都要追，N 家 CLI = N 倍维护地狱）；上线时全 CLI 适配（先只做 Claude Code 一家）。

### D11 版本固化
预装验证过兼容的 agent 版本，升级是用户显式动作。把"跟最新"从义务变成选项。

### D12 端点与镜像：国产优先
模型端点预设（GLM 等 Anthropic 兼容 base_url 模板，填 key 即用）；rootfs 和 apt 源默认清华/中科大镜像。
理由：目标用户主要在国内，这是体验分水岭，也是海外产品不会认真做的事。

### D13 平台范围
安卓系（含 HyperOS 等国产 ROM，重点适配杀后台策略）；只做 arm64-v8a；不支持 iOS（本地路线物理不通）、不支持鸿蒙 NEXT（无 AOSP 兼容层，Termux 类方案不可行）。

### D14 分发渠道
F-Droid / 官网直装 / 国内应用市场优先，Google Play 后置（targetSdk 与 exec 政策的历史摩擦）。

## 明确不做清单

| 不做的事 | 理由 |
|---|---|
| 自研 agent 内核 | 模型方有绝对优势；用官方内核 headless 模式 |
| 聊天气泡 UI 复刻 agent 对话 | 版本追赶地狱 |
| 内置代码编辑器 | 手机手写代码≈伪需求；diff 预览止步于预览 |
| 通用文件管理器 | 红海；DocumentsProvider 让全安卓生态替我们做 |
| Linux 图形桌面（X11/VNC） | 工程量翻几倍价值存疑；Web 产物用 WebView 开 localhost 预览 |
| fork/复用 Termux 代码 | GPLv3；自建壳干净且体验上限更高 |
| 打包 Claude Code 二进制 | 专有许可无再分发权 |
| 全 CLI 生态适配 | 先只做 Claude Code；其他走 L0/L1 |
| 以赚钱为目标 | 已明确非商业项目 |
