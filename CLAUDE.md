# Notes for Claude Code (Jellyarr)

## Releases: always publish the app
The owner wants every app build published as a GitHub **release**, not left as a zip artifact (a zip of a
150 MB APK is hard to extract on a phone).

- CI does it: `.github/workflows/apk.yml` builds the debug APK and publishes a pre-release
  (`build-<run>-<version>`, raw `.apk` + `.sha256`) on every push to `feature/**`, `ccr-*` and `claude/**`.
- After pushing, wait for that run, check it succeeded, then give the user the link to the release
  (https://github.com/MedRAM-RAM/Jellyarr/releases) and the APK size. Do this in every conversation.
- Never describe the Actions artifact as the way to get the app. If the run fails, read its logs and fix it.
- Stable releases are the owner's call (`release.yml`, tags `v*`); do not create those unasked.
- **Always bump the version** (`versionCode` and `versionName`) before publishing a build. The app does not treat
  a build with the same version as new, so the update bar and the cancel-download feature misbehave otherwise.
- **While an update is downloading, show that it is in progress** ("جاري التنزيل" / "downloading") in the update bar.

## Working here
- Build check without a device: `./gradlew :app:compileDebugJavaWithJavac` (needs the Android SDK; set
  `sdk.dir` in `local.properties`, which is git-ignored).
- The app targets ARM64 only; services (Jellyfin, Sonarr, Radarr, Prowlarr, Transmission, TorrServer,
  cloudflared) run as native processes managed by `MediaServiceManager`.
- Stremio addon: `services/StremioBridge.java` (port 7070, secret key in every URL). Everything goes through
  that one port: Jellyfin/TorrServer streams are proxied, the Jellyfin token and API keys (SubDL) must never
  appear in a URL or a log line. Test it against fake Jellyfin/Prowlarr/TorrServer servers; real Cinemeta and
  OpenSubtitles work from the sandbox once their hosts are allowed.
- `cloudflared` is built in CI from upstream plus `ci/cloudflared/jellyarr_android_dns.go` (Android has no
  resolv.conf) and shipped as an APK asset.
- Arabic UI strings live in `app/src/main/assets/i18n_ar.json` (one line; edit it with a JSON tool).
- Subtitle sync (`services/SubtitleSync.java`): aligns a candidate to a reference by comparing when speech happens
  (language independent), FFT cross-correlation, then Theil-Sen refinement; applied only with a strong, clearly
  better match, otherwise the file is returned unchanged. The reference is never guessed: only the library file,
  a hash-matched subtitle or a subtitle inside the torrent. Keep `SyncTest`-style synthetic tests (known offset,
  fps ratio, cuts, unrelated and partial files) green when changing it.
