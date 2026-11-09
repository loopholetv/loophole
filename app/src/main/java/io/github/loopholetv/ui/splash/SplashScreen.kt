package io.github.loopholetv.ui.splash

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.loopholetv.R
import kotlin.math.sqrt

/** Local radius of every gradient; shapes are scaled from it, so each brush is built once. */
private const val UNIT = 100f

/** Full logo width as a fraction of the screen width, and its drawable's aspect ratio. */
private const val LOGO_WIDTH_FRACTION = 0.54f
private const val LOGO_ASPECT = 254.04f / 63.36f

/**
 * One soft glow. Positions and sizes are fractions of the screen; [dx]/[dy] is the drift at its far
 * end (the glow moves there and back over [periodMs], growing by [grow] and turning [turn] degrees).
 * [hx]/[hy] are the half sizes of the ellipse.
 */
private class Glow(
    val cx: Float, val cy: Float, val hx: Float, val hy: Float,
    val dx: Float, val dy: Float, val grow: Float, val turn: Float,
    val periodMs: Long, color: Color, alpha: Float,
) {
    // A radial falloff shaped like a blurred disc: a soft core with a long, gentle edge.
    val brush = Brush.radialGradient(
        0.00f to color.copy(alpha = alpha),
        0.20f to color.copy(alpha = alpha * 0.92f),
        0.40f to color.copy(alpha = alpha * 0.72f),
        0.60f to color.copy(alpha = alpha * 0.45f),
        0.80f to color.copy(alpha = alpha * 0.18f),
        0.92f to color.copy(alpha = alpha * 0.05f),
        1.00f to color.copy(alpha = 0f),
        center = Offset.Zero,
        radius = UNIT,
    )
}

// The glows live on a layer 1.7 times the screen, centred, that turns slowly about the centre.
private val Glows = listOf(
    Glow(0.160f, 0.2875f, 0.680f, 0.4675f, 0.612f, 0.234f, 0.30f, 25f, 3_500, Color(0xFF087A74), 1f),
    Glow(0.925f, 0.8400f, 0.595f, 0.4250f, -0.5355f, -0.255f, 0.25f, -20f, 4_200, Color(0xFF055050), 1f),
    Glow(0.585f, 0.7125f, 0.510f, 0.3825f, -0.357f, -0.306f, 0.50f, 15f, 5_000, Color(0xFF0C8A82), 0.5f),
)

private val Vignette = Brush.radialGradient(
    0.35f to Color.Transparent,
    1.00f to Color.Black.copy(alpha = 0.85f),
    center = Offset.Zero,
    radius = UNIT,
)

private val Halo = Brush.radialGradient(
    0f to Color(0x520ABAB5),
    1f to Color(0x000ABAB5),
    center = Offset.Zero,
    radius = UNIT,
)

/** Slightly wider than each glow's box, so the faint tail reads as blur rather than an edge. */
private const val GLOW_SPREAD = 1.08f

private fun DrawScope.drawGlows(t: Long) {
    val w = size.width
    val h = size.height
    rotate(SplashMotion.spinDegrees(t)) {
        for (g in Glows) {
            val d = SplashMotion.drift(t, g.periodMs)
            val s = 1f + g.grow * d
            withTransform({
                translate(g.cx * w + g.dx * w * d, g.cy * h + g.dy * h * d)
                rotate(g.turn * d, pivot = Offset.Zero)
                scale(s * g.hx * w * GLOW_SPREAD / UNIT, s * g.hy * h * GLOW_SPREAD / UNIT, pivot = Offset.Zero)
            }) {
                drawCircle(g.brush, radius = UNIT, center = Offset.Zero)
            }
        }
    }
}

private fun DrawScope.drawVignette() {
    // An ellipse through the corners, transparent to 35% of the way out.
    val sqrt2 = sqrt(2f)
    withTransform({
        translate(size.width / 2, size.height / 2)
        scale(size.width / 2 * sqrt2 / UNIT, size.height / 2 * sqrt2 / UNIT, pivot = Offset.Zero)
    }) {
        val half = UNIT / sqrt2 + 1f
        drawRect(Vignette, topLeft = Offset(-half, -half), size = Size(2 * half, 2 * half))
    }
}

private fun DrawScope.drawHalo(alpha: Float) {
    if (alpha <= 0f) return
    val w = size.width
    val h = size.height
    val r = 0.65f * sqrt((0.23f * w) * (0.23f * w) + (0.30f * h) * (0.30f * h))
    withTransform({
        translate(w / 2, h / 2)
        scale(r / UNIT, r / UNIT, pivot = Offset.Zero)
    }) {
        drawCircle(Halo, radius = UNIT, center = Offset.Zero, alpha = alpha)
    }
}

/**
 * Full-screen start-up splash: moving teal glows on black, a vignette, and the logo fading in
 * over a soft halo. Everything is drawn from a single frame clock read only in the draw phase,
 * so frames are redrawn without recomposing. [timeline] decides when it fades out; [onDone] runs
 * once it is fully transparent.
 */
@Composable
fun SplashScreen(timeline: SplashTimeline, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val elapsed = remember { mutableLongStateOf(0L) }
    val overlay = remember { mutableFloatStateOf(1f) }
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(timeline) {
        var first = -1L
        while (true) {
            val frame = withFrameMillis { it }
            val now = SystemClock.uptimeMillis()
            if (first < 0) {
                first = frame
                timeline.start(now)
            }
            elapsed.longValue = frame - first
            overlay.floatValue = timeline.overlayAlpha(now)
            if (!timeline.isActive(now)) break
        }
        done()
    }
    Box(modifier.fillMaxSize().graphicsLayer { alpha = overlay.floatValue }) {
        Canvas(Modifier.fillMaxSize()) {
            val t = elapsed.longValue
            drawRect(Color.Black)
            drawGlows(t)
            drawVignette()
            drawHalo(SplashMotion.haloAlpha(t))
        }
        Image(
            painter = painterResource(R.drawable.loophole_logo),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(LOGO_WIDTH_FRACTION)
                .aspectRatio(LOGO_ASPECT)
                .graphicsLayer {
                    val t = elapsed.longValue
                    alpha = SplashMotion.logoAlpha(t)
                    val s = SplashMotion.logoScale(t)
                    scaleX = s
                    scaleY = s
                },
        )
    }
}
