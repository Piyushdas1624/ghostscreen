package com.etaegord.ghostscreen.ui.screens

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
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
            
            val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
            
            // Common messaging / privacy sensitive app keywords or packages
            val suggestedKeywords = listOf(
                "whatsapp", "telegram", "messenger", "instagram", "snapchat", 
                "facebook", "message", "signal", "discord", "viber", "skype", "slack",
                "bank", "wallet", "pay", "secure", "contacts", "gallery", "photo"
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
                }

                AppItemInfo(packageName, appName, icon, isSuggested)
            }.toMutableList()

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

            // Sort: Alphabetical app name
            loadedApps.sortBy { it.appName }

            withContext(Dispatchers.Main) {
                appList = loadedApps
                isLoading = false
            }
        }
    }

    var filterMode by remember { mutableStateOf("All") } // "All", "Enabled", "Disabled"

    // Filter app list based on search query and filter mode.
    //
    // Filter-chip crash root cause: the old inline filter re-derived the list
    // from `protectedPackages` (a Room Flow emission) on every toggle. Under
    // the "Enabled" filter, toggling a row off re-emitted the Flow and yanked
    // the row out of the list while its onToggle coroutine was still in flight,
    // dropping the row's LazyColumn key mid-composition.
    //
    // Fix: route through the pure AppListFilter (unit-tested) and derive the
    // result ONCE via derivedStateOf so the visible list is a stable snapshot
    // for the composition pass. The DB write is already idempotent, so a row
    // that has already vanished is a harmless no-op.
    val filterScope = remember(filterMode) {
        when (filterMode) {
            "Enabled" -> com.etaegord.ghostscreen.util.AppListFilter.Scope.ENABLED
            "Disabled" -> com.etaegord.ghostscreen.util.AppListFilter.Scope.DISABLED
            else -> com.etaegord.ghostscreen.util.AppListFilter.Scope.ALL
        }
    }
    val filteredList by remember {
        derivedStateOf {
            com.etaegord.ghostscreen.util.AppListFilter.apply(
                all = appList,
                scope = filterScope,
                query = searchQuery,
                protectedSet = protectedPackages,
                packageName = { it.packageName },
                appName = { it.appName }
            )
        }
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
                    FilterChip(
                        selected = filterMode == mode,
                        onClick = { filterMode = mode },
                        label = { Text(mode) },
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
                    // Suggested List Section
                    if (suggestedApps.isNotEmpty()) {
                        item {
                            Text(
                                text = "Suggested for Privacy",
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

                    // Normal List Section
                    if (normalApps.isNotEmpty()) {
                        item {
                            Text(
                                text = "All Applications",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                            )
                        }
                        
                        items(normalApps, key = { "norm_${it.packageName}" }) { app ->
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
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
                    Text(
                        text = app.appName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
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
