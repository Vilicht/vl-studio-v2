# Vilicht Live Studio

Aplikasi live streaming game Android (Kotlin, Jetpack Compose, Room, RootEncoder 2.7.0).
Package/applicationId: `com.vilicht.livestudio`.

## Build

Syarat: Android Studio (Ladybug atau lebih baru), JDK 17+, Android SDK sesuai `compileSdk` di `app/build.gradle.kts`.

```
./gradlew assembleDebug        # APK debug: app/build/outputs/apk/debug/
./gradlew assembleRelease      # butuh keystore (lihat app/build.gradle.kts / workflow CI)
```

CI: `.github/workflows/build-apk.yml` membangun APK di GitHub Actions (gunakan ini jika tidak punya Android Studio).
Update aplikasi memakai package name dan signing key yang sama, jadi data pengguna (Room v2, migrasi 1→2) tetap aman.

Detail audit dan perubahan: lihat `docs/AUDIT_DAN_PERUBAHAN.md`.
