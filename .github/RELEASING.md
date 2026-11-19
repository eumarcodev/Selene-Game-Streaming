# Releasing

Selene Game Streaming publishes two kinds of builds: **release candidate (RC)** and **stable**. Releases are created by the
workflows in [`.github/workflows`](workflows); nothing is published by hand. The flow is made for a single maintainer:
no release or hotfix branches, and one click to go from a tested RC to stable.

## Flow

```
feature/* --PR (no CI build)--> develop --merge--> RC vX.Y.Z-rc.N   (rc.yml, automatic)
                                                     |
                                       test it, then merge develop into master
                                                     |
                     Queue stable release + Promote to stable --> STABLE vX.Y.Z (latest, tagged on master)
                     version.txt on develop --> next minor
```

| Channel | Source | When | Version | GitHub release |
|---|---|---|---|---|
| RC | `develop` | every merge that changes more than docs | `1.9.0-rc.3` | pre-release |
| Stable | `master` (same build as the RC) | every merge of `develop` into `master` | `1.10.0` | latest |

- `develop`: integration branch; pull requests go here.
- `master`: released code only. Merging `develop` into it (PR `develop` -> `master`) releases the stable version.
- `version.txt` (repository root, on `develop`): the version the next stable release will have. RCs are numbered after it.
  It is not called `VERSION` because on Windows that name collides with the C++ standard header `<version>`.

## How to

### Release candidate

Merge a pull request into `develop`. **Queue release candidate** (`rc-queue.yml`, a few seconds) works out the next
`vX.Y.Z-rc.N` and starts **Release candidate** (`rc.yml`), whose run is titled `Release candidate vX.Y.Z-rc.N`. It
builds and publishes that RC and, in parallel, builds the same commit as the stable `vX.Y.Z` and keeps it as an
artifact of the run (`selene-android-stable`, plus `selene-android-stable-play` with Play publishing on; 90 days), for
the promotion. A newer merge cancels an RC that is still building. Merges that only change Markdown, `docs/`,
`.github/` (except `build.yml`) or `version.txt` do not queue an RC. To queue one anyway:
**Actions > Queue release candidate > Run workflow** on `develop`.

### Stable

Merge `develop` into `master` (PR `develop` -> `master`) after the RC of `develop`'s last change is published.
**Queue stable release** (`stable-queue.yml`, a few seconds) reads `version.txt` and starts **Promote to stable**
(`promote.yml`) with that version and the merge commit; its run is titled `Stable release v1.10.0`. It stops with an
error when `master` builds differently from the latest RC of that version (only Markdown, `docs/`, `.github/` other than
`build.yml` and `version.txt` may differ), and queues nothing when nothing in the build changed since the last stable
release. To release by hand instead: **Actions > Promote to stable > Run workflow**, type the version (`1.10.0`) and
optionally an RC number (`2` for `v1.10.0-rc.2`); this fast-forwards `master` to the RC's commit, so it only works while
`master` has no merge commits that `develop` lacks. The workflow:

1. takes the stable build that the RC's run made, without building again (about 1 minute). Only when that artifact is
   gone (older than 90 days, or its job failed) it builds the RC's commit with the stable version, as the version is
   baked into the APKs;
2. creates the tag `vX.Y.Z` (on the merge commit of `master`, or on the RC's commit when run by hand) and publishes it
   as the latest release, with notes since the previous stable release;
3. bumps `version.txt` on `develop` to the next minor version (`1.10.0` -> `1.11.0`).

### Patch release

Set `version.txt` to the patch version (e.g. `1.10.1`) in the pull request with the fix. Its merge builds
`v1.10.1-rc.1`; merge `develop` into `master` to release it. After it, `version.txt` goes to the next minor (`1.11.0`).

If `develop` already has unreleased work that must not ship in the patch, ship it all anyway, or revert it first:
there are no hotfix branches in this flow.

## One-time setup

Add the signing secrets (see [Signing](#signing)). Google Play publishing is optional (see [Google Play](#google-play)).

## Pull requests

Pull requests are not built in CI: build and test locally before opening the PR. The merge into `develop` builds the RC.

## APK files

Each release has one APK per ABI of the `nonRoot_game` flavor, named `Selene-<version>-nonRoot-<abi>.apk`
(`arm64-v8a` for almost every phone).
The root flavor is not published.

## Signing

Android only installs an update over an app signed with the **same key**, so the keystore must never change.
Back it up outside GitHub (a password manager and an offline copy). If it is lost, users have to uninstall and pair again.

Repository secrets (**Settings > Secrets and variables > Actions**):

| Secret | Content |
|---|---|
| `SELENE_KEYSTORE_BASE64` | the `.jks` file encoded in base64 |
| `SELENE_KEYSTORE_PASSWORD` | keystore password |
| `SELENE_KEY_ALIAS` | key alias (`selene`) |
| `SELENE_KEY_PASSWORD` | key password |

RC and stable builds fail if a secret is missing. For a local signed build, export `SELENE_KEYSTORE_FILE`,
`SELENE_KEYSTORE_PASSWORD`, `SELENE_KEY_ALIAS` and `SELENE_KEY_PASSWORD` before `./gradlew assembleNonRoot_gameRelease`.

## Versions

`version.txt` gives `X.Y.Z`; `rc.yml` adds `-rc.N`. The RC workflow refuses a `version.txt` that is already released or lower
than the latest stable release. The version is passed to Gradle as `-PversionName`. Local builds without these
properties get `0.0.0-dev` and code 1. Gradle needs JDK 17 to 21 (JDK 25 breaks the Android Gradle plugin).

`versionCode` is the number of commits of the built revision times 10, plus 1 for a stable build. A stable release is
built from the same commit as the RC it promotes (in the same run), so the `+1` keeps its code above that RC, and any later RC has more
commits and a higher code again. Google Play rejects a `versionCode` it has already received, on any track, so
re-running an RC or a promotion after its Play upload succeeded fails at the Play step; that is expected.

Old releases (`release/*`, `hotfix/*` branches and nightly tags) were made by the previous flow and are left as they
are. Their codes were the plain commit count, so the new codes are all higher.

## Google Play

The app is also published on Google Play as `com.eumarcodev.selene`. The Play build is the `nonRoot_play` flavor: the
same app as `nonRoot_game` (same id, same signing key), without the link to the GitHub releases page in the settings,
since Play policy forbids pointing users to another update channel (`BuildConfig.PLAY_BUILD`). Play receives an AAB,
not APKs, and it carries the native debug symbols; `mapping.txt` is uploaded too, so crash reports are readable.

| Channel | Play track |
|---|---|
| RC | `PLAY_RC_TRACK` (default `alpha`, the closed testing track) |
| Stable | `production` |

Publishing is off until the repository variable `PLAY_PUBLISHING` is `true`; until then the workflows do exactly what
they did before. `play.yml` does the upload, using `r0adkll/upload-google-play`.

### One-time setup (Play Console and Google Cloud)

1. **Play Console > Create app**, package `com.eumarcodev.selene`. Fill in the store listing (512 px icon, 1024x500
   feature graphic, screenshots), the privacy policy URL, Data safety, content rating and target audience.
2. **App signing**: choose to use your own key and upload the existing `selene` keystore (Play Console shows the
   `pepk` command to export it). Then the Play version and the GitHub APKs have the same signature and users can
   switch between them without uninstalling. If Google generates a new key instead, the two can never update each other.
   The same keystore is then the upload key, so the `SELENE_KEYSTORE_*` secrets need no change.
3. **First release by hand**: build the AAB locally (`./gradlew bundleNonRoot_playRelease` with the signing variables
   from [Signing](#signing)) and upload it in the Play Console to the closed testing track. The API cannot create the app.
4. **Personal accounts created after 2023-11-13**: Google requires a closed test with at least 12 testers opted in for
   14 days in a row before production access. Use the `alpha` track and the RC builds for it.
5. **Service account**: in Google Cloud, enable the *Google Play Android Developer API*, create a service account and a
   JSON key. In **Play Console > Users and permissions**, invite the service account e-mail with access to this app and
   the release permissions (*Release to production...* and *Release apps to testing tracks*).
6. In GitHub, **Settings > Secrets and variables > Actions**:
   - secret `PLAY_SERVICE_ACCOUNT_JSON`: the whole JSON key file;
   - variable `PLAY_PUBLISHING` = `true`.

Store the JSON key like the keystore: it is enough to publish the app. Revoke it in Google Cloud if it leaks.

### Variables

| Variable | Default | Effect |
|---|---|---|
| `PLAY_PUBLISHING` | (unset) | `true` turns the Play upload on in `rc.yml` and `promote.yml` |
| `PLAY_RC_TRACK` | `alpha` | track for RCs (`alpha`, `beta`, `internal` or a custom closed track) |
| `PLAY_ROLLOUT` | (unset) | fraction for a staged production rollout, e.g. `0.2`; finish or halt it in the Play Console |
| `PLAY_RELEASE_STATUS` | `completed` | `draft` while the app has never been published (the API refuses other statuses then) |

### Target SDK

Play requires new apps and updates to target the latest Android API within a year of its release (API 36 since
2026-08-31). When Google raises it, bump `targetSdk` in `app/build.gradle` and test edge-to-edge layouts, back
navigation (`enableOnBackInvokedCallback` per activity in the manifest) and tablet orientation.
