package dev.drydock.prototype

/**
 * rootfs 版本清单：一切来源 pin 死在这里，升级 = 改这里 + 走一次 AV1。
 * 选 24.04 系：glibc 2.39 与 proot 的组合被安卓生态海量验证（discussion-log §17）。
 */
object RootfsManifest {
    const val UBUNTU_VERSION = "24.04.5"
    const val FILE_NAME = "ubuntu-base-24.04.5-base-arm64.tar.gz"
    const val SIZE_BYTES = 29_936_675L
    const val SHA256 = "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2"

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
