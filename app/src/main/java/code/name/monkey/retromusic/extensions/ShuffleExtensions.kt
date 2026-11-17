package code.name.monkey.retromusic.extensions

import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.appcompat.content.res.AppCompatResources
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.service.MusicService

/**
 * Shows the current shuffle state on a player's shuffle button:
 * off = dimmed, shuffle = highlighted, Smart Shuffle = highlighted sparkle icon.
 * The layout's own shuffle icon is remembered so each player theme keeps its look.
 */
fun ImageView.applyShuffleState(@ColorInt activeColor: Int, @ColorInt inactiveColor: Int) {
    val mode = MusicPlayerRemote.shuffleMode
    val defaultIcon = (getTag(R.id.tag_default_shuffle_icon) as? Drawable)
        ?: (drawable ?: AppCompatResources.getDrawable(context, R.drawable.ic_shuffle))
            .also { setTag(R.id.tag_default_shuffle_icon, it) }
    if (mode == MusicService.SHUFFLE_MODE_SMART) {
        setImageResource(R.drawable.ic_shuffle_smart)
    } else {
        setImageDrawable(defaultIcon)
    }
    setColorFilter(
        if (mode == MusicService.SHUFFLE_MODE_NONE) inactiveColor else activeColor,
        PorterDuff.Mode.SRC_IN
    )
}
