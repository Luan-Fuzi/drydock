package dev.drydock.prototype

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
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
            "/bin/bash", "-c", command,
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
