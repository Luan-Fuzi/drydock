# 讨论历程

2026-09-26，一次对话从调研走到产品定义。按时间线记录，每条含：问题 → 结论 → 关键依据。

## 1. 手机上 vibe coding 的现状调研

**问题**：手机上搞 vibe coding 有没有案例？Linux 虚拟机路线还是装 App？

**结论**：三条成熟模式——
1. **本地 Linux + CLI**（仅安卓可行）：Termux 不是 VM，是安卓上的 Linux 用户态环境；Termux + Claude Code / OpenCode / aider 是社区验证过的玩法；可配 GLM 等 Anthropic 兼容端点，国内免代理。
2. **遥控器**（体验最好）：agent 跑在 Mac/PC 上，手机装 Happy / Omnara / SeaWork 等配套 App 监视和批准；或 Termius + Tailscale SSH 回家。
3. **云端沙箱**：Claude Code on web、Codex 云端、Replit / bolt.new / Cloud Studio，手机零负担但受平台沙箱限制。

iOS 本地路线不通（iSH 模拟 x86 慢、UTM 无 JIT），iPhone 用户实际都走 2/3。

## 2. 安卓底层是 Linux，能不能直接用

**结论**：能，这正是安卓能玩而 iOS 不能的根本原因（iOS 内核是 XNU）。"Linux 系统" = 内核 + 用户态，安卓保留了内核但换掉了用户态（Bionic 而非 glibc、toybox、mksh、无 root、SELinux）。沿"内核现成、补用户态"由轻到重：

- **Termux**：二进制原生跑在 Linux 内核上，零模拟开销（比 iSH 快的原因）；
- **proot-distro**：ptrace 做"假 chroot"，免 root 跑完整发行版，CLI 场景性能损耗可接受；
- **root + chroot**：原教旨路线（Linux Deploy），现在没必要为它解锁手机；
- **官方 AVF/pKVM**（Linux Terminal）：硬件级真虚拟机，Android 16 起随 Pixel 出货、三星 Tab S11 跟进，但**系统应用专属，第三方 App 无权调用**，且仅 Tensor 类芯片；
- **postmarketOS / Ubuntu Touch**：整机刷成 Linux，极客玩物。

## 3. 平台格局：HyperOS vs 鸿蒙

**结论**：HyperOS = AOSP 定制，Termux 路线完整可用（痛点是杀后台激进，需电池白名单 + 锁定任务）。鸿蒙分两代：HarmonyOS 4.x 及更早兼容 APK，可侧载 Termux；HarmonyOS NEXT（纯血鸿蒙）移除 AOSP 兼容层，Termux 官方明确不支持，本地路线堵死（Termony 项目尚在早期），只能走浏览器云端 + 遥控。**本产品平台范围：安卓系（含 HyperOS），不含鸿蒙 NEXT。**

## 4. 产品想法成形

**原始设想**：做一个安卓工具（APK），用户便捷获得 Linux 虚拟环境 → 交互、装 vibe coding 工具、开发 → 环境内目录挂载到真机目录。

**结论**：可行性高，路线被反复验证。需求三要素（便捷获取、交互开发、目录挂载）各自有现成实现（proot 原生支持 `-b` 绑定），但拼成顺滑产品的成品空缺。

## 5. 已有产品对照

| 产品 | 形态 | 与本设想的关系 |
|---|---|---|
| Termux + proot-distro | DIY 工具 | 能力全覆盖，零产品化 |
| UserLAnd | 一键 Linux App | 最接近的产品形态，维护低调、UI 老化 |
| Andronix / AnLinux / Complete Linux Installer | Termux 引导器/脚本商店 | 只解决安装，不管使用 |
| Google Linux Terminal (pKVM) | 系统级真 VM + 文件共享 | 系统应用专属，仅 Tensor 设备 |
| ZeroTermux | Termux 中文魔改（换源/备份/装发行版一键化） | 验证了"产品化壳"需求真实，天花板是 Termux 本身 |
| Happy / Omnara / SeaWork | 桌面 agent 的手机遥控器 | 验证了"投影 GUI"形态，但都是遥控路线非本地 |
| DSHA | 免 Root 免 Termux 跑 DeepSeek Harness 的安卓启动器（Ubuntu rootfs + proroot，MIT，alpha） | 定位最接近的在制品，详见 §17 |

## 6. 难点排序（不是性能也不是内核兼容）

**结论**：国产 ROM 碎片化（杀后台、权限）≈ 60% > 终端体验细节（软键盘、中文 IME 组合输入、虚拟键条）≈ 30% > 性能 ≈ 10%。proot 的 ptrace 开销只在 I/O 风暴（npm install）时可感（慢 2–5 倍），交互和跑 agent 无感；现代旗舰跑 Node/agent 够用。

**TUI 完全支持**：htop/vim/tmux/lazygit/Claude Code 的 TUI 只需 PTY + `TERM=xterm-256color` + 转义序列 + SIGWINCH，xterm.js（VS Code 同款渲染器）原生全覆盖。GUI 程序（X11/VNC 桌面）不在范围内，但 agent 起 dev server 后用 WebView 开 `http://localhost:端口` 预览 Web 产物的闭环成立。

## 7. Termux 现状（2026-09 查证）

活跃维护：GitHub 持续发版，proot-distro 已至 v4.38；Google Play 纠纷已了结，官方包（`com.termux`）回归 Play 并恢复更新（2026.02.11 版本内置更多 Termux:API 工具）。插件生态齐全（:API/:Boot/:Widget/:Float）。但作为产品依然是极客工具：无 onboarding、英文文档、IME 不完美、杀后台用户自己扛。**"基础设施层饱和、产品层只被填了一半"（ZeroTermux 填的是中文用户顺手用 Linux 这半，agent-first 这半没人做）。**

## 8. 两个定位问题（产品生死题）

**Q1：为什么用户不去用 Termux？**
技术上没有理由；差异在"能力"与"产品"的距离：获取渠道（F-Droid/GitHub/山寨包）、30–60 分钟手工 onboarding（换源、装 node、配端点）、持续隐性维护、体验层。但存在**夹缝悖论**：装得动 Termux 的人不需要你，需要你的人不想要终端。破法：产品形态 agent-first（Claude Code 的 TUI 本身接近聊天），终端退到底层。

**Q2：为什么不用云端沙箱？**
云端赢算力和省心；本地赢四件事：代码不出设备、永不下班的持久环境（无配额回收）、真机文件闭环（agent 直接读写 `/sdcard`，云端碰不到手机文件）、国内网络现实（境外云沙箱慢/不可达，国内云 IDE 有账号计费摩擦）。**定位：云端的补集。** 远期独有筹码：agent 在设备上就能操控设备（云端永远做不到）。

## 9. 纠正一个认知：不自研 agent ≠ 不能做 GUI

agent 内核与 agent 界面是分离的：Claude Code 有 headless 模式（`claude -p` + stream-json，批准/diff/进度全是结构化事件），Happy/Omnara 就是官方内核 + 自建渲染层。"图形界面不方便跑那几个 agent"不成立；"我写的 agent 不如模型方"也不构成障碍——**白嫖内核，自建界面。**

## 10. Termux 解决得不好的四件事 → 产品定义

Termux 是终端模拟器 + 包管理器，不是工作负载管理器。四件结构上不做/做不了的事，极客手动绕过、普通人绕不过：

1. **环境生命周期**：无"环境"概念——无快照/回滚/重置/迁移/每项目隔离，apt 冲突和 agent 乱装依赖会慢慢烂掉，换手机重来。（ZeroTermux 加备份恢复，证明需求真实。）
2. **任务存活**：保活是"整个终端进程"粒度；App 形态可把"一次 agent 任务"做成系统前台服务——锁屏不死、崩溃自动恢复接回 tmux、完成推系统通知。**"agent 在跑，锁屏走人，干完震一下"是 Termux 永远给不了、又是 vibe coding 核心使用姿势的体验。**
3. **密钥安全**：API key 是环境变量，环境里任何 curl|bash 装的脚本都能读走；信任链全靠社区仓库 + 用户自觉。App 层做密钥保管（环境读不到，宿主注入）+ 环境隔离。
4. **文件落点**：Android 11+ Scoped Storage 把 Termux 私有目录对一切文件管理器锁死；"我的代码在手机哪里"本身就劝退。

→ **产品定义：手机上的 agent host——环境管理、任务调度、密钥保管、文件落点四件管家事务，底下随便跑哪家官方 agent。** 验证原型时盯的是锁屏存活、环境重置、文件落点这三个管家功能，不是终端能不能用。

## 11. 文件策略：不做文件管理器，做边界桥

用户当然会用他们习惯的文件管理器（MT 管理器/系统自带）——不与他们竞争，而是让环境里的文件**出现在**那些管理器里：

- **项目住环境内**，不摊 `/sdcard`：FUSE 带 noexec 且小文件 I/O 慢，`node_modules/.bin` 需要可执行位，npm 工具链在 `/sdcard` 上会坏；
- **实现 SAF DocumentsProvider**：所有文件管理器/系统选择器都能浏览编辑环境内文件，读写权限自控——浏览编辑 UI 全部外包给安卓生态；
- **成果落袋**：环境是工作室、`/sdcard` 是展厅，构建产物/生成物按规则自动落到下载/文档等可见目录，用户已有的云同步习惯直接复用；
- **卸载安全**：一键导出整个环境为 tar 到可见目录，卸载检测强提醒；
- 编辑 = 审阅：做 diff 视图，手动编辑用 intent 甩给用户喜欢的编辑器；不做内置编辑器（手机手写代码接近伪需求）。

## 12. 交互架构：TUI 主屏 + GUI 投影

**原则：会话之内归 TUI，会话之外归 host。**

- 主屏 ~85% 给终端视图（xterm.js），host 贴身提供：虚拟键条（Esc/Ctrl/Tab/方向键）、快速输入通道（语音转文字注入 + 系统分享菜单接收文字/链接/文件）、状态灯（运行中/等待批准/空闲）；
- **通知栏是比屏内更重要的交互面**：批准卡片（锁屏直接点允许/拒绝，不用解锁）、完成/卡住通知、保活常驻通知做成状态面板；
- 抽屉：多会话管理（每会话一个 tmux window，可切换/重启/新建带模板）；
- 成果面板：本会话改了哪些文件、产出了什么、落袋状态；diff 预览 + 甩给其他 App；
- 首启向导：选 agent（预装列表 + 自定义）→ 填 API key（进密钥保管）→ 建环境 → 按 ROM 厂商给保活引导；
- **批准流双路径**：agent 请求权限时 TUI 里本来就有确认提示，hook 同时通知 host；前台用户直接在 TUI 按（通知撤销），后台/锁屏点通知按钮（host 写回决策：hook 返回值或向 tmux 注入按键）。两路同一会话，谁先响应算谁。

**不做**：聊天气泡界面（复刻 agent 对话 = 版本追赶地狱起点）、内置代码编辑器。

## 13. 多 CLI 与永久维护的解法：分层 + 允许烂

**问题**：用户可自由选 CLI（Claude Code/Codex/OpenCode/Crush/aider…），CLI 会更新，不确定能永久维护适配。

**结论**：把耦合面从"解析 CLI 输出"（界面级耦合，必死）换成"CLI 官方扩展点"（API 级，稳定性高一个数量级）：Claude Code 的 hooks（PreToolUse/Notification/Stop，结构化 JSON 事件，批准决策可由 hook 返回值给出）、Codex 的 notify 配置、标准 MCP。三层结构各有维护义务：

- **L0 保底层**（PTY + 终端 + 管家）：不理解 CLI 在说什么，对一切 CLI 任何版本永远工作，零适配维护。产品退化下限 = "Termux++"，不会死。
- **L1 通知层**：不看内容看状态（PTY 静默 N 秒/进程退出 → 系统通知），对一切 CLI 通用，几乎零维护。
- **L2 适配层**：基于官方扩展点的深度 GUI（批准卡片/diff），每 CLI 一个独立适配器，**允许烂、烂了禁用、等有空再修**。

配套策略：**版本固化**（环境预装验证过兼容的版本，升级是用户显式动作）；**只做一家**（先 Claude Code——GLM 端点实际需求 + hooks 最完善）；适配器本质是 shell 脚本 + 少量宿主代码，真有人用社区可写（Termux 生态模式）。**产品本体（管家四件套）不依赖任何 CLI；GUI 驾驶舱是樱桃，不是蛋糕。**

## 14. Claude Code 许可与分发方式

**问题**：预装 Claude Code 是否违反开源协议？

**结论**：前提要纠正——Claude Code 根本不是开源软件（专有、all rights reserved、Anthropic 商业条款，npm 挂商业 ToS）。分三种动作：

- 打进 APK/镜像分发 = 未经授权重分发，**不行**；
- **首启从 npm 自动安装 = 可行的标准做法**（软件从官方渠道到用户设备，许可是用户与 Anthropic 间的事，App 只是代敲 `npm install`），顺带解决"永远官方最新版"+ 可 pin 验证过的版本；
- 用 Claude Code 连 GLM 端点 = 模型方公开背书的用法。

**决策：下载式安装**——提供几个预选（开源 agent 可直接预装：Codex CLI Apache-2.0、Gemini CLI Apache-2.0、aider Apache-2.0、OpenCode、Crush MIT），也支持自定义（npm 包名/安装命令）。策略：镜像预装开源 agent 开箱即用，Claude Code 作为首启可选项一键下载。附赠好处：下载式让代码完全隔离，GPL 传染疑虑整体消除，宿主 App 可闭源。

## 15. 关键技术备忘

- **exec 限制**（Android 10+ 禁止执行下载的二进制）：可执行文件命名 `lib*.so` 放 jniLibs，系统解压到 `nativeLibraryDir` 后可执行（Termux 同款）；
- **终端架构**：环境内 ttyd（MIT）挂 PTY → WebSocket → WebView 里 xterm.js（MIT）；不 fork/不抄 Termux 代码（GPLv3）；
- **PTY 一分为二**：一路原样进终端视图（保底，永远全功能），一路解析结构化事件流（只消费五种事件：发起/批准/diff/进度/完成）；
- **会话持久化**：tmux；崩溃恢复 = 前台服务 + 自动重启接回 tmux；
- **rootfs 分发**：APK 只装引擎（几十 MB），发行版首启从清华/中科大镜像拉取，apt 源换国内；
- **分发渠道**：F-Droid / 官网直装 / 国内应用市场优先，Play 后置（targetSdk/exec 政策历史麻烦）；
- **架构**：只做 arm64-v8a。

## 16. 命名

**决策**：暂定 **Drydock**（干船坞：环境整备、agent 入驻/放飞/回收的隐喻，容量装得下未来的设备操控），中文名候选"机库/停机坪"，中间可改。应用商店标题可用自述式："Drydock - 口袋里的 Agent 机房"。重名检查结论：GitHub 232 个同名仓库但无本领域冲突；npm `drydock` 被占、`drydock-app` 空闲；短域名全部被占；手机端同名 App 均在海事行业。详见 [name-check.md](name-check.md)。

## 17. 环境翻译层调研（2026-09-27）：proot / proroot / DSHA

**背景**：复核 D5 的 proot 路线时发现 2026 年生态出现两个新变量——零 ptrace 开销的 proroot，以及与本产品定义高度重叠的 DSHA。

**proot 现状**：
- 上游 proot-me/PRoot（GPLv2+）：v5.4.1（2026-09-07）在三年空窗后复更，新增 clone3 支持（新版 glibc 默认走 clone3，缺它会退化或出错）、修复 readlinkat 崩溃；整体"低频但活着"。
- **安卓实战主力是 Termux 的 fork**（github.com/termux/proot，包版本 5.1.107.95，随 tag 自动更新）：安卓加固全部提交在 fork 内——link2symlink 硬链接仿真、libandroid-shmem 适配、loader 预装 libexec 免运行时解压。
- 性能第三方实测：纯 CPU 负载约 -7~10%；UNIXbench 综合分比 chroot 低约 44%（全部损失来自带路径 syscall 的两次上下文切换）；I/O 元数据风暴 2~3 倍——与 §6 的"npm install 慢 2–5 倍"评估吻合。
- 许可证 GPLv2+ 对宿主无传染（独立进程二进制，随包提供源码即合规）。

**proroot 现状**（coderredlab/proroot）：
- 原理：LD_PRELOAD 进程内路径翻译（免 ptrace 免上下文切换），静态/Go 二进制经 stub loader 路由（--static-loader）；CLI 兼容 proot（-r/-b/-0/--link2symlink）；形态为 5 个 .so。
- **闭源专有许可**："source is not public yet"，免费使用但禁止重分发修改后的二进制；83★/42 commit 的早期项目，作者已宣布转向新项目 proroom、更新放缓。
- DSHA 真机实测（vivo V2352A / Android 14，相对 proot）：关键项合计 +58%、tar +94%、stat 密集 +82%；采用"proroot 默认 + 三层降级回 proot、装环境阶段一律走 proot"的双引擎设计。
- 判断：性能收益真实、工程可用性已被 DSHA 验证；但闭源 + 早期 + 作者分心三个风险叠加，只能当可选加速器，不能当唯一引擎。

**DSHA**（DSH-APP/DSHA，MIT，667★，alpha，活跃重构中）：免 Root 免 Termux 跑 DeepSeek 官方 harness（类 Claude Code）的安卓启动器。与本产品几乎同构：Ubuntu 24.04 arm64 rootfs 离线打进 APK（~370 MB）+ Node 24 + 前台服务/watchdog/通知 + Android Keystore 密钥加密 + 十槽位备份轮换 + 23 项自检与 15 个自愈脚本 + 多镜像测速。**已实现 agent 经 ADB 无线配对操控设备（点按/滑动/截图/装 App）——§8 视为"远期独有筹码"的能力别人已经首发。**边界：锁死 DeepSeek 单家、以 DSH Web UI 为交互中心、无多 agent/多会话管理、无国产端点预设、无文件桥。对本项目的含义：本地路线的强工程验证 + 竞品警报 + 差异化收窄到"多 agent 宿主 / 通知批准流 / 文件落点 / 环境生命周期"。另：其 README 确认 bubblewrap/非特权 userns 被 Android sepolicy 封死，容器派与 Termux 派同样绕不过。

**选型倾向（同日复核）**：termux/proot 本体 1.1k★/279 fork（约为上游星数四成），是安卓生态的**事实标准**——proot-distro 直接用它，Andronix（Play 上架）明确声明构建于 proot-distro 之上不自带 proot，ZeroTermux 继承 Termux 打包，即安卓上所有主流免 root Linux 方案全部踩在这个 fork 上；且生态日常跑 Ubuntu 24.04（glibc 2.39 级）说明它与新 glibc 的组合已被海量真机验证。**默认引擎倾向 Termux fork**（锁 tag、版本固化），上游 v5.4.1 作为 cherry-pick 观察项（clone3 原生支持）；Q7 的基准电池从"二选一"调整为"确认倾向 + 量化 proroot 加速收益"。

**翻译层候选全景（同日补查）**：ptrace 系——上游 proot-me/PRoot（v5.4.1）、Termux fork（安卓唯一有存在感的维护分支）、proot-rs（官方 Rust 重写，实验期）；LD_PRELOAD 系——proroot（闭源专有，出身于 termux-app#5245 社区讨论，靠 stub loader 堵静态二进制绕过）、fakeroot/pseudoroot（只伪装身份不做路径翻译，不同问题域）、termux-exec（只修 shebang）；特权系（要 root，性能满）——chroot/Linux Deploy、DroidSpaces（namespace 容器）；虚拟机系——AVF/pKVM（系统应用专属）、QEMU 全模拟（慢一个量级）、QEMU user-mode（可经 proot -q 跑异架构）；观察名单——proroom（proroot 作者的闭源 App）、AndroSH（经 ADB/Shizuku 部署发行版、不依赖 Termux 的新项目）。**结论：选型地图不变（Termux fork 默认 + proroot 可选加速 + proot 永远可退），本清单作为 Q7 实测对象与 Plan B 矩阵。**

**对 D5 的修订候选**（原型验证后定）：翻译层可插拔双引擎——proot 开源保底（Termux fork 或上游 v5.4.1 二选一待实测）+ proroot 闭源加速器（可选，默认开/关待基准数据）；首次装环境阶段固定走 proot（慢但稳，DSHA 同款策略）。proroom 补充调查（同日）：无公开源码仓库，仅有一个隐私政策仓库（proroom-privacy-policy，2026-05），按 proroot README 描述是作者自己打包 proroot 的独立安卓 App——引擎作者开始做自己的壳；proroot 仓库 2026-06-17 后未再更新，印证"更新放缓"。作者（coderredlab，首尔）同期维护多个 AI 终端/agent 相关项目（openclaw、oh-my-pi fork、GGUF 推理运行时 runNburn），proroom 向 agent 方向演化的概率不低，列入观察。

## 18. 六类技术方案的原理对比与编译能力边界（2026-09-27）

**框架**：所有"在安卓上跑 Linux"方案的区别在于**在哪一层动手脚**。两条定律贯穿：拦截点离内核越近，覆盖越全但单次拦截越贵；隔离强度与性能无关，只取决于内核是否以不同视角解析世界。

- **A 无翻译直跑**（Termux Bionic 路线）：满速，但 glibc 生态整棵不可用，每个包重编译（发行版税），无隔离。
- **B libc 层拦截**（LD_PRELOAD：proroot）：拦截=普通函数调用，近原生；但只覆盖"经过 libc 的调用"，静态二进制与 Go 直发 syscall 全部逃逸，靠 stub loader/二进制补丁逐个堵（维护税转移非消失）。
- **C 内核边界拦截**（ptrace：proot）：内核边界是一切代码必经之路，兼容性满、免 root；代价是路径 syscall 两次上下文切换（CPU -7~10%、I/O 风暴 2–5 倍），新 syscall 需跟进（追赶型维护）。
- **D 特权容器**（namespace：DroidSpaces）：不拦任何东西，内核换视角解析——性能兼容双满且是唯一用户态真隔离；但免 root 通道（userns）被安卓 sepolicy 封死。**"零开销"与"免 root"在安卓上互斥，正是 proot 存在的理由。**
- **E 硬件虚拟机**（AVF/pKVM）：近原生、隔离最强，hypervisor 权限系统应用独占；若哪天开放，D5 应重开。
- **F 指令模拟**（QEMU 全模拟）：慢一到两个量级，仅异架构兜底。

**推论**：免 root 硬约束排除 D/E/F，发行版税排除 A → 免 root 世界真正的选择只剩 B 与 C，且互补成对（C 保底 B 加速）——D5 修订候选的原理级依据。B 与 C 安全同级（都是视角欺骗、零隔离），密钥设计统一按"环境不可信"处理。

**编译能力边界**：纯计算原生速度（编译主循环不带路径不拦）；arm64 用户态任意可编、交叉编译任意目标可编；需要真 mount/chroot 的构建不可编（Docker build、live ISO、内核模块）；io_uring 被安卓 seccomp 屏蔽（多数软件可回退）；LLVM/Chromium 级链接受手机 RAM 限制；持续满负载受热节流（root 方案同样逃不掉）。结论：编译能力不是本路线短板，agent 场景（测试/装依赖/构建 Web 项目）绰绰有余。

## 19. 生态分层与本工程定位（2026-09-27）

安卓免 root Linux 生态分四层：**产品层**（Andronix：卖"安装便利"，Play 上架，freemium，装完不管）、**编排层**（proot-distro：环境生命周期 CLI，现已 Python 化且支持 OCI 镜像拉取/层缓存，GPLv3-only 可参考不可抄）、**引擎层**（proot Termux fork）、**底座层**（Termux App）。

**本工程的工程本质**：底座换成自己 + 引擎直接采用 + 编排产品化 + 产品层从安装推进到使用。"底座换成自己"= 我们的 App 当安卓宿主（jniLibs exec 手法、私有目录、前台服务），Keystore/系统通知/DocumentsProvider/分享菜单这些管家能力**只有 App 本尊做得到**，寄居 Termux 则入口和节奏都在别人手里（ZeroTermux 生态位）；Termux 底座带的 Bionic bootstrap 我们不需要（用户态就是 glibc rootfs 本身），底座可以做得很薄——DSHA 无 Termux 已验证。"引擎直接采用"= Termux fork 源码 NDK 自编译随 APK 分发（非复制其二进制包），CLI 黑盒集成（spawn + 传参），GPLv2+ 随包供源码合规，锁 tag 版本固化。

竞争格局补充：**DroidSpaces**（root namespace 容器，GPLv3，详见 §17 表）占据"root + 性能 + 基础设施"生态位；其保活手法（init.rc 守护 + magiskpolicy 动态改 SELinux）是 Q1 的"root 答案"参照——若免 root 天花板不可接受，翻墙手段只有 root；其需求检查器（requirements checker）值得吸收进首启向导（特性探针的产品化形态）。

## 20. 版本适配三轴（2026-09-27）

**内核版本本身几乎不构成约束**：syscall ABI 有向后兼容承诺；proot 依赖的 ptrace/seccomp 在 2016 后任何 arm64 设备满足；在役设备内核散布 4.14–6.6，由 minSdk 自然锁定底线；W^X 执行限制是 Android 10+ 用户态策略与内核版本无关。真正的适配工作在三条相互独立的轴：

1. **API level 轴**（编译期决策）：建议 minSdk 29 / Android 10——2019 后设备、统一 W^X 与 Scoped Storage 行为、测试矩阵减半（低于 29 的存量小且跑不动 agent 负载；产品决策非技术约束）；targetSdk 跟渠道要求（35+）。
2. **16KB 页尺寸轴**（构建期管线）：proot 链接加 `-Wl,-z,max-page-size=16384`，CI 用 readelf 断言 LOAD 段对齐；rootfs/Node/npm 原生模块逐个验证 p_align；运行时 `sysconf(_SC_PAGESIZE)` 探测做能力标记而非拒绝；Play 已自 2025-11 强制 targetSdk 35+ 支持 16KB。模拟器预检：4KB 与 16KB 双镜像（见 dev-environment.md）。
3. **OEM 行为轴**（Q3，运行时）：杀后台/白名单路径/自启限制与内核无关，做**特性探针不做机型白名单**——探针是事实来源，机型知识只用于首启向导预填；白名单必然落后于 ROM 更新。

边缘风险：新 glibc 的 clone3/faccessat2 在老内核靠 ENOSYS 回退，4.9+ 稳，由 minSdk 兜底。小坑：环境内 `uname -r` 是安卓内核串（如 `5.15-android13`），个别发行版脚本被 `-android` 后缀迷惑，遇脚本行为怪异先想到这层。

## 21. 终端层与 rootfs 分发细节（2026-09-27）

**自建终端（D6）的完整代价清单**。收益：license 干净、xterm.js 正确性白嫖（转义序列/真彩/鼠标全被 VS Code 同款渲染器锤过）、WebView 输入链对中文 IME 友好（Termux 原生 View 方案的多年痛点）、PTY 住环境内 App 重启不丢会话、宿主 UI 随意叠加。代价：WebView 渲染开销（快速刷屏可能掉帧，WebGL renderer addon 可救）、无 Play 服务 ROM 的 WebView 碎片化（并入 Q3 矩阵实测）、**localhost 端口同机其他 App 可访问——必须 token 认证（已列安全不变量 I2）**、**ttyd 住环境内 → rootfs 装坏时环境内无终端可自救——宿主需救援通道（立为 Q8）**、软键盘 resize/旋转行列重算自行承担。

**rootfs 分发**：APK 不预装发行版（App 更新不必重复携带、rootfs 独立于 App 演进、F-Droid 对大包不友好、用户不预付存储），首启从清华/中科大拉压缩包（Ubuntu/Debian arm64 基础 30–50 MB）；全套配完约 600 MB–1 GB（rootfs 解压 ~200 MB + Node ~200 MB + agent 及依赖）；proot 路线无内核镜像成本（不是虚拟机）。Alpine（musl）rootfs 最小但 npm 原生模块兼容差，维持 glibc 发行版选择。**优化项**：预烤常用依赖进 rootfs 或做层缓存（参考 proot-distro 的 OCI 层缓存思路），让日常路径几乎不触发 I/O 风暴。

## 22. 开发环境就绪与语言选型（2026-09-27）

macOS（Apple Silicon）工具链已配齐并逐项验证，现状与注意事项见 [dev-environment.md](dev-environment.md)。AVD 分工：`medium_phone`（4KB，API 36）日常开发回路；`Pixel_9`（16KB ps16k，API 37）Q4 页尺寸专项预检。**Q1/Q7 的数据必须真机**（模拟器无真实电源管理/OEM 杀后台/发热降频，对这些问题说谎）。待办：真机接入。

**语言选型（决策候选，待正式确认后进 decisions.md）**：宿主 App = **Kotlin + Jetpack Compose**（前台服务/Keystore/SAF/通知等管家 API 全是一等公民，Compose 配 Material 3 接设计语言）；WebView 宿主 UI = TypeScript 薄层（包 xterm.js 与 diff 视图）；proot = C（只构建按需打补丁）；环境内脚本与 hooks 适配器 = bash + JSON；构建 = Gradle（Kotlin DSL）+ GitHub Actions。两条原则：原型与产品用同一套语言（无语言税，纯逻辑直接长进产品）；不上第四门语言（除非 proot-rs 成熟才引入 Rust）。
