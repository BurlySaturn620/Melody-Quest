package code.name.monkey.retromusic.helper

import code.name.monkey.retromusic.model.Song
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Melody Quest "Smart Shuffle" — Spotify-style shuffle for a local library.
 *
 * Two parts, both pure functions so they can run off the main thread:
 *  1. [arrange]: a "human" shuffle that avoids playing the same artist back to back.
 *  2. [pickSuggestions] + [interleave]: finds songs from the rest of the library that fit the
 *     queue (shared artists / album artists / albums / composers / era, boosted by how often you
 *     play them) and sprinkles roughly one in every [SUGGESTION_EVERY] songs.
 *
 * No network, no extra index: it only uses MediaStore tags and the existing play-count/history DB.
 */
object SmartShuffle {

    /** One suggestion after every N queue songs (Spotify does roughly 1 in 3–4). */
    const val SUGGESTION_EVERY = 3

    /** Queues shorter than this just get the smarter ordering, no suggestions. */
    const val MIN_SEED_SIZE = 5

    private const val MAX_SUGGESTIONS = 60
    private const val LOOK_AHEAD = 8

    /**
     * Shuffles [songs] in place keeping the song at [current] first, while trying not to put two
     * songs by the same artist next to each other.
     */
    fun arrange(songs: MutableList<Song>, current: Int, random: Random = Random.Default) {
        if (songs.isEmpty()) return
        val first = if (current in songs.indices) songs.removeAt(current) else null
        val pool = songs.shuffled(random).toMutableList()
        songs.clear()
        var last: Song? = first
        while (pool.isNotEmpty()) {
            val window = min(pool.size, LOOK_AHEAD)
            var pick = 0
            for (i in 0 until window) {
                if (!sameArtist(pool[i], last)) {
                    pick = i
                    break
                }
            }
            val next = pool.removeAt(pick)
            songs.add(next)
            last = next
        }
        if (first != null) songs.add(0, first)
    }

    /** How many suggestions a queue of [seedSize] songs should get. */
    fun suggestionCount(seedSize: Int): Int =
        if (seedSize < MIN_SEED_SIZE) 0 else min(MAX_SUGGESTIONS, seedSize / SUGGESTION_EVERY)

    /**
     * Picks up to [count] songs from [library] that are not in [seed] and fit it.
     *
     * @param playCounts song id -> times played (from the play-count DB)
     * @param recentlyPlayed ids played recently; slightly pushed down to keep things fresh
     */
    fun pickSuggestions(
        seed: List<Song>,
        library: List<Song>,
        playCounts: Map<Long, Int>,
        recentlyPlayed: Set<Long>,
        count: Int,
        random: Random = Random.Default,
    ): List<Song> {
        if (count <= 0 || seed.isEmpty() || library.isEmpty()) return emptyList()

        val seedIds = HashSet<Long>(seed.size * 2)
        val artists = HashMap<String, Int>()
        val albums = HashSet<Long>()
        val composers = HashSet<String>()
        val years = ArrayList<Int>()
        for (song in seed) {
            seedIds.add(song.id)
            artistKeys(song).forEach { artists[it] = (artists[it] ?: 0) + 1 }
            albums.add(song.albumId)
            key(song.composer)?.let { composers.add(it) }
            if (song.year > 0) years.add(song.year)
        }
        years.sort()
        val medianYear = if (years.isEmpty()) 0 else years[years.size / 2]

        val scored = ArrayList<Pair<Song, Double>>()
        for (candidate in library) {
            if (candidate.id in seedIds) continue
            var relevance = 0.0
            val candidateArtist = key(candidate.artistName)
            val candidateAlbumArtist = key(candidate.albumArtist)
            if (candidateArtist != null && candidateArtist in artists) {
                relevance += 3.0 + min(1.0, (artists[candidateArtist] ?: 0) / 10.0)
            }
            if (candidateAlbumArtist != null && candidateAlbumArtist != candidateArtist &&
                candidateAlbumArtist in artists
            ) {
                relevance += 2.0
            }
            if (candidate.albumId in albums) relevance += 2.0
            key(candidate.composer)?.let { if (it in composers) relevance += 1.5 }
            if (medianYear > 0 && candidate.year > 0) {
                val gap = abs(candidate.year - medianYear)
                if (gap <= 3) relevance += 1.0 else if (gap <= 8) relevance += 0.4
            }
            val plays = playCounts[candidate.id] ?: 0
            // Songs with no connection at all only qualify if you actually play them.
            if (relevance <= 0.0 && plays == 0) continue

            var score = relevance + min(2.0, ln(1.0 + plays) * 0.6)
            if (candidate.id in recentlyPlayed) score -= 1.5
            score += random.nextDouble() * 1.2 // keep each smart shuffle a little different
            scored.add(candidate to score)
        }
        scored.sortByDescending { it.second }

        // Don't let one artist take over the suggestions.
        val perArtistCap = max(2, count / 4)
        val perArtist = HashMap<String, Int>()
        val result = ArrayList<Song>(count)
        for ((song, _) in scored) {
            val artist = key(song.artistName) ?: ""
            val used = perArtist[artist] ?: 0
            if (used >= perArtistCap) continue
            perArtist[artist] = used + 1
            result.add(song)
            if (result.size >= count) break
        }
        return result.shuffled(random)
    }

    /**
     * Returns [queue] with [suggestions] inserted after every [every] songs. Suggestions that do
     * not fit (queue too short) are dropped.
     */
    fun interleave(queue: List<Song>, suggestions: List<Song>, every: Int = SUGGESTION_EVERY): List<Song> {
        if (suggestions.isEmpty()) return queue
        val out = ArrayList<Song>(queue.size + suggestions.size)
        var s = 0
        queue.forEachIndexed { index, song ->
            out.add(song)
            if ((index + 1) % every == 0 && s < suggestions.size && index < queue.lastIndex) {
                out.add(suggestions[s++])
            }
        }
        return out
    }

    private fun sameArtist(a: Song, b: Song?): Boolean {
        if (b == null) return false
        if (a.artistId > 0 && a.artistId == b.artistId) return true
        val ka = key(a.artistName)
        return ka != null && ka == key(b.artistName)
    }

    private fun artistKeys(song: Song): List<String> =
        listOfNotNull(key(song.artistName), key(song.albumArtist)).distinct()

    private fun key(value: String?): String? {
        val k = value?.trim()?.lowercase(Locale.ROOT)
        return if (k.isNullOrEmpty() || k == "<unknown>" || k == "unknown") null else k
    }
}
