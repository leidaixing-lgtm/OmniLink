import Foundation
import Network
import UIKit

final class LanBonjourBridge {
    private var browser: NWBrowser?
    private var connection: NWConnection?
    private var receiveBuffer = Data()
    private var heartbeatTimer: Timer?

    var onConnectionChanged: ((Bool, String?) -> Void)?
    var onDiscoveredServicesChanged: (([String]) -> Void)?
    var onEnvelopeReceived: ((BridgeEnvelope) -> Void)?
    var onLog: ((String) -> Void)?

    private var discoveredEndpoints: [String: NWEndpoint] = [:]

    func start() {
        startBonjourBrowser()
        startHeartbeat()
    }

    func stop() {
        heartbeatTimer?.invalidate()
        heartbeatTimer = nil
        browser?.cancel()
        browser = nil
        connection?.cancel()
        connection = nil
        onConnectionChanged?(false, nil)
    }

    func connectManualIp(_ ip: String, port: UInt16 = OmniConstants.lanTcpPort) {
        let trimmed = ip.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let nwPort = NWEndpoint.Port(rawValue: port) else { return }
        let host = NWEndpoint.Host(trimmed)
        onLog?("正在通过局域网直连安卓 IP \(trimmed):\(port) ...")
        openConnection(to: .hostPort(host: host, port: nwPort), label: trimmed)
    }

    func connectDiscoveredService(named name: String) {
        guard let endpoint = discoveredEndpoints[name] else { return }
        openConnection(to: endpoint, label: name)
    }

    func sendOverLan(_ envelope: BridgeEnvelope) -> Bool {
        guard let conn = connection,
              conn.state == .ready,
              let json = envelope.toJsonString(),
              let lineData = (json + "\n").data(using: .utf8)
        else {
            return false
        }

        conn.send(content: lineData, completion: .contentProcessed { _ in })
        return true
    }

    private func startBonjourBrowser() {
        browser?.cancel()
        let descriptor = NWBrowser.Descriptor.bonjour(
            type: OmniConstants.bonjourServiceType,
            domain: nil
        )
        let params = NWParameters.tcp
        params.includePeerToPeer = true

        let nwBrowser = NWBrowser(for: descriptor, using: params)
        browser = nwBrowser

        nwBrowser.stateUpdateHandler = { [weak self] state in
            DispatchQueue.main.async {
                if case .failed(let error) = state {
                    self?.onLog?("Bonjour 局域网发现受限 (\(error.localizedDescription))，可手动输入安卓 IP 连接")
                }
            }
        }

        nwBrowser.browseResultsChangedHandler = { [weak self] results, _ in
            guard let self = self else { return }
            DispatchQueue.main.async {
                var names: [String] = []
                self.discoveredEndpoints.removeAll()

                for result in results {
                    if case let .service(name, _, _, _) = result.endpoint {
                        names.append(name)
                        self.discoveredEndpoints[name] = result.endpoint
                    }
                }
                self.onDiscoveredServicesChanged?(names)

                // Auto-connect to the first discovered Android OmniLink instance if not connected
                if self.connection == nil || self.connection?.state != .ready,
                   let firstName = names.first,
                   let endpoint = self.discoveredEndpoints[firstName] {
                    self.onLog?("Bonjour 自动发现安卓设备: \(firstName)，正在建立高速通道...")
                    self.openConnection(to: endpoint, label: firstName)
                }
            }
        }

        nwBrowser.start(queue: .main)
    }

    private func openConnection(to endpoint: NWEndpoint, label: String) {
        connection?.cancel()
        receiveBuffer.removeAll()

        let tcpOptions = NWProtocolTCP.Options()
        tcpOptions.noDelay = true
        tcpOptions.enableKeepalive = true
        let params = NWParameters(tls: nil, tcp: tcpOptions)
        params.includePeerToPeer = true

        let conn = NWConnection(to: endpoint, using: params)
        connection = conn

        conn.stateUpdateHandler = { [weak self] state in
            guard let self = self else { return }
            DispatchQueue.main.async {
                switch state {
                case .ready:
                    self.onConnectionChanged?(true, label)
                    self.onLog?("Wi-Fi 局域网高速通道已连接: \(label)")
                    let hello = BridgeEnvelope(
                        type: .HELLO,
                        deviceName: UIDevice.current.name,
                        platform: "ios"
                    )
                    _ = self.sendOverLan(hello)
                    self.receiveNextChunk(on: conn, label: label)

                case .failed(let err):
                    self.onLog?("Wi-Fi 连接断开: \(err.localizedDescription)")
                    self.onConnectionChanged?(false, nil)

                case .cancelled:
                    self.onConnectionChanged?(false, nil)

                default:
                    break
                }
            }
        }

        conn.start(queue: .main)
    }

    private func receiveNextChunk(on conn: NWConnection, label: String) {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 16_384) { [weak self] content, _, isComplete, error in
            guard let self = self else { return }
            DispatchQueue.main.async {
                if let data = content, !data.isEmpty {
                    self.receiveBuffer.append(data)
                    self.processLines()
                }
                if isComplete || error != nil {
                    self.onConnectionChanged?(false, nil)
                    conn.cancel()
                    if self.connection === conn {
                        self.connection = nil
                    }
                    return
                }
                self.receiveNextChunk(on: conn, label: label)
            }
        }
    }

    private func processLines() {
        let newline = UInt8(ascii: "\n")
        while let newlineIndex = receiveBuffer.firstIndex(of: newline) {
            let lineData = receiveBuffer.subdata(in: 0..<newlineIndex)
            receiveBuffer.removeSubrange(0...newlineIndex)
            if let line = String(data: lineData, encoding: .utf8)?
                .trimmingCharacters(in: .whitespacesAndNewlines),
               !line.isEmpty,
               let env = BridgeEnvelope.fromJsonString(line) {
                onEnvelopeReceived?(env)
            }
        }
    }

    private func startHeartbeat() {
        heartbeatTimer?.invalidate()
        heartbeatTimer = Timer.scheduledTimer(withTimeInterval: 15.0, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            let ping = BridgeEnvelope(
                type: .PING,
                deviceName: UIDevice.current.name,
                platform: "ios"
            )
            _ = self.sendOverLan(ping)
        }
    }
}
