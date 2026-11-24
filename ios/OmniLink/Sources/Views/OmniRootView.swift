import SwiftUI

struct OmniRootView: View {
    @EnvironmentObject var store: OmniAppStore

    var body: some View {
        VStack(spacing: 0) {
            if let call = store.activeAndroidCall {
                ActiveAndroidCallBannerView(call: call)
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
            }

            TabView {
                ConnectionDashboardView()
                    .tabItem {
                        Label("互联状态", systemImage: "antenna.radiowaves.left.and.right")
                    }

                AndroidNotificationsView()
                    .tabItem {
                        Label("安卓通知", systemImage: "bell.badge.fill")
                    }

                DiagnosticsAndSimView()
                    .tabItem {
                        Label("联调测试", systemImage: "wrench.and.screwdriver.fill")
                    }

                SettingsAndGuideView()
                    .tabItem {
                        Label("设置与指南", systemImage: "gearshape.fill")
                    }
            }
        }
    }
}

private struct ActiveAndroidCallBannerView: View {
    @EnvironmentObject var store: OmniAppStore
    let call: CallStatePayload

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Image(systemName: "phone.badge.waveform.fill")
                    .foregroundColor(.green)
                Text(call.phase == .RINGING ? "安卓手机正在来电" : "安卓手机通话中")
                    .font(.subheadline.weight(.semibold))
                    .foregroundColor(.green)
                Spacer()
            }

            Text(call.callerName.isEmpty ? "未知来电" : call.callerName)
                .font(.title3.bold())
                .foregroundColor(.white)

            Text(call.phoneNumber)
                .font(.subheadline)
                .foregroundColor(.secondary)

            HStack(spacing: 10) {
                if call.phase == .RINGING {
                    Button {
                        store.sendCallCommandToAndroid(.ANSWER)
                    } label: {
                        Label("接听来电", systemImage: "phone.fill")
                            .font(.subheadline.bold())
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 10)
                            .background(Color.green)
                            .foregroundColor(.white)
                            .cornerRadius(10)
                    }
                } else {
                    Button {
                        store.sendCallCommandToAndroid(.SPEAKER_ON)
                    } label: {
                        Label("开安卓免提", systemImage: "speaker.wave.3.fill")
                            .font(.caption.bold())
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 10)
                            .background(Color.blue)
                            .foregroundColor(.white)
                            .cornerRadius(10)
                    }

                    Button {
                        store.sendCallCommandToAndroid(.SPEAKER_OFF)
                    } label: {
                        Label("关免提", systemImage: "speaker.slash.fill")
                            .font(.caption.bold())
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 10)
                            .background(Color.gray.opacity(0.4))
                            .foregroundColor(.white)
                            .cornerRadius(10)
                    }
                }

                Button {
                    store.sendCallCommandToAndroid(call.phase == .RINGING ? .REJECT : .HANGUP)
                } label: {
                    Label(call.phase == .RINGING ? "拒接" : "挂断", systemImage: "phone.down.fill")
                        .font(.subheadline.bold())
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .background(Color.red)
                        .foregroundColor(.white)
                        .cornerRadius(10)
                }
            }
        }
        .padding(14)
        .background(
            RoundedRectangle(cornerRadius: 16)
                .fill(Color(red: 0.08, green: 0.16, blue: 0.22))
        )
    }
}

private struct ConnectionDashboardView: View {
    @EnvironmentObject var store: OmniAppStore

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack(spacing: 14) {
                        ZStack {
                            Circle()
                                .fill(store.isAnyChannelConnected ? Color.green.opacity(0.2) : Color.orange.opacity(0.2))
                                .frame(width: 48, height: 48)
                            Image(systemName: store.isAnyChannelConnected ? "checkmark.shield.fill" : "dot.radiowaves.left.and.right")
                                .font(.title3)
                                .foregroundColor(store.isAnyChannelConnected ? .green : .orange)
                        }
                        VStack(alignment: .leading, spacing: 4) {
                            Text(store.isAnyChannelConnected ? "双机互联已在线" : "正在搜寻附近的安卓手机...")
                                .font(.headline)
                            Text("蓝牙 BLE + Wi-Fi 局域网双通道自动主备协同")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }
                    .padding(.vertical, 4)
                }

                Section("1. 蓝牙 BLE 与 ANCS 系统通知通道") {
                    HStack {
                        Label("BLE 状态", systemImage: "bolt.horizontal.circle.fill")
                        Spacer()
                        Text(store.blePeerName ?? store.bleStatusText)
                            .foregroundColor(store.isBleConnected ? .green : .secondary)
                            .font(.subheadline)
                    }

                    HStack {
                        Label("iPhone 通知与来电共享 (ANCS)", systemImage: "app.badge.checkmark.fill")
                        Spacer()
                        Text(store.isAncsPairedReady ? "已激活" : "待确认共享")
                            .foregroundColor(store.isAncsPairedReady ? .green : .orange)
                            .font(.subheadline)
                    }

                    Button {
                        store.requestSystemAncsPairing()
                    } label: {
                        Label("触发蓝牙配对与「共享系统通知」弹窗", systemImage: "link.badge.plus")
                    }
                    .disabled(!store.isBleConnected)
                }

                Section("2. Wi-Fi 局域网高速通道") {
                    HStack {
                        Label("Wi-Fi 直连状态", systemImage: "wifi")
                        Spacer()
                        Text(store.isLanConnected ? "已连 \(store.lanPeerLabel ?? "Android")" : "未连接")
                            .foregroundColor(store.isLanConnected ? .green : .secondary)
                            .font(.subheadline)
                    }

                    if !store.discoveredBonjourNames.isEmpty {
                        ForEach(store.discoveredBonjourNames, id: \.self) { name in
                            Button {
                                store.connectBonjourService(name)
                            } label: {
                                HStack {
                                    Image(systemName: "iphone.radiowaves.left.and.right")
                                    Text("自动发现: \(name)")
                                    Spacer()
                                    Text("点击重连")
                                        .font(.caption)
                                        .foregroundColor(.accentColor)
                                }
                            }
                        }
                    }

                    HStack {
                        TextField("输入安卓本机 IP (例如 192.168.1.20)", text: $store.manualAndroidIp)
                            .keyboardType(.numbersAndPunctuation)
                            .autocorrectionDisabled()
                        Button("连接") {
                            store.connectManualIp()
                        }
                        .buttonStyle(.borderedProminent)
                    }
                }

                Section("首次设置重要提示") {
                    Text(
                        "1. 打开安卓端「双机互联」，确保蓝牙广播已启动；\n" +
                        "2. 本机弹出蓝牙配对请求时点击【配对】，随后点击【允许】共享 iPhone 通知；\n" +
                        "3. 若未自动同步 iPhone 通知到安卓，请前往 iPhone「设置 -> 蓝牙 -> 点击已连接的安卓手机右侧 ⓘ -> 开启【共享系统通知】」。"
                    )
                    .font(.footnote)
                    .foregroundColor(.secondary)
                    .lineSpacing(4)
                }
            }
            .navigationTitle("双机互联 OmniLink")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        store.rescanAndReconnect()
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                }
            }
        }
    }
}

private struct AndroidNotificationsView: View {
    @EnvironmentObject var store: OmniAppStore

    private let timeFormatter: DateFormatter = {
        let df = DateFormatter()
        df.dateFormat = "HH:mm:ss"
        return df
    }()

    var body: some View {
        NavigationStack {
            Group {
                if store.notifications.isEmpty {
                    VStack(spacing: 12) {
                        Image(systemName: "bell.slash")
                            .font(.system(size: 42))
                            .foregroundColor(.secondary)
                        Text("暂无来自安卓手机的通知")
                            .font(.headline)
                        Text("连接成功后，安卓手机收到的微信、短信、钉钉等通知将实时弹窗并汇总在此")
                            .font(.footnote)
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 36)
                    }
                } else {
                    List {
                        ForEach(store.notifications) { item in
                            VStack(alignment: .leading, spacing: 6) {
                                HStack {
                                    Text("来自安卓 · \(item.appName)")
                                        .font(.caption.weight(.semibold))
                                        .foregroundColor(.cyan)
                                    Spacer()
                                    Text(
                                        timeFormatter.string(
                                            from: Date(timeIntervalSince1970: TimeInterval(item.timestamp) / 1000.0)
                                        )
                                    )
                                    .font(.caption2)
                                    .foregroundColor(.secondary)
                                }
                                Text(item.title)
                                    .font(.subheadline.bold())
                                if !item.body.isEmpty {
                                    Text(item.body)
                                        .font(.footnote)
                                        .foregroundColor(.secondary)
                                }
                            }
                            .padding(.vertical, 4)
                        }
                    }
                }
            }
            .navigationTitle("安卓通知 (\(store.notifications.count))")
            .toolbar {
                if !store.notifications.isEmpty {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("清空") {
                            store.clearNotifications()
                        }
                    }
                }
            }
        }
    }
}

private struct DiagnosticsAndSimView: View {
    @EnvironmentObject var store: OmniAppStore

    var body: some View {
        NavigationStack {
            List {
                Section("双向链路一键自检（免插卡测试）") {
                    Button {
                        store.sendSimulatedIosNotification()
                    } label: {
                        Label("向安卓发送一条模拟通知弹窗", systemImage: "paperplane.fill")
                    }

                    Button {
                        store.sendSimulatedIosIncomingCall()
                    } label: {
                        Label("向安卓发送模拟来电全屏提醒", systemImage: "phone.arrow.up.right.fill")
                    }
                    .foregroundColor(.green)
                }

                Section("实时通信日志") {
                    if store.logs.isEmpty {
                        Text("暂无日志")
                            .foregroundColor(.secondary)
                    } else {
                        ForEach(store.logs, id: \.self) { line in
                            Text(line)
                                .font(.system(size: 12, design: .monospaced))
                                .foregroundColor(.secondary)
                        }
                    }
                }
            }
            .navigationTitle("联调与日志")
        }
    }
}

private struct SettingsAndGuideView: View {
    @EnvironmentObject var store: OmniAppStore

    var body: some View {
        NavigationStack {
            List {
                Section("来电与通知偏好") {
                    Toggle(isOn: $store.enableCallKitForAndroidCalls) {
                        VStack(alignment: .leading, spacing: 3) {
                            Text("启用 iOS 系统原生电话界面 (LiveCommunicationKit / CallKit)")
                            Text("完美适配国行及全球机型：锁屏黑屏亮屏弹出原生全屏来电，解锁状态显示灵动岛通话条")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }

                    Toggle(isOn: $store.enableBlackScreenKeepAlive) {
                        VStack(alignment: .leading, spacing: 3) {
                            Text("黑屏锁屏秒级唤醒保活")
                            Text("保持无感混音后台会话（不打断音乐、无麦克风黄点），确保锁屏黑屏久置也能秒弹安卓来电")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }

                    Toggle(isOn: $store.showAndroidNotificationBanners) {
                        VStack(alignment: .leading, spacing: 3) {
                            Text("弹出安卓消息系统横幅通知")
                            Text("锁屏、通知中心及 Apple Watch 均可直接查看安卓消息")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }

                    Toggle(isOn: $store.forwardIosCallStateOverLanFallback) {
                        VStack(alignment: .leading, spacing: 3) {
                            Text("Wi-Fi 模式下补充来电状态提醒")
                            Text("当未配对蓝牙 ANCS 时，通过 Wi-Fi 提醒安卓端 iPhone 有来电")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }
                }

                Section("通话声音最佳实践") {
                    Text(
                        "• 推荐搭配支持「双设备连接 (Multipoint)」的蓝牙耳机同时连接两台手机：在任意一台手机上点击接听，耳机都会自动切入通话声音；\n" +
                        "• 未戴耳机时，安卓端开启「远程接听自动打开免提」，在 iPhone 上滑动接听即可直接免提通话。"
                    )
                    .font(.footnote)
                    .foregroundColor(.secondary)
                    .lineSpacing(4)
                }
            }
            .navigationTitle("设置与指南")
        }
    }
}
