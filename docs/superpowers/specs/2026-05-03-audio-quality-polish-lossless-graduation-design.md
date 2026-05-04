# v0.9.8 — Audio Quality Polish + Lossless Graduation Design

**Date:** 2026-05-03
**Status:** Design
**Branch (proposed):** `feat/v0.9.8-audio-polish`

## Problem

Lossless downloads (squid.wtf-backed Qobuz proxy → FLAC) shipped in v0.9.0-beta1 and have been hardened across v0.9.1–v0.9.7. The feature works in production. But the Settings UX still treats it as a marginal opt-in:

- The toggle is labelled `"Lossless downloads (experimental)"`, framing the feature as risky.
- Off-state subtitle is `"Off — uses YouTube/yt-dlp like before"` — purely descriptive, no promotional pull.
- The captcha entry point is a button labelled `"Verify in browser"` with zero context. New users have no idea what they're verifying or why.
- First-launch defaults are `losslessEnabled = false` + `qualityTier = BEST` (which is the second-tier, not actually best). A fresh install gives no indication that lossless is even an option.
- Existing users who upgraded with `losslessEnabled = false` saved have no nudge to discover or try the feature.

Separately:

- The Last.fm "Scrobble your plays" disconnected card occupies ~140 dp with a long body description users typically ignore. Density reduction is asked.
- The Library tab opens to `Playlists` by default. Users heavily skew toward the Tracks tab (the place to discover/play their own collection), and lossless graduation makes a `FLAC` filter default valuable.

## Goals

- Lossless is presented as the headline download mode, not a beta opt-in.
- The captcha-setup path is self-explanatory: a user who has never seen this UI before can complete it without reading docs.
- Fresh installs default to the most aspirational state: `losslessEnabled = true` + `qualityTier = MAX`. Users who have already explicitly chosen something keep their choice.
- Existing v0.9.7 users who haven't enabled lossless get a single, dismissible discoverability nudge on Home — the same pattern Stash already uses for the Last.fm connect prompt.
- The Last.fm Disconnected card matches the visual density of the rest of the Audio Quality section (single-row, ~48 dp).
- The Library tab opens to the user's actual collection (Tracks tab, Recently Added sort) and surfaces FLAC tracks first when present.

## Non-goals

- No changes to the lossless download pipeline, captcha solver, FLAC codec list, `MusicRepository` storage code, or `FileOrganizer`.
- No "What's new" modal on first v0.9.8 launch. DataStore's absent-key-returns-default semantics handle the migration.
- No persistence of the user's last Library filter across cold starts. The smart-default re-evaluates each launch — this is the simplest correct behavior given current data flows.
- No `NavArg` from the Home banner to pre-expand the captcha block. Banner taps land on Settings; the new Audio Quality defaults already have the captcha block expanded.
- No analytics/telemetry instrumentation.
- No copy changes to the Connected / AwaitingAuth / Error / NotConfigured Last.fm states. Disconnected only.
- No FLAC-specific UI on the Connected Last.fm card.

## Design

### 1. Audio Quality section — copy + defaults + captcha instructions

**Files touched:**
- `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsScreen.kt` (lines ~430–573)
- `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsUiState.kt` (initial values for `audioQuality` + `losslessEnabled`)
- `feature/settings/src/main/kotlin/com/stash/feature/settings/components/SquidWtfCaptchaScreen.kt` (status text in the WebView screen)
- `core/data/src/main/kotlin/com/stash/core/data/prefs/QualityPreference.kt` (interface KDoc — no behavior change)
- `data/download/src/main/kotlin/com/stash/data/download/prefs/QualityPreferencesManager.kt` (fallback default `BEST` → `MAX`)
- `data/download/src/main/kotlin/com/stash/data/download/lossless/LosslessSourcePreferences.kt` (fallback default for `enabled`: `false` → `true`; new `bannerDismissed` Boolean key — see §3)

**Visible copy changes:**

| Where | Before | After |
|---|---|---|
| Toggle title (`SettingsScreen.kt:449`) | `Lossless downloads (experimental)` | `Lossless downloads` |
| Toggle subtitle when OFF (`:458`) | `Off — uses YouTube/yt-dlp like before` | `Studio-quality FLAC via Qobuz. Files ~10× larger than MP3.` |
| Toggle subtitle when ON (`:456`) | `Try Qobuz proxy first; FLAC ~10× larger` | `On — studio-quality FLAC via Qobuz. ~10× larger files.` |
| Captcha button (`:491`) | `Verify in browser` | `Connect to squid.wtf` |
| New body text under the captcha button row | (none today) | `Search any song → tap Download → solve the captcha. Stash captures the cookie automatically.` (small body, on its own row) |
| WebView header `statusText` (`SquidWtfCaptchaScreen.kt:63`) | `Click Download on any track and solve the captcha. The cookie will save automatically.` | `To verify:`<br>`1. Search for any song`<br>`2. Tap Download on a track`<br>`3. Solve the captcha popup`<br>`Cookie saves automatically once verified.` |

The "captured" status text after success (`SquidWtfCaptchaScreen.kt:64` "Got it — saving and closing.") stays as-is.

**Default-state changes (data-class + DataStore-fallback only — no migration code):**

- `QualityPreferencesManager.qualityTier` Flow's fallback (today line 39): `QualityTier.BEST` → `QualityTier.MAX`.
- `LosslessSourcePreferences.enabled` Flow's fallback: `false` → `true`.
- `SettingsUiState.audioQuality` initial value (line 27): `QualityTier.BEST` → `QualityTier.MAX`.
- `SettingsUiState.losslessEnabled` initial value (line 46): `false` → `true`.

**Migration semantics (intentional, no code):**

DataStore returns the data-class default when a key is absent. Three cohorts:

1. **Fresh v0.9.8 installs** — no DataStore entries → see new defaults (`MAX` + ON). Captcha block expanded by default; the new instructional copy is what the user sees first.
2. **v0.9.7 users who explicitly toggled lossless off** — DataStore has `enabled = false` → preserved on upgrade. Banner shows on Home (see §3) to surface the feature again.
3. **v0.9.7 users who never opened the Audio Quality card** — no DataStore entry → silently flip to ON + MAX. Functionally identical to v0.9.7 behavior because: captcha is unverified → silent MP3 fallback (today's `LosslessRegistry: source squid_qobuz threw on resolve` path); MAX vs BEST differs only in yt-dlp args (both 256 kbps). No user-visible regression.

**Captcha block visibility:** unchanged. The existing `AnimatedVisibility(visible = uiState.losslessEnabled)` will now be expanded by default for fresh installs and untouched-toggle upgraders, so the new instructional copy under the button is what users see first.

### 2. Lossless graduation — what "out of experimental" means

There is no feature flag, telemetry tag, or build-config gating the lossless feature today — the only graduation surface is the parenthetical `(experimental)` in the toggle title. Removing the parenthetical (§1) plus shipping the new defaults (§1) plus shipping the discoverability banner (§3) constitutes graduation. No additional changes are required.

### 3. Home discoverability banner

Mirrors the existing Last.fm prompt at `HomeViewModel:148–164` exactly. Same combine, same `*PromptState?` (null = hidden), same DataStore-backed dismissal, same visual treatment.

**Files touched:**
- `data/download/.../lossless/LosslessSourcePreferences.kt` — add `bannerDismissed: Flow<Boolean>` (default `false`) + `setBannerDismissed(value: Boolean)` setter, alongside existing `enabled` and `captchaCookieValue` keys. No new DataStore file.
- `feature/home/.../HomeUiState.kt` — add `losslessPrompt: LosslessPromptState? = null` field. `LosslessPromptState` is a `data object` (no fields — its mere presence in the UI state signals "show the banner"). Defined alongside the existing `LastFmPromptState` in the same file.
- `feature/home/.../HomeViewModel.kt` — add `losslessPromptFlow`, fold it into the existing `authStateFlow` combine (the established 3-input bundling point — its KDoc reads *"Bundled so the top-level combine stays at 5 inputs"*; the top-level combine is already at the 5-input non-vararg ceiling and cannot grow). Extend `AuthInfo` with a `losslessPrompt: LosslessPromptState?` field. Add `dismissLosslessBanner()` method. Add `private val losslessPrefs: LosslessSourcePreferences` constructor param.
- `feature/home/.../HomeScreen.kt` — render the banner at the top of the scrollable Column, directly under the sync card, when `uiState.losslessPrompt != null`. Tap → `onSetUpLossless()` callback. `×` icon → `viewModel::dismissLosslessBanner`.
- `app/.../navigation/StashNavHost.kt` — wire `onSetUpLossless = { navController.navigate(SettingsRoute) }`.

**Trigger logic:**
```kotlin
private val losslessPromptFlow = combine(
    losslessPrefs.enabled,
    losslessPrefs.bannerDismissed,
) { enabled, dismissed ->
    if (!enabled && !dismissed) LosslessPromptState else null
}
```

**Folding into `authStateFlow`** (the existing 3-input bundle becomes 4-input):
```kotlin
private val authStateFlow = combine(
    tokenManager.spotifyAuthState,
    tokenManager.youTubeAuthState,
    lastFmPromptFlow,
    losslessPromptFlow,
) { spotify, youtube, lastFmPrompt, losslessPrompt ->
    AuthInfo(
        spotifyConnected = spotify is AuthState.Connected,
        youTubeConnected = youtube is AuthState.Connected,
        lastFmPrompt = lastFmPrompt,
        losslessPrompt = losslessPrompt,
    )
}
```

`AuthInfo` gains a `losslessPrompt: LosslessPromptState?` field. The top-level combine stays at exactly 5 inputs.

This gives the right behavior across all three cohorts:
- Fresh install → `enabled = true` (new default) → banner hidden.
- v0.9.7 user who toggled off → `enabled = false` saved → banner shows.
- v0.9.7 user who never toggled → no key → new fallback `true` → banner hidden (already opted in via the new default).

**Banner UI** (one line + dismiss icon, GlassCard-wrapped):
- Title: `Try lossless audio`
- Body: `Studio-quality FLAC downloads via Qobuz. Tap to set up.`
- Tap action: `onSetUpLossless()` → navigates to Settings. The new defaults mean the Audio Quality card lands with the captcha block expanded and instructions visible.
- Dismiss icon (`Icons.Filled.Close`): tap → `dismissLosslessBanner()` → DataStore write → banner Flow re-emits null → banner disappears.

**Banner visual style:** matches the existing Last.fm prompt rendering. Same Compose composable shape (a row inside a `GlassCard` with title + body + trailing dismiss icon). Reuse the Last.fm prompt's existing styling code via shared component if cheap; duplicate inline if not. Out of scope to extract a generic `HomePromptCard` component — the brief is to ship discoverability, not refactor Home.

### 4. Last.fm card — Disconnected density

**File touched:** `feature/settings/.../SettingsScreen.kt:998–1020` (Disconnected branch only).

**Replace:** title + 4dp spacer + 3-line body + 12dp spacer + full-width OutlinedButton.
**With:**
```kotlin
Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
) {
    Text(
        text = "Scrobble your plays",
        modifier = Modifier.weight(1f),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    OutlinedButton(
        onClick = onConnect,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.primary,
        ),
    ) {
        Text("Connect")
    }
}
```

Result: ~48 dp tall. Matches the lossless toggle row's density two cards above.

The 3-line body description ("Connect Last.fm and every song you finish in Stash lands in your Last.fm profile…") is removed entirely. Other states (`NotConfigured`, `AwaitingAuth`, `Connected`, `Error`) are not modified.

### 5. Library smart-default

**Files touched:**
- `feature/library/.../LibraryUiState.kt` (line 14) — change `activeTab` data-class default from `LibraryTab.PLAYLISTS` to `LibraryTab.TRACKS`. This is the *initial* `LibraryUiState` value emitted before flows resolve.
- `feature/library/.../LibraryViewModel.kt`:
  - In the `private data class ControlState` (line ~421) change the `activeTab` default from `LibraryTab.PLAYLISTS` to `LibraryTab.TRACKS`. This is the source-of-truth default that drives the user-controls flow. The `LibraryUiState.activeTab` change above is a matching cosmetic default — `ControlState` is what the rest of the ViewModel reads.
  - Keep `sortOrder = SortOrder.RECENT` and `sourceFilter = SourceFilter.ALL` in `ControlState`. The FLAC override is applied after init by the smart-default logic below, not as a static default, so the empty-state never flickers.
  - Add an init-time one-shot snapshot read that flips `sourceFilter` to `FLAC` when ≥1 lossless track is present.

**Smart-default init logic** (lives inside the `LibraryViewModel.init` block; uses the existing `_controls: MutableStateFlow<ControlState>` mutation pattern at line 217 — `_controls.update { it.copy(sourceFilter = …) }` — *not* a separate `MutableStateFlow<SourceFilter>`):

```kotlin
init {
    viewModelScope.launch {
        val firstSnapshot = musicRepository.getAllTracks().first()
        val hasLossless = firstSnapshot.any {
            it.fileFormat?.lowercase() in LOSSLESS_CODECS
        }
        if (hasLossless && _controls.value.sourceFilter == SourceFilter.ALL) {
            _controls.update { it.copy(sourceFilter = SourceFilter.FLAC) }
        }
    }
}
```

Reuses the existing `LOSSLESS_CODECS` set defined in `LibraryViewModel` (line 116 region — same set used by the manual FLAC filter chip).

The `_controls.value.sourceFilter == SourceFilter.ALL` guard means: don't fight the user. If anything else (e.g. future `SavedStateHandle`-based restoration) has already set a non-`ALL` filter before the snapshot resolves, we leave it alone.

**Behavior:**
- Cold start, library has any FLAC tracks → Library opens to TRACKS / RECENT / FLAC.
- Cold start, no FLAC tracks → Library opens to TRACKS / RECENT / ALL.
- Mid-session filter changes are honored — no fighting the user.
- Next cold start re-evaluates from scratch.

## Risks

| Risk | Mitigation |
|---|---|
| Existing users with no DataStore entry get silently flipped to lossless ON + MAX after upgrade | No user-visible regression — captcha unverified means silent MP3 fallback (current production behavior); MAX vs BEST differs in yt-dlp args only, both 256 kbps. Banner only fires for users with an explicit `enabled = false` saved. |
| Banner shows on Home for users who deliberately disabled lossless and do not want a nudge | One-tap `×` dismissal writes to DataStore; banner never returns. Mirrors the pattern users already accept for the Last.fm prompt. |
| Library smart-default causes confusion when FLAC count is 0 right after a fresh install (before any downloads complete) | The empty-result safeguard `hasLossless` defaults to `false` → `sourceFilter` stays at `ALL`. The user lands on Tracks / Recently Added / All — same as v0.9.7's Tracks tab today. |
| Library's `getAllTracks().first()` is a one-shot snapshot; if the first emission is empty (cold-start before Room loads), we'd never apply FLAC default | `Room` Flows emit synchronously on collection from the latest snapshot; `first()` resolves to the actual current state. If empty (no downloads yet) the smart-default correctly chooses `ALL`. Acceptable. |
| Removing the 3-line Last.fm body description loses context for users who don't recognise "Last.fm" | Acceptable per design discussion. The Connect button + the parent `Last.fm` SectionHeader are sufficient. Stash's audience overlaps heavily with people who know Last.fm. |
| Captcha instructions overlap with the existing in-card body — visual crowding | The new instruction text sits in its own `Row` below the button row, with its own subtle color (`onSurfaceVariant`) and a `bodySmall` style. Same visual weight as the existing toggle subtitles. |
| The lossless banner is in the way for users who are about to dismiss the Last.fm banner — two prompts above the playlists grid feels noisy | Acceptable. Both are dismissible. v0.9.7 users who never enabled either feature see one banner each, dismiss them quickly, and never see them again. The pattern is established. |

## Out of scope

- "What's new" / changelog modal on first v0.9.8 launch.
- Pre-expanding the captcha block via a `NavArg` when banner-tapped.
- Persisting the user's last Library filter (`sourceFilter`) across cold starts.
- Refactoring the Home prompt rendering into a generic `HomePromptCard` composable.
- Touching the Library tab's other defaults (sort, search query) beyond what's documented in §5.
- Adding analytics or telemetry around the new banner / new defaults / lossless adoption.
- Any change to the download pipeline, FLAC codec list, captcha solver, or storage code.

## Ship as

v0.9.8. Single coherent release: "lossless graduates from experimental." The Last.fm density change and Library smart-default ride along because they're tightly scoped Settings/Library polish that fits the narrative without blocking on heavier work (smart bug report, custom playlist covers, etc., which each get their own brainstorm).

Bumps `versionCode` 44 → 45 and `versionName` `0.9.7` → `0.9.8` in `app/build.gradle.kts`.
