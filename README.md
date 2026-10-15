# Bluetooth GPS for Android

[![Stars](https://img.shields.io/github/stars/jqssun/android-bt-gps)](https://github.com/jqssun/android-bt-gps)
[![GitHub](https://img.shields.io/github/downloads/jqssun/android-bt-gps/total?label=GitHub&logo=GitHub)](https://github.com/jqssun/android-bt-gps/releases)
[![license](https://img.shields.io/badge/License-AGPLv3-blue.svg)](https://github.com/jqssun/android-bt-gps/blob/main/LICENSE)
[![build](https://img.shields.io/github/actions/workflow/status/jqssun/android-bt-gps/apk.yml?label=build)](https://github.com/jqssun/android-bt-gps/actions/workflows/apk.yml)
[![release](https://img.shields.io/github/v/release/jqssun/android-bt-gps)](https://github.com/jqssun/android-bt-gps/releases)

A free and open-source app that turns your Android device into a Bluetooth GPS receiver for Apple devices and others. It implements the Apple accessory protocol over Bluetooth, so they can use the Android device's real-time location as if it were an external GPS accessory. This is particularly useful for pilots flying with iPad models with no cellular connectivity.

Location can come from the device's GNSS fix, its NMEA output, or a custom position. Accessory identification is adjustable for better compatibility with other devices.

[<img height="48" alt="Get it on GitHub" src="https://jqssun.github.io/images/badges/github.svg">](https://github.com/jqssun/android-bt-gps/releases/latest)

<p>
  <img height="400" alt="Android app sending a custom location" src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png">
  <img height="400" alt="iPad EFB receiving the location" src="fastlane/metadata/android/en-US/images/phoneScreenshots/EFB.PNG">
</p>

## Compatibility

- Android 6.0+ with Bluetooth
- Apple devices (including any iPad model)

## Implementation

The app listens for connections on a Bluetooth RFCOMM socket and is compatible with the iAP2 link layer and control session. After identification, location is streamed as NMEA sentences in `LocationInformation` messages whenever the Apple device requests it.

```mermaid
flowchart LR
    Location["Android (Accessory)<br/>GNSS + NMEA"]
    Session["iAP2 Link"]
    Target["Other Devices"]

    Location --> Session
    Session -- "RFCOMM" --> Target
```

```bash
./gradlew assembleDebug
```

See the [CI](https://github.com/jqssun/android-bt-gps/blob/main/.github/workflows/apk.yml) for release builds.

---

Disclaimer: This project is not affiliated with Apple Inc.
