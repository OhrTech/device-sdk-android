# Changelog

## Unreleased

- `config` (`OhrConfig`): the app's managed configuration, from its own `app_restrictions.xml`, set per fleet
  or device; typed getters with the declared defaults, and `changes`. Needs the OhrTech Agent 1060 or later.
- `attestation` (`OhrAttestation`): `attest(challenge, suite)` makes an attested key (EC, or EC + ML-DSA-65)
  whose chain runs to the OhrTech Attestation Root and names the device; `token(audience)` signs a
  short-lived proof per request.

## 0.1.0 (2026-11-08)

The first release. Needs the OhrTech Agent 1059 or later; keys and push need an OS with the OhrTech KEM app (OhrTech OS 2026110702 or later).

- `OhrDeviceSdk.connect` and the areas `device`, `updates`, `wifi`, `network`, `kiosk`, `power` and `settings`.
- `OhrWifiView`: Android's Wi-Fi screen, embedded.
- `keys`: ML-KEM-768, ML-KEM-1024, X-Wing and X25519 keys in the TEE, with attestation; the JCA provider
  `OhrTech`, `OhrKem` and `OhrHpke`.
- `push` and `OhrPushService`: end-to-end push, suites `x25519` and `xwing-mldsa65`.
