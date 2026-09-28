# 原型实施计划

2026-09-27 定稿，即 [engineering-plan.md](engineering-plan.md) 末尾引用的"[prototype 计划]"。目标：2–3 周产出丢弃型原型并真用一周，回答 Q1（锁屏挂机链路是否敢依赖）。原则不变：探针建精，脚手架从简；每步有可检查判据，判据即停机点。

## 前提决策

- **单仓**：文档与代码同仓（`app/` 宿主工程、`scripts/` 环境层脚本与 AV 剧本、`docs/` 文档）。被否：文档/代码双仓（原型期同步成本大于收益）。
- **真机策略**：步骤 1–5 全部在 AVD（`medium_phone`，4KB，API 36）开发；真机只进入步骤 6。理由：Apple Silicon 上 arm64 模拟器接近原生，链路开发可信；但电源管理、OEM 杀后台、发热降频模拟器不可信，Q1/Q7 结论必须真机。接受的风险：设备特异性问题最晚暴露（Q3 本来就先只覆盖手持机型）。
- **语言**：按 discussion-log §22 候选执行（宿主 Kotlin + Compose，WebView 薄层 TypeScript，proot 构建 C，环境脚本 bash），步骤 1 结束时正式确认进 decisions.md。

## 六步路线

| 步骤 | 内容 | 对应判据 |
|---|---|---|
| 1 工程骨架与翻译引擎 | Android 工程骨架 + proot NDK 自编译 | CI 出 APK，AVD 上 spawn proot 跑通 `/bin/sh` |
| 2 rootfs 生命周期 | 首启拉取、校验、解压、进入环境 | AV1 冷启动 ≤ 15 分钟 |
| 3 终端链路 | ttyd + xterm.js + tmux | AV2 终端可用，中文 IME 不乱码 |
| 4 agent 链路 | Claude Code + GLM 端点 + 密钥注入 | AV3 完成一次对话并产出文件 |
| 5 仪器 | 存活遥测 + 前台服务 + 基准电池 | 遥测一轮完整记录并可导出；基准脚本一条命令出 JSON |
| 6 真机验证周 | 装机真用一周 | S1/S2/S3 + Q7 结论，continue/pivot 决策 |

### 步骤 1：工程骨架与翻译引擎（预计 2–3 天）

Gradle（Kotlin DSL）建 `app/`：Kotlin + Jetpack Compose、minSdk 29、仅 arm64-v8a；NDK r29 交叉编译 proot Termux fork（锁 tag），产物命名 `lib*.so` 进 jniLibs（Android 10+ exec 限制的手法）；GitHub Actions arm64 runner 跑 `assembleDebug`。
**判据**：CI 绿；APK 装进 AVD，经宿主以 proot 启动一个最小 rootfs（可手推进私有目录）内的 `/bin/sh` 并执行 `uname -a`。

### 步骤 2：rootfs 生命周期（预计 1–2 天）

首启从清华/中科大拉 Ubuntu arm64 基础压缩包（30–50 MB），sha256 校验后解压（I5）；proot 绑定挂载进环境；apt 源换国内。
**判据**：AV1——装 APK → 拉 rootfs → shell 提示符，全程 ≤ 15 分钟（AVD 先跑通，真机复跑计时）。

### 步骤 3：终端链路（预计 2–3 天）

环境内预置 ttyd + tmux（脚本走 `adb push` + 重启服务，免编译）；宿主 WebView + xterm.js（TypeScript 薄层）；WebSocket 仅 localhost + 随机 token（I2）；最小虚拟键条（Esc/Ctrl/Tab/方向键）。
**判据**：AV2——ttyd WebSocket 连通，渲染与键盘输入正常，中文 IME 组合输入不乱码；杀掉宿主进程重启后 tmux 会话可接回（S2 的 AVD 预演）。调试走 `chrome://inspect` 直调 WebView DevTools。

### 步骤 4：agent 链路（预计 1–2 天）

环境内装 Node + npm 下载 Claude Code（pin 验证过的版本）；GLM 端点预设模板（Anthropic 兼容 base_url + key）；API key 只存 Android Keystore、按进程注入环境变量、永不写入环境内文件（I1）；成果落袋最小规则：产物单向落到用户可见目录（I4）。
**判据**：AV3——完成一次真实对话并产出文件，文件出现在可见落点。兜底：Claude Code 在 proot 内异常时换 OpenCode / Codex CLI 走同一判据（L0 思路）。

### 步骤 5：仪器（预计 2–3 天）

前台服务的**承载部分已随步骤 3 落地**（EnvService/:env 进程，见 D18——AMS 按进程组清剿实测后前移）；本步剩余：WakeLock + 每会话一个 tmux window 的多会话管理（会话机制现为 dtach）；时间线记录器按 engineering-plan 事件清单（锁屏/解锁、WakeLock、服务重启、proot 退出码、tmux 心跳 60s、CPU 温度频率 5min、充电状态）写本地 ring buffer，调试菜单一键导出；L1 最小通知（PTY 静默/退出启发式 → 系统通知）；hyperfine 基准电池脚本（npm install / tar / stat 风暴 / make -j，JSON 落盘）。
**判据**：AVD 上完整记录一轮锁屏/解锁/杀进程/恢复并导出报告；基准电池一条命令出 JSON。注意：AVD 上的存活数据与基准数据只用于验证仪器本身，不作 Q1/Q7 结论。

### 步骤 6：真机验证周（1 周）

接真机（dev-environment 待办项）：开发者模式 + USB 调试，`adb devices` 确认；装原型真用一周；同机跑基准电池（proot vs proroot，每用例 ≥ 5 次取中位数）。
**判据**：S1 锁屏 30 分钟任务存活（全部样本）、S2 系统杀进程后 tmux 接回、S3 发热降频人工评估。产出 Q1/Q7 结论，触发 continue / pivot 决策（转向预案见 open-questions.md）。

## 验收方式（协作约定）

默认用无视觉能力的模型开发，验收以命令行为主：

- **宿主侧**：`adb shell` / `run-as`（查私有目录 rootfs）/ `logcat` 过滤服务与 proot 事件；AV1 用计时脚本。
- **WebView 侧**：debug 构建开 `setWebContentsDebuggingEnabled`，`adb forward` 把 WebView DevTools socket 转到 Mac，走 CDP 在页面内执行 JS，直接读 xterm.js buffer 做断言，顺带拿 console 报错。
- **ttyd 侧**：`adb forward` 后用 WebSocket 客户端验 token 鉴权（错 token 必须拒连）与输出帧；环境内 `tmux capture-pane` 作显示内容对照组。
- **输出契约**：每条 AV 剧本产出 verdict JSON（pass/fail + 证据路径），人与 CI 消费同一份。
- **截图的角色**：`adb exec-out screencap` 存 `draft/` 仅作证据；观感判断（渲染 / IME / 布局）集中分批做，届时提醒切换有视觉能力的模型，一次切换覆盖一批问题。
- **已知盲区**：`adb input text` 不走真实 IME 组合流程（拼音候选、预编辑串），AV2 的"中文不乱码"最终需要人在设备上敲一次或由视觉模型判读。

## 风险与止损

- proot NDK 自编译卡壳超过 2 天：原型期降级为直接使用 Termux 打包的 proot 二进制验证链路（不公开发布则许可无碍），自编译移回产品期。
- WebView 输入链疑难（IME/resize）：优先调 xterm.js 配置与输入法事件，不改架构；AV2 判据不过则记录为已知问题进 Q3 矩阵。
- Claude Code 与 proot 组合的未知不兼容：换开源 agent 保住 AV3，不阻塞 Q1（Q1 验证的是宿主管家链路，不是特定 agent）。

## 时间盒合计

步骤 1–5 约 2 周（AVD），步骤 6 验证周 1 周，与 open-questions Q1 的"2–3 周 + 真用一周"一致。任一步骤判据两次不过即停下复盘，不带病推进。
