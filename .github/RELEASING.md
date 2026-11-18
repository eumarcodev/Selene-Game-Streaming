# Releasing

Selene Game Streaming publishes three kinds of builds: **nightly**, **release candidate (RC)** and **stable**. Releases are
created by the workflows in [`.github/workflows`](workflows); nothing is published by hand.

## Channels

| Channel | Source | When | Version | GitHub release |
|---|---|---|---|---|
| Nightly | `develop` | every day at 03:00 (Brasilia), only if `develop` has new commits | `1.4.0-nightly.20261116` | pre-release, last 7 kept |
| RC | `release/X.Y` or `hotfix/X.Y.Z` | every push to the branch | `1.3.0-rc.2` | pre-release |
| Stable | tag `vX.Y.Z` on `master` | when you push the tag | `1.3.0` | latest |

The nightly version is one minor above the highest stable/rc version already tagged, so a nightly is always newer than
the release candidate in progress. With the same `X.Y.Z`, the order is `nightly < rc < stable`.

## Branches

```
feature/* --PR--> develop --daily--> NIGHTLY
                     |
                     +-- cut-release --> release/1.4 --push--> RC 1.4.0-rc.1, rc.2, ...
                                              |
                                              +-- PR --> master -- tag v1.4.0 --> STABLE 1.4.0
                                                                         |
                                                                         +-- PR --> develop (automatic)

master --- cut-release (hotfix) --> hotfix/1.4.1 --push--> RC 1.4.1-rc.1 --PR--> master -- tag v1.4.1 --> STABLE
```

- `develop`: integration branch, pull requests go here.
- `release/X.Y`: cut from `develop` when the release is feature complete. Only fixes go in.
- `hotfix/X.Y.Z`: cut from `master`, only for the **latest** stable line.
- `master`: only receives finished releases. It always contains what was published as stable.

## One-time setup

1. Create `master` and `develop` from the current default branch (`moonlight-noir`), then make `master` the default branch (scheduled workflows run from it):
   `git push origin moonlight-noir:refs/heads/master moonlight-noir:refs/heads/develop`
2. In **Settings > Actions > General**, enable **Allow GitHub Actions to create and approve pull requests**. Without
   it the stable workflow cannot open the PRs that bring `master` back into `develop`.
3. Add the signing secrets (below).
4. Optional: protect `master` and `release/**` (require a pull request).

## How to

### Nightly

Nothing to do. To build one now: **Actions > Nightly > Run workflow** (tick *force* to rebuild even without new
commits or to replace today's nightly).

### Cut a release candidate

**Actions > Cut release or hotfix > Run workflow**, kind `release`. The version defaults to one minor above the latest
stable (or type `X.Y`). It creates `release/X.Y` from `develop` and starts `vX.Y.0-rc.1`. Every further push to that
branch (bug fixes) publishes the next RC. Commits that are already on `master` are never published as an RC, so if
`develop` has nothing new, no RC is created.

### Publish the stable release

1. Open a pull request from `release/X.Y` to `master` and merge it.
2. Tag the merge commit:
   ```bash
   git checkout master && git pull
   git tag v1.4.0
   git push origin v1.4.0
   ```
3. `stable.yml` checks that the tag is on `master`, builds, publishes the release as *latest*, and opens PRs from
   `master` to `develop`. Merge them so `develop` sees the new tag.

### Hotfix

1. **Cut release or hotfix**, kind `hotfix`. The version defaults to the next patch of the latest stable
   (`1.4.1`). It creates `hotfix/1.4.1` from `master`. No RC is started yet: the branch is identical to the
   latest stable release.
2. Push the fix to that branch. Each push publishes a new RC to test.
3. Open a PR from `hotfix/1.4.1` to `master`, merge it, and tag `v1.4.1` as above.

Hotfixes only target the latest stable line. Fixing an older line needs its own support branch, which is not covered.

## Pull requests

`pr.yml` builds an **unsigned** APK when the PR changes the app, Gradle files, the submodule or the workflows
(`app/`, `gradle/`, `*.gradle`, `.gitmodules`, `.github/workflows/`). Draft PRs are skipped. Add the `build` label to force it.

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

Nightly, RC and stable builds fail if a secret is missing. For a local signed build, export `SELENE_KEYSTORE_FILE`,
`SELENE_KEYSTORE_PASSWORD`, `SELENE_KEY_ALIAS` and `SELENE_KEY_PASSWORD` before `./gradlew assembleNonRoot_gameRelease`.

## Versions

The version comes from the branch and tag names (see above) and is passed to Gradle as `-PversionName`.
`versionCode` is the number of commits of the built revision, so it grows with every build. Local builds without
these properties get `0.0.0-dev` and code 1. Gradle needs JDK 17 to 21 (JDK 25 breaks the Android Gradle plugin).

Google Play rejects a `versionCode` it has already received, on any track. Stable is built from the merge commit on
`master`, which has more commits than any RC of that release, so its code is always higher. Re-running a stable or RC
workflow after its Play upload succeeded fails at the Play step for this reason; that is expected.

## Google Play

The app is also published on Google Play as `com.eumarcodev.selene`. The Play build is the `nonRoot_play` flavor: the
same app as `nonRoot_game` (same id, same signing key), without the link to the GitHub releases page in the settings,
since Play policy forbids pointing users to another update channel (`BuildConfig.PLAY_BUILD`). Play receives an AAB,
not APKs, and it carries the native debug symbols; `mapping.txt` is uploaded too, so crash reports are readable.

| Channel | Play track |
|---|---|
| Nightly | not published |
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
| `PLAY_PUBLISHING` | (unset) | `true` turns the Play upload on in `rc.yml` and `stable.yml` |
| `PLAY_RC_TRACK` | `alpha` | track for RCs (`alpha`, `beta`, `internal` or a custom closed track) |
| `PLAY_ROLLOUT` | (unset) | fraction for a staged production rollout, e.g. `0.2`; finish or halt it in the Play Console |
| `PLAY_RELEASE_STATUS` | `completed` | `draft` while the app has never been published (the API refuses other statuses then) |

### Target SDK

Play requires new apps and updates to target the latest Android API within a year of its release (API 36 since
2026-08-31). When Google raises it, bump `targetSdk` in `app/build.gradle` and test edge-to-edge layouts, back
navigation (`enableOnBackInvokedCallback` per activity in the manifest) and tablet orientation.
