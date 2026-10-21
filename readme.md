# Tengerium

Tengerium is an Android client for connecting to MSN Messenger-compatible services using the MSNP11 protocol. It brings classic Messenger-style presence and conversations to modern Android devices, with a Material Design interface.

> **Status:** This repository contains an Android Studio/Gradle project. The app is configured for Android 6.0 (API 23) and newer.

## Features

- Sign in to an MSNP11-compatible service and manage your online status.
- View contacts and presence, search the roster by name or account, and chat with individual contacts.
- Manage contact requests, profile details, display pictures, and chat settings.
- Receive message notifications and reply from notifications.
- Store chat and app data locally, with encrypted database support.
- Customize the app's appearance and review data/storage and connection information.

Feature availability depends on the server you connect to.

## Requirements

- Android Studio with Android SDK platform 34 (as configured by the app module).
- JDK 17.
- An Android device or emulator running Android 6.0 (API 23) or newer.

## Build and run

1. Clone this repository and open its root directory in Android Studio.
2. Allow Gradle to sync and download the Android dependencies.
3. Select the `app` run configuration and launch it on a device or emulator.

To build a debug APK from the command line:

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. On Windows, use `gradlew.bat assembleDebug`.

To run the available tests:

```bash
./gradlew testDebugUnitTest connectedDebugAndroidTest
```

`connectedDebugAndroidTest` requires a connected Android device or emulator.

## Connecting

On first launch, enter the account and connection details for an MSNP11-compatible service. Tengerium is a client; it does not provide Messenger accounts or host a messaging service. Check with your service provider for current server, authentication, and account requirements.

## Technology

- Kotlin and AndroidX
- Material Components and Jetpack Navigation
- Room with SQLCipher-backed database support
- [msnp11-sdk](https://github.com/campos02/msnp11-sdk) for MSNP11 protocol support
- Firebase Cloud Messaging dependencies for push notifications

## License

This project is licensed under the BSD 3-Clause License. See [LICENSE](LICENSE) for details.
