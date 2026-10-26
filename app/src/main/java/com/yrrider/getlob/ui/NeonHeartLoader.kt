package com.yrrider.getlob.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun NeonHeartLoader(
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    neonColor: Color = Color(0xFFFF2A85),
    glowColor: Color = Color(0xFF38BDF8),
) {
    val transition = rememberInfiniteTransition(label = "NeonHeartLoop")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Progress"
    )

    val pulse by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 750, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Pulse"
    )

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height

            val heartPath = Path().apply {
                val startX = w * 0.5f
                val startY = h * 0.28f
                moveTo(startX, startY)

                cubicTo(
                    w * 0.12f, h * -0.06f,
                    -w * 0.08f, h * 0.45f,
                    w * 0.5f, h * 0.94f
                )

                cubicTo(
                    w * 1.08f, h * 0.45f,
                    w * 0.88f, h * -0.06f,
                    startX, startY
                )
                close()
            }

            val pm = PathMeasure().apply { setPath(heartPath, true) }
            val totalLen = pm.length
            if (totalLen <= 0f) return@Canvas

            val strokeWidth = (w * 0.075f).coerceIn(2.5f, 7f)

            drawPath(
                path = heartPath,
                color = neonColor.copy(alpha = 0.16f),
                style = Stroke(
                    width = strokeWidth * 0.8f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            val tailLen = totalLen * 0.38f
            val headDist = (progress * totalLen) % totalLen
            val tailDist = headDist - tailLen

            val segmentPath = Path()
            if (tailDist >= 0f) {
                pm.getSegment(tailDist, headDist, segmentPath, true)
            } else {
                pm.getSegment(tailDist + totalLen, totalLen, segmentPath, true)
                val wrapSegment = Path()
                pm.getSegment(0f, headDist, wrapSegment, true)
                segmentPath.addPath(wrapSegment)
            }

            drawPath(
                path = segmentPath,
                color = neonColor.copy(alpha = 0.35f * pulse),
                style = Stroke(
                    width = strokeWidth * 2.4f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            drawPath(
                path = segmentPath,
                color = glowColor.copy(alpha = 0.55f),
                style = Stroke(
                    width = strokeWidth * 1.4f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            drawPath(
                path = segmentPath,
                brush = Brush.radialGradient(
                    colors = listOf(Color.White, neonColor),
                    center = Offset(w * 0.5f, h * 0.5f),
                    radius = w * 0.6f
                ),
                style = Stroke(
                    width = strokeWidth,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            val headPos = pm.getPosition(headDist)
            if (headPos != Offset.Unspecified) {

                drawCircle(
                    color = glowColor.copy(alpha = 0.6f),
                    radius = strokeWidth * 2.2f * pulse,
                    center = headPos
                )

                drawCircle(
                    color = neonColor,
                    radius = strokeWidth * 1.3f,
                    center = headPos
                )

                drawCircle(
                    color = Color.White,
                    radius = strokeWidth * 0.65f,
                    center = headPos
                )
            }
        }
    }
}
