package dev.drydock.prototype

import org.json.JSONArray
import org.json.JSONObject

/**
 * rootfs 版本清单：一切来源 pin 死在这里，升级 = 改这里 + 走一次 AV1。
 * 选 24.04 系：glibc 2.39 与 proot 的组合被安卓生态海量验证（discussion-log §17）。
 *
 * R8 起支持旁路升级（D26）：版本发现 = 内置 pin + 本地更新索引（files/
 * rootfs-updates.json，原型期的侧载/测试通道；远端清单渠道随 D26 分发定稿再接，
 * 不臆造 URL）。索引里的版本号可与 Ubuntu 发行号不同构（如 "24.04.6-r8test"），
 * 按每段前导数字比较（versionKey）。
 */
object RootfsManifest {
    const val UBUNTU_VERSION = "24.04.5"
    const val FILE_NAME = "ubuntu-base-24.04.5-base-arm64.tar.gz"
    const val SIZE_BYTES = 29_936_675L
    const val SHA256 = "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2"

    /** 本地更新索引（filesDir 下；缺省即无更新源，只有内置 pin 兜底）。 */
    const val UPDATE_INDEX_FILE = "rootfs-updates.json"

    /** 一个可部署版本：镜像 tar 的完整指纹（文件名 + 大小 + sha256 + 下载源）。 */
    data class Release(
        val version: String,
        val fileName: String,
        val sizeBytes: Long,
        val sha256: String,
        val mirrors: List<String>,
    )

    /** 内置 pin 也是一个 Release（D26「宿主侧改清单」路线：app 升级带新 pin 即升级源）。 */
    fun builtinRelease() = Release(
        UBUNTU_VERSION, FILE_NAME, SIZE_BYTES, SHA256, MIRRORS,
    )

    /** 解析本地更新索引 JSON（{"releases":[{version,fileName,sizeBytes,sha256,mirrors[]}]}）。 */
    fun parseIndex(text: String): List<Release> = try {
        val arr = JSONObject(text).optJSONArray("releases") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                Release(
                    o.getString("version"),
                    o.getString("fileName"),
                    o.getLong("sizeBytes"),
                    o.getString("sha256"),
                    o.optJSONArray("mirrors")?.let { m -> (0 until m.length()).map(m::getString) }
                        ?: emptyList(),
                )
            }.getOrNull()
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** 版本比较键：每段取前导数字（"6-r8"→6，无数字段记 0），列表字典序——
     *  "24.04.6-r8test" > "24.04.5"，"24.04.5.1" > "24.04.5"。 */
    fun versionKey(v: String): List<Int> = v.split('.').map { seg ->
        seg.takeWhile { it.isDigit() }.takeIf { it.isNotEmpty() }?.toInt() ?: 0
    }

    /** 版本字典序比较（List 无 compareTo，手写；短前缀者小：24.04.5 < 24.04.5.1）。 */
    fun compareVersions(a: String, b: String): Int {
        val ka = versionKey(a)
        val kb = versionKey(b)
        for (i in 0 until minOf(ka.size, kb.size)) {
            val c = ka[i].compareTo(kb[i])
            if (c != 0) return c
        }
        return ka.size.compareTo(kb.size)
    }

    /** 点分数值段比较：candidate 是否严格更新（同版本异后缀不算，防升级环）。 */
    fun isNewer(candidate: String, baseline: String): Boolean = candidate != baseline &&
        compareVersions(candidate, baseline) > 0

    /** 下载源按序回退（D12：国产优先；USTC 未镜像 ubuntu-base，故备源为官方）。 */
    val MIRRORS = listOf(
        "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/",
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/",
    )

    /**
     * apt 用 http：apt 的完整性由 GPG 签名链保证（Ubuntu 官方镜像同为此立场），
     * 且规避企业网/代理对 https 的 MITM 导致证书验证失败。arm64 的源在 ports。
     */
    const val APT_MIRROR = "http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports"
    const val APT_SUITE = "noble"
}
