# Repository Guidelines

## Project Structure & Module Organization
This repository contains an iOS SwiftUI app in `iOS/Eneo tal-till-text/`. App entry points are `EneoTalTillTextApp.swift` and `ContentView.swift`. Domain data lives in `Models/`, service logic in `Services/`, and SwiftUI screens/components in `Views/`. App icons, colors, and bundled resources live in `Assets.xcassets`.

The checkout also includes package dependency sources such as `argmax-oss-swift/` and `swift-argument-parser/`. Treat these as external dependencies unless a task explicitly targets them.

## Build, Test, and Development Commands
Prefer building from Xcode with the active app scheme.

- `xcodebuild -scheme "Eneo tal-till-text" build` builds the app from the command line when the scheme is available.
- `xcodebuild -scheme "Eneo tal-till-text" test` runs tests if a test target is added.
- In Xcode, use Product > Build for the most reliable local validation.

The app depends on microphone access and Eneo API settings, so full end-to-end testing may require a physical device, VPN access, and a valid API key.

## Coding Style & Naming Conventions
Use SwiftUI-native patterns and Apple platform conventions. Indent with 4 spaces. Use `PascalCase` for types and `camelCase` for properties, methods, and local variables.

Keep SwiftUI views small and move business logic into `Services/`. Use `@Observable` services and async/await APIs where possible. Prefer system views, dynamic type styles, system colors, and SF Symbols.

## Testing Guidelines
There is currently no dedicated app test target in the main app. Add focused tests when introducing parsing, persistence, upload, or formatting logic. Use Swift Testing for unit tests and XCTest UI automation for user flows.

Name tests after behavior, for example `testUploadParsesCompletedRunOutput()`.

## Commit & Pull Request Guidelines
Git history currently only shows `Initial Commit`, so no project-specific commit convention is established. Use short imperative commit messages, for example `Add Eneo app picker`.

Pull requests should include a concise summary, testing notes, screenshots for UI changes, and any required Eneo/API configuration details. Do not include real API keys or credentials in commits, logs, screenshots, or PR text.

## Security & Configuration Tips
Store Eneo API keys only through `APISettingsStore` and the iOS Keychain. Do not hard-code secrets. Keep debug output useful but avoid exposing credentials.
