package code.name.monkey.retromusic.onlinemetadata

import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.util.ArtistSeparator
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.text.Normalizer
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

/** What MusicBrainz knows about one recording. Null fields = not known. */
data class TagMatch(
    val mbid: String,
    val year: Int,
    val artist: String?,
    val albumArtist: String?,
    val composer: String?,
    val lyricist: String?,
    val genre: String? = null,
    /** Only set when the user's album name matched a specific release. */
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
)

data class LyricsMatch(val plain: String?, val synced: String?)

/**
 * Finds a song on MusicBrainz / LRCLIB. Errs on the side of "not found": a wrong year or composer
 * is worse than an empty one.
 */
object OnlineMetadataLookup {

    private const val MIN_SEARCH_SCORE = 85
    private const val MAX_DURATION_DIFF_MS = 7_000L
    private const val STRICT_DURATION_DIFF_MS = 3_000L

    // ------------------------------------------------------------------ MusicBrainz

    /**
     * @param withCredits also look up composer/lyricist (one extra request).
     * @throws OnlineMetadataApi.TransientException when the network/server failed.
     */
    suspend fun findTags(song: Song, withCredits: Boolean): TagMatch? {
        val title = cleanTitle(song.title)
        if (title.isBlank()) return null
        val artist = knownArtist(song.artistName)

        val query = buildString {
            append("recording:\"").append(luceneEscape(title)).append('"')
            if (artist != null) {
                append(" AND artist:\"").append(luceneEscape(artist)).append('"')
            } else if (song.duration > 0) {
                append(" AND dur:[")
                    .append(song.duration - STRICT_DURATION_DIFF_MS).append(" TO ")
                    .append(song.duration + STRICT_DURATION_DIFF_MS).append(']')
            }
        }
        val result = OnlineMetadataApi.musicBrainz(
            "recording", mapOf("query" to query, "limit" to "10")
        )?.obj() ?: return null
        val recordings = result.arr("recordings") ?: return null

        var best: JsonObject? = null
        var bestScore = Int.MIN_VALUE
        for (element in recordings) {
            val recording = element.obj() ?: continue
            val score = scoreRecording(recording, song, title, artist) ?: continue
            if (score > bestScore) {
                best = recording
                bestScore = score
            }
        }
        val recording = best ?: return null
        val mbid = recording.str("id") ?: return null

        val release = pickRelease(recording, song.albumName)
        val recordingArtist = artistCredit(recording.arr("artist-credit"))
        val albumArtist = release?.let { artistCredit(it.arr("artist-credit")) } ?: recordingArtist

        var composer: String? = null
        var lyricist: String? = null
        var genre: String? = null
        if (withCredits) {
            val details = try {
                OnlineMetadataApi.musicBrainz(
                    "recording/$mbid",
                    mapOf("inc" to "work-rels+work-level-rels+artist-rels+genres")
                )?.obj()
            } catch (e: OnlineMetadataApi.TransientException) {
                null // keep the year/album artist we already have
            }
            if (details != null) {
                val credits = workCredits(details)
                composer = credits.first
                lyricist = credits.second
                genre = topGenre(details)
            }
        }
        val (trackNumber, discNumber) = trackPosition(release)

        return TagMatch(
            mbid = mbid,
            year = parseYear(recording.str("first-release-date"))
                ?: parseYear(release?.str("date")) ?: 0,
            artist = recordingArtist,
            albumArtist = albumArtist,
            composer = composer,
            lyricist = lyricist,
            genre = genre,
            trackNumber = trackNumber,
            discNumber = discNumber,
        )
    }

    /** Most-voted genre, "indie rock" -> "Indie Rock". */
    private fun topGenre(recording: JsonObject): String? =
        recording.arr("genres")?.mapNotNull { it.obj() }
            ?.maxByOrNull { it.int("count") ?: 0 }
            ?.str("name")
            ?.split(' ')
            ?.joinToString(" ") { word -> word.replaceFirstChar { it.titlecase(Locale.ROOT) } }
            ?.ifBlank { null }

    /** (track, disc) of this recording on [release], from the search result's media list. */
    private fun trackPosition(release: JsonObject?): Pair<Int?, Int?> {
        val media = release?.arr("media")?.mapNotNull { it.obj() } ?: return null to null
        for (medium in media) {
            val track = medium.arr("track")?.firstOrNull()?.obj() ?: continue
            val number = track.str("number")?.toIntOrNull() ?: track.int("position")
            val disc = medium.int("position")
            if (number != null) return number to disc?.takeIf { media.size > 1 || it > 1 }
        }
        return null to null
    }

    /** Returns a score, or null if this recording is not a believable match. */
    private fun scoreRecording(recording: JsonObject, song: Song, title: String, artist: String?): Int? {
        val searchScore = recording.int("score") ?: 0
        if (searchScore < MIN_SEARCH_SCORE) return null
        if (artist == null && searchScore < 95) return null

        val candidateTitle = recording.str("title") ?: return null
        if (!similar(normalize(cleanTitle(candidateTitle)), normalize(title))) return null

        val names = creditNames(recording.arr("artist-credit"))
        if (artist != null) {
            val wanted = ArtistSeparator.split(artist).map(::normalize).filter { it.isNotEmpty() }
                .ifEmpty { listOf(normalize(artist)) }
            val joined = normalize(artistCredit(recording.arr("artist-credit")) ?: "")
            val artistOk = wanted.any { w -> names.any { similar(it, w) } } || similar(joined, normalize(artist))
            if (!artistOk) return null
        }

        var score = searchScore
        val length = recording.long("length")
        if (song.duration > 0 && length != null && length > 0) {
            val diff = abs(length - song.duration)
            val limit = if (artist == null) STRICT_DURATION_DIFF_MS else MAX_DURATION_DIFF_MS
            if (diff > limit) return null
            score += ((limit - diff) / 1000).toInt() // closer length = better
        } else if (artist == null) {
            return null // title alone is too weak without a duration check
        }

        val album = normalize(song.albumName)
        if (album.isNotEmpty() && recording.arr("releases")?.any {
                similar(normalize(it.obj()?.str("title") ?: ""), album)
            } == true
        ) {
            score += 15
        }
        val type = (recording.arr("releases")?.firstOrNull()?.obj()?.obj("release-group"))
            ?.str("primary-type")
        if (type.equals("Album", true)) score += 2
        return score
    }

    /** The release that matches the user's album name, else the earliest official album. */
    private fun pickRelease(recording: JsonObject, albumName: String): JsonObject? {
        val releases = recording.arr("releases")?.mapNotNull { it.obj() } ?: return null
        val album = normalize(albumName)
        if (album.isNotEmpty()) {
            releases.firstOrNull { similar(normalize(it.str("title") ?: ""), album) }?.let { return it }
        }
        // No album match: only trust the recording's own artist for "album artist".
        return null
    }

    /** (composer, lyricist) from the works this recording performs. */
    private fun workCredits(recording: JsonObject): Pair<String?, String?> {
        val composers = LinkedHashSet<String>()
        val lyricists = LinkedHashSet<String>()
        val writers = LinkedHashSet<String>()
        recording.arr("relations")?.forEach { rel ->
            val relation = rel.obj() ?: return@forEach
            if (relation.str("target-type") != "work") return@forEach
            relation.obj("work")?.arr("relations")?.forEach { workRel ->
                val credit = workRel.obj() ?: return@forEach
                if (credit.str("target-type") != "artist") return@forEach
                val name = credit.obj("artist")?.str("name") ?: return@forEach
                when (credit.str("type")) {
                    "composer" -> composers.add(name)
                    "lyricist", "librettist" -> lyricists.add(name)
                    "writer" -> writers.add(name)
                }
            }
        }
        // "writer" = wrote both words and music.
        val composer = composers.ifEmpty { writers }.joinToString(", ").ifBlank { null }
        val lyricist = lyricists.ifEmpty { writers }.joinToString(", ").ifBlank { null }
        return composer to lyricist
    }

    // ------------------------------------------------------------------ LRCLIB

    /** @throws OnlineMetadataApi.TransientException when the network/server failed. */
    suspend fun findLyrics(song: Song, artistOverride: String?): LyricsMatch? {
        val title = song.title.trim()
        val artist = knownArtist(song.artistName) ?: artistOverride ?: return null
        val seconds = song.duration / 1000

        val album = song.albumName.trim()
        if (album.isNotEmpty() && seconds > 0) {
            val exact = OnlineMetadataApi.lrcLib(
                "get", mapOf(
                    "track_name" to title,
                    "artist_name" to artist,
                    "album_name" to album,
                    "duration" to seconds.toString(),
                )
            )?.obj()
            toLyrics(exact)?.let { return it }
        }

        val results = OnlineMetadataApi.lrcLib(
            "search", mapOf("track_name" to title, "artist_name" to artist)
        ) as? JsonArray ?: return null
        val wantedTitle = normalize(cleanTitle(title))
        for (element in results) {
            val item = element.obj() ?: continue
            if (!similar(normalize(cleanTitle(item.str("trackName") ?: "")), wantedTitle)) continue
            val duration = item.double("duration")
            if (seconds > 0 && duration != null && abs(duration - seconds) > 3.0) continue
            toLyrics(item)?.let { return it }
        }
        return null
    }

    private fun toLyrics(item: JsonObject?): LyricsMatch? {
        if (item == null || item.bool("instrumental") == true) return null
        val plain = item.str("plainLyrics")?.takeIf { it.isNotBlank() }
        val synced = item.str("syncedLyrics")?.takeIf { it.isNotBlank() }
        return if (plain == null && synced == null) null else LyricsMatch(plain, synced)
    }

    // ------------------------------------------------------------------ text helpers

    fun knownArtist(name: String?): String? {
        val n = name?.trim()
        return if (n.isNullOrEmpty() || n.equals("<unknown>", true) || n.equals("unknown", true) ||
            n.equals("unknown artist", true)
        ) null else n
    }

    /** "Song (Remastered 2011)" / "Song - Live" -> "Song" for matching. */
    private fun cleanTitle(title: String): String =
        title.replace(Regex("""\s*[(\[][^)\]]*(remaster|live|mono|stereo|version|edit|mix|bonus|deluxe)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+-\s+.*(remaster|live|mono|stereo|version|edit|mix).*$""", RegexOption.IGNORE_CASE), "")
            .trim()

    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .replace("&", " and ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    private fun similar(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val (short, long) = if (a.length <= b.length) a to b else b to a
        // e.g. "beatles" vs "the beatles"; require the shorter one to be most of the longer one.
        return short.length >= 4 && " $long ".contains(" $short ") && short.length * 10 >= long.length * 6
    }

    private fun luceneEscape(text: String): String = text.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun parseYear(date: String?): Int? {
        val year = date?.take(4)?.toIntOrNull() ?: return null
        return year.takeIf { it in 1900..(Calendar.getInstance().get(Calendar.YEAR) + 1) }
    }

    private fun creditNames(credits: JsonArray?): List<String> =
        credits?.mapNotNull { c ->
            val o = c.obj() ?: return@mapNotNull null
            o.str("name") ?: o.obj("artist")?.str("name")
        }?.map(::normalize) ?: emptyList()

    /** "Artist A feat. Artist B" exactly as MusicBrainz credits it. */
    private fun artistCredit(credits: JsonArray?): String? {
        if (credits == null || credits.size() == 0) return null
        return buildString {
            credits.forEach { c ->
                val o = c.obj() ?: return@forEach
                append(o.str("name") ?: o.obj("artist")?.str("name") ?: "")
                append(o.str("joinphrase") ?: "")
            }
        }.trim().ifBlank { null }
    }

    // ------------------------------------------------------------------ JSON helpers

    private fun JsonElement.obj(): JsonObject? = if (isJsonObject) asJsonObject else null
    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.arr(key: String): JsonArray? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray
    private fun JsonObject.prim(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
    private fun JsonObject.str(key: String): String? = prim(key)?.asString
    private fun JsonObject.int(key: String): Int? = prim(key)?.takeIf { it.isNumber }?.asInt
    private fun JsonObject.long(key: String): Long? = prim(key)?.takeIf { it.isNumber }?.asLong
    private fun JsonObject.double(key: String): Double? = prim(key)?.takeIf { it.isNumber }?.asDouble
    private fun JsonObject.bool(key: String): Boolean? = prim(key)?.takeIf { it.isBoolean }?.asBoolean
}
