package ai.flossyapp.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The poses the call avatar performs, one per thing a voice call does.
 *
 * The mapping is drawn from the personality the poses have to carry rather
 * than from the pipeline's states, so a surface translates its own phase into
 * one of these and the avatar stays the same character in both:
 * [CallPose.CONNECTING] (breathing, waiting on the network), [CallPose.LISTENING]
 * (leaning in toward the user), [CallPose.THINKING] (the scanning head tilt
 * while the reply is being prepared) and [CallPose.SPEAKING] (a rhythmic pulse
 * riding the reply).
 */
enum class CallPose { CONNECTING, LISTENING, THINKING, SPEAKING }

/**
 * The one animated bot face both voice surfaces share.
 *
 * Personality in voice UIs comes from motion, not detail: a round body and
 * two eyes that move meaningfully read as a character doing something, and a
 * mouth is the detail that drifts into uncanny, so there is none. Every frame
 * is a transform (scale, rotation, translation, alpha) on two shapes - nothing
 * is ever redrawn, so the animation cannot glitch into a broken frame and
 * costs nothing to render.
 *
 * The one rule the poses all honour: **never fully still while busy**. A
 * breathing idle is what reads as alive; stillness is what reads as broken,
 * which is exactly the complaint that produced this avatar.
 */
@Composable
fun CallAvatar(pose: CallPose, size: Dp, modifier: Modifier = Modifier) {
    // The transition is read through pose-dependent specs and amplitudes, so
    // a pose change is a change of numbers, never of machinery - the same
    // oscillators keep running and the character simply switches move.
    val body = rememberInfiniteTransition(label = "avatarBody")
    val blinker = rememberInfiniteTransition(label = "avatarBlink")

    // One oscillator per pose drives every transform through pose-dependent
    // amplitude, so switching pose changes only how the value is read, never
    // the animation machinery. The speed differs per pose: speaking pulses
    // quickly, thinking scans slowly, connecting breathes slowest.
    val wave by body.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (pose) {
                    CallPose.SPEAKING -> 160
                    CallPose.LISTENING -> 700
                    CallPose.THINKING -> 1200
                    CallPose.CONNECTING -> 1800
                },
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "wave"
    )
    val blink by blinker.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                // Held open nearly the whole cycle, then one quick blink: the
                // cheapest "alive" signal there is.
                durationMillis = 2600
                1f at 0
                1f at 2200
                0.15f at 2280
                1f at 2360
            }
        ),
        label = "blink"
    )

    // The body's amplitude and the eyes' gaze per pose; everything is written
    // as a multiple of the wave so a pose is a set of numbers, not new code.
    val (breathAmp, tiltDeg, leanIn, scanAmp) = when (pose) {
        CallPose.CONNECTING -> Quadruple(0.03f, 0f, 0f, 0f)
        CallPose.LISTENING -> Quadruple(0.06f, 2f, 1f, 0f)
        CallPose.THINKING -> Quadruple(0.02f, 7f, 0f, 1f)
        CallPose.SPEAKING -> Quadruple(0.08f, 0f, 0f, 0f)
    }
    val scan = scanAmp

    val scale = 1f + breathAmp * wave + leanIn * 0.04f
    val rotation = tiltDeg * (wave - 0.5f) * 2f
    val bob = leanIn * (wave - 0.5f) * (size.value * 0.06f)

    val bodyColor = MaterialTheme.colorScheme.primary
    val eyeColor = MaterialTheme.colorScheme.onPrimaryContainer

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            // The whole face is one transform target: the body scales, tilts
            // and bobs as one, and the eyes ride inside it.
            modifier = Modifier.graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                rotationZ = rotation,
                translationY = bob
            )
        ) {
            // The head: a soft rounded square rather than a circle, so a tilt
            // reads as a head turning instead of a ball rolling.
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(size * 0.42f))
                    .background(bodyColor),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    // The eyes lead the motion: while thinking they glide
                    // side to side and ride a little up, the glance that
                    // reads as searching - the pose carries the character.
                    modifier = Modifier.graphicsLayer(
                        translationX = scan * (wave - 0.5f) * size.value * 0.24f,
                        translationY = -scan * size.value * 0.04f
                    )
                ) {
                    Eye(eyeColor, size * 0.13f, blink)
                    Spacer(Modifier.width(size * 0.18f))
                    Eye(eyeColor, size * 0.13f, blink)
                }
            }
        }
    }
}

/** One eye: a plain dot, blinking only in alpha. */
@Composable
private fun Eye(color: Color, size: Dp, blink: Float) {
    Box(
        modifier = Modifier
            .graphicsLayer(alpha = blink)
            .size(size)
            .clip(RoundedCornerShape(size / 2))
            .background(color)
    )
}

/** A tiny named tuple so the pose table above stays readable. */
private data class Quadruple(
    val breath: Float,
    val tilt: Float,
    val lean: Float,
    val scan: Float
)
