package local.codex.mobile

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import local.codex.mobile.core.MobileViewModel
import local.codex.mobile.core.TERMUX_PERMISSION
import rikka.shizuku.Shizuku
import local.codex.mobile.core.string
import local.codex.mobile.ui.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = getSharedPreferences("codex-mobile", MODE_PRIVATE)
        setContent {
            MobileTheme {
                val vm: MobileViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                val nav = rememberNavController()
                val startDestination = remember {
                    if (prefs.getBoolean("setup-complete", false)) "chat" else "welcome"
                }
                val focus = LocalFocusManager.current
                val keyboard = LocalSoftwareKeyboardController.current
                val releaseInput: () -> Unit = {
                    focus.clearFocus(force = true)
                    keyboard?.hide()
                }
                val current by nav.currentBackStackEntryAsState()
                val route = current?.destination?.route
                DisposableEffect(route) {
                    if (route == "credentials") window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
                }
                val scope = rememberCoroutineScope()
                val snackbar = remember { SnackbarHostState() }
                val drawer = rememberDrawerState(DrawerValue.Closed)
                var modal by rememberSaveable { mutableStateOf<String?>(null) }
                var granted by remember { mutableStateOf(Environment.isExternalStorageManager()) }

                val termuxPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
                    vm.report(if (allowed) "Termux autorizado." else "Permissão do Termux não concedida.")
                }
                val connectTermux: () -> Unit = {
                    if (checkSelfPermission(TERMUX_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
                        vm.report("Termux já autorizado.")
                    } else {
                        try {
                            packageManager.getPackageInfo("com.termux", 0)
                            termuxPermission.launch(TERMUX_PERMISSION)
                        } catch (_: PackageManager.NameNotFoundException) {
                            vm.report("Instale o Termux para ativar essa ferramenta.")
                        }
                    }
                }
                DisposableEffect(vm) {
                    val listener = Shizuku.OnRequestPermissionResultListener { _, result ->
                        vm.report(if (result == PackageManager.PERMISSION_GRANTED) "Shizuku autorizado." else "Permissão do Shizuku não concedida.")
                    }
                    Shizuku.addRequestPermissionResultListener(listener)
                    onDispose { Shizuku.removeRequestPermissionResultListener(listener) }
                }
                var background by remember { mutableStateOf(prefs.getBoolean("background", false)) }
                val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    if (!granted) vm.report("Ative notificações do Codex Mobile para acompanhar o serviço.")
                }
                val setBackground: (Boolean) -> Unit = { enabled ->
                    background = enabled
                    prefs.edit().putBoolean("background", enabled).apply()
                    val service = Intent(this, local.codex.mobile.core.BackgroundService::class.java)
                    if (enabled) {
                        if (android.os.Build.VERSION.SDK_INT >= 33 &&
                            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                        runCatching { androidx.core.content.ContextCompat.startForegroundService(this, service) }
                            .onFailure { background = false; prefs.edit().putBoolean("background", false).apply()
                                vm.report("Não foi possível iniciar o serviço em segundo plano.") }
                    } else stopService(service)
                }
                LaunchedEffect(background) {
                    if (background) runCatching {
                        androidx.core.content.ContextCompat.startForegroundService(this@MainActivity,
                            Intent(this@MainActivity, local.codex.mobile.core.BackgroundService::class.java))
                    }.onFailure { vm.report("Não foi possível iniciar o serviço em segundo plano.") }
                }
                val connectShizuku: () -> Unit = {
                    runCatching {
                        if (!Shizuku.pingBinder()) {
                            val launch = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                                ?: error("O app Shizuku não está instalado.")
                            startActivity(launch)
                            vm.report("Inicie o Shizuku e depois toque em Conectar novamente.")
                            return@runCatching
                        }
                        check(Shizuku.getUid() == 2000) { "Inicie o Shizuku via Depuração sem fio ou ADB." }
                        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) vm.report("Shizuku já autorizado.")
                        else Shizuku.requestPermission(28)
                    }.onFailure { vm.report(it.message ?: "Shizuku não disponível.") }
                }
                val grantResult = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                    val result = Environment.isExternalStorageManager()
                    if (result != granted) {
                        granted = result
                        if (route != "permissions" && route != "welcome") vm.storageChanged()
                    }
                }
                val askGrant = {
                    val request = Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                    try {
                        grantResult.launch(request)
                    } catch (_: ActivityNotFoundException) {
                        grantResult.launch(Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
                val back: () -> Unit = { releaseInput(); nav.popBackStack() }
                LaunchedEffect(route, modal, drawer.targetValue, state.credentialRequest?.optString("id")) {
                    releaseInput()
                }
                val loginBrowser: (String) -> Unit = { address ->
                    val uri = Uri.parse(address)
                    if (uri.scheme == "https") try {
                        CustomTabsIntent.Builder().build().launchUrl(this, uri)
                    } catch (_: ActivityNotFoundException) {
                        openBrowser(address)
                    }
                }
                LaunchedEffect(route, state.connected, state.accountLoaded, state.account != null) {
                    if (route == "welcome" || route == "permissions" || route == null) return@LaunchedEffect
                    vm.ensureStarted()
                    if (route != "login" && state.connected && state.accountLoaded && state.account == null) {
                        modal = null
                        nav.navigate("login") { popUpTo(nav.graph.id) { inclusive = true }; launchSingleTop = true }
                    }
                }
                LaunchedEffect(modal, state.connected) {
                    if (state.connected) when (modal) {
                        "meters" -> vm.rateLimits()
                        "configure" -> vm.models()
                    }
                }
                LaunchedEffect(state.error, route) {
                    val message = state.error ?: return@LaunchedEffect
                    if (route != "login" && state.connected) {
                        snackbar.showSnackbar(message)
                        vm.clearError()
                    }
                }

                var messageTarget by remember { mutableStateOf<Int?>(null) }
                CompositionLocalProvider(
                    LocalArtifactAccess provides ArtifactAccess(state.chat?.string("cwd"), state.connected, vm::readFile, vm::mediaUrl),
                    LocalUriHandler provides object : UriHandler {
                        override fun openUri(uri: String) {
                            when {
                                uri.startsWith("codex-file:") -> {
                                    releaseInput()
                                    nav.navigate("explorer/browse?file=" + uri.removePrefix("codex-file:"))
                                }
                                uri.startsWith("codex-credential:") -> {
                                    releaseInput()
                                    nav.navigate("credentials?alias=" + Uri.encode(uri.removePrefix("codex-credential:")))
                                }
                                uri.startsWith("codex-message:") -> {
                                    releaseInput()
                                    messageTarget = uri.removePrefix("codex-message:").toIntOrNull()
                                }
                                else -> openBrowser(uri)
                            }
                        }
                    },
                    LocalTextSelectionColors provides TextSelectionColors(Ink.Link, Ink.Accent.copy(alpha = .40f))
                ) {
                    Surface(Modifier.fillMaxSize(), color = Ink.Background) {
                        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                            NavHost(
                                navController = nav,
                                startDestination = startDestination,
                                enterTransition = { fadeIn(tween(180)) },
                                exitTransition = { fadeOut(tween(150)) }
                            ) {
                                composable("welcome") { Welcome { nav.navigate("permissions") } }
                                composable("permissions") { Permissions(granted, askGrant) { nav.navigate("login") } }
                                composable("login") {
                                    LoginScreen(state, { vm.login(it, loginBrowser) }, loginBrowser, vm::cancelLogin, vm::start) {
                                        prefs.edit().putBoolean("setup-complete", true).apply()
                                        nav.navigate("chat") { popUpTo(nav.graph.id) { inclusive = true }; launchSingleTop = true }
                                    }
                                }
                                composable("chat") {
                                    BoxWithConstraints(Modifier.fillMaxSize()) {
                                        val compact = maxWidth < 760.dp
                                        val new: () -> Unit = { vm.newChat(); scope.launch { drawer.close() } }
                                        val select: (String) -> Unit = { vm.select(it); scope.launch { drawer.close() } }
                                        val settings: () -> Unit = { scope.launch { drawer.close() }; nav.navigate("settings") }
                                        val content: @Composable () -> Unit = {
                                            ChatScreen(state, vm, compact, { scope.launch { drawer.open() } },
                                                { nav.navigate("explorer/browse") },
                                                { nav.navigate("explorer/projects") },
                                                { modal = "meters" },
                                                { nav.navigate("credentials") },
                                                { modal = "configure" },
                                                vm::start,
                                                { modal = "attachments" }, messageTarget, { messageTarget = null })
                                        }
                                        val drawerWidth = minOf(318.dp, maxWidth * .84f)
                                        if (compact) ModalNavigationDrawer(drawerState = drawer, drawerContent = {
                                            ModalDrawerSheet(drawerContainerColor = Ink.Surface, modifier = Modifier.width(drawerWidth),
                                                drawerShape = androidx.compose.ui.graphics.RectangleShape) {
                                                ConversationList(state, new, select, settings, vm)
                                            }
                                        }, content = content)
                                        else Row {
                                            Surface(Modifier.width(288.dp).fillMaxHeight(), color = Ink.Surface) {
                                                ConversationList(state, new, select, settings, vm)
                                            }
                                            Box(Modifier.weight(1f)) { content() }
                                        }
                                    }
                                }
                                composable("explorer/{mode}?file={file}", arguments = listOf(
                                    navArgument("file") { type = NavType.StringType; nullable = true; defaultValue = null }
                                )) { entry ->
                                    Explorer(state, vm, entry.arguments?.getString("mode") ?: "projects", granted,
                                        back, askGrant, entry.arguments?.getString("file"))
                                }
                                composable("settings") {
                                    Settings(state, back, { vm.usage(); nav.navigate("usage") }, askGrant, vm::logout, connectTermux, connectShizuku, background, setBackground)
                                }
                                composable("usage") { Usage(state, back) }
                                composable("credentials?alias={alias}", arguments = listOf(
                                    navArgument("alias") { type = NavType.StringType; nullable = true; defaultValue = null }
                                )) { entry ->
                                    CredentialsScreen(state, vm, back, entry.arguments?.getString("alias"))
                                }
                            }
                            when (modal) {
                                "meters" -> UsageSheet(state, dismiss = { modal = null })
                                "configure" -> ConfigurationSheet(state, { modal = null }) { model, effort ->
                                    vm.configure(model, effort) { modal = null }
                                }
                                "attachments" -> AttachmentSheet({ modal = null }) { mode ->
                                    modal = null
                                    nav.navigate("explorer/$mode")
                                }
                            }
                            CredentialAuthorization(state, vm)
                            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 92.dp))
                        }
                    }
                }
            }
        }
    }

    private fun openBrowser(address: String) {
        val uri = Uri.parse(address)
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "O Android não encontrou um navegador.", Toast.LENGTH_SHORT).show()
        }
    }
}
