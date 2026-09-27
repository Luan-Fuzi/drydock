# AGENTS.md — drydock 项目说明

## 项目现状

纯文档阶段，原型未开工。动手前先读 `docs/open-questions.md` 与 `docs/prototype-plan.md`；已定决策见 `docs/decisions.md`，不要重开已否方案。

## Git 纪律（2026-09-27）

- **分支**：`main` 稳定线，`dev` 日常工作线；一切提交先进 dev，不在 main 上直接开发。
- **合并跟判据走，不跟日历走**：prototype-plan 一个步骤判据全绿（或文档一次定稿）→ `git merge --no-ff` 回 main，merge message 写明对应判据；CI 建立后合并前必须绿。
- **tag 打在 main 的合并提交上**，用 annotated tag，名字跟判据：`av1`、`av2`、`av3`、`probe-ready`（仪器就绪）、`q1-verdict`（真机周结论）；tag message 附证据摘要（verdict JSON 路径或关键数字）。
- **发到真机验证的 APK 只从 main 的 tag 构建**，保证可复现。
- **提交原子**：一次提交一件完整的事；信息用中文一行说清做了什么。
- **不入库**：构建产物（APK）、rootfs、node_modules；证据文件（截图、遥测导出、基准原始 JSON）默认进 `draft/`，结论提炼进 docs/。新增生成物类型时同步补 `.gitignore`。
- 大改动可从 dev 拉短命分支，合回即删。

## 协作约定（2026-09-27）

- 用户默认用**无视觉能力**的模型做开发；验收以命令行为主（adb / CDP / 脚本退出码），协议见 `docs/prototype-plan.md` 的"验收方式"。
- 遇到必须看画面才能判断的验证（xterm.js 渲染观感、IME 组合输入、UI 布局），先把截图用 `adb exec-out screencap` 存到 `draft/` 留证，然后**明确提醒用户切换到有视觉能力的模型**复查；观感审查集中分批做，不频繁来回切换。
- 会话结束前保持 git 工作区干净；临时文件进 `draft/`（已忽略），不删 `docs/` 里的正式文档。
