package se.iteca.eneotaltilltext.ui.record

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun WaveformView(
    levels: List<Float>,
    barColor: Color,
    modifier: Modifier = Modifier,
    barCount: Int = 60,
) {
    val sampled = sampleLevels(levels, barCount)
    Row(
        modifier = modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        sampled.forEach { level ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(level.coerceIn(0.04f, 1f))
                    .width(2.dp)
                    .background(barColor, RoundedCornerShape(1.5.dp)),
            )
        }
    }
}

private fun sampleLevels(levels: List<Float>, count: Int): List<Float> {
    if (levels.isEmpty()) return List(count) { 0.05f }
    if (levels.size <= count) {
        val padded = levels + List(count - levels.size) { 0.05f }
        return padded.takeLast(count)
    }
    val chunkSize = levels.size.toDouble() / count
    return List(count) { i ->
        val start = (i * chunkSize).toInt()
        val end = minOf(((i + 1) * chunkSize).toInt(), levels.size)
        val chunk = levels.subList(start, maxOf(start + 1, end))
        chunk.maxOrNull() ?: 0.04f
    }
}
