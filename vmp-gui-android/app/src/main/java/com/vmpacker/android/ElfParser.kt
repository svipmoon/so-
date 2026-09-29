package com.vmpacker.android

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 轻量级 ELF64 解析器（仅用于列出可保护的函数符号）。
 * 支持 ARM64 (AArch64) 的 .elf / .so 文件。
 */
object ElfParser {

    data class Symbol(
        val name: String,
        val value: Long,
        val size: Long,
        val type: Int,
        val bind: Int,
        val shndx: Int   // section index；SHN_UNDEF(0)=导入符号(无代码)
    )

    class ElfInfo(
        val isElf: Boolean,
        val is64Bit: Boolean,
        val littleEndian: Boolean,
        val machine: Int,
        val symbols: List<Symbol>
    ) {
        // 只返回「本文件内已定义、有实际代码」的函数（排除 SHN_UNDEF 导入符号和空名）
        val funcs: List<Symbol> get() = symbols.filter {
            it.type == STT_FUNC && it.name.isNotBlank() && it.shndx != SHN_UNDEF && it.size > 0
        }
    }

    private const val SHN_UNDEF = 0

    private const val EI_CLASS = 4
    private const val EI_DATA = 5
    private const val ELFCLASS64 = 2
    private const val ELFDATA2LSB = 1

    private const val SHT_SYMTAB = 2
    private const val SHT_DYNSYM = 11

    private const val STT_FUNC = 2
    private const val STB_GLOBAL = 1
    private const val STB_WEAK = 2

    fun parse(file: File): ElfInfo {
        val bytes = file.readBytes()
        if (bytes.size < 64 || bytes[0] != 0x7F.toByte() || bytes[1] != 'E'.code.toByte() ||
            bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()
        ) {
            return ElfInfo(false, false, false, 0, emptyList())
        }

        val is64 = bytes[EI_CLASS] == ELFCLASS64.toByte()
        val le = bytes[EI_DATA] == ELFDATA2LSB.toByte()
        val order = if (le) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN

        val machine = if (is64) {
            ByteBuffer.wrap(bytes, 18, 2).order(order).short.toInt() and 0xFFFF
        } else {
            ByteBuffer.wrap(bytes, 18, 2).order(order).short.toInt() and 0xFFFF
        }

        val symbols = if (is64) parseSymbols64(bytes, order) else parseSymbols32(bytes, order)
        return ElfInfo(true, is64, le, machine, symbols)
    }

    private fun parseSymbols64(bytes: ByteArray, order: ByteOrder): List<Symbol> {
        // ELF64 header: e_shoff @0x28 (8), e_shentsize @0x3A (2), e_shnum @0x3C (2), e_shstrndx @0x3E (2)
        val shOff = ByteBuffer.wrap(bytes, 0x28, 8).order(order).long
        val shEntSize = ByteBuffer.wrap(bytes, 0x3A, 2).order(order).short.toInt() and 0xFFFF
        val shNum = ByteBuffer.wrap(bytes, 0x3C, 2).order(order).short.toInt() and 0xFFFF
        if (shOff <= 0 || shEntSize < 64 || shNum <= 0) return emptyList()

        data class Section(val name: String, val type: Int, val offset: Long, val size: Long, val link: Int)

        val sections = ArrayList<Section>(shNum)
        for (i in 0 until shNum) {
            val base = shOff + i * shEntSize.toLong()
            if (base + 64 > bytes.size) continue
            val nameIdx = ByteBuffer.wrap(bytes, base.toInt(), 4).order(order).int
            val type = ByteBuffer.wrap(bytes, (base + 4).toInt(), 4).order(order).int
            val offset = ByteBuffer.wrap(bytes, (base + 24).toInt(), 8).order(order).long
            val size = ByteBuffer.wrap(bytes, (base + 32).toInt(), 8).order(order).long
            val link = ByteBuffer.wrap(bytes, (base + 40).toInt(), 4).order(order).int
            sections.add(Section("", type, offset, size, link))
            // 用 shstrtab 解析名称（shstrndx 指向的 section）
        }

        // 解析 shstrtab 得到 section 名
        val shStrIdx = ByteBuffer.wrap(bytes, 0x3E, 2).order(order).short.toInt() and 0xFFFF
        if (shStrIdx in sections.indices) {
            val strSec = sections[shStrIdx]
            val nameBytes = bytes.copyOfRange(strSec.offset.toInt(), (strSec.offset + strSec.size).toInt())
            for (i in sections.indices) {
                val base = shOff + i * shEntSize.toLong()
                val nameIdx = ByteBuffer.wrap(bytes, base.toInt(), 4).order(order).int
                sections[i] = sections[i].copy(name = readCString(nameBytes, nameIdx))
            }
        }

        val out = ArrayList<Symbol>()
        for (sec in sections) {
            if (sec.type != SHT_SYMTAB && sec.type != SHT_DYNSYM) continue
            if (sec.offset + sec.size > bytes.size) continue
            val strSec = if (sec.link in sections.indices) sections[sec.link] else null
            val strBytes = strSec?.let {
                if (it.offset + it.size <= bytes.size) bytes.copyOfRange(it.offset.toInt(), (it.offset + it.size).toInt()) else null
            } ?: continue

            val entSize = 24
            val count = (sec.size / entSize).toInt()
            for (j in 0 until count) {
                val p = sec.offset + j * entSize
                val nameIdx = ByteBuffer.wrap(bytes, p.toInt(), 4).order(order).int
                val info = bytes[(p + 4).toInt()]
                val type = info.toInt() and 0x0F
                val bind = (info.toInt() shr 4) and 0x0F
                val shndx = ByteBuffer.wrap(bytes, (p + 6).toInt(), 2).order(order).short.toInt() and 0xFFFF
                val value = ByteBuffer.wrap(bytes, (p + 8).toInt(), 8).order(order).long
                val size = ByteBuffer.wrap(bytes, (p + 16).toInt(), 8).order(order).long
                val name = readCString(strBytes, nameIdx)
                out.add(Symbol(name, value, size, type, bind, shndx))
            }
        }
        return out
    }

    private fun parseSymbols32(bytes: ByteArray, order: ByteOrder): List<Symbol> {
        // ELF32 header: e_shoff @0x20 (4), e_shentsize @0x2E (2), e_shnum @0x30 (2), e_shstrndx @0x32 (2)
        val shOff = ByteBuffer.wrap(bytes, 0x20, 4).order(order).int.toLong()
        val shEntSize = ByteBuffer.wrap(bytes, 0x2E, 2).order(order).short.toInt() and 0xFFFF
        val shNum = ByteBuffer.wrap(bytes, 0x30, 2).order(order).short.toInt() and 0xFFFF
        if (shOff <= 0 || shEntSize < 40 || shNum <= 0) return emptyList()

        data class Section(val name: String, val type: Int, val offset: Long, val size: Long, val link: Int)

        val sections = ArrayList<Section>(shNum)
        for (i in 0 until shNum) {
            val base = shOff + i * shEntSize.toLong()
            if (base + 40 > bytes.size) continue
            val nameIdx = ByteBuffer.wrap(bytes, base.toInt(), 4).order(order).int
            val type = ByteBuffer.wrap(bytes, (base + 4).toInt(), 4).order(order).int
            val offset = ByteBuffer.wrap(bytes, (base + 16).toInt(), 4).order(order).int.toLong()
            val size = ByteBuffer.wrap(bytes, (base + 20).toInt(), 4).order(order).int.toLong()
            val link = ByteBuffer.wrap(bytes, (base + 24).toInt(), 4).order(order).int
            sections.add(Section("", type, offset, size, link))
        }

        val shStrIdx = ByteBuffer.wrap(bytes, 0x32, 2).order(order).short.toInt() and 0xFFFF
        if (shStrIdx in sections.indices) {
            val strSec = sections[shStrIdx]
            val nameBytes = bytes.copyOfRange(strSec.offset.toInt(), (strSec.offset + strSec.size).toInt())
            for (i in sections.indices) {
                val base = shOff + i * shEntSize.toLong()
                val nameIdx = ByteBuffer.wrap(bytes, base.toInt(), 4).order(order).int
                sections[i] = sections[i].copy(name = readCString(nameBytes, nameIdx))
            }
        }

        val out = ArrayList<Symbol>()
        for (sec in sections) {
            if (sec.type != SHT_SYMTAB && sec.type != SHT_DYNSYM) continue
            val strSec = if (sec.link in sections.indices) sections[sec.link] else null
            val strBytes = strSec?.let {
                if (it.offset + it.size <= bytes.size) bytes.copyOfRange(it.offset.toInt(), (it.offset + it.size).toInt()) else null
            } ?: continue

            val entSize = 16
            val count = (sec.size / entSize).toInt()
            for (j in 0 until count) {
                val p = sec.offset + j * entSize
                val nameIdx = ByteBuffer.wrap(bytes, p.toInt(), 4).order(order).int
                val info = bytes[(p + 12).toInt()]
                val type = info.toInt() and 0x0F
                val bind = (info.toInt() shr 4) and 0x0F
                val shndx = ByteBuffer.wrap(bytes, (p + 14).toInt(), 2).order(order).short.toInt() and 0xFFFF
                val value = ByteBuffer.wrap(bytes, (p + 4).toInt(), 4).order(order).int.toLong()
                val size = ByteBuffer.wrap(bytes, (p + 8).toInt(), 4).order(order).int.toLong()
                val name = readCString(strBytes, nameIdx)
                out.add(Symbol(name, value, size, type, bind, shndx))
            }
        }
        return out
    }

    private fun readCString(buf: ByteArray, start: Int): String {
        if (start < 0 || start >= buf.size) return ""
        val sb = StringBuilder()
        var i = start
        while (i < buf.size && buf[i] != 0.toByte()) {
            sb.append((buf[i].toInt() and 0xFF).toChar())
            i++
        }
        return sb.toString()
    }
}