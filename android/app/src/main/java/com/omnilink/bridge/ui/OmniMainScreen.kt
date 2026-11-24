package com.omnilink.bridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnilink.bridge.data.LinkConnectionState
import com.omnilink.bridge.data.OmniRepository
import com.omnilink.bridge.protocol.CallPhase
import com.omnilink.bridge.protocol.CallStatePayload
import com.omnilink.bridge.protocol.NotificationPayload
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OmniMainScreen(
    permissionRefreshKey: Int,
    hasBluetoothPerms: Boolean,
    hasPhonePerms: Boolean,
    hasNotifListener: Boolean,
    ignoringBatteryOpt: Boolean,
    onRequestRuntimePerms: () -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    onRequestBatteryWhitelist: () -> Unit,
    onRequestCallScreeningRole: () -> Unit,
    onRestartBridge: () -> Unit,
    onConnectManualIp: (String) -> Unit
) {
    val connState by OmniRepository.connectionState.collectAsState()
    val remoteCall by OmniRepository.remoteIphoneCall.collectAsState()
    val localCall by OmniRepository.localAndroidCall.collectAsState()
    val notifications by OmniRepository.notifications.collectAsState()
    val logs by OmniRepository.logs.collectAsState()
    val settings by OmniRepository.settings.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "双机互联 OmniLink",
                            fontWeight = FontWeight.Bold,
                            fontSize = 19.sp
                        )
                        Text(
                            text = if (connState.isAnyChannelConnected) {
                                "跨生态双机实时在线"
                            } else {
                                "等待连接 iPhone（支持蓝牙 ANCS + Wi-Fi）"
                            },
                            fontSize = 12.sp,
                            color = if (connState.isAnyChannelConnected) {
                                Color(0xFF34D399)
                            } else {
                                Color(0xFF94A3B8)
                            }
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRestartBridge) {
                        Icon(Icons.Default.Refresh, contentDescription = "重连服务")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Bluetooth, contentDescription = null) },
                    label = { Text("互联状态") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Notifications, contentDescription = null) },
                    label = { Text("通知中心 (${notifications.size})") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Terminal, contentDescription = null) },
                    label = { Text("联调与日志") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("设置") }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Persistent live call banner whenever iPhone or Android is ringing/in-call
            remoteCall?.let { call ->
                ActiveIphoneCallBanner(
                    call = call,
                    onAnswer = { OmniRepository.performIphoneCallAction(true) },
                    onReject = { OmniRepository.performIphoneCallAction(false) }
                )
            }

            localCall?.let { call ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E3A8A))
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Call, contentDescription = null, tint = Color(0xFF93C5FD))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "本机来电已同步至 iPhone: ${call.callerName}",
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                            Text(
                                text = "可在 iPhone 锁屏 CallKit 直接滑动接听或挂断",
                                fontSize = 12.sp,
                                color = Color(0xFFBFDBFE)
                            )
                        }
                    }
                }
            }

            when (selectedTab) {
                0 -> DashboardTab(
                    key = permissionRefreshKey,
                    connState = connState,
                    hasBluetoothPerms = hasBluetoothPerms,
                    hasPhonePerms = hasPhonePerms,
                    hasNotifListener = hasNotifListener,
                    ignoringBatteryOpt = ignoringBatteryOpt,
                    onRequestRuntimePerms = onRequestRuntimePerms,
                    onOpenNotificationListenerSettings = onOpenNotificationListenerSettings,
                    onRequestBatteryWhitelist = onRequestBatteryWhitelist,
                    onRequestCallScreeningRole = onRequestCallScreeningRole,
                    onConnectManualIp = onConnectManualIp
                )

                1 -> NotificationsTab(
                    notifications = notifications,
                    onClear = { OmniRepository.clearNotifications() }
                )

                2 -> DiagnosticsTab(logs = logs)

                3 -> SettingsTab(settings = settings)
            }
        }
    }
}

@Composable
private fun ActiveIphoneCallBanner(
    call: CallStatePayload,
    onAnswer: () -> Unit,
    onReject: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF064E3B)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (call.phase == CallPhase.RINGING) {
                    "iPhone 正在来电 · ${call.callerName}"
                } else {
                    "iPhone 通话中 · ${call.callerName}"
                },
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = Color.White
            )
            Text(
                text = call.phoneNumber,
                fontSize = 14.sp,
                color = Color(0xFFA7F3D0)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (call.phase == CallPhase.RINGING) {
                    Button(
                        onClick = onAnswer,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Call, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("接听 iPhone 来电", color = Color.White)
                    }
                }
                Button(
                    onClick = onReject,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.CallEnd, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("拒接 / 挂断", color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun DashboardTab(
    key: Int,
    connState: LinkConnectionState,
    hasBluetoothPerms: Boolean,
    hasPhonePerms: Boolean,
    hasNotifListener: Boolean,
    ignoringBatteryOpt: Boolean,
    onRequestRuntimePerms: () -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    onRequestBatteryWhitelist: () -> Unit,
    onRequestCallScreeningRole: () -> Unit,
    onConnectManualIp: (String) -> Unit
) {
    val context = LocalContext.current
    var manualIp by remember(connState.manualTargetIp) { mutableStateOf(connState.manualTargetIp) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("双通道近场连接状态", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(12.dp))

                    ChannelStatusRow(
                        icon = Icons.Default.Bluetooth,
                        title = "蓝牙 BLE + 苹果 ANCS 通道",
                        subtitle = when {
                            connState.ancsSubscribed ->
                                "已连接 ${connState.bleConnectedPeer ?: "iPhone"} · ANCS 通知与来电已激活"
                            connState.bleConnectedPeer != null ->
                                "已连 ${connState.bleConnectedPeer} · 请在 iPhone 弹窗点「配对」并允许显示通知"
                            connState.bleAdvertising ->
                                "广播就绪 · 请打开 iPhone 端「双机互联」点击扫描配对"
                            else -> "蓝牙广播未启动（请检查蓝牙开关与权限）"
                        },
                        online = connState.ancsSubscribed || connState.bleConnectedPeer != null
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    ChannelStatusRow(
                        icon = Icons.Default.Wifi,
                        title = "Wi-Fi 局域网高速通道 (端口 52780)",
                        subtitle = if (connState.lanConnectedPeer != null) {
                            "已直连 ${connState.lanConnectedPeer} · 本机 IP: ${connState.lanLocalIp}"
                        } else {
                            "本机 IP: ${connState.lanLocalIp} · Bonjour 自动发现已开启"
                        },
                        online = connState.lanConnectedPeer != null
                    )

                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = manualIp,
                            onValueChange = { manualIp = it },
                            label = { Text("可选：输入 iPhone 局域网 IP") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                OmniRepository.saveManualTargetIp(context, manualIp)
                                onConnectManualIp(manualIp)
                            }
                        ) {
                            Text("连接")
                        }
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "系统权限一键配置体检",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )

                    PermissionCheckRow(
                        title = "1. 蓝牙近场广播与连接权限",
                        granted = hasBluetoothPerms,
                        actionLabel = "授权",
                        onAction = onRequestRuntimePerms
                    )
                    PermissionCheckRow(
                        title = "2. 通知读取权限 (同步安卓通知至 iPhone)",
                        granted = hasNotifListener,
                        actionLabel = "去开启",
                        onAction = onOpenNotificationListenerSettings
                    )
                    PermissionCheckRow(
                        title = "3. 来电状态识别与远程接听权限",
                        granted = hasPhonePerms,
                        actionLabel = "授权",
                        onAction = onRequestRuntimePerms
                    )
                    PermissionCheckRow(
                        title = "4. 后台保活 / 忽略电池优化",
                        granted = ignoringBatteryOpt,
                        actionLabel = "白名单",
                        onAction = onRequestBatteryWhitelist
                    )

                    OutlinedButton(
                        onClick = onRequestCallScreeningRole,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("可选增强：授权「来电号码识别服务」精准显示陌电号码")
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("首次配对 3 步指南（免越狱 / 免 Root）", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "① 确保上面 4 项权限均显示绿色已就绪；\n" +
                            "② 打开 iPhone 上的「双机互联 OmniLink」，点击【扫描并绑定安卓手机】；\n" +
                            "③ iPhone 弹出系统提示时，依次点击【配对】与【允许显示通知】，即可自动双向打通所有通知与来电控制！",
                        fontSize = 13.sp,
                        color = Color(0xFFCBD5E1),
                        lineHeight = 20.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelStatusRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    online: Boolean
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = CircleShape,
            color = if (online) Color(0xFF34D399).copy(alpha = 0.2f) else Color(0xFF475569).copy(alpha = 0.3f),
            modifier = Modifier.size(42.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (online) Color(0xFF34D399) else Color(0xFF94A3B8)
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(subtitle, fontSize = 12.sp, color = Color(0xFF94A3B8))
        }
    }
}

@Composable
private fun PermissionCheckRow(
    title: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (granted) Color(0xFF34D399) else Color(0xFFFBBF24),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = title, fontSize = 13.sp)
        }
        if (!granted) {
            Button(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(actionLabel, fontSize = 12.sp)
            }
        } else {
            Text("已就绪", fontSize = 12.sp, color = Color(0xFF34D399))
        }
    }
}

@Composable
private fun NotificationsTab(
    notifications: List<NotificationPayload>,
    onClear: () -> Unit
) {
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("双机实时通知时间流", fontWeight = FontWeight.Bold)
            if (notifications.isNotEmpty()) {
                OutlinedButton(onClick = onClear) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("清空记录")
                }
            }
        }

        if (notifications.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "暂无跨机通知记录\n连接成功后，iPhone 与本机的通知会在此实时显示",
                    color = Color(0xFF94A3B8)
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(notifications, key = { "${it.key}-${it.timestamp}" }) { item ->
                    val fromIos = item.sourcePlatform == "ios"
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (fromIos) "来自 iPhone · ${item.appName}" else "已推至 iPhone · ${item.appName}",
                                    fontSize = 12.sp,
                                    color = if (fromIos) Color(0xFF38BDF8) else Color(0xFF34D399),
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = formatter.format(Date(item.timestamp)),
                                    fontSize = 11.sp,
                                    color = Color(0xFF64748B)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(item.title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            if (item.body.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(item.body, fontSize = 13.sp, color = Color(0xFFCBD5E1))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsTab(logs: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("一键链路模拟测试（无需真打电话即可验证）", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            OmniRepository.onAndroidNotificationPosted(
                                NotificationPayload(
                                    key = "sim-${System.currentTimeMillis()}",
                                    appId = "com.tencent.mm",
                                    appName = "微信(测试)",
                                    title = "双机互联测试",
                                    body = "这是一条从安卓发往 iPhone 的实时同步测试通知！",
                                    sourcePlatform = "android"
                                )
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("推测试通知到iPhone", fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            OmniRepository.onAndroidCallStateChanged(
                                CallStatePayload(
                                    callId = "sim-call-${System.currentTimeMillis()}",
                                    phase = CallPhase.RINGING,
                                    phoneNumber = "138-0013-8000",
                                    callerName = "模拟安卓来电",
                                    sourcePlatform = "android"
                                )
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("模拟来电弹CallKit", fontSize = 12.sp, color = Color.White)
                    }
                }
            }
        }

        Text("底层通信日志", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Card(
            modifier = Modifier.fillMaxSize(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF090D16))
        ) {
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(logs) { line ->
                    Text(text = line, fontSize = 12.sp, color = Color(0xFF94A3B8))
                }
            }
        }
    }
}

@Composable
private fun SettingsTab(settings: com.omnilink.bridge.data.UserSettings) {
    val context = LocalContext.current
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SettingToggleCard(
                title = "同步本机安卓通知到 iPhone",
                subtitle = "实时捕获微信、短信等通知并推送至 iPhone 弹窗",
                checked = settings.forwardAndroidNotifications,
                onCheckedChange = { v ->
                    OmniRepository.updateSettings(context) { it.copy(forwardAndroidNotifications = v) }
                }
            )
        }
        item {
            SettingToggleCard(
                title = "接收 iPhone 系统通知 (ANCS)",
                subtitle = "在本机状态栏弹出 iPhone 微信、短信及应用通知",
                checked = settings.receiveIphoneNotifications,
                onCheckedChange = { v ->
                    OmniRepository.updateSettings(context) { it.copy(receiveIphoneNotifications = v) }
                }
            )
        }
        item {
            SettingToggleCard(
                title = "同步本机安卓来电到 iPhone CallKit",
                subtitle = "安卓来电时唤醒 iPhone 原生电话锁屏界面，支持滑动接听/拒接",
                checked = settings.forwardAndroidCalls,
                onCheckedChange = { v ->
                    OmniRepository.updateSettings(context) { it.copy(forwardAndroidCalls = v) }
                }
            )
        }
        item {
            SettingToggleCard(
                title = "接收 iPhone 来电全屏弹窗",
                subtitle = "iPhone 来电时亮屏弹出接听/拒接控制器",
                checked = settings.receiveIphoneCalls,
                onCheckedChange = { v ->
                    OmniRepository.updateSettings(context) { it.copy(receiveIphoneCalls = v) }
                }
            )
        }
        item {
            SettingToggleCard(
                title = "iPhone 远程接听安卓来电时自动开启免提",
                subtitle = "未佩戴蓝牙耳机时，点击接听自动打开安卓扬声器免提",
                checked = settings.autoSpeakerOnRemoteAnswer,
                onCheckedChange = { v ->
                    OmniRepository.updateSettings(context) { it.copy(autoSpeakerOnRemoteAnswer = v) }
                }
            )
        }
        item {
            SettingToggleCard(
                title = "过滤常驻状态栏通知",
                subtitle = "忽略音乐播放条、天气常驻、计步器等持续性通知",
                checked = settings.ignoreOngoingNotifications,
                onCheckedChange = { v ->
                    OmniRepository.updateSettings(context) { it.copy(ignoreOngoingNotifications = v) }
                }
            )
        }
    }
}

@Composable
private fun SettingToggleCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(subtitle, fontSize = 12.sp, color = Color(0xFF94A3B8))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}
