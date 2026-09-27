#!/usr/bin/env bash
# 交叉编译 proot（Termux fork，锁 tag）及其 loader，产物安置到 app jniLibs。
#
# 依据：termux-packages packages/proot/build.sh（无补丁配方）+ 本仓约束：
#   - 产物命名 lib*.so 进 jniLibs，靠 useLegacyPackaging 落地 nativeLibraryDir（W^X 下唯一可 exec 位置）
#   - loader 运行时路径经 PROOT_LOADER 环境变量注入（nativeLibraryDir 每次安装随机化）
#   - proot 主二进制按 16KB page size 链接（讨论记录 §20 版本适配三轴）
#
# 用法：scripts/build-proot.sh   （首次运行需要网络；third_party/ 可随时删除重来）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TP="$ROOT/third_party"
OUT="$TP/out"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
NDK_VER="29.0.14206865"
TC="$SDK/ndk/$NDK_VER/toolchains/llvm/prebuilt/darwin-x86_64"
[ -d "$TC" ] || TC="$SDK/ndk/$NDK_VER/toolchains/llvm/prebuilt/darwin-arm64"
API=29

CC="$TC/bin/aarch64-linux-android$API-clang"
AR="$TC/bin/llvm-ar"
STRIP="$TC/bin/llvm-strip"
OBJCOPY="$TC/bin/llvm-objcopy"
OBJDUMP="$TC/bin/llvm-objdump"
READELF="$TC/bin/llvm-readelf"
for t in "$CC" "$AR" "$STRIP" "$OBJCOPY" "$OBJDUMP" "$READELF"; do
    [ -x "$t" ] || { echo "工具缺失: $t" >&2; exit 1; }
done

PROOT_REPO="https://github.com/termux/proot.git"
PROOT_TAG="v5.1.107.95"          # Termux 打包版本（discussion-log §17 锁定）
SHMEM_REPO="https://github.com/termux/libandroid-shmem.git"
SHMEM_TAG="v0.7"
TALLOC_VER="2.4.3"
TALLOC_URL="https://www.samba.org/ftp/talloc/talloc-$TALLOC_VER.tar.gz"
TALLOC_SHA256="dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd"

JOBS="$(sysctl -n hw.ncpu)"
mkdir -p "$TP" "$OUT/lib" "$OUT/include"

# ---------- 1. 源码（pin 版本） ----------
if [ ! -d "$TP/proot/.git" ]; then
    git clone "$PROOT_REPO" "$TP/proot"
fi
git -C "$TP/proot" checkout --quiet "$PROOT_TAG"
echo "proot: $(git -C "$TP/proot" describe --tags)"

if [ ! -d "$TP/libandroid-shmem/.git" ]; then
    git clone --quiet --depth 1 --branch "$SHMEM_TAG" "$SHMEM_REPO" "$TP/libandroid-shmem"
fi

if [ ! -d "$TP/talloc-$TALLOC_VER" ]; then
    if [ ! -f "$TP/talloc-$TALLOC_VER.tar.gz" ]; then
        curl -sSL -o "$TP/talloc-$TALLOC_VER.tar.gz" "$TALLOC_URL"
    fi
    echo "$TALLOC_SHA256  $TP/talloc-$TALLOC_VER.tar.gz" | shasum -a 256 -c -
    tar -C "$TP" -xzf "$TP/talloc-$TALLOC_VER.tar.gz"
fi

# ---------- 2. talloc 静态库（waf 交叉编译，配方出自 termux-packages/packages/libtalloc） ----------
if [ ! -f "$OUT/lib/libtalloc.a" ]; then
    cd "$TP/talloc-$TALLOC_VER"
    cat > cross-answers.txt <<'EOF'
Checking uname sysname type: "Linux"
Checking uname machine type: "dontcare"
Checking uname release type: "dontcare"
Checking uname version type: "dontcare"
Checking simple C program: OK
building library support: OK
Checking for large file support: OK
Checking for -D_FILE_OFFSET_BITS=64: OK
Checking for WORDS_BIGENDIAN: OK
Checking for C99 vsnprintf: OK
Checking for HAVE_SECURE_MKSTEMP: OK
rpath library support: OK
-Wl,--version-script support: FAIL
Checking correct behavior of strtoll: OK
Checking correct behavior of strptime: OK
Checking for HAVE_IFACE_GETIFADDRS: OK
Checking for HAVE_IFACE_IFCONF: OK
Checking for HAVE_IFACE_IFREQ: OK
Checking getconf LFS_CFLAGS: OK
Checking for large file support without additional flags: OK
Checking for working strptime: OK
Checking for HAVE_SHARED_MMAP: OK
Checking for HAVE_MREMAP: OK
Checking for HAVE_INCOHERENT_MMAP: OK
Checking getconf large file support flags work: OK
EOF
    CC="$CC" AR="$AR" ./configure --prefix="$OUT" --disable-rpath --disable-python \
        --cross-compile --cross-answers=cross-answers.txt
    make -j"$JOBS"
    (cd bin/default && "$AR" rcu libtalloc.a talloc*.o && cp libtalloc.a "$OUT/lib/")
    cp talloc.h "$OUT/include/" 2>/dev/null || cp bin/default/talloc.h "$OUT/include/" 2>/dev/null || true
fi

# ---------- 3. libandroid-shmem 静态库 ----------
if [ ! -f "$OUT/lib/libandroid-shmem.a" ]; then
    cd "$TP/libandroid-shmem"
    mkdir -p "$OUT/include/sys"
    cp shm.h "$OUT/include/sys/shm.h"
    # bionic 的 <paths.h> 不定义 _PATH_TMP（glibc 扩展），shmem.c 的 ashv key 路径用到
    "$CC" -fpic -std=c11 -Wall -Wextra -D_PATH_TMP='"/data/local/tmp"' \
        -I"$OUT/include" -c shmem.c -o "$OUT/shmem.o"
    "$AR" rcs "$OUT/lib/libandroid-shmem.a" "$OUT/shmem.o"
fi

# ---------- 4. proot + loader ----------
cd "$TP/proot"
# CFLAGS/CPPFLAGS/LDFLAGS 走环境变量：GNUmakefile 内部用 += 追加，命令行传参会覆盖掉追加项
# -std=gnu11 -Wno-error=implicit-function-declaration：clang 16+ 把隐式函数声明默认
# 升为错误（与 -std 无关），proot 老代码（如 ashmem_memfd.c 缺 string.h）按 Termux 全局
# CFLAGS 的方式降回警告
export CPPFLAGS="-DARG_MAX=131072 -I$OUT/include"
export CFLAGS="-std=gnu11 -Wno-error=implicit-function-declaration"
# -landroid：ASharedMemory_*（libandroid-shmem 依赖，API 26+）；-llog：android_log
export LDFLAGS="-L$OUT/lib -Wl,-z,max-page-size=16384 -llog -landroid"

# loader-info 生成规则调用裸命令 readelf，垫一个指向 llvm-readelf 的壳
mkdir -p "$OUT/bin"
ln -sf "$READELF" "$OUT/bin/readelf"
export PATH="$OUT/bin:$PATH"

make -C src clean >/dev/null 2>&1 || true
make -C src -j"$JOBS" \
    CC="$CC" STRIP="$STRIP" OBJCOPY="$OBJCOPY" OBJDUMP="$OBJDUMP" \
    PROOT_WITH_LIBANDROID_SHMEM=true \
    PROOT_UNBUNDLE_LOADER="$OUT/libexec/proot"

"$STRIP" src/proot
"$STRIP" src/loader/loader

# ---------- 5. 安置到 jniLibs ----------
JNILIBS="$ROOT/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$JNILIBS"
cp src/proot "$JNILIBS/libproot.so"
cp src/loader/loader "$JNILIBS/libproot-loader.so"

# ---------- 6. 断言 ----------
ALIGN="$("$READELF" -l "$JNILIBS/libproot.so" | awk '/LOAD/{print $NF}' | sort -u | tr '\n' ' ')"
echo "libproot.so LOAD align: ${ALIGN} (want 0x10000)"
"$READELF" -h "$JNILIBS/libproot.so" | grep -E 'Class|Machine'
ls -la "$JNILIBS"
echo "完成：proot $(git -C "$TP/proot" describe --tags) + talloc $TALLOC_VER + shmem $SHMEM_TAG"
