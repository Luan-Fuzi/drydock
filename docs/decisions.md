# 决策记录

记录截至 2026-09-26 的产品与技术决策。格式：决策 → 理由 → 被否方案。

## 产品定位

### D1 产品定义：手机上的 agent host（2026-10-05 修订：四事务 → 三事务）
agent 宿主 = **环境生命周期管理 + 任务调度 + 文件落点**三件管家事务，底下跑各家官方 agent。（原第四件「密钥保管」经 D29 删除：key 走环境变量由用户自管，编辑器与 agent 代配是宿主的界面，保管不是宿主的职责——见 D29。）
理由：这是 Termux（终端模拟器 + 包管理器，不是工作负载管理器）结构上不做、云端沙箱物理上做不到的位置；三件事均被 ZeroTermux（备份恢复）、Happy/Omnara（投影 GUI）等单点产品部分验证。
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
**边界修订（2026-10-08，用户定调，R3 落地）**：否决项「内置编辑器」收窄为否决**工程级编辑器**（语法高亮、多文件、工程管理、LSP）；**小文本查看+编辑+保存**纳入边界内——单文件全屏编辑（等宽字体，顶部保存/取消），判定 = 纯文本（首 8KB 无 NUL）且 ≤1MB，UTF-8 严格解码失败按二进制甩外部 App；保存前文件 mtime 与进入时不一致（agent 或外部应用改过）先提示覆盖风险。动机：改 env.sh / 小配置 / agent 小产物的真实高频场景成立，env.sh 设置页内编辑器已是先例。

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
   **2026-10-07 梯度复测降级（draft/wedge-verdict.json）**：用户提议从小到大复测——npm 安装全谱系（is-odd 0 依赖 / lodash / esbuild 平台二进制 / typescript / vite 中树 / next 巨树，共 12 次）+ hyperfine `--setup npm-install -w1 -m3` 原始触发器，全部通过、零楔死、零 stop 态进程（proot 译层正常工作时被跟踪进程仅在 syscall 边界瞬时停，4s 采样不可见；真楔死则持续可见——判据成立）。同 APK 同 proot 下不可复现，最可能诱因是当时的 rootfs 毒化状态（本条同文上方另录）。**楔死从「常态威胁」降级为「特定状态历史现象」**：检测缓解版紧迫性下调，Q1 观测从重点降为留意。附带方法论教训：CDP 驱动的长脚本必须 keyDown/keyUp 成对（漏 keyUp 让 xterm `_keyDownSeen` 永久卡 true、insertText 全被吞）且注入确认走文件标记不走屏幕子串（43 列折行制造假阴性）——本实验三轮假失败全因测试驱动自身。
另三条环境实测教训：rootfs 磁盘状态可被毒化（环境内全灭而同 uid 非 proot 进程正常的不对称性即铁证），重放即愈=Q8 救援通道的正向验证；tar 全目录会撞 D17 的 .l2s 自指环（ELOOP），**D7 环境导出 tar 功能必须排除/转换 .l2s**；edge-to-edge 下滚动列表必须 navigationBarsPadding（末尾按钮被手势条吃掉）；重装 APK 重置运行时权限（验收用 pm grant 补）。宿主代理 TUN(fake-ip) 劫持 AVD guest 流量属环境干扰，不进产品路径。

**D31 增补（同日，DSH 真机打通）**：真机补装 dsh 0.2.0-rc.2，实测它**不是多协议工具**——内嵌 @anthropic-ai/sdk 走 Anthropic /messages 协议（OpenAI 兼容端点 404）。用 GLM coding plan 驱动它的完整路径：`DEEPSEEK_API_KEY`+`/root/.dsh/cordis.patch.yml` 覆盖 llm-deepseek 插件（baseURL=https://open.bigmodel.cn/api/anthropic、maxTokens=131072——默认 256000 超 GLM 上限报 1210、apiKeyEnv=ZAI_CODING_CN_API_KEY）+ agent-default-model（model=glm-5.3-flash）→ headless 出话；终端页菜单「DSH Web」真机实测拉起系统浏览器进入 Web UI。三工具现状：env.sh 一把 key 通用（opencode/pi 内置识别、dsh 经 patch 接线），GLM 额度覆盖全部三家。

### D33 rootfs 升级的 /root 迁移细则（2026-10-08，用户定稿，D26 留白收口）
D26 把「/root 用户文件迁移细则」留白随产品期实现定，R8（rootfs 升级路径）动工前对齐，四条定稿：
1. **黑名单式全量迁移**：排除 `.l2s*`（D21 自指环教训）、`.npm/`、`.cache/`（可重建缓存）、`AndroidDownload/`（绑定挂载点，本体在宿主 Download）、`*.sock`（运行时 socket，会话重启自建），**其余一律迁走**——含 `.drydock/`（端点与密钥）、`.config/`、`.local/`、`.pi/`、`.npmrc`、`.ssh/` 与全部工作区。理由：升级不替用户判断哪个文件不重要，黑名单比白名单少丢东西。
2. **用户文件优先**：与新版 rootfs 自带文件（`.bashrc`/`.profile` 等模板）同名冲突时用户版一律保留，新版模板仅在文件不存在时落位；「重置默认 dotfiles」显式入口本期不做（要新默认值的用户手删该文件再走升级，后续按需立项）。与 R4 恢复的「同名弹询问」不同：升级迁移是后台一次跑完，逐文件交互不现实。
3. **apt/npm 用户自装包不自动重装**：用户自装包（旧环境 dpkg/npm 全局清单减去基础层幂等清单与配方 pin 集）在升级结果报告中列出并附一键重装命令；不自动重装——重装走网络且可能交互失败，违反配方「过程可见、失败可重试」哲学。基础层 17 包由 ensureTerminalLayer 幂等补装，配方由 RecipeManager 按 pin 重放，不属「自装」。
4. **回滚 = 反向迁移**：回滚到上一版时 /root 反向迁移（新版→旧版），升级后新产生的改动不丢；与正向复用同一套迁移代码。
升级切换时活跃会话的处理（拒绝切换或自动停会话）属实现细节，随实现定并写入提交记录。



**① xterm.js 5.3.0（ttyd 1.7.4 内嵌）Android 输入链丢尾字**。现象：WeType 打「你好」回车发送只剩「你」、「今天几号」只剩「今天几」——输入框显示完整、发送瞬间少最后一个字。根因（真机 xterm 实例 dry-run 三角合成实锤）：Android 所有 IME 提交被 Chromium 包在 `keydown(229)/keyup` 之间，xterm 5.3.0 的 `_inputEvent` 门控 `(!composed || !_keyDownSeen)` 在包裹期恒关，实际发送靠 229-keydown 快照 + `setTimeout(0)` 差分且发完不清 textarea——微信输入法换行键会先清理 IME 编辑状态（textarea 残留被清空=值变短），差分逻辑误译成一个 DEL 发给终端，回车发送前最后一字被删；自家键条 ↵ 走 keydown(13)（顺带清 textarea）无此问题。修法：terminal-overlay.js 运行时替换 `_handleAnyTextareaChanges`——值变短不再立即发 DEL（整段清空≥2 字直接判换行清理丢弃；其余挂起 40ms 内有回车随行即丢、无则如期补发，真退格通道不变）。已知残留：单字+超窗慢回车理论漏一个 DEL（观察期）。**关键认知**：WeType 在 Android WebView 上完全不走 composition 事件（拼音预编辑在键盘内部、上屏单批 insertText）——与桌面浏览器 IME 行为模型不同，照搬桌面 composition 竞态假设会诊断错方向（本次第一轮即因此走偏）；且 ttyd/xterm 升级前必须重跑 IME 场景矩阵（draft/ime-patch-verify.py 八场景，合成事件须同任务内 dispatch——CDP 逐条往返的人为时序会制造假阳性全丢）。

**② npm 升级 × proot link2symlink 断链（Q8 首个真实实例，生态级已知问题）**。用户环境内升级 opencode-ai 1.18.34→1.18.35，npm「删旧建新」链接序列经 l2s 翻译后清掉 185MB 平台真身（`.l2s.opencode0001`），三层符号链接完好但指向不存在=bash command not found；**旧进程活在内存里掩盖数小时，宿主重启才暴露**。生态证据：AgentNet #116 做到 syscall 级——proot 的 `link()` 在 untrusted_app 域**假成功**（返回 0 但文件从未落盘），git 默认用 link() 写 loose object 会静默丢对象；proot 上游 14 个 l2s 相关 issue。风险面：npm 带原生二进制的包（esbuild/sharp/swc 形态）升级时都可能断；pnpm 架构性依赖 link/symlink 最重（环境内暂不建议用）；pip（wheel 解压复制）/apt（dpkg unpack+rename）/go install（编译直写）/gem 安全。落地：BinDoctor 自愈层（scan 解析断链自带修复信息→宿主 npmmirror/npmjs 拉平台 tarball→环境内解压落位，断链成串按 target 深度降序防中间层链接实体化、fix 内 SKIP 传导修复，只新增文件幂等可重试，修不了如实 FAIL）+ `/etc/gitconfig` 幂等写 `core.createObject=rename` 预防 git 假成功。AVD 实证删真身复刻故障→自动修复→`opencode --version` 端到端恢复。

**方法论教训（无视觉模型诊断真机问题的可复用工作流）**：只读取证（CDP 读 term buffer/body.innerText，比截图可断言）→ 事件流 hook（textarea 的 composition/input/keydown + 包装 `triggerDataEvent` 记录 xterm 实际外发）→ 真键盘抓取定位真实事件序列（用户配合一次）→ 合成事件在同任务内 dispatch 复现（精确控制时序交错）→ dry-run 替换 triggerDataEvent 做无损实验（不污染用户会话）→ 修复后合成场景矩阵全绿再上真机人测。两条元教训：「坏了不立刻知道」类缺陷（内存进程掩盖/断链掩盖）的验收必须含重启后首启路径；断链类故障的结构往往自描述（链接路径自带包名/版本/位置），宿主同 uid 直修 rootfs 的救援模式成本极低——凡「环境坏了」先查结构里有没有自带修复信息，再考虑重装。

### D31 GLM Coding Plan 走内置 provider：手写段退役（2026-10-06，用户质疑触发核实）
**用户质疑「π 读图不工作 + 上下文 128K」→ 核实出双层错误**：①contextWindow 被写成 131072（D30 重构注入时把 maxTokens 钳制思维错用到 context 值上）；②更根本——pi 内置目录本就有 `zai-coding-cn` provider（baseUrl 恰为 coding plan 端点，glm-5.3-flash 条目自带 input:["text","image"]、1M 上下文、官方 compat 配置），opencode 侧 models.dev 注册表有 4 个智谱系 provider（env 统一 `ZHIPU_API_KEY`，含 zhipuai-coding-plan/zai-coding-plan 两个 coding 专用条目）。**手写自定义 provider 段把内置识别整个屏蔽了**——多模态「不工作」是手写条目缺 input 声明的衍生症状，不是模型或工具的问题。
**终版接入**：env.sh 放 `ZHIPU_API_KEY`（opencode 约定，设一各点亮 4 provider，用 disabled_providers 收敛到 coding-plan 两家防误选通用端点扣余额）+ `ZAI_CODING_CN_API_KEY`（pi 约定，变量名精确匹配大小写敏感）；两个工具的手写段删除；`opencode models` 自动出现（56→收敛后 coding-plan 两家）、`pi --list-models` 自动出现 4 模型（glm-5.3-flash 标 images=yes、1M）。**教训**：接一家新厂商前先查工具内置目录（pi `--list-models`、opencode `opencode models`、models.dev api.json）——「零配置识别 + 官方维护元数据」几乎总是优于手写段；自定义端点机制只服务真正的目录外长尾。
**核实来源**：pi-ai 1.0.0/1.0.4 发布包 zai-coding-cn.json（4 模型含 glm-5.3-flash text+image）；models.dev api.json（4 provider 全含 glm-5.3-flash，modalities 含 image/video/pdf）；智谱官方 docs.bigmodel.cn coding-plan/tool/pi 与 /opencode 页；opencode v1.18.34 源码 provider.ts env 识别逻辑。

### D30 端点列表化### D30 端点列表化：向导只引导，设置页表单追加（2026-10-06，用户三轮修正后定稿）
**向导端点步退化为纯引导**（可跳过）：只讲两件事——内置目录厂商往 env.sh 放标准变量名即自动识别（opencode 经 models.dev、pi 经内置 catalog，元数据全带，实测核实）；自定义端点去「设置 → Coding 端点」填表。**设置页承载配置**：已添加端点列表（provider 名/baseUrl/模型/key 变量名可见、逐条删除）+ 追加表单（协议/Base URL/模型 ID/上下文可选/key 变量名/provider 名可留空自动生成）。**写入语义 = 列表重算**：opencode.json 的 provider 对象按名合并、pi models.json 的 providers 按名替换——只动列表内名字，agent/用户手写的其他段原样保留（AVD 实证：预放手写段三轮重算后完好）。
**演化过程如实记录**：第一版预设表（GLM 两条）被用户否——「一开始选择够多是抱薪救火」；第二版「退役生成器全靠 agent 代配」被用户否——鸡生蛋（agent 没模型前没法让 agent 配）；第三版「向导预设」再被否——不能每换一家模型就写一遍，正确引导是「key 用标准名 + 自定义 URL 用户自己配」；终版 = 引导 + 表单追加，宿主从「生成器」退到「表单 → 两个工具同构 JSON 的搬运工」（映射固定，不跟工具版本赛跑）。**教训入册：配置 UI 的每次扩张都要先问「这表/这预设会不会变成永远追不上现实的维护负债」。**
**技术细节**：pi 的 maxTokens 会作为 max_tokens 发给 API，GLM 端点限制 ≤131072（1210 实锤）——上下文出处值钳制 128k；合并用环境内 node（ubuntu-base 无 python3，apt 装有 dpkg 卡死风险，配方已带 node）；Kotlin raw string 与 JS/JSON 花括号转义踩坑三轮（${'$'} 字面量在转义拼接字符串里不插值），终版用普通字符串拼接 "$" + e.envVar。provider 真名/去 drydock-default/knownModels 退役沿用第一版结论。
### D29 删除密钥托管层：key 交还用户环境变量（2026-10-05，用户拍板，推翻 I1/D27 密钥两层制）
**宿主不再保管密钥**：SecretStore（Keystore）与 KeyVault（多 key/会话级注入）整体删除；API key 由用户写进 `~/.drydock/env.sh`（变量名 DRYDOCK_API_KEY，新会话生效），或把 key 发给终端里的 agent 让它代写。生成的工具配置不变（仍引用 `{env:DRYDOCK_API_KEY}` / `$DRYDOCK_API_KEY`），只换了值的来源。runInEnv 统一先 source env.sh（非登录 shell 与登录 shell 的 profile.d 同口径），smoke/AV3 仪器随之续命。
**决策理由（用户三轮推演）**：①工具生态本来就是 env-var 原生（Claude Code/Codex/各家 CLI 全读环境变量），一个通用的环境变量入口覆盖所有工具的所有变量，而 Keystore 只覆盖我们自己想到的那一个——「不可能用有限的努力对抗无穷的变量，把窗口做好」；②单变量注入确实覆盖不了多端点/多模型并存（配置层多 provider 引用各自的变量名即可解）；③明文凭据文件是生态常态（~/.ssh、~/.aws、opencode/pi 的 auth.json 全是明文），文件浏览器可见自己的文件是普遍接受的边界——宿主不必比全行业更圣洁；④防误不防恶的定位下，托管防住的面（误分享/误导出）改为在导出说明里明示「env.sh 含用户自行存放的 key」。
**代价（如实入册）**：会话级「不注入」能力消失（env.sh 对所有会话生效）；key 明文落 env.sh，随导出 tar 走；env.sh 在 /root 下，rootfs 重装会抹掉（重装后需重新配置）。被否：保留 Keystore 作为新手默认路径（用户定调「做得干净点」，两套机制并存徒增心智负担）；KeyVault 泛化为「命名密钥→命名变量注入」（在 env.sh 已覆盖该需求的前提下属于重复建设）。迁移：装新包前从存活 holder 的 environ 提取现役 key 写入 env.sh，用户无感。
**后续**：设置页「API key 与环境变量」指引（编辑器 GUI 暂缓，env.sh + agent 代配已可用）；motd/向导文案同步；night-b t8（多密钥会话级注入）随功能删除，t7 向导步骤更名「端点与模型」。

### D28 夜批补全：导出口径、多密钥形态、直通绑定最小版与五处实锤缺陷（2026-10-04，AVD 全实证）
一夜跑完 B 类测试清账与 A 类缺口补全（verdict：`draft/night-b-verdict.json`，t1–t11 全绿；剧本 `scripts/night-b.py` 可复跑）。四项口径定稿：
1. **环境导出口径收窄为「工作区与配置」**：/root 全量（排 .l2s/npm/cache/绑定目录）+ drydock 的 /etc 片段，经 MediaStore 落 Downloads/Drydock。实证依据：全环境 gzip 后 ~2GB、proot 下十分钟级，作为卸载前备份不可行；系统层（apt 包、node 运行时、配方）按 D8 版本 pin 可重放，本就不该进备份。I1 的「导出无密钥」性质保持。UI 附卸载强提醒（私有目录随卸载蒸发）。
2. **密钥两层制第②层 UI 形态定稿（KeyVault）**：设置页列表（名称+掩码+设默认+删除）+ 添加（名称+值）；新建会话时弹选注入——默认密钥 / 指定条目 / **不注入**（「部分密钥不想让环境拿到」的落点）；会话注册表记 keyId，恢复时按原选择重建；旧单 key 首访迁移为「初始密钥」默认条目，行为兼容。实证：不同 key 的会话 holder environ 各取其值、「不注入」会话环境里无 DRYDOCK_API_KEY。
3. **文件互通第三档（直通绑定）最小版落地**：固定绑定手机 Download ↔ 环境 /root/AndroidDownload（proot -b），默认关闭的高级选项 + 系统授权引导（真机上用户本人开，真机纪律）；绑定参数进 holder/ttyd/runInEnv 全部 spawn 点（环境视图一致，救援通道同口径）。实证与代价：app 进程双向读写通（含 FUSE）；**run-as 通道视角不具代表性**（appops 已 allow 仍 Permission denied——验收探针须走 app 进程口径）；跨 uid 的 0660 文件（如 adb shell 所造）读不到——as-is 记录为该档代价。导出显式排除绑定目录。
4. **终端页内菜单（D27 增补条目，用户批的计划外加项）**：右上角浮钮——会话列表切换（含端口）/ 新建（默认密钥）/ 回主页，AlertDialog 最小实现。
五处实锤缺陷修复（均为测试驱动发现）：`writeAptSources` 的 trimIndent 裁掉 deb822 续行缩进——**自 eb7be9b 起写出的 ubuntu.sources 全部非法（Malformed stanza），凡跑过 resetAptSources/configure 的环境 apt 已坏，AVD 已修，真机侧待白天修**；aptTools 失败静默（fd 符号链接盲建悬空、rg 缺失仍 RECIPE_RC=0）——工具校验进 RECIPE_RC；`RecipeManager.ensure` npm 首选源默认 npmjs 与 D12 国内优先意图不符——改回 npmmirror 默认 + 回退 npmjs 不变（假源注入实测回退链真实代码路径成立）；EnvService `ensureAll` 串行恢复历史会话把新会话排队到 60s 轮询之外——新会话优先；主页会话列表不随回前台刷新、主题切换不即时生效（组合期只读一次）——5s 轮询 + recreate。另：D18 的「tmux 复用待再议（进 open-questions）」悬空指向就此闭合——dtach 无 server 的现状即定案，不再立项；debug 通道 `byId` 大小写不敏感；`am start --es` 值含 `|` 须引号包裹（设备端 shell 管道符，exec64 同源教训）。

### D27 界面定义 v1：底部三栏（会话/文件/设置）与密钥两层制（2026-10-03，用户拍板）
**主页 = 底部导航三栏**：①会话（管理已开终端、新建、空状态承载首启教育）②文件（Linux 目录树浏览；文件经 WorkspaceProvider 的 content URI 甩系统应用打开——有界浏览器，非文件管理器，D7 维持）③设置（镜像源、外观昼夜、端点与密钥入口、开发者工具收纳旧验收仪器）。
**镜像源设置页 = `~/.drydock/mirrors` 覆盖文件的 GUI 编辑器**：默认回退链零配置不变（TUNA→官方、npmmirror→npmjs），GUI/手编文件/让 agent 改三条路等价写同一数据源，不构成配置中台。
**密钥两层制（奥卡姆剃刀后）**：普通变量 `~/.drydock/env.sh`（新 shell 生效）+ Keystore 多 key 按会话注入。**否决第三档 vault 通道**（`drydock seal` 包装拉取式注入）：同 uid 下 `/proc/environ` 可读使其「防恶」不成立、「防误」两层已覆盖，工程量最大而边际收益最小。密钥不进环境内文件的硬理由：环境导出 tar（D7 规划功能）会常态化携带文件态密钥、按会话选择性注入只有文件态不存在时才可能。威胁模型口径：防误不防恶，运行期暴露（printenv）为单 uid 架构固有代价。（2026-10-04 夜批：多 key 管理与按会话注入/不注入的 UI 形态定稿并实证，见 D28-2。）

### D26 版本升级路径与分发渠道（2026-10-03，阶段 4 收口）
**版本升级**：一切外部来源版本 pin 死（rootfs 走 `RootfsManifest`，D17；配方与 Node 走 `RecipeManager`/`AgentManager` 版本常量，D25），环境内自升级一律禁用。升级 = 宿主侧改清单 + 重走装机判据；产品期实现形态定为**旁路部署 + 原子切换**：新 rootfs 下载解压到旁路目录 → sha256 校验 → 目录重命名原子切换 → 保留上一版本一份供回滚，磁盘峰值约 2×rootfs。配方升级复用 npm install 语义（幂等装新 pin 版本），不引入第二套机制；终端层（ttyd/dtach）随 rootfs apt 源走。/root 下的用户文件与工作区在切换前迁移，细则随产品期实现定。
**分发渠道**：非商业阶段 GitHub Releases 侧载，APK 只从 main tag 构建（git 纪律）；国内链路不依赖 GitHub——rootfs/Node/npm 主链路已镜像化（TUNA/npmmirror 实测直连，配方安装 OpenCode 19s / pi 12s）。**不做应用商店上架承诺**：主链路在运行时下载可执行代码，Google Play 政策对此严格限制（Termux 前车之鉴），「引导下载器」形态的合规论证成本高、收益存疑，不投入。F-Droid 纯 FOSS 构建链（proot 自编译过其 CI）留待有兴趣时评估，不阻塞判据。
被否：环境内 apt/npm 自升级承载产品升级（版本漂移不可复现，违反版本固化）；Play 商店首发（政策与主链路冲突）。

### D25 宿主与 agent 解耦：agent 无关的终端宿主、首启引导与文件边界分层（2026-10-03，用户拍板；同日补定默认路径全开源、端点零预置）
**宿主不绑定任何 agent**：安装 agent 不再是装机必经步骤，以「配方」形态提供——安装脚本 + 端点预设 + 密钥环境变量映射，在终端内执行、过程可见、失败可重试。默认引导只含开源配方：OpenCode、pi（均 MIT，可预置默认配置与模型环境变量，用户开箱即可选模型）；Claude Code 不进默认引导，用户自行安装则宿主不阻止（专有许可，仅下载式使用，不改二进制）；另可跳过、自行安装。默认路径全程开源工具链（proot / ttyd / xterm.js / Node / OpenCode / pi）。宿主价值收缩到 agent 无关的四件管家事务；底层为 glibc Ubuntu 用户态（D17），agent 官方 arm64 构建原生可运行——「agent 无关」在架构上近零成本的根据。
理由：①用户可能不用 Claude Code；②按 agent 逐个适配包装层无法泛化——hooks 批准卡片是 Claude Code 专属机制（Q5），codex/opencode 事件模型各不相同；③许可姿态（2026-10-03 查证）：从 npm 官方源代为下载等价于包管理器行为，红线是随 APK 再分发、修改二进制、代用户付费或中转用量，均不触碰；预设第三方端点（GLM）属上游「不支持」而非禁止，安装选择权归还用户后该风险随之缩小。
**首启引导为一次性向导，至多三步，逐步可跳（保活除外）**：①保活设置（省电白名单引导，D22，强引导）②端点与密钥：零预置厂商，先选 API 协议（Chat Completions / Responses / Anthropic Messages 三类预设），再填 Base URL 与 API Key（key 只进 Keystore，按进程注入；D12 的下载镜像源与此无关，维持国产优先）③agent 配方（预置模型环境变量，用户直接选模型）。原型步骤 4 的 GLM 端点预设（AgentManager）是 AV3 验收仪器的一部分，不进产品路径。首启之后的个性化配置（shell 启动文件、启动行为等）不再扩展 GUI，由用户在终端内让 agent 协助完成——「agent 是配置器」。
**端点零预置的必要补充（2026-10-03 真机实证）**：同一厂商的「订阅型专属端点」与「按量 API 端点」是两条 URL，订阅额度不覆盖按量端点——GLM Coding Plan key 在 `/api/paas/v4` 报 1113 余额不足，在 `/api/anthropic`（Anthropic 协议）与 `/api/coding/paas/v4`（Chat Completions 协议）均 200（真机实测，产品作者本人踩坑）。向导端点步骤须教育此区分（提示「订阅用户填厂商给的专属端点」），但不因此预置厂商；另 pi 内置 ZAI Coding Plan 支持（`ZAI_API_KEY`/`ZAI_CODING_CN_API_KEY`），配方可选利用。系统镜像不提供发行版选择：单一镜像走 D17 的 manifest 路线，每新增一张镜像都是一套 proot 实测成本，且目标用户对发行版无感；用户可见的是版本升级而非品牌选择。环境变量引导与文件操作不做 GUI，由首启 motd / 终端内引导脚本承接（D24 终端原生原则的延伸）。
**文件互通按安卓权限现实分四档执行**（D7「边界桥」的落地顺序）：环境→安卓 = MediaStore 保存到 Downloads/Drydock（已有，I4）+ DocumentsProvider 将 workspace 提供给全安卓文件选择器（下一步，性价比最高）；安卓→环境 = 注册系统分享目标 + SAF 文件选择器，选中文件复制进 workspace；双向直通 = proot -b 绑定安卓目录需 MANAGE_EXTERNAL_STORAGE（全文件访问权限，应用商店审核受限），做成默认关闭的高级选项并如实标注代价；任意目录双向实时同步不做。（2026-10-04 夜批：前三档全部落地实证——ACTION_VIEW 甩系统应用经 Resolver 确认、provider 补齐 delete/rename 全回路自测、第三档最小版见 D28-3。）
**开源为选型硬前提**（2026-10-03，用户定）：工具链尽量开源——proot 本为 GPL 开源（Termux fork，宿主自编译），闭源候选 proroot 出局，Q7 收敛为 Termux fork 的确认性实测（见 open-questions）。**环境内 Web 服务直达安卓浏览器**（2026-10-03，用户提方向）：proot 不隔离网络，环境内起的服务即监听手机本机回环，系统浏览器直接打开 `http://127.0.0.1:端口`（终端页本就是该机制的实例）；产品工作只剩可发现性——监听端口面板 + 一键用浏览器打开。「Web 产物预览」由 WebView 内嵌升级为系统浏览器直达，不做 X11/VNC 图形桌面的理由更充分。
与 Termux 的差异定位重申：区别不在终端，在四件管家事务的默认可用（环境生命周期、任务存活、密钥保管、文件边界）；其中任务存活是 Q1 赌注而非纯工程。驾驶舱残余角色裁定（2026-10-03，用户定）：删除——不做逐 agent 适配，hooks 网关（Q5 机制）没有通用场景；CockpitActivity/CockpitManager 与主页入口已移除，Q5 遗留项随之留档关闭。
术语规范（2026-10-03，用户要求）：新文档行文用「保存/导出到 Downloads/Drydock」等直白表述，不再使用「落袋」等内部口语；存量文档与代码标识不追溯。
被否：①常驻配置中台（在用户与终端之间再立一层包装，与 D24 矛盾）；②发行版交给用户选（测试矩阵成本，目标用户无感）；③环境变量编辑器与文件管理器 GUI 化（通用文件管理器本在不做清单）；④任意目录双向实时同步（冲突处理工作量与价值不成比例）。

### D24 产品形态定调：终端原生为主屏，不自建对话渲染（2026-10-01，用户拍板 + 真机实证）
**主屏 = 手机终端里直接跑 `claude` 交互模式，其原生 TUI（Ink/ANSI）经 PTY→ttyd→xterm.js 原样渲染，零转译、流式天然成立**；虚拟键条（方向键/Esc/Tab）保留——claude TUI 的菜单导航恰需要。真机实证（小米13）：首启引导（主题/安全提示/信任目录）经"数字直选 + ArrowDown + Enter+text\r"走通，主界面输入框、对话往返（5s 出话）、auto mode 状态条全部正常渲染（draft/claude-tui-*.png）。批准流分层：交互模式走 claude 自身权限 UI（2.1.283 默认 Auto 模式：自动风险评估+高风险拦截）；headless（驾驶舱/调度）走宿主 PreToolUse hook 网关（Q5 机制）。
**驾驶舱重定位**：从"对话主界面"降为辅助层（发起任务、产物落袋、通知；headless 场景的批准卡片），不再复刻对话渲染——避免与 agent 官方 UI 的版本追赶（同"明确不做：聊天气泡复刻"的理由延伸）。
配套实测：①CDP 合成回车必须带 `text:"\r"`（不带则 TUI 不吃，insertText 通道天然有效——数字直选可用）；②HyperOS"充电时不熄屏"压不住自动锁屏（设置全对仍 10 分钟锁），产品正解 = 终端/驾驶舱页 FLAG_KEEP_SCREEN_ON（已落地）；③proot -0 无 uid 映射使 claude 跨会话消息 socket 关闭（警告非致命，观察项）；④ttyd 页 WebView 的 DevTools socket 建立晚于 Activity 启动数秒，CDP 前需重试。

### D23 工况定位：不做抗恶劣环境专项（2026-10-01，用户拍板）
对齐主流 coding agent 产品对宿主活跃度的合理预期：目标工况 = 任务 5–30 分钟、安装时引导配置省电白名单（D22）、任务中屏幕可熄可亮。**不做**超长时/深 Doze/夜间维护窗口的专项加压（原 8h 过夜样本取消，S1 判据已以 60min 正式样本通过）；熄屏挂机能力经 D22 A/B 实测为白名单下的白送优势（60min 耗电 1%），保留但不追加强度。若产品期确需"亮屏保活"模式，实现路径为任务期 FLAG_KEEP_SCREEN_ON（app 自持，不依赖开发者选项）。被否：为通过 OEM 极端杀后台做进程级对抗（黑科技保活）——与厂商策略军备竞赛，工程量和口碑风险不成比例。

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
| 全 CLI 生态适配 | 不做逐个 agent 的包装层适配（机制为各家专属、无法泛化）；接入走配方，任何 agent 在终端原生运行（D25） |
| 常驻配置中台 / 环境变量与文件管理 GUI | 与终端原生定调矛盾（D24/D25），配置引导由终端内引导脚本承接 |
| 任意目录双向实时同步 | 冲突处理工作量与价值不成比例；文件互通按四档分层（D25） |
| 以赚钱为目标 | 已明确非商业项目 |
