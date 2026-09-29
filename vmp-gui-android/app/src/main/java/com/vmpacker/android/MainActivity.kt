package com.vmpacker.android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var btnPickFile: Button
    private lateinit var tvFileInfo: TextView
    private lateinit var etFuncs: EditText
    private lateinit var etAddr: EditText
    private lateinit var cbToken: CheckBox
    private lateinit var cbStrip: CheckBox
    private lateinit var cbVerbose: CheckBox
    private lateinit var cbDebug: CheckBox
    private lateinit var btnProtect: Button
    private lateinit var tvLog: TextView
    private lateinit var btnSaveOutput: Button

    private var selectedUri: Uri? = null
    private var selectedDisplayName: String = ""
    private var inputCacheFile: File? = null
    private var outputFile: File? = null
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnPickFile = findViewById(R.id.btnPickFile)
        tvFileInfo = findViewById(R.id.tvFileInfo)
        etFuncs = findViewById(R.id.etFuncs)
        etAddr = findViewById(R.id.etAddr)
        cbToken = findViewById(R.id.cbToken)
        cbStrip = findViewById(R.id.cbStrip)
        cbVerbose = findViewById(R.id.cbVerbose)
        cbDebug = findViewById(R.id.cbDebug)
        btnProtect = findViewById(R.id.btnProtect)
        tvLog = findViewById(R.id.tvLog)
        btnSaveOutput = findViewById(R.id.btnSaveOutput)

        btnPickFile.setOnClickListener { openFilePicker() }
        findViewById<Button>(R.id.btnListFuncs).setOnClickListener { fillAllFuncs() }
        btnProtect.setOnClickListener { startProtect() }
        btnSaveOutput.setOnClickListener { exportOutput() }
    }

    // ---------- 文件选择 ----------
    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/octet-stream",
                "application/x-executable",
                "application/x-sharedlib"
            ))
        }
        startActivityForResult(intent, REQ_PICK)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_PICK -> if (resultCode == RESULT_OK && data?.data != null) {
                handlePicked(data.data!!)
            }
            REQ_CREATE -> if (resultCode == RESULT_OK && data?.data != null) {
                writeToUri(data.data!!)
            }
        }
    }

    private fun handlePicked(uri: Uri) {
        selectedUri = uri
        selectedDisplayName = queryDisplayName(uri)

        // 拷贝到缓存目录供命令行工具读取
        val cacheDir = File(cacheDir, "input").apply { mkdirs() }
        val ext = if (selectedDisplayName.endsWith(".so")) ".so" else ".elf"
        val cacheFile = File(cacheDir, "input_${System.currentTimeMillis()}$ext")
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(cacheFile).use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            log("❌ 读取文件失败: ${e.message}")
            return
        }
        inputCacheFile = cacheFile

        // 解析 ELF
        val info = ElfParser.parse(cacheFile)
        if (!info.isElf) {
            tvFileInfo.text = "⚠️ 不是有效 ELF 文件: $selectedDisplayName"
            log("⚠️ 所选文件不是 ELF 格式")
            return
        }
        val arch = when (info.machine) {
            183 -> "AArch64 (ARM64)"
            40 -> "ARM"
            62 -> "x86-64"
            3 -> "x86"
            else -> "0x${info.machine.toString(16)}"
        }
        val funcCount = info.funcs.size
        tvFileInfo.text = "📄 $selectedDisplayName\n" +
                "架构: $arch | ${if (info.is64Bit) "64位" else "32位"} | " +
                "符号: ${info.symbols.size} | 函数: $funcCount"
        log("✅ 已加载: $selectedDisplayName ($arch)")
        log("   共发现 ${funcCount} 个函数符号")
        btnProtect.isEnabled = true
    }

    private fun queryDisplayName(uri: Uri): String {
        var name = "input.elf"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(idx) ?: name
            }
        }
        return name
    }

    // ---------- 函数列表 ----------
    private fun fillAllFuncs() {
        val cache = inputCacheFile ?: run {
            toast("请先选择文件"); return
        }
        val info = ElfParser.parse(cache)
        val funcs = info.funcs
        if (funcs.isEmpty()) {
            toast("未找到函数符号（文件可能已被 strip）")
            return
        }
        // 过滤掉常见内部符号，保留业务函数
        val names = funcs.map { it.name }
            .filter { !it.startsWith(".") && !it.startsWith("_") && it != "main" && it != "start" }
        val text = names.joinToString(",")
        etFuncs.setText(text)
        log("📋 已填入 ${names.size} 个函数名")
    }

    // ---------- 执行保护 ----------
    private fun startProtect() {
        if (running) return
        val bin = try {
            VmpRunner.ensureBinary(this)
        } catch (e: Exception) {
            log("❌ 释放 vmpacker 二进制失败: ${e.message}")
            return
        }
        val input = inputCacheFile ?: run { toast("请先选择文件"); return }
        val funcs = etFuncs.text.toString().trim()
        val addr = etAddr.text.toString().trim()
        if (funcs.isEmpty() && addr.isEmpty()) {
            toast("请填写函数名或地址范围")
            return
        }

        // 输出文件
        val outDir = File(getExternalFilesDir(null), "protected").apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outName = selectedDisplayName.substringBeforeLast('.') + "_vmp_$ts.so"
        outputFile = File(outDir, outName)

        // 构造参数
        val args = ArrayList<String>()
        if (funcs.isNotEmpty()) {
            args.add("-func"); args.add(funcs)
        }
        if (addr.isNotEmpty()) {
            args.add("-addr"); args.add(addr)
        }
        if (cbToken.isChecked) args.add("-token=true") else args.add("-token=false")
        if (cbStrip.isChecked) args.add("-strip=true") else args.add("-strip=false")
        if (cbVerbose.isChecked) args.add("-v")
        if (cbDebug.isChecked) args.add("-debug")
        args.add("-o"); args.add(outputFile!!.absolutePath)
        args.add(input.absolutePath)

        log("🚀 开始保护...")
        log("   命令: vmpacker ${args.joinToString(" ")}")
        running = true
        btnProtect.isEnabled = false

        thread {
            val code = try {
                VmpRunner.run(bin, args) { line -> runOnUiThread { log(line) } }
            } catch (e: Exception) {
                runOnUiThread { log("❌ 执行失败: ${e.message}") }
                -1
            } finally {
                running = false
                runOnUiThread { btnProtect.isEnabled = true }
            }

            runOnUiThread {
                if (code == 0 && outputFile?.exists() == true) {
                    log("✅ 保护完成！")
                    log("   输出: ${outputFile!!.absolutePath}")
                    log("   大小: ${outputFile!!.length()} 字节")
                    btnSaveOutput.isEnabled = true
                    toast("VMP 保护完成")
                } else {
                    log("❌ 保护失败，退出码: $code")
                    btnSaveOutput.isEnabled = false
                }
            }
        }
    }

    // ---------- 导出 ----------
    private fun exportOutput() {
        val out = outputFile ?: run { toast("没有可导出的文件"); return }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, out.name)
        }
        startActivityForResult(intent, REQ_CREATE)
    }

    private fun writeToUri(uri: Uri) {
        val out = outputFile ?: return
        try {
            contentResolver.openOutputStream(uri)?.use { os ->
                out.inputStream().use { it.copyTo(os) }
            }
            log("💾 已导出到: $uri")
            toast("导出成功")
        } catch (e: Exception) {
            log("❌ 导出失败: ${e.message}")
        }
    }

    // ---------- 工具 ----------
    private fun log(msg: String) {
        tvLog.append(msg + "\n")
        // 自动滚动到底部
        tvLog.post {
            (tvLog.parent as? ScrollView)?.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQ_PICK = 1001
        private const val REQ_CREATE = 1002
    }
}