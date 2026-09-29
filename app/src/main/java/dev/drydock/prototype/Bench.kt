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

    /** includeNpm=false 供验收通道跳过 npm 用例（AVD 上 hyperfine×npm 的 proot
     *  ptrace 楔死为概率性事件、与网络无关——网络正常时约半数复现；真机周复测
     *  后恢复默认。UI 路径恒为 true。） */
    fun run(context: Context, onLog: (String) -> Unit, includeNpm: Boolean = true): Result {
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

        // 2. 基准电池：bash 内建计时（$EPOCHREALTIME，零 fork）× 每用例 5 轮取中位。
        //    放弃 hyperfine：AVD×proot 上其进程管理不可信——app 语境下对 --setup/-w
        //    组合必现无声退码 2（同命令手动全过、裸 tar 直跑 RC=0），多子进程命令
        //    还会整体楔死在 ptrace-stop（D18 族）；真机周可再评估恢复。npm 用例
        //    先探 registry，不通记 SKIPPED（D12）。
        onLog("运行基准电池（约 2–3 分钟）…")
        val cmd = """
            set -e
            mkdir -p /root/bench && cd /root/bench
            rm -rf npmbench mk x dataset pack.tar times.txt result.json
            : > times.txt
            # 受控数据集：cp 打散硬链接（app 目录禁 link()，D17），规避 /usr/bin 的
            # 绝对前缀警告与 .l2s 残留；tar 只打自己的数据集
            cp -r /usr/bin dataset 2>/dev/null || true
            rm -f dataset/.l2s.*
            tar -cf pack.tar -C /root/bench dataset

            bench() { # name prepare cmd —— prepare/cmd 为单串，计时只包 cmd
              local name=${'$'}1 prep=${'$'}2 run=${'$'}3 i t0 t1
              for i in 1 2 3 4 5; do
                [ -n "${'$'}prep" ] && eval "${'$'}prep" >/dev/null 2>&1
                t0=${'$'}EPOCHREALTIME
                eval "${'$'}run" >/dev/null 2>&1 || true
                t1=${'$'}EPOCHREALTIME
                awk -v a="${'$'}t0" -v b="${'$'}t1" -v n="${'$'}name" 'BEGIN{printf "%s %.1f\n", n, (b-a)*1000}' >> /root/bench/times.txt
              done
              echo "BENCH:${'$'}name done"
            }

            if [ "${'$'}DD_BENCH_NPM" = "1" ] && node -e 'fetch("https://registry.npmmirror.com/left-pad",{signal:AbortSignal.timeout(8000)}).then(()=>process.exit(0)).catch(()=>process.exit(1))'; then
              bench npm-install \
                'rm -rf /root/bench/npmbench && mkdir -p /root/bench/npmbench && cd /root/bench/npmbench && npm init -y >/dev/null 2>&1' \
                'cd /root/bench/npmbench && npm install --no-audit --no-fund --no-progress left-pad'
            else
              echo "npm-install SKIPPED（npm=${'$'}DD_BENCH_NPM 或 registry 不可达）"
            fi
            bench tar-pack 'rm -f /root/bench/pack2.tar' 'tar -cf /root/bench/pack2.tar -C /root/bench dataset'
            bench tar-extract 'rm -rf /root/bench/x && mkdir -p /root/bench/x' 'tar -xf /root/bench/pack.tar -C /root/bench/x'
            bench stat-storm '' 'stat /usr/bin/*'
            bench node-startup '' 'node -e 0'
            bench make-j \
              'rm -rf /root/bench/mk && mkdir -p /root/bench/mk && cd /root/bench/mk && for i in ${'$'}(seq 1 40); do printf "int main(){return 0;}\n" > c${'$'}i.c; done && { echo "all:"; for i in ${'$'}(seq 1 40); do printf " all"; done; echo; for i in ${'$'}(seq 1 40); do printf "f${'$'}i: c${'$'}i.c\n\ttcc -o f${'$'}i c${'$'}i.c\n"; done; } > Makefile' \
              'cd /root/bench/mk && make -j${'$'}(nproc) && rm -f f*'
            node -e '
              const fs = require("fs");
              const runs = {};
              for (const line of fs.readFileSync("/root/bench/times.txt", "utf8").split("\n")) {
                if (!line.trim()) continue;
                const [name, ms] = line.split(" ");
                (runs[name] = runs[name] || []).push(parseFloat(ms));
              }
              const median = a => { const s = [...a].sort((x, y) => x - y); return s[Math.floor(s.length / 2)]; };
              const results = Object.entries(runs).map(([name, a]) =>
                ({ command: name, runs: a.length, median_ms: median(a), min_ms: Math.min(...a), max_ms: Math.max(...a) }));
              const meta = { ts: new Date().toISOString(), engine: "proot-termux-fork",
                device: process.env.BENCH_DEVICE, timer: "bash-EPOCHREALTIME",
                skipped: ["npm-install"].filter(n => !runs[n]) };
              fs.writeFileSync("/root/bench/result.json", JSON.stringify({ meta, results }, null, 1));
            '
            ls -la /root/bench/result.json && echo BENCH_OK
        """.trimIndent()
        // 脚本以文件形式绑定进环境执行（bash /battery.sh）而非 bash -c 内联：
        // 实测内联形式在 hyperfine 执行段无声退码 2，文件形式稳定（机理未深究），
        // 且免 argv 长度上限
        val script = File(context.cacheDir, "bench-battery.sh")
        script.writeText(cmd)
        val r = RootfsManager.runInEnv(
            context,
            "bash /battery.sh",
            extraEnv = mapOf(
                "BENCH_DEVICE" to Build.MODEL,
                "DD_BENCH_NPM" to if (includeNpm) "1" else "0",
            ),
            extraBinds = listOf("${script.absolutePath}:/battery.sh"),
        )
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
