# Changelog

## 0.2.0 (unreleased)

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
