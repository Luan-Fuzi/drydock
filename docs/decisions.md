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

### D15 语言与工具链（2026-09-27，原型步骤 1 定稿）
宿主 App = Kotlin + Jetpack Compose（Material 3 深色优先）；WebView 薄层 = TypeScript；proot = C（NDK r29 交叉编译，不修改上游）；环境内脚本与 hooks 适配器 = bash + JSON；构建 = Gradle 8.14.3（Kotlin DSL + 版本目录）+ GitHub Actions arm64 runner。
理由：管家 API（前台服务/Keystore/SAF/通知）在 Kotlin 是一等公民；原型与产品同一套语言，纯逻辑直接长进产品；不上第四门语言（除非 proot-rs 成熟再议 Rust）。

### D16 引擎打包与运行时接线（2026-09-27，原型步骤 1 落地）
proot Termux fork 锁 tag v5.1.107.95，NDK 交叉编译（talloc 2.4.3 / libandroid-shmem v0.7 静态链接，`-landroid`/`-llog`），构建脚本 `scripts/build-proot.sh` 可复现。产物命名 `libproot.so` / `libproot-loader.so` 进 jniLibs，`useLegacyPackaging` 保证以真实可执行文件落地 nativeLibraryDir（targetSdk 29+ W^X 下唯一可 exec 位置）。loader 路径运行时经 `PROOT_LOADER` 环境变量注入——nativeLibraryDir 每次安装随机化，编译期无法烧入；Termux fork 原生支持该变量（src/execve/enter.c）。proot 主二进制按 16KB page size 链接（LOAD 段 align 0x4000，readelf 断言进 CI）。
被否：编译期烧 loader 路径（安装随机化）；运行时把 loader 复制到 filesDir 再 exec（W^X 禁止，SELinux untrusted_app 不可执行 app_data_file）。

### D17 rootfs 部署形态（2026-09-27，原型步骤 2 落地）
ubuntu-base 24.04.5 arm64（TUNA 主源、官方备源，sha256 pin 进 `RootfsManifest`）；解压为两段式——toybox tar 铺底盘（容忍硬链接失败），再用 rootfs 内 GNU tar 在 `proot -0 --link2symlink` 里补硬链接条目。原因：targetSdk 29+ 的 SELinux 禁止 app 在数据目录 link()（Termux 靠 targetSdk 28 逃过），link2symlink 把硬链接模拟成符号链接+底盘文件，且这正是 apt/dpkg 之后的运行形态；注意正确旗标是 `--link2symlink`（短旗标为小写 `-l`，大写 `-L` 无效且不报错）。apt 换 TUNA ubuntu-ports（deb822），DNS 写 223.5.5.5/8.8.8.8。
被否：纯 toybox tar（硬链接丢失，perl/uncompress 残缺）；提取后手工 copy 补链（与 apt 未来产生的硬链接形态不一致）。

### D18 终端链路与进程承载（2026-09-28，原型步骤 3 落地）
会话持久化用 **dtach**（宿主持有的 proot+dtach 进程，`-n` 分离建会话），ttyd 只 attach；终端页经 WebView 直连同源 ttyd 页（`-t rendererType=dom`），宿主注入 overlay（虚拟键条+观测桥），认证链 = 随机端口 + ttyd `-c` basic auth（护 HTTP 与 /token）+ AuthToken 应用层校验（护 ws）。
两条实测铁律：
1. **tmux server 的双 fork daemonize 在 proot ptrace 追踪下卡死**（进程停在 ptrace-stop，client 报 no server running）——dtach 单进程无此问题；tmux 复用待真机周或换机制再议（进 open-questions）。
2. **AMS 对死亡 App 按进程组清剿**（主进程 kill -9 后连 ppid=1 的孤儿也被回收）——环境进程必须挂在 **`:env` 前台服务进程**下才能在宿主 UI 崩溃/重启时存活；该服务（EnvService）已随步骤 3 提前落地，UI 经 `terminal-session.json` 发现端口与 token。
被否：宿主主进程直接持有环境进程（一死全灭）；WebView 页面 JS 凭据注入（webkit 层 auth 缓存不进 Chromium ws，改 hook fetch 补 /token 凭据）。

### D19 终端显示层用户定制：原型期不做，方向存档（2026-09-28）
用户侧的终端外观定制（字体/字号/主题）原型期不做；产品期方向已实测定调，避免阶段 2 重新调研：
1. **颜色主题走 OSC 转义序列，纯 shell 配置即生效**——xterm.js 原生支持 OSC 10/11（前景/背景）与 OSC 4（调色板），实测 shell 内一条 printf 即时换色（证据 `draft/av2-osc-theme.png`）。与 kitty/iTerm2/Alacritty 主题脚本生态同机制，社区主题可直接搬；官方 rootfs 预装几套主题命令（shell 函数发 OSC）即可。
2. **字体/字号走 App 设置页**（SharedPreferences → spawn 时拼 ttyd `-t` 参数，或经 overlay 注入 `term.options` 即时生效）；可选补约定配置文件 `~/.config/drydock/terminal.json`（宿主桥读取），设置页与文件写同一处，极客与普通人共用单一事实源。
3. **前置重构**：insets 垫色现为原生层硬编码 `TERM_BG`（TerminalActivity），OSC 换色后出现色差缝隙——定制落地前须把垫色移到页面 CSS 侧或经桥同步主题色。
边界：字体不走 shell 侧（OSC 50 为 kitty/st 私有，xterm.js 不认；Terminal.app/iTerm2 改字体同样要 GUI，属行业常态）。

### D20 agent 层落地铁律（2026-09-28，原型步骤 4 实测）
四条实测事实，均已被代码吸收：
1. **link2symlink 的 .l2s 链接必须自绑定宿主路径**：`--link2symlink` 把 link() 落成符号链接，目标为宿主绝对路径（realpath 规范化的 `/data/data` 拼写），仅在创建它的 proot 会话内可解析，**换会话即断链**——claude.exe exec ENOENT 实证；D17 pass2 补的 perl/gunzip/dpkg-status 同样中招（此前未踩中）。修复：所有 proot spawn 把 rootfs 宿主路径按 canonical 拼写自绑定进环境（`RootfsManager.l2sSelfBind`，Termux proot-distro 同款手法）。
2. **proot `-0` 下 Claude Code 恒为 root**：官方检查拒绝 root/sudo 使用 `--dangerously-skip-permissions`；headless（`-p`）用 `--permission-mode acceptEdits` 代替（自动批准工作区内文件写入，AV3 只需 Write）。交互场景本就走批准流（D9）。
3. **ubuntu-base 不装 ca-certificates**：Node 自带 CA store，npm 走 npmmirror 实测无碍；而环境内 apt 装 ca-certificates 会因缺 debconf 在 postinst 半配置卡死（`exec /usr/share/debconf/frontend not found`）毒化 dpkg，修复需补装 debconf 再 `dpkg --configure -a`。
4. **Claude Code 2.x 为原生二进制分发**：npm 包的 bin/claude.exe（~240MB）与平台包硬链接——正是 npm 的 bin 硬链接触发铁律 1；D8 的下载式安装与 D11 版本固化按此事实执行（pin 2.1.283，Node v22.20.0 官方 tarball sha256 双源交叉核对）。
被否：proot `-i` 假换 uid 绕 root 检查（acceptEdits 已够，不动 spawn 形态）；环境内装 debconf 预防 ca-certificates 问题（不需要的东西不进环境）。

### D21 仪器落点（2026-09-28 落地，2026-09-29 增补）
时间线记录器为 JSONL ring buffer（512KB 轮转保留一份 .old），ui 与 :env 两进程同 uid 追加；事件全集 = engineering-plan 清单（screen off/on、wakelock、service_start/destroy、proot_exit 含退出码、session_heartbeat 60s、cpu_sample 5min、battery、l1_alert），一键导出 = 合并 .old+当前经 MediaStore 落 Downloads/Drydock（复用 I4 落袋通道）。三条实测口径：
1. **心跳的"静默"指标用 holder 的 /proc/io rchar**（dtach 只从 PTY 读，rchar 增量即 PTY 输出量代理）；连续 5 分钟无增长 → L1 通知，每静默期只报一次。holder 经 :env 重启被 SIGKILL 时其监控线程同死，proot_exit 记不到——该场景由 service_start 新 pid + session_recreated 表达，只有 holder 单独死亡才有退出码。
2. **多会话 = 每会话一对 holder+ttyd**（dtach 无 server 复用，名字进注册表 terminal-sessions.json，:env 启动时全量重建死会话=空 shell）；UI 会话名经 Intent extra 传给终端页。
3. **基准电池计时器用 bash `$EPOCHREALTIME` ×5 轮取中位**（原定 hyperfine）。弃用原因（AVD×proot 实测）：app 语境下对 `--setup`/`-w` 组合必现无声退码 2（同命令手动全过、app 内裸命令直跑正常，机理未明）；多子进程命令与 npm 整树楔死 ptrace-stop（fork 密度相关，单进程命令稳定通过）。npm 用例加预检/开关：registry 不通或 `--ez skip_npm` 即记 SKIPPED，电池仍出 JSON（D12 网络现实）。真机周再评估恢复 hyperfine。**proot 楔死为产品层真问题**：对 Q1 的威胁形态=任务冻死而进程活、WakeLock 空耗，列真机周重点观测。
另三条环境实测教训：rootfs 磁盘状态可被毒化（环境内全灭而同 uid 非 proot 进程正常的不对称性即铁证），重放即愈=Q8 救援通道的正向验证；tar 全目录会撞 D17 的 .l2s 自指环（ELOOP），**D7 环境导出 tar 功能必须排除/转换 .l2s**；edge-to-edge 下滚动列表必须 navigationBarsPadding（末尾按钮被手势条吃掉）；重装 APK 重置运行时权限（验收用 pm grant 补）。宿主代理 TUN(fake-ip) 劫持 AVD guest 流量属环境干扰，不进产品路径。

### D22 HyperOS 熄屏冻结与省电白名单（2026-10-01，真机周 D1 实测）
小米13 / HyperOS 3.0.2（Android 16）上，**默认省电策略 = 熄屏即全树冻结**：会话与任务进程进 ptrace-stop（tracer=proot 本体）、:env 进程活着但定时器/广播/时间线全部停摆（仪器与被测者共生死）、合法持有的 PARTIAL_WAKE_LOCK 被系统置 DISABLED。冻结跨充电态（8h 满电不恢复），**解锁瞬间全部解冻且零数据丢失**——任务语义是"暂停"而非"死亡"。A/B 钉死：应用设置省电策略改「无限制」（+自启动）即映射进 `dumpsys deviceidle whitelist`，熄屏 10 分钟任务全程存活、WakeLock 恢复持有；正式 S1 样本（60min 电池供电、白名单态）零断档、耗电 1%。产品含义：**首次安装必须引导用户改省电策略（原型已落省电状态卡）；L1 静默告警需区分"任务静默"与"疑似系统冻结"（原型已落 freeze_suspected：monitor 5s tick 断档 >60s 判定，AVD SIGSTOP 93s 实测）**。Q3 矩阵 Xiaomi 行第一格：省电策略白名单 = 保活硬前提。
配套实测教训：ttyd 直连 ws 客户端在该设备输入帧不达（鉴权/旁观正常）——验收注入走 CDP→xterm insertText 通道；`am start` 不重建已存任务的 MainActivity（栈顶是终端页时 --es 注入失效，须 -S）；dtach 无回滚，晚接入客户端空屏至新输出；Compose 列表按钮会滚出可视区，uiautomator 只见可视节点（驱动前先滚动）。

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
