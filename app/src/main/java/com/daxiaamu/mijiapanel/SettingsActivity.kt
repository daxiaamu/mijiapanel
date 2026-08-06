package com.daxiaamu.mijiapanel

import android.Manifest
import android.app.DownloadManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.daxiaamu.mijiapanel.update.AppUpdater
import com.daxiaamu.mijiapanel.update.UpdateInstallActivity
import com.daxiaamu.mijiapanel.update.UpdateInstaller
import kotlin.concurrent.thread
import kotlin.math.roundToInt

class SettingsActivity : ComponentActivity() {
    private companion object {
        const val DONATION_URL = "https://ifdian.net/a/daxiaamu"
        const val BLOG_URL = "https://www.daxiaamu.com"
        const val GITHUB_PROFILE_URL = "https://github.com/daxiaamu"
        const val REPOSITORY_URL = "https://github.com/daxiaamu/mijiapanel"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val updater = AppUpdater()
    private var preferences: SharedPreferences? = null
    private var brightnessLocked by mutableStateOf(false)
    private var brightnessPercent by mutableIntStateOf(
        BrightnessSettings.DEFAULT_BRIGHTNESS_PERCENT,
    )
    private var brightnessDragging by mutableStateOf(false)
    private var burnInProtectionEnabled by mutableStateOf(
        BrightnessSettings.DEFAULT_BURN_IN_PROTECTION,
    )
    private var drawInDisplayCutoutEnabled by mutableStateOf(
        BrightnessSettings.DEFAULT_DRAW_IN_DISPLAY_CUTOUT,
    )
    private var presenceDetectionEnabled by mutableStateOf(
        BrightnessSettings.DEFAULT_PRESENCE_DETECTION,
    )
    private var cameraPermissionGranted by mutableStateOf(false)
    private var systemFrameworkScopeGranted by mutableStateOf(false)
    private var systemBridgeReady by mutableStateOf(false)
    private var systemFrameworkScopeRequestPending by mutableStateOf(false)
    private var absenceBehavior by mutableIntStateOf(
        BrightnessSettings.DEFAULT_ABSENCE_BEHAVIOR,
    )
    private var absenceDelaySeconds by mutableIntStateOf(
        BrightnessSettings.DEFAULT_ABSENCE_DELAY_SECONDS,
    )
    private var remotePreferencesReady by mutableStateOf(false)
    private var launcherIconVisible by mutableStateOf(true)
    private var showOpenSourceLicenses by mutableStateOf(false)
    private var updateStatus by mutableStateOf("")
    private var updateButtonEnabled by mutableStateOf(true)
    private var availableUpdate by mutableStateOf<AppUpdater.UpdateInfo?>(null)
    private var updateConfirmation by mutableStateOf<AppUpdater.UpdateInfo?>(null)
    private var activeDownloadId = -1L
    private var readyDownloadId = -1L
    private var checkingUpdate = false
    private val localPreferences by lazy {
        getSharedPreferences(BrightnessSettings.PREFERENCES, MODE_PRIVATE)
    }
    private val localPreferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == BrightnessSettings.SYSTEM_BRIDGE_BOOT_COUNT) {
                handler.post(::refreshSystemBridgeReadyState)
            }
        }
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraPermissionGranted = granted
        if (granted) {
            persistPresenceDetection(true)
        } else {
            Toast.makeText(
                this,
                R.string.presence_camera_permission_required,
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    private val serviceListener = MijiaPanelApplication.ServiceListener {
        loadRemotePreferences()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!moduleApplication.isIntegrityTrusted || !AppIntegrity.verify(this)) {
            IntegrityFailureActivity.open(this)
            finish()
            return
        }
        enableEdgeToEdge()
        title = getString(R.string.settings_title)
        launcherIconVisible = isLauncherIconVisible()
        updateStatus = getString(R.string.update_auto_check_summary)
        setContent {
            MijiaPanelTheme {
                SettingsScreen()
            }
        }
        localPreferences.registerOnSharedPreferenceChangeListener(localPreferenceListener)
        moduleApplication.addServiceListener(serviceListener)
        handler.postDelayed({ checkForUpdates(manual = false) }, 1_500L)
    }

    override fun onResume() {
        super.onResume()
        if (!moduleApplication.isIntegrityTrusted || !AppIntegrity.verify(this)) {
            IntegrityFailureActivity.open(this)
            finish()
            return
        }
        refreshCameraPermissionState()
        refreshSystemFrameworkScopeState()
        refreshReadyDownload()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        localPreferences.unregisterOnSharedPreferenceChangeListener(localPreferenceListener)
        moduleApplication.removeServiceListener(serviceListener)
        super.onDestroy()
    }

    @Composable
    private fun SettingsScreen() {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    vertical = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item {
                    Text(
                        text = getString(R.string.settings_title),
                        modifier = Modifier.padding(horizontal = 20.dp),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = getString(R.string.settings_description),
                        modifier = Modifier.padding(
                            start = 20.dp,
                            top = 6.dp,
                            end = 20.dp,
                            bottom = 24.dp,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                item { SectionTitle(getString(R.string.display_settings)) }
                item {
                    SwitchSetting(
                        icon = Icons.Default.Brightness6,
                        title = getString(R.string.lock_brightness),
                        summary = getString(R.string.lock_brightness_summary),
                        checked = brightnessLocked,
                        enabled = remotePreferencesReady,
                        onCheckedChange = { updateBrightnessLock(it) },
                    )
                }
                item {
                    AnimatedVisibility(
                        visible = brightnessLocked,
                        enter = fadeIn(tween(180)) +
                            expandVertically(tween(220)) +
                            slideInVertically(tween(220)) { -it / 4 },
                        exit = fadeOut(tween(140)) +
                            shrinkVertically(tween(180)) +
                            slideOutVertically(tween(180)) { -it / 4 },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min)
                                .padding(
                                    start = 64.dp,
                                    top = 8.dp,
                                    end = 16.dp,
                                    bottom = 8.dp,
                                ),
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .fillMaxHeight()
                                    .background(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                                    ),
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 12.dp),
                            ) {
                                val controlColor = if (remotePreferencesReady) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = getString(R.string.panel_brightness),
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = controlColor,
                                    )
                                    Text(
                                        text = getString(
                                            R.string.brightness_percent,
                                            brightnessPercent,
                                        ),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = controlColor,
                                    )
                                }
                                BoxWithConstraints(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp),
                                ) {
                                    if (brightnessDragging) {
                                        val indicatorWidth = 184.dp
                                        val positionFraction =
                                            ((brightnessPercent - 1) / 99f).coerceIn(0f, 1f)
                                        val thumbCenter = 16.dp +
                                            (maxWidth - 32.dp) * positionFraction
                                        val indicatorOffset =
                                            (thumbCenter - indicatorWidth / 2f)
                                                .coerceIn(0.dp, maxWidth - indicatorWidth)
                                        val density = LocalDensity.current
                                        Popup(
                                            alignment = Alignment.TopStart,
                                            offset = with(density) {
                                                IntOffset(
                                                    indicatorOffset.roundToPx(),
                                                    (-104).dp.roundToPx(),
                                                )
                                            },
                                            properties = PopupProperties(
                                                focusable = false,
                                                clippingEnabled = false,
                                            ),
                                        ) {
                                            Text(
                                                text = getString(
                                                    R.string.brightness_percent,
                                                    brightnessPercent,
                                                ),
                                                modifier = Modifier.width(indicatorWidth),
                                                style = MaterialTheme.typography.displayLarge.copy(
                                                    shadow = Shadow(
                                                        color = MaterialTheme.colorScheme.surface,
                                                        offset = Offset.Zero,
                                                        blurRadius = 14f,
                                                    ),
                                                ),
                                                fontWeight = FontWeight.SemiBold,
                                                textAlign = TextAlign.Center,
                                                maxLines = 1,
                                                softWrap = false,
                                                color = controlColor,
                                            )
                                        }
                                    }
                                    Slider(
                                        value = brightnessPercent.toFloat(),
                                        onValueChange = {
                                            brightnessDragging = true
                                            setBrightness(it.roundToInt(), commit = false)
                                        },
                                        onValueChangeFinished = {
                                            setBrightness(brightnessPercent, commit = true)
                                            brightnessDragging = false
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(52.dp)
                                            .align(Alignment.BottomCenter),
                                        enabled = remotePreferencesReady,
                                        valueRange = 1f..100f,
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    SwitchSetting(
                        icon = Icons.Default.Shield,
                        title = getString(R.string.burn_in_protection),
                        summary = getString(R.string.burn_in_protection_summary),
                        checked = burnInProtectionEnabled,
                        enabled = remotePreferencesReady,
                        onCheckedChange = { updateBurnInProtection(it) },
                    )
                }
                item {
                    SwitchSetting(
                        icon = Icons.Default.Screenshot,
                        title = getString(R.string.draw_in_display_cutout),
                        summary = getString(R.string.draw_in_display_cutout_summary),
                        checked = drawInDisplayCutoutEnabled,
                        enabled = remotePreferencesReady,
                        onCheckedChange = { updateDrawInDisplayCutout(it) },
                    )
                }
                item {
                    val presenceWarning = when {
                        !presenceDetectionEnabled -> null
                        !cameraPermissionGranted ->
                            getString(R.string.presence_camera_permission_action)
                        !systemFrameworkScopeGranted ->
                            getString(
                                if (systemFrameworkScopeRequestPending) {
                                    R.string.presence_framework_scope_requesting
                                } else {
                                    R.string.presence_framework_scope_action
                                },
                            )
                        !systemBridgeReady ->
                            getString(R.string.presence_framework_reboot_required)
                        else -> null
                    }
                    SwitchSetting(
                        icon = Icons.Default.PersonSearch,
                        title = getString(R.string.presence_detection),
                        summary = getString(R.string.presence_detection_summary),
                        checked = presenceDetectionEnabled,
                        enabled = remotePreferencesReady,
                        warning = presenceWarning,
                        onWarningClick = if (
                            presenceDetectionEnabled && !cameraPermissionGranted
                        ) {
                            { openAppPermissionSettings() }
                        } else if (
                            presenceDetectionEnabled && !systemFrameworkScopeGranted
                        ) {
                            { requestSystemFrameworkScope() }
                        } else {
                            null
                        },
                        onCheckedChange = { updatePresenceDetection(it) },
                    )
                }
                item {
                    AnimatedVisibility(
                        visible = presenceDetectionEnabled,
                        enter = fadeIn(tween(180)) +
                            expandVertically(tween(220)) +
                            slideInVertically(tween(220)) { -it / 4 },
                        exit = fadeOut(tween(140)) +
                            shrinkVertically(tween(180)) +
                            slideOutVertically(tween(180)) { -it / 4 },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min)
                                .padding(
                                    start = 64.dp,
                                    top = 8.dp,
                                    end = 16.dp,
                                    bottom = 8.dp,
                                ),
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .fillMaxHeight()
                                    .background(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                                    ),
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 12.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = getString(R.string.absence_delay),
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = formatAbsenceDelay(absenceDelaySeconds),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                                Text(
                                    text = getString(
                                        R.string.absence_delay_summary,
                                        formatAbsenceDelay(absenceDelaySeconds),
                                    ),
                                    modifier = Modifier.padding(top = 3.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Slider(
                                    value = absenceDelaySeconds.toFloat(),
                                    onValueChange = {
                                        setAbsenceDelay(it.roundToInt(), commit = false)
                                    },
                                    onValueChangeFinished = {
                                        setAbsenceDelay(absenceDelaySeconds, commit = true)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp),
                                    enabled = remotePreferencesReady,
                                    valueRange =
                                        BrightnessSettings.MIN_ABSENCE_DELAY_SECONDS.toFloat()..
                                            BrightnessSettings.MAX_ABSENCE_DELAY_SECONDS.toFloat(),
                                )
                                Text(
                                    text = getString(R.string.absence_behavior),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = getString(R.string.absence_behavior_summary),
                                    modifier = Modifier.padding(top = 3.dp, bottom = 10.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                SingleChoiceSegmentedButtonRow(
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    val options = listOf(
                                        BrightnessSettings.ABSENCE_SCREEN_OFF to
                                            getString(R.string.absence_screen_off),
                                        BrightnessSettings.ABSENCE_MINIMUM_BRIGHTNESS to
                                            getString(R.string.absence_minimum_brightness),
                                    )
                                    options.forEachIndexed { index, option ->
                                        SegmentedButton(
                                            selected = absenceBehavior == option.first,
                                            onClick = { updateAbsenceBehavior(option.first) },
                                            shape = SegmentedButtonDefaults.itemShape(
                                                index = index,
                                                count = options.size,
                                            ),
                                            enabled = remotePreferencesReady,
                                        ) {
                                            Text(option.second)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                item { SectionDivider() }
                item { SectionTitle(getString(R.string.app_settings)) }
                item {
                    SwitchSetting(
                        icon = Icons.Default.Apps,
                        title = getString(R.string.show_launcher_icon),
                        summary = getString(R.string.show_launcher_icon_summary),
                        checked = launcherIconVisible,
                        onCheckedChange = { updateLauncherIconVisibility(it) },
                    )
                }

                item { SectionDivider() }
                item { SectionTitle(getString(R.string.app_update)) }
                item {
                    val performUpdateAction = {
                        if (availableUpdate != null || readyDownloadId >= 0L) {
                            revalidateUpdateAction()
                        } else {
                            checkForUpdates(manual = true)
                        }
                    }
                    val actionText = if (readyDownloadId >= 0L) {
                        getString(R.string.update_install)
                    } else if (availableUpdate != null) {
                        getString(R.string.update_download)
                    } else {
                        getString(R.string.update_check_action)
                    }
                    ListItem(
                        headlineContent = {
                            Text(
                                getString(
                                    R.string.current_version,
                                    BuildConfig.VERSION_NAME,
                                ),
                            )
                        },
                        supportingContent = { Text(updateStatus) },
                        leadingContent = {
                            Icon(Icons.Default.SystemUpdate, contentDescription = null)
                        },
                        trailingContent = {
                            if (checkingUpdate) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Row(
                                    modifier = Modifier
                                        .widthIn(min = 64.dp)
                                        .height(48.dp)
                                        .clickable(enabled = updateButtonEnabled) {
                                            performUpdateAction()
                                        },
                                    horizontalArrangement = Arrangement.spacedBy(
                                        6.dp,
                                        Alignment.End,
                                    ),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = actionText,
                                        color = if (updateButtonEnabled) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                        },
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                    if (availableUpdate != null || readyDownloadId >= 0L) {
                                        Badge(modifier = Modifier.size(7.dp))
                                    }
                                }
                            }
                        },
                        modifier = Modifier.clickable(enabled = updateButtonEnabled) {
                            performUpdateAction()
                        },
                    )
                }

                item { SectionDivider() }
                item { SectionTitle(getString(R.string.about)) }
                item {
                    AboutSetting(
                        icon = Icons.Default.Favorite,
                        title = getString(R.string.developer_donation),
                        subtitle = getString(R.string.developer_donation_summary),
                        external = true,
                        onClick = { openUrl(DONATION_URL) },
                    )
                }
                item {
                    AboutSetting(
                        icon = Icons.Default.Language,
                        title = getString(R.string.developer_blog),
                        subtitle = getString(R.string.developer_blog_summary),
                        external = true,
                        onClick = { openUrl(BLOG_URL) },
                    )
                }
                item {
                    AboutSetting(
                        icon = Icons.Default.Person,
                        title = getString(R.string.developer_github),
                        subtitle = getString(R.string.developer_github_summary),
                        external = true,
                        onClick = { openUrl(GITHUB_PROFILE_URL) },
                    )
                }
                item {
                    AboutSetting(
                        icon = Icons.Default.Code,
                        title = getString(R.string.view_source_code),
                        subtitle = getString(R.string.view_source_code_summary),
                        external = true,
                        onClick = { openUrl(REPOSITORY_URL) },
                    )
                }
                item {
                    AboutSetting(
                        icon = Icons.Default.Description,
                        title = getString(R.string.open_source_licenses),
                        subtitle = getString(R.string.open_source_licenses_summary),
                        onClick = { showOpenSourceLicenses = true },
                    )
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }

        if (showOpenSourceLicenses) {
            OpenSourceLicensesSheet(
                onDismiss = { showOpenSourceLicenses = false },
                onOpenUrl = { openUrl(it) },
            )
        }

        updateConfirmation?.let { info ->
            UpdateConfirmationDialog(
                info = info,
                onDismiss = {
                    updateConfirmation = null
                    updateButtonEnabled = true
                },
                onDownload = {
                    updateConfirmation = null
                    startDownload(info)
                },
            )
        }

    }

    @Composable
    private fun UpdateConfirmationDialog(
        info: AppUpdater.UpdateInfo,
        onDismiss: () -> Unit,
        onDownload: () -> Unit,
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    getString(
                        R.string.update_dialog_title,
                        displayVersion(info.versionName),
                    ),
                )
            },
            text = {
                Column {
                    Text(
                        text = getString(R.string.update_release_notes),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    MarkdownReleaseNotes(
                        markdown = info.notes.ifBlank {
                            getString(R.string.update_release_notes_empty)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(getString(android.R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = onDownload) {
                    Text(getString(R.string.update_download))
                }
            },
        )
    }

    @Composable
    private fun SectionTitle(title: String) {
        Text(
            text = title,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    @Composable
    private fun SectionDivider() {
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
    }

    @Composable
    private fun AboutSetting(
        icon: ImageVector,
        title: String,
        subtitle: String,
        external: Boolean = false,
        onClick: () -> Unit,
    ) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(subtitle) },
            leadingContent = { Icon(icon, contentDescription = null) },
            trailingContent = {
                if (external) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                    )
                } else {
                    Text(
                        text = "›",
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            },
            modifier = Modifier.clickable(onClick = onClick),
        )
    }

    @Composable
    private fun SwitchSetting(
        icon: ImageVector,
        title: String,
        summary: String,
        checked: Boolean,
        enabled: Boolean = true,
        warning: String? = null,
        onWarningClick: (() -> Unit)? = null,
        onCheckedChange: (Boolean) -> Unit,
    ) {
        val contentColor = if (enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        }
        ListItem(
            headlineContent = {
                Text(
                    text = title,
                    color = contentColor,
                )
            },
            supportingContent = {
                Column {
                    Text(
                        text = summary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = if (enabled) 1f else 0.38f,
                        ),
                    )
                    if (warning != null) {
                        Text(
                            text = warning,
                            modifier = Modifier
                                .padding(top = 5.dp)
                                .clickable(enabled = onWarningClick != null) {
                                    onWarningClick?.invoke()
                                },
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            leadingContent = {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                )
            },
            trailingContent = {
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    enabled = enabled,
                )
            },
            modifier = Modifier.clickable(enabled = enabled) {
                onCheckedChange(!checked)
            },
        )
    }

    private fun updateBrightnessLock(locked: Boolean) {
        brightnessLocked = locked
        if (!locked) brightnessDragging = false
        preferences?.edit()
            ?.putBoolean(BrightnessSettings.LOCK_BRIGHTNESS, locked)
            ?.commit()
    }

    private fun setBrightness(value: Int, commit: Boolean) {
        val safeValue = BrightnessSettings.clampPercent(value)
        brightnessPercent = safeValue
        val editor = preferences?.edit()
            ?.putInt(BrightnessSettings.BRIGHTNESS_PERCENT, safeValue)
            ?: return
        if (commit) editor.commit() else editor.apply()
    }

    private fun updateBurnInProtection(enabled: Boolean) {
        burnInProtectionEnabled = enabled
        preferences?.edit()
            ?.putBoolean(BrightnessSettings.BURN_IN_PROTECTION, enabled)
            ?.commit()
    }

    private fun updateDrawInDisplayCutout(enabled: Boolean) {
        drawInDisplayCutoutEnabled = enabled
        preferences?.edit()
            ?.putBoolean(BrightnessSettings.DRAW_IN_DISPLAY_CUTOUT, enabled)
            ?.commit()
    }

    private fun updatePresenceDetection(enabled: Boolean) {
        if (enabled && !hasCameraPermission()) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        persistPresenceDetection(enabled)
    }

    private fun hasCameraPermission(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun refreshCameraPermissionState() {
        if (!presenceDetectionEnabled) {
            cameraPermissionGranted = false
            stopService(Intent(this, PresenceDetectionService::class.java))
            return
        }
        val granted = hasCameraPermission()
        cameraPermissionGranted = granted
        val serviceIntent = Intent(this, PresenceDetectionService::class.java)
        if (!granted) {
            stopService(serviceIntent)
        } else if (presenceDetectionEnabled && remotePreferencesReady) {
            startForegroundService(serviceIntent)
        }
    }

    private fun openAppPermissionSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }

    private fun persistPresenceDetection(enabled: Boolean) {
        presenceDetectionEnabled = enabled
        preferences?.edit()
            ?.putBoolean(BrightnessSettings.PRESENCE_DETECTION, enabled)
            ?.commit()
        getSharedPreferences(BrightnessSettings.PREFERENCES, MODE_PRIVATE)
            .edit()
            .putBoolean(BrightnessSettings.PRESENCE_DETECTION, enabled)
            .apply()
        val intent = Intent(this, PresenceDetectionService::class.java)
        if (enabled) {
            startForegroundService(intent)
            requestNotificationPermissionForPresence()
            refreshSystemFrameworkScopeState()
            if (!systemFrameworkScopeGranted) {
                requestSystemFrameworkScope()
            }
        } else {
            stopService(intent)
        }
    }

    private fun updateAbsenceBehavior(behavior: Int) {
        if (behavior != BrightnessSettings.ABSENCE_SCREEN_OFF &&
            behavior != BrightnessSettings.ABSENCE_MINIMUM_BRIGHTNESS
        ) {
            return
        }
        absenceBehavior = behavior
        preferences?.edit()
            ?.putInt(BrightnessSettings.ABSENCE_BEHAVIOR, behavior)
            ?.commit()
    }

    private fun setAbsenceDelay(value: Int, commit: Boolean) {
        val safeValue = BrightnessSettings.clampAbsenceDelaySeconds(value)
        absenceDelaySeconds = safeValue
        if (!commit) return
        preferences?.edit()
            ?.putInt(BrightnessSettings.ABSENCE_DELAY_SECONDS, safeValue)
            ?.commit()
    }

    private fun formatAbsenceDelay(seconds: Int): String {
        val safeSeconds = BrightnessSettings.clampAbsenceDelaySeconds(seconds)
        val minutes = safeSeconds / 60
        val remainingSeconds = safeSeconds % 60
        return when {
            minutes == 0 -> getString(R.string.duration_seconds, remainingSeconds)
            remainingSeconds == 0 -> getString(R.string.duration_minutes, minutes)
            else -> getString(
                R.string.duration_minutes_seconds,
                minutes,
                remainingSeconds,
            )
        }
    }

    private fun requestNotificationPermissionForPresence() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun loadRemotePreferences() {
        val remote = moduleApplication.getRemotePreferences(
            BrightnessSettings.PREFERENCES,
        ) ?: return
        migrateLocalPreferences(remote)
        preferences = remote
        brightnessLocked = remote.getBoolean(BrightnessSettings.LOCK_BRIGHTNESS, false)
        brightnessPercent = BrightnessSettings.clampPercent(
            remote.getInt(
                BrightnessSettings.BRIGHTNESS_PERCENT,
                BrightnessSettings.DEFAULT_BRIGHTNESS_PERCENT,
            ),
        )
        burnInProtectionEnabled = remote.getBoolean(
            BrightnessSettings.BURN_IN_PROTECTION,
            BrightnessSettings.DEFAULT_BURN_IN_PROTECTION,
        )
        drawInDisplayCutoutEnabled = remote.getBoolean(
            BrightnessSettings.DRAW_IN_DISPLAY_CUTOUT,
            BrightnessSettings.DEFAULT_DRAW_IN_DISPLAY_CUTOUT,
        )
        presenceDetectionEnabled = remote.getBoolean(
            BrightnessSettings.PRESENCE_DETECTION,
            BrightnessSettings.DEFAULT_PRESENCE_DETECTION,
        )
        absenceBehavior = remote.getInt(
            BrightnessSettings.ABSENCE_BEHAVIOR,
            BrightnessSettings.DEFAULT_ABSENCE_BEHAVIOR,
        ).coerceIn(
            BrightnessSettings.ABSENCE_SCREEN_OFF,
            BrightnessSettings.ABSENCE_MINIMUM_BRIGHTNESS,
        )
        absenceDelaySeconds = BrightnessSettings.clampAbsenceDelaySeconds(
            remote.getInt(
                BrightnessSettings.ABSENCE_DELAY_SECONDS,
                BrightnessSettings.DEFAULT_ABSENCE_DELAY_SECONDS,
            ),
        )
        localPreferences.edit()
            .putBoolean(BrightnessSettings.PRESENCE_DETECTION, presenceDetectionEnabled)
            .apply()
        remotePreferencesReady = true
        refreshSystemFrameworkScopeState()
        if (presenceDetectionEnabled) {
            if (!systemFrameworkScopeGranted) {
                requestSystemFrameworkScope()
            }
            cameraPermissionGranted = hasCameraPermission()
            if (cameraPermissionGranted) {
                startForegroundService(Intent(this, PresenceDetectionService::class.java))
            }
        } else {
            cameraPermissionGranted = false
        }
    }

    private fun refreshSystemFrameworkScopeState() {
        systemFrameworkScopeGranted = runCatching {
            moduleApplication.isScopeEnabled("system")
        }.getOrDefault(false)
        if (systemFrameworkScopeGranted) {
            systemFrameworkScopeRequestPending = false
        }
        refreshSystemBridgeReadyState()
    }

    private fun refreshSystemBridgeReadyState() {
        val currentBootCount = Settings.Global.getInt(
            contentResolver,
            Settings.Global.BOOT_COUNT,
            -1,
        )
        val localBootCount = localPreferences.getInt(
            BrightnessSettings.SYSTEM_BRIDGE_BOOT_COUNT,
            -1,
        )
        val remoteBootCount = preferences?.getInt(
            BrightnessSettings.SYSTEM_BRIDGE_BOOT_COUNT,
            -1,
        ) ?: -1
        systemBridgeReady = currentBootCount >= 0 &&
            (localBootCount == currentBootCount || remoteBootCount == currentBootCount)
    }

    private fun requestSystemFrameworkScope() {
        if (systemFrameworkScopeRequestPending) return
        systemFrameworkScopeRequestPending = true
        val requested = moduleApplication.requestScope(
            "system",
            object : MijiaPanelApplication.ScopeRequestListener {
                override fun onApproved() {
                    handler.post {
                        systemFrameworkScopeRequestPending = false
                        systemFrameworkScopeGranted = true
                        systemBridgeReady = false
                        localPreferences.edit()
                            .putInt(BrightnessSettings.SYSTEM_BRIDGE_BOOT_COUNT, -1)
                            .commit()
                        preferences?.edit()
                            ?.putInt(BrightnessSettings.SYSTEM_BRIDGE_BOOT_COUNT, -1)
                            ?.commit()
                        Toast.makeText(
                            this@SettingsActivity,
                            R.string.presence_framework_reboot_required,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }

                override fun onFailed() {
                    handler.post {
                        systemFrameworkScopeRequestPending = false
                        Toast.makeText(
                            this@SettingsActivity,
                            R.string.presence_framework_scope_failed,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            },
        )
        if (!requested) {
            systemFrameworkScopeRequestPending = false
            Toast.makeText(
                this,
                R.string.presence_framework_scope_failed,
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun migrateLocalPreferences(remote: SharedPreferences) {
        if (remote.contains(BrightnessSettings.LOCK_BRIGHTNESS)) return
        val local = getSharedPreferences(BrightnessSettings.PREFERENCES, MODE_PRIVATE)
        if (!local.contains(BrightnessSettings.LOCK_BRIGHTNESS)) return
        remote.edit()
            .putBoolean(
                BrightnessSettings.LOCK_BRIGHTNESS,
                local.getBoolean(BrightnessSettings.LOCK_BRIGHTNESS, false),
            )
            .putInt(
                BrightnessSettings.BRIGHTNESS_PERCENT,
                BrightnessSettings.clampPercent(
                    local.getInt(
                        BrightnessSettings.BRIGHTNESS_PERCENT,
                        BrightnessSettings.DEFAULT_BRIGHTNESS_PERCENT,
                    ),
                ),
            )
            .commit()
    }

    private fun checkForUpdates(manual: Boolean) {
        if (checkingUpdate) return
        checkingUpdate = true
        if (manual) {
            updateButtonEnabled = false
            updateStatus = getString(R.string.update_checking)
        }
        updater.checkAsync(object : AppUpdater.CheckCallback {
            override fun onSuccess(info: AppUpdater.UpdateInfo) {
                checkingUpdate = false
                updateButtonEnabled = true
                if (updater.isNewer(info)) {
                    applyDetectedUpdate(info)
                } else if (manual) {
                    availableUpdate = null
                    updateStatus = getString(
                        R.string.update_already_latest,
                        BuildConfig.VERSION_NAME,
                    )
                }
            }

            override fun onFailure(error: Throwable) {
                checkingUpdate = false
                updateButtonEnabled = true
                if (manual) updateStatus = getString(R.string.update_check_failed)
            }
        })
    }

    private fun applyDetectedUpdate(info: AppUpdater.UpdateInfo) {
        val readyVersion = AppUpdater.readyVersionCode(this)
        if (readyDownloadId >= 0L &&
            readyVersion == info.versionCode &&
            UpdateInstaller.isSuccessful(this, readyDownloadId)
        ) {
            availableUpdate = null
            updateStatus = getString(R.string.update_download_ready)
            return
        }
        if (readyDownloadId >= 0L) {
            AppUpdater.discardReadyDownload(this)
            readyDownloadId = -1L
        }
        availableUpdate = info
        updateStatus = getString(
            R.string.update_available_status,
            displayVersion(info.versionName),
        )
    }

    private fun revalidateUpdateAction() {
        if (checkingUpdate) return
        checkingUpdate = true
        updateButtonEnabled = false
        updateStatus = getString(R.string.update_confirming_latest)
        updater.checkAsync(object : AppUpdater.CheckCallback {
            override fun onSuccess(info: AppUpdater.UpdateInfo) {
                checkingUpdate = false
                if (!updater.isNewer(info)) {
                    AppUpdater.discardReadyDownload(this@SettingsActivity)
                    readyDownloadId = -1L
                    availableUpdate = null
                    updateButtonEnabled = true
                    updateStatus = getString(
                        R.string.update_already_latest,
                        BuildConfig.VERSION_NAME,
                    )
                    return
                }

                val readyVersion = AppUpdater.readyVersionCode(this@SettingsActivity)
                if (readyDownloadId >= 0L &&
                    readyVersion == info.versionCode &&
                    UpdateInstaller.isSuccessful(this@SettingsActivity, readyDownloadId)
                ) {
                    updateButtonEnabled = true
                    UpdateInstallActivity.open(this@SettingsActivity, readyDownloadId)
                    return
                }

                if (readyDownloadId >= 0L) {
                    AppUpdater.discardReadyDownload(this@SettingsActivity)
                    readyDownloadId = -1L
                }
                availableUpdate = info
                updateStatus = getString(
                    R.string.update_available_status,
                    displayVersion(info.versionName),
                )
                updateButtonEnabled = true
                updateConfirmation = info
            }

            override fun onFailure(error: Throwable) {
                checkingUpdate = false
                updateButtonEnabled = true
                updateStatus = getString(R.string.update_confirm_latest_failed)
            }
        })
    }

    private fun startDownload(info: AppUpdater.UpdateInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        runCatching { updater.download(this, info) }
            .onSuccess { id ->
                activeDownloadId = id
                updateStatus = getString(R.string.update_downloading)
                updateButtonEnabled = false
                pollDownload(id)
            }
            .onFailure {
                updateStatus = getString(R.string.update_download_failed)
                updateButtonEnabled = true
            }
    }

    private fun pollDownload(downloadId: Long) {
        if (isFinishing || downloadId != activeDownloadId) return
        updater.queryProgressAsync(this, downloadId) { progress ->
            if (isFinishing || downloadId != activeDownloadId) return@queryProgressAsync
            when {
                progress == null -> retryDownload(downloadId)
                progress.isActive -> {
                    if (progress.fraction >= 0f) {
                        updateStatus = getString(
                            R.string.update_downloading_progress,
                            (progress.fraction * 100f).roundToInt(),
                        )
                    }
                    handler.postDelayed({ pollDownload(downloadId) }, 500L)
                }
                progress.isSuccessful -> verifyDownload(downloadId)
                else -> retryDownload(downloadId)
            }
        }
    }

    private fun verifyDownload(downloadId: Long) {
        thread(name = "MijiaPanel-update-verify") {
            val verified = UpdateInstaller.isVerified(
                this,
                downloadId,
                updater.expectedSha256(this),
            )
            runOnUiThread {
                if (downloadId != activeDownloadId) return@runOnUiThread
                if (verified) {
                    activeDownloadId = -1L
                    readyDownloadId = downloadId
                    availableUpdate = null
                    AppUpdater.markReady(this, downloadId)
                    updateStatus = getString(R.string.update_download_ready)
                    updateButtonEnabled = true
                    UpdateInstallActivity.open(this, downloadId)
                } else {
                    retryDownload(downloadId)
                }
            }
        }
    }

    private fun retryDownload(failedId: Long) {
        val next = updater.retryNextDownload(this, failedId)
        if (next == null || next < 0L || next == failedId) {
            activeDownloadId = -1L
            updateStatus = getString(R.string.update_download_failed)
            updateButtonEnabled = true
            return
        }
        activeDownloadId = next
        updateStatus = getString(R.string.update_downloading)
        handler.postDelayed({ pollDownload(next) }, 500L)
    }

    private fun refreshReadyDownload() {
        val ready = AppUpdater.readyDownload(this)
        val readyVersion = AppUpdater.readyVersionCode(this)
        if (readyVersion <= BuildConfig.VERSION_CODE) {
            if (ready >= 0L) AppUpdater.discardReadyDownload(this)
            readyDownloadId = -1L
            return
        }
        if (ready >= 0L && UpdateInstaller.isSuccessful(this, ready)) {
            readyDownloadId = ready
            updateStatus = getString(R.string.update_download_ready)
        }
    }

    private fun displayVersion(versionName: String): String =
        if (versionName.startsWith("v", ignoreCase = true)) versionName else "v$versionName"

    private fun isLauncherIconVisible(): Boolean =
        packageManager.getComponentEnabledSetting(launcherAlias) !=
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    private fun updateLauncherIconVisibility(visible: Boolean) {
        packageManager.setComponentEnabledSetting(
            launcherAlias,
            if (visible) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            PackageManager.DONT_KILL_APP,
        )
        launcherIconVisible = visible
        Toast.makeText(
            this,
            if (visible) R.string.launcher_icon_shown else R.string.launcher_icon_hidden,
            Toast.LENGTH_SHORT,
        ).show()
    }

    private val launcherAlias: ComponentName
        get() = ComponentName(packageName, "$packageName.LauncherAlias")

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure {
                Toast.makeText(this, R.string.browser_not_available, Toast.LENGTH_SHORT).show()
            }
    }

    private val moduleApplication: MijiaPanelApplication
        get() = application as MijiaPanelApplication
}

@Composable
private fun MijiaPanelTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (darkTheme) {
        darkColorScheme(
            primary = Color(0xFF72D9BA),
            onPrimary = Color(0xFF00382B),
            primaryContainer = Color(0xFF00513F),
            onPrimaryContainer = Color(0xFF90F6D6),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF00866A),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFB1EFDC),
            onPrimaryContainer = Color(0xFF002116),
        )
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
