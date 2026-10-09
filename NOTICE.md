# 第三方组件声明

本仓库与构建产物内置以下第三方组件，各自适用其原始许可证：

| 组件 | 版本（pin） | 许可证 | 源码 |
|---|---|---|---|
| proot（Termux fork） | v5.1.107.95 | GPL-2.0-or-later（STMicroelectronics） | https://github.com/termux/proot |
| libandroid-shmem | v0.7 | BSD-3-Clause（Termux） | https://github.com/termux/libandroid-shmem |
| talloc | 2.4.3 | LGPL-3.0（Samba 项目） | https://www.samba.org/talloc/ |

以上组件以二进制形式随仓库分发（`app/src/main/jniLibs/arm64-v8a/libproot.so`、`libproot-loader.so`，talloc 静态链接其中）。**对应源码的获取与重建路径**：`scripts/build-proot.sh` 按上述 pin 从上游仓库与官方发布 tarball 重新获取源码、交叉编译并回填 jniLibs（含 64KB 对齐断言），是这些 GPL/LGPL 组件的完整源码等价物。

另：应用运行时下载的 Linux 环境（Ubuntu rootfs 及其 apt 包、node、ttyd、npm 安装的 agent CLI 等）不随本仓库分发，各组件许可证随其发行物自带。

本项目自有代码采用 GPL-3.0（见 [LICENSE](LICENSE)）。
