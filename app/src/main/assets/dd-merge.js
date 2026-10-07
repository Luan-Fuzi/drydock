// D30 端点列表合并（数据由宿主经 argv 注入，文件本身不随端点变化）：
//   node dd-merge.js '<ocProviders JSON: {name: segment}>' '<piProviders JSON: [{providerId, ...segment}]>'
// 只替换列表内 provider 名的段；agent/用户手写的其他段原样保留。
const fs = require('fs');
const path = require('path');

const [ocArg, piArg] = process.argv.slice(2);
const ocProviders = JSON.parse(ocArg || '{}');
const piProviders = JSON.parse(piArg || '[]');

function mergeJson(file, fn) {
  let doc = {};
  if (fs.existsSync(file)) {
    try { doc = JSON.parse(fs.readFileSync(file, 'utf8')); } catch (e) { doc = {}; }
  }
  fn(doc);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(doc, null, 2));
}

if (Object.keys(ocProviders).length) {
  mergeJson('/root/.config/opencode/opencode.json', doc => {
    const prov = doc.provider || {};
    for (const [k, v] of Object.entries(ocProviders)) prov[k] = v;
    doc.provider = prov;
    // opencode 默认 autoupdate=true：TUI 启动即静默 npm 自升级——版本失控且每次
    // 升级都可能踩 proot l2s 断链（2026-10-07 两次实证）。默认关；用户显式写
    // true/false 后不再动（尊重手动选择）。
    if (doc.autoupdate === undefined) doc.autoupdate = false;
  });
  console.log('OPENCODE_CFG_MERGED');
}
if (piProviders.length) {
  mergeJson('/root/.pi/agent/models.json', doc => {
    const provs = doc.providers || {};
    for (const p of piProviders) {
      const { providerId, ...rest } = p;
      provs[providerId] = rest;
    }
    doc.providers = provs;
  });
  console.log('PI_CFG_MERGED');
}
