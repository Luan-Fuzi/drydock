# AGENTS.md — drydock 项目说明

## 项目现状

纯文档阶段，原型未开工。动手前先读 `docs/open-questions.md` 与 `docs/prototype-plan.md`；已定决策见 `docs/decisions.md`，不要重开已否方案。

## 协作约定（2026-09-27）

- 用户默认用**无视觉能力**的模型做开发；验收以命令行为主（adb / CDP / 脚本退出码），协议见 `docs/prototype-plan.md` 的"验收方式"。
- 遇到必须看画面才能判断的验证（xterm.js 渲染观感、IME 组合输入、UI 布局），先把截图用 `adb exec-out screencap` 存到 `draft/` 留证，然后**明确提醒用户切换到有视觉能力的模型**复查；观感审查集中分批做，不频繁来回切换。
- 会话结束前保持 git 工作区干净；临时文件进 `draft/`（已忽略），不删 `docs/` 里的正式文档。
