package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import kotlin.math.roundToInt
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.DistroPreset
import com.example.data.DistroPresetCatalog
import com.example.data.ImageKind
import com.example.data.ImageRepository
import com.example.data.LocalImage
import com.example.emulator.DiagnosticMonitorRom
import com.example.ui.theme.StatusError
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
import com.example.ui.viewmodel.ImageViewModel

@Composable
fun StorageScreen(
    viewModel: ImageViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showCustomUrlDialog by remember { mutableStateOf(false) }
    var showCreateDiskDialog by remember { mutableStateOf(false) }
    var showBuildrootRecipe by remember { mutableStateOf(false) }
    var showStorageHelpDialog by remember { mutableStateOf(false) }

    val safKernelLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importLocalUri(uri, ImageKind.KERNEL_IMAGE)
        }
    }

    val safDtbLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importLocalUri(uri, ImageKind.DEVICE_TREE)
        }
    }

    val safIsoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importLocalUri(uri, ImageKind.ISO_IMAGE)
        }
    }

    LaunchedEffect(uiState.statusMessage) {
        uiState.statusMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatus()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
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
                    .widthIn(max = 680.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Storage // Berkas Terpasang",
                                style = MaterialTheme.typography.headlineSmall,
                                color = TerminalTextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            IconButton(
                                onClick = { showStorageHelpDialog = true },
                                modifier = Modifier.testTag("storage_help_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.HelpOutline,
                                    contentDescription = "Panduan & Fungsi Storage",
                                    tint = TerminalSlatePrimary
                                )
                            }
                        }
                        Text(
                            text = "Kelola berkas kernel, ISO bootable, device tree (DTB), dan disk virtual terpasang.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TerminalTextSecondary
                        )

                        // SAF Import Row 1: Bring your own ISO/Disk & Kernel
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { safIsoLauncher.launch(arrayOf("*/*")) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = TerminalSlateContainer,
                                    contentColor = TerminalTextPrimary
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("saf_import_iso_button")
                            ) {
                                Icon(Icons.Outlined.Album, contentDescription = null)
                                Spacer(Modifier.padding(3.dp))
                                Text("Bawa File ISO")
                            }
                            Button(
                                onClick = { safKernelLauncher.launch(arrayOf("*/*")) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = TerminalSurfaceElevated,
                                    contentColor = TerminalTextPrimary
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("saf_import_kernel_button")
                            ) {
                                Icon(Icons.Outlined.FileOpen, contentDescription = null)
                                Spacer(Modifier.padding(3.dp))
                                Text("Impor Kernel")
                            }
                        }

                        // Row 2: DTB, Create Virtual Disk, URL+SHA, Recipe
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { safDtbLauncher.launch(arrayOf("*/*")) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("saf_import_dtb_button")
                            ) {
                                Icon(Icons.Outlined.FileOpen, contentDescription = null)
                                Spacer(Modifier.padding(3.dp))
                                Text("Impor .DTB")
                            }
                            OutlinedButton(
                                onClick = { showCreateDiskDialog = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("create_virtual_disk_button")
                            ) {
                                Icon(Icons.Outlined.Storage, contentDescription = null)
                                Spacer(Modifier.padding(3.dp))
                                Text("Buat Disk .IMG")
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showCustomUrlDialog = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("download_custom_url_button")
                            ) {
                                Icon(Icons.Outlined.Link, contentDescription = null)
                                Spacer(Modifier.padding(3.dp))
                                Text("URL + SHA-256")
                            }
                            OutlinedButton(
                                onClick = { showBuildrootRecipe = !showBuildrootRecipe },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("toggle_buildroot_recipe_button")
                            ) {
                                Icon(Icons.Outlined.Code, contentDescription = null)
                                Spacer(Modifier.padding(3.dp))
                                Text("Resep .config")
                            }
                        }

                        Surface(
                            color = TerminalSurface,
                            border = BorderStroke(1.dp, TerminalBorder),
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Catatan Dual-Drive RV64: Slot ISO installer (/dev/vda RO @ 0x10001000) & Virtual Disk Target (/dev/vdb RW @ 0x10003000) divalidasi otomatis agar hanya citra RISC-V yang diterima.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TerminalTextSecondary,
                                modifier = Modifier.padding(10.dp)
                            )
                        }

                        AnimatedVisibility(visible = showBuildrootRecipe) {
                            Surface(
                                color = TerminalSurface,
                                border = BorderStroke(1.dp, TerminalBorder),
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "KONFIGURASI KERNEL LINUX M-MODE NO-MMU (EMBEDDED INITRAMFS)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TerminalSlatePrimary
                                    )
                                    Text(
                                        text = DistroPresetCatalog.BUILDROOT_CONFIG_RECIPE.trim(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TerminalTextPrimary
                                    )
                                }
                            }
                        }
                    }
                }

                // Active transfer progress bar
                uiState.activeTransfer?.let { transfer ->
                    item {
                        Surface(
                            color = TerminalSurface,
                            border = BorderStroke(1.dp, TerminalSlatePrimary.copy(alpha = 0.5f)),
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("active_transfer_card")
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Memproses: ${transfer.label}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = TerminalTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = viewModel::cancelActiveTransfer,
                                        modifier = Modifier.testTag("cancel_transfer_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Cancel,
                                            contentDescription = "Batalkan transfer",
                                            tint = StatusError
                                        )
                                    }
                                }
                                val frac = transfer.fraction
                                if (frac != null) {
                                    LinearProgressIndicator(
                                        progress = { frac },
                                        modifier = Modifier.fillMaxWidth().height(4.dp),
                                        color = TerminalSlatePrimary,
                                        trackColor = TerminalSurfaceElevated
                                    )
                                } else {
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth().height(4.dp),
                                        color = TerminalSlatePrimary,
                                        trackColor = TerminalSurfaceElevated
                                    )
                                }
                                val totalLabel = if (transfer.totalBytes > 0) {
                                    " / ${ImageRepository.formatMb(transfer.totalBytes)}"
                                } else {
                                    ""
                                }
                                Text(
                                    text = "${ImageRepository.formatMb(transfer.bytesTransferred)}$totalLabel",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TerminalTextSecondary
                                )
                            }
                        }
                    }
                }

                // Local Images Section
                item {
                    Text(
                        text = "BERKAS LOKAL TERSIMPAN (${uiState.localImages.size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = TerminalSlatePrimary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                if (uiState.localImages.isEmpty()) {
                    item {
                        Surface(
                            color = TerminalSurface,
                            border = BorderStroke(1.dp, TerminalBorder),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "Belum Ada Berkas di Storage",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = TerminalTextPrimary
                                )
                                Text(
                                    text = "Buka tab Store untuk mengunduh ISO / Kernel pilihan, atau gunakan tombol di atas untuk mengimpor dari perangkat Anda.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TerminalTextSecondary
                                )
                            }
                        }
                    }
                } else {
                    items(uiState.localImages, key = { it.id }) { img ->
                        LocalImageCard(
                            image = img,
                            onDelete = { viewModel.deleteImage(img) }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(36.dp)) }
            }
        }
    }

    if (showCustomUrlDialog) {
        CustomUrlDownloadDialog(
            onDismiss = { showCustomUrlDialog = false },
            onStartDownload = { name, url, sha ->
                viewModel.downloadCustomUrl(name, url, sha)
                showCustomUrlDialog = false
            }
        )
    }

    if (showCreateDiskDialog) {
        CreateVirtualDiskDialog(
            onDismiss = { showCreateDiskDialog = false },
            onCreate = { name, sizeMb ->
                viewModel.createVirtualDisk(name, sizeMb)
                showCreateDiskDialog = false
            }
        )
    }

    if (showStorageHelpDialog) {
        AlertDialog(
            onDismissRequest = { showStorageHelpDialog = false },
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
                        text = "Panduan & Fungsi Storage",
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
                        text = "📁 Apa itu Storage?",
                        style = MaterialTheme.typography.labelLarge,
                        color = TerminalSlatePrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Storage adalah ruang penyimpanan internal lokal KernoVM di perangkat Android Anda. Seluruh berkas yang diunduh dari Store, diimpor dari penyimpanan HP, atau diekspor disimpan di sini.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TerminalTextPrimary
                    )

                    HorizontalDivider(color = TerminalBorder)

                    Text(
                        text = "💿 Panduan Berkas ISO & Distro:",
                        style = MaterialTheme.typography.labelLarge,
                        color = TerminalSlatePrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "• Cara Kerja ISO: Berkas ISO dipasang sebagai drive CD-ROM / blok sekunder (/dev/vda) via VirtIO.\n" +
                                "• Kompatibilitas ISO: Gunakan berkas ISO atau image disk berarsitektur RISC-V agar kompatibel dengan engine native KernoVM.\n" +
                                "• Fungsi ISO Saat Ini: Sebagai media data VirtIO-blk untuk membaca berkas, skrip, dan paket data ke dalam Linux RISC-V.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TerminalTextSecondary
                    )

                    HorizontalDivider(color = TerminalBorder)

                    Text(
                        text = "💾 Tipe Berkas Lainnya:",
                        style = MaterialTheme.typography.labelLarge,
                        color = TerminalSlatePrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "• Kernel (.bin): Citra biner kernel Linux atau bare-metal ROM yang dimuat ke RAM.\n" +
                                "• DTB (.dtb): Device Tree Blob yang mendefinisikan perangkat keras virtual.\n" +
                                "• Virtual Disk (.img): Hard disk virtual baca-tulis untuk penyimpanan persisten.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TerminalTextSecondary
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showStorageHelpDialog = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TerminalSlatePrimary,
                        contentColor = TerminalCanvasBg
                    ),
                    modifier = Modifier.testTag("dismiss_storage_help_dialog")
                ) {
                    Text("Mengerti")
                }
            }
        )
    }

    uiState.pendingConfirmation?.let { conf ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissPendingConfirmation() },
            containerColor = TerminalSurface,
            title = {
                Text(
                    text = conf.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = TerminalTextPrimary
                )
            },
            text = {
                Text(
                    text = conf.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TerminalTextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = { conf.onConfirm() },
                    colors = ButtonDefaults.buttonColors(containerColor = TerminalSlatePrimary)
                ) {
                    Text("Lanjutkan")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissPendingConfirmation() }) {
                    Text("Batal", color = TerminalTextSecondary)
                }
            }
        )
    }
}

@Composable
private fun LocalImageCard(
    image: LocalImage,
    onDelete: () -> Unit
) {
    val isBuiltIn = image.id == DiagnosticMonitorRom.BUILTIN_IMAGE_ID
    Surface(
        color = TerminalSurface,
        border = BorderStroke(1.dp, TerminalBorder),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("local_image_${image.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = image.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = TerminalTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Surface(
                        color = TerminalSurfaceElevated,
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            text = when (image.resolvedKind()) {
                                ImageKind.BUILTIN_ROM -> "ROM"
                                ImageKind.DEVICE_TREE -> "DTB"
                                ImageKind.ISO_IMAGE -> "ISO (vda)"
                                ImageKind.DISK_IMAGE -> "DISK (vdb)"
                                ImageKind.KERNEL_IMAGE -> "KERNEL"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = TerminalSlatePrimary,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
                Text(
                    text = "${ImageRepository.formatMb(image.sizeBytes)} · ${image.architectureTag}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TerminalTextSecondary
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.VerifiedUser,
                        contentDescription = null,
                        tint = StatusRunning
                    )
                    Text(
                        text = "SHA-256: ${image.sha256.take(16)}…",
                        style = MaterialTheme.typography.labelSmall,
                        color = TerminalTextSecondary
                    )
                }
            }
            if (!isBuiltIn) {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("delete_image_${image.id}")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = "Hapus image ${image.name}",
                        tint = TerminalTextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetCatalogCard(
    preset: DistroPreset,
    isTransferBusy: Boolean,
    onDownload: () -> Unit
) {
    Surface(
        color = TerminalSurface,
        border = BorderStroke(1.dp, TerminalBorder),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
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
                    text = preset.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = TerminalTextPrimary,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    color = if (preset.isBuiltInOffline) StatusRunningBg else TerminalSurfaceElevated,
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Text(
                        text = if (preset.isBuiltInOffline) "TERPASANG" else preset.estimatedSizeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (preset.isBuiltInOffline) StatusRunning else TerminalTextSecondary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Text(
                text = preset.description,
                style = MaterialTheme.typography.bodySmall,
                color = TerminalTextSecondary
            )
            Text(
                text = "Kernel: ${preset.kernelVersion} · RAM Saran: ${preset.recommendedRamMb} MB · ${preset.license}",
                style = MaterialTheme.typography.labelSmall,
                color = TerminalTextSecondary
            )
            Text(
                text = preset.kernelConfigNotes,
                style = MaterialTheme.typography.labelSmall,
                color = TerminalTextSecondary
            )
            if (preset.expectedSha256 != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.VerifiedUser,
                        contentDescription = "SHA-256 Terverifikasi",
                        tint = StatusRunning
                    )
                    Text(
                        text = "SHA-256 Terverifikasi: ${preset.expectedSha256.take(16)}…",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusRunning
                    )
                }
            }
            if (!preset.isBuiltInOffline) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = preset.sourceRepoUrl,
                        style = MaterialTheme.typography.labelSmall,
                        color = TerminalSlatePrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    OutlinedButton(
                        onClick = onDownload,
                        enabled = !isTransferBusy,
                        modifier = Modifier.testTag("download_preset_${preset.id}")
                    ) {
                        Icon(Icons.Outlined.CloudDownload, contentDescription = null)
                        Spacer(Modifier.padding(3.dp))
                        Text("Unduh")
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomUrlDownloadDialog(
    onDismiss: () -> Unit,
    onStartDownload: (String, String, String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var sha256 by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TerminalSurface,
        title = { Text("Unduh Kernel dari URL", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Mendukung flat binary Image atau arsip .zip yang berisi Image M-Mode No-MMU.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TerminalTextSecondary
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nama Image") },
                    placeholder = { Text("mis. Linux 6.1 RV32") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL HTTPS Immutable") },
                    placeholder = { Text("https://...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = sha256,
                    onValueChange = { sha256 = it },
                    label = { Text("SHA-256 Digest (Opsional)") },
                    placeholder = { Text("64 karakter hex") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onStartDownload(
                        name.ifBlank { "Custom RV32 Kernel" },
                        url,
                        sha256.takeIf { it.isNotBlank() }
                    )
                },
                enabled = url.trim().startsWith("http")
            ) {
                Text("Mulai Unduh")
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
private fun CreateVirtualDiskDialog(
    onDismiss: () -> Unit,
    onCreate: (String, Int) -> Unit
) {
    var name by remember { mutableStateOf("rootfs_data.img") }
    var sizeMb by remember { mutableStateOf(32) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TerminalSurface,
        title = { Text("Buat Disk Virtual (.IMG)", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Disk virtual raw read-write yang dapat dipasang ke profil VM sebagai Virtual Disk Target /dev/vdb (VirtIO-blk).",
                    style = MaterialTheme.typography.bodySmall,
                    color = TerminalTextSecondary
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nama File Disk") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Ukuran Disk (geser)",
                        style = MaterialTheme.typography.labelMedium,
                        color = TerminalTextSecondary
                    )
                    Text(
                        text = "$sizeMb MB",
                        style = MaterialTheme.typography.labelMedium,
                        color = TerminalTextPrimary
                    )
                }
                Slider(
                    value = sizeMb.toFloat(),
                    onValueChange = { raw ->
                        sizeMb = ((raw / 4f).roundToInt() * 4).coerceIn(4, 512)
                    },
                    valueRange = 4f..512f,
                    colors = SliderDefaults.colors(
                        thumbColor = TerminalSlatePrimary,
                        activeTrackColor = TerminalSlatePrimary,
                        inactiveTrackColor = TerminalBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("virtual_disk_size_slider")
                )
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(name, sizeMb) }) {
                Text("Buat (${sizeMb} MB)")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal")
            }
        }
    )
}

/**
 * Backward compatibility alias for [StorageScreen].
 */
@Composable
fun ImageManagerScreen(
    viewModel: ImageViewModel,
    modifier: Modifier = Modifier
) {
    StorageScreen(viewModel = viewModel, modifier = modifier)
}
