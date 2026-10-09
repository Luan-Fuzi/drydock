#!/usr/bin/env bash
# 宿主侧环境执行器：在 app 私有 rootfs 内以 bash 跑一个脚本（验收/调试用，绕过 App UI）。
# 用法：scripts/env-run.sh <本地脚本路径> [host:env 绑定]（可选，如 /storage/emulated/0/Download:/root/AndroidDownload）
# 依赖：adb、目标 app 为 debuggable（run-as）；AVD 或真机均可。
# 实现注记：
# - 含 '=' 的路径不能作为 toybox env 的命令参数（会被误判为赋值），路径一律经 sh argv 传递；
# - link2symlink 的 .l2s 符号链接目标是宿主绝对路径，环境必须自绑定 rootfs 宿主路径，
#   否则跨会话断链（见 docs/decisions.md D20）。
set -euo pipefail
ADB_CMD="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG="${DRYDOCK_PKG:-dev.drydock.debug}"

# 真机纪律（AGENTS.md）：多设备在线且未显式指定 serial 时拒绝执行
# （macOS 自带 bash 3.2，不能用 mapfile；设备列表统一空格分隔便于 case 匹配）
SERIAL="${ANDROID_SERIAL:-}"
DEVS="$("$ADB_CMD" devices | awk 'NR>1 && $2=="device"{printf "%s ", $1}')"
NDEVS=$(printf '%s' "$DEVS" | wc -w | tr -d ' ')
if [[ -n "$SERIAL" ]]; then
  case " $DEVS" in
    *" $SERIAL "*) ADB=("$ADB_CMD" -s "$SERIAL") ;;
    *) echo "ANDROID_SERIAL=$SERIAL 不在线：${DEVS:-无}" >&2; exit 1 ;;
  esac
elif (( NDEVS == 0 )); then
  echo "无 adb 设备在线（检查 USB 调试 / RSA 授权）" >&2; exit 1
elif (( NDEVS > 1 )); then
  echo "多设备在线（$DEVS）：须 ANDROID_SERIAL=<serial> 显式指定目标（真机纪律）" >&2; exit 1
else
  ADB=("$ADB_CMD")
fi
SCRIPT="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"

INNER=/data/local/tmp/drydock-inner.sh
RUNNER=/data/local/tmp/drydock-run.sh

cat > /tmp/drydock-inner.sh <<'EOF'
#!/system/bin/sh
# $1 loader, $2 proot，$3 可选绑定（host:env），$4 包名；脚本已放在 app files/__envrun.sh
if [ -n "$3" ]; then
  PROOT_LOADER="$1" PROOT_TMP_DIR=cache HOME=/root LANG=C.UTF-8 \
  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  exec "$2" -0 --link2symlink -r files/ubuntu-rootfs \
  -b "/data/user/0/$4/files/ubuntu-rootfs:/data/data/$4/files/ubuntu-rootfs" \
  -b "$3" \
  -b /dev -b /proc -b /sys -b files/__envrun.sh:/check.sh -w /root \
  /bin/bash /check.sh
else
  PROOT_LOADER="$1" PROOT_TMP_DIR=cache HOME=/root LANG=C.UTF-8 \
  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  exec "$2" -0 --link2symlink -r files/ubuntu-rootfs \
  -b "/data/user/0/$4/files/ubuntu-rootfs:/data/data/$4/files/ubuntu-rootfs" \
  -b /dev -b /proc -b /sys -b files/__envrun.sh:/check.sh -w /root \
  /bin/bash /check.sh
fi
EOF
BIND_ARG="${2:-}"
cat > /tmp/drydock-run.sh <<EOF
#!/system/bin/sh
NATLIB=\$(dirname "\$(pm path $PKG | sed 's/package://')")/lib/arm64
exec run-as $PKG /system/bin/sh /data/local/tmp/drydock-inner.sh \
  "\$NATLIB/libproot-loader.so" "\$NATLIB/libproot.so" "$BIND_ARG" "$PKG"
EOF

"${ADB[@]}" push /tmp/drydock-inner.sh "$INNER" >/dev/null
"${ADB[@]}" push /tmp/drydock-run.sh "$RUNNER" >/dev/null
"${ADB[@]}" push "$SCRIPT" /data/local/tmp/drydock-check.sh >/dev/null
"${ADB[@]}" shell run-as "$PKG" cp /data/local/tmp/drydock-check.sh files/__envrun.sh
exec "${ADB[@]}" shell sh "$RUNNER"
