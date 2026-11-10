import Foundation
import Testing
@testable import Eneo_tal_till_text

struct MarkdownParsingTests {
    @Test func headingsParseAtEveryLevelAndStripClosingHashes() {
        let blocks = MarkdownBlockParser.parse("# Rubrik\n### Under ##\n####### Inte rubrik")

        #expect(blocks.count == 3)
        #expect(blocks[0] == MarkdownBlock(kind: .heading(level: 1), text: "Rubrik"))
        #expect(blocks[1] == MarkdownBlock(kind: .heading(level: 3), text: "Under"))
        #expect(blocks[2].kind == .paragraph)
    }

    @Test func paragraphJoinsSoftLineBreaksAndStopsAtBlankLine() {
        let blocks = MarkdownBlockParser.parse("Första raden\nandra raden\n\nNytt stycke")

        #expect(blocks.count == 2)
        #expect(blocks[0] == MarkdownBlock(kind: .paragraph, text: "Första raden andra raden"))
        #expect(blocks[1] == MarkdownBlock(kind: .paragraph, text: "Nytt stycke"))
    }

    @Test func bulletListsKeepDepthFromIndentation() {
        let blocks = MarkdownBlockParser.parse("- Ett\n  - Nästlad\n- Två")

        #expect(blocks.count == 3)
        #expect(blocks[0] == MarkdownBlock(kind: .bullet(depth: 0), text: "Ett"))
        #expect(blocks[1] == MarkdownBlock(kind: .bullet(depth: 1), text: "Nästlad"))
        #expect(blocks[2] == MarkdownBlock(kind: .bullet(depth: 0), text: "Två"))
    }

    @Test func numberedListsKeepTheirNumbersAndContinuationLines() {
        let blocks = MarkdownBlockParser.parse("1. Första\n   fortsättning\n2) Andra")

        #expect(blocks.count == 2)
        #expect(blocks[0] == MarkdownBlock(kind: .numbered(depth: 0, number: 1), text: "Första\nfortsättning"))
        #expect(blocks[1] == MarkdownBlock(kind: .numbered(depth: 0, number: 2), text: "Andra"))
    }

    @Test func looseListSurvivesBlankLineBetweenItems() {
        let blocks = MarkdownBlockParser.parse("- Ett\n\n- Två\n\nStycke")

        #expect(blocks.count == 3)
        #expect(blocks[0].kind == .bullet(depth: 0))
        #expect(blocks[1].kind == .bullet(depth: 0))
        #expect(blocks[2] == MarkdownBlock(kind: .paragraph, text: "Stycke"))
    }

    @Test func fencedCodeBlockKeepsContentVerbatim() {
        let blocks = MarkdownBlockParser.parse("```swift\nlet a = 1\n  let b = 2\n```\nEfter")

        #expect(blocks.count == 2)
        #expect(blocks[0] == MarkdownBlock(kind: .code(language: "swift"), text: "let a = 1\n  let b = 2"))
        #expect(blocks[1] == MarkdownBlock(kind: .paragraph, text: "Efter"))
    }

    @Test func quoteLinesAreMergedIntoOneBlock() {
        let blocks = MarkdownBlockParser.parse("> Första\n> Andra\nStycke")

        #expect(blocks.count == 2)
        #expect(blocks[0] == MarkdownBlock(kind: .quote, text: "Första\nAndra"))
        #expect(blocks[1].kind == .paragraph)
    }

    @Test func tableRequiresSeparatorRow() {
        let withSeparator = MarkdownBlockParser.parse("| Namn | Roll |\n|---|:---:|\n| Anna | Ordförande |")
        let withoutSeparator = MarkdownBlockParser.parse("| Namn | Roll |\n| Anna | Ordförande |")

        #expect(withSeparator.count == 1)
        #expect(withSeparator[0].kind == .table(header: ["Namn", "Roll"], rows: [["Anna", "Ordförande"]]))
        #expect(withoutSeparator.count == 1)
        #expect(withoutSeparator[0].kind == .paragraph)
    }

    @Test func thematicBreakIsRecognizedWithSpaces() {
        let blocks = MarkdownBlockParser.parse("Före\n\n- - -\n\nEfter")

        #expect(blocks.count == 3)
        #expect(blocks[1].kind == .thematicBreak)
    }

    @Test func windowsLineEndingsAreNormalized() {
        let blocks = MarkdownBlockParser.parse("# Rubrik\r\nText\r\n")

        #expect(blocks.count == 2)
        #expect(blocks[0].kind == .heading(level: 1))
        #expect(blocks[1] == MarkdownBlock(kind: .paragraph, text: "Text"))
    }
}
