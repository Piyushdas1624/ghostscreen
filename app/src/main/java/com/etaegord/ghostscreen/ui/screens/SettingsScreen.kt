package com.etaegord.ghostscreen.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.etaegord.ghostscreen.data.PreferencesManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    preferencesManager: PreferencesManager,
    onNavigateToHome: () -> Unit,
    onNavigateToAbout: () -> Unit
) {
    val scrollState = rememberScrollState()

    val context = androidx.compose.ui.platform.LocalContext.current
    val isHardwareBlurSupported = remember {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            try {
                val wm = context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
                wm.isCrossWindowBlurEnabled
            } catch (_: Exception) { false }
        } else false
    }

    var overlayColor        by remember { mutableStateOf(preferencesManager.color) }
    var launchOnBoot        by remember { mutableStateOf(preferencesManager.isLaunchOnBootEnabled) }
    var vibrateOnActivation by remember { mutableStateOf(preferencesManager.isVibrateOnActivationEnabled) }
    var dynamicColorEnabled by remember { mutableStateOf(preferencesManager.isDynamicColorEnabled) }
    // Blur mode: mutually exclusive with solid color
    var blurEnabled         by remember { mutableStateOf(preferencesManager.isBlurEnabled) }
    var showColorPicker     by remember { mutableStateOf(false) }

    // Preset swatches: name, hex, Color
    data class Swatch(val name: String, val hex: String, val color: Color)
    val presets = listOf(
        Swatch("Black",    "#000000", Color.Black),
        Swatch("Navy",     "#0a192f", Color(0xFF0a192f)),
        Swatch("Maroon",   "#4a0e1c", Color(0xFF4a0e1c)),
        Swatch("Forest",   "#1b3b22", Color(0xFF1b3b22)),
        Swatch("Charcoal", "#212121", Color(0xFF212121)),
        Swatch("Indigo",   "#1a0533", Color(0xFF1a0533)),
        Swatch("Rust",     "#3b1a08", Color(0xFF3b1a08)),
        Swatch("Slate",    "#1c2433", Color(0xFF1c2433)),
    )

    if (showColorPicker) {
        ColorPickerDialog(
            currentColor = overlayColor,
            presets      = presets.map { Pair(it.name, it.hex) },
            onDismiss    = { showColorPicker = false },
            onConfirm    = { hex ->
                overlayColor = hex
                preferencesManager.color = hex
                blurEnabled = false
                preferencesManager.isBlurEnabled = false
                showColorPicker = false
            }
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.VisibilityOff, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                        Text("GhostScreen", fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            BottomNavigationBar(currentScreen = "settings", onHomeClick = onNavigateToHome, onSettingsClick = {}, onAboutClick = onNavigateToAbout)
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {

            // ── Mask Appearance ────────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(24.dp),
                colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.Palette, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                        Text("Mask Appearance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    }

                    // ── Segmented mode selector ────────────────────────────
                    Text("Mask Mode", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    ) {
                        val solidBg by animateColorAsState(
                            targetValue = if (!blurEnabled) MaterialTheme.colorScheme.primary else Color.Transparent,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            ),
                            label = "solidBg"
                        )
                        val solidFg by animateColorAsState(
                            targetValue = if (!blurEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            ),
                            label = "solidFg"
                        )
                        val blurBg by animateColorAsState(
                            targetValue = if (blurEnabled) MaterialTheme.colorScheme.primary else Color.Transparent,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            ),
                            label = "blurBg"
                        )
                        val blurFg by animateColorAsState(
                            targetValue = if (blurEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            ),
                            label = "blurFg"
                        )

                        // Solid Color chip
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .background(solidBg)
                                .clickable {
                                    blurEnabled = false
                                    preferencesManager.isBlurEnabled = false
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Circle, null,
                                    tint = solidFg,
                                    modifier = Modifier.size(14.dp))
                                Text("Solid Color",
                                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                                    color = solidFg)
                            }
                        }
                        // Blur chip
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .background(blurBg)
                                .clickable {
                                    blurEnabled = true
                                    preferencesManager.isBlurEnabled = true
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.BlurOn, null,
                                    tint = blurFg,
                                    modifier = Modifier.size(14.dp))
                                Text("Blur / Frost",
                                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                                    color = blurFg)
                            }
                        }
                    }

                    // Blur description
                    AnimatedVisibility(visible = blurEnabled, enter = expandVertically(), exit = shrinkVertically()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Text(
                                    "Blur/Frost mode obscures content instead of using a solid color. " +
                                    "Control blur intensity using the eye controller on the overlay strips.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            if (!isHardwareBlurSupported) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f))
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                    Text(
                                        "Hardware cross-window blur is disabled on this device by the OS/GPU vendor (MediaTek / power efficiency). GhostScreen automatically applies an enhanced frosted privacy tint with noise texture.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }

                    // ── Color section (hidden when blur is active) ─────────
                    AnimatedVisibility(visible = !blurEnabled, enter = expandVertically(), exit = shrinkVertically()) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Overlay Color", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                // Current hex chip with edit icon
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                        .clickable { showColorPicker = true }
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier.size(11.dp).clip(CircleShape)
                                                .background(try { Color(android.graphics.Color.parseColor(overlayColor)) } catch (_: Exception) { Color.Black })
                                        )
                                        Text(overlayColor.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
                                        Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(11.dp))
                                    }
                                }
                            }

                            // Swipeable colour row
                            Text("Slide to browse • tap to select", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                itemsIndexed(presets, key = { _, swatch -> swatch.hex }) { _, swatch ->
                                    val isSelected = overlayColor.equals(swatch.hex, true)
                                    val swatchScale by animateFloatAsState(
                                        targetValue = if (isSelected) 1.15f else 1.0f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMediumLow
                                        ),
                                        label = "swatchScale_${swatch.hex}"
                                    )
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(5.dp),
                                        modifier = Modifier.clickable {
                                            overlayColor = swatch.hex
                                            preferencesManager.color = swatch.hex
                                        }
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(52.dp)
                                                .graphicsLayer {
                                                    scaleX = swatchScale
                                                    scaleY = swatchScale
                                                }
                                                .clip(CircleShape)
                                                .background(swatch.color)
                                                .border(
                                                    width = if (isSelected) 3.dp else 1.dp,
                                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                                                    shape = CircleShape
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (isSelected) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(22.dp))
                                        }
                                        Text(
                                            swatch.name,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                                // Custom swatch at the end
                                item {
                                    val customActive = !presets.any { it.hex.equals(overlayColor, true) }
                                    val customScale by animateFloatAsState(
                                        targetValue = if (customActive) 1.15f else 1.0f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMediumLow
                                        ),
                                        label = "customScale"
                                    )
                                    val customColor = if (customActive) try { Color(android.graphics.Color.parseColor(overlayColor)) } catch (_: Exception) { MaterialTheme.colorScheme.secondaryContainer } else MaterialTheme.colorScheme.secondaryContainer
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(5.dp),
                                        modifier = Modifier.clickable { showColorPicker = true }
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(52.dp)
                                                .graphicsLayer {
                                                    scaleX = customScale
                                                    scaleY = customScale
                                                }
                                                .clip(CircleShape)
                                                .background(customColor)
                                                .border(
                                                    width = if (customActive) 3.dp else 1.dp,
                                                    color = if (customActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                                                    shape = CircleShape
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.Add, null, tint = if (customActive) Color.White else MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
                                        }
                                        Text("Custom ✏️",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (customActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontWeight = if (customActive) FontWeight.Bold else FontWeight.Normal)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Behavior Card ──────────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(24.dp),
                colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.SettingsSuggest, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                        Text("Behavior", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        BehaviorRow("Dynamic Color (Material You)", "Match app theme with system wallpaper colors", dynamicColorEnabled) {
                            dynamicColorEnabled = it
                            preferencesManager.isDynamicColorEnabled = it
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    }
                    BehaviorRow("Launch on boot", "Start background service automatically", launchOnBoot) { launchOnBoot = it; preferencesManager.isLaunchOnBootEnabled = it }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    BehaviorRow("Haptic feedback", "Vibrate on mask activation & controls", vibrateOnActivation) { vibrateOnActivation = it; preferencesManager.isVibrateOnActivationEnabled = it }
                }
            }

            // ── Quick Settings Tile Card ───────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(24.dp),
                colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Widgets, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                        Text("Quick Settings Tile", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    }
                    Text("Add the tile to your quick settings panel for instant toggle access.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), RoundedCornerShape(20.dp)).padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(modifier = Modifier.width(84.dp).height(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.VisibilityOff, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(24.dp))
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("GhostScreen", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = {
                                try {
                                    val statusBarManager = context.getSystemService(android.app.StatusBarManager::class.java)
                                    statusBarManager?.requestAddTileService(
                                        android.content.ComponentName(context, com.etaegord.ghostscreen.service.GhostScreenTileService::class.java),
                                        "GhostScreen",
                                        android.graphics.drawable.Icon.createWithResource(context, com.etaegord.ghostscreen.R.drawable.ic_tile),
                                        context.mainExecutor
                                    ) { _ -> }
                                } catch (e: Exception) {
                                    android.util.Log.e("SettingsScreen", "requestAddTileService", e)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Add Tile to Quick Settings", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BehaviorRow(label: String, sublabel: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(sublabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange,
            thumbContent = { if (checked) Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp)) })
    }
}

// ── Color Picker Dialog ────────────────────────────────────────────────────────
@Composable
private fun ColorPickerDialog(
    currentColor: String,
    presets: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var rVal by remember { mutableStateOf(0f) }
    var gVal by remember { mutableStateOf(0f) }
    var bVal by remember { mutableStateOf(0f) }
    var hexInput    by remember { mutableStateOf(currentColor) }
    var hexError    by remember { mutableStateOf(false) }
    var selPreset   by remember { mutableStateOf(currentColor) }

    LaunchedEffect(Unit) {
        try {
            val c = android.graphics.Color.parseColor(currentColor)
            rVal = android.graphics.Color.red(c).toFloat()
            gVal = android.graphics.Color.green(c).toFloat()
            bVal = android.graphics.Color.blue(c).toFloat()
        } catch (_: Exception) {}
    }

    fun fromRgb() {
        hexInput = String.format("#%02X%02X%02X", rVal.toInt(), gVal.toInt(), bVal.toInt())
        selPreset = hexInput; hexError = false
    }

    val previewColor = try { Color(android.graphics.Color.parseColor(hexInput)) } catch (_: Exception) { Color.Black }

    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Choose Mask Color", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

                // Preview swatch
                Box(modifier = Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp)).background(previewColor).border(2.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(14.dp)))

                // Presets
                Text("Presets", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(presets, key = { _, pair -> pair.second }) { _, (name, hex) ->
                        val cv = try { Color(android.graphics.Color.parseColor(hex)) } catch (_: Exception) { Color.Black }
                        val sel = selPreset.equals(hex, true)
                        Box(
                            modifier = Modifier.size(if (sel) 40.dp else 34.dp).clip(CircleShape).background(cv)
                                .border(if (sel) 3.dp else 0.dp, if (sel) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                                .clickable {
                                    selPreset = hex; hexInput = hex; hexError = false
                                    try { val c = android.graphics.Color.parseColor(hex); rVal = android.graphics.Color.red(c).toFloat(); gVal = android.graphics.Color.green(c).toFloat(); bVal = android.graphics.Color.blue(c).toFloat() } catch (_: Exception) {}
                                },
                            contentAlignment = Alignment.Center
                        ) { if (sel) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Text("Custom RGB", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

                @Composable
                fun RgbRow(label: String, value: Float, trackColor: Color, onVal: (Float) -> Unit) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(14.dp))
                        Slider(value = value, onValueChange = onVal, valueRange = 0f..255f,
                            colors = SliderDefaults.colors(activeTrackColor = trackColor, thumbColor = trackColor), modifier = Modifier.weight(1f))
                        Text(value.toInt().toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(28.dp))
                    }
                }
                RgbRow("R", rVal, Color(0.9f, 0.2f, 0.2f)) { rVal = it; fromRgb() }
                RgbRow("G", gVal, Color(0.2f, 0.75f, 0.2f)) { gVal = it; fromRgb() }
                RgbRow("B", bVal, Color(0.2f, 0.4f, 0.95f)) { bVal = it; fromRgb() }

                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { v ->
                        hexInput = v
                        try { val c = android.graphics.Color.parseColor(v); rVal = android.graphics.Color.red(c).toFloat(); gVal = android.graphics.Color.green(c).toFloat(); bVal = android.graphics.Color.blue(c).toFloat(); hexError = false; selPreset = v } catch (_: Exception) { hexError = v.length >= 7 }
                    },
                    label = { Text("Hex (#RRGGBB)") }, isError = hexError,
                    supportingText = if (hexError) {{ Text("Invalid hex color") }} else null,
                    singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(onClick = { if (!hexError) onConfirm(hexInput) }, enabled = !hexError, shape = RoundedCornerShape(12.dp)) { Text("Apply") }
                }
            }
        }
    }
}
