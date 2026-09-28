#!/usr/bin/env bash
# 宿主侧环境执行器：在 app 私有 rootfs 内以 bash 跑一个脚本（验收/调试用，绕过 App UI）。
# 用法：scripts/env-run.sh <本地脚本路径>
# 依赖：adb、目标 app 为 debuggable（run-as）；AVD 或真机均可。
# 实现注记：
# - 含 '=' 的路径不能作为 toybox env 的命令参数（会被误判为赋值），路径一律经 sh argv 传递；
# - link2symlink 的 .l2s 符号链接目标是宿主绝对路径，环境必须自绑定 rootfs 宿主路径，
#   否则跨会话断链（见 docs/decisions.md D20）。
set -euo pipefail
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG=dev.drydock.prototype
SCRIPT="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"

INNER=/data/local/tmp/drydock-inner.sh
RUNNER=/data/local/tmp/drydock-run.sh

cat > /tmp/drydock-inner.sh <<'EOF'
#!/system/bin/sh
# $1 loader, $2 proot；脚本已放在 app files/__envrun.sh
PROOT_LOADER="$1" PROOT_TMP_DIR=cache HOME=/root LANG=C.UTF-8 \
  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  exec "$2" -0 --link2symlink -r files/ubuntu-rootfs \
  -b /data/user/0/dev.drydock.prototype/files/ubuntu-rootfs:/data/data/dev.drydock.prototype/files/ubuntu-rootfs \
  -b /dev -b /proc -b /sys -b files/__envrun.sh:/check.sh -w /root \
  /bin/bash /check.sh
EOF
cat > /tmp/drydock-run.sh <<'EOF'
#!/system/bin/sh
NATLIB=$(dirname "$(pm path dev.drydock.prototype | sed 's/package://')")/lib/arm64
exec run-as dev.drydock.prototype /system/bin/sh /data/local/tmp/drydock-inner.sh \
  "$NATLIB/libproot-loader.so" "$NATLIB/libproot.so"
EOF

"$ADB" push /tmp/drydock-inner.sh "$INNER" >/dev/null
"$ADB" push /tmp/drydock-run.sh "$RUNNER" >/dev/null
"$ADB" push "$SCRIPT" /data/local/tmp/drydock-check.sh >/dev/null
"$ADB" shell run-as "$PKG" cp /data/local/tmp/drydock-check.sh files/__envrun.sh
exec "$ADB" shell sh "$RUNNER"
