package com.nikhil.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp

@Composable
fun RadarDisplay(
    relativeAngle: Float,
    isTargetAcquired: Boolean,
    modifier: Modifier = Modifier
) {
    // Smooth the needle rotation animation
    val animatedAngle by animateFloatAsState(
        targetValue = relativeAngle,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f),
        label = "NeedleAngle"
    )

    Box(
        modifier = modifier.size(240.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(220.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = size.minDimension / 2

            // Radar Ring Graphics
            drawCircle(
                color = Color(0xFF1E293B),
                radius = radius,
                style = Stroke(width = 4.dp.toPx())
            )
            drawCircle(
                color = Color(0xFF334155),
                radius = radius * 0.65f,
                style = Stroke(width = 2.dp.toPx())
            )
            drawCircle(
                color = Color(0xFF475569),
                radius = radius * 0.35f,
                style = Stroke(width = 2.dp.toPx())
            )

            // Crosshairs
            drawLine(
                color = Color(0xFF334155),
                start = Offset(center.x, 0f),
                end = Offset(center.x, size.height),
                strokeWidth = 2.dp.toPx()
            )
            drawLine(
                color = Color(0xFF334155),
                start = Offset(0f, center.y),
                end = Offset(size.width, center.y),
                strokeWidth = 2.dp.toPx()
            )

            if (isTargetAcquired) {
                // Rotating Direction Needle
                rotate(degrees = animatedAngle, pivot = center) {
                    val arrowPath = Path().apply {
                        moveTo(center.x, center.y - radius * 0.85f) // Tip
                        lineTo(center.x - 22f, center.y)            // Left base
                        lineTo(center.x, center.y - 12f)            // Notch
                        lineTo(center.x + 22f, center.y)            // Right base
                        close()
                    }

                    drawPath(
                        path = arrowPath,
                        color = Color(0xFFFF3366) // Tracking neon red/pink
                    )

                    // Glow center pin
                    drawCircle(
                        color = Color(0xFFFF3366),
                        radius = 8.dp.toPx(),
                        center = center
                    )
                }
            } else {
                // Standby Idle Blip
                drawCircle(
                    color = Color(0xFF64748B),
                    radius = 6.dp.toPx(),
                    center = center
                )
            }
        }
    }
}