# T-001: Replace Shizuku with our own shell host

- **Step:** alongside E2 (owner, 2026-11-06; was proposed as E1b) · **Lead:** claude · **Opened:** 2026-11-06 · **Status:** IN PROGRESS (1a)
- **Read first:** `docs/ENGINE_SPEC.md` → Decisions, 2026-11-03 → "Shizuku after reboot" (the 2026-11-06 "no Shizuku"
  decision and the fork review); build order step 1; `docs/E1_INVENTORY.md` rows for `ShizukuLink`, `ShellService`,
  `SystemRestore`, the watchdog; D-070 (no package rename; the accessibility service is stored by class name); D-071
  (`dev.rikka.shizuku:api` 13.1.5 declared in `:engine`); `docs/PROBE_FINDINGS.md` (what the shell service proves).
- **Reference code (not in this repo):** `RikkaApps/Shizuku` (upstream, Apache-2.0) and `thedjchi/Shizuku` v13.7.0 (the
  boot-restart fork). Clone both beside the repo for reading; nothing is copied without its NOTICE.

## 1. Proposal (lead)

**Goal.** The launcher runs every Pro feature with no Shizuku installed: it pairs with wireless debugging once, starts its
own shell-uid process hosting `ShellService`, gets the binder back, and comes back by itself after a reboot. This closes
step 1's exit test as the spec now states it.

**Where it goes.** ~~A separate E1b before E2.~~ **Alongside E2** (owner, 2026-11-06: "rework T-001 so it runs alongside
E2 instead of before it"). The reason given for "before" no longer holds: E2's plan (`docs/E2_PLAN.md`, decision on its
question 3, D-074) builds only the registry's shape and its app-side sources; the AIDL-backed entries come with their
first consumer in E3. So no E2 slice wraps `ShizukuLink` or the AIDL, and the two run on disjoint files:

| Runs now | Owner | Files it touches |
| --- | --- | --- |
| E2.0a, the Compose 1.11.4 + Kotlin 2.2 upgrade (T-002) | claude | every `build.gradle.kts`, `gradle.properties`, Kotlin sources the new compiler refuses |
| T-001 slice 1, the vendored ADB client and its tests | codex (offered) | new files only, under `engine/src/main/java/dev/launcher/engine/adb/` and its tests, plus `engine/src/main/adb-NOTICE` |

Ordering constraints, so neither waits on the other by surprise: T-001 slice 1 adds no dependency and edits no Gradle
file while T-002 is open (any library it needs is a *question* in §2, and goes to the owner, protocol §5.3); T-001 slices
2-5 touch `ShellService`, `ShizukuLink`, `SystemRestore` and the watchdog, which no E2 slice touches; T-001 slice 5
(flip and remove) waits for E2's exit run on the S24 so the two measurements are never mixed.

**Approach, as slices** (each ~200 changed lines or a pure vendor import):

1. *Vendor the ADB client.* Upstream's `manager/.../adb/` (client, pairing with TLS and SPAKE2, key store, mDNS) into
   `:engine` under its own package with `NOTICE` and the Apache-2.0 header kept. Unit tests over the message framing and
   the key store. No callers yet.
2. *Our starter.* The command that `app_process` runs as shell to start `ShellService` (today Shizuku's user-service does
   this), and the binder handover back to the app (upstream sends it with a broadcast to the manager; we only ever send
   it to our own package, checked by uid). Behind a `ShellHost` interface with two implementations: Shizuku (today's
   path) and ours, so slices 2–4 change nothing for the user until slice 5 flips the default.
3. *Pairing and start in onboarding.* The pair-code flow (notification reply, as upstream), connect, start, show state.
   Minimal UI on the existing settings page; the polished onboarding is the alpha's job.
4. *Boot restart.* The fork's sequence: the shell process grants us `WRITE_SECURE_SETTINGS` once; on boot a worker
   enables wireless debugging, waits for Wi-Fi and adbd, finds the port over mDNS, connects with the stored key, starts
   the process, disables wireless debugging. Visible "Pro resumes in about N s" state. Root start (`su`) as a second
   path, a few lines.
5. *Flip and remove.* Our host becomes the only one; `ShizukuLink` and `dev.rikka.shizuku:api` leave; existing installs
   are migrated (the user pairs once; the watchdog and `SystemRestore` work unchanged).

**Verification.** Slice 1: unit tests. Slices 2–5: `scripts/check.sh`; `tools/dexlist.sh` with each slice's declared
diff; on the emulator and the S24, every check in D-072's emulator list passes on our host; reboot test on the S24 and
the Redmi (if connected): time from boot to "Pro running", measured over five reboots; the watchdog's kill test from
E1's risk 10 (freeze the heartbeat file) on our host. Security checklist (protocol §7) on slices 2, 4 and 5.

**Risks and open questions.**

- **New permissions need the owner.** Android 16/17 gate mDNS behind `NEARBY_WIFI_DEVICES` (neverForLocation),
  `ACCESS_LOCAL_NETWORK` and multicast; boot needs `RECEIVE_BOOT_COMPLETED` and possibly a foreground service type. The
  fork declares these; we must list exactly which we need and ask before slice 3.
- **SPAKE2 is native in upstream** (a small BoringSSL piece behind JNI, as far as the lead remembers). That would be our
  first native code. Codex: please check upstream's source and say whether a pure-Kotlin/Conscrypt path exists.
- **Play policy** on an app that enables wireless debugging and runs a shell process: Shizuku is on Play, so there is a
  precedent; the disclosure page names it.
- **Two shell processes** for users who also run Shizuku for other apps: they should coexist; to be tested, not assumed.

**Proposed split** (to be agreed in §4; slice 1 offered to Codex now, 2026-11-06): Codex owns slice 1 (the vendored client and its tests: self-contained,
well-specified) and the security checklist's first pass on slice 2; the lead owns slices 2–5 (they touch `ShellService`,
the watchdog and the phone) and holds the device.

## 2. Discussion

### codex — discuss, 2026-11-06T17:57Z

I take slice 1, but I would split and amend it before work starts.

### Source check

I could not create the requested clone because discussion mode is filesystem read-only, and no Shizuku clone exists beside either worktree. I inspected the official repository at its latest release tag, **`v13.6.0` (`2650830`)**, directly on GitHub.

Relevant files:

- `manager/src/main/java/moe/shizuku/manager/adb/{AdbClient,AdbException,AdbKey,AdbMdns,AdbMessage,AdbPairingClient,AdbProtocol}.kt`
- `manager/src/main/jni/{adb_pairing.cpp,adb_pairing.h,misc.cpp,CMakeLists.txt}`
- `manager/build.gradle`
- `manager/src/main/AndroidManifest.xml`

I would not copy `AdbPairingService.kt` or `AdbPairingTutorialActivity.kt` in slice 1: they are callers/UI assigned to slice 3.

### SPAKE2 and dependencies

Pairing definitely uses native code upstream:

- `AdbPairingClient.kt:118-151,288-293` declares JNI methods and loads `libadb.so`.
- `adb_pairing.cpp:12-16,25-200` uses BoringSSL SPAKE2, HKDF-SHA256 and AES-128-GCM.
- `CMakeLists.txt:32-39` builds `adb_pairing.cpp + misc.cpp` and links BoringSSL and libc++.
- `AdbPairingClient.kt:190-208` uses TLS 1.3 and exports 64 bytes of TLS key material before SPAKE2.

A pure public-JCA implementation does not exist on Android 11+: neither JCA nor Conscrypt exposes SPAKE2. Conscrypt’s published capability list has TLS, X25519/Ed25519 and ordinary JCA primitives, but no SPAKE2. A pure-Kotlin version is theoretically possible, but it means implementing/adapting the exact BoringSSL SPAKE2 group and transcript plus HKDF, sequence-numbered AES-GCM and interoperability vectors. That is no longer “vendor upstream”; it creates substantially more crypto code and review burden than the ~230-line audited native bridge. I recommend the native upstream route.

TLS itself needs no bundled provider. Android’s platform JSSE supplies TLS 1.3. For the exporter:

- Android 12/API 31+ has the public [`android.net.ssl.SSLSockets.exportKeyingMaterial`](https://developer.android.com/reference/android/net/ssl/SSLSockets#exportKeyingMaterial(javax.net.ssl.SSLSocket,%20java.lang.String,%20byte[],%20int)).
- Android 11/API 30 does not. Upstream uses hidden `com.android.org.conscrypt.Conscrypt` (`AdbPairingClient.kt:5,202`).
- The API-30 choices are hidden platform Conscrypt with the required hidden-API build/runtime support, or bundled `org.conscrypt:conscrypt-android`. Bundled Conscrypt is itself native and still does not supply SPAKE2. This is an owner decision.

Exact upstream client dependencies are:

- Platform JSSE/JCA, Android Keystore and `NsdManager`: no Maven dependency.
- `org.bouncycastle:bcpkix-jdk15on:1.70`, plus transitive `bcprov-jdk15on` and `bcutil-jdk15on`, solely for the self-signed ADB TLS certificate (`AdbKey.kt:10-14,75-90`).
- `io.github.vvb2060.ndk:boringssl:20250114`.
- `org.lsposed.libcxx:libcxx:27.0.12077973`.
- For upstream’s API-30 internal-Conscrypt call: its hidden compat/stub/refine machinery and HiddenApiBypass. Public API 31+ should not use this.
- Upstream’s AndroidX `Observer`, `SharedPreferences.edit`, Rikka `unsafeLazy`, `BuildUtils` and logging helpers are incidental; replace them with a callback and standard Kotlin/framework code rather than importing more libraries.

All dependency licences/notices, the upstream tag/commit and the exact copied-file list belong in the NOTICE. The tagged Kotlin files do not actually carry per-file Apache headers, so preserve provenance and LICENSE/NOTICE rather than inventing a header.

### Permission result

The proposal’s `T-001:57-59` wording is too broad. Permissions depend on target SDK. This app currently targets 34 (`app/build.gradle.kts:18`) and already declares `INTERNET` and `ACCESS_NETWORK_STATE` (`app/src/main/AndroidManifest.xml:6-8`).

- `INTERNET` (normal): required for the TCP/TLS pairing and connection sockets; already declared.
- `ACCESS_NETWORK_STATE` (normal): required for network-aware NSD/waiting for Wi-Fi; already declared.
- Android 16: local-network protection is opt-in. `NEARBY_WIFI_DEVICES` (runtime, `neverForLocation`) is needed only when the `RESTRICT_LOCAL_NETWORK` compatibility restriction is enabled; it restores the direct TCP access. It is not an unconditional Android 16 mDNS permission.
- Android 17: only apps targeting API 37+ must declare and request dangerous `ACCESS_LOCAL_NETWORK`. Apps targeting 36 or below retain implicit LAN access through `INTERNET`, and Android explicitly says not to request the new permission yet. Automatic headless boot discovery probably needs broad access; the system NSD picker can avoid it, but introduces UI and grants a selected service rather than arbitrary changing ADB endpoints. [Android local-network rules](https://developer.android.com/privacy-and-security/local-network-permission).
- `CHANGE_WIFI_MULTICAST_STATE` (normal): needed only if we explicitly acquire a `WifiManager.MulticastLock`. `NsdManager` manages multicast for foreground apps on current T-extension devices, but background boot discovery may require a short-held lock; decide after testing. [NsdManager multicast rules](https://developer.android.com/reference/android/net/nsd/NsdManager).
- No location permission and no `ACCESS_WIFI_STATE` are required for NSD or the socket itself.
- If boot/start uses a connected-device foreground service, add `FOREGROUND_SERVICE_CONNECTED_DEVICE` plus a qualifying normal permission such as `CHANGE_WIFI_MULTICAST_STATE`; `FOREGROUND_SERVICE` already exists. User pairing can use `shortService`, as upstream does. `RECEIVE_BOOT_COMPLETED` remains a separate owner-approved boot permission.

No permission should be added in slice 1 because it has no callers.

### Scope and ordering changes

`T-001:27-36` is internally inconsistent: full upstream pairing cannot compile without Gradle/CMake changes and new dependencies, while T-002 owns every Gradle file. I propose:

1. **1a while T-002 is open:** new dependency-free protocol/codec files, bounded framing tests, endpoint-shaped mDNS abstraction, key-store interfaces, NOTICE. No native pairing implementation pretending to be complete.
2. **1b after T-002 lands and the owner approves native code/dependencies:** `engine/build.gradle.kts`, CMake/native sources, certificate/key implementation and pairing client. Amend the split so those files are mine.

Additional required changes:

- Do not copy `AdbClient.read()` unchanged: `AdbClient.kt:118-151` allocates the peer-controlled `dataLength` without an upper bound. Extract a bounded codec and test oversized, negative, truncated, bad-magic and bad-checksum frames.
- Pairing header tests must cover version/type/payload refusal, not only round trips (`AdbPairingClient.kt:93-114`).
- JVM tests cannot prove Android Keystore behavior. Unit-test storage/encryption seams and corruption/key-regeneration paths; add an emulator test for real Android Keystore persistence before any caller ships.
- Remove upstream’s private-key log (`AdbKey.kt:91`) and payload-byte logging. The security checklist requires secrets and commands never enter logs.
- `AdbMdns.kt:14-66` reports only a port and discards the resolved address/network. Carry an endpoint containing address, port and `Network`; this also incorporates the fork’s Android 17 host fix.
- Apply the security checklist to slices **1 and 3** as well as 2/4/5: slice 1 parses network data and stores the ADB identity.
- A package-targeted broadcast plus shell UID is not sufficient authentication for binder handover: any adb shell is UID 2000. Slice 2 needs an app-generated one-time nonce/capability tied to that start attempt and a bounded expiry.
- “Root start, a few lines” should be its own sub-slice: UID 0, SELinux/cgroup behavior and caller validation differ from shell UID.
- Existing Shizuku users do not migrate an ADB identity; they re-pair. Keep the Shizuku fallback until our host has started successfully once, then remove it in the later flip.

codex: NOT YET — I take slice 1, but owner approval for native code/dependencies and a 1a/1b split are required before the approach is ready.

### claude — 2026-11-06

Accepted, all of it; the plan changes as you propose. Where I checked rather than took it on trust: `T-001:27-36` does
contradict T-002 owning every Gradle file (full pairing cannot compile without Gradle and CMake), so the 1a/1b split is
the only honest shape; and SPAKE2 in neither JCA nor Conscrypt matches what I know of both. Decisions:

1. **Slice 1a now, yours:** dependency-free, no Gradle or CMake edit, no permission. `dev.launcher.engine.adb`: the
   message codec (header, checksum, magic, a hard upper bound on `dataLength` and a test for oversized, negative,
   truncated, bad-magic and bad-checksum frames), the pairing packet header with its refusals, an endpoint type carrying
   address, port and `Network` (the fork's Android 17 host fix), key-store and pairing-client *interfaces* with fakes,
   and `engine/src/main/adb-NOTICE` naming the upstream tag (`v13.6.0`, `2650830`), the files each piece came from and
   Apache-2.0. Nothing logs key material or payload bytes; a test asserts the codec's `toString`s do not contain them.
   No callers. Security checklist (protocol §7) answered in §6 for 1a: you first, me second.
2. **Slice 1b waits** for T-002 to land and for the owner's answer on native code and the libraries you listed
   (BoringSSL via prefab, libc++, `bcpkix-jdk15on` 1.70) and on Android 11's exporter path. I am asking him now; his
   answer goes in §3. If he declines native code, 1b is re-planned around a pure-Kotlin SPAKE2 with interoperability
   vectors, and we say what that costs before starting.
3. **Slice 2's handover gets your nonce:** an app-generated one-time capability per start attempt, bounded expiry, the
   shell side proving it before the binder is accepted. Recorded here so slice 2's proposal starts from it.
4. **Root start** becomes its own sub-slice after 4. **Migration**: existing Shizuku users re-pair; the Shizuku path stays
   until ours has started once on that phone, then the flip removes it.
5. **Security checklist on slices 1 and 3** as well as 2, 4, 5. Agreed.

For this round: confirm 1a's scope as written above, or amend it, and end with `codex: AGREE approach` for 1a if it is
ready. 1b stays NOT YET until the owner answers.

### codex — discuss, 2026-11-06T18:17Z

I agree with the 1a/1b boundary and the lead’s slice ordering. I would make five narrow amendments to 1a:

- Defer the key-store and pairing-client interfaces and their unused fakes to 1b or their first consumer. With no callers, they are untested API shape; D-070 (`DECISIONS.md:771-774`) explicitly rejected that pattern so use could drive the API.
- Name the bounds: ADB message payloads are capped at **4,096 bytes**, matching upstream `A_MAXDATA` ([`AdbProtocol.kt:13-15`](https://github.com/RikkaApps/Shizuku/blob/v13.6.0/manager/src/main/java/moe/shizuku/manager/adb/AdbProtocol.kt#L13-L15)); pairing packets are capped at **16,384 bytes**, matching `kMaxPeerInfoSize * 2` ([`AdbPairingClient.kt:15-20`](https://github.com/RikkaApps/Shizuku/blob/v13.6.0/manager/src/main/java/moe/shizuku/manager/adb/AdbPairingClient.kt#L15-L20)). Pairing-header refusal tests should explicitly cover unsupported version/type, payload `<= 0`, payload above 16,384, and a truncated six-byte header.
- Make the endpoint’s `Network` nullable. The module supports API 29 (`engine/build.gradle.kts:19`), while `NsdServiceInfo.getNetwork()` arrived in API 33 and may return null even there ([Android API reference](https://developer.android.com/reference/android/net/nsd/NsdServiceInfo#getNetwork())). The later mDNS implementation can populate it where available.
- If the endpoint shape incorporates the fork’s Android 17 fix, `adb-NOTICE` must name the exact `thedjchi/Shizuku` revision and source file as well as upstream `v13.6.0`/`2650830`; the owner’s decision requires attribution (`ENGINE_SPEC.md:406-408`).
- Verification is the focused refusal/round-trip/secrecy unit tests plus full `scripts/check.sh`, as required by `OPERATING_RULES.md:42-43`. Existing JUnit support is already present (`engine/build.gradle.kts:59`), so this adds no dependency.

Everything else in 1a is ready: new code belongs under `dev.launcher.engine.*` per D-074 (`DECISIONS.md:870`), it adds no Gradle/CMake edit, permission, caller, or user-visible behavior, and its protocol parsing/storage boundary correctly gets the protocol §7 security review. Slice 1b remains waiting for the owner.

codex: AGREE approach — slice 1a is ready with the amendments above; slice 1b remains waiting for the owner’s native/dependency/exporter decision.

## 3. Agreed approach

**Agreed 2026-11-06 (claude and codex, two rounds; the owner on 1b's questions).**

- **Where:** alongside E2 (owner). Slices 2-5 touch files no E2 slice touches; slice 5 waits for E2's exit run.
- **Slice 1a (codex, now):** dependency-free, no Gradle/CMake edit, no permission, no callers, under
  `dev.launcher.engine.adb`: the ADB message codec (payload capped at 4,096 bytes, upstream's `A_MAXDATA`; refusals for
  oversized, negative, truncated, bad-magic and bad-checksum frames), the pairing packet header (capped at 16,384 bytes,
  upstream's `kMaxPeerInfoSize * 2`; refusals for unsupported version or type, payload <= 0 or above the cap, a truncated
  six-byte header), an endpoint type of address, port and a **nullable** `Network` (API 33+, and null even there), and
  `engine/src/main/adb-NOTICE` naming upstream `v13.6.0` (`2650830`), the exact `thedjchi/Shizuku` revision and file of
  the Android 17 endpoint fix, the files each piece came from, and Apache-2.0. Nothing logs key material or payload bytes
  (a test checks the `toString`s). **Changed from the proposal:** the key-store and pairing-client interfaces and their
  fakes are not in 1a: unused API shape with no consumer is what D-070 rejected; they come with 1b. Verified by the
  refusal, round-trip and secrecy unit tests and the full `scripts/check.sh`; security checklist (§7 of the protocol) in §6.
- **Slice 1b (codex, after T-002 lands):** the owner, 2026-11-06: **native route approved** (upstream's audited JNI
  bridge to BoringSSL's SPAKE2, HKDF and AES-GCM, with BoringSSL via prefab, libc++ and `bcpkix-jdk15on` 1.70; the
  owner's yes covers exactly these three and NDK/CMake in `:engine`), and **our own pairing supports Android 12+ only**
  (the public `SSLSockets.exportKeyingMaterial`; no hidden Conscrypt, no bundled Conscrypt; Android 11 keeps Shizuku).
  1b also brings the key-store and pairing-client interfaces with their implementations, and removes upstream's
  private-key and payload logging. Its proposal is written in §1 of a new round before work starts.
- **Slice 2:** the binder handover carries an app-generated one-time capability per start attempt with a bounded expiry
  (codex's finding: any adb shell is uid 2000). **Root start** is its own sub-slice after 4. **Migration:** Shizuku users
  re-pair; the Shizuku path stays until ours has started once on that phone. Security checklist on slices 1-5.

- `codex: AGREE approach — slice 1a is ready with the amendments above; slice 1b remains waiting for the owner's
  native/dependency/exporter decision.` (2026-11-06T18:17Z; the owner has since answered.)

## 4. Split

| Sub-task | Owner | Files owned | Branch | Verified by |
| --- | --- | --- | --- | --- |
| 1a: ADB codec, pairing header, endpoint, NOTICE, their tests | codex | new files under `engine/src/main/java/dev/launcher/engine/adb/` and `engine/src/test/java/dev/launcher/engine/adb/`, `engine/src/main/adb-NOTICE` | `team/codex/t001-adb` | unit tests (refusals, round trips, secrecy); `scripts/check.sh` |
| 1a review, integration | claude | nothing in 1a's files; `engine` after rebase | `engine` | §6 cross-check, security checklist |

## 5. Reports

### codex — work, 2026-11-06T19:31Z

#### Commits

- None. `git add`/`git commit` were blocked because `D:/Launcher/.git/worktrees/launcher-codex/index.lock` is read-only to this session. The six assigned files remain untracked on `team/codex/t001-adb`.

#### What changed

- Added bounded 4,096-byte ADB message framing, checksum/magic validation, defensive payload copies, and non-secret `toString`.
- Added six-byte pairing headers with the 16,384-byte bound and strict version/type/length refusal.
- Added resolved address, port, and nullable `Network` endpoint data.
- Added Apache-2.0 provenance for upstream `2650830` and the fork’s resolved-host fix [`3b17352`](https://github.com/thedjchi/Shizuku/commit/3b1735223d4b83a8c5531832553cc059643d447d).
- Added 10 focused tests. No Gradle, CMake, manifest, permission, caller, or non-assigned source changed.

#### Verified

- **Measured:** focused ADB tests 10/10 passed.
- **Measured:** full `scripts/check.sh` passed: 504 unit tests, 0 failures/errors/skips; Gradle 113 tasks; fidelity 74 scenarios/746 checks; fidelity Python 58/58; icon-play and layout-guard passed.
- **Measured:** `tools/dexlist.sh E2-11`: manifest identical and all E2-11 declarations matched; only these seven new classes were undeclared:

```text
+ Ldev/launcher/engine/adb/AdbEndpoint; 3 10
+ Ldev/launcher/engine/adb/AdbMessage; 3 7
+ Ldev/launcher/engine/adb/AdbMessageCodec; 4 2
+ Ldev/launcher/engine/adb/AdbProtocolException; 2 0
+ Ldev/launcher/engine/adb/PairingPacketHeader$Companion; 2 1
+ Ldev/launcher/engine/adb/PairingPacketHeader; 2 4
+ Ldev/launcher/engine/adb/PairingPacketType; 6 1
```

- **Measured:** `git diff --check` passed; Kotlin source ASCII scan found no non-ASCII characters.
- **Judged:** implementation matches §3/§4 and the five agreed amendments.

#### Not verified

- No caller, socket, mDNS implementation, real pairing, emulator, or phone test exists in 1a.
- No commit could be created under the session’s repository-metadata permissions.

#### Surprises

- The exact fork revision for the Android 17 resolved-host change is `3b1735223d4b83a8c5531832553cc059643d447d`, included in tag commit `608fcf0e49d9cffc53427189cf7e0d5b3a6a23e4`.
- Verification required ignored workspace-local Gradle/SDK caches because external cache and SDK paths were read-restricted.

#### Security checklist — Slice 1a

- [x] Caller checks: N/A; 1a adds no callable component, binder, intent, service, or other entry point.
- [x] Inputs bounded: ADB headers are fixed at 24 bytes and payloads at 4,096 bytes before allocation; pairing headers are exactly 6 bytes and payloads 1–16,384 bytes. No reflection.
- [x] No KDL/Lua/package path to shell, `exec`, reflection, external files, or another app’s components was added.
- [x] Capabilities: N/A; no capability or runnable operation was added.
- [x] No captured data exists or is persisted, logged, or transmitted.
- [x] No key or payload bytes are logged or printed; secrecy tests cover both message and pairing-header strings.
- [x] Refusal paths are tested: negative/oversized/truncated lengths, bad magic/checksum, unsupported pairing version/type, and non-positive pairing payloads.

## 6. Cross-check

### claude — cross-check of 1a, 2026-11-07

Read: `AdbMessage.kt` (the message, `AdbMessageCodec`, `AdbProtocolException`), `PairingPacketHeader.kt`, `AdbEndpoint.kt`,
both tests, `adb-NOTICE`; ran nothing new (your gate and dex numbers stand). Ownership respected: only §4's paths.

- **fix (as I read adb; please confirm against AOSP before changing):** `AdbMessage.kt`, `decode`: `if (checksum(payload) !=
  expectedChecksum) throw`. Since protocol version 0x01000001 (`A_VERSION_SKIP_CHECKSUM`), adbd's `send_packet`
  (`packages/modules/adb/transport.cpp`) writes `data_check = 0` instead of the sum, and nothing verifies it. If that is
  right, every payload-carrying message from a modern adbd is refused. Proposed: the codec takes the negotiated protocol
  version (from the peer's `CNXN`, 1b's concern) and checks the sum only below 0x01000001; tests for both versions, and a
  zero checksum accepted only at or above it.
- **note (for 1b):** the 4,096-byte bound is safe only while our `CNXN` advertises `maxdata` 4,096, as upstream does
  (modern adbd otherwise negotiates up to 256 KB and would send larger payloads that we refuse). 1b's `CNXN` must take its
  value from `AdbMessageCodec.MAX_PAYLOAD_BYTES`, with a test tying the two.
- **note:** `readExactly`'s `count == 0` fallback is correct for a stream that returns 0 for a non-zero request (unusual,
  allowed); fine as is.
- **process:** your sandbox cannot write a linked worktree's git metadata (it lives in the main repo's `.git`). From now
  on your work stays uncommitted in your worktree and the lead commits it on your branch with you as author; no wider
  sandbox. Recorded on the board.

Security checklist (lead's pass): agreed with every line of yours; the refusal tests cover all bounds; nothing logs payload
bytes (`toString` reports the length only, tested).

## 7. Sign-off

### codex — signoff, 2026-11-06T19:47Z

codex: AGREE — verified the lead’s checksum finding against AOSP and fixed it in my assigned files.

- **Judged:** the lead is right. AOSP’s [`send_packet` and `check_header`](https://android.googlesource.com/platform/packages/modules/adb/+/dac5ffe21f8e7bccc60041154c27c4198162c6af/transport.cpp) and [`A_VERSION_SKIP_CHECKSUM`](https://android.googlesource.com/platform/packages/modules/adb/+/dac5ffe21f8e7bccc60041154c27c4198162c6af/adb.h) confirm the behavior and cutoff.
- Encoding and decoding now require the negotiated version: below `0x01000001`, send/verify the sum; at or above it, send zero and ignore the received checksum. Updated tests and NOTICE.
- **Measured:** both modern-checksum regressions failed before the fix; afterward, `CHECK_FAST=1 scripts/check.sh` passed: 113 tasks, 507 tests, zero failures/errors/skips; ADB tests 13/13.
- **Security recheck:** caller/capability checks remain N/A; bounds and refusal tests remain intact; no new execution paths, captured data, secret storage, or logging.
- Slice 1b must tie advertised `CNXN` maxdata to `MAX_PAYLOAD_BYTES` (4,096). Real connection/pairing remains unverified; no lead-owned fix remains in §6.
- Changes remain **uncommitted** on `team/codex/t001-adb`. No full gate, phone use, or edits to lead-owned files.

- `claude: AGREE — slice 1a: read every file and both tests; my checksum finding confirmed by Codex from AOSP and fixed with
  tests that failed first; bounds, refusals and secrecy as agreed; committed on the branch as 3168f9e (Codex the
  author); integration gate on engine next.` (2026-11-07)

---

# Round 2: slice 1b (native pairing, Android 12+)

## 1. Proposal (lead), 2026-11-07

T-002 has landed (`698ebcd`); 1b is unblocked. The owner's yes (2026-11-06) covers exactly: NDK and CMake in `:engine`,
BoringSSL through prefab, libc++, `bcpkix-jdk15on` 1.70; our pairing on Android 12+ only (`SSLSockets.exportKeyingMaterial`).
Anything beyond that list is a question for the owner before it is used.

**Codex owns 1b** (branch `team/codex/t001-adb`, fast-forwarded to `engine`). Proposed scope, for Codex to correct:

1. *Native SPAKE2 bridge*: upstream's JNI file(s) for SPAKE2, HKDF and AES-GCM under `engine/src/main/cpp/`, CMake, the
   prefab BoringSSL; no logging of key material, payloads or the pairing code (upstream's logging removed, a test or a
   grep in the gate proves it). ABIs: arm64-v8a and x86_64 only (the S24, the Redmi and the emulator), so the APK grows
   as little as possible: state the growth.
2. *Key store*: the RSA-2048 adb key, generated once, kept in the Android Keystore or encrypted app-private storage (say
   which and why); its public key in adb's format (base64 of the Android RSA struct + " name"). Interface + implementation
   + tests; nothing reads it but the client.
3. *Pairing client*: TLS 1.3 to the pairing port, the exported keying material (label `adb-label\0`, 64 bytes) as the
   SPAKE2 password suffix, the pairing header from 1a, peer info exchange (our public key), bounded reads throughout.
4. *Connection client*: `CNXN` with `maxdata` = `AdbMessageCodec.MAX_PAYLOAD_BYTES` (tied by a test), the `STLS` upgrade,
   `OPEN`/`WRTE`/`OKAY`/`CLSE` for one `shell:` stream; the negotiated version handed to the codec.
5. No callers, no permission, no manifest change, no UI: slice 3 brings the pairing flow. On-device proof is the lead's
   (an adb-hooked dev entry or an instrumented run, agreed in §2), since the phone is the lead's.

**Verified by:** unit tests (key format against a known vector, `CNXN` maxdata tie, the bounded reads' refusals, the
SPAKE2 bridge against the RFC/BoringSSL test vector if one exists); `tools/dexlist.sh` declared diff (new classes only,
under `dev.launcher.engine.adb`); the APK size and native libraries listed; full `scripts/check.sh`; security checklist
(protocol §7) by both. On the emulator (Android 17, Wireless debugging on): the lead pairs and runs `shell:id` once,
expecting `uid=2000(shell)`.

**Risks**: prefab BoringSSL's availability for AGP 8.13 (Codex: check the artifact and version upstream uses);
the build-time cost of the NDK on the owner's machine (one NDK install; state its version); `bcpkix` 1.70's size.

## 2. Discussion

### codex — discuss, 2026-11-07T06:22Z

**Judged from source review:** I agree with the native Android 12+ route and keeping production callers out of 1b, with these amendments. No code, builds or device checks were run this round.

1. **Pin the actual upstream dependencies and toolchain.** Upstream v13.6.0 declares:
   - `io.github.vvb2060.ndk:boringssl:20250114`
   - `org.lsposed.libcxx:libcxx:27.0.12077973`
   - `org.bouncycastle:bcpkix-jdk15on:1.70`, including its `bcprov`/`bcutil` transitives.

   Enable prefab, pass `-DANDROID_STL=none`, and link `boringssl::crypto_static` and `cxx::cxx`; avoid adding a second C++ runtime. These are the declarations in [upstream manager Gradle](https://github.com/RikkaApps/Shizuku/blob/v13.6.0/manager/build.gradle#L171-L176) and [CMake](https://github.com/RikkaApps/Shizuku/blob/v13.6.0/manager/src/main/jni/CMakeLists.txt#L25-L40). Upstream pins **NDK `29.0.13113456`**, independently of libc++’s version ([root Gradle](https://github.com/RikkaApps/Shizuku/blob/v13.6.0/build.gradle#L12-L13)). Its CMake file requires only 3.4.1 minimum; that is not an installed-tool version pin. I propose CMake **3.22.1**, with availability/build compatibility verified during implementation.

   The fork’s pinned manager revision uses the same BoringSSL/libc++ but **`bcpkix-jdk18on:1.80`**. That upgrade is outside D-075’s approval; retain approved 1.70. ([Fork dependencies](https://github.com/thedjchi/Shizuku/blob/608fcf0e49d9cffc53427189cf7e0d5b3a6a23e4/manager/build.gradle#L184-L189)) Artifact declarations are verified; repository resolution and AGP compatibility remain unmeasured.

2. **Name the import boundary precisely.** Adapt `AdbKey.kt`, `AdbPairingClient.kt`, `AdbClient.kt`, necessary exception types from `AdbException.kt`, and protocol constants. Reuse 1a’s message codec and pairing header; do not introduce duplicate upstream parsers. Native scope is `adb_pairing.cpp`, its empty `adb_pairing.h` if retained, and `logging.h` if retaining upstream logging macros. Although upstream’s CMake includes `misc.cpp` in `adb`, the pairing source calls none of its helpers: omit `misc.cpp`/`misc.h`, starter, SELinux and cgroup sources. Update JNI’s hard-coded `FindClass` package, check registration failure, and retain provenance for every adapted file. ([Native source](https://github.com/RikkaApps/Shizuku/blob/v13.6.0/manager/src/main/jni/adb_pairing.cpp), [miscellaneous helpers](https://github.com/RikkaApps/Shizuku/blob/v13.6.0/manager/src/main/jni/misc.cpp))

   Defer `AdbMdns.kt`, pairing service/tutorial, boot workers and root start. Both clients must accept `AdbEndpoint` and use its address and optional `Network`. The fork preserves the resolved host and adds a five-second connect timeout, but its current `connect()` also shadows the socket field with `val socket`; do not copy that cleanup bug. These observations are from the fork’s current branch, not a verified diff of the pinned revision. ([Discovery](https://github.com/thedjchi/Shizuku/blob/master/manager/src/main/java/moe/shizuku/manager/adb/AdbMdns.kt#L54-L66), [client](https://github.com/thedjchi/Shizuku/blob/master/manager/src/main/java/moe/shizuku/manager/adb/AdbClient.kt#L41-L46))

3. **Choose encrypted app-private RSA storage.** Generate software RSA-2048/F4 once; store PKCS#8 encrypted with AES-256-GCM under an Android Keystore wrapping key. Use an atomic file in `noBackupFilesDir`, with bounded reads and confirmed persistence before reporting success. This follows upstream’s key model while avoiding its silent catch-and-regenerate behavior. Direct Keystore RSA would change the existing raw signing path and needs separate interoperability proof. Keep the self-signed TLS certificate and ADB public key derived from the same RSA identity. Public-key encoding is the 524-byte little-endian Android struct, unwrapped base64, space, bounded name and terminal NUL. Remove the private-key log. ([`AdbKey.kt`](https://github.com/RikkaApps/Shizuku/blob/v13.6.0/manager/src/main/java/moe/shizuku/manager/adb/AdbKey.kt))

   Test missing storage, corrupt ciphertext, missing/invalidated wrapping key and write failure separately. Return a typed identity-loss/re-pair result rather than silently replacing a previously paired key. This is an internal result; slice 3 owns its presentation.

4. **Specify protocol and failure behavior.** Pairing uses TLS 1.3, then public `SSLSockets.exportKeyingMaterial(socket, "adb-label\u0000", null, 64)`. Require a completed handshake and non-null 64-byte result; validate six ASCII pairing digits before concatenating their bytes with the exporter bytes. The public exporter is API 31+, matching D-075. ([Android API](https://developer.android.com/reference/android/net/ssl/SSLSockets#exportKeyingMaterial(javax.net.ssl.SSLSocket,%20java.lang.String,%20byte[],%20int)))

   Enforce the expected packet type at each stage, the native SPAKE2 message bound, and exactly 8,192 decrypted peer-info bytes. Remove peer-info/payload logging. Close TLS/socket/native context on every failure and cancellation, with idempotent cleanup. ([Pairing client](https://github.com/RikkaApps/Shizuku/blob/v13.6.0/manager/src/main/java/moe/shizuku/manager/adb/AdbPairingClient.kt))

   Connection tests must cover NUL-terminated `host::`/`shell:` payloads, initial versus negotiated checksum handling, peer maxdata, stream IDs, acknowledgements and premature close. Advertise 4,096 from the codec constant. For this wireless path require STLS; defer classic plaintext AUTH rather than inheriting upstream’s fallback. ([Upstream connection flow](https://github.com/RikkaApps/Shizuku/blob/v13.6.0/manager/src/main/java/moe/shizuku/manager/adb/AdbClient.kt#L40-L105)) Bound connect, handshake, reads and total operation time; a payload bound alone cannot stop a stalled peer.

5. **Make native verification executable.** Replace “RFC/BoringSSL vector if one exists” with client/server bridge tests: matching password succeeds; wrong password, altered ciphertext and malformed SPAKE2 messages fail; repeated cleanup is safe. BoringSSL’s published SPAKE2 tests explicitly retain a TODO for fixed vectors and use two-party exchanges. Run JNI tests on Android, not only the host JVM. ([BoringSSL tests](https://github.com/google/boringssl/blob/master/crypto/curve25519/spake25519_test.cc#L29-L145)) Document upstream’s ADB-specific trust manager in both security reviews and keep it confined to the ADB TLS context. Source reuse is not evidence of an independent security audit.

6. **Run the proof inside an Android app process.** I propose a framework-only instrumentation runner under `engine/src/androidTest`, with its test manifest and no new AndroidX test dependency. The lead runs it on the one shared Android 17 emulator:
   - Open Wireless debugging’s pairing-code dialog; supply the device address, pairing port and transient code to our test client.
   - Pair through our JNI/exporter path, then connect to the **separate connection port** and run `shell:id`; require `uid=2000(shell)`.
   - End the test process, rerun without pairing, reload the persisted identity and require `shell:id` again.
   - Exercise wrong-code rejection and cleanup; never print the code or key material.

   Host `adb pair`, host `adb shell id`, or running our client through shell `app_process` does not prove app-UID Keystore/storage behavior. The production debug build is non-debuggable (`app/build.gradle.kts`), so avoid assuming `run-as` works. The test APK proves the test UID; any later launcher-UID/S24 proof must be labelled separately. Device ownership stays with claude.

7. **Keep approval and ownership limits explicit.** Two ABIs may be used for the proof, but permanently dropping pairing support on 32-bit devices is user-visible scope not covered by the native-dependency yes. Preserve production ABI coverage unless the owner approves that restriction. D-075 also does not authorize SDK/minSdk changes, hidden/bundled Conscrypt, extra test libraries, production debug entry points, permissions or boot behavior. If library lint requires an `INTERNET` declaration, record the existing app permission and demonstrate an unchanged merged manifest, following D-071.

   Before work, amend §4 to assign Codex `engine/build.gradle.kts`, native/CMake files, ADB sources/tests, `androidTest` files and NOTICE. Keep integration as reviewable vendor/adaptation slices. Retain the full gate, both security checklists and declared dex diff; the dex allowance must include Bouncy Castle’s added classes outside our package. Report measured APK growth, packaged native libraries, and native alignment/loading checks. None of those numbers is established by this discussion.

codex: AGREE approach — slice 1b is ready with these amendments, production ABI coverage preserved and the lead running the app-process proof.

### claude — 2026-11-07

Accepted, all seven, with one change:

- **NDK: 29.0.14206865 (r29 stable), not upstream's 29.0.13113456**, which Google's repository lists as `r29-beta1`
  (`repository2-3.xml`, read 2026-11-07). A beta toolchain is no pin worth keeping; the stable release of the same major
  is. CMake 3.22.1 (AGP 8.13's default). Both are installed on the owner's machine by the lead from Google's repository,
  SHA-1 checked (Codex's sandbox cannot write the SDK folder); `ndkVersion` pinned in `engine/build.gradle.kts`. Logged
  as a decision at integration.
- **ABIs:** you are right that dropping 32-bit is scope the owner's yes did not cover. Every ABI BoringSSL's prefab
  ships; the APK growth is reported per ABI so the owner can decide later with numbers.
- **Proof:** the framework-only instrumentation run, by the lead on the shared emulator, exactly as your point 6. The
  test never prints the code; I type it into an adb-provided argument (`-e code`), which lands in the instrumentation's
  arguments only; the run's log is checked for it afterwards (grep, expecting none).
- **STLS only, classic AUTH deferred:** agreed. Timeouts on connect, handshake, each read and the whole operation, each a
  named constant with a test of the stalled-peer path (a socket that accepts and never answers).
- **Key storage:** agreed (software RSA-2048 wrapped by a Keystore AES-256-GCM key, atomic file in `noBackupFilesDir`,
  typed identity-loss result). The Keystore part is checked only in the instrumentation run (JVM tests use a fake wrapper).

## 3. Agreed approach

Codex's seven amendments as written above, with the NDK at r29 stable (29.0.14206865) and CMake 3.22.1. Dependencies
exactly `io.github.vvb2060.ndk:boringssl:20250114`, `org.lsposed.libcxx:libcxx:27.0.12077973`,
`org.bouncycastle:bcpkix-jdk15on:1.70` (+ its `bcprov`/`bcutil`). No callers, permissions, manifest change (merged
manifest shown unchanged), UI, mDNS, boot work or root start. Production ABIs all kept.

- `codex: AGREE approach — slice 1b is ready with these amendments, production ABI coverage preserved and the lead
  running the app-process proof.` (2026-11-07T06:22Z)

## 4. Split

| Sub-task | Owner | Files owned | Branch | Verified by |
| --- | --- | --- | --- | --- |
| 1b: native bridge, CMake, key store, pairing and connection clients, their unit and instrumentation tests, NOTICE | codex | `engine/build.gradle.kts`, `engine/src/main/cpp/**`, `engine/src/main/java/dev/launcher/engine/adb/**`, `engine/src/test/java/dev/launcher/engine/adb/**`, `engine/src/androidTest/**`, `engine/src/main/adb-NOTICE`, `engine/lint-baseline.xml` only if lint demands it (say why) | `team/codex/t001-adb` | unit tests; `scripts/check.sh`; dex diff incl. Bouncy Castle; APK growth per ABI |
| NDK/CMake install; emulator proof (pair, `shell:id`, reload, wrong code); integration | claude | nothing in Codex's files | `engine` | the instrumentation run's output; the log grep |

Note: T-004 (slice 6) edits `engine/build.gradle.kts` too if `:engine` needs lifecycle declared. If so, the lead takes
that one line at integration and rebases; Codex owns the file meanwhile.

## 5. Reports

### codex — work, 2026-11-07T07:28Z

- **Commits:** Left uncommitted on `team/codex/t001-adb`. Proposed messages: `Vendor SPAKE2 bridge and pin native toolchain`; `Add RSA identities and TLS certificates`; `Persist wrapped identities with explicit loss results`; `Add bounded STLS shell transport`; `Test hostile transcripts and stalled peers`; `Add public-exporter ADB pairing`; `Add framework Android pairing proof`. Each gets `Co-Authored-By: Codex <noreply@openai.com>`.
- **What changed:** Native pairing, encrypted atomic identity storage, bounded connection/pairing clients, tests and notices. NDK pinned to `29.0.14206865`; all four production ABIs retained.
- **Verified — measured:** Full `scripts/check.sh` passed: 421 app + 109 engine JVM tests, 58 Python tests, all five shell suites. Capture suite: 74 scenarios, 746 checks, zero failures. Instrumentation APK built; Gradle daemon stopped.
- **APK growth — measured:** Universal: **23,683,143 → 31,979,509 bytes (+8,296,366)**. Aligned, signed single-ABI repacks: arm64 **+5,448,111**; armv7 **+5,104,049**; x86 **+5,464,489**; x86_64 **+5,525,932 bytes**.
- **Dex/manifest — measured:** Slice-baseline `dexlist.sh` check passed: **4,719 new classes**—4,681 Bouncy Castle, 36 ADB, two prefab resource classes—and one changed codec method count. Production merged manifest identical. Declaration: `.gradle/T001-1b.delta.expect`. New native libraries pass 16 KB ELF and APK alignment checks.
- **Security — judged:** No external production entry or component-to-shell route; bounded inputs/time; capability plumbing untouched; no captures; keys private and unlogged. Refusal tests passed. ADB-only self-signed TLS trust is documented.
- **Not verified:** Android JNI loading, real pairing, Keystore persistence/loss and wrong-code interoperability. Lead runs instrumentation modes `selftest`, `pair`, fresh-process `reload`, then `wrong-code`. Phone/emulator untouched.
- **Surprises:** The repository’s historical dex reference already differs before this slice; validation therefore used a private slice-baseline harness. No tracked allowance or lint-baseline file changed.

Automatic approval review blocked deleting generated `engine/.cxx` (“blocked by policy”); it remains untracked and must be excluded from commits.

### claude — device proof, 2026-11-07 (emulator, Android 17, x86_64; test APK uid, not the launcher's)

Built `:engine:assembleDebugAndroidTest` from `8293b8e` (Codex's work, committed by the lead on `team/codex/t001-adb`
with Codex as author, as one commit listing its seven steps). Measured, `am instrument` results:
- `selftest`: PASS (the JNI library loads; native SPAKE2/HKDF/AES-GCM exchanges and storage tests in an app process).
- `pair` (Wireless debugging's pairing-code dialog, host 10.0.2.16, its pairing port and the connect port): PASS, and
  `shell:id` contained `uid=2000(shell)`; Settings then listed the pairing as "not-a-launcher".
- `reload` (a fresh process, no code): PASS: the wrapped identity reloaded and `shell:id` again `uid=2000(shell)`.
- `wrong-code` (a new dialog, a code that was not its): PASS: refused as `AdbPairingRejectedException`.
- Log check: **no line from our test process** carries either code. Three system lines do: `AdbDebuggingManager:
  updateUIPairCode` (system_server logs every code it shows, Android's own) and adbd's log of my `adb shell am instrument
  ... -e code` command line (the test method; production never passes a code through adb).

## 6. Cross-check

### claude — cross-check of 1b, 2026-11-07

Read `AdbPairingClient`, `AdbTransport`, the trust manager in `AdbKey`, the report; grepped every new Kotlin and C++ file
for logging (none). Ownership kept to §4's files (plus `.gitignore`'s `.cxx/`, the lead's).
- **note (slice 2's checklist):** `AdbKey.kt:82` trusts any server certificate (adb's own model, upstream's too). On the
  pairing port SPAKE2 over the exporter authenticates the peer; on the **connect port nothing does**: a host on the same
  network answering there would get our `shell:` commands. Slice 2's one-time capability is what keeps that from
  mattering (an impostor cannot hand a binder our app accepts); say so explicitly in slice 2's checklist, or pin adbd's
  certificate from pairing if that proves possible.
- **note (slice 2):** `AdbTransport.kt` closes every transport at 30 s (`OPERATION_MS`): right for one-shot commands;
  slice 2's starter must fit inside it or use its own bound.
- **note (owner):** APK +8.3 MB universal (23.7 to 32.0 MB), ~5.1-5.5 MB per single-ABI install: reported to the owner as
  measured, no action asked.
- **process:** `codex.sh` placed the report under round 1's §5 (the first `## 5.` heading); moved by the lead. The script
  should take the last heading of that number; the lead's to fix (not in this slice).

Security checklist (lead's pass): caller: no production caller, no component, manifest unchanged (Codex measured); inputs:
code validated as six ASCII digits, every packet bounded (type, size), peer info exactly 8,192 bytes; escape paths: none
(the client issues only what its caller asks; no caller yet); capabilities: none; captures: none; secrets: key wrapped
by the Keystore, code and exporter zeroed after use, nothing logged by us (device grep above); refusal tests: wrong code
refused on the device, hostile transcripts and stalled peers in the unit tests.

## 7. Sign-off

- `claude: AGREE — slice 1b: device proof (selftest, pair, reload, wrong code) on the emulator, no code in our logs,
  bounds and timeouts read, security checklist above; three notes for slice 2 and the owner, none blocking.` (2026-11-07)

### codex — signoff, 2026-11-07T08:09Z

codex: AGREE — reviewed `8293b8e`, round 2’s device proof and §6 cross-check; no blocking fixes were requested.
- **Judged (source):** all seven security checklist items covered: no production entry point or caller; bounded inputs and time; no component-to-shell route; capability wiring unchanged; no captures; private wrapped keys without logging; refusal tests present.
- **Measured evidence reviewed:** saved full gate and declared dex check passed; manifest hashes match. Lead reports emulator selftest, pair, fresh-process reload and wrong-code rejection passed under the test APK UID.
- **Slice 2 requirements retained:** connect-port TLS does not authenticate the server; binder handover needs its one-time capability. Starter execution must respect the 30-second bound. Test-process logs were clean; system logs exposed the adb-supplied codes.
- No edits or device use this round. Launcher-UID and S24 behavior remain unverified.
