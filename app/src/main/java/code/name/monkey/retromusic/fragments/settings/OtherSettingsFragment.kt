/*
 * Copyright (c) 2020 Hemanth Savarla.
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 */
package code.name.monkey.retromusic.fragments.settings

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import code.name.monkey.appthemehelper.common.prefs.supportv7.ATEListPreference
import code.name.monkey.retromusic.LANGUAGE_NAME
import code.name.monkey.retromusic.LAST_ADDED_CUTOFF
import code.name.monkey.retromusic.ONLINE_METADATA_STATUS
import code.name.monkey.retromusic.QUEST_RESET
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.extensions.showToast
import code.name.monkey.retromusic.onlinemetadata.OnlineMetadataManager
import code.name.monkey.retromusic.quest.QuestEngine
import code.name.monkey.retromusic.extensions.installLanguageAndRecreate
import code.name.monkey.retromusic.fragments.LibraryViewModel
import code.name.monkey.retromusic.fragments.ReloadType.HomeSections
import code.name.monkey.retromusic.util.PreferenceUtil
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.activityViewModel

/**
 * @author Hemanth S (h4h13).
 */

class OtherSettingsFragment : AbsSettingsFragment() {
    private val libraryViewModel by activityViewModel<LibraryViewModel>()

    override fun invalidateSettings() {
        val languagePreference: ATEListPreference? = findPreference(LANGUAGE_NAME)
        languagePreference?.setOnPreferenceChangeListener { _, _ ->
            restartActivity()
            return@setOnPreferenceChangeListener true
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        PreferenceUtil.languageCode =
            AppCompatDelegate.getApplicationLocales().toLanguageTags().ifEmpty { "auto" }
        addPreferencesFromResource(R.xml.pref_advanced)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val preference: Preference? = findPreference(LAST_ADDED_CUTOFF)
        preference?.setOnPreferenceChangeListener { lastAdded, newValue ->
            setSummary(lastAdded, newValue)
            libraryViewModel.forceReload(HomeSections)
            true
        }
        val languagePreference: Preference? = findPreference(LANGUAGE_NAME)
        languagePreference?.setOnPreferenceChangeListener { prefs, newValue ->
            setSummary(prefs, newValue)
            if (newValue as? String == "auto") {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
            } else {
                // Install the languages from Play Store first and then set the application locale
                requireActivity().installLanguageAndRecreate(newValue.toString()) {
                    AppCompatDelegate.setApplicationLocales(
                        LocaleListCompat.forLanguageTags(
                            newValue as? String
                        )
                    )
                }
            }
            true
        }
        setUpOnlineMetadata()
        findPreference<Preference>(QUEST_RESET)?.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.pref_title_quest_reset)
                .setMessage(R.string.quest_reset_confirm)
                .setPositiveButton(R.string.reset_action) { _, _ ->
                    QuestEngine.reset()
                    showToast(R.string.quest_reset_done)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
    }

    /** Melody Quest: live status line + "clear downloaded info" for the Online info section. */
    private fun setUpOnlineMetadata() {
        val status: Preference = findPreference(ONLINE_METADATA_STATUS) ?: return
        status.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.online_metadata_clear_title)
                .setMessage(R.string.online_metadata_clear_message)
                .setPositiveButton(R.string.clear_action) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        OnlineMetadataManager.clearAll()
                        showToast(R.string.online_metadata_cleared)
                        refreshOnlineMetadataStatus(status, OnlineMetadataManager.progress.value)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                OnlineMetadataManager.progress.collect { refreshOnlineMetadataStatus(status, it) }
            }
        }
    }

    private suspend fun refreshOnlineMetadataStatus(
        status: Preference,
        progress: OnlineMetadataManager.Progress
    ) {
        status.summary = if (progress.running) {
            getString(R.string.online_metadata_status_running, progress.done, progress.total)
        } else {
            val (found, checked) = OnlineMetadataManager.counts()
            if (checked == 0) getString(R.string.online_metadata_status_none)
            else getString(R.string.online_metadata_status, found, checked)
        }
    }
}
