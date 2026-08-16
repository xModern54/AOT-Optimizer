# AOT Optimizer
Android app for rooted Android devices for manually managing AOT compilation and optimization of installed apps.

## What it does
AOT Optimizer gives you granular control over the Android Runtime (ART) compilation process. Instead of waiting for the system to optimize apps during idle maintenance windows (or via Cloud Profiles), you can force specific compilation modes immediately. It includes a "Contract" system that remembers your optimization preferences for each app and detects "drift"—when an app update resets your optimization—allowing for easy restoration.

## Features
- **Force Compilation**: Manually compile apps using modes like `speed`, `everything`, `speed-profile`, or `quicken`.
- **Drift Detection**: Automatically detects when optimized apps have been updated or reset by the system.
- **Contract System**: "Locks" your preferred optimization mode for specific apps.
- **Batch Operations**: Restore all drifted optimizations or apply a strategy to all new apps in one go.
- **System Guard**: Option to disable the native `bg-dexopt-job` to prevent the OS from overwriting your changes.
- **Detailed Metrics**: Displays artifact size (disk usage) and code complexity estimates.

## Screenshots

| Dashboard | Optimization Selection | Batch Processing |
|:---:|:---:|:---:|
| ![Dashboard](assets/MainScreen.jpg) | ![Modes](assets/FullSystemOptimizeSelect.jpg) | ![Terminal](assets/FullSystemOptimizeProc.jpg) |

## Requirements (runtime)
- **Root Access**: Required (Magisk or KernelSU) to execute `cmd package compile`.
- **Android 8.0+** (MinSDK 26). Targeted for Android 14 (API 34).

## Build from source (RELEASE)

### Versions (from this repo)
| Component | Version | Source |
| :--- | :--- | :--- |
| **Gradle** | 8.9 | `gradle-wrapper.properties` |
| **Android Gradle Plugin** | 8.7.3 | `build.gradle.kts` |
| **Kotlin** | 2.0.20 | `build.gradle.kts` |
| **Target SDK** | 34 | `app/build.gradle.kts` |
| **Min SDK** | 26 | `app/build.gradle.kts` |
| **JDK** | 17 (Required) | AGP 8.x Requirement |

### Setup
1.  **JDK**: Ensure you have JDK 17 installed and set as `JAVA_HOME`.
2.  **Android SDK**: Create a `local.properties` file in the project root (do not commit this file):
    ```properties
    sdk.dir=/path/to/your/android/sdk
    # Example (macOS): /Users/username/Library/Android/sdk
    # Example (Linux): /home/username/Android/Sdk
    ```

### Build (release)
To build the release APK:

```bash
./gradlew assembleRelease
```

**Output location:**
`app/build/outputs/apk/release/app-release.apk`

### Verification
The build process generates a signed APK using the debug keystore configured in `app/build.gradle.kts` for simplicity. For production use, configure your own signing keys.

## GitHub Actions
Every push and pull request to `main` runs `.github/workflows/android.yml`: JDK 17, Android SDK, `assembleRelease`, then uploads `app-release.apk` as the **AOT-Optimizer** artifact.

A tag `v*` (for example `v1.0.0`) also publishes a GitHub Release with that APK. Manual runs are available via **Actions → Android CI → Run workflow**.