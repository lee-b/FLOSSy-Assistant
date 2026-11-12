package ai.flossyapp.ui.wake

import ai.flossyapp.ui.common.BubblePinEffect
import ai.flossyapp.ui.common.CallAvatar
import ai.flossyapp.ui.common.CallPose
import ai.flossyapp.ui.common.ChatBubble
import ai.flossyapp.ui.theme.FLOSSyAssistantTheme
import ai.flossyapp.ui.theme.GRUVBOX_ERROR
import ai.flossyapp.ui.theme.GRUVBOX_ON_SURFACE
import ai.flossyapp.ui.theme.GRUVBOX_SURFACE_LOW
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * What one paint of the wake card shows.
 *
 * The session owns the choreography (window cutting, the climb and sink, the
 * palette) and hands the card a fresh snapshot on every show; the content
 * composable below renders whatever the newest snapshot says. A data class per
 * paint is what lets Compose - not the session - decide what to redraw: the
 * session changes state in one assignment and the card follows.
 *
 * The lambdas carry the two ways out of the popup; they are wired once by the
 * session and never change, but they ride in the snapshot because the snapshot
 * is the card's whole world.
 */
data class WakeCardSnapshot(
    val statusLabel: String,
    val statusColor: Int,
    val chipColor: Int,
    val request: String,
    val reply: String,
    val streaming: Boolean,
    // What the bot is doing right now: the pose carries the "I am alive and
    // working" signal, most importantly while thinking - a still card reads
    // as broken, a moving one reads as busy.
    val pose: CallPose,
    val alpha: Float,
    val fontScale: Float,
    val onOpenApp: () -> Unit,
    val onEndChat: () -> Unit
)

/** The state the session paints into and the card reads from. */
class WakeCardState {
    var snapshot by mutableStateOf<WakeCardSnapshot?>(null)
}

/**
 * The wake card as a mini version of the main chat view.
 *
 * It is drawn from the same parts the chat screen is - [ChatBubble] for the
 * turns and [BubblePinEffect] for the bottom-pinning scroll - under the same
 * Gruvbox theme, at a smaller size via [WakeCardSnapshot.fontScale]. The
 * bubbles wrap themselves, so there is no line-cutting constant here at all:
 * the width the text may fill is the width the card is given, which is the
 * same deal the chat view runs on.
 */
@Composable
fun WakeCardContent(state: WakeCardState) {
    val snap = state.snapshot ?: return
    FLOSSyAssistantTheme(darkTheme = true) {
        Column(
            Modifier
                .fillMaxSize()
                .alpha(snap.alpha)
        ) {
            StatusRow(snap)
            Spacer(Modifier.height(sdp(8f, snap)))
            // The same bot the chat screen rides: while the reply is being
            // prepared the scanning pose is the card's only sign of life, so
            // the card never looks dead while thinking.
            CallAvatar(pose = snap.pose, size = sdp(56f, snap), modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(sdp(8f, snap)))
            BubbleThread(snap, Modifier.weight(1f).fillMaxWidth())
            Spacer(Modifier.height(sdp(10f, snap)))
            ButtonRow(snap)
        }
    }
}

/** The status line: the phase chip and the label beside it, as the chat screen's header is. */
@Composable
private fun StatusRow(snap: WakeCardSnapshot) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(sdp(10f, snap))
                .background(Color(snap.chipColor), CircleShape)
        )
        Spacer(Modifier.width(sdp(8f, snap)))
        Text(
            text = snap.statusLabel,
            color = Color(snap.statusColor),
            // The status is the card's one line of prose - "Thinking…" at a
            // whisper was exactly what the user called out - so it rides a
            // larger base than the buttons and scales with the physical
            // display like everything else.
            fontSize = ssp(16f, snap)
        )
    }
}

/**
 * The conversation itself: the user's request and the assistant's reply as the
 * same bubbles the chat view draws, in a lazily-scrolled run that pins to the
 * bottom while the reply streams - the chat screen's own scroll behaviour,
 * from the shared kit.
 */
@Composable
private fun BubbleThread(snap: WakeCardSnapshot, modifier: Modifier) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = modifier
    ) {
        if (snap.request.isNotBlank()) {
            item { ChatBubble(text = snap.request, isUser = true) }
        }
        if (snap.reply.isNotBlank()) {
            item { ChatBubble(text = snap.reply, isUser = false, streaming = snap.streaming) }
        }
    }
    // The reply is the last item when it exists; otherwise the request is.
    val lastIndex = if (snap.reply.isNotBlank()) 1 else if (snap.request.isNotBlank()) 0 else -1
    BubblePinEffect(listState, lastIndex, snap.streaming, snap.request.length, snap.reply.length)
}

/** The two ways out of the popup: opening the full app, and ending the exchange. */
@Composable
private fun ButtonRow(snap: WakeCardSnapshot) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(sdp(8f, snap))
    ) {
        Button(
            onClick = snap.onOpenApp,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(GRUVBOX_SURFACE_LOW),
                contentColor = Color(GRUVBOX_ON_SURFACE)
            )
        ) { Text("Open app", fontSize = ssp(14f, snap)) }
        Button(
            onClick = snap.onEndChat,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(GRUVBOX_ERROR),
                contentColor = Color(GRUVBOX_ON_SURFACE)
            )
        ) { Text("End chat", fontSize = ssp(14f, snap)) }
    }
}

/**
 * Design lengths re-scaled by the session's measured display factor.
 *
 * The card's geometry belongs to the session's design constants, so the
 * content scales its dp/sp values by the same factor the session scales its
 * own by - one source of truth for size, applied twice.
 */
private fun sdp(base: Float, snap: WakeCardSnapshot): Dp = (base * snap.fontScale).dp
private fun ssp(base: Float, snap: WakeCardSnapshot): TextUnit =
    (base * snap.fontScale).sp
