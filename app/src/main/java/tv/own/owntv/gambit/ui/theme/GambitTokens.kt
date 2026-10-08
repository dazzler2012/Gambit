package tv.own.owntv.gambit.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import tv.own.owntv.ui.stage.StageFocus
import tv.own.owntv.ui.stage.drawOuterRing
import tv.own.owntv.ui.theme.StageAccent
import tv.own.owntv.ui.theme.mpx

/**
 * Gambit's look: flat, dark and quiet. Focus is a plain fill (no glow, no sweep, no ring), surfaces are
 * near-black greys, and the one colour is the user's accent (Settings → Appearance → Accent color) in
 * two tones: as chosen for text and markers (a light green by default, see GambitDefaults), and
 * darkened for focus fills, which always carry white text ([gambitStageAccent]).
 *
 * Values are in mockup pixels ([mpx]) like the rest of Stage, so UI zoom and font size still apply.
 */
object GambitLook {
    /** Every Stage focus treatment becomes a flat fill (see [gambitFocusDecor]). */
    const val FlatFocus = true
}

object GambitColors {
    /** The navigation rail and side panels. */
    val Rail = Color(0xFF121212)
    /** Guide programme cells. */
    val Cell = Color(0xFF232323)
    /** The cell of the programme on air now. */
    val CellNow = Color(0xFF2A2A2A)
    /** The cell under the cursor while the row is selected but not yet entered. */
    val CellRow = Color(0xFF3A3A3A)
    /** The focused cell. */
    val CellFocused = Color(0xFF575757)
    val Text = Color(0xFFF2F2F2)
    val Muted = Color(0xFFB4B4B4)
    val Dim = Color(0xFF8C8C8C)
    val Divider = Color(0xFF2E2E2E)
    val NowLine = Color(0xFF5C5C5C)
    val ProgressTrack = Color(0xFF4A4A4A)
}

object GambitRadii {
    val Cell = 4.mpx
    val Video = 20.mpx
}

/** The accent darkened for focus fills, so white text reads on it whatever accent is chosen. */
fun gambitFocusFill(accent: Color): Color = lerp(accent, Color.Black, 0.55f)

/**
 * Stage's accent triple as Gambit uses it: text on a focus fill is always white, because the fill is
 * the darkened accent ([gambitFocusFill]). Called from `StageTokens.stageAccent` when
 * [GambitLook.FlatFocus] is on.
 */
fun gambitStageAccent(a: StageAccent): StageAccent = a.copy(onAccent = Color.White)

/** The accent lifted towards white: date labels, the now dot, switches that are on. */
fun gambitAccentLight(accent: Color): Color = lerp(accent, Color.White, 0.45f)

/**
 * Gambit's replacement for Stage's focus decoration: FX, FILLED and PRIMARY become a flat fill of the
 * darkened accent ([gambitFocusFill]) with the element's own radius; POSTER keeps only a 3 px accent
 * ring. Called from `StageComponents.stageFocusDecor` when [GambitLook.FlatFocus] is on.
 */
fun Modifier.gambitFocusDecor(style: StageFocus, radius: Dp, a: StageAccent): Modifier = drawBehind {
    val r = radius.toPx()
    when (style) {
        StageFocus.FX, StageFocus.FILLED, StageFocus.PRIMARY -> drawRoundRect(gambitFocusFill(a.accent), cornerRadius = CornerRadius(r))
        StageFocus.POSTER -> drawOuterRing(a.accent, 3 * 1.mpx.toPx(), r)
    }
}
