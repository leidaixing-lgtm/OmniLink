import AVFoundation
import Foundation
import UIKit

/// Keeps the iOS process & LAN/BLE event loop responsive while the iPhone screen is locked (black screen)
/// using a zero-volume `.playback` + `.mixWithOthers` audio loop (no microphone orange dot, never pauses user music).
final class BackgroundKeepAliveManager {
    static let shared = BackgroundKeepAliveManager()

    private var silentPlayer: AVAudioPlayer?
    private var isEnabled: Bool = true
    private var pausedForCall: Bool = false

    private init() {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleAudioInterruption(_:)),
            name: AVAudioSession.interruptionNotification,
            object: nil
        )
    }

    func setEnabled(_ enabled: Bool) {
        isEnabled = enabled
        if enabled {
            startIfNeeded()
        } else {
            stop()
        }
    }

    func pauseForSystemCall() {
        pausedForCall = true
        silentPlayer?.pause()
    }

    func resumeAfterSystemCall() {
        pausedForCall = false
        if isEnabled {
            startIfNeeded()
        }
    }

    func startIfNeeded() {
        guard isEnabled, !pausedForCall else { return }
        if let player = silentPlayer, player.isPlaying {
            return
        }

        let bgModes = (Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String]) ?? []
        guard bgModes.contains("audio") else { return }

        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
            try session.setActive(true)

            if silentPlayer == nil {
                let wavData = Self.makeOneSecondSilentWav()
                let player = try AVAudioPlayer(data: wavData)
                player.numberOfLoops = -1
                player.volume = 0.0
                player.prepareToPlay()
                silentPlayer = player
            }
            silentPlayer?.play()
        } catch {
            // Non-fatal if another exclusive call is active right now
        }
    }

    func stop() {
        silentPlayer?.stop()
        silentPlayer = nil
    }

    @objc private func handleAudioInterruption(_ notification: Notification) {
        guard let info = notification.userInfo,
              let typeVal = info[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeVal)
        else {
            return
        }

        if type == .ended, isEnabled, !pausedForCall {
            startIfNeeded()
        }
    }

    /// Generates a minimal valid 44-byte header + 8000-sample (1s @ 8kHz, 8-bit mono PCM silence = 0x80) WAV in memory.
    private static func makeOneSecondSilentWav() -> Data {
        let sampleRate: UInt32 = 8000
        let numSamples: UInt32 = 8000
        let dataSize = numSamples
        let riffChunkSize = 36 + dataSize

        var data = Data(capacity: Int(44 + dataSize))
        data.append(contentsOf: "RIFF".utf8)
        data.append(littleEndianBytes(riffChunkSize))
        data.append(contentsOf: "WAVE".utf8)
        data.append(contentsOf: "fmt ".utf8)
        data.append(littleEndianBytes(UInt32(16))) // PCM header size
        data.append(littleEndianBytes(UInt16(1)))  // AudioFormat = 1 (PCM)
        data.append(littleEndianBytes(UInt16(1)))  // NumChannels = 1
        data.append(littleEndianBytes(sampleRate)) // SampleRate = 8000
        data.append(littleEndianBytes(sampleRate)) // ByteRate = 8000
        data.append(littleEndianBytes(UInt16(1)))  // BlockAlign = 1
        data.append(littleEndianBytes(UInt16(8)))  // BitsPerSample = 8
        data.append(contentsOf: "data".utf8)
        data.append(littleEndianBytes(dataSize))
        // Unsigned 8-bit PCM silence center value is 128 (0x80)
        data.append(Data(repeating: 0x80, count: Int(numSamples)))
        return data
    }

    private static func littleEndianBytes<T: FixedWidthInteger>(_ value: T) -> Data {
        var le = value.littleEndian
        return withUnsafeBytes(of: &le) { Data($0) }
    }
}
