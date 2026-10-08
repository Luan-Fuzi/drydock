# 需求池（工作文档）

> **性质**：临时需求整理与排程文档（2026-10-08 建）。条目核实→排期→落地后，内容并入 roadmap/decisions 或从本文删除；与正式决策冲突的条目必须先更新 decisions.md 再动工。
> **来源**：2026-10-08 用户全应用评审（设置页/向导多轮反馈后的扫荡）+ 评估补充。
> **已收口**：R0（f555e50）、R9（7585a42，Q4 关闭）、第 2 批全部六条——R2+R3+R12（34af402，文件页菜单/编辑页/half-installed 自愈，D7 边界修订）、R6+R7+R11（4fab36e，阿里云/文案/BusyBar 进度反馈）；各自判据全绿 + dev 组合态全量 t1-t12 回归。R1 图标定稿 A 锚并入 dev（15be17e，2026-10-08 用户选定稿；判据 AVD 覆盖安装像素 A/B + 暗色 + badging 全过，证据 draft/r1-*.png）。
> **用法**：每个条目带核实状态、判据（判据即停机点）、触碰面与冲突；开工前先读「并行与冲突」一节。

## 总览

| 编号 | 条目 | 核实 | 量 | 建议批次 |
|---|---|---|---|---|
| R4 | 备份恢复（导入） | 已核实缺失（只有导出） | M | 第 3 批 |
| R5 | 开发者选项四件套 | 已核实（三缺口一待接） | M | 第 3 批 |
| R8 | rootfs 升级路径（D26 实现） | 方案在、未实现 | L | 第 4 批（大） |
| R10 | 会话并行重建提速 | 待核实收益 | S-M | 待核实后定 |

## 并行与冲突

**文件热点**：HomeActivity.kt（三个 pane + 全部设置二级页都在这一个文件里）是最大冲突点——R2/R3/R4/R5/R6/R7 都要动它。**R0 先把 pane 拆成独立文件**（SessionPane/FilePane/Settings*.kt/Dev*.kt，纯移动不改逻辑，night-b 全量回归护航），之后各条目各占各的文件，才能真并行。

**批次划分**（同批 = 可在多个 worktree 并行；跨批串行）：
- ~~第 1 批~~（已收口：R0 合并 f555e50、R9 合并 7585a42；R1 图标定稿 A 锚并入 15be17e）。
- ~~第 2 批~~（已收口：tree A=R2+R3+R12 合并 34af402，tree B=R6+R7+R11 合并 4fab36e）。
- 第 3 批：R4（RootfsManager + BackupSettingsPage）‖ R5（DevSettingsPage + 各管理类接线）。R4 与 R8 都动 RootfsManager，R4 先行。
- 第 4 批：R8（大，单独一个 tree）。

**验证资源（重要）**：所有 worktree 共享同一台物理 AVD（emulator-5554）时，UI 驱动类验证（night-b/uitap/截图）**互斥**——两个 tree 同时驱动会互相抢 UI（2026-10-07 实锤过并发冲突）。约定二选一：①验证时段串行（约定信号）；②每个并行 tree 建自己的 AVD 实例（`android emulator create` + 各自 serial，ANDROID_SERIAL 区分）。编译与写码不受限。

**分支纪律**：一切从 dev 拉短命分支（`git worktree add .worktrees/<item> -b item/<n>-<名>`，2026-10-08 起约定建在仓库内部，见 AGENTS.md；`.worktrees/` 已忽略），判据全绿合回 dev 即删；发真机的 APK 只从 main tag 构建（既有纪律不变）。UI 文案类改动必须核对 night-b 锚点（t4「外观」「浅色」、t7「连接大模型」「初始设置」、t10「目录直通绑定」「已关闭/已开启」、t1/t11「新建会话」「本地端口」），动到锚点须同步剧本。

## 条目

### R4 备份恢复（导入）

- **核实**：只有导出（exportEnvTar → Downloads/Drydock/drydock-env-export.tar.gz），无任何导入 UI；换机/重装后恢复只能 adb。
- **做法**：备份页加「从 tar.gz 恢复」：SAF 选文件 → 校验（tar 结构、路径白名单只收 ./root 与 /etc 片段、拒绝绝对路径与 ..）→ 恢复策略（默认：合并进现有 /root，同名文件询问覆盖/跳过；rootfs 未部署时先部署再灌）→ 结果报告（导入条数/跳过/失败）。危险操作双重确认。密钥随 env.sh 进入环境——确认框明示。
- **判据**：导出→改环境→恢复→文件回到导出时点；篡改过的 tar（含 ../ 路径）被拒绝；未部署环境走恢复自动部署后成功。
- **触碰面**：RootfsManager（新增 importEnvTar）、BackupSettingsPage。**注意**：与 R8 同动 RootfsManager，排 R8 前；tar 读取注意 D21 的 .l2s 排除口径（恢复的 tar 不含 .l2s，无需处理自指环）。**量**：M。

### R5 开发者选项四件套

- **核实**：①缓存清理函数在 RootfsManager（实测 438MB→121MB）但**无 UI 入口**；②BinDoctor 只有 EnvService 会话建立后一个异步触发点（EnvService.kt:104），无手动触发；③会话诊断（holder/ttyd pid+端口+存活、WakeLock）无页内视图，排障靠 adb；④时间线只可导出不可页内看。
- **做法**：DevSettingsPage 加四个入口：立即清理 apt 缓存（显示前后 KB）、BinDoctor 立即扫描（结果进页面与 Timeline）、会话诊断列表（读 TerminalManager 注册表 + 进程探活 + WakeLock 状态）、时间线最近 100 条。
- **判据**：四个按钮各有可断言结果（缓存前后 KB 数字、扫描报告、诊断列表与会话注册表一致、时间线行数）。
- **触碰面**：DevSettingsPage + BinDoctor/RootfsManager/Timeline 只读接线。**量**：M。

### R8 rootfs 升级路径（D26 落地）

- **核实**：D26 定了「旁路部署 + 原子切换 + 保留上一版回滚」，代码未实现；当前换 rootfs 只能重装（/root 用户文件全丢）。
- **做法**：RootfsManifest 新版本 → 下载解压到旁路目录 → sha256 → 原子重命名切换 → 保留上一版；切换前 /root 用户文件迁移（细则 D26 留白，此处定：旧 rootfs 的 /root 增量拷入新 rootfs，.l2s/npm/cache 排除）；设置页「检查更新」入口。
- **判据**：模拟旧→新升级后工作区文件存活、旧版可一键回滚；sha256 不过拒绝切换。
- **触碰面**：RootfsManager 大改 + RootfsManifest + 设置页入口；与 R4 串行（同文件）。**量**：L。**前置**：与用户对齐 /root 迁移细则后再动工。

### R10 会话并行重建提速

- **核实**：冷启动串行重建每会话 ~10-15s（proot+ttyd 就绪探测自身 20s 上限），多会话线性叠加；D28 只修过「新会话优先」排序，未并行化。**待核实**：并行重建的资源峰值（多 proot 同时 fork 的 CPU/内存尖峰）是否可接受——先测后做。
- **做法**：EnvService.ensureAll 并行 spawn（协程并发 + 上限 2），完成顺序注册。
- **判据**：3 会话冷启动总时长从 ~40s 降到 ≤25s；期间 UI/时间线无异常。
- **触碰面**：EnvService。**量**：S-M。

## 建议排期

1. ~~第 1 批~~ 已收口（R0 拆分 + R9 预检均合 dev；R1 图标定稿 A 锚已并入）。
2. ~~第 2 批~~ 已收口（两 tree 各自判据全绿合并；D7 边界修订入 decisions.md，night-b 增 t12、t2 探针改二进制）。
3. **第 3 批**：tree C：R4（备份恢复）；tree D：R5（开发者选项）。
4. **第 4 批**：R8（rootfs 升级，先对齐迁移细则）；R10 待收益核实后插队。

每条落地时：本文件条目打勾/删除 → 判据与结论入 roadmap 或 decisions → 合 dev。
