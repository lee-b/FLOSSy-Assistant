package ai.flossyapp.service

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import ai.flossyapp.ui.common.CallPose
import ai.flossyapp.ui.theme.TRANSPARENT
import ai.flossyapp.ui.theme.GRUVBOX_ON_SURFACE
import ai.flossyapp.ui.theme.GRUVBOX_ON_SURFACE_MUTED
import ai.flossyapp.ui.theme.GRUVBOX_PRIMARY
import ai.flossyapp.ui.theme.GRUVBOX_ERROR
import ai.flossyapp.ui.theme.WAKE_CARD_SURFACE
import ai.flossyapp.ui.theme.WAKE_CARD_ELEVATION
import ai.flossyapp.ui.theme.GRUVBOX_SURFACE_LOW
import ai.flossyapp.ui.theme.WAKE_USER_BUBBLE
import ai.flossyapp.ui.theme.WAKE_REPLY_BUBBLE
import ai.flossyapp.ui.theme.WAKE_PHASE_LISTENING
import ai.flossyapp.ui.theme.WAKE_PHASE_THINKING
import ai.flossyapp.ui.theme.WAKE_PHASE_ANSWERING
import ai.flossyapp.ui.theme.WAKE_DIVIDER
import ai.flossyapp.ui.wake.WakeCardContent
import ai.flossyapp.domain.model.ToolCallRecord
import ai.flossyapp.ui.wake.WakeCardSnapshot
import ai.flossyapp.ui.wake.WakeCardState
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * The key the wake service uses to hand the user's request to a session.
 *
 * The two halves of the pipeline never share memory, so the exchange travels
 * under these keys in the bundle the wake service passes to
 * [VoiceInteractionService.showSession][showSession].
 */
const val SESSION_REQUEST_KEY = "request"

/**
 * The key the wake service uses to hand the spoken reply to a session.
 */
const val SESSION_REPLY_KEY = "reply"

/**
 * The key the wake service uses to say which half of the exchange the user is in.
 * The window is raised the moment the wake phrase is matched, before any words
 * exist, so a session has to be able to render an exchange that has no text yet.
 */
const val SESSION_STATE_KEY = "state"

/**
 * The key the wake service uses to say how far through the card's climb or sink
 * this show is, as text from "0" (at the bottom edge, no ink) to "1" (settled).
 *
 * There is no animation API to reach for: `View.startAnimation` and `View.setAnimation`
 * exist but their argument type is `android.animation.Animation`, which this toolchain
 * can neither name nor construct - `android.animation.Animation`, every
 * `android.animatable.*` spelling and `android.app.Activity.loadAnimation` are
 * unresolved, and the only members of `android.animation` that do resolve
 * ([android.animation.Animator] and [android.animation.Keyframe]) are abstract
 * classes with no constructor. Motion is therefore a sequence of shows, and this
 * key is what turns that sequence into an animation rather than a handful of jumps:
 * the wake service asks for the window many times in a row and hands each show a
 * position along the curve, so the card translates up from the bottom edge and fades
 * in, and the same ladder run backwards under [SESSION_STATE_CLOSE] sinks it.
 *
 * The wake service owns the timing and this file only ever reads a position, so the
 * two halves cannot drift apart over a frame count they each hard-code. The position
 * travels as text because every other key here is text and a session parses what it
 * is handed.
 */
const val SESSION_ANIM_KEY = "anim"

/**
 * The tool calls of the current exchange, carried into the session as one
 * JSON document (the same wire the other keys use). The card draws one
 * compact row per call with tap-to-expand input and output, so the tool
 * step between question and answer is visible hands-free too.
 */
const val SESSION_TOOLCALLS_KEY = "tool_calls"

/**
 * The window is being raised and the card is still climbing from the bottom edge.
 *
 * The rise is a sequence of shows rather than an animation - see [SESSION_ANIM_KEY]
 * for why there is no animation API here - so the wake service asks for the
 * window several times in a row before the exchange itself has anything to show.
 */
const val SESSION_STATE_RISE = "rise"

/** The phrase was heard; the app is waiting for the request. */
const val SESSION_STATE_LISTENING = "listening"

/** The request is known and the answer is being produced. */
const val SESSION_STATE_REQUEST = "request"

/** The request and the reply are both known. */
const val SESSION_STATE_REPLY = "reply"

/**
 * The reply is arriving from the LLM as it is written, so the answer bubble
 * grows with the text that has arrived so far.
 */
const val SESSION_STATE_STREAMING = "streaming"

/** The reply is being spoken by the TTS endpoint. */
const val SESSION_STATE_SPEAKING = "speaking"

/**
 * The exchange has been closed, so the session is asked to show nothing and to
 * tear its window down.
 */
const val SESSION_STATE_CLOSE = "close"

/*
 * The card's colours come from the shared palette module (see the imports above),
 * which is the one set of Gruvbox tokens both halves of the app name.
 *
 * A session is drawn by the system, so it cannot read a Compose MaterialTheme -
 * that is why the palette lives as plain ARGB integers rather than as the colour
 * roles [Theme] builds, and why this file can depend on it without carrying any
 * Compose into a process that must not have any.
 *
 * The card surface is translucent rather than solid: the window is transparent
 * and the platform paints nothing behind the card, so the screen the device is
 * showing reads through it and the exchange stays a floating chat card instead
 * of a panel over the display.
 */

/**
 * The short edge of the display the design values below were measured on.
 *
 * A session cannot ask for a density: `getDisplayMetrics`,
 * `android.graphics.DisplayMetrics`, `android.util.Metrics`,
 * `android.os.DisplayManager`, `android.graphics.types.*`,
 * `android.content.Configuration`/`Resolution` and `android.os.FileUri` are all
 * unresolved here, and the [android.view.Window] the session is handed answers
 * `setLayout` but exposes none of its own geometry (`width`, `height`, `left`,
 * `top`, `right`, `bottom`, `frame`, `getParameters` are all unresolved). The one
 * display measurement that does work is indirect: leave the window as the
 * platform builds it - a `fillxfill` surface over the whole display - and read
 * back the card, which the window stretches to fill. That measured short edge
 * divided by this baseline is [scale], and it is the density-independent basis
 * available here: a physical edge, not a pixel count assumed to mean the same
 * thing on every screen.
 */
private const val BASE_SHORT_EDGE = 1840

/**
 * How far the design may shrink or grow relative to that reference display.
 *
 * Taken literally, a small phone would get a card whose text is unreadable across
 * a room and a large display would get a card taller than an exchange needs, so
 * the scale is clamped: the card stays legible and the proportions stay sane at
 * both ends. The clamp is what the fallback relies on, too - an unmeasured
 * display leaves the scale at 1, which is the shipped design.
 */
private const val MIN_SCALE = 0.6f
private const val MAX_SCALE = 1.4f

/**
 * How big the card is.

 *
 * A session cannot read the display: `android.graphics.DisplayMetrics`,
 * `getDisplayMetrics` and `android.widget.internal` (a `Filler` spacer) are all
 * unreachable, `View.setFillDimensions` does not exist, and a forced minimum
 * (`setMinimumWidth`/`setMinimumHeight`) is not honoured by the window's measure
 * pass - forcing one left the card in the window's top-left corner, which is what
 * the user reported. The width therefore comes from padding, which is the sizing
 * API the measure pass does honour: a `ruler` child of the card carries
 * [CARD_WIDTH] as left-plus-right padding and nothing else, so it measures to
 * exactly that width, contributes no height, and pins the card to it whatever
 * the text inside it happens to be.
 *
 * The numbers below are a **design**: they are the values measured on the
 * reference display, whose short edge is [BASE_SHORT_EDGE] px. The session
 * measures the display it is actually running on (see [measureDisplay]) and
 * multiplies the whole set by one [scale], so the card keeps its proportions on
 * a phone as on the tab it was tuned on - a card built from these numbers
 * literally is what made the sides of the card get cut off on smaller displays.
 *
 * The scale comes from the **short** edge, which is also what makes the card the
 * same size in landscape and portrait: a width that fits across the short edge
 * still fits when the device turns, where a width taken from the long edge would
 * be wider than the screen there and have its sides cut off.
 */
private const val CARD_WIDTH = 1560
private const val USER_TEXT_SIZE = 20f
private const val REPLY_TEXT_SIZE = 22f
private const val STATUS_SIZE = 17f
private const val CARD_SIDE_PADDING = 44
private const val CARD_TOP_PADDING = 32
private const val CARD_BOTTOM_PADDING = 32
private const val BUBBLE_PADDING = 32

/**
 * How tall a line of text and the status line actually are, which is what the
 * card's height is built from.
 *
 * A legacy [android.widget.TextView] reports no intrinsic size, so the session
 * cannot ask the views how tall the text they hold is - the only heights it
 * knows are the ones it gives them. Measured on the tab, the view draws at about
 * **twice** the pixel size it is handed: at a 44 px setting the glyphs came out
 * ~88 px tall, a character advanced ~43 px, and the line box measured 125 px.
 * That is why the sizes above are half what the card should read as - a 22 px
 * setting is a ~44 px card font - and these are the resulting line boxes, about
 * 2.8 times the setting rather than the 1.2 times Roboto's own ascent, descent
 * and leading suggest, because the view adds its internal padding to the font
 * box. Underestimate these and the last line of a reply is cut off at the bottom
 * edge of the card, which is exactly what the earlier build showed.
 */
private const val LINE_HEIGHT = 62
private const val STATUS_HEIGHT = 48

/**
 * The height the card rests at, whatever is inside it.
 *
 * Without this the card is exactly as tall as its content, which is nothing at
 * the start of an exchange: the card measured 0, then 68, then 0 across the rise
 * shows, so the panel jumped instead of climbing, and a two-word request sat in a
 * sliver far too short to read as a chat card. The window is cut to at least this
 * much card, so the card is [CARD_HEIGHT] until the text genuinely needs more,
 * and only then does it grow.
 */
private const val CARD_HEIGHT = 620

/**
 * The tallest the card may become when an answer genuinely needs it.
 *
 * A long reply streams into the card line by line, and without a ceiling a
 * rambling answer turns the card back into a panel over the whole display. This
 * is set to the height [neededHeight] asks for at the fullest exchange the card
 * is allowed to show, so the last line of a reply is never cut off by the ceiling
 * itself - only by [REPLY_MAX_LINES].
 */
private const val MAX_CARD_HEIGHT = 960

/**
 * How much of the window the platform keeps for itself around the card.
 *
 * Measured on the tab: whatever height the window is cut to, the card inside it
 * measures exactly that less this. The card is stretched to fill the window, so
 * this is the only room the card cannot claim, and it is why the window - not the
 * card - decides how tall the card is.
 */
private const val WINDOW_OVERHEAD = 96

/**
 * How far the card floats above the bottom edge of the display.
 *
 * The window is pinned to the bottom edge, so the room under the card is not the
 * window's - it is the stage's own. A bare [android.view.View] carrying padding
 * measures **0** height, which is why the earlier build sat flush against the
 * edge; a `LinearLayout` with no children and top padding does measure to that
 * padding - the same honoured trick the ruler uses for width - so the inset is a
 * `foot` under the card. It is what keeps the card clear of the gesture bar,
 * which the earlier build drew straight through the bottom of the card.
 */
private const val BOTTOM_MARGIN = 56

/**
 * How wide the layer of ink under the card is, which is what reads as elevation.
 *
 * A session has no shadow API and [GradientDrawable] takes no stroke, so depth is
 * layered translucency: the card wears a faint, wider layer and the panel sits on
 * top of it, which reads as a sheet lifted off the screen rather than a flat
 * cut-out of it.
 */
private const val CARD_ELEVATION = 10

/**
 * The width the card's children may use, which is what a line of text and a
 * bubble are both measured against.
 *
 * A bubble that only spans its own longest line makes the card jump wider every
 * time a longer sentence arrives, so each bubble carries a ruler of this width
 * and the card is the same shape for the whole exchange.
 */
private const val INNER_WIDTH = CARD_WIDTH - CARD_SIDE_PADDING * 2

/**
 * The width a bubble's text may use, which is what a line is wrapped to.
 *
 * A bubble spans the card's inner width so the card never jumps wider as longer
 * sentences arrive, and its text is inset by the bubble's own padding - so the
 * text budget is that width less the padding, not the card width.
 */
private const val BUBBLE_TEXT_WIDTH = INNER_WIDTH - BUBBLE_PADDING * 2

/**
 * The size of the window the system raises for the exchange.
 *
 * The window used to be left exactly as the platform builds it, which is a
 * `fillxfill` surface covering the whole display: the platform paints its own
 * rounded panel behind whatever a session draws, so the exchange looked like a
 * large white box over the screen and no amount of styling the card could change
 * that. Neither half of that is fixed, though: [VoiceInteractionSession.getWindow][window]
 * hands back the session's [android.app.Dialog], and that Dialog's
 * [android.view.Window] answers `setLayout` **and** `setBackgroundDrawable`, so
 * the window can be cut to the card *and* the panel it carries can be cleared -
 * handing it a transparent shape removes the cream panel entirely, which is what
 * lets the screen underneath read through the card.
 *
 * The height is worked out from the text about to be drawn, never read back off
 * the card, and [MAX_WINDOW_HEIGHT] is the ceiling that keeps a long answer from
 * turning the card back into a panel over the display.
 */
private const val CARD_OUTER_WIDTH = CARD_WIDTH + CARD_SIDE_PADDING * 2

/**
 * How big the drawn status indicator is.
 *
 * The glyph that used to lead the status line was a character the typeface had
 * to carry, so it drew whatever the font drew - a thin ring that read as part of
 * the words rather than as the state of the exchange. The indicator is now a
 * childless `LinearLayout` wearing a rounded shape in the phase's colour: a
 * background fills the whole widget, so the chip's padding **is** its size, which
 * is the same honoured trick the ruler and the foot use for measuring.
 */
private const val CHIP_SIZE = 26

/**
 * How thick the rule between the two bubbles is.
 *
 * The bubbles are the same rounded sheet on the same card, so across a room they
 * read as one block of ink; a faint rule between them is what separates the turn
 * the user spoke from the answer, without a stroke API or a second panel.
 */
private const val DIVIDER_HEIGHT = 6

/**
 * How thick the underline beneath a still-arriving answer is.
 *
 * While the reply is streaming, the answer block looks the same whether the last
 * chunk landed a heartbeat ago or the model has gone quiet, so the card gives no
 * hint that the sentence is unfinished. A bar under the answer that toggles
 * between shows reads as a cursor: a session is drawn as a sequence of shows and
 * has no animation API, so the blink is a counter advanced in [onPrepareShow] and
 * read in [paint]. The bar is always padded - ink or no ink - so toggling it
 * never jumps the card.
 */
private const val CARET_HEIGHT = 8

/**
 * The two ways out of the popup, drawn as buttons at the card's foot.
 *
 * One opens the full app (the assistant metaphor's "go to the assistant"), the
 * other ends the exchange in the same red the app chat view's End Call wears -
 * both surfaces read as one assistant. The height is the design value at
 * [BASE_SHORT_EDGE]; the radius matches the reply bubble so the buttons read
 * as part of the card rather than as system chrome.
 */
private const val BUTTON_HEIGHT = 72

// The room the bot face takes between the status and the bubbles, in design
// units - scaled like every other length by the display factor.
private const val AVATAR_HEIGHT = 200
private const val BUTTON_RADIUS = 26f

/**
 * How rounded the card and the bubbles inside it are.
 *
 * A flat `setBackgroundColor(Int)` paints a sharp rectangle, which is what the
 * user saw as "the message boxes are sharp squares". The rounded route that
 * resolves here is [GradientDrawable]: it constructs with no arguments, takes
 * [GradientDrawable.setColor][setColor] and a **Float**
 * [GradientDrawable.setCornerRadius][setCornerRadius] (the Int spelling is
 * rejected with "Argument type mismatch: actual type is 'Int', but 'Float' was
 * expected"), and a `View` accepts it through `setBackgroundDrawable`/`background`
 * - the same setters that are unresolved on a [VoiceInteractionSession] itself
 * work on the widgets inside it, which is why the card can be rounded while the
 * platform's own panel behind it cannot be touched.
 */
private const val CARD_RADIUS = 28f
private const val USER_RADIUS = 16f
private const val REPLY_RADIUS = 26f

/**
 * The one gap between blocks of the card: status to user bubble, bubble to rule,
 * rule to answer bubble.
 *
 * Every block is separated by the same room, which is what gives the card a
 * vertical rhythm rather than a stack of boxes that each carry their own. It is
 * padding on the block above rather than a spacer child, because the card builds
 * its children once and the structure cannot grow between shows.
 */
private const val BUBBLE_GAP = 24

/**
 * The gap between the status chip and the label beside it.
 *
 * A row lays its children flush against each other, so the breathing room the
 * card's own padding would give is nothing here. The gap is left padding on the
 * label rather than right padding on the chip, because a background fills a
 * chip's whole widget - padding on the chip would widen the ink, not the gap.
 */
private const val STATUS_DOT_GAP = 18

/**
 * The main-axis orientation every container in the card is forced to.
 *
 * The one-argument [android.widget.LinearLayout] constructor does **not** stack
 * children here: measured on the tab, every child of the panel reported the
 * same height - the panel's inner height - and the card's two children each
 * measured the full card height, which is cross-axis stretching, i.e. a
 * horizontal layout. The card therefore painted as one flat band with the
 * bubbles side by side and clipped, and no amount of padding or gravity changed
 * it. AOSP names the orientations as the statics `HORIZONTAL = 0` and
 * `VERTICAL = 1`; Kotlin cannot reach a static member of a Java class
 * (`android.widget.LinearLayout.VERTICAL` is rejected, as is the
 * `android.widget.LinearLayoutHorizontal` spelling), so the value is named here
 * and applied with [android.widget.LinearLayout.setOrientation][setOrientation].
 */
private const val VERTICAL = 1
private const val HORIZONTAL = 0

/**
 * How much text the card can show.
 *
 * A legacy `TextView` never wraps, so a reply is split into lines here and each
 * line gets one of a fixed pool of TextViews - the pool is fixed because the
 * session builds its content view once and reuses it for every later show, so
 * the structure cannot grow with the text.
 *
 * [LINE_CHARS] is chosen so a full line fits [BUBBLE_TEXT_WIDTH]: at [REPLY_TEXT_SIZE] a
 * character is roughly 22 px wide, so 64 characters fill a bubble without
 * overflowing it. The counts are deliberately small - a request is a spoken
 * sentence and an answer is read aloud - because the card must stay a card:
 * [USER_MAX_LINES] plus [REPLY_MAX_LINES] lines is the tallest it is ever allowed
 * to be, and [MAX_WINDOW_HEIGHT] is the ceiling the window is cut to.
 */
private const val USER_MAX_LINES = 2
private const val REPLY_MAX_LINES = 8

/**
 * How many characters one bubble line holds, derived rather than tuned: a
 * character advances about as far as the text size is tall (the measured font
 * metrics above), and a line's room is [BUBBLE_TEXT_WIDTH] - so the count is
 * the width divided by the size. It is deliberately **not** multiplied by the
 * display scale, because the font size scales by the same factor: multiplying
 * both is what under-filled the bubbles on a smaller display (and the shipped
 * constant was hard-coded to one reference screen, which is what made the
 * budget a guess everywhere else).
 */
private const val LINE_CHARS = BUBBLE_TEXT_WIDTH / REPLY_TEXT_SIZE.toInt()

/**
 * The session half of the wake pipeline.
 *
 * A [VoiceInteractionService] can keep FLOSSyAssistant listening while the app is
 * minimized, but everything it can show the user in that state travels in a
 * notification - the app's own Compose overlay only paints inside the app's
 * surface, so it is invisible on the home screen or over another app.
 *
 * A [VoiceInteractionSession] is the opposite half: the system draws it as a
 * window of type TYPE_VOICE_INTERACTION over whatever is on screen, which is the
 * only way the wake chat can be seen outside the app. The two halves never
 * share memory - the session runs in its own process - so the exchange travels
 * in the [Bundle] the wake service passes to
 * [VoiceInteractionService.showSession][showSession].
 *
 * The content view is built from the legacy Java UI classes ([LinearLayout],
 * [TextView]) because that is what a session window accepts:
 * [VoiceInteractionSession.onCreateContentView][onCreateContentView] returns an
 * [android.view.View], not a Compose surface. The card is styled to match the
 * card the app draws itself, and it is the only rendering of the wake chat - the
 * app-level overlay was removed so both halves show the same thing.
 */
class WakeChatSessionService : VoiceInteractionSessionService() {

    /**
     * Create the session that will show the exchange.
     *
     * The session is built around this service as its context, and that is the
     * only context that can work here. [VoiceInteractionSession] starts by asking
     * the context for a window context of type TYPE_VOICE_INTERACTION
     * (createWindowContextIfNeeded), and it does that only when the context is
     * NOT a UI context: an android.app.Activity is annotated @UiContext, so
     * handing one over skips that step entirely and the session's window is
     * attached to a surface that belongs to nothing, which is why the exchange
     * never appears over the home screen or another app. A Service is a
     * ContextWrapper - hence a Context - whose base is null, so it reports
     * isUiContext() false and can answer getSystemService(DisplayManager),
     * which is exactly what the window context needs. AOSP hands this same
     * object to HandlerCaller in onCreate() for the same reason.
     */
    override fun onNewSession(args: android.os.Bundle): VoiceInteractionSession {
        Log.i("WakeChatSessionService", "The system asked for a session: $args")
        return WakeChatSession(this)
    }
}

/**
 * A voice interaction session that shows the wake exchange the system asked for.
 *
 * The context is the session service itself, never an [android.app.Activity]:
 * Activity is annotated @UiContext, which makes
 * [VoiceInteractionSession.createWindowContextIfNeeded][VoiceInteractionSession]
 * keep the context as it is and attach the session window to a surface that
 * belongs to nothing. A Service is a ContextWrapper with no base, so it reports
 * isUiContext() false and the session gets a real TYPE_VOICE_INTERACTION window
 * context from the DisplayManager, which is the surface drawn over every app.
 *
 * The views are built once, in [onCreateContentView], and every later show only
 * mutates them through [paint], because the framework creates the content view
 * the first time it shows a session and hands that same view to its window
 * afterwards - building fresh views per show would leave the reply never drawn.
 */
private class WakeChatSession(private val context: android.app.Service) : VoiceInteractionSession(context) {

    private var state = ""
    private var request = ""
    private var reply = ""

    /**
     * The exchange's tool calls, parsed from the service's JSON document at
     * each show. The card draws one compact row per call; tap-to-expand is
     * the shared kit's own behaviour.
     */
    private val toolCalls = mutableListOf<ToolCallRecord>()

    /**
     * How far through the climb or sink the next show is, from "0" at the bottom
     * edge to "1" settled at rest.
     *
     * The session is created per exchange, so this counts from the first show of
     * an exchange and never needs resetting. One position drives both the height
     * and the ink, which is what ties the slide and the fade into one motion.
     */
    private var riseProgress = 0f

    /**
     * The short edge of the display, in px, as measured from the window the
     * platform lays out for the first show; zero until measured.
     *
     * See [measureDisplay] for how the session gets it at all, and see [scale]
     * for what it is used for. Zero is the fallback: an exchange that never
     * measures draws at the shipped design.
     */
    private var measuredShortEdge = 0

    /**
     * How much bigger the card is than its design, which is what every metric
     * in this file is multiplied by.
     *
     * One factor rather than a per-metric override is the point: the card is a
     * design, and scaling all of it together is what keeps the text inside the
     * bubbles, the bubbles inside the card and the card inside the display in the
     * same relationship on every screen.
     */
    private var scale = 1f

    /**
     * What the next show must draw, wrapped once so the window and the paint
     * agree on how many lines there are.
     */
    private var userShown: List<String> = listOf()
    private var replyShown: List<String> = listOf()

    /**
     * The views the window shows, built once here and only ever mutated after.
     *
     * An empty `TextView` measures nothing and a transparent background paints
     * nothing, so a state that shows no turn contributes no height and no ink.
     *
     * The stage holds only the card: the window is pinned to the bottom edge and
     * stretches the card to fill itself, so the card's height is the window's
     * doing - see [sizeWindow] - and nothing has to be stacked under it.
     */
    private val stage = LinearLayout(context)
    private val card = LinearLayout(context)

    /**
     * The empty strip under the card that keeps it off the bottom edge.
     *
     * It measures to its own top padding and paints nothing, which is the same
     * trick the card's elevation inset uses.
     */
    private val foot = LinearLayout(context)

    /**
     * The exchange the card draws, painted by [WakeCardContent] - the same
     * bubbles and the same bottom-pinning scroll the main chat view draws with.
     *
     * A Compose surface inside a voice-interaction window needs owners the
     * window does not build on its own: a lifecycle and a saved-state registry
     * the recomposer attaches to. The session builds them itself and wires them
     * onto the view before it joins the tree - see [SessionComposeOwner].
     */
    private val cardState = WakeCardState()
    private lateinit var composeCard: ComposeView
    private val composeOwner = SessionComposeOwner()

    /**
     * The shapes the legacy half still paints: the card's surface and its
     * elevation halo, built once and re-tinted per show.
     */
    private var cardShape = rounded(WAKE_CARD_SURFACE, CARD_RADIUS)
    private var elevationShape = rounded(WAKE_CARD_ELEVATION, CARD_RADIUS + CARD_ELEVATION)
    private val clearShape = rounded(TRANSPARENT, CARD_RADIUS)

    /**
     * Records what the platform is about to show.
     *
     * AOSP calls this before it asks for the content view, so the text has to be
     * taken here rather than read where the views are built. A rise frame only
     * advances the climb; every other state settles the card at its resting
     * height, so an exchange that is interrupted mid-climb still ends in the
     * same place. The blink counter advances on every show, whatever the state,
     * so the caret's phase keeps running even when nothing draws it.
     */
    override fun onPrepareShow(args: android.os.Bundle, showFlags: Int) {
        // A show the platform raised itself (the assistant gesture, a voice
        // session the system asked for on its own) carries none of our state
        // keys: the bundle is the system's, not this service's. Such a session
        // is the assistant at its most idle - listening for the user - so an
        // unknown or missing state renders as listening rather than as a fault
        // ("Something went wrong" was the whole card the gesture produced).
        // Our own show() calls always name one of the SESSION_STATE_* values,
        // so they are unaffected by the fallback.
        state = args.getString(SESSION_STATE_KEY) ?: ""
        if (state !in listOf(
                SESSION_STATE_RISE, SESSION_STATE_LISTENING, SESSION_STATE_REQUEST,
                SESSION_STATE_STREAMING, SESSION_STATE_SPEAKING, SESSION_STATE_REPLY,
                SESSION_STATE_CLOSE
            )
        ) {
            state = SESSION_STATE_LISTENING
        }
        request = args.getString(SESSION_REQUEST_KEY) ?: ""
        reply = args.getString(SESSION_REPLY_KEY) ?: ""
        // The tool calls arrive as one JSON document; a corrupt one costs
        // nothing but the rows, never the card itself.
        toolCalls.clear()
        runCatching {
            val doc = org.json.JSONArray(args.getString(SESSION_TOOLCALLS_KEY) ?: "[]")
            for (i in 0 until doc.length()) {
                val node = doc.getJSONObject(i)
                toolCalls.add(
                    ToolCallRecord(
                        name = node.optString("name"),
                        arguments = node.optString("arguments"),
                        result = node.optString("result")
                    )
                )
            }
        }.onFailure { android.util.Log.w("WakeChatSession", "Tool calls unread: ${it.message}") }
        riseProgress = settledProgress(args.getString(SESSION_ANIM_KEY))
        rescale()
        // The bubbles are Compose now, so they wrap themselves; the line counts
        // here are only the window-height estimate [neededHeight] runs on, kept
        // because the window is cut before the card is ever laid out.
        userShown = wrap(request, USER_MAX_LINES)
        replyShown = wrap(reply, REPLY_MAX_LINES)
        sizeWindow()
        paint()
    }

    /**
     * Cuts the window the system raises for the exchange down to the card.
     *
     * The platform builds the session window as a `fillxfill` surface and lays it
     * out to fill the display, then paints its own rounded panel across the whole
     * of it - that panel is why the exchange used to cover the screen as a large
     * white box that no amount of card styling could shrink. The window is
     * reachable from here, though: [window] is the session's [android.app.Dialog]
     * and that Dialog's [android.view.Window] answers `setLayout`, so the window
     * can be sized to the card and pinned to the bottom edge. The panel then
     * measures to the card, and everything outside it is the app underneath -
     * which is what makes the exchange a small card floating over everything
     * rather than a panel over the whole display.
     *
     * AOSP only lays the window out to fill the screen while it is *not* visible
     * (`doShow` calls `ensureWindowAdded` inside `if (!mWindowVisible)`), and it
     * calls [onPrepareShow] before that, so sizing here wins on the first show
     * and is never fought back on the ones that follow.
     *
     * The height is worked out from the text that is about to be drawn rather
     * than read back off the card, because the window's fixed height stretches
     * the card back to fill it: deriving the one from the measured other is a
     * feedback loop, and it is what made the card grow by 54 px on every show
     * forever. [neededHeight] is the truth, [CARD_HEIGHT] is the floor it rests
     * at, and [MAX_WINDOW_HEIGHT] is the ceiling a long answer may not pass.
     */
    private fun sizeWindow() {
        val surface = windowSurface() ?: return
        val needed =
            if (state == SESSION_STATE_CLOSE && riseProgress <= 0f) 0
            else neededHeight(userShown.size, replyShown.size)
        val risen = riseHeight()
        val wanted = if (needed > risen) needed else risen
        val limit = px(MAX_CARD_HEIGHT)
        val tall = if (wanted > limit) limit else wanted
        val ring = px(CARD_ELEVATION) * 2
        // The window is bottom-anchored, so the real navigation inset is what
        // separates the card from the screen's bottom edge - not a guess (see
        // readBottomInset). The ceiling scales the same way, so a taller bar
        // than the design guessed does not silently clip the card.
        val cut = tall + WINDOW_OVERHEAD + ring + bottomInsetPx
        val ceiling = px(MAX_CARD_HEIGHT + WINDOW_OVERHEAD) + bottomInsetPx + ring
        val height = if (cut > ceiling) ceiling else cut
        surface.setLayout(px(CARD_OUTER_WIDTH) + ring, height)
        surface.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        surface.setBackgroundDrawable(clearShape)
        Log.i(
            "WakeChatSessionService",
            "Cut the window to ${px(CARD_OUTER_WIDTH) + ring} by ${height} for a ${tall}-tall card at $riseProgress (scale $scale)"
        )
    }

    /**
     * The window the system raised for the exchange, or null until it exists.
     *
     * [window] is the session's [android.app.Dialog] and that Dialog answers
     * [android.view.Window], which is the only object here that takes
     * `setLayout` - the session itself does not answer it.
     */
    private fun windowSurface(): android.view.Window? {
        val dialog = window
        if (dialog == null) {
            Log.i("WakeChatSessionService", "No window yet, so nothing to cut")
            return null
        }
        val surface = dialog.window
        if (surface == null) {
            Log.i("WakeChatSessionService", "The dialog carries no window to cut")
            return null
        }
        return surface
    }

    /**
     * The current scale, applied to a design length.
     *
     * Every metric in this file is a design value at [BASE_SHORT_EDGE]; this is
     * the one conversion between that design and the display measured here.
     */
    private fun px(base: Int): Int = (base * scale).toInt()

    /**
     * The current scale, applied to a design text size or corner radius.
     */
    private fun pt(base: Float): Float = base * scale

    /**
     * Re-derives the scale from the measured display, and rebuilds the shapes at
     * the scaled radii.
     *
     * Called on every show rather than once, because the measurement lands
     * between shows: the first show measures and the one after it is the first
     * that can draw at the real size. Re-deriving is cheap and idempotent, and an
     * exchange that never measured leaves the shipped design alone.
     */
    /**
     * The bottom inset Android actually wants at the screen edge, in real px.
     *
     * The card is anchored at the screen bottom, so whatever the system draws
     * there - a gesture bar, a three-button bar, nothing at all - claims space
     * the card's foot may not cover. [BOTTOM_MARGIN] was a design guess at
     * that claim, and a wrong one on every real device: a gesture bar is
     * taller than it, a three-button one is over twice it, which pushed the
     * button row into the nav bar's territory (the card arrived without its
     * buttons), and a device with no bar at all was over-reserved. The
     * display's own insets are the truth - read here the same way [rescale]
     * measures the display, off the WindowManager's maximum metrics, which
     * carry the whole display's insets with no layout timing involved.
     */
    private var bottomInsetPx = 0

    /**
     * Reads the real bottom navigation inset, with the design guess as the
     * fallback for a display that reports none of it (the shipped [BOTTOM_MARGIN]
     * is then the safest reservation a guess can be).
     */
    private fun readBottomInset(): Int {
        val wm = runCatching {
            context.getSystemService(android.view.WindowManager::class.java)
        }.getOrNull() ?: return px(BOTTOM_MARGIN)
        val insets = runCatching { wm.maximumWindowMetrics.windowInsets }.getOrNull()
            ?: runCatching { wm.currentWindowMetrics.windowInsets }.getOrNull()
            ?: return px(BOTTOM_MARGIN)
        val bottom = insets
            .getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
        return if (bottom > 0) bottom else px(BOTTOM_MARGIN)
    }

    private fun rescale() {
        // The scale comes from the display, and the display is measured here
        // directly: the indirect route - leaving the first window uncut so a
        // stretched card could be read back in [onShow] - never worked, because
        // onShow ran before the card was laid out and its width read 0. The
        // exchange then drew at the shipped design for a 1840-px reference on a
        // 1080-px phone: the whole 1668-px card went off the right edge, which
        // is exactly the clipping the user reported. A session's context is a
        // service, and any service can answer its own WindowManager, whose
        // maximum metrics are the real display - no layout timing involved.
        if (measuredShortEdge <= 0) {
            val bounds = runCatching {
                context.getSystemService(android.view.WindowManager::class.java)
                    .maximumWindowMetrics
            }.getOrNull()
            if (bounds != null) {
                val rect = bounds.bounds
                measuredShortEdge = minOf(rect.width(), rect.height())
                Log.i(
                    "WakeChatSessionService",
                    "Measured the display directly: ${rect.width()}x${rect.height()}, " +
                        "short edge $measuredShortEdge px"
                )
            } else {
                Log.i(
                    "WakeChatSessionService",
                    "The display could not be measured, so the card stays at the shipped design"
                )
            }
        }
        if (measuredShortEdge <= 0) return
        val raw = measuredShortEdge.toFloat() / BASE_SHORT_EDGE
        val target = if (raw < MIN_SCALE) MIN_SCALE else if (raw > MAX_SCALE) MAX_SCALE else raw
        if (target == scale && bottomInsetPx > 0) return
        scale = target
        // The bottom inset is display-owned, not scale-owned (it comes off the
        // display's own metrics in real px), but it is read with the scale as
        // its fallback, so it refreshes here - the same every-show cadence.
        bottomInsetPx = readBottomInset()
        cardShape = rounded(WAKE_CARD_SURFACE, pt(CARD_RADIUS))
        elevationShape = rounded(WAKE_CARD_ELEVATION, pt(CARD_RADIUS + CARD_ELEVATION))
        Log.i(
            "WakeChatSessionService",
            "Scaled the card to $scale for a $measuredShortEdge-px short edge of the display"
        )
    }

    /**
     * Wires the card once and returns the stage the window shows.
     *
     * The structure is wired here and never again; only [paint] changes what is
     * drawn. Painting again after wiring is what makes the first exchange show
     * the text that [onPrepareShow] already recorded, and it is harmless on
     * every later show because painting is idempotent.
     *
     * The stage is deliberately left unpainted: it is as large as the card, so
     * a background here would paint the window's margin too and turn the card
     * into a block that reaches the bottom edge. Only the card and the bubbles
     * inside it are painted.
     */
    override fun onCreateContentView(): View {
        Log.i(
            "WakeChatSessionService",
            "Wiring the card for state=$state, a ${request.length}-character request and a ${reply.length}-character reply"
        )
        stage.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        stage.setOrientation(VERTICAL)
        stage.addView(card)
        stage.addView(foot)
        card.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        card.setOrientation(VERTICAL)
        card.setPadding(px(CARD_ELEVATION), px(CARD_ELEVATION), px(CARD_ELEVATION), px(CARD_ELEVATION))
        // The content view is a Compose surface: the card is a mini version of
        // the main chat view, drawn from the same bubble and scroll parts the
        // chat screen draws with (see ui/common/BubbleKit.kt). The legacy views
        // this session used before could not share a single line of rendering
        // with the chat view - they could only imitate its look - and the
        // bubbles' line cutting was a hand-rolled algorithm the chat view did
        // not run. Drawing both surfaces from one kit is what makes the popup
        // a smaller copy of the chat view rather than a look-alike.
        //
        // A voice-interaction window builds no lifecycle of its own, so the
        // owners the recomposer needs are built here and wired onto the view
        // BEFORE it joins the tree: the recomposer looks them up at attach,
        // and a view attached without them never starts composing.
        composeCard = ComposeView(context)
        wireComposeOwners(composeCard, composeOwner)
        // The recomposer resolves from the window's decor view, which the
        // framework builds outside this chain, so the owner rides there too.
        // The window already exists by the first show - sizeWindow laid it
        // out in onPrepareShow - so the decor is reachable here.
        window?.window?.decorView?.let { wireComposeOwners(it, composeOwner) }
        composeCard.setContent {
            WakeCardContent(cardState)
        }
        composeCard.layoutParams = LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
        card.addView(composeCard)
        // The foot's clearance is the display's real bottom inset, not a
        // design guess: this padding is what keeps the button row clear of
        // the navigation bar, and only the display knows where that bar is
        // or whether there is one.
        foot.setPadding(0, bottomInsetPx, 0, 0)
        paint()
        return stage
    }

    /**
     * Draws what [state] asks for, leaving everything else empty.
     *
     * The session's half of the card is only the choreography: the card
     * surface, its elevation halo and the fade the climb carries. Everything
     * inside - the status line, the two bubbles, the caret, the buttons - is
     * the snapshot [WakeCardContent] renders, and an empty snapshot paints
     * nothing, which is how a closed exchange looks like nothing at all.
     */
    private fun paint() {
        tint()
        val closed = state == SESSION_STATE_CLOSE && riseProgress <= 0f
        // The card wears the elevation halo; the Compose surface inside wears
        // the card surface, so the ring the panel used to show is still the
        // space between the two backgrounds. On the first show
        // [onPrepareShow] runs before the framework asks for the content
        // view, so the surface may not exist yet: the paint that matters then
        // is [onCreateContentView]'s own, and this pass draws only the shell.
        card.background = if (closed) clearShape else elevationShape
        if (this::composeCard.isInitialized) composeCard.background = if (closed) clearShape else cardShape
        if (this::composeCard.isInitialized) cardState.snapshot = if (closed) null else WakeCardSnapshot(
            statusLabel = statusLabel(),
            statusColor = ink(GRUVBOX_ON_SURFACE_MUTED),
            chipColor = ink(phaseColor()),
            request = request,
            reply = reply,
            toolCalls = toolCalls.toList(),
            streaming = state == SESSION_STATE_STREAMING,
            pose = pose(),
            alpha = riseProgress,
            fontScale = scale,
            onOpenApp = {
                try {
                    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                    if (launch != null) startAssistantActivity(launch)
                } catch (e: Exception) {
                    Log.w("WakeChatSessionService", "Could not open the app: ${e.message}")
                }
            },
            onEndChat = {
                try {
                    context.startService(
                        WakeChatService.buildIntent(context, WakeChatService.ACTION_CLOSE_CHAT)
                    )
                } catch (e: Exception) {
                    Log.w("WakeChatSessionService", "Could not end the chat: ${e.message}")
                }
            }
        )
    }

    /**
     * Re-tints the legacy half's shapes to the current progress.
     *
     * The card surface and its elevation halo are the only paint left here;
     * everything inside the card is Compose, which carries the fade itself via
     * the snapshot's alpha.
     */
    private fun tint() {
        cardShape.setColor(ink(WAKE_CARD_SURFACE))
        elevationShape.setColor(ink(WAKE_CARD_ELEVATION))
    }

    /**
     * A palette token carrying the current progress's share of its ink.
     *
     * The alpha byte is the only fade a session has: a widget takes one background
     * and a [GradientDrawable] has no opacity of its own, so the translucency already
     * baked into [WAKE_CARD_SURFACE] is scaled rather than replaced - the card keeps
     * letting the screen read through it at full strength, it just arrives translucent.
     */
    private fun ink(argb: Int): Int {
        if (riseProgress >= 1f) return argb
        if (riseProgress <= 0f) return TRANSPARENT
        val alpha = ((argb ushr 24) * riseProgress).toInt()
        return (argb and 0x00FFFFFF) or (alpha shl 24)
    }

    /**
     * How tall the card has to be to hold the lines it is showing, padding
     * included and nothing clamped away.
     *
     * This is the truth [sizeWindow] cuts the window to: the card is stretched to
     * fill the window, so the only way to give it room for a long answer is to
     * give the window that room. The heights are added up rather than measured
     * because a legacy [android.widget.TextView] reports no intrinsic size, so
     * [LINE_HEIGHT] and [STATUS_HEIGHT] are the only line boxes this can see.
     */
    private fun neededHeight(userLineCount: Int, replyLineCount: Int): Int {
        val status = px(STATUS_HEIGHT) + px(BUBBLE_GAP)
        val user =
            if (userLineCount == 0) 0
            else px(BUBBLE_PADDING) * 2 + userLineCount * px(LINE_HEIGHT) + px(BUBBLE_GAP)
        val answer =
            if (replyLineCount == 0) 0
            else px(BUBBLE_PADDING) * 2 + replyLineCount * px(LINE_HEIGHT) + px(CARET_HEIGHT)
        // The button row and the bot ride on every open card, bubble or not:
        // [WakeCardContent] always paints both, so an exchange with no words
        // yet (the wake word's first show, the assistant gesture's session)
        // must still be cut tall enough for the two ways out of the popup and
        // the avatar - omitting them on an empty card clipped the buttons
        // off-window, and the card arrived without its End chat button. The
        // rule measures to its own padding whatever it paints.
        val buttons = px(BUBBLE_GAP) + px(BUTTON_HEIGHT)
        val avatar = px(BUBBLE_GAP) + px(AVATAR_HEIGHT)
        return px(CARD_TOP_PADDING) + px(CARD_BOTTOM_PADDING) + status + px(DIVIDER_HEIGHT) + user + answer + buttons + avatar
    }

    /**
     * The small line beside the status chip that says what the exchange is doing.
     *
     * A session only ever sees the state the wake service put in the bundle, so
     * the label is derived here rather than sent: the same show that grows the
     * answer bubble is the one that says the answer is still arriving. A settled
     * reply still carries a label, because the card is open and the user should
     * be able to tell it is open.
     */
    /**
     * What the bot in the card is doing, one pose per thing a voice exchange
     * does. Thinking is the pose that matters most: nothing arrives from the
     * network during it, so the scanning head tilt is the only sign of life
     * the card has - a still card reads as broken, a moving one reads busy.
     */
    private fun pose(): CallPose = when (state) {
        SESSION_STATE_LISTENING -> CallPose.LISTENING
        SESSION_STATE_REQUEST -> CallPose.THINKING
        SESSION_STATE_STREAMING, SESSION_STATE_SPEAKING, SESSION_STATE_REPLY -> CallPose.SPEAKING
        else -> CallPose.CONNECTING
    }

    private fun statusLabel(): String = when (state) {
        SESSION_STATE_RISE -> "Ready"
        SESSION_STATE_LISTENING -> "Listening…"
        SESSION_STATE_REQUEST -> "Thinking…"
        SESSION_STATE_STREAMING -> "Answering…"
        SESSION_STATE_SPEAKING -> "Speaking…"
        SESSION_STATE_REPLY -> "Here you go"
        SESSION_STATE_CLOSE -> ""
        else -> "Something went wrong"
    }

    /**
     * The shape the status chip wears, which is the exchange's phase wearing the
     * colour it is doing in.
     *
     * The chip carries a whole shape rather than a colour because a background is
     * the only paint a widget can take, so one shape exists per phase and is
     * rebuilt only when the card rescales. Only the chip wears it: the label
     * beside it stays [GRUVBOX_ON_SURFACE_MUTED], so the phase is legible from
     * across a room by colour alone while the words stay quiet instead of shouting
     * the state. A state the wake service does not name is taken as a fault and
     * wears [GRUVBOX_ERROR].
     */
    /**
     * The colour the status chip wears, which is the exchange's phase wearing
     * the colour it is doing in. Only the chip wears it: the label beside it
     * stays [GRUVBOX_ON_SURFACE_MUTED], so the phase is legible from across a
     * room by colour alone while the words stay quiet instead of shouting the
     * state. A state the wake service does not name is taken as a fault and
     * wears [GRUVBOX_ERROR].
     */
    private fun phaseColor(): Int = when (state) {
        SESSION_STATE_LISTENING -> WAKE_PHASE_LISTENING
        SESSION_STATE_REQUEST -> WAKE_PHASE_THINKING
        SESSION_STATE_STREAMING, SESSION_STATE_SPEAKING, SESSION_STATE_REPLY -> WAKE_PHASE_ANSWERING
        else -> GRUVBOX_ERROR
    }

    /**
     * How tall the card is for the current show, which is how it climbs.
     *
     * The card fills the window it is given, so the climb is the window growing
     * upward from the bottom edge: the first show is a stub against the edge and
     * the last settles the card at [CARD_HEIGHT]. The climb eases out, so each
     * show adds a smaller share of what is left and the card leaves the edge
     * briskly and settles softly, which is what a pop-up reads as.
     *
     * The same position run backwards is the sink, which is why a closing
     * exchange leaves the way it came rather than blinking out: at "0" there is
     * no card at all, so the last frame of the sink is a hair of ink against the
     * edge, and the fade means nothing has to jump.
     */
    private fun riseHeight(): Int {
        if (riseProgress <= 0f) return 0
        return (px(CARD_HEIGHT) * eased(riseProgress)).toInt()
    }

    /**
     * The climb's easing: distance travelled as a share of the show's progress,
     * decelerating so the card leaves the edge briskly and settles softly.
     *
     * Arithmetic on one Float is the whole curve, which is what lets the sink reuse
     * it: reversing the ladder of progress values reverses the curve with it, so the
     * two directions are symmetric by construction rather than by two curves that
     * have to be kept in step.
     */
    private fun eased(progress: Float): Float = 1f - (1f - progress) * (1f - progress)

    /**
     * The progress this show was handed, clamped into the range the card draws in.
     *
     * Only the climb and the sink carry a position: every other state is an open
     * exchange at rest, and a show that names no state at all is treated as closed
     * rather than as a card frozen in mid-air. A missing or unparseable position
     * settles the exchange rather than hiding it, so a wake service that stops
     * animating still draws the card it has always drawn.
     */
    private fun settledProgress(travel: String?): Float {
        if (state != SESSION_STATE_RISE && state != SESSION_STATE_CLOSE) return 1f
        val raw = travel?.toFloatOrNull() ?: 1f
        if (raw <= 0f) return 0f
        if (raw >= 1f) return 1f
        return raw
    }

    /**
     * A filled, rounded rectangle - the shape of the card and of a bubble.
     *
     * This is the only rounding available to a session: a widget's
     * `setBackgroundColor(Int)` paints a sharp rectangle, while
     * [GradientDrawable] is a concrete drawable that constructs with no arguments
     * and takes a solid [GradientDrawable.setColor][setColor] plus a Float
     * [GradientDrawable.setCornerRadius][setCornerRadius]. A single colour with
     * no colour array set fills solid rather than gradient, and a fully
     * transparent colour paints nothing, which is how a turn that has no text
     * yet contributes no ink.
     */
    private fun rounded(color: Int, radius: Float): GradientDrawable {
        val shape = GradientDrawable()
        shape.setColor(color)
        shape.setCornerRadius(radius)
        return shape
    }

    /**
     * Tears the window down when the exchange has been closed, and reads the
     * display back off the card the measurement show stretched.
     *
     * The close is requested by [WakeChatService] through
     * [SESSION_STATE_CLOSE], which [paint] already draws as nothing, so the
     * window is invisible even if finishing it only hides it.
     */
    override fun onShow(args: android.os.Bundle?, showFlags: Int) {
        Log.i(
            "WakeChatSessionService",
            "Shown state=$state scale=$scale stageW=${stage.width} stageH=${stage.height} " +
                "cardW=${card.width} cardH=${card.height} composeW=${composeCard.width} composeH=${composeCard.height}"
        )
        if (state == SESSION_STATE_CLOSE) finish()
    }

    /**
     * Splits text into the lines the card can show.
     *
     * A legacy `TextView` is one line, so a reply longer than the card has to be
     * cut here. The cut is searched only inside the next [LINE_CHARS] characters:
     * searching the whole remainder instead makes the break land far past the
     * card edge, which is what painted lines wider than the card and spilling
     * over the bubbles. When the text is still not finished at the last slot, the
     * ellipsis is taken *inside* the budget rather than appended to it, so a
     * wrapped line can never be wider than the line the card can hold.
     */
    private fun wrap(text: String, maxLines: Int): List<String> {
        val all = mutableListOf<String>()
        // Characters per line is scale-invariant - see LINE_CHARS - so the
        // budget is the design value, never re-scaled here.
        val budget = LINE_CHARS
        var rest = text.trim()
        while (rest.isNotEmpty()) {
            var cut = rest.length
            if (cut > budget) {
                val space = rest.substring(0, budget).lastIndexOf(' ')
                cut = if (space > budget / 3) space else budget
            }
            all.add(rest.substring(0, cut).trim())
            rest = rest.substring(cut).trim()
        }
        // A reply longer than the pool scrolls up like a chat, it does not
        // stop at the first screenful: the oldest lines are dropped and the
        // newest ones stay, so the tail the user is waiting for is always the
        // text on the card. Wrapping only the first pool of lines is what
        // made a growing answer look frozen - the head never changed while
        // everything arrived below it, invisible.
        return if (all.size > maxLines) {
            val scrolled = all.subList(all.size - maxLines, all.size)
            // The ellipsis is taken inside the first line's budget, so the
            // scrolled line can never widen the bubble.
            var first = scrolled[0]
            if (first.length > budget - 1) first = first.substring(0, budget - 1).trim()
            listOf("…$first") + scrolled.subList(1, scrolled.size)
        } else all
    }

    /**
     * Drops the exchange when the session ends.
     *
     * The session is created per exchange, so this is the only place its state
     * is released.
     */
    override fun onDestroy() {
        state = ""
        request = ""
        reply = ""
        toolCalls.clear()
        riseProgress = 0f
        measuredShortEdge = 0
        scale = 1f
    }

    /**
     * Keeps the window awake while the exchange is open.
     *
     * The session is shown over whatever the device is displaying, including a
     * screen that would otherwise sleep, so the exchange has to hold the screen
     * open for as long as it is raised.
     */
    override fun onCreate() {
        setKeepAwake(true)
    }
}

/**
 * The lifecycle and saved-state owners a Compose surface needs that a
 * voice-interaction window does not build.
 *
 * A [VoiceInteractionSession]'s content view joins a window the framework
 * drives directly, so the view tree carries no
 * [androidx.lifecycle.LifecycleOwner] and the recomposer a [ComposeView]
 * builds at attach has nothing to observe: without one the surface never
 * starts composing and the card stays blank. This owner is constructed with
 * its lifecycle already resumed - the session is only ever shown while it is
 * alive, so there is no pause state to model - and is wired onto the view
 * before the view joins the tree, which is when the lookup happens.
 *
 * The saved-state registry rides along because Compose asks for one at
 * attach too; the card keeps no state worth saving, so the registry is
 * restored from nothing.
 */
private class SessionComposeOwner : LifecycleOwner, SavedStateRegistryOwner {
    // The Restarter check demands the controller attach during the owner's own
    // initialization, and a field initialiser IS that stage: creating the
    // controller here instead of in an init block is what keeps performRestore
    // from throwing "Restarter must be created only during owner's
    // initialization stage". The lifecycle is still INITIALIZED at that point,
    // which is exactly what the check reads. The session is only ever shown
    // while it is alive, so there is no pause state to model: the lifecycle
    // starts resumed and stays there.
    private val state = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this).also {
        it.performRestore(null)
    }

    init {
        state.currentState = Lifecycle.State.RESUMED
    }

    // Lifecycle 2.8 and savedstate 1.2 are Kotlin interfaces exposing these as
    // properties, not getter overrides.
    override val lifecycle: Lifecycle get() = state
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
}

/**
 * Wires the view-tree owners a Compose surface needs onto [view].
 *
 * The setter classes are android-only sources of their artifacts, so this
 * module's compiler does not see them even though the runtime classpath
 * carries them - Compose itself looks the owners up at attach by the same
 * names. Reflection is therefore the only way to reach them, and a failure
 * only means the recomposer will have nothing to observe and the card stays
 * blank, which the log says plainly.
 */
private fun wireComposeOwners(view: android.view.View, owner: SessionComposeOwner) {
    // The recomposer resolves from the ROOT of the view tree, not from the
    // ComposeView: getWindowRecomposer walks to the root and looks the owner
    // up there, so an owner set only on the leaf is invisible to it - which
    // is exactly what the "ViewTreeLifecycleOwner not found from
    // LinearLayout{stage}" crash was. The owner therefore rides on every view
    // from the leaf to the root, so whichever root the window ends up with
    // carries it.
    var link: android.view.View? = view
    while (link != null) {
        runCatching {
            Class.forName("androidx.lifecycle.ViewTreeLifecycleOwner")
                .getMethod("set", android.view.View::class.java, LifecycleOwner::class.java)
                .invoke(null, link, owner)
        }.onFailure {
            Log.w("WakeChatSessionService", "Could not wire the lifecycle owner: ${it.message}")
        }
        runCatching {
            Class.forName("androidx.savedstate.ViewTreeSavedStateRegistryOwner")
                .getMethod("set", android.view.View::class.java, SavedStateRegistryOwner::class.java)
                .invoke(null, link, owner)
        }.onFailure {
            Log.w("WakeChatSessionService", "Could not wire the saved-state owner: ${it.message}")
        }
        link = link.parent as? android.view.View
    }
}
