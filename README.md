<p align="center"><img src="art/jellyarr_logo_v11-1_dark.svg" width="112" alt="Jellyarr logo" /></p>

<h1 align="center">Jellyarr</h1>

<p align="center">
  <b>Jellyfin server and a complete *arr media stack in one Android app (ARM64)</b><br>
  <i>Runs natively on the phone: no root, no containers, no cloud.</i>
</p>

<p align="center">
  <a href="https://github.com/MedRAM-RAM/Jellyarr/releases"><img src="https://img.shields.io/github/v/release/MedRAM-RAM/Jellyarr?include_prereleases&style=flat-square&label=Release&color=00A4DC" alt="Latest release" /></a>
  <a href="https://github.com/MedRAM-RAM/Jellyarr/actions/workflows/build.yml"><img src="https://img.shields.io/github/actions/workflow/status/MedRAM-RAM/Jellyarr/build.yml?style=flat-square&logo=githubactions&logoColor=white&label=CI" alt="CI" /></a>
  <img src="https://img.shields.io/badge/Platform-Android%20ARM64-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android ARM64" />
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL%20v3.0-F1C40F?style=flat-square" alt="GPLv3" /></a>
</p>

---

## What it is

Jellyarr turns an Android phone or TV box into a home media server. It hosts **Jellyfin** for streaming and the
companion services that find, download and organize your media, and wires them together for you.

| Service | Port | Role |
| :--- | :--- | :--- |
| **Jellyfin** | `8096` | Media server: libraries, streaming, hardware transcoding |
| **Sonarr** | `8989` | TV shows: monitoring, search, import and renaming |
| **Radarr** | `7878` | Movies: monitoring, search, import and renaming |
| **Prowlarr** | `9696` | Indexer manager, synced to Sonarr and Radarr |
| **Transmission** | `9091` | BitTorrent download client |
| **TorrServer** *(optional)* | `8090` | Streams torrents over HTTP while they download |
| **Jellyarr addon** *(optional)* | `7070` | Stremio addon for AIOStreams and Stremio (built into the app) |
| **Cloudflare Tunnel** *(optional)* | `20244` | Runs `cloudflared` inside the app to give the addon a public HTTPS address |

All services listen on the phone and on your local network only.

## Features

- **One app, one account.** A single username and password for Jellyfin, Sonarr, Radarr, Prowlarr and
  Transmission (Settings › Security & Identity). With an account set, the web interfaces always ask for it, so
  signing out really signs out.
- **Built-in clients.** Full Sonarr, Radarr, Prowlarr and Transmission screens inside the app (library, add,
  interactive search, calendar, queue, history, wanted, system), plus a Jellyfin library browser.
- **SYNC STACK.** One tap connects everything: download folders, Transmission as the download client,
  Prowlarr indexers pushed to Sonarr/Radarr, API keys and existing media.
- **Media storage.** Movies and TV libraries as Jellyfin sees them, with their folders, sizes and the drive
  they live on. Move a library folder to another path or another drive; Jellyfin, Radarr and Sonarr are
  updated automatically.
- **File management.** Radarr and Sonarr naming and import options in one place, *Organize* (rename every file to
  the current format) and import from a folder with Move or Copy.
- **Stremio addon.** Built into the app (Settings › Stremio & AIOStreams). Add its URL to Stremio, or to any
  app that takes Stremio addons, and you get your Jellyfin library with direct play, Popular and Featured
  catalogs (Popular, Featured, New by year; genres, search, a 📁 badge on what you already own, plus any other
  Stremio catalog addon you add), torrents from Prowlarr streamed through TorrServer, and subtitles from Jellyfin,
  SubDL (your own free API key), OpenSubtitles and any other Stremio subtitle addon, in the languages you pick,
  with the ones that match the playing file first (old Arabic/Hebrew/Cyrillic files are converted to UTF-8).
  Everything (video, subtitles, posters, torrents) is served through the addon's single port, and Jellyfin's key
  never appears in a URL. Set an optional public HTTPS address (Cloudflare Tunnel, Tailscale Funnel...) to use
  it from apps that are not on your home network, such as a hosted AIOStreams. With a public address set, local
  files also get a 🏠 LAN entry that streams over the home network instead of looping through the internet. The app can run the Cloudflare
  Tunnel itself: paste your tunnel token in the same settings page. `cloudflared` is built in CI from the upstream
  source plus a small DNS patch for Android (`ci/cloudflared`) and shipped inside the APK.
- **Automation.** Transmission auto-cleanup and auto-recheck, automatic missing-episode search, Wi-Fi-only mode.
- **Updates in the app.** Stable and beta channels, background download, install from the notification.
- **Backup & restore** of every service configuration and database.
- **Arabic and English**, full right-to-left layout, Material 3 themes including AMOLED black and dynamic colours.
- **Home screen widgets** and a quick-settings tile.

## Requirements

- 64-bit ARM device (`arm64-v8a`), Android 7.0 or newer (Android 11+ recommended)
- 4 GB RAM minimum, 6 GB or more to run every service at once
- About 1 GB free internal storage for the runtime

## Installation

1. Download the latest APK from [Releases](https://github.com/MedRAM-RAM/Jellyarr/releases).
2. Install it and allow:
   - **All files access**, so the services can read and write your media folders;
   - **Unrestricted battery usage**, so Android does not stop the server in the background.
3. Open Jellyarr and wait for the first-run setup (the runtime is unpacked once).
4. Create the unified account when asked, then tap **START SERVER**.
5. Install the companion services from the **Media** tab and tap **SYNC STACK**.

Jellyfin is then available at `http://<phone-ip>:8096` from any device on your network.

### Moving from the old package (2.x)

Since 3.0.0 the app id is `com.jellyarr.app` (it was `com.fishbowl.app`), so Android installs 3.0.0 as a new app and
the old one cannot update itself to it:

1. In the old app, make a backup (**Settings › Backup**) and stop all services.
2. Install Jellyarr 3.0.0 and restore the backup when the first start offers it.
3. Uninstall the old app.

Media in `JellyarrMedia` is not touched. Paths that point inside the old app's data folder are switched to the new
package during the restore.

## Storage layout

```text
/storage/emulated/0/JellyarrMedia/
├── Movies/      Radarr and Jellyfin movies
├── TV/          Sonarr and Jellyfin TV shows
├── Downloads/   Transmission download staging
└── Backups/     configuration and database backups
```

Folders on an SD card or USB drive can be added as library paths and media can be moved between drives from
**Media › Media storage**. Installs that still use the old `FishbowlMedia` folder are renamed automatically on
first launch.

## Building from source

```bash
git clone https://github.com/MedRAM-RAM/Jellyarr.git
cd Jellyarr

./gradlew testDebugUnitTest   # unit tests
./gradlew assembleDebug       # debug APK (arm64-v8a)
```

Requirements: JDK 17+, Android SDK 36, NDK 29. Every push is built by GitHub Actions; pre-releases are published by
the *Build APK (Jellyarr)* workflow.

## Legal

Jellyarr does not host, index or distribute any media. The companion services are general-purpose tools; you are
responsible for using them only with content you are allowed to download and keep.

Jellyarr is an independent project and is not affiliated with Jellyfin, the Servarr projects, Transmission,
Termux, Microsoft or Google.

## Credits

- Based on [Fishbowl](https://github.com/Suz41/Fishbowl) by Sujal Achari (GPLv3).
- [Jellyfin](https://jellyfin.org), [Sonarr, Radarr and Prowlarr](https://wiki.servarr.com),
  [Transmission](https://transmissionbt.com), [Termux](https://termux.dev) and [.NET](https://dotnet.microsoft.com).

## License

[GNU General Public License v3.0](LICENSE). Third-party notices are in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).
