import SwiftUI

struct WaveformView: View {
    let levels: [Float]
    var barColor: Color = .blue
    var barCount: Int = 60

    var body: some View {
        GeometryReader { geometry in
            HStack(alignment: .center, spacing: 2) {
                let sampled = sampleLevels(count: barCount)
                ForEach(Array(sampled.enumerated()), id: \.offset) { _, level in
                    RoundedRectangle(cornerRadius: 1.5)
                        .fill(barColor)
                        .frame(
                            width: max(2, (geometry.size.width - CGFloat(barCount) * 2) / CGFloat(barCount)),
                            height: max(3, CGFloat(level) * geometry.size.height)
                        )
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
        }
    }

    private func sampleLevels(count: Int) -> [Float] {
        guard !levels.isEmpty else {
            return Array(repeating: 0.05, count: count)
        }

        if levels.count <= count {
            let padded = levels + Array(repeating: Float(0.05), count: count - levels.count)
            return Array(padded.suffix(count))
        }

        var result: [Float] = []
        let chunkSize = Double(levels.count) / Double(count)
        for i in 0..<count {
            let start = Int(Double(i) * chunkSize)
            let end = min(Int(Double(i + 1) * chunkSize), levels.count)
            let chunk = levels[start..<end]
            let avg = chunk.reduce(0, +) / Float(max(1, chunk.count))
            result.append(avg)
        }
        return result
    }
}
