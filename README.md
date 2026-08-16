<p align="center">
  <img src="assets/icon.png" width="168" alt="AOT Optimizer">
</p>

<h1 align="center">AOT Optimizer</h1>

<p align="center">
  Manual ART / AOT control for rooted Android.<br>
  Compile now. Lock the mode. Catch drift after updates.
</p>

<p align="center">
  <a href="https://github.com/xModern54/AOT-Optimizer/actions/workflows/android.yml">
    <img src="https://github.com/xModern54/AOT-Optimizer/actions/workflows/android.yml/badge.svg" alt="Android CI">
  </a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/Root-Magisk%20%2F%20KernelSU-FF1744" alt="Root required">
  <img src="https://img.shields.io/badge/Target-API%2034-00E5FF" alt="Target API 34">
  <img src="https://img.shields.io/badge/Kotlin-2.0.20-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin">
</p>

---

Instead of waiting for idle maintenance windows or Cloud Profiles, AOT Optimizer talks to ART directly: force `speed`, `everything`, `speed-profile`, or `quicken` on the apps you care about. A **contract** remembers that choice. If an update or the system resets the filter, the app flags **drift** and can restore it.

## Features

| | |
|:--|:--|
| **Force compilation** | `speed`, `everything`, `speed-profile`, `quicken` — immediately, not overnight. |
| **Contracts** | Locks the preferred filter per app. Manual contracts survive a global batch. |
| **Drift detection** | Catches updates and system resets, then restores the saved mode. |
| **New-app sheet** | New installs stay in a pending list until you compile them or ignore them. |
| **System Guard** | Disables `bg-dexopt-job` and sets `pm.dexopt.disable_bg_dexopt` so the OS does not overwrite your work. |
| **Metrics** | Artifact size, compile status (worst of primary + secondary dex), code complexity. |

## Screenshots

<p align="center">

| Dashboard | Modes | Batch |
|:---:|:---:|:---:|
| <img src="assets/MainScreen.jpg" width="240" alt="Dashboard"> | <img src="assets/FullSystemOptimizeSelect.jpg" width="240" alt="Optimization modes"> | <img src="assets/FullSystemOptimizeProc.jpg" width="240" alt="Batch processing"> |

</p>

## Requirements

- **Root** — Magisk or KernelSU, for `cmd package compile`
- **Android 8.0+** (minSdk 26), targeted at Android 14 (API 34)

## Build

| Component | Version |
|:---|:---|
| Gradle | 8.9 |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.20 |
| JDK | 17 |
| minSdk / targetSdk | 26 / 34 |

1. Install **JDK 17** and set `JAVA_HOME`.
2. Point Gradle at your SDK with `local.properties` (do not commit this file):

```properties
sdk.dir=/path/to/your/android/sdk
```

3. Assemble the release APK:

```bash
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

The repo signs release with the debug keystore for convenience. Use your own keystore for a real distribution build.

## GitHub Actions

Every push and pull request to `main` runs [Android CI](https://github.com/xModern54/AOT-Optimizer/actions/workflows/android.yml): JDK 17, SDK, `assembleRelease`, then uploads **AOT-Optimizer** as an artifact.

- **Actions → Android CI → Run workflow** — build on demand
- Tag `v*` (for example `v1.0.0`) — publish a GitHub Release with the APK
