package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Eject
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.CpuThreadPriority
import com.example.data.ImageKind
import com.example.data.ImageRepository
import com.example.data.LocalImage
import com.example.data.VmProfile
import com.example.data.VmRepository
import com.example.emulator.DeviceTreeBuilder
import com.example.emulator.DiagnosticMonitorRom
import com.example.emulator.ParsedFdtSummary
import com.example.ui.theme.StatusPaused
import com.example.ui.theme.TerminalBorder
import com.example.ui.theme.TerminalSlateContainer
import com.example.ui.theme.TerminalSlatePrimary
import com.example.ui.theme.TerminalSurfaceElevated
import com.example.ui.theme.TerminalTextPrimary
import com.example.ui.theme.TerminalTextSecondary
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VmEditorDialog(
    initialProfile: VmProfile?,
    availableKernels: List<LocalImage>,
    availableDtbs: List<LocalImage>,
    availableIsos: List<LocalImage>,
    canAllocate128Mb: Boolean,
    maxHeapMb: Int,
    maxSupportedCpuThreads: Int,
    inspectedDtbSummary: ParsedFdtSummary?,
    onInspectCustomDtb: (Long?) -> Unit,
    onDismiss: () -> Unit,
    onSave: (VmProfile) -> Unit,
    availableTargetDisks: List<LocalImage> = emptyList(),
    onEjectIsoAndSwitchPrimaryDisk: ((Long) -> Unit)? = null
) {
    var name by remember { mutableStateOf(initialProfile?.name ?: "") }
    var description by remember { mutableStateOf(initialProfile?.description ?: "") }
    var selectedArch by remember {
        mutableStateOf(
            if (initialProfile?.architecture.equals(VmProfile.ARCH_RISCV32, ignoreCase = true)) {
                VmProfile.ARCH_RISCV32
            } else {
                VmProfile.ARCH_RISCV64
            }
        )
    }
    var kernelImageId by remember {
        mutableLongStateOf(
            initialProfile?.kernelImageId
                ?: availableKernels.firstOrNull()?.id
                ?: DiagnosticMonitorRom.BUILTIN_IMAGE_ID
        )
    }
    var customDtbId by remember { mutableStateOf(initialProfile?.customDtbImageId) }
    var attachedIsoId by remember { mutableStateOf(initialProfile?.attachedIsoImageId) }
    var targetDiskId by remember { mutableStateOf(initialProfile?.targetDiskImageId) }
    var primaryBootSlot by remember {
        mutableStateOf(initialProfile?.resolvePrimaryBootSlot() ?: VmProfile.BOOT_SLOT_ISO)
    }
    var ramSizeMb by remember { mutableIntStateOf(initialProfile?.ramSizeMb ?: 64) }
    var cpuThreadCount by remember { mutableIntStateOf(initialProfile?.cpuThreadCount ?: 1) }
    var cpuQuotaPercent by remember { mutableIntStateOf(initialProfile?.cpuQuotaPercent ?: 100) }
    var threadPriority by remember {
        mutableStateOf(initialProfile?.resolvedThreadPriority() ?: CpuThreadPriority.NORMAL)
    }
    var storageQuotaMb by remember { mutableIntStateOf(initialProfile?.storageQuotaMb ?: 128) }
    var bootArgs by remember {
        mutableStateOf(initialProfile?.bootArgs ?: DeviceTreeBuilder.DEFAULT_BOOTARGS)
    }
    var autoPauseBg by remember { mutableStateOf(initialProfile?.autoPauseInBackground ?: false) }
    var customInputRequest by remember { mutableStateOf<CustomLimitRequest?>(null) }

    LaunchedEffect(customDtbId) {
        onInspectCustomDtb(customDtbId)
    }

    val isCustomDtbActive = customDtbId != null
    val selectedKernel = availableKernels.firstOrNull { it.id == kernelImageId }
    val isBuiltInRom = selectedKernel?.resolvedKind() == ImageKind.BUILTIN_ROM

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = if (initialProfile == null) "Buat Profil VM Baru" else "Edit Profil VM",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nama Mesin Virtual") },
                    placeholder = { Text("mis. Alpine Linux RV64") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("vm_editor_name_input")
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Deskripsi (Opsional)") },
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                // Architecture Selector (RISC-V 64-bit target)
                Text(
                    text = "ARSITEKTUR VIRTUAL MACHINE",
                    style = MaterialTheme.typography.labelMedium,
                    color = TerminalTextSecondary
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = selectedArch != VmProfile.ARCH_RISCV32,
                        onClick = { selectedArch = VmProfile.ARCH_RISCV64 },
                        label = { Text("RISC-V 64-bit (RV64)", style = MaterialTheme.typography.labelSmall) },
                        colors = kernoChipColors(),
                        modifier = Modifier.testTag("arch_chip_riscv64")
                    )
                }

                // Kernel image selector
                Text(
                    text = "KERNEL IMAGE (M-MODE NO-MMU)",
                    style = MaterialTheme.typography.labelMedium,
                    color = TerminalTextSecondary
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    availableKernels.forEach { img ->
                        FilterChip(
                            selected = kernelImageId == img.id,
                            onClick = { kernelImageId = img.id },
                            label = {
                                Text(
                                    text = img.name,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            colors = kernoChipColors()
                        )
                    }
                }

                // Device Tree selector
                Text(
                    text = "DEVICE TREE BLOB (DTB)",
                    style = MaterialTheme.typography.labelMedium,
                    color = TerminalTextSecondary
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = customDtbId == null,
                        onClick = { customDtbId = null },
                        label = { Text("Otomatis (FDT Dinamis)", style = MaterialTheme.typography.labelSmall) },
                        colors = kernoChipColors(),
                        modifier = Modifier.testTag("dtb_auto_chip")
                    )
                    availableDtbs.forEach { dtb ->
                        FilterChip(
                            selected = customDtbId == dtb.id,
                            onClick = { customDtbId = dtb.id },
                            label = { Text(dtb.name, style = MaterialTheme.typography.labelSmall) },
                            colors = kernoChipColors(),
                            modifier = Modifier.testTag("dtb_custom_chip_${dtb.id}")
                        )
                    }
                }

                if (isCustomDtbActive) {
                    Surface(
                        color = TerminalSurfaceElevated,
                        border = BorderStroke(1.dp, TerminalBorder),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("custom_dtb_lock_notice")
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Info,
                                    contentDescription = "Info DTB kustom",
                                    tint = StatusPaused,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "DTB Kustom aktif — RAM, CPU & Storage tetap bebas diatur",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = StatusPaused
                                )
                            }
                            if (inspectedDtbSummary != null) {
                                val detMem = inspectedDtbSummary.detectedMemorySizeBytes?.let {
                                    ImageRepository.formatMb(it)
                                } ?: "tidak terdeteksi"
                                Text(
                                    text = "FDT v${inspectedDtbSummary.version} · ${inspectedDtbSummary.totalSize} B · /memory: $detMem",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TerminalTextSecondary
                                )
                                inspectedDtbSummary.bootArgs?.let { args ->
                                    Text(
                                        text = "bootargs DTB: $args",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TerminalTextSecondary
                                    )
                                }
                            }
                        }
                    }
                }

                // Attached User ISO Image selector (VirtIO-blk /dev/vda, read-only)
                Text(
                    text = "SLOT INSTALLER ISO (/dev/vda · Read-Only)",
                    style = MaterialTheme.typography.labelMedium,
                    color = TerminalTextSecondary
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = attachedIsoId == null,
                        onClick = { attachedIsoId = null },
                        label = { Text("Tanpa ISO", style = MaterialTheme.typography.labelSmall) },
                        colors = kernoChipColors(),
                        modifier = Modifier.testTag("iso_none_chip")
                    )
                    availableIsos.forEach { iso ->
                        FilterChip(
                            selected = attachedIsoId == iso.id,
                            onClick = { attachedIsoId = iso.id },
                            label = { Text(iso.name, style = MaterialTheme.typography.labelSmall) },
                            colors = kernoChipColors(),
                            modifier = Modifier.testTag("iso_attach_chip_${iso.id}")
                        )
                    }
                }
                if (availableIsos.isEmpty()) {
                    Text(
                        text = "Belum ada ISO RISC-V. Unduh di Store atau impor ISO RISC-V di tab Storage.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TerminalTextSecondary
                    )
                }

                // Virtual Disk Target selector (VirtIO-blk /dev/vdb, read-write)
                Text(
                    text = "VIRTUAL DISK TARGET (/dev/vdb · Read-Write)",
                    style = MaterialTheme.typography.labelMedium,
                    color = TerminalTextSecondary
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = targetDiskId == null,
                        onClick = { targetDiskId = null },
                        label = { Text("Tanpa Disk Target", style = MaterialTheme.typography.labelSmall) },
                        colors = kernoChipColors(),
                        modifier = Modifier.testTag("target_disk_none_chip")
                    )
                    availableTargetDisks.forEach { disk ->
                        FilterChip(
                            selected = targetDiskId == disk.id,
                            onClick = { targetDiskId = disk.id },
                            label = { Text(disk.name, style = MaterialTheme.typography.labelSmall) },
                            colors = kernoChipColors(),
                            modifier = Modifier.testTag("target_disk_attach_chip_${disk.id}")
                        )
                    }
                }
                if (availableTargetDisks.isEmpty()) {
                    Text(
                        text = "Belum ada Virtual Disk Target (.img / Alpine RV64 Rootfs). Unduh di Store atau buat disk .IMG di tab Storage.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TerminalTextSecondary
                    )
                }

                // Deterministic bootindex ordering & native Eject ISO / Switch Primary Disk action
                if (attachedIsoId != null || targetDiskId != null) {
                    Text(
                        text = "URUTAN BOOT (FDT bootindex)",
                        style = MaterialTheme.typography.labelMedium,
                        color = TerminalTextSecondary
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (attachedIsoId != null) {
                            FilterChip(
                                selected = primaryBootSlot == VmProfile.BOOT_SLOT_ISO,
                                onClick = { primaryBootSlot = VmProfile.BOOT_SLOT_ISO },
                                label = { Text("Prioritas ISO (/dev/vda · bootindex=0)", style = MaterialTheme.typography.labelSmall) },
                                colors = kernoChipColors(),
                                modifier = Modifier.testTag("boot_priority_iso_chip")
                            )
                        }
                        if (targetDiskId != null) {
                            FilterChip(
                                selected = primaryBootSlot == VmProfile.BOOT_SLOT_DISK || attachedIsoId == null,
                                onClick = { primaryBootSlot = VmProfile.BOOT_SLOT_DISK },
                                label = { Text("Prioritas Disk Target (/dev/vdb · bootindex=0)", style = MaterialTheme.typography.labelSmall) },
                                colors = kernoChipColors(),
                                modifier = Modifier.testTag("boot_priority_disk_chip")
                            )
                        }
                    }

                    if (attachedIsoId != null) {
                        OutlinedButton(
                            onClick = {
                                attachedIsoId = null
                                primaryBootSlot = VmProfile.BOOT_SLOT_DISK
                                initialProfile?.id?.takeIf { it > 0L }?.let { pid ->
                                    onEjectIsoAndSwitchPrimaryDisk?.invoke(pid)
                                }
                            },
                            border = BorderStroke(1.dp, TerminalBorder),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = TerminalSurfaceElevated,
                                contentColor = TerminalSlatePrimary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("eject_iso_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Eject,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "  Eject ISO / Switch Primary Disk (/dev/vdb)",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }

                HorizontalDivider(color = TerminalBorder)

                // === COMPACT UNLOCKED PER-VM RESOURCE LIMITS (SLIDER + CUSTOM INPUT) ===
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "BATASAN RESOURCE (GESER / KETUK NILAI)",
                        style = MaterialTheme.typography.labelMedium,
                        color = TerminalSlatePrimary
                    )
                    Text(
                        text = "Heap ${maxHeapMb}MB · ${maxSupportedCpuThreads}C",
                        style = MaterialTheme.typography.labelSmall,
                        color = TerminalTextSecondary
                    )
                }

                // 1. RAM (Memory) Slider — unlocked
                val ramAdvisory: Pair<String, Boolean>? = when {
                    !isBuiltInRom && ramSizeMb < 32 ->
                        "RAM < 32 MB sangat ketat untuk Linux No-MMU (64 MB disarankan)." to true
                    ramSizeMb >= 128 && !canAllocate128Mb ->
                        "Melebihi batas aman heap ART ($maxHeapMb MB); dapat memicu OOM saat boot." to true
                    isCustomDtbActive ->
                        "DTB kustom aktif; ukuran RAM emulator tetap mengikuti pengaturan ini." to false
                    else -> null
                }
                CompactResourceSliderRow(
                    icon = Icons.Outlined.Memory,
                    label = "Memori (RAM)",
                    valueLabel = "$ramSizeMb MB",
                    sliderValue = ramSizeMb.coerceIn(VmRepository.MIN_RAM_MB, VmRepository.MAX_RAM_SLIDER_MB).toFloat(),
                    valueRange = VmRepository.MIN_RAM_MB.toFloat()..VmRepository.MAX_RAM_SLIDER_MB.toFloat(),
                    stepSize = VmRepository.RAM_STEP_MB,
                    onValueChange = { ramSizeMb = it },
                    onTapCustomValue = {
                        customInputRequest = CustomLimitRequest(
                            title = "Atur RAM Kustom (MB)",
                            unit = "MB",
                            currentValue = ramSizeMb,
                            min = VmRepository.MIN_RAM_MB,
                            max = VmRepository.MAX_RAM_CUSTOM_MB,
                            onApply = { ramSizeMb = it }
                        )
                    },
                    advisoryText = ramAdvisory?.first,
                    advisoryIsWarning = ramAdvisory?.second ?: false,
                    sliderTestTag = "ram_slider",
                    valueTestTag = "ram_custom_value_button"
                )

                // 2. CPU Threads Slider — unlocked
                val threadAdvisory: Pair<String, Boolean>? = when {
                    selectedArch != VmProfile.ARCH_RISCV32 ->
                        "Target RV64 aktif. Thread ekstra disiapkan untuk eksekusi I/O & perangkat virtual VirtIO dual-drive." to false
                    else ->
                        "RV32IMA berjalan single-hart (1 core aktif). Thread ekstra disiapkan untuk eksekusi I/O & perangkat virtual." to false
                }
                CompactResourceSliderRow(
                    icon = Icons.Outlined.Speed,
                    label = "CPU Thread",
                    valueLabel = "$cpuThreadCount Thread",
                    sliderValue = cpuThreadCount.coerceIn(VmRepository.MIN_CPU_THREADS, VmRepository.MAX_CPU_THREADS).toFloat(),
                    valueRange = VmRepository.MIN_CPU_THREADS.toFloat()..VmRepository.MAX_CPU_THREADS.toFloat(),
                    stepSize = 1,
                    onValueChange = { cpuThreadCount = it },
                    onTapCustomValue = {
                        customInputRequest = CustomLimitRequest(
                            title = "Atur Jumlah Thread CPU",
                            unit = "Thread",
                            currentValue = cpuThreadCount,
                            min = VmRepository.MIN_CPU_THREADS,
                            max = VmRepository.MAX_CPU_THREADS,
                            onApply = { cpuThreadCount = it }
                        )
                    },
                    advisoryText = threadAdvisory?.first,
                    advisoryIsWarning = false,
                    sliderTestTag = "cpu_threads_slider",
                    valueTestTag = "cpu_threads_custom_value_button"
                )

                // 3. CPU Quota (%) Slider — unlocked
                CompactResourceSliderRow(
                    icon = Icons.Outlined.Tune,
                    label = "Kuota CPU",
                    valueLabel = "$cpuQuotaPercent%",
                    sliderValue = cpuQuotaPercent.coerceIn(VmRepository.MIN_CPU_QUOTA, VmRepository.MAX_CPU_QUOTA).toFloat(),
                    valueRange = VmRepository.MIN_CPU_QUOTA.toFloat()..VmRepository.MAX_CPU_QUOTA.toFloat(),
                    stepSize = VmRepository.CPU_QUOTA_STEP,
                    onValueChange = { cpuQuotaPercent = it },
                    onTapCustomValue = {
                        customInputRequest = CustomLimitRequest(
                            title = "Atur Kuota CPU (%)",
                            unit = "%",
                            currentValue = cpuQuotaPercent,
                            min = VmRepository.MIN_CPU_QUOTA,
                            max = VmRepository.MAX_CPU_QUOTA,
                            onApply = { cpuQuotaPercent = it }
                        )
                    },
                    advisoryText = null,
                    advisoryIsWarning = false,
                    sliderTestTag = "cpu_quota_slider",
                    valueTestTag = "cpu_quota_custom_value_button"
                )

                // Compact inline CPU thread priority selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Prioritas Thread",
                        style = MaterialTheme.typography.labelSmall,
                        color = TerminalTextSecondary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CpuThreadPriority.entries.forEach { prio ->
                            val shortLabel = if (prio == CpuThreadPriority.NORMAL) "Normal" else "Hemat Daya"
                            FilterChip(
                                selected = threadPriority == prio,
                                onClick = { threadPriority = prio },
                                label = { Text(shortLabel, style = MaterialTheme.typography.labelSmall) },
                                colors = kernoChipColors(),
                                modifier = Modifier.testTag("cpu_prio_${prio.name}")
                            )
                        }
                    }
                }

                // 4. Storage Quota Slider — unlocked
                val storageDisplayLabel = if (storageQuotaMb >= 1024 && storageQuotaMb % 1024 == 0) {
                    "${storageQuotaMb / 1024} GB ($storageQuotaMb MB)"
                } else {
                    "$storageQuotaMb MB"
                }
                CompactResourceSliderRow(
                    icon = Icons.Outlined.Storage,
                    label = "Kuota Storage",
                    valueLabel = storageDisplayLabel,
                    sliderValue = storageQuotaMb.coerceIn(
                        VmRepository.MIN_STORAGE_QUOTA_MB,
                        VmRepository.MAX_STORAGE_QUOTA_SLIDER_MB
                    ).toFloat(),
                    valueRange = VmRepository.MIN_STORAGE_QUOTA_MB.toFloat()..VmRepository.MAX_STORAGE_QUOTA_SLIDER_MB.toFloat(),
                    stepSize = VmRepository.STORAGE_STEP_MB,
                    onValueChange = { storageQuotaMb = it },
                    onTapCustomValue = {
                        customInputRequest = CustomLimitRequest(
                            title = "Atur Kuota Storage (MB)",
                            unit = "MB",
                            currentValue = storageQuotaMb,
                            min = VmRepository.MIN_STORAGE_QUOTA_CUSTOM_MB,
                            max = VmRepository.MAX_STORAGE_QUOTA_CUSTOM_MB,
                            onApply = { storageQuotaMb = it }
                        )
                    },
                    advisoryText = null,
                    advisoryIsWarning = false,
                    sliderTestTag = "storage_quota_slider",
                    valueTestTag = "storage_quota_custom_value_button"
                )

                HorizontalDivider(color = TerminalBorder)

                // Kernel bootargs (editable even when Custom DTB is selected, with subtle hint)
                OutlinedTextField(
                    value = bootArgs,
                    onValueChange = { bootArgs = it },
                    label = {
                        Text(
                            if (isCustomDtbActive) "Kernel bootargs (fallback jika DTB tanpa /chosen)"
                            else "Kernel bootargs (ttyS0 polling)"
                        )
                    },
                    singleLine = false,
                    maxLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("vm_editor_bootargs_input")
                )

                // Auto-pause background toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = "Auto-Pause di Latar Belakang",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Membekukan mtime & menghemat daya saat pindah aplikasi (juga aktif otomatis jika izin notifikasi ditolak).",
                            style = MaterialTheme.typography.bodySmall,
                            color = TerminalTextSecondary
                        )
                    }
                    Switch(
                        checked = autoPauseBg,
                        onCheckedChange = { autoPauseBg = it },
                        modifier = Modifier.testTag("vm_editor_autopause_switch")
                    )
                }

                Text(
                    text = stringResource(R.string.ram_only_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = TerminalTextSecondary
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val engine = VmProfile.ENGINE_KERNO_KOTLIN
                    val normalizedArch = VmProfile.normalizeArchitecture(selectedArch)
                    val normalizedBoot = if (attachedIsoId == null && targetDiskId != null) {
                        VmProfile.BOOT_SLOT_DISK
                    } else {
                        primaryBootSlot
                    }
                    val profile = VmProfile(
                        id = initialProfile?.id ?: 0L,
                        name = name.trim().ifBlank { "RV64 VM" },
                        description = description.trim(),
                        ramSizeMb = ramSizeMb,
                        cpuThreadCount = cpuThreadCount,
                        cpuQuotaPercent = cpuQuotaPercent,
                        cpuThreadPriority = threadPriority.name,
                        storageQuotaMb = storageQuotaMb,
                        kernelImageId = kernelImageId,
                        customDtbImageId = customDtbId,
                        attachedIsoImageId = attachedIsoId,
                        targetDiskImageId = targetDiskId,
                        primaryBootSlot = normalizedBoot,
                        bootArgs = bootArgs.trim().ifBlank { DeviceTreeBuilder.DEFAULT_BOOTARGS },
                        clockMhz = 1,
                        autoPauseInBackground = autoPauseBg,
                        lastBootedAt = initialProfile?.lastBootedAt ?: 0L,
                        architecture = normalizedArch,
                        engineType = engine
                    )
                    onSave(profile)
                },
                modifier = Modifier.testTag("vm_editor_save_button")
            ) {
                Text("Simpan")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal")
            }
        }
    )

    customInputRequest?.let { req ->
        CustomLimitInputDialog(
            request = req,
            onDismiss = { customInputRequest = null }
        )
    }
}

private data class CustomLimitRequest(
    val title: String,
    val unit: String,
    val currentValue: Int,
    val min: Int,
    val max: Int,
    val onApply: (Int) -> Unit
)

@Composable
private fun CompactResourceSliderRow(
    icon: ImageVector,
    label: String,
    valueLabel: String,
    sliderValue: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    stepSize: Int,
    onValueChange: (Int) -> Unit,
    onTapCustomValue: () -> Unit,
    advisoryText: String?,
    advisoryIsWarning: Boolean,
    sliderTestTag: String,
    valueTestTag: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = TerminalTextSecondary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = TerminalTextPrimary
                )
            }
            Surface(
                color = TerminalSurfaceElevated,
                border = BorderStroke(1.dp, TerminalBorder),
                shape = MaterialTheme.shapes.extraSmall,
                modifier = Modifier
                    .clickable(onClick = onTapCustomValue)
                    .testTag(valueTestTag)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = valueLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = TerminalTextPrimary
                    )
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = "Ubah nilai kustom $label",
                        tint = TerminalTextSecondary,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }

        Slider(
            value = sliderValue,
            onValueChange = { raw ->
                val snapped = ((raw / stepSize).roundToInt() * stepSize)
                    .coerceIn(valueRange.start.toInt(), valueRange.endInclusive.toInt())
                onValueChange(snapped)
            },
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = TerminalSlatePrimary,
                activeTrackColor = TerminalSlatePrimary,
                inactiveTrackColor = TerminalBorder
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(sliderTestTag)
        )

        if (advisoryText != null) {
            Text(
                text = advisoryText,
                style = MaterialTheme.typography.bodySmall,
                color = if (advisoryIsWarning) StatusPaused else TerminalTextSecondary
            )
        }
    }
}

@Composable
private fun CustomLimitInputDialog(
    request: CustomLimitRequest,
    onDismiss: () -> Unit
) {
    var rawText by remember(request) { mutableStateOf(request.currentValue.toString()) }
    val parsed = rawText.trim().toIntOrNull()
    val isValid = parsed != null && parsed in request.min..request.max

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(text = request.title, style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = rawText,
                    onValueChange = { input -> rawText = input.filter { it.isDigit() } },
                    label = { Text("Nilai (${request.min}–${request.max} ${request.unit})") },
                    singleLine = true,
                    isError = rawText.isNotEmpty() && !isValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("custom_limit_number_input")
                )
                Text(
                    text = "Masukkan angka kustom antara ${request.min} dan ${request.max} ${request.unit}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TerminalTextSecondary
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (parsed != null && isValid) {
                        request.onApply(parsed)
                        onDismiss()
                    }
                },
                enabled = isValid,
                modifier = Modifier.testTag("custom_limit_apply_button")
            ) {
                Text("Terapkan")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal")
            }
        }
    )
}

@Composable
private fun kernoChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = TerminalSlateContainer,
    selectedLabelColor = TerminalTextPrimary,
    labelColor = TerminalTextSecondary,
    selectedLeadingIconColor = TerminalSlatePrimary
)
