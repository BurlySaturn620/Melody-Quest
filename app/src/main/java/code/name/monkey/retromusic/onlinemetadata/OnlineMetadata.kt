package code.name.monkey.retromusic.onlinemetadata

import android.content.Context
import code.name.monkey.retromusic.db.OnlineMetadataEntity
import code.name.monkey.retromusic.model.Song
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory view of what was found online, used to fill *empty* fields of songs as they are read
 * from MediaStore (see RealSongRepository). Nothing here ever overrides a tag that is in the file.
 *
 * Lyrics live as small files in the app's private storage (`online_lyrics/<songId>.lrc|.txt`) so
 * they don't sit in memory and can be read from any thread, like the app's existing .lrc support.
 */
object OnlineMetadata {

    private val bySongId = ConcurrentHashMap<Long, OnlineMetadataEntity>()
    private val songIdByPath = ConcurrentHashMap<String, Long>()
    private var lyricsDir: File? = null

    fun init(context: Context) {
        lyricsDir = File(context.filesDir, "online_lyrics")
    }

    fun replaceAll(entities: List<OnlineMetadataEntity>) {
        bySongId.clear()
        songIdByPath.clear()
        entities.forEach(::put)
    }

    fun put(entity: OnlineMetadataEntity) {
        bySongId[entity.songId] = entity
        songIdByPath[entity.path] = entity.songId
    }

    fun get(songId: Long): OnlineMetadataEntity? = bySongId[songId]

    val isEmpty: Boolean get() = bySongId.isEmpty()

    /** Returns [song] with empty year / artist / album artist / composer filled in, if we know them. */
    fun apply(song: Song): Song {
        val e = bySongId[song.id] ?: return song
        if (e.status != OnlineMetadataEntity.STATUS_MATCHED) return song
        // File changed since we looked it up (re-tagged, replaced...): don't trust the old match.
        if (e.path != song.data || e.dateModified != song.dateModified) return song

        val year = if (song.year > 0) song.year else e.year
        val artist = if (OnlineMetadataLookup.knownArtist(song.artistName) != null) song.artistName
        else e.artist ?: song.artistName
        val albumArtist = song.albumArtist?.takeIf { it.isNotBlank() } ?: e.albumArtist ?: song.albumArtist
        val composer = song.composer?.takeIf { it.isNotBlank() } ?: e.composer ?: song.composer

        if (year == song.year && artist == song.artistName && albumArtist == song.albumArtist &&
            composer == song.composer
        ) return song

        return Song(
            song.id,
            song.title,
            song.trackNumber,
            year,
            song.duration,
            song.data,
            song.dateModified,
            song.albumId,
            song.albumName,
            song.artistId,
            artist,
            composer,
            albumArtist,
        )
    }

    // ------------------------------------------------------------------ lyrics files

    private fun syncedFile(songId: Long) = lyricsDir?.let { File(it, "$songId.lrc") }
    private fun plainFile(songId: Long) = lyricsDir?.let { File(it, "$songId.txt") }

    fun saveLyrics(songId: Long, lyrics: LyricsMatch) {
        val dir = lyricsDir ?: return
        if (!dir.isDirectory && !dir.mkdirs()) return
        lyrics.synced?.let { syncedFile(songId)?.writeText(it) }
        lyrics.plain?.let { plainFile(songId)?.writeText(it) }
    }

    fun syncedLyrics(songId: Long): String? = read(syncedFile(songId))

    fun plainLyrics(songId: Long): String? = read(plainFile(songId))

    /** For code that only has the file path (LyricUtil). */
    fun syncedLyricsForPath(path: String): String? = songIdByPath[path]?.let(::syncedLyrics)

    fun plainLyricsForPath(path: String): String? = songIdByPath[path]?.let(::plainLyrics)

    private fun read(file: File?): String? = try {
        file?.takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    fun clear() {
        bySongId.clear()
        songIdByPath.clear()
        lyricsDir?.deleteRecursively()
    }
}
