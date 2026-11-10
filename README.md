# Loophole

Loophole is an open-source IPTV player for Android TV, Google TV and Fire TV, built with Kotlin and Compose for TV. It plays the live channels and programme guide from playlists you provide: an M3U link, a local M3U file, an Xtream Codes account or a MAC (Stalker) portal, with XMLTV guides.

**Loophole is a player only. It contains no channels — add the playlist from your own IPTV subscription.**

## Status

Early development. Not yet released.

## Features

- Live channels from an M3U playlist or Xtream Codes account, with an XMLTV programme guide.
- TV guide with a live preview; full-screen playback with an info bar and a channel list overlay.
- **Favourites:** hold OK (or press ≡) on a channel → *Channel options* → add or remove. Favourites is the first group in the guide; reorder it with *Move*.
- **History:** channels watched for 10 seconds or more, newest first, from the History tile in the full-screen overlay.
- **Search:** channels and programmes from the Search item in the side rail, with the on-screen keyboard (and voice where the device offers it).
- **Scheduled updates:** playlist and guide update on a schedule, at app start, and (optionally) the guide after a playlist update. Settings → EPG sets the past days to keep and can clear the guide.
- **Start and exit:** optionally reopen the last channel on start; optionally confirm before Back leaves the app.
- **Several playlists:** Settings → Playlists → *Add playlist* opens the same form as first start. Each playlist can be turned off (its channels, favourites and history disappear until it is turned on again), renamed, given another address or login, its own user agent and update schedule, and updated on its own or with *Update all playlists*. Channel numbers continue from one playlist to the next.
- **Channel and group edits:** hold OK on a channel or group in the guide, or use Settings → Playlists → (playlist) → *Groups and channels*: hide, rename (and restore the original name), move, sort a group, change a channel's logo with an image link, choose its TV guide channel and shift its guide time. Edits survive playlist updates. Hidden channels and groups come back from *Groups and channels*.
- **Guides per playlist:** Settings → EPG lists each playlist's own guide (which can be turned off) and any guide links you add.
- **General:** a user agent for every playlist, and a UDP proxy (udpxy-style) for multicast playlists.
- **Quick menu:** while watching full screen, press ≡ or hold OK. A row of tiles along the bottom: Search, Channels, Video quality, Audio track, Audio delay (lip-sync, −1000 to +1000 ms), Subtitles, Aspect ratio (Fit, Stretch, Zoom, 16:9, 4:3, kept per channel), Sleep timer, Add to favourites, Channel options, Picture-in-picture (where the TV supports it) and Settings. Track choices last until you change channel.
- **Playback settings:** Settings → Playback: buffer size (Small, Normal, Large), hardware or software video and audio decoders, auto frame rate, surround sound by default, audio passthrough, DTS to PCM, audio delay, preferred audio and subtitle languages, subtitle size, colour, background and edge, tunneled playback, and (where supported) the Home button opening picture-in-picture.
- **Per-channel playback:** *Channel options* → video decoder, audio decoder and external player for that channel.
- **External players:** a channel can open in MX Player, VLC, Kodi or Just Player (when installed), or ask each time. Loophole stops its own stream first, so a one-connection account is not used twice. Coming back lands in the guide with nothing playing.
- **Picture-in-picture:** on TVs that support it (for example the NVIDIA Shield), from the quick menu or, if turned on, the Home button. Closing the small window stops the stream. Fire TV devices usually do not offer it, and then it is not shown.
- **Extra audio formats:** software decoders for MP2, AC3/E-AC3 and DTS sound, used only when the TV's own decoders can't play a channel's audio (see *Third-party software*).

### Local playlist files

Choose *Local file* in the playlist form. Loophole uses the TV's own file picker when it has a real one, otherwise its own simple browser (the Download folder, USB drives, and a *Loophole folder* that always works: copy the file into `Android/data/io.github.loopholetv/files/` with a file manager or adb). *Update playlist* reads the file again; if the file or the USB drive is missing, the channels stay and Settings says which copy is in use.

### MAC portals

Enter the portal address and the MAC address your provider registered (usually starting `00:1A:79:`), plus the login and device details only if your provider gave them. A portal account usually allows one device at a time.

## Install

Loophole is not in any app store. Download the latest `loophole-<version>.apk` from the project's Releases page and sideload it.

**Fire TV (Fire Stick, Fire TV Cube)**

1. Settings → My Fire TV → Developer options: turn on *Apps from Unknown Sources* (or allow the Downloader app to install apps).
2. Install the **Downloader** app from the Amazon Appstore.
3. In Downloader, enter the APK's download link and choose *Install* when the download finishes.

**Android TV / Google TV (Shield, Chromecast with Google TV, …)**

- With a computer on the same network: turn on *Developer options → USB/Network debugging*, then run `adb connect <tv-ip>:5555` and `adb install loophole-<version>.apk`.
- Without a computer: copy the APK to the TV (USB drive or a cloud drive) and open it with a file manager app, allowing that app to install unknown apps when asked.

**Optional, for the smoothest first run:** after sideloading with adb, ask Android to compile the app ahead of time (otherwise this happens gradually over the first few days):

```
adb shell cmd package compile -m speed-profile -f io.github.loopholetv
```

## Updating

Install the new APK over the old one (the same way as above). Your playlists and settings are kept. Every release is signed with the same key; if Android refuses to update ("App not installed" / signature conflict), you have a build from a different source and must uninstall it first, which removes its data.

## Building

```
./gradlew assembleDebug      # debug build
./gradlew assembleRelease    # release build (R8)
```

Release signing reads a `keystore.properties` file (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) from the path in the Gradle property `loophole.signing`, else the environment variable `LOOPHOLE_SIGNING_PROPERTIES`, else `~/.loophole-signing/keystore.properties`. Without it the release APK is built unsigned and Gradle prints a warning. Keystores and signing properties are never committed.

## Privacy

Loophole has no analytics, crash reporting or ads. The only network requests it makes are to the playlist, guide, logo and stream URLs you supply. Playlist URLs and credentials are redacted from logs and error messages.

## Licence

GPL-3.0. See [LICENSE](LICENSE).

## Third-party software

Loophole bundles the FFmpeg audio decoder for Media3 built by the Jellyfin project ([jellyfin-androidx-media](https://github.com/jellyfin/jellyfin-androidx-media)), which contains code from [FFmpeg](https://ffmpeg.org) and is licensed under the GNU General Public License v3.0. Its source code is available from that repository and from FFmpeg. See [NOTICE](NOTICE) for this and the other third-party components.
