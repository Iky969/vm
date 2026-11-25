package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.AppUpdateInfo
import com.example.data.UpdateChecker
import com.example.ui.theme.TerminalBorder
import com.example.ui.theme.TerminalBorderSubtle
import com.example.ui.theme.TerminalSlateContainer
import com.example.ui.theme.TerminalSlatePrimary
import com.example.ui.theme.TerminalSurface
import com.example.ui.theme.TerminalSurfaceElevated
import com.example.ui.theme.TerminalTextMuted
import com.example.ui.theme.TerminalTextPrimary
import com.example.ui.theme.TerminalTextSecondary

val UpdateButtonBlue = Color(0xFF0D6EFD)

/**
 * About Dialog displaying application credentials, Google AI Studio attribution,
 * open source software licenses (KernoVM, mini-rv32ima, fonts),
 * and links to repository & author.
 */
@Composable
fun AboutDialog(
    isCheckingUpdate: Boolean = false,
    onDismiss: () -> Unit,
    onCheckUpdate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 560.dp)
                .padding(vertical = 24.dp)
                .testTag("about_dialog"),
            shape = RoundedCornerShape(16.dp),
            color = TerminalSurface,
            border = BorderStroke(1.dp, TerminalBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header: KernoVM v0.1.0, developed with Google AI Studio
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        color = TerminalSlateContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.size(44.dp),
                        border = BorderStroke(1.dp, TerminalBorder)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.Terminal,
                                contentDescription = null,
                                tint = TerminalSlatePrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "KernoVM v${UpdateChecker.CURRENT_VERSION_NAME}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = TerminalTextPrimary
                        )
                        Text(
                            text = "developed with Google AI Studio",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TerminalSlatePrimary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Text(
                    text = "Emulator Linux RISC-V (RV32IMA M-Mode No-MMU) untuk Android tanpa root. Mendukung ttyS0 polling serial console, framebuffer display, dan VirtIO block & net.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TerminalTextSecondary,
                    lineHeight = 18.sp
                )

                HorizontalDivider(color = TerminalBorderSubtle)

                // Licenses Section
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "LISENSI & KOMPONEN OPEN SOURCE",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TerminalTextSecondary
                    )

                    // 1. KernoVM: Apache License 2.0
                    LicenseItemCard(
                        title = "KernoVM",
                        licenseBadge = "Apache License 2.0",
                        description = "Aplikasi Android, orchestrator runtime VM, antarmuka Jetpack Compose, manajemen resource limit, dan JNI native bridge.",
                        legalNote = "Copyright © 2026 Iky969. Berlisensi di bawah Apache License 2.0. Anda dapat memperoleh salinan Lisensi di http://www.apache.org/licenses/LICENSE-2.0"
                    )

                    // 2. mini-rv32ima: MIT License (Charles Lohr)
                    LicenseItemCard(
                        title = "mini-rv32ima",
                        licenseBadge = "MIT License (Charles Lohr)",
                        description = "Inti emulasi CPU RISC-V RV32IMA M-Mode No-MMU yang sangat ringkas dan efisien (cnlohr/mini-rv32ima).",
                        legalNote = "Copyright © 2022 Charles Lohr. Berlisensi di bawah syarat dan ketentuan The MIT License."
                    )

                    // 3. Fonts: SIL Open Font License (OFL 1.1)
                    LicenseItemCard(
                        title = "Tipografi (JetBrains Mono & IBM Plex Sans)",
                        licenseBadge = "SIL OFL 1.1",
                        description = "Aset font monospaced untuk buffer konsol ANSI terminal dan tipografi UI.",
                        legalNote = "Copyright JetBrains s.r.o. dan IBM Corp. Berlisensi di bawah SIL Open Font License 1.1."
                    )
                }

                HorizontalDivider(color = TerminalBorderSubtle)

                // Actions: Check for Updates & Repo Link
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onCheckUpdate,
                        enabled = !isCheckingUpdate,
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = TerminalSurfaceElevated,
                            contentColor = TerminalTextPrimary
                        ),
                        border = BorderStroke(1.dp, TerminalBorder),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("about_check_update_button")
                    ) {
                        if (isCheckingUpdate) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = TerminalSlatePrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Memeriksa Pembaruan...")
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = TerminalSlatePrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Periksa Pembaruan")
                        }
                    }

                    // GitHub Repository Link
                    Surface(
                        color = TerminalSurfaceElevated,
                        border = BorderStroke(1.dp, TerminalBorderSubtle),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                openBrowserUrl(context, "https://github.com/Iky969/vm")
                            }
                            .testTag("about_repo_link")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "Repositori GitHub",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TerminalTextSecondary
                                )
                                Text(
                                    text = "https://github.com/Iky969/vm",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TerminalSlatePrimary,
                                    textDecoration = TextDecoration.Underline
                                )
                            }
                            Icon(
                                imageVector = Icons.Outlined.OpenInNew,
                                contentDescription = "Buka repositori di browser",
                                tint = TerminalTextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // Footer: Author & Copyright
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "© 2026 Iky969 · KernoVM",
                        style = MaterialTheme.typography.bodySmall,
                        color = TerminalTextMuted
                    )

                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("about_close_button")
                    ) {
                        Text(
                            text = "Tutup",
                            color = TerminalTextPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LicenseItemCard(
    title: String,
    licenseBadge: String,
    description: String,
    legalNote: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = TerminalSurfaceElevated,
        border = BorderStroke(1.dp, TerminalBorderSubtle),
        shape = MaterialTheme.shapes.small,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = TerminalTextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Surface(
                    color = TerminalSlateContainer,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = licenseBadge,
                        style = MaterialTheme.typography.labelSmall,
                        color = TerminalSlatePrimary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = TerminalTextSecondary,
                lineHeight = 16.sp
            )

            Text(
                text = legalNote,
                style = MaterialTheme.typography.labelSmall,
                color = TerminalTextMuted,
                lineHeight = 14.sp
            )
        }
    }
}

/**
 * In-App Update Notice Dialog.
 * Prompts user with new version info, changelog release notes,
 * a bright blue Update button, and a subtle blended Skip button.
 */
@Composable
fun UpdateNoticeDialog(
    updateInfo: AppUpdateInfo,
    onDismiss: () -> Unit,
    onUpdate: (AppUpdateInfo) -> Unit = { info ->
        // Default intent action handled before callback
    },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val notesScrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TerminalSurface,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .widthIn(max = 520.dp)
            .testTag("update_dialog"),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    color = UpdateButtonBlue.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.SystemUpdate,
                            contentDescription = null,
                            tint = UpdateButtonBlue,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Column {
                    Text(
                        text = "Pembaruan Tersedia",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TerminalTextPrimary
                    )
                    Text(
                        text = updateInfo.latestVersion,
                        style = MaterialTheme.typography.bodySmall,
                        color = TerminalSlatePrimary
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Versi baru KernoVM telah dirilis dengan perbaikan dan peningkatan performa emulator.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TerminalTextSecondary
                )

                // Scrollable changelog
                Surface(
                    color = TerminalSurfaceElevated,
                    border = BorderStroke(1.dp, TerminalBorder),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp, max = 200.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp)
                            .verticalScroll(notesScrollState)
                    ) {
                        Text(
                            text = if (updateInfo.releaseNotes.isNotBlank()) {
                                updateInfo.releaseNotes
                            } else {
                                "Catatan rilis tidak tersedia. Kunjungi halaman GitHub Releases untuk detail selengkapnya."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = TerminalTextPrimary,
                            lineHeight = 16.sp
                        )
                    }
                }

                if (updateInfo.downloadUrl.endsWith(".apk")) {
                    Text(
                        text = "APK siap diunduh langsung dari GitHub Releases.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TerminalTextMuted
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val targetUrl = if (updateInfo.downloadUrl.isNotBlank()) {
                        updateInfo.downloadUrl
                    } else {
                        updateInfo.htmlUrl
                    }
                    openBrowserUrl(context, targetUrl)
                    onUpdate(updateInfo)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = UpdateButtonBlue,
                    contentColor = Color.White
                ),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.testTag("update_dialog_update_button")
            ) {
                Icon(
                    imageVector = Icons.Outlined.SystemUpdate,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Perbarui Sekarang", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = TerminalTextSecondary
                ),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.testTag("update_dialog_skip_button")
            ) {
                Text("Lewati")
            }
        }
    )
}

fun openBrowserUrl(context: Context, url: String) {
    runCatching {
        val uri = Uri.parse(url)
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
