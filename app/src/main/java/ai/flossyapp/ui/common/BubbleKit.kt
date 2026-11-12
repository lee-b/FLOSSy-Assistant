package ai.flossyapp.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp

/**
 * The bubble-and-scrolling kit every conversation surface shares.
 *
 * The chat screen and the wake card are two sizes of the same view: both are a
 * run of bubbles that grows while a reply streams in, and both should behave
 * the same while it happens. The bubble shell ([ChatBubble]) and the
 * bottom-pinning scroll ([BubblePinEffect]) therefore live here once, and both
 * surfaces render through them - the wake card is a mini chat view not by
 * imitating one but by being drawn from the same parts.
 *
 * The scroll algorithm is the chat screen's own, extracted rather than
 * rewritten: while a reply is still arriving the list eases up only as far as
 * the text has grown past the viewport bottom, and when a message just
 * arrived the last bubble is bottom-aligned outright. Chasing every streamed
 * chunk with absolute jumps is what made the list unscrollable mid-reply, and
 * the wake card inherits the fixed behaviour along with the code.
 */

/**
 * Returns true when the list is scrolled to (or very near) the bottom, i.e. the
 * last item is visible or the second-to-last item is fully in view.
 */
internal fun androidx.compose.foundation.lazy.LazyListState.isNearBottom(): Boolean {
    val layout = layoutInfo
    if (layout.totalItemsCount == 0) return true
    // Consider "near the bottom" when the last visible item is within the last
    // two items of the list.
    val lastVisibleIndex = layout.visibleItemsInfo.last().index
    return lastVisibleIndex >= layout.totalItemsCount - 2
}

/**
 * Keeps the bubble list pinned to its bottom the way the chat screen pins it.
 *
 * [lastItemIndex] is the index of the item a new message lands on, and the
 * vararg [growth] are the values that change while text streams in, so the
 * effect re-runs on every chunk the same way the chat screen's effect does.
 *
 * `isNearBottom()` reads the CURRENT layoutInfo. On the recomposition that
 * appends a message, that snapshot is often stale - which is exactly "were we
 * at the bottom before the new bubble". If the user had scrolled back through
 * the reply, they are not near the bottom and the list is left alone.
 *
 * While streaming, the list scrolls by only the amount the text has grown past
 * the viewport bottom: each new line eases the list up one line, so the user
 * reads the output as it arrives. An absolute bottom-align here would jump by
 * whole paragraphs. When a message just arrived (not streaming), the last
 * bubble is bottom-aligned outright: scrollToItem pins the item's top, so
 * passing Int.MAX_VALUE as scrollOffset pushes the item's start past the
 * viewport top and the list clamps at maximum scroll, leaving the bubble's
 * BOTTOM at the viewport bottom - tall and short bubbles alike.
 */
@Composable
fun BubblePinEffect(
    listState: LazyListState,
    lastItemIndex: Int,
    streaming: Boolean,
    vararg growth: Any?
) {
    LaunchedEffect(lastItemIndex, streaming, *growth) {
        if (lastItemIndex < 0) return@LaunchedEffect
        if (!listState.isNearBottom()) return@LaunchedEffect
        if (streaming) {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()
            if (lastVisible != null) {
                val overflow = lastVisible.offset + lastVisible.size - layout.viewportSize.height
                if (overflow > 0) {
                    listState.scroll { scrollBy(overflow.toFloat()) }
                }
            }
        } else {
            listState.scrollToItem(lastItemIndex, Int.MAX_VALUE)
        }
    }
}

/**
 * The one bubble shell both conversation surfaces draw through.
 *
 * The look is the chat screen's MessageBubble container: a full-width rounded
 * card, [androidx.compose.material3.ColorScheme.primaryContainer] for the
 * user's turn and [androidx.compose.material3.ColorScheme.surfaceVariant] for
 * the assistant's, with the same inner padding and text style. The wake card
 * being a mini chat view starts here - the bubble is literally the same
 * composable, not a copy of it.
 *
 * The extras the chat screen hangs off its bubbles (pin headers, reasoning
 * sections, read-aloud buttons) stay in the chat screen: this shell is the
 * visual they are all hung on.
 */
@Composable
fun ChatBubble(text: String, isUser: Boolean, streaming: Boolean = false, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isUser)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium
            )
            if (streaming) {
                Spacer(Modifier.height(2.dp))
                // The streaming caret: a small bar that blinks while a reply is
                // still arriving. The wake card used to blink it by repaint
                // parity; a card drawn in Compose blinks on its own clock, so
                // the session only has to say that the reply is streaming.
                val transition = rememberInfiniteTransition(label = "caret")
                val on by transition.animateFloat(
                    initialValue = 1f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 350, easing = LinearEasing),
                        repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                    ),
                    label = "caretAlpha"
                )
                Box(
                    Modifier
                        .width(24.dp)
                        .height(3.dp)
                        .alpha(on)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
