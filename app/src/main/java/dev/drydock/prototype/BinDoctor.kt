package dev.drydock.prototype

import android.content.Context
import android.util.Log
import java.io.File
import java.net.URL

/**
 * bin 健康检查与自愈（Q8，2026-10-07 真机首例：npm 升级 opencode-ai 时
 * 「删旧建新」的链接序列经 proot link2symlink 翻译后清掉了平台真身
 * `.l2s.*`，三层符号链接完好但指向不存在——bash 报 command not found，
 * 且旧进程活在内存里掩盖数小时）。
 *
 * 两阶段，下载在宿主（不依赖环境内 curl，镜像回退与配方安装同款）：
 * 1. scan（环境内）：列断链 → 沿目标路径向上找最近的 package.json 取
 *    包名+版本（断链自带全部修复信息，实测 package.json 必在）；
 * 2. fix（环境内）：宿主按清单从 npmmirror/npmjs 拉 tarball 绑定进环境，
 *    解出 package/bin/ 单文件真身，放到断链期待的路径（cp 改名，如
 *    tarball 内 opencode → .l2s.opencode0001），chmod 755，test -x 断言。
 * 只新增文件不删改既有文件，幂等可重试；修不了的如实报告（FAIL 行）。
 * 触发点在 EnvService 会话 ensure 后异步执行，无断链时秒级空跑；开发者选项
 * 另有手动入口 scanNow（R5）。
 *
 * 附带 git 假成功预防（AgentNet #116 syscall 级实证：proot 的 link() 在
 * untrusted_app 域返回成功但文件从未落盘，git 默认用 link() 写 loose
 * object 会静默丢对象）：幂等写 /etc/gitconfig 的 core.createObject=rename
 * 让 git 改用 proot 翻译正确的 rename()。
 */
object BinDoctor {

    private const val TAG = "DrydockDoctor"
    private val NPM_MIRRORS = listOf(
        "https://registry.npmmirror.com",
        "https://registry.npmjs.org",
    )
    private const val THROTTLE_MS = 5 * 60_000L

    @Volatile
    private var running = false
    @Volatile
    private var lastRunAt = 0L

    /** 环境内脚本，scan/fix 两模式。成员规则：tarball 的 package/bin/ 下
     *  常规文件恰一个才动手（平台二进制包主流形态：opencode/claude/esbuild），
     *  多个或没有都如实报 FAIL 交人工，不猜。 */
    private val SCRIPT = """
        #!/bin/bash
        # Drydock bin doctor（宿主写入，勿手编——内容以 app 为准会被重写）
        set -u
        mode="${'$'}1"
        case "${'$'}mode" in
        scan)
          find /usr/local/lib/node_modules /usr/local/bin -type l 2>/dev/null | while IFS= read -r l; do
            [ -e "${'$'}l" ] && continue
            t=$(readlink -m "${'$'}l" 2>/dev/null)
            [ -z "${'$'}t" ] && { printf 'FAIL\t%s\tno-target\n' "${'$'}l"; continue; }
            d=$(dirname "${'$'}t"); name=""; ver=""
            for i in 1 2 3 4 5 6; do
              if [ -f "${'$'}d/package.json" ]; then
                name=$(sed -n 's/.*"name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "${'$'}d/package.json" | head -1)
                ver=$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "${'$'}d/package.json" | head -1)
                break
              fi
              d=$(dirname "${'$'}d"); [ "${'$'}d" = "/" ] && break
            done
            if [ -n "${'$'}name" ] && [ -n "${'$'}ver" ]; then
              printf 'ITEM\t%s\t%s\t%s\t%s\n' "${'$'}name" "${'$'}ver" "${'$'}t" "${'$'}l"
            else
              printf 'FAIL\t%s\tno-package-json\n' "${'$'}l"
            fi
          done
          echo SCAN_DONE
          ;;
        fix)
          LIST="${'$'}2"
          while IFS=${'$'}'\t' read -r name ver target link tb; do
            [ -z "${'$'}name" ] && continue
            # 断链成串：最深真身补上后，浅层断链被传导修复，跳过（清单已按
            # target 深度降序，避免把中间层符号链接实体化）
            [ -e "${'$'}target" ] && { printf 'SKIP\t%s\n' "${'$'}link"; continue; }
            [ -f "${'$'}tb" ] || { printf 'FAIL\t%s\tno-tarball\n' "${'$'}link"; continue; }
            member=$(tar -tzf "${'$'}tb" 2>/dev/null | grep -E '^package/bin/[^/]+$' | head -1)
            if [ -z "${'$'}member" ]; then
              printf 'FAIL\t%s\tno-bin-member\n' "${'$'}link"; continue
            fi
            tmp=${'$'}(mktemp -d)
            if ! tar -xzf "${'$'}tb" -C "${'$'}tmp" "${'$'}member" 2>/dev/null; then
              printf 'FAIL\t%s\textract\n' "${'$'}link"; rm -rf "${'$'}tmp"; continue
            fi
            mkdir -p "$(dirname "${'$'}target")"
            cp "${'$'}tmp/${'$'}member" "${'$'}target" && chmod 755 "${'$'}target"
            if [ -f "${'$'}target" ] && [ -x "${'$'}target" ]; then
              printf 'FIXED\t%s\t%s@%s\n' "${'$'}link" "${'$'}name" "${'$'}ver"
            else
              printf 'FAIL\t%s\tplace\n' "${'$'}link"
            fi
            rm -rf "${'$'}tmp"
          done < "${'$'}LIST"
          echo FIX_DONE
          ;;
        esac
    """.trimIndent()

    /** 会话 ensure 后异步触发（节流 + 并发去重；无断链时秒级返回）。 */
    fun ensureAsync(context: Context) {
        val now = System.currentTimeMillis()
        if (running || now - lastRunAt < THROTTLE_MS) return
        if (!RootfsManager.isDeployed(context)) return
        running = true
        Thread {
            try {
                runOnce(context)
            } catch (e: Exception) {
                Log.w(TAG, "doctor 运行异常：$e")
            } finally {
                running = false
                lastRunAt = System.currentTimeMillis()
            }
        }.apply { isDaemon = true }.start()
    }

    private data class DoctorItem(val name: String, val version: String, val target: String, val link: String)

    /** 一轮 scan+fix 的结果（R5 手动入口消费；健康 = 两个列表皆空）。 */
    private data class Report(val fixed: List<String>, val failed: List<String>)

    /** 手动触发（开发者选项 R5）：同步跑一轮 scan+fix，报告返回给页面且必落
     *  Timeline（manual=true，健康也记——异步路径健康保持静默不变）。绕节流但
     *  保留本进程 running 去重；与 :env 的异步轮在不同进程，极端并发下 tmp 清单
     *  可能互踩（原型可接受）。调用方放 IO 线程。 */
    fun scanNow(context: Context): String {
        if (!RootfsManager.isDeployed(context)) return "环境未部署"
        if (running) return "已有 doctor 扫描在跑，稍后再试"
        running = true
        return try {
            val r = runOnce(context, manual = true)
            val text = if (r.fixed.isEmpty() && r.failed.isEmpty()) {
                "扫描完成：无断链（/usr/local 链接全部有效，修复 0 项）"
            } else {
                "扫描完成：修复 ${r.fixed.size} 项、未修复 ${r.failed.size} 项" +
                    r.fixed.joinToString("") { "\n✓ $it" } +
                    r.failed.joinToString("") { "\n✗ $it" }
            }
            Timeline.log(
                context, "bin_doctor",
                mapOf(
                    "manual" to true,
                    "fixed" to r.fixed.joinToString(";").take(400),
                    "failed" to r.failed.joinToString(";").take(400),
                ),
            )
            text
        } catch (e: Exception) {
            "扫描异常：$e"
        } finally {
            running = false
            lastRunAt = System.currentTimeMillis()
        }
    }

    /** manual=true 时不在此落 Timeline（由 scanNow 统一记），其余行为与异步路径一致。 */
    private fun runOnce(context: Context, manual: Boolean = false): Report {
        installScript(context)
        ensureGitConfig(context)

        // 1) 扫描（环境内，纯本地秒级）
        val scan = RootfsManager.runInEnv(context, "bash /root/.drydock/bin-doctor.sh scan")
        val failed = mutableListOf<String>()
        failed += scan.output.lineSequence()
            .filter { it.startsWith("FAIL\t") }
            .map { it.removePrefix("FAIL\t").replace('\t', ' ') }
            .toList()
        if (!scan.output.contains("SCAN_DONE")) {
            failed += "scan-stage ${scan.output.takeLast(120)}"
        }
        val items = scan.output.lineSequence()
            .filter { it.startsWith("ITEM\t") }
            .map { it.split('\t') }
            .filter { it.size >= 5 }
            .map { DoctorItem(it[1], it[2], it[3], it[4]) }
            .toList()
        if (items.isEmpty() && failed.isEmpty()) return Report(emptyList(), emptyList()) // 健康，静默

        // 2) 宿主下载 tarball（npmmirror → npmjs 回退；同包去重；断链按
        //    target 深度降序——补好最深真身后浅层断链被传导修复，fix 内 SKIP）
        val pkgDir = File(context.cacheDir, "doctor").apply { mkdirs() }
        val tarballByPkg = HashMap<String, String>() // name@ver → 环境内 tarball 路径
        val rows = StringBuilder()
        for (item in items.sortedByDescending { it.target.length }) {
            val key = "${item.name}@${item.version}"
            val envPath = tarballByPkg[key]
                ?: File(pkgDir, "${item.name.replace('/', '_')}-${item.version}.tgz").let { tgz ->
                    if (download(item, tgz)) {
                        val p = "/tmp/doctorpkgs/${tgz.name}"
                        tarballByPkg[key] = p
                        p
                    } else null
                }
            if (envPath != null) {
                rows.append("${item.name}\t${item.version}\t${item.target}\t${item.link}\t$envPath\n")
            } else {
                failed += "${item.link} download"
            }
        }
        val fixed = mutableListOf<String>()
        val toFix = rows.toString().trimEnd('\n')
        if (toFix.isNotEmpty()) {
            // 3) 环境内解压落位（清单与 tarball 都从宿主带进去）
            File(RootfsManager.rootfsDir(context), "tmp/doctor-fix.tsv").writeText(toFix + "\n")
            val fix = RootfsManager.runInEnv(
                context,
                "bash /root/.drydock/bin-doctor.sh fix /tmp/doctor-fix.tsv",
                extraBinds = listOf("${pkgDir.absolutePath}:/tmp/doctorpkgs"),
            )
            fixed += fix.output.lineSequence()
                .filter { it.startsWith("FIXED\t") }
                .map { it.removePrefix("FIXED\t").replace('\t', ' ') }
            failed += fix.output.lineSequence()
                .filter { it.startsWith("FAIL\t") }
                .map { it.removePrefix("FAIL\t").replace('\t', ' ') }
            File(RootfsManager.rootfsDir(context), "tmp/doctor-fix.tsv").delete()
        }
        pkgDir.deleteRecursively()

        if (!manual) {
            Timeline.log(
                context, "bin_doctor",
                mapOf(
                    "fixed" to fixed.joinToString(";").take(400),
                    "failed" to failed.joinToString(";").take(400),
                ),
            )
        }
        if (fixed.isNotEmpty()) Log.i(TAG, "断链自愈 ${fixed.size} 项：$fixed")
        if (failed.isNotEmpty()) Log.w(TAG, "断链未修复 ${failed.size} 项：$failed")
        return Report(fixed, failed)
    }

    private fun download(item: DoctorItem, dest: File): Boolean {
        val file = "${item.name.substringAfterLast('/')}-${item.version}.tgz"
        for (mirror in NPM_MIRRORS) {
            try {
                RootfsManager.download(URL("$mirror/${item.name}/-/$file"), dest) { }
                if (dest.length() > 0) return true
            } catch (e: Exception) {
                Log.w(TAG, "doctor 下载失败 $mirror ${item.name}: $e")
            }
        }
        dest.delete()
        return false
    }

    /** 脚本随 app 更新重写（内容比对，幂等）。 */
    private fun installScript(context: Context) {
        try {
            val dir = File(RootfsManager.rootfsDir(context), "root/.drydock")
            dir.mkdirs()
            val f = File(dir, "bin-doctor.sh")
            if (!f.exists() || f.readText() != SCRIPT) f.writeText(SCRIPT + "\n")
        } catch (e: Exception) {
            Log.w(TAG, "bin-doctor.sh 写入失败：$e")
        }
    }

    /** git 假成功预防（AgentNet #116 实证的 core.createObject=rename）。
     *  已含该配置则跳过，否则追加段落——不动用户其余 git 配置。 */
    private fun ensureGitConfig(context: Context) {
        try {
            val f = File(RootfsManager.rootfsDir(context), "etc/gitconfig")
            if (f.exists() && f.readText().contains("createObject")) return
            f.parentFile?.mkdirs()
            f.appendText("[core]\n\tcreateObject = rename\n")
            Log.i(TAG, "gitconfig 写入 core.createObject=rename")
        } catch (e: Exception) {
            Log.w(TAG, "gitconfig 写入失败：$e")
        }
    }
}
