package com.xmodern.aotoptimizer

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete // NEW
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.*
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.draw.scale
import androidx.compose.material3.FilterChip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.rememberAsyncImagePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun formatSize(kb: Long): String {
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return if (gb >= 1.0) "%.2f GB".format(gb) else if (mb >= 1.0) "%.1f MB".format(mb) else if (kb > 0) "$kb KB" else "0 B"
}

@Composable
fun AotOptimizerTheme(content: @Composable () -> Unit) {
    val colorScheme = darkColorScheme(
        primary = NeonBlue,
        onPrimary = Color.Black,
        background = DeepBlack,
        surface = DarkSurface,
        onSurface = Color.White,
        error = NeonRed
    )
    MaterialTheme(colorScheme = colorScheme, content = content)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            AotOptimizerTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    RootGuard()
                }
            }
        }
    }
}

@Composable
fun RootGuard() {
    var rootStatus by remember { mutableStateOf<Boolean?>(null) }
    var currentScreen by remember { mutableStateOf("dashboard") }

    LaunchedEffect(Unit) { rootStatus = ShellHelper.isRootGranted() }

    when (rootStatus) {
        null -> LoadingScreen("Verifying System Access...")
        true -> {
            if (currentScreen == "dashboard") {
                DashboardScreen(onNavigateToBatch = { currentScreen = "batch" })
            } else {
                BatchOptimizeScreen(onBack = { currentScreen = "dashboard" })
            }
        }
        false -> ErrorScreen(
            title = "ROOT ACCESS DENIED",
            message = "This system utility requires superuser privileges.\n\nPlease grant Root access and restart the app."
        )
    }
}

@Composable
fun LoadingScreen(message: String) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = NeonBlue)
            Spacer(modifier = Modifier.height(16.dp))
            Text(message, color = Color.Gray, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun ErrorScreen(title: String, message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.Security, null, tint = NeonRed, modifier = Modifier.size(64.dp))
        Spacer(modifier = Modifier.height(16.dp))
        Text(title, color = NeonRed, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text(message, color = Color.Gray, textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

@Composable

fun DashboardScreen(onNavigateToBatch: () -> Unit) {

    val context = LocalContext.current

    val scope = rememberCoroutineScope()

    val viewModel: AppViewModel = viewModel()

    val uiState by viewModel.uiState.collectAsState()

    

    // SEARCH STATES

        val filteredApps by viewModel.filteredApps.collectAsState()

            val isSearchActive by viewModel.isSearchActive.collectAsState()

            val searchQuery by viewModel.searchQuery.collectAsState()

            

            val focusRequester = remember { FocusRequester() }

        

            // Toast Listener

            LaunchedEffect(Unit) {

                viewModel.toastMessage.collect { message ->

                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

                }

            }

        

                LaunchedEffect(isSearchActive) {

        

                    if (isSearchActive) {

        

                        focusRequester.requestFocus()

        

                    }

        

                }

        

                

        

                // Auto-navigate to Batch Screen if process started (e.g. from Restore)

        

                LaunchedEffect(uiState.isBatchRunning) {

        

                    if (uiState.isBatchRunning) {

        

                        onNavigateToBatch()

        

                    }

        

                }

        

                

        

                var selectedApp by remember { mutableStateOf<AppItem?>(null) }

    var isProcessing by remember { mutableStateOf(false) }

    var showSortMenu by remember { mutableStateOf(false) }

    

    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {

        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshData() }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }

    }



    Scaffold(

        contentWindowInsets = WindowInsets(0.dp),

        topBar = {

            Column(modifier = Modifier.fillMaxWidth().background(DeepBlack).windowInsetsPadding(WindowInsets.statusBars).padding(16.dp)) {

                if (isSearchActive) {

                    // --- SEARCH MODE ---

                    Row(

                        modifier = Modifier.fillMaxWidth().height(56.dp).background(DarkSurface, RoundedCornerShape(28.dp)).padding(horizontal = 8.dp),

                        verticalAlignment = Alignment.CenterVertically

                    ) {

                        Icon(Icons.Default.Search, null, tint = Color.Gray, modifier = Modifier.padding(start = 8.dp))

                        TextField(

                            value = searchQuery,

                            onValueChange = { viewModel.onSearchQueryChanged(it) },

                            placeholder = { Text("Search package...", color = Color.Gray) },

                            colors = TextFieldDefaults.colors(

                                focusedContainerColor = Color.Transparent,

                                unfocusedContainerColor = Color.Transparent,

                                focusedIndicatorColor = Color.Transparent,

                                unfocusedIndicatorColor = Color.Transparent,

                                cursorColor = NeonCyan,

                                                                focusedTextColor = Color.White,

                                                                unfocusedTextColor = Color.White

                                                            ),

                                                            modifier = Modifier.weight(1f).focusRequester(focusRequester),

                                                            singleLine = true

                                                        )

                                                        IconButton(onClick = { viewModel.setSearchActive(false) }) {

                            Icon(Icons.Default.Close, null, tint = Color.Gray)

                        }

                    }

                } else {

                                        // --- NORMAL MODE ---

                                        Row(

                                            modifier = Modifier.fillMaxWidth(), 

                                            horizontalArrangement = Arrangement.SpaceBetween, 

                                            verticalAlignment = Alignment.CenterVertically

                                        ) {

                                            Column(modifier = Modifier.weight(1f)) {

                                                Text("AOT OPTIMIZER", color = NeonBlue, fontWeight = FontWeight.Black, fontSize = 20.sp, letterSpacing = 2.sp)

                                                Text("SYSTEM CORE // ART MANAGER", color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)

                                            }

                                            

                                            Row(verticalAlignment = Alignment.CenterVertically) {

                                                // Removed Search Icon from here

                                                

                                                Surface(

                                                    onClick = onNavigateToBatch,

                                                    color = Color.Transparent,

                                                    shape = RoundedCornerShape(50),

                                                    border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan.copy(alpha = 0.6f))

                                                ) {

                                                    Text("FULL AOT", color = NeonCyan, fontSize = 12.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))

                                                }

                                                

                                                Spacer(modifier = Modifier.width(8.dp))

                                                

                                                                            Box {

                                                

                                                                                IconButton(onClick = { 

                                                

                                                                                    viewModel.checkDexOptStatus() // Refresh real status on open

                                                

                                                                                    showSortMenu = true 

                                                

                                                                                }) {

                                                

                                                                                    Icon(Icons.Default.Sort, contentDescription = "Sort", tint = NeonCyan)

                                                

                                                                                }

                                                

                                                                                DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {

                                                

                                                                                    SortOption.values().forEach { option ->

                                                

                                                                                        DropdownMenuItem(

                                                

                                                                                            text = { Text(option.label, color = if (uiState.sortOption == option) NeonBlue else Color.Unspecified) },

                                                

                                                                                            onClick = { viewModel.setSortOption(option); showSortMenu = false }

                                                

                                                                                        )

                                                

                                                                                    }

                                                

                                                                                    Divider(modifier = Modifier.padding(vertical = 4.dp), color = Color.Gray.copy(alpha = 0.3f))

                                                

                                                                                    

                                                

                                                                                    // AUTO-AOT TOGGLE

                                                

                                                                                    DropdownMenuItem(

                                                

                                                                                        text = { 

                                                

                                                                                            Text(

                                                

                                                                                                if (uiState.isDexOptDisabled) "Enable Auto-AOT" else "Disable Auto-AOT", 

                                                

                                                                                                color = if (uiState.isDexOptDisabled) NeonGreen else NeonRed, // Green=Safe(Disabled), Red=Danger(Enabled)? Or vice versa. Let's use Red for Action "Disable" and Green for "Enable"

                                                

                                                                                                fontWeight = FontWeight.Bold

                                                

                                                                                            ) 

                                                

                                                                                        },

                                                

                                                                                        onClick = { 

                                                

                                                                                            viewModel.toggleDexOpt()

                                                

                                                                                            // Keep menu open to see change? Or close? User preference usually close.

                                                

                                                                                            // Let's keep it open or just close. Closing is safer UI.

                                                

                                                                                            showSortMenu = false

                                                

                                                                                        }

                                                

                                                                                    )

                                                

                                                

                                                

                                                                                                                                                            // CHECK UPDATES

                                                

                                                

                                                

                                                                                                                                                            DropdownMenuItem(

                                                

                                                

                                                

                                                                                                                                                                text = { Text("Check Updates", color = Color.White) },

                                                

                                                

                                                

                                                                                                                                                                onClick = { 

                                                

                                                

                                                

                                                                                                                                                                    viewModel.checkForUpdates()

                                                

                                                

                                                

                                                                                                                                                                    showSortMenu = false

                                                

                                                

                                                

                                                                                                                                                                }

                                                

                                                

                                                

                                                                                                                                                            )

                                                

                                                

                                                

                                                                                                                                                            

                                                

                                                

                                                

                                                                                                                                                            // SAVED PROFILES

                                                

                                                

                                                

                                                                                                                                                            DropdownMenuItem(

                                                

                                                

                                                

                                                                                                                                                                text = { Text("Saved Profiles", color = Color.White) },

                                                

                                                

                                                

                                                                                                                                                                onClick = { 

                                                

                                                

                                                

                                                                                                                                                                    viewModel.loadProfiles()

                                                

                                                

                                                

                                                                                                                                                                    showSortMenu = false

                                                

                                                

                                                

                                                                                                                                                                }

                                                

                                                

                                                

                                                                                                                                                            )

                                                

                                                

                                                

                                                                                                                        

                                                

                                                

                                                

                                                                                                                                                            // SHOW ALL APPS

                                                

                                                

                                                

                                                                                                                        DropdownMenuItem(

                                                                                                                            text = { 

                                                                                                                                Text(

                                                                                                                                    "Show All Apps", 

                                                                                                                                    color = Color.White,

                                                                                                                                    fontWeight = if (uiState.showAllApps) FontWeight.Bold else FontWeight.Normal

                                                                                                                                ) 

                                                                                                                            },

                                                                                            onClick = { 

                                                                                                viewModel.toggleShowAllApps()

                                                                                                showSortMenu = false

                                                                                            }

                                                                                        )

                                                        

                                                                                            // SEARCH OPTION

                                                        

                                                                                            DropdownMenuItem(

                                                        

                                                                                                text = { Text("Search", color = Color.White) },

                                                        

                                                                                                onClick = { 

                                                        

                                                                                                    viewModel.setSearchActive(true)

                                                        

                                                                                                    showSortMenu = false

                                                        

                                                                                                }

                                                        

                                                                                            )

                                                    }

                                                }

                                            }

                                        }

                }

                

                Spacer(modifier = Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {

                    // Use filteredApps size for stats if searching, or keep total? Usually stats show TOTAL.

                    // Let's show TOTAL stats, but maybe dim them if searching.

                    StatCard("USER APPS", uiState.apps.size.toString(), NeonBlue, Modifier.weight(1f))

                    StatCard("OPTIMIZED", uiState.apps.count { it.status.contains("speed") || it.status.contains("everything") }.toString(), NeonGreen, Modifier.weight(1f))

                }

            }

        }

    ) { paddingValues ->

        if (uiState.isLoading && uiState.apps.isEmpty()) {

            LoadingScreen("Scanning Packages...")

        } else {

            val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

            LazyColumn(modifier = Modifier.padding(paddingValues).padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = navBarPadding + 80.dp)) {

                items(items = filteredApps, key = { it.packageName }) { appItem ->

                    AppItemRow(app = appItem, modifier = Modifier.animateItemPlacement(animationSpec = tween(500))) { selectedApp = appItem }

                    Spacer(modifier = Modifier.height(8.dp))

                }

            }

        }

    }

    if (selectedApp != null) {
        val currentApp = selectedApp!!
        var logLines by remember { mutableStateOf(listOf<String>()) }
        
        LaunchedEffect(currentApp.packageName) {
            if (currentApp.complexity == "scanning...") {
                val sizeMb = withContext(Dispatchers.IO) { ShellHelper.getDexSizeInMb(currentApp.packageName) }
                val rating = when {
                    sizeMb == 0 -> "unknown"
                    sizeMb < 15 -> "lite code"
                    sizeMb < 45 -> "standard"
                    sizeMb < 80 -> "heavy"
                    else -> "extreme"
                }
                viewModel.updateAppDetails(currentApp.packageName, currentApp.status, currentApp.size, currentApp.sizeBytes, currentApp.framework)
                val updatedApps = viewModel.uiState.value.apps.map { 
                    if (it.packageName == currentApp.packageName) it.copy(complexity = rating) else it 
                }
                selectedApp = currentApp.copy(complexity = rating)
            }
        }

        CompilationDialog(
            app = currentApp, isProcessing = isProcessing, logLines = logLines,
            onDismiss = { if (!isProcessing) selectedApp = null },
            onOptimize = { mode ->
                scope.launch {
                    isProcessing = true
                    logLines = listOf("> Target: ${currentApp.packageName}", "> Mode: $mode", "> Force: Enabled")
                    val res = ShellHelper.compilePackage(currentApp.packageName, mode, force = true)
                    
                    val updatedLogs = logLines.toMutableList()
                    res.out.forEach { updatedLogs.add(it) }
                    res.err.forEach { updatedLogs.add("[ERR] $it") }
                    logLines = updatedLogs

                    if (res.isSuccess) {
                        val status = ShellHelper.getCompilationFilter(currentApp.packageName)
                        val sizeStr = ShellHelper.getArtifactsSize(currentApp.packageName)
                        val sizeKb = ShellHelper.getArtifactsSizeInKb(currentApp.packageName)
                        viewModel.updateAppDetails(currentApp.packageName, status, sizeStr, sizeKb, currentApp.framework)
                        
                        // Save Contract & Log
                        val isSaved = viewModel.saveContract(currentApp.packageName, mode)
                        val dbLog = if (isSaved) "> [DB] Contract saved: $mode" else "> [DB] System rejected optimization (No Contract)"
                        
                        // Append to logs (using mutable list logic)
                        val finalLogs = logLines.toMutableList()
                        finalLogs.add(dbLog)
                        logLines = finalLogs
                        
                        val displayMode = mode.replaceFirstChar { it.uppercase() }.replace("-profile", " Profile")
                        Toast.makeText(context, "${currentApp.label} - $displayMode", Toast.LENGTH_SHORT).show()
                    }
                    delay(500); isProcessing = false; selectedApp = null
                }
            },
            onReset = {
                scope.launch {
                    isProcessing = true; logLines = listOf("> Resetting...")
                    val res = ShellHelper.resetPackage(currentApp.packageName)
                    val updatedLogs = logLines.toMutableList()
                    res.out.forEach { updatedLogs.add(it) }
                    logLines = updatedLogs
                    
                    viewModel.updateAppDetails(currentApp.packageName, "no-opt", "0 B", 0L, currentApp.framework)
                    isProcessing = false; selectedApp = null
                }
            }
        )
    }

    // CHECK UPDATES PROGRESS
    if (uiState.isLoading && uiState.checkUpdatesProgress > 0f) {
        Dialog(onDismissRequest = {}) {
            Card(colors = CardDefaults.cardColors(containerColor = DeepBlack), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = NeonCyan)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Verifying Contracts...", color = Color.White, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("${(uiState.checkUpdatesProgress * 100).toInt()}%", color = NeonCyan, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }

    if (uiState.showBatchSheet && uiState.newAppsDetected.isNotEmpty()) {
        BatchOptimizationSheet(newApps = uiState.newAppsDetected, onDismiss = { viewModel.dismissBatchSheet() }, onOptimizeAll = {})
    }
    
    if (uiState.showUpdatesSheet && uiState.updatesDetected.isNotEmpty()) {
        UpdatesSheet(
            updates = uiState.updatesDetected,
            onDismiss = { viewModel.dismissUpdatesSheet() },
            onRestore = { viewModel.restoreOptimizations() }
        )
    }
    
    if (uiState.showProfilesSheet) {
        ProfilesSheet(
            profiles = uiState.savedProfiles,
            onDismiss = { viewModel.dismissProfilesSheet() },
            onDelete = { pkg -> viewModel.deleteProfile(pkg) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchOptimizeScreen(onBack: () -> Unit) {
    val viewModel: AppViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()
    val view = LocalView.current
    
    // Fix: Explicitly set enabled and onBack
    BackHandler(enabled = true, onBack = { 
       if (!uiState.isBatchRunning) onBack() 
    })
    
    DisposableEffect(Unit) {
            view.keepScreenOn = true; viewModel.calculateGlobalSize()
            onDispose { 
                view.keepScreenOn = false
                // Reset state if we are leaving and not running (finished or aborted)
                if (!viewModel.uiState.value.isBatchRunning) {
                    viewModel.resetBatchState()
                }
            }
        }
        Scaffold(
            containerColor = DeepBlack,
            topBar = {
                TopAppBar(
                    title = { Text("BATCH SYSTEM OPTIMIZE", fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) },
                    navigationIcon = { IconButton(onClick = onBack, enabled = !uiState.isBatchRunning) { Icon(Icons.Default.ArrowBack, null) } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = DeepBlack, titleContentColor = NeonCyan, navigationIconContentColor = Color.White)
                )
            }
        ) { paddingValues ->
            Column(modifier = Modifier.padding(paddingValues).fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (!uiState.isBatchRunning && uiState.batchProgress == 0f) {
                    Text("SELECT GLOBAL STRATEGY", color = Color.Gray, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    OptionButton("SPEED PROFILE", "Recommended. Uses usage profiles.", NeonBlue) { viewModel.startBatchOptimize("speed-profile") }
                    OptionButton("SPEED (FULL)", "Full AOT. Faster, but larger disk usage.", NeonGreen) { viewModel.startBatchOptimize("speed") }
                    OptionButton("EVERYTHING", "Max performance. Heaviest.", NeonPurple) { viewModel.startBatchOptimize("everything") }
                    OptionButton("RESET ALL", "Delete all compiled code.", NeonRed) { viewModel.startBatchOptimize("reset") }
                    Spacer(modifier = Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("TOTAL SYSTEM AOT ARTIFACTS", color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        if (uiState.isCalculatingGlobalSize && uiState.globalSizeKb == 0L) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(4.dp), color = NeonCyan, strokeWidth = 2.dp)
                        } else {
                            Text(formatSize(uiState.globalSizeKb), color = NeonCyan, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            if (uiState.isCalculatingGlobalSize) Text("Calculating...", color = Color.Gray, fontSize = 10.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                } else {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
                        CircularProgressIndicator(progress = uiState.batchProgress, modifier = Modifier.fillMaxSize(), strokeWidth = 8.dp, color = NeonCyan, trackColor = DarkSurface)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${(uiState.batchProgress * 100).toInt()}%", fontSize = 32.sp, fontWeight = FontWeight.Black, color = Color.White)
                            Text(formatSize(uiState.globalSizeKb), fontSize = 12.sp, color = NeonCyan, fontFamily = FontFamily.Monospace)
                        }
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                    Text("PROCESS: ${uiState.batchCurrentIndex} / ${uiState.batchTotal}", color = NeonCyan, fontFamily = FontFamily.Monospace)
                    Text(uiState.batchCurrentApp, color = Color.Gray, fontSize = 12.sp, maxLines = 1, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
                    Spacer(modifier = Modifier.height(24.dp)); TerminalBox(logs = uiState.batchLogs)
                    Spacer(modifier = Modifier.height(32.dp))
                    if (uiState.isBatchRunning) {
                        Button(onClick = { 
                            viewModel.stopBatchOptimize()
                            onBack()
                        }, colors = ButtonDefaults.buttonColors(containerColor = NeonRed)) { Text("ABORT SYSTEM PROCESS", color = Color.White, fontWeight = FontWeight.Bold) }
                    } else if (uiState.batchProgress >= 1f) {
                        Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = NeonGreen)) { Text("RETURN TO DASHBOARD", color = Color.Black, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
}

@Composable
fun StatCard(label: String, value: String, color: Color, modifier: Modifier) {
    Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), modifier = modifier.border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(12.dp))) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(value, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = color)
            Text(label, fontSize = 10.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun AppItemRow(app: AppItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), modifier = modifier.fillMaxWidth().clickable { onClick() }) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painter = rememberAsyncImagePainter(app.icon), contentDescription = null, modifier = Modifier.size(48.dp).clip(CircleShape))
            Spacer(modifier = Modifier.width(16.dp))
            
            // FIXED: Added weight(1f) to prevent overflow pushing badges off-screen
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = app.label, 
                        color = Color.White, 
                        fontWeight = FontWeight.Bold, 
                        maxLines = 1, 
                        overflow = TextOverflow.Ellipsis
                    )
                    if (app.isNew) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(color = NeonBlue, shape = RoundedCornerShape(4.dp)) { Text("NEW", fontSize = 8.sp, color = Color.Black, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) }
                    }
                }
                Text(
                    text = app.packageName, 
                    color = Color.Gray, 
                    fontSize = 10.sp, 
                    lineHeight = 12.sp, 
                    maxLines = 1, 
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            if (app.framework != "Native") { FrameworkBadge(app.framework); Spacer(modifier = Modifier.width(8.dp)) }
            if (app.size != "..." && app.size != "0 B") {
                Surface(color = Color.Transparent, shape = RoundedCornerShape(4.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color.Gray.copy(alpha = 0.3f)), modifier = Modifier.padding(end = 8.dp)) {
                    Text(app.size, color = NeonCyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
            StatusChip(app.status)
        }
    }
}

@Composable
fun StatusChip(status: String) {
    val (color, label) = when {
        status.contains("everything") -> NeonPurple to "MAXIMUM"
        status.contains("speed") -> NeonGreen to "FAST"
        status.contains("quicken") || status.contains("verify") -> NeonOrange to "BALANCED"
        status == "loading..." -> Color.Gray to "..."
        else -> NeonRed to "SLOW"
    }
    Surface(color = color.copy(alpha = 0.1f), shape = RoundedCornerShape(50), border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.5f))) {
        Text(text = if (status == "loading...") "..." else status.uppercase(), color = color, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompilationDialog(app: AppItem, isProcessing: Boolean, logLines: List<String>, onDismiss: () -> Unit, onOptimize: (String) -> Unit, onReset: () -> Unit) {
    BackHandler(enabled = isProcessing) { /* Blocked */ }
    ModalBottomSheet(
        onDismissRequest = { if (!isProcessing) onDismiss() }, 
        sheetState = rememberModalBottomSheetState(confirmValueChange = { !isProcessing }),
        containerColor = DeepBlack, 
        windowInsets = WindowInsets(0), 
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(modifier = Modifier.padding(24.dp).fillMaxWidth().navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(contentAlignment = Alignment.BottomEnd) {
                    Image(painter = rememberAsyncImagePainter(app.icon), contentDescription = null, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, Color.Gray.copy(alpha = 0.2f), RoundedCornerShape(12.dp)))
                    ComplexityIndicator(app.complexity)
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(text = app.label, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = app.complexity.uppercase(), color = getComplexityColor(app.complexity), fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Text(" • ", color = Color.DarkGray)
                        Text(text = "CODE DENSITY", color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (app.framework != "Native") { FrameworkBadge(app.framework); Spacer(modifier = Modifier.width(8.dp)) }
                        if (app.size != "..." && app.size != "0 B") {
                            Surface(color = Color.Transparent, shape = RoundedCornerShape(4.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color.Gray.copy(alpha = 0.3f))) {
                                Text(text = app.size, color = NeonCyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        StatusChip(app.status)
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
            if (isProcessing) {
                Text("EXECUTING ART COMMANDS...", color = NeonBlue, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.height(8.dp)); LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = NeonBlue, trackColor = DarkSurface)
                Spacer(modifier = Modifier.height(16.dp)); TerminalBox(logLines); Spacer(modifier = Modifier.height(24.dp))
            } else {
                Text("SELECT OPTIMIZATION PROFILE", color = NeonBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.height(16.dp))
                OptionButton("SPEED (FULL)", "Full AOT. Faster, but larger disk usage.", NeonGreen) { onOptimize("speed") }
                OptionButton("EVERYTHING", "Force compile all code. Maximum Optimization.", NeonPurple) { onOptimize("everything") }
                OptionButton("SPEED PROFILE", "Recommended. Balanced performance.", NeonBlue) { onOptimize("speed-profile") }
                OptionButton("QUICKEN", "Fast install. Interpreted execution.", NeonOrange) { onOptimize("quicken") }
                Spacer(modifier = Modifier.height(16.dp)); Divider(color = Color.DarkGray.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onReset, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent), border = androidx.compose.foundation.BorderStroke(1.dp, NeonRed), modifier = Modifier.fillMaxWidth()) {
                    Text("RESET / CLEAR COMPILED ARTIFACTS", color = NeonRed, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun ComplexityIndicator(complexity: String) {
    val color = getComplexityColor(complexity)
    Box(modifier = Modifier.offset(x = 4.dp, y = 4.dp).size(14.dp).clip(CircleShape).background(DeepBlack).padding(2.dp).clip(CircleShape).background(color))
}

fun getComplexityColor(complexity: String): Color {
    return when (complexity) { "lite code" -> NeonGreen; "standard" -> NeonBlue; "heavy" -> NeonOrange; "extreme" -> NeonRed; else -> Color.Gray }
}

@Composable
fun FrameworkBadge(framework: String) {
    val fwColor = when (framework) { "Flutter" -> Color(0xFF02569B); "React Native" -> Color(0xFF61DAFB); else -> Color.Gray }
    Surface(color = fwColor.copy(alpha = 0.2f), shape = RoundedCornerShape(4.dp), border = androidx.compose.foundation.BorderStroke(1.dp, fwColor.copy(alpha = 0.5f))) {
        Text(text = framework.uppercase(), color = fwColor, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
    }
}

@Composable
fun OptionButton(title: String, subtitle: String, color: Color, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onClick() }) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Bolt, null, tint = color); Spacer(modifier = Modifier.width(16.dp))
            Column { 
                Text(title, color = Color.White, fontWeight = FontWeight.Bold)
                Text(subtitle, color = Color.Gray, fontSize = 12.sp, lineHeight = 14.sp) // Fixed line height
            }
        }
    }
}

@Composable
fun TerminalBox(logs: List<String>) {
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) { if (logs.isNotEmpty()) listState.animateScrollToItem(logs.lastIndex) }
    Surface(color = Color(0xFF111111), shape = RoundedCornerShape(8.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF333333)), modifier = Modifier.fillMaxWidth().height(200.dp) ) {
        LazyColumn(state = listState, contentPadding = PaddingValues(12.dp), modifier = Modifier.fillMaxSize()) {
            items(logs) { logLine ->
                val color = when { logLine.startsWith(">") -> NeonBlue; logLine.contains("[ERR]") || logLine.contains("Failure") -> NeonRed; logLine.contains("Success") -> NeonGreen; else -> Color(0xFF00E676) }
                Text(text = logLine, color = color, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchOptimizationSheet(newApps: List<AppItem>, onDismiss: () -> Unit, onOptimizeAll: () -> Unit) {
    var isRunning by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf(listOf<String>()) }
    var progress by remember { mutableStateOf(0f) }
    val viewModel: AppViewModel = viewModel()
    BackHandler(enabled = isRunning) { /* Blocked */ }
    
    val scope = rememberCoroutineScope() 

    val runBatch = { 
        isRunning = true
        logs = listOf("> Starting Batch...", "> Strategy: Speed (AOT)")
        scope.launch(Dispatchers.Main) { 
             newApps.forEachIndexed {
                 index, app ->
                 logs = logs + "> Compiling: ${app.label}"
                 val res = withContext(Dispatchers.IO) { ShellHelper.compilePackage(app.packageName, "speed") }
                 
                 if (res.isSuccess) {
                    logs = logs + "  [OK] Success."
                 } else {
                    logs = logs + "  [ERR] Failed."
                 }
                 
                 viewModel.updateAppDetails(app.packageName, "speed", "...", 0L, app.framework)
                 progress = (index + 1).toFloat() / newApps.size
             }
             logs = logs + "> BATCH COMPLETE."
             delay(1000)
             onDismiss()
        }
    }
    
    ModalBottomSheet(
        onDismissRequest = { /* Block dismiss */ }, 
        sheetState = rememberModalBottomSheetState(confirmValueChange = { false }),
        containerColor = DeepBlack, 
        windowInsets = WindowInsets(0)
    ) {
        Column(modifier = Modifier.padding(24.dp).fillMaxWidth().navigationBarsPadding()) {
// ... (rest of content)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.NewReleases, null, tint = NeonPurple); Spacer(modifier = Modifier.width(12.dp))
                Column { Text("SYSTEM CHANGES DETECTED", color = NeonPurple, fontWeight = FontWeight.Bold, fontSize = 16.sp); Text("${newApps.size} new apps found.", color = Color.Gray, fontSize = 12.sp) }
            }
            Spacer(modifier = Modifier.height(24.dp))
            if (isRunning) {
                LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth(), color = NeonPurple, trackColor = DarkSurface)
                Spacer(modifier = Modifier.height(16.dp)); TerminalBox(logs = logs)
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(newApps) { app ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(60.dp)) {
                            Image(painter = rememberAsyncImagePainter(app.icon), null, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, Color.Gray, RoundedCornerShape(12.dp)))
                            Spacer(modifier = Modifier.height(4.dp)); Text(app.label, maxLines = 1, fontSize = 10.sp, color = Color.Gray, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = DarkSurface), modifier = Modifier.weight(1f)) { Text("IGNORE", color = Color.Gray) }
                    Button(onClick = { runBatch() }, colors = ButtonDefaults.buttonColors(containerColor = NeonPurple), modifier = Modifier.weight(1f)) { Text("OPTIMIZE ALL", color = Color.Black, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesSheet(updates: List<OptimizationContract>, onDismiss: () -> Unit, onRestore: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = { /* Block dismiss */ },
        sheetState = rememberModalBottomSheetState(confirmValueChange = { false }),
        containerColor = DeepBlack, 
        windowInsets = WindowInsets(0)
    ) {
        Column(modifier = Modifier.padding(24.dp).fillMaxWidth().navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.NewReleases, null, tint = NeonOrange)
                Spacer(modifier = Modifier.width(12.dp))
                Column { 
                    Text("OPTIMIZATION DRIFT DETECTED", color = NeonOrange, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("${updates.size} apps updated or reset by system.", color = Color.Gray, fontSize = 12.sp)
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                items(updates) { contract ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(contract.packageName, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("-> ${contract.targetMode}", color = NeonCyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onDismiss, 
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurface), 
                    modifier = Modifier.weight(1f)
                ) { Text("IGNORE", color = Color.Gray) }
                
                Button(
                    onClick = onRestore, 
                    colors = ButtonDefaults.buttonColors(containerColor = NeonOrange), 
                    modifier = Modifier.weight(1f)
                ) { Text("RESTORE ALL", color = Color.Black, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

fun getStatusColor(status: String): Color {
    return when {
        status.contains("everything") -> NeonPurple
        status.contains("speed") && !status.contains("profile") -> NeonGreen
        status.contains("speed-profile") -> NeonBlue
        status.contains("quicken") -> NeonOrange
        else -> Color.Gray
    }
}

fun getPriority(status: String): Int {
    return when {
        status.contains("everything") -> 4
        status.contains("speed") && !status.contains("profile") -> 3
        status.contains("speed-profile") -> 2
        status.contains("quicken") -> 1
        else -> 0
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesSheet(profiles: List<OptimizationContract>, onDismiss: () -> Unit, onDelete: (String) -> Unit) {
    val context = LocalContext.current
    var filterManual by remember { mutableStateOf(true) }
    
    val filteredProfiles = remember(profiles, filterManual) {
        val filtered = if (filterManual) profiles.filter { it.isManual } else profiles.filter { !it.isManual }
        filtered.sortedByDescending { getPriority(it.targetMode) }
    }
    
    ModalBottomSheet(
        onDismissRequest = { onDismiss() },
        containerColor = DeepBlack, 
        windowInsets = WindowInsets(0),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(modifier = Modifier.padding(24.dp).fillMaxWidth().fillMaxHeight(0.85f).navigationBarsPadding()) {
            Text("SAVED PROFILES", color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp, letterSpacing = 1.sp)
            Spacer(modifier = Modifier.height(16.dp))
            
            // Filter Chips
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = filterManual,
                    onClick = { filterManual = true },
                    label = { Text("Manual") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = NeonCyan, selectedLabelColor = Color.Black)
                )
                FilterChip(
                    selected = !filterManual,
                    onClick = { filterManual = false },
                    label = { Text("Auto") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = NeonPurple, selectedLabelColor = Color.Black)
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            Text("${filteredProfiles.size} contracts found.", color = Color.Gray, fontSize = 12.sp)
            
            Spacer(modifier = Modifier.height(16.dp))
            
            if (filteredProfiles.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No profiles found.", color = Color.Gray, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(filteredProfiles) { contract ->
                        val icon = remember(contract.packageName) {
                            try { context.packageManager.getApplicationIcon(contract.packageName) } catch (e: Exception) { null }
                        }
                        
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), 
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (icon != null) {
                                Image(
                                    painter = rememberAsyncImagePainter(icon),
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                            }
                            
                            Column(modifier = Modifier.weight(1f)) {
                                Text(contract.packageName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("Target: ${contract.targetMode.uppercase()}", color = getStatusColor(contract.targetMode), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            }
                            
                            IconButton(onClick = { onDelete(contract.packageName) }) {
                                Icon(Icons.Default.Delete, null, tint = Color.Gray)
                            }
                        }
                        Divider(color = Color.DarkGray.copy(alpha = 0.3f))
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onDismiss, 
                colors = ButtonDefaults.buttonColors(containerColor = DarkSurface), 
                modifier = Modifier.fillMaxWidth()
            ) { Text("CLOSE", color = Color.White) }
        }
    }
}
