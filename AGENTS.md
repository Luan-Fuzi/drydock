# AGENTS.md — drydock 项目说明

## 项目现状

原型步骤 1–5 判据全绿并合并 main（tag `step-1`/`av1`/`av2`/`av3`/`probe-ready`）；当前为步骤 6 真机验证周（2026-10-01 起，小米13 / HyperOS 3，采 S1/S2/S3 与 Q1/Q7 结论）。动手前先读 `docs/open-questions.md` 与 `docs/prototype-plan.md`；已定决策见 `docs/decisions.md`，不要重开已否方案。

## Git 纪律（2026-09-27）

- **分支**：`main` 稳定线，`dev` 日常工作线；一切提交先进 dev，不在 main 上直接开发。
- **合并跟判据走，不跟日历走**：prototype-plan 一个步骤判据全绿（或文档一次定稿）→ `git merge --no-ff` 回 main，merge message 写明对应判据；CI 建立后合并前必须绿。
- **tag 打在 main 的合并提交上**，用 annotated tag，名字跟判据：`av1`、`av2`、`av3`、`probe-ready`（仪器就绪）、`q1-verdict`（真机周结论）；tag message 附证据摘要（verdict JSON 路径或关键数字）。
- **发到真机验证的 APK 只从 main 的 tag 构建**，保证可复现。
- **提交原子**：一次提交一件完整的事；信息用中文一行说清做了什么。
- **不入库**：构建产物（APK）、rootfs、node_modules；证据文件（截图、遥测导出、基准原始 JSON）默认进 `draft/`，结论提炼进 docs/。新增生成物类型时同步补 `.gitignore`。
- 大改动可从 dev 拉短命分支，合回即删。

## 真机纪律（2026-10-01，步骤 6 真机周生效）

接入的设备都是用户花钱买的私人设备，**"不搞坏设备"优先级高于任何判据采样**；模拟器"炸了重开"的宽容度在真机上不存在。

- **只读默认**：adb 侧默认只做读操作（shell 查询、logcat、dumpsys、screencap、pull、uiautomator dump）。写操作走白名单：安装/更新我们自己的包（`pm install -r`）、`pm grant` 它的运行时权限、push 临时脚本到 `/data/local/tmp`、经 `run-as` 读写 app 自己的私有目录、app 自己经 MediaStore 落 Downloads/Drydock。
- **绝不**：root / 解 Bootloader / 刷机 / fastboot / recovery；`settings put`、`pm uninstall`、`pm clear`、`wipe`、factory reset；写或删 `/sdcard` 与任何非 drydock 数据；动其他接入设备（哪怕只是"看一眼"的写操作）。
- **单设备瞄准**：多设备在线时所有 adb 命令必须显式 `-s <serial>` 或设 `ANDROID_SERIAL`；发命令前 `adb devices -l` 核对目标。与 AVD 同时在线时尤甚。
- **系统设置归用户**：开发者选项、USB 调试、锁屏方式等只由用户本人在手机上操作；测试确实需要改（如临时改锁屏为无密码）时，说明理由和还原方法，由用户自己动手、自己还原。
- **输入模拟限界**：`input tap/text/keyevent` 只作用于 drydock 页面操作与电源键熄屏/唤醒、HOME 切后台（S1/S2 采样需要），不向其他 app 输入；HOME/熄屏前核对前台与屏幕状态，不确定时先 uiautomator dump 核对。
- **正式采样不插线**：S1/S3 的有效性数据必须在电池供电下采（充电改变 Doze 与温控行为）；插线阶段只做装机、部署与冒烟。

## 协作约定（2026-09-27）

- 用户默认用**无视觉能力**的模型做开发；验收以命令行为主（adb / CDP / 脚本退出码），协议见 `docs/prototype-plan.md` 的"验收方式"。
- 遇到必须看画面才能判断的验证（xterm.js 渲染观感、IME 组合输入、UI 布局），先把截图用 `adb exec-out screencap` 存到 `draft/` 留证，然后**明确提醒用户切换到有视觉能力的模型**复查；观感审查集中分批做，不频繁来回切换。
- 会话结束前保持 git 工作区干净；临时文件进 `draft/`（已忽略），不删 `docs/` 里的正式文档。
