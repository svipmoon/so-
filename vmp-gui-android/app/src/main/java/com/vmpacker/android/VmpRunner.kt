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

    /**
     * 确保 vmpacker 二进制可用（从 assets 释放到 filesDir 并赋予执行权限）。
     * @return 二进制文件的绝对路径
     */
    fun ensureBinary(context: Context): File {
        val dir = File(context.filesDir, "bin").apply { mkdirs() }
        val bin = File(dir, "vmpacker_arm64")

        if (!bin.exists() || bin.length() == 0L) {
            context.assets.open(ASSET_NAME).use { input ->
                FileOutputStream(bin).use { output -> input.copyTo(output) }
            }
        }
        // 赋予执行权限（仅属主可执行）
        bin.setExecutable(true, false)
        bin.setReadable(true, false)
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