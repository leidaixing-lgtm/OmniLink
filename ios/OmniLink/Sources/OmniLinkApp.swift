import SwiftUI

@main
struct OmniLinkApp: App {
    @StateObject private var store = OmniAppStore()

    var body: some Scene {
        WindowGroup {
            OmniRootView()
                .environmentObject(store)
                .preferredColorScheme(.dark)
                .onAppear {
                    store.startAll()
                }
        }
    }
}
