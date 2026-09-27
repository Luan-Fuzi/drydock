# 待验证问题

动手前先看这里。按优先级排序，前两项决定项目存在性。

## Q1（核心存在性验证）：锁屏挂机链路是否"敢依赖"？

agent 任务一跑几十分钟，手机会锁屏、降频、被杀后台、发热。前台服务 + tmux + WakeLock 只能缓解不能根治——这条链路的真实体验决定产品成立与否。

**验证方式**：2–3 周丢弃型原型，不求产品化——proot + ttyd + xterm.js + 预装 Claude Code（npm 下载式）+ GLM 端点，装在自己的安卓手机上真用一周。观察：锁屏 30 分钟后任务是否还活着、被杀后 tmux 能否接回、发热程度。**结论（正/负）直接决定项目继续或转向，比任何分析都值钱。**

## Q2（需求验证）：目标用户真的会用吗？

"想要 TUI 但装不来 Termux"和"想要 agent 但不想碰终端"两个交集人群的盘子大小是假设不是事实。

**验证方式**：原型装给 2–3 个不懂终端的朋友，什么都不教，一周后看使用痕迹。判据：留下来的人是否在干"云端不方便干的事"（本地文件、隐私、随开随用）。没人第二次打开，答案也清楚了。

## Q3：OEM ROM 保活矩阵

小米 HyperOS / OPPO ColorOS / vivo OriginOS / 荣耀 MagicOS 的杀后台行为与白名单设置路径各不相同，需要实测建表。原型阶段先覆盖自己手上的机型。

## Q4：新安卓特性对环境层的影响

16KB page size 等新特性对 proot 与 rootfs 内 glibc 二进制的兼容性影响未验证（部分新设备内核页尺寸变化可能导致未按其编译的 ELF 失败）。购入/借用新设备时补测。

## Q5：hook 集成的实际深度

Claude Code hooks 能否完整覆盖"批准卡片"所需的全部事件（PreToolUse 返回决策、Notification 触达时机）需要原型实测；Codex notify 与 OpenCode 的事件粒度待调查。L2 只做 Claude Code，此问题不阻塞 Q1/Q2。

## Q6：命名与品牌

Drydock 暂定：GitHub 同名仓库 232 个（无本领域冲突）、npm `drydock` 被占（`drydock-app` 空闲）、短域名全被占、应用商店同名 App 均在海事行业。**待办**：若最终定名，需做中国商标网第 9 类近似查询；域名需考虑变体（drydockandroid / drydocklab 等）或随改名解决。

## Q7：环境翻译层选型（proot 分支 × proroot）

上游 proot v5.4.1 与 Termux fork 的取舍已有初步倾向（**Termux fork**：安卓生态事实标准，proot-distro/Andronix/ZeroTermux 全在其上，见 discussion-log §17），实测仅为确认而非开放式二选一。proroot 性能收益已被 DSHA 真机验证（+58~94%），但闭源专有许可需逐条核实（能否随 APK 分发未修改二进制、条款可撤销性），且作者转向 proroom、长期维护存疑。**验证方式**：原型阶段跑 30 分钟基准电池（npm install / tar 解包 / stat 风暴 / make -j，hyperfine 重复取中位数），同机对比 proot（Termux fork）与 proroot，用一手数据决定默认引擎与降级策略。不阻塞 Q1/Q2。

## Q8：环境救援通道设计

ttyd 住在环境内：rootfs 损坏（依赖冲突、误删、升级失败）时环境内没有终端可自救——这是 proot 方案相对 Termux（外壳永远活着）的结构性缺陷。候选方向：宿主提供绕过 ttyd 的最小救援通道（直接 spawn proot + 最简 PTY，跑 rootfs 内 /bin/sh）；rootfs 完整性校验失败时自动引导修复或重装；快照回滚兜底。与 D7 环境生命周期设计合并考虑。不阻塞 Q1/Q2。

## 转向预案

若 Q1 失败（锁屏链路不可依赖）：本地宿主路线降级为"有网时的玩具"，转向遥控器型产品（控制 Mac 上的 agent），工程量小一个数量级、需求更确定——Happy/Omnara 模式 + 国产端点预设的国内版。
