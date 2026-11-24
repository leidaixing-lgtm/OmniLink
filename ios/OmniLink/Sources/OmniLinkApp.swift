import SwiftUI

@main
struct OmniLinkApp: App {
    @StateObject private var store = OmniAppStore()

    var body: some Scene {
        WindowGroup {
            OmniRootView()
                .environmentObject(store)
                .preferredColorScheme(.dark)
                .task {
                    // Allow UIKit/SwiftUI scene to finish initial layout before requesting TCC Bluetooth/Network permissions
                    try? await Task.sleep(nanoseconds: 250_000_000)
                    store.startAll()
                }
        }
    }
}
