# Bridge protocol: frozen remote-control envelope v1 (1.1)

`BRIDGE_PROTOCOL_MAJOR = 1`, `BRIDGE_PROTOCOL_MINOR = 1` (constants on `IEdlCompanion`). This is the fleet-wide remote-control schema that Droidcommand-AI (the controller) uses to drive the EDL-Commander companion over the existing AIDL binding. The schema is frozen; this document describes how this companion binds and enforces it.

Nothing here has been exercised against a real controller or real hardware. All behaviour below is covered by JVM unit tests with fakes (`BridgeGateTest`).

## Logical schema

| Field | Type | Notes |
|---|---|---|
| `op` | string | `capabilities.get` \| `remote.command` \| `macro.step` \| `macro.run` \| `node.status.get` (STATUS-TRAY, below) \| `config.export` \| `config.import` (UNIFIED_BACKUP, below; always refused remotely). Anything else: `unsupported_op` |
| `requestId` | string | 1..128 chars, echoed back |
| `targetNodeRef` | string | Informational echo in this version (max 256 chars) |
| `actionRef` | string | Dotted action, e.g. `usb.list` (`remote.command`, `macro.step`) |
| `params` | object | Action arguments (max 64 KiB) |
| `nonce` | string | 128 bits as 32 hex characters (case-insensitive) |
| `timestampMs` | long | Sender clock, epoch ms |
| `ttlMs` | long | Default 120000 (`0` means default), max 300000; larger or negative is `invalid_parameters` |
| `destructive` | bool | **Advisory only.** The receiver re-classifies; the flag can raise but never lower the classification |
| `trigger` | object | Informational only; validated as a JSON object, never acted on |
| `macroId`, `stepIndex`, `onError` | | `macro.step`: `macroId` required, `stepIndex >= 0`, `onError` `abort` (default) \| `continue` |
| `macroId`, `steps[]`, `dryRun` | | `macro.run`: 1..32 steps of `{actionRef, params, onError?}` |

## AIDL binding (1:1, flat, no new parcel types)

Fields are **appended** to the existing structured parcelables, so older callers and older companions keep working (stable-AIDL parcelables carry a size prefix; missing trailing fields take their defaults).

`CommandEnvelope` (existing: `schemaVersion`, `requestId`, `command`, `argsJson`, `dryRun`), appended:

| Frozen field | Parcel field | Default |
|---|---|---|
| (version) | `bridgeProtocolMajor`, `bridgeProtocolMinor` | `0`, `0` (= legacy caller) |
| `op` | `op` | null |
| `requestId` | `requestId` (existing field, reused) | |
| `targetNodeRef` | `targetNodeRef` | null |
| `actionRef` | `actionRef` | null |
| `params` | `paramsJson` | `"{}"` |
| `nonce` | `nonce` | null |
| `timestampMs` | `timestampMs` | `0` |
| `ttlMs` | `ttlMs` | `120000` |
| `destructive` | `destructive` | `false` |
| `trigger` | `triggerJson` | `"{}"` |
| `macroId` | `macroId` | null |
| `stepIndex` | `stepIndex` | `-1` |
| `onError` | `onError` | null |
| `steps` | `stepsJson` (JSON array) | `"[]"` |
| `dryRun` | `dryRun` (existing field, reused) | `true` |

With `bridgeProtocolMajor == 0` the legacy `command`/`argsJson` path is used. With `bridgeProtocolMajor == 1` the bridge path is used and `command`/`argsJson` are ignored. Any other major is `invalid_parameters`.

`ResultEnvelope` (existing: `schemaVersion`, `requestId`, `status`, `code`, `message`, `payloadJson`, `simulated`, `dryRun`), appended: `bridgeProtocolMajor`, `bridgeProtocolMinor`, `op` (echo), `errorCode` (frozen code, empty on success), `sourceNodeRef`. `code` keeps the detailed companion code (e.g. `permission_prompt_shown`, `usb_permission_required`); `status` keeps the legacy status class.

### Identity

Identity is the Binder calling uid plus the signature-level `BIND_COMPANION` permission (re-checked on every call by `SignatureCallerVerifier`). `sourceNodeRef` is derived by the companion from the calling uid as `android:uid/<uid>/<sorted package names joined by +>`; it is **never** read from the envelope. A uid with no packages is `not_paired`.

## Checks, in order

1. `bridgeProtocolMajor == 1` else `invalid_parameters`.
2. `op` known else `unsupported_op`.
3. Field validation (`requestId`, `nonce`, `timestampMs`, `ttlMs`, `params`, `trigger`, ref lengths) else `invalid_parameters`.
4. Caller identity resolvable else `not_paired`.
5. Expiry: `now > timestampMs + ttlMs` or `|now - timestampMs| > 120000` is `expired`.
6. Replay: `(sourceNodeRef, nonce)` seen within the last 10 minutes is `replay_detected`. The cache holds up to 10000 live entries; when full of live entries it fails closed with `internal` rather than evicting.
7. **Local remote-control switch** (OFF by default): every op except `capabilities.get` is `external_control_disabled`.
8. Receiver classification (below), then the existing `CommandRouter` gates (write families refused, USB permission, `security::authorize`, audit-before-run).

## Classification

| actionRef | kind | remoteAllowed | requiresConfirm |
|---|---|---|---|
| `companion.capabilities`, `companion.status`, `usb.list` | read | true | false |
| `sahara.identify` | read | true | false (`dryRun=false` still needs a user-granted USB permission, else `confirmation_required`) |
| `usb.request_permission` | action | true | true (the Android permission dialog is the local confirmation; returns `confirmation_required` with `code=permission_prompt_shown`) |
| any actionRef with a segment in `flash, firehose, write, erase, format, unlock, lock, bootloader, fastboot, program, programmer, patch, wipe, reset, provision, oem, upload, restore, sideload, reboot_edl, rawprogram` | **destructive** | **false** | true |
| anything else | `not_found` | | |

Destructive actions are refused remotely with `unauthorized`, including with `dryRun=true`, before the router or any device is reached. None exist in this build anyway. An advisory `destructive=true` on a read action raises it to destructive (refused); `destructive=false` on a flash action is ignored.

## Ops

- `capabilities.get`: always answers (even with remote control off). Payload: `bridgeProtocolMajor`, `bridgeProtocolMinor`, `envelopeOps`, `externalControlEnabled`, `ops: [{name, kind, remoteAllowed, requiresConfirm}]` (includes representative destructive families such as `flash.*` and `edl.write`), TTL/skew limits, `hardwareValidated: false`.
- `remote.command`: one action through the router.
- `macro.step`: as `remote.command`; payload echoes `macroId`, `stepIndex`, `onError`. Sequencing is the controller's job.
- `macro.run`: all steps are classified first. `dryRun=true` returns `DRY_RUN` with a per-step plan (`kind`, `remoteAllowed`, `requiresConfirm`, `verdict`) and **never reaches the router or a device**, destructive steps included. `dryRun=false`: if any step would be refused, nothing runs (all-or-nothing preflight); otherwise steps run in order, stopping at the first failure unless that step has `onError: continue`. The macro's `errorCode` is the first step error.

## `node.status.get` (fleet STATUS-TRAY)

Frozen spec: `Victorious93/droidcommand-AI` `docs/specs/STATUS_TRAY.md`. Carried over the same envelope, either as `op = "node.status.get"` (the spec's `operation`) or as `remote.command` with `actionRef = "node.status.get"`.

- Capabilities descriptor: `{name: "node.status.get", kind: "read", remoteAllowed: true, requiresConfirm: false}`.
- Result (`payloadJson`): one `NodeStatusSnapshot`: `nodeId` (the applicationId `ai.edlcommander.companion`), `packageName`, `connectionClass: "BOUND_COMPANION"`, `updatedAtMs`, and `domains` with a single `node_health` entry (`ok` with the native core linked, `degraded` without it; short `detail`, max 200 characters).
- Built from in-process app state only. It never touches USB, Sahara, the audit log or any write path.
- Optional `params.nodeId` (or `params.args.nodeId`): if it doesn't match this node the result is `not_found`.
- **Remote-control switch:** the spec says remote pulls fail closed with `external_control_disabled` while the switch is OFF, so `node.status.get` is refused while it is off. Only `capabilities.get` answers then.
- Replay, expiry and pairing checks apply as for every op. As the spec says, the advisory `destructive` flag is ignored for gating on this read.
- The snapshot encoder refuses (`invalid_parameters`) a `detail` that contains forbidden names (password, token, secret, apiKey, whitelist, signing/private key, …) or is over 200 characters; it does not silently strip.

## `config.export` / `config.import` (fleet UNIFIED_BACKUP)

Frozen spec: `Victorious93/droidcommand-AI` `docs/specs/UNIFIED_BACKUP.md` (bundle envelope `version: 1`).

| Op | kind | remoteAllowed | requiresConfirm |
|---|---|---|---|
| `config.export` | action | **false** | false |
| `config.import` | action | **false** | **true** |

- **Remote:** both are refused with `capability_off` (spec section 6: "remoteAllowed=false for a remote caller"), whether sent as `op` or as a `remote.command` `actionRef`, dry runs included, after the usual replay/expiry/switch checks. Nothing is read or written. Destructive ops are unchanged and still return `unauthorized`.
- **Local:** the companion UI has a "Settings backup" card with Preview import (dry run) and Apply import, which needs an on-device confirmation dialog. **In-app export is disabled** (fleet rule): until Android Keystore wrapping is implemented the app never writes, saves, shares or copies a `wrapping: none` backup. The Export button calls `ConfigBackup.exportForDevice()`, which refuses with `capability_off` and a clear message when no encryption key is configured (always, in this build) and never emits `clearPayload`. `ConfigBackup` implements the spec.
- **Payload:** one `UnifiedBackupEntry` for `ai.edlcommander.companion`, `schemaVersion: 1`, holding `{"preferences": {...}}` from an explicit allowlist of preference keys. **Preferences only**: never signing keys, secrets or firehose/programmer/loader paths, and never the remote-control switch. This build has no exportable user preferences yet, so the allowlist is empty and an export carries `preferences: {}`.
- **Wrapping:** `raw-aes-256-gcm` (AES-256-GCM, 12-byte IV, 128-bit tag, record `v1.<b64url iv>.<b64url ct>`, AAD `packageName|schemaVersion|sourceNodeRef`) when a key is supplied. `wrapping: "none"` / `clearPayload` (the spec's local-dev format) is accepted on import and produced only by the JVM-level `export(clearPayload = true)` used in tests; it is never written to a file or shown for export on the device. Android Keystore wrapping isn't implemented (the spec also leaves it undone).
- **Import order (spec section 4):** version → duplicate package → exactly one of ciphertext/clearPayload matching `wrapping` → decrypt (AAD mismatch is `internal`) → secret-name scan (refused with `invalid_parameters`, never silently stripped; covers api keys, tokens, passwords, signing/private keys, keystore, cert PEM, code word, whitelist, twilio/authToken/numberPool, cookies, firehose/programmer/loader paths) → plan.
- **dryRun** (the default in the UI) returns a per-key plan (`add`, `overwrite`, `kept_live`, `unchanged`) plus `stripped` keys and writes nothing. Applying without on-device confirmation is `confirmation_required`.
- **Conflict policy:** `skip` (default), `overwrite`, `merge-by-id`. For flat preferences, `merge-by-id` behaves like `skip` (live values win; missing keys are added).
- **Remote control is never imported:** any key containing `remote` or `external_control` is stripped (reason `remote_control_never_imported`). After an applied import the switch is **forced OFF** (spec section 4 step 7), even if it was on.
- Destructive flash/erase/bootloader ops are unaffected and stay `remoteAllowed: false`.

## Errors

| errorCode | status | when |
|---|---|---|
| `invalid_parameters` | INVALID | bad version or fields; router `INVALID` |
| `not_paired` | DENIED | caller identity unresolvable |
| `not_found` | UNSUPPORTED | unknown actionRef |
| `internal` | ERROR | replay cache full; router `ERROR` |
| `unauthorized` | DENIED | destructive action; router `DENIED` |
| `replay_detected` | INVALID | duplicate nonce |
| `expired` | INVALID | TTL or skew |
| `external_control_disabled` | DENIED | local switch off |
| `confirmation_required` | DENIED, or OK when a prompt was shown | local user action pending (USB permission) |
| `unsupported_op` | UNSUPPORTED | unknown op |
| `capability_off` | UNSUPPORTED | action exists but is not available in this build (e.g. native core not linked), or a non-destructive action with `remoteAllowed: false` (`config.export`/`config.import`) from a remote caller |

## Remote-control switch

`PreferencesRemoteControlSetting` (app-private SharedPreferences, default **off**). Only the "Allow remote control" switch in the companion UI writes it; no AIDL command can. The legacy (`bridgeProtocolMajor == 0`) path is gated by the same switch: while off, only `companion.capabilities` answers and everything else returns `DENIED/external_control_disabled`. The on-device UI is not affected by the switch.

## Controller requirements (Droidcommand-AI)

The controller must copy the updated AIDL files and send `bridgeProtocolMajor=1`, `bridgeProtocolMinor=1`, `op`, `actionRef`, `paramsJson`, a fresh 128-bit `nonce`, `timestampMs` and (optionally) `ttlMs` on every call, and read `errorCode`. Legacy calls keep working only while the user has enabled remote control and carry no replay protection; they are kept for transition and may be removed in a later major.
