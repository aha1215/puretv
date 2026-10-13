# Android TV feature audit — `app-tv` (play / full flavors)

Device: **onn. Streaming Device 4K Pro** (`jarvis`, Android 14 / API 34), `192.168.0.110:5555`.
Build under test: branch `codex/tv-feature-sweep`, `versionCode 16` / `versionName 1.3.2`, signed release APKs.
Evidence: `docs/tv-audit/evidence/` (byte-exact PNG screenshots + uiautomator XML dumps per screen; see §5).

Scope of this sweep: split `app-tv` into `play` / `full` product flavors, then audit every TV
feature on the device and land improvements for the weakest ones. Only `app-tv` changed; `core`
is untouched, so `app-android` / `app-windows` are unaffected.

## 1. Flavor split (`play` vs `full`)

`app-tv` has one dimension, `distribution`, with two flavors:

| | `full` | `play` |
|---|---|---|
| applicationId | `com.puretv.twitch.tv` | `com.puretv.twitch.tv.play` |
| label | PureTV for Twitch (TV) | PureTV for Twitch (Play) |
| ad stripping | enabled | **compiled out** |
| updater manifest | `tv-latest/tv-version.json` | `tv-play-latest/tv-play-version.json` |
| everything else | shared | shared |

Ad stripping is gated by a per-flavor `BuildConfig.AD_STRIPPING_ENABLED`. In `full` it is `true`; in
`play` it is a constant `false`, so R8 folds the branch away. Verified against the R8 mapping files
produced by this build:

- `full` mapping contains `TvAdBlockInterceptor`, `PlaylistDetect`, `AdMarkers`, `AdSimulator`.
- `play` mapping contains **none** of them.

On-device: `full` → Settings ▸ Network & Ads shows the **Ad blocking** section; `play` → the same
screen has no Ad blocking section (`full-03-settings-network-ads`, `play-02-settings-network-ads`).

The split also fixed flavor-incorrect copy: the sign-in screen and the Settings index previously
claimed ad blocking unconditionally. They now follow `AD_STRIPPING_ENABLED`, so `play` no longer
advertises a feature it does not ship (see §4; `TvLoginScreen.kt`, `TvSettingsScreen.kt`).

## 2. Build, install, coexistence

- `:app-tv:test` passes (both flavors; 5m33s clean build).
- Signed release APKs: `app-tv-full-release.apk`, `app-tv-play-release.apk`.
- Bundles: `app-tv-full-release.aab`, `app-tv-play-release.aab` (`:app-tv:bundlePlayRelease`).
- Both installed side by side; `pm list packages` shows both; each launches via its own
  `LEANBACK_LAUNCHER` activity (`com.puretv.twitch.tv/.TvMainActivity`,
  `com.puretv.twitch.tv.play/com.puretv.twitch.tv.TvMainActivity`).

Build commands:

```
./gradlew :app-tv:test :app-tv:assembleFullRelease :app-tv:assemblePlayRelease :app-tv:bundlePlayRelease
```

## 3. Signing

`apksigner verify --print-certs`:

- Published upstream APK (`dhawal-ss/puretv` `tv-latest`): `CN=PureTV for Twitch, OU=Mobile, O=PureTV, C=US`, SHA-256 `3d19d7907a88ea3710f8b0e9a2cd5fb740a6017494bd53068af219d287f1f94c`.
- Built `full` APK **and** the app installed on this TV: `CN=PureTV Local Fork, OU=Personal Build, O=Local`, SHA-256 `dbad5de07e4dd237f12d307abfd0d6234deffa51f57d24bfff022a710866cb5d`.

The built APK is signed with **the fork's own key** (`puretv-local-release.jks`), not upstream's
`CN=PureTV for Twitch`. Consequence:

- It installs **in place over the app already on this TV without data loss** (identical certificate).
- It **cannot** install over an upstream-signed build; a fork cannot possess upstream's private
  key, so this is expected. It means `full`'s updater URL (still upstream's `tv-latest`) would
  download an APK the OS rejects. Flagged, not changed — see §8.

## 4. Feature verdicts

| Feature | Verdict | Evidence |
|---|---|---|
| play/full flavor split | **improved** | new; R8 mapping + on device (§1) |
| ad stripping, `full` | already fine | `AD BLOCKED` pill on live playback (`full-05`, OCR); interceptor present in `full` dex |
| ad stripping compiled out, `play` | **improved** | no interceptor/strip classes in `play` dex |
| flavor-correct copy | **improved** | `play` login + Settings no longer claim ad blocking (`play-10`, `play-02`) |
| live stream playback | already fine | `full-04` (video), `full-05` (controls + chat) |
| chat overlay | already fine | message overlay renders during live playback (`full-05`) |
| chat appearance editor | already fine | `full-03b-settings-chat` |
| VOD playback + resume | already fine | `Continue 9:50:20` / `Continue 42:55` rows (`full-01`) |
| SMB VOD downloads | already fine | Downloads shows the share + `1 saved VOD` / `Open saved VODs` (`full-09`); capacity read is occasionally `unavailable` (see below) |
| saved VODs (offline) | already fine | `full-09` |
| Home browse while signed in | already fine | `full-01`; `play-07` after sign-in |
| Following — fresh sign-in | already fine | real live follows list (`play-08`) |
| Following — existing `full` install | **improved** | root cause found (§6b); now surfaces "Your Twitch session expired · Sign in again" instead of a dead list (`full-13`) |
| Search — fresh sign-in | already fine | query returns results (`play-09`) |
| Search — existing `full` install | **improved** | actionable message instead of silently-empty results (`full-14`) |
| Login (device code + QR) | already fine | `play-05` shows code + `twitch.tv/activate`; sign-in completed on device → `play-07` |
| signed-out browse | **deferred** | Twitch Helix 401s anonymous reads; `play-04`, `play-06`; see §6 |
| cold start | measured | §7 |
| self-updater | **improved** | both flavors now point at the fork's own repo; an absent manifest fails quietly (`full-15`); exercising it end-to-end needs a published fork release (§8a) |
| no-telemetry promise | already fine | no analytics/crash SDK in any `*.kts`; `core` untouched |

D-pad behaviour checked on every screen above: each dump shows a focused, focusable element and a
visible Back affordance where the screen is not a nav root; no screen trapped Back (Search and
Settings are nav roots, so Back exits the app, which is the standard Android TV root behaviour), no
screen was a dead end (error states all offer `Try again` / `Sign in`), and no placeholder text or
crash was observed.

## 5. Per-screen evidence index

All files in `docs/tv-audit/evidence/`. PNGs are byte-exact (`adb exec-out`-safe `screencap -p` +
`adb pull`); XMLs are `uiautomator dump` output for the same screen.

| Screen | `full` | `play` |
|---|---|---|
| Home (signed out) | — | `play-01-home.png/.xml` |
| Home (signed in) | `full-01-home.png/.xml` | `play-07-home-signedin.png/.xml` |
| Settings (overview / palette) | `full-02-settings.png/.xml`, `full-10-settings-overview.png/.xml` | `play-10-settings-overview.png/.xml` |
| Settings ▸ Network & Ads | `full-03-settings-network-ads.png/.xml` | `play-02-settings-network-ads.png/.xml` |
| Settings ▸ Chat | `full-03b-settings-chat.png` | — |
| Categories | `full-06-categories.png/.xml` | `play-03-categories.png/.xml` |
| Search (empty) | `full-07-search.png/.xml` | `play-06-search.png/.xml` |
| Search (results) | `full-14-search-session-expired.png/.xml` (expired session), `full-11-search-results.png/.xml` (empty) | `play-09-search-results.png/.xml` |
| Following | `full-13-following-session-expired.png/.xml` (expired session), `full-08-following.png/.xml` (pre-fix error) | `play-04-following.png/.xml` (signed out), `play-08-following-signedin.png/.xml` |
| Downloads / SMB / saved VODs | `full-09-downloads.png/.xml` | — |
| Live stream | `full-04-stream-live.png/.xml`, `full-05-stream-controls.png/.xml` | — |
| Login (device code + QR) | — | `play-05-login.png/.xml` |
| Settings ▸ Account & Updates (update check) | `full-15-settings-update-check.png/.xml` | — |
| Cold-start A/B (raw) | `full-16-coldstart-ab.txt` | — |

The player screenshots (`full-04`, `full-05`) carry the player evidence: the ExoPlayer surface and
its Compose overlay expose **no** accessibility text, so their XML dumps are structurally empty by
design. Their content was read from the screenshots instead (see §7).

## 6. Deferred with reason

### 6a. Signed-out browse — Twitch Helix now requires OAuth for public reads

Reproduced directly against the live API from the host:

```
GET https://api.twitch.tv/helix/games/top?first=1   (fork client id)        -> 401 {"message":"OAuth token is missing"}
GET https://api.twitch.tv/helix/games/top?first=1   (twitch web public id)  -> 401 {"message":"OAuth token is missing"}
```

Helix now rejects **anonymous** requests for `/streams`, `/games/top`, `/search/channels`. PureTV
only attaches `Authorization` when a viewer is signed in, so a **signed-out** viewer gets HTTP 401.
On `play` (fresh, signed out) this is visible on Home (`play-01`), Following (`play-04`) and Search
(`play-06`). This is **pre-existing** (the flavor split touched no networking/auth code) and external
(a Twitch policy change). Landing a full fix needs one of:

1. **App access token** — mint a client-credentials token so anonymous reads succeed. Needs a real
   Twitch `client_secret`, which this checkout does not have.
2. **Migrate public reads to GQL** — the app's other Twitch calls already use `gql.twitch.tv` with
   the web client id, which still works anonymously. Re-routing `/streams`, `/games/top` and
   `/search/channels` is a `core` change shared by `app-android` and `app-windows` and needs
   cross-platform validation.

Landed mitigation this sweep: the TV app no longer leaks the raw exception or blames the connection
— a 401 is mapped to an actionable message ("Twitch now requires sign-in to browse. Choose Sign in
from the nav rail.") on Home and Browse (the category grid) via `ViewModels.friendlyLoadError`; the
signed-out Home is captured in `play-01`. The signed-out Following/Search screens are covered by the
same auth-vs-network distinction described in §6b.

### 6b. User-scoped reads fail on the long-lived `full` install — root cause found, made actionable

**Symptom.** On the `full` install already on this TV, **Following** showed "Couldn't load your
follows" (`full-08`) and **Search** returned nothing (`full-11`), while the app showed no `Sign in`
rail item — it believed it was signed in.

**Root cause (established).** The stored access token had expired and the refresh could never
succeed, and the failure was swallowed:

1. The app's own log while refreshing:
   `W TokenRefresher: com.puretv.twitch.core.api.TokenRefreshException: invalid client secret`.
2. Reproduced against `id.twitch.tv/oauth2/token` from the host with this app's client id:

   | request | response |
   |---|---|
   | `client_secret=PASTE_YOUR_CLIENT_SECRET_HERE` | `403 {"message":"invalid client secret"}` |
   | no `client_secret` / empty | `400 {"message":"missing client secret"}` |

   So this Twitch client **requires** a client secret for refresh, and the fork builds with the
   placeholder ([`TwitchSecrets`] is generated from a gitignored `secrets.properties` / `TWITCH_CLIENT_SECRET`
   that this checkout does not have). Refresh therefore fails **every** time.
3. `TokenRefresher.refreshIfPossible` did `runCatching { … }.onSuccess { … }` with **no `onFailure`**,
   so a definitive rejection was indistinguishable from success to every caller: the dead token
   stayed, the app stayed "signed in", and every user-scoped read 401'd.

The device-flow **sign-in** path sends no secret, which is why signing in fresh works and why the
fresh session on `play` was fine (`play-08`) — until its ~4 h access token expires and hits the same
wall.

**Fix landed (app-tv only).**

- `TokenRefresher` now **logs** the failure instead of swallowing it silently (this is what made the
  cause visible above).
- A new `isAuthFailure()` (401/403 from Helix) plus `TV_SESSION_EXPIRED_MESSAGE` drive an actionable
  state: **Following** tries one forced refresh first (so a build that *does* ship a secret recovers
  transparently) and then shows "Your Twitch session expired · Sign in again" (`full-13`); **Search**
  shows "Your Twitch session expired. Sign in again to reload." instead of an empty screen
  (`full-14`). A network error still shows the retryable "Check your connection" copy.

Verified on device: `full` shows both new states (`full-13`, `full-14`), and a fresh sign-in still
works — `play`'s Following/Search are unchanged and successful (`play-08`, `play-09`).

**Residual (a credential, not code).** Without a real Twitch client secret the fork cannot refresh at
all, so the recovery is a re-sign-in roughly every 4 h. Restoring automatic refresh needs a secret
(`secrets.properties` `twitch.client.secret=…` or `TWITCH_CLIENT_SECRET`); that is a credential this
sweep does not have and must not fabricate.

## 7. Performance — cold start

The sweep was reported as a cold-start regression (2012.7 ms vs a recorded **1190 ms** median). The
defensible finding: **it is not a regression from this sweep, and the 1190 ms reference is not
reproducible.**

### A/B — pre-sweep `e716aa6` vs current HEAD

Same device, exclusive access, same harness and configuration (`TvExperienceBenchmark#coldStartup`,
`StartupTimingMetric` + `MemoryUsageMetric(Max)`, `StartupMode.COLD`, `CompilationMode.None()`,
5 iterations per run). Raw output: `full-16-coldstart-ab.txt`.

| build | run | **median ms** | stddev | min | max |
|---|---|---|---|---|---|
| `e716aa6` (pre-sweep) | 1 | **2015.1** | 142.7 | 1991.4 | 2323.2 |
| `e716aa6` (pre-sweep) | 2 | **2034.5** | 110.7 | 2019.1 | 2280.4 |
| HEAD | 1 | **2033.9** | 83.9 | 2010.2 | 2213.4 |
| HEAD | 2 | **2066.3** | 168.7 | 2006.8 | 2418.1 |

The medians overlap (pre-sweep 2015–2035 ms, HEAD 2034–2066 ms); the difference is ~1–2%, smaller
than the run-to-run stddev. **The flavor split did not change cold start.** Memory is flat too
(RSS-anon median ≈ 61.8 MB pre-sweep, ≈ 61.9–62.1 MB HEAD).

### The 1190 ms reference

**Unreproducible.** Its measurement conditions are not recorded anywhere in the repo beyond the
number itself, and both A/B arms — including the exact pre-sweep commit — land near 2.0 s under
`CompilationMode.None()`. Comparing a differently-conditioned historical number to this and calling
it "fixed" or "a regression" would not be defensible, so no such claim is made. What can be said
plainly: on this device, un-warmed, cold start is ≈ 2.0 s for both the pre-sweep and current builds.
If the absolute figure is worth attacking, the lever already on the classpath is profile-guided
startup (`androidx.profileinstaller:profileinstaller:1.4.1` is a dependency) — but that needs its
own before/after measurement, not an assumption.

### Device exclusivity (methodology)

Earlier cold-start attempts looked like "the harness crashes after 3 iterations". That was **not**
the firmware: a second OpenChamber goal session was running the same `am instrument` against the
same package, so each session's run killed the other's runner
(`ActivityManager: Crash of app … running instrumentation`). With one agent on the device the run
completes cleanly (`OK (1 test)`). Every A/B run above was taken under exclusive device access and
checked uncontended (`CONTENDED=false`); a contended run is discarded, never reported.

### Explicitly unmeasured (FrameTimeline)

`TvExperienceBenchmark#homeDpadNavigation` and `#savedVodPlaybackWithChat` collect
`FrameTimingMetric`. The onn firmware's FrameTimeline track has no slices for TraceProcessor (the
harness itself documents this in `TvExperienceBenchmark.kt`), so **no frame-timing number is
reported** — left explicitly unmeasured rather than estimated.

## 8. Open decisions / deferrals

These need a product or cross-platform decision this sweep cannot take; recorded rather than silently
passed.

**(a) Self-updater manifest URLs — repointed at the fork (fixed; still needs a release to exercise).**
Both flavors now read `BuildConfig.UPDATE_MANIFEST_URL` from the fork's own repo (`aha1215/puretv`):
`full` → `tv-latest/tv-version.json`, `play` → `tv-play-latest/tv-play-version.json`. Previously
`full` pointed at upstream's `dhawal-ss/puretv`, which serves an APK signed with a key this fork
cannot install over (§3) — the configuration was self-inconsistent. The fork currently has **no
releases**, so the URL 404s; `fetchLatest` treats any non-2xx (and any network failure) as "nothing
to offer", so the updater stays silent on launch and neither crashes nor nags when the manifest is
absent. Verified on device against the live 404: the app stays up (`full-15`). One honesty note: the
*best-effort pressed* check reports "You're on the latest version." in that case (the forced path
reads "no manifest" as "nothing newer"); it is quiet, but it is not literally "verified latest".

Exercising the updater **end to end** (real download + install) still requires a published fork
release, which this sweep must not create. That is the one open item here.

**(b) GQL migration for public reads** (see §6a) is a `core` change shared by three platforms and
needs cross-platform validation; deferred.

**(c) Evidence size.** `docs/tv-audit/evidence/` is ~6.8 MB of PNGs (25 files) plus ~0.3 MB of XML
(24 files) and one raw benchmark text file. The PNGs are kept because the goal requires per-screen
screenshots alongside the dumps and they are the primary human-verifiable artefact; XML dumps alone
do not show focus/visual state. A ~7 MB checked-in evidence set is acceptable for a one-off audit.
If that is unwanted, the PNGs are the part to drop.
