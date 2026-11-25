package com.example.ui.viewmodel

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.AppUpdateInfo
import com.example.data.ArchitectureValidator
import com.example.data.DistroPreset
import com.example.data.DistroPresetCatalog
import com.example.data.ImageKind
import com.example.data.ImageRepository
import com.example.data.LocalImage
import com.example.data.StoreCategory
import com.example.data.StoreItem
import com.example.data.TransferProgress
import com.example.data.UpdateChecker
import com.example.data.VmProfile
import com.example.data.VmRepository
import com.example.emulator.AnsiTerminalBuffer
import com.example.emulator.FdtInspector
import com.example.emulator.ParsedFdtSummary
import com.example.vm.VmRuntimeState
import com.example.vm.VmSessionManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VmProfileUiItem(
    val profile: VmProfile,
    val kernelName: String,
    val customDtbName: String?,
    val attachedIsoName: String?,
    val targetDiskName: String? = null,
    val storageUsedBytes: Long
)

data class VmListUiState(
    val items: List<VmProfileUiItem> = emptyList(),
    val availableKernels: List<LocalImage> = emptyList(),
    val availableDtbs: List<LocalImage> = emptyList(),
    val availableIsos: List<LocalImage> = emptyList(),
    val availableTargetDisks: List<LocalImage> = emptyList(),
    val maxHeapMb: Int = 256,
    val maxSupportedCpuThreads: Int = 1,
    val canAllocate128Mb: Boolean = false,
    val activeVmId: Long? = null,
    val activeVmName: String = "",
    val isAnyVmActive: Boolean = false,
    val inspectedDtbSummary: ParsedFdtSummary? = null,
    val feedbackMessage: String? = null
)

class VmListViewModel(
    private val vmRepository: VmRepository,
    private val imageRepository: ImageRepository,
    private val sessionManager: VmSessionManager,
    private val updateChecker: UpdateChecker = UpdateChecker()
) : ViewModel() {

    private val storageUsageMap = MutableStateFlow<Map<Long, Long>>(emptyMap())
    private val inspectedDtb = MutableStateFlow<ParsedFdtSummary?>(null)
    private val feedback = MutableStateFlow<String?>(null)

    private val _availableUpdate = MutableStateFlow<AppUpdateInfo?>(null)
    val availableUpdate: StateFlow<AppUpdateInfo?> = _availableUpdate.asStateFlow()

    private val _isCheckingUpdate = MutableStateFlow(false)
    val isCheckingUpdate: StateFlow<Boolean> = _isCheckingUpdate.asStateFlow()

    val uiState: StateFlow<VmListUiState> = combine(
        vmRepository.allProfiles,
        imageRepository.allImages,
        sessionManager.state,
        storageUsageMap,
        inspectedDtb,
        feedback
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val profiles = values[0] as List<VmProfile>
        @Suppress("UNCHECKED_CAST")
        val images = values[1] as List<LocalImage>
        val runtime = values[2] as VmRuntimeState
        @Suppress("UNCHECKED_CAST")
        val usageMap = values[3] as Map<Long, Long>
        val dtbSummary = values[4] as ParsedFdtSummary?
        val msg = values[5] as String?

        val imageById = images.associateBy { it.id }
        val uiItems = profiles.map { p ->
            VmProfileUiItem(
                profile = p,
                kernelName = imageById[p.kernelImageId]?.name ?: "Kernel belum tersedia",
                customDtbName = p.customDtbImageId?.let { imageById[it]?.name },
                attachedIsoName = p.attachedIsoImageId?.let { imageById[it]?.name },
                targetDiskName = p.targetDiskImageId?.let { imageById[it]?.name },
                storageUsedBytes = usageMap[p.id] ?: 0L
            )
        }
        VmListUiState(
            items = uiItems,
            availableKernels = images.filter { img ->
                val kind = img.resolvedKind()
                kind == ImageKind.BUILTIN_ROM ||
                    (kind == ImageKind.KERNEL_IMAGE &&
                        ArchitectureValidator.isImageCompatibleWithProfile(img, VmProfile.ARCH_RISCV64))
            },
            availableDtbs = images.filter { it.resolvedKind() == ImageKind.DEVICE_TREE },
            availableIsos = images.filter { img ->
                img.resolvedKind() == ImageKind.ISO_IMAGE &&
                    ArchitectureValidator.isImageCompatibleWithProfile(img, VmProfile.ARCH_RISCV64)
            },
            availableTargetDisks = images.filter { img ->
                val kind = img.resolvedKind()
                (kind == ImageKind.DISK_IMAGE ||
                    (kind == ImageKind.ISO_IMAGE &&
                        (img.name.endsWith(".img", ignoreCase = true) || img.name.endsWith(".raw", ignoreCase = true)))) &&
                    ArchitectureValidator.isImageCompatibleWithProfile(img, VmProfile.ARCH_RISCV64)
            },
            maxHeapMb = vmRepository.maxHeapMb(),
            maxSupportedCpuThreads = vmRepository.maxSupportedCpuThreads(),
            canAllocate128Mb = vmRepository.canAllocateRamMb(128),
            activeVmId = runtime.activeVmId,
            activeVmName = runtime.vmName,
            isAnyVmActive = runtime.isActiveSession,
            inspectedDtbSummary = dtbSummary,
            feedbackMessage = msg
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = VmListUiState(
            maxHeapMb = vmRepository.maxHeapMb(),
            maxSupportedCpuThreads = vmRepository.maxSupportedCpuThreads(),
            canAllocate128Mb = vmRepository.canAllocateRamMb(128)
        )
    )

    init {
        viewModelScope.launch {
            combine(vmRepository.allProfiles, imageRepository.allImages) { profiles, _ ->
                profiles
            }.collect { profiles ->
                val map = LinkedHashMap<Long, Long>()
                for (p in profiles) {
                    map[p.id] = imageRepository.computeVmStorageUsageBytes(p)
                }
                storageUsageMap.value = map
            }
        }
    }

    fun inspectCustomDtb(dtbId: Long?) {
        if (dtbId == null) {
            inspectedDtb.value = null
            return
        }
        viewModelScope.launch {
            val img = imageRepository.getImageById(dtbId)
            inspectedDtb.value = if (img != null) {
                runCatching { FdtInspector.parse(imageRepository.readImageBytes(img)) }.getOrNull()
            } else {
                null
            }
        }
    }

    fun saveProfile(profile: VmProfile) {
        viewModelScope.launch {
            if (profile.name.isBlank()) {
                feedback.value = "Nama profil VM tidak boleh kosong."
                return@launch
            }
            vmRepository.saveProfile(profile)
            feedback.value = "Profil '${profile.name.trim()}' disimpan."
        }
    }

    fun deleteProfile(profile: VmProfile) {
        viewModelScope.launch {
            if (sessionManager.state.value.activeVmId == profile.id) {
                sessionManager.stopVm()
            }
            vmRepository.deleteProfile(profile.id)
            feedback.value = "Profil '${profile.name}' dihapus."
        }
    }

    fun bootVm(profile: VmProfile): Boolean {
        val current = sessionManager.state.value
        if (current.isActiveSession && current.activeVmId != profile.id) {
            feedback.value = "Hentikan '${current.vmName}' terlebih dahulu sebelum boot VM lain."
            return false
        }
        sessionManager.startVm(profile)
        return true
    }

    fun ejectIsoAndSwitchPrimaryDisk(profileId: Long) {
        viewModelScope.launch {
            val res = sessionManager.ejectIsoAndSwitchPrimaryDisk(profileId)
            feedback.value = res.fold(
                onSuccess = {
                    "ISO (/dev/vda) dilepas; Virtual Disk Target (/dev/vdb) dijadikan disk utama (bootindex=0)."
                },
                onFailure = { err -> err.message ?: "Gagal melepas ISO." }
            )
        }
    }

    fun clearFeedback() {
        feedback.value = null
    }

    fun checkForUpdates(manual: Boolean = false) {
        if (_isCheckingUpdate.value) return
        viewModelScope.launch {
            _isCheckingUpdate.value = true
            try {
                val update = updateChecker.checkForUpdate()
                if (update != null && update.isNewer) {
                    _availableUpdate.value = update
                } else if (manual) {
                    if (update != null) {
                        feedback.value = "KernoVM v${UpdateChecker.CURRENT_VERSION_NAME} (build ${UpdateChecker.CURRENT_BUILD_NUMBER}) sudah versi terbaru."
                    } else {
                        feedback.value = "Gagal memeriksa pembaruan. Periksa koneksi internet."
                    }
                }
            } finally {
                _isCheckingUpdate.value = false
            }
        }
    }

    fun dismissUpdate() {
        _availableUpdate.value = null
    }
}

class ConsoleViewModel(
    private val sessionManager: VmSessionManager
) : ViewModel() {

    val runtimeState: StateFlow<VmRuntimeState> = sessionManager.state

    private val _exportMessage = MutableStateFlow<String?>(null)
    val exportMessage: StateFlow<String?> = _exportMessage.asStateFlow()

    fun sendBytes(bytes: ByteArray) = sessionManager.sendTerminalBytes(bytes)
    fun sendControlBytes(bytes: ByteArray) = sessionManager.sendRawControlBytes(bytes)
    fun toggleCtrlSticky() = sessionManager.toggleCtrlSticky()
    fun toggleAltSticky() = sessionManager.toggleAltSticky()
    fun setTerminalTheme(theme: AnsiTerminalBuffer.ColorTheme) = sessionManager.setTerminalTheme(theme)
    fun pause() = sessionManager.pauseVm(fromBackgroundAutoPause = false)
    fun resume() = sessionManager.resumeVm()
    fun reset() = sessionManager.resetVm()
    fun stop() = sessionManager.stopVm()
    fun retryFallbackRam(ramMb: Int) = sessionManager.retryBootWithFallbackRam(ramMb)
    fun resize(cols: Int, rows: Int) = sessionManager.resizeTerminal(cols, rows)
    fun clearScreen() = sessionManager.clearTerminal()
    fun increaseFont() = sessionManager.adjustFontSize(1f)
    fun decreaseFont() = sessionManager.adjustFontSize(-1f)
    fun adjustFontSize(deltaSp: Float) = sessionManager.adjustFontSize(deltaSp)
    fun setFontSize(sizeSp: Float) = sessionManager.setFontSize(sizeSp)
    fun captureFramebufferBitmap(): Bitmap? = sessionManager.captureFramebufferBitmap()
    fun getPlainLog(): String = sessionManager.getPlainTerminalLog()
    fun dismissError() = sessionManager.dismissError()

    fun saveLogToVmWorkspace() {
        viewModelScope.launch {
            val res = sessionManager.exportLogToVmStorage()
            _exportMessage.value = res.fold(
                onSuccess = { fileName -> "Log disimpan ke ruang VM: $fileName" },
                onFailure = { err -> err.message ?: "Gagal menyimpan log." }
            )
        }
    }

    fun ejectIsoAndSwitchPrimaryDisk() {
        viewModelScope.launch {
            val res = sessionManager.ejectIsoAndSwitchPrimaryDisk()
            _exportMessage.value = res.fold(
                onSuccess = {
                    "ISO (/dev/vda) dilepas & bootindex=0 dialihkan ke Virtual Disk Target (/dev/vdb)."
                },
                onFailure = { err -> err.message ?: "Gagal melepas ISO." }
            )
        }
    }

    fun clearExportMessage() {
        _exportMessage.value = null
    }
}

data class PendingLargeFileConfirmation(
    val title: String,
    val message: String,
    val sizeBytes: Long,
    val formattedSize: String,
    val onConfirm: () -> Unit
)

data class ImageManagerUiState(
    val localImages: List<LocalImage> = emptyList(),
    val presets: List<DistroPreset> = DistroPresetCatalog.presets,
    val activeTransfer: TransferProgress? = null,
    val statusMessage: String? = null,
    val isError: Boolean = false,
    val pendingConfirmation: PendingLargeFileConfirmation? = null
)

class ImageViewModel(
    private val imageRepository: ImageRepository
) : ViewModel() {

    private val activeTransfer = MutableStateFlow<TransferProgress?>(null)
    private val statusMsg = MutableStateFlow<Pair<String?, Boolean>>(null to false)
    private val pendingConfirmation = MutableStateFlow<PendingLargeFileConfirmation?>(null)
    private var transferJob: Job? = null

    val uiState: StateFlow<ImageManagerUiState> = combine(
        imageRepository.allImages,
        activeTransfer,
        statusMsg,
        pendingConfirmation
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val images = values[0] as List<LocalImage>
        val transfer = values[1] as TransferProgress?
        @Suppress("UNCHECKED_CAST")
        val status = values[2] as Pair<String?, Boolean>
        val conf = values[3] as PendingLargeFileConfirmation?
        ImageManagerUiState(
            localImages = images,
            presets = DistroPresetCatalog.presets,
            activeTransfer = transfer,
            statusMessage = status.first,
            isError = status.second,
            pendingConfirmation = conf
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ImageManagerUiState()
    )

    fun importLocalUri(uri: Uri, kind: ImageKind, skipWarning: Boolean = false) {
        if (transferJob?.isActive == true) {
            statusMsg.value = "Tunggu proses impor/unduh saat ini selesai atau batalkan dulu." to true
            return
        }
        if (kind == ImageKind.ISO_IMAGE && !skipWarning) {
            val meta = imageRepository.queryDocumentMeta(uri)
            val declaredSize = meta.second
            if (declaredSize > 500L * 1024L * 1024L) {
                val formatted = ImageRepository.formatMb(declaredSize)
                pendingConfirmation.value = PendingLargeFileConfirmation(
                    title = "Peringatan Ukuran File (> 500 MB)",
                    message = "File berukuran besar ($formatted). Menyalin file ini akan menggunakan ruang penyimpanan internal perangkat Anda. Lanjutkan?",
                    sizeBytes = declaredSize,
                    formattedSize = formatted,
                    onConfirm = {
                        pendingConfirmation.value = null
                        importLocalUri(uri, kind, skipWarning = true)
                    }
                )
                return
            }
        }
        transferJob = viewModelScope.launch {
            activeTransfer.value = TransferProgress("Menyiapkan impor...", 0L, -1L)
            val result = imageRepository.importFromSaf(uri, kind) { progress ->
                activeTransfer.value = progress
            }
            activeTransfer.value = null
            result.fold(
                onSuccess = { img ->
                    statusMsg.value = "Berhasil mengimpor '${img.name}' (SHA-256: ${img.sha256.take(12)}…)" to false
                },
                onFailure = { err ->
                    statusMsg.value = (err.message ?: "Impor SAF gagal.") to true
                }
            )
        }
    }

    fun dismissPendingConfirmation() {
        pendingConfirmation.value = null
    }

    fun downloadPreset(preset: DistroPreset, overrideSha256: String? = null) {
        if (preset.isBuiltInOffline) return
        downloadCustomUrl(
            name = preset.name,
            url = preset.downloadUrl,
            expectedSha256 = overrideSha256?.takeIf { it.isNotBlank() } ?: preset.expectedSha256,
            mirrorUrls = preset.mirrorUrls
        )
    }

    fun downloadStoreItem(item: StoreItem, overrideSha256: String? = null, skipWarning: Boolean = false) {
        if (item.targetKind == ImageKind.BUILTIN_ROM && item.downloadUrl.isBlank()) {
            statusMsg.value = "'${item.name}' sudah terpasang secara bawaan." to false
            return
        }
        if (transferJob?.isActive == true) {
            statusMsg.value = "Masih ada proses transfer aktif." to true
            return
        }
        if (item.category == StoreCategory.ISO && item.sizeBytes > 500L * 1024L * 1024L && !skipWarning) {
            val formatted = item.formattedSize
            pendingConfirmation.value = PendingLargeFileConfirmation(
                title = "Peringatan Ukuran File (> 500 MB)",
                message = "File berukuran besar ($formatted). Menyalin file ini akan menggunakan ruang penyimpanan internal perangkat Anda. Lanjutkan?",
                sizeBytes = item.sizeBytes,
                formattedSize = formatted,
                onConfirm = {
                    pendingConfirmation.value = null
                    downloadStoreItem(item, overrideSha256, skipWarning = true)
                }
            )
            return
        }
        transferJob = viewModelScope.launch {
            activeTransfer.value = TransferProgress(item.name, 0L, -1L)
            val result = imageRepository.downloadStoreItem(
                item = item,
                overrideSha256 = overrideSha256
            ) { progress ->
                activeTransfer.value = progress
            }
            activeTransfer.value = null
            result.fold(
                onSuccess = { img ->
                    statusMsg.value = "Berhasil mengunduh '${img.name}' (${ImageRepository.formatMb(img.sizeBytes)}, SHA-256: ${img.sha256.take(12)}…)" to false
                },
                onFailure = { err ->
                    statusMsg.value = (err.message ?: "Unduhan katalog gagal.") to true
                }
            )
        }
    }

    fun downloadCustomUrl(
        name: String,
        url: String,
        expectedSha256: String?,
        mirrorUrls: List<String> = emptyList()
    ) {
        if (transferJob?.isActive == true) {
            statusMsg.value = "Masih ada proses transfer aktif." to true
            return
        }
        val cleanName = name.trim().ifBlank { "linux_rv64_image" }
        transferJob = viewModelScope.launch {
            activeTransfer.value = TransferProgress(cleanName, 0L, -1L)
            val result = imageRepository.downloadKernelImage(
                name = cleanName,
                url = url,
                expectedSha256 = expectedSha256,
                mirrorUrls = mirrorUrls
            ) { progress ->
                activeTransfer.value = progress
            }
            activeTransfer.value = null
            result.fold(
                onSuccess = { img ->
                    statusMsg.value = "Unduhan '${img.name}' siap (${ImageRepository.formatMb(img.sizeBytes)}, SHA-256: ${img.sha256.take(12)}…)" to false
                },
                onFailure = { err ->
                    statusMsg.value = (err.message ?: "Unduhan gagal.") to true
                }
            )
        }
    }

    fun cancelActiveTransfer() {
        transferJob?.cancel()
        transferJob = null
        activeTransfer.value = null
        statusMsg.value = "Proses transfer dibatalkan; berkas parsial telah dibersihkan." to false
    }

    fun createVirtualDisk(name: String, sizeMb: Int) {
        viewModelScope.launch {
            val res = imageRepository.createEmptyVirtualDisk(name, sizeMb)
            res.fold(
                onSuccess = { img ->
                    statusMsg.value = "Disk virtual '${img.name}' (${sizeMb} MB) dibuat untuk /dev/vdb." to false
                },
                onFailure = { err ->
                    statusMsg.value = (err.message ?: "Gagal membuat disk virtual.") to true
                }
            )
        }
    }

    fun deleteImage(image: LocalImage) {
        viewModelScope.launch {
            imageRepository.deleteImage(image)
            statusMsg.value = "Image '${image.name}' dihapus." to false
        }
    }

    fun clearStatus() {
        statusMsg.value = null to false
    }
}

class KernoViewModelFactory(
    private val vmRepository: VmRepository,
    private val imageRepository: ImageRepository,
    private val sessionManager: VmSessionManager
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(VmListViewModel::class.java) ->
                VmListViewModel(vmRepository, imageRepository, sessionManager) as T
            modelClass.isAssignableFrom(ConsoleViewModel::class.java) ->
                ConsoleViewModel(sessionManager) as T
            modelClass.isAssignableFrom(ImageViewModel::class.java) ->
                ImageViewModel(imageRepository) as T
            else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
    }
}
