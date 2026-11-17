package code.name.monkey.retromusic.quest

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.MenuItem
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import code.name.monkey.appthemehelper.util.ToolbarContentTintHelper
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.activities.base.AbsThemeActivity
import code.name.monkey.retromusic.databinding.ActivityQuestBinding
import code.name.monkey.retromusic.databinding.ItemQuestRowBinding
import code.name.monkey.retromusic.extensions.accentColor
import code.name.monkey.retromusic.extensions.drawAboveSystemBars
import code.name.monkey.retromusic.extensions.isColorLight
import code.name.monkey.retromusic.extensions.neonGlow
import code.name.monkey.retromusic.extensions.neonOutline
import code.name.monkey.retromusic.extensions.surfaceColor
import code.name.monkey.retromusic.model.quest.QuestState
import code.name.monkey.retromusic.util.PreferenceUtil
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/** Melody Quest "Quest Log": character sheet, today's quests and all achievements. */
class QuestActivity : AbsThemeActivity() {

    private lateinit var binding: ActivityQuestBinding

    /** "While you were away" news, captured once when the screen opens. */
    private var news: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQuestBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        ToolbarContentTintHelper.colorBackButton(binding.toolbar)
        binding.container.drawAboveSystemBars()

        news = QuestEngine.stateFlow.value.unseenNews.toList()
        QuestEngine.markNewsSeen()
        QuestEngine.refresh()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                QuestEngine.stateFlow.collect { render(it) }
            }
        }
    }

    private fun render(state: QuestState) {
        val accent = accentColor()
        val surface = surfaceColor()
        val tonal = ColorUtils.blendARGB(accent, surface, if (surface.isColorLight) 0.90f else 0.86f)
        val onAccent = if (accent.isColorLight) Color.BLACK else Color.WHITE

        // Character sheet
        val level = QuestCatalog.levelFor(state.xp)
        val levelStart = QuestCatalog.xpForLevel(level)
        val levelEnd = QuestCatalog.xpForLevel(level + 1)
        with(binding) {
            if (!PreferenceUtil.materialYou) headerCard.setCardBackgroundColor(tonal)
            headerCard.neonOutline(accent)
            levelBadge.text = level.toString()
            levelBadge.backgroundTintList = ColorStateList.valueOf(accent)
            levelBadge.setTextColor(onAccent)
            levelBadge.neonGlow(accent, 16f)
            rankText.text = getString(
                R.string.quest_level_rank, level, getString(QuestCatalog.rankFor(level))
            )
            totalXpText.text = getString(R.string.quest_total_xp, state.xp)
            xpBar.max = levelEnd - levelStart
            xpBar.setProgressCompat(state.xp - levelStart, false)
            xpBar.setIndicatorColor(accent)
            xpBar.trackColor = ColorUtils.setAlphaComponent(accent, 0x33)
            xpText.text = getString(
                R.string.quest_xp_progress, state.xp - levelStart, levelEnd - levelStart
            )
            val minutes = state.totalListenedMs / 60_000
            statsText.text = getString(
                R.string.quest_stats,
                state.totalPlays,
                (minutes / 60).toInt(),
                (minutes % 60).toInt(),
                state.unlocked.size
            )
            val streak = QuestEngine.displayStreak(state)
            streakText.isVisible = streak > 0
            streakText.text = getString(R.string.quest_streak, streak)
            disabledText.isVisible = !PreferenceUtil.isQuestMode

            newsTitle.isVisible = news.isNotEmpty()
            newsText.isVisible = news.isNotEmpty()
            newsText.text = news.joinToString("\n")

            dailySubtitle.text = getString(
                R.string.quest_daily_subtitle,
                QuestCatalog.XP_PER_DAILY_QUEST,
                QuestCatalog.XP_DAILY_CLEAR_BONUS
            )
        }

        // Daily quests
        binding.dailyContainer.removeAllViews()
        for (quest in state.dailyQuests) {
            val type = QuestCatalog.questType(quest.type) ?: continue
            val progress = if (quest.done) quest.target else type.progress(state).coerceAtMost(quest.target)
            addRow(
                container = binding.dailyContainer,
                icon = if (quest.done) R.drawable.ic_check_circle else R.drawable.ic_flag,
                title = getString(type.title, quest.target),
                description = null,
                progress = progress,
                target = quest.target,
                reward = getString(R.string.quest_reward_xp, QuestCatalog.XP_PER_DAILY_QUEST),
                active = !quest.done,
                accent = accent,
                tonal = tonal,
            )
        }

        // Achievements: unlocked first (newest first), then locked by how close they are
        binding.achievementsTitle.text = getString(
            R.string.quest_achievements_title, state.unlocked.size, QuestCatalog.achievements.size
        )
        binding.achievementsContainer.removeAllViews()
        val sorted = QuestCatalog.achievements.sortedWith(
            compareByDescending<QuestCatalog.Achievement> { it.id in state.unlocked }
                .thenByDescending { state.unlocked[it.id] ?: 0L }
                .thenByDescending { it.progress(state).toFloat() / it.target }
        )
        for (achievement in sorted) {
            val unlockedAt = state.unlocked[achievement.id]
            val progress = achievement.progress(state).coerceAtMost(achievement.target)
            addRow(
                container = binding.achievementsContainer,
                icon = if (unlockedAt != null) R.drawable.ic_trophy else R.drawable.ic_lock_quest,
                title = getString(achievement.title),
                description = if (unlockedAt != null) {
                    getString(achievement.description) + " · " + getString(
                        R.string.quest_unlocked_on,
                        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(unlockedAt))
                    )
                } else getString(achievement.description),
                progress = if (unlockedAt != null) achievement.target else progress,
                target = achievement.target,
                reward = getString(R.string.quest_reward_xp, achievement.xp),
                active = unlockedAt != null,
                accent = accent,
                tonal = tonal,
                dimWhenInactive = true,
            )
        }
    }

    private fun addRow(
        container: LinearLayout,
        @DrawableRes icon: Int,
        title: String,
        description: String?,
        progress: Int,
        target: Int,
        reward: String,
        active: Boolean,
        accent: Int,
        tonal: Int,
        dimWhenInactive: Boolean = false,
    ) {
        val row = ItemQuestRowBinding.inflate(layoutInflater, container, false)
        if (!PreferenceUtil.materialYou) row.root.setCardBackgroundColor(tonal)
        row.icon.setImageResource(icon)
        row.icon.backgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 0x2E))
        row.icon.imageTintList = ColorStateList.valueOf(accent)
        row.title.text = title
        row.description.isVisible = description != null
        row.description.text = description
        row.progress.max = target
        row.progress.setProgressCompat(progress, false)
        row.progress.setIndicatorColor(accent)
        row.progress.trackColor = ColorUtils.setAlphaComponent(accent, 0x33)
        // Single-step goals don't need a bar.
        row.progress.isVisible = target > 1 && progress < target
        row.reward.text = if (target > 1 && progress < target) {
            reward + "\n" + getString(R.string.quest_progress, progress, target)
        } else reward
        row.reward.setTextColor(accent)
        if (active) row.root.neonOutline(accent, 0x66)
        if (dimWhenInactive && !active) row.root.alpha = 0.6f
        container.addView(row.root)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
