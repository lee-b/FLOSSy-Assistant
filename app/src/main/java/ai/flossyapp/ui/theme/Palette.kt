package ai.flossyapp.ui.theme

/**
 * The Gruvbox palette, as plain ARGB integers.
 *
 * The app paints with Compose colour roles and the wake card is drawn by the
 * system, which cannot read a [MaterialTheme][android.material.theme] - a session
 * runs in its own process and has no Compose surface to ask. Both halves still
 * have to agree on what "the reply colour" or "the card surface" is, so the
 * palette lives here as the one set of tokens both name, and each half converts
 * to whatever its own paint API wants: Compose wraps these in a
 * [android.compose.ui.graphics.Color], a session hands them straight to
 * `setTextColor`/`setColor`.
 *
 * Keeping the tokens as integers rather than Compose colours is what lets the
 * session service depend on this module without dragging the whole Compose
 * toolchain into a process that must not carry one.
 */

/**
 * Nothing at all - a colour that paints no ink, which is how a turn that has no
 * text yet contributes nothing to the card.
 */
const val TRANSPARENT = 0x00000000

// Gruvbox dark scheme. The accents are a cool slate blue-gray: a low-saturation
// steel hue sits quietly on the dark neutral surfaces, keeps the user bubble and
// the action buttons calm instead of shouting in warm orange, and leaves the
// error red as the only warm colour on screen - the one that should be loud.
const val GRUVBOX_PRIMARY = 0xFF9BB7D4.toInt() // Soft steel blue (accent)
const val GRUVBOX_ON_PRIMARY = 0xFF2B2B2B.toInt()
// Container roles are dark, low-saturation surfaces, not accents: a container
// carries body text, so it must sit near the background in luminance and let a
// light foreground (0xFFE8E8E8, ~7:1 contrast) read on it. The previous
// 0xFF86A3C2 was a bright mid-tone - accent luminance in a container role -
// and the dark on-color it forced left the user's own bubble unreadable.
const val GRUVBOX_PRIMARY_CONTAINER = 0xFF3D5266.toInt()
const val GRUVBOX_ON_PRIMARY_CONTAINER = 0xFFE8E8E8.toInt()
const val GRUVBOX_SECONDARY = 0xFF86A3C2.toInt() // Muted slate blue (secondary accent)
const val GRUVBOX_ON_SECONDARY = 0xFF2B2B2B.toInt()
const val GRUVBOX_SECONDARY_CONTAINER = 0xFF38485A.toInt()
const val GRUVBOX_ON_SECONDARY_CONTAINER = 0xFFE8E8E8.toInt()
const val GRUVBOX_TERTIARY = 0xFF7E9CB8.toInt() // Blue-gray (tertiary accent)
const val GRUVBOX_ON_TERTIARY = 0xFF333333.toInt()
const val GRUVBOX_TERTIARY_CONTAINER = 0xFF324356.toInt()
const val GRUVBOX_ON_TERTIARY_CONTAINER = 0xFFE8E8E8.toInt()
const val GRUVBOX_BACKGROUND = 0xFF2B2B2B.toInt() // Neutral dark gray
const val GRUVBOX_ON_BACKGROUND = 0xFFE8E8E8.toInt()
const val GRUVBOX_SURFACE = 0xFF333333.toInt() // Neutral gray
const val GRUVBOX_ON_SURFACE = 0xFFE8E8E8.toInt() // Light gray for text on dark gray surfaces
const val GRUVBOX_SURFACE_LOW = 0xFF2D2D2D.toInt() // Slightly darker gray
const val GRUVBOX_SURFACE_MED = 0xFF3A3A3A.toInt() // Surface medium gray
const val GRUVBOX_SURFACE_HIGH = 0xFF444444.toInt() // Elevated gray
const val GRUVBOX_SURFACE_HIGHEST = 0xFF4F4F4F.toInt()
const val GRUVBOX_ON_SURFACE_MUTED = 0xFFABABAB.toInt()
const val GRUVBOX_ERROR = 0xFFCC3D2B.toInt() // Red-orange for errors (Gruvbox style)
const val GRUVBOX_ON_ERROR = 0xFF2B2B2B.toInt()

// Gruvbox light scheme (fallback palette).
const val GRUVBOX_LIGHT_PRIMARY = 0xFF5C7C9E.toInt() // Steel blue (accent)
const val GRUVBOX_LIGHT_ON_PRIMARY = 0xFFF7FAFD.toInt()
const val GRUVBOX_LIGHT_PRIMARY_CONTAINER = 0xFFB9CDE3.toInt()
const val GRUVBOX_LIGHT_SECONDARY = 0xFF4A6A8C.toInt() // Deeper slate blue
const val GRUVBOX_LIGHT_ON_SECONDARY = 0xFFF7FAFD.toInt()
const val GRUVBOX_LIGHT_SECONDARY_CONTAINER = 0xFFA9C0D8.toInt()
const val GRUVBOX_LIGHT_TERTIARY = 0xFF54708D.toInt() // Blue-gray
const val GRUVBOX_LIGHT_ON_TERTIARY = 0xFFF7FAFD.toInt()
const val GRUVBOX_LIGHT_TERTIARY_CONTAINER = 0xFF9FB4C9.toInt()
const val GRUVBOX_LIGHT_BACKGROUND = 0xFFF2F5F8.toInt() // Cool off-white
const val GRUVBOX_LIGHT_ON_BACKGROUND = 0xFF333333.toInt()
const val GRUVBOX_LIGHT_SURFACE = 0xFFE9EEF3.toInt() // Lighter cool gray
const val GRUVBOX_LIGHT_SURFACE_VARIANT = 0xFFDDE4EB.toInt()
const val GRUVBOX_LIGHT_ON_SURFACE_VARIANT = 0xFF5C7286.toInt()
const val GRUVBOX_LIGHT_ERROR = 0xFFAF3A1B.toInt()
const val GRUVBOX_LIGHT_ON_ERROR = 0xFFF7FAFD.toInt()
const val GRUVBOX_LIGHT_SURFACE_DIM = 0xFFDCE3E9.toInt()
const val GRUVBOX_LIGHT_SURFACE_BRIGHT = 0xFFE9EEF3.toInt()
const val GRUVBOX_LIGHT_SURFACE_CONTAINER_LOW = 0xFFE4EAF0.toInt()
const val GRUVBOX_LIGHT_SURFACE_CONTAINER = 0xFFDDE4EB.toInt()
const val GRUVBOX_LIGHT_SURFACE_CONTAINER_HIGH = 0xFFD5DEE5.toInt()
const val GRUVBOX_LIGHT_SURFACE_CONTAINER_HIGHEST = 0xFFCFD9E2.toInt()

/**
 * The card the wake session draws.
 *
 * Translucent rather than solid: the session window is transparent and the
 * platform paints nothing behind the card, so the screen the device is showing
 * reads through it and the exchange stays a floating chat card rather than a
 * panel over the display.
 */
const val WAKE_CARD_SURFACE = 0xE6141414.toInt()

/**
 * The layer the card rests on - the ring of ink that reads as elevation.
 *
 * A session has no shadow API: a widget carries one background and
 * [GradientDrawable] takes no stroke. Depth is therefore layered translucency -
 * a faint, wider layer under the card surface, so the card reads as one sheet
 * lifted off the screen rather than a flat cut-out of it.
 */
const val WAKE_CARD_ELEVATION = 0x59000000.toInt()

/**
 * The bubble the user's half of the exchange wears - the medium gray surface,
 * lifted off the card so the two turns read as separate bubbles rather than two
 * blocks of ink on one panel.
 */
const val WAKE_USER_BUBBLE = GRUVBOX_SURFACE_MED

/**
 * The bubble the reply wears. It carries the accent's brown rather than the
 * accent itself, so the card stays Gruvbox while the answer is still obvious
 * against the user's gray.
 */
const val WAKE_REPLY_BUBBLE = 0xFF26303B.toInt()

/**
 * The rule drawn between the two bubbles.
 *
 * The bubbles are the same rounded sheet on the same card, so at a glance across
 * a room they read as one block of ink. A faint rule between them is what
 * separates the turn the user spoke from the answer, without a stroke API or a
 * second panel.
 */
const val WAKE_DIVIDER = 0x2AE8E8E8.toInt()

/**
 * The colours the status dot wears, one per phase of the exchange.
 *
 * The dot is what makes the status line readable at a glance across a room: the
 * label needs the eye to be on the card, the colour does not. A state the wake
 * service does not name is taken as a fault and wears [GRUVBOX_ERROR].
 */
const val WAKE_PHASE_LISTENING = 0xFF67B744.toInt()
const val WAKE_PHASE_THINKING = 0xFF7A94A8.toInt()
const val WAKE_PHASE_ANSWERING = GRUVBOX_PRIMARY
