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

    /** 在已部署环境内执行命令（proot -0 -L，绑定 dev/proc/sys）。 */
    fun runInEnv(context: Context, command: String): ExecResult {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val proot = File(nativeDir, "libproot.so")
        val loader = File(nativeDir, "libproot-loader.so")
        val argv = listOf(
            proot.absolutePath,
            "-0", "--link2symlink",
            "-r", rootfsDir(context).absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
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
            }
        }.start()
        val out = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        Log.i(TAG, "exit=$code\n$out")
        return ExecResult(code, out)
    }

    private fun download(url: URL, dest: File, onProgress: (Int) -> Unit) {
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

    private fun sha256(f: File): String {
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

    private fun configure(rootfs: File) {
        // DNS：ubuntu-base 里的 resolv.conf 可能是悬空符号链接，先删再写
        val resolv = rootfs.resolve("etc/resolv.conf")
        if (java.nio.file.Files.exists(resolv.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            resolv.delete()
        }
        resolv.writeText("nameserver 223.5.5.5\nnameserver 8.8.8.8\n")

        // apt：24.04 默认 deb822（ubuntu.sources）。arm64 的包在 ports 仓库，安全源同站。
        val suite = RootfsManifest.APT_SUITE
        val mirror = RootfsManifest.APT_MIRROR
        val sources = rootfs.resolve("etc/apt/sources.list.d/ubuntu.sources")
        sources.parentFile?.mkdirs()
        sources.writeText(
            """
            Types: deb
            URIs: $mirror
            Suites: $suite $suite-updates $suite-backports
            Components: main universe restricted multiverse
            Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg

            Types: deb
            URIs: $mirror
            Suites: $suite-security
            Components: main universe restricted multiverse
            Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
            """.trimIndent() + "\n",
        )
        val legacy = rootfs.resolve("etc/apt/sources.list")
        if (legacy.exists()) legacy.writeText("# 已切换到 ubuntu.sources（drydock）\n")
    }
}
