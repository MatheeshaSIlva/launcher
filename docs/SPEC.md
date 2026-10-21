# Product spec

> **Status: LOCKED (approved by the owner 2026-10-22).**
> Keep this to one page. Agents must not edit this file; they propose changes in `docs/QUESTIONS.md`.

## What it is
An Android launcher that replaces home, recents and the notification / Quick Settings shade with one interface of its
own. Its look, layout and motion are fully themeable, and it runs without root by using Shizuku. It must feel like part
of the system: premium, alive, iOS-grade in detail.

## Who it's for
**Themers and tinkerers**: people who want full ("Hyprland-level") control over how their phone looks and moves, and
who are held back by stock Android and One UI. The iOS 27 profile is the polished first theme; the engine and builder
are the product.

## Core journeys (all four must be excellent in v1)
1. **Home and app open/close**: home grid, dock, folders, widgets, App Library, Spotlight, and launching and closing apps
   with our own cards that follow the finger.
2. **App Switcher and gestures**: swipe home, hold for the deck, quick switch, picture-in-picture, all on one gesture set.
3. **Shade everywhere**: our status bar, Control Center, Notification Center and banners over every app, with no stock
   panel showing next to ours. Two layouts ship: iOS 27 and Pixel (Android 17).
4. **Customise look and motion**: the settings app (themes, colours, fonts, motion presets and moments, layouts) and a
   **full in-app theme builder** that writes theme and motion files.

## Non-goals (v1)
- Online theme gallery or theme sharing.
- Phones other than the Galaxy S24.
- A One UI shade layout (only iOS 27 and Pixel ship in v1).
- Play Store release and Play Billing (v1 ships through GitHub builds and the in-app updater).

## Constraints
- Platform: Android, Kotlin, Views plus Jetpack Compose (settings), Gradle 8.9 / AGP 8.7.3 / Kotlin 2.0.21. Test device
  is a Galaxy S24 (One UI 9, Android 17), with the Android 17 emulator for checks before the phone.
- No root. Shizuku unlocks the advanced (paid) features. Without Shizuku the app still works as a plain launcher.
- The three rules from CLAUDE.md outrank every feature: **perfectly smooth** (120 Hz, measured from frame stats, not
  eyeballed), **fully consistent** (one shade, one bar, one gesture set everywhere), **attention to detail** (every change
  animated, interruptible and retargetable; nothing pops).
- Safety: everything that changes system state auto-restores (watchdog, Restore system) and can be recovered without
  Shizuku.
- Layouts, themes and motion stay three separate layers: any combination is valid, and a theme never chooses a layout.
- Privacy: never screenshot or record the owner's notifications or private apps.
- Builds: CI on every push to `main` and the in-app silent updater stay; local builds and checks run on the Windows dev machine (JDK 17, Git Bash).

## What "polished v1" means
- **Onboarding works:** a fresh user installs the app, sets up Shizuku and its permissions, and reaches the full experience
  without adb.
- **0 missed refreshes:** every core journey's scenario script on the S24 (warm) shows 0 missed refreshes in framestats.
- **Polish pass:** every screen and element passes `.claude/skills/polish-pass` (nothing pops, no stock seams, no literals).
- **Zero open P0/P1 bugs:** no crashes, no stuck states (stuck stock UI, frozen home, blocked gestures), no broken core journey.
