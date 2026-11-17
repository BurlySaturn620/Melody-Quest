# Melody Quest — fork notes (v0.0.2)

Fork of Retro Music Player 7.0.3 (GPLv3). This pass only changes the look.

## What changed

**Brand**
- App name "Melody Quest", new launcher icon (an eighth note carrying a quest pennant), new splash.
- "Retro Music" titles on Home / Library / Permission screens now say "Melody **Quest**".
- Default accent: ember `#E8602C` (was deep purple).

**Theme** (`res/values/styles_melody.xml`)
- One overlay applied on top of every base theme (`AbsThemeActivity` → `getMelodyThemeOverlay()`):
  ink surfaces at night, warm "paper" surfaces by day, pure black for AMOLED. Material You keeps its
  dynamic colours and only gets the shape/component overlay.
- Flat tonal cards (no shadows), softer corner scale (6/10/18/24/32dp).
- Font: Outfit (OFL, `licenses/Outfit-OFL.txt`), **on by default**. Replaces Manrope (680 KB → 110 KB).
  The debug build's Google Sans override was removed.

**Shell**
- Mini player is a floating accent-tinted card: title + artist on two lines, thin progress line at the
  bottom, rounded play chip (was a flat row with a circular progress ring).
- Bottom bar and sheet sit flush with the page surface; rounded-square nav indicator, labels on the
  selected tab only, 72dp tall.
- Toolbar titles are left-aligned and larger.

**Screens**
- Home: big greeting with a rounded-square avatar on the right; quick actions are one row of four
  icon-over-label tiles; Suggestions is a "New music mix" bar + clean 4×2 cover grid; section headers
  restyled with a chip arrow.
- Song rows: larger rounded art, bold titles, more breathing room.
- Default ("Normal") now playing: left-aligned title block, chunky scrubber with times underneath,
  shuffle · prev · squircle play · next · repeat, larger rounded cover.

## Styles: Melody (default) and Neon
- Settings → Look and feel → **Style**: Melody (warm classic, default) or Neon (gamer). Switching picks
  that style's accent (cyan / ember); you can still choose any accent afterwards.
- Neon palette: surfaces #0A0B12 ladder, accent #00E5FF, secondary #FF3DCB, tertiary #B4FF39; light and
  pure-black variants too. Overlays `ThemeOverlay.Melody.Neon*` in `values/styles_melody.xml`.
- Display font Tektur (`font/neon_display.xml`) on greeting, section titles, level badges and quest names,
  set per style via `?attr/melodyDisplayFont` (Melody uses Outfit).
- Glow (Android 9+, tinted elevation shadow) on the play button and level badges; 1dp accent outlines
  on the mini player and quest cards. Helpers in `extensions/NeonExtensions.kt`. Only when Material You is off.
- Defaults for new installs: Melody style, ember accent, **dark** theme, Material You **off** (still
  selectable on Android 12+). Debug builds use the same name and version as release (no "Debug" tag);
  they still install separately as `code.melodyquest.musicplayer.debug`.

## Smart Shuffle
- The shuffle button now cycles **Off → Shuffle → Smart Shuffle → Off**, the same order as Spotify.
  Smart mode shows a shuffle icon with a sparkle, and a toast the first time you switch it on.
- Smart order: shuffled, but it avoids playing the same artist twice in a row.
- Suggestions: for queues of 5+ songs it adds about 1 extra song after every 3, picked from the rest of your
  library by shared artist / album artist / album / composer / era, boosted by your play counts and
  nudged away from songs you heard recently. Max 60, max ~¼ from any one artist. Runs off the main thread, fully offline.
- Suggested songs show a sparkle and "Suggested · Artist" in the queue. Swipe one away to remove it.
- Turning Smart Shuffle off removes the suggestions (except the one playing). Shuffle buttons around the
  app stay in Smart mode if it's on. Suggestions survive an app restart.
- Code: `helper/SmartShuffle.kt` (logic), `MusicService` (queue wiring), `extensions/ShuffleExtensions.kt` (button state).

## Fill in missing info (online)
- Settings → Other → **Online info**. Off by default; "Only on Wi-Fi" and "Download lyrics" on by default.
- While the app is open and you're online, it walks the library in the background and looks up whatever
  is missing: year (first release), artist (only if the file says unknown), album artist, composer
  (MusicBrainz), and lyrics, synced when available (LRCLIB). It only fills **empty** fields and **never
  writes to your music files**. The results live in the app's database and lyrics folder.
- Picky matching: title + artist + length must agree (±7s, or ±3s when the file has no artist). A
  wrong composer is worse than an empty one, so unsure matches count as "not found".
- MusicBrainz allows ~1 request/second, so it's slow on purpose: about 1–2 s per song. Not-found songs are
  retried after 30 days, errors on the next pass, and a song is re-checked when its file changes.
  Rescans at most every 30 minutes.
- Settings shows progress ("Checking songs: 120 of 900") and has a "Clear downloaded info" option.
- **Per song (tag editor):** the cloud button "Look up online" does a fresh lookup and lists everything it
  found (artist, album artist, composer, lyricist, genre, year, track, disc, lyrics) next to what the
  field holds now. Empty fields come pre-ticked; fields that already have something are offered but
  not ticked. Apply, then Save to write them into the file. Works even when the background feature is off.
- Code: `onlinemetadata/` (API, matching, in-app store, background manager), `db/OnlineMetadata*`
  (Room table, DB v25 + migration), hook in `RealSongRepository`, lyrics fallbacks in `LyricUtil`,
  `MusicUtil`, `LyricsFragment`.
- MusicBrainz contact (User-Agent) is set in `OnlineMetadataApi.CONTACT`.

## Quest mode (version 0.0.1)
- XP: 10 per song that counts as played (same rule as the play counter: more than half heard),
  40 per daily quest, 60 for clearing all three, 25–400 per achievement.
  Level curve: level L needs 50·(L−1)·L XP total. Ranks: Rookie 1, Wanderer 5, Adventurer 10, Bard 20, Virtuoso 30, Legend 50.
- Daily quests: 3 per day, picked from plays / minutes / artists / album / barely-played songs / Smart Shuffle,
  same for the whole day (seeded by the date).
- 13 achievements (see `quest/QuestCatalog.kt`). "Explorer" counts artists new to you; it's seeded from existing play history.
- Never shows a toast while the app is in the background. News waits on the Home card / Quest Log.
- State is one JSON blob in the `melody_quest_game` prefs file (class in `model/quest` so R8 keeps field names).
- Code: `quest/QuestEngine.kt` (rules), `quest/QuestCatalog.kt` (content), `quest/QuestActivity.kt` (Quest Log),
  hook in `MusicService` (META_CHANGED), Home card `layout/melody_quest_card.xml`.
- Settings → Other → Quest: on/off and reset.

## App ID
- App ID is `code.melodyquest.musicplayer` (debug builds: `code.melodyquest.musicplayer.debug`), so it
  installs next to Retro Music Player. Broadcast actions use the same prefix.
- The Kotlin package / Gradle `namespace` is still `code.name.monkey.retromusic`. Users never see it;
  renaming it would touch nearly every source file.

## Not done yet / follow-ups
- The other 20+ now-playing themes only pick up the global theme changes.
- Built without an Android SDK here; run `./gradlew lint app:assemble` before shipping.
