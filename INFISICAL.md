# Secrets in Tengerium (Infisical)

Secrets manager: Infisical (project not linked yet)

## How the app is built and run locally

Tengerium is a single-module Android (Kotlin/Gradle) app. There is no `package.json` and
no dev server: local development is a Gradle build that installs the debug APK on a
connected device or emulator. The `Makefile` wraps that build with `infisical run`.

- Dev command (wrapped in `infisical run`): `make dev` → `infisical run --env=dev -- ./gradlew installDebug`
- Debug build from the command line: `./gradlew assembleDebug` (`gradlew.bat` on Windows)
- Tests: `./gradlew testDebugUnitTest connectedDebugAndroidTest`
- Production / CI entry points (left unchanged, they do not go through Infisical):
  - `.github/workflows/android.yml` runs `./gradlew test` and `./gradlew assembleDebug`
  - `./gradlew assembleRelease` builds the minimized release APK
  - Android Studio's `app` run configuration

## Environment variables the app reads

The Android application code reads **no** environment variables (`System.getenv` is not
used; the only `System.getProperty` call is inside the bundled uniffi/msnp11-sdk runtime
for a test-only library override). Firebase configuration is compiled in from the
checked-in `app/google-services.json`, and `local.properties` only holds `sdk.dir`.

Because of that, the Infisical project has no secrets to inject today. The names below
are reserved so that local runs already receive them once the app configuration moves
out of the repository; nothing reads them yet.

- `FIREBASE_API_KEY`
- `FIREBASE_PROJECT_ID`
- `FIREBASE_PROJECT_NUMBER`
- `FIREBASE_APP_ID`
- `FIREBASE_STORAGE_BUCKET`
- `ANDROID_KEYSTORE_PATH`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

## Finish setup

1. Create a project for this service at <https://app.infisical.com> (name it `Tengerium`, keep the default `dev` / `staging` / `prod` environments).
2. Install the Infisical CLI:
   - macOS: `brew install infisical/get-cli/infisical`
   - Windows: `scoop bucket add org https://github.com/Infisical/scoop-infisical.git` then `scoop install infisical`
   - Any platform: `npm install -g @infisical/cli`
3. `infisical login`
4. `infisical init` in this folder (it writes `.infisical.json` with the project id; that file holds no secrets and is safe to commit)
5. Drag your `.env` onto the project's **Secrets Overview** page with the `dev` environment selected: every key is imported at once
6. `make dev` (or `infisical run --env=dev -- ./gradlew installDebug`) to confirm the app starts with injected values
7. Move `.env` **out of the repository folder**, for example `mv .env ~/.config/tengerium/.env`, then run `make dev` again to prove the values come from Infisical
8. For CI and production builds, create a machine identity with Universal Auth and provide `INFISICAL_UNIVERSAL_AUTH_CLIENT_ID` / `INFISICAL_UNIVERSAL_AUTH_CLIENT_SECRET` through the platform's secret store instead of the interactive CLI
9. Rotate any secret that was ever committed to git: it stays in history after deletion, and `infisical scan` finds leaked keys

`app/google-services.json` is committed and contains a Firebase API key for this app;
treat that key as exposed, rotate it in the Firebase console and move the configuration
into Infisical when convenient.
