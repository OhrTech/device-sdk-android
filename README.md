# OhrTech Device SDK for Android

`net.ohrtech:device-sdk`: what an app on an OhrTech device can ask the OhrTech Agent for. Who
the device is, the app's own updates, Wi-Fi (with Android's Wi-Fi screen embedded in your
layout), mobile data and airplane mode, kiosk mode, power, settings, managed configuration, KEM
keys and attestation in the device's TEE, client certificates, pre-approved USB devices, its own
events and logs, and push from your own server, end to end.

**Documentation: <https://docs.ohrtech.net/device-sdk/>**

```kotlin
dependencies {
    implementation("net.ohrtech:device-sdk:0.3.1")
}
```

Android 13 (API 33) and up, on OhrTech devices, with the OhrTech Agent 1059 or later (1061 for
what 0.2.0 added; see the [changelog](CHANGELOG.md)). This is the **Device** SDK, for apps on the
device. For servers, use the Management SDK ([.NET](https://github.com/OhrTech/management-sdk-dotnet)).

## Who may use what

The Agent answers apps your organisation installed through OhrTech; others get `NotManaged`.
Reading is mostly always allowed. Changing something needs the fleet's grant for that area, set in
the portal under the fleet's apps: `wifi`, `network`, `kiosk`, `power`, `settings`. `device`,
`updates`, `keys`, `push`, `diagnostics`, `usb` and `certificates` are open to every managed app. Settings and networks the fleet locks
stay locked (`FleetLocked`).

## Use

```kotlin
val info = OhrDevice.infoAsync()                 // deviceId "dev_…", fleet, org, OS, Agent
if (OhrAccess.mayUse("kiosk")) OhrKiosk.start(activity)
OhrWifi.stateChanges.collect { state -> … }      // cancel the collection to stop
```

Each area is an object to call directly; there is nothing to connect or close. The library binds the
Agent on the first call and keeps the binding for the life of the process; when the Agent stops (a
crash, its own update) it binds again, and every subscription goes on. `OhrDeviceSdk.connection`
says how it stands; `OhrDeviceSdk.connect()` connects up front, to know at app start whether the
Device SDK is there. The application context comes from AndroidX App Startup; an app that removes
the library's initializer calls `OhrDeviceSdk.init(context)`.

Calls are binder calls into the Agent: make them off the main thread (the `…Async` versions do that
themselves; a first call on the main thread works too, without deadlock). From Java, the same
objects have static methods (`OhrWifi.state()`), and changes come to a listener on an `Executor`
(`OhrWifi.listenState(executor, listener)`; close it to stop). `OhrAccess.info()` says who the Agent
takes the app for and which areas it may use, asked each time.

| Object | |
|---|---|
| `OhrDevice` | `info()`: device id, serial, fleet, org, OS version and patch, Agent version, online |
| `OhrUpdates` | `available()`, `install(whenIdle)`, `changes`: the app's own updates the hub offers |
| `OhrWifi` | state, networks around, saved networks, `save`, `connect`, `forget`, `setEnabled`, `connectQr` |
| `OhrNetwork` | `state()`, `setMobileData`, `setAirplaneMode`, `setDataRoaming` |
| `OhrKiosk` | `start(activity, features)`, `stop()`, `state()`: lock task as the system; `heartbeat()` or `beat()` for the watchdog |
| `OhrPower` | `battery()`, `reboot`, `scheduleReboot`, `setScreenTimeout`, `setBrightness` |
| `OhrSettings` | `keys()`, `get(key)`, `set(key, value)`: the device settings apps may change, checked as the catalogue says |
| `OhrConfig` | the app's managed configuration, as its fleet or device sets it |
| `OhrAttestation` | `attest(challenge)`, `token(audience)`: proof for your server of this app on this device |
| `OhrCertificates` | client certificates made on the device or imported, trusted CAs, `sslContext()` |
| `OhrUsb` | the USB devices the fleet pre-approves for the app: no permission prompt |
| `OhrDiagnostics` | the app's own events and log lines for the hub; crashes are reported without it |
| `OhrKeys` | TEE KEM keys: see below |
| `OhrPush` | end-to-end push: see below |

## The Wi-Fi screen

```xml
<net.ohrtech.device.OhrWifiView
    android:id="@+id/wifi"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
```

It opens once it is laid out (set its `listener` and `options` in `onCreate`), and again when it is
attached again or the Agent was restarted.

Android Settings' Wi-Fi screen, drawn by the Agent inside your layout: every network type Android
supports, saved networks, forgetting. It opens no other window, so a kiosk app stays a kiosk.
`theme` and `options` change the look and what it offers.

## Keys in the TEE

ML-KEM-768, ML-KEM-1024, X-Wing, and X25519 (for HPKE). The private keys never leave the
device's TEE. Each app has its own, by alias.

```kotlin
val pub = OhrKeys.generate("orders", OhrKeys.Algorithm.X_WING)
val proof = OhrKeys.attest("orders", serverNonce)    // send to your server
val secret = OhrKeys.decapsulate("orders", ciphertext)
```

Your server checks `proof` with the Management API (`POST /v1/devices/{deviceId}/key-attestations/verify`):
the key was made in this device's TEE, for this app. The same keys through the JCA:
`OhrTechProvider.install()`, then `KeyPairGenerator.getInstance("ML-KEM-768", "OhrTech")`, the
`"OhrTech"` KeyStore, `OhrKem` (shaped like `javax.crypto.KEM`) and `OhrHpke` (HPKE, the recipient).
For signatures, use Android's own Keystore: its ML-DSA-65 and Ed25519 keys are TEE-backed too.

## Push

Your server seals each message to the device's push key, which is in the TEE, and signs it. The
OhrTech hub carries it but can't read it. Two suites: `x25519` (about 190 bytes over your data;
the default) and `xwing-mldsa65` (post-quantum, about 4.6 KB over).

1. Declare a push service. Only the Agent holds the permission, so nothing else can feed it:
   ```xml
   <service android:name=".Push" android:exported="true"
       android:permission="net.ohrtech.device.permission.RECEIVE_PUSH">
       <intent-filter><action android:name="net.ohrtech.device.action.PUSH" /></intent-filter>
   </service>
   ```
   ```kotlin
   class Push : OhrPushService() {
       override fun onMessage(message: OhrPushMessage) { /* message.data */ }
       override fun onTokenChanged(token: PushToken?) { /* tell your server; null: register again */ }
   }
   ```
2. Register with your server's sender public keys, and send the token to your server:
   ```kotlin
   val token = OhrPush.register(listOf(SenderKey.fromBase64(ED25519_PUBLIC_KEY)))
   myServer.save(token.token, token.publicKeyBase64)
   ```
3. Your server seals with the Management SDK (`PushSealer.Seal` in .NET) and sends it with
   `POST /v1/push`.

Before `onMessage`, the library checks that one of your sender keys signed the message, that it
opens with this app's key, that it hasn't expired (2 minutes' grace for the clock), and that its id
is new. If any check fails, it drops the message, tells the hub why, and calls `onDropped`.

## Managed configuration

Settings your app declares in `res/xml/app_restrictions.xml` (manifest meta-data
`android.content.APP_RESTRICTIONS`), set per fleet or per device in the portal or with the Management
API. The portal shows a form from the app's own declaration; secrets (`hidden`) are sealed until they
reach the device.

```kotlin
val store = OhrConfig.int("store_number")           // the declared default when it isn't set
val lanes = OhrConfig.bundles("lanes")
OhrConfig.changes.collect { values -> reload(values) }
```

`OhrConfig` needs no Agent connection. It is Android's own
`RestrictionsManager`, so code that already reads application restrictions works as it is.

## Device attestation

Proof for your server that a request comes from your app, unmodified, on a specific, genuine and
secure OhrTech device, without a secret in the APK. `attest` makes a key in your app's Android Keystore
(in the TEE), bound to your server's challenge, and returns its certificate chain up to the OhrTech
Attestation Root. The chain names the device (`dev_…`) and says which app made the key (package and
signer), the boot state and the patch levels.

```kotlin
val attestation = OhrAttestation.attest(serverChallenge)             // or Suite.EC_MLDSA65
myServer.register(attestation.toJson())                              // once
val token = OhrAttestation.token("https://api.example.com")          // each request, 5 minutes
```

Two suites: `EC` (P-256), and `EC_MLDSA65`, post-quantum: an EC key and an ML-DSA-65 key, both
attested, every token signed with both. Your server checks the chain once and keeps the public
key(s): online with the Management API (`POST /v1/attestations/verify`, which also names the fleet
and app for your own devices), or offline with the Management SDK (`DeviceAttestationVerifier` in
.NET) against the published root and the signed status list of revoked devices
(`GET /v1/attestations/status`). Then it checks each request's token with those keys
(`AttestationToken.Verify`).

## Errors

Every call throws an `OhrException` (unchecked): `NotManaged`, `NotAllowed(area)`, `FleetLocked(key)`,
`Unsupported(reason, needsAgent)`, `InvalidConfig(field, reason)`, `Refused(reason)` or `Failed(reason)`.

## Building

```sh
./gradlew :device-sdk:assembleRelease :device-sdk:testReleaseUnitTest :sample:assembleDebug
```

JDK 17 or 21 for Gradle. Add `-PtestJdk=<JDK 24+ home>` to run the ML-DSA-65 tests too. `sample/`
is a kiosk app that uses most of it. `tools/push-vectors` makes the push test vectors with the .NET SDK.

## License

MIT
