# Archive Saver Android MVP

This folder contains a native Android client that receives shared URLs, opens the page inside a `WebView`, extracts the rendered HTML, and posts it to the existing Flask backend at `/api/save-html`.

## MVP flow

1. Share an FMKorea URL from Samsung Internet.
2. Choose `Archive Saver`.
3. The app opens the page in `WebView`.
4. After the page finishes loading, the app prepares lazy media, captures the full HTML, and sends it to the existing backend with `collectionTitle = "축구"`.

## Open in Android Studio

- Open the `android/` folder as a project.
- Install Android SDK 35 and Android Studio with JDK 17 support.
- The backend base URL is currently hard-coded through `BuildConfig.ARCHIVE_API_BASE_URL` in `app/build.gradle.kts`.

## Current limitations

- This MVP reuses the existing backend for Dropbox and Raindrop storage.
- If FMKorea blocks direct media file downloads from the backend IP, the next step is to move image/video downloading into the Android app as well.
- The Gradle wrapper is configured for Gradle 8.10.2, which matches AGP 8.8 requirements.
