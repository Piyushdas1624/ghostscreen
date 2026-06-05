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
import androidx.compose.material.icons.filled.Search
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
import com.etaegord.ghostscreen.data.ProtectedApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppItemInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val isSuggested: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartMaskSettingsScreen(
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
            }
            // Sort: Alphabetical app name
            .sortedBy { it.appName }

            withContext(Dispatchers.Main) {
                appList = loadedApps
                isLoading = false
            }
        }
    }

    // Filter app list based on search query
    val filteredList = remember(appList, searchQuery) {
        if (searchQuery.isBlank()) {
            appList
        } else {
            appList.filter { 
                it.appName.contains(searchQuery, ignoreCase = true) || 
                it.packageName.contains(searchQuery, ignoreCase = true)
            }
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
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                shape = RoundedCornerShape(28.dp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
            )

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
                                            db.appDao().deleteProtectedApp(
                                                ProtectedApp(app.packageName, app.appName)
                                            )
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
                                            db.appDao().deleteProtectedApp(
                                                ProtectedApp(app.packageName, app.appName)
                                            )
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
                // App Icon
                if (app.icon != null) {
                    Image(
                        bitmap = app.icon.toBitmap(56, 56).asImageBitmap(),
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
