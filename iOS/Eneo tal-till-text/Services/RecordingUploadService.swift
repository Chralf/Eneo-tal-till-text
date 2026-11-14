import Foundation
import Observation
#if canImport(UIKit)
import UIKit
#endif

struct APISettings {
    static let baseURLStorageKey = "eneoBaseURLString"
    static let appIDStorageKey = "eneoAppID"
    /// No app is chosen until the user picks one; every Eneo installation has
    /// its own app IDs, so a built-in default would only fit one organisation.
    static let defaultAppID = ""
    static let defaultAppNameStorageKey = "eneoAppName"
    static let defaultAppSpaceNameStorageKey = "eneoAppSpaceName"
    /// When on, a finished recording is sent to Eneo as soon as it opens.
    static let autoConvertAfterRecordingStorageKey = "autoConvertAfterRecording"
    /// No server is assumed either; onboarding asks for it.
    static let defaultBaseURLString = ""

    /// The chosen default app, or nil when the user has not picked one yet.
    static var storedDefaultAppID: String? {
        let value = (UserDefaults.standard.string(forKey: appIDStorageKey) ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }

    let baseURL: URL
    let appID: String
    let accessToken: String

    static func load() throws -> APISettings {
        let session = try loadSession()

        guard let appID = storedDefaultAppID else {
            throw RecordingUploadError.missingAppID
        }

        return APISettings(baseURL: session.baseURL, appID: appID, accessToken: session.accessToken)
    }

    /// Base URL + token for authenticated calls that do not need an app yet
    /// (listing spaces/apps). Uploads still use `load()`.
    static func loadSession() throws -> APISettings {
        let baseURL = try loadBaseURL()

        let accessToken = AuthTokenStore.loadAccessToken().trimmingCharacters(in: .whitespacesAndNewlines)
        guard !accessToken.isEmpty else {
            throw RecordingUploadError.missingAccessToken
        }

        return APISettings(baseURL: baseURL, appID: storedDefaultAppID ?? "", accessToken: accessToken)
    }

    static func loadBaseURL() throws -> URL {
        let storedValue = (UserDefaults.standard.string(forKey: baseURLStorageKey) ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !storedValue.isEmpty else {
            throw RecordingUploadError.missingServerURL
        }
        guard let baseURL = makeBaseURL(from: storedValue) else {
            throw RecordingUploadError.missingAPISettings
        }

        return baseURL
    }

    static func makeBaseURL(from value: String) -> URL? {
        let normalizedBaseURLString = normalizedBaseURLString(value)
        guard !normalizedBaseURLString.isEmpty,
              let baseURL = URL(string: normalizedBaseURLString),
              let scheme = baseURL.scheme?.lowercased(),
              scheme == "https",
              baseURL.host?.isEmpty == false
        else {
            return nil
        }
        return baseURL
    }

    static func normalizedBaseURLString(_ value: String) -> String {
        var trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "" }

        // Always use HTTPS — the user should not have to type a scheme.
        if trimmed.lowercased().hasPrefix("http://") {
            trimmed = "https://" + trimmed.dropFirst("http://".count)
        } else if !trimmed.contains("://") {
            trimmed = "https://\(trimmed)"
        }

        while trimmed.hasSuffix("/") {
            trimmed.removeLast()
        }

        return trimmed
    }

    /// Host (and optional path) for editing fields — without the https:// prefix.
    static func displayBaseURLString(_ value: String) -> String {
        var trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.lowercased().hasPrefix("https://") {
            trimmed = String(trimmed.dropFirst("https://".count))
        } else if trimmed.lowercased().hasPrefix("http://") {
            trimmed = String(trimmed.dropFirst("http://".count))
        }
        while trimmed.hasSuffix("/") {
            trimmed.removeLast()
        }
        return trimmed
    }

    static func storedDefaultAppDisplayName() -> String? {
        let name = (UserDefaults.standard.string(forKey: defaultAppNameStorageKey) ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { return nil }

        let spaceName = (UserDefaults.standard.string(forKey: defaultAppSpaceNameStorageKey) ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return spaceName.isEmpty ? name : "[\(spaceName)] \(name)"
    }

    static func persistDefaultAppDisplayName(from app: EneoApp) {
        guard app.id == storedDefaultAppID else { return }
        UserDefaults.standard.set(app.name, forKey: defaultAppNameStorageKey)
        UserDefaults.standard.set(app.spaceName, forKey: defaultAppSpaceNameStorageKey)
    }
}

struct RecordingUploadResponse: Decodable {
    let id: String?
    let fileID: String?
    let status: String?
    let createdAt: String?
    let output: String?
}

struct EneoApp: Identifiable, Hashable, Sendable {
    let id: String
    let name: String
    let spaceID: String
    let spaceName: String

    var displayName: String {
        spaceName.isEmpty ? name : "[\(spaceName)] \(name)"
    }
}

private struct EneoSpace: Sendable {
    let id: String
    let name: String
}

struct RecordingResultMetadata {
    let suggestedTitle: String?
    let shortSummary: String?
}

private struct StaleRunError: Error {}

/// Whether the Eneo host answered the latest probe. Being unreachable is the
/// normal state outside the organisation's network, not an error.
enum ServerReachability {
    case unknown
    case reachable
    case unreachable
}

@Observable
@MainActor
final class RecordingUploadService {
    private static let pollIntervalSeconds: UInt64 = 10
    private static let maxPollAttempts = 1_080 // 3 hours at 10 second intervals.

    var isUploading = false
    var statusText: String?
    var uploadingRecordingID: UUID?
    /// Updated by every probe, so all screens share one answer.
    var serverReachability: ServerReachability = .unknown
    var isCheckingReachability = false

    private var inFlightUpload: Task<Void, Never>?

    private let urlSession: URLSession

    /// Tests inject a session backed by a stub URLProtocol.
    init(urlSession: URLSession = RecordingUploadService.makeDefaultSession()) {
        self.urlSession = urlSession
    }

    nonisolated static func makeDefaultSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 30 * 60
        configuration.timeoutIntervalForResource = 4 * 60 * 60
        configuration.waitsForConnectivity = true
        return URLSession(configuration: configuration)
    }

    func isUploading(_ recording: Recording) -> Bool {
        isUploading && uploadingRecordingID == recording.id
    }

    /// Returns a task that finishes once the upload and its cleanup are done,
    /// so a caller can run uploads one after another.
    @discardableResult
    func performUpload(recordingID: UUID, operation: @escaping @MainActor () async -> Void) -> Task<Void, Never> {
        inFlightUpload?.cancel()
        uploadingRecordingID = recordingID
        // Busy from the first moment so a second tap cannot start a parallel
        // upload while e.g. the notification permission prompt is showing.
        isUploading = true

        // Without this the upload and the polling loop are suspended the
        // moment the user locks the phone. The extra time (about 30 s) lets
        // short conversions finish and post their notification; longer ones
        // simply resume when the app is next in the foreground.
        #if canImport(UIKit)
        var backgroundTask: UIBackgroundTaskIdentifier = .invalid
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "UploadRecording") {
            UIApplication.shared.endBackgroundTask(backgroundTask)
            backgroundTask = .invalid
        }
        #endif

        let task = Task { @MainActor in
            await operation()
        }
        inFlightUpload = task
        return Task { @MainActor in
            await task.value
            #if canImport(UIKit)
            if backgroundTask != .invalid {
                UIApplication.shared.endBackgroundTask(backgroundTask)
                backgroundTask = .invalid
            }
            #endif
            // Only the task that is still current may clear the shared state;
            // a superseded task must not drop the reference to its successor.
            guard inFlightUpload == task else { return }
            inFlightUpload = nil
            isUploading = false
            statusText = nil
            uploadingRecordingID = nil
        }
    }

    func cancelInFlightUpload() {
        inFlightUpload?.cancel()
        inFlightUpload = nil
        isUploading = false
        statusText = nil
        uploadingRecordingID = nil
    }

    /// Lightweight reachability: any HTTP response means the host is reachable
    /// (even 401/404). Connection failures surface as network guidance.
    func probeServerReachability() async throws {
        do {
            try await probe()
            serverReachability = .reachable
        } catch {
            serverReachability = .unreachable
            throw error
        }
    }

    /// Probe without throwing, for status displays.
    func refreshServerReachability() async {
        isCheckingReachability = true
        defer { isCheckingReachability = false }
        try? await probeServerReachability()
    }

    private func probe() async throws {
        do {
            try await probeOnce()
        } catch let error as URLError where Self.isTransientConnectionError(error) {
            // An on-demand VPN starts its tunnel on the first request to the
            // host; that request can fail before the tunnel is up.
            try await Task.sleep(for: .seconds(2))
            try await probeOnce()
        }
    }

    nonisolated private static func isTransientConnectionError(_ error: URLError) -> Bool {
        [.cannotConnectToHost, .networkConnectionLost, .notConnectedToInternet, .cannotFindHost]
            .contains(error.code)
    }

    private func probeOnce() async throws {
        let baseURL = try APISettings.loadBaseURL()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 12
        configuration.timeoutIntervalForResource = 12
        configuration.waitsForConnectivity = false
        let session = URLSession(configuration: configuration)
        // A session is never released until invalidated; without this every
        // probe would leak one.
        defer { session.finishTasksAndInvalidate() }
        var request = URLRequest(url: baseURL)
        request.httpMethod = "GET"
        request.timeoutInterval = 12
        let (_, response) = try await session.data(for: request)
        guard response is HTTPURLResponse else {
            throw URLError(.badServerResponse)
        }
    }

    func fetchPersonalSpaceId() async -> String? {
        do {
            let settings = try APISettings.loadSession()
            let spacesEndpoint = try Self.makeURL(baseURL: settings.baseURL, path: "/api/v1/spaces/type/personal/")
            var spacesRequest = URLRequest(url: spacesEndpoint)
            spacesRequest.httpMethod = "GET"
            Self.authorize(&spacesRequest, accessToken: settings.accessToken)
            let (spacesData, spacesResponse) = try await urlSession.data(for: spacesRequest)
            try Self.validate(response: spacesResponse, data: spacesData, operation: "Hämta spaces", url: spacesEndpoint, method: "GET")
            return Self.parseSpaces(from: spacesData).first?.id
        } catch {
            return nil
        }
    }

    func fetchApps() async throws -> [EneoApp] {
        let settings = try APISettings.loadSession()
        let spaces = try await fetchSpaces(settings: settings)
        guard !spaces.isEmpty else {
            throw RecordingUploadError.noSpacesFound
        }

        var audioApps: [EneoApp] = []
        try await withThrowingTaskGroup(of: [EneoApp].self) { group in
            for space in spaces {
                let baseURL = settings.baseURL
                let accessToken = settings.accessToken
                let urlSession = urlSession
                group.addTask {
                    let appsEndpoint = try Self.makeURL(baseURL: baseURL, path: "/api/v1/spaces/\(space.id)/applications/")
                    var appsRequest = URLRequest(url: appsEndpoint)
                    appsRequest.httpMethod = "GET"
                    Self.authorize(&appsRequest, accessToken: accessToken)

                    let (appsData, appsResponse) = try await urlSession.data(for: appsRequest)
                    try Self.validate(response: appsResponse, data: appsData, operation: "Hämta appar i \(space.name)", url: appsEndpoint, method: "GET")
                    let listedApps = Self.parseApps(from: appsData, space: space)

                    var audioApps: [EneoApp] = []
                    try await withThrowingTaskGroup(of: EneoApp?.self) { appGroup in
                        for app in listedApps {
                            appGroup.addTask {
                                let appEndpoint = try Self.makeURL(baseURL: baseURL, path: "/api/v1/apps/\(app.id)/")
                                var appRequest = URLRequest(url: appEndpoint)
                                appRequest.httpMethod = "GET"
                                Self.authorize(&appRequest, accessToken: accessToken)

                                // One app the account may not read (e.g. 403)
                                // must not hide every other app. Only an
                                // expired sign-in still aborts the list.
                                do {
                                    let (appData, appResponse) = try await urlSession.data(for: appRequest)
                                    try Self.validate(response: appResponse, data: appData, operation: "Hämta app \(app.name)", url: appEndpoint, method: "GET")
                                    return Self.acceptsAudioInput(in: appData) ? app : nil
                                } catch RecordingUploadError.unauthorized(let operation, let url) {
                                    throw RecordingUploadError.unauthorized(operation: operation, url: url)
                                } catch {
                                    try Task.checkCancellation()
                                    return nil
                                }
                            }
                        }

                        for try await app in appGroup {
                            if let app {
                                audioApps.append(app)
                            }
                        }
                    }

                    return audioApps
                }
            }

            for try await spaceApps in group {
                audioApps.append(contentsOf: spaceApps)
            }
        }

        guard !audioApps.isEmpty else {
            throw RecordingUploadError.noAppsFound
        }

        return audioApps.sorted {
            let spaceComparison = $0.spaceName.localizedStandardCompare($1.spaceName)
            if spaceComparison != .orderedSame {
                return spaceComparison == .orderedAscending
            }
            return $0.name.localizedStandardCompare($1.name) == .orderedAscending
        }
    }

    private func fetchSpaces(settings: APISettings) async throws -> [EneoSpace] {
        async let fromList = fetchSpacesFromList(settings: settings)
        let personal = await fetchPersonalSpace(settings: settings)
        let list = try await fromList
        var seen = Set<String>()
        return (list + personal).filter { seen.insert($0.id).inserted }
    }

    private func fetchSpacesFromList(settings: APISettings) async throws -> [EneoSpace] {
        let spacesEndpoint = try Self.makeURL(
            baseURL: settings.baseURL,
            path: "/api/v1/spaces/",
            queryItems: [URLQueryItem(name: "include_personal", value: "true")]
        )

        var spacesRequest = URLRequest(url: spacesEndpoint)
        spacesRequest.httpMethod = "GET"
        Self.authorize(&spacesRequest, accessToken: settings.accessToken)

        let (spacesData, spacesResponse) = try await urlSession.data(for: spacesRequest)
        try Self.validate(response: spacesResponse, data: spacesData, operation: "Hämta spaces", url: spacesEndpoint, method: "GET")

        return Self.parseSpaces(from: spacesData)
    }

    private func fetchPersonalSpace(settings: APISettings) async -> [EneoSpace] {
        do {
            let spacesEndpoint = try Self.makeURL(baseURL: settings.baseURL, path: "/api/v1/spaces/type/personal/")

            var spacesRequest = URLRequest(url: spacesEndpoint)
            spacesRequest.httpMethod = "GET"
            Self.authorize(&spacesRequest, accessToken: settings.accessToken)

            let (spacesData, spacesResponse) = try await urlSession.data(for: spacesRequest)
            try Self.validate(response: spacesResponse, data: spacesData, operation: "Hämta spaces", url: spacesEndpoint, method: "GET")

            return Self.parseSpaces(from: spacesData)
        } catch {
            return []
        }
    }

    func upload(
        _ recording: Recording,
        appID selectedAppID: String? = nil,
        onCheckpoint: ((String?, String?) async -> Void)? = nil
    ) async throws -> RecordingUploadResponse {
        guard recording.audioFileExists else {
            throw RecordingUploadError.audioFileMissing
        }

        // Busy state is owned by performUpload. Clearing it here would let a
        // cancelled upload, unwinding late, mark its successor as finished.
        statusText = String(localized: "Laddar upp ljud...", comment: "Status medan ljudfilen skickas")

        let loadedSettings = try APISettings.load()
        let trimmedSelectedAppID = selectedAppID?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let appID = trimmedSelectedAppID.isEmpty ? loadedSettings.appID : trimmedSelectedAppID
        let settings = APISettings(baseURL: loadedSettings.baseURL, appID: appID, accessToken: loadedSettings.accessToken)

        var fileID = recording.uploadFileID?.trimmingCharacters(in: .whitespacesAndNewlines)
        if fileID?.isEmpty != false { fileID = nil }
        var runID = recording.uploadServerID?.trimmingCharacters(in: .whitespacesAndNewlines)
        if runID?.isEmpty != false { runID = nil }

        let isResumingRun = runID != nil
        if runID == nil {
            if fileID == nil {
                fileID = try await uploadAudioFile(recording, settings: settings)
                await onCheckpoint?(fileID, nil)
            } else {
                statusText = Self.transcribingStatus
            }
            runID = try await startRun(fileID: fileID!, settings: settings)
            await onCheckpoint?(fileID, runID)
        } else {
            statusText = Self.transcribingStatus
        }

        let completedRun: RunResult
        do {
            completedRun = try await pollRun(runID: runID!, settings: settings)
        } catch is StaleRunError {
            if fileID == nil {
                fileID = try await uploadAudioFile(recording, settings: settings)
                await onCheckpoint?(fileID, nil)
            }
            runID = try await startRun(fileID: fileID!, settings: settings)
            await onCheckpoint?(fileID, runID)
            completedRun = try await pollRun(runID: runID!, settings: settings)
        } catch RecordingUploadError.runFailed where isResumingRun {
            // A saved run that already failed on the server will never succeed,
            // so resuming it would fail on every retry. Upload the file again
            // and start a fresh run instead.
            statusText = String(localized: "Laddar upp ljud...", comment: "Status medan ljudfilen skickas")
            fileID = try await uploadAudioFile(recording, settings: settings)
            await onCheckpoint?(fileID, nil)
            runID = try await startRun(fileID: fileID!, settings: settings)
            await onCheckpoint?(fileID, runID)
            completedRun = try await pollRun(runID: runID!, settings: settings)
        }

        return RecordingUploadResponse(
            id: completedRun.id ?? runID,
            fileID: fileID,
            status: completedRun.status,
            createdAt: completedRun.createdAt,
            output: completedRun.output
        )
    }

    private func uploadAudioFile(_ recording: Recording, settings: APISettings) async throws -> String {
        let endpoint = try Self.makeURL(baseURL: settings.baseURL, path: "/api/v1/files/")
        let boundary = "Boundary-\(UUID().uuidString)"
        let audioFileURL = recording.audioFileURL
        let audioFileName = recording.audioFileName
        let mimeType = Self.mimeType(for: audioFileURL)
        let bodyURL = try await Task.detached {
            try Self.makeMultipartBody(
                audioFileURL: audioFileURL,
                audioFileName: audioFileName,
                mimeType: mimeType,
                fieldName: "upload_file",
                boundary: boundary
            )
        }.value
        defer { try? FileManager.default.removeItem(at: bodyURL) }

        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        Self.authorize(&request, accessToken: settings.accessToken)
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")

        let (data, response) = try await urlSession.upload(for: request, fromFile: bodyURL)
        try Self.validate(response: response, data: data, operation: "Filuppladdning", url: endpoint)

        guard let fileID = Self.stringValue(for: "id", in: data), !fileID.isEmpty else {
            throw RecordingUploadError.missingResponseValue(operation: "Filuppladdning", key: "id")
        }

        return fileID
    }

    private func startRun(fileID: String, settings: APISettings) async throws -> String {
        let endpoint = try Self.makeURL(baseURL: settings.baseURL, path: "/api/v1/apps/\(settings.appID)/runs/")
        let payload = RunRequest(files: [RunFile(id: fileID)])

        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        Self.authorize(&request, accessToken: settings.accessToken)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(payload)

        let (data, response) = try await urlSession.data(for: request)
        try Self.validate(response: response, data: data, operation: "Starta appkörning", url: endpoint)

        guard let runID = Self.stringValue(for: "id", in: data), !runID.isEmpty else {
            throw RecordingUploadError.missingResponseValue(operation: "Starta appkörning", key: "id")
        }

        return runID
    }

    private func pollRun(runID: String, settings: APISettings) async throws -> RunResult {
        let endpoint = try Self.makeURL(baseURL: settings.baseURL, path: "/api/v1/app-runs/\(runID)/")
        var lastStatus = "queued"

        for _ in 0..<Self.maxPollAttempts {
            try Task.checkCancellation()
            var request = URLRequest(url: endpoint)
            request.httpMethod = "GET"
            Self.authorize(&request, accessToken: settings.accessToken)

            let (data, response) = try await urlSession.data(for: request)
            if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 404 {
                throw StaleRunError()
            }
            try Self.validate(response: response, data: data, operation: "Hämta appkörning", url: endpoint)

            let result = parseRunResult(from: data)
            lastStatus = normalizedStatus(result.status ?? lastStatus)
            statusText = statusText(for: lastStatus)

            if Self.isCompletedStatus(lastStatus) || (result.hasOutput && result.status == nil) {
                guard result.hasOutput else {
                    throw RecordingUploadError.runFailed(
                        status: lastStatus,
                        message: String(localized: "Eneo skickade tillbaka ett tomt resultat.", comment: "Fel när körningen är klar utan text")
                    )
                }
                statusText = Self.downloadingStatus
                return result
            }

            if Self.isFailedStatus(lastStatus) {
                throw RecordingUploadError.runFailed(status: lastStatus, message: result.failureMessage)
            }

            try await Task.sleep(for: .seconds(Self.pollIntervalSeconds))
        }

        throw RecordingUploadError.runTimedOut(status: lastStatus)
    }

    private static var transcribingStatus: String {
        String(localized: "Transkriberar...", comment: "Status medan Eneo omvandlar tal till text")
    }

    private static var downloadingStatus: String {
        String(localized: "Hämtar resultat...", comment: "Status medan resultatet hämtas hem")
    }

    private func statusText(for status: String) -> String {
        switch status {
        case "queued", "pending", "created":
            return Self.transcribingStatus
        case "running", "processing", "in_progress", "in progress", "started":
            return String(localized: "Sammanfattar med AI...", comment: "Status medan Eneo sammanfattar texten")
        case "complete", "completed", "success", "succeeded", "done", "finished":
            return Self.downloadingStatus
        default:
            return String(localized: "Arbetar...", comment: "Status för okänt läge i Eneo-körningen")
        }
    }

    private func normalizedStatus(_ status: String) -> String {
        status.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    nonisolated private static func isCompletedStatus(_ status: String) -> Bool {
        ["complete", "completed", "success", "succeeded", "done", "finished"].contains(status)
    }

    nonisolated private static func isFailedStatus(_ status: String) -> Bool {
        ["failed", "failure", "error", "cancelled", "canceled"].contains(status)
    }

    func metadata(from markdown: String) -> RecordingResultMetadata {
        let lines = markdown
            .replacingOccurrences(of: "\r\n", with: "\n")
            .components(separatedBy: "\n")
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }

        let title = explicitValue(in: lines, labels: ["Titel", "Rubrik"])
            ?? firstHeading(in: lines)
        let summary = explicitValue(in: lines, labels: ["Sammanfattning", "Kort sammanfattning", "Beskrivning"])
            ?? firstParagraph(in: lines, excluding: title)

        return RecordingResultMetadata(
            suggestedTitle: title.map { trimmedSentence($0, maxLength: 60) },
            shortSummary: summary.map { trimmedSentence($0, maxLength: 110) }
        )
    }

    nonisolated private static func validate(response: URLResponse, data: Data, operation: String, url: URL, method: String = "POST") throws {
        guard let httpResponse = response as? HTTPURLResponse else {
            throw RecordingUploadError.invalidResponse
        }

        guard 200..<300 ~= httpResponse.statusCode else {
            // 401 has its own recovery path, so it must not collapse into a generic server error.
            if httpResponse.statusCode == 401 {
                throw RecordingUploadError.unauthorized(operation: operation, url: url.absoluteString)
            }

            throw RecordingUploadError.serverError(
                operation: operation,
                statusCode: httpResponse.statusCode,
                url: url.absoluteString,
                method: method,
                authDescription: "Bearer-token skickad",
                message: Self.makeServerMessage(from: data)
            )
        }
    }

    nonisolated private static func makeURL(
        baseURL: URL,
        path: String,
        queryItems: [URLQueryItem]? = nil
    ) throws -> URL {
        guard var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false) else {
            throw RecordingUploadError.missingAPISettings
        }

        let basePath = components.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let appendedPath = path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let fullPath = [basePath, appendedPath]
            .filter { !$0.isEmpty }
            .joined(separator: "/")
        let trailingSlash = path.hasSuffix("/") ? "/" : ""
        components.path = "/\(fullPath)\(trailingSlash)"
        if let queryItems, !queryItems.isEmpty {
            components.queryItems = queryItems
        }

        guard let url = components.url else {
            throw RecordingUploadError.missingAPISettings
        }

        return url
    }

    nonisolated private static func authorize(_ request: inout URLRequest, accessToken: String) {
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
    }

    nonisolated private static func makeMultipartBody(
        audioFileURL: URL,
        audioFileName: String,
        mimeType: String,
        fieldName: String,
        boundary: String
    ) throws -> URL {
        let bodyURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("EneoTalTillTextUpload_\(UUID().uuidString).body")
        FileManager.default.createFile(atPath: bodyURL.path, contents: nil)
        Recording.protectFile(at: bodyURL)

        let handle = try FileHandle(forWritingTo: bodyURL)
        defer { try? handle.close() }

        try Self.appendFile(
            name: fieldName,
            fileName: audioFileName,
            mimeType: mimeType,
            fileURL: audioFileURL,
            boundary: boundary,
            to: handle
        )
        try Self.appendString("--\(boundary)--\r\n", to: handle)

        return bodyURL
    }

    nonisolated private static func appendFile(
        name: String,
        fileName: String,
        mimeType: String,
        fileURL: URL,
        boundary: String,
        to handle: FileHandle
    ) throws {
        try Self.appendString("--\(boundary)\r\n", to: handle)
        try Self.appendString("Content-Disposition: form-data; name=\"\(name)\"; filename=\"\(fileName)\"\r\n", to: handle)
        try Self.appendString("Content-Type: \(mimeType)\r\n\r\n", to: handle)

        let fileHandle = try FileHandle(forReadingFrom: fileURL)
        defer { try? fileHandle.close() }

        while true {
            let data = fileHandle.readData(ofLength: 1024 * 1024)
            if data.isEmpty { break }
            try handle.write(contentsOf: data)
        }

        try Self.appendString("\r\n", to: handle)
    }

    nonisolated private static func appendString(_ string: String, to handle: FileHandle) throws {
        guard let data = string.data(using: .utf8) else { return }
        try handle.write(contentsOf: data)
    }

    nonisolated private static func mimeType(for fileURL: URL) -> String {
        switch fileURL.pathExtension.lowercased() {
        case "m4a": return "audio/x-m4a"
        case "mp3": return "audio/mpeg"
        case "wav": return "audio/wav"
        case "aac": return "audio/aac"
        case "caf": return "audio/x-caf"
        case "flac": return "audio/flac"
        default: return "application/octet-stream"
        }
    }

    nonisolated private static func makeServerMessage(from data: Data) -> String? {
        guard !data.isEmpty else { return nil }

        if let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            if let detail = json["detail"] as? String {
                return detail
            }
            if let message = json["message"] as? String {
                return message
            }
            if let error = json["error"] as? String {
                return error
            }
            if let output = json["output"] {
                return String(describing: output)
            }
        }

        return String(data: data, encoding: .utf8)
    }

    nonisolated private static func stringValue(for key: String, in data: Data) -> String? {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        return json[key] as? String
    }

    nonisolated private static func parseSpaces(from data: Data) -> [EneoSpace] {
        guard let json = try? JSONSerialization.jsonObject(with: data) else { return [] }
        let rawItems: [Any]
        if let array = json as? [Any] {
            rawItems = array
        } else if let dictionary = json as? [String: Any] {
            if dictionary["id"] is String {
                rawItems = [dictionary]
            } else {
                rawItems = (dictionary["items"] as? [Any])
                    ?? (dictionary["data"] as? [Any])
                    ?? (dictionary["results"] as? [Any])
                    ?? []
            }
        } else {
            rawItems = []
        }

        return rawItems.compactMap { item in
            guard let dictionary = item as? [String: Any],
                  let id = dictionary["id"] as? String
            else { return nil }

            let name = dictionary["name"] as? String
                ?? dictionary["title"] as? String
                ?? dictionary["display_name"] as? String
                ?? id
            return EneoSpace(id: id, name: name)
        }
    }

    nonisolated private static func parseApps(from data: Data, space: EneoSpace) -> [EneoApp] {
        guard let json = try? JSONSerialization.jsonObject(with: data) else { return [] }
        let rawItems: [Any]
        if let dictionary = json as? [String: Any],
           let apps = dictionary["apps"] as? [String: Any] {
            rawItems = apps["items"] as? [Any] ?? []
        } else if let dictionary = json as? [String: Any] {
            rawItems = (dictionary["items"] as? [Any])
                ?? (dictionary["data"] as? [Any])
                ?? (dictionary["results"] as? [Any])
                ?? []
        } else if let array = json as? [Any] {
            rawItems = array
        } else {
            rawItems = []
        }

        return rawItems.compactMap { item in
            guard let dictionary = item as? [String: Any],
                  let id = dictionary["id"] as? String
            else { return nil }

            let name = dictionary["name"] as? String
                ?? dictionary["title"] as? String
                ?? dictionary["display_name"] as? String
                ?? id
            return EneoApp(id: id, name: name, spaceID: space.id, spaceName: space.name)
        }
    }

    nonisolated private static func acceptsAudioInput(in data: Data) -> Bool {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let inputFields = json["input_fields"] as? [[String: Any]]
        else {
            return false
        }

        return inputFields.contains { field in
            guard let type = field["type"] as? String else { return false }
            return type == "audio-upload" || type == "audio-recorder"
        }
    }

    private func explicitValue(in lines: [String], labels: [String]) -> String? {
        for line in lines {
            let cleaned = line
                .replacingOccurrences(of: "**", with: "")
                .replacingOccurrences(of: "#", with: "")
                .trimmingCharacters(in: .whitespacesAndNewlines)

            for label in labels {
                let prefixes = ["\(label):", "\(label) -", "\(label) –"]
                if let prefix = prefixes.first(where: { cleaned.lowercased().hasPrefix($0.lowercased()) }) {
                    let value = cleaned.dropFirst(prefix.count).trimmingCharacters(in: .whitespacesAndNewlines)
                    if !value.isEmpty { return String(value) }
                }
            }
        }
        return nil
    }

    private func firstHeading(in lines: [String]) -> String? {
        lines.first { $0.hasPrefix("#") }?
            .trimmingCharacters(in: CharacterSet(charactersIn: "# "))
    }

    private func firstParagraph(in lines: [String], excluding title: String?) -> String? {
        lines.first { line in
            !line.hasPrefix("#")
                && !line.hasPrefix("- ")
                && !line.hasPrefix("* ")
                && numberedPrefix(in: line) == nil
                && line != title
        }
    }

    private func numberedPrefix(in line: String) -> String? {
        guard let dotIndex = line.firstIndex(of: "."),
              Int(line[..<dotIndex]) != nil
        else { return nil }
        return String(line[...dotIndex])
    }

    private func trimmedSentence(_ text: String, maxLength: Int) -> String {
        let cleaned = text
            .replacingOccurrences(of: "**", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard cleaned.count > maxLength else { return cleaned }
        let end = cleaned.index(cleaned.startIndex, offsetBy: maxLength)
        return String(cleaned[..<end]).trimmingCharacters(in: .whitespacesAndNewlines) + "..."
    }

    private func formattedJSONValue(_ value: Any) -> String {
        guard JSONSerialization.isValidJSONObject(value),
              let data = try? JSONSerialization.data(withJSONObject: value, options: [.prettyPrinted, .sortedKeys]),
              let string = String(data: data, encoding: .utf8)
        else {
            return String(describing: value)
        }
        return string
    }

    private func parseRunResult(from data: Data) -> RunResult {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return RunResult(id: nil, status: nil, createdAt: nil, output: nil, failureMessage: nil)
        }

        let output: String?
        if let value = json["output"] as? String {
            output = value
        } else if let value = json["output"] {
            output = formattedJSONValue(value)
        } else {
            output = nil
        }

        let failureMessage = Self.firstStringValue(
            in: json,
            keys: ["error", "message", "detail", "failure_reason", "failureReason", "failed_reason", "failedReason"]
        )

        return RunResult(
            id: json["id"] as? String,
            status: json["status"] as? String,
            createdAt: json["created_at"] as? String ?? json["createdAt"] as? String,
            output: output,
            failureMessage: failureMessage ?? output
        )
    }

    nonisolated private static func firstStringValue(in json: [String: Any], keys: [String]) -> String? {
        for key in keys {
            if let value = json[key] as? String {
                let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
                if !trimmed.isEmpty {
                    return trimmed
                }
            }

            if let value = json[key], !(value is NSNull) {
                let text = String(describing: value).trimmingCharacters(in: .whitespacesAndNewlines)
                if !text.isEmpty, text != "<null>" {
                    return text
                }
            }
        }

        return nil
    }
}

private struct RunRequest: Encodable {
    let files: [RunFile]
}

private struct RunFile: Encodable {
    let id: String
}

private struct RunResult {
    let id: String?
    let status: String?
    let createdAt: String?
    let output: String?
    let failureMessage: String?

    var hasOutput: Bool {
        output?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
    }
}

enum RecordingUploadError: PresentableError {
    case missingServerURL
    case missingAPISettings
    case missingAppID
    case missingAccessToken
    case unauthorized(operation: String, url: String)
    case audioFileMissing
    case invalidResponse
    case noSpacesFound
    case noAppsFound
    case missingResponseValue(operation: String, key: String)
    case runFailed(status: String, message: String?)
    case runTimedOut(status: String)
    case serverError(operation: String, statusCode: Int, url: String, method: String, authDescription: String, message: String?)

    var errorDescription: String? {
        switch self {
        case .missingServerURL:
            return String(localized: "Ingen serveradress är angiven", comment: "Rubrik när Eneo-adressen saknas helt")
        case .missingAPISettings:
            return String(localized: "Serveradressen är ogiltig", comment: "Rubrik när Eneo-adressen inte går att tolka")
        case .missingAppID:
            return String(localized: "Ingen Eneo-app är vald", comment: "Rubrik när app-ID saknas")
        case .missingAccessToken, .unauthorized:
            return String(localized: "Din inloggning gäller inte längre", comment: "Rubrik när token saknas eller nekas")
        case .audioFileMissing:
            return String(localized: "Ljudfilen saknas", comment: "Rubrik när ljudfilen inte finns kvar på enheten")
        case .invalidResponse:
            return String(localized: "Oväntat svar från Eneo", comment: "Rubrik när svaret inte är giltig HTTP")
        case .noSpacesFound:
            return String(localized: "Inga spaces hittades", comment: "Rubrik när kontot saknar spaces")
        case .noAppsFound:
            return String(localized: "Inga appar hittades", comment: "Rubrik när kontot saknar appar")
        case .missingResponseValue:
            return String(localized: "Ofullständigt svar från Eneo", comment: "Rubrik när ett fält saknas i svaret")
        case .runFailed:
            return String(localized: "Omvandlingen misslyckades", comment: "Rubrik när Eneo-körningen misslyckades")
        case .runTimedOut:
            return String(localized: "Omvandlingen tog för lång tid", comment: "Rubrik när körningen inte blev klar i tid")
        case .serverError:
            return String(localized: "Eneo kunde inte utföra begäran", comment: "Rubrik vid serverfel")
        }
    }

    var recoverySuggestion: String? {
        switch self {
        case .missingServerURL:
            return String(localized: "Ange adressen till din organisations Eneo under Inställningar.", comment: "Åtgärd när serveradress saknas")
        case .missingAPISettings:
            return String(localized: "Kontrollera serveradressen under Inställningar.", comment: "Åtgärd vid ogiltig adress")
        case .missingAppID:
            return String(localized: "Välj vilken Eneo-app som ska användas under Inställningar.", comment: "Åtgärd när app saknas")
        case .missingAccessToken, .unauthorized:
            return String(localized: "Logga in i Eneo igen för att skicka inspelningen.", comment: "Åtgärd vid utgången inloggning")
        case .audioFileMissing:
            return String(localized: "Filen kan ha raderats utanför appen. Spela in på nytt.", comment: "Åtgärd när ljudfilen saknas")
        case .invalidResponse:
            return String(localized: "Försök igen. Kvarstår felet kan servern vara nere.", comment: "Åtgärd vid ogiltigt svar")
        case .noSpacesFound:
            return String(localized: "Kontrollera med din organisation att ditt konto har tillgång till minst ett space.", comment: "Åtgärd när spaces saknas")
        case .noAppsFound:
            return String(localized: "Kontrollera med din organisation att ditt konto har tillgång till en app för tal till text.", comment: "Åtgärd när appar saknas")
        case .missingResponseValue:
            return String(localized: "Försök igen. Kvarstår felet behöver din organisation kontrollera Eneo.", comment: "Åtgärd vid ofullständigt svar")
        case .runFailed(_, let message):
            if let message, !message.isEmpty, message != "<null>" {
                return message
            }
            // Eneo reports e.g. an unsupported transcription model only as a
            // bare "failed" status, so point the user at the app's settings.
            return String(localized: "Eneo angav ingen orsak. Det beror ofta på att den valda Eneo-appen använder en transkriberingsmodell som inte stöds. Kontrollera appens modellinställningar i Eneo och försök igen.", comment: "Åtgärd när körningen misslyckades utan orsak")
        case .runTimedOut:
            return String(localized: "Ljudfilen kan vara för lång. Försök igen eller dela upp inspelningen.", comment: "Åtgärd vid timeout")
        case .serverError(_, _, _, _, _, let message):
            if let message, !message.isEmpty {
                return message
            }
            return String(localized: "Försök igen om en stund.", comment: "Åtgärd vid serverfel utan meddelande")
        }
    }

    var systemImage: String {
        switch self {
        case .missingAccessToken, .unauthorized:
            return "person.crop.circle.badge.exclamationmark"
        case .audioFileMissing:
            return "waveform.slash"
        case .runTimedOut:
            return "clock.badge.exclamationmark"
        case .missingServerURL, .missingAPISettings, .noSpacesFound, .noAppsFound, .missingAppID:
            return "gearshape.exclamationmark"
        default:
            return "exclamationmark.triangle.fill"
        }
    }

    var requiresSignIn: Bool {
        switch self {
        case .missingAccessToken, .unauthorized:
            return true
        default:
            return false
        }
    }

    var technicalDetails: String? {
        switch self {
        case .unauthorized(let operation, let url):
            return "\(operation): status 401\nURL: \(url)"
        case .missingResponseValue(let operation, let key):
            return "\(operation): saknat fält \"\(key)\""
        case .runFailed(let status, let message):
            var details = "Run status: \(status)"
            if let message, !message.isEmpty {
                details += "\nMeddelande: \(message)"
            }
            return details
        case .runTimedOut(let status):
            return "Senaste status: \(status)"
        case .serverError(let operation, let statusCode, let url, let method, let authDescription, let message):
            var details = "\(operation): status \(statusCode)\n\(method) \(url)\nAuth: \(authDescription)"
            if let message, !message.isEmpty {
                details += "\nSvar: \(message)"
            }
            return details
        default:
            return nil
        }
    }
}
