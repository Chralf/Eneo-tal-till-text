import SwiftUI

struct ContentView: View {
    @State private var showOnboarding = OnboardingStore.shouldShowOnboarding

    var body: some View {
        Group {
            if showOnboarding {
                OnboardingView {
                    showOnboarding = false
                }
            } else {
                RecordingListView()
            }
        }
        .onAppear {
            OnboardingStore.migrateExistingUsersIfNeeded()
            showOnboarding = OnboardingStore.shouldShowOnboarding
        }
    }
}
