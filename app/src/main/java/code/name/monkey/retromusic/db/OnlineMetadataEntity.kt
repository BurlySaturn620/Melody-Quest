package code.name.monkey.retromusic.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Melody Quest: tags looked up online for one song. Kept inside the app only; the audio file is
 * never touched unless the user saves through the tag editor. Lyrics are stored as files (see
 * OnlineMetadata) so this row stays small enough to cache in memory.
 */
@Entity(tableName = "online_metadata")
data class OnlineMetadataEntity(
    @PrimaryKey
    @ColumnInfo(name = "song_id") val songId: Long,
    @ColumnInfo(name = "path") val path: String,
    @ColumnInfo(name = "date_modified") val dateModified: Long,
    @ColumnInfo(name = "status") val status: Int,
    @ColumnInfo(name = "checked_at") val checkedAt: Long,
    @ColumnInfo(name = "mbid") val mbid: String?,
    @ColumnInfo(name = "year") val year: Int,
    @ColumnInfo(name = "artist") val artist: String?,
    @ColumnInfo(name = "album_artist") val albumArtist: String?,
    @ColumnInfo(name = "composer") val composer: String?,
    @ColumnInfo(name = "lyricist") val lyricist: String?,
) {
    companion object {
        /** Something was found online. */
        const val STATUS_MATCHED = 1

        /** Looked it up, nothing trustworthy found (retried after a while). */
        const val STATUS_NOT_FOUND = 2

        /** Network/server problem; retried on the next pass. */
        const val STATUS_ERROR = 3

        /** Tags and lyrics were already complete; nothing to look up. */
        const val STATUS_COMPLETE = 4
    }
}
