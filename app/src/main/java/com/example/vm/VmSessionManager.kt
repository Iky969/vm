package com.example.vm

import android.content.Context
import android.graphics.Bitmap
import android.os.Process
import com.example.data.ArchitectureValidator
import com.example.data.BootGuardResult
import com.example.data.CpuThreadPriority
import com.example.data.ImageKind
import com.example.data.ImageRepository
import com.example.data.VmProfile
import com.example.data.VmRepository
import com.example.emulator.AnsiTerminalBuffer
import com.example.emulator.DeviceTreeBuilder
import com.example.emulator.DiagnosticMonitorRom
import com.example.emulator.FdtInspector
import com.example.emulator.FdtTargetArch
import com.example.emulator.Rv32ImaCpuCore
import com.example.emulator.Uart8250
import com.example.emulator.VirtioBlockDevice
import com.example.emulator.VirtioBlockFdtDescriptor
import com.example.emulator.VirtioBlockSlotConfig
import com.example.emulator.VirtioNetDevice
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.coroutines.coroutineContext

/**
 * Application-scoped single-active-VM runtime manager.
 *
 * Enforces:
 * - Single active VM at a time
 * - Conservative ART heap checks + OutOfMemoryError recovery (without System.gc())
 * - 16 MB Linux boot headroom guard
 * - Frozen virtual CLINT mtime across Pause/Resume
 * - Zero-busy-spin WFI parking with instant wake on UART RX, timer expiry, Pause, Resume, Reset, Stop
 * - Per-VM CPU quota duty-cycle throttling (25%..100%) and worker thread priority
 * - Throttled ~30ms terminal snapshot publication to keep Compose at 60fps
 */
class VmSessionManager(
    private val context: Context,
    private val vmRepository: VmRepository,
    private val imageRepository: ImageRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val terminal = AnsiTerminalBuffer(initialCols = 80, initialRows = 24)

    private val _state = MutableStateFlow(VmRuntimeState(terminalSnapshot = terminal.snapshot()))
    val state: StateFlow<VmRuntimeState> = _state.asStateFlow()

    @Volatile private var activeProfile: VmProfile? = null
    @Volatile private var cpuCore: Rv32ImaCpuCore? = null
    @Volatile private var activeVirtioBlk: VirtioBlockDevice? = null
    @Volatile private var activeVirtioTargetBlk: VirtioBlockDevice? = null
    @Volatile var activeVirtioNet: VirtioNetDevice? = null
    @Volatile var activeDtbBytes: ByteArray? = null
        private set
    @Volatile private var virtualClock: FreezableVirtualClock? = null
    @Volatile private var isPausedFlag: Boolean = false
    @Volatile private var terminalDirty: Boolean = false

    private var runnerJob: Job? = null
    private var uiSyncJob: Job? = null
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)
    private val activeSessionSequence = java.util.concurrent.atomic.AtomicLong(0L)

    // Updated by MainActivity based on runtime POST_NOTIFICATIONS grant & FGS start outcome
    @Volatile var notificationPermissionGranted: Boolean = false

    fun startVm(profile: VmProfile, ramOverrideMb: Int? = null) {
        val current = _state.value
        if (current.isActiveSession && current.activeVmId != profile.id) {
            _state.update {
                it.copy(
                    errorMessage = "Hentikan '${current.vmName}' terlebih dahulu (v1 mendukung 1 VM aktif)."
                )
            }
            return
        }

        stopInternal(clearTerminal = true)

        val effectiveRamMb = ramOverrideMb ?: profile.ramSizeMb
        val effectiveProfile = profile.copy(ramSizeMb = effectiveRamMb)
        activeProfile = effectiveProfile

        _state.update {
            it.copy(
                activeVmId = effectiveProfile.id,
                vmName = effectiveProfile.name,
                status = VmStatus.BOOTING,
                errorMessage = null,
                oomFallbackSuggestedRamMb = null,
                allocatedRamMb = effectiveRamMb,
                cpuThreadCount = effectiveProfile.cpuThreadCount,
                effectiveCpuQuotaPercent = effectiveProfile.cpuQuotaPercent.coerceIn(
                    VmRepository.MIN_CPU_QUOTA,
                    VmRepository.MAX_CPU_QUOTA
                ),
                cpuThreadPriorityLabel = effectiveProfile.resolvedThreadPriority().label,
                storageQuotaMb = effectiveProfile.storageQuotaMb,
                attachedIsoName = null,
                targetDiskName = null,
                primaryBootSlot = effectiveProfile.resolvePrimaryBootSlot(),
                targetArchitecture = VmProfile.normalizeArchitecture(effectiveProfile.architecture),
                virtioBlockNodes = emptyList(),
                isUsingCustomDtb = effectiveProfile.customDtbImageId != null,
                autoPausedByBackground = false,
                instructionsPerSecond = 0L,
                totalCycles = 0L,
                uptimeSeconds = 0L,
                isWfiSleeping = false
            )
        }

        val sessionRunId = activeSessionSequence.incrementAndGet()
        runnerJob = scope.launch {
            bootAndRunLoop(effectiveProfile, sessionRunId)
        }
    }

    private suspend fun bootAndRunLoop(profile: VmProfile, sessionRunId: Long) {
        var virtioBlk: VirtioBlockDevice? = null
        var virtioTargetBlk: VirtioBlockDevice? = null
        var virtioNet: VirtioNetDevice? = null
        try {
            val kernelImage = imageRepository.getImageById(profile.kernelImageId)
                ?: throw IllegalStateException("Kernel image tidak ditemukan. Pilih ulang kernel di Editor VM.")

            val isBuiltInRom = kernelImage.id == DiagnosticMonitorRom.BUILTIN_IMAGE_ID ||
                kernelImage.resolvedKind() == ImageKind.BUILTIN_ROM
            val kernelBytes = imageRepository.readImageBytes(kernelImage)

            val profileArch = VmProfile.normalizeArchitecture(profile.architecture)
            val resolvedSlot = profile.resolvePrimaryBootSlot()
            val hasIsoConfigured = profile.attachedIsoImageId != null
            val hasTargetConfigured = profile.targetDiskImageId != null
            val isoBootIndex = if (hasIsoConfigured && hasTargetConfigured && resolvedSlot == VmProfile.BOOT_SLOT_DISK) 1 else 0
            val diskBootIndex = if (hasIsoConfigured && (!hasTargetConfigured || resolvedSlot != VmProfile.BOOT_SLOT_DISK)) 1 else 0

            // Attach optional installer ISO / read-only media via VirtIO-MMIO v2 (/dev/vda @ 0x10001000)
            var attachedIsoName: String? = null
            profile.attachedIsoImageId?.let { attachedIsoId ->
                val isoImg = imageRepository.getImageById(attachedIsoId)
                if (isoImg != null && isoImg.localPath.isNotBlank()) {
                    val isoFile = File(isoImg.localPath)
                    if (isoFile.exists() && isoFile.isFile) {
                        val validation = ArchitectureValidator.validateFile(
                            file = isoFile,
                            kind = isoImg.resolvedKind(),
                            displayName = isoImg.name,
                            targetProfileArch = profileArch
                        )
                        if (!validation.isAccepted) {
                            throw IllegalStateException("Media ISO '${isoImg.name}' ditolak: ${validation.reason}")
                        }
                        val slotConfig = VirtioBlockSlotConfig.primaryIso(bootIndex = isoBootIndex)
                        virtioBlk = VirtioBlockDevice(isoFile, slotConfig)
                        activeVirtioBlk = virtioBlk
                        attachedIsoName = isoImg.name
                    }
                }
            }

            // Attach optional writable Virtual Disk Target via VirtIO-MMIO v2 (/dev/vdb @ 0x10003000)
            var targetDiskName: String? = null
            profile.targetDiskImageId?.let { targetDiskId ->
                val diskImg = imageRepository.getImageById(targetDiskId)
                if (diskImg != null && diskImg.localPath.isNotBlank()) {
                    val diskFile = File(diskImg.localPath)
                    if (diskFile.exists() && diskFile.isFile) {
                        val validation = ArchitectureValidator.validateFile(
                            file = diskFile,
                            kind = diskImg.resolvedKind(),
                            displayName = diskImg.name,
                            targetProfileArch = profileArch
                        )
                        if (!validation.isAccepted) {
                            throw IllegalStateException("Virtual Disk '${diskImg.name}' ditolak: ${validation.reason}")
                        }
                        val targetConfig = VirtioBlockSlotConfig.targetDisk(bootIndex = diskBootIndex)
                        virtioTargetBlk = VirtioBlockDevice(diskFile, targetConfig)
                        activeVirtioTargetBlk = virtioTargetBlk
                        targetDiskName = diskImg.name
                    }
                }
            }

            // VirtIO-MMIO v2 Network Device (/dev/eth0 @ 0x10002000)
            virtioNet = if (!isBuiltInRom) VirtioNetDevice() else null
            activeVirtioNet = virtioNet

            // Resolve DTB (custom .dtb or dynamically generated FDT v17)
            val customDtbId = profile.customDtbImageId
            val dtbBytes: ByteArray
            val effectiveRamBytes: Long
            val fdtTargetArch = if (profileArch == VmProfile.ARCH_RISCV32) FdtTargetArch.RISCV32 else FdtTargetArch.RISCV64
            val fdtBlockDescriptors = listOfNotNull(virtioBlk, virtioTargetBlk).map(VirtioBlockFdtDescriptor::fromDevice)

            var resolvedFbAddr = 0L
            if (customDtbId != null) {
                val dtbImage = imageRepository.getImageById(customDtbId)
                    ?: throw IllegalStateException("File DTB kustom tidak ditemukan.")
                val rawDtb = imageRepository.readImageBytes(dtbImage)
                val parsed = FdtInspector.parse(rawDtb)
                dtbBytes = rawDtb
                val dtbMemBytes = parsed.detectedMemorySizeBytes
                effectiveRamBytes = if (dtbMemBytes != null && dtbMemBytes in (8L * MB)..(1024L * MB)) {
                    maxOf(dtbMemBytes, profile.ramSizeMb.toLong() * MB)
                } else {
                    profile.ramSizeMb.toLong() * MB
                }
                val fbNode = parsed.detectedFramebufferNode
                val parsedReg = fbNode?.u32ListProp("reg")?.firstOrNull()?.toLong()
                resolvedFbAddr = if (parsedReg != null) {
                    parsedReg and 0xFFFFFFFFL
                } else {
                    DeviceTreeBuilder.FramebufferConfig().resolveBaseAddress(effectiveRamBytes)
                }
            } else {
                effectiveRamBytes = profile.ramSizeMb.toLong() * MB
                val fbConfig = DeviceTreeBuilder.FramebufferConfig(
                    width = 640,
                    height = 480,
                    format = "r5g6b5"
                )
                resolvedFbAddr = fbConfig.resolveBaseAddress(effectiveRamBytes)
                dtbBytes = DeviceTreeBuilder.build(
                    ramSizeBytes = effectiveRamBytes,
                    bootArgs = profile.bootArgs,
                    timebaseFrequencyHz = profile.clockMhz * 1_000_000,
                    hasVirtioBlock = fdtBlockDescriptors.isNotEmpty(),
                    hasVirtioNet = virtioNet != null,
                    framebufferConfig = fbConfig,
                    targetArch = fdtTargetArch,
                    virtioBlockDevices = fdtBlockDescriptors
                )
            }
            activeDtbBytes = dtbBytes
            val initialParsedFdt = runCatching { FdtInspector.parse(dtbBytes) }.getOrNull()

            val effectiveRamMb = (effectiveRamBytes / MB).toInt().coerceAtLeast(VmRepository.MIN_RAM_MB)
            when (
                val guard = vmRepository.validateBootMemoryLayout(
                    ramSizeMb = effectiveRamMb,
                    kernelSizeBytes = kernelBytes.size.toLong(),
                    dtbSizeBytes = dtbBytes.size.toLong(),
                    isBuiltInDiagRom = isBuiltInRom
                )
            ) {
                is BootGuardResult.Rejected -> {
                    publishError(guard.reason, guard.suggestedRamMb)
                    return
                }
                is BootGuardResult.Warning -> {
                    terminal.appendString("[KernoVM] Peringatan: ${guard.message}\r\n")
                    terminalDirty = true
                }
                BootGuardResult.Valid -> Unit
            }

            // Allocate IntArray RAM safely with OutOfMemoryError recovery (no System.gc())
            val wordCount = (effectiveRamBytes ushr 2).toInt()
            val ramArray: IntArray = try {
                IntArray(wordCount)
            } catch (_: OutOfMemoryError) {
                val fallback = if (effectiveRamMb > 64) 64 else null
                publishError(
                    "Memori ART tidak mencukupi untuk mengalokasikan RAM $effectiveRamMb MB.",
                    fallback
                )
                return
            }

            val clock = FreezableVirtualClock(clockMhz = profile.clockMhz)
            virtualClock = clock

            var coreRef: Rv32ImaCpuCore? = null
            val uart = Uart8250(
                onTxByte = { byteVal ->
                    terminal.appendByte(byteVal)
                    terminalDirty = true
                },
                onRxAvailable = {
                    coreRef?.wakeFromWfi()
                    wakeSignal.trySend(Unit)
                }
            )

            val core = Rv32ImaCpuCore(
                ram = ramArray,
                uart = uart,
                virtioBlk = virtioBlk,
                virtioNet = virtioNet,
                clockSourceUs = { clock.currentTicks() },
                virtioBlkSecondary = virtioTargetBlk
            )
            coreRef = core
            cpuCore = core

            // Load kernel at 0x80000000 and DTB aligned at top of RAM
            val activeDtb = activeDtbBytes ?: dtbBytes
            val alignedDtbSize = (activeDtb.size + 15) and 15.inv()
            val dtbOffset = effectiveRamBytes.toInt() - alignedDtbSize
            val dtbPhysAddr = Rv32ImaCpuCore.RAM_BASE + dtbOffset

            core.loadBytesIntoRam(kernelBytes, 0)
            core.loadBytesIntoRam(activeDtb, dtbOffset)
            core.reset(entryPc = Rv32ImaCpuCore.RAM_BASE, hartId = 0, dtbAddress = dtbPhysAddr)
            clock.reset(startImmediately = true)
            isPausedFlag = false

            vmRepository.markBooted(profile.id)

            // Apply configured worker thread priority
            runCatching {
                Process.setThreadPriority(profile.resolvedThreadPriority().androidThreadPriority)
            }

            _state.update {
                it.copy(
                    status = VmStatus.RUNNING,
                    allocatedRamMb = effectiveRamMb,
                    attachedIsoName = attachedIsoName,
                    targetDiskName = targetDiskName,
                    primaryBootSlot = resolvedSlot,
                    targetArchitecture = profileArch,
                    virtioBlockNodes = initialParsedFdt?.virtioBlockNodes.orEmpty(),
                    errorMessage = null,
                    oomFallbackSuggestedRamMb = null,
                    terminalSnapshot = terminal.snapshot(),
                    registerSnapshot = captureRegisters(core),
                    framebuffer = FramebufferState(
                        enabled = true,
                        width = 640,
                        height = 480,
                        format = "r5g6b5",
                        baseAddress = resolvedFbAddr,
                        sizeBytes = 640L * 480L * 2L
                    )
                )
            }

            // Start ForegroundService when allowed; otherwise background ON_STOP will auto-pause
            startForegroundServiceIfPermitted(profile.name)
            startUiSyncLoop()

            // Main VM execution loop
            val quotaPercent = profile.cpuQuotaPercent.coerceIn(
                VmRepository.MIN_CPU_QUOTA,
                VmRepository.MAX_CPU_QUOTA
            )
            var lastRateSampleNs = System.nanoTime()
            var lastRateInsnCount = 0L

            while (coroutineContext.isActive) {
                if (isPausedFlag) {
                    _state.update { it.copy(status = VmStatus.PAUSED, isWfiSleeping = core.isWfiWaiting) }
                    // Wait until Resume/Stop wakes us via wakeSignal
                    wakeSignal.receive()
                    continue
                }

                val sliceStartNs = System.nanoTime()
                val batchResult = core.stepBatch(INSTRUCTIONS_PER_SLICE)
                val sliceElapsedNs = (System.nanoTime() - sliceStartNs).coerceAtLeast(0L)

                when (batchResult) {
                    Rv32ImaCpuCore.BATCH_POWEROFF -> {
                        clock.pause()
                        terminal.appendString("\r\n[KernoVM] SYSCON poweroff (0x5555) diterima — mesin berhenti.\r\n")
                        terminalDirty = true
                        _state.update {
                            it.copy(
                                status = VmStatus.HALTED,
                                isWfiSleeping = false,
                                instructionsPerSecond = 0L,
                                totalCycles = core.totalCycles,
                                uptimeSeconds = clock.elapsedSeconds(),
                                terminalSnapshot = terminal.snapshot(),
                                registerSnapshot = captureRegisters(core)
                            )
                        }
                        stopForegroundServiceSafe()
                        return
                    }
                    Rv32ImaCpuCore.BATCH_REBOOT -> {
                        terminal.appendString("\r\n[KernoVM] SYSCON reboot (0x7777) diterima — memuat ulang kernel...\r\n")
                        terminalDirty = true
                        ramArray.fill(0)
                        val rebootDtb = activeDtbBytes ?: dtbBytes
                        val rebootAlignedDtb = (rebootDtb.size + 15) and 15.inv()
                        val rebootDtbOffset = effectiveRamBytes.toInt() - rebootAlignedDtb
                        val rebootDtbPhysAddr = Rv32ImaCpuCore.RAM_BASE + rebootDtbOffset
                        core.loadBytesIntoRam(kernelBytes, 0)
                        core.loadBytesIntoRam(rebootDtb, rebootDtbOffset)
                        core.reset(entryPc = Rv32ImaCpuCore.RAM_BASE, hartId = 0, dtbAddress = rebootDtbPhysAddr)
                        clock.reset(startImmediately = true)
                        lastRateInsnCount = 0L
                        lastRateSampleNs = System.nanoTime()
                        continue
                    }
                    Rv32ImaCpuCore.BATCH_WFI -> {
                        if (isPausedFlag) continue
                        val nowTicks = clock.currentTicks()
                        core.syncInterrupts(nowTicks)
                        core.wakeFromWfi()

                        // Flush pending terminal output immediately so all boot/kernel output renders without delay
                        if (terminalDirty) {
                            terminalDirty = false
                            val snap = terminal.snapshot()
                            _state.update { it.copy(terminalSnapshot = snap) }
                        }

                        // When VM is active and running, keep the CPU awake so guest threads, boot tasks,
                        // and console output run to completion without requiring user keystrokes.
                        // Perform a cooperative coroutine yield to keep the Android UI responsive at 60fps.
                        yield()
                    }
                    else -> {
                        if (_state.value.isWfiSleeping) {
                            _state.update { it.copy(isWfiSleeping = false) }
                        }
                        // Enforce per-VM CPU quota duty-cycle throttling when quota < 100%
                        if (quotaPercent < 100 && sliceElapsedNs > 0L) {
                            val throttleNs = (sliceElapsedNs * (100 - quotaPercent)) / quotaPercent
                            val throttleMs = (throttleNs / 1_000_000L).coerceAtMost(35L)
                            if (throttleMs >= 1L) {
                                withTimeoutOrNull(throttleMs) {
                                    wakeSignal.receive()
                                }
                            }
                        }
                    }
                }

                // Update MIPS telemetry every ~500ms
                val nowNs = System.nanoTime()
                val rateDeltaNs = nowNs - lastRateSampleNs
                if (rateDeltaNs >= 500_000_000L) {
                    val insnDelta = core.totalInstructions - lastRateInsnCount
                    val ips = (insnDelta * 1_000_000_000L) / rateDeltaNs.coerceAtLeast(1L)
                    lastRateInsnCount = core.totalInstructions
                    lastRateSampleNs = nowNs
                    _state.update {
                        it.copy(
                            instructionsPerSecond = ips,
                            totalCycles = core.totalCycles,
                            uptimeSeconds = clock.elapsedSeconds(),
                            registerSnapshot = captureRegisters(core)
                        )
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            if (coroutineContext.isActive && activeSessionSequence.get() == sessionRunId) {
                publishError(t.message ?: "Terjadi galat runtime emulator.", null)
            }
        } finally {
            if (activeSessionSequence.get() == sessionRunId) {
                uiSyncJob?.cancel()
                uiSyncJob = null
            }
            virtioBlk?.close()
            if (virtioBlk != null && activeVirtioBlk === virtioBlk) {
                activeVirtioBlk = null
            }
            virtioTargetBlk?.close()
            if (virtioTargetBlk != null && activeVirtioTargetBlk === virtioTargetBlk) {
                activeVirtioTargetBlk = null
            }
            virtioNet?.reset()
            if (virtioNet != null && activeVirtioNet === virtioNet) {
                activeVirtioNet = null
            }
        }
    }

    private fun startUiSyncLoop() {
        uiSyncJob?.cancel()
        uiSyncJob = scope.launch {
            while (isActive) {
                delay(30L)
                if (terminalDirty) {
                    terminalDirty = false
                    val snap = terminal.snapshot()
                    _state.update { it.copy(terminalSnapshot = snap) }
                }
            }
        }
    }

    fun pauseVm(fromBackgroundAutoPause: Boolean = false) {
        if (_state.value.status != VmStatus.RUNNING) return
        isPausedFlag = true
        virtualClock?.pause()
        // Wake immediately if parked in WFI so Pause transitions cleanly (< 5ms)
        wakeSignal.trySend(Unit)
        _state.update {
            it.copy(
                status = VmStatus.PAUSED,
                instructionsPerSecond = 0L,
                autoPausedByBackground = fromBackgroundAutoPause,
                registerSnapshot = cpuCore?.let(::captureRegisters) ?: it.registerSnapshot
            )
        }
        updateForegroundNotification()
    }

    fun resumeVm() {
        if (_state.value.status != VmStatus.PAUSED) return
        isPausedFlag = false
        virtualClock?.resume()
        _state.update {
            it.copy(
                status = VmStatus.RUNNING,
                autoPausedByBackground = false
            )
        }
        wakeSignal.trySend(Unit)
        activeProfile?.let { startForegroundServiceIfPermitted(it.name) }
    }

    fun resetVm() {
        val profile = activeProfile ?: return
        startVm(profile, ramOverrideMb = _state.value.allocatedRamMb.takeIf { it > 0 })
    }

    fun stopVm() {
        stopInternal(clearTerminal = false)
        _state.update {
            it.copy(
                activeVmId = null,
                status = VmStatus.STOPPED,
                instructionsPerSecond = 0L,
                isWfiSleeping = false,
                autoPausedByBackground = false
            )
        }
    }

    fun retryBootWithFallbackRam(ramMb: Int) {
        val profile = activeProfile ?: return
        startVm(profile, ramOverrideMb = ramMb)
    }

    /**
     * Native Eject ISO / Switch Primary Disk operation:
     * 1. Clears installer ISO attachment (`/dev/vda`) and switches primary boot slot to `/dev/vdb` in Room DB.
     * 2. Detaches `/dev/vda` [VirtioBlockDevice] from the active CPU core cleanly via native Kotlin engine.
     * 3. Promotes `/dev/vdb` [VirtioBlockDevice] `bootindex = 0` and rebuilds the FDT without `/dev/vda`.
     */
    suspend fun ejectIsoAndSwitchPrimaryDisk(profileId: Long? = null): Result<VmProfile> = runCatching {
        val targetId = profileId ?: activeProfile?.id
            ?: throw IllegalStateException("Tidak ada profil VM untuk Eject ISO.")
        val updated = vmRepository.ejectIsoAndSwitchPrimaryDisk(targetId)
            ?: throw IllegalStateException("Profil VM #$targetId tidak ditemukan.")

        if (activeProfile?.id == targetId || _state.value.activeVmId == targetId) {
            activeProfile = updated
            cpuCore?.detachPrimaryBlockDevice()
            activeVirtioBlk?.detach()
            activeVirtioBlk = null

            val targetBlk = activeVirtioTargetBlk
            targetBlk?.updateBootIndex(0)

            val ramBytes = (_state.value.allocatedRamMb.takeIf { it > 0 } ?: updated.ramSizeMb).toLong() * MB
            val arch = if (VmProfile.normalizeArchitecture(updated.architecture) == VmProfile.ARCH_RISCV32) {
                FdtTargetArch.RISCV32
            } else {
                FdtTargetArch.RISCV64
            }
            val descriptors = listOfNotNull(targetBlk).map(VirtioBlockFdtDescriptor::fromDevice)
            val rebuiltDtb = DeviceTreeBuilder.build(
                ramSizeBytes = ramBytes,
                bootArgs = updated.bootArgs,
                timebaseFrequencyHz = updated.clockMhz * 1_000_000,
                hasVirtioBlock = descriptors.isNotEmpty(),
                hasVirtioNet = activeVirtioNet != null,
                framebufferConfig = DeviceTreeBuilder.FramebufferConfig(
                    width = _state.value.framebuffer.width,
                    height = _state.value.framebuffer.height,
                    format = _state.value.framebuffer.format
                ),
                targetArch = arch,
                virtioBlockDevices = descriptors
            )
            activeDtbBytes = rebuiltDtb
            val parsed = runCatching { FdtInspector.parse(rebuiltDtb) }.getOrNull()

            _state.update {
                it.copy(
                    attachedIsoName = null,
                    primaryBootSlot = VmProfile.BOOT_SLOT_DISK,
                    virtioBlockNodes = parsed?.virtioBlockNodes.orEmpty()
                )
            }
        }
        updated
    }

    fun activeBlockDevicesSnapshot(): Pair<VirtioBlockDevice?, VirtioBlockDevice?> =
        activeVirtioBlk to activeVirtioTargetBlk

    fun onAppBackgrounded() {
        val profile = activeProfile ?: return
        if (_state.value.status != VmStatus.RUNNING) return
        // Default mandatory fallback: auto-pause if user opted in OR if notification/FGS isn't available
        val fgsRunning = VmForegroundService.isRunning
        if (profile.autoPauseInBackground || !notificationPermissionGranted || !fgsRunning) {
            pauseVm(fromBackgroundAutoPause = true)
        }
    }

    fun sendTerminalBytes(bytes: ByteArray) {
        val core = cpuCore ?: return
        if (_state.value.status != VmStatus.RUNNING) return
        if (_state.value.ctrlStickyActive && bytes.size == 1) {
            val ch = bytes[0].toInt() and 0xFF
            val ctrlByte = mapCtrlByte(ch)
            _state.update { it.copy(ctrlStickyActive = false) }
            core.uart.pushRxByte(ctrlByte)
        } else if (_state.value.altStickyActive) {
            _state.update { it.copy(altStickyActive = false) }
            core.uart.pushRxByte(0x1B)
            core.uart.pushRxBytes(bytes)
        } else {
            core.uart.pushRxBytes(bytes)
        }
        wakeSignal.trySend(Unit)
    }

    fun sendRawControlBytes(bytes: ByteArray) {
        val core = cpuCore ?: return
        if (_state.value.status != VmStatus.RUNNING) return
        core.uart.pushRxBytes(bytes)
        wakeSignal.trySend(Unit)
    }

    fun toggleCtrlSticky() {
        _state.update { it.copy(ctrlStickyActive = !it.ctrlStickyActive) }
    }

    fun toggleAltSticky() {
        _state.update { it.copy(altStickyActive = !it.altStickyActive) }
    }

    fun setTerminalTheme(theme: AnsiTerminalBuffer.ColorTheme) {
        terminal.setTheme(theme)
        _state.update {
            it.copy(
                terminalTheme = theme,
                terminalSnapshot = terminal.snapshot()
            )
        }
    }

    fun resizeTerminal(cols: Int, rows: Int) {
        terminal.resize(cols, rows)
        _state.update { it.copy(terminalSnapshot = terminal.snapshot()) }
    }

    fun clearTerminal() {
        terminal.reset()
        _state.update { it.copy(terminalSnapshot = terminal.snapshot()) }
    }

    fun adjustFontSize(deltaSp: Float) {
        _state.update {
            val next = (it.terminalFontSizeSp + deltaSp).coerceIn(8f, 24f)
            it.copy(terminalFontSizeSp = next)
        }
    }

    fun setFontSize(sizeSp: Float) {
        _state.update {
            val next = sizeSp.coerceIn(8f, 24f)
            it.copy(terminalFontSizeSp = next)
        }
    }

    fun captureFramebufferBitmap(): Bitmap? {
        val core = cpuCore ?: return null
        val fb = _state.value.framebuffer
        if (!fb.enabled) return null
        val w = fb.width
        val h = fb.height
        val totalPixels = w * h
        if (totalPixels <= 0) return null

        val fbPhysAddr = fb.baseAddress.toInt()
        val ramBase = Rv32ImaCpuCore.RAM_BASE
        val startOffset = fbPhysAddr - ramBase
        if (startOffset < 0 || startOffset + (totalPixels * 2) > core.ramSizeBytes) {
            return null
        }

        val argbPixels = IntArray(totalPixels)
        val ram = core.ram
        var pixelIdx = 0
        val startWord = startOffset ushr 2
        val totalWords = totalPixels ushr 1

        for (wordIdx in 0 until totalWords) {
            val word = ram[startWord + wordIdx]
            // Lower 16-bit RGB565
            val p0 = word and 0xFFFF
            val r0 = ((p0 ushr 11) and 0x1F) * 255 / 31
            val g0 = ((p0 ushr 5) and 0x3F) * 255 / 63
            val b0 = (p0 and 0x1F) * 255 / 31
            argbPixels[pixelIdx++] = (0xFF shl 24) or (r0 shl 16) or (g0 shl 8) or b0

            // Upper 16-bit RGB565
            val p1 = (word ushr 16) and 0xFFFF
            val r1 = ((p1 ushr 11) and 0x1F) * 255 / 31
            val g1 = ((p1 ushr 5) and 0x3F) * 255 / 63
            val b1 = (p1 and 0x1F) * 255 / 31
            argbPixels[pixelIdx++] = (0xFF shl 24) or (r1 shl 16) or (g1 shl 8) or b1
        }

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(argbPixels, 0, w, 0, 0, w, h)
        return bitmap
    }

    fun getPlainTerminalLog(): String = terminal.snapshot().plainText()

    suspend fun exportLogToVmStorage(): Result<String> {
        val profile = activeProfile
            ?: return Result.failure(IllegalStateException("Tidak ada sesi VM aktif."))
        return imageRepository.saveConsoleLogForVm(profile, getPlainTerminalLog())
            .map { it.name }
    }

    fun dismissError() {
        _state.update { it.copy(errorMessage = null, oomFallbackSuggestedRamMb = null) }
    }

    private fun stopInternal(clearTerminal: Boolean) {
        activeSessionSequence.incrementAndGet()
        isPausedFlag = false
        wakeSignal.trySend(Unit)
        val oldRunner = runnerJob
        runnerJob = null
        oldRunner?.cancel()
        uiSyncJob?.cancel()
        uiSyncJob = null
        virtualClock?.pause()
        virtualClock = null
        if (oldRunner == null || !oldRunner.isActive) {
            activeVirtioBlk?.close()
            activeVirtioBlk = null
            activeVirtioTargetBlk?.close()
            activeVirtioTargetBlk = null
            activeVirtioNet?.reset()
            activeVirtioNet = null
        }
        cpuCore = null
        activeDtbBytes = null
        stopForegroundServiceSafe()
        if (clearTerminal) {
            terminal.reset()
            terminalDirty = false
            _state.update { it.copy(terminalSnapshot = terminal.snapshot()) }
        }
    }

    private fun publishError(message: String, suggestedRamMb: Int?) {
        stopForegroundServiceSafe()
        _state.update {
            it.copy(
                status = VmStatus.ERROR,
                errorMessage = message,
                oomFallbackSuggestedRamMb = suggestedRamMb,
                instructionsPerSecond = 0L,
                isWfiSleeping = false
            )
        }
    }

    private fun startForegroundServiceIfPermitted(vmName: String) {
        if (!notificationPermissionGranted) return
        val profile = activeProfile ?: return
        if (profile.autoPauseInBackground) return
        VmForegroundService.startOrUpdate(context, vmName, isPaused = false)
    }

    private fun updateForegroundNotification() {
        if (!VmForegroundService.isRunning) return
        val name = _state.value.vmName
        val paused = _state.value.status == VmStatus.PAUSED
        VmForegroundService.startOrUpdate(context, name, isPaused = paused)
    }

    private fun stopForegroundServiceSafe() {
        VmForegroundService.stop(context)
    }

    private fun captureRegisters(core: Rv32ImaCpuCore): RegisterSnapshot {
        return RegisterSnapshot(
            pc = core.pc,
            regs = core.regs.copyOf(),
            misa = Rv32ImaCpuCore.MISA_RV32IMA,
            mstatus = core.mstatus,
            mcause = core.mcause,
            mepc = core.mepc,
            mtval = core.mtval,
            mie = core.mie,
            mip = core.mip,
            mtvec = core.mtvec,
            mtime = core.lastMtime,
            mtimecmp = core.mtimecmp,
            uartRxBuffered = core.uart.rxBufferedCount()
        )
    }

    private fun mapCtrlByte(ch: Int): Int {
        return when (ch) {
            in 'a'.code..'z'.code -> ch - 'a'.code + 1
            in 'A'.code..'Z'.code -> ch - 'A'.code + 1
            '['.code -> 0x1B
            '\\'.code -> 0x1C
            ']'.code -> 0x1D
            '^'.code -> 0x1E
            '_'.code -> 0x1F
            else -> ch
        }
    }

    companion object {
        private const val MB = 1024L * 1024L
        private const val INSTRUCTIONS_PER_SLICE = 65_536
        private const val WFI_MAX_SLEEP_MS = 25L
    }
}
