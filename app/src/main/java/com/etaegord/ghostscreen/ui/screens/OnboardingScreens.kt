package com.etaegord.ghostscreen.ui.screens

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.etaegord.ghostscreen.service.GhostScreenAccessibilityService
import com.etaegord.ghostscreen.util.isGhostScreenAccessibilityEnabled
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun OnboardingFlow(onFinish: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { 4 })
    val coroutineScope = rememberCoroutineScope()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f)
            ) { page ->
                when (page) {
                    0 -> WelcomeScreenContent(
                        onNext = { coroutineScope.launch { pagerState.animateScrollToPage(1) } }
                    )
                    1 -> PermissionScreenContent(
                        onNext = { coroutineScope.launch { pagerState.animateScrollToPage(2) } }
                    )
                    2 -> ManualModeScreenContent(
                        onNext = { coroutineScope.launch { pagerState.animateScrollToPage(3) } }
                    )
                    3 -> AllSetScreenContent(onFinish = onFinish)
                }
            }
        }
    }
}

@Composable
fun PageIndicators(currentPage: Int) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 24.dp, top = 16.dp)
    ) {
        for (i in 0 until 4) {
            val isSelected = i == currentPage
            Box(
                modifier = Modifier
                    .height(8.dp)
                    .width(if (isSelected) 32.dp else 8.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            )
            if (i < 3) Spacer(modifier = Modifier.width(8.dp))
        }
    }
}

@Composable
fun WelcomeScreenContent(onNext: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        PageIndicators(currentPage = 0)
        
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(24.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(MaterialTheme.colorScheme.primaryContainer, Color.Transparent)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(64.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "GhostScreen Overlay",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 18.sp
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(vertical = 32.dp, horizontal = 16.dp)
        ) {
            Text(
                text = "Your screen, your business.",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Black
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Prevent shoulder surfing in public spaces with an adjustable privacy mask. Swipe left and right to navigate.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text("NEXT", fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun PermissionScreenContent(onNext: () -> Unit) {
    val context = LocalContext.current
    var isOverlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var isAccessibilityGranted by remember { mutableStateOf(isGhostScreenAccessibilityEnabled(context)) }
    var showHelpDialog by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isOverlayGranted = Settings.canDrawOverlays(context)
                isAccessibilityGranted = isGhostScreenAccessibilityEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Bug 9: Track whether permissions were NOT granted on initial composition.
    // Only auto-advance if permissions transitioned from missing→granted during this session.
    val hadMissingOnEntry = remember { !Settings.canDrawOverlays(context) || !isGhostScreenAccessibilityEnabled(context) }

    // Auto-skip after 1.5s ONLY if permissions were granted during this session
    LaunchedEffect(isOverlayGranted, isAccessibilityGranted) {
        if (hadMissingOnEntry && isOverlayGranted && isAccessibilityGranted) {
            delay(1500)
            onNext()
        }
    }

    var mockupAllowed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(1500); mockupAllowed = true }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        PageIndicators(currentPage = 1)

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), RoundedCornerShape(32.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (isOverlayGranted && isAccessibilityGranted) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Success",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(72.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Permissions Granted!", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    Text("Display over other apps", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Layers, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(24.dp))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("GhostScreen", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                                Text(if (mockupAllowed) "Allowed" else "Not allowed", fontSize = 12.sp, color = if (mockupAllowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        
                        val switchBg by animateColorAsState(if (mockupAllowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, label = "switchBg")
                        val thumbOffset by animateDpAsState(if (mockupAllowed) 20.dp else 0.dp, label = "thumbOffset")
                        Box(
                            modifier = Modifier.width(48.dp).height(28.dp).clip(CircleShape).background(switchBg).padding(4.dp).clickable { mockupAllowed = !mockupAllowed },
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Box(modifier = Modifier.padding(start = thumbOffset).size(20.dp).clip(CircleShape).background(if (mockupAllowed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.outline))
                        }
                    }
                }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 16.dp)) {
            Text("Permission Guide", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onBackground)
            Spacer(modifier = Modifier.height(8.dp))
            Text("GhostScreen requires system permissions to draw the privacy mask and detect sensitive app switching.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }

        Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
            Button(
                onClick = {
                    if (!isOverlayGranted) {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                    } else if (!isAccessibilityGranted) {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    } else {
                        onNext()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                val label = when {
                    !isOverlayGranted -> "Grant Overlay Permission"
                    !isAccessibilityGranted -> "Enable Accessibility Service"
                    else -> "Continue"
                }
                Text(label, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
            }

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(onClick = { showHelpDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Why is this needed?", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
    }

    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = { Text("Permissions Required") },
            text = { Text("1. Display over other apps: Allows GhostScreen to draw the adjustable privacy overlay window on top of other applications.\n\n2. Accessibility Service: Allows GhostScreen to detect which application is currently in the foreground, enabling the Smart Masking overlay automatically when you enter WhatsApp or banking apps.") },
            confirmButton = { TextButton(onClick = { showHelpDialog = false }) { Text("Got it") } }
        )
    }
}

@Composable
fun ManualModeScreenContent(onNext: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        PageIndicators(currentPage = 2)
        
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(24.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(modifier = Modifier.size(80.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Settings, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(40.dp))
                    }
                    Box(modifier = Modifier.size(80.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.tertiaryContainer), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.TouchApp, null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(40.dp))
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Manual Override",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 18.sp
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(vertical = 32.dp, horizontal = 16.dp)
        ) {
            Text(
                text = "Mask anywhere, anytime.",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Black
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Use the Quick Settings Tile or add the GhostScreen Shortcut to your Smart Sidebar. Manual mode ignores Smart Mask rules and stays on until you close it.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text("NEXT", fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun AllSetScreenContent(onFinish: () -> Unit) {
    val animatedScale by com.etaegord.ghostscreen.ui.utils.rememberManualLoopAnim(min = 1f, max = 1.15f, durationMs = 1500)

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        PageIndicators(currentPage = 3)

        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier.size(240.dp).graphicsLayer { scaleX = animatedScale; scaleY = animatedScale; alpha = 0.6f }
                    .clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f))
            )
            Box(
                modifier = Modifier.size(180.dp).graphicsLayer { scaleX = animatedScale; scaleY = animatedScale; alpha = 0.4f }
                    .clip(CircleShape).background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.2f))
            )
            Box(
                modifier = Modifier.size(120.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(4.dp, MaterialTheme.colorScheme.background, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.CheckCircle, "Success", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp))
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Text("You're all set!", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(12.dp))
            Text("Tap the activate button on the home screen to start your first mask.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }

        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text("Pro Tip: Smart Sidebar", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Text("Hold the app icon to add the Manual Shortcut to your Sidebar.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Button(
            onClick = onFinish,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text("FINISH", fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

