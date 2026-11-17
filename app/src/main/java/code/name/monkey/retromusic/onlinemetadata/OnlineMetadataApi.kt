package code.name.monkey.retromusic.onlinemetadata

import code.name.monkey.retromusic.BuildConfig
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Tiny HTTP layer for MusicBrainz (tags) and LRCLIB (lyrics).
 *
 * MusicBrainz asks clients for at most ~1 request/second and a descriptive User-Agent with a way
 * to contact the developer, so every MusicBrainz call goes through [musicBrainzGate].
 */
object OnlineMetadataApi {

    /**
     * Put a URL or e-mail for the project here before publishing (MusicBrainz asks for it so they
     * can reach you instead of blocking the app if something goes wrong).
     */
    const val CONTACT = "tootbrushoil@gmail.com"

    private const val MUSICBRAINZ = "https://musicbrainz.org/ws/2/"
    private const val LRCLIB = "https://lrclib.net/api/"
    private const val MUSICBRAINZ_INTERVAL_MS = 1100L
    private const val LRCLIB_INTERVAL_MS = 250L

    /** Thrown for problems worth retrying later (offline, timeouts, 5xx, rate limited). */
    class TransientException(message: String) : IOException(message)

    private val userAgent: String =
        "MelodyQuest/${BuildConfig.VERSION_NAME}" + if (CONTACT.isBlank()) "" else " ( $CONTACT )"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", userAgent)
                        .header("Accept", "application/json")
                        .build()
                )
            }
            .build()
    }

    private val musicBrainzGate = Mutex()
    private var lastMusicBrainzCall = 0L
    private val lrcLibGate = Mutex()
    private var lastLrcLibCall = 0L

    /** GET a MusicBrainz path ("recording", "recording/<mbid>") with query params. */
    suspend fun musicBrainz(path: String, params: Map<String, String>): JsonElement? {
        val url = MUSICBRAINZ.toHttpUrl().newBuilder()
            .addPathSegments(path)
            .addQueryParameter("fmt", "json")
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
        return musicBrainzGate.withLock {
            try {
                callMusicBrainz(url)
            } catch (e: TransientException) {
                // 503 = MusicBrainz says slow down: back off once, then give up for this song.
                delay(5_000)
                callMusicBrainz(url)
            }
        }
    }

    /** Must be called while holding [musicBrainzGate]. */
    private suspend fun callMusicBrainz(url: HttpUrl): JsonElement? {
        val wait = lastMusicBrainzCall + MUSICBRAINZ_INTERVAL_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        lastMusicBrainzCall = System.currentTimeMillis()
        return get(url)
    }

    /** GET an LRCLIB endpoint ("get", "search"). Returns null on 404. */
    suspend fun lrcLib(endpoint: String, params: Map<String, String>): JsonElement? {
        val url = LRCLIB.toHttpUrl().newBuilder()
            .addPathSegments(endpoint)
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
        return lrcLibGate.withLock {
            val wait = lastLrcLibCall + LRCLIB_INTERVAL_MS - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            lastLrcLibCall = System.currentTimeMillis()
            get(url)
        }
    }

    /** Blocking GET; call from Dispatchers.IO. Returns null for 404, throws for other failures. */
    private fun get(url: HttpUrl): JsonElement? {
        val request = Request.Builder().url(url).get().build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw TransientException(e.message ?: "network error")
        }
        response.use {
            return when {
                it.code == 404 -> null
                it.code == 429 || it.code == 503 || it.code >= 500 ->
                    throw TransientException("HTTP ${it.code}")
                !it.isSuccessful -> null // 400 etc: our query was bad, treat as "not found"
                else -> {
                    val body = it.body?.string() ?: return null
                    try {
                        JsonParser.parseString(body)
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }
    }
}
