# Radio Dial

<img src="favicon.svg" alt="Radio Dial" width="80">

Simple direct-stream internet radio. No accounts, no tracking.

[Listen live](https://radio-dial.pages.dev) · [![Android build](https://github.com/aesrael/radio-dial/actions/workflows/android.yml/badge.svg)](https://github.com/aesrael/radio-dial/actions/workflows/android.yml)

- Search and filter stations, save favourites, and add stations from Radio Browser.
- The Android app supports background playback and Android Auto.

## Run locally

```sh
python3 -m http.server 8000
```

Open <http://localhost:8000>.

## Android

```sh
cd android
./gradlew assembleDebug
```

The APK is created at `android/app/build/outputs/apk/debug/app-debug.apk`.

## Stations

Edit `stations.json` or regenerate it with `python3 tools/build_stations.py`.
