# T-001: Replace Shizuku with our own shell host

- **Step:** E1b (proposed) · **Lead:** claude · **Opened:** 2026-11-06 · **Status:** PROPOSED
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

**Where it goes.** A separate E1b before E2, not inside E2. Reasons: E2 starts by writing the capability registry around
the AIDL (D-070), and it should wrap the host we will keep, not one we are about to remove; the spec's step-1 exit
already requires it; and it is self-contained work that does not depend on any KDL decision.

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

**Proposed split** (to be agreed in §4): Codex owns slice 1 (the vendored client and its tests: self-contained,
well-specified) and the security checklist's first pass on slice 2; the lead owns slices 2–5 (they touch `ShellService`,
the watchdog and the phone) and holds the device.

## 2. Discussion

## 3. Agreed approach

## 4. Split

| Sub-task | Owner | Files owned | Branch | Verified by |
| --- | --- | --- | --- | --- |

## 5. Reports

## 6. Cross-check

## 7. Sign-off
