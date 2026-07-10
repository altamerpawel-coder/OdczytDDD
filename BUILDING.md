# Budowanie APK

## Android Studio

1. Otwórz katalog `OdczytDDD` jako projekt.
2. Użyj JDK 17.
3. Zainstaluj Android SDK Platform 36 i Build Tools 36.0.0.
4. Wybierz **Build > Build APK(s)**.
5. Debug APK pojawi się w `app/build/outputs/apk/debug/app-debug.apk`.

Projekt używa:

- Android Gradle Plugin 9.2.0,
- compileSdk 36,
- targetSdk 36,
- minSdk 23,
- Java 17.

## GitHub Actions

W repozytorium jest workflow `.github/workflows/build-apk.yml`. Po wysłaniu projektu do GitHub uruchomi budowę debug APK i zapisze je jako artefakt `OdczytDDD-debug-apk`.
