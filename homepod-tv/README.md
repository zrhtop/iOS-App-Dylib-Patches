# HomePod Plus for Android TV (experimental)

This branch contains a TV-focused overlay on [HomePod Plus v1.5](https://github.com/M-Pennn/HomePod-Plus/releases/tag/v1.5). The upstream Apache-2.0 source is cloned by GitHub Actions. Its AirPlay sender, audio capture engine, and codec remain upstream.

## Build and download

Every push to branch `homepod-plus-androidtv` runs **Build HomePod Plus Android TV APK**. Open [Actions](https://github.com/zrhtop/iOS-App-Dylib-Patches/actions), select the latest run, and download its `HomePodPlus-TV-v1.5-debug-APK` artifact. Extract `app-debug.apk` to sideload to Android TV.

For local building (JDK 17 and Android SDK 36 required):

```bash
git clone --depth 1 --branch v1.5 https://github.com/M-Pennn/HomePod-Plus.git upstream
python3 homepod-tv/patch_tv.py --project upstream
cd upstream && ./gradlew :app:assembleDebug
```

## Intended features

- Focusable D-pad navigation and TV launcher icon/banner
- Discover and connect to HomePod, or add a manual IP address
- Single and dual HomePod mode, stereo/split channels and volume controls
- Latency, robust mode, and ALAC settings
- Foreground MediaProjection audio capture with reconnect attempts and notification controls
- Keep playback capture running while other Android TV applications are open, **if Android permits their audio to be captured**

## Important restrictions

A successful APK build does **not** guarantee that protected streaming apps (Netflix, etc.), HDMI input, system sounds, or content which disallows playback capture can be forwarded. Android's AudioPlaybackCapture rules apply. Android may revoke MediaProjection consent or end the foreground service, in which case the user must authorize capture again. Network reconnect cannot bypass permission prompts.

The TV fork is a testing build: hardware compatibility, audio sync, and long-running reconnection require real-device testing. Do not describe it as a full-system default audio output or a universally stable HomePod driver.

Original project copyright and third-party codec licenses are retained by cloning the upstream source at the pinned v1.5 tag.
