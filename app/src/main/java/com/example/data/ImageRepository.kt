package com.example.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.emulator.DiagnosticMonitorRom
import com.example.emulator.FdtInspector
import com.example.emulator.Iso9660Inspector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

data class TransferProgress(
    val label: String,
    val bytesTransferred: Long,
    val totalBytes: Long // -1 if unknown
) {
    val fraction: Float?
        get() = if (totalBytes > 0L) (bytesTransferred.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else null

    val isIndeterminate: Boolean
        get() = fraction == null

    val percentage: Int
        get() = ((fraction ?: 0f) * 100).toInt()

    val sourceLabel: String
        get() = label

    val formattedProgress: String
        get() {
            val totalLabel = if (totalBytes > 0) " / ${ImageRepository.formatMb(totalBytes)}" else ""
            return "${ImageRepository.formatMb(bytesTransferred)}$totalLabel"
        }
}

class ImageRepository(
    private val context: Context,
    private val imageDao: LocalImageDao,
    private val httpClient: OkHttpClient = defaultHttpClient()
) {
    val allImages: Flow<List<LocalImage>> = imageDao.observeAllImages()

    private val imagesDir: File
        get() = File(context.filesDir, "images").apply { mkdirs() }

    private val dtbsDir: File
        get() = File(context.filesDir, "dtbs").apply { mkdirs() }

    private val isosDir: File
        get() = File(context.filesDir, "isos").apply { mkdirs() }

    private val disksDir: File
        get() = File(context.filesDir, "disks").apply { mkdirs() }

    fun vmWorkspaceDir(vmId: Long): File =
        File(File(context.filesDir, "vms"), "vm_$vmId").apply { mkdirs() }

    suspend fun getImageById(id: Long): LocalImage? = imageDao.getById(id)

    suspend fun readImageBytes(image: LocalImage): ByteArray = withContext(Dispatchers.IO) {
        if (image.id == DiagnosticMonitorRom.BUILTIN_IMAGE_ID ||
            image.resolvedKind() == ImageKind.BUILTIN_ROM
        ) {
            return@withContext DiagnosticMonitorRom.binary
        }
        val file = File(image.localPath)
        require(file.exists() && file.isFile) {
            "File image '${image.name}' tidak ditemukan di penyimpanan lokal."
        }
        file.readBytes()
    }

    /**
     * Computes total storage consumed by a VM profile (its private logs/exports + referenced local files).
     */
    suspend fun computeVmStorageUsageBytes(profile: VmProfile): Long = withContext(Dispatchers.IO) {
        var total = 0L
        val countedIds = LinkedHashSet<Long>()

        suspend fun addImageSize(imageId: Long?, ignoreBuiltin: Boolean = false) {
            if (imageId == null || !countedIds.add(imageId)) return
            val img = imageDao.getById(imageId) ?: return
            if (ignoreBuiltin && img.resolvedKind() == ImageKind.BUILTIN_ROM) return
            total += img.sizeBytes
        }

        addImageSize(profile.kernelImageId, ignoreBuiltin = true)
        addImageSize(profile.customDtbImageId)
        addImageSize(profile.attachedIsoImageId)
        addImageSize(profile.targetDiskImageId)

        val ws = vmWorkspaceDir(profile.id)
        ws.walkTopDown().filter { it.isFile }.forEach { total += it.length() }
        total
    }

    /**
     * Saves an exported serial console log into the VM's workspace while enforcing [VmProfile.storageQuotaMb].
     */
    suspend fun saveConsoleLogForVm(profile: VmProfile, content: String): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = content.toByteArray(Charsets.UTF_8)
                val currentUsage = computeVmStorageUsageBytes(profile)
                val quotaBytes = profile.storageQuotaMb.toLong() * 1024L * 1024L
                if (currentUsage + bytes.size > quotaBytes) {
                    throw IllegalStateException(
                        "Kuota penyimpanan VM '${profile.name}' (${profile.storageQuotaMb} MB) terlampaui. " +
                            "Naikkan kuota storage di Editor VM atau hapus file lama."
                    )
                }
                val fileName = "console_${System.currentTimeMillis()}.log"
                val outFile = File(vmWorkspaceDir(profile.id), fileName)
                outFile.writeBytes(bytes)
                outFile
            }
        }

    /**
     * Imports a local kernel image or DTB via Android Storage Access Framework (SAF)
     * with strict size caps, streaming progress, cancellation support, HTML/JSON sniffing,
     * and FDT magic verification for `.dtb` files.
     */
    suspend fun importFromSaf(
        uri: Uri,
        kind: ImageKind,
        onProgress: (TransferProgress) -> Unit = {}
    ): Result<LocalImage> = withContext(Dispatchers.IO) {
        var tempFile: File? = null
        runCatching {
            require(
                kind == ImageKind.KERNEL_IMAGE ||
                    kind == ImageKind.DEVICE_TREE ||
                    kind == ImageKind.ISO_IMAGE ||
                    kind == ImageKind.DISK_IMAGE
            ) {
                "Jenis impor tidak valid."
            }

            val meta = queryDocumentMeta(uri)
            val displayName = meta.first.ifBlank {
                when (kind) {
                    ImageKind.DEVICE_TREE -> "custom.dtb"
                    ImageKind.ISO_IMAGE -> "disk_image.iso"
                    ImageKind.DISK_IMAGE -> "rootfs_disk.img"
                    else -> "kernel_image.bin"
                }
            }
            val declaredSize = meta.second

            val maxAllowedBytes = maxAllowedBytesFor(kind)
            if (declaredSize > 0L && declaredSize > maxAllowedBytes) {
                val kindLabel = when (kind) {
                    ImageKind.DEVICE_TREE -> "DTB"
                    ImageKind.ISO_IMAGE -> "ISO / Installer"
                    ImageKind.DISK_IMAGE -> "Virtual Disk / Rootfs"
                    else -> "kernel"
                }
                throw IllegalArgumentException(
                    "Ukuran file (${formatMb(declaredSize)}) melebihi batas aman " +
                        "(${formatMb(maxAllowedBytes)}) untuk $kindLabel."
                )
            }

            val targetDir = when (kind) {
                ImageKind.DEVICE_TREE -> dtbsDir
                ImageKind.ISO_IMAGE -> isosDir
                ImageKind.DISK_IMAGE -> disksDir
                else -> imagesDir
            }
            val sanitizedName = displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val uniqueBase = "${System.currentTimeMillis()}_$sanitizedName"
            val partFile = File(targetDir, "$uniqueBase.part")
            tempFile = partFile

            val digest = MessageDigest.getInstance("SHA-256")
            val headerSample = ByteArray(512)
            var headerFilled = 0
            var totalRead = 0L

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(partFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n <= 0) break
                        totalRead += n
                        if (totalRead > maxAllowedBytes) {
                            throw IllegalArgumentException(
                                "File melebihi batas ukuran maksimum (${formatMb(maxAllowedBytes)})."
                            )
                        }
                        if (headerFilled < headerSample.size) {
                            val toCopy = minOf(n, headerSample.size - headerFilled)
                            System.arraycopy(buffer, 0, headerSample, headerFilled, toCopy)
                            headerFilled += toCopy
                        }
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                        onProgress(TransferProgress(displayName, totalRead, declaredSize))
                    }
                }
            } ?: throw IllegalStateException("Gagal membuka dokumen dari penyimpanan.")

            if (totalRead < 40L) {
                throw IllegalArgumentException("File terlalu kecil atau kosong ($totalRead byte).")
            }

            val sampleSlice = headerSample.copyOf(headerFilled)
            validateImportedBinaryContent(sampleSlice, kind)

            val archCheck = ArchitectureValidator.validateFile(
                file = partFile,
                kind = kind,
                displayName = displayName,
                targetProfileArch = VmProfile.ARCH_RISCV64
            )
            require(archCheck.isAccepted) { archCheck.reason }

            val finalFile = File(targetDir, uniqueBase)
            if (!partFile.renameTo(finalFile)) {
                partFile.copyTo(finalFile, overwrite = true)
                partFile.delete()
            }
            tempFile = null

            val shaHex = digest.digest().joinToString("") { "%02x".format(it) }
            val archTag = when (kind) {
                ImageKind.ISO_IMAGE -> {
                    val isoMeta = Iso9660Inspector.inspect(finalFile)
                    if (isoMeta.isIso9660) {
                        "${LocalImage.ARCH_RISCV64} · ISO-9660 ${isoMeta.volumeId} (${isoMeta.sectorCount512} sektor)"
                    } else {
                        "${LocalImage.ARCH_RISCV64} · Raw ISO/Blk (${(totalRead + 511L) / 512L} sektor)"
                    }
                }
                ImageKind.DISK_IMAGE -> {
                    "${LocalImage.ARCH_RISCV64} · Target Disk RW (${(totalRead + 511L) / 512L} sektor)"
                }
                else -> LocalImage.ARCH_RISCV64
            }
            val entity = LocalImage(
                name = displayName,
                kind = kind.name,
                sourceType = ImageSourceType.SAF_IMPORT.name,
                localPath = finalFile.absolutePath,
                sizeBytes = totalRead,
                sha256 = shaHex,
                sourceUrl = uri.toString(),
                architectureTag = archTag
            )
            val id = imageDao.insert(entity)
            entity.copy(id = id)
        }.onFailure {
            tempFile?.delete()
        }
    }

    /**
     * Downloads a preset or custom URL kernel image with:
     * - Multi-mirror fallback support (primary URL + secondary raw/CDN mirrors)
     * - HTTP status + Content-Type rejection (blocks HTML error pages)
     * - Streaming SHA-256 verification (enforced strictly whenever [expectedSha256] is provided)
     * - Automatic ZIP extraction (extracts largest kernel binary safely with zip-slip guard)
     * - Atomic `.part` -> final file promotion and cleanup on failure/cancellation
     */
    suspend fun downloadKernelImage(
        name: String,
        url: String,
        expectedSha256: String? = null,
        mirrorUrls: List<String> = emptyList(),
        onProgress: (TransferProgress) -> Unit = {}
    ): Result<LocalImage> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmedUrl = url.trim()
            val candidateUrls = resolveCandidateDownloadUrls(trimmedUrl, mirrorUrls)
            require(candidateUrls.isNotEmpty()) {
                "URL unduhan tidak boleh kosong."
            }
            for (candidate in candidateUrls) {
                require(candidate.startsWith("https://") || candidate.startsWith("http://")) {
                    "URL harus diawali https:// atau http:// ($candidate)"
                }
            }

            val normalizedExpectedSha = expectedSha256
                ?.trim()
                ?.lowercase(Locale.US)
                ?.takeIf { it.isNotEmpty() }
            if (normalizedExpectedSha != null) {
                require(normalizedExpectedSha.matches(Regex("^[0-9a-f]{64}$"))) {
                    "Format SHA-256 tidak valid (harus 64 karakter heksadesimal)."
                }
            }

            val maxAllowed = maxAllowedBytesFor(ImageKind.KERNEL_IMAGE)
            val attemptFailures = mutableListOf<String>()
            var successResult: LocalImage? = null

            for ((index, candidateUrl) in candidateUrls.withIndex()) {
                var partFile: File? = null
                var extractedFile: File? = null
                try {
                    coroutineContext.ensureActive()
                    val host = runCatching { Uri.parse(candidateUrl).host }.getOrNull().orEmpty().ifBlank { candidateUrl }
                    val progressLabel = if (candidateUrls.size > 1) {
                        "$name (Sumber ${index + 1}/${candidateUrls.size}: $host)"
                    } else {
                        name
                    }
                    onProgress(TransferProgress("Menghubungi $progressLabel...", 0L, -1L))

                    val request = Request.Builder()
                        .url(candidateUrl)
                        .header("User-Agent", "KernoVM/${UpdateChecker.CURRENT_VERSION_NAME} (Android; RISCV64)")
                        .get()
                        .build()

                    val safeName = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    val baseFileName = "${System.currentTimeMillis()}_${safeName}_src${index + 1}"
                    val downloadPart = File(imagesDir, "$baseFileName.part")
                    partFile = downloadPart

                    val streamRes = streamDownloadToFile(
                        request = request,
                        destinationPartFile = downloadPart,
                        progressLabel = progressLabel,
                        maxAllowedBytes = maxAllowed,
                        onProgress = onProgress
                    )

                    val totalRead = streamRes.totalBytes
                    val sampleSlice = streamRes.sampleSlice
                    val downloadedSha = streamRes.sha256Hex

                    val isZip = sampleSlice.size >= 4 &&
                        sampleSlice[0] == 0x50.toByte() &&
                        sampleSlice[1] == 0x4B.toByte() &&
                        sampleSlice[2] == 0x03.toByte() &&
                        sampleSlice[3] == 0x04.toByte()

                    val finalFile: File
                    val finalSize: Long
                    val finalSha: String

                    if (isZip) {
                        val targetBin = File(imagesDir, "${baseFileName}_Image.bin")
                        extractedFile = targetBin
                        val extracted = extractKernelFromZip(downloadPart, targetBin, maxAllowed)
                        downloadPart.delete()
                        partFile = null
                        finalFile = targetBin
                        finalSize = extracted.first
                        finalSha = extracted.second

                        // Strict verification: expectedSha may match either the downloaded archive or extracted binary
                        if (normalizedExpectedSha != null &&
                            downloadedSha != normalizedExpectedSha &&
                            finalSha != normalizedExpectedSha
                        ) {
                            throw SecurityException(
                                "Verifikasi SHA-256 gagal! Diharapkan ${normalizedExpectedSha.take(12)}…, " +
                                    "tetapi arsip=$downloadedSha dan biner=$finalSha. Berkas dibersihkan."
                            )
                        }
                    } else {
                        if (normalizedExpectedSha != null && downloadedSha != normalizedExpectedSha) {
                            throw SecurityException(
                                "Verifikasi SHA-256 gagal! Diharapkan ${normalizedExpectedSha.take(12)}…, " +
                                    "diterima ${downloadedSha.take(12)}…. Berkas sementara telah dihapus."
                            )
                        }
                        validateImportedBinaryContent(sampleSlice, ImageKind.KERNEL_IMAGE)
                        val targetBin = File(imagesDir, "$baseFileName.bin")
                        extractedFile = targetBin
                        if (!downloadPart.renameTo(targetBin)) {
                            downloadPart.copyTo(targetBin, overwrite = true)
                            downloadPart.delete()
                        }
                        partFile = null
                        finalFile = targetBin
                        finalSize = totalRead
                        finalSha = downloadedSha
                    }

                    val kernelArchCheck = ArchitectureValidator.validateFile(
                        file = finalFile,
                        kind = ImageKind.KERNEL_IMAGE,
                        displayName = name,
                        targetProfileArch = VmProfile.ARCH_RISCV64
                    )
                    require(kernelArchCheck.isAccepted) { kernelArchCheck.reason }

                    val resolvedArchTag = if (kernelArchCheck.detectedArchitecture == VmProfile.ARCH_RISCV32) {
                        LocalImage.ARCH_RV32IMA_MMODE_NOMMU
                    } else {
                        LocalImage.ARCH_RISCV64
                    }

                    val entity = LocalImage(
                        name = name,
                        kind = ImageKind.KERNEL_IMAGE.name,
                        sourceType = ImageSourceType.DOWNLOADED.name,
                        localPath = finalFile.absolutePath,
                        sizeBytes = finalSize,
                        sha256 = finalSha,
                        sourceUrl = candidateUrl,
                        architectureTag = resolvedArchTag
                    )
                    val id = imageDao.insert(entity)
                    extractedFile = null
                    successResult = entity.copy(id = id)
                    break
                } catch (e: Exception) {
                    partFile?.delete()
                    extractedFile?.delete()
                    if (e is kotlinx.coroutines.CancellationException) {
                        throw e
                    }
                    val host = runCatching { Uri.parse(candidateUrl).host }.getOrNull().orEmpty().ifBlank { candidateUrl }
                    val msg = e.message ?: e.javaClass.simpleName
                    attemptFailures.add("$host ($msg)")
                }
            }

            if (successResult != null) {
                return@runCatching successResult
            }

            val hasShaMismatch = attemptFailures.any { it.contains("SHA-256") }
            val detailMsg = attemptFailures.joinToString("; ")
            if (hasShaMismatch) {
                throw SecurityException(
                    "Verifikasi SHA-256 gagal untuk '$name'! Seluruh berkas sementara telah dihapus. Detail: $detailMsg"
                )
            } else {
                throw IllegalStateException(
                    "Gagal mengunduh '$name' dari ${candidateUrls.size} mirror: $detailMsg"
                )
            }
        }
    }

    /**
     * Downloads a curated item from the KernoVM Store catalog:
     * - Returns BUILTIN_ROM directly without network roundtrip.
     * - Directs destination to isosDir, dtbsDir, or imagesDir based on item.targetKind.
     * - Resolves candidate URLs with mirrors.
     * - Performs streaming HTTP download with Content-Type HTML/JSON rejection.
     * - Verifies streaming SHA-256 digest against expectedSha256 or overrideSha256.
     * - Safely extracts ZIP archives for both kernels and ISO/raw disk images.
     * - Runs Iso9660Inspector for ISO images to populate volume metadata.
     * - Atomically moves .part files to destination and inserts LocalImage record.
     */
    suspend fun downloadStoreItem(
        item: StoreItem,
        overrideSha256: String? = null,
        onProgress: (TransferProgress) -> Unit = {}
    ): Result<LocalImage> = withContext(Dispatchers.IO) {
        runCatching {
            if (item.targetKind == ImageKind.BUILTIN_ROM && item.downloadUrl.isBlank()) {
                val existing = imageDao.getById(DiagnosticMonitorRom.BUILTIN_IMAGE_ID)
                if (existing != null) return@runCatching existing
                val entity = LocalImage(
                    id = DiagnosticMonitorRom.BUILTIN_IMAGE_ID,
                    name = item.name,
                    kind = ImageKind.BUILTIN_ROM.name,
                    sourceType = ImageSourceType.BUILTIN.name,
                    localPath = "",
                    sizeBytes = DiagnosticMonitorRom.binary.size.toLong(),
                    sha256 = DiagnosticMonitorRom.sha256,
                    sourceUrl = "builtin://diagnostic-rom",
                    architectureTag = LocalImage.ARCH_RV32IMA_MMODE_NOMMU
                )
                val id = imageDao.insert(entity)
                return@runCatching entity.copy(id = id)
            }

            val trimmedUrl = item.downloadUrl.trim()
            val candidateUrls = resolveCandidateDownloadUrls(trimmedUrl, item.mirrorUrls)
            require(candidateUrls.isNotEmpty()) {
                "URL unduhan katalog '${item.name}' tidak boleh kosong."
            }
            for (candidate in candidateUrls) {
                require(candidate.startsWith("https://") || candidate.startsWith("http://")) {
                    "URL harus diawali https:// atau http:// ($candidate)"
                }
            }

            val normalizedExpectedSha = (overrideSha256 ?: item.expectedSha256)
                ?.trim()
                ?.lowercase(Locale.US)
                ?.takeIf { it.isNotEmpty() }
            if (normalizedExpectedSha != null) {
                require(normalizedExpectedSha.matches(Regex("^[0-9a-f]{64}$"))) {
                    "Format SHA-256 tidak valid (harus 64 karakter heksadesimal)."
                }
            }

            val archValidation = ArchitectureValidator.validateStoreItem(item, VmProfile.ARCH_RISCV64)
            require(archValidation.isAccepted) { archValidation.reason }

            val targetDir = when (item.targetKind) {
                ImageKind.DEVICE_TREE -> dtbsDir
                ImageKind.ISO_IMAGE -> isosDir
                ImageKind.DISK_IMAGE -> disksDir
                else -> imagesDir
            }
            val maxAllowed = maxAllowedBytesFor(item.targetKind)
            val attemptFailures = mutableListOf<String>()
            var successResult: LocalImage? = null

            for ((index, candidateUrl) in candidateUrls.withIndex()) {
                var partFile: File? = null
                var extractedFile: File? = null
                try {
                    coroutineContext.ensureActive()
                    val host = runCatching { Uri.parse(candidateUrl).host }.getOrNull().orEmpty().ifBlank { candidateUrl }
                    val progressLabel = if (candidateUrls.size > 1) {
                        "${item.name} (Mirror ${index + 1}/${candidateUrls.size}: $host)"
                    } else {
                        item.name
                    }
                    onProgress(TransferProgress("Menghubungi $progressLabel...", 0L, -1L))

                    val request = Request.Builder()
                        .url(candidateUrl)
                        .header("User-Agent", "KernoVM/${UpdateChecker.CURRENT_VERSION_NAME} (Android; Store)")
                        .get()
                        .build()

                    val safeName = item.filename.ifBlank { item.name }.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    val baseFileName = "${System.currentTimeMillis()}_${safeName}_src${index + 1}"
                    val downloadPart = File(targetDir, "$baseFileName.part")
                    partFile = downloadPart

                    val streamRes = streamDownloadToFile(
                        request = request,
                        destinationPartFile = downloadPart,
                        progressLabel = progressLabel,
                        maxAllowedBytes = maxAllowed,
                        onProgress = onProgress
                    )

                    val totalRead = streamRes.totalBytes
                    val sampleSlice = streamRes.sampleSlice
                    val downloadedSha = streamRes.sha256Hex

                    val isZip = (sampleSlice.size >= 4 &&
                        sampleSlice[0] == 0x50.toByte() &&
                        sampleSlice[1] == 0x4B.toByte() &&
                        sampleSlice[2] == 0x03.toByte() &&
                        sampleSlice[3] == 0x04.toByte()) || candidateUrl.endsWith(".zip", ignoreCase = true)

                    val finalFile: File
                    val finalSize: Long
                    val finalSha: String

                    val isGzipArchive = (sampleSlice.size >= 2 &&
                        sampleSlice[0] == 0x1F.toByte() &&
                        sampleSlice[1] == 0x8B.toByte()) ||
                        candidateUrl.endsWith(".tar.gz", ignoreCase = true) ||
                        candidateUrl.endsWith(".tgz", ignoreCase = true)

                    if (isZip) {
                        val isIsoTarget = item.targetKind == ImageKind.ISO_IMAGE
                        val isDiskTarget = item.targetKind == ImageKind.DISK_IMAGE
                        val extension = when {
                            isIsoTarget -> "iso"
                            isDiskTarget -> "img"
                            else -> "bin"
                        }
                        val targetExtracted = File(targetDir, "${baseFileName}_extracted.$extension")
                        extractedFile = targetExtracted

                        val extracted = if (isIsoTarget || isDiskTarget) {
                            extractIsoFromZip(downloadPart, targetExtracted, maxAllowed)
                        } else {
                            extractKernelFromZip(downloadPart, targetExtracted, maxAllowed)
                        }
                        downloadPart.delete()
                        partFile = null
                        finalFile = targetExtracted
                        finalSize = extracted.first
                        finalSha = extracted.second

                        if (normalizedExpectedSha != null &&
                            downloadedSha != normalizedExpectedSha &&
                            finalSha != normalizedExpectedSha
                        ) {
                            throw SecurityException(
                                "Verifikasi SHA-256 gagal! Diharapkan ${normalizedExpectedSha.take(12)}…, " +
                                    "arsip=$downloadedSha biner=$finalSha."
                            )
                        }
                    } else {
                        if (normalizedExpectedSha != null && downloadedSha != normalizedExpectedSha) {
                            throw SecurityException(
                                "Verifikasi SHA-256 gagal! Diharapkan ${normalizedExpectedSha.take(12)}…, diterima ${downloadedSha.take(12)}…."
                            )
                        }
                        validateImportedBinaryContent(sampleSlice, item.targetKind)
                        val extension = when {
                            item.targetKind == ImageKind.ISO_IMAGE -> "iso"
                            isGzipArchive || item.filename.endsWith(".tar.gz", ignoreCase = true) -> "tar.gz"
                            item.targetKind == ImageKind.DISK_IMAGE -> "img"
                            else -> "bin"
                        }
                        val targetFile = File(targetDir, "${baseFileName}.$extension")
                        extractedFile = targetFile
                        if (!downloadPart.renameTo(targetFile)) {
                            downloadPart.copyTo(targetFile, overwrite = true)
                            downloadPart.delete()
                        }
                        partFile = null
                        finalFile = targetFile
                        finalSize = totalRead
                        finalSha = downloadedSha
                    }

                    val downloadedArchCheck = ArchitectureValidator.validateFile(
                        file = finalFile,
                        kind = item.targetKind,
                        displayName = item.filename,
                        targetProfileArch = VmProfile.ARCH_RISCV64
                    )
                    require(downloadedArchCheck.isAccepted) { downloadedArchCheck.reason }

                    val archTag = when (item.targetKind) {
                        ImageKind.ISO_IMAGE -> {
                            val isoMeta = Iso9660Inspector.inspect(finalFile)
                            if (isoMeta.isIso9660) {
                                "${LocalImage.ARCH_RISCV64} · ISO-9660 ${isoMeta.volumeId} (${isoMeta.sectorCount512} sektor)"
                            } else {
                                "${LocalImage.ARCH_RISCV64} · Raw ISO/Blk (${(finalSize + 511L) / 512L} sektor)"
                            }
                        }
                        ImageKind.DISK_IMAGE -> "${LocalImage.ARCH_RISCV64} · Rootfs / Disk (${(finalSize + 511L) / 512L} sektor)"
                        else -> when {
                            item.architecture.equals("riscv64", ignoreCase = true) || item.architecture.isBlank() ->
                                LocalImage.ARCH_RISCV64
                            item.architecture.equals("riscv32", ignoreCase = true) ->
                                LocalImage.ARCH_RV32IMA_MMODE_NOMMU
                            else -> item.architecture
                        }
                    }

                    val entity = LocalImage(
                        name = item.name,
                        kind = item.targetKind.name,
                        sourceType = ImageSourceType.DOWNLOADED.name,
                        localPath = finalFile.absolutePath,
                        sizeBytes = finalSize,
                        sha256 = finalSha,
                        sourceUrl = candidateUrl,
                        architectureTag = archTag
                    )
                    val id = imageDao.insert(entity)
                    extractedFile = null
                    successResult = entity.copy(id = id)
                    break
                } catch (e: Exception) {
                    partFile?.delete()
                    extractedFile?.delete()
                    if (e is kotlinx.coroutines.CancellationException) {
                        throw e
                    }
                    val host = runCatching { Uri.parse(candidateUrl).host }.getOrNull().orEmpty().ifBlank { candidateUrl }
                    val msg = e.message ?: e.javaClass.simpleName
                    attemptFailures.add("$host ($msg)")
                }
            }

            if (successResult != null) {
                return@runCatching successResult
            }

            val hasShaMismatch = attemptFailures.any { it.contains("SHA-256") }
            val detailMsg = attemptFailures.joinToString("; ")
            if (hasShaMismatch) {
                throw SecurityException(
                    "Verifikasi SHA-256 gagal untuk '${item.name}'! Seluruh berkas sementara telah dihapus. Detail: $detailMsg"
                )
            } else {
                throw IllegalStateException(
                    "Gagal mengunduh '${item.name}' dari ${candidateUrls.size} mirror: $detailMsg"
                )
            }
        }
    }

    suspend fun deleteImage(image: LocalImage) = withContext(Dispatchers.IO) {
        if (image.id == DiagnosticMonitorRom.BUILTIN_IMAGE_ID) return@withContext
        if (image.localPath.isNotBlank()) {
            File(image.localPath).delete()
        }
        imageDao.deleteById(image.id)
    }

    private fun extractKernelFromZip(
        zipFile: File,
        destFile: File,
        maxUncompressedBytes: Long
    ): Pair<Long, String> {
        var bestEntryName: String? = null
        var bestEntrySize = -1L

        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val entryName = entry.name
                if (!entry.isDirectory && !entryName.contains("..") && !entryName.startsWith("/")) {
                    val lower = entryName.lowercase(Locale.US)
                    val isLikelyKernel = lower.endsWith("image") ||
                        lower.contains("image") ||
                        lower.endsWith(".bin") ||
                        lower.endsWith(".elf") ||
                        lower.endsWith("doom") ||
                        lower.endsWith(".img")
                    val score = if (isLikelyKernel) entry.size.coerceAtLeast(1L) + 1_000_000_000L else entry.size
                    if (score > bestEntrySize) {
                        bestEntrySize = score
                        bestEntryName = entryName
                    }
                }
                entry = zis.nextEntry
            }
        }

        val chosen = bestEntryName ?: throw IllegalStateException("Arsip ZIP tidak berisi file kernel.")
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        val sample = ByteArray(512)
        var sampleFilled = 0

        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == chosen) {
                    FileOutputStream(destFile).use { fos ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = zis.read(buf)
                            if (n <= 0) break
                            written += n
                            if (written > maxUncompressedBytes) {
                                throw IllegalStateException("Kernel hasil ekstraksi melebihi batas ukuran aman.")
                            }
                            if (sampleFilled < sample.size) {
                                val toCopy = minOf(n, sample.size - sampleFilled)
                                System.arraycopy(buf, 0, sample, sampleFilled, toCopy)
                                sampleFilled += toCopy
                            }
                            digest.update(buf, 0, n)
                            fos.write(buf, 0, n)
                        }
                    }
                    break
                }
                entry = zis.nextEntry
            }
        }

        if (written < 64L) {
            throw IllegalStateException("Kernel hasil ekstraksi terlalu kecil ($written byte).")
        }
        validateImportedBinaryContent(sample.copyOf(sampleFilled), ImageKind.KERNEL_IMAGE)
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        return written to sha
    }

    private fun extractIsoFromZip(
        zipFile: File,
        destFile: File,
        maxUncompressedBytes: Long
    ): Pair<Long, String> {
        var bestEntryName: String? = null
        var bestEntrySize = -1L

        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val entryName = entry.name
                if (!entry.isDirectory && !entryName.contains("..") && !entryName.startsWith("/")) {
                    val lower = entryName.lowercase(Locale.US)
                    val isLikelyIso = lower.endsWith(".iso") ||
                        lower.endsWith(".img") ||
                        lower.endsWith(".raw") ||
                        lower.endsWith(".bin")
                    val score = if (isLikelyIso) entry.size.coerceAtLeast(1L) + 1_000_000_000L else entry.size
                    if (score > bestEntrySize) {
                        bestEntrySize = score
                        bestEntryName = entryName
                    }
                }
                entry = zis.nextEntry
            }
        }

        val chosen = bestEntryName ?: throw IllegalStateException("Arsip ZIP tidak berisi file citra disk (.iso / .img).")
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L

        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == chosen) {
                    FileOutputStream(destFile).use { fos ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            val n = zis.read(buf)
                            if (n <= 0) break
                            written += n
                            if (written > maxUncompressedBytes) {
                                throw IllegalStateException("Citra disk hasil ekstraksi melebihi batas ukuran aman.")
                            }
                            digest.update(buf, 0, n)
                            fos.write(buf, 0, n)
                        }
                    }
                    break
                }
                entry = zis.nextEntry
            }
        }

        if (written < 512L) {
            throw IllegalStateException("Citra disk hasil ekstraksi terlalu kecil ($written byte).")
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        return written to sha
    }

    fun queryDocumentMeta(uri: Uri): Pair<String, Long> {
        var name = ""
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) name = cursor.getString(nameIdx).orEmpty()
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                }
            }
        }
        return name to size
    }

    /**
     * Creates a sparse/zeroed raw virtual disk image (`.img`) in local storage for VirtIO-blk `/dev/vdb`.
     */
    suspend fun createEmptyVirtualDisk(name: String, sizeMb: Int): Result<LocalImage> =
        withContext(Dispatchers.IO) {
            runCatching {
                val clampedMb = sizeMb.coerceIn(4, 1024)
                val sizeBytes = clampedMb.toLong() * 1024L * 1024L
                val cleanName = name.trim().ifBlank { "disk_${clampedMb}mb.img" }
                    .let { if (it.endsWith(".img") || it.endsWith(".raw")) it else "$it.img" }
                val safeFileName = "${System.currentTimeMillis()}_${cleanName.replace(Regex("[^a-zA-Z0-9._-]"), "_")}"
                val file = File(disksDir, safeFileName)
                java.io.RandomAccessFile(file, "rw").use { raf ->
                    raf.setLength(sizeBytes)
                }
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest("$safeFileName:$sizeBytes".toByteArray())
                    .joinToString("") { "%02x".format(it) }
                val entity = LocalImage(
                    name = cleanName,
                    kind = ImageKind.DISK_IMAGE.name,
                    sourceType = ImageSourceType.SAF_IMPORT.name,
                    localPath = file.absolutePath,
                    sizeBytes = sizeBytes,
                    sha256 = digest,
                    sourceUrl = "local://virtual-disk",
                    architectureTag = "${LocalImage.ARCH_RISCV64} · Raw Disk RW (${sizeBytes / 512L} sektor)"
                )
                val id = imageDao.insert(entity)
                entity.copy(id = id)
            }
        }

    private fun maxAllowedBytesFor(kind: ImageKind): Long {
        return when (kind) {
            ImageKind.DEVICE_TREE -> MAX_DTB_BYTES
            ImageKind.ISO_IMAGE, ImageKind.DISK_IMAGE -> MAX_ISO_BYTES
            else -> {
                val halfHeap = (Runtime.getRuntime().maxMemory() / 2L).coerceAtLeast(16L * 1024L * 1024L)
                minOf(halfHeap, MAX_KERNEL_BYTES)
            }
        }
    }

    private data class StreamDownloadResult(
        val totalBytes: Long,
        val sampleSlice: ByteArray,
        val sha256Hex: String
    )

    private suspend fun streamDownloadToFile(
        request: Request,
        destinationPartFile: File,
        progressLabel: String,
        maxAllowedBytes: Long,
        onProgress: (TransferProgress) -> Unit
    ): StreamDownloadResult {
        val downloadDigest = MessageDigest.getInstance("SHA-256")
        val headerSample = ByteArray(512)
        var headerFilled = 0
        var totalRead = 0L

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} ${response.message.ifBlank { "" }}".trim())
            }
            val contentType = response.header("Content-Type")?.lowercase(Locale.US).orEmpty()
            if (contentType.contains("text/html") || contentType.contains("application/json")) {
                throw IllegalStateException("Server mengembalikan respons $contentType, bukan biner yang valid.")
            }
            val body = response.body ?: throw IllegalStateException("Respons kosong dari server.")
            val contentLen = body.contentLength()
            if (contentLen > maxAllowedBytes) {
                throw IllegalStateException(
                    "Ukuran unduhan (${formatMb(contentLen)}) melebihi batas aman (${formatMb(maxAllowedBytes)})."
                )
            }

            body.byteStream().use { input ->
                FileOutputStream(destinationPartFile).use { output ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n <= 0) break
                        totalRead += n
                        if (totalRead > maxAllowedBytes) {
                            throw IllegalStateException(
                                "Unduhan melebihi batas ukuran aman (${formatMb(maxAllowedBytes)})."
                            )
                        }
                        if (headerFilled < headerSample.size) {
                            val toCopy = minOf(n, headerSample.size - headerFilled)
                            System.arraycopy(buf, 0, headerSample, headerFilled, toCopy)
                            headerFilled += toCopy
                        }
                        downloadDigest.update(buf, 0, n)
                        output.write(buf, 0, n)
                        onProgress(TransferProgress(progressLabel, totalRead, contentLen))
                    }
                }
            }
        }

        if (totalRead < 64L) {
            throw IllegalStateException("Berkas unduhan terlalu kecil ($totalRead byte).")
        }

        return StreamDownloadResult(
            totalBytes = totalRead,
            sampleSlice = headerSample.copyOf(headerFilled),
            sha256Hex = downloadDigest.digest().joinToString("") { "%02x".format(it) }
        )
    }

    companion object {
        const val MAX_DTB_BYTES = 4L * 1024L * 1024L       // 4 MB cap for DTB
        const val MAX_KERNEL_BYTES = 64L * 1024L * 1024L   // 64 MB cap for kernel image
        const val MAX_ISO_BYTES = 4096L * 1024L * 1024L    // 4096 MB (4 GB) cap for ISO/disk (streamed via RandomAccessFile, 0 heap)

        fun validateImportedBinaryContent(headerSample: ByteArray, kind: ImageKind) {
            if (kind == ImageKind.DEVICE_TREE) {
                require(FdtInspector.hasValidMagic(headerSample)) {
                    "File bukan Device Tree Blob (.dtb) yang valid (magic 0xD00DFEED tidak ditemukan)."
                }
                return
            }
            val textPrefix = String(headerSample, Charsets.UTF_8).trimStart().lowercase(Locale.US)
            if (textPrefix.startsWith("<!doctype html") ||
                textPrefix.startsWith("<html") ||
                textPrefix.startsWith("<?xml") ||
                textPrefix.startsWith("{\"error") ||
                textPrefix.startsWith("{\"message")
            ) {
                throw IllegalArgumentException(
                    "Konten file terdeteksi sebagai halaman HTML/XML/JSON, bukan binary RISC-V."
                )
            }
            val headerArchCheck = ArchitectureValidator.inspectBinaryHeader(headerSample, VmProfile.ARCH_RISCV64)
            require(headerArchCheck.isAccepted) { headerArchCheck.reason }
        }

        fun formatMb(bytes: Long): String {
            return if (bytes >= 1024L * 1024L) {
                String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1024.0 * 1024.0))
            } else {
                String.format(Locale.US, "%.1f KB", bytes.toDouble() / 1024.0)
            }
        }

        /**
         * Resolves candidate URLs in preferred order:
         * 1. Primary URL
         * 2. Any explicit mirror URLs
         * 3. Auto-derived mirrors (e.g. raw.githubusercontent <-> jsdelivr CDN & GitHub raw)
         */
        fun resolveCandidateDownloadUrls(
            primaryUrl: String,
            explicitMirrors: List<String> = emptyList()
        ): List<String> {
            val candidates = LinkedHashSet<String>()
            val trimmedPrimary = primaryUrl.trim()
            if (trimmedPrimary.isNotEmpty()) {
                candidates.add(trimmedPrimary)
            }
            for (mirror in explicitMirrors) {
                val trimmed = mirror.trim()
                if (trimmed.isNotEmpty()) {
                    candidates.add(trimmed)
                }
            }

            // Pattern 1: raw.githubusercontent.com/{owner}/{repo}/{branch}/{path...}
            val ghRawRegex = Regex("""^https?://raw\.githubusercontent\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$""")
            ghRawRegex.find(trimmedPrimary)?.let { match ->
                val (owner, repo, branch, path) = match.destructured
                candidates.add("https://cdn.jsdelivr.net/gh/$owner/$repo@$branch/$path")
                candidates.add("https://github.com/$owner/$repo/raw/$branch/$path")
            }

            // Pattern 2: cdn.jsdelivr.net/gh/{owner}/{repo}@{branch}/{path...}
            val jsdelivrRegex = Regex("""^https?://cdn\.jsdelivr\.net/gh/([^/@]+)/([^/@]+)@([^/]+)/(.+)$""")
            jsdelivrRegex.find(trimmedPrimary)?.let { match ->
                val (owner, repo, branch, path) = match.destructured
                candidates.add("https://raw.githubusercontent.com/$owner/$repo/$branch/$path")
                candidates.add("https://github.com/$owner/$repo/raw/$branch/$path")
            }

            // Pattern 3: github.com/{owner}/{repo}/raw/{branch}/{path...}
            val ghWebRawRegex = Regex("""^https?://github\.com/([^/]+)/([^/]+)/raw/([^/]+)/(.+)$""")
            ghWebRawRegex.find(trimmedPrimary)?.let { match ->
                val (owner, repo, branch, path) = match.destructured
                candidates.add("https://raw.githubusercontent.com/$owner/$repo/$branch/$path")
                candidates.add("https://cdn.jsdelivr.net/gh/$owner/$repo@$branch/$path")
            }

            return candidates.toList()
        }

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
