# SysHush

**System Mode Control for Android**

SysHush is a native Android utility that helps you quiet the status bar and manage eligible running user apps from one place. It uses Android's Accessibility Service to navigate the device's own Settings and App Info screens—without root access or direct modification of SystemUI.

The Android app is built with **Kotlin, Jetpack Compose, Android Accessibility APIs and Preferences DataStore**.

> **Compatibility note:** Android Settings screens are implemented differently by each manufacturer and can change between OS releases. SysHush uses label matching and accessibility-node inspection to support multiple device families, but automation is necessarily best-effort on OEM variants that expose different controls.

## Features

### Status bar control

- Hide the status-bar clock by selecting the device's **Do not show** option when available.
- Turn off the real-time network-speed indicator when the device exposes that setting.
- Discover available controls in the device's **Status icons** settings page and turn off supported icons.
- Save the original state of discovered controls before changing them.
- Restore the saved baseline instead of blindly turning every icon back on.
- Inspect for a wired-headphone visibility control. When the OEM does not expose one, SysHush reports the indicator as system-managed rather than attempting an unsafe workaround.

### Running-app management

- Find eligible third-party user applications.
- Exclude system applications and important system roles such as the current launcher, active keyboard, default dialer, default SMS handler, SystemUI and SysHush itself.
- Let the user protect applications with a persistent whitelist.
- Use the device's native **App info → Force stop** flow and confirmation dialog.
- Verify stop state and retry apps that remain unstopped.

Android controls which running-process details are visible to ordinary apps. App discovery and force-stop coverage can therefore vary by Android version, device policy and manufacturer.

### Three operating modes

| Mode | Action |
| --- | --- |
| **Status Bar Only** | Hide the supported status-bar information and later restore its saved baseline. |
| **Force Stop Apps Only** | Run the eligible-app force-stop workflow without engaging the status bar. |
| **Both** | Perform both workflows as one operation. |

Stopping an app is not the same as uninstalling or disabling it. **Disengaging SysHush restores the status-bar baseline; it does not automatically relaunch apps that were force-stopped.** Open those apps normally when you need them again.

### Other controls

- Live operation progress, step checklist and result counters.
- Persistent settings backup and backup-detail view.
- Main Hush Button home-screen widget.
- Separate home-screen actions for stopping apps and toggling status-bar control.
- OEM family detection for ColorOS/OxygenOS/Realme UI, Samsung One UI, Xiaomi HyperOS/MIUI, Pixel/AOSP and generic Android devices.

## Screenshots and project preview

The repository also includes a small React/Vite-based project explorer. It provides an interactive phone/settings simulation, architecture notes, a source-code explorer and an Android project ZIP export. This is a development/documentation companion; **it is not the Android application and does not perform device automation**.

The native Android project is in the `android/` directory.

## Requirements

| Item | Requirement |
| --- | --- |
| Minimum Android version | Android 8.0 (API 26) |
| Compile SDK | Android 15 / API 35 |
| Target SDK | API 35 |
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Java toolchain | JDK 17 |
| Gradle | 8.9 (wrapper) |
| Android Gradle Plugin | 8.7.2 |
| Kotlin plugin | 2.0.21 |

SysHush does not require root access. It also does not declare the `INTERNET` permission in its Android manifest.

## Permissions and privacy

SysHush needs the following Android capabilities for its current features:

| Permission / access | Why it is used |
| --- | --- |
| **Accessibility Service** (enabled manually in Android Settings) | Inspects the relevant Settings and App Info accessibility trees, navigates those screens, activates controls and confirms force-stop actions. |
| `QUERY_ALL_PACKAGES` | Enumerates installed applications for the app list, eligibility filtering and whitelist management. |
| `POST_NOTIFICATIONS` | Displays operation progress and completion notifications on Android versions that require notification permission. |

The Accessibility Service is the mechanism that makes the no-root workflow possible. Enable it only after reviewing the in-app explanation and deciding that you are comfortable granting this access. It is intended to run automation only after an action is requested by the user.

The manifest currently requests **no Internet permission**, and the app has no cloud account or remote service as part of its Android automation design. The accessibility service uses the device's Settings and App Info interfaces to carry out the requested actions; it is not intended to collect keystrokes or personal content from other apps.

Notification permission is for progress reporting. Denying it can hide progress notifications; it does not grant or replace Accessibility access.

## Getting started

1. Install the SysHush APK built from this repository.
2. Open SysHush and read the Accessibility explanation.
3. Open Android **Settings → Accessibility** and enable **SysHush Assistant**.
4. Return to SysHush.
5. Choose **Status Bar Only**, **Force Stop Apps Only** or **Both**.
6. Review the protected-app list and whitelist apps that should never be included in a force-stop batch.
7. Start the operation and review its progress and final result.

The exact Accessibility Settings path and wording vary by manufacturer. You can use the in-app manual Settings shortcut if your device organizes these options differently.

## Build from source

### Prerequisites

- Android Studio or the Android command-line SDK.
- JDK 17.
- Android SDK Platform 35 and Build Tools 35.0.0.

### Build and test

From the repository root:

**macOS / Linux**
```bash
cd android
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

**Windows PowerShell / Command Prompt**
```bat
cd android
gradlew.bat testDebugUnitTest
gradlew.bat lintDebug
gradlew.bat assembleDebug
```

The debug APK is generated at:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

To build the release variant:

```bash
cd android
./gradlew assembleRelease
```

**Release-signing warning:** The current `android/app/build.gradle.kts` config assigns the debug signing configuration to the release build. The resulting release APK is useful for build verification, but it is **not a production-signed release**. Configure a private release keystore and signing properties before distributing a production APK or publishing to an app store. Never commit signing keys or passwords.

The debug variant adds the `.debug` application ID suffix. The current release application ID is `com.statusbarmanager.app`. These identifiers are intentionally documented as they exist in the source; changing the display name to SysHush does not change the installed package identity.

## Continuous integration

The GitHub Actions workflow at `.github/workflows/android-debug-apk.yml` runs on pushes and pull requests that modify `android/**`, and can also be started manually.

It provisions JDK 17, Android SDK 35 and Gradle 8.9, then runs:

- Debug unit tests.
- Android debug lint.
- Debug APK build.
- Release APK build.

Successful workflow runs upload debug and release APK artifacts, plus lint reports, for a limited retention period. Remember that the current release artifact uses the debug signing configuration described above.

## Project structure

```text
.
├── android/
│   ├── app/
│   │   └── src/main/
│   │       ├── java/com/statusbarmanager/app/
│   │       │   ├── automation/     # Operation coordination and app/Settings automation
│   │       │   ├── data/           # DataStore and original-settings backup
│   │       │   ├── service/        # Accessibility service and progress notifications
│   │       │   ├── ui/             # Jetpack Compose screens and theme
│   │       │   └── widget/         # Home-screen widget providers
│   │       └── res/                # Android resources, widget layouts and service config
│   ├── gradle/                     # Version catalog and wrapper configuration
│   └── gradlew
├── .github/workflows/              # Android CI workflow
├── src/                            # React/Vite project explorer and simulator
├── public/                          # Companion project assets
└── metadata.json                   # Companion project metadata
```

The package namespace and source directory still use the original `com.statusbarmanager.app` identifier. That is an internal Android package name, not the product display name.

## Compatibility and limitations

- **OEM-dependent Settings:** Automation relies on accessible labels and controls in the manufacturer's Settings app. A system update, OEM redesign, language change or different Settings package can require compatibility adjustments.
- **Only exposed controls can be changed:** SysHush cannot hide an indicator if the device offers no user-accessible setting for it. Wired-headphone indicators are a known example on devices where SystemUI manages the icon.
- **No direct SystemUI modification:** SysHush does not patch Android framework code or directly edit SystemUI. It uses the visible native Settings workflow.
- **Process visibility varies:** Android may limit which running processes a normal app can inspect. Force-stop results are verified and retried, but not every app or device can be guaranteed to behave identically.
- **Force-stopped apps stay stopped:** SysHush does not relaunch apps during restoration.
- **User review matters:** Review the whitelist before running a force-stop batch. Keep messaging, navigation, accessibility, alarm or other important apps protected when appropriate.

## Troubleshooting

**The app says Accessibility is disabled**

Open Android Settings → Accessibility and enable **SysHush Assistant**. Return to the app so it can refresh the permission state.

**Status-bar automation cannot find a setting**

Confirm that your device has a corresponding Settings control. OEMs may rename, relocate or remove the relevant menu. Share the device manufacturer/model, Android version and the operation log when reporting a compatibility issue.

**Some icons remain visible**

Only icons with a supported, user-accessible toggle can be changed. Some indicators are controlled by SystemUI or other system services.

**An app was not force-stopped**

Check whether it is a system/protected app, already stopped, whitelisted, or subject to device/Android restrictions. Review the operation summary before retrying.

## Contributing and reporting issues

Bug reports are most useful when they include:

- Device manufacturer and model.
- Android version and OEM skin version.
- Selected SysHush operating mode.
- Which step failed and whether the result was partial.
- Relevant logcat output, with personal information removed.
- Screenshots of the relevant Settings page where appropriate.

Please avoid sharing private information, notification contents or unrelated personal screen data.

## License

No license file is currently included in this repository. Until a license is added, do not assume that the source is available for unrestricted reuse, redistribution or modification.
