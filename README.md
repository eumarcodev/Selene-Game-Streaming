# Selene Game Streaming

An open source Android client for [Helios](https://github.com/eumarcodev/Helios-Server), [Apollo](https://github.com/ClassicOldSong/Apollo) and [Sunshine](https://github.com/LizardByte/Sunshine) hosts.

Selene Game Streaming will allow you to stream your collection of games from your Windows PC to your Android device,
whether in your own home or over the internet.

It is an independent fork of [Artemis](https://github.com/ClassicOldSong/moonlight-android) (previously named Moonlight Noir), which is itself a fork of
[Moonlight Android](https://github.com/moonlight-stream/moonlight-android), with loads of optimizations for office usage.

A more seamless experience with virtual display will be Selene paired with [Helios](https://github.com/eumarcodev/Helios-Server).

## Lineage and credits

The names continue the sun and moon family of the streaming projects: Selene is the moon, the companion of the sun host Helios.

| Project | Role | Authors |
|---------|------|---------|
| [Moonlight](https://github.com/moonlight-stream) (moonlight-android) | Original open source client for NVIDIA GameStream | Cameron Gutman and the Moonlight contributors |
| [Artemis (Moonlight Noir)](https://github.com/ClassicOldSong/moonlight-android) | Moonlight Android fork with virtual display, clipboard sync and many client features | ClassicOldSong |
| **Selene Game Streaming** | This project, an independent fork of Artemis | Maintained by [eumarcodev](https://github.com/eumarcodev) |

The matching host side: [Sunshine](https://github.com/LizardByte/Sunshine) (LizardByte and contributors) -> [Apollo](https://github.com/ClassicOldSong/Apollo) (ClassicOldSong) -> [Helios](https://github.com/eumarcodev/Helios-Server).

### Thanks

- **ClassicOldSong**, for Artemis and Apollo, which are the base of this fork.
- **Cameron Gutman**, **Diego Waxemberg**, **Aaron Neyer**, **Andrew Hennessy** and every Moonlight contributor, for the client and the streaming protocol work.
- **LizardByte** and the Sunshine contributors.

This fork keeps the original license (GPLv3) and copyright notices. The upstream history is preserved in this repository's git log.

# Features

If you switch back to the main stream version, you'll be missing the following awesome features which are very unlikely to be added there:

1. Custom virtual buttons with import and export support.
2. [Custom resolutions](https://github.com/moonlight-stream/moonlight-android/pull/1349).
3. Custom bitrates.
4. [Multiple mouse mode switching](https://github.com/moonlight-stream/moonlight-android/pull/1304) (normal mouse, [multi-touch](https://github.com/moonlight-stream/moonlight-android/pull/1364), touchpad, disabled, local cursor mode).
5. Optimized virtual gamepad skins and free joystick.
6. External monitor mode.
7. Joycon D-pad support.
8. Simplified performance information display.
9. [Game back menu](https://github.com/moonlight-stream/moonlight-android/pull/1171).
10. Custom shortcut commands.
11. Easy soft keyboard switching.
12. Portrait mode.
13. Display on top mode, useful for foldable phones.
14. [Virtual touchpad space and sensitivity adjustment](https://github.com/moonlight-stream/moonlight-android/issues/1348#issuecomment-2236344729) for playing right-click view games, such as Warcraft.
15. Force use device's own vibration motor (in case your gamepad's vibration is not effective).
16. Gamepad debugging page to view gamepad vibration and gyroscope information, as well as Android kernel version information.
17. Trackpad tap/scrolling support
18. Natural track pad mode with touch screen
19. Non-QWERTY keyboard layout support
20. Quick Meta key with physical BACK button
21. Frame rate lock fix for some devices
22. Video scale mode: Fit/Fill/Stretch
23. View pan/zoom support
24. Rotate screen in-game
25. Add option to quit app directly
26. Samsung DeX scrolling support
27. Proper click/scroll/right-click for trackpad on generic Android tablet when using local cursor
28. Virtual Display integration with [Helios](https://github.com/eumarcodev/Helios-Server) / [Apollo](https://github.com/ClassicOldSong/Apollo)
29. Server Command integration with [Helios](https://github.com/eumarcodev/Helios-Server) / [Apollo](https://github.com/ClassicOldSong/Apollo)
30. Clipboard sync (requires Helios or Apollo)
31. SBS 3D for external Displays (Using AI MiDaS v2 Lite)

# About this fork

Selene Game Streaming started as a fork of Artemis. Since the original project is no longer being updated, this fork continues independently, with its own releases and roadmap.

It is not affiliated with Moonlight, Artemis, Apollo or Sunshine. Compatibility with other GameStream-compatible hosts is kept where possible, but is not guaranteed.

Selene uses its own application id (`com.eumarcodev.selene`), so it installs next to Artemis and Moonlight and does **not** reuse their settings or pairings: pair it again with your host.

Pull requests and ideas are welcome.

## Downloads
* [Download APK directly](https://github.com/eumarcodev/Selene-Game-Streaming/releases)
* [Use Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.eumarcodev.selene%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Feumarcodev%2FSelene-Game-Streaming%22%2C%22author%22%3A%22eumarcodev%22%2C%22name%22%3A%22Selene%20Game%20Streaming%22%2C%22additionalSettings%22%3A%22%7B%5C%22apkFilterRegEx%5C%22%3A%5C%22nonRoot%5C%22%2C%5C%22matchGroutToUse%5C%22%3A%5C%22%241%5C%22%2C%5C%22versionExtractionRegEx%5C%22%3A%5C%22v(.%2B)%5C%22%7D%22%7D)%5C%22%7D%22%7D) (recommended)

## Building
* Install Android Studio and the Android NDK
* Run ‘git submodule update --init’ from within the repository folder, then ‘git submodule update --init --recursive app/src/main/jni/moonlight-core/moonlight-common-c’ and ‘bash third_party/pyrowave/checkout_granite.sh’ (PyroWave's Granite has many nested submodules that are not needed, so do not use ‘--recursive’ on everything)
* Install the CMake package (‘cmake;3.22.1’) from the Android SDK Manager; it builds the PyroWave module
* In the repository folder, create a file called ‘local.properties’. Add an ‘ndk.dir=’ property to the local.properties file and set it equal to your NDK directory.
* Build the APK using Android Studio or gradle

## Authors

* [Cameron Gutman](https://github.com/cgutman)  
* [Diego Waxemberg](https://github.com/dwaxemberg)  
* [Aaron Neyer](https://github.com/Aaronneyer)  
* [Andrew Hennessy](https://github.com/yetanothername)

Moonlight is the work of students at [Case Western](http://case.edu) and was
started as a project at [MHacks](http://mhacks.org).
