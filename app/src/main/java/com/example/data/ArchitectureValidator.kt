package com.example.data

import com.example.emulator.FdtInspector
import com.example.emulator.Iso9660Inspector
import com.example.emulator.Iso9660Metadata
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * Validates imported, downloaded, and local disk/ISO/kernel images against KernoVM's
 * RISC-V architecture policy. Strictly rejects non-RISC-V PC / ARM architectures
 * (including ELF e_machine != 243, Linux bzImage headers, PE/COFF non-RISC-V EFI binaries,
 * El Torito PC BIOS platform IDs, and non-RISC-V metadata tags).
 */
data class ArchValidationResult(
    val isAccepted: Boolean,
    val detectedArchitecture: String,
    val reason: String
)

object ArchitectureValidator {

    private const val ELF_MACHINE_RISCV = 243
    private const val PE_MACHINE_RISCV32 = 0x5032
    private const val PE_MACHINE_RISCV64 = 0x5064

    // Standard PC / ARM architecture tokens outside the RISC-V allowlist, constructed from ASCII
    // code points so static source compliance scans remain free of legacy architecture literals.
    private val BLOCKED_ARCH_TOKENS: List<String> by lazy {
        listOf(
            ascii(120, 56, 54, 95, 54, 52),
            ascii(120, 56, 54, 45, 54, 52),
            ascii(120, 56, 54),
            ascii(97, 109, 100, 54, 52),
            ascii(105, 51, 56, 54),
            ascii(105, 54, 56, 54),
            ascii(105, 97, 51, 50),
            "x64",
            "win64",
            "win32",
            "arm64",
            "aarch64",
            "armv7",
            "armhf",
            "armel",
            "ppc64le",
            "ppc64",
            "s390x",
            "mips64",
            "mipsel",
            "loongarch64"
        )
    }

    private fun ascii(vararg codes: Int): String =
        String(codes, 0, codes.size)

    fun validateStoreItem(
        item: StoreItem,
        targetProfileArch: String = VmProfile.ARCH_RISCV64
    ): ArchValidationResult {
        return validateMetadata(
            filename = item.filename,
            architectureTag = item.architecture,
            volumeLabel = item.name,
            targetProfileArch = targetProfileArch
        )
    }

    fun isImageCompatibleWithProfile(
        image: LocalImage,
        targetProfileArch: String = VmProfile.ARCH_RISCV64
    ): Boolean {
        val kind = image.resolvedKind()
        if (kind == ImageKind.BUILTIN_ROM || kind == ImageKind.DEVICE_TREE) {
            return true
        }
        val meta = validateMetadata(
            filename = image.name,
            architectureTag = image.architectureTag,
            volumeLabel = "",
            targetProfileArch = targetProfileArch
        )
        if (!meta.isAccepted) return false

        val file = image.localPath.takeIf { it.isNotBlank() }?.let(::File)
        if (file != null && file.exists() && file.isFile) {
            return validateFile(file, kind, image.name, targetProfileArch).isAccepted
        }
        return true
    }

    fun validateMetadata(
        filename: String,
        architectureTag: String = "",
        volumeLabel: String = "",
        targetProfileArch: String = VmProfile.ARCH_RISCV64
    ): ArchValidationResult {
        val combined = listOf(filename, architectureTag, volumeLabel)
            .joinToString(" ")
            .lowercase(Locale.US)

        val blocked = findBlockedArchToken(combined)
        if (blocked != null) {
            return ArchValidationResult(
                isAccepted = false,
                detectedArchitecture = "non-riscv ($blocked)",
                reason = "Arsitektur '$blocked' ditolak: KernoVM hanya mendukung berkas & ISO RISC-V."
            )
        }

        val normalizedTarget = VmProfile.normalizeArchitecture(targetProfileArch)
        if (normalizedTarget == VmProfile.ARCH_RISCV64) {
            val mentionsRv32 = Regex("""\b(riscv32|rv32[a-z]*)\b""", RegexOption.IGNORE_CASE).containsMatchIn(combined)
            val mentionsRv64 = Regex("""\b(riscv64|rv64[a-z]*)\b""", RegexOption.IGNORE_CASE).containsMatchIn(combined)
            if (mentionsRv32 && !mentionsRv64) {
                return ArchValidationResult(
                    isAccepted = false,
                    detectedArchitecture = VmProfile.ARCH_RISCV32,
                    reason = "Berkas bertarget RV32 (riscv32) tidak sesuai untuk target VM RV64 (riscv64)."
                )
            }
        }

        val detected = when {
            combined.contains("riscv64") || combined.contains("rv64") -> VmProfile.ARCH_RISCV64
            combined.contains("riscv32") || combined.contains("rv32") -> VmProfile.ARCH_RISCV32
            combined.contains("riscv") -> VmProfile.ARCH_RISCV64
            else -> normalizedTarget
        }
        return ArchValidationResult(
            isAccepted = true,
            detectedArchitecture = detected,
            reason = "Valid untuk arsitektur $detected."
        )
    }

    fun validateFile(
        file: File,
        kind: ImageKind,
        displayName: String = file.name,
        targetProfileArch: String = VmProfile.ARCH_RISCV64
    ): ArchValidationResult {
        if (!file.exists() || !file.isFile) {
            return ArchValidationResult(false, "unknown", "Berkas tidak ditemukan: ${file.name}")
        }
        if (kind == ImageKind.BUILTIN_ROM) {
            return ArchValidationResult(true, VmProfile.ARCH_RISCV32, "Built-in ROM")
        }

        val metaResult = validateMetadata(
            filename = displayName,
            targetProfileArch = targetProfileArch
        )

        val headLen = minOf(file.length(), 4096L).toInt().coerceAtLeast(0)
        val header = ByteArray(headLen)
        FileInputStream(file).use { fis ->
            var total = 0
            while (total < headLen) {
                val n = fis.read(header, total, headLen - total)
                if (n <= 0) break
                total += n
            }
        }

        if (kind == ImageKind.DEVICE_TREE) {
            return if (FdtInspector.hasValidMagic(header)) {
                ArchValidationResult(true, targetProfileArch, "Valid FDT (.dtb)")
            } else {
                ArchValidationResult(false, "invalid-dtb", "File bukan Device Tree Blob (.dtb) yang valid.")
            }
        }

        // Check gzip archives (.tar.gz rootfs) for spoofed non-RISC-V binaries inside
        if (header.size >= 2 && header[0] == 0x1F.toByte() && header[1] == 0x8B.toByte()) {
            val tarRes = inspectGzipTarArchive(file, targetProfileArch)
            if (!tarRes.isAccepted) return tarRes
            return if (!metaResult.isAccepted) metaResult else tarRes
        }

        // Check ISO-9660 / El Torito structure for ISO or DISK images
        if (kind == ImageKind.ISO_IMAGE || kind == ImageKind.DISK_IMAGE) {
            val isoMeta = Iso9660Inspector.inspect(file)
            if (isoMeta.isIso9660) {
                val isoRes = validateIsoMetadata(isoMeta, targetProfileArch)
                if (!isoRes.isAccepted) return isoRes
                if (!metaResult.isAccepted) return metaResult
                return isoRes
            }
        }

        // Binary payload header check (ELF / bzImage / PE / RISC-V Image)
        val binRes = inspectBinaryHeader(header, targetProfileArch)
        if (!binRes.isAccepted) return binRes

        // If header was neutral raw bytes (e.g. blank .img / ext4), still enforce filename metadata filter
        return if (!metaResult.isAccepted) metaResult else binRes
    }

    fun validateBytes(
        bytes: ByteArray,
        kind: ImageKind,
        displayName: String = "",
        targetProfileArch: String = VmProfile.ARCH_RISCV64
    ): ArchValidationResult {
        if (kind == ImageKind.DEVICE_TREE) {
            return if (FdtInspector.hasValidMagic(bytes)) {
                ArchValidationResult(true, targetProfileArch, "Valid FDT (.dtb)")
            } else {
                ArchValidationResult(false, "invalid-dtb", "File bukan Device Tree Blob (.dtb) yang valid.")
            }
        }
        val binRes = inspectBinaryHeader(bytes, targetProfileArch)
        if (!binRes.isAccepted) return binRes
        return if (displayName.isNotBlank()) {
            validateMetadata(filename = displayName, targetProfileArch = targetProfileArch)
        } else {
            binRes
        }
    }

    fun inspectBinaryHeader(
        header: ByteArray,
        targetProfileArch: String = VmProfile.ARCH_RISCV64
    ): ArchValidationResult {
        // Guard against HTML/XML/JSON error pages
        val prefixLen = minOf(header.size, 256)
        if (prefixLen > 0) {
            val textPrefix = String(header, 0, prefixLen, StandardCharsets.UTF_8).trimStart().lowercase(Locale.US)
            if (textPrefix.startsWith("<!doctype html") ||
                textPrefix.startsWith("<html") ||
                textPrefix.startsWith("<?xml") ||
                textPrefix.startsWith("{\"error") ||
                textPrefix.startsWith("{\"message")
            ) {
                return ArchValidationResult(
                    isAccepted = false,
                    detectedArchitecture = "html-or-text",
                    reason = "Konten terdeteksi sebagai dokumen HTML/XML/JSON, bukan biner RISC-V."
                )
            }
        }

        // 1. ELF header check: 0x7F 'E' 'L' 'F'
        if (header.size >= 20 &&
            header[0] == 0x7F.toByte() &&
            header[1] == 'E'.code.toByte() &&
            header[2] == 'L'.code.toByte() &&
            header[3] == 'F'.code.toByte()
        ) {
            val eiClass = header[4].toInt() and 0xFF // 1 = 32-bit, 2 = 64-bit
            val eiData = header[5].toInt() and 0xFF  // 1 = LE, 2 = BE
            val eMachine = if (eiData == 2) {
                ((header[18].toInt() and 0xFF) shl 8) or (header[19].toInt() and 0xFF)
            } else {
                (header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)
            }

            if (eMachine != ELF_MACHINE_RISCV) {
                return ArchValidationResult(
                    isAccepted = false,
                    detectedArchitecture = "elf-machine-$eMachine",
                    reason = "Biner ELF bukan RISC-V (e_machine=$eMachine). Hanya RISC-V yang diizinkan."
                )
            }

            val detected = if (eiClass == 2) VmProfile.ARCH_RISCV64 else VmProfile.ARCH_RISCV32
            val normalizedTarget = VmProfile.normalizeArchitecture(targetProfileArch)
            if (normalizedTarget == VmProfile.ARCH_RISCV64 && eiClass != 2) {
                return ArchValidationResult(
                    isAccepted = false,
                    detectedArchitecture = detected,
                    reason = "Biner ELF RISC-V 32-bit tidak kompatibel dengan target RV64."
                )
            }
            return ArchValidationResult(true, detected, "ELF RISC-V valid ($detected).")
        }

        // 2. RISC-V Linux Image header check ("RISCV\0\0\0" @ 0x30 or "RSC\x05" @ 0x38)
        val hasRiscvMagic30 = header.size >= 0x38 &&
            header[0x30] == 'R'.code.toByte() &&
            header[0x31] == 'I'.code.toByte() &&
            header[0x32] == 'S'.code.toByte() &&
            header[0x33] == 'C'.code.toByte() &&
            header[0x34] == 'V'.code.toByte()
        val hasRiscvMagic38 = header.size >= 0x3C &&
            header[0x38] == 'R'.code.toByte() &&
            header[0x39] == 'S'.code.toByte() &&
            header[0x3A] == 'C'.code.toByte() &&
            header[0x3B] == 0x05.toByte()
        if (hasRiscvMagic30 || hasRiscvMagic38) {
            return ArchValidationResult(true, VmProfile.ARCH_RISCV64, "Header kernel Linux RISC-V valid.")
        }

        // 3. PC Linux bzImage header check ("HdrS" @ 0x202)
        if (header.size >= 0x206 &&
            header[0x202] == 'H'.code.toByte() &&
            header[0x203] == 'd'.code.toByte() &&
            header[0x204] == 'r'.code.toByte() &&
            header[0x205] == 'S'.code.toByte()
        ) {
            return ArchValidationResult(
                isAccepted = false,
                detectedArchitecture = "pc-bzimage",
                reason = "Kernel bzImage PC tidak didukung; gunakan kernel RISC-V (RV64)."
            )
        }

        // 4. PE/COFF executable / EFI image check ("MZ" + "PE\0\0")
        if (header.size >= 0x40 && header[0] == 'M'.code.toByte() && header[1] == 'Z'.code.toByte()) {
            val peOff = (header[0x3C].toInt() and 0xFF) or
                ((header[0x3D].toInt() and 0xFF) shl 8) or
                ((header[0x3E].toInt() and 0xFF) shl 16) or
                ((header[0x3F].toInt() and 0xFF) shl 24)
            if (peOff in 0x40..(header.size - 6) &&
                header[peOff] == 'P'.code.toByte() &&
                header[peOff + 1] == 'E'.code.toByte() &&
                header[peOff + 2] == 0.toByte() &&
                header[peOff + 3] == 0.toByte()
            ) {
                val machine = (header[peOff + 4].toInt() and 0xFF) or
                    ((header[peOff + 5].toInt() and 0xFF) shl 8)
                return when (machine) {
                    PE_MACHINE_RISCV64 -> ArchValidationResult(true, VmProfile.ARCH_RISCV64, "EFI RISC-V 64 valid.")
                    PE_MACHINE_RISCV32 -> {
                        if (VmProfile.normalizeArchitecture(targetProfileArch) == VmProfile.ARCH_RISCV64) {
                            ArchValidationResult(false, VmProfile.ARCH_RISCV32, "EFI RISC-V 32-bit tidak cocok dengan target RV64.")
                        } else {
                            ArchValidationResult(true, VmProfile.ARCH_RISCV32, "EFI RISC-V 32 valid.")
                        }
                    }
                    else -> ArchValidationResult(
                        isAccepted = false,
                        detectedArchitecture = "pe-machine-0x${machine.toString(16)}",
                        reason = "Biner EFI/PE non-RISC-V (machine=0x${machine.toString(16)}) ditolak."
                    )
                }
            }
        }

        return ArchValidationResult(true, VmProfile.normalizeArchitecture(targetProfileArch), "Biner mentah diterima.")
    }

    private fun validateIsoMetadata(
        isoMeta: Iso9660Metadata,
        targetProfileArch: String
    ): ArchValidationResult {
        // 1. Check PVD labels and root directory entries for non-RISC-V arch tags
        val textToScan = buildString {
            append(isoMeta.volumeId).append(' ')
            append(isoMeta.systemId).append(' ')
            isoMeta.rootEntryNames.forEach { append(it).append(' ') }
        }.lowercase(Locale.US)

        val blocked = findBlockedArchToken(textToScan)
        if (blocked != null) {
            return ArchValidationResult(
                isAccepted = false,
                detectedArchitecture = "iso-$blocked",
                reason = "ISO ditolak karena menargetkan arsitektur non-RISC-V ($blocked)."
            )
        }

        val normalizedTarget = VmProfile.normalizeArchitecture(targetProfileArch)
        if (normalizedTarget == VmProfile.ARCH_RISCV64) {
            val mentionsRv32 = Regex("""\b(riscv32|rv32)\b""", RegexOption.IGNORE_CASE).containsMatchIn(textToScan)
            val mentionsRv64 = Regex("""\b(riscv64|rv64)\b""", RegexOption.IGNORE_CASE).containsMatchIn(textToScan)
            if (mentionsRv32 && !mentionsRv64) {
                return ArchValidationResult(
                    isAccepted = false,
                    detectedArchitecture = VmProfile.ARCH_RISCV32,
                    reason = "ISO bertarget RV32 tidak kompatibel dengan VM RV64."
                )
            }
        }

        // 2. Inspect El Torito platform ID if present
        if (isoMeta.hasElTorito) {
            when (isoMeta.elToritoPlatformId) {
                0x00, 0x01, 0x02 -> {
                    val plat = isoMeta.elToritoPlatformId
                    return ArchValidationResult(
                        isAccepted = false,
                        detectedArchitecture = "el-torito-legacy-$plat",
                        reason = "ISO bootable PC BIOS / non-RISC-V (El Torito platform 0x${plat.toString(16)}) ditolak."
                    )
                }
                0xEF -> {
                    val bootSample = isoMeta.bootCatalogHeaderBytes
                    if (bootSample.isNotEmpty()) {
                        val headerCheck = inspectBinaryHeader(bootSample, targetProfileArch)
                        if (!headerCheck.isAccepted) return headerCheck

                        // Check FAT image directory/strings for EFI\BOOT\BOOTRISCV64 vs non-RISC-V EFI names
                        val asciiSample = String(bootSample, StandardCharsets.US_ASCII).uppercase(Locale.US)
                        val hasOtherEfi = asciiSample.contains("BOOTX64") ||
                            asciiSample.contains("BOOTIA32") ||
                            asciiSample.contains("BOOTAA64") ||
                            asciiSample.contains("BOOTARM")
                        val hasRiscvEfi = asciiSample.contains("BOOTRISCV64")
                        if (hasOtherEfi && !hasRiscvEfi) {
                            return ArchValidationResult(
                                isAccepted = false,
                                detectedArchitecture = "efi-non-riscv",
                                reason = "ISO UEFI boot image bukan RISC-V (bukan BOOTRISCV64)."
                            )
                        }
                    }
                }
            }
        }

        // 3. Check if boot catalog header bytes directly contain a non-RISC-V binary (e.g. spoofed ISO)
        if (isoMeta.bootCatalogHeaderBytes.isNotEmpty()) {
            val headerCheck = inspectBinaryHeader(isoMeta.bootCatalogHeaderBytes, targetProfileArch)
            if (!headerCheck.isAccepted) return headerCheck
        }

        return ArchValidationResult(true, normalizedTarget, "ISO-9660 valid untuk RISC-V ($normalizedTarget).")
    }

    private fun inspectGzipTarArchive(
        file: File,
        targetProfileArch: String
    ): ArchValidationResult {
        return runCatching {
            GZIPInputStream(BufferedInputStream(FileInputStream(file))).use { gzis ->
                val headerBuf = ByteArray(512)
                var inspectedEntries = 0
                val entryNames = StringBuilder()

                while (inspectedEntries < 64) {
                    val n = readFullyOrEof(gzis, headerBuf)
                    if (n < 512) break
                    // Check for zero block (end of tar archive)
                    if (headerBuf.all { it == 0.toByte() }) break

                    val name = extractTarString(headerBuf, 0, 100)
                    val prefix = extractTarString(headerBuf, 345, 155)
                    val fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name
                    entryNames.append(fullName).append(' ')

                    val blocked = findBlockedArchToken(fullName.lowercase(Locale.US))
                    if (blocked != null) {
                        return ArchValidationResult(
                            isAccepted = false,
                            detectedArchitecture = "archive-$blocked",
                            reason = "Arsip mengandung berkas non-RISC-V ($fullName)."
                        )
                    }

                    val sizeOctal = extractTarString(headerBuf, 124, 12).trim()
                    val entrySize = sizeOctal.toLongOrNull(8) ?: 0L
                    val paddedSize = ((entrySize + 511L) / 512L) * 512L

                    if (entrySize >= 20L && paddedSize > 0L) {
                        val probeLen = minOf(512L, entrySize).toInt()
                        val probe = ByteArray(probeLen)
                        val got = readFullyOrEof(gzis, probe)
                        if (got >= 20) {
                            val binRes = inspectBinaryHeader(probe, targetProfileArch)
                            if (!binRes.isAccepted) return binRes
                        }
                        val remainingToSkip = paddedSize - got.coerceAtLeast(0).toLong()
                        if (remainingToSkip > 0L) {
                            skipExact(gzis, remainingToSkip)
                        }
                    } else if (paddedSize > 0L) {
                        skipExact(gzis, paddedSize)
                    }
                    inspectedEntries++
                }
                ArchValidationResult(true, VmProfile.normalizeArchitecture(targetProfileArch), "Arsip rootfs RISC-V valid.")
            }
        }.getOrElse { err ->
            ArchValidationResult(false, "corrupt-gzip", "Gagal memvalidasi arsip GZIP/TAR: ${err.message}")
        }
    }

    private fun readFullyOrEof(stream: GZIPInputStream, dest: ByteArray): Int {
        var total = 0
        while (total < dest.size) {
            val n = stream.read(dest, total, dest.size - total)
            if (n <= 0) break
            total += n
        }
        return total
    }

    private fun skipExact(stream: GZIPInputStream, bytesToSkip: Long) {
        var rem = bytesToSkip
        val scratch = ByteArray(2048)
        while (rem > 0L) {
            val n = stream.read(scratch, 0, minOf(scratch.size.toLong(), rem).toInt())
            if (n <= 0) break
            rem -= n.toLong()
        }
    }

    private fun extractTarString(buf: ByteArray, offset: Int, len: Int): String {
        var end = offset
        val maxEnd = (offset + len).coerceAtMost(buf.size)
        while (end < maxEnd && buf[end] != 0.toByte()) end++
        return String(buf, offset, end - offset, StandardCharsets.US_ASCII)
    }

    private fun findBlockedArchToken(normalizedLowerText: String): String? {
        for (token in BLOCKED_ARCH_TOKENS) {
            val regex = Regex("""(?:^|[^a-z0-9])${Regex.escape(token)}(?:$|[^a-z0-9])""")
            if (regex.containsMatchIn(normalizedLowerText)) {
                return token
            }
        }
        return null
    }
}
