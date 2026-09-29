# VMPacker Android (vmp-gui-android)

VMPacker 的 **Android 端图形界面**：安装 APK 后，在手机上直接选择 ARM64 ELF/.so 文件，
勾选要保护的函数，一键完成 VMP 虚拟化保护，并导出加密后的文件。

> 无需 root。App 内置的 `vmpacker_arm64` 静态二进制由 CI 自动交叉编译并打包。

## ✨ 功能

- 📂 **SAF 文件选择**：通过系统文件选择器选取 `.so` / `.elf` / 可执行文件
- 🔍 **ELF 符号解析**：App 内置轻量 ELF 解析器，自动列出文件中的函数符号
- 🎯 **按函数保护**：填写函数名（逗号分隔），或一键填入全部函数
- 📐 **按地址保护**：支持 `0xSTART-0xEND[:名称]` 地址范围
- ⚙️ **保护选项**：Token 入口 / 清除符号表 / 详细输出 / 调试映射
- 📋 **实时日志**：等宽字体终端式日志，实时显示 vmpacker 输出
- 💾 **导出**：保护结果可保存到任意位置（系统 SAF）

## 🏗️ 架构

```
┌─ Android App (Kotlin) ────────────────────────────┐
│ MainActivity (UI)                                 │
│   ├─ ElfParser.kt      ← ELF 符号解析（函数列表）  │
│   └─ VmpRunner.kt      ← 释放 & 执行 vmpacker     │
│                        ↓ 调用                     │
│  assets/vmpacker_arm64 (Go 静态编译的 ARM64 二进制)│
│                        ↓ 输出                     │
│  保护后的 .so/.elf → 导出到用户指定位置            │
└──────────────────────────────────────────────────┘
```

## 🚀 构建方式（推荐：GitHub Actions 一键出包）

项目根目录已配置 `.github/workflows/build-android.yml`，
**push 到 GitHub 后自动完成以下流水线**：

1. 安装 `gcc-aarch64-linux-gnu` 交叉工具链
2. `scripts/build_stub_linux.sh` 编译 VM 解释器 stub → `vm_interp.bin`
3. `GOOS=linux GOARCH=arm64 go build` 编译 `vmpacker_arm64`
4. 拷贝进 `app/assets/` 并执行 `gradle assembleDebug`
5. 产出可直接安装的 **APK**（Actions → Artifacts 下载）

触发方式：
- push 到 `master` / `main`（涉及 cmd/pkg/stub/vmp-gui-android 变更时）
- 或手动在 Actions 页面点击 **Run workflow**

## 🛠️ 本地构建（Linux / Termux）

```bash
# 1. 编译 stub（需 aarch64-linux-gnu-gcc）
bash scripts/build_stub_linux.sh

# 2. 编译 ARM64 vmpacker
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -o vmp-gui-android/app/src/main/assets/vmpacker_arm64 ./cmd/vmpacker/

# 3. 构建 APK（需 JDK17 + Gradle8.7 + Android SDK）
cd vmp-gui-android
gradle assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

> 在 Android Studio 中直接打开 `vmp-gui-android/` 目录亦可（会自动生成 wrapper）。

## 📱 使用步骤

1. 安装 APK 并打开「VMPacker」
2. 点 **① 选择 ELF / SO 文件**，选择要保护的 ARM64 文件
3. 点 **列出文件内全部函数** 自动填入函数名（或手动填写）
4. 按需调整保护选项
5. 点 **🚀 开始 VMP 保护**，等待日志输出
6. 点 **💾 导出保护后的文件**，选择保存位置

## ⚠️ 注意事项

- 仅支持 **ARM64 (AArch64) ELF** 格式；32 位 ARM ELF 会提示但不受保护
- 若文件已被 strip（无符号表），请改用地址范围保护
- 保护后的文件**请先充分测试**再上线，虚拟化会带来性能开销
- 请仅用于保护自己拥有合法权利的软件

## 📁 目录结构

```
vmp-gui-android/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/            ← vmpacker_arm64（CI 构建时自动填充，必在 src/main/assets/ 下才会打包）
│       ├── java/com/vmpacker/android/
│       │   ├── MainActivity.kt
│       │   ├── ElfParser.kt
│       │   └── VmpRunner.kt
│       └── res/layout/activity_main.xml
```
