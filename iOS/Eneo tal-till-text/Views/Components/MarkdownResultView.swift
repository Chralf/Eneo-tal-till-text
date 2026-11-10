import SwiftUI

/// Renders CommonMark-style markdown that Eneo typically returns: headings,
/// lists (including nested), quotes, fenced code, rules, and inline emphasis.
/// Equatable so a parent can apply `.equatable()` and skip re-parsing the
/// document on unrelated state changes such as playback progress.
struct MarkdownResultView: View, Equatable {
    let markdown: String

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(blocks.enumerated()), id: \.offset) { index, block in
                blockView(block)
                    .padding(.top, topPadding(for: block, previous: index > 0 ? blocks[index - 1] : nil))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .textSelection(.enabled)
    }

    @ViewBuilder
    private func blockView(_ block: MarkdownBlock) -> some View {
        switch block.kind {
        case .heading(let level):
            Text(inline(block.text))
                .font(headingFont(for: level))
                .foregroundStyle(.primary)
                .fixedSize(horizontal: false, vertical: true)

        case .paragraph:
            Text(inline(block.text))
                .font(.body)
                .lineSpacing(3)
                .fixedSize(horizontal: false, vertical: true)

        case .bullet(let depth):
            listRow(marker: "•", depth: depth, text: block.text)

        case .numbered(let depth, let number):
            listRow(marker: "\(number).", depth: depth, text: block.text)

        case .quote:
            HStack(alignment: .top, spacing: 10) {
                RoundedRectangle(cornerRadius: 1.5, style: .continuous)
                    .fill(.tertiary)
                    .frame(width: 3)
                    .padding(.vertical, 2)

                Text(inline(block.text))
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .lineSpacing(3)
                    .fixedSize(horizontal: false, vertical: true)
            }

        case .code:
            Text(block.text)
                .font(.callout.monospaced())
                .foregroundStyle(.primary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(12)
                .background(Color(.secondarySystemFill), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                .textSelection(.enabled)

        case .table(let header, let rows):
            markdownTable(header: header, rows: rows)

        case .thematicBreak:
            Divider()
                .padding(.vertical, 4)
        }
    }

    private func markdownTable(header: [String], rows: [[String]]) -> some View {
        let columnCount = max(header.count, rows.map(\.count).max() ?? 0, 1)
        let normalizedHeader = padCells(header, count: columnCount)
        let normalizedRows = rows.map { padCells($0, count: columnCount) }

        return ScrollView(.horizontal, showsIndicators: true) {
            Grid(horizontalSpacing: 0, verticalSpacing: 0) {
                GridRow {
                    ForEach(Array(normalizedHeader.enumerated()), id: \.offset) { _, cell in
                        tableCell(cell, isHeader: true)
                    }
                }
                ForEach(Array(normalizedRows.enumerated()), id: \.offset) { rowIndex, row in
                    GridRow {
                        ForEach(Array(row.enumerated()), id: \.offset) { _, cell in
                            tableCell(cell, isHeader: false)
                        }
                    }
                    .overlay(alignment: .bottom) {
                        if rowIndex < normalizedRows.count - 1 {
                            Divider()
                        }
                    }
                }
            }
            .overlay(alignment: .top) {
                Divider().offset(y: 32)
            }
        }
    }

    private func tableCell(_ text: String, isHeader: Bool) -> some View {
        Text(inline(text))
            .font(isHeader ? .subheadline.weight(.semibold) : .subheadline.monospaced())
            .foregroundStyle(.primary)
            .frame(minWidth: 72, alignment: .leading)
            .padding(.horizontal, 8)
            .padding(.vertical, 8)
            .fixedSize(horizontal: false, vertical: true)
    }

    private func padCells(_ cells: [String], count: Int) -> [String] {
        if cells.count >= count { return Array(cells.prefix(count)) }
        return cells + Array(repeating: "", count: count - cells.count)
    }

    private func listRow(marker: String, depth: Int, text: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text(marker)
                .font(.body)
                .monospacedDigit()
                .foregroundStyle(.secondary)
                .frame(width: markerWidth(for: marker), alignment: .trailing)
                .accessibilityHidden(true)

            Text(inline(text))
                .font(.body)
                .frame(maxWidth: .infinity, alignment: .leading)
                .multilineTextAlignment(.leading)
                .lineSpacing(3)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.leading, CGFloat(depth) * 18)
        .accessibilityElement(children: .combine)
    }

    private func markerWidth(for marker: String) -> CGFloat {
        marker.count <= 2 ? 22 : 30
    }

    private func topPadding(for block: MarkdownBlock, previous: MarkdownBlock?) -> CGFloat {
        guard let previous else { return 0 }

        switch block.kind {
        case .heading(let level):
            return level <= 2 ? 18 : 14
        case .thematicBreak:
            return 12
        case .bullet, .numbered:
            switch previous.kind {
            case .bullet, .numbered:
                return 4
            default:
                return 10
            }
        case .quote, .code, .table:
            return 12
        case .paragraph:
            return 10
        }
    }

    private func headingFont(for level: Int) -> Font {
        switch level {
        case 1: return .title2.weight(.semibold)
        case 2: return .title3.weight(.semibold)
        case 3: return .headline
        case 4: return .body.weight(.semibold)
        default: return .subheadline.weight(.semibold)
        }
    }

    private func inline(_ markdown: String) -> AttributedString {
        let options = AttributedString.MarkdownParsingOptions(
            interpretedSyntax: .inlineOnlyPreservingWhitespace,
            failurePolicy: .returnPartiallyParsedIfPossible
        )
        return (try? AttributedString(markdown: markdown, options: options)) ?? AttributedString(markdown)
    }

    private var blocks: [MarkdownBlock] {
        MarkdownBlockParser.parse(markdown)
    }
}

// MARK: - Parser

private enum MarkdownBlockParser {
    static func parse(_ markdown: String) -> [MarkdownBlock] {
        let lines = markdown
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
            .components(separatedBy: "\n")

        var blocks: [MarkdownBlock] = []
        var index = 0

        while index < lines.count {
            let rawLine = lines[index]
            let trimmed = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)

            if trimmed.isEmpty {
                index += 1
                continue
            }

            if let fence = matchFence(trimmed) {
                let (block, nextIndex) = consumeCodeBlock(lines: lines, start: index, opening: fence)
                blocks.append(block)
                index = nextIndex
                continue
            }

            if isThematicBreak(trimmed) {
                blocks.append(MarkdownBlock(kind: .thematicBreak, text: ""))
                index += 1
                continue
            }

            if let heading = heading(from: trimmed) {
                blocks.append(heading)
                index += 1
                continue
            }

            if trimmed.hasPrefix(">") {
                let (block, nextIndex) = consumeQuote(lines: lines, start: index)
                blocks.append(block)
                index = nextIndex
                continue
            }

            if let listStart = matchListItem(rawLine) {
                let (listBlocks, nextIndex) = consumeList(lines: lines, start: index, first: listStart)
                blocks.append(contentsOf: listBlocks)
                index = nextIndex
                continue
            }

            if looksLikeTableRow(trimmed),
               index + 1 < lines.count,
               isTableSeparator(lines[index + 1].trimmingCharacters(in: .whitespacesAndNewlines)) {
                let (block, nextIndex) = consumeTable(lines: lines, start: index)
                blocks.append(block)
                index = nextIndex
                continue
            }

            let (paragraph, nextIndex) = consumeParagraph(lines: lines, start: index)
            blocks.append(paragraph)
            index = nextIndex
        }

        return blocks
    }

    // MARK: Headings

    private static func heading(from line: String) -> MarkdownBlock? {
        for level in (1...6).reversed() {
            let prefix = String(repeating: "#", count: level) + " "
            guard line.hasPrefix(prefix) else { continue }
            let text = String(line.dropFirst(prefix.count))
                .trimmingCharacters(in: .whitespacesAndNewlines)
            // Optional CommonMark closing sequence: `## Title ##`
            let cleaned = stripTrailingATXHashes(text)
            guard !cleaned.isEmpty else { return nil }
            return MarkdownBlock(kind: .heading(level: level), text: cleaned)
        }
        return nil
    }

    // MARK: Code

    private static func matchFence(_ line: String) -> String? {
        guard line.hasPrefix("```") || line.hasPrefix("~~~") else { return nil }
        let marker = String(line.prefix(3))
        return marker
    }

    private static func consumeCodeBlock(lines: [String], start: Int, opening: String) -> (MarkdownBlock, Int) {
        let openingLine = lines[start].trimmingCharacters(in: .whitespaces)
        let language = String(openingLine.dropFirst(3)).trimmingCharacters(in: .whitespacesAndNewlines)
        var body: [String] = []
        var index = start + 1

        while index < lines.count {
            let line = lines[index]
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.hasPrefix(opening) {
                index += 1
                break
            }
            body.append(line)
            index += 1
        }

        // Drop a single trailing empty line for nicer framing.
        if body.last?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == true {
            body.removeLast()
        }

        return (
            MarkdownBlock(kind: .code(language: language.isEmpty ? nil : language), text: body.joined(separator: "\n")),
            index
        )
    }

    // MARK: Quotes

    private static func consumeQuote(lines: [String], start: Int) -> (MarkdownBlock, Int) {
        var parts: [String] = []
        var index = start

        while index < lines.count {
            let trimmed = lines[index].trimmingCharacters(in: .whitespacesAndNewlines)
            guard trimmed.hasPrefix(">") else { break }
            var content = String(trimmed.dropFirst())
            if content.hasPrefix(" ") {
                content = String(content.dropFirst())
            }
            if !content.isEmpty {
                parts.append(content)
            }
            index += 1
        }

        return (MarkdownBlock(kind: .quote, text: parts.joined(separator: "\n")), index)
    }

    // MARK: Lists

    private struct ListMatch {
        let indent: Int
        let depth: Int
        let kind: ListKind
        let text: String
    }

    private enum ListKind {
        case bullet
        case numbered(Int)
    }

    private static func matchListItem(_ rawLine: String) -> ListMatch? {
        let indent = leadingWhitespaceCount(rawLine)
        let content = String(rawLine.dropFirst(indent))

        if content.hasPrefix("- ") || content.hasPrefix("* ") || content.hasPrefix("+ ") {
            return ListMatch(
                indent: indent,
                depth: depth(forIndent: indent),
                kind: .bullet,
                text: String(content.dropFirst(2))
            )
        }

        if let numbered = matchOrderedMarker(content) {
            return ListMatch(
                indent: indent,
                depth: depth(forIndent: indent),
                kind: .numbered(numbered.number),
                text: numbered.text
            )
        }

        return nil
    }

    private static func matchOrderedMarker(_ content: String) -> (number: Int, text: String)? {
        var numberChars: [Character] = []
        var index = content.startIndex

        while index < content.endIndex, content[index].isNumber {
            numberChars.append(content[index])
            index = content.index(after: index)
            if numberChars.count > 9 { return nil }
        }

        guard !numberChars.isEmpty,
              index < content.endIndex,
              content[index] == "." || content[index] == ")",
              let number = Int(String(numberChars))
        else {
            return nil
        }

        let afterMarker = content.index(after: index)
        guard afterMarker < content.endIndex, content[afterMarker] == " " else { return nil }
        let textStart = content.index(after: afterMarker)
        return (number, String(content[textStart...]))
    }

    private static func consumeList(lines: [String], start: Int, first: ListMatch) -> ([MarkdownBlock], Int) {
        var blocks: [MarkdownBlock] = []
        var index = start
        var current = first
        var textLines = [current.text]
        let baseIndent = first.indent

        func flush() {
            let text = textLines
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                .filter { !$0.isEmpty }
                .joined(separator: "\n")
            switch current.kind {
            case .bullet:
                blocks.append(MarkdownBlock(kind: .bullet(depth: current.depth), text: text))
            case .numbered(let number):
                blocks.append(MarkdownBlock(kind: .numbered(depth: current.depth, number: number), text: text))
            }
            textLines = []
        }

        index += 1

        while index < lines.count {
            let rawLine = lines[index]
            let trimmed = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)

            if trimmed.isEmpty {
                // A blank line ends the list unless the next non-empty line
                // continues it (CommonMark loose list). Peek ahead.
                if let nextIndex = indexOfNextNonEmpty(lines, after: index),
                   let nextItem = matchListItem(lines[nextIndex]),
                   nextItem.indent >= baseIndent {
                    index += 1
                    continue
                }
                break
            }

            if let nextItem = matchListItem(rawLine), nextItem.indent >= baseIndent {
                flush()
                current = nextItem
                textLines = [nextItem.text]
                index += 1
                continue
            }

            // Indented continuation of the current item.
            let indent = leadingWhitespaceCount(rawLine)
            if indent > current.indent, matchFence(trimmed) == nil, heading(from: trimmed) == nil {
                textLines.append(String(rawLine.dropFirst(indent)))
                index += 1
                continue
            }

            // Nested content that looks like a new block ends the list.
            break
        }

        flush()
        return (blocks, index)
    }

    private static func depth(forIndent indent: Int) -> Int {
        max(0, indent / 2)
    }

    // MARK: Paragraphs

    private static func consumeParagraph(lines: [String], start: Int) -> (MarkdownBlock, Int) {
        var parts: [String] = []
        var index = start

        while index < lines.count {
            let rawLine = lines[index]
            let trimmed = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)

            if trimmed.isEmpty { break }
            if matchFence(trimmed) != nil { break }
            if isThematicBreak(trimmed) { break }
            if heading(from: trimmed) != nil { break }
            if trimmed.hasPrefix(">") { break }
            if matchListItem(rawLine) != nil { break }
            // A pipe in ordinary prose is not a table unless the next line is
            // a Markdown separator. Consume the line so parsing always advances.
            if looksLikeTableRow(trimmed),
               index + 1 < lines.count,
               isTableSeparator(lines[index + 1].trimmingCharacters(in: .whitespacesAndNewlines)) {
                break
            }

            parts.append(trimmed)
            index += 1
        }

        // Soft line breaks become spaces, matching typical markdown rendering.
        return (MarkdownBlock(kind: .paragraph, text: parts.joined(separator: " ")), index)
    }

    // MARK: Helpers

    private static func stripTrailingATXHashes(_ text: String) -> String {
        guard let range = text.range(of: #"\s+#+$"#, options: .regularExpression) else {
            return text
        }
        return String(text[..<range.lowerBound])
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func isThematicBreak(_ line: String) -> Bool {
        let compact = line.replacingOccurrences(of: " ", with: "")
        guard compact.count >= 3 else { return false }
        return compact.allSatisfy { $0 == "-" }
            || compact.allSatisfy { $0 == "*" }
            || compact.allSatisfy { $0 == "_" }
    }

    private static func leadingWhitespaceCount(_ line: String) -> Int {
        var count = 0
        for character in line {
            if character == " " {
                count += 1
            } else if character == "\t" {
                count += 4
            } else {
                break
            }
        }
        return count
    }

    private static func indexOfNextNonEmpty(_ lines: [String], after index: Int) -> Int? {
        var next = index + 1
        while next < lines.count {
            if !lines[next].trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                return next
            }
            next += 1
        }
        return nil
    }

    private static func looksLikeTableRow(_ line: String) -> Bool {
        let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.contains("|") && trimmed.filter { $0 == "|" }.count >= 2
    }

    private static func isTableSeparator(_ line: String) -> Bool {
        let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "|"))
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return false }
        return trimmed.split(separator: "|").allSatisfy { cell in
            let content = cell.trimmingCharacters(in: .whitespacesAndNewlines)
            return !content.isEmpty && content.allSatisfy { $0 == "-" || $0 == ":" || $0 == " " }
        }
    }

    private static func parseTableCells(_ line: String) -> [String] {
        var trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.hasPrefix("|") { trimmed.removeFirst() }
        if trimmed.hasSuffix("|") { trimmed.removeLast() }
        return trimmed.split(separator: "|", omittingEmptySubsequences: false)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
    }

    private static func consumeTable(lines: [String], start: Int) -> (MarkdownBlock, Int) {
        let header = parseTableCells(lines[start])
        var index = start + 2
        var rows: [[String]] = []
        while index < lines.count {
            let trimmed = lines[index].trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.isEmpty { break }
            if !looksLikeTableRow(trimmed) || isTableSeparator(trimmed) { break }
            rows.append(parseTableCells(lines[index]))
            index += 1
        }
        return (MarkdownBlock(kind: .table(header: header, rows: rows), text: ""), index)
    }
}

private struct MarkdownBlock {
    let kind: MarkdownBlockKind
    let text: String
}

private enum MarkdownBlockKind {
    case heading(level: Int)
    case paragraph
    case bullet(depth: Int)
    case numbered(depth: Int, number: Int)
    case quote
    case code(language: String?)
    case table(header: [String], rows: [[String]])
    case thematicBreak
}
