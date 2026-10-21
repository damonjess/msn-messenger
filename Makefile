# Local development: build the debug APK and install it on the connected
# device/emulator with the Infisical `dev` secrets injected into Gradle.
# Requires the Infisical CLI and a linked project - see INFISICAL.md.
# CI and release builds do not use this target.
.PHONY: dev

dev:
	infisical run --env=dev -- ./gradlew installDebug
