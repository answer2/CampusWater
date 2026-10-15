# CampusWater

[中文](README.md) | **English**

A third-party Android client for campus water-control meters. It talks to the meter over Bluetooth to
open and close the valve, and uses the "趣智校园" (QuZhi Campus) backend for login, billing and
settlement. The whole UI was rewritten on Jetpack Compose + Miuix, with an optional liquid-glass look.

> **This is not an official app.** It reuses the operator's API endpoints and signing rules in a way
> that is publicly reproducible. Billing, valve control and settlement are all decided server-side;
> this app bypasses nothing and offers no way to avoid being charged.
> Using it may conflict with the terms your school or the operator imposes — at your own risk.
> Whenever behaviour differs from the official mini program, the official server is authoritative.

## Features

**Water usage**

- Scan for nearby Bluetooth water-control devices and open/close the valve from the device screen
- Idle auto-shutoff countdown: starts when water stops, shows a confirmation dialog when it expires,
  letting you either resume (restarts the timer) or end the session
- Configurable timeout policy: follow the idle limit pushed by the server, or set 1–60 minutes
  yourself; choose between "notify only" and "send the close-valve command automatically" when it expires
- Allowed time windows are listed individually, with a progress bar on the current one
  (minutes elapsed / minutes remaining)
- The server supplies the permitted windows per device type; being outside a window warns you but does
  not block you (same as the official client)

**Background monitoring and notifications**

- Monitoring runs in a foreground service: closing the app or swiping it away from recents does not
  stop it, and the timeout auto-shutoff still fires
- A persistent notification with a live timer and an "End usage" action while water is running
- Notifications on session end, timeout auto-shutoff and valve-open failure
- In-app banners for valve-open failure / device busy / timeout shutdown; the banner channel can be
  turned off on its own (events then go silently to the notification list)

**Bills and settlement**

- After the valve closes, the actual settled amount for that order is queried from the server, and the
  end notification reports both the cost and the duration
- Bills browsed by month, with device-type filter, sort by time or amount, and an amount-range filter
- Monthly total, last-7-days spend, and average per transaction

**Account**

- SMS verification-code login (unregistered numbers are auto-created by the server); password login also
  supported
- Switchable client identity (some projects only accept the Alipay client)
- Project ID is editable; credentials and account details are stored on-device only
- The Terms & Statement must be accepted before signing in

**Interface**

- Two switchable styles: Miuix (HyperOS) and liquid glass (Apple's design language)
- In liquid-glass mode: the bottom bar floats as a glass capsule, the toolbar is transparent and blurs
  the content scrolling beneath it, switches follow the iOS design, and dialogs use Apple's alert layout
- Preset accent colours, or pick any colour on an HSV palette; follow-system-dark, manual dark and a
  pure-white light theme are all available
- The runtime log lives in the profile screen, where it can be copied or cleared

## Building

Requirements:

| Component | Version |
| --- | --- |
| Android Gradle Plugin | 9.2.1 |
| Kotlin | 2.3.21 |
| Jetpack Compose | 1.12.0 |
| compileSdk / targetSdk / minSdk | 37 / 36 / 26 |
| JDK | 17+ |

```bash
# A local Android SDK is required; set sdk.dir in local.properties
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

Two gotchas you must not "fix" (both are already configured in this repo):

1. `android.builtInKotlin=false` in `gradle.properties` — the Kotlin version bundled with AGP cannot read
   backdrop 2.0.1's 2.3 metadata, so the build falls back to the external KGP and `app/build.gradle.kts`
   applies `kotlin.android` and `compose.compiler` explicitly.
2. `android.newDsl=false` in the same file — the external KGP is incompatible with AGP 9's new DSL, so the
   module build scripts use the classic DSL.

`io.github.kyant0:backdrop` requires the Compose 1.12 / Kotlin 2.3 tier. Miuix 0.7.2 was compiled against
Compose 1.9.3 but works fine in this combination.

## Permissions

Requested only where a feature needs them: Bluetooth (scanning and connecting to the meter), Nearby
devices (Android 12+), and Notifications (the persistent status entry and alerts). No analytics SDK, and
no access to contacts, location, photos or other unrelated permissions.

## Layout

```
app/src/main/java/dev/answer/campuswater/
├── api/         Server endpoints, signing, money and bill parsing
├── ble/         Bluetooth frame codec and water-control flow
├── data/        Settings, runtime state, log, repositories
├── notify/      Notification channels and notification building
├── service/     Foreground service for background water monitoring
└── ui/          Compose UI (Miuix + liquid-glass components)
```

## Copyright and license

Copyright (C) 2026 AnswerDev

This program is free software: you can redistribute it and/or modify it under the terms of the GNU
General Public License as published by the Free Software Foundation, either version 3 of the License,
or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public
License for more details.

You should have received a copy of the GNU General Public License along with this program. If not,
see <https://www.gnu.org/licenses/>.

The full text is in [LICENSE](LICENSE). SPDX identifier: `GPL-3.0-or-later`.

### Third-party components

Third-party libraries keep their own licenses; none of them changes this project's GPL-3.0 licensing.

| Component | License |
| --- | --- |
| [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) (backdrop, shapes) | Apache-2.0 |
| [Miuix](https://github.com/compose-miuix-ui/miuix) | Apache-2.0 |
| Jetpack / JetBrains Compose, Material Color Utilities | Apache-2.0 |
| Alipay app payment SDK | Published by Alipay; see the Alipay open platform |

The server API and the device protocol belong to the operator (趣智校园 / QuZhi Campus). The mechanics and
API semantics were informed by the open-source third-party client "淋浴 (LinYu, MIT)" and its contributors.
