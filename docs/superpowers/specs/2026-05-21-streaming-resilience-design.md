# Streaming Resilience Design

**Date:** 2026-05-21
**Status:** Draft

## Goal

Make Stash's online-streaming path resilient to upstream backend degradation. Today, when Kennyy returns 502 (as it did during the 2026-05-21 incident), the resolver chain silently falls through to YouTube, and a separate YouTube CDN problem (HTTP 410s) causes ExoPlayer's `skip-next` recovery to silently consume the entire queue at one track per ~3 seconds. The user is left with a paused player, no clear cause, and a queue that's advanced 15 tracks past where they were.

This spec covers three independent-but-related improvements:

1. **Resolver attempt logging** — make silent fallback visible in `logcat`.
2. **Player cascade guard** — bound the `skip-next` recovery so one upstream outage doesn't drain the queue.
3. **Squid cookie auto-refresh** — when Kennyy is unhealthy and the app is active, automatically refresh the ~30-min Squid captcha cookie in the background so the user isn't repeatedly bounced to a WebView.

## Background

Stash routes every play through `StreamSourceRegistry.resolve()`, which tries resolvers in order:

1. `KennyyStreamResolver` — primary Qobuz catalog via `qobuz.kennyy.com.br`. No captcha gate.
2. `QobuzStreamResolver` — same Qobuz catalog via `qobuz.squid.wtf`. Requires a `captcha_verified_at` cookie obtained by solving ALTCHA in a WebView (`SquidWtfCaptchaScreen`). ~30 min sliding window.
3. `YouTubeStreamResolver` — yt-dlp + InnerTube extraction. Lossy fallback, slow (~12s per resolve), hits its own rate-limit / signing-rotation problems periodically.

`KennyyStreamResolver` has **zero `Log.d` calls**, so when Kennyy fails it leaves no trace in the log. During the 2026-05-21 incident, a logcat capture showed 28 `YouTubeStreamResolver` hits and 0 Kennyy events, which read as "those tracks weren't on Qobuz" when in fact every track had been attempted on Kennyy first and silently failed.

`PlayerRepositoryImpl.onPlayerError` (PlayerRepositoryImpl.kt:1031) handles `ERROR_CODE_IO_BAD_HTTP_STATUS` (code 2004) by calling `skipToNextMediaItem()`. With no consecutive-error bound, one bad URL kicks the queue forward, the next URL fails the same way, and the cascade burns through tracks in seconds.

## Component 1: Resolver attempt logging

### Scope

`KennyyStreamResolver` and `QobuzStreamResolver` gain `Log.d` calls at three lifecycle points:

- **Attempt:** `resolve attempt id=<trackId> title='<title>'` when `resolve()` is entered.
- **Success:** `resolved id=<trackId> origin=<origin> codec=<codec> expiresInSec=<n>` after a non-null `StreamUrl` is built.
- **Failure:** `failed id=<trackId> reason=<reason>` when returning null. `reason` is one of:
  - `http_<code>` (when an HTTP error bubbles up from the source — needs source layer change)
  - `no_match` (search returned empty)
  - `no_etsp` (URL was missing the expiry parameter)
  - `disabled` (resolver gated off, e.g. Squid with no cookie)
  - `exception_<className>` (defensive — resolvers should not throw)

### Surfacing the reason

`KennyySource.resolveImmediate` and `QobuzSource.resolveImmediate` currently return `T?`. To surface the failure reason, they will be augmented to log the failure cause **at the source layer** before returning null. The resolver itself can then log `failed reason=source_null` and rely on the source-layer log for the specific cause.

This avoids changing the resolver/source return-type contract (which would ripple into tests and the existing registry logic). The trade-off is two log lines per failure instead of one, but each is cheap and the dual surface aids tracing.

### Why not log every `null` from existing source code

Per project convention, `KennyySource` and `QobuzSource` are also used by the *download* pipeline, not just streaming. Adding `Log.d` calls there is fine and useful for both surfaces; we'll add them but at the source layer rather than only in the streaming wrapper.

## Component 2: Player cascade guard

### Scope

`PlayerRepositoryImpl` gains a `consecutiveStreamErrors: Int` counter, scoped to the player listener.

### Behavior

- Counter increments on every `onPlayerError` with `error.errorCode == ERROR_CODE_IO_BAD_HTTP_STATUS` (code 2004) OR `ERROR_CODE_IO_NETWORK_CONNECTION_FAILED` (code 2001).
- If counter < **3**: perform existing `skipToNextMediaItem()` recovery, unchanged.
- If counter ≥ 3: **stop the cascade.**
  - Call `controller.pause()`.
  - Emit a `StreamingHaltedEvent` on a SharedFlow exposed by `PlayerRepositoryImpl`.
  - Do **not** call `skipToNextMediaItem()`.
- Counter resets to 0 on `onPlaybackStateChanged(STATE_READY)` when `isPlaying == true` (a track has actually started playing) OR on any user-initiated transport command (next/previous/seek/play from UI). User input means "I see what's wrong, let me try something else"; rearming the cascade lets normal playback continue.

### User-visible surface

`StreamingHaltedEvent` is collected by the active screen (NowPlaying primarily; whatever surface is foregrounded). The screen shows a Snackbar:

> Streaming is failing — try a downloaded track or check your connection.

Snackbar over notification because:
- The system media notification already reflects the paused state (Media3 propagates `pause()` automatically).
- Snackbars match Stash's existing in-app error patterns and don't compete for tray attention with the existing `CaptchaExpiredNotifier`.
- If the user is backgrounded, the cascade still stops; they just don't see the Snackbar. The system media notification's "paused" state is the signal.

### Threshold rationale

3 is the minimum that distinguishes "one bad URL in an otherwise-healthy queue" (cascade should recover) from "the backend is down" (cascade should stop). Two would over-fire on transient single-track issues; four leaves too long a tail when the backend really is down.

### Error code selection

Only the IO/HTTP error codes trigger the counter. Other `PlaybackException` codes (decoder errors, malformed-input, source-not-found) are individual-track problems that should still skip-next without contributing to the global counter.

## Component 3: Squid cookie auto-refresh

### Scope

Three new components plus a hook into `StreamSourceRegistry`:

- `KennyyHealthMonitor` — `@Singleton`. Tracks a sliding window of Kennyy resolver outcomes; exposes `isHealthy: StateFlow<Boolean>`.
- `SquidCookieAutoRefresher` — process-lifecycle-scoped. Observes `KennyyHealthMonitor.isHealthy` and the existing `LosslessSourcePreferences.captchaCookieValue`; triggers headless re-auth when conditions are met.
- `HeadlessSquidCaptchaSolver` — encapsulates the offscreen `WebView` + `CookieManager` polling logic, reusing the same configuration as `SquidWtfCaptchaScreen.buildWebView`.
- Plumbing in `KennyyStreamResolver` to call `monitor.recordSuccess()` / `monitor.recordFailure()`.

### `KennyyHealthMonitor`

State: a fixed-size ring buffer of the last 5 Kennyy attempt outcomes (`Success` | `Failure`).

```
isHealthy = true  unless  count(Failure in last 5) >= 3
```

Recovery: once `count(Success in last 5) >= 3`, `isHealthy` returns to `true`. (Implicitly, this means after 3 consecutive successes if we were at full-failure.)

State is **transient** — held in-memory, not persisted. Process restart resets to healthy (optimistic, gives Kennyy a fresh chance after each launch).

### `SquidCookieAutoRefresher`

Lifecycle: hooks `ProcessLifecycleOwner.get().lifecycle`. Active when state is `STARTED` (foreground or recently-backgrounded grace period). Cancelled when `STOPPED`.

State machine:

```
Idle
  → triggered when (KennyyHealthMonitor.isHealthy == false)
         AND (existing cookie absent OR cookie age > 25 min)
  → enters Refreshing
Refreshing
  → invokes HeadlessSquidCaptchaSolver.solve() with 30s timeout
  → on success: persists cookie via LosslessSourcePreferences.setCaptchaCookieValue,
       resets failure counter, schedules next check for cookie-age + 25 min
  → on failure: increments failure counter, retries after 60s
  → after 2 consecutive failures: falls back to CaptchaExpiredNotifier (existing
       notification path) and stops the auto-refresh loop until the user manually
       resolves it
Idle (cooldown)
  → waits until cookie-age reaches 25 min, then re-enters trigger check
```

When `KennyyHealthMonitor.isHealthy` flips back to `true`, the refresher transitions to `Idle (paused)` — won't refresh again until Kennyy is unhealthy. The current cookie continues to be used by `SquidWtfCaptchaInterceptor` as normal until it expires naturally; we just don't re-warm it.

### `HeadlessSquidCaptchaSolver`

Single `suspend fun solve(): Result<String>`.

Implementation:
1. Construct a new `WebView` programmatically (no Compose, no AndroidView). Apply the same configuration block as `SquidWtfCaptchaScreen.buildWebView` (cookies on, JS on, DOM storage on).
2. Load `https://qobuz.squid.wtf/`.
3. After page load, inject JS that simulates the "click Download on the first search result" flow that triggers ALTCHA. Exact JS to be determined during implementation by inspecting the site's bundle; documented as part of the task.
4. Poll `CookieManager.getInstance().getCookie(SQUID_WTF_URL)` every 500ms (same cadence as the visible screen).
5. When the `captcha_verified_at` cookie appears, extract and return the value.
6. On 30s timeout, return `Result.failure`.
7. Always destroy the WebView in a `finally` block (`stopLoading`, `loadUrl("about:blank")`, `clearHistory`, `destroy`).

### Hook into KennyyStreamResolver

`KennyyStreamResolver.resolve` gains injected `KennyyHealthMonitor`. After the call to `source.resolveImmediate`:

```kotlin
if (result == null) monitor.recordFailure() else monitor.recordSuccess()
```

This must wrap the `runCatching` boundary — exceptions count as failures too.

### Interaction with existing components

- `SquidWtfCaptchaInterceptor` — no change. Picks up the refreshed cookie via the existing `LosslessSourcePreferences.captchaCookieValue` Flow.
- `CaptchaExpiredNotifier` — no change in normal operation. Still fires when `QobuzApiClient` gets a "Captcha required" 403 (e.g. between auto-refresh attempts, or after 2 auto-refresh failures).
- `SquidWtfCaptchaScreen` — no change. Still available for manual refresh via Settings or the notification.
- `LosslessSourcePreferences.captchaCookieValue` — gains a sibling field `captchaCookieSetAtMs: Flow<Long>` so the refresher can read cookie age without parsing the cookie itself (the cookie value is *just* a timestamp, but we don't want to depend on that internal format).

### Why headless WebView, not native ALTCHA solver

ALTCHA is a SHA-256-based proof-of-work; a native solver is mathematically straightforward (~50 lines of Kotlin). Rejected because:

1. Squid's challenge-issuing endpoint, request shape, and response format are not stable surfaces we control. A reverse-engineered native solver breaks the next time the site updates its bundle.
2. The headless WebView reuses the *exact* mechanism that already works for the manual flow, so failure modes are identical and well-understood.
3. ALTCHA's hash-rate target is intentionally tuned to the website's own solver in JS — running it in our WebView matches the intended UX. A native solver running 10x faster might raise bot-detection flags downstream.

### Why ProcessLifecycle scope, not Activity scope

Activity-scoped components die on rotation, which would interrupt a refresh in progress. Process-lifecycle scope survives configuration changes but stops when the app is fully backgrounded (~30s grace period after last Activity hides), matching the user's stated requirement of "only when the app is active."

### Why not always-on, why not WorkManager periodic

User explicitly requested "only when Kennyy is down" and "only when the app is active." This rules out:

- WorkManager periodic refresh (runs when app is killed; refreshes a cookie we may never use).
- Always-on foreground service (wasteful for healthy Kennyy users; battery cost).

ProcessLifecycle + Kennyy-health gate gives exactly the requested envelope.

## Resolved decisions

1. **Source-layer logging surface name.** Per-source tags (`KennyySource`, `QobuzSource`). Clarity at the call site beats marginal greppability gain; cross-source filtering via regex is trivial.

2. **Cascade-guard event surface.** `SharedFlow<StreamingHaltedEvent>`. The Snackbar is a transient notification; it shouldn't redisplay on rotation. The *paused* state is already exposed via existing playback state.

3. **What counts as "Kennyy failed" for the health window.**
   - Network failure (timeout, no route, DNS fail) — **counts.**
   - HTTP 5xx — **counts.**
   - HTTP 4xx (proxy reachable but rejecting our query) — **counts.**
   - `null` from successful HTTP (no match in catalog) — **does NOT count.** Per-track catalog misses are normal and shouldn't trip the health monitor; only proxy-level distress signals do.

   Implementation: `KennyySource.resolveImmediate` already returns null for both "no match" and "request failed." The plan must add a distinct signal (sealed result, parallel error channel, or per-failure log inspection) so `KennyyStreamResolver` can call `monitor.recordFailure()` only for the latter.

4. **Auto-refresh cadence.** **Derived from cookie age.** Read `captchaCookieSetAtMs`, schedule next refresh for `setAt + 25min - now`. If already past, refresh immediately. Handles the edge case where the user manually re-auths shortly before the auto-refresh would have fired.

## Non-goals

- **Replacing yt-dlp** with a different YouTube backend. The 2026-05-21 incident showed that even working yt-dlp URLs can be CDN-blocked; that's a separate problem. Out of scope.
- **Surfacing degraded-quality warnings** when YouTube fallback serves a track. The `origin` field on `StreamUrl` already supports this; UI surfacing is a separate feature.
- **Persisting Kennyy health across process restarts.** Optimistic-on-launch is fine — gives Kennyy a fresh chance, and the auto-refresh cycle picks up within ~5 resolves if Kennyy is still down.
- **Auto-refreshing during a download workflow** (vs streaming). Downloads have their own retry semantics via `LosslessRetryScheduler` and the existing notification path; not touching that.

## Implementation order

1. Resolver logging (Component 1) — small, low-risk, immediately useful for diagnosis.
2. Cascade guard (Component 2) — small, prevents queue destruction during outages, ships independently.
3. Squid auto-refresh (Component 3) — larger, depends on (1) for `KennyyHealthMonitor`'s recordSuccess/recordFailure plumbing being uncontroversial.

Ships as one branch with three commits or three branches; user's preference at planning time.
