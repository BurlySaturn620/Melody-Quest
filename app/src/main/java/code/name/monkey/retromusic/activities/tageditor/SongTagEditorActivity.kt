/*
 * Copyright (c) 2020 Hemanth Savarla.
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 */
package code.name.monkey.retromusic.activities.tageditor

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.widget.ImageView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import code.name.monkey.appthemehelper.util.MaterialValueHelper
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.databinding.ActivitySongTagEditorBinding
import code.name.monkey.retromusic.extensions.*
import code.name.monkey.retromusic.glide.RetroGlideExtension.asBitmapPalette
import code.name.monkey.retromusic.glide.palette.BitmapPaletteWrapper
import code.name.monkey.retromusic.model.ArtworkInfo
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.onlinemetadata.LyricsMatch
import code.name.monkey.retromusic.onlinemetadata.OnlineMetadataApi
import code.name.monkey.retromusic.onlinemetadata.OnlineMetadataManager
import code.name.monkey.retromusic.onlinemetadata.TagMatch
import code.name.monkey.retromusic.repository.SongRepository
import code.name.monkey.retromusic.util.ImageUtil
import code.name.monkey.retromusic.util.MusicUtil
import code.name.monkey.retromusic.util.RetroColorUtil
import code.name.monkey.retromusic.util.logD
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.ImageViewTarget
import com.bumptech.glide.request.transition.Transition
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.textfield.TextInputEditText
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jaudiotagger.tag.FieldKey
import org.koin.android.ext.android.inject

class SongTagEditorActivity : AbsTagEditorActivity<ActivitySongTagEditorBinding>() {

    override val bindingInflater: (LayoutInflater) -> ActivitySongTagEditorBinding =
        ActivitySongTagEditorBinding::inflate


    private val songRepository by inject<SongRepository>()

    private var albumArtBitmap: Bitmap? = null
    private var deleteAlbumArt: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setUpViews()
        setSupportActionBar(binding.toolbar)
        binding.appBarLayout?.statusBarForeground =
            MaterialShapeDrawable.createWithElevationOverlay(this)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setUpViews() {
        fillViewsWithFileTags()
        binding.songTextContainer.setTint(false)
        binding.composerContainer.setTint(false)
        binding.albumTextContainer.setTint(false)
        binding.artistContainer.setTint(false)
        binding.albumArtistContainer.setTint(false)
        binding.yearContainer.setTint(false)
        binding.genreContainer.setTint(false)
        binding.trackNumberContainer.setTint(false)
        binding.discNumberContainer.setTint(false)
        binding.lyricsContainer.setTint(false)
        binding.lyricistContainer.setTint(false)

        binding.songText.appHandleColor().doAfterTextChanged { dataChanged() }
        binding.albumText.appHandleColor().doAfterTextChanged { dataChanged() }
        binding.albumArtistText.appHandleColor().doAfterTextChanged { dataChanged() }
        binding.artistText.appHandleColor().doAfterTextChanged { dataChanged() }
        binding.genreText.appHandleColor().doAfterTextChanged { dataChanged() }
        binding.yearText.appHandleColor().doAfterTextChanged { dataChanged() }
        binding.trackNumberText.appHandleColor().doAfterTextChanged { dataChanged() }
        binding.discNumberText.appHandleColor().doAfterTextChanged { dataChanged() }
        binding.lyricsText.appHandleColor().doAfterTextChanged { dataChanged() }
        binding.songComposerText.appHandleColor().doAfterTextChanged { dataChanged() }
        binding.lyricistText.appHandleColor().doAfterTextChanged { dataChanged() }
    }

    private fun fillViewsWithFileTags() {
        binding.songText.setText(songTitle)
        binding.albumArtistText.setText(albumArtist)
        binding.albumText.setText(albumTitle)
        binding.artistText.setText(artistName)
        binding.genreText.setText(genreName)
        binding.yearText.setText(songYear)
        binding.trackNumberText.setText(trackNumber)
        binding.discNumberText.setText(discNumber)
        binding.lyricsText.setText(lyrics)
        binding.songComposerText.setText(composer)
        binding.lyricistText.setText(lyricist)
        logD(songTitle + songYear)
    }

    override fun loadCurrentImage() {
        val bitmap = albumArt
        setImageBitmap(
            bitmap,
            RetroColorUtil.getColor(
                RetroColorUtil.generatePalette(bitmap),
                defaultFooterColor()
            )
        )
        deleteAlbumArt = false
    }

    override fun searchImageOnWeb() {
        searchWebFor(binding.songText.text.toString(), binding.artistText.text.toString())
    }

    override fun deleteImage() {
        setImageBitmap(
            BitmapFactory.decodeResource(resources, R.drawable.default_audio_art),
            defaultFooterColor()
        )
        deleteAlbumArt = true
        dataChanged()
    }

    override fun setColors(color: Int) {
        super.setColors(color)
        ColorStateList.valueOf(
            MaterialValueHelper.getPrimaryTextColor(
                this,
                color.isColorLight
            )
        ).also {
            saveFab.iconTint = it
            saveFab.setTextColor(it)
        }
    }

    override fun save() {
        val fieldKeyValueMap = EnumMap<FieldKey, String>(FieldKey::class.java)
        fieldKeyValueMap[FieldKey.TITLE] = binding.songText.text.toString()
        fieldKeyValueMap[FieldKey.ALBUM] = binding.albumText.text.toString()
        fieldKeyValueMap[FieldKey.ARTIST] = binding.artistText.text.toString()
        fieldKeyValueMap[FieldKey.GENRE] = binding.genreText.text.toString()
        fieldKeyValueMap[FieldKey.YEAR] = binding.yearText.text.toString()
        fieldKeyValueMap[FieldKey.TRACK] = binding.trackNumberText.text.toString()
        fieldKeyValueMap[FieldKey.DISC_NO] = binding.discNumberText.text.toString()
        fieldKeyValueMap[FieldKey.LYRICS] = binding.lyricsText.text.toString()
        fieldKeyValueMap[FieldKey.ALBUM_ARTIST] = binding.albumArtistText.text.toString()
        fieldKeyValueMap[FieldKey.COMPOSER] = binding.songComposerText.text.toString()
        fieldKeyValueMap[FieldKey.LYRICIST] = binding.lyricistText.text.toString()
        writeValuesToFiles(
            fieldKeyValueMap, when {
                deleteAlbumArt -> ArtworkInfo(id, null)
                albumArtBitmap == null -> null
                else -> ArtworkInfo(id, albumArtBitmap!!)
            }
        )
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_song_tag_editor, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_fill_from_online) {
            fillFromOnline()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /**
     * Melody Quest: look this song up on MusicBrainz / LRCLIB and show everything found in a
     * checklist (see [showFoundOnline]). Nothing is written to the file until the user taps save.
     */
    private fun fillFromOnline() {
        val title = binding.songText.text.toString().trim()
        if (title.isEmpty()) {
            showToast(R.string.online_metadata_not_found)
            return
        }
        showToast(R.string.online_metadata_searching)
        lifecycleScope.launch {
            val (tags, lyrics) = try {
                val stored = withContext(Dispatchers.IO) { songRepository.song(id) }
                // Use what's typed in the form, so the user can fix a typo and try again.
                val query = Song(
                    id = stored.id,
                    title = title,
                    trackNumber = stored.trackNumber,
                    year = 0,
                    duration = stored.duration,
                    data = stored.data,
                    dateModified = stored.dateModified,
                    albumId = stored.albumId,
                    albumName = binding.albumText.text.toString().trim(),
                    artistId = stored.artistId,
                    artistName = binding.artistText.text.toString().trim(),
                    composer = null,
                    albumArtist = null,
                )
                OnlineMetadataManager.lookUpForEditor(query)
            } catch (e: OnlineMetadataApi.TransientException) {
                showToast(R.string.online_metadata_offline, Toast.LENGTH_LONG)
                return@launch
            }
            if (tags == null && lyrics == null) {
                showToast(R.string.online_metadata_not_found)
                return@launch
            }
            showFoundOnline(tags, lyrics)
        }
    }

    /** One line in the "Found online" checklist. */
    private class FoundField(
        val label: String,
        val field: TextInputEditText,
        val value: String,
        /** What to show instead of the raw value (lyrics are summarised). */
        val display: String = value,
    )

    /**
     * Lists everything that was found next to what's in the form now. Empty fields come
     * pre-ticked; fields that already have a different value are offered but not ticked, so
     * nothing gets replaced unless the user asks for it.
     */
    private fun showFoundOnline(tags: TagMatch?, lyrics: LyricsMatch?) {
        val found = buildList {
            fun offer(label: Int, field: TextInputEditText, value: String?, display: String? = null) {
                if (!value.isNullOrBlank()) add(FoundField(getString(label), field, value, display ?: value))
            }
            if (tags != null) {
                offer(R.string.artist, binding.artistText, tags.artist)
                offer(R.string.album_artist, binding.albumArtistText, tags.albumArtist)
                offer(R.string.composer, binding.songComposerText, tags.composer)
                offer(R.string.lyricist, binding.lyricistText, tags.lyricist)
                offer(R.string.genre, binding.genreText, tags.genre)
                offer(R.string.year, binding.yearText, tags.year.takeIf { it > 0 }?.toString())
                offer(R.string.track_list, binding.trackNumberText, tags.trackNumber?.toString())
                offer(R.string.disc_hint, binding.discNumberText, tags.discNumber?.toString())
            }
            if (lyrics != null) {
                // Synced lyrics embed fine and the app's lyrics view can follow along with them.
                val text = lyrics.synced ?: lyrics.plain
                val lines = text?.lines()?.count { it.isNotBlank() } ?: 0
                offer(
                    R.string.lyrics, binding.lyricsText, text,
                    getString(
                        if (lyrics.synced != null) R.string.online_metadata_synced_lyrics
                        else R.string.online_metadata_plain_lyrics, lines
                    )
                )
            }
        }
        if (found.isEmpty()) {
            showToast(R.string.online_metadata_not_found)
            return
        }

        val labels = found.map { item ->
            val current = item.field.text?.toString()?.trim().orEmpty()
            when {
                current.isEmpty() ->
                    getString(R.string.online_metadata_row_new, item.label, item.display)
                current.equals(item.value.trim(), ignoreCase = true) ->
                    getString(R.string.online_metadata_row_same, item.label, item.display)
                else -> getString(
                    R.string.online_metadata_row_replace, item.label, item.display,
                    if (item.field == binding.lyricsText) getString(R.string.online_metadata_current_lyrics)
                    else current.take(40)
                )
            }
        }.toTypedArray<CharSequence>()
        val checked = found.map { it.field.text.isNullOrBlank() }.toBooleanArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.online_metadata_found_title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.online_metadata_apply) { _, _ ->
                var applied = 0
                found.forEachIndexed { index, item ->
                    if (checked[index] && item.field.text?.toString() != item.value) {
                        item.field.setText(item.value)
                        applied++
                    }
                }
                if (applied > 0) {
                    showToast(getString(R.string.online_metadata_filled, applied), Toast.LENGTH_LONG)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun getSongPaths(): List<String> = listOf(songRepository.song(id).data)

    override fun getSongUris(): List<Uri> = listOf(MusicUtil.getSongFileUri(id))

    override fun loadImageFromFile(selectedFile: Uri?) {
        Glide.with(this@SongTagEditorActivity)
            .asBitmapPalette()
            .load(selectedFile)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .skipMemoryCache(true)
            .into(object : ImageViewTarget<BitmapPaletteWrapper>(binding.editorImage) {
                override fun onResourceReady(
                    resource: BitmapPaletteWrapper,
                    transition: Transition<in BitmapPaletteWrapper>?
                ) {
                    RetroColorUtil.getColor(resource.palette, Color.TRANSPARENT)
                    albumArtBitmap = resource.bitmap?.let { ImageUtil.resizeBitmap(it, 2048) }
                    setImageBitmap(
                        albumArtBitmap,
                        RetroColorUtil.getColor(
                            resource.palette,
                            defaultFooterColor()
                        )
                    )
                    deleteAlbumArt = false
                    dataChanged()
                    setResult(Activity.RESULT_OK)
                }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    super.onLoadFailed(errorDrawable)
                    showToast(R.string.error_load_failed, Toast.LENGTH_LONG)
                }

                override fun setResource(resource: BitmapPaletteWrapper?) {}
            })
    }

    companion object {
        val TAG: String = SongTagEditorActivity::class.java.simpleName
    }

    override val editorImage: ImageView
        get() = binding.editorImage
}
