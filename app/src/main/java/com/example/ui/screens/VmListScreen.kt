package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.ImageRepository
import com.example.data.VmProfile
import com.example.ui.theme.StatusIdle
import com.example.ui.theme.StatusIdleBg
import com.example.ui.theme.StatusRunning
import com.example.ui.theme.StatusRunningBg
import com.example.ui.theme.TerminalBorder
import com.example.ui.theme.TerminalCanvasBg
import com.example.ui.theme.TerminalSlateContainer
import com.example.ui.theme.TerminalSlatePrimary
import com.example.ui.theme.TerminalSurface
import com.example.ui.theme.TerminalSurfaceElevated
import com.example.ui.theme.TerminalTextPrimary
import com.example.ui.theme.TerminalTextSecondary
import com.example.ui.viewmodel.VmListUiState
import com.example.ui.viewmodel.VmListViewModel
import com.example.ui.viewmodel.VmProfileUiItem

@Composable
fun VmListScreen(
    viewModel: VmListViewModel,
    onOpenConsole: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val availableUpdate by viewModel.availableUpdate.collectAsStateWithLifecycle()
    val isCheckingUpdate by viewModel.isCheckingUpdate.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showEditor by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<VmProfile?>(null) }
    var deletingProfile by remember { mutableStateOf<VmProfile?>(null) }

    LaunchedEffect(uiState.feedbackMessage) {
        uiState.feedbackMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearFeedback()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = TerminalCanvasBg,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editingProfile = null
                    showEditor = true
                },
                containerColor = TerminalSlateContainer,
                contentColor = TerminalTextPrimary,
                modifier = Modifier.testTag("add_vm_fab")
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Buat profil VM baru")
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 680.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    VmListHeader(
                        uiState = uiState,
                        onOpenConsole = onOpenConsole,
                        onOpenAbout = { showAboutDialog = true }
                    )
                }

                if (uiState.items.isEmpty()) {
                    item {
                        Surface(
                            color = TerminalSurface,
                            border = BorderStroke(1.dp, TerminalBorder),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Belum ada profil mesin virtual",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "Tekan tombol + untuk membuat konfigurasi RV32IMA M-Mode No-MMU baru.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TerminalTextSecondary
                                )
                            }
                        }
                    }
                } else {
                    items(uiState.items, key = { it.profile.id }) { item ->
                        val isThisActive = uiState.isAnyVmActive && uiState.activeVmId == item.profile.id
                        val blockedByOtherVm = uiState.isAnyVmActive && uiState.activeVmId != item.profile.id
                        VmProfileCard(
                            item = item,
                            isActive = isThisActive,
                            blockedByOtherVm = blockedByOtherVm,
                            activeOtherVmName = uiState.activeVmName,
                            onBoot = {
                                if (isThisActive) {
                                    onOpenConsole()
                                } else if (viewModel.bootVm(item.profile)) {
                                    onOpenConsole()
                                }
                            },
                            onEdit = {
                                editingProfile = item.profile
                                showEditor = true
                            },
                            onDelete = { deletingProfile = item.profile }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(72.dp)) }
            }
        }
    }

    if (showEditor) {
        VmEditorDialog(
            initialProfile = editingProfile,
            availableKernels = uiState.availableKernels,
            availableDtbs = uiState.availableDtbs,
            availableIsos = uiState.availableIsos,
            canAllocate128Mb = uiState.canAllocate128Mb,
            maxHeapMb = uiState.maxHeapMb,
            maxSupportedCpuThreads = uiState.maxSupportedCpuThreads,
            inspectedDtbSummary = uiState.inspectedDtbSummary,
            onInspectCustomDtb = viewModel::inspectCustomDtb,
            onDismiss = { showEditor = false },
            onSave = { saved ->
                viewModel.saveProfile(saved)
                showEditor = false
            },
            availableTargetDisks = uiState.availableTargetDisks,
            onEjectIsoAndSwitchPrimaryDisk = { profileId ->
                viewModel.ejectIsoAndSwitchPrimaryDisk(profileId)
                showEditor = false
            }
        )
    }

    deletingProfile?.let { target ->
        AlertDialog(
            onDismissRequest = { deletingProfile = null },
            containerColor = TerminalSurface,
            title = { Text("Hapus Profil VM?", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    text = "Profil '${target.name}' akan dihapus dari daftar lokal. File kernel di tab Image tidak ikut terhapus.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteProfile(target)
                        deletingProfile = null
                    },
                    modifier = Modifier.testTag("confirm_delete_vm_button")
                ) {
                    Text("Hapus")
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingProfile = null }) {
                    Text("Batal")
                }
            }
        )
    }

    if (showAboutDialog) {
        AboutDialog(
            isCheckingUpdate = isCheckingUpdate,
            onDismiss = { showAboutDialog = false },
            onCheckUpdate = {
                viewModel.checkForUpdates(manual = true)
            }
        )
    }

    availableUpdate?.let { updateInfo ->
        UpdateNoticeDialog(
            updateInfo = updateInfo,
            onDismiss = { viewModel.dismissUpdate() }
        )
    }
}

@Composable
private fun VmListHeader(
    uiState: VmListUiState,
    onOpenConsole: () -> Unit,
    onOpenAbout: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "KernoVM // RV64",
                    style = MaterialTheme.typography.headlineSmall,
                    color = TerminalTextPrimary
                )
                Text(
                    text = "Emulator Linux RISC-V 64-bit (RV64) · Tanpa Root",
                    style = MaterialTheme.typography.bodySmall,
                    color = TerminalTextSecondary
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    color = TerminalSurfaceElevated,
                    border = BorderStroke(1.dp, TerminalBorder),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = "HEAP ${uiState.maxHeapMb}MB",
                        style = MaterialTheme.typography.labelSmall,
                        color = TerminalTextSecondary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                IconButton(
                    onClick = onOpenAbout,
                    modifier = Modifier.testTag("about_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = "Tentang KernoVM & Lisensi",
                        tint = TerminalSlatePrimary
                    )
                }
            }
        }

        // Subtle architecture & RAM-only strip
        Surface(
            color = TerminalSurface,
            border = BorderStroke(1.dp, TerminalBorder),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = TerminalTextSecondary
                )
                Text(
                    text = stringResource(R.string.mmode_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = TerminalTextSecondary
                )
            }
        }

        if (uiState.isAnyVmActive) {
            Surface(
                color = StatusRunningBg,
                border = BorderStroke(1.dp, StatusRunning.copy(alpha = 0.45f)),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "SESI AKTIF (SINGLE-VM)",
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusRunning
                        )
                        Text(
                            text = uiState.activeVmName,
                            style = MaterialTheme.typography.titleSmall,
                            color = TerminalTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    OutlinedButton(
                        onClick = onOpenConsole,
                        border = BorderStroke(1.dp, StatusRunning.copy(alpha = 0.6f)),
                        modifier = Modifier.testTag("open_active_console_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Terminal,
                            contentDescription = null,
                            tint = StatusRunning
                        )
                        Spacer(modifier = Modifier.padding(horizontal = 3.dp))
                        Text("Konsol", color = TerminalTextPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun VmProfileCard(
    item: VmProfileUiItem,
    isActive: Boolean,
    blockedByOtherVm: Boolean,
    activeOtherVmName: String,
    onBoot: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val profile = item.profile
    val quotaBytes = profile.storageQuotaMb.toLong() * 1024L * 1024L
    val storageFraction = (item.storageUsedBytes.toFloat() / quotaBytes.coerceAtLeast(1L).toFloat())
        .coerceIn(0f, 1f)

    Surface(
        color = TerminalSurface,
        border = BorderStroke(
            width = 1.dp,
            color = if (isActive) StatusRunning.copy(alpha = 0.6f) else TerminalBorder
        ),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("vm_card_${profile.id}")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = profile.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = TerminalTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Surface(
                            color = if (isActive) StatusRunningBg else StatusIdleBg,
                            shape = MaterialTheme.shapes.extraSmall
                        ) {
                            Text(
                                text = if (isActive) "AKTIF" else "SIAP",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isActive) StatusRunning else StatusIdle,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (profile.description.isNotBlank()) {
                        Text(
                            text = profile.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = TerminalTextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
                Row {
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.testTag("edit_vm_${profile.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = "Edit profil ${profile.name}",
                            tint = TerminalTextSecondary
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.testTag("delete_vm_${profile.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = "Hapus profil ${profile.name}",
                            tint = TerminalTextSecondary
                        )
                    }
                }
            }

            // Kernel, DTB, ISO (/dev/vda) & Target Disk (/dev/vdb) metadata
            val vdaLabel = item.attachedIsoName?.let { " · /dev/vda RO: $it" } ?: ""
            val vdbLabel = item.targetDiskName?.let { " · /dev/vdb RW: $it" } ?: ""
            val bootLabel = if (item.attachedIsoName != null || item.targetDiskName != null) {
                if (profile.resolvePrimaryBootSlot() == VmProfile.BOOT_SLOT_DISK) " · Boot: /dev/vdb" else " · Boot: /dev/vda"
            } else {
                ""
            }
            Text(
                text = "Kernel: ${item.kernelName}" +
                    (if (item.customDtbName != null) " · DTB: ${item.customDtbName}" else " · DTB: Auto") +
                    vdaLabel + vdbLabel + bootLabel,
                style = MaterialTheme.typography.labelSmall,
                color = TerminalTextSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            // Per-VM resource limits badges (Memory, CPU thread/quota, Storage)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ResourceChip(
                    icon = Icons.Outlined.Memory,
                    text = "${profile.ramSizeMb} MB RAM",
                    modifier = Modifier.weight(1f)
                )
                ResourceChip(
                    icon = Icons.Outlined.Speed,
                    text = "${profile.cpuThreadCount}T · ${profile.cpuQuotaPercent}%",
                    modifier = Modifier.weight(1f)
                )
                ResourceChip(
                    icon = Icons.Outlined.Storage,
                    text = "Cap ${profile.storageQuotaMb} MB",
                    modifier = Modifier.weight(1f)
                )
            }

            // Storage usage bar
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Pemakaian ruang VM",
                        style = MaterialTheme.typography.labelSmall,
                        color = TerminalTextSecondary
                    )
                    Text(
                        text = "${ImageRepository.formatMb(item.storageUsedBytes)} / ${profile.storageQuotaMb} MB",
                        style = MaterialTheme.typography.labelSmall,
                        color = TerminalTextSecondary
                    )
                }
                LinearProgressIndicator(
                    progress = { storageFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = TerminalSlatePrimary,
                    trackColor = TerminalSurfaceElevated
                )
            }

            // Boot / Open Console Action
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (blockedByOtherVm) {
                    Text(
                        text = "Hentikan '$activeOtherVmName' dahulu",
                        style = MaterialTheme.typography.bodySmall,
                        color = TerminalTextSecondary,
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                } else {
                    Text(
                        text = "Thread: ${profile.resolvedThreadPriority().label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TerminalTextSecondary
                    )
                }

                Button(
                    onClick = onBoot,
                    enabled = !blockedByOtherVm,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isActive) StatusRunningBg else TerminalSlateContainer,
                        contentColor = TerminalTextPrimary
                    ),
                    modifier = Modifier.testTag("boot_vm_${profile.id}")
                ) {
                    Icon(
                        imageVector = if (isActive) Icons.Filled.Terminal else Icons.Filled.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.padding(horizontal = 3.dp))
                    Text(if (isActive) "Buka Konsol" else "Boot")
                }
            }
        }
    }
}

@Composable
private fun ResourceChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = TerminalSurfaceElevated,
        border = BorderStroke(1.dp, TerminalBorder),
        shape = MaterialTheme.shapes.extraSmall,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = TerminalTextSecondary
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = TerminalTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
