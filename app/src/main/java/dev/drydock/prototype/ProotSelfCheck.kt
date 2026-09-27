package dev.drydock.prototype

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * 步骤 1 自检：经宿主 spawn proot，在最小 rootfs 内执行 /bin/sh -c 'uname -a'。
 *
 * 最小 rootfs 不下载任何东西：bin/sh 符号链接到 /system/bin/sh，
 * 通过 -b /system 让 guest 内的 linker64 与动态库可解析。
 */
object ProotSelfCheck {

    data class Result(
        val command: List<String>,
        val exitCode: Int,
        val output: String,
    )

    private const val TAG = "DrydockSelfCheck"
    private const val MARKER = "PROOT_SELFCHECK_OK"

    fun run(context: Context): Result {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val proot = File(nativeDir, "libproot.so")
        val loader = File(nativeDir, "libproot-loader.so")

        if (!proot.canExecute() || !loader.canExecute()) {
            val msg = "引擎文件缺失或不可执行: proot=${proot.canExecute()} loader=${loader.canExecute()}\n" +
                "nativeLibraryDir=$nativeDir\n内容: ${nativeDir.list()?.joinToString()}"
            Log.e(TAG, msg)
            return Result(emptyList(), -1, msg)
        }

        val rootfs = prepareRootfs(context)

        val command = listOf(
            proot.absolutePath,
            "-r", rootfs.absolutePath,
            // /system 提供 sh 与库；/apex 是 linker64 的符号链接目标（Android 二进制必需）；
            // /proc 供动态 linker 的 readlink(/proc/self/fd) 自检
            "-b", "/system",
            "-b", "/apex",
            "-b", "/proc",
            "/bin/sh", "-c",
            "uname -a; echo $MARKER",
        )
        Log.i(TAG, "exec: ${command.joinToString(" ")}")

        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .apply {
                environment().apply {
                    put("PROOT_LOADER", loader.absolutePath)
                    put("PROOT_TMP_DIR", context.cacheDir.absolutePath)
                    put("PATH", "/system/bin:/system/xbin")
                    put("HOME", context.filesDir.absolutePath)
                    put("TERM", "dumb")
                }
            }
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        Log.i(TAG, "exit=$exitCode\n$output")
        return Result(command, exitCode, output)
    }

    /** 判据：exit=0 且输出含 uname 行与 OK 标记。 */
    fun passed(result: Result): Boolean =
        result.exitCode == 0 &&
            result.output.contains("Linux") &&
            result.output.contains(MARKER)

    private fun prepareRootfs(context: Context): File {
        val rootfs = File(context.filesDir, "rootfs")
        val bin = File(rootfs, "bin")
        if (!bin.exists()) bin.mkdirs()
        val sh = File(bin, "sh")
        if (!Files.exists(sh.toPath()) && !sh.exists()) {
            Files.createSymbolicLink(sh.toPath(), Paths.get("/system/bin/sh"))
        }
        return rootfs
    }
}
