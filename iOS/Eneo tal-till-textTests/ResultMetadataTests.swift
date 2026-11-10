import Foundation
import Testing
@testable import Eneo_tal_till_text

@MainActor
struct ResultMetadataTests {
    private let service = RecordingUploadService()

    @Test func explicitTitleAndSummaryLabelsWin() {
        let markdown = """
        # Mötesanteckningar
        **Titel:** Budgetmöte oktober
        Sammanfattning: Gruppen gick igenom utfallet.
        - Punkt
        """

        let metadata = service.metadata(from: markdown)

        #expect(metadata.suggestedTitle == "Budgetmöte oktober")
        #expect(metadata.shortSummary == "Gruppen gick igenom utfallet.")
    }

    @Test func fallsBackToFirstHeadingAndFirstParagraph() {
        let markdown = """
        ## Planeringsmöte

        - Första punkten
        1. Numrerad punkt

        Detta är det första riktiga stycket.
        """

        let metadata = service.metadata(from: markdown)

        #expect(metadata.suggestedTitle == "Planeringsmöte")
        #expect(metadata.shortSummary == "Detta är det första riktiga stycket.")
    }

    @Test func summaryDoesNotRepeatTheTitle() {
        let metadata = service.metadata(from: "Bara en rad\nBara en rad\nAndra raden")

        #expect(metadata.suggestedTitle == nil)
        #expect(metadata.shortSummary == "Bara en rad")
    }

    @Test func longValuesAreTruncatedWithEllipsis() throws {
        let longTitle = String(repeating: "a", count: 80)
        let metadata = service.metadata(from: "# \(longTitle)")

        let title = try #require(metadata.suggestedTitle)
        #expect(title.hasSuffix("..."))
        #expect(title.count == 63)
    }

    @Test func emptyOutputYieldsNoMetadata() {
        let metadata = service.metadata(from: "\n\n   \n")

        #expect(metadata.suggestedTitle == nil)
        #expect(metadata.shortSummary == nil)
    }
}
