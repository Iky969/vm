package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.screens.ConsoleScreen
import com.example.ui.screens.ImageManagerScreen
import com.example.ui.screens.StorageScreen
import com.example.ui.screens.StoreScreen
import com.example.ui.screens.VmListScreen
import com.example.ui.theme.KernoVmTheme
import com.example.ui.theme.TerminalCanvasBg
import com.example.ui.theme.TerminalSlateContainer
import com.example.ui.theme.TerminalSlatePrimary
import com.example.ui.theme.TerminalSurface
import com.example.ui.theme.TerminalTextPrimary
import com.example.ui.theme.TerminalTextSecondary
import com.example.ui.viewmodel.ConsoleViewModel
import com.example.ui.viewmodel.ImageViewModel
import com.example.ui.viewmodel.KernoViewModelFactory
import com.example.ui.viewmodel.VmListViewModel

private const val ROUTE_VMS = "vms"
private const val ROUTE_CONSOLE = "console"
private const val ROUTE_STORE = "store"
private const val ROUTE_STORAGE = "storage"
private const val ROUTE_IMAGES = "images"

private data class NavDestination(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

private val TopDestinations = listOf(
    NavDestination(ROUTE_VMS, R.string.nav_vms, Icons.Filled.Computer, Icons.Outlined.Computer, "nav_vms"),
    NavDestination(ROUTE_CONSOLE, R.string.nav_console, Icons.Filled.Terminal, Icons.Outlined.Terminal, "nav_console"),
    NavDestination(ROUTE_STORE, R.string.nav_store, Icons.Filled.Storefront, Icons.Outlined.Storefront, "nav_store"),
    NavDestination(ROUTE_STORAGE, R.string.nav_storage, Icons.Filled.SdStorage, Icons.Outlined.SdStorage, "nav_storage")
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        val app = application as KernoVmApplication
        val factory = KernoViewModelFactory(
            vmRepository = app.vmRepository,
            imageRepository = app.imageRepository,
            sessionManager = app.vmSessionManager
        )

        setContent {
            KernoVmTheme {
                KernoVmRoot(app = app, viewModelFactory = factory)
            }
        }
    }
}

@Composable
private fun KernoVmRoot(
    app: KernoVmApplication,
    viewModelFactory: KernoViewModelFactory
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: ROUTE_VMS

    val vmListViewModel: VmListViewModel = viewModel(factory = viewModelFactory)
    val consoleViewModel: ConsoleViewModel = viewModel(factory = viewModelFactory)
    val imageViewModel: ImageViewModel = viewModel(factory = viewModelFactory)

    // Android 13+ POST_NOTIFICATIONS runtime request; denial automatically enables Auto-Pause fallback on ON_STOP
    val notificationPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        app.vmSessionManager.notificationPermissionGranted = granted
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            app.vmSessionManager.notificationPermissionGranted = granted
            if (!granted) {
                notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            app.vmSessionManager.notificationPermissionGranted = true
        }
        // Non-blocking background launch update check
        vmListViewModel.checkForUpdates(manual = false)
    }

    // Observe ON_STOP for mandatory default Auto-Pause fallback when FGS/notification is unavailable
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                app.vmSessionManager.onAppBackgrounded()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun navigateTopLevel(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalCanvasBg)
    ) {
        val useNavRail = maxWidth >= 600.dp

        if (useNavRail) {
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail(
                    containerColor = TerminalSurface,
                    contentColor = TerminalTextPrimary
                ) {
                    TopDestinations.forEach { dest ->
                        val selected = currentRoute == dest.route
                        NavigationRailItem(
                            selected = selected,
                            onClick = { navigateTopLevel(dest.route) },
                            icon = {
                                Icon(
                                    imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                    contentDescription = stringResource(dest.labelRes)
                                )
                            },
                            label = { Text(stringResource(dest.labelRes)) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = TerminalTextPrimary,
                                selectedTextColor = TerminalTextPrimary,
                                indicatorColor = TerminalSlateContainer,
                                unselectedIconColor = TerminalTextSecondary,
                                unselectedTextColor = TerminalTextSecondary
                            ),
                            modifier = Modifier.testTag(dest.testTag)
                        )
                    }
                }
                Scaffold(
                    modifier = Modifier.weight(1f),
                    containerColor = TerminalCanvasBg,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0)
                ) { innerPadding ->
                    KernoNavHost(
                        navController = navController,
                        vmListViewModel = vmListViewModel,
                        consoleViewModel = consoleViewModel,
                        imageViewModel = imageViewModel,
                        onNavigateTop = ::navigateTopLevel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        } else {
            val density = LocalDensity.current
            val isImeVisible = WindowInsets.ime.getBottom(density) > 0
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = TerminalCanvasBg,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    if (!isImeVisible) {
                        NavigationBar(
                            containerColor = TerminalSurface,
                            tonalElevation = 0.dp
                        ) {
                            TopDestinations.forEach { dest ->
                                val selected = currentRoute == dest.route
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateTopLevel(dest.route) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                            contentDescription = stringResource(dest.labelRes)
                                        )
                                    },
                                    label = { Text(stringResource(dest.labelRes)) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = TerminalTextPrimary,
                                        selectedTextColor = TerminalSlatePrimary,
                                        indicatorColor = TerminalSlateContainer,
                                        unselectedIconColor = TerminalTextSecondary,
                                        unselectedTextColor = TerminalTextSecondary
                                    ),
                                    modifier = Modifier.testTag(dest.testTag)
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                KernoNavHost(
                    navController = navController,
                    vmListViewModel = vmListViewModel,
                    consoleViewModel = consoleViewModel,
                    imageViewModel = imageViewModel,
                    onNavigateTop = ::navigateTopLevel,
                    modifier = Modifier.padding(innerPadding)
                )
            }
        }
    }
}

@Composable
private fun KernoNavHost(
    navController: androidx.navigation.NavHostController,
    vmListViewModel: VmListViewModel,
    consoleViewModel: ConsoleViewModel,
    imageViewModel: ImageViewModel,
    onNavigateTop: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = ROUTE_VMS,
        modifier = modifier.fillMaxSize()
    ) {
        composable(ROUTE_VMS) {
            VmListScreen(
                viewModel = vmListViewModel,
                onOpenConsole = { onNavigateTop(ROUTE_CONSOLE) }
            )
        }
        composable(ROUTE_CONSOLE) {
            ConsoleScreen(
                viewModel = consoleViewModel,
                onNavigateBackToVms = { onNavigateTop(ROUTE_VMS) }
            )
        }
        composable(ROUTE_STORE) {
            StoreScreen(viewModel = imageViewModel)
        }
        composable(ROUTE_STORAGE) {
            StorageScreen(viewModel = imageViewModel)
        }
        composable(ROUTE_IMAGES) {
            StorageScreen(viewModel = imageViewModel)
        }
    }
}
