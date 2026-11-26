# is250-obd

Android app for the aftermarket head unit (UIS8141E, Android 8.1) in a 2008 Lexus IS250.

**Stage 1 – probe:** a read-only app that reports what the head unit exposes: serial ports, CAN box / MCU apps and services, USB and Bluetooth devices, system properties. It changes nothing on the unit.

**Stage 2 – OBD dashboard:** built once the probe shows how the CAN box data can be reached.

## Install on the head unit

Open this link in the head unit's browser (over a phone hotspot), download, and tap the file to install. You may need to allow "Install unknown apps" for the browser.

https://github.com/D1Mayback/is250-obd/releases/latest/download/is250-obd.apk

Every push to `main` builds a new APK with GitHub Actions and publishes it as the latest release, so the link above always gives the newest build. Builds are signed with the same key, so new versions install over old ones.
