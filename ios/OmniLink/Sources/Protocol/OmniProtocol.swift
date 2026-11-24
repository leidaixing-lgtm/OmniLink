import CoreBluetooth
import Foundation

enum OmniConstants {
    static let protocolVersion = 1
    static let lanTcpPort: UInt16 = 52780
    static let bonjourServiceType = "_omnilink._tcp"

    static let bridgeServiceUUID = CBUUID(string: "6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    static let bridgeRxCharUUID = CBUUID(string: "6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // iOS writes -> Android
    static let bridgeTxCharUUID = CBUUID(string: "6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // Android notifies -> iOS
    static let bridgeAuthCharUUID = CBUUID(string: "6E400004-B5A3-F393-E0A9-E50E24DCCA9E") // Encrypted read triggers Pairing + ANCS

    static let chunkMagic: UInt8 = 0x4F // 'O'
}

enum MessageType: String, Codable {
    case HELLO
    case PING
    case PONG
    case NOTIFICATION_POSTED
    case NOTIFICATION_REMOVED
    case CALL_STATE
    case CALL_ACTION
}

enum CallPhase: String, Codable {
    case RINGING
    case ACTIVE
    case ENDED
}

enum RemoteCallCommand: String, Codable {
    case ANSWER
    case REJECT
    case HANGUP
    case SPEAKER_ON
    case SPEAKER_OFF
}

struct NotificationPayload: Codable, Identifiable, Equatable {
    var id: String { "\(key)-\(timestamp)" }
    let key: String
    let appId: String
    let appName: String
    let title: String
    let body: String
    let timestamp: Int64
    let sourcePlatform: String
}

struct CallStatePayload: Codable, Equatable {
    let callId: String
    var phase: CallPhase
    let phoneNumber: String
    let callerName: String
    let sourcePlatform: String
    var ancsUid: Int?
    var canAnswer: Bool = true
    var canReject: Bool = true
}

struct CallActionPayload: Codable, Equatable {
    let callId: String
    let command: RemoteCallCommand
}

struct BridgeEnvelope: Codable {
    let version: Int
    let msgId: String
    let type: MessageType
    let timestamp: Int64
    let deviceName: String
    let platform: String
    var notification: NotificationPayload?
    var removedKey: String?
    var callState: CallStatePayload?
    var callAction: CallActionPayload?

    enum CodingKeys: String, CodingKey {
        case version = "v"
        case msgId
        case type
        case timestamp
        case deviceName
        case platform
        case notification
        case removedKey
        case callState
        case callAction
    }

    init(
        version: Int = OmniConstants.protocolVersion,
        msgId: String = UUID().uuidString,
        type: MessageType,
        timestamp: Int64 = Int64(Date().timeIntervalSince1970 * 1000),
        deviceName: String,
        platform: String = "ios",
        notification: NotificationPayload? = nil,
        removedKey: String? = nil,
        callState: CallStatePayload? = nil,
        callAction: CallActionPayload? = nil
    ) {
        self.version = version
        self.msgId = msgId
        self.type = type
        self.timestamp = timestamp
        self.deviceName = deviceName
        self.platform = platform
        self.notification = notification
        self.removedKey = removedKey
        self.callState = callState
        self.callAction = callAction
    }

    func toJsonString() -> String? {
        guard let data = try? JSONEncoder().encode(self) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func fromJsonString(_ raw: String) -> BridgeEnvelope? {
        guard let data = raw.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(BridgeEnvelope.self, from: data)
    }
}

/// Thread-safe BLE MTU chunk encoder and reassembler matching Android's `BleChunkCodec`.
final class BleChunkCodec {
    private let lock = NSLock()
    private var outgoingSeq: UInt8 = 0
    private var inFlightChunks: [UInt8: [Data?]] = [:]

    func encode(json: String, maxChunkBytes: Int = 180) -> [Data] {
        lock.lock()
        defer { lock.unlock() }

        let payload = Array(json.utf8)
        let usable = max(16, maxChunkBytes - 4)
        let total = min(255, max(1, (payload.count + usable - 1) / usable))
        let seq = outgoingSeq
        outgoingSeq = outgoingSeq &+ 1

        var frames: [Data] = []
        frames.reserveCapacity(total)

        var offset = 0
        for idx in 0..<total {
            let end = min(offset + usable, payload.count)
            var frame = Data(capacity: 4 + (end - offset))
            frame.append(OmniConstants.chunkMagic)
            frame.append(seq)
            frame.append(UInt8(idx))
            frame.append(UInt8(total))
            if end > offset {
                frame.append(contentsOf: payload[offset..<end])
            }
            frames.append(frame)
            offset = end
        }
        return frames
    }

    func decodeChunk(_ packet: Data) -> String? {
        lock.lock()
        defer { lock.unlock() }

        guard packet.count >= 4 else { return nil }
        let bytes = [UInt8](packet)
        guard bytes[0] == OmniConstants.chunkMagic else { return nil }

        let seq = bytes[1]
        let idx = Int(bytes[2])
        let total = Int(bytes[3])
        guard total > 0, idx < total else { return nil }

        let slice = Data(bytes[4..<bytes.count])
        if total == 1 {
            return String(data: slice, encoding: .utf8)
        }

        if inFlightChunks[seq]?.count != total {
            inFlightChunks[seq] = Array(repeating: nil, count: total)
        }
        inFlightChunks[seq]?[idx] = slice

        if let bucket = inFlightChunks[seq], bucket.allSatisfy({ $0 != nil }) {
            inFlightChunks.removeValue(forKey: seq)
            var merged = Data()
            for part in bucket {
                if let p = part {
                    merged.append(p)
                }
            }
            return String(data: merged, encoding: .utf8)
        }

        if inFlightChunks.count > 32, let firstKey = inFlightChunks.keys.first {
            inFlightChunks.removeValue(forKey: firstKey)
        }
        return nil
    }
}
