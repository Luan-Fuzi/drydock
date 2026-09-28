package dev.drydock.prototype

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import org.json.JSONObject

/**
 * 环境内基准电池（步骤 5，服务 Q7）：hyperfine 固定用例出 JSON 落盘并落袋。
 * 原型验证用轻量数据集（AVD 上一次跑通即判据）；真机周换 engineering-plan
 * 指定的重数据集（create-vite 模板、500MB tar 包）做引擎对比。
 * 用例：npm install / tar 打包 / tar 解包 / stat 风暴 / node 启动 / make -j（tcc 小型 C 项目）。
 */
object Bench {

    private const val TAG = "DrydockBench"

    data class Result(val ok: Boolean, val jsonPath: String?, val landedUri: String?, val log: String)

    fun run(context: Context, onLog: (String) -> Unit): Result {
        // 1. 幂等装工具（universe 已启用；hyperfine/make/tcc 均小包）
        onLog("确保 hyperfine/make/tcc…")
        val ins = RootfsManager.runInEnv(
            context,
            "command -v hyperfine >/dev/null && command -v make >/dev/null && command -v tcc >/dev/null " +
                "&& echo BENCH_ALREADY " +
                "|| (apt-get update -o Acquire::Retries=2 >/dev/null 2>&1; " +
                "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends hyperfine make tcc 2>&1 | tail -2); " +
                "command -v hyperfine make tcc; echo TOOL_RC=\$?",
        )
        if (!ins.output.contains("TOOL_RC=0")) {
            return Result(false, null, null, "工具安装失败：${ins.output.takeLast(300)}")
        }

        // 2. 基准电池：每用例独立跑 hyperfine（noble 的 1.18 不支持多次 --setup，
        //    全局参数一次一个），node 合并 JSON + meta。npm 用例先探 registry，
        //    不通则记 SKIPPED（D12 网络现实：隔离网/代理环境下电池仍要能出 JSON）。
        onLog("运行基准电池（约 2–3 分钟）…")
        val cmd = """
            set -e
            mkdir -p /root/bench && cd /root/bench
            rm -rf npmbench mk x pack.tar pack2.tar bench_*.json result.json
            tar -cf pack.tar /usr/bin 2>/dev/null || true
            # npm 用例预检用单进程 node fetch（无 fork 链——proot ptrace 下
            # fork 链/后台作业会整体停在 ptrace-stop，D18 同族问题，实测
            # timeout/pkill/看门狗子壳全救不了）；不通即 SKIPPED，保住本地
            # 用例出 JSON。npm 在 hyperfine 内的 fork 链楔死风险在半死网络下
            # 依然存在，留待真机周复测（见 verdict notes）。
            if node -e 'fetch("https://registry.npmmirror.com/left-pad",{signal:AbortSignal.timeout(8000)}).then(()=>process.exit(0)).catch(()=>process.exit(1))'; then
              hyperfine --style basic -r 5 -w 1 -n npm-install \
                --setup 'rm -rf /root/bench/npmbench && mkdir -p /root/bench/npmbench && cd /root/bench/npmbench && npm init -y >/dev/null 2>&1' \
                'cd /root/bench/npmbench && npm install --no-audit --no-fund --no-progress left-pad >/dev/null 2>&1' \
                --export-json bench_npm.json >/dev/null 2>&1
            else
              echo "npm-install SKIPPED: registry 不可达"
            fi
            hyperfine --style basic -r 5 -w 1 -n tar-pack \
              --setup 'rm -f /root/bench/pack2.tar' \
              'tar -cf /root/bench/pack2.tar /usr/bin 2>/dev/null' \
              --export-json bench_tarpack.json >/dev/null 2>&1
            hyperfine --style basic -r 5 -w 1 -n tar-extract \
              --setup 'rm -rf /root/bench/x && mkdir -p /root/bench/x' \
              'tar -xf /root/bench/pack.tar -C /root/bench/x' \
              --export-json bench_tarx.json >/dev/null 2>&1
            hyperfine --style basic -r 5 -w 1 -n stat-storm \
              'find /usr -type f 2>/dev/null | head -800 | xargs stat >/dev/null 2>&1' \
              --export-json bench_stat.json >/dev/null 2>&1
            hyperfine --style basic -r 5 -w 1 -n node-startup \
              'node -e 0' \
              --export-json bench_node.json >/dev/null 2>&1
            hyperfine --style basic -r 5 -w 1 -n make-j \
              --setup 'rm -rf /root/bench/mk && mkdir -p /root/bench/mk && cd /root/bench/mk && for i in ${'$'}(seq 1 40); do printf "int main(){return 0;}\n" > c${'$'}i.c; done && { echo "all:"; for i in ${'$'}(seq 1 40); do printf " all"; done; echo; for i in ${'$'}(seq 1 40); do printf "f${'$'}i: c${'$'}i.c\n\ttcc -o f${'$'}i c${'$'}i.c\n"; done; } > Makefile' \
              'cd /root/bench/mk && make -j${'$'}(nproc) >/dev/null 2>&1 && rm -f f*' \
              --export-json bench_make.json >/dev/null 2>&1
            node -e '
              const fs = require("fs");
              const wanted = ["npm", "tarpack", "tarx", "stat", "node", "make"];
              const found = wanted.filter(n => fs.existsSync("/root/bench/bench_" + n + ".json"));
              const results = found.map(n =>
                JSON.parse(fs.readFileSync("/root/bench/bench_" + n + ".json", "utf8")).results[0]);
              const meta = { ts: new Date().toISOString(), engine: "proot-termux-fork",
                device: process.env.BENCH_DEVICE, skipped: wanted.filter(n => !found.includes(n)) };
              fs.writeFileSync("/root/bench/result.json", JSON.stringify({ meta, results }, null, 1));
            '
            ls -la /root/bench/result.json && echo BENCH_OK
        """.trimIndent()
        val r = RootfsManager.runInEnv(context, cmd, extraEnv = mapOf("BENCH_DEVICE" to Build.MODEL))
        Log.i(TAG, "bench exit=${r.exitCode}\n${r.output.takeLast(1500)}")
        if (!r.output.contains("BENCH_OK")) {
            return Result(false, null, null, "hyperfine 失败：${r.output.takeLast(400)}")
        }

        // 3. 落袋 Downloads/Drydock
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(java.util.Date())
        val host = File(RootfsManager.rootfsDir(context), "root/bench/result.json")
        val export = File(context.cacheDir, "bench-$stamp.json")
        export.writeText(host.readText())
        return try {
            val uri = Landing.toDownloads(context, export).toString()
            onLog("✓ 结果已落 Downloads/Drydock")
            Result(true, "/root/bench/result.json", uri, r.output.takeLast(400))
        } catch (e: Exception) {
            Result(true, "/root/bench/result.json", "落袋失败: $e", r.output.takeLast(400))
        }
    }

    /** verdict 用：各用例中位数摘要（ms）。 */
    fun mediansSummary(context: Context): JSONObject? = try {
        val f = File(RootfsManager.rootfsDir(context), "root/bench/result.json")
        if (!f.exists()) null else JSONObject(f.readText())
    } catch (_: Exception) {
        null
    }
}
