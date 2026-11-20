# CLAUDE.md

Guidance for Claude Code in this repository. Workspace-wide rules (language, bench, issue tracking) live in `../CLAUDE.md`.

Selene Game Streaming is the Android client of `eumarcodev/Selene-Game-Streaming`, an independent fork of Artemis /
Moonlight Android, paired with the Helios host (`../Helios-Server`). The Java package and namespace are still `com.limelight`.

## 1. Critical commands

Toolchain: JDK `C:\Users\hanre\.jdks\jbr-21.0.11` (the system `JAVA_HOME` is a JDK 25 in Program Files: set `JAVA_HOME` to jbr-21),
NDK `27.0.12077973`, compileSdk/targetSdk 36, minSdk 21.

```powershell
cmd /c "cd /d E:\projects\pessoal\network-game\Selene-Game-Streaming && E:\projects\pessoal\network-game\Selene-Game-Streaming\gradlew.bat assembleNonRoot_gameDebug"
cmd /c "cd /d E:\projects\pessoal\network-game\Selene-Game-Streaming && E:\projects\pessoal\network-game\Selene-Game-Streaming\gradlew.bat testNonRoot_gameDebugUnitTest --tests <Class>"
```

- Debug APKs land in `app\build\outputs\apk\nonRoot_game\debug`, one per ABI: take `app-nonRoot_game-arm64-v8a-debug.apk` by name, never "the newest file".
- Unit tests fail with "Could not find or load main class Files" when `PATH` has spaces (the test JVM gets `-Djava.library.path=<PATH>`): first `$env:Path="C:\Users\hanre\.jdks\jbr-21.0.11\bin;C:\WINDOWS\system32;C:\WINDOWS"`.
- In PowerShell 5.1 scripts do not set `$ErrorActionPreference='Stop'` around Gradle: its stderr warnings become fatal.
- CI release build: `./gradlew assembleNonRoot_gameRelease -PversionName=<v> -PversionCode=<n>`; local builds default to `0.0.0-dev` / 1.
- Install on a device: `adb` at `/e/projects/android-sdk/platform-tools/adb.exe` with `export MSYS_NO_PATHCONV=1`, Windows-style paths (`E:/.../app-nonRoot_game-arm64-v8a-debug.apk`), and `-s <serial>` when more than one device is connected. Debug package: `com.eumarcodev.selene.debug`.

## 2. Architecture map

- `app/src/main/java/com/limelight/`: activities (`PcView` host list, `AppView`, `Game` stream screen, `AutoSetupActivity`, `ProfilesActivity`).
  - `nvstream/`: connection to the host (`NvConnection`, `StreamConfiguration`, `http/` pairing and host queries).
  - `binding/video/`: decoders and presentation (`MediaCodecDecoderRenderer`, `PyroWaveDecoderRenderer`, `PyroWaveBudgetController`, `DecodeBenchmarkRunner`, `PresentRegularity`, stats/overlay).
  - `binding/input`, `binding/audio`: input drivers and audio renderer.
  - `preferences/`: settings and automatic-setup logic; `profiles/`: per-profile settings.
- `app/src/main/jni/`: ndk-build native code (`Android.mk`); `moonlight-core/` holds the JNI bridge and the `moonlight-common-c` submodule (protocol, depacketizer, control stream).
- `pyrowave-android/`: Gradle module `:pyrowave` (CMake) that builds `pyrowave-shared` from the `third_party/pyrowave` submodule plus the JNI player glue in `src/main/cpp/pyrowave_player.cpp`.
- `app/src/test/java/`: Robolectric/JUnit unit tests (mirrors the main package layout; shadows in `shadows/`).
- Flavors: `nonRoot_game` (published, id `com.eumarcodev.selene`), `nonRoot_play` (Google Play, same id and key), `root` (not published).
- Release automation: `.github/workflows` (`rc-queue.yml`, `rc.yml`, `stable-queue.yml`, `promote.yml`, `play.yml`, reusable `build.yml`), documented in `.github/RELEASING.md`.

## 3. Hard rules

- **IMPORTANT: no device-specific code.** The app is open source: NEVER add model/vendor checks or constants measured on the bench devices; decide from what the OS reports at runtime.
- **IMPORTANT: any flow that opens a test session must never quit an app another client is streaming** (`NvConnection` quits the running app on the host in some paths).
- **The version file is `version.txt`, never `VERSION`.** `versionCode` = commits × 10, +1 for stable (stable is built from the RC's commit).
- PyroWave 4:2:0 needs even stream sizes: keep the rounding to even.
- On Mali, GPU timestamps under-report when frames overlap: measure decode as wall-clock time per frame with the GPU saturated and the surface sized like the stream.
- After restoring a C/C++ file with `Copy-Item`, `touch` it: ndk-build skips files with old timestamps and the APK silently keeps the old code.
- Files written from PowerShell 5 must be UTF-8 **without BOM** (a BOM breaks javac).
- Branches: `feature/*` from `develop` (the default branch), PR to `develop`. **No `release/*`, `hotfix/*` or nightly.** Never commit directly to `develop` or `master`: the `master-develop` ruleset rejects direct pushes, force-pushes and deletion of both (no bypass, not even for GitHub Actions).
- After any stream test on a device: Back → "Quit Session" → "SIM", and restore the debug prefs. Do not force-stop mid-session (it leaves the session open on the host).
- In adb scripts wait for the element (`uiautomator dump`) before every tap and verify the result; after ONE failed step take a screenshot and diagnose, never repeat it blindly.

## 4. Workflow preferences

- Change only what the task needs; unrelated findings become a new issue in `eumarcodev/Helios-Server` (component label `selene`), not extra changes in the PR.
- Pull requests are not built in CI: build and run the unit tests locally before opening one.
- Do not trust AI-generated tests alone (`.github/CONTRIBUTING.md`): test each change by hand on a real device, and back performance claims with an alternated A/B measurement.
- Conventional Commits, one commit per logical phase, temporary debug logs out of commits.
- Protocol or codec changes go first to `eumarcodev/moonlight-common-c` or `eumarcodev/pyrowave` (PR to their `develop`, then `develop` → `master`), then a separate submodule bump PR here.
- Releases: each merge to `develop` that changes more than Markdown/`docs/`/`.github/` (except `build.yml`)/`version.txt` publishes `vX.Y.Z-rc.N`. Stable = PR `develop` → `master`; its merge runs `stable-queue.yml` → `promote.yml`, which opens a PR `chore: start X.Y+1.0 ...` that bumps `version.txt` on `develop`: merge it before the next merge into `develop`, or `rc-queue.yml` refuses the RC. After any merge, follow the run to the end before calling the work done.

## 5. Out of scope

Do not change without being asked:
- **Signing and Play publishing**: the `SELENE_KEYSTORE_FILE` signing setup, the `nonRoot_play` flavor, `play.yml` and the release workflows.
- **`master`**: it only moves through the `develop` → `master` PR (the manual `promote.yml` fast-forward is rejected by the ruleset).
- **Submodules** `third_party/pyrowave` and `app/src/main/jni/moonlight-core/moonlight-common-c`: never edit in place; change the fork, then bump.
- **README lineage and credits** (Moonlight, Artemis, ClassicOldSong).
- **The user's uncommitted state** in this checkout (modified `third_party/pyrowave` submodule, `.vscode/`, `local.properties`): do not commit, discard or stash it.
