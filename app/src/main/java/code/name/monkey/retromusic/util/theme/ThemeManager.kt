package code.name.monkey.retromusic.util.theme

import android.content.Context
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatDelegate
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.extensions.generalThemeValue
import code.name.monkey.retromusic.util.PreferenceUtil
import code.name.monkey.retromusic.util.theme.ThemeMode.*

@StyleRes
fun Context.getThemeResValue(): Int =
    if (PreferenceUtil.materialYou) {
        if (generalThemeValue == BLACK) R.style.Theme_RetroMusic_MD3_Black
        else R.style.Theme_RetroMusic_MD3
    } else {
        when (generalThemeValue) {
            LIGHT -> R.style.Theme_RetroMusic_Light
            DARK -> R.style.Theme_RetroMusic_Base
            BLACK -> R.style.Theme_RetroMusic_Black
            AUTO -> R.style.Theme_RetroMusic_FollowSystem
        }
    }

/**
 * Melody Quest look, layered on top of [getThemeResValue]. Material You keeps its dynamic
 * colours and only receives the shape/component overlay.
 */
@StyleRes
fun Context.getMelodyThemeOverlay(): Int =
    if (PreferenceUtil.materialYou) {
        R.style.ThemeOverlay_Melody
    } else if (PreferenceUtil.isNeonStyle) {
        when (generalThemeValue) {
            LIGHT -> R.style.ThemeOverlay_Melody_NeonLight
            DARK -> R.style.ThemeOverlay_Melody_NeonDark
            BLACK -> R.style.ThemeOverlay_Melody_NeonBlack
            AUTO -> R.style.ThemeOverlay_Melody_NeonDayNight
        }
    } else {
        when (generalThemeValue) {
            LIGHT -> R.style.ThemeOverlay_Melody_Light
            DARK -> R.style.ThemeOverlay_Melody_Dark
            BLACK -> R.style.ThemeOverlay_Melody_Black
            AUTO -> R.style.ThemeOverlay_Melody_DayNight
        }
    }

fun Context.getNightMode(): Int = when (generalThemeValue) {
    LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
    DARK -> AppCompatDelegate.MODE_NIGHT_YES
    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
}