package com.vmpacker.android

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * VMPacker 引擎封装：
 * 1. 从 assets 释放编译好的 vmpacker_arm64 可执行文件到应用私有目录
 * 2. 通过 ProcessBuilder 执行命令行保护
 */
object VmpRunner {

    const val ASSET_NAME = "vmpacker_arm64"
    const val NATIVE_LIB_NAME = "libvmpacker_arm64.so"

    /**
     * 确保 vmpacker 二进制可用。
     *
     * 优先使用 native lib 目录（APK lib/arm64-v8a/，系统以只读+可执行方式提取，
     * 符合 Android 10+ 的 W^X 安全策略，可直接执行）；
     * 若不存在则回退到 assets 释放到 filesDir（部分设备允许）。
     *
     * @return 二进制文件的绝对路径
     * @throws IllegalStateException 当 APK 内不存在引擎文件时
     */
    fun ensureBinary(context: Context): File {
        // 方案1（推荐）：nativeLibraryDir —— 只读 + 可执行，天然满足 W^X
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val nativeBin = File(nativeDir, NATIVE_LIB_NAME)
        if (nativeBin.exists() && nativeBin.length() > 0L) {
            return nativeBin
        }

        // 方案2（备选）：assets → filesDir + chmod
        val assetNames = context.assets.list("") ?: emptyArray()
        if (ASSET_NAME !in assetNames) {
            throw IllegalStateException(
                "APK 内未找到引擎文件 ('$NATIVE_LIB_NAME' / '$ASSET_NAME')。\n" +
                "请从 GitHub Actions 下载 CI 构建的最新 APK（已内置引擎），\n" +
                "或本地构建前先执行: bash scripts/build_stub_linux.sh 并编译 vmpacker_arm64"
            )
        }
        val dir = File(context.filesDir, "bin").apply { mkdirs() }
        val bin = File(dir, "vmpacker_arm64")
        if (!bin.exists() || bin.length() == 0L) {
            context.assets.open(ASSET_NAME).use { input ->
                FileOutputStream(bin).use { output -> input.copyTo(output) }
            }
        }
        // Android 10+ (W^X)：可执行文件不应同时可写
        bin.setReadable(true, false)
        bin.setWritable(false, false)
        bin.setExecutable(true, false)
        return bin
    }

    /**
     * 执行 vmpacker。
     * @param bin vmpacker 二进制
     * @param args 命令行参数（不含程序名）
     * @param onLine 实时输出回调（子线程调用）
     * @return 进程退出码
     */
    fun run(bin: File, args: List<String>, onLine: (String) -> Unit): Int {
        val cmd = ArrayList<String>(args.size + 1)
        cmd.add(bin.absolutePath)
        cmd.addAll(args)

        val pb = ProcessBuilder(cmd)
        pb.redirectErrorStream(true) // stdout + stderr 合并
        val process = pb.start()

        process.inputStream.bufferedReader().use { reader ->
            reader.forEachLine { line -> onLine(line) }
        }
        return process.waitFor()
    }
}