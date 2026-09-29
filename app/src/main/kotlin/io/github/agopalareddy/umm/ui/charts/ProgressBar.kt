package io.github.agopalareddy.umm.ui.charts

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** A rounded track filled to [progress] (0 to 1; anything else is clamped, and not-a-number counts as 0). */
@Composable
internal fun ProgressBar(progress: Float, description: String, modifier: Modifier = Modifier) {
    val fraction = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    val drawIn = rememberDrawIn(fraction)
    val scheme = MaterialTheme.colorScheme
    Spacer(
        modifier.fillMaxWidth().height(12.dp)
            .semantics {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            }
            .drawBehind {
                drawTrack(fraction * drawIn.value, scheme.primary.copy(alpha = 0.14f)) { width ->
                    Brush.horizontalGradient(listOf(scheme.primary, scheme.tertiary), startX = 0f, endX = width)
                }
            },
    )
}

/**
 * A pill-shaped [track] across the whole area with a fill of [fraction] of its width. Any fraction above zero
 * shows at least a dot, so a small share is still visible.
 */
internal fun DrawScope.drawTrack(fraction: Float, track: Color, fill: (width: Float) -> Brush) {
    val radius = CornerRadius(size.height / 2)
    drawRoundRect(track, cornerRadius = radius)
    if (fraction <= 0f) return
    val width = (size.width * fraction).coerceAtLeast(size.height).coerceAtMost(size.width)
    drawRoundRect(fill(width), size = Size(width, size.height), cornerRadius = radius)
}
