# 待验证问题

动手前先看这里。按优先级排序，前两项决定项目存在性。

## Q1（核心存在性验证）：锁屏挂机链路是否"敢依赖"？

agent 任务一跑几十分钟，手机会锁屏、降频、被杀后台、发热。前台服务 + tmux + WakeLock 只能缓解不能根治——这条链路的真实体验决定产品成立与否。

**验证方式**：2–3 周丢弃型原型，不求产品化——proot + ttyd + xterm.js + 预装 Claude Code（npm 下载式）+ GLM 端点，装在自己的安卓手机上真用一周。观察：锁屏 30 分钟后任务是否还活着、被杀后 tmux 能否接回、发热程度。**结论（正/负）直接决定项目继续或转向，比任何分析都值钱。**

**真机周新增重点观测（2026-09-29，probe-ready 实测衍生）**：proot ptrace 楔死——AVD 上 hyperfine 管理的多子进程命令与 npm 以约半数概率整树停在 ptrace-stop（D18 家族，与网络无关）。真机上须验证 agent 长任务（claude 直跑形态从未复现楔死，密集 fork 形态高危）；时间线记录器已能从心跳/rchar 与 proot_exit 观测此类异常。另：基准电池在真机上重跑 npm 用例并评估恢复 hyperfine；宿主代理与时钟跳变两类干扰在真机不存在。

**2026-10-01 收口（真机周 D1）**：S1 正式样本已采（60min 电池供电、白名单态、零断档、耗电 1%，`draft/s1-soak-verdict.json`）；冻结死因与白名单解法经 A/B 钉死（D22）。**原计划的 8h 过夜加压样本取消**：判据已过、机制已明，产品工况定位收缩为"任务 5–30 分钟、安装引导白名单、任务中屏幕可熄可亮"（对齐主流 agent 产品对宿主活跃度的合理预期；熄屏能力经实测为白送优势保留，不做抗恶劣工况专项）。深 Doze/夜间维护窗口若后续真用中自然出现异常，按 Q3 矩阵记录即可。
**2026-10-08 终局收口（用户拍板）**：S3 ✓（45min 电池供电熄屏混合负载 gzip/sha256/tar/stat/npm：热区 45→48°C 后平台稳定、圈速前后半程中位 70.5s→69.8s（-0.9%）无降频、CPU 频率峰值无下滑、负载零断档、用户体感温和；仪器 `scripts/s3-heat.py`，verdict `draft/s3-heat-verdict.json`）。S1/S2/S3 三判据全过，**Q1 结论（正）：锁屏挂机链路敢依赖 → continue，进入产品化推进**（冻结=可解的暂停而非死亡，D22 白名单解法+首装引导已落；楔死=特定状态历史现象，D21 增补）。

## Q2（需求验证）：目标用户真的会用吗？

"想要 TUI 但装不来 Termux"和"想要 agent 但不想碰终端"两个交集人群的盘子大小是假设不是事实。

**验证方式**：原型装给 2–3 个不懂终端的朋友，什么都不教，一周后看使用痕迹。判据：留下来的人是否在干"云端不方便干的事"（本地文件、隐私、随开随用）。没人第二次打开，答案也清楚了。

## Q3：OEM ROM 保活矩阵

小米 HyperOS / OPPO ColorOS / vivo OriginOS / 荣耀 MagicOS 的杀后台行为与白名单设置路径各不相同，需要实测建表。原型阶段先覆盖自己手上的机型。

## Q4：新安卓特性对环境层的影响

16KB page size 等新特性对 proot 与 rootfs 内 glibc 二进制的兼容性影响未验证（部分新设备内核页尺寸变化可能导致未按其编译的 ELF 失败）。购入/借用新设备时补测。

**2026-10-08 已验（R9 预检，Pixel_9 AVD android-37 ps16k 镜像，verdict：通过）**：冷启 `getconf PAGE_SIZE`=**16384**（Android 17 / SDK 37，内核 6.12.58-android16-6，指纹 CP21.260306.017.A1），现 APK（item/r9-ps16k 构建）全链冒烟三步全过：① 部署——主页「新建会话」触发首启部署 91s 完成（rootfs 下载解压配置 + 终端层 apt `LAYER_RC=0`）；② 终端——TerminalActivity 内 ttyd/xterm 渲染出 `root@localhost:~#` 提示符（CDP 读 buffer 断言）；③ 配方——向导真实路径（设置→初始设置→agent 步）安装 OpenCode 1.18.35（npm 走 npmmirror，`RECIPE_RC=0`），proot ptrace 密集的 npm install 未触发楔死。ELF PT_LOAD `p_align` 断言（llvm-readelf）：APK 内 `libproot.so`/`libproot-loader.so` 最小 align=**0x4000(16384)**；rootfs 内 bash/ttyd/dtach（apt Ubuntu arm64）、node（官方 arm64 tarball）、opencode.exe（npm 平台包）全部=**0x10000(65536)**——均 ≥16KB，64KB 对齐在 16KB 内核天然兼容。**结论：16KB 页尺寸对现 proot 链路无阻碍，Q4 关闭**；遗留备注：模拟器验证不等价真机（真机 16KB 设备入手时按需复测），rootfs 内全部二进制依赖上游 arm64 包自身保持大页对齐，若上游改用 4KB-only 编译需重检。证据：`draft/r9-ps16k-verdict.json`（判定与数值）、`draft/r9-elf/`（七个 ELF 原文件）、`draft/r9-a-deploy-terminal.png`、`draft/r9-b-terminal-prompt.png`、`draft/r9-c-wizard-install.png`、驱动脚本 `draft/r9-smoke.py`（复用 scripts/scommon.py）。

## Q5：hook 集成的实际深度

**2026-10-01 真机实测（draft/q5-hooks-result.json）**：PreToolUse/PostToolUse/Stop 全链路触发、tool_input 载荷完整（file_path+content，够批准卡片展示）；PreToolUse `exit 2 + stderr` 决策返回被模型尊重——工具调用拦截、stderr 回馈、模型明确不绕过（拒绝改用 shell 等替代路径）。**批准卡片地基成立**。遗留：Notification hook 在 headless 无权限请求场景未触发，触发时机留驾驶舱交互场景验证；结构化 permissionDecision JSON（比 exit 2 更细的 allow/deny/ask 三态）留产品期。"批准"方向的挂起等待外部决策（hook ↔ 宿主 IPC）为驾驶舱期工程项。Codex notify 与 OpenCode 的事件粒度待调查。L2 只做 Claude Code，此问题不阻塞 Q1/Q2。
**2026-10-03 随 D25 收口**：宿主转向 agent 无关、驾驶舱删除（D25），批准卡片不再有产品承载面。结论定格为「机制实测成立（exit 2 决策被模型尊重），产品不采用」；Notification hook 触发时机、结构化 permissionDecision、hook ↔ 宿主挂起 IPC 等遗留项全部留档关闭，Codex notify / OpenCode 事件粒度的调查随配方化（不做逐家 hook 集成）失去必要。

## Q6：命名与品牌

Drydock 暂定：GitHub 同名仓库 232 个（无本领域冲突）、npm `drydock` 被占（`drydock-app` 空闲）、短域名全被占、应用商店同名 App 均在海事行业。**待办**：若最终定名，需做中国商标网第 9 类近似查询；域名需考虑变体（drydockandroid / drydocklab 等）或随改名解决。

## Q7：环境翻译层选型（proot 分支 × proroot）

上游 proot v5.4.1 与 Termux fork 的取舍已有初步倾向（**Termux fork**：安卓生态事实标准，proot-distro/Andronix/ZeroTermux 全在其上，见 discussion-log §17），实测仅为确认而非开放式二选一。proroot 性能收益已被 DSHA 真机验证（+58~94%），但闭源专有许可需逐条核实（能否随 APK 分发未修改二进制、条款可撤销性），且作者转向 proroom、长期维护存疑。**验证方式**：原型阶段跑 30 分钟基准电池（npm install / tar 解包 / stat 风暴 / make -j，hyperfine 重复取中位数），同机对比 proot（Termux fork）与 proroot，用一手数据决定默认引擎与降级策略。不阻塞 Q1/Q2。
**2026-10-03 收敛（D25 开源硬前提）**：用户定工具链尽量开源为选型硬前提——proroot（闭源专有）出局，不再作为引擎候选。Q7 从「同机对比二选一」收敛为 Termux fork 的确认性实测；基准电池降级为 proot 性能基线记录（调优与文档用），不再承担选型职能。
**2026-10-08 收口**：确认性实测成立——一周真用（含 agent 长任务、锁屏挂机）全程零楔死，2026-10-07 梯度复测（npm 六档全谱系 + hyperfine 原始触发器）零复现；proroot 出局维持（D25）。proot 性能基线数字为可选补测（插线随时可跑），不阻塞收口。**Q7 关闭**。

## Q8：环境救援通道设计

ttyd 住在环境内：rootfs 损坏（依赖冲突、误删、升级失败）时环境内没有终端可自救——这是 proot 方案相对 Termux（外壳永远活着）的结构性缺陷。候选方向：宿主提供绕过 ttyd 的最小救援通道（直接 spawn proot + 最简 PTY，跑 rootfs 内 /bin/sh）；rootfs 完整性校验失败时自动引导修复或重装；快照回滚兜底。与 D7 环境生命周期设计合并考虑。不阻塞 Q1/Q2。
**2026-10-07 首个真实实例（npm 升级 × link2symlink 断链）**：用户在环境内把 opencode-ai 升到 1.18.35，npm 的「删旧建新」链接序列经 proot l2s 翻译后清掉了 185MB 平台真身（`.l2s.opencode0001`），三层符号链接完好但指向不存在——bash 报 command not found。旧进程活在内存里掩盖数小时，宿主 APK 重启后才暴露。**宿主侧救援实测成立**：断链自身携带修复所需的全部信息（链接路径指向的平台包目录 + package.json 仍在），从 npmmirror 拉平台 tarball 解出真身放回原位即修好（无需进环境、无需 npm）。同类风险面：npm 装的带原生二进制的包（esbuild/sharp/swc 形态）升级时都可能触发；pip（wheel 解压复制）与 apt（dpkg unpack+rename）未观察到同类问题。既有配方层的版本校验（command -v + --version）能发现断链但只会触发按钉死版本重装（降级）；待产品化：bin 健康检查（解引用+可执行测试）进环境 ensure/夜间自检，断链自愈脚本化（解析链路→读平台包 package.json→镜像源补真身），白名单外至少报告。

## Q9：HyperOS 通知小图标强制单色化（彩色 mini 图标不可行）

用户真机（Redmi K60E / HyperOS，Android 底 15+）反馈通知图标「与应用图标不同步/显示默认安卓机器人」。排查结论（2026-10-09）：①状态栏左侧的安卓机器人是「已连接到 USB 调试」系统通知的图标，与本项目无关；②本项目环境宿主通知原用 IMPORTANCE_MIN，平台规则 MIN 不显示状态栏小图标，已升 LOW（通道 importance 建后不可改，需删旧重建）；③HyperOS 对通知 smallIcon **强制单色化**：点阵占位底 + 白色 alpha 剪影，彩色内容（vector / 彩色 PNG / 运行时位图 `Icon.createWithBitmap` 三种形态）均被同一管线处理，卡片左列彩色 mini 图标在 smallIcon 通道不可行（微博等应用的彩色头像走会话式通知/Person 等其它机制，属语义误用不采用）。**最终形态**：24dp 加粗白描边锚剪影（ic_stat_anchor），状态栏与卡片稳定成像；状态栏是否显示常驻通知图标受用户侧开关「设置→通知与控制中心→状态栏→显示常驻通知图标」控制（系统设置归用户）。不为 OEM 做专用适配（用户定调）。**2026-10-09 用户验收定论**：LOW 后状态栏仍无我们的图标（常驻图标开关未拨 + OEM 隐藏行为），用户拍板放弃继续追查——白色剪影形态定格，不做 HyperOS 专用功能。

## 转向预案

若 Q1 失败（锁屏链路不可依赖）：本地宿主路线降级为"有网时的玩具"，转向遥控器型产品（控制 Mac 上的 agent），工程量小一个数量级、需求更确定——Happy/Omnara 模式 + 国产端点预设的国内版。
