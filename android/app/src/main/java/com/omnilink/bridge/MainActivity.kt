package com.omnilink.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.omnilink.bridge.service.OmniBridgeForegroundService
import com.omnilink.bridge.service.OmniNotificationListenerService
import com.omnilink.bridge.ui.OmniMainScreen

private val OmniColorScheme = darkColorScheme(
    primary = Color(0xFF38BDF8),
    secondary = Color(0xFF34D399),
    tertiary = Color(0xFFA78BFA),
    background = Color(0xFF0B1120),
    surface = Color(0xFF151F32),
    onPrimary = Color(0xFF082F49),
    onBackground = Color(0xFFF1F5F9),
    onSurface = Color(0xFFE2E8F0)
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startBridgeService()

        setContent {
            MaterialTheme(colorScheme = OmniColorScheme) {
                var refreshTick by remember { mutableIntStateOf(0) }

                val runtimePermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    refreshTick++
                    restartBridgeService()
                }

                val roleLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) {
                    refreshTick++
                }

                LaunchedEffect(Unit) {
                    val missing = requiredRuntimePermissions().filter {
                        ContextCompat.checkSelfPermission(this@MainActivity, it) !=
                            PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isNotEmpty()) {
                        runtimePermissionLauncher.launch(missing.toTypedArray())
                    }
                }

                OmniMainScreen(
                    permissionRefreshKey = refreshTick,
                    hasBluetoothPerms = hasBluetoothPermissions(),
                    hasPhonePerms = hasPhonePermissions(),
                    hasNotifListener = isNotificationListenerEnabled(),
                    ignoringBatteryOpt = isIgnoringBatteryOptimizations(),
                    onRequestRuntimePerms = {
                        runtimePermissionLauncher.launch(requiredRuntimePermissions().toTypedArray())
                    },
                    onOpenNotificationListenerSettings = {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    onRequestBatteryWhitelist = {
                        requestIgnoreBatteryOptimization()
                    },
                    onRequestCallScreeningRole = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val rm = getSystemService(Context.ROLE_SERVICE) as? RoleManager
                            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
                                roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
                            }
                        }
                    },
                    onRestartBridge = {
                        refreshTick++
                        restartBridgeService()
                    },
                    onConnectManualIp = { ip ->
                        connectManualIp(ip)
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startBridgeService()
    }

    private fun requiredRuntimePermissions(): List<String> {
        val list = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.ANSWER_PHONE_CALLS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
            list.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasPhonePermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val cn = ComponentName(this, OmniNotificationListenerService::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(cn.flattenToString()) == true
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimization() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun startBridgeService() {
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, OmniBridgeForegroundService::class.java)
            )
        } catch (_: Exception) {
        }
    }

    private fun restartBridgeService() {
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, OmniBridgeForegroundService::class.java).apply {
                    action = OmniBridgeForegroundService.ACTION_RESTART_CHANNELS
                }
            )
        } catch (_: Exception) {
        }
    }

    private fun connectManualIp(ip: String) {
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, OmniBridgeForegroundService::class.java).apply {
                    action = OmniBridgeForegroundService.ACTION_CONNECT_MANUAL_IP
                    putExtra(OmniBridgeForegroundService.EXTRA_MANUAL_IP, ip)
                }
            )
        } catch (_: Exception) {
        }
    }
}
