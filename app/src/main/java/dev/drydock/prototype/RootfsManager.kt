package dev.drydock.prototype

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * rootfs 生命周期（步骤 2 / AV1）：
 * 下载（多镜像回退）→ sha256 校验（I5，失败即拒绝并清理）→ tar 解压 → 换源/DNS 配置。
 * 解压用 /system/bin/tar（toybox），app 身份 + --no-same-owner。
 */
object RootfsManager {

    private const val TAG = "DrydockRootfs"

    sealed class DeployState {
        data object Idle : DeployState()
        data class Downloading(val mirror: String, val percent: Int) : DeployState()
        data object Verifying : DeployState()
        data class Extracting(val output: String) : DeployState()
        data object Configuring : DeployState()
        data object Ready : DeployState()
        data class Failed(val reason: String) : DeployState()
    }

    data class ExecResult(val exitCode: Int, val output: String)

    fun rootfsDir(context: Context): File = File(context.filesDir, "ubuntu-rootfs")

    /** 用户环境变量文件（D27 引入 / D29 起也是 key 的住址）：登录 shell 经 profile.d
     *  source，runInEnv 统一 source；设置页编辑器直接读写（app 与 rootfs 同 uid）。 */
    fun envShFile(context: Context): File {
        val f = File(rootfsDir(context), "root/.drydock/env.sh")
        if (!f.exists()) {
            f.parentFile?.mkdirs()
            f.writeText(
                "# 用户自定义环境变量，每个新会话生效；例如：\n" +
                    "# export HTTP_PROXY=http://127.0.0.1:7890\n" +
                    "# API key（opencode/pi 的配置已引用 DRYDOCK_API_KEY）：\n" +
                    "# export DRYDOCK_API_KEY=sk-xxxx\n",
            )
        }
        return f
    }

    /**
     * link2symlink 自绑定（步骤 4 实测教训）：--link2symlink 把 link() 落成 .l2s 符号
     * 链接，目标是宿主绝对路径（realpath 规范化后的 /data/data 拼写）。不自绑定进
     * 环境的话，链接只在创建它的那个 proot 会话内有效，换会话即断（claude.exe exec
     * ENOENT 实证；D17 pass2 补的 perl/gunzip/dpkg-status 同样中招）。Termux
     * proot-distro 的同款手法：把 rootfs 宿主路径原样绑定进环境内。
     */
    internal fun l2sSelfBind(context: Context): String {
        val canon = rootfsDir(context).canonicalFile.absolutePath
        return "$canon:$canon"
    }

    fun isDeployed(context: Context): Boolean =
        File(rootfsDir(context), "bin/bash").exists() &&
            File(rootfsDir(context), ".drydock-manifest").exists()

    /** 部署全程（阻塞，调用方放 IO 线程）。成功后 tar 包清理。 */
    fun deploy(context: Context, onState: (DeployState) -> Unit) {
        try {
            deployInner(context, onState)
        } catch (e: Throwable) {
            Log.e(TAG, "deploy 异常", e)
            onState(DeployState.Failed("deploy 异常：$e"))
        }
    }

    private fun deployInner(context: Context, onState: (DeployState) -> Unit) {
        val tarball = File(context.cacheDir, RootfsManifest.FILE_NAME)

        // 1. 下载（镜像按序回退，已有完整文件则跳过）
        if (tarball.length() != RootfsManifest.SIZE_BYTES) {
            tarball.delete()
            var ok = false
            var lastErr = ""
            for (mirror in RootfsManifest.MIRRORS) {
                try {
                    onState(DeployState.Downloading(mirror, 0))
                    download(URL(mirror + RootfsManifest.FILE_NAME), tarball) { p ->
                        onState(DeployState.Downloading(mirror, p))
                    }
                    ok = tarball.length() == RootfsManifest.SIZE_BYTES
                    if (ok) break
                    lastErr = "size mismatch on $mirror"
                } catch (e: Exception) {
                    lastErr = "$mirror: $e"
                    Log.w(TAG, "下载失败，换下一镜像：$lastErr")
                }
            }
            if (!ok) {
                onState(DeployState.Failed("下载失败：$lastErr"))
                return
            }
        }

        // 2. sha256 校验（I5）
        onState(DeployState.Verifying)
        val actual = sha256(tarball)
        if (actual != RootfsManifest.SHA256) {
            tarball.delete()
            onState(DeployState.Failed("sha256 不符：$actual（期望 ${RootfsManifest.SHA256}），已删除拒绝使用"))
            return
        }
        Log.i(TAG, "sha256 校验通过")

        // 3. 解压（两段式）
        // targetSdk 29+ 的 SELinux 禁止 app 在数据目录 link()——硬链接条目必须走
        // proot link2symlink 模拟。先用 toybox tar 铺开拿到底盘（容忍 link 失败），
        // 再用 rootfs 内自带的 GNU tar 在 proot -0 -L 里补齐硬链接成员。
        val rootfs = rootfsDir(context)
        rootfs.deleteRecursively()
        rootfs.mkdirs()

        val tar = ProcessBuilder(
            "/system/bin/tar", "-xzf", tarball.absolutePath,
            "-C", rootfs.absolutePath, "--no-same-owner",
        ).redirectErrorStream(true).start()
        val tarOut = tar.inputStream.bufferedReader().readText()
        val tarExit = tar.waitFor()
        val linkMisses = Regex("can't link '([^']+)' -> '([^']+)'").findAll(tarOut)
            .map { it.groupValues[1] }.toList()
        val otherErrors = tarOut.lineSequence()
            .filter { it.startsWith("tar:") && !it.contains("can't link") && it != "tar: had errors" }
            .toList()
        if (tarExit != 0 && (otherErrors.isNotEmpty() || !File(rootfs, "usr/bin/tar").exists())) {
            onState(DeployState.Failed("tar 退出码 $tarExit：${tarOut.takeLast(2000)}"))
            return
        }
        Log.i(TAG, "pass1 完成，link 缺失 ${linkMisses.size} 项：$linkMisses")

        if (linkMisses.isNotEmpty()) {
            val nativeDir = File(context.applicationInfo.nativeLibraryDir)
            val proot = File(nativeDir, "libproot.so")
            // 成员名用 toybox 报错里的原样路径（无 ./ 前缀；该 tarball 不存 ./ 形式）
            val memberArgs = linkMisses
            val argv = listOf(
                proot.absolutePath,
                "-0", "--link2symlink",
                "-r", rootfs.absolutePath,
                "-b", "/dev", "-b", "/proc",
                "-b", "${tarball.absolutePath}:/rootfs.tar.gz",
                "-b", l2sSelfBind(context),
                "-w", "/",
                "/usr/bin/tar", "-xzf", "/rootfs.tar.gz", "-C", "/", "--no-same-owner",
            ) + memberArgs
            val p2 = ProcessBuilder(argv).redirectErrorStream(true).apply {
                environment().apply {
                    put("PROOT_LOADER", File(nativeDir, "libproot-loader.so").absolutePath)
                    put("PROOT_TMP_DIR", context.cacheDir.absolutePath)
                    put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
                    put("HOME", "/root")
                }
            }.start()
            Log.i(TAG, "pass2 已启动，等待输出…")
            val p2Out = p2.inputStream.bufferedReader().readText()
            Log.i(TAG, "pass2 输出读完 ${p2Out.length} 字节")
            val p2Exit = p2.waitFor()
            Log.i(TAG, "pass2 exit=$p2Exit")
            if (p2Exit != 0) {
                onState(DeployState.Failed("proot 内补链退出码 $p2Exit：${p2Out.takeLast(2000)}"))
                return
            }
            Log.i(TAG, "pass2 补链完成")
        }
        onState(DeployState.Extracting(tarOut))

        // 4. 配置：DNS + apt 国内源
        onState(DeployState.Configuring)
        configure(rootfs)

        // 5. 清理 tar 包，写 manifest
        tarball.delete()
        rootfs.resolve(".drydock-manifest").writeText(
            "version=${RootfsManifest.UBUNTU_VERSION}\n" +
                "sha256=${RootfsManifest.SHA256}\n" +
                "deployedAt=${System.currentTimeMillis()}\n",
        )
        onState(DeployState.Ready)
        Log.i(TAG, "rootfs 就绪：${RootfsManifest.UBUNTU_VERSION}")
    }

    /** 维护：清理包管理器占用（阶段 4 存储优化，实测口径见 draft/phase4-storage-verdict.json）。
     *  大头不是 deb 归档而是 apt 索引与二进制缓存（合计约 317MB）：lists 清掉后
     *  下次 apt 操作需 update 重拉——终端层装完后常态不再用 apt，属划算交换。 */
    fun cleanCaches(context: Context): ExecResult {
        val cmd = """
            BEFORE=${'$'}(du -sk /var/lib/apt/lists /var/cache/apt 2>/dev/null | awk '{s+=${'$'}1} END{print s+0}')
            apt-get clean 2>/dev/null
            rm -f /var/cache/apt/*.bin
            rm -rf /var/lib/apt/lists/*
            npm cache clean --force >/dev/null 2>&1
            AFTER=${'$'}(du -sk /var/lib/apt/lists /var/cache/apt 2>/dev/null | awk '{s+=${'$'}1} END{print s+0}')
            echo "APT_KB_BEFORE=${'$'}BEFORE APT_KB_AFTER=${'$'}AFTER"
            echo CLEAN_RC=0
        """.trimIndent()
        return runInEnv(context, cmd)
    }

    /** 环境导出（D7 规划 / D27 引用）：导出**工作区与配置**（/root 全量 + drydock 的
     *  /etc 片段），经 MediaStore 落 Downloads/Drydock。系统层（apt 包、node 运行时、
     *  配方）由版本 pin 重放（D8），不进导出——夜批实锤：全环境 gzip 后 ~2GB、proot
     *  下十分钟级，作为备份产品形态不可行。排除 .l2s（link2symlink 目标是宿主绝对路径，
     *  tar 全目录撞 D21 自指环 ELOOP，导出到别处也无效）与 npm/编译缓存（可重取）。
     *  密钥不在环境内文件（I1），导出天然无密钥。返回摘要；失败抛异常。 */
    fun exportEnvTar(context: Context): String {
        val cmd = """
            tar -C / -czf /tmp/drydock-env-export.tar.gz \
              --exclude='./root/.l2s' --exclude='./root/.npm' --exclude='./root/.cache' \
              --exclude='./root/*.sock' --exclude='./root/AndroidDownload' \
              ./root ./etc/profile.d ./etc/apt/sources.list.d 2>&1 | tail -3
            echo TAR_RC=${'$'}{PIPESTATUS[0]}
            stat -c %s /tmp/drydock-env-export.tar.gz 2>/dev/null | sed 's/^/EXPORT_BYTES=/'
        """.trimIndent()
        val r = runInEnv(context, cmd)
        if (!r.output.contains("TAR_RC=0")) throw IllegalStateException("tar 失败：${r.output.takeLast(300)}")
        val f = File(rootfsDir(context), "tmp/drydock-env-export.tar.gz")
        if (!f.exists() || f.length() == 0L) throw IllegalStateException("导出文件缺失")
        val bytes = f.length()
        val uri = Landing.toDownloads(context, f)
        f.delete()
        return "${"%.1f".format(bytes / 1_000_000.0)} MB → $uri"
    }

    // ---------- R4 环境导入（备份恢复） ----------

    /** 同名冲突的用户决定。 */
    enum class ImportDecision { OVERWRITE, SKIP }

    /** 导入结果计数；failedSample 取前几个失败原因（路径 + 异常）。 */
    data class EnvImportResult(
        val imported: Int,
        val skipped: Int,
        val failed: Int,
        val failedSample: List<String>,
    )

    sealed class ImportState {
        data object Copying : ImportState()
        data class Scanning(val entries: Int) : ImportState()
        data class Merging(val done: Int, val total: Int) : ImportState()
    }

    /** 校验失败整体拒绝（此时环境零改动）。 */
    class ImportReject(reason: String) : IllegalStateException(reason)

    /**
     * 从 SAF 选中的 tar.gz 恢复（exportEnvTar 的逆）：先单遍「校验 + 解包到 cacheDir
     * 暂存」，全部条目过了白名单才进入合并——恶意包（绝对路径 / .. / 白名单外 /
     * 头损坏 / 借符号链接越界）在暂存阶段即整体拒绝，环境不动。合并默认增量：
     * 新条目写入 /root（与 /etc 片段），同名同内容静默跳过，同名不同内容问一次
     * onConflict（覆盖/跳过）。恢复的 tar 不含 .l2s（D21 导出口径排除），符号链接
     * 原样重建、无自指环。rootfs 未部署时调用方先 deploy（页内接线），本函数只管灌。
     */
    suspend fun importEnvTar(
        context: Context,
        uri: Uri,
        onState: (ImportState) -> Unit,
        onConflict: suspend (path: String, remaining: Int) -> ImportDecision,
    ): EnvImportResult = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val cacheTar = File(appCtx.cacheDir, "drydock-import.tar.gz")
        val stage = File(appCtx.cacheDir, "drydock-import-stage")
        val rootfs = rootfsDir(appCtx)
        try {
            // 1) SAF 流落缓存（gzip 流不可回卷，落盘后单遍扫描）
            onState(ImportState.Copying)
            cacheTar.delete()
            try {
                appCtx.contentResolver.openInputStream(uri)?.use { input ->
                    cacheTar.outputStream().use { input.copyTo(it, 1 shl 16) }
                } ?: throw ImportReject("无法读取所选文件")
            } catch (e: ImportReject) {
                throw e
            } catch (e: Exception) {
                throw ImportReject("读取所选文件失败：$e")
            }
            if (cacheTar.length() == 0L) throw ImportReject("所选文件为空")

            // 2) 校验 + 解包到暂存（违规即抛 ImportReject，整体拒绝）
            stage.deleteRecursively()
            stage.mkdirs()
            val entries = ArrayList<StagedEntry>(256)
            try {
                scanAndExtract(appCtx, cacheTar, stage, entries, onState)
            } catch (e: ImportReject) {
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                throw ImportReject("tar.gz 结构非法：$e")
            }
            if (entries.isEmpty()) throw ImportReject("tar 内没有可恢复的条目（./root 与 /etc 片段）")

            // 3) 合并（此时校验已全过）。冲突 = 目标已存在且内容不同，先数一遍供
            //    「还剩 N 个」提示；同名同内容不问（覆盖/跳过都无效果）
            var conflictTotal = 0
            for (e in entries) {
                if (!e.isDir && existsNoFollow(e.target) && !sameContent(e)) conflictTotal++
            }
            var imported = 0
            var skipped = 0
            var failed = 0
            val failedSample = ArrayList<String>(3)
            var asked = 0
            for ((idx, e) in entries.withIndex()) {
                try {
                    val stagedSrc = e.staged
                    if (stagedSrc == null) {
                        failed++
                        if (failedSample.size < 3) failedSample.add("${e.name}：硬链接源缺失")
                        continue
                    }
                    ensureUnderRoot(rootfs, e.target)
                    when {
                        e.isDir -> {
                            if (existsNoFollow(e.target) &&
                                !Files.isDirectory(e.target.toPath(), LinkOption.NOFOLLOW_LINKS)
                            ) {
                                removeExisting(e.target)
                            }
                            e.target.mkdirs()
                            imported++
                        }
                        else -> {
                            val exists = existsNoFollow(e.target)
                            val conflict = exists && !sameContent(e)
                            var overwrite = true
                            if (conflict) {
                                val d = onConflict(e.name, conflictTotal - ++asked)
                                overwrite = d == ImportDecision.OVERWRITE
                            }
                            if (exists && !conflict) {
                                skipped++ // 同名同内容：覆盖/跳过无差别，静默跳过
                            } else if (conflict && !overwrite) {
                                skipped++
                            } else {
                                if (exists) {
                                    removeExisting(e.target)
                                }
                                e.target.parentFile?.mkdirs()
                                if (e.link != null) {
                                    Files.createSymbolicLink(
                                        e.target.toPath(), Paths.get(e.link),
                                    )
                                } else {
                                    Files.copy(
                                        stagedSrc.toPath(), e.target.toPath(),
                                        StandardCopyOption.REPLACE_EXISTING,
                                    )
                                    e.target.setExecutable((e.mode and 0x49) != 0, true)
                                }
                                imported++
                            }
                        }
                    }
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (e2: Exception) {
                    failed++
                    if (failedSample.size < 3) failedSample.add("${e.name}：$e2")
                }
                if (idx % 25 == 0 || idx == entries.size - 1) {
                    onState(ImportState.Merging(idx + 1, entries.size))
                }
            }
            EnvImportResult(imported, skipped, failed, failedSample)
        } finally {
            cacheTar.delete()
            stage.deleteRecursively()
        }
    }

    private class StagedEntry(
        val name: String, // 归一化路径（root/… 或 etc/profile.d/… 等）
        val isDir: Boolean,
        val mode: Int,
        val link: String?, // 符号链接目标（tar 原样，不校验——环境内链接可指向任意环境路径）
        var staged: File?, // 暂存源；硬链接源缺失时为 null（计入 failed）
        val target: File, // rootfsDir 下的目标
    )

    /** 同名是否等价：目录不比；链接比目标；文件比字节（大小先短路）。 */
    private fun sameContent(e: StagedEntry): Boolean {
        val t = e.target.toPath()
        return if (e.link != null) {
            Files.isSymbolicLink(t) &&
                Files.readSymbolicLink(t).toString() == e.link
        } else {
            val s = e.staged ?: return false
            !Files.isSymbolicLink(t) &&
                s.length() == e.target.length() &&
                s.inputStream().use { a ->
                    e.target.inputStream().use { b -> streamsEqual(a, b) }
                }
        }
    }

    private fun streamsEqual(a: InputStream, b: InputStream): Boolean {
        val buf1 = ByteArray(64 * 1024)
        val buf2 = ByteArray(64 * 1024)
        while (true) {
            val n1 = readFull(a, buf1)
            val n2 = readFull(b, buf2)
            if (n1 != n2) return false
            if (n1 <= 0) return true
            if (!buf1.copyOf(n1).contentEquals(buf2.copyOf(n1))) return false
        }
    }

    private fun readFull(s: InputStream, buf: ByteArray): Int {
        var done = 0
        while (done < buf.size) {
            val n = s.read(buf, done, buf.size - done)
            if (n < 0) break
            done += n
        }
        return done
    }

    private fun existsNoFollow(f: File): Boolean = Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS)

    /** 让位旧目标：符号链接只删链接本身（deleteRecursively 会跟进目标，删穿到链接
     *  指向处）；真目录按覆盖语义整目录删除；普通文件/硬链直删。 */
    private fun removeExisting(f: File) {
        val p = f.toPath()
        when {
            Files.isSymbolicLink(p) -> Files.delete(p)
            Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) -> f.deleteRecursively()
            else -> Files.delete(p)
        }
    }

    /** 写入目标必须物理落在 rootfs 内：目标链上有符号链接借道出界（如指向宿主路径）即拒。 */
    private fun ensureUnderRoot(rootfs: File, target: File) {
        val root = rootfs.canonicalFile.absolutePath + File.separator
        val canon = target.canonicalFile.absolutePath
        check(canon.startsWith(root)) { "目标越界：$target" }
    }

    /**
     * 单遍读 tar.gz：逐条目归一化路径 → 白名单校验 → 解包到暂存目录。任何违规
     * 抛 ImportReject（调用方删暂存，环境零改动）。支持 GNU L/K 长名、ustar
     * prefix、pax x 记录（path/linkpath/size）、硬链接（内容复制——SELinux 禁
     * app 数据目录 link()，见 deploy 注释）。
     */
    private fun scanAndExtract(
        context: Context,
        cacheTar: File,
        stage: File,
        entries: MutableList<StagedEntry>,
        onState: (ImportState) -> Unit,
    ) {
        val rootfs = rootfsDir(context)
        val input = try {
            java.util.zip.GZIPInputStream(cacheTar.inputStream(), 1 shl 16)
        } catch (e: Exception) {
            throw ImportReject("不是有效的 gzip 文件：$e")
        }
        val header = ByteArray(512)
        var longName: String? = null
        var longLink: String? = null
        var paxPath: String? = null
        var paxLink: String? = null
        var paxSize: Long? = null
        val pendingHard = ArrayList<Pair<StagedEntry, String>>()
        var count = 0
        input.use { ins ->
            while (true) {
                val h = readFullOrThrow(ins, header, "tar 头截断")
                if (header.all { it == 0.toByte() }) break // 结束块
                verifyChecksum(header)
                val rawName = fieldStr(header, 0, 100)
                var size = fieldOctal(header, 124, 12)
                val typeflag = header[156].toInt().toChar()
                val linkField = fieldStr(header, 157, 257)
                // GNU 长名/长链接与 pax 覆盖作用于紧随的条目
                when (typeflag) {
                    'L' -> { longName = payloadStr(ins, size); skipPad(ins, size); continue }
                    'K' -> { longLink = payloadStr(ins, size); skipPad(ins, size); continue }
                    'x', 'g' -> {
                        val recs = payloadStr(ins, size)
                        skipPad(ins, size)
                        if (typeflag == 'x') {
                            Regex("(?:^|\\n)\\d+ (path|linkpath|size)=(.*)").findAll(recs)
                                .associate { it.groupValues[1] to it.groupValues[2].trim() }
                                .also { m ->
                                    paxPath = m["path"]?.takeIf { it.isNotBlank() }
                                    paxLink = m["linkpath"]?.takeIf { it.isNotBlank() }
                                    paxSize = m["size"]?.toLongOrNull()
                                }
                        }
                        continue
                    }
                    else -> {}
                }
                val prefix = if (fieldStr(header, 257, 263) == "ustar") fieldStr(header, 345, 500) else ""
                val name = longName ?: paxPath ?: (if (prefix.isNotBlank()) "$prefix/$rawName" else rawName)
                val link = longLink ?: paxLink ?: linkField.takeIf { it.isNotBlank() }
                if (paxSize != null) size = paxSize!!
                longName = null; longLink = null; paxPath = null; paxLink = null; paxSize = null

                val norm = normalizeTarPath(name)
                    ?: throw ImportReject("路径非法（绝对路径或含 ..）：$name")
                if (norm.isEmpty()) continue // tar 根目录标记（"./"），无需恢复
                if (!withinWhitelist(norm)) {
                    throw ImportReject("路径在白名单外（只收 ./root 与 /etc 片段）：$norm")
                }
                if (++count % 200 == 0) onState(ImportState.Scanning(count))

                val staged = File(stage, norm)
                ensureUnderRoot(stage, staged) // 暂存链上有越界符号链接即拒（防借道写穿）
                val target = File(rootfs, norm)
                when (typeflag) {
                    '5' -> {
                        staged.mkdirs()
                        entries.add(StagedEntry(norm, true, 0, null, staged, target))
                    }
                    '0', '\u0000', '7' -> {
                        staged.parentFile?.mkdirs()
                        staged.outputStream().use { out ->
                            copyExactly(ins, size, out)
                        }
                        skipPad(ins, size)
                        val mode = fieldOctal(header, 100, 8).toInt()
                        entries.add(StagedEntry(norm, false, mode, null, staged, target))
                    }
                    '2' -> {
                        val lt = link ?: throw ImportReject("符号链接缺目标：$norm")
                        staged.parentFile?.mkdirs()
                        Files.createSymbolicLink(staged.toPath(), Paths.get(lt))
                        entries.add(StagedEntry(norm, false, 0, lt, staged, target))
                        skipPad(ins, size) // 符号链接 size 应为 0，防御性跳过
                    }
                    '1' -> {
                        val ref = normalizeTarPath(link ?: "")
                            ?.takeIf { withinWhitelist(it) }
                            ?: throw ImportReject("硬链接引用非法：$name → $link")
                        staged.parentFile?.mkdirs()
                        val e = StagedEntry(norm, false, 0, null, null, target)
                        val src = File(stage, ref)
                        if (existsNoFollow(src)) {
                            src.copyTo(staged)
                            e.staged = staged
                        } else {
                            pendingHard.add(e to ref) // 引用可能晚于自身出现，扫描完统一补
                        }
                        entries.add(e)
                        skipPad(ins, size)
                    }
                    else -> throw ImportReject("不支持的条目类型 '$typeflag'：$norm")
                }
            }
        }
        // 硬链接兜底：引用成员此时应全部在场（GNU tar 先发首见成员）
        val byName = entries.filter { !it.isDir }.associateBy { it.name }
        for ((e, ref) in pendingHard) {
            val src = byName[ref]?.staged
            if (src != null && existsNoFollow(src)) {
                val staged = File(stage, e.name)
                src.copyTo(staged)
                e.staged = staged
            } // 仍缺则保持 staged=null，合并阶段计入 failed
        }
    }

    // ---------- tar 底层件（R4 导入用；无第三方依赖，512 字节头逐条解析） ----------

    private fun readFullOrThrow(s: InputStream, buf: ByteArray, what: String) {
        val n = readFull(s, buf)
        if (n < buf.size) throw ImportReject("数据截断（$what，读到 $n/${buf.size}）")
    }

    private fun verifyChecksum(h: ByteArray) {
        val stored = fieldOctal(h, 148, 8)
        var unsigned = 0L
        var signed = 0L
        for (i in h.indices) {
            val b = if (i in 148..155) 0x20.toByte() else h[i] // 校验和字段按空格计
            unsigned += b.toInt() and 0xFF
            signed += b.toInt()
        }
        if (stored != unsigned && stored != signed) {
            throw ImportReject("条目头校验和不符（文件损坏或不是 tar.gz）")
        }
    }

    private fun fieldStr(h: ByteArray, from: Int, to: Int): String =
        h.copyOfRange(from, to).takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)

    private fun fieldOctal(h: ByteArray, from: Int, len: Int): Long {
        val s = h.copyOfRange(from, from + len)
            .takeWhile { it != 0.toByte() && it != ' '.code.toByte() }
            .toByteArray().toString(Charsets.UTF_8).trim()
        if (s.isEmpty()) return 0
        return s.toLongOrNull(radix = 8) ?: throw ImportReject("数值字段非法：$s")
    }

    private fun payloadStr(ins: InputStream, size: Long): String {
        val bytes = ByteArray(size.toInt())
        readFullOrThrow(ins, bytes, "长名/pax 载荷")
        return bytes.takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)
    }

    private fun copyExactly(ins: InputStream, size: Long, out: java.io.OutputStream) {
        val buf = ByteArray(64 * 1024)
        var done = 0L
        while (done < size) {
            val n = ins.read(buf, 0, minOf(buf.size.toLong(), size - done).toInt())
            if (n < 0) throw ImportReject("文件数据截断")
            out.write(buf, 0, n)
            done += n
        }
    }

    private fun skipPad(ins: InputStream, size: Long) {
        val pad = ((size + 511) / 512 * 512 - size).toInt()
        if (pad > 0) {
            val buf = ByteArray(pad)
            readFullOrThrow(ins, buf, "条目填充")
        }
    }

    /** tar 条目名归一化：去掉 ./、空段与尾斜杠；绝对路径或含 .. 返回 null。tar 根（"./"）返回 ""。 */
    private fun normalizeTarPath(raw: String): String? {
        if (raw.isEmpty()) return null
        if (raw.startsWith("/")) return null
        val parts = raw.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    /** 白名单：./root 全量 + 导出口径的两个 /etc 片段（backlog R4）。 */
    private fun withinWhitelist(p: String): Boolean =
        p == "root" || p.startsWith("root/") ||
            p == "etc/profile.d" || p.startsWith("etc/profile.d/") ||
            p == "etc/apt/sources.list.d" || p.startsWith("etc/apt/sources.list.d/")

    /** 向 env.sh 写入/更新若干 export 行（同名行去重后追加；文件不存在则建模板）。
     *  向导的常见服务 key 与自定义端点 key、设置页共用；值原样写入（key 惯例为
     *  URL-safe token，含空格等 bash 特殊字符的值应由用户自编 env.sh 处理）。 */
    fun upsertEnvExports(context: Context, exports: List<Pair<String, String>>) {
        val f = envShFile(context)
        val names = exports.map { it.first }.toSet()
        val kept = runCatching { f.readText() }.getOrElse { "" }
            .lines()
            .filter { line ->
                val t = line.trim()
                names.none { v -> t.startsWith("export $v=") }
            }
            .toMutableList()
        while (kept.isNotEmpty() && kept.last().isBlank()) kept.removeAt(kept.size - 1)
        val sb = StringBuilder()
        if (kept.isNotEmpty()) sb.appendLine(kept.joinToString("\n"))
        sb.appendLine()
        exports.forEach { (v, value) -> sb.appendLine("export $v=$value") }
        f.writeText(sb.toString())
    }

    /** 在已部署环境内执行命令（proot -0 -L，绑定 dev/proc/sys）。
     *  extraBinds：额外 "宿主路径:环境内路径" 绑定；extraEnv：注入宿主侧环境变量
     *  （I1 的密钥即经此进环境，只存在于进程 environment，不落环境内文件）。 */
    fun runInEnv(
        context: Context,
        command: String,
        extraEnv: Map<String, String> = emptyMap(),
        extraBinds: List<String> = emptyList(),
    ): ExecResult {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val proot = File(nativeDir, "libproot.so")
        val loader = File(nativeDir, "libproot-loader.so")
        val baseArgv = listOf(
            proot.absolutePath,
            "-0", "--link2symlink",
            "-r", rootfsDir(context).absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", l2sSelfBind(context),
        ) + BindStore.binds(context).flatMap { listOf("-b", it) } + extraBinds.flatMap { listOf("-b", it) }
        val argv = baseArgv + listOf(
            "-w", "/root",
            // 非登录 shell：显式 source 用户环境变量（~/.drydock/env.sh，D27），与
            // 登录 shell 的 profile.d 同口径；密钥自 2026-10-05 起也住这里（D29）
            "/bin/bash", "-c",
            ". /root/.drydock/env.sh 2>/dev/null || true; $command",
        )
        Log.i(TAG, "exec: ${argv.joinToString(" ").dropLast(command.length)}…")
        val p = ProcessBuilder(argv).redirectErrorStream(true).apply {
            environment().apply {
                put("PROOT_LOADER", loader.absolutePath)
                put("PROOT_TMP_DIR", context.cacheDir.absolutePath)
                put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
                put("HOME", "/root")
                put("TERM", "xterm-256color")
                put("LANG", "C.UTF-8")
                extraEnv.forEach { (k, v) -> put(k, v) }
            }
        }.start()
        val out = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        Log.i(TAG, "exit=$code\n$out")
        return ExecResult(code, out)
    }

    /** 下载带进度回调（镜像多拒 Java UA，统一伪装）。AgentManager 复用。 */
    internal fun download(url: URL, dest: File, onProgress: (Int) -> Unit) {
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        // TUNA 等镜像拒绝 Java/* 默认 UA（403）
        conn.setRequestProperty("User-Agent", "drydock-prototype/0.1 (Android)")
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            var lastPct = -1
            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    internal fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 恢复出厂 apt 源（镜像设置切回默认时调用）。 */
    fun resetAptSources(context: Context) {
        writeAptSources(rootfsDir(context))
    }

    private fun configure(rootfs: File) {
        // DNS：ubuntu-base 里的 resolv.conf 可能是悬空符号链接，先删再写
        val resolv = rootfs.resolve("etc/resolv.conf")
        if (java.nio.file.Files.exists(resolv.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            resolv.delete()
        }
        resolv.writeText("nameserver 223.5.5.5\nnameserver 8.8.8.8\n")
        writeAptSources(rootfs)
        val legacy = rootfs.resolve("etc/apt/sources.list")
        if (legacy.exists()) legacy.writeText("# 已切换到 ubuntu.sources（drydock）\n")
    }

    // apt：24.04 默认 deb822（ubuntu.sources）。arm64 的包在 ports 仓库，安全源同站。
    // 多 URIs = apt 镜像回退序（deb822 一节多 URI，apt 按序失败转移）——国产镜像先行、官方兜底（D12）。
    // URIs 必须单行空格分隔：多行续行经 trimIndent 会丢缩进变顶格，写出非法 stanza
    // （2026-10-04 夜批实锤：文件 mtime 落坏点后一切 apt 报 Malformed stanza 1）。
    private fun writeAptSources(rootfs: File) {
        val suite = RootfsManifest.APT_SUITE
        val mirror = RootfsManifest.APT_MIRROR
        val sources = rootfs.resolve("etc/apt/sources.list.d/ubuntu.sources")
        sources.parentFile?.mkdirs()
        val uris = listOf(mirror, "http://ports.ubuntu.com/ubuntu-ports")
        sources.writeText(
            """
            Types: deb
            URIs: ${uris.joinToString(" ")}
            Suites: $suite $suite-updates $suite-backports
            Components: main universe restricted multiverse
            Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg

            Types: deb
            URIs: ${uris.joinToString(" ")}
            Suites: $suite-security
            Components: main universe restricted multiverse
            Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
            """.trimIndent() + "\n",
        )
    }
}
