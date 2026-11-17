package code.name.monkey.retromusic.quest

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.model.quest.DailyQuest
import code.name.monkey.retromusic.model.quest.QuestState
import code.name.monkey.retromusic.repository.Repository
import code.name.monkey.retromusic.util.PreferenceUtil
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * Melody Quest game layer: XP, levels, achievements and daily quests.
 *
 * Fed by MusicService whenever a song "counts" as played (same rule as the play counter: more than
 * half of it was heard). Everything is local; state is one small JSON blob.
 *
 * Gamer-friendly rule: it never pops anything up while the app is in the background (e.g. while
 * you're in a game). News from then is kept and shown on the Home card next time.
 */
object QuestEngine : KoinComponent {

    private const val TAG = "QuestEngine"
    private const val PREFS = "melody_quest_game"
    private const val KEY_STATE = "state"
    private const val MAX_UNSEEN = 20

    private val repository: Repository by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val gson = Gson()
    private val startedActivities = AtomicInteger(0)
    private lateinit var app: Application

    private var state = QuestState()
    private val _state = MutableStateFlow(QuestState())

    /** Read-only snapshots for the UI. */
    val stateFlow: StateFlow<QuestState> = _state

    fun init(application: Application) {
        app = application
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities.incrementAndGet()
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities.decrementAndGet()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        scope.launch {
            mutex.withLock {
                state = load()
                seedKnownArtists()
                rollOver(Calendar.getInstance())
                saveAndPublish()
            }
        }
    }

    private val isForeground: Boolean get() = startedActivities.get() > 0

    // ------------------------------------------------------------------ events

    /** Called by MusicService (background thread) when [song] counted as played. */
    fun onListened(song: Song, listenedMs: Long, smartShuffle: Boolean) {
        if (!::app.isInitialized || !PreferenceUtil.isQuestMode || song.id == Song.emptySong.id) return
        scope.launch {
            val news = try {
                mutex.withLock { record(song, listenedMs, smartShuffle) }
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't record listen", e)
                emptyList()
            }
            announce(news)
        }
    }

    /** Make sure today's quests exist (e.g. when the Quest Log opens after midnight). */
    fun refresh() {
        if (!::app.isInitialized) return
        scope.launch {
            mutex.withLock {
                if (rollOver(Calendar.getInstance())) saveAndPublish()
            }
        }
    }

    /** The user has seen the "while you were away" news. */
    fun markNewsSeen() {
        if (!::app.isInitialized) return
        scope.launch {
            mutex.withLock {
                if (state.unseenNews.isNotEmpty()) {
                    state.unseenNews.clear()
                    saveAndPublish()
                }
            }
        }
    }

    /** Settings: start over from level 1. */
    fun reset() {
        if (!::app.isInitialized) return
        scope.launch {
            mutex.withLock {
                state = QuestState()
                seedKnownArtists()
                rollOver(Calendar.getInstance())
                saveAndPublish()
            }
        }
    }

    // ------------------------------------------------------------------ rules

    private suspend fun record(song: Song, listenedMs: Long, smartShuffle: Boolean): List<String> {
        val now = Calendar.getInstance()
        rollOver(now)
        val s = state
        val levelBefore = QuestCatalog.levelFor(s.xp)
        val news = ArrayList<String>()

        // Counters
        val ms = listenedMs.coerceIn(0, max(song.duration, 0L) + 60_000)
        s.totalPlays++
        s.totalListenedMs += ms
        s.playsToday++
        s.msToday += ms
        s.bestDayMs = max(s.bestDayMs, s.msToday)
        when (now.get(Calendar.HOUR_OF_DAY)) {
            in 0..4 -> s.nightPlays++
            in 5..7 -> s.earlyPlays++
        }
        if (smartShuffle) {
            s.smartShufflePlays++
            s.smartToday++
        }

        // Artists
        artistKey(song.artistName)?.let { artist ->
            s.artistsToday.add(artist)
            s.artistPlays[artist] = (s.artistPlays[artist] ?: 0) + 1
            if (s.knownArtists.add(artist)) {
                s.newArtistsThisWeek++
                s.bestWeekNewArtists = max(s.bestWeekNewArtists, s.newArtistsThisWeek)
            }
        }

        // Streak (days in a row with at least one song)
        if (s.lastListenDay != s.dayKey) {
            s.streak = if (s.lastListenDay == dayKey(yesterday(now))) s.streak + 1 else 1
            s.lastListenDay = s.dayKey
            s.bestStreak = max(s.bestStreak, s.streak)
        }

        // Hidden gems: songs you've barely played (the play count already includes this play)
        val playCount = try {
            repository.findSongExistInPlayCount(song.id)?.playCount ?: 0
        } catch (e: Exception) {
            Int.MAX_VALUE
        }
        if (playCount <= QuestCatalog.GEM_MAX_PLAYS) s.gemsToday++

        // Album run: every track of one album heard in one go (any order)
        if (song.albumId != s.albumRunId) {
            s.albumRunId = song.albumId
            s.albumRunSongs.clear()
        }
        s.albumRunSongs.add(song.id)
        val albumSize = try {
            repository.albumById(song.albumId).songCount
        } catch (e: Exception) {
            0
        }
        if (albumSize >= 3 && s.albumRunSongs.size >= albumSize) {
            s.albumsCompleted++
            s.albumsToday++
            s.albumRunId = -1
            s.albumRunSongs.clear()
            news += app.getString(R.string.quest_news_album, song.albumName)
        }

        s.xp += QuestCatalog.XP_PER_SONG

        // Daily quests
        for (quest in s.dailyQuests) {
            if (quest.done) continue
            val type = QuestCatalog.questType(quest.type) ?: continue
            quest.progress = type.progress(s).coerceAtMost(quest.target)
            if (quest.progress >= quest.target) {
                quest.done = true
                s.questsCompleted++
                s.xp += QuestCatalog.XP_PER_DAILY_QUEST
                news += app.getString(
                    R.string.quest_news_daily,
                    app.getString(type.title, quest.target),
                    QuestCatalog.XP_PER_DAILY_QUEST
                )
            }
        }
        if (!s.dailyBonusClaimed && s.dailyQuests.isNotEmpty() && s.dailyQuests.all { it.done }) {
            s.dailyBonusClaimed = true
            s.xp += QuestCatalog.XP_DAILY_CLEAR_BONUS
            news += app.getString(R.string.quest_news_daily_clear, QuestCatalog.XP_DAILY_CLEAR_BONUS)
        }

        // Achievements
        for (achievement in QuestCatalog.achievements) {
            if (achievement.id in s.unlocked) continue
            if (achievement.progress(s) >= achievement.target) {
                s.unlocked[achievement.id] = now.timeInMillis
                s.xp += achievement.xp
                news += app.getString(
                    R.string.quest_news_achievement,
                    app.getString(achievement.title),
                    achievement.xp
                )
            }
        }

        val levelAfter = QuestCatalog.levelFor(s.xp)
        if (levelAfter > levelBefore) {
            news += app.getString(
                R.string.quest_news_level_up,
                levelAfter,
                app.getString(QuestCatalog.rankFor(levelAfter))
            )
        }

        if (news.isNotEmpty() && !isForeground) {
            s.unseenNews.addAll(news)
            while (s.unseenNews.size > MAX_UNSEEN) s.unseenNews.removeAt(0)
        }
        saveAndPublish()
        return news
    }

    /** New day: fresh counters and quests. New week: reset the weekly artist count. */
    private fun rollOver(now: Calendar): Boolean {
        var changed = false
        val s = state
        val today = dayKey(now)
        if (s.dayKey != today) {
            s.dayKey = today
            s.playsToday = 0
            s.msToday = 0
            s.artistsToday.clear()
            s.albumsToday = 0
            s.gemsToday = 0
            s.smartToday = 0
            s.dailyBonusClaimed = false
            s.dailyQuests = QuestCatalog.questsForDay(today)
                .map { (type, target) -> DailyQuest(type.id, target) }
                .toMutableList()
            changed = true
        }
        val week = weekKey(now)
        if (s.weekKey != week) {
            s.weekKey = week
            s.newArtistsThisWeek = 0
            changed = true
        }
        return changed
    }

    /** So "Explorer" means new to you, not new since installing Melody Quest. */
    private suspend fun seedKnownArtists() {
        if (state.seededFromHistory) return
        try {
            repository.playCountSongs().forEach { entity ->
                artistKey(entity.artistName)?.let { state.knownArtists.add(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read play history", e)
        }
        state.seededFromHistory = true
    }

    // ------------------------------------------------------------------ helpers

    /** Streak to show: it's broken if you skipped a whole day. */
    fun displayStreak(s: QuestState): Int {
        val now = Calendar.getInstance()
        return if (s.lastListenDay == dayKey(now) || s.lastListenDay == dayKey(yesterday(now))) {
            s.streak
        } else 0
    }

    private fun artistKey(name: String?): String? {
        val key = name?.trim()?.lowercase(Locale.ROOT)
        return if (key.isNullOrEmpty() || key == "<unknown>" || key == "unknown" || key == "unknown artist") {
            null
        } else key
    }

    private fun dayKey(calendar: Calendar): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.time)

    private fun weekKey(calendar: Calendar): String {
        val c = calendar.clone() as Calendar
        c.firstDayOfWeek = Calendar.MONDAY
        c.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        if (c.after(calendar)) c.add(Calendar.WEEK_OF_YEAR, -1)
        return dayKey(c) // the Monday that starts this week
    }

    private fun yesterday(now: Calendar): Calendar =
        (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }

    private suspend fun announce(news: List<String>) {
        if (news.isEmpty() || !isForeground) return
        withContext(Dispatchers.Main) {
            Toast.makeText(app, news.take(3).joinToString("\n"), Toast.LENGTH_LONG).show()
        }
    }

    private fun load(): QuestState = try {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_STATE, null)
            ?.let { gson.fromJson(it, QuestState::class.java) }
            ?: QuestState()
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't load quest progress", e)
        QuestState()
    }

    private fun saveAndPublish() {
        val json = gson.toJson(state)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_STATE, json).apply()
        _state.value = gson.fromJson(json, QuestState::class.java) // detached copy for the UI
    }
}
