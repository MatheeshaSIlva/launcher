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

# Round 3: slice 2 (our starter and the binder handover)

## 1. Proposal (lead), 2026-11-07

**Goal.** With wireless debugging paired (1b), the launcher starts its own shell-uid process hosting `ShellService` and
gets its binder back, with no Shizuku involved, behind a developer switch. Shizuku stays the default host until slice 5.
The owner (2026-11-07): carry on with slice 2.

**Approach.**

1. *`ShellHost`* (`dev.launcher.app`, beside `ShizukuLink`): the interface the rest of the app sees today through
   `ShizukuLink` (`service`, `state()`, `connect()`, `awaitService`, listeners, `clientToken`). `ShizukuLink` keeps its
   name and its 74 call sites; inside, it holds the active host: `ShizukuHost` (today's code, moved, unchanged) or
   `OwnHost` (new). The choice is a developer switch (adb broadcast with the DUMP sender check, as T-004's), read at start;
   default Shizuku. Everything that runs on connect (`grantSelf`, `Watchdog.sync`, flags) runs the same for both.
2. *The starter* (`dev.launcher.engine.shell.Starter`, a `main()` run by `app_process` as uid 2000): prepares a main
   looper, makes a `ShellService`, hands its binder to the app (step 3), links to the death of the app's token and exits
   when the app dies (Shizuku's `daemon(false)` behaviour, so no orphan; boot restart is slice 4), then loops. A new
   build replaces an old process (the build stamp is in the handover; the app destroys a service of another build).
3. *The handover.* The app starts an attempt: a 32-byte random capability from `SecureRandom`, kept in memory with a
   15 s expiry, one use. It opens an ADB `shell:` stream (1b's client) running
   `CLASSPATH=<our APK's sourceDir> setsid app_process /system/bin --nice-name=launcher-shell
   dev.launcher.engine.shell.Starter &` and **writes the capability to the stream's stdin** (never in argv or the
   environment, which any uid-2000 process can read in `/proc`); the starter reads one line, closes stdin, detaches.
   The starter calls our package's provider (`IActivityManager.getContentProviderExternal`, as upstream's
   `ServiceStarter`) with `call("shell-binder", extras = {binder, capability, build})`. The provider
   (`ShellBinderProvider`, exported, no permission, every other method refuses) accepts only when the calling uid is
   2000, the capability matches the live attempt in constant time and has not expired; the attempt is consumed whatever
   the result. A refused call is logged by reason, never with the capability.
4. *Bounds.* The whole start fits in 1b's 30 s transport bound: the stream closes once the starter has detached; the
   app waits at most 15 s for the handover, then reports "start timed out" and clears the attempt.

**Why this answers 1b's two notes.** The connect port does not authenticate adbd, so a host answering there gets our
start command: it learns the capability but cannot use it, since only a uid-2000 process **on this phone** can call our
provider with it, and the attempt expires and is single-use. The 30 s bound: step 4.

**Verification.**
- Unit: the attempt (capability length, expiry, single use, constant-time compare, consumed on refusal); the provider's
  refusals (wrong uid, no attempt, wrong capability, expired, used twice, wrong method), all with fakes, no Android
  binder; `ShellHost` selection; the starter's stdin read (one line, bounded at 128 bytes, refuses more).
- Emulator (measured, Android 17, the launcher's own uid this time, not the test APK's): switch on, pair once through the
  1b proof's runner, start: `identity()` answers `uid=2000`; D-072's emulator list on our host (status bar flags set and
  dropped with the app's token, animation scales, `runShell`, the watchdog's heartbeat); a second start with the
  capability replayed is refused; `adb shell` handing a binder with no attempt is refused; force the app's death: the
  starter exits within 1 s. Then switch off: Shizuku again, nothing else changed (the smoke script).
- `tools/dexlist.sh` (ShizukuLink's body moves into ShizukuHost; new classes), `scripts/check.sh` full; security
  checklist (protocol §7) by both.
- S24: the same start once with the owner's go (Wireless debugging is his phone's setting); reported unverified until then.

**Risks and open questions.**
- *Manifest:* the provider is a new exported component. No new permission. It is attack surface, so it is in the
  checklist; any app can call it, and it refuses everything but uid 2000 with a live capability.
- *adbd's cgroup:* CLAUDE.md's pitfall (adbd restarts SIGKILL what was started through it) applies to our process as it
  did to Shizuku's; `setsid` does not escape the cgroup. Same answer as before (USB "No data transfer"); slice 4's boot
  restart covers the rest. Codex: does upstream's starter do anything more here?
- *`getContentProviderExternal`* is hidden API from the shell's side, as upstream uses it; the shell uid is not subject
  to our app's hidden-API policy. Codex: check upstream v13.6.0's `ServiceStarter` for the exact call per API level
  (Android 12 to 17).
- *stdin over the ADB stream:* 1b's `AdbSession.shell` is one-shot (open, read to close). It needs a write of one line
  after `OPEN`/`OKAY`; Codex owns `adb/` and is best placed to add `shell(command, stdin)`.
- *Proposed split:* **codex**: `AdbSession`'s stdin write and its tests; the attempt/capability class and its tests;
  the starter's stdin reader; the §7 checklist first pass. **claude**: `ShellHost`/`ShizukuHost`/`OwnHost`, the provider,
  the starter's binder and lifecycle, the manifest entry, the switch, every device check.

## 2. Discussion

### codex — discuss, 2026-11-07T10:43Z

I agree with keeping Shizuku as the default, preserving the `ShizukuLink` facade, and using an expiring capability over stdin. I would amend the launch, refusal and lifecycle contracts before work starts.

### Upstream check — judged from source

- **`ServiceStarter` is a provider handover, not a broadcast.** In v13.6.0 it prepares the looper, creates the service, obtains the manager provider, sends the binder/token, retains the returned server binder and exits when that binder dies. It releases the external provider reference in `finally`. Its retry force-stops the manager: **do not copy that retry**, because killing our app also loses the pending capability. Our starter should exit on refusal or failed handover. [Tagged `ServiceStarter.java`, lines 55–147](https://github.com/RikkaApps/Shizuku/blob/v13.6.0/starter/src/main/java/moe/shizuku/starter/ServiceStarter.java#L55-L147).
- **The shell path does not escape adbd’s cgroup.** The native starter forks, calls `setsid`, changes directory and redirects all three standard descriptors to `/dev/null`. Its cgroup moves and mount-namespace change are guarded by `uid == 0`. That supports the proposal’s warning and `CLAUDE.md:482–484`; `setsid` alone is insufficient. Boot restart does not establish recovery after a later adbd restart. [Tagged `starter.cpp`, lines 107–194](https://github.com/RikkaApps/Shizuku/blob/v13.6.0/manager/src/main/jni/starter.cpp#L107-L194).
- **Provider compatibility needs two separate adapters.** The inspected `ActivityManagerApis` uses `getContentProviderExternal(authority, userId, token, tag)` for API 29+, followed by `removeContentProviderExternal(authority, token)`. The inspected `IContentProviderCompat` uses `call(AttributionSource, authority, method, arg, extras)` for API 31+, constructing the attribution with the actual UID; its fallback on `LinkageError` is the older six-argument call. There are no separate 12/13/14/15/16/17 branches in that helper. [Activity-manager adapter](https://github.com/RikkaW/HiddenApi/blob/master/compat/src/main/java/rikka/hidden/compat/ActivityManagerApis.java#L53-L72), [provider adapter](https://github.com/RikkaApps/Shizuku/blob/master/starter/src/main/java/moe/shizuku/starter/util/IContentProviderCompat.java#L14-L28).

**Source limitation:** I fetched the tagged `ServiceStarter` and native starter, but the tagged compatibility helper repeatedly failed to fetch. The helper above is from `master`, so I cannot claim its exact v13.6.0 contents or Android 17 execution are verified. Record the pinned helper before implementation; prove the reflected signatures on Android 17. Keep reflection restricted to fixed framework names/signatures, without adding upstream’s hidden-API dependencies.

### Changes required

1. **Specify a working stdin/detach sequence.** The proposed trailing `&` in a non-interactive shell can give the background command `/dev/null` as stdin unless explicitly redirected. Conversely, leaving stdout/stderr attached can keep the ADB stream open. Preserve the original input descriptor explicitly, keep the stream alive until the capability is read, then release **all** inherited ADB descriptors. Closing `System.in` alone is insufficient.

   Keep the non-interactive raw shell path: AOSP selects raw mode for a non-empty command. Verify that the capability is never echoed. Quote `sourceDir` safely; use only the installed APK and a fixed starter class. No capability in shell text, argv, environment or temporary files. [AOSP shell-service defaults](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/daemon/services.cpp#103).

   For the wire format, choose exactly **64 lowercase ASCII hex characters plus LF**, representing 32 random bytes. Reject malformed, truncated and overlong input; retain the 128-byte ceiling and add a read deadline.

2. **Make stdin writing a protocol state machine.** `AdbClient.kt:62–93` currently accepts only incoming `WRTE`/`CLSE` after `OPEN`’s `OKAY`. The extension must handle outgoing `WRTE` acknowledgements, negotiated `peerMax`, matching stream IDs, interleaved output, premature close and stalled acknowledgements. ADB `CLSE` closes the stream; it is not a stdin half-close. The starter must finish reading at LF without waiting for EOF.

3. **Do not consume the attempt for arbitrary refusals.** “Consumed whatever the result” lets another app cancel a legitimate start merely by calling the exported provider. Check the Binder calling UID and method **before** touching pending state. Wrong UID, unknown method and incorrect capability must not consume it. Atomically claim the attempt only after a valid, unexpired capability matches; any subsequent build/binder validation failure consumes that claimed attempt.

   Use a monotonic clock, one pending attempt and a generation identifier. Old timeout/cancellation callbacks must not clear a newer attempt. Validate exact capability length, bounded build text and a non-null binder; catch malformed extras without logging their contents. No URI grants or provider operation should expose the service or app token.

4. **Complete the service-side caller check.** The provider authenticates handover, but `ShellService.onTransact` currently delegates without a UID check (`ShellService.kt:169–174`), including access to `runShell` and `destroy` (`:21–32`). The own-host binder must enforce the installed launcher’s UID on every application transaction, even if a binder handle is forwarded elsewhere. Resolve that UID from the fixed installed package, not network input. Preserve the Shizuku path’s required control calls separately.

   The capability authenticates a local handover; it **does not authenticate adbd**. A fake connect endpoint receives it. State the narrower guarantee: an off-device endpoint alone cannot satisfy the local shell-UID check. Do not claim protection against a cooperating or compromised local shell process.

5. **Bound the starter independently.** The 30 s timer in `AdbTransport.kt:19–26` closes a socket, not a detached process. Add a starter deadline covering stdin, provider acquisition/call and the lifecycle handshake; refusal, timeout or failure to link the app token must terminate it. Retain the token strongly, and establish death monitoring before reporting the service ready.

   Define the 15 s deadline from attempt creation and the 30 s transport deadline from transport creation; do not add another 15 s wait afterward. Late handovers are refused. Test that the accepted service survives transport closure, and that failed starts leave no process behind.

6. **Keep shared connection effects in the facade.** Preserve `ShizukuLink.State`, `clientToken`, listener threading and all public signatures. Move `grantSelf`, `Watchdog.sync`, flag restoration and service-loss handling into one facade-owned path (`ShizukuLink.kt:45–77`); neither host should duplicate them. Register Shizuku callbacks only for the selected host, and ignore callbacks from obsolete generations. `awaitService` must invoke the selected host rather than retaining its current Shizuku permission check (`:160–170`).

   Repeated connect calls should join one start. Build replacement must operate only on an authenticated service already owned by this host; never destroy an arbitrary binder supplied with a mismatched build string.

### Missing verification and split details

The existing proof runner uses `Instrumentation.context` and explicitly stores keys under the **test APK UID** (`AdbProofInstrumentation.kt:77–81`). Pairing there does not provision the launcher’s Keystore/private identity. The lead needs an explicit DUMP-gated route that performs pairing **inside the launcher process**, then proves start and fresh-process identity reload there. Keep codes and capabilities out of broadcast extras that get logged.

Add actual Binder refusal tests alongside the pure policy tests: wrong-UID call leaves the attempt usable, simultaneous valid handovers accept exactly once, late callbacks cannot replace a newer service, and a forwarded service binder refuses another app. JVM fakes cannot establish those Binder identities. Constant-time comparison is a source-review claim; unit timing tests cannot prove it.

I take the proposed share. Before work, name exact files in §4:

- Codex: `AdbClient.kt`, its transcript tests, new pure attempt/capability class and tests, new bounded stdin reader and tests.
- Claude: host/facade, provider, starter orchestration and deadlines, service caller enforcement, manifest/switch, launcher-UID proof and device checks.
- Assign any adapted-source NOTICE update explicitly. Keep this as several reviewable slices under D-070’s edit-size rule.

### Security checklist — first pass, judged design requirements

- [ ] **Callers:** DUMP-gated control route; provider UID checked before parsing; own-service transactions restricted to launcher UID.
- [ ] **Bounds:** exact capability format, bounded extras/stdin/output, atomic attempt state, monotonic expiry and independent starter deadline.
- [x] **Escape paths:** proposal adds no KDL/Lua/package-to-shell route; fixed starter/package only, consistent with `ENGINE_SPEC.md:70`.
- [ ] **Capabilities:** handover capability required and single-use; component `uses` checks remain separate and unchanged under D-068/D-074.
- [x] **Captures:** no new capture or persistence path proposed.
- [ ] **Secrets:** private identity storage retained; capability only in memory/stdin/Binder extras; fixed refusal reasons, no echoed input or exception payloads.
- [ ] **Refusals:** policy tests plus real Binder, replay, timeout, stalled stdin and failed-lifecycle tests required.

No edits, builds or device use this round. These are source judgments; slice 2 has no new measured evidence.

codex: NOT YET — agree with the direction; resolve stdin detachment, refusal consumption, service caller enforcement and the launcher-UID proof before marking the approach ready.

### claude — 2026-11-07

Accepted, all of it; three of these (refusal consumption, the service's own caller check, the launcher-uid proof) are
holes in my proposal, not refinements. The amended plan:

**Upstream.** No retry that force-stops the app; the starter exits on refusal, timeout or a failed handover. The two
adapters (`getContentProviderExternal(authority, userId, token, tag)` + `removeContentProviderExternal` in `finally`;
`call(AttributionSource, …)` on 31+, the six-argument call as fallback) are written from fixed framework names and
signatures, with the helper's revision pinned in `adb-NOTICE` (the lead's, as owner of the starter) and both signatures
proved on the Android 17 emulator before anything else is built on them (the first step of my share). The guarantee is
stated as you put it: an off-device endpoint alone cannot complete a handover; nothing here defends against a
cooperating or compromised local shell process.

1. **Start sequence.** `exec` so the shell is replaced (no `sh` waiting on a child, no `&`):
   `exec app_process -Djava.class.path='<sourceDir, single-quoted, from our own ApplicationInfo>' /system/bin
   --nice-name=launcher-shell dev.launcher.engine.shell.Starter`. The starter reads exactly 64 lowercase hex + LF from
   fd 0 (128-byte ceiling, 5 s read deadline, refuses malformed, short and long), then `Os.setsid()`, ignores SIGHUP,
   `dup2`s `/dev/null` onto fds 0, 1 and 2 and closes every other inherited descriptor; adbd sees EOF and the stream
   ends. Raw mode (non-empty command), nothing echoed: checked on the emulator by reading the stream's output. Whether
   adbd signals the starter when the stream closes is **measured first** (the service must survive the transport's
   close; a failed start must leave no process): if it does, the fallback is a double `exec` through `setsid -f` if the
   phone's toybox has it, decided by measurement and written here.
2. **stdin write.** Yours, as a state machine: outgoing `WRTE` and its `OKAY`, `peerMax`, matching stream ids,
   interleaved output, premature `CLSE`, stalled acknowledgements; no half-close (the reader stops at LF).
3. **Attempts.** Calling uid and method checked before any state is touched; wrong uid, unknown method and a wrong
   capability leave the attempt alive. A valid, unexpired match claims it atomically; any later failure (build text,
   binder) consumes the claimed attempt. Monotonic clock (`SystemClock.elapsedRealtime`), one pending attempt with a
   generation id, so a stale timeout cannot clear a newer one; exact length, bounded build text (64 chars), non-null
   binder; malformed extras caught, never logged; no URI grants.
4. **The service checks its caller.** In own-host mode `ShellService.onTransact` refuses every application transaction
   whose calling uid is not the launcher's, resolved once in the starter from the fixed package name through the
   package manager (never from the stream). The Shizuku path keeps its behaviour (the starter passes the mode; Shizuku's
   user service does not). A refusal test with a forwarded binder from another uid (the engine test APK) on the emulator.
5. **Starter deadline.** One 20 s deadline from its start over stdin, provider acquisition, the call and linking to the
   app's token; any failure or timeout exits the process. The token is held strongly and death-linked before the
   service is reported ready. Attempt expiry: 15 s from its creation; transport: 30 s from its creation; no extra wait
   after either. Late handovers are refused.
6. **Facade.** `ShizukuLink` keeps `State`, `clientToken`, the listeners' threading and every public signature, and owns
   the one connect path (`grantSelf`, `Watchdog.sync`, flags, service loss) that both hosts feed. Shizuku's callbacks
   are registered only when Shizuku is the selected host; callbacks of an obsolete generation are ignored;
   `awaitService` asks the selected host; repeated `connect()` joins the start in flight; a build replacement destroys
   only an authenticated service this host started.

**Launcher-uid proof.** A DUMP-gated route inside the launcher process (`TEST_SHELL --es do pair|start|identity|stop`)
pairs with the launcher's own Keystore identity and starts there. The pairing code and port never travel in broadcast
extras: they are pushed to the app's inbox (`dev-in/adb-pair.txt`), read once and deleted by the route. Then the
fresh-process reload (kill the launcher's process by `am kill` after a home press, not force-stop: CLAUDE.md's
pitfall) and a start with the stored identity.

**Refusal tests on the emulator,** real binders: a wrong-uid call (the engine test APK) leaves the attempt usable; two
simultaneous valid handovers, exactly one accepted; a late callback cannot replace a newer service; a forwarded service
binder refuses another app; replay refused; a stalled stdin and a failed link both leave no process. Constant-time
comparison is a source-review claim (`MessageDigest.isEqual`), said so.

**Slices** (D-070's size rule): **2a, codex**: `AdbClient.kt` (stdin write) and its transcript tests;
`engine/.../engine/shell/StartAttempt.kt` (the attempt and capability, pure) and tests;
`engine/.../engine/shell/CapabilityReader.kt` (the bounded stdin read, pure over an `InputStream` and a clock) and tests.
No callers. **2b, claude**: `ShellHost`/`ShizukuHost`/`OwnHost`, `ShizukuLink.kt`, the provider, `Starter.kt`
(orchestration, adapters, deadline, detach), `ShellService.kt` (the caller check), the manifest entry, the switch and
`TEST_SHELL` route, `adb-NOTICE`, every device check. 2b starts with the two measurements above (adapters, adbd's
signal on close) while you build 2a.

### codex — discuss, 2026-11-07T10:54Z

**Judged from source review:** the amended attempt policy, service caller check, facade, launcher-UID pairing route and 2a/2b ownership resolve my previous objections. Two details still need correction before freezing §3/§4:

1. **A clock cannot bound a blocking `InputStream.read()`.** The proposed `CapabilityReader.kt` contract (`T-001:692–693`) cannot enforce the 5 s deadline when no byte arrives. Keep my reader as the pure bounded parser; assign deadline enforcement explicitly to your `Starter.kt`, using a deadline-aware input adapter, such as fd polling with the remaining time. Android exposes [`Os.poll(fds, timeoutMs)`](https://developer.android.com/reference/android/system/Os#poll(android.system.StructPollfd[],%20int)). The independent 20 s watchdog must also terminate the process while provider acquisition/call is blocked. Emulator refusals should cover **zero stdin bytes**, a partial line without LF, and a blocked handover; checking the clock between successful reads does not prove these bounds.

2. **Make descriptor cleanup precise.** “Closes every other inherited descriptor” (`T-001:656`) must mean identified ADB descriptors and duplicates, never an indiscriminate close of every fd above 2 after entering Java. Preserve runtime-owned descriptors: Binder itself retains an open driver fd ([AOSP `ProcessState.cpp`](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/main/libs/binder/ProcessState.cpp#596)). Redirect 0/1/2, close the temporary `/dev/null` descriptor and any demonstrated ADB duplicates, and measure stream EOF plus a working service call afterward. Your adapter/detachment measurements remain the right first gate; neither `setsid` nor the fallback is established until those pass.

The remaining proof detail is evidential: after `am kill`, confirm the old launcher PID disappeared and a new PID loaded the stored identity. Issuing the command alone is insufficient evidence of a fresh process. Keep the inbox pairing file bounded and delete it on failure as well as success.

I accept the named split, including your ownership of NOTICE and all Android adapters. No edits, builds or device use this round; both agents’ implementation security checklists remain required under protocol §7.

codex: NOT YET — resolve who enforces blocked-read deadlines and specify descriptor cleanup that preserves the Java runtime; the authentication, facade and ownership approach otherwise stands.

### claude — 2026-11-07 (round 2)

Both accepted. (1) `CapabilityReader` is the pure bounded parser over bytes it is given; `Starter.kt` owns the
deadline: it reads fd 0 through `Os.poll` with the time remaining, and a separate 20 s watchdog thread calls
`exitProcess` whatever the main thread is blocked in (provider acquisition, the call). The emulator refusals add zero
stdin bytes, a partial line without LF and a blocked handover. (2) Cleanup is exactly: `dup2` of a `/dev/null` fd onto
0, 1 and 2, close that temporary fd, and close only ADB descriptors the measurement shows were inherited (listed from
`/proc/self/fd` before and after, by target); nothing the runtime opened (binder, ashmem, the APK) is touched. Measured:
the stream's EOF and a working service call after cleanup. The `am kill` proof records the old and new pids and the
identity reload's log line in the new one; the inbox file is bounded (256 bytes) and deleted on failure too.

### codex — discuss, 2026-11-07T10:56Z

**Judged from source and discussion review:** Round 3’s §3 and §4 match the agreement; no amendments needed.

- The round-2 correction assigns the 5 s stdin deadline and independent 20 s watchdog to `Starter.kt`; my `CapabilityReader` remains a pure bounded parser.
- Cleanup preserves runtime descriptors and requires measured stream EOF plus a working service call. Adapter compatibility and detachment remain implementation gates.
- Handover authentication, service UID enforcement and real-Binder refusal tests preserve `ENGINE_SPEC.md`’s “one AIDL to one caller” requirement.
- I accept §4’s split: Codex owns the stream state machine, attempt policy and parser with tests; Claude owns Android integration, NOTICE and device proof. Both complete protocol §7.
- Nothing blocking is missing. No edits, builds or device use this round; implementation and S24 behavior remain unverified.

codex: AGREE approach — §3 and §4 capture the agreed authentication, deadlines, descriptor cleanup, verification gates and ownership for slice 2.

## 3. Agreed approach

**Agreed 2026-11-07 (claude and codex, two rounds; codex's line below once it confirms).** The proposal as amended by
both claude entries above. In short:
- **Start:** `exec app_process` with the APK's quoted `sourceDir` and the fixed `Starter` class; 64 hex + LF on stdin
  (never argv, environment or files); the starter polls fd 0 against its deadline, then `setsid`, ignores SIGHUP,
  redirects 0/1/2 to `/dev/null` and closes only measured ADB descriptors. **Gate:** the two Android 17 measurements
  first (both provider adapters' signatures; the service surviving the stream's close with a working call), else the
  measured fallback is written here before anything is built on it.
- **Handover:** our exported provider checks the calling uid (2000) and method before touching state; only a valid,
  unexpired capability claims the one pending attempt (monotonic clock, generation id); later failures consume it.
  Bounded extras, nothing logged but fixed reasons, no URI grants. No retry that stops the app.
- **Service:** in own-host mode `ShellService.onTransact` serves only the launcher's uid (resolved from the fixed package).
- **Bounds:** attempt 15 s from creation; transport 30 s from creation; starter 20 s from start, enforced by a watchdog
  thread; no extra waits; late handovers refused.
- **Facade:** `ShizukuLink`'s public surface unchanged; one connect path for both hosts; callbacks only for the selected
  host and its current generation; Shizuku stays the default (developer switch, DUMP-gated).
- **Proof:** in the launcher's own process (`TEST_SHELL` route; pairing data through the inbox, bounded, deleted either
  way); `am kill` with old and new pids and the reload logged; real-binder refusal tests (wrong uid keeps the attempt,
  two valid handovers accept one, late callback, forwarded binder, replay, zero/partial stdin, blocked handover, failed
  link: no process left). The guarantee is local: an off-device endpoint cannot complete a handover; a compromised local
  shell is out of scope. Constant-time comparison is a source claim.

- `codex: AGREE approach — §3 and §4 capture the agreed authentication, deadlines, descriptor cleanup, verification
  gates and ownership for slice 2.` (2026-11-07T10:56Z)

## 4. Split

| Sub-task | Owner | Files owned | Branch | Verified by |
| --- | --- | --- | --- | --- |
| 2a: stdin write on the ADB stream (state machine) | codex | `engine/src/main/java/dev/launcher/engine/adb/AdbClient.kt`, `engine/src/test/java/dev/launcher/engine/adb/AdbSessionTest.kt` | `team/codex/t001-adb` | transcript tests (acks, peerMax, ids, interleaved output, premature close, stalls); `scripts/check.sh` |
| 2a: the start attempt and capability (pure) | codex | new `engine/src/main/java/dev/launcher/engine/shell/StartAttempt.kt` and its test | `team/codex/t001-adb` | unit tests (format, expiry, single claim, generation, refusals that keep it) |
| 2a: the bounded capability parser (pure) | codex | new `engine/src/main/java/dev/launcher/engine/shell/CapabilityReader.kt` and its test | `team/codex/t001-adb` | unit tests (exact, short, long, malformed, no LF) |
| 2b: measurements, then hosts, facade, provider, starter, service check, manifest, switch, `TEST_SHELL`, NOTICE | claude | `ShizukuLink.kt`, new `ShellHost.kt`/`ShizukuHost.kt`/`OwnHost.kt`, new `ShellBinderProvider.kt`, new `engine/.../engine/shell/Starter.kt`, `ShellService.kt`, the manifests, `adb-NOTICE`, the test route | `team/claude/t001-starter` | emulator measurements and refusal tests (§3); `tools/dexlist.sh`; `scripts/check.sh` |
| §7 checklist | both | (thread) | | both answer each line in §6 |

## 5. Reports

### claude — 2b gate measurements, 2026-11-07 (emulator, Android 17 sdk 37, x86_64; host adb `shell -T`, not yet our client)

Measured with a measurement build on `team/claude/t001-starter` (the provider accepting any uid-2000 call; never to
`engine` in that form):
- **Adapters pass.** `getContentProviderExternal(String,int,IBinder,String)` exists and returned our provider; its
  `call` overloads are `(String,String,String,Bundle)` and `(AttributionSource,String,String,String,Bundle)`; the
  latter with `AttributionSource.Builder(2000).setPackageName("com.android.shell")` delivered the binder: the launcher
  logged `identity()` as `uid=2000 ... android=17 sdk=37`. Start to ready: **61 ms**. Hidden-API reflection from
  `app_process` as shell was not blocked.
- **`setsid` is refused (EPERM) and not needed**: adbd already makes its child a session leader before the `exec`.
  Kept as a best-effort call, logged; the plan's "then setsid" is corrected to this.
- **The stream does not end at stdout's EOF.** adbd waits for its child, so the stream stays open while the starter
  lives. It ends when the client closes it: with the host's adb client killed, the starter **survived** (alive, parent
  adbd, no exit logged). So the app closes the stream itself once the handover is accepted (before the attempt's 15 s,
  inside the 30 s transport bound). To repeat with our own v1 `shell:` client in the proof.
- **Descriptors.** Before cleanup: 0 and 1 = one socket, 2 = another (adbd's), 3 = a unix datagram socket (the log's
  writer: not in adbd's table), 5/6 = a pipe (not adbd's), the runtime's jars, binder (59). After `dup2` of
  `/dev/null` onto 0-2: no descriptor shared with adbd remains, binder untouched. So "close only measured ADB
  descriptors" is: none beyond 0-2 on Android 17.
- Not yet measured: a working service call after the stream's close (the provider kept the binder; the call comes with
  the `TEST_SHELL` route), our own client, the S24.

### codex — work, 2026-11-07T11:38Z

- **Commits / surprises:** none: sandbox denied `D:/Launcher/.git/worktrees/launcher-codex/index.lock`. Lead must commit the six assigned files. Nothing pushed.
- **Changed (judged):** acknowledged stdin chunks with interleaved output; `StartAttempt.begin/claim/clear`; strict `CapabilityReader.read`; their tests. No callers.
- **Verified (measured):** fast and full gates printed `CHECK PASSED`; 550 unit tests, zero failures/errors/skips; 5/5 shell suites; capture suite 74 scenarios/746 checks. `git diff --check` passed; `./gradlew --stop` confirmed no daemons. Log: `.gradle/t001-2a-full.log`.
- **Not verified:** Android/Binder integration, real blocked-read deadlines, detachment or device behavior; these remain slice 2b.
- **§7 callers (judged/JVM-tested):** wrong UID/method cannot claim or consume an attempt; actual Binder identity awaits 2b.
- **§7 bounds (judged/JVM-tested):** stdin ≤4096 bytes, parser ≤65 reads, output ≤1 MiB, expiry 15 s; starter enforces blocking-read deadlines.
- **§7 execution paths (judged):** no new component/KDL/Lua path to shell, reflection, files or other apps.
- **§7 capabilities (judged/JVM-tested):** valid capability required, one atomic claim, generation-safe cancellation; `uses` unchanged. Constant-time comparison is source-reviewed `MessageDigest.isEqual`, not timing-proven.
- **§7 captures/secrets (judged):** no captured-app data; capabilities remain in memory/stdin, absent from new logs/errors; private key storage unchanged.
- **§7 refusals (measured):** all 22 affected-class tests passed, including 13 new tests covering malformed input, expiry, replay, concurrent claims, IDs, acknowledgments, premature close and simulated stalls.

### claude — 2b report, 2026-11-07

- **Commits** (`team/claude/t001-starter`): `484c6aa` the facade (ShizukuLink over `ShellHost`; Shizuku's code moved
  into `ShizukuHost` unchanged; reports carry the host's generation); `d1eaef1` the measurement build (superseded);
  `5ff7a26` codex's 2a (committed by the lead: codex's sandbox could not write the worktree's index); `a9b909e` our own
  host: `OwnHost`, the provider over `StartAttempt.claim`, `ShellService.onlyUid`, the starter on `CapabilityReader`
  through a polled input, the launcher's uid from its fixed package, `TEST_SHELL` (DUMP senders).
- **Verified, measured on the emulator (Android 17, the launcher's own uid 10232, our own client over wireless debugging):**
  - pairing from the inbox with the launcher's identity: `paired (identity new)`; the inbox file deleted after reading.
  - start: `ready in 46 ms`; the launcher logged `own host: service connected`; after the connection's close,
    `identity()` answered `uid=2000 pid=26429 android=17 sdk=37`. The identity reloaded in a new launcher process
    (pid 24476, then 26188 after a reinstall) and started again.
  - refusals: a uid-2000 `content call` with a fresh capability and no attempt: `Result: null`, `handover refused`,
    the live service untouched; empty stdin and a partial line: `start failed: stdin: IOException` at once; stdin held
    open with nothing sent: refused about 5 s after start; the launcher stopped: the starter logged `launcher gone:
    exiting` and no `launcher-shell` process was left.
  - unit tests: 537 + codex's 13 green before the full gate (running).
- **Surprises (both fixed or open below):**
  1. Our client's `shell:` (v1) gets a **PTY** (`fd 0 = /dev/pts/1`), unlike the host's `shell -T`: adbd closes the
     stream at the child's stdout EOF and **SIGHUPs it** (the starter died 2 ms after `dup2`, before its next log line).
     Fixed with `trap '' HUP; exec app_process ...` (an ignored signal stays ignored across `exec`). Measured: survives.
  2. With a PTY, **the capability is echoed back** over the (TLS) connection into our discarded output. Not logged
     and on the channel that carried it, but it is a needless copy: `shell,raw:` would give no PTY and no echo. That
     is a one-line change in `AdbClient.kt` (codex's): question for codex below.
- **Deviation from §3:** no build text in the handover. The starter runs from the running launcher's own `sourceDir`,
  so its build is ours by construction, and it exits when the launcher dies (measured), so an update never meets an old
  service. Codex: agree, or do you want the field back?
- **Not verified:** a forwarded service binder refused for another uid; two simultaneous valid handovers; a late
  callback after a newer start; a blocked handover hitting the 20 s watchdog; `tools/dexlist.sh` (its reference build
  predates T-002 and its manifest check fails on every build since: parking lot); the S24 (needs the owner's go and his
  Wireless debugging).

### codex — work, 2026-11-07T19:03Z

- **Commits:** None. `git add` failed: permission denied creating `D:/Launcher/.git/worktrees/launcher-codex/index.lock`. Changes remain on `team/codex/t001-adb`; nothing pushed. Suggested commit: `Use raw ADB shell transport`, with `Co-Authored-By: Codex <noreply@openai.com>`.
- **What changed:** `AdbClient.kt` requests `shell,raw:` without v2. `AdbSessionTest.kt` expects that OPEN payload; stdin fixtures retain three-chunk coverage.
- **Verified — measured:** `git diff --check` passed; exactly two assigned files changed, 10 insertions/10 deletions.
- **Not verified:** `.\gradlew.bat :engine:testDebugUnitTest --tests 'dev.launcher.engine.adb.*' --console=plain` failed before tests: cannot create the wrapper lock under `C:\.gradle`. `--stop` failed identically; this attempt started no daemon. Full gate left to the lead as instructed; no device used.
- **Surprises — judged:** Raw `id` requires 13 service bytes; the previous 10-byte fixtures would refuse OPEN before exercising stdin behavior. Lead must test and commit.

## 6. Cross-check

### codex — review, 2026-11-07T18:49Z

**Reviewed, judged from source:** `484c6aa`, `a9b909e` and branch tip `768ece8`, against §3 and the 2b report. No files changed, builds run or devices used.

- **fix — `engine/src/main/java/dev/launcher/app/OwnHost.kt:90`:** Handover completion is not generation-safe. After claiming an attempt, its timeout can run before the posted completion; line 94 conditionally clears `starting`, but lines 95–97 unconditionally close the current client and publish the service. An expired handover can therefore replace a newer start. The death callback also carries only the host generation, so an obsolete binder can clear a newer service. Serialize acceptance with timeout/cancellation, check the attempt generation and deadline before publication, and associate death callbacks and transport cleanup with that exact connection. Add deterministic regression tests.

- **fix — `engine/src/main/java/dev/launcher/engine/shell/Starter.kt:48`:** Retain the launcher token in a strong process-lifetime field. Currently it is a local last used at `linkToDeath`; subsequent GC can make the death subscription unreliable. AOSP explicitly documents that dropping all proxy references can prevent `binderDied()` delivery ([Binder JNI implementation](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/jni/android_util_Binder.cpp#732)). Exercise launcher death after forcing GC in the starter.

- **fix — `engine/src/main/java/dev/launcher/app/OwnHost.kt:97`:** The facade reports connected and begins grants/watchdog/flags before the starter receives the reply and links the launcher token (`Starter.kt:49`). §3 requires death-linking before reporting ready. Add a completion acknowledgement after successful linking; publish the service only for the still-current attempt. A failed link must leave no connected state or surviving process.

- **fix — `engine/src/main/java/dev/launcher/engine/adb/AdbClient.kt:69`:** **Yes, request `shell,raw:`.** Explicit raw mode removes PTY echo and terminal transformations while retaining the existing byte stream protocol; do not add `v2` without implementing its framing. AOSP handles `raw` separately from shell-protocol selection ([ADB service parser](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/daemon/services.cpp#90)). Update the OPEN transcript assertion and repeat the actual-client measurements: no capability echo, descriptor cleanup, transport close, then a working `identity()` call. This review authorizes no edit; the file remains in my split.

- **question — `engine/src/main/java/dev/launcher/app/OwnHost.kt:85`:** Can caller UID and method be rejected before accessing extras? `StartAttempt.claim` checks them correctly, but `getString` currently processes the exported provider’s untrusted Bundle first. Also, at line 159, replace the file-length check followed by unrestricted `readText` with a bounded read: the file can grow between those operations.

- **note — `engine/src/main/java/dev/launcher/app/OwnHost.kt:60`:** **Dropping build text is acceptable once token lifetime and readiness are fixed.** The fixed starter is loaded from this launcher’s `sourceDir`, and the service is scoped to that launcher process. Build text would check compatibility, not authenticate the peer. Record this smaller design decision; retain the rule that only an authenticated service belonging to this host may be destroyed.

- **fix — `engine/src/main/java/dev/launcher/app/ShellService.kt:175`:** The UID guard covers application AIDL transactions by source inspection, but the agreed real-Binder proof remains outstanding. Complete wrong UID **during a live attempt**, concurrent valid handovers, replay, obsolete completion/death, forwarded service binder, blocked handover at 20 s, and failed token link. The reported uid-2000 call with no attempt proves neither wrong-UID rejection nor preservation of a live attempt. Reinstall-based identity reload also does not establish the agreed `am kill` sequence. Reconcile the report’s “full gate running” with `768ece8`’s green-gate claim and provide the declared dex diff.

**Protocol §7 — judged unless explicitly attributed to the lead’s emulator report:**

| Check | Assessment |
|---|---|
| Caller checked before acting | **Partial:** provider claims require UID 2000, method and capability; own-host AIDL checks launcher UID; test receiver requires DUMP. Early extras rejection and real-Binder proofs remain. |
| Inputs bounded; no attacker-selected reflection | **Partial:** capability format, stdin parser/polling, socket limits and watchdog exist; reflected names are fixed. Inbox read and handover completion bounds need the fixes above. |
| No new package/KDL/Lua escape path | **Pass:** no new component-facing route to shell, reflection, external paths or other apps. |
| Required capabilities enforced | **Partial:** one-use handover capability is checked atomically; `uses` derivation is unchanged. Publication after cancellation still needs fixing. |
| Captured app data stays private | **Pass for this diff:** no capture handling or new capture persistence/transmission added. |
| Secrets private and absent from logs | **Partial:** stored identity unchanged; capability absent from new explicit logs. Remove PTY echo. Pairing code temporarily uses the external inbox, deleted in `finally`; this is a developer-proof exception to strictly app-private storage. |
| Refusal paths tested | **Incomplete:** pure refusal tests exist and the lead reports stdin/no-attempt refusals; the agreed integration refusals above remain unverified. |

**codex: NOT YET — resolve handover races, token retention/readiness and missing refusal evidence before sign-off.**

### claude — answers to codex's 2b review, 2026-11-08

Commits on `team/claude/t001-starter`: `cb4d9e7` (your raw mode, committed by the lead), `4c7e6e9` (the fixes).

1. **Handover races (fix): done.** `engine/shell/HandoverGate.kt` (new, mine) decides every step under one lock by
   generation: `begin`, `claim` (your `StartAttempt.claim`, then a one-use 64-hex ack), `ready` (that ack, the attempt
   still in flight, before its deadline: the only step that publishes), `expire` (only its own generation), `lost`
   (`CONNECTION`, `START` or `NOTHING`: an obsolete binder's death ends nothing newer). `OwnHost` keeps each attempt's
   client by generation and closes only its own; a publication posted before a death was handled checks
   `isConnected(gen)` first. **Measured (JVM):** `HandoverGateTest`, 10 tests: claim then ready once; nothing published
   before the ack; a timeout between claim and ack; an ack past the deadline before the timeout ran; a newer start
   superseding a claimed one; an obsolete death against a newer connection; a death before the ack; a second claim of
   one attempt (two valid handovers: only the first taken); wrong uid and method change nothing and leave the attempt
   live; secrets absent from `toString`.
2. **Token retention (fix): done.** `Starter.launcher` holds it for the process's life; the starter runs
   `System.gc(); System.runFinalization()` once after every start. **Measured (emulator):** start, then the launcher
   force-stopped about 20 s later: `launcher gone: exiting`, no `launcher-shell` left.
3. **Ready before reporting (fix): done.** The reply carries the token and the ack; the starter links the token, then
   calls `shell-ready`; the launcher publishes on that call only, and the starter sets `ready` (the 20 s deadline's
   flag) only when the ack was taken. A failed link exits (`fail("link")`) before any ack, so nothing is published.
   **Measured (emulator):** `own host: service connected` at .609, the starter's `ready in 123 ms` at .611 (after its
   ack returned); `identity()` after the stream's close: `uid=2000 pid=26983 android=17 sdk=37`.
4. **`shell,raw:` (fix): applied, but it did not remove the PTY here.** adbd logged `service requested 'shell,raw:...'`
   and the starter's descriptors before `dup2` were still `0, 1, 2 = /dev/pts/1` (Android 17 emulator). So the line
   discipline may still echo the capability back to our own client. It goes nowhere else: our client discards the
   stream's output and logs none of it. Question for you: keep raw (harmless), and accept the echo into our own
   process's memory as is? Or is there a v1 form that really gives no PTY? I did not try `v2` (it needs its framing).
5. **Extras read before the caller check (question): fixed.** `OwnHost.handover` refuses any uid but 2000 and any
   method but `shell-binder`/`shell-ready` before touching the Bundle. **Bounded inbox read: fixed:** at most 257
   bytes read whatever the file does, 257 refused.
6. **Build text (note): agreed;** a D-entry at integration.
7. **Real-binder proofs (fix): partly.** Measured on the emulator: start and publish, survival of the stream's close,
   launcher death, the identity reload in a new process (pid 28078 after the force-stop: `host: OwnHost`, started
   again). Measured earlier: a uid-2000 call with no attempt refused. **Not measured on a device:** a wrong uid during
   a live attempt, a forwarded binder, a blocked handover at 20 s, a failed token link, replay of a real capability or
   ack. Each needs a test hook (a second uid with an attempt open; a starter told to block or skip its link). The
   decisions behind each are the gate's and are unit-tested above; the transport (`Binder.getCallingUid()` in the
   provider, `onlyUid` in `ShellService`) is unchanged. My proposal: a debug-only hook in slice 3 (the starter takes
   `--test block|nolink` only from a build flag), and these proofs before slice 5 makes the host the default. Does
   that block 2b for you?
8. **The gate (note):** the "running" in the report was written before it finished; it finished green (`768ece8`'s
   message is right: build, lint, unit tests, shell tests, the capture suite's 74 scenarios and 746 checks). The dex
   diff: `tools/dexlist.sh`'s reference predates T-002 and its manifest check fails on every build; parked on the
   board, not run.

## 7. Sign-off

### codex — signoff, 2026-11-07T19:26Z

codex: OBJECT — the source fixes address the reviewed races, token retention and readiness ordering; 2b’s required verification remains incomplete.

- **Judged, commits `cb4d9e7` and `4c7e6e9`:** generation-bound acceptance/death/cleanup, acknowledgement after token linking, strong token retention, caller/method rejection before extras, and the bounded inbox read are fixed. Dropping build text remains acceptable.
- **Measured evidence inspected:** `HandoverGateTest` XML records 10 tests, zero failures/errors/skips. Emulator results are the lead’s reported measurements; I used no device and ran no builds.
- **PTY answer:** keep `shell,raw:`. My earlier claim that it removes the PTY was wrong: AOSP deliberately substitutes a PTY in raw terminal mode for v1 and applies `cfmakeraw`. PTY presence alone does not establish echo; measure returned bytes with a disposable input. This implementation has no PTY-free v1 shell form. [AOSP implementation](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/daemon/shell_service.cpp#833).
- **Device-proof answer:** the agreed real-Binder refusals and lifecycle failure proofs block 2b sign-off. Add the necessary test support as a verification slice now; JVM policy tests cannot establish Binder caller identity or process termination. I do not agree to postpone these until before slice 5.
- **Gate/dex:** `t001-fix.log` ends in `BUILD SUCCESSFUL`, but the inspected full-gate log has no completion verdict. Supply a completed full gate for the fixes and the declared dex/manifest comparison; the stale reference explains the obstacle but does not satisfy the check.
- **§7:** caller policy, bounds, capability sequencing, escape paths and capture handling pass source review; secrets/no-echo and real refusal evidence remain open. No files changed or pushed.

### claude — the device proofs, 2026-11-08

Accepted: you were right that the policy tests could not stand in for these. `e0ba731` adds the support and the
proofs; everything below is **measured on the emulator** (Android 17), the launcher uid 10232, the engine test APK's
uid 10233.

- **Support.** The starter takes `--test=hold|block|late|nolink|double|replay` (only the DUMP-gated `TEST_SHELL` route
  passes one; each only makes the starter fail or be refused). The route gains `stop` (the service's `destroy`),
  `lend` (armed for 10 s: one `test-lend` call from any uid gets the published binder) and `echo-check`. The test APK's
  `ShellProof` (`am instrument -e mode shell-wrong-uid|shell-forwarded`) declares the provider in `<queries>` and checks
  it is reachable before counting a refusal.
- **Wrong uid during a live attempt:** a start held 6 s (`hold`); uid 10233 called `shell-binder` (made-up capability,
  a binder) and `shell-ready` (made-up ack): both `null`, the launcher logged `handover refused` twice, and the same
  attempt then connected (`ready in 6091 ms`). `INSTRUMENTATION_RESULT: result=PASS`.
- **Forwarded binder:** the published binder lent to uid 10233; its `identity()` threw `SecurityException`. PASS.
- **Two valid handovers at once** (`double`, two threads, one capability): `1 of 2 accepted`, the launcher refused
  the other, one connection.
- **Replay** (`replay`, after ready): the handover and the ack sent again, both refused.
- **Failed token link** (`nolink`): `start failed: link`; the launcher logged `the service died before it was ready`;
  nothing published, no process left.
- **Late handover** (`late`, 16 s): the launcher's attempt timed out at 15.0 s; the handover at 16 s refused; the
  starter exited.
- **Blocked handover** (`block`, 30 s): the launcher timed out at 15 s; the starter's deadline killed it at 20.0 s
  (`start failed: deadline`); no process left.
- **Echo:** a disposable 64-hex line on stdin under `shell,raw:`: output 15 characters (the marker only), the line
  **not** echoed (your `cfmakeraw` reading, measured).
- **After all of them** a normal start connected (`ready in 61-138 ms`).
- **Launcher death** (earlier, `4c7e6e9`): the starter collects garbage after every start; `launcher gone: exiting`.
  The agreed `am kill` after a home press does not end the launcher (its accessibility service keeps it); it was
  force-stopped on the emulator instead, never on the phone.
- **Gate:** `scripts/check.sh` finished green on `b49c15e` (CHECK PASSED, capture suite 746 checks); it runs again
  on the branch's tip before integration. **Dex/manifest:** compared against `engine`'s tip, not the stale reference,
  before integration (both lists in the report then).


### codex — signoff, 2026-11-07T20:04Z

codex: AGREE — reviewed `e0ba731`; the reported emulator proofs address my outstanding 2b objection.
- **Judged, §7 caller checks:** stop/lend arming/echo-check require DUMP; lending is one-use for 10 s. The forwarded binder retains the launcher-UID guard across application AIDL transactions.
- **Judged, §7 bounds/capabilities:** test modes are allowlisted before command construction; deadlines, capability checks and acknowledgement sequencing remain enforced.
- **Judged, §7 escape paths/captures/secrets:** no new unprivileged shell route, attacker-selected reflection, capture handling or secret logging; package `uses` derivation is unchanged.
- **Lead-measured emulator refusals:** wrong UID during a live attempt, forwarded binder, duplicate handovers, replay and late handover cover the requested refusals; normal starts recover.
- **Proof limits:** `nolink` injects failure before linking; `block` stalls before handover. These demonstrate cleanup/deadline behavior, not actual Binder failure injection. Force-stop reasonably substitutes for ineffective `am kill` here.
- **Lead-measured echo:** disposable input was absent from returned output; launcher death after GC terminated the starter.
- **Gate:** inspected `t001-2b-full.log` ends `CHECK PASSED`. Final-tip full gate and engine-tip dex/manifest comparison remain mandatory before integration.
- No files changed, builds started, devices used or pushes made.

- `claude: AGREE — slice 2 (2a codex, 2b claude): read every changed file; the review's races, token retention and
  readiness fixed (HandoverGate, 10 JVM tests); the real-binder and lifecycle refusals measured on the emulator from a
  second uid; the echo measured absent; final gate CHECK PASSED on the tip (build, lint, unit tests, shell tests, the
  capture suite's 746 checks); dex and manifest against engine's tip 70ab62d: classes changed only under
  dev.launcher.app.{OwnHost, ShellBinderProvider, ShellHost, ShellService, ShizukuHost, ShizukuLink} and
  dev.launcher.engine.{adb, shell}; the manifest adds only the exported ShellBinderProvider (no permission). Integrated
  into engine. Codex's limit noted: nolink and block inject the failure in the starter, not in Binder.` (2026-11-08)


---

# Round 4: slice 3 (pairing and start on the phone, no adb)

## 1. Proposal (lead), 2026-11-08

**Goal.** On the phone alone, without a computer: the user pairs the launcher with Wireless debugging once, and the
launcher finds the ports, connects and starts its own host. Still behind the developer switch (Shizuku stays the
default until slice 5); the `TEST_SHELL` route's `pair` (an inbox file) and `use-own` become developer conveniences.

**Approach.**
1. *Discovery* (`engine/adb/AdbDiscovery.kt`, new): `NsdManager` browses `_adb-tls-pairing._tcp` and
   `_adb-tls-connect._tcp` while a pairing or a start is in progress (never otherwise), resolves each service, and
   keeps only those whose resolved host is one of **this phone's own interface addresses** (`NetworkInterface`); the
   endpoint used is then `127.0.0.1` with the resolved port. A service on another device of the same Wi-Fi is never
   connected to (an attacker could advertise one). Results are a flow of `(kind, port)`; resolution bounded (5 s each,
   at most 8 services kept). Discovery stops on success, cancel, or 60 s.
2. *Pairing code entry* (upstream's way, no activity on top of Settings): when a pairing service appears, a
   notification ("Enter the pairing code") with a `RemoteInput` field; the reply goes to a receiver of ours
   (not exported; `PendingIntent.FLAG_MUTABLE` only for the RemoteInput, explicit component), which takes exactly six
   digits, pairs on a worker with the launcher's identity, and updates the notification ("Paired" or "Wrong code, try
   again"). The code is never logged, stored or put in an extra beyond the RemoteInput result. A new notification
   channel ("Shell host"), low importance.
3. *Start.* After pairing, the connect port from discovery is stored and `OwnHost.connect()` runs; on later starts the
   stored port is tried first and discovery runs only if it fails (the port changes whenever Wireless debugging is
   toggled).
4. *UI* (`settings/`, the Developer page, behind the developer tools): a "Shell host" group: Shizuku | Launcher (the
   `host_kind` switch, applied at the next start, said so), state (not paired / paired / running, with the `identity()`
   answer's uid), "Pair with Wireless debugging" (opens Developer options' Wireless debugging; Android has no public
   intent for that page, so: `Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS`, the user taps Wireless debugging, then
   "Pair device with pairing code"; the notification waits), and "Forget pairing" (deletes the identity, asks first).
   Polish pass applies; it is a developer page, the owner signs it off on the S24 with the rest of slice 3.

**Permissions.** None new, as far as the lead can tell: `NsdManager` needs `INTERNET` (declared); the notification uses
`POST_NOTIFICATIONS` (declared); the app targets SDK 34, so Android 16's local-network permission
(`ACCESS_LOCAL_NETWORK`, for apps targeting 36+) does not apply yet, and `NEARBY_WIFI_DEVICES` is for Wi-Fi Aware and
P2P, not NSD. Measured on the emulator before relying on it; if anything is refused, the owner is asked before it is
declared (the plan's rule).

**Split (proposed).** **codex**: `AdbDiscovery` (with an injectable resolver so its filtering, bounds and timeouts are
JVM-tested: own-address filter, a foreign host refused, more than 8 services, a resolve that never returns, stop on
success), the six-digit parser for the reply, and their tests. **claude**: the notification and its receiver,
`OwnHost` wiring (stored port first, discovery on failure), the settings group, the emulator and S24 runs.

**Verification.**
- JVM: discovery filtering and bounds (above); the code parser (six digits only; spaces trimmed; anything else refused
  without consuming an attempt).
- Emulator (Android 17): Wireless debugging on; pair through the notification with the code Settings shows; start;
  `identity()` answers uid 2000; toggle Wireless debugging off and on (new port): the stored port fails, discovery finds
  the new one, the start succeeds; a wrong code says so and the next right one pairs. The emulator's adbd listens on
  the emulator's own network; if NSD does not see it there, the lead says so and the S24 is the measurement.
- S24 (owner's go): the same journey once, then a reboot (the host does not come back by itself: that is slice 4).
- Security checklist (protocol §7) by both: the receiver, the discovery filter, the code's handling.

**Risks and open questions.**
- Some phones' adbd advertises only the connect service while the pairing dialog is open (that is Android's design):
  the notification appears only while the user has "Pair device with pairing code" open, which is the moment the code
  is on screen. Splitting the screen is not needed: the notification shade is reachable over Settings.
- One UI's notification shade is ours when our shade is on: the reply field must work in our shade (`RemoteInput` in
  `NotificationListBlock`?). If our shade cannot take inline replies yet (CLAUDE.md: "replying in place" is still stock),
  the user replies with our shade off, or the code is entered in a small dialog of ours instead. Codex: which do you
  prefer? The lead leans to a dialog in the settings page ("Enter the code Wireless debugging shows"), with the
  notification only as the fallback, because it works whatever shade is on.

## 2. Discussion

### codex — discuss, 2026-11-07T22:06Z

**Judged from the full thread, current source and Android documentation:** I agree with NSD discovery, loopback connections and trying the saved connect port first. The code-entry journey needs resolving before agreement.

1. **A settings-page dialog is not yet a working alternative.** Our shade explicitly substitutes opening the conversation for inline reply (`app/src/main/java/dev/launcher/app/shade/nc/NotificationListBlock.kt:1026`; `CLAUDE.md:1119`). However, switching back to our settings activity also has a problem: AOSP’s `WirelessDebuggingFragment.onPause()` removes the pairing dialog, whose dismissal calls `disablePairing()`. Remembering the code does not preserve its server. This is source evidence, not a One UI measurement. [AOSP Settings](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/main/src/com/android/settings/development/WirelessDebuggingFragment.java).

   Prefer RemoteInput **if we provide a working input surface while Android Settings remains resumed**. Otherwise first prove a split-screen journey with our settings dialog; do not claim that an ordinary activity switch works. Temporarily disabling our shade should not become an automatic workaround. Before building the UI, the lead should measure the chosen journey on the emulator and S24, including keyboard focus and wrong-code recovery. Any broader change to shade behavior needs the owner’s decision under `OPERATING_RULES.md`’s Ask list.

2. **No new permission for the proposed Android 17/target-34 path.** Correct the threshold: `ACCESS_LOCAL_NETWORK` becomes mandatory at **target 37**, not 36. Targets 36 and below retain implicit access through `INTERNET`; Android expressly advises against adding/requesting it there. Existing permissions are at `app/src/main/AndroidManifest.xml:6-10`; target 34 is at `app/build.gradle.kts:18`. `NEARBY_WIFI_DEVICES` is unnecessary for this Android 17 NSD path, but describing it as only Aware/P2P overlooks Android 16’s former opt-in protection. [Android permission rules](https://developer.android.com/privacy-and-security/local-network-permission).

   Keep the conclusion scoped: NSD documentation requires a multicast lock on older devices before T extension 7. Also, our launcher activity is backgrounded while Settings shows the code. Measure discovery in that actual state; an emulator foreground test does not settle it. No multicast permission or foreground service should be added without the owner’s yes. [NSD documentation](https://developer.android.com/reference/android/net/nsd/NsdManager).

3. **Discovery design: agree, with explicit limits.** Compare resolved address bytes against current interface addresses; refuse null/foreign addresses and ports outside `1..65535`. Connect using numeric `127.0.0.1` and `network=null`, so `AdbTransport` does not bind loopback to a Wi-Fi `Network`. Prove loopback interoperability on both devices. The address filter narrows candidates; spoofable mDNS does not authenticate adbd. Retain slice 2’s UID/capability/acknowledgement checks.

   Cap **pending and active work**, not just eight retained results: deduplicate service identities, serialize resolution, bound the queue and emitted results, and ignore callbacks from expired generations. Remove lost services. Stop both browsers and outstanding resolutions on cancellation/deadline; guard resolution cancellation by API availability. Do not stop the whole workflow when merely finding the pairing port: the separate connect port is still needed.

4. **Receiver: the proposed exposure is appropriate, but insufficient alone.** Use a manifest receiver with `exported=false` and an explicit mutable broadcast PendingIntent only for RemoteInput. Android documents inline reply as a valid mutability requirement. [PendingIntent reference](https://developer.android.com/reference/android/app/PendingIntent).

   Treat possession of that PendingIntent as delegated authority, not proof of a particular sender UID. Require a current, expiring pairing-session identifier; keep endpoint/identity selection in app memory, never accept them from reply extras. Reject stale, duplicate, oversized and malformed replies before claiming work. Bound the CharSequence before converting/trimming; accept six ASCII digits, including leading zeroes. Invalidate old PendingIntents on session replacement/cancel. Keep network work outside `onReceive`, with explicit lifetime and cancellation ownership.

5. **Saved-port start: agree, with separate discovery and handover timing.** Always reconstruct the cached endpoint as loopback; do not retain the test route’s arbitrary saved `host` (`OwnHost.kt:184`). A failed cached attempt must close before one bounded discovery fallback; cache a replacement only after authenticated readiness. `OwnHost.kt:62-65` currently starts the 15-second handover deadline immediately: do not spend it waiting through discovery. Give discovery its own bounded phase, then a fresh handover attempt; obsolete completion must not publish or overwrite the cache.

6. **State and split need two additions.** A stored key is not evidence that Android still trusts it. Distinguish identity loss/storage failure, pairing success, connection failure and running; report running only after readiness and `identity()`. “Forget pairing” must cancel in-flight work and explain that local deletion does not remove Android Settings’ paired-device entry.

   I take discovery policy/adapter, the bounded six-digit parser and their JVM tests. Name exact files in §4, including new tests. Claude owns orchestration, receiver/notification, settings, identity reset/storage integration, manifests, NOTICE and device proofs. Add tests for late callbacks, service loss, queue flooding, cancellation during pairing, stale replies and saved-port fallback. Both complete §7; retain full gate, declared dex/manifest comparison and polish-pass verification.

No edits, builds or device use this round. All findings above are source judgments; board ownership remains `Device: claude`.

codex: NOT YET — resolve and measure code entry while Android’s pairing server stays alive, then freeze the discovery bounds, operation lifetime and exact split.

### claude — 2026-11-08

All accepted; the code entry is settled by a measurement.

1. **Code entry, measured on the emulator (Android 17).** With Settings' "Pair with device" dialog up, adbd's pairing
   port appeared (46705, beside 38529 and 5555). (a) The stock notification shade expanded over it (a window that takes
   the focus: `mCurrentFocus=NotificationShade`): Settings' `SubSettings` stayed the resumed activity and port 46705
   kept listening. (b) Our settings activity started in front: port 46705 was gone at once. So your reading of
   `WirelessDebuggingFragment.onPause` holds, and a window does not pause it. **Plan:** the code is typed into a small
   card of ours, a focusable overlay window shown over Settings (no activity, no RemoteInput, no PendingIntent, no
   receiver), at the top so the dialog's code stays visible above the keyboard. Before building it, the work's first
   step measures our own overlay (not the stock shade) the same way, on the emulator and on the S24 (the owner's go),
   with keyboard focus and a wrong code then a right one. If our overlay pauses Settings anywhere, we stop and ask the
   owner. The notification stays only as the prompt ("Open the pairing dialog") and never carries the code.
2. **Permissions.** Corrected: `ACCESS_LOCAL_NETWORK` is mandatory from target 37. None added. Discovery is measured in
   the real state (launcher in the background, Settings in front); no multicast lock or foreground service without the
   owner's yes.
3. **Discovery** as you set out: address bytes compared with the current interfaces, null or foreign refused, ports
   1..65535, numeric `127.0.0.1` with `network=null`; services deduplicated, one resolution at a time, the queue and the
   results bounded, lost services removed, callbacks of an expired generation ignored, browsers and resolutions stopped
   on cancel or deadline (resolution cancel guarded by API level); finding the pairing port does not stop the search for
   the connect port. Loopback proved on the emulator and the S24. The filter narrows; slice 2's uid, capability and ack
   checks still authenticate.
4. **No receiver now** (the overlay is in our process): the code goes from the field to the pairing session in memory,
   bounded before conversion (at most 16 characters read), six ASCII digits with leading zeroes, a current session id
   held in memory, stale or duplicate submits refused before any work, the network work off the main thread with its
   own cancellation.
5. **Start timing.** The cached endpoint is rebuilt as loopback (the test route's arbitrary `host` dropped); a failed
   cached attempt closes, then one bounded discovery phase (its own 20 s), then a fresh attempt with its own 15 s
   handover deadline; an obsolete completion neither publishes nor overwrites the cache; the cache is written only
   after authenticated readiness.
6. **States:** identity lost or unreadable, not paired, pairing, paired, connecting, connection failed, running (after
   readiness and `identity()`). "Forget pairing" cancels work in flight and says that Android's paired-device entry
   stays in Settings until removed there.

**Split.** **codex** (`team/codex/t001-discovery`): `engine/src/main/java/dev/launcher/engine/adb/AdbDiscovery.kt`
(policy and an injectable NSD adapter) + `engine/src/test/.../adb/AdbDiscoveryTest.kt` (late callbacks, service loss,
queue flooding, cancellation, foreign and null hosts, bad ports, generations); `engine/src/main/java/dev/launcher/engine/
shell/PairingCode.kt` + its test. **claude** (`team/claude/t001-pairing`): the pairing session and start orchestration
(`OwnHost`, a new `PairingSession.kt`), the overlay card, the settings group, identity reset, manifests if any, the
device proofs. Both: §7, full gate, dex and manifest comparison, polish pass.

### codex — discuss, 2026-11-07T22:11Z

**Judged from the full thread and source:** the revised approach resolves my blocking objections. I agree with measuring our own overlay first, removing RemoteInput/receiver handling, separating discovery from handover timing, and the proposed ownership split.

Carry these explicit requirements into §3/§4:

- **Overlay gate:** the stock-shade measurement supports the experiment; our card remains unproved. Require Settings still resumed, pairing port still listening, keyboard input working, then wrong-code recovery and successful pairing through our client on emulator and S24. Use a separate app-process window; the existing shade must remain non-focusable (`docs/LAUNCHER_AUDIT.md:140,150`; `CLAUDE.md:580-586`). Cancel must remove the card and release keyboard focus; service loss, rotation and lock belong in the polish-pass checks.
- **Freeze numeric discovery bounds:** retain **5 s per resolution**, **60 s for the pairing/discovery session**, **one active resolution**, **at most eight queued-or-active service identities total**, and **eight retained results**. The start fallback has its separate **20 s** deadline. Expired/lost results must not supply an endpoint, and callbacks must not restart work after cancellation.
- **Keep the authentication wording narrow:** loopback filtering restricts destinations; UID/capability/ack checks authenticate the local handover, **not adbd’s connect-port certificate**. Round 3 already established this distinction; the latest entry’s “checks still authenticate” should preserve it.
- **Cancellation includes side effects:** cancellation, session replacement and Forget must close pairing/start sockets and prevent obsolete work from updating pairing state, recreating a deleted identity or writing the port cache. Claude’s orchestration tests should cover these races, duplicate submits and saved-port fallback. Clear the field’s code on completion/cancel and avoid saved-state persistence.
- **Split accepted:** Codex owns the two named production files and their tests; Claude owns orchestration, overlay/settings, identity integration and device proofs. Record exact Claude paths in §4 before edits. Both security reviews, full gate, dex/manifest comparison and owner’s S24 sign-off remain required.

No edits, builds or device use this round. Overlay behavior remains an implementation gate, not an established result.

codex: AGREE approach — slice 3 is ready with the explicit bounds and lifecycle requirements above, gated first on our own overlay’s emulator and S24 measurements.

### codex — discuss, 2026-11-08T09:09Z

**Judged from the full lead-worktree thread and source:** yes—my Round 4, first-response receiver rules stand unchanged for Android’s own shade. The failed overlay measurement justifies dropping `PairingCard.kt`. `SystemRestore.kt:151-158,173-176` supports the shell-held flags explanation; the complete RemoteInput journey still needs emulator and S24 proof.

Carry these requirements into §3/§4:

- **Receiver:** manifest `exported=false`; explicit broadcast PendingIntent, mutable only for RemoteInput. Treat it as delegated authority, without requiring SystemUI’s UID. Keep endpoint and identity selection in memory. Require a live, expiring session; bound input before conversion/trimming; accept six ASCII digits, preserving leading zeroes; reject malformed, stale and duplicate submissions before starting work. [Android PendingIntent reference](https://developer.android.com/reference/android/app/PendingIntent).
- **PendingIntent identity:** give each session a distinct action/data or request code; changing extras alone must not reuse the old token through `FLAG_UPDATE_CURRENT`. Cancel old tokens on replacement, cancellation, expiry and Forget. After process death, a reply must refuse because its in-memory session no longer exists. [Android PendingIntent reference](https://developer.android.com/reference/android/app/PendingIntent).
- **Retry and lifetime:** atomically allow one pairing operation at a time; a wrong code releases that submission for retry while the session remains valid. Keep network work outside `onReceive` with explicit lifetime ownership. Cancellation closes sockets and prevents obsolete state/cache writes. Update the notification without echoing the code or retaining RemoteInput history.
- **Notification availability:** check the existing `POST_NOTIFICATIONS` grant and channel availability before sending the user into Settings; declaration alone does not establish availability (`app/src/main/AndroidManifest.xml:10`). Explain blocked notifications on the settings page. [Android notification requirements](https://developer.android.com/develop/ui/compose/notifications/create-notification).
- **Running Shizuku case:** choose the proposed **“Stop Shizuku first”** prerequisite for this slice. Automatic flag clearing introduces restoration races: `applyFlags()` sets both disable masks and runs gesture/One UI side effects (`SystemRestore.kt:151-169`). That exception need not expand this pairing change.
- **Verification and ownership:** Claude owns the notification, receiver, manifest and orchestration tests; replace the overlay paths in §4. Prove Settings remains resumed and its pairing port alive during keyboard input, wrong-code recovery and successful pairing on emulator and S24. Test stale tokens, process death, duplicate replies and cancellation. Existing discovery bounds, authentication limits, both §7 reviews and integration gates remain.

No edits, builds or device use this round; the overlay results are the lead’s reported emulator measurements.

codex: AGREE approach — use stock-shade RemoteInput with the original receiver rules and additions above; require Shizuku stopped for the transitional running-host case.

## 3. Agreed approach

Agreed 2026-11-08 (`codex: AGREE approach`, 09:09Z), after the overlay gate failed and the owner's answer: the code
is typed into a notification's reply field in **Android's own shade**, which is there whenever pairing is needed (our
shell, which holds `DISABLE_EXPAND`, is not running then).

- **Notification and receiver:** a manifest receiver, `exported=false`; an explicit broadcast PendingIntent, mutable
  only for the RemoteInput, distinct per session (its own request code and data), cancelled on replacement, cancel,
  expiry and Forget; possession of it is delegated authority (no sender uid required). Endpoint and identity stay in
  memory, never in the reply. A live, expiring session id (`PairingFlow`, 5 min); input bounded before conversion; six
  ASCII digits with leading zeroes; malformed, stale and duplicate replies refused before any work; after process
  death a reply is refused (no session in memory). One pairing at a time; a wrong code reopens the session. Network
  work outside `onReceive`, owned and cancellable; the notification is updated without echoing the code and without
  RemoteInput history. `POST_NOTIFICATIONS` and the channel are checked before the user is sent to Settings; blocked
  notifications are explained on the settings page.
- **Shizuku running** (until slice 5): "Stop Shizuku first" (no automatic flag clearing).
- **Discovery bounds (frozen):** 5 s per resolution, one at a time, at most 8 queued-or-active identities and 8
  results, 60 s per pairing session, 20 s for the start's fallback; expired or lost results never give an endpoint;
  nothing restarts after cancel; own-interface address bytes only; numeric `127.0.0.1`, `network=null`; ports 1..65535.
  The loopback filter restricts destinations only; slice 2's uid, capability and ack authenticate the local handover,
  not adbd's certificate.
- **Cancellation, replacement and Forget** close pairing and start sockets; no obsolete work updates the pairing
  state, recreates a deleted identity or writes the port cache.
- **Start:** cached loopback endpoint, then one bounded discovery, then a fresh attempt with its own handover deadline;
  the cache written only after authenticated readiness.
- **States:** identity lost or unreadable, not paired, pairing, paired, connecting, connection failed, running (after
  readiness and `identity()`); Forget explains Android's paired-device entry remains.
- **Proof:** Settings stays resumed and its pairing port alive during keyboard input, a wrong code and the right one,
  on the emulator and the S24; stale tokens, process death, duplicate replies and cancellation tested. No permission.
  Both: §7, full gate, dex and manifest comparison, polish pass, the owner's S24 sign-off.

- `codex: AGREE approach — use stock-shade RemoteInput with the original receiver rules and additions above; require
  Shizuku stopped for the transitional running-host case.`

## 4. Split

| Sub-task | Owner | Files owned | Branch | Verified by |
| --- | --- | --- | --- | --- |
| Discovery policy and NSD adapter; the code parser | codex | `engine/.../adb/AdbDiscovery.kt` + test, `engine/.../shell/PairingCode.kt` + test | `team/codex/t001-discovery` (`71d973a`, done) | JVM 18/18 (lead's Gradle run: 14 + 4) |
| Pairing rules, notification, receiver, start orchestration, settings | claude | `engine/.../shell/PairingFlow.kt` + test (done, 8/8), `engine/.../app/PairingSession.kt` (notification, discovery, pairing, start), `engine/.../app/PairingReplyReceiver.kt`, `app/src/main/AndroidManifest.xml` (the receiver), `engine/.../app/OwnHost.kt`, the Developer page's group in `app/.../settings/` | `team/claude/t001-pairing` | JVM; emulator and S24 journeys |

## 5. Reports

### codex — work, 2026-11-07T22:21Z

- **Commits:** none; staging denied at `D:/Launcher/.git/worktrees/launcher-codex/index.lock`. Only the four assigned files are uncommitted.
- **Changed:** `AdbDiscovery.kt`, `AdbDiscoveryTest.kt`, `PairingCode.kt`, `PairingCodeTest.kt`. Discovery exposes `start()`, `snapshot()`, `close()`, with injectable NSD/clock adapters.
- **Verified — measured:** Kotlin 2.2.21 compilation against API 35; **18/18 JVM tests passed**.
- **Coverage — measured:** 5 s resolution, 60/20 s sessions, eight pending identities/results, flooding, generations, loss, cancellation, host/port refusals and bounded ASCII parsing.
- **Security §7 — judged:** no exported caller entry point; inputs bounded; no new shell/reflection path; capability derivation unchanged; no capture path; codes remain in memory without logging; refusal tests pass.
- **Not verified:** Android NSD behavior, integration, lint, dex/manifest comparison or device checks. No phone use; full gate deliberately not run.
- **Surprises:** fast gate and `gradlew --stop` failed before execution because the sandbox denied the `C:\.gradle` wrapper lock. No Gradle daemon started.
- **Platform limit:** below API 34, framework resolution cannot be cancelled; obsolete callbacks are discarded. Lead must run the integration checks.

### claude — the overlay gate failed, 2026-11-08

**Measured on the emulator (Android 17):** our card as a focusable `TYPE_APPLICATION_OVERLAY` window over Settings'
"Pair with device" dialog: Settings stayed resumed and the pairing port kept listening, but the card **never showed**:
`dumpsys window` gives `mIsForceHiddenNonSystemOverlayWindow=true`, `mPolicyVisibility=false`, `isVisible=false`, and
the dialog kept the focus. Settings' pairing screen hides every non-system overlay (Android's protection of
security-sensitive screens, the hide-non-system-overlays flag); the stock shade showed because it is SystemUI's. So an
ordinary app overlay cannot be the code entry, and the gate stops here for the owner (the plan's rule). Options:
1. **Our own shade gets inline reply** (the pairing prompt is a notification in our Notification Center with a reply
   field; our shade's window is an accessibility overlay, which that flag does not hide): one shade, works for every
   notification later (still a gap: "replying in place" is stock). Larger; touches the shade (E3's code) and its focus
   rules (a focus window for the keyboard, as `FocusHolder` does for Back).
2. **During pairing only, let Android's own shade through** so its notification reply works (upstream Shizuku's
   path): smallest; breaks "one shade" for the minute pairing takes.
3. **Split screen**, Settings and our page side by side (both resumed): no shade change; awkward on a phone.
4. Our card as an **accessibility overlay** (also not hidden by that flag): would put our window over a screen
   Android deliberately protects from overlays. The lead advises against it (it defeats a platform safeguard, and
   uses the accessibility service for something that is not accessibility: Play policy risk).
Codex's discovery and parser (`AdbDiscovery`, `PairingCode`, 18 JVM tests) do not depend on this choice.

### claude — the owner's answer, 2026-11-08

The owner: "we haven't activated our shades or gesture bar before pairing, correct? so we would have to use stock
shade right?". Correct, checked in the code: Android's shade is kept away only by `DISABLE_EXPAND`, held by the shell
service (`SystemRestore.applyFlags`, `Shade.kt:44`) and dropped when it dies; before the first pairing no shell runs,
and re-pairing is needed exactly when ours is not running. **So the code is typed into a notification's reply field in
Android's own shade** (upstream's way; `RemoteInput`, an unexported receiver, the mutable PendingIntent only for the
reply, a session id in memory: round 1's receiver rules come back). No shade change, no overlay. One case: while
Shizuku is still the default (until slice 5) and running, its service holds the flags; then the app clears them for
the pairing session (a call to the running service, restored after) or says "Stop Shizuku first". The card
(`PairingCard.kt`) is dropped. Codex: please confirm the receiver rules from your round 1 still stand as written.

### claude — slice 3 work, 2026-11-08

- **Commits** (`team/claude/t001-pairing`): `d8cfba4` `PairingFlow`; `b788e68` `PairingSession`, `PairingReplyReceiver`,
  `OwnHost`'s start, the Developer page's Shell host group, the search words; the tip: the notification's icon.
- **A change from §3, please check it:** the 60 s discovery session could end before a person has typed the code
  (measured: the reply came at 66 s and found nothing). So a reply or the start that finds no port runs one fresh
  bounded discovery then (20 s, `PairingSession.find`, wanted only while its claim is current). Your per-session bounds
  are unchanged; there may now be several sessions within one pairing, each started by a reply.
- **Measured (emulator, Android 17, launcher uid 10232, Shizuku's server killed first):**
  - NSD with the launcher in the background and Settings in front: both services resolved under 1 s, no new permission.
  - The journey: Settings' dialog open; the code typed with the keyboard in our notification's reply in Android's
    shade: `resumed SubSettings`, `focus NotificationShade`, the pairing port listening at every step; a wrong code:
    `pairing: wrong`, the same dialog and port still up (adbd's server survives it); the right one: `pairing: paired`,
    `own host: service connected` 0.8 s later, `ready in 71 ms`, `identity: uid=2000`.
  - The launcher's start then runs on it: `[watchdog] armed`, animation scales, `[flags] ... stock gestures blocked`,
    `gesture strip on`.
  - Port change: service stopped, Wireless debugging off and on (34135 to 38939): `stream: ConnectException`, `the cached
    port did not answer`, `service connected` 0.7 s later.
  - **The real pull** (`tools/kswipe.sh`, touchscreen events, gesture nav on, no shell): `focus NotificationShade`:
    Android's shade comes, as the owner reasoned.
  - The rows on the Developer page, in the page's own style (screenshot, judged).
- **Measured (JVM):** `PairingFlowTest` 8 (claimed once, wrong code reopens, malformed consumes nothing, another or no
  session ignored, expiry, cancel and replacement against late outcomes, no secret in `toString`); process death is the
  "nothing begun" case. Codex's `AdbDiscoveryTest` 14, `PairingCodeTest` 4 (lead's Gradle run).
- **Not measured:** replying with a stale token on a device, the S24 (needs the owner's go), One UI's shade and its
  keyboard; the "notifications off" and "Stop Shizuku first" messages by eye. The automatic start at launch relies on
  `bindQuietly` -> `connect` as slice 2.
- **Noticed:** the System tile and rows still say "Shizuku not running" and "Restore without Shizuku" with our own host
  selected: slice 5's wording, on the board.

## 6. Cross-check

### codex — review, 2026-11-08T09:42Z

**codex: OBJECT — fixes required.** Judged from `d8cfba4`, `b788e68` and the icon follow-up; no files changed.

- **fix (P1)** — `OwnHost.kt:251–254`, `OwnHost.kt:79–84,138–143`: Forget removes the cache and closes discovery but does not invalidate the start attempt or close its clients. An in-flight handover can subsequently publish and recreate the cache. Its worker can also load/create an identity after deletion. Invalidate the attempt before deletion, close its sockets, and guard identity creation, publication and caching against obsolete work.
- **fix (P1)** — `PairingSession.kt:87–95,142–153`: cancellation can run before the worker assigns `client`; the worker then loads the identity and opens a new pairing connection anyway. Rejecting its eventual `finish()` protects state, but does not prevent pairing side effects. Make worker admission/client registration atomic with cancellation, and test cancellation before identity load and before client registration.
- **fix (P1)** — `OwnHost.kt:70–77,245–247`, `PairingSession.kt:136–137`: startup without a cached port returns immediately instead of discovering. This also breaks the natural “select own host, then pair” journey: selection applies next launch, so `current` can still be null; pairing succeeds, startup does nothing, no port is cached, and the next launch gives up. Preserve the successful pairing outcome across that transition and discover when no cached endpoint exists. A cached attempt reaching its 15 s timeout also bypasses fallback discovery; only the exception path invokes it.
- **fix (P2)** — `PairingFlow.kt:53–56,65–82`, `PairingSession.kt:59–65`: expiry is checked only on another reply. Waiting sessions retain their token/notification indefinitely, and an admitted operation can finish after expiry because `isCurrent()`/`finish()` ignore the deadline. Schedule expiry to cancel the token, discovery and socket, and reject expired completions. Also invalidate post-pair work on cancel: `cancel()` currently leaves `PAIRED` unchanged.
- **fix (P2)** — `Pages.kt:3065–3077`: the agreed states are missing: identity lost/unreadable, pairing, connecting and connection failed collapse into “Not paired” or “Paired, not running.” “Running” uses the saved next-launch selection, so toggling it can misidentify the currently running Shizuku host. Display actual host and operation state.
- **fix (P2)** — `Pages.kt:3077–3078`, `PairingSession.kt:90`: the new pairing action is exposed below Android 12 despite `AdbPairingClient` requiring API 31 and D-075 retaining Shizuku there. Guard the entry and explain availability.
- **note** — `PairingSession.kt:110–127`: fresh discovery on reply is reasonable and retains the 20 s discovery bound. `OwnHost.kt:132–143` correctly places caching after authenticated readiness; the Forget race above still needs fixing. Add orchestration regression tests: `PairingFlowTest` does not exercise sockets, identity deletion, fallback or cache writes.

**Protocol §7 — security checklist (judged):**

- **Caller checks: PASS with lifecycle fixes pending.** `AndroidManifest.xml:242`, `PairingReplyReceiver.kt:15–19`, `PairingSession.kt:62–64`: unexported receiver, explicit mutable token, distinct session data; current-session checks provide delegated authority without requiring SystemUI’s UID.
- **Bounded inputs/time: PARTIAL.** Code parsing, discovery and transport bounds remain; active expiry and cancellation have the gaps above.
- **No new package/KDL/Lua route to shell: PASS.** No such caller path added; destinations remain numeric loopback.
- **Capabilities: PASS for this slice.** Existing UID/capability/ack handover remains; `uses` derivation is unchanged.
- **Other-app captures: PASS.** No capture path added.
- **Secrets: PASS by inspection.** Wrapped app-private identity storage remains; no code/key logging or RemoteInput history added. Forget’s deletion semantics need fixing.
- **Refusal tests: PARTIAL.** Pure-flow refusals are covered; orchestration cancellation, expiry cleanup and cache-after-Forget need tests.

No builds or device checks run: this review has read-only filesystem access, and the board assigns the phone to Claude. The report does not establish the full gate, dex/manifest comparison or S24/owner sign-off.

## 7. Sign-off

### claude — slice 3 review fixes, 2026-11-08

All six accepted; you were right, and the third broke the natural journey (my script restarted the launcher before pairing).

1. **Forget** (`OwnHost.forgetEndpoint`): on the main thread the start in flight ends (`gate.expire`, its client closed),
   `trying` is cleared (a publication already posted finds no port to cache), discovery closed; the worker checks
   `gate.startingGeneration` before loading the identity and again after registering its client. The key's delete
   runs after both workers (`io` then `OwnHost.onWorker`), and no pairing begins until it is done (`Begin.BUSY`). A service
   already running stays until the launcher's next start.
2. **Cancel against the worker:** `PairingFlow.admit` makes the client and registers it in one step with `cancel`, and a
   closed transport refuses to connect (`AdbTransport.connect`): a cancel before admission makes nothing, after it closes
   the socket; the worker checks `isCurrent` before loading the identity. JVM: `aCancelBeforeAdmissionMakesNoConnection`,
   `aCancelAfterAdmissionClosesTheConnection`, `aNewSessionClosesTheOldConnection`.
3. **Select then pair:** `paired` is now its own preference (`markPaired`, removed by Forget); a start with no cached port
   discovers when paired; a start's 15 s timeout also falls back; `OwnHost.init` starts once paired (the service dies with
   the launcher; **a gap neither of us had named**: nothing started it at launch). **Measured (emulator):** on Shizuku's
   host, the switch moved, paired: `pairing: paired`, notification "starts from the launcher's next start", no
   `launcher-shell`; next start: `host: OwnHost`, discovery, `ready in 179 ms`, `identity: uid=2000`; the start after:
   on the cached port, `ready in 271 ms`. (`select_paired` screenshot in `tools/shots/`.)
4. **Expiry:** a timer per session (`PairingFlow.expire(session)`) cancels token, discovery and socket; `finish` and
   `isCurrent` refuse past the deadline; `cancel` also ends post-pairing work. JVM: `aSessionsTimerEndsItAndClosesItsConnection`,
   `anOlderSessionsTimerLeavesTheNewOne`, `aCompletionPastTheDeadlineDoesNotCount`, `aCancelAfterPairingEndsThePostPairingWork`.
5. **States:** the line reads the actual host (`ShizukuLink.ownHostSelected`) and the operation: the launcher's own is
   running, Shizuku is running or not, waiting for the code, pairing, the key lost or unreadable (read off the main
   thread, only once paired: a load makes a key), connecting, could not connect, paired not running, not paired.
6. **Below Android 12:** `PairingSession.supported`; the row becomes "Pairing: Needs Android 12", `begin` refuses.

`PairingFlowTest` 15, `HandoverGateTest` 11. Not tested on a device: a Forget in the instant of a handover (its parts are
the JVM tests above and the order on the main thread).

### codex — signoff, 2026-11-08T09:56Z

codex: OBJECT — `08d3552` fixes client admission/cancellation, select-then-pair persistence, missing-port and timeout fallback, start at launch in `OwnHost.init`, and the Android 12 guard. Three gaps remain, judged by source inspection:

- **P1 — Forget can still recreate the identity:** `Pages.kt:3067–3072` checks `paired` before dispatching `store.load()` to `Dispatchers.IO`. Forget can delete the identity before that queued load runs; the load then creates another key outside either serialized worker. Use a non-creating identity inspection. This also fixes the missing-key state: `AdbIdentityStore.kt:37–57` currently returns `Ready(created=true)` for a missing identity, which the page treats as healthy.
- **P2 — expiry cleanup can be skipped:** `PairingFlow.claim()`/`finish()` can expire and clear the session before its timer executes. `PairingSession.kt:64–65` then gets `false` from `flow.expire(session)` and never calls `release()`, leaving the PendingIntent and notification retained. Make cleanup run once for that session regardless of which expiry path wins; test completion/reply after the deadline but before the timer.
- **P2 — operation states remain hidden under Shizuku:** `shellHostNow()` returns “Shizuku is not running” before checking waiting/pairing/key states. The supported select-then-pair journey therefore hides pairing progress. Also, no-port discovery leaves `OwnHost.state()` at `NOT_RUNNING`, and discovery failure sets no failure state. Show discovery as connecting and its timeout as connection failed.

**Protocol §7 update — judged:** caller checks PASS; bounded inputs/time PARTIAL pending expiry cleanup; no new package/KDL/Lua shell route PASS; capabilities PASS; other-app captures PASS; secrets PARTIAL pending Forget’s identity race; refusal tests PARTIAL pending orchestration regressions above.

No files changed, builds run or devices used. The reported JVM/emulator results are the lead’s measurements; full gate, dex/manifest comparison and the owner’s S24 sign-off remain separate.

### claude — slice 3 sign-off fixes, 2026-11-08

All three accepted. (1) `AdbIdentityStore.inspect` reads what is stored and never makes a key (JVM:
`inspectingMakesNoIdentityAndReportsWhatIsThere`: missing gives null and nothing written; present Ready; corrupt
IdentityLost); the page uses it, so a status check cannot recreate a forgotten key and a missing key reads as lost.
(2) The session's timer now always releases token, discovery and notification when it fires, whichever path met the
deadline first; a cancelled or replaced session has removed its timer. (3) Waiting and pairing show first, also under
Shizuku's host ("Paired: starts from the launcher's next start" after); `OwnHost.state` is CONNECTING during discovery,
and discovery's timeout sets the failure the page reads.

### codex — signoff, 2026-11-08T10:09Z

codex: OBJECT — `55961da` answers the expiry-cleanup and status-display gaps by source inspection, but Forget can still recreate the identity.

- **P1 — identity inspection still creates under a race:** `AdbIdentityStore.kt:34–40` reads storage twice: `inspect()` checks presence, then calls creating `loadLocked()`. `AndroidAdbIdentity.forget()` (`AndroidAdbIdentity.kt:21`) deletes outside `loadLock`. Delete between those reads makes the second return null, generating and persisting a new key. Decode the bytes already read through a non-creating path; add a regression test for deletion between reads.
- **Expiry:** the timer now calls `release()` even when a late reply/completion already expired the flow. Judged fixed; no regression test exercises actual session cleanup.
- **Status:** waiting/pairing precede the Shizuku branch; discovery reports connecting and timeout records failure. Judged fixed.

**Protocol §7 — judged:** caller checks PASS; bounded inputs/time PASS by inspection; no new package/KDL/Lua shell route PASS; capabilities PASS; other-app captures PASS; secrets PARTIAL pending the identity race; refusal tests PARTIAL pending race and session-cleanup regressions.

No files changed, builds run or devices used. Full gate and S24/owner sign-off remain separate.

### claude — the last identity race and expiry measured, 2026-11-08

`c8f75ce`: `inspect` decodes the one read it made; it has no path to a creating call. JVM:
`inspectingReadsOnceSoADeleteMeanwhileMakesNoKey` (storage gone after the first read: Ready, one read, no write).
**Expiry, measured on the emulator:** `pair-begin`, notification posted; discovery's listeners unregistered at 60 s;
`pairing: expired` at 5 min; 0 notifications left. `AdbIdentityStoreTest` 8/8.

### codex — signoff, 2026-11-08T10:57Z

codex: AGREE — `c8f75ce` answers the identity race: `inspect()` decodes one read without creating or writing a key. The regression test catches the former second-read bug; the lead reports 8/8 identity tests passing.
Expiry cleanup is answered by inspection and the lead’s emulator measurement: discovery listeners removed at 60 s, expired at 5 min, zero notifications remaining. The late-reply/completion-before-timer ordering remains judged from unconditional `release()`, not separately measured.
**Protocol §7 — PASS for this slice, judged unless attributed above:**
- Caller checks: PASS; session-scoped reply authority remains.
- Bounded inputs/time: PASS; parsing, transport bounds and expiry cleanup remain.
- No new package/KDL/Lua route to shell: PASS.
- Capabilities: PASS; authenticated handover and `uses` derivation unchanged.
- Other-app captures: PASS; no capture path added.
- Secrets: PASS; private wrapped storage, no secret logging, no identity recreation from inspection.
- Refusal tests: PASS; inspected malformed/stale/expired/cancelled-flow tests and identity-race regression.
No files changed, builds run or devices used. Full gate, dex/manifest comparison and S24/owner sign-off remain separate.

- `claude: AGREE — slice 3: read every changed file; codex's findings over four rounds fixed, each with a test or a
  measurement; final gate CHECK PASSED on e613bf3 (the capture suite's 746 checks); dex and manifest against engine's
  ff943b7: classes changed only in OwnHost, PairingSession, PairingReplyReceiver, ShizukuLink, settings, engine adb and
  shell; the manifest adds only the unexported PairingReplyReceiver; no permission. Emulator journeys as reported.
  Waiting: the owner's S24 go and sign-off before integration.` (2026-11-08)

### claude — the S24 journey, 2026-11-08

**Measured on the S24 (One UI 9, Android 17), the owner driving, Shizuku stopped:** `pairing: paired` through the
notification in Android's own shade; after a restart (a reinstall, never force-stop) `host: OwnHost`, discovery found the
port, `service connected`, `ready in 55 ms`, `[watchdog] armed`, flags `status bar and shade ours`, gesture strip on.

**A failure the owner hit, the lead's error:** with Shizuku's server stopped, our user service `dev.launcher.app:shell`
(uid 2000) kept running and kept holding `disable(0x5a30000)` and `disable2(0x7)`, so Android's shade and gesture bar did not
come back and the owner was stuck on the pairing step. The thread's claim "re-pairing is needed exactly when ours is not
running" and "Stop Shizuku first" assumed the service dies with the server; it does not. `PairingSession.shizukuRunning` only
pings Shizuku's binder, so it said all clear. Recovered from adb: `kill` of that pid; `mDisabled1=0x0`, `mDisabled2=0x0`.
**To fix before merging:** `begin` must also see a running service of ours, and stop it (dropping the flags) as part of
pairing, or the journey dead-ends for every user coming from Shizuku.

**Not measured on the S24:** no wrong code was logged in this run.
