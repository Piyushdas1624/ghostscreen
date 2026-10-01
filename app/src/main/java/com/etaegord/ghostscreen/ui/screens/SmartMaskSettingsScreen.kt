package com.etaegord.ghostscreen.ui.screens

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.etaegord.ghostscreen.data.AppDatabase
import com.etaegord.ghostscreen.data.PreferencesManager
import com.etaegord.ghostscreen.data.ProtectedApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Build
import android.view.inputmethod.InputMethodManager

data class AppItemInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val isSuggested: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartMaskSettingsScreen(
    preferencesManager: PreferencesManager,
    db: AppDatabase,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }

    var isAiSensitiveShieldingEnabled by remember { mutableStateOf(preferencesManager.isAiSensitiveShieldingEnabled) }
    var isAiShieldPasswordsEnabled by remember { mutableStateOf(preferencesManager.isAiShieldPasswordsEnabled) }
    var isAiShieldCardsEnabled by remember { mutableStateOf(preferencesManager.isAiShieldCardsEnabled) }
    var isAiShieldOtpsEnabled by remember { mutableStateOf(preferencesManager.isAiShieldOtpsEnabled) }
    var isAiShieldBalancesEnabled by remember { mutableStateOf(preferencesManager.isAiShieldBalancesEnabled) }
    
    var appList by remember { mutableStateOf<List<AppItemInfo>>(emptyList()) }
    val protectedApps by db.appDao().getAllProtectedApps().collectAsState(initial = emptyList())
    val protectedPackages = remember(protectedApps) { protectedApps.map { it.packageName }.toSet() }

    var showDisclaimerDialog by remember {
        mutableStateOf(!preferencesManager.isSmartMaskDisclaimerUnderstood)
    }

    if (showDisclaimerDialog) {
        AlertDialog(
            onDismissRequest = {},
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = "System UI & Keyboard Guide",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Text(
                            text = "To keep your screen fully hidden, protect your keyboard and System UI (for status bar / notifications).",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    item {
                        Text(
                            text = "💡 Why is this needed?",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "• Notification Shade: Masking System UI blocks snooping if notifications are pulled down accidentally.\n" +
                                   "• Keyboard Swiping: Masking Gboard keeps the privacy mask active and secure while typing.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    item {
                        Text(
                            text = "🔄 Conditional Masking (Smart trigger)",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "The keyboard and notification shade only trigger masking if you are currently using a masked app (e.g. Messages). They remain normal on apps you did not mask (e.g. WhatsApp).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    item {
                        Text(
                            text = "📱 OEM Compatibility:",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "• Google / Moto / Realme / Vivo / Samsung: com.android.systemui\n" +
                                   "• Xiaomi / MIUI / HyperOS: com.miui.systemui\n" +
                                   "• Infinix / Tecno: com.transsion.systemui\n" +
                                   "• Keyboards: Gboard (com.google.android.inputmethod.latin) or Samsung Keyboard (com.samsung.android.honeyboard).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        preferencesManager.isSmartMaskDisclaimerUnderstood = true
                        showDisclaimerDialog = false
                    }
                ) {
                    Text("Understood")
                }
            }
        )
    }

    // Load installed launcher applications in background
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            
            val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(
                    mainIntent,
                    android.content.pm.PackageManager.ResolveInfoFlags.of(android.content.pm.PackageManager.MATCH_ALL.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(mainIntent, android.content.pm.PackageManager.MATCH_ALL)
            }
            
            // Common messaging / privacy sensitive app keywords or packages
            val suggestedKeywords = listOf(
                "whatsapp", "telegram", "messenger", "instagram", "snapchat", 
                "facebook", "message", "signal", "discord", "viber", "skype", "slack",
                "bank", "wallet", "pay", "secure", "contacts", "gallery", "photo",
                "browser", "chrome", "firefox", "brave", "opera", "edge", "dialer",
                "phone", "gboard", "keyboard", "input"
            )
            val topPrivacyPackagePrefixes = listOf(
                "com.whatsapp", "org.telegram", "org.thoughtcrime.securesms",
                "com.google.android.apps.messaging", "com.android.mms", "com.transsion.smartmessage",
                "com.instagram.android", "com.snapchat.android", "com.facebook.orca",
                "com.google.android.apps.nbu.paisa.user", "com.phonepe.app", "net.one97.paytm",
                "com.paypal.android.p2pmobile", "in.org.npci.upiapp", "com.android.chrome",
                "org.mozilla.firefox", "com.brave.browser", "com.google.android.inputmethod.latin",
                "com.samsung.android.honeyboard", "com.touchtype.swiftkey", "com.kika.keyboard",
                "com.android.systemui", "com.transsion.systemui", "com.miui.systemui"
            )

            val loadedApps = resolveInfos.mapNotNull { resolveInfo ->
                val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
                val packageName = activityInfo.packageName
                val appName = resolveInfo.loadLabel(pm).toString()
                
                // Get app icon
                val icon = try {
                    resolveInfo.loadIcon(pm)
                } catch (e: Exception) {
                    null
                }

                val isSuggested = suggestedKeywords.any { keyword ->
                    packageName.contains(keyword, ignoreCase = true) || appName.contains(keyword, ignoreCase = true)
                } || topPrivacyPackagePrefixes.any { prefix ->
                    packageName.startsWith(prefix, ignoreCase = true)
                }

                AppItemInfo(packageName, appName, icon, isSuggested)
            }.distinctBy { it.packageName }.toMutableList()

            // Predefined System UI and Keyboard packages for suggestions
            val commonPackages = listOf(
                "com.android.systemui" to "System UI (Standard/Samsung/Google/Moto/OPPO/Vivo)",
                "com.miui.systemui" to "System UI (Xiaomi/Redmi/POCO/HyperOS)",
                "com.transsion.systemui" to "System UI (Infinix/Tecno)",
                "com.huawei.systemui" to "System UI (Huawei/Honor)",
                "com.google.android.inputmethod.latin" to "Gboard (Google Keyboard)",
                "com.samsung.android.honeyboard" to "Samsung Keyboard",
                "com.touchtype.swiftkey" to "SwiftKey Keyboard",
                "com.kika.keyboard.emoji.teclado" to "Kika Keyboard (Infinix/Tecno)",
                "com.baidu.input_mi" to "Baidu Input (Xiaomi)"
            )

            commonPackages.forEach { (pkg, name) ->
                // Avoid duplicates if already loaded via launcher
                if (loadedApps.none { it.packageName == pkg }) {
                    try {
                        val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                            pm.getApplicationInfo(pkg, android.content.pm.PackageManager.ApplicationInfoFlags.of(0))
                        else
                            @Suppress("DEPRECATION") pm.getApplicationInfo(pkg, 0)
                        val icon = pm.getApplicationIcon(appInfo)
                        loadedApps.add(
                            AppItemInfo(
                                packageName = pkg,
                                appName = name,
                                icon = icon,
                                isSuggested = true
                            )
                        )
                    } catch (_: Exception) {}
                }
            }

            // Also query installed keyboards via InputMethodManager
            try {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                val imis = imm?.inputMethodList ?: emptyList()
                for (imi in imis) {
                    val pkg = imi.packageName
                    if (loadedApps.none { it.packageName == pkg }) {
                        val label = try { imi.loadLabel(pm).toString() } catch (_: Exception) { "Keyboard" }
                        val icon = try { imi.loadIcon(pm) } catch (_: Exception) { null }
                        loadedApps.add(AppItemInfo(pkg, label, icon, isSuggested = true))
                    }
                }
            } catch (_: Exception) {}

            // Also query all installed applications to include non-launcher apps and system components
            try {
                val installedApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getInstalledApplications(android.content.pm.PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION") pm.getInstalledApplications(0)
                }
                for (appInfo in installedApps) {
                    val pkg = appInfo.packageName
                    if (pkg == context.packageName) continue
                    if (loadedApps.none { it.packageName == pkg }) {
                        val isUserApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 ||
                                        (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                        val name = try { appInfo.loadLabel(pm).toString() } catch (_: Exception) { pkg }
                        val isSuggested = suggestedKeywords.any { keyword ->
                            pkg.contains(keyword, ignoreCase = true) || name.contains(keyword, ignoreCase = true)
                        } || topPrivacyPackagePrefixes.any { prefix ->
                            pkg.startsWith(prefix, ignoreCase = true)
                        }
                        if (isUserApp || isSuggested) {
                            val icon = try { pm.getApplicationIcon(appInfo) } catch (_: Exception) { null }
                            loadedApps.add(AppItemInfo(pkg, name, icon, isSuggested = isSuggested))
                        }
                    }
                }
            } catch (_: Exception) {}

            // Sort: Alphabetical app name and ensure strictly unique packages
            val uniqueApps = loadedApps.distinctBy { it.packageName }.sortedBy { it.appName }

            withContext(Dispatchers.Main) {
                appList = uniqueApps
                isLoading = false
            }
        }
    }

    var filterMode by remember { mutableStateOf("All") } // "All", "Enabled", "Disabled"

    // Filter app list based on search query and filter mode.
    val filteredList = remember(appList, filterMode, searchQuery, protectedPackages) {
        val filterScope = when (filterMode) {
            "Enabled" -> com.etaegord.ghostscreen.util.AppListFilter.Scope.ENABLED
            "Disabled" -> com.etaegord.ghostscreen.util.AppListFilter.Scope.DISABLED
            else -> com.etaegord.ghostscreen.util.AppListFilter.Scope.ALL
        }
        com.etaegord.ghostscreen.util.AppListFilter.apply(
            all = appList,
            scope = filterScope,
            query = searchQuery,
            protectedSet = protectedPackages,
            packageName = { it.packageName },
            appName = { it.appName }
        )
    }

    // Split into suggested vs others
    val (suggestedApps, normalApps) = remember(filteredList) {
        filteredList.partition { it.isSuggested }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Smart Mask Apps",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showDisclaimerDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "System UI & Keyboard Info",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            // AI Sensitive Field Auto-Shielding Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 4.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isAiSensitiveShieldingEnabled) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    else MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Security,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "AI Sensitive Field Shielding",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Auto-drops micro-shields over passwords, cards, OTPs & balances (<0.5% CPU)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = isAiSensitiveShieldingEnabled,
                            onCheckedChange = {
                                isAiSensitiveShieldingEnabled = it
                                preferencesManager.isAiSensitiveShieldingEnabled = it
                            }
                        )
                    }

                    androidx.compose.animation.AnimatedVisibility(visible = isAiSensitiveShieldingEnabled) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                FilterChip(
                                    selected = isAiShieldPasswordsEnabled,
                                    onClick = {
                                        isAiShieldPasswordsEnabled = !isAiShieldPasswordsEnabled
                                        preferencesManager.isAiShieldPasswordsEnabled = isAiShieldPasswordsEnabled
                                    },
                                    label = { Text("Passwords", fontSize = 11.sp, maxLines = 1) }
                                )
                                FilterChip(
                                    selected = isAiShieldCardsEnabled,
                                    onClick = {
                                        isAiShieldCardsEnabled = !isAiShieldCardsEnabled
                                        preferencesManager.isAiShieldCardsEnabled = isAiShieldCardsEnabled
                                    },
                                    label = { Text("Cards", fontSize = 11.sp, maxLines = 1) }
                                )
                                FilterChip(
                                    selected = isAiShieldOtpsEnabled,
                                    onClick = {
                                        isAiShieldOtpsEnabled = !isAiShieldOtpsEnabled
                                        preferencesManager.isAiShieldOtpsEnabled = isAiShieldOtpsEnabled
                                    },
                                    label = { Text("OTPs", fontSize = 11.sp, maxLines = 1) }
                                )
                                FilterChip(
                                    selected = isAiShieldBalancesEnabled,
                                    onClick = {
                                        isAiShieldBalancesEnabled = !isAiShieldBalancesEnabled
                                        preferencesManager.isAiShieldBalancesEnabled = isAiShieldBalancesEnabled
                                    },
                                    label = { Text("Balances", fontSize = 11.sp, maxLines = 1) }
                                )
                            }
                        }
                    }
                }
            }

            // Pill-shaped Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                placeholder = {
                    Text(
                        "Search apps...",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                shape = CircleShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                ),
                singleLine = true
            )
            
            // Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("All", "Enabled", "Disabled").forEach { mode ->
                    val isSelected = filterMode == mode
                    val chipScale by animateFloatAsState(
                        targetValue = if (isSelected) 1.05f else 1.0f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        ),
                        label = "chipScale_$mode"
                    )
                    FilterChip(
                        selected = isSelected,
                        onClick = { filterMode = mode },
                        label = { Text(mode) },
                        modifier = Modifier.graphicsLayer {
                            scaleX = chipScale
                            scaleY = chipScale
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    if (searchQuery.isNotBlank() || filterMode != "All") {
                        items(filteredList, key = { it.packageName }) { app ->
                            AppRow(
                                app = app,
                                isProtected = protectedPackages.contains(app.packageName),
                                onToggle = { isChecked ->
                                    scope.launch(Dispatchers.IO) {
                                        if (isChecked) {
                                            db.appDao().insertProtectedApp(
                                                ProtectedApp(app.packageName, app.appName)
                                            )
                                        } else {
                                            db.appDao().deleteProtectedAppByPackage(app.packageName)
                                        }
                                    }
                                }
                            )
                        }
                    } else {
                        // 1. Suggested List Section (Top Privacy Targets)
                        if (suggestedApps.isNotEmpty()) {
                            item {
                                Text(
                                    text = "Suggested for Privacy (${suggestedApps.size})",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                            
                            items(suggestedApps, key = { "sug_${it.packageName}" }) { app ->
                                AppRow(
                                    app = app,
                                    isProtected = protectedPackages.contains(app.packageName),
                                    onToggle = { isChecked ->
                                        scope.launch(Dispatchers.IO) {
                                            if (isChecked) {
                                                db.appDao().insertProtectedApp(
                                                    ProtectedApp(app.packageName, app.appName)
                                                )
                                            } else {
                                                db.appDao().deleteProtectedAppByPackage(app.packageName)
                                            }
                                        }
                                    }
                                )
                            }
                        }

                        // 2. All Applications Section (Every installed application)
                        item {
                            Text(
                                text = "All Applications (${filteredList.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                            )
                        }
                        
                        items(filteredList, key = { "all_${it.packageName}" }) { app ->
                            AppRow(
                                app = app,
                                isProtected = protectedPackages.contains(app.packageName),
                                onToggle = { isChecked ->
                                    scope.launch(Dispatchers.IO) {
                                        if (isChecked) {
                                            db.appDao().insertProtectedApp(
                                                ProtectedApp(app.packageName, app.appName)
                                            )
                                        } else {
                                            db.appDao().deleteProtectedAppByPackage(app.packageName)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppRow(
    app: AppItemInfo,
    isProtected: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val rowContainerColor by animateColorAsState(
        targetValue = if (isProtected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "rowContainerColor"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = rowContainerColor
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // App Icon — bitmap is decoded once per app and cached, otherwise
                // toBitmap()/asImageBitmap() would allocate on every recomposition
                // and cause GC jank in a long app list.
                //
                // Bug fix (search "GhostScreen" crash): the app's OWN launcher entry
                // is returned by queryIntentActivities and its icon is an
                // AdaptiveIconDrawable whose intrinsic size is -1 until bounded —
                // Drawable.toBitmap can throw on some API levels for that case.
                // Because this runs inside remember(), the exception was re-thrown on
                // every recomposition that re-keyed it (e.g. typing in the search box),
                // making the search-for-"GhostScreen" crash deterministic.
                // Fix: runCatching so any decode failure falls back to the letter tile.
                val cachedBitmap = remember(app.packageName) {
                    runCatching { app.icon?.toBitmap(56, 56)?.asImageBitmap() }.getOrNull()
                }
                if (cachedBitmap != null) {
                    Image(
                        bitmap = cachedBitmap,
                        contentDescription = null,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = app.appName.firstOrNull()?.toString() ?: "?",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                // App Info
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = app.appName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (app.isSuggested) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = "SUGGESTED",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = app.packageName,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }

            // Switch with checkmark
            Switch(
                checked = isProtected,
                onCheckedChange = onToggle,
                thumbContent = {
                    if (isProtected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            )
        }
    }
}
