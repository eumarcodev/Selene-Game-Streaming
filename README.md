<p align="center">
  <img src="branding/selene-icon.png" width="160" alt="Selene icon">
</p>

# Selene Game Streaming

Selene Game Streaming is an open source Android client that streams games and the desktop of a Windows PC to an
Android device, at home or over the internet. It is made for the [Helios](https://github.com/eumarcodev/Helios-Server)
host and also works with other GameStream-compatible hosts such as [Sunshine](https://github.com/LizardByte/Sunshine).

It keeps the client features of Artemis: custom resolutions, bitrates and virtual buttons, several mouse and touchpad
modes, external monitor and portrait modes, video scale and pan/zoom, and, with Helios, virtual display control,
server commands and clipboard sync.

Selene uses its own application id (`com.eumarcodev.selene`), so it installs next to Artemis and Moonlight and does not
reuse their settings or pairings. Downloads: [Releases](https://github.com/eumarcodev/Selene-Game-Streaming/releases)
(one APK per processor type; `arm64-v8a` fits almost every device).

## Lineage and credits

The names continue the sun and moon family of the streaming projects: Selene is the moon, the companion of the sun host Helios.

<p align="center">
  <img src="branding/helios-selene.png" width="600" alt="Helios and Selene icons joined by the stream">
</p>

The icons tell the same story: Helios, the sun, emits the stream, and Selene, the moon, receives it and reflects it (the orange waves cool into cyan as they reach the sapphire). The icon sources and the generator live in [`branding/`](branding).

| Project | Role | Authors |
|---------|------|---------|
| [Moonlight](https://github.com/moonlight-stream) (moonlight-android) | Original open source client for NVIDIA GameStream | Cameron Gutman and the Moonlight contributors |
| [Artemis (Moonlight Noir)](https://github.com/ClassicOldSong/moonlight-android) | Moonlight Android fork with virtual display, clipboard sync and many client features | ClassicOldSong |
| **Selene Game Streaming** | This project, an independent fork of Artemis | Maintained by [eumarcodev](https://github.com/eumarcodev) |

The matching host side: [Sunshine](https://github.com/LizardByte/Sunshine) (LizardByte and contributors) -> [Apollo](https://github.com/ClassicOldSong/Apollo) (ClassicOldSong) -> [Helios](https://github.com/eumarcodev/Helios-Server).

The low-latency codec is [PyroWave](https://github.com/Themaister/pyrowave) by Hans-Kristian Arntzen (Themaister), used through the fork [eumarcodev/pyrowave](https://github.com/eumarcodev/pyrowave).

### Thanks

- **ClassicOldSong**, for Artemis and Apollo, which are the base of this fork.
- **Cameron Gutman**, **Diego Waxemberg**, **Aaron Neyer**, **Andrew Hennessy** and every Moonlight contributor, for the client and the streaming protocol work. Moonlight is the work of students at [Case Western](http://case.edu) and was started as a project at [MHacks](http://mhacks.org).
- **LizardByte** and the Sunshine contributors.
- **Hans-Kristian Arntzen (Themaister)**, for PyroWave.

This fork keeps the original license (GPLv3) and copyright notices. The upstream history is preserved in this repository's git log. It is not affiliated with Moonlight, Artemis, Apollo or Sunshine.

## What changed since the fork

**PyroWave codec** (decoded with Vulkan compute shaders, for the lowest latency on a local network):

- Offered to the host next to H.264, HEVC and AV1 and on by default; the renderer follows the negotiated format.
- Frames that lost packets are still decoded (only the lost blocks degrade), and a frame is delivered as soon as its last packet arrives.
- Decode runs off the network thread, and the next frame is recorded while the previous one decodes; frame pacing, an option to drop the older frame when newer ones wait, a half display rate option, and steadier presentation with `VK_GOOGLE_display_timing`.
- Own bitrate setting (Automatic follows the Wi-Fi link speed) and "Sharper still screen" with Helios.
- Automatic setup: a local decode benchmark finds the resolution the device sustains, Selene can ask Helios for a test-pattern session, and the frame budget is adjusted during the session.
- Even stream sizes, so odd custom resolutions work; an even frame rate is suggested when the panel refuses the stream rate.

**Streaming and overlay**

- The same performance overlay for every codec (decode and host encode time, frame arrival jitter, speed, loss, network latency, audio loss and late audio, PyroWave stage times), built off the decode thread.
- Audio catches up after late bursts instead of staying behind.
- Faster FEC recovery (ARMv8 NEON) in the streaming core.
- A clear message when the host is in use by another device.

**Product and releases**

- Renamed to Selene Game Streaming with its own application id and icon.
- Signed APKs per processor type, release candidates on every merge and stable releases; a Google Play build flavor.

Recommended settings per network and troubleshooting with the overlay are in the Helios
[streaming guide](https://github.com/eumarcodev/Helios-Server/blob/master/docs/streaming-guide.md).

## Building

* Install Android Studio, JDK 21 and the Android NDK `27.0.12077973`.
* Run `git submodule update --init` from within the repository folder, then `git submodule update --init --recursive app/src/main/jni/moonlight-core/moonlight-common-c` and `bash third_party/pyrowave/checkout_granite.sh` (PyroWave's Granite has many nested submodules that are not needed, so do not use `--recursive` on everything).
* Install the CMake package (`cmake;3.22.1`) from the Android SDK Manager; it builds the PyroWave module.
* In the repository folder, create a file called `local.properties` with an `ndk.dir=` property set to your NDK directory.
* Build with Android Studio or Gradle, for example `./gradlew assembleNonRoot_gameDebug`.

## License

GPLv3
