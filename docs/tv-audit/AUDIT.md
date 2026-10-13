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
| Following — existing `full` install | **deferred** | `full-08` "Couldn't load your follows"; see §6 |
| Search — fresh sign-in | already fine | query returns results (`play-09`) |
| Search — existing `full` install | **deferred** | `full-11` returns no results; see §6 |
| Login (device code + QR) | already fine | `play-05` shows code + `twitch.tv/activate`; sign-in completed on device → `play-07` |
| signed-out browse | **deferred** | Twitch Helix 401s anonymous reads; `play-04`, `play-06`; see §6 |
| cold start | measured | §7 |
| self-updater | **deferred** | flavor-aware manifest; cannot exercise end-to-end; see §8 |
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
| Search (results) | `full-11-search-results.png/.xml` (empty results) | `play-09-search-results.png/.xml` |
| Following | `full-08-following.png/.xml` (error) | `play-04-following.png/.xml` (signed out), `play-08-following-signedin.png/.xml` |
| Downloads / SMB / saved VODs | `full-09-downloads.png/.xml` | — |
| Live stream | `full-04-stream-live.png/.xml`, `full-05-stream-controls.png/.xml` | — |
| Login (device code + QR) | — | `play-05-login.png/.xml` |
| Cold-start benchmark (raw) | `full-12-coldstart-benchmark.txt` | — |

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
from the nav rail.") on Home, Following and Category (`ViewModels.friendlyLoadError`), confirmed on
`play-01`.

### 6b. User-scoped reads fail on the long-lived `full` install

On the `full` install already on this TV, **Following** shows "Couldn't load your follows"
(`full-08`) and **Search** returns no results (`full-11`), while the app does **not** show the
`Sign in` rail item — i.e. it believes it is signed in. The app's own log while loading says:

```
TvHomeViewModel: Twitch private resume history is unavailable; retaining local VOD checkpoints.
```

The same account freshly signed in on `play` works for both (`play-08`, `play-09`). Conclusion: the
stored credential on that specific older install no longer authorizes user-scoped reads. This is
**not** a regression from the flavor split (no auth/networking code changed) and the root cause of
the stale credential was **not established** in this sweep; it is recorded honestly rather than
declared fine. Freshly reinstalling/signing in `full` is the expected recovery.

## 7. Performance — cold start

Measured with the existing `benchmark-tv` harness
(`scripts/benchmark-tv-smb.ps1 -Methods coldStartup` → `TvExperienceBenchmark#coldStartup`,
`StartupTimingMetric` + `MemoryUsageMetric(Max)`, `StartupMode.COLD`, `CompilationMode.None`,
5 iterations), against the final build. Raw output: `full-12-coldstart-benchmark.txt`.

| Metric | min | **median** | max | stddev |
|---|---|---|---|---|
| `timeToInitialDisplayMs` | 1985.8 | **2012.7** | 2277.6 | 123.8 |
| `memoryHeapSizeMaxKb` | 15102 | 15706 | 19389 | 1768 |
| `memoryRssAnonMaxKb` | 60964 | 61828 | 65856 | 1935 |
| `memoryRssFileMaxKb` | 119972 | 120708 | 130488 | 4450 |

**Cold start: 2012.7 ms median**, versus the recorded **1190 ms** median this sweep is compared
against (≈ +69%). Two caveats, both material:

- `CompilationMode.None()` deliberately does **no** AOT/profiling run, so this is the un-warmed
  launch path; the 1190 ms baseline's measurement conditions are not recorded anywhere in-repo
  beyond the number itself.
- Run-to-run variance on this firmware is large: an earlier identical 5-iteration run this sweep
  reported 2117.4 ms median / 99.8 MB RSS-anon, this one 2012.7 ms / 61.8 MB. The device is
  thermally and co-tenancy noisy, so treat these as one sample of a wide distribution.

Methodology note: the first attempts looked like "the harness crashes after 3 iterations". That was
**not** the firmware — a second, supposedly-cancelled OpenChamber goal session was running the same
`am instrument` against the same package, so each session's run killed the other's runner
(`ActivityManager: Crash of app … running instrumentation`). With one session on the device, the run
completes cleanly (`OK (1 test)`).

### Explicitly unmeasured (FrameTimeline)

`TvExperienceBenchmark#homeDpadNavigation` and `#savedVodPlaybackWithChat` collect
`FrameTimingMetric`. The onn firmware's FrameTimeline track has no slices for TraceProcessor (the
harness itself documents this in `TvExperienceBenchmark.kt`), so **no frame-timing number is
reported** — left explicitly unmeasured rather than estimated.

## 8. Open decisions / deferrals

These need a product or cross-platform decision this sweep cannot take; recorded rather than silently
passed.

**(a) `full` self-updater still points at upstream's `tv-latest`.**
`BuildConfig.UPDATE_MANIFEST_URL` for `full` is
`https://github.com/dhawal-ss/puretv/releases/download/tv-latest/tv-version.json` — upstream's
release, signed with upstream's key, which this fork's build cannot install over (§3). `play` points
at a `tv-play-latest` manifest that does not exist yet. Exercising the updater end-to-end therefore
requires a fork-published release, which this sweep must not create (no publishing). Options, needing
a decision: (1) point `full` at the fork's own releases once one is published; (2) disable the
in-app updater for `full` and rely on manual sideload; (3) leave as-is. Pre-existing behaviour; left
unchanged.

**(b) GQL migration for public reads** (see §6a) is a `core` change shared by three platforms and
needs cross-platform validation; deferred.

**(c) Evidence size.** `docs/tv-audit/evidence/` is ~6.6 MB of PNGs (22 files) plus ~0.3 MB of XML.
The PNGs are kept because the goal requires per-screen screenshots alongside the dumps and they are
the primary human-verifiable artefact; XML dumps alone do not show focus/visual state. A 6.6 MB
checked-in evidence set is acceptable for a one-off audit. If that is unwanted, the PNGs are the part
to drop.
