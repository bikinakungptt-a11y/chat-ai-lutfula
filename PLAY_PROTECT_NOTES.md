# Play Protect cleanup notes

This app was cleaned to reduce Play Protect risk when installing APKs outside the Play Store, without breaking gallery/file upload.

## What changed

- Kept required Android permissions:
  - `INTERNET` for AI/API calls
  - `CAMERA` for the AI Studio camera button
  - `READ_MEDIA_IMAGES` for gallery image upload on Android 13+
  - `READ_MEDIA_VIDEO` for gallery video access on Android 13+
  - `READ_MEDIA_VISUAL_USER_SELECTED` for Android 14 selected-photo compatibility
  - `READ_EXTERNAL_STORAGE` with `maxSdkVersion="32"` for gallery/file upload on Android 12 and below
- Removed unused Android permissions:
  - `RECORD_AUDIO`
  - `POST_NOTIFICATIONS`
- Removed unused notification/reminder background code:
  - `AppNotify.kt`
  - `ReminderScheduler.kt`
  - `AlarmEvent.kt`
  - `AppVisibility.kt`
- Removed startup notification permission request.
- Bumped APK version to `1.0.3` / `versionCode 4` after the Gradle CI fix.
- Debug builds now use package suffix `.debug` so they do not look like the real release app.
- GitHub Actions now uses the Gradle setup action and the `gradle` command directly, so it does not fail on a corrupt `gradle-wrapper.jar`.

## Important

For normal phone installation, use the APK artifact named `app-debug-test-only` from Android CI until release signing is configured again.
