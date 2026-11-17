package code.name.monkey.retromusic.onlinemetadata

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import androidx.core.content.getSystemService
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import code.name.monkey.retromusic.ONLINE_METADATA
import code.name.monkey.retromusic.ONLINE_METADATA_LYRICS
import code.name.monkey.retromusic.ONLINE_METADATA_WIFI_ONLY
import code.name.monkey.retromusic.db.OnlineMetadataDao
import code.name.monkey.retromusic.db.OnlineMetadataEntity
import code.name.monkey.retromusic.db.OnlineMetadataEntity.Companion.STATUS_COMPLETE
import code.name.monkey.retromusic.db.OnlineMetadataEntity.Companion.STATUS_ERROR
import code.name.monkey.retromusic.db.OnlineMetadataEntity.Companion.STATUS_MATCHED
import code.name.monkey.retromusic.db.OnlineMetadataEntity.Companion.STATUS_NOT_FOUND
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.repository.SongRepository
import code.name.monkey.retromusic.service.MusicService
import code.name.monkey.retromusic.util.LyricUtil
import code.name.monkey.retromusic.util.PreferenceUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Melody Quest "Fill in missing info": while the app is running and the user is online (Wi-Fi only
 * by default), walks the library and looks up missing year / artist / album artist / composer /
 * lyricist on MusicBrainz and lyrics on LRCLIB. Results are stored in the app only.
 *
 * Off by default. Each song is checked once; "not found" is retried after 30 days, errors on the
 * next pass, and a song is checked again if its file changes.
 */
object OnlineMetadataManager : KoinComponent {

    data class Progress(val running: Boolean, val done: Int, val total: Int)

    private val dao: OnlineMetadataDao by inject()
    private val songRepository: SongRepository by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var passJob: Job? = null
    private lateinit var appContext: Context

    private val _progress = MutableStateFlow(Progress(false, 0, 0))
    val progress: StateFlow<Progress> = _progress

    private val RETRY_NOT_FOUND_MS = TimeUnit.DAYS.toMillis(30)

    /** Network callbacks fire a lot (even on signal changes); don't rescan more often than this. */
    private val PASS_COOLDOWN_MS = TimeUnit.MINUTES.toMillis(30)
    private var lastPassFinishedAt = 0L
    private const val REFRESH_LIBRARY_EVERY = 25
    private const val TAG = "OnlineMetadata"

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            ONLINE_METADATA, ONLINE_METADATA_WIFI_ONLY, ONLINE_METADATA_LYRICS -> {
                stop()
                maybeStart(force = true)
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = maybeStart()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (networkAllowed()) maybeStart() else stop()
        }
        override fun onLost(network: Network) {
            if (!networkAllowed()) stop()
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        OnlineMetadata.init(appContext)
        // Load what we already found so the library shows it, even offline.
        scope.launch {
            val saved = try {
                dao.all()
            } catch (e: Exception) {
                emptyList()
            }
            OnlineMetadata.replaceAll(saved)
            if (saved.any { it.status == STATUS_MATCHED }) refreshLibrary()
        }
        PreferenceManager.getDefaultSharedPreferences(appContext)
            .registerOnSharedPreferenceChangeListener(prefListener)
        try {
            appContext.getSystemService<ConnectivityManager>()
                ?.registerDefaultNetworkCallback(networkCallback)
        } catch (e: Exception) {
            // Some ROMs throw if too many callbacks are registered; we'll just not auto-start.
        }
    }

    private fun networkAllowed(): Boolean {
        val cm = appContext.getSystemService<ConnectivityManager>() ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        ) return false
        return !PreferenceUtil.isOnlineMetadataWifiOnly ||
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    @Synchronized
    fun maybeStart(force: Boolean = false) {
        if (!::appContext.isInitialized || !PreferenceUtil.isOnlineMetadata) return
        if (passJob?.isActive == true || !networkAllowed()) return
        if (!force && System.currentTimeMillis() - lastPassFinishedAt < PASS_COOLDOWN_MS) return
        passJob = scope.launch {
            var completed = false
            try {
                runPass()
                completed = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never let a background lookup take the app down.
                Log.w(TAG, "Online metadata pass failed", e)
            } finally {
                // Only a pass that got to the end counts; a cut-off one resumes when back online.
                if (completed) lastPassFinishedAt = System.currentTimeMillis()
            }
        }
    }

    @Synchronized
    fun stop() {
        passJob?.cancel()
        passJob = null
        _progress.value = _progress.value.copy(running = false)
    }

    private suspend fun runPass() {
        val songs = try {
            songRepository.songs()
        } catch (e: Exception) {
            return
        }
        val existing = dao.all().associateBy { it.songId }
        val now = System.currentTimeMillis()
        val todo = songs.filter { song ->
            val e = existing[song.id]
            when {
                e == null -> true
                e.path != song.data || e.dateModified != song.dateModified -> true
                e.status == STATUS_ERROR -> true
                e.status == STATUS_NOT_FOUND -> now - e.checkedAt > RETRY_NOT_FOUND_MS
                else -> false
            }
        }
        _progress.value = Progress(true, 0, todo.size)
        var changed = 0
        try {
            todo.forEachIndexed { index, song ->
                coroutineContext.ensureActive()
                val entity = try {
                    lookUp(song)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Lookup failed for ${song.data}", e)
                    errorEntity(song)
                }
                dao.upsert(entity)
                OnlineMetadata.put(entity)
                if (entity.status == STATUS_MATCHED && ++changed % REFRESH_LIBRARY_EVERY == 0) {
                    refreshLibrary()
                }
                _progress.value = Progress(true, index + 1, todo.size)
            }
        } finally {
            _progress.value = _progress.value.copy(running = false)
            if (changed % REFRESH_LIBRARY_EVERY != 0) {
                withContext(NonCancellable) { refreshLibrary() }
            }
        }
    }

    /** One song. Never throws (except cancellation); failures become STATUS_ERROR. */
    private suspend fun lookUp(song: Song): OnlineMetadataEntity {
        val missingTags = song.year <= 0 ||
                OnlineMetadataLookup.knownArtist(song.artistName) == null ||
                song.albumArtist.isNullOrBlank() ||
                song.composer.isNullOrBlank()
        val wantLyrics = PreferenceUtil.isOnlineMetadataLyrics && !hasLocalLyrics(song)

        var failed = false
        val tags = if (missingTags) {
            try {
                OnlineMetadataLookup.findTags(song, withCredits = song.composer.isNullOrBlank())
            } catch (e: OnlineMetadataApi.TransientException) {
                failed = true
                null
            }
        } else null

        val lyrics = if (wantLyrics) {
            try {
                OnlineMetadataLookup.findLyrics(song, tags?.artist)
            } catch (e: OnlineMetadataApi.TransientException) {
                failed = true
                null
            }
        } else null
        lyrics?.let { OnlineMetadata.saveLyrics(song.id, it) }

        val status = when {
            tags != null || lyrics != null -> STATUS_MATCHED
            failed -> STATUS_ERROR
            !missingTags && !wantLyrics -> STATUS_COMPLETE
            else -> STATUS_NOT_FOUND
        }
        return OnlineMetadataEntity(
            songId = song.id,
            path = song.data,
            dateModified = song.dateModified,
            status = status,
            checkedAt = System.currentTimeMillis(),
            mbid = tags?.mbid,
            year = tags?.year ?: 0,
            artist = tags?.artist,
            albumArtist = tags?.albumArtist,
            composer = tags?.composer,
            lyricist = tags?.lyricist,
        )
    }

    private fun errorEntity(song: Song) = OnlineMetadataEntity(
        songId = song.id,
        path = song.data,
        dateModified = song.dateModified,
        status = STATUS_ERROR,
        checkedAt = System.currentTimeMillis(),
        mbid = null,
        year = 0,
        artist = null,
        albumArtist = null,
        composer = null,
        lyricist = null,
    )

    private fun hasLocalLyrics(song: Song): Boolean {
        if (OnlineMetadata.syncedLyrics(song.id) != null || OnlineMetadata.plainLyrics(song.id) != null) {
            return true
        }
        if (LyricUtil.getSyncedLyricsFile(song) != null) return true
        return try {
            !AudioFileIO.read(File(song.data)).tagOrCreateDefault.getFirst(FieldKey.LYRICS).isNullOrBlank()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Tag editor "Fill from online": always does a fresh, full lookup for this one song (works even
     * when the background feature is off). Returns null if nothing was found.
     * @throws OnlineMetadataApi.TransientException if offline / server trouble.
     */
    suspend fun lookUpForEditor(song: Song): Pair<TagMatch?, LyricsMatch?> = withContext(Dispatchers.IO) {
        val tags = OnlineMetadataLookup.findTags(song, withCredits = true)
        val lyrics = OnlineMetadataLookup.findLyrics(song, tags?.artist)
        tags to lyrics
    }

    /** Settings: (songs matched, songs checked). */
    suspend fun counts(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        dao.countWithStatus(STATUS_MATCHED) to dao.count()
    }

    /** Settings: forget everything found online. */
    suspend fun clearAll() {
        stop()
        withContext(Dispatchers.IO) {
            dao.clear()
            OnlineMetadata.clear()
        }
        refreshLibrary()
        maybeStart(force = true)
    }

    private suspend fun refreshLibrary() = withContext(Dispatchers.Main) {
        LocalBroadcastManager.getInstance(appContext)
            .sendBroadcast(Intent(MusicService.MEDIA_STORE_CHANGED))
    }
}
