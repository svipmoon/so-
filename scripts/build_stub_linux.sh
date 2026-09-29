#!/usr/bin/env bash
# ============================================================
# build_stub_linux.sh — Linux 版 VM 解释器 stub 编译脚本
# 替代原 Makefile 中 Windows/PowerShell 逻辑，用于 CI 与 Linux 主机
#
# 产物: cmd/vmpacker/vm_interp.bin (被 Go embed 进 vmpacker)
# ============================================================
set -euo pipefail

CROSS="${CROSS:-aarch64-linux-gnu-}"
STUB_DIR="stub/linux/arm64"
BUILD_DIR="build"
STUB_O="$BUILD_DIR/stub/vm_interp.o"
STUB_ELF="$BUILD_DIR/stub/vm_interp.elf"
RAW_BIN="$BUILD_DIR/vm_interp_raw.bin"
OUT_BIN="cmd/vmpacker/vm_interp.bin"

echo "[*] 编译 VM 解释器 stub ..."
mkdir -p "$BUILD_DIR/stub"

# 1. 编译 C stub（必须 -mcmodel=tiny，禁止 -fPIC）
"${CROSS}gcc" -c -Os -mcmodel=tiny -fno-stack-protector \
    -fno-builtin -nostdlib -march=armv8-a \
    -DVM_INDIRECT_DISPATCH -DVM_FUNC_SPLIT -DVM_TOKEN_ENTRY \
    "$STUB_DIR/vm_interp.c" -o "$STUB_O"

# 2. 链接
"${CROSS}ld" -T "$STUB_DIR/vm_interp.lds" -o "$STUB_ELF" "$STUB_O"

# 3. 提取入口符号地址
VM_ENTRY=$("${CROSS}nm" "$STUB_ELF" | awk '/ vm_entry$/{print $1; exit}')
VM_ENTRY_TOKEN=$("${CROSS}nm" "$STUB_ELF" | awk '/ vm_entry_token$/{print $1; exit}')
TOKEN_TABLE=$("${CROSS}nm" "$STUB_ELF" | awk '/ _token_table_va$/{print $1; exit}')

if [[ -z "$VM_ENTRY" || -z "$VM_ENTRY_TOKEN" || -z "$TOKEN_TABLE" ]]; then
    echo "[!] 错误: 无法在 stub ELF 中找到入口符号" >&2
    exit 1
fi
echo "    vm_entry=0x$VM_ENTRY  vm_entry_token=0x$VM_ENTRY_TOKEN  _token_table_va=0x$TOKEN_TABLE"

# 4. 生成 raw binary
"${CROSS}objcopy" -O binary "$STUB_ELF" "$RAW_BIN"

# 5. 用 Python 拼接 3x uint64 LE 头 + raw
python3 - "$VM_ENTRY" "$VM_ENTRY_TOKEN" "$TOKEN_TABLE" "$RAW_BIN" "$OUT_BIN" <<'PYEOF'
import struct, sys

vm_entry = int(sys.argv[1], 16)
vm_entry_token = int(sys.argv[2], 16)
token_table = int(sys.argv[3], 16)

with open(sys.argv[4], "rb") as f:
    raw = f.read()

header = struct.pack("<QQQ", vm_entry, vm_entry_token, token_table)
blob = header + raw

with open(sys.argv[5], "wb") as f:
    f.write(blob)

print(f"[+] {sys.argv[5]}: {len(blob)} bytes (vm_entry=0x{vm_entry:X} "
      f"vm_entry_token=0x{vm_entry_token:X} _token_table_va=0x{token_table:X})")
PYEOF

echo "[+] stub 编译完成: $OUT_BIN"