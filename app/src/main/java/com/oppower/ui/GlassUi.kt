package com.oppower.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Calm, almost-flat backdrop. A single low-alpha cool glow gives the page
 * depth; there are no coloured blobs, moving ribbons or bright washes.
 */
@Composable
internal fun GlassBackdrop(content: @Composable BoxScope.() -> Unit) {
    val dark = Background.luminance() < 0.45f
    val base = remember(dark) {
        if (dark) {
            Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF0A232A),
                    Color(0xFF0C2A31),
                    Color(0xFF081E24),
                ),
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    Color(0xFFF7FBFA),
                    Color(0xFFEDF4F2),
                    Color(0xFFF7F2EA),
                ),
            )
        }
    }
    Box(Modifier.fillMaxSize().background(base)) {
        Canvas(Modifier.fillMaxSize()) {
            if (dark) {
                val coolCenter = Offset(size.width * 0.22f, size.height * 0.28f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF2BB9AA).copy(alpha = 0.075f),
                            Color.Transparent,
                        ),
                        center = coolCenter,
                        radius = size.width,
                    ),
                    center = coolCenter,
                    radius = size.width,
                )
                val heroCenter = Offset(size.width * 0.50f, size.height * 0.30f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF2BB9AA).copy(alpha = 0.050f),
                            Color.Transparent,
                        ),
                        center = heroCenter,
                        radius = size.width * 0.78f,
                    ),
                    center = heroCenter,
                    radius = size.width * 0.78f,
                )
                val warmCenter = Offset(size.width * 0.88f, size.height * 0.86f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFD9A15F).copy(alpha = 0.022f),
                            Color.Transparent,
                        ),
                        center = warmCenter,
                        radius = size.width * 0.75f,
                    ),
                    center = warmCenter,
                    radius = size.width * 0.75f,
                )
            } else {
                val coolCenter = Offset(size.width * 0.20f, size.height * 0.26f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF8ADFD1).copy(alpha = 0.10f),
                            Color.Transparent,
                        ),
                        center = coolCenter,
                        radius = size.width,
                    ),
                    center = coolCenter,
                    radius = size.width,
                )
            }
        }
        content()
    }
}

/**
 * Minimal smoked-glass surface: a deep translucent fill, one hairline rim and
 * one soft top reflection. No thick bevels, colour bands or large white glare.
 */
@Composable
internal fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(18.dp),
    tint: Color = Surface,
    accent: Color = Cyan,
    highlighted: Boolean = false,
    shadowElevation: Dp = if (highlighted) 12.dp else 6.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val dark = Background.luminance() < 0.45f
    val requestedOpacity = tint.alpha.coerceIn(0.65f, 1f)
    val surfaceAlpha = if (dark) {
        0.62f + requestedOpacity * 0.06f
    } else {
        0.74f + requestedOpacity * 0.08f
    }
    val topTint = remember(tint, dark) {
        lerp(tint, Color.White, if (dark) 0.06f else 0.40f)
    }
    val bottomTint = remember(tint, dark) {
        lerp(
            tint,
            if (dark) Color(0xFF061A20) else Color(0xFFE4ECEA),
            if (dark) 0.12f else 0.20f,
        )
    }
    val fill = remember(topTint, bottomTint, surfaceAlpha) {
        Brush.verticalGradient(
            colors = listOf(
                topTint.copy(alpha = (surfaceAlpha + 0.02f).coerceAtMost(0.92f)),
                bottomTint.copy(alpha = surfaceAlpha),
            ),
        )
    }
    val rim = remember(accent, highlighted, dark) {
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = if (dark) { if (highlighted) 0.62f else 0.34f } else 0.92f),
                accent.copy(alpha = if (highlighted) 0.34f else 0.10f),
                Color.White.copy(alpha = if (dark) 0.02f else 0.25f),
            ),
            start = Offset.Zero,
            end = Offset(900f, 900f),
        )
    }
    Box(
        modifier = modifier
            .shadow(
                elevation = shadowElevation,
                shape = shape,
                ambientColor = Color(0xFF00161A).copy(alpha = if (dark) 0.30f else 0.10f),
                spotColor = Color(0xFF00161A).copy(alpha = if (dark) 0.32f else 0.10f),
            )
            .clip(shape)
            .background(fill)
            .border(0.8.dp, rim, shape),
    ) {
        Canvas(Modifier.matchParentSize()) {
            val corner = when (val outline = shape.createOutline(size, layoutDirection, this)) {
                is Outline.Rounded -> outline.roundRect.topLeftCornerRadius
                else -> CornerRadius(14.dp.toPx())
            }
            val compact = size.height < 44.dp.toPx()
            val compactScale = if (compact) 0.6f else 1f

            // A restrained top sheen suggests a curved glass surface.
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = (if (dark) 0.035f else 0.12f) * compactScale),
                        Color.Transparent,
                    ),
                    startY = 0f,
                    endY = minOf(size.height * 0.45f, 64.dp.toPx()),
                ),
            )

            // Small specular glint at the top-left corner.
            val glintCenter = Offset(size.width * 0.14f, 0f)
            val glintRadius = minOf(size.width * 0.50f, size.height * 1.0f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = (if (dark) 0.10f else 0.24f) * compactScale),
                        Color.Transparent,
                    ),
                    center = glintCenter,
                    radius = glintRadius,
                ),
                center = glintCenter,
                radius = glintRadius,
            )

            // One broad, clean diagonal reflection across the glass.
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = (if (dark) 0.045f else 0.10f) * compactScale),
                        Color.Transparent,
                    ),
                    start = Offset.Zero,
                    end = Offset(size.width * 0.9f, size.height * 0.9f),
                ),
            )

            // Focused corner glint: a small, bright reflection rather than a
            // broad white wash.
            if (highlighted && !compact) {
                val spotCenter = Offset(size.width * 0.07f, size.height * 0.06f)
                val spotRadius = minOf(size.width * 0.20f, 52.dp.toPx())
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (dark) 0.20f else 0.30f),
                            Color.Transparent,
                        ),
                        center = spotCenter,
                        radius = spotRadius,
                    ),
                    center = spotCenter,
                    radius = spotRadius,
                )
            }

            // One-pixel inner line for a clean glass thickness cue.
            if (!compact) {
                drawRoundRect(
                    color = Color.White.copy(alpha = if (dark) 0.10f else 0.32f),
                    topLeft = Offset(1.dp.toPx(), 1.dp.toPx()),
                    size = Size(size.width - 2.dp.toPx(), size.height - 2.dp.toPx()),
                    cornerRadius = CornerRadius(
                        (corner.x - 1.dp.toPx()).coerceAtLeast(0f),
                        (corner.y - 1.dp.toPx()).coerceAtLeast(0f),
                    ),
                    style = Stroke(0.8.dp.toPx()),
                )
            }

            // Bright arc that fades toward the lower-right: the clearest
            // reading of a polished glass rim.
            if (highlighted && !compact) {
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (dark) 0.60f else 0.86f),
                            Color.Transparent,
                        ),
                        start = Offset.Zero,
                        end = Offset(size.width * 0.42f, size.height * 0.62f),
                    ),
                    topLeft = Offset(0.6.dp.toPx(), 0.6.dp.toPx()),
                    size = Size(size.width - 1.2.dp.toPx(), size.height - 1.2.dp.toPx()),
                    cornerRadius = corner,
                    style = Stroke(1.2.dp.toPx()),
                )
            }

            // Hairline specular edge along the top.
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = (if (dark) { if (highlighted) 0.55f else 0.40f } else 0.82f) * compactScale),
                        accent.copy(alpha = (if (highlighted) 0.12f else 0.04f) * compactScale),
                        Color.Transparent,
                    ),
                ),
                topLeft = Offset(size.width * 0.06f, 0f),
                size = Size(size.width * 0.88f, 1.dp.toPx()),
            )

            // Soft bottom shade gives thickness without going black.
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color(0xFF02161B).copy(alpha = if (dark) 0.10f else 0.02f),
                    ),
                    startY = size.height * 0.55f,
                    endY = size.height,
                ),
            )
        }
        content()
    }
}




