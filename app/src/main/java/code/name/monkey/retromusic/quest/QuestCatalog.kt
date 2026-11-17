package code.name.monkey.retromusic.quest

import androidx.annotation.StringRes
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.model.quest.QuestState
import kotlin.math.max
import kotlin.random.Random

/** Rules of the game: XP values, level curve, ranks, achievements and daily quests. */
object QuestCatalog {

    const val XP_PER_SONG = 10
    const val XP_PER_DAILY_QUEST = 40
    const val XP_DAILY_CLEAR_BONUS = 60
    const val DAILY_QUEST_COUNT = 3

    /** Songs heard at least this far count as "hidden gems" if played this few times before. */
    const val GEM_MAX_PLAYS = 2

    // ------------------------------------------------------------------ levels

    /** Total XP needed to reach [level]: 0, 100, 300, 600, 1000, 1500... */
    fun xpForLevel(level: Int): Int = 50 * (level - 1) * level

    fun levelFor(xp: Int): Int {
        var level = 1
        while (xpForLevel(level + 1) <= xp) level++
        return level
    }

    @StringRes
    fun rankFor(level: Int): Int = when {
        level >= 50 -> R.string.quest_rank_legend
        level >= 30 -> R.string.quest_rank_virtuoso
        level >= 20 -> R.string.quest_rank_bard
        level >= 10 -> R.string.quest_rank_adventurer
        level >= 5 -> R.string.quest_rank_wanderer
        else -> R.string.quest_rank_rookie
    }

    // ------------------------------------------------------------------ achievements

    class Achievement(
        val id: String,
        @StringRes val title: Int,
        @StringRes val description: Int,
        val target: Int,
        val xp: Int,
        val progress: (QuestState) -> Int,
    )

    val achievements: List<Achievement> = listOf(
        Achievement("first_song", R.string.ach_first_song, R.string.ach_first_song_desc, 1, 25) { it.totalPlays },
        Achievement("plays_100", R.string.ach_plays_100, R.string.ach_plays_100_desc, 100, 100) { it.totalPlays },
        Achievement("plays_1000", R.string.ach_plays_1000, R.string.ach_plays_1000_desc, 1000, 400) { it.totalPlays },
        Achievement("night_owl", R.string.ach_night_owl, R.string.ach_night_owl_desc, 50, 150) { it.nightPlays },
        Achievement("early_bird", R.string.ach_early_bird, R.string.ach_early_bird_desc, 25, 100) { it.earlyPlays },
        Achievement("completionist", R.string.ach_completionist, R.string.ach_completionist_desc, 1, 150) { it.albumsCompleted },
        Achievement("album_hunter", R.string.ach_album_hunter, R.string.ach_album_hunter_desc, 10, 300) { it.albumsCompleted },
        Achievement("explorer", R.string.ach_explorer, R.string.ach_explorer_desc, 10, 150) { it.bestWeekNewArtists },
        Achievement("loyal_fan", R.string.ach_loyal_fan, R.string.ach_loyal_fan_desc, 100, 200) {
            it.artistPlays.values.maxOrNull() ?: 0
        },
        Achievement("marathon", R.string.ach_marathon, R.string.ach_marathon_desc, 180, 200) {
            (it.bestDayMs / 60_000).toInt()
        },
        Achievement("lucky_draw", R.string.ach_lucky_draw, R.string.ach_lucky_draw_desc, 50, 100) { it.smartShufflePlays },
        Achievement("quest_taker", R.string.ach_quest_taker, R.string.ach_quest_taker_desc, 10, 150) { it.questsCompleted },
        Achievement("on_a_roll", R.string.ach_on_a_roll, R.string.ach_on_a_roll_desc, 7, 200) {
            max(it.bestStreak, it.streak)
        },
    )

    // ------------------------------------------------------------------ daily quests

    class QuestType(
        val id: String,
        /** Plural-free format string with one %d for the target. */
        @StringRes val title: Int,
        val targets: List<Int>,
        val progress: (QuestState) -> Int,
    )

    val questTypes: List<QuestType> = listOf(
        QuestType("plays", R.string.quest_daily_plays, listOf(10, 15, 20)) { it.playsToday },
        QuestType("minutes", R.string.quest_daily_minutes, listOf(30, 45, 60)) { (it.msToday / 60_000).toInt() },
        QuestType("artists", R.string.quest_daily_artists, listOf(3, 5, 8)) { it.artistsToday.size },
        QuestType("album", R.string.quest_daily_album, listOf(1)) { it.albumsToday },
        QuestType("gems", R.string.quest_daily_gems, listOf(2, 3)) { it.gemsToday },
        QuestType("smart", R.string.quest_daily_smart, listOf(5, 10)) { it.smartToday },
    )

    fun questType(id: String): QuestType? = questTypes.firstOrNull { it.id == id }

    /** Same three quests all day (seeded by the date), different ones tomorrow. */
    fun questsForDay(dayKey: String): List<Pair<QuestType, Int>> {
        val random = Random(dayKey.hashCode())
        return questTypes.shuffled(random).take(DAILY_QUEST_COUNT).map { it to it.targets.random(random) }
    }
}
