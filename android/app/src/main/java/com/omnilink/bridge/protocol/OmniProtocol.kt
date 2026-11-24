package com.omnilink.bridge.protocol

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.math.min

object OmniConstants {
    const val PROTOCOL_VERSION = 1
    const val LAN_TCP_PORT = 52780
    const val BONJOUR_SERVICE_TYPE = "_omnilink._tcp."
    const val BONJOUR_SERVICE_NAME = "OmniLink-Android"

    // Custom Bidirectional BLE Bridge Service (Nordic UART inspired)
    val BRIDGE_SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    val BRIDGE_RX_CHAR_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // iOS writes -> Android
    val BRIDGE_TX_CHAR_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // Android notifies -> iOS
    val BRIDGE_AUTH_CHAR_UUID: UUID = UUID.fromString("6E400004-B5A3-F393-E0A9-E50E24DCCA9E") // Encrypted read triggers iOS Pairing + ANCS

    // Apple Notification Center Service (ANCS) System UUIDs
    val ANCS_SERVICE_UUID: UUID = UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0")
    val ANCS_NOTIF_SOURCE_UUID: UUID = UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD")
    val ANCS_CONTROL_POINT_UUID: UUID = UUID.fromString("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9")
    val ANCS_DATA_SOURCE_UUID: UUID = UUID.fromString("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB")

    // Standard BLE Client Characteristic Configuration Descriptor
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    const val CHUNK_MAGIC: Byte = 0x4F // 'O'
}

enum class MessageType {
    HELLO,
    PING,
    PONG,
    NOTIFICATION_POSTED,
    NOTIFICATION_REMOVED,
    CALL_STATE,
    CALL_ACTION
}

enum class CallPhase {
    RINGING,
    ACTIVE,
    ENDED
}

enum class RemoteCallCommand {
    ANSWER,
    REJECT,
    HANGUP,
    SPEAKER_ON,
    SPEAKER_OFF
}

data class NotificationPayload(
    val key: String,
    val appId: String,
    val appName: String,
    val title: String,
    val body: String,
    val timestamp: Long = System.currentTimeMillis(),
    val sourcePlatform: String = "android"
)

data class CallStatePayload(
    val callId: String,
    val phase: CallPhase,
    val phoneNumber: String,
    val callerName: String,
    val sourcePlatform: String,
    val ancsUid: Int? = null,
    val canAnswer: Boolean = true,
    val canReject: Boolean = true
)

data class CallActionPayload(
    val callId: String,
    val command: RemoteCallCommand
)

data class BridgeEnvelope(
    val version: Int = OmniConstants.PROTOCOL_VERSION,
    val msgId: String = UUID.randomUUID().toString(),
    val type: MessageType,
    val timestamp: Long = System.currentTimeMillis(),
    val deviceName: String,
    val platform: String = "android",
    val notification: NotificationPayload? = null,
    val removedKey: String? = null,
    val callState: CallStatePayload? = null,
    val callAction: CallActionPayload? = null
) {
    fun toJsonString(): String {
        val root = JSONObject()
        root.put("v", version)
        root.put("msgId", msgId)
        root.put("type", type.name)
        root.put("timestamp", timestamp)
        root.put("deviceName", deviceName)
        root.put("platform", platform)
        removedKey?.let { root.put("removedKey", it) }

        notification?.let { n ->
            val obj = JSONObject()
            obj.put("key", n.key)
            obj.put("appId", n.appId)
            obj.put("appName", n.appName)
            obj.put("title", n.title)
            obj.put("body", n.body)
            obj.put("timestamp", n.timestamp)
            obj.put("sourcePlatform", n.sourcePlatform)
            root.put("notification", obj)
        }

        callState?.let { c ->
            val obj = JSONObject()
            obj.put("callId", c.callId)
            obj.put("phase", c.phase.name)
            obj.put("phoneNumber", c.phoneNumber)
            obj.put("callerName", c.callerName)
            obj.put("sourcePlatform", c.sourcePlatform)
            c.ancsUid?.let { obj.put("ancsUid", it) }
            obj.put("canAnswer", c.canAnswer)
            obj.put("canReject", c.canReject)
            root.put("callState", obj)
        }

        callAction?.let { a ->
            val obj = JSONObject()
            obj.put("callId", a.callId)
            obj.put("command", a.command.name)
            root.put("callAction", obj)
        }

        return root.toString()
    }

    companion object {
        fun fromJsonString(raw: String): BridgeEnvelope? {
            return try {
                val root = JSONObject(raw)
                val typeStr = root.optString("type", "")
                val type = MessageType.valueOf(typeStr)
                val notifObj = root.optJSONObject("notification")
                val callStateObj = root.optJSONObject("callState")
                val callActionObj = root.optJSONObject("callAction")

                BridgeEnvelope(
                    version = root.optInt("v", 1),
                    msgId = root.optString("msgId", UUID.randomUUID().toString()),
                    type = type,
                    timestamp = root.optLong("timestamp", System.currentTimeMillis()),
                    deviceName = root.optString("deviceName", "Peer Device"),
                    platform = root.optString("platform", "ios"),
                    removedKey = if (root.has("removedKey")) root.optString("removedKey") else null,
                    notification = notifObj?.let {
                        NotificationPayload(
                            key = it.optString("key"),
                            appId = it.optString("appId"),
                            appName = it.optString("appName"),
                            title = it.optString("title"),
                            body = it.optString("body"),
                            timestamp = it.optLong("timestamp", System.currentTimeMillis()),
                            sourcePlatform = it.optString("sourcePlatform", "ios")
                        )
                    },
                    callState = callStateObj?.let {
                        CallStatePayload(
                            callId = it.optString("callId"),
                            phase = CallPhase.valueOf(it.optString("phase", "RINGING")),
                            phoneNumber = it.optString("phoneNumber"),
                            callerName = it.optString("callerName"),
                            sourcePlatform = it.optString("sourcePlatform", "ios"),
                            ancsUid = if (it.has("ancsUid")) it.optInt("ancsUid") else null,
                            canAnswer = it.optBoolean("canAnswer", true),
                            canReject = it.optBoolean("canReject", true)
                        )
                    },
                    callAction = callActionObj?.let {
                        CallActionPayload(
                            callId = it.optString("callId"),
                            command = RemoteCallCommand.valueOf(it.optString("command", "ANSWER"))
                        )
                    }
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Splits arbitrary UTF-8 JSON payloads into MTU-safe BLE chunks and reassembles them reliably.
 * Header format (4 bytes): [0x4F, seqId, chunkIndex, totalChunks]
 */
class BleChunkCodec {
    private var outgoingSeq: Int = 0
    private val inFlightChunks = HashMap<Int, Array<ByteArray?>>()

    @Synchronized
    fun encode(json: String, maxChunkBytes: Int = 180): List<ByteArray> {
        val payloadBytes = json.toByteArray(StandardCharsets.UTF_8)
        val usablePayload = (maxChunkBytes - 4).coerceAtLeast(16)
        val totalChunks = ((payloadBytes.size + usablePayload - 1) / usablePayload).coerceIn(1, 255)
        val seq = outgoingSeq and 0xFF
        outgoingSeq = (outgoingSeq + 1) and 0xFF

        val frames = ArrayList<ByteArray>(totalChunks)
        var offset = 0
        for (idx in 0 until totalChunks) {
            val end = min(offset + usablePayload, payloadBytes.size)
            val sliceLen = end - offset
            val frame = ByteArray(4 + sliceLen)
            frame[0] = OmniConstants.CHUNK_MAGIC
            frame[1] = seq.toByte()
            frame[2] = idx.toByte()
            frame[3] = totalChunks.toByte()
            System.arraycopy(payloadBytes, offset, frame, 4, sliceLen)
            frames.add(frame)
            offset = end
        }
        return frames
    }

    @Synchronized
    fun decodeChunk(packet: ByteArray): String? {
        if (packet.size < 4 || packet[0] != OmniConstants.CHUNK_MAGIC) return null
        val seq = packet[1].toInt() and 0xFF
        val index = packet[2].toInt() and 0xFF
        val total = packet[3].toInt() and 0xFF
        if (total == 0 || index >= total) return null

        val slice = packet.copyOfRange(4, packet.size)
        if (total == 1) {
            return String(slice, StandardCharsets.UTF_8)
        }

        val bucket = inFlightChunks.getOrPut(seq) { arrayOfNulls(total) }
        if (bucket.size != total) {
            inFlightChunks[seq] = arrayOfNulls(total)
        }
        inFlightChunks[seq]!![index] = slice

        val currentBucket = inFlightChunks[seq]!!
        if (currentBucket.all { it != null }) {
            inFlightChunks.remove(seq)
            val totalSize = currentBucket.sumOf { it!!.size }
            val merged = ByteArray(totalSize)
            var pos = 0
            for (part in currentBucket) {
                System.arraycopy(part!!, 0, merged, pos, part.size)
                pos += part.size
            }
            return String(merged, StandardCharsets.UTF_8)
        }

        // Prevent stale memory growth
        if (inFlightChunks.size > 32) {
            val oldestKey = inFlightChunks.keys.first()
            inFlightChunks.remove(oldestKey)
        }
        return null
    }
}

/**
 * Apple Notification Center Service (ANCS) binary parser & fragmented TLV reassembler.
 */
object AncsProtocol {
    const val EVENT_ID_NOTIFICATION_ADDED = 0
    const val EVENT_ID_NOTIFICATION_MODIFIED = 1
    const val EVENT_ID_NOTIFICATION_REMOVED = 2

    const val EVENT_FLAG_PRE_EXISTING = 1 shl 2
    const val EVENT_FLAG_POSITIVE_ACTION = 1 shl 3
    const val EVENT_FLAG_NEGATIVE_ACTION = 1 shl 4

    const val CATEGORY_INCOMING_CALL = 1
    const val CATEGORY_MISSED_CALL = 2

    const val CMD_GET_NOTIFICATION_ATTRIBUTES: Byte = 0x00
    const val CMD_PERFORM_NOTIFICATION_ACTION: Byte = 0x02

    const val ATTR_APP_IDENTIFIER: Byte = 0x00
    const val ATTR_TITLE: Byte = 0x01
    const val ATTR_SUBTITLE: Byte = 0x02
    const val ATTR_MESSAGE: Byte = 0x03
    const val ATTR_DATE: Byte = 0x05
    const val ATTR_POSITIVE_ACTION_LABEL: Byte = 0x06
    const val ATTR_NEGATIVE_ACTION_LABEL: Byte = 0x07

    const val ACTION_ID_POSITIVE: Byte = 0x00 // Answer Call / Confirm
    const val ACTION_ID_NEGATIVE: Byte = 0x01 // Reject / Dismiss

    data class SourceEvent(
        val eventId: Int,
        val eventFlags: Int,
        val categoryId: Int,
        val categoryCount: Int,
        val notificationUid: Int
    ) {
        val isPreExisting: Boolean get() = (eventFlags and EVENT_FLAG_PRE_EXISTING) != 0
        val hasPositiveAction: Boolean get() = (eventFlags and EVENT_FLAG_POSITIVE_ACTION) != 0
        val hasNegativeAction: Boolean get() = (eventFlags and EVENT_FLAG_NEGATIVE_ACTION) != 0
    }

    data class ParsedAncsNotification(
        val uid: Int,
        val appIdentifier: String,
        val appDisplayName: String,
        val title: String,
        val subtitle: String,
        val message: String,
        val date: String,
        val positiveLabel: String,
        val negativeLabel: String
    )

    fun parseNotificationSource(bytes: ByteArray): SourceEvent? {
        if (bytes.size < 8) return null
        val uid = ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        return SourceEvent(
            eventId = bytes[0].toInt() and 0xFF,
            eventFlags = bytes[1].toInt() and 0xFF,
            categoryId = bytes[2].toInt() and 0xFF,
            categoryCount = bytes[3].toInt() and 0xFF,
            notificationUid = uid
        )
    }

    fun buildGetAttributesCommand(uid: Int): ByteArray {
        val buf = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(CMD_GET_NOTIFICATION_ATTRIBUTES)
        buf.putInt(uid)
        buf.put(ATTR_APP_IDENTIFIER)
        buf.put(ATTR_TITLE)
        buf.putShort(128.toShort())
        buf.put(ATTR_SUBTITLE)
        buf.putShort(64.toShort())
        buf.put(ATTR_MESSAGE)
        buf.putShort(512.toShort())
        buf.put(ATTR_DATE)
        buf.put(ATTR_POSITIVE_ACTION_LABEL)
        buf.put(ATTR_NEGATIVE_ACTION_LABEL)
        return buf.array()
    }

    fun buildPerformActionCommand(uid: Int, positive: Boolean): ByteArray {
        val buf = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(CMD_PERFORM_NOTIFICATION_ACTION)
        buf.putInt(uid)
        buf.put(if (positive) ACTION_ID_POSITIVE else ACTION_ID_NEGATIVE)
        return buf.array()
    }

    fun friendlyIosAppName(bundleId: String): String {
        return when (bundleId) {
            "com.tencent.xin" -> "微信"
            "com.tencent.mqq" -> "QQ"
            "com.apple.MobileSMS" -> "iPhone 短信"
            "com.apple.mobilephone", "com.apple.InCallService" -> "iPhone 电话"
            "com.apple.facetime" -> "FaceTime"
            "com.alipay.iphoneclient" -> "支付宝"
            "com.ss.iphone.ugc.Aweme" -> "抖音"
            "com.xingin.discover" -> "小红书"
            "com.alibaba.DingTalk" -> "钉钉"
            "com.bytedance.ee.lark" -> "飞书"
            "com.tencent.wework" -> "企业微信"
            "com.meituan.imeituan" -> "美团"
            "com.taobao.taobao4iphone" -> "淘宝"
            "com.apple.mobilemail" -> "iPhone 邮件"
            "com.apple.mobilecal" -> "iPhone 日历"
            else -> bundleId.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
    }
}

/**
 * Reassembles fragmented ANCS Data Source BLE packets across multiple notifications.
 * Expects exact 7 attributes requested in [AncsProtocol.buildGetAttributesCommand].
 */
class AncsDataReassembler {
    private var buffer = ByteArray(0)
    private val expectedAttrCount = 7

    @Synchronized
    fun appendAndTryParse(chunk: ByteArray): AncsProtocol.ParsedAncsNotification? {
        val combined = ByteArray(buffer.size + chunk.size)
        System.arraycopy(buffer, 0, combined, 0, buffer.size)
        System.arraycopy(chunk, 0, combined, buffer.size, chunk.size)
        buffer = combined

        if (buffer.size < 5) return null
        if (buffer[0] != AncsProtocol.CMD_GET_NOTIFICATION_ATTRIBUTES) {
            // Reset corrupted header
            buffer = ByteArray(0)
            return null
        }

        val uid = ByteBuffer.wrap(buffer, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
        var cursor = 5
        val attrs = HashMap<Byte, String>()

        for (i in 0 until expectedAttrCount) {
            if (cursor + 3 > buffer.size) {
                // Need more BLE fragments
                return null
            }
            val attrId = buffer[cursor]
            val len = ((buffer[cursor + 1].toInt() and 0xFF) or
                ((buffer[cursor + 2].toInt() and 0xFF) shl 8))
            cursor += 3
            if (cursor + len > buffer.size) {
                // Wait for remaining payload bytes
                return null
            }
            val value = if (len > 0) {
                String(buffer, cursor, len, StandardCharsets.UTF_8)
            } else {
                ""
            }
            attrs[attrId] = value
            cursor += len
        }

        // Successfully parsed 1 complete ANCS attribute response; keep any trailing bytes
        buffer = if (cursor < buffer.size) {
            buffer.copyOfRange(cursor, buffer.size)
        } else {
            ByteArray(0)
        }

        val bundleId = attrs[AncsProtocol.ATTR_APP_IDENTIFIER].orEmpty()
        return AncsProtocol.ParsedAncsNotification(
            uid = uid,
            appIdentifier = bundleId,
            appDisplayName = AncsProtocol.friendlyIosAppName(bundleId),
            title = attrs[AncsProtocol.ATTR_TITLE].orEmpty(),
            subtitle = attrs[AncsProtocol.ATTR_SUBTITLE].orEmpty(),
            message = attrs[AncsProtocol.ATTR_MESSAGE].orEmpty(),
            date = attrs[AncsProtocol.ATTR_DATE].orEmpty(),
            positiveLabel = attrs[AncsProtocol.ATTR_POSITIVE_ACTION_LABEL].orEmpty(),
            negativeLabel = attrs[AncsProtocol.ATTR_NEGATIVE_ACTION_LABEL].orEmpty()
        )
    }

    @Synchronized
    fun reset() {
        buffer = ByteArray(0)
    }
}
