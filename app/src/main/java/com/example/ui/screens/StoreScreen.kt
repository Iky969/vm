package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.ImageKind
import com.example.data.ImageRepository
import com.example.data.LocalImage
import com.example.data.StoreCatalog
import com.example.data.StoreCategory
import com.example.data.StoreItem
import com.example.data.TransferProgress
import com.example.ui.theme.StatusRunning
import com.example.ui.theme.StatusRunningBg
import com.example.ui.theme.TerminalBorder
import com.example.ui.theme.TerminalBorderSubtle
import com.example.ui.theme.TerminalCanvasBg
import com.example.ui.theme.TerminalSlateContainer
import com.example.ui.theme.TerminalSlatePrimary
import com.example.ui.theme.TerminalSurface
import com.example.ui.theme.TerminalSurfaceElevated
import com.example.ui.theme.TerminalTextMuted
import com.example.ui.theme.TerminalTextPrimary
import com.example.ui.theme.TerminalTextSecondary
import com.example.ui.viewmodel.ImageViewModel

private data class StoreTab(
    val category: StoreCategory,
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

private val StoreTabs = listOf(
    StoreTab(StoreCategory.ISO, "ISO & Rootfs (RV64)", Icons.Filled.Album, Icons.Outlined.Album, "store_tab_iso"),
    StoreTab(StoreCategory.KERNEL, "Kernel & Bare-Metal", Icons.Filled.Memory, Icons.Outlined.Memory, "store_tab_kernel")
)

@Composable
fun StoreScreen(
    viewModel: ImageViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var showStoreHelpDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.statusMessage) {
        uiState.statusMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatus()
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("store_screen"),
        containerColor = TerminalCanvasBg,
        snackbarHost = { SnackbarHost(snackbarHostState) }
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
                    .widthIn(max = 680.dp)
                    .testTag("store_lazy_list"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "KernoVM Store & Download",
                                style = MaterialTheme.typography.headlineSmall,
                                color = TerminalTextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            IconButton(
                                onClick = { showStoreHelpDialog = true },
                                modifier = Modifier.testTag("store_help_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.HelpOutline,
                                    contentDescription = "Panduan & Informasi Store",
                                    tint = TerminalSlatePrimary
                                )
                            }
                        }
                        Text(
                            text = "Katalog unduhan berkas ISO RISC-V dan kernel/ROM bawaan KernoVM.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TerminalTextSecondary
                        )
                    }
                }

                // Active transfer banner if any
                item {
                    uiState.activeTransfer?.let { transfer ->
                        ActiveTransferBanner(
                            transfer = transfer,
                            onCancel = { viewModel.cancelActiveTransfer() }
                        )
                    }
                }

                // TabRow with separate distinct panes
                item {
                    Surface(
                        color = TerminalSurface,
                        border = BorderStroke(1.dp, TerminalBorder),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TabRow(
                            selectedTabIndex = selectedTabIndex,
                            containerColor = TerminalSurface,
                            contentColor = TerminalTextPrimary,
                            indicator = { tabPositions ->
                                if (selectedTabIndex < tabPositions.size) {
                                    TabRowDefaults.SecondaryIndicator(
                                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                                        color = TerminalSlatePrimary
                                    )
                                }
                            },
                            divider = { HorizontalDivider(color = TerminalBorderSubtle) }
                        ) {
                            StoreTabs.forEachIndexed { index, tab ->
                                val isSelected = selectedTabIndex == index
                                Tab(
                                    selected = isSelected,
                                    onClick = { selectedTabIndex = index },
                                    icon = {
                                        Icon(
                                            imageVector = if (isSelected) tab.selectedIcon else tab.unselectedIcon,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                            tint = if (isSelected) TerminalTextPrimary else TerminalTextSecondary
                                        )
                                    },
                                    text = {
                                        Text(
                                            text = tab.title,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (isSelected) TerminalTextPrimary else TerminalTextSecondary,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                        )
                                    },
                                    modifier = Modifier.testTag(tab.testTag)
                                )
                            }
                        }
                    }
                }

                // Pane description banner
                item {
                    val description = when (StoreTabs[selectedTabIndex].category) {
                        StoreCategory.ISO -> "Pre-installed rootfs (Alpine RV64) & berkas ISO resmi RISC-V 64-bit untuk slot VirtIO-blk (/dev/vda RO dan /dev/vdb RW)."
                        StoreCategory.KERNEL -> "Kernel flat binary M-Mode No-MMU dan ROM diagnostik bawaan untuk RISC-V."
                    }

                    Surface(
                        color = TerminalSurfaceElevated,
                        border = BorderStroke(1.dp, TerminalBorderSubtle),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = TerminalSlatePrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodySmall,
                                color = TerminalTextSecondary,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }

                // Items list according to active pane
                val currentCategory = StoreTabs[selectedTabIndex].category
                val categoryItems = StoreCatalog.items.filter { it.category == currentCategory }

                if (categoryItems.isEmpty()) {
                    item {
                        Surface(
                            color = TerminalSurface,
                            border = BorderStroke(1.dp, TerminalBorder),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("store_empty_state")
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "Belum Ada Item Katalog di Kategori Ini",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TerminalTextPrimary
                                )
                                Text(
                                    text = "Anda tetap dapat mengimpor berkas ISO atau image disk RISC-V secara lokal melalui tab Storage.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TerminalTextSecondary
                                )
                            }
                        }
                    }
                } else {
                    items(categoryItems, key = { it.id }) { item ->
                        val isInstalled = isItemInstalled(item, uiState.localImages)
                        val isDownloadingThis = uiState.activeTransfer?.sourceLabel?.contains(item.name) == true

                        StoreItemCard(
                            item = item,
                            isInstalled = isInstalled,
                            isDownloading = isDownloadingThis,
                            onDownload = {
                                viewModel.downloadStoreItem(item)
                            }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(48.dp)) }
            }
        }
    }

    uiState.pendingConfirmation?.let { conf ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.dismissPendingConfirmation() },
            containerColor = TerminalSurface,
            title = { Text(conf.title, style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    text = conf.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TerminalTextSecondary
                )
            },
            confirmButton = {
                androidx.compose.material3.Button(
                    onClick = { conf.onConfirm() },
                    modifier = Modifier.testTag("confirm_large_file_button")
                ) {
                    Text("Lanjutkan")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { viewModel.dismissPendingConfirmation() },
                    modifier = Modifier.testTag("dismiss_large_file_button")
                ) {
                    Text("Batal")
                }
            }
        )
    }

    if (showStoreHelpDialog) {
        AlertDialog(
            onDismissRequest = { showStoreHelpDialog = false },
            containerColor = TerminalSurface,
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.HelpOutline,
                        contentDescription = null,
                        tint = TerminalSlatePrimary
                    )
                    Text(
                        text = "Panduan & Informasi Store",
                        style = MaterialTheme.typography.titleMedium,
                        color = TerminalTextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "🛍️ Fungsi Store",
                        style = MaterialTheme.typography.labelLarge,
                        color = TerminalSlatePrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Store menyediakan katalog unduhan langsung ke tautan resmi/mirror tanpa database perantara untuk sistem operasi dan komponen virtualisasi.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TerminalTextPrimary
                    )

                    HorizontalDivider(color = TerminalBorder)

                    Text(
                        text = "💿 Pane 1: ISO / Disk OS",
                        style = MaterialTheme.typography.labelLarge,
                        color = TerminalSlatePrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "• Menampilkan berkas ISO / image disk RISC-V yang kompatibel dengan VirtIO-blk.\n" +
                                "• Anda juga dapat mengimpor berkas ISO/disk RISC-V lokal melalui menu Storage untuk dipasang ke VM.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TerminalTextSecondary
                    )

                    HorizontalDivider(color = TerminalBorder)

                    Text(
                        text = "🧠 Pane 2: Kernel & Bare-Metal",
                        style = MaterialTheme.typography.labelLarge,
                        color = TerminalSlatePrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "• Berkas flat binary dan Diagnostic ROM bawaan khusus RISC-V (RV32IMA No-MMU) yang dapat langsung dijalankan oleh CPU KernoVM.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TerminalTextSecondary
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showStoreHelpDialog = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TerminalSlatePrimary,
                        contentColor = TerminalCanvasBg
                    ),
                    modifier = Modifier.testTag("dismiss_store_help_dialog")
                ) {
                    Text("Mengerti")
                }
            }
        )
    }
}

@Composable
private fun StoreItemCard(
    item: StoreItem,
    isInstalled: Boolean,
    isDownloading: Boolean,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = TerminalSurface,
        border = BorderStroke(1.dp, if (isInstalled) StatusRunning.copy(alpha = 0.4f) else TerminalBorder),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .fillMaxWidth()
            .testTag("store_item_${item.id}")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Title & Badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TerminalTextPrimary
                    )
                    Text(
                        text = item.filename,
                        style = MaterialTheme.typography.labelSmall,
                        color = TerminalTextMuted
                    )
                }

                // Badges Row
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        color = TerminalSlateContainer,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = item.architecture.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = TerminalSlatePrimary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    Surface(
                        color = TerminalSurfaceElevated,
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(1.dp, TerminalBorderSubtle)
                    ) {
                        Text(
                            text = item.formattedSize,
                            style = MaterialTheme.typography.labelSmall,
                            color = TerminalTextSecondary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Description
            Text(
                text = item.description,
                style = MaterialTheme.typography.bodySmall,
                color = TerminalTextSecondary,
                lineHeight = 18.sp
            )

            if (item.category == StoreCategory.ISO) {
                Surface(
                    color = TerminalSurfaceElevated,
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, TerminalBorderSubtle)
                ) {
                    val slotInfo = if (item.targetKind == ImageKind.DISK_IMAGE) {
                        "ℹ️ Pre-installed Rootfs / Virtual Disk Target (/dev/vdb · Read-Write)"
                    } else {
                        "ℹ️ Media installer VirtIO-blk (/dev/vda · Read-Only)"
                    }
                    Text(
                        text = slotInfo,
                        style = MaterialTheme.typography.labelSmall,
                        color = TerminalTextSecondary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // License & Action Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Lisensi: ${item.license}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TerminalTextMuted
                )

                when {
                    isInstalled -> {
                        Surface(
                            color = StatusRunningBg,
                            border = BorderStroke(1.dp, StatusRunning.copy(alpha = 0.5f)),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.CheckCircle,
                                    contentDescription = null,
                                    tint = StatusRunning,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Terpasang",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = StatusRunning
                                )
                            }
                        }
                    }

                    isDownloading -> {
                        Surface(
                            color = TerminalSurfaceElevated,
                            border = BorderStroke(1.dp, TerminalBorderSubtle),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = TerminalSlatePrimary
                                )
                                Text(
                                    text = "Mengunduh...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TerminalSlatePrimary
                                )
                            }
                        }
                    }

                    else -> {
                        OutlinedButton(
                            onClick = onDownload,
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = TerminalSurfaceElevated,
                                contentColor = TerminalTextPrimary
                            ),
                            border = BorderStroke(1.dp, TerminalBorder),
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.testTag("store_download_button_${item.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.CloudDownload,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = TerminalSlatePrimary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Unduh")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveTransferBanner(
    transfer: TransferProgress,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = TerminalSurfaceElevated,
        border = BorderStroke(1.dp, TerminalSlatePrimary.copy(alpha = 0.5f)),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Mengunduh: ${transfer.sourceLabel}",
                    style = MaterialTheme.typography.titleSmall,
                    color = TerminalTextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Cancel,
                        contentDescription = "Batalkan unduhan",
                        tint = TerminalTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (transfer.isIndeterminate) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = TerminalSlatePrimary,
                    trackColor = TerminalBorder
                )
            } else {
                LinearProgressIndicator(
                    progress = { transfer.fraction ?: 0f },
                    modifier = Modifier.fillMaxWidth(),
                    color = TerminalSlatePrimary,
                    trackColor = TerminalBorder
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = transfer.formattedProgress,
                    style = MaterialTheme.typography.labelSmall,
                    color = TerminalTextSecondary
                )
                Text(
                    text = "${transfer.percentage}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = TerminalSlatePrimary
                )
            }
        }
    }
}

private fun isItemInstalled(item: StoreItem, localImages: List<LocalImage>): Boolean {
    return localImages.any { local ->
        local.name.equals(item.name, ignoreCase = true) ||
            local.sourceUrl == item.downloadUrl ||
            local.localPath.endsWith(item.filename, ignoreCase = true) ||
            (item.id == "kernel_diag_rom" && local.resolvedKind().name == "BUILTIN_ROM")
    }
}
