# Tengerium

Tengerium is an Android client for connecting to MSN Messenger-compatible services (such as Escargot) using the MSNP11 protocol. It brings classic Messenger-style presence, conversations, and UI elements to modern Android devices with a Material Design interface.

> **Status:** This repository contains an Android Studio/Gradle project. The app is configured for Android 6.0 (API 23) and newer.

## Features

- **Authentication & Cascading Preferences:**
  - Classic MSN sign-in checkbox chain (*Remember Me* → *Remember my Password* → *Sign me in automatically*).
  - Secure credential storage and auto-login controls.
  - Classic MSN XP blue title bar (`#1F59B1`) and responsive login layout.
- **Messaging & Contacts:**
  - View contacts, status presence, and avatar display pictures.
  - Search roster by name or account, and chat with individual contacts.
  - Send and receive files in one-to-one chats using the MSNP11 peer-to-peer transfer flow. Transfers are limited to 25 MiB per file.
  - Send nudges with sound/vibration effects, link previews, and custom chat backgrounds.
  - Manage contact requests, groups, and avatar libraries (*Change My Picture*).
  - Sign-out confirmation dialogs and active session management.
- **Built-in Mini-Game:**
  - Single-player MSN-themed **Tic-Tac-Toe** against an AI opponent with score tracking (*You / Computer / Draws*).
- **Notifications & Privacy:**
  - In-app and system notifications with quick reply options.
  - Local database storage with SQLCipher encryption.

Feature availability depends on the server you connect to. File transfers also require the peer's server/client to support MSNP11 P2P transfers.

## Requirements

- Android Studio with Android SDK platform 34 (as configured by the app module).
- JDK 17.
- An Android device or emulator running Android 6.0 (API 23) or newer.
- An account on an MSNP11-compatible server (such as [Escargot](https://escargot.chat/)).

## Build and run

1. Clone this repository and open its root directory in Android Studio.
2. Allow Gradle to sync and download the Android dependencies.
3. Select the `app` run configuration and launch it on a device or emulator.

To build and install a debug build on a connected device with local secrets injected
from [Infisical](INFISICAL.md):

```bash
make dev
```

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

On first launch, enter the account and connection details for an MSNP11-compatible service. Tengerium is a client; it does not provide Messenger accounts or host a messaging service. An **Escargot** account (or an account on a compatible MSNP11 service such as [Escargot](https://escargot.chat/)) is required to sign in and use the app. Check with your service provider for current server, authentication, and account requirements.

## Technology

- Kotlin and AndroidX
- Material Components and Jetpack Navigation
- Room with SQLCipher-backed database support
- [msnp11-sdk](https://github.com/campos02/msnp11-sdk) for MSNP11 protocol support, including P2P file transfers (native Android libraries and UniFFI bindings generated from revision [ac0e6ed](https://github.com/campos02/msnp11-sdk/commit/ac0e6ed))
- Firebase Cloud Messaging dependencies for push notifications

## License

This project is licensed under the BSD 3-Clause License. See [LICENSE](LICENSE) for details.
