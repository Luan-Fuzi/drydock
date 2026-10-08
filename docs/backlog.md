# 需求池（工作文档）

> **性质**：临时需求整理与排程文档（2026-10-08 建）。条目核实→排期→落地后，内容并入 roadmap/decisions 或从本文删除；与正式决策冲突的条目必须先更新 decisions.md 再动工。
> **来源**：2026-10-08 用户全应用评审（设置页/向导多轮反馈后的扫荡）+ 评估补充。
> **用法**：每个条目带核实状态、判据（判据即停机点）、触碰面与冲突；开工前先读「并行与冲突」一节。

## 总览

| 编号 | 条目 | 核实 | 量 | 建议批次 |
|---|---|---|---|---|
| R0 | 拆分 HomeActivity（并行使能） | 已核实 | M | 第 1 批（前置） |
| R1 | 应用图标 | 已核实缺失 | S | 第 1 批（独立） |
| R2 | 文件页长按菜单 | 已核实缺失 | S | 第 2 批（与 R3 同 tree） |
| R3 | 轻量文本编辑页 | 成立；需更新 D7 边界 | M | 第 2 批（与 R2 同 tree） |
| R4 | 备份恢复（导入） | 已核实缺失（只有导出） | M | 第 3 批 |
| R5 | 开发者选项四件套 | 已核实（三缺口一待接） | M | 第 3 批 |
| R6 | 镜像源加阿里云 | 已核实（URL 200） | S | 第 2 批（设置页小改） |
| R7 | 设置页文案微调 | 已核实 | S | 第 2 批（设置页小改） |
| R8 | rootfs 升级路径（D26 实现） | 方案在、未实现 | L | 第 4 批（大） |
| R9 | 16KB 页尺寸预检（Q4） | 未验 | S | 任意批次（无代码） |
| R10 | 会话并行重建提速 | 待核实收益 | S-M | 待核实后定 |
| R11 | 长操作统一进度反馈（busy 行 + 模拟进度条） | 已核实（全应用零进度条） | S-M | 第 2 批（tree B 并入） |

## 并行与冲突

**文件热点**：HomeActivity.kt（三个 pane + 全部设置二级页都在这一个文件里）是最大冲突点——R2/R3/R4/R5/R6/R7 都要动它。**R0 先把 pane 拆成独立文件**（SessionPane/FilePane/Settings*.kt/Dev*.kt，纯移动不改逻辑，night-b 全量回归护航），之后各条目各占各的文件，才能真并行。

**批次划分**（同批 = 可在多个 worktree 并行；跨批串行）：
- 第 1 批：R0（串行做，是后面一切的前提）‖ R1、R9（完全独立，随时可并行）。
- 第 2 批：R2+R3（同 tree，同在 FilePane）‖ R6+R7+R11（同 tree，同在设置页；R11 另动 SessionPane 与向导，无文件冲突）‖ R1/R9 收尾。
- 第 3 批：R4（RootfsManager + BackupSettingsPage）‖ R5（DevSettingsPage + 各管理类接线）。R4 与 R8 都动 RootfsManager，R4 先行。
- 第 4 批：R8（大，单独一个 tree）。

**验证资源（重要）**：所有 worktree 共享同一台物理 AVD（emulator-5554）时，UI 驱动类验证（night-b/uitap/截图）**互斥**——两个 tree 同时驱动会互相抢 UI（2026-10-07 实锤过并发冲突）。约定二选一：①验证时段串行（约定信号）；②每个并行 tree 建自己的 AVD 实例（`android emulator create` + 各自 serial，ANDROID_SERIAL 区分）。编译与写码不受限。

**分支纪律**：一切从 dev 拉短命分支（`git worktree add ../drydock-<item> -b item/<n>-<名>`），判据全绿合回 dev 即删；发真机的 APK 只从 main tag 构建（既有纪律不变）。UI 文案类改动必须核对 night-b 锚点（t4「外观」「浅色」、t7「连接大模型」「初始设置」、t10「目录直通绑定」「已关闭/已开启」、t1/t11「新建会话」「本地端口」），动到锚点须同步剧本。

## 条目

### R0 拆分 HomeActivity（并行使能）

- **现状**：HomeActivity.kt ~1000 行，含 HomeScreen/SessionPane/FilePane/SettingsPane 及全部二级页、ThemeStore、debug 验收注入通道。
- **做法**：按 pane 拆文件（纯移动，不改逻辑不趁机动手优化）；ThemeStore 等共享对象进独立文件；debug 注入通道留在 HomeActivity。
- **判据**：night-b 干净全量 t1-t11 all_pass=True；`grep -c "fun " HomeActivity.kt` 显著下降且编译零警告新增。
- **触碰面**：HomeActivity.kt → 新文件若干。**量**：M（半天内）。

### R1 应用图标

- **核实**：Manifest 无 `android:icon`，res/ 无图片资源，启动器显示系统默认机器人图标。
- **做法**：adaptive icon（前景图形 + 背景主题色），图形走名字无关方向（锚/船坞意象；名字 Q6 未定，不绑文字）；`android:icon` + `android:roundIcon` 声明。
- **判据**：启动器/设置里显示新图标，明暗壁纸下可辨；安装升级不掉图标（adaptive 规范）。
- **触碰面**：res/mipmap-*（新增）、AndroidManifest.xml。**量**：S。设计稿可用 image-search/multi-tree 并行，无代码冲突。

### R2 文件页长按菜单

- **核实**：FilePane 行只有单击（进目录/甩系统应用），无长按（无 combinedClickable）。
- **做法**：`combinedClickable` + 长按弹菜单：用其他应用打开（ACTION_VIEW + chooser，满足"调用其他编辑器"）、导出到 Downloads/Drydock（复用 Landing.toDownloads）、重命名、删除（WorkspaceProvider rename/delete 阶段 3 已验，纯接线）。删除加确认。
- **判据**：长按四项各有 night-b 或手动断言：chooser 弹出、产物落 Downloads/Drydock、改名后列表刷新、删除后文件消失。
- **触碰面**：FilePane（R0 后独立文件）。**量**：S。

### R3 轻量文本编辑页

- **核实**：需求成立（改 env.sh/配置/小文本产物的真实场景，env.sh 编辑器已存在是佐证）；与 D7「不做代码编辑器」冲突 → **前置：decisions.md 更新 D7 边界**——做「小文本查看+编辑+保存」，不做语法高亮/多文件/工程级编辑（用户 2026-10-08 定调入池）。
- **做法**：单击文本文件（可按扩展名/大小判定：纯文本且 ≤ 某阈值如 1MB）进编辑页（全屏 TextField，等宽字体，顶部保存/取消）；二进制或超大文件维持甩系统应用；保存前若文件在进入后被 agent 改过（mtime 比对）提示覆盖风险。
- **判据**：小文本可开、改、存（内容落盘、文件页刷新可见大小变化）；二进制文件仍走系统应用；>阈值文件走系统应用。
- **触碰面**：新 Composable/Activity + FilePane 接线（与 R2 同 tree）。**量**：M。

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

### R6 镜像源加阿里云

- **核实**：applyMirrors 的 apt id→URL 映射硬编码 when（tuna/ustc/nju/official）；`http://mirrors.aliyun.com/ubuntu-ports/dists/noble/Release` 实测 200。
- **做法**：映射表加 `"aliyun" -> "http://mirrors.aliyun.com/ubuntu-ports"`；MirrorSettingsPage aptOpts 加一行（id aliyun、label「阿里云」）。位置放 TUNA 之后（国内优先序：清华→中科大→南大→阿里云？或按全局镜像源惯例阿里云靠前——**待用户定序**，默认加在南大后）。
- **判据**：选阿里云应用后环境内 `apt update` 走该源（apt sources URI 断言）。
- **触碰面**：RecipeManager 一行 + MirrorSettingsPage 一行。**量**：S。

### R7 设置页文案微调

- **核实**：「终端」易误解为会话管理；「回滚行数」无解释；直通绑定缺直观解释；备份说明未写产物路径。
- **做法**：「终端」→「终端显示」；回滚行数加「可往上翻看的历史行数上限」；直通绑定加「环境里读写 /root/AndroidDownload ≈ 手机 Download 目录」；备份说明加「产物存 Downloads/Drydock/drydock-env-export.tar.gz」。
- **判据**：night-b 全量绿（t4/t10 锚点「外观」「目录直通绑定」不受影响，核对过）；截图留证。
- **触碰面**：SettingsRoot/TerminalSettingsPage/BindSettingsPage/BackupSettingsPage 文案。**量**：S。

### R8 rootfs 升级路径（D26 落地）

- **核实**：D26 定了「旁路部署 + 原子切换 + 保留上一版回滚」，代码未实现；当前换 rootfs 只能重装（/root 用户文件全丢）。
- **做法**：RootfsManifest 新版本 → 下载解压到旁路目录 → sha256 → 原子重命名切换 → 保留上一版；切换前 /root 用户文件迁移（细则 D26 留白，此处定：旧 rootfs 的 /root 增量拷入新 rootfs，.l2s/npm/cache 排除）；设置页「检查更新」入口。
- **判据**：模拟旧→新升级后工作区文件存活、旧版可一键回滚；sha256 不过拒绝切换。
- **触碰面**：RootfsManager 大改 + RootfsManifest + 设置页入口；与 R4 串行（同文件）。**量**：L。**前置**：与用户对齐 /root 迁移细则后再动工。

### R9 16KB 页尺寸预检（Q4）

- **核实**：open-questions Q4 未验；Pixel_9 AVD（android-37 ps16k）已备。
- **做法**：现 APK + rootfs 装进 Pixel_9 AVD，跑冒烟（部署/终端/配方）+ 读 proot ELF align 断言；结果记回 Q4。
- **判据**：Q4 条目更新为已验/有问题清单。
- **触碰面**：无代码。**量**：S。可与其他任何 tree 并行（独立 AVD，不占 medium_phone）。

### R10 会话并行重建提速

- **核实**：冷启动串行重建每会话 ~10-15s（proot+ttyd 就绪探测自身 20s 上限），多会话线性叠加；D28 只修过「新会话优先」排序，未并行化。**待核实**：并行重建的资源峰值（多 proot 同时 fork 的 CPU/内存尖峰）是否可接受——先测后做。
- **做法**：EnvService.ensureAll 并行 spawn（协程并发 + 上限 2），完成顺序注册。
- **判据**：3 会话冷启动总时长从 ~40s 降到 ≤25s；期间 UI/时间线无异常。
- **触碰面**：EnvService。**量**：S-M。

### R11 长操作统一进度反馈（busy 行 + 模拟进度条）

- **核实**：全应用**零进度条组件**（无任何 Linear/CircularProgressIndicator），长操作反馈全是文字——SessionPane busy 文字行（新建/接回/关闭会话、首启部署、终端层安装各阶段），设置页按钮内文字（「导出中…（约 1 分钟）」「应用中…」「保存中…」），向导步骤③ busy 直接塞进主按钮文字（按钮被撑变形的隐患同镜像页旧问题）。长操作全量清单：首启部署 ~1min、终端层 apt 安装（~10MB 网络）、新建/接回会话 ~10s、关闭会话（≤15s 轮询）、向导 agent 安装（npm install -g，**可达数分钟、全应用最慢**）、导出备份 ~1min、应用镜像（秒~十几秒）、端点/env.sh 保存（秒级）。
- **用户定调（2026-10-08）**：不只新建/关闭会话，全应用长操作都要有反馈；形式 = busy 文字行紧邻一条小进度条（文字行上/下沿，实现按观感定），从左到右滚动的模拟进度动画（indeterminate LinearProgressIndicator，无真实进度概念的操作不装进度数字）；**不弹独立对话框**。
- **做法**：共享小 Composable（如 BusyBar(text)：文字行 + 2-4dp indeterminate LinearProgressIndicator）放 R0 拆出的共享 UI 文件；SessionPane busy 行、BackupSettingsPage 导出、MirrorSettingsPage 应用、端点表单保存、向导步骤③ 各处替换接线；后续新长操作（R4 导入、R5 清理/扫描、R8 升级）统一复用。**可选增强**：首启部署把 createSession 现在丢弃的 DeployState 回调接上——Downloading 自带下载百分比，可显示真实进度（其余操作保持 indeterminate）。
- **判据**：night-b 全量 t1-t11 all_pass=True（busy 行文字照常渲染，t1「新建会话」锚点不动）；手动/截图断言：新建会话、导出备份、应用镜像期间进度条可见且在滚动、操作结束即消失；向导 agent 安装期间主按钮不再被 busy 文字撑变形。
- **触碰面**：SessionPane + Backup/Mirror/端点设置页 + WizardActivity + 新共享组件（R0 后各文件独立）。**量**：S-M。

## 建议排期

1. **第 1 批**：R0（单人串行）开工同时，R1（图标，可外包设计）与 R9（Pixel_9 预检）并行。
2. **第 2 批**：R0 合并后开多 tree——tree A：R2+R3（文件页，含 D7 决策更新）；tree B：R6+R7+R11（设置页小改 + 全应用进度反馈）；R9 收尾。
3. **第 3 批**：tree C：R4（备份恢复）；tree D：R5（开发者选项）。
4. **第 4 批**：R8（rootfs 升级，先对齐迁移细则）；R10 待收益核实后插队。

每条落地时：本文件条目打勾/删除 → 判据与结论入 roadmap 或 decisions → 合 dev。
