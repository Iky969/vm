package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Eject
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.TextDecrease
import androidx.compose.material.icons.outlined.TextIncrease
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.emulator.AnsiTerminalBuffer
import com.example.ui.components.RawTerminalImeView
import com.example.ui.components.TerminalInputBridge
import com.example.ui.components.arrowSeq
import com.example.ui.components.mapHardwareKeyEventToTerminalBytes
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusErrorBg
import com.example.ui.theme.StatusIdle
import com.example.ui.theme.StatusIdleBg
import com.example.ui.theme.StatusPaused
import com.example.ui.theme.StatusPausedBg
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
import com.example.ui.viewmodel.ConsoleViewModel
import com.example.vm.FramebufferState
import com.example.vm.RegisterSnapshot
import com.example.vm.VmRuntimeState
import com.example.vm.VmStatus
import kotlinx.coroutines.launch
import java.util.Locale

enum class ConsoleViewTab {
    TERMINAL,
    DISPLAY
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleScreen(
    viewModel: ConsoleViewModel,
    onNavigateBackToVms: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.runtimeState.collectAsStateWithLifecycle()
    val exportMessage by viewModel.exportMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val uiScope = rememberCoroutineScope()

    var imeViewRef by remember { mutableStateOf<RawTerminalImeView?>(null) }
    var showInspector by remember { mutableStateOf(false) }
    var showRunningBackDialog by remember { mutableStateOf(false) }
    var activeTab by remember { mutableStateOf(ConsoleViewTab.TERMINAL) }

    // Back button behavior: prompt Pause / Stop / Cancel when VM is RUNNING; allow free exit when PAUSED/STOPPED/HALTED/ERROR
    BackHandler(enabled = state.status == VmStatus.RUNNING) {
        showRunningBackDialog = true
    }

    LaunchedEffect(exportMessage) {
        exportMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearExportMessage()
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .onPreviewKeyEvent { keyEvent ->
                if (state.status == VmStatus.RUNNING) {
                    val bytes = mapHardwareKeyEventToTerminalBytes(
                        event = keyEvent,
                        applicationCursorKeys = state.terminalSnapshot.applicationCursorKeys
                    )
                    if (bytes != null) {
                        viewModel.sendBytes(bytes)
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            },
        containerColor = TerminalCanvasBg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
        ) {
            // Compact Telemetry Top Bar
            ConsoleTelemetryHeader(
                state = state,
                onTogglePause = {
                    if (state.status == VmStatus.RUNNING) viewModel.pause() else viewModel.resume()
                },
                onOpenInspector = { showInspector = true }
            )

            // Calm one-line RAM-only notice
            Surface(
                color = TerminalSurface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (state.autoPausedByBackground) {
                        "Dijeda otomatis saat latar belakang (mtime dibekukan) — tekan Lanjut untuk meneruskan."
                    } else {
                        stringResource(R.string.ram_only_notice)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.autoPausedByBackground) StatusPaused else TerminalTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
            HorizontalDivider(color = TerminalBorder)

            // Graphical Framebuffer / Terminal Console Switcher Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TerminalSurface)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = if (activeTab == ConsoleViewTab.TERMINAL) TerminalSlateContainer else TerminalSurfaceElevated,
                    border = BorderStroke(1.dp, if (activeTab == ConsoleViewTab.TERMINAL) TerminalSlatePrimary else TerminalBorder),
                    shape = MaterialTheme.shapes.extraSmall,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { activeTab = ConsoleViewTab.TERMINAL }
                        .testTag("tab_console_terminal")
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Terminal,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (activeTab == ConsoleViewTab.TERMINAL) TerminalSlatePrimary else TerminalTextSecondary
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Terminal (ttyS0)",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (activeTab == ConsoleViewTab.TERMINAL) TerminalSlatePrimary else TerminalTextSecondary
                        )
                    }
                }
                Surface(
                    color = if (activeTab == ConsoleViewTab.DISPLAY) TerminalSlateContainer else TerminalSurfaceElevated,
                    border = BorderStroke(1.dp, if (activeTab == ConsoleViewTab.DISPLAY) TerminalSlatePrimary else TerminalBorder),
                    shape = MaterialTheme.shapes.extraSmall,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { activeTab = ConsoleViewTab.DISPLAY }
                        .testTag("tab_console_display")
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Computer,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (activeTab == ConsoleViewTab.DISPLAY) TerminalSlatePrimary else TerminalTextSecondary
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Layar Grafis (fb0)",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (activeTab == ConsoleViewTab.DISPLAY) TerminalSlatePrimary else TerminalTextSecondary
                        )
                    }
                }
            }
            HorizontalDivider(color = TerminalBorder)

            // Main VT100/ANSI Terminal Canvas or Framebuffer Display + Transparent Raw InputConnection Bridge
            val currentTheme = state.terminalTheme
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(if (activeTab == ConsoleViewTab.DISPLAY) 0xFF000000.toInt() else currentTheme.defaultBg))
            ) {
                // Non-zero measured transparent custom InputConnection bridge for raw IME keystrokes
                TerminalInputBridge(
                    modifier = Modifier
                        .matchParentSize()
                        .alpha(0.01f),
                    applicationCursorKeys = state.terminalSnapshot.applicationCursorKeys,
                    onTerminalBytes = viewModel::sendBytes,
                    onViewReady = { imeViewRef = it }
                )

                if (state.activeVmId == null && state.status == VmStatus.STOPPED) {
                    EmptyConsoleState(onNavigateBackToVms = onNavigateBackToVms)
                } else if (activeTab == ConsoleViewTab.DISPLAY) {
                    FramebufferCanvasArea(
                        framebufferState = state.framebuffer,
                        isRunning = state.status == VmStatus.RUNNING,
                        onCaptureBitmap = viewModel::captureFramebufferBitmap,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    TerminalCanvasArea(
                        snapshot = state.terminalSnapshot,
                        fontSizeSp = state.terminalFontSizeSp,
                        theme = currentTheme,
                        onResize = viewModel::resize,
                        onTapCanvas = { imeViewRef?.showSoftKeyboard() },
                        onAdjustFontSize = viewModel::adjustFontSize
                    )
                }
            }

            HorizontalDivider(color = TerminalBorder)

            // Dedicated Hardware/Shortcut Accessory Toolbar (always above IME)
            TerminalAccessoryToolbar(
                ctrlStickyActive = state.ctrlStickyActive,
                altStickyActive = state.altStickyActive,
                applicationCursorKeys = state.terminalSnapshot.applicationCursorKeys,
                enabled = state.status == VmStatus.RUNNING,
                onToggleCtrl = viewModel::toggleCtrlSticky,
                onToggleAlt = viewModel::toggleAltSticky,
                onSendControl = viewModel::sendControlBytes,
                onShowKeyboard = { imeViewRef?.showSoftKeyboard() }
            )
        }
    }

    // Back press confirmation while VM is RUNNING
    if (showRunningBackDialog) {
        AlertDialog(
            onDismissRequest = { showRunningBackDialog = false },
            containerColor = TerminalSurface,
            title = {
                Text(
                    text = stringResource(R.string.back_dialog_title),
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.back_dialog_body),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            viewModel.pause()
                            showRunningBackDialog = false
                            onNavigateBackToVms()
                        },
                        modifier = Modifier.testTag("back_dialog_pause")
                    ) {
                        Text(stringResource(R.string.action_pause_vm))
                    }
                    Button(
                        onClick = {
                            viewModel.stop()
                            showRunningBackDialog = false
                            onNavigateBackToVms()
                        },
                        modifier = Modifier.testTag("back_dialog_stop")
                    ) {
                        Text(stringResource(R.string.action_stop_vm))
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showRunningBackDialog = false },
                    modifier = Modifier.testTag("back_dialog_cancel")
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Boot / OOM Error Recovery Dialog
    state.errorMessage?.let { err ->
        val fallbackRam = state.oomFallbackSuggestedRamMb
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            containerColor = TerminalSurface,
            title = {
                Text(
                    text = "Galat Mesin Virtual",
                    style = MaterialTheme.typography.titleLarge,
                    color = StatusError
                )
            },
            text = {
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                if (fallbackRam != null) {
                    Button(
                        onClick = { viewModel.retryFallbackRam(fallbackRam) },
                        modifier = Modifier.testTag("oom_fallback_button")
                    ) {
                        Text("Coba Boot $fallbackRam MB")
                    }
                } else {
                    Button(onClick = viewModel::dismissError) {
                        Text("Tutup")
                    }
                }
            },
            dismissButton = {
                if (fallbackRam != null) {
                    TextButton(onClick = viewModel::dismissError) {
                        Text("Batal")
                    }
                }
            }
        )
    }

    // CPU & CSR Hardware Inspector Sheet
    if (showInspector) {
        ModalBottomSheet(
            onDismissRequest = { showInspector = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = TerminalSurface
        ) {
            HardwareInspectorSheetContent(
                state = state,
                onPauseResume = {
                    if (state.status == VmStatus.RUNNING) viewModel.pause() else viewModel.resume()
                },
                onReset = {
                    viewModel.reset()
                    showInspector = false
                },
                onStop = {
                    viewModel.stop()
                    showInspector = false
                },
                onClearTerminal = viewModel::clearScreen,
                onFontIncrease = viewModel::increaseFont,
                onFontDecrease = viewModel::decreaseFont,
                onSetFontSize = viewModel::setFontSize,
                onSelectTheme = viewModel::setTerminalTheme,
                onCopyLog = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("KernoVM Log", viewModel.getPlainLog()))
                    uiScope.launch { snackbarHostState.showSnackbar("Log terminal disalin ke papan klip.") }
                },
                onSaveLog = {
                    viewModel.saveLogToVmWorkspace()
                },
                onEjectIso = {
                    viewModel.ejectIsoAndSwitchPrimaryDisk()
                }
            )
        }
    }
}

@Composable
private fun ConsoleTelemetryHeader(
    state: VmRuntimeState,
    onTogglePause: () -> Unit,
    onOpenInspector: () -> Unit
) {
    val (statusLabel, statusFg, statusBg) = when (state.status) {
        VmStatus.RUNNING -> Triple(if (state.isWfiSleeping) "WFI" else "RUN", StatusRunning, StatusRunningBg)
        VmStatus.BOOTING -> Triple("BOOT", TerminalSlatePrimary, TerminalSlateContainer)
        VmStatus.PAUSED -> Triple("PAUSE", StatusPaused, StatusPausedBg)
        VmStatus.HALTED -> Triple("HALT", StatusIdle, StatusIdleBg)
        VmStatus.ERROR -> Triple("ERR", StatusError, StatusErrorBg)
        VmStatus.STOPPED -> Triple("OFF", StatusIdle, StatusIdleBg)
    }

    Surface(
        color = TerminalSurface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    color = statusBg,
                    border = BorderStroke(1.dp, statusFg.copy(alpha = 0.5f)),
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusFg,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.vmName.ifBlank { "Tidak ada sesi aktif" },
                        style = MaterialTheme.typography.titleSmall,
                        color = TerminalTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val rateStr = formatIps(state.instructionsPerSecond)
                    val uptimeStr = formatUptime(state.uptimeSeconds)
                    val driveSuffix = buildString {
                        if (state.attachedIsoName != null) append(" · /dev/vda")
                        if (state.targetDiskName != null) append(" · /dev/vdb")
                    }
                    Text(
                        text = "${state.allocatedRamMb}MB · ${state.cpuThreadCount}T/${state.effectiveCpuQuotaPercent}% · $rateStr · $uptimeStr$driveSuffix",
                        style = MaterialTheme.typography.labelSmall,
                        color = TerminalTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.status == VmStatus.RUNNING || state.status == VmStatus.PAUSED) {
                    IconButton(
                        onClick = onTogglePause,
                        modifier = Modifier.testTag("console_pause_resume_button")
                    ) {
                        Icon(
                            imageVector = if (state.status == VmStatus.RUNNING) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (state.status == VmStatus.RUNNING) "Jeda VM" else "Lanjutkan VM",
                            tint = TerminalTextPrimary
                        )
                    }
                }
                IconButton(
                    onClick = onOpenInspector,
                    modifier = Modifier.testTag("console_inspector_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeveloperBoard,
                        contentDescription = "Buka Inspektor CPU dan kontrol daya",
                        tint = TerminalSlatePrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun FramebufferCanvasArea(
    framebufferState: FramebufferState,
    isRunning: Boolean,
    onCaptureBitmap: () -> Bitmap?,
    modifier: Modifier = Modifier
) {
    var fbBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(isRunning) {
        while (isRunning) {
            fbBitmap = onCaptureBitmap()
            kotlinx.coroutines.delay(66)
        }
        if (!isRunning) {
            fbBitmap = onCaptureBitmap()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("framebuffer_display_area"),
        contentAlignment = Alignment.Center
    ) {
        val currentBitmap = fbBitmap
        if (currentBitmap != null) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Resolution and telemetry info bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(TerminalSurface)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${framebufferState.width}×${framebufferState.height} RGB565 · /dev/fb0",
                        style = MaterialTheme.typography.labelSmall,
                        color = TerminalSlatePrimary
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            color = if (isRunning) StatusRunningBg else StatusIdleBg,
                            shape = MaterialTheme.shapes.extraSmall
                        ) {
                            Text(
                                text = if (isRunning) "LIVE" else "PAUSED",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isRunning) StatusRunning else StatusIdle,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                        IconButton(
                            onClick = { fbBitmap = onCaptureBitmap() },
                            modifier = Modifier
                                .size(24.dp)
                                .testTag("framebuffer_refresh_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Perbarui Framebuffer",
                                tint = TerminalTextSecondary
                            )
                        }
                    }
                }

                // Render Framebuffer Bitmap with 4:3 Aspect Ratio
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = currentBitmap.asImageBitmap(),
                        contentDescription = "Layar Framebuffer Grafis",
                        modifier = Modifier
                            .fillMaxSize()
                            .aspectRatio(framebufferState.width.toFloat() / framebufferState.height.toFloat())
                            .border(1.dp, TerminalBorder)
                            .testTag("framebuffer_image_canvas")
                    )
                }
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Computer,
                    contentDescription = null,
                    tint = TerminalTextSecondary,
                    modifier = Modifier.size(48.dp)
                )
                Text(
                    text = "LAYAR GRAFIS FRAMEBUFFER (/dev/fb0)",
                    style = MaterialTheme.typography.titleSmall,
                    color = TerminalTextPrimary
                )
                Text(
                    text = if (isRunning) "Menunggu sinyal output grafis dari guest..." else "Mulai atau boot sesi VM untuk mengaktifkan framebuffer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TerminalTextSecondary
                )
            }
        }
    }
}

@Composable
private fun TerminalCanvasArea(
    snapshot: AnsiTerminalBuffer.TerminalSnapshot,
    fontSizeSp: Float,
    theme: AnsiTerminalBuffer.ColorTheme = snapshot.theme,
    onResize: (Int, Int) -> Unit,
    onTapCanvas: () -> Unit,
    onAdjustFontSize: (Float) -> Unit = {}
) {
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    var zoomAccumulator by remember { mutableFloatStateOf(1f) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    zoomAccumulator *= zoom
                    if (zoomAccumulator > 1.15f) {
                        onAdjustFontSize(1f)
                        zoomAccumulator = 1f
                    } else if (zoomAccumulator < 0.85f) {
                        onAdjustFontSize(-1f)
                        zoomAccumulator = 1f
                    }
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTapCanvas
            )
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag("terminal_canvas")
    ) {
        // Compute terminal grid columns & rows dynamically on layout/rotation change
        // Avoid thrashing/reallocating grid when soft keyboard appears/animates
        val imeInsets = WindowInsets.ime
        val isImeVisible = imeInsets.getBottom(density) > 0

        val charWidthDp = with(density) { (fontSizeSp.sp.toPx() * 0.62f).toDp() }
        val lineHeightDp = with(density) { (fontSizeSp.sp.toPx() * 1.32f).toDp() }
        val computedCols = (maxWidth / charWidthDp).toInt().coerceIn(32, 160)
        val computedRows = (maxHeight / lineHeightDp).toInt().coerceIn(12, 80)

        var stableRows by remember { mutableIntStateOf(computedRows) }
        var stableCols by remember { mutableIntStateOf(computedCols) }

        if (!isImeVisible) {
            stableRows = computedRows
            stableCols = computedCols
        } else {
            // Keep rows stable while IME is visible; never shrink rows on keyboard popup
            stableCols = computedCols
        }

        LaunchedEffect(stableCols, stableRows) {
            onResize(stableCols, stableRows)
        }

        val allLines = remember(snapshot.revision, theme) {
            val sb = snapshot.scrollbackLines
            val scr = snapshot.screenLines
            val lastRelevantRow = scr.indices.lastOrNull { r ->
                r <= snapshot.cursorRow || scr[r].any { cell ->
                    cell.ch != ' ' || (cell.bgArgb != theme.defaultBg && cell.bgArgb != AnsiTerminalBuffer.DEFAULT_BG)
                }
            } ?: snapshot.cursorRow
            val visibleScr = if (lastRelevantRow < scr.size) {
                scr.subList(0, (lastRelevantRow + 1).coerceAtMost(scr.size))
            } else {
                scr
            }
            sb + visibleScr
        }
        val scrollbackCount = snapshot.scrollbackLines.size

        var autoScrollEnabled by remember { mutableStateOf(true) }

        LaunchedEffect(listState.isScrollInProgress) {
            if (listState.isScrollInProgress) {
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                autoScrollEnabled = lastVisible >= (allLines.size - 3).coerceAtLeast(0)
            }
        }

        // Only auto-scroll if user is not actively scrolling/dragging and autoScrollEnabled is true
        LaunchedEffect(snapshot.revision, isImeVisible) {
            if (autoScrollEnabled && !listState.isScrollInProgress && allLines.isNotEmpty()) {
                val scrollTarget = (allLines.size - 1).coerceAtLeast(0)
                listState.scrollToItem(scrollTarget)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 8.dp)
        ) {
            itemsIndexed(allLines) { index, lineCells ->
                val isCursorLine = snapshot.cursorVisible && index == (scrollbackCount + snapshot.cursorRow)
                val annotated = remember(lineCells, isCursorLine, snapshot.cursorCol, theme) {
                    buildStyledTerminalLine(
                        cells = lineCells,
                        cursorCol = if (isCursorLine) snapshot.cursorCol else -1,
                        theme = theme
                    )
                }
                Text(
                    text = annotated,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = fontSizeSp.sp,
                    lineHeight = (fontSizeSp * 1.30f).sp,
                    softWrap = false,
                    maxLines = 1
                )
            }
        }
    }
}

private fun buildStyledTerminalLine(
    cells: List<AnsiTerminalBuffer.ImmutableCell>,
    cursorCol: Int,
    theme: AnsiTerminalBuffer.ColorTheme = AnsiTerminalBuffer.ColorTheme.DEFAULT
): AnnotatedString {
    var end = cells.size
    while (end > 0 && cells[end - 1].ch == ' ' &&
        (cells[end - 1].bgArgb == theme.defaultBg || cells[end - 1].bgArgb == AnsiTerminalBuffer.DEFAULT_BG) &&
        (end - 1) != cursorCol
    ) {
        end--
    }
    if (cursorCol >= end) {
        end = (cursorCol + 1).coerceAtMost(cells.size)
    }
    if (end == 0) return AnnotatedString(" ")

    return buildAnnotatedString {
        var i = 0
        while (i < end) {
            val cell = cells[i]
            val isCursor = i == cursorCol
            val reversed = (cell.styleFlags and AnsiTerminalBuffer.STYLE_REVERSE) != 0 || isCursor
            val fg = Color(if (reversed) cell.bgArgb else cell.fgArgb)
            val bg = if (reversed) {
                Color(if (isCursor) theme.cursorColor else cell.fgArgb)
            } else if (cell.bgArgb != theme.defaultBg && cell.bgArgb != AnsiTerminalBuffer.DEFAULT_BG) {
                Color(cell.bgArgb)
            } else {
                Color.Transparent
            }
            val weight = if ((cell.styleFlags and AnsiTerminalBuffer.STYLE_BOLD) != 0) FontWeight.Bold else FontWeight.Normal
            val deco = if ((cell.styleFlags and AnsiTerminalBuffer.STYLE_UNDERLINE) != 0) TextDecoration.Underline else null
            val alpha = if ((cell.styleFlags and AnsiTerminalBuffer.STYLE_DIM) != 0) 0.65f else 1f

            // Coalesce contiguous cells with identical styling
            val runStart = i
            val sb = StringBuilder()
            sb.append(cell.ch)
            i++
            if (!isCursor) {
                while (i < end && i != cursorCol) {
                    val next = cells[i]
                    if (next.fgArgb == cell.fgArgb &&
                        next.bgArgb == cell.bgArgb &&
                        next.styleFlags == cell.styleFlags
                    ) {
                        sb.append(next.ch)
                        i++
                    } else {
                        break
                    }
                }
            }

            pushStyle(
                SpanStyle(
                    color = fg.copy(alpha = alpha),
                    background = bg,
                    fontWeight = weight,
                    textDecoration = deco
                )
            )
            append(sb.toString())
            pop()
        }
    }
}

@Composable
private fun TerminalAccessoryToolbar(
    ctrlStickyActive: Boolean,
    altStickyActive: Boolean,
    applicationCursorKeys: Boolean,
    enabled: Boolean,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onSendControl: (ByteArray) -> Unit,
    onShowKeyboard: () -> Unit
) {
    Surface(
        color = TerminalSurface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AccessoryKeyButton(
                label = "CTRL",
                active = ctrlStickyActive,
                enabled = enabled,
                testTag = "key_ctrl",
                onClick = onToggleCtrl
            )
            AccessoryKeyButton(
                label = "ALT",
                active = altStickyActive,
                enabled = enabled,
                testTag = "key_alt",
                onClick = onToggleAlt
            )
            AccessoryKeyButton(
                label = "ESC",
                enabled = enabled,
                testTag = "key_esc",
                onClick = { onSendControl(byteArrayOf(0x1B)) }
            )
            AccessoryKeyButton(
                label = "TAB",
                enabled = enabled,
                testTag = "key_tab",
                onClick = { onSendControl(byteArrayOf(0x09)) }
            )
            AccessoryKeyButton(
                label = "Ctrl+C",
                enabled = enabled,
                testTag = "key_ctrl_c",
                onClick = { onSendControl(byteArrayOf(0x03)) }
            )
            AccessoryKeyButton(
                label = "Ctrl+D",
                enabled = enabled,
                testTag = "key_ctrl_d",
                onClick = { onSendControl(byteArrayOf(0x04)) }
            )
            AccessoryKeyButton(
                label = "↑",
                enabled = enabled,
                testTag = "key_up",
                onClick = { onSendControl(arrowSeq('A', applicationCursorKeys)) }
            )
            AccessoryKeyButton(
                label = "↓",
                enabled = enabled,
                testTag = "key_down",
                onClick = { onSendControl(arrowSeq('B', applicationCursorKeys)) }
            )
            AccessoryKeyButton(
                label = "←",
                enabled = enabled,
                testTag = "key_left",
                onClick = { onSendControl(arrowSeq('D', applicationCursorKeys)) }
            )
            AccessoryKeyButton(
                label = "→",
                enabled = enabled,
                testTag = "key_right",
                onClick = { onSendControl(arrowSeq('C', applicationCursorKeys)) }
            )
            AccessoryKeyButton(
                label = "^L",
                enabled = enabled,
                testTag = "key_ctrl_l",
                onClick = { onSendControl(byteArrayOf(0x0C)) }
            )
            AccessoryKeyButton(
                label = "/",
                enabled = enabled,
                testTag = "key_slash",
                onClick = { onSendControl("/".toByteArray()) }
            )
            AccessoryKeyButton(
                label = "-",
                enabled = enabled,
                testTag = "key_dash",
                onClick = { onSendControl("-".toByteArray()) }
            )
            AccessoryKeyButton(
                label = "|",
                enabled = enabled,
                testTag = "key_pipe",
                onClick = { onSendControl("|".toByteArray()) }
            )
            AccessoryKeyButton(
                label = "~",
                enabled = enabled,
                testTag = "key_tilde",
                onClick = { onSendControl("~".toByteArray()) }
            )
            Surface(
                color = TerminalSurfaceElevated,
                border = BorderStroke(1.dp, TerminalBorder),
                shape = MaterialTheme.shapes.extraSmall,
                modifier = Modifier
                    .height(48.dp)
                    .clickable(onClick = onShowKeyboard)
                    .testTag("key_show_keyboard")
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Keyboard,
                        contentDescription = "Tampilkan keyboard lunak",
                        tint = TerminalTextPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun AccessoryKeyButton(
    label: String,
    active: Boolean = false,
    enabled: Boolean = true,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        color = if (active) TerminalSlateContainer else TerminalSurfaceElevated,
        border = BorderStroke(1.dp, if (active) TerminalSlatePrimary else TerminalBorder),
        shape = MaterialTheme.shapes.extraSmall,
        modifier = Modifier
            .height(48.dp)
            .widthIn(min = 48.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(testTag)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) TerminalTextPrimary else TerminalTextSecondary.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun EmptyConsoleState(onNavigateBackToVms: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "KONSOL SERIAL SIAGA",
                style = MaterialTheme.typography.titleMedium,
                color = TerminalTextPrimary
            )
            Text(
                text = "Pilih profil VM dari tab Mesin lalu tekan Boot untuk memulai eksekusi RV32IMA dan menghubungkan UART ttyS0.",
                style = MaterialTheme.typography.bodyMedium,
                color = TerminalTextSecondary
            )
            OutlinedButton(
                onClick = onNavigateBackToVms,
                modifier = Modifier.testTag("empty_console_goto_vms")
            ) {
                Text("Buka Daftar Mesin")
            }
        }
    }
}

@Composable
private fun HardwareInspectorSheetContent(
    state: VmRuntimeState,
    onPauseResume: () -> Unit,
    onReset: () -> Unit,
    onStop: () -> Unit,
    onClearTerminal: () -> Unit,
    onFontIncrease: () -> Unit,
    onFontDecrease: () -> Unit,
    onSetFontSize: (Float) -> Unit,
    onSelectTheme: (AnsiTerminalBuffer.ColorTheme) -> Unit,
    onCopyLog: () -> Unit,
    onSaveLog: () -> Unit,
    onEjectIso: () -> Unit = {}
) {
    val snap: RegisterSnapshot = state.registerSnapshot
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "INSPEKTOR CPU & KONTROL DAYA (${state.targetArchitecture.uppercase()})",
            style = MaterialTheme.typography.titleMedium,
            color = TerminalTextPrimary
        )

        // VirtIO Dual-Drive & Native Eject ISO / Switch Primary Disk card
        Surface(
            color = TerminalSurfaceElevated,
            border = BorderStroke(1.dp, TerminalBorder),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "VIRTIO DUAL-DRIVE & BOOTINDEX",
                    style = MaterialTheme.typography.labelMedium,
                    color = TerminalSlatePrimary
                )
                Text(
                    text = "/dev/vda (ISO RO): ${state.attachedIsoName ?: "Terlepas"} · " +
                        "/dev/vdb (Target Disk RW): ${state.targetDiskName ?: "Tidak terpasang"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TerminalTextSecondary
                )
                val orderText = if (state.virtioBlockNodes.isNotEmpty()) {
                    state.virtioBlockNodes.joinToString(" -> ") { node ->
                        "${node.deviceName ?: node.path}(bootindex=${node.bootIndex ?: 0})"
                    }
                } else {
                    "Slot utama aktif: ${state.primaryBootSlot}"
                }
                Text(
                    text = "FDT order: $orderText",
                    style = MaterialTheme.typography.labelSmall,
                    color = TerminalTextPrimary
                )
                OutlinedButton(
                    onClick = onEjectIso,
                    enabled = state.attachedIsoName != null && state.activeVmId != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("eject_iso_button")
                ) {
                    Icon(Icons.Outlined.Eject, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Eject ISO / Switch Primary Disk")
                }
            }
        }

        // Power controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onPauseResume,
                enabled = state.status == VmStatus.RUNNING || state.status == VmStatus.PAUSED,
                modifier = Modifier.weight(1f).testTag("inspector_pause_resume")
            ) {
                Icon(
                    imageVector = if (state.status == VmStatus.RUNNING) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null
                )
                Spacer(Modifier.padding(2.dp))
                Text(if (state.status == VmStatus.RUNNING) "Jeda" else "Lanjut")
            }
            OutlinedButton(
                onClick = onReset,
                enabled = state.activeVmId != null,
                modifier = Modifier.weight(1f).testTag("inspector_reset")
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Spacer(Modifier.padding(2.dp))
                Text("Reset")
            }
            OutlinedButton(
                onClick = onStop,
                enabled = state.activeVmId != null,
                modifier = Modifier.weight(1f).testTag("inspector_stop")
            ) {
                Icon(Icons.Filled.Stop, contentDescription = null)
                Spacer(Modifier.padding(2.dp))
                Text("Off")
            }
        }

        // Terminal utilities
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onFontDecrease, modifier = Modifier.testTag("font_decrease_button")) {
                Icon(Icons.Outlined.TextDecrease, contentDescription = "Perkecil font terminal")
            }
            Text(
                text = "${state.terminalFontSizeSp.toInt()}sp",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.testTag("font_size_display")
            )
            IconButton(onClick = onFontIncrease, modifier = Modifier.testTag("font_increase_button")) {
                Icon(Icons.Outlined.TextIncrease, contentDescription = "Perbesar font terminal")
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClearTerminal) {
                Icon(Icons.Outlined.DeleteSweep, contentDescription = "Bersihkan layar terminal")
            }
            IconButton(onClick = onCopyLog) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "Salin log terminal")
            }
            IconButton(onClick = onSaveLog, modifier = Modifier.testTag("inspector_save_log")) {
                Icon(Icons.Outlined.Save, contentDescription = "Simpan log ke penyimpanan VM")
            }
        }

        // Font scaling presets
        Text("SKALA FONT TERMINAL", style = MaterialTheme.typography.labelMedium, color = TerminalSlatePrimary)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val presets = listOf(
                10f to "10sp",
                12.5f to "12.5sp",
                14f to "14sp",
                16f to "16sp",
                18f to "18sp",
                20f to "20sp"
            )
            for ((size, label) in presets) {
                val isSelected = kotlin.math.abs(state.terminalFontSizeSp - size) < 0.6f
                Surface(
                    color = if (isSelected) TerminalSlateContainer else TerminalSurfaceElevated,
                    border = BorderStroke(1.dp, if (isSelected) TerminalSlatePrimary else TerminalBorder),
                    shape = MaterialTheme.shapes.extraSmall,
                    modifier = Modifier
                        .clickable { onSetFontSize(size) }
                        .testTag("font_preset_${size.toInt()}")
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSelected) TerminalSlatePrimary else TerminalTextPrimary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // Color theme selection
        Text("TEMA WARNA TERMINAL (ANSI BUFFER)", style = MaterialTheme.typography.labelMedium, color = TerminalSlatePrimary)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val themes = listOf(
                AnsiTerminalBuffer.ColorTheme.DEFAULT to ("Default" to "theme_chip_default"),
                AnsiTerminalBuffer.ColorTheme.CRT_GREEN to ("CRT Hijau" to "theme_chip_crt_green"),
                AnsiTerminalBuffer.ColorTheme.AMBER to ("Amber CRT" to "theme_chip_amber"),
                AnsiTerminalBuffer.ColorTheme.SOLARIZED to ("Solarized" to "theme_chip_solarized"),
                AnsiTerminalBuffer.ColorTheme.MONOKAI to ("Monokai" to "theme_chip_monokai")
            )
            for ((th, pair) in themes) {
                val (label, tag) = pair
                val isSelected = state.terminalTheme == th
                Surface(
                    color = if (isSelected) TerminalSlateContainer else TerminalSurfaceElevated,
                    border = BorderStroke(1.dp, if (isSelected) TerminalSlatePrimary else TerminalBorder),
                    shape = MaterialTheme.shapes.extraSmall,
                    modifier = Modifier
                        .clickable { onSelectTheme(th) }
                        .testTag(tag)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(Color(th.defaultBg))
                                .border(1.dp, Color(th.defaultFg), MaterialTheme.shapes.extraSmall)
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isSelected) TerminalSlatePrimary else TerminalTextPrimary
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = TerminalBorder)

        // CSR table
        Text("MACHINE CSR & CLINT", style = MaterialTheme.typography.labelMedium, color = TerminalSlatePrimary)
        Surface(
            color = TerminalSurfaceElevated,
            border = BorderStroke(1.dp, TerminalBorder),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CsrRow("pc", hex32(snap.pc), "misa", hex32(snap.misa))
                CsrRow("mstatus", hex32(snap.mstatus), "mtvec", hex32(snap.mtvec))
                CsrRow("mcause", hex32(snap.mcause), "mepc", hex32(snap.mepc))
                CsrRow("mtval", hex32(snap.mtval), "mie/mip", "${hex16(snap.mie)}/${hex16(snap.mip)}")
                CsrRow("mtime", snap.mtime.toString(), "uartRX", "${snap.uartRxBuffered} B")
            }
        }

        // General purpose registers x0..x31
        Text("REGISTER UMUM (x0 – x31)", style = MaterialTheme.typography.labelMedium, color = TerminalSlatePrimary)
        Surface(
            color = TerminalSurfaceElevated,
            border = BorderStroke(1.dp, TerminalBorder),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                for (row in 0 until 16) {
                    val leftIdx = row * 2
                    val rightIdx = leftIdx + 1
                    CsrRow(
                        k1 = ABI_NAMES[leftIdx],
                        v1 = hex32(snap.regs[leftIdx]),
                        k2 = ABI_NAMES[rightIdx],
                        v2 = hex32(snap.regs[rightIdx])
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
private fun CsrRow(k1: String, v1: String, k2: String, v2: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "${k1.padEnd(7)} $v1",
            style = MaterialTheme.typography.labelSmall,
            color = TerminalTextPrimary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "${k2.padEnd(7)} $v2",
            style = MaterialTheme.typography.labelSmall,
            color = TerminalTextSecondary,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun hex32(v: Int): String = String.format(Locale.US, "0x%08x", v)
private fun hex16(v: Int): String = String.format(Locale.US, "%04x", v and 0xFFFF)

private fun formatIps(ips: Long): String {
    return when {
        ips >= 1_000_000L -> String.format(Locale.US, "%.2f MIPS", ips / 1_000_000.0)
        ips >= 1_000L -> String.format(Locale.US, "%.0f kIPS", ips / 1_000.0)
        else -> "$ips IPS"
    }
}

private fun formatUptime(sec: Long): String {
    val m = sec / 60
    val s = sec % 60
    return String.format(Locale.US, "%02d:%02d", m, s)
}

private val ABI_NAMES = arrayOf(
    "zero", "ra", "sp", "gp", "tp", "t0", "t1", "t2",
    "s0", "s1", "a0", "a1", "a2", "a3", "a4", "a5",
    "a6", "a7", "s2", "s3", "s4", "s5", "s6", "s7",
    "s8", "s9", "s10", "s11", "t3", "t4", "t5", "t6"
)
