# Changelog

## 0.3.2 (2026-11-10)

- A push message the app doesn't take is no longer lost silently. If `OhrPushService.onMessage` throws, the hub
  shows the message as dropped (`app_handler`, or `app_` + the reason of an `OhrPushService.NotHandled`), and its
  id is forgotten, so the sender may send it again. Before, it was logged and counted as delivered.
- `OhrDiagnostics.event` and `log` never wait for the Agent: the arguments are checked at once and the call goes
  from the library's own thread, in order. In 0.3.1 they waited for the Agent while it restarted.
- `OhrPush.tokenAsync()` and `unregisterAsync()`, beside `registerAsync()`.
- `OhrKiosk.start` with `NOTIFICATIONS` or `OVERVIEW` but not `HOME` is `OhrException.InvalidConfig` (field `features`):
  Android doesn't allow them without the home button. Agent 1063 refuses it too.
- A reboot reason (`OhrPower.reboot`, `scheduleReboot`) is up to 64 of A-Z a-z 0-9 space . _ -; another is
  `OhrException.InvalidConfig` (field `reason`). Agent 1063 refuses it too.
- A bad AEAD for `OhrHpke.recipient` and an encapsulation of the wrong size for `OhrKem` decapsulation are
  `OhrException.InvalidConfig` (fields `aead`, `encapsulation`), not `IllegalArgumentException`.
- `OhrPushService.onTokenChanged`: today the Agent calls it only when the hub ended the token (null).

## 0.3.1 (2026-11-09)

- The kiosk heartbeat never stalls the app's main thread. Each beat is now sent from the library's own thread;
  in 0.3.0 the main thread waited for the Agent when the Agent was being updated (about 4 s on a test device, near
  Android's 5 s ANR limit). `OhrKiosk.beat()` and `stopHeartbeat()` don't wait for the Agent either.
- A key generation that fails in the Android Keystore (`ProviderException`) is `OhrException.Failed` in
  `OhrAttestation` and `OhrCertificates`, like the Keystore's other errors.

## 0.3.0 (2026-11-09)

The areas are objects to call directly, over one connection to the Agent that the library keeps for
the life of the process. Works with the OhrTech Agent 1059 or later, like 0.2.0.

- **No `connect()` and nothing to close.** `OhrDeviceSdk.connect(context).wifi.state()` is now
  `OhrWifi.state()`; likewise `OhrDevice`, `OhrUpdates`, `OhrNetwork`, `OhrKiosk`, `OhrPower`,
  `OhrSettings`, `OhrConfig`, `OhrAttestation`, `OhrCertificates`, `OhrUsb`, `OhrDiagnostics`,
  `OhrKeys` and `OhrPush`. From Java they are static methods. The application context comes from
  AndroidX App Startup (`OhrDeviceSdk.init(context)` if an app removes the initializer).
- **The connection looks after itself.** It binds on the first call, from any thread (the binding is
  answered on the library's own thread, so a first call on the main thread doesn't deadlock). When
  the Agent stops, a crash or its own update, it binds again and every flow and listener goes on;
  flows of a state (Wi-Fi, network, battery, kiosk mode, updates) get the current state when the Agent
  is back, for what changed meanwhile. Calls made while the Agent is away wait for it (10 s).
  `OhrDeviceSdk.connection` (and `listenConnection`) says how it stands; `OhrDeviceSdk.connect()`
  connects up front.
- **`OhrAccess`** replaces `session`: `OhrAccess.info()` and `OhrAccess.mayUse(area)` ask the Agent
  each time, so a change of the fleet's grants shows at once.
- `OhrWifiView` opens by itself once laid out, and again when it is attached again or the Agent was
  restarted; `attach(sdk)` is gone.
- `OhrKiosk.beat(timeoutMs)`: one heartbeat, for apps whose UI thread isn't Android's main thread
  (Flutter, React Native). The automatic `heartbeat()` survives Agent restarts.
- `OhrTechProvider.install()` takes no argument. `OhrPushService` uses the app's connection.
- Arguments the library refuses throw `OhrException.InvalidConfig(field, reason)`, not
  `IllegalArgumentException`; an unexpected error from the Agent is `OhrException.Failed`.
- `OhrKeys.Attestation.owner` is documented as it is: the Agent's UUID for the app, not its `app_…` id.

## 0.2.0 (2026-11-09)

Needs the OhrTech Agent 1061 or later for everything new.

- `config` (`OhrConfig`): the app's managed configuration, from its own `app_restrictions.xml`, set per fleet
  or device; typed getters with the declared defaults, and `changes`. From Java, `int(...)` is `getInt(...)`.
- `attestation` (`OhrAttestation`): `attest(challenge, suite)` makes an attested key (EC, or EC + ML-DSA-65)
  whose chain runs to the OhrTech Attestation Root and names the device; `token(audience)` signs a
  short-lived proof per request.
- `kiosk`: the exit PIN and the watchdog (the Agent starts a kiosk app again after a crash, a hang or leaving it).
- `diagnostics` (`OhrDiagnostics`): the app's own events for the hub, and log lines for log bundles. Crashes need
  nothing: the Agent reports them.
- `usb` (`OhrUsb`): the USB devices the fleet pre-approves for the app, attached now, and their changes.
- `certificates` (`OhrCertificates`): client certificates made on the device (an attested key, certified by the
  organisation's issuing CA; valid to its client root and to the OhrTech Device Identity Root), PKCS#12
  certificates and private CAs from the fleet, and `keyManager`, `trustManager`, `sslContext` for TLS.
- `OhrException` is unchecked (`RuntimeException`): Java catches it without `throws`. New subclass `Refused`.
- `session.app` is the app's `app_…` id, as the Management API and the portal name it.
- For Java: `updates.install()`, `OhrWifiView.Theme` and `OhrWifiView.Options` take their defaults;
  `OhrWifiView.Listener` methods are default methods. `OhrAttestation` reports Keystore errors as
  `OhrException.Failed`.

## 0.1.0 (2026-11-08)

The first release. Needs the OhrTech Agent 1059 or later; keys and push need an OS with the OhrTech KEM app (OhrTech OS 2026110702 or later).

- `OhrDeviceSdk.connect` and the areas `device`, `updates`, `wifi`, `network`, `kiosk`, `power` and `settings`.
- `OhrWifiView`: Android's Wi-Fi screen, embedded.
- `keys`: ML-KEM-768, ML-KEM-1024, X-Wing and X25519 keys in the TEE, with attestation; the JCA provider
  `OhrTech`, `OhrKem` and `OhrHpke`.
- `push` and `OhrPushService`: end-to-end push, suites `x25519` and `xwing-mldsa65`.
