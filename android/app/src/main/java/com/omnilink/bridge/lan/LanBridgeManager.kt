package com.omnilink.bridge.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.omnilink.bridge.data.OmniRepository
import com.omnilink.bridge.protocol.BridgeEnvelope
import com.omnilink.bridge.protocol.MessageType
import com.omnilink.bridge.protocol.OmniConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

class LanBridgeManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var heartbeatJob: Job? = null
    private var nsdRegistered = false

    private val activeWriters = CopyOnWriteArrayList<BufferedWriter>()

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            nsdRegistered = true
            OmniRepository.log("局域网 Bonjour 服务已发布: ${serviceInfo.serviceName} (端口 ${OmniConstants.LAN_TCP_PORT})")
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            nsdRegistered = false
            OmniRepository.log("Bonjour 注册失败 (错误码: $errorCode)，可使用 IP 直连")
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
            nsdRegistered = false
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
    }

    fun start() {
        refreshLocalIp()
        startTcpServer()
        registerBonjourService()
        startHeartbeat()
    }

    fun stop() {
        unregisterBonjourService()
        acceptJob?.cancel()
        heartbeatJob?.cancel()
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        activeWriters.forEach {
            try {
                it.close()
            } catch (_: Exception) {
            }
        }
        activeWriters.clear()
        OmniRepository.updateConnection {
            it.copy(lanListening = false, lanConnectedPeer = null)
        }
    }

    fun refreshLocalIp() {
        val ip = resolveLocalIpv4() ?: "未连接局域网"
        OmniRepository.updateConnection { it.copy(lanLocalIp = ip) }
    }

    fun connectToPeerIp(ip: String, port: Int = OmniConstants.LAN_TCP_PORT) {
        val cleanIp = ip.trim()
        if (cleanIp.isBlank()) return
        scope.launch {
            try {
                OmniRepository.log("正在主动连接局域网设备 $cleanIp:$port ...")
                val socket = Socket()
                socket.connect(InetSocketAddress(cleanIp, port), 4000)
                handleSocketConnection(socket)
            } catch (e: Exception) {
                OmniRepository.log("连接 $cleanIp 失败: ${e.message}")
            }
        }
    }

    fun sendOverLan(envelope: BridgeEnvelope): Boolean {
        if (activeWriters.isEmpty()) return false
        val jsonLine = envelope.toJsonString() + "\n"
        scope.launch {
            val deadWriters = ArrayList<BufferedWriter>()
            for (writer in activeWriters) {
                try {
                    synchronized(writer) {
                        writer.write(jsonLine)
                        writer.flush()
                    }
                } catch (_: Exception) {
                    deadWriters.add(writer)
                }
            }
            if (deadWriters.isNotEmpty()) {
                activeWriters.removeAll(deadWriters.toSet())
                if (activeWriters.isEmpty()) {
                    OmniRepository.updateConnection { it.copy(lanConnectedPeer = null) }
                }
            }
        }
        return true
    }

    private fun startTcpServer() {
        if (serverSocket != null) return
        acceptJob = scope.launch {
            try {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(OmniConstants.LAN_TCP_PORT))
                serverSocket = server
                OmniRepository.updateConnection { it.copy(lanListening = true) }
                refreshLocalIp()

                while (isActive && !server.isClosed) {
                    val client = server.accept()
                    launch { handleSocketConnection(client) }
                }
            } catch (e: Exception) {
                if (isActive) {
                    OmniRepository.log("局域网监听异常: ${e.message}")
                }
                OmniRepository.updateConnection { it.copy(lanListening = false) }
            }
        }
    }

    private suspend fun handleSocketConnection(socket: Socket) {
        socket.keepAlive = true
        socket.tcpNoDelay = true
        val remoteIp = socket.inetAddress?.hostAddress ?: "iPhone"
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))

        activeWriters.add(writer)
        OmniRepository.updateConnection { it.copy(lanConnectedPeer = "iOS ($remoteIp)") }
        OmniRepository.log("Wi-Fi 局域网通道已连接: $remoteIp")

        // Send HELLO handshake immediately
        try {
            val hello = BridgeEnvelope(
                type = MessageType.HELLO,
                deviceName = OmniRepository.localDeviceName(),
                platform = "android"
            ).toJsonString() + "\n"
            synchronized(writer) {
                writer.write(hello)
                writer.flush()
            }
        } catch (_: Exception) {
        }

        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val env = BridgeEnvelope.fromJsonString(line) ?: continue
                if (env.deviceName.isNotBlank()) {
                    OmniRepository.updateConnection {
                        it.copy(lanConnectedPeer = "${env.deviceName} ($remoteIp)")
                    }
                }
                OmniRepository.handleIncomingEnvelope(env, "Wi-Fi")
            }
        } catch (_: Exception) {
        } finally {
            activeWriters.remove(writer)
            try {
                socket.close()
            } catch (_: Exception) {
            }
            if (activeWriters.isEmpty()) {
                OmniRepository.updateConnection { it.copy(lanConnectedPeer = null) }
                OmniRepository.log("Wi-Fi 局域网连接已断开: $remoteIp")
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(15_000)
                refreshLocalIp()
                if (activeWriters.isNotEmpty()) {
                    sendOverLan(
                        BridgeEnvelope(
                            type = MessageType.PING,
                            deviceName = OmniRepository.localDeviceName(),
                            platform = "android"
                        )
                    )
                }
            }
        }
    }

    private fun registerBonjourService() {
        val manager = nsdManager ?: return
        if (nsdRegistered) return
        try {
            val info = NsdServiceInfo().apply {
                serviceName = "${OmniConstants.BONJOUR_SERVICE_NAME}-${OmniRepository.localDeviceName().take(12)}"
                serviceType = OmniConstants.BONJOUR_SERVICE_TYPE
                port = OmniConstants.LAN_TCP_PORT
            }
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            OmniRepository.log("Bonjour 启动异常: ${e.message}")
        }
    }

    private fun unregisterBonjourService() {
        if (!nsdRegistered) return
        try {
            nsdManager?.unregisterService(registrationListener)
        } catch (_: Exception) {
        }
        nsdRegistered = false
    }

    private fun resolveLocalIpv4(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val name = intf.name.lowercase()
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun")) continue
                for (addr in intf.inetAddresses.toList()) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
