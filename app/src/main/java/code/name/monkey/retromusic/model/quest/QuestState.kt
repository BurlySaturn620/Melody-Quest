package code.name.monkey.retromusic.model.quest

/**
 * Melody Quest game progress. Saved as JSON (Gson) in a private SharedPreferences file.
 *
 * Lives under `model` on purpose: proguard-rules.pro keeps `code.name.monkey.retromusic.model.**`
 * un-obfuscated, so field names (and therefore saved progress) survive app updates.
 * Every field needs a default so older saves load cleanly when new fields are added.
 */
data class QuestState(
    var xp: Int = 0,

    // Lifetime counters
    var totalPlays: Int = 0,
    var totalListenedMs: Long = 0,
    var nightPlays: Int = 0,
    var earlyPlays: Int = 0,
    var smartShufflePlays: Int = 0,
    var albumsCompleted: Int = 0,
    var questsCompleted: Int = 0,
    var knownArtists: MutableSet<String> = mutableSetOf(),
    var artistPlays: MutableMap<String, Int> = mutableMapOf(),
    var seededFromHistory: Boolean = false,

    // Bests (for achievements that are about "in one day / week")
    var bestDayMs: Long = 0,
    var bestWeekNewArtists: Int = 0,
    var bestStreak: Int = 0,

    // Listening streak
    var lastListenDay: String = "",
    var streak: Int = 0,

    // This week
    var weekKey: String = "",
    var newArtistsThisWeek: Int = 0,

    // Today
    var dayKey: String = "",
    var playsToday: Int = 0,
    var msToday: Long = 0,
    var artistsToday: MutableSet<String> = mutableSetOf(),
    var albumsToday: Int = 0,
    var gemsToday: Int = 0,
    var smartToday: Int = 0,
    var dailyQuests: MutableList<DailyQuest> = mutableListOf(),
    var dailyBonusClaimed: Boolean = false,

    // Album run: tracks of the current album heard back to back
    var albumRunId: Long = -1,
    var albumRunSongs: MutableSet<Long> = mutableSetOf(),

    /** achievement id -> unlock time (ms). */
    var unlocked: MutableMap<String, Long> = mutableMapOf(),

    /** Things that happened while the app was in the background, shown on next open. */
    var unseenNews: MutableList<String> = mutableListOf(),
)

data class DailyQuest(
    var type: String = "",
    var target: Int = 1,
    var progress: Int = 0,
    var done: Boolean = false,
)
