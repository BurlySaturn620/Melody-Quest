package code.name.monkey.retromusic.extensions

import android.os.Build
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import code.name.monkey.retromusic.util.PreferenceUtil
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlin.math.roundToInt

/** True when the Neon (gamer) look is showing: Neon style and Material You off. */
val isNeonLook: Boolean
    get() = PreferenceUtil.isNeonStyle && !PreferenceUtil.materialYou

/**
 * Neon: a soft coloured glow under the view, made from a tinted elevation shadow.
 * Coloured shadows need Android 9+; older versions simply get no glow.
 */
fun View.neonGlow(@ColorInt color: Int, elevationDp: Float = 14f) {
    if (!isNeonLook || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
    outlineAmbientShadowColor = color
    outlineSpotShadowColor = color
    val px = elevationDp * resources.displayMetrics.density
    if (this is FloatingActionButton) compatElevation = px else elevation = px
}

/** Neon: thin HUD-style outline in the accent colour. */
fun MaterialCardView.neonOutline(@ColorInt color: Int, alpha: Int = 0x99) {
    if (!isNeonLook) return
    strokeColor = ColorUtils.setAlphaComponent(color, alpha)
    strokeWidth = (resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
}
