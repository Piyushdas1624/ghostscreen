package com.etaegord.ghostscreen.ui.screens

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.etaegord.ghostscreen.util.isGhostScreenAccessibilityEnabled
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.etaegord.ghostscreen.data.AppDatabase
import com.etaegord.ghostscreen.data.PreferencesManager
import com.etaegord.ghostscreen.service.GhostScreenAccessibilityService
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    preferencesManager: PreferencesManager,
    db: AppDatabase,
    onNavigateToSettings: () -> Unit,
    onNavigateToSmartMask: () -> Unit,
    onNavigateToAuditLog: () -> Unit,
    onNavigateToAbout: () -> Unit
) {
    val context = LocalContext.current
    var isArmed by remember { mutableStateOf(preferencesManager.isArmed) }

    // Status indicators
    var isOverlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var isAccessibilityGranted by remember { mutableStateOf(isGhostScreenAccessibilityEnabled(context)) }

    // Refresh permission + armed state when returning from system settings.
    // Without this the warning banners and armed chip stay stale after the user
    // grants overlay/accessibility permission or toggles the app from outside.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isOverlayGranted = Settings.canDrawOverlays(context)
                isAccessibilityGranted = isGhostScreenAccessibilityEnabled(context)
                isArmed = preferencesManager.isArmed
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Database counts
    val protectedAppsCount by db.appDao().getAllProtectedApps().collectAsState(initial = emptyList())
    val auditLogsCount by db.appDao().getAllAuditLogs().collectAsState(initial = emptyList())

    // ──────────────────────────────────────────────────
    // Pulse animation. One choreographer-driven InfiniteTransition (not two
    // 16ms-ticking forever-coroutines) drives both pulse values, so the home
    // screen no longer recomposes constantly while idle. graphicsLayer keeps the
    // work on the GPU compositor. The overlay engine (separate, custom view) is
    // untouched and still bypasses Compose animation per the project rule.
    // ──────────────────────────────────────────────────
    val pulseTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.0f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(2000, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(2000, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.VisibilityOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "GhostScreen",
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            BottomNavigationBar(
                currentScreen = "home",
                onHomeClick = {},
                onSettingsClick = onNavigateToSettings,
                onAboutClick = onNavigateToAbout
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Service Status Banners
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                if (!isOverlayGranted) {
                    StatusWarningBanner(
                        title = "Overlay Permission Missing",
                        subtitle = "Tap to grant permission to draw the privacy mask.",
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        }
                    )
                }
                if (!isAccessibilityGranted) {
                    StatusWarningBanner(
                        title = "Accessibility Service Disabled",
                        subtitle = "Required to auto-detect and protect apps.",
                        onClick = {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            context.startActivity(intent)
                        }
                    )
                }
            }

            // Status Chip
            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (isArmed) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline
                            )
                    )
                    Text(
                        text = if (isArmed) "Protection Active" else "Ready to Mask",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ──────────────────────────────────────────────────
            // Concentric Circle Master Toggle
            // ALL animations use graphicsLayer for GPU compositing
            // ──────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                val primaryColor = MaterialTheme.colorScheme.primary
                val surfaceHighest = MaterialTheme.colorScheme.surfaceContainerHighest
                val surfaceHigh = MaterialTheme.colorScheme.surfaceContainerHigh

                // Outermost pulsing ring — GPU-only, no blur
                if (isArmed) {
                    Box(
                        modifier = Modifier
                            .size(280.dp)
                            .graphicsLayer {
                                scaleX = pulseScale
                                scaleY = pulseScale
                                alpha = pulseAlpha * 0.4f
                            }
                            .clip(CircleShape)
                            .background(primaryColor.copy(alpha = 0.15f))
                    )
                }

                // Outer ring
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .clip(CircleShape)
                        .background(
                            if (isArmed) primaryColor.copy(alpha = 0.10f)
                            else surfaceHighest.copy(alpha = 0.5f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    // Middle ring
                    Box(
                        modifier = Modifier
                            .size(200.dp)
                            .clip(CircleShape)
                            .background(
                                if (isArmed) primaryColor.copy(alpha = 0.15f)
                                else surfaceHigh.copy(alpha = 0.6f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        // Interactive center circle
                        val toggleColorTarget = if (isArmed) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHigh
                        
                        // We use the basic animateColorAsState here since it's just a click transition,
                        // not a continuous loop.
                        val animColor by androidx.compose.animation.animateColorAsState(
                            targetValue = toggleColorTarget,
                            animationSpec = androidx.compose.animation.core.tween(400),
                            label = "toggleColor"
                        )

                        Card(
                            onClick = {
                                val newArmed = !preferencesManager.isArmed
                                preferencesManager.isArmed = newArmed
                                isArmed = newArmed
                            },
                            modifier = Modifier
                                .size(160.dp)
                                .border(
                                    width = if (isArmed) 3.dp else 2.dp,
                                    color = if (isArmed) primaryColor
                                    else MaterialTheme.colorScheme.outlineVariant,
                                    shape = CircleShape
                                ),
                            shape = CircleShape,
                            colors = CardDefaults.cardColors(containerColor = animColor),
                            elevation = CardDefaults.cardElevation(
                                defaultElevation = if (isArmed) 8.dp else 2.dp
                            )
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PowerSettingsNew,
                                        contentDescription = if (isArmed) "Disarm" else "Arm",
                                        tint = if (isArmed) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = if (isArmed) "ARMED" else "Activate",
                                        fontWeight = FontWeight.Black,
                                        fontSize = 14.sp,
                                        color = if (isArmed) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        letterSpacing = 2.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Smart Mask Card
            Card(
                onClick = onNavigateToSmartMask,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Smart Mask",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${protectedAppsCount.size} apps protected",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Quick Stats Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    modifier = Modifier.weight(1f),
                    title = "Smart Mask",
                    value = "${protectedAppsCount.size} apps",
                    icon = Icons.Default.Apps,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    onClick = onNavigateToSmartMask
                )
                StatCard(
                    modifier = Modifier.weight(1f),
                    title = "Audit Log",
                    value = "${auditLogsCount.size} blocked",
                    icon = Icons.Default.Security,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    onClick = onNavigateToAuditLog
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
fun StatusWarningBanner(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontSize = 14.sp
                )
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun StatCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    icon: ImageVector,
    containerColor: Color,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier.height(120.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Default.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun BottomNavigationBar(
    currentScreen: String,
    onHomeClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onAboutClick: () -> Unit
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
    ) {
        NavigationBarItem(
            selected = currentScreen == "home",
            onClick = onHomeClick,
            icon = {
                Icon(
                    Icons.Default.Home,
                    contentDescription = "Home"
                )
            },
            label = { Text("Home", fontWeight = FontWeight.Medium) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                selectedTextColor = MaterialTheme.colorScheme.primary,
                indicatorColor = MaterialTheme.colorScheme.primaryContainer
            )
        )
        NavigationBarItem(
            selected = currentScreen == "settings",
            onClick = onSettingsClick,
            icon = {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Settings"
                )
            },
            label = { Text("Settings", fontWeight = FontWeight.Medium) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                selectedTextColor = MaterialTheme.colorScheme.primary,
                indicatorColor = MaterialTheme.colorScheme.primaryContainer
            )
        )
        NavigationBarItem(
            selected = currentScreen == "about",
            onClick = onAboutClick,
            icon = {
                Icon(
                    Icons.Default.Info,
                    contentDescription = "About"
                )
            },
            label = { Text("About", fontWeight = FontWeight.Medium) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                selectedTextColor = MaterialTheme.colorScheme.primary,
                indicatorColor = MaterialTheme.colorScheme.primaryContainer
            )
        )
    }
}

