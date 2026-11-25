package com.example.e2e

import android.content.Context
import androidx.room.Room
import com.example.data.KernoDatabase
import com.example.emulator.DiagnosticMonitorRom
import com.example.emulator.GuestMemoryBus
import com.example.emulator.Rv32ImaCpuCore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/**
 * Shared deterministic fixtures and helpers for KernoVM opaque-box E2E tests (M1–M4 & Tier 5).
 */
object E2eTestFixtures {

    private val EXCLUDED_DIR_NAMES = setOf(
        ".git",
        ".gradle",
        ".kotlin",
        "build",
        "wrapper"
    )

    private val BINARY_OR_SCRIPT_EXTENSIONS = setOf(
        "ttf",
        "otf",
        "png",
        "jpg",
        "jpeg",
        "webp",
        "jar",
        "jks",
        "keystore",
        "bin",
        "iso",
        "gz",
        "zip"
    )

    private val EXCLUDED_ROOT_FILENAMES = setOf(
        "gradlew",
        "gradlew.bat",
        "local.properties"
    )

    /**
     * Resolves the KernoVM repository root dynamically by walking upward from `user.dir`
     * until both `settings.gradle.kts` and `app/build.gradle.kts` are found.
     */
    fun resolveWorkspaceRoot(): File {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile.normalize()
        var cursor: File? = start
        while (cursor != null) {
            if (File(cursor, "settings.gradle.kts").isFile &&
                File(cursor, "app/build.gradle.kts").isFile
            ) {
                return cursor
            }
            cursor = cursor.parentFile
        }
        error("Unable to locate KernoVM workspace root upward from user.dir=${start.absolutePath}")
    }

    fun mainSourceDir(): File = File(resolveWorkspaceRoot(), "app/src/main")

    fun nonE2eTestSourceFiles(): List<File> {
        val root = resolveWorkspaceRoot()
        val testRoots = listOf(
            File(root, "app/src/test"),
            File(root, "app/src/androidTest")
        )
        return testRoots
            .filter { it.exists() }
            .flatMap { collectTextFilesUnder(it, excludeE2e = true) }
    }

    fun rootProjectAuditFiles(): List<File> {
        val root = resolveWorkspaceRoot()
        val relativePaths = listOf(
            "LICENSE",
            "NOTICE",
            "THIRD_PARTY_LICENSES.md",
            "README.md",
            "metadata.json",
            "build.gradle.kts",
            "settings.gradle.kts",
            "gradle.properties",
            "app/build.gradle.kts",
            "gradle/libs.versions.toml",
            "app/proguard-rules.pro",
            ".github/workflows/build-release.yml",
            ".env.example"
        )
        return relativePaths.map { File(root, it) }.filter { it.isFile }
    }

    fun collectAuditTextFiles(): List<File> {
        val mainFiles = collectTextFilesUnder(mainSourceDir(), excludeE2e = true)
        val nonE2eTestFiles = nonE2eTestSourceFiles()
        val rootFiles = rootProjectAuditFiles()
        return (mainFiles + nonE2eTestFiles + rootFiles)
            .distinctBy { it.canonicalPath }
            .sortedBy { it.canonicalPath }
    }

    fun collectTextFilesUnder(baseDir: File, excludeE2e: Boolean = true): List<File> {
        if (!baseDir.exists()) return emptyList()
        val result = ArrayList<File>()
        fun walk(file: File) {
            val name = file.name
            if (file.isDirectory) {
                if (name in EXCLUDED_DIR_NAMES) return
                val normalized = file.invariantSeparatorsPath
                if (normalized.endsWith("/app/build") || normalized.endsWith("/gradle/wrapper")) return
                if (excludeE2e && (normalized.endsWith("/com/example/e2e") || normalized.contains("/com/example/e2e/"))) {
                    return
                }
                file.listFiles()?.forEach { walk(it) }
            } else if (file.isFile) {
                if (name in EXCLUDED_ROOT_FILENAMES) return
                val ext = file.extension.lowercase()
                if (ext in BINARY_OR_SCRIPT_EXTENSIONS) return
                val normalized = file.invariantSeparatorsPath
                if (excludeE2e && normalized.contains("/com/example/e2e/")) return
                result.add(file)
            }
        }
        walk(baseDir)
        return result.sortedBy { it.canonicalPath }
    }

    fun createInMemoryDatabase(context: Context): KernoDatabase =
        Room.inMemoryDatabaseBuilder(context, KernoDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    fun createMockHttpClient(handler: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain -> handler(chain.request()) }
            .build()

    fun mockResponse(
        request: Request,
        code: Int = 200,
        body: ByteArray = ByteArray(0),
        contentType: String = "application/octet-stream"
    ): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "HTTP $code")
            .body(body.toResponseBody(contentType.toMediaType()))
            .build()

    fun createTempBackingFile(
        tempFolder: TemporaryFolder,
        name: String,
        sizeBytes: Int = 4096,
        fill: Byte = 0
    ): File {
        val file = tempFolder.newFile(name)
        file.writeBytes(ByteArray(sizeBytes) { fill })
        return file
    }

    /**
     * Generates real RV32I machine code that reads 32-bit words from each `(mmioAddr, ramOutOffset)`
     * pair via `LW`, stores them into RAM at `RAM_BASE + ramOutOffset`, and powers off via SYSCON.
     */
    fun assembleMmioReadAndPoweroff(mmioReads: List<Pair<Int, Int>>): ByteArray {
        val asm = DiagnosticMonitorRom.Rv32Assembler(Rv32ImaCpuCore.RAM_BASE)
        with(asm) {
            val t0 = DiagnosticMonitorRom.Rv32Assembler.T0
            val t1 = DiagnosticMonitorRom.Rv32Assembler.T1
            val t2 = DiagnosticMonitorRom.Rv32Assembler.T2
            for ((mmioAddr, ramOutOffset) in mmioReads) {
                li(t0, mmioAddr)
                lw(t1, 0, t0)
                li(t2, Rv32ImaCpuCore.RAM_BASE + ramOutOffset)
                sw(t1, 0, t2)
            }
            li(t0, Rv32ImaCpuCore.SYSCON_BASE)
            li(t1, 0x5555)
            sw(t1, 0, t0)
            label("halt")
            wfi()
            j("halt")
        }
        return asm.build()
    }

    /**
     * Generates real RV32I machine code that performs subword (`LBU` or `LHU`) MMIO loads
     * and stores each result as a 32-bit word into `RAM_BASE + ramOutOffset`, then powers off.
     */
    fun assembleSubwordMmioReadAndPoweroff(
        reads: List<Triple<Int, Int, Int>> // (mmioAddr, funct3 [4=LBU, 5=LHU], ramOutOffset)
    ): ByteArray {
        val asm = DiagnosticMonitorRom.Rv32Assembler(Rv32ImaCpuCore.RAM_BASE)
        with(asm) {
            val t0 = DiagnosticMonitorRom.Rv32Assembler.T0
            val t1 = DiagnosticMonitorRom.Rv32Assembler.T1
            val t2 = DiagnosticMonitorRom.Rv32Assembler.T2
            for ((mmioAddr, funct3, ramOutOffset) in reads) {
                li(t0, mmioAddr)
                emit(((0 and 0xFFF) shl 20) or (t0 shl 15) or ((funct3 and 0x7) shl 12) or (t1 shl 7) or 0x03)
                li(t2, Rv32ImaCpuCore.RAM_BASE + ramOutOffset)
                sw(t1, 0, t2)
            }
            li(t0, Rv32ImaCpuCore.SYSCON_BASE)
            li(t1, 0x5555)
            sw(t1, 0, t0)
            label("halt")
            wfi()
            j("halt")
        }
        return asm.build()
    }

    /**
     * Generates real RV32I machine code that writes `(mmioAddr, value)` pairs via `SW`,
     * optionally reads back `(mmioAddr, ramOutOffset)` via `LW`, and powers off via SYSCON.
     */
    fun assembleMmioWriteAndPoweroff(
        writes: List<Pair<Int, Int>>,
        readBacks: List<Pair<Int, Int>> = emptyList()
    ): ByteArray {
        val asm = DiagnosticMonitorRom.Rv32Assembler(Rv32ImaCpuCore.RAM_BASE)
        with(asm) {
            val t0 = DiagnosticMonitorRom.Rv32Assembler.T0
            val t1 = DiagnosticMonitorRom.Rv32Assembler.T1
            val t2 = DiagnosticMonitorRom.Rv32Assembler.T2
            for ((mmioAddr, value) in writes) {
                li(t0, mmioAddr)
                li(t1, value)
                sw(t1, 0, t0)
            }
            for ((mmioAddr, ramOutOffset) in readBacks) {
                li(t0, mmioAddr)
                lw(t1, 0, t0)
                li(t2, Rv32ImaCpuCore.RAM_BASE + ramOutOffset)
                sw(t1, 0, t2)
            }
            li(t0, Rv32ImaCpuCore.SYSCON_BASE)
            li(t1, 0x5555)
            sw(t1, 0, t0)
            label("halt")
            wfi()
            j("halt")
        }
        return asm.build()
    }

    /**
     * Creates a minimal valid ISO-9660 byte image with Primary Volume Descriptor at sector 16.
     */
    fun createMinimalIsoBytes(
        volumeLabel: String,
        volumeBlocks: Int = 32,
        systemId: String = "LINUX"
    ): ByteArray {
        val sectorSize = 2048
        val totalBlocks = volumeBlocks.coerceAtLeast(24)
        val bytes = ByteArray(totalBlocks * sectorSize)
        val pvdOffset = 16 * sectorSize
        bytes[pvdOffset] = 1
        "CD001".toByteArray(StandardCharsets.US_ASCII).copyInto(bytes, pvdOffset + 1)
        bytes[pvdOffset + 6] = 1

        systemId.padEnd(32, ' ').take(32).toByteArray(StandardCharsets.US_ASCII)
            .copyInto(bytes, pvdOffset + 8)
        volumeLabel.padEnd(32, ' ').take(32).toByteArray(StandardCharsets.US_ASCII)
            .copyInto(bytes, pvdOffset + 40)

        bytes[pvdOffset + 80] = (totalBlocks and 0xFF).toByte()
        bytes[pvdOffset + 81] = ((totalBlocks ushr 8) and 0xFF).toByte()
        bytes[pvdOffset + 82] = ((totalBlocks ushr 16) and 0xFF).toByte()
        bytes[pvdOffset + 83] = ((totalBlocks ushr 24) and 0xFF).toByte()
        bytes[pvdOffset + 84] = ((totalBlocks ushr 24) and 0xFF).toByte()
        bytes[pvdOffset + 85] = ((totalBlocks ushr 16) and 0xFF).toByte()
        bytes[pvdOffset + 86] = ((totalBlocks ushr 8) and 0xFF).toByte()
        bytes[pvdOffset + 87] = (totalBlocks and 0xFF).toByte()

        bytes[pvdOffset + 128] = 0x00
        bytes[pvdOffset + 129] = 0x08
        bytes[pvdOffset + 130] = 0x08
        bytes[pvdOffset + 131] = 0x00

        // Volume Set Terminator at sector 17
        val termOffset = 17 * sectorSize
        bytes[termOffset] = 0xFF.toByte()
        "CD001".toByteArray(StandardCharsets.US_ASCII).copyInto(bytes, termOffset + 1)
        bytes[termOffset + 6] = 1
        return bytes
    }

    /**
     * Builds a synthetic ISO-9660 image containing an El Torito Boot Record (sector 17),
     * Validation Entry (sector 18) with [platformId] (`0x00` = 80x86 BIOS, `0xEF` = UEFI),
     * and an optional boot image payload at sector 19.
     */
    fun createElToritoIsoBytes(
        volumeLabel: String,
        platformId: Int,
        bootImagePayload: ByteArray = ByteArray(512),
        systemId: String = "LINUX"
    ): ByteArray {
        val sectorSize = 2048
        val bytes = createMinimalIsoBytes(volumeLabel, volumeBlocks = 24, systemId = systemId)

        // Sector 17: El Torito Boot Record Volume Descriptor
        val brOff = 17 * sectorSize
        bytes.fill(0, brOff, brOff + sectorSize)
        bytes[brOff] = 0x00
        "CD001".toByteArray(StandardCharsets.US_ASCII).copyInto(bytes, brOff + 1)
        bytes[brOff + 6] = 1
        "EL TORITO SPECIFICATION".toByteArray(StandardCharsets.US_ASCII).copyInto(bytes, brOff + 7)
        // Boot catalog LBA = 18
        bytes[brOff + 71] = 18
        bytes[brOff + 72] = 0
        bytes[brOff + 73] = 0
        bytes[brOff + 74] = 0

        // Sector 18: Boot Catalog (Validation Entry + Initial/Default Entry)
        val catOff = 18 * sectorSize
        bytes[catOff] = 0x01 // Header ID = Validation Entry
        bytes[catOff + 1] = (platformId and 0xFF).toByte()
        bytes[catOff + 30] = 0x55
        bytes[catOff + 31] = 0xAA.toByte()

        // Default Entry at catOff + 32 -> points to boot image sector 19
        bytes[catOff + 32] = 0x88.toByte() // Bootable
        bytes[catOff + 32 + 8] = 19

        // Sector 19: Boot image payload
        val imgOff = 19 * sectorSize
        val toCopy = minOf(bootImagePayload.size, bytes.size - imgOff)
        bootImagePayload.copyInto(bytes, imgOff, 0, toCopy)

        // Move Volume Set Terminator to sector 20
        val termOff = 20 * sectorSize
        bytes[termOff] = 0xFF.toByte()
        "CD001".toByteArray(StandardCharsets.US_ASCII).copyInto(bytes, termOff + 1)
        bytes[termOff + 6] = 1
        return bytes
    }

    fun createElfBytes(eiClass: Int, eMachine: Int, totalSize: Int = 128): ByteArray {
        val bytes = ByteArray(totalSize.coerceAtLeast(64))
        bytes[0] = 0x7F
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = eiClass.toByte() // 1 = ELF32, 2 = ELF64
        bytes[5] = 1 // Little-endian
        bytes[6] = 1 // EV_CURRENT
        bytes[16] = 2 // ET_EXEC
        bytes[18] = (eMachine and 0xFF).toByte()
        bytes[19] = ((eMachine ushr 8) and 0xFF).toByte()
        return bytes
    }

    fun createRiscvLinuxImageHeader(size: Int = 256, useRsc5Magic: Boolean = false): ByteArray {
        val bytes = ByteArray(size.coerceAtLeast(64))
        bytes[0] = 'M'.code.toByte()
        bytes[1] = 'Z'.code.toByte()
        if (useRsc5Magic) {
            "RSC".toByteArray(StandardCharsets.US_ASCII).copyInto(bytes, 0x38)
            bytes[0x3B] = 0x05
        } else {
            "RISCV\u0000\u0000\u0000".toByteArray(StandardCharsets.US_ASCII).copyInto(bytes, 0x30)
        }
        return bytes
    }

    fun createPcBzImageBytes(size: Int = 1024): ByteArray {
        val bytes = ByteArray(size.coerceAtLeast(0x210))
        bytes[0] = 'M'.code.toByte()
        bytes[1] = 'Z'.code.toByte()
        "HdrS".toByteArray(StandardCharsets.US_ASCII).copyInto(bytes, 0x202)
        return bytes
    }

    fun createPeCoffBytes(machineId: Int, size: Int = 512): ByteArray {
        val bytes = ByteArray(size.coerceAtLeast(0x100))
        bytes[0] = 'M'.code.toByte()
        bytes[1] = 'Z'.code.toByte()
        val peOff = 0x80
        bytes[0x3C] = peOff.toByte()
        bytes[peOff] = 'P'.code.toByte()
        bytes[peOff + 1] = 'E'.code.toByte()
        bytes[peOff + 4] = (machineId and 0xFF).toByte()
        bytes[peOff + 5] = ((machineId ushr 8) and 0xFF).toByte()
        return bytes
    }

    fun createTarGzBytes(entries: List<Pair<String, ByteArray>>): ByteArray {
        val tarOut = ByteArrayOutputStream()
        for ((name, content) in entries) {
            val header = ByteArray(512)
            val nameBytes = name.toByteArray(StandardCharsets.US_ASCII)
            nameBytes.copyInto(header, 0, 0, minOf(nameBytes.size, 99))
            val modeStr = "0000755\u0000".toByteArray(StandardCharsets.US_ASCII)
            modeStr.copyInto(header, 100)
            val sizeStr = "%011o\u0000".format(content.size).toByteArray(StandardCharsets.US_ASCII)
            sizeStr.copyInto(header, 124)
            header[156] = '0'.code.toByte() // regular file
            "ustar\u000000".toByteArray(StandardCharsets.US_ASCII).copyInto(header, 257)
            // Compute tar header checksum
            header.fill(' '.code.toByte(), 148, 156)
            var sum = 0
            for (b in header) sum += (b.toInt() and 0xFF)
            "%06o\u0000 ".format(sum).toByteArray(StandardCharsets.US_ASCII).copyInto(header, 148)
            tarOut.write(header)
            tarOut.write(content)
            val rem = content.size % 512
            if (rem != 0) {
                tarOut.write(ByteArray(512 - rem))
            }
        }
        // Two 512-byte EOF blocks
        tarOut.write(ByteArray(1024))

        val gzOut = ByteArrayOutputStream()
        GZIPOutputStream(gzOut).use { gz ->
            gz.write(tarOut.toByteArray())
        }
        return gzOut.toByteArray()
    }

    /**
     * Byte-addressed 64-bit [GuestMemoryBus] backed by an arbitrary [ramBaseAddress] (including > 4 GiB)
     * for opaque-box testing high 64-bit VirtIO descriptor & queue addresses.
     */
    class HighAddressGuestMemoryBus(
        override val ramBaseAddress: Long,
        sizeBytes: Int = 64 * 1024
    ) : GuestMemoryBus {
        private val mem = ByteArray(sizeBytes)
        override val ramSizeBytes: Long = mem.size.toLong()

        override fun readPhysicalByte(addr: Long): Int {
            val off = addr - ramBaseAddress
            return if (off in 0L until ramSizeBytes) (mem[off.toInt()].toInt() and 0xFF) else 0
        }

        override fun readPhysicalWord(addr: Long): Int {
            return readPhysicalByte(addr) or
                (readPhysicalByte(addr + 1L) shl 8) or
                (readPhysicalByte(addr + 2L) shl 16) or
                (readPhysicalByte(addr + 3L) shl 24)
        }

        override fun writePhysicalByte(addr: Long, value: Int) {
            val off = addr - ramBaseAddress
            if (off in 0L until ramSizeBytes) {
                mem[off.toInt()] = (value and 0xFF).toByte()
            }
        }

        fun writeBytes(addr: Long, src: ByteArray) {
            for (i in src.indices) {
                writePhysicalByte(addr + i.toLong(), src[i].toInt() and 0xFF)
            }
        }
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
