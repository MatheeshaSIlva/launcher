# Not a Launcher — Engine Spec

_2026-11-06 · canonical copy; edit this file, not the Claude Doc._

## Scope and principles

The engine is a widget runtime, not a theming engine. The target is Quickshell parity on a phone: a creator can write a component nobody anticipated, bind it to live system data, and ship it, and a user can run that component beside components from other creators. A creator who has a Hyprland rice with a Quickshell bar, a Rofi launcher and a Dunst theme should be able to port it as a set of components and a palette, not rebuild it from a menu of our widgets.

Five rules everything else obeys:

1. Declarative tree, imperative behaviour. A component is a KDL view tree plus an optional Lua block. The script decides what to render; the engine renders. Scripts never touch pixels.
2. Data is subscribed, not fetched. Every system source is a stream the component binds to. The engine owns the Android side; a script sees values and events.
3. Every source and every action is a capability, declared in the manifest and approved by the user at install. A component with no script needs no capabilities and no trust.
4. The engine is Compose for the tree and its own layers for the pixels, on phone and in the desktop editor, running the same interpreter. Compose owns parsing, hierarchy, layout, props, state and recomposition when data changes. Each primitive draws into a RenderNode the engine owns, and gesture-driven motion (a shade pull, a card fling) drives those nodes from the transition engine without passing through Compose state. The launcher audit showed why: per-frame Compose drawing cost frames in four measured ways (accessibility re-reads on recomposition, `SnapshotStateMap` churn, an alpha layer per item, seeking a spring through state), and the hand-drawn views that replaced it hold 120 Hz. The editor's preview is what ships. Decision: a small, bounded frame cost is accepted in exchange for versatility; see *Frame budget* for the numbers.
5. Mixing is first-class. A surface is a slot; any component that satisfies the slot's contract can fill it, whoever wrote it. A layout is a named set of fills for the slots, and a user can override any one of them.

Out of scope for the engine, by Android's rules: the status bar, lock screen, always-on display, system navigation, the keyboard, and other apps' windows. The engine draws its own surfaces and overlays and controls the system through a fixed allowlist over Shizuku.

## Data API and capabilities

A component declares the sources it reads and the actions it calls in its manifest. Each maps to one capability. Sources are streams: the engine pushes the current value on subscribe and every change after. Actions are calls that return a result or an error. Nothing else exists; there is no generic shell and no generic Android object.

### Sources

| Source | Shape | Capability | Android mechanism |
| --- | --- | --- | --- |
| `time` | now, zone, locale, 12/24h; ticks at second or minute | none | system clock |
| `battery` | level, charging, health, time-to-full, temperature | none | BatteryManager |
| `network` | type, wifi ssid and strength, cellular bars and carrier, airplane, vpn | `network.read` | ConnectivityManager, WifiManager |
| `bluetooth` | on, connected devices with battery where reported | `bluetooth.read` | BluetoothManager |
| `audio` | volumes per stream, mute, ringer mode, active output | `audio.read` | AudioManager |
| `media` | active sessions: title, artist, art, position, state, app | `media.read` | MediaSessionManager (notification listener) |
| `notifications` | the live list: app, title, text, icon, actions, time, group | `notifications.read` | NotificationListenerService |
| `apps` | installed launchable apps, labels, icons, usage order; install and uninstall events | none | LauncherApps, PackageManager |
| `tasks` | recent tasks with thumbnails | `tasks.read` | ActivityTaskManager via Shizuku |
| `shortcuts` | an app's static and dynamic shortcuts | none | LauncherApps |
| `widgets` | hosted AppWidget views | `widgets.host` | AppWidgetHost |
| `display` | brightness, auto-brightness, rotation lock, night mode, dark theme | `display.read` | Settings |
| `device` | model, screen metrics, cutout insets, fold state, dpi | none | Resources, WindowManager |
| `sensors` | light, proximity, step count, orientation at low rate | `sensors.read` | SensorManager |
| `location` | coarse location, updated on request | `location.coarse` | FusedLocation (runtime permission) |
| `calendar` | upcoming events in a window | `calendar.read` | CalendarContract (runtime permission) |
| `contacts` | starred contacts | `contacts.read` | ContactsContract (runtime permission) |
| `storage` | free and used space per volume | none | StorageStatsManager |
| `http` | GET and POST to allow-listed hosts, JSON or text, with cache and rate limit | `net.http` with hosts listed | engine-side client; no raw sockets |
| `file` | read a file inside the component's own package or its sandbox dir | none | package assets |
| `kv` | the component's own key-value store, persisted | none | engine store |
| `env` | theme tokens, active surface, locale, reduced motion, which other components are present, caps.\<name> for every optional capability (granted or not) | none | engine |

### Actions

| Action | Capability | Mechanism |
| --- | --- | --- |
| `apps.launch(pkg, activity?)`, `shortcuts.launch(id)` | none | LauncherApps |
| `apps.info(pkg)`, `apps.uninstall(pkg)` | none (uninstall prompts) | system intents |
| `notifications.open(key)`, `.dismiss(key)`, `.action(key, i)`, `.reply(key, text)` | `notifications.act` | NotificationListenerService |
| `media.play/pause/next/prev/seek(pos)`, `.volume(stream, v)` | `media.control` | MediaController, AudioManager |
| `system.wifi(on)`, `.bluetooth(on)`, `.data(on)`, `.airplane(on)`, `.torch(on)`, `.dnd(mode)`, `.rotate(lock)`, `.saver(on)`, `.brightness(v)`, `.nightmode(on)` | `system.toggles` | `svc`, `settings put`, `cmd` over Shizuku; CameraManager for torch |
| `tasks.switch(id)`, `.remove(id)`, `.lockTask(id)` | `tasks.act` | ActivityTaskManager via Shizuku |
| `apps.freeze(pkg)`, `.kill(pkg)` | `apps.manage` | `pm`/`am` over Shizuku |
| `shade.open()`, `.close()`, `surface.show(role)`, `.hide(role)` | none | engine |
| `haptic(kind)`, `toast(text)`, `clipboard.copy(text)` | none | system |
| `intent(action, data?, extras?)` to an allowlist of safe actions | `intent.send` | startActivity, never broadcasts to other apps' components |
| `kv.set(k, v)`, `file.write(path, data)` in sandbox dir | none | engine store |

Rules:

- Every runtime permission the engine needs for a source is requested once, at the moment a component that declares it is installed, with the component's own explanation shown.
- `http` hosts are listed in the manifest and shown to the user. Requests go through the engine's client with a 2 KB/s per-component budget by default and a shared cache. No sockets, no arbitrary ports.
- There is no `shell`, no `exec`, no reflection and no intent broadcast. If a creator needs something not on this list, the path is a feature request to the engine, not a workaround.
- Capabilities are shown to users as plain sentences at install: "reads your notifications", "can toggle wifi and bluetooth", "fetches from api.open-meteo.com".

## Component model

A component is one KDL file: a header with props and capabilities, a view tree, and optionally a Lua block that owns state. This is the QML shape — declarative tree, imperative behaviour — with the script kept off the render thread.

```kdl
component "battery-ring" version="1.2.0" {
  props {
    size      type="dp"    default=48
    thickness type="dp"    default=4
    warn      type="int"   default=20
  }
  uses { source "battery" }

  state {
    level 100
    charging false
    color "@ink"
  }

  view {
    ring size="@size" thickness="@thickness" value="$level" color="$color" {
      text "$level%" font="mono" size="@thickness*3"
      icon "bolt" visible="$charging"
    }
  }

  script lang="lua" {
    on("battery", function(b)
      state.level = b.level
      state.charging = b.charging
      state.color = (b.level <= props.warn and not b.charging) and "@signal" or "@ink"
    end)
    on("tap", function() act.intent("android.intent.action.POWER_USAGE_SUMMARY") end)
  }
}
```

The parts:

- **props** are the public knobs, typed. The editor shows them as controls; a layout sets them; a theme can override the ones that are tokens. `@name` reads a prop or a token.
- **uses** declares sources and actions. The manifest capability list is derived from this, not written twice.
- **state** is the component's own mutable values. `$name` binds a view attribute to state. Changing state marks the component dirty; the engine re-renders it on the next frame.
- **view** is the tree. Primitives are the engine's: `box`, `row`, `column`, `stack`, `grid`, `scroll`, `text`, `icon`, `image`, `ring`, `bar`, `sparkline`, `glass`, `spacer`, `button`, `slider`, `switch`, `list` with an item template, `appicon`, `widget` (an AppWidget host), `surface-slot`. Every primitive takes layout, padding, radius, background, opacity, transform and the animation attributes. Conditionals and loops are attributes, not script: `visible="$charging"`, `each="$items" as="n"`.
- **script** runs in a Lua worker. It gets `props`, `state`, `on(event, fn)`, `act.*`, `env`, `kv`, `timer.every(ms, fn)`, `timer.after(ms, fn)`, `http.get(url, fn)`. Events: every subscribed source by name, `tap`, `longpress`, `swipe(dir)`, `drag(x, y, phase)`, `appear`, `disappear`, `focus`, `theme`, `mounted`, `unmounted`. Handlers must return quickly; a handler over 8 ms is logged, over 50 ms is killed and the component shows its error state.
- **animations** are declared on the view, never scripted: `transition="value 200ms ease-out"`, `enter="fade-up 240ms"`, `exit="fade 120ms"`, `spring="stiff"`. The engine runs them on the UI thread at frame rate; the script only changes values.

Composition: a component can mount another by name with props, `use "clock" style="mono"`, from its own package or from any installed package the user has allowed. A component can expose named **slots** that a parent fills, which is how a bar component takes modules from other creators: `slot "left"`, `slot "right"`, and the layout file lists what goes in each.

States and variants: `variant "compact"` blocks inside `view` override attributes when the host sets `variant="compact"`, which the editor uses for the different states of one component (idle, pressed, warning, empty).

Error handling: a component whose script throws, whose source is denied, or whose view fails to resolve renders its `fallback` block if it has one, else a quiet placeholder with the component name. One broken component never takes a surface down.

## Surfaces

A surface is a slot the engine owns: a place on the phone, the gesture that summons it, and a contract. Any component that satisfies the contract can fill the slot. A surface component is an ordinary component whose header says `fills "shade"`; the contract adds a few required props and events and grants the slot's sources without a separate capability prompt, because the user chose to put it there.

| Surface | Where it lives | Summoned by | Gets for free | Must handle |
| --- | --- | --- | --- | --- |
| `home` | the launcher activity, under everything | home press, back from anywhere | `apps`, `shortcuts`, `widgets` host, wallpaper slot, page count | `home` press (scroll to first page), long-press on empty (opens edit), drop of an app icon |
| `drawer` | over home | swipe up on home, or a component calling `surface.show("drawer")` | `apps` with search index, usage order | query input, dismiss on swipe down or back |
| `shade` | overlay window over any app | pull from the top edge, or a call; the engine handles the edge strip and `SYSTEM_ALERT_WINDOW` | `notifications`, `media`, `audio`, `network`, `bluetooth`, `display`, `system.toggles` | partial pull (progress 0–1 arrives as `pull` events), dismiss, the glass material behind it |
| `recents` | over home, replacing the system overview when the launcher is default | overview gesture or a call | `tasks`, thumbnails | switch, remove, clear all, dismiss |
| `folder` | popover on home | tap on a folder | its app list, its name | rename, drag out, dismiss |
| `search` | over home or drawer | a component calling it, or swipe down on home | `apps`, `shortcuts`, `contacts` if granted, a web hand-off action | query, results list, dismiss |
| `lock` | not available | — | — | Android keeps the lock screen. The slot exists in the file format so a layout can say so honestly and the store can show it greyed. |
| `status` | not available as a replacement | — | — | A component can draw its own status row inside its surface. The real bar stays. |

Mixing:

- A **layout** is a file that fills slots: `fill "home" with "mira/paper-home"`, `fill "shade" with "koji/glass-shade"`, plus theme and per-fill props. A whole-phone layout fills all slots; a partial one fills some and leaves the rest to the user's current choices.
- The user can override any fill from settings. The engine keeps `user overrides > layout fills > engine defaults`. Install a bar-only port and your home stays.
- Surfaces talk to each other only through the engine: `surface.show`, `surface.hide`, and `env.surfaces` (which slots are filled and by whom). No component reaches into another's state.
- Transitions between surfaces are engine-owned so mixed fills still feel like one phone: the shade's pull, the drawer's rise, recents' zoom. The model is the launcher's `motion/transition` engine, ported verbatim: a transition is a set of keyframe tracks over 25 properties (position, scale, alpha, radius, blur, tint, depth and the rest), each keyframe with its own easing, tracks running on per-role clocks, and hand-over mid-gesture that never jumps (a transition in flight is retargeted, not restarted). A surface component declares `motion="inherit"` (default) or supplies its own `enter` and `exit` tracks in KDL, which the engine runs against the same gesture progress. Fifty-two unit tests cover the track maths and come along with it.
- A fill that is missing or broken falls back to the engine default for that slot, never to nothing.

What a component cannot be: a surface that Android owns. The engine refuses fills for "lock" and "status" at validation time and says why.

### Surface presentation

How a surface follows the finger is an engine `presentation` type with knobs, not something each creator re-derives. The launcher's three proven models ship as presets, every number exposed:

| Preset | Progress | Commit | Knobs |
| --- | --- | --- | --- |
| `sheet` (iOS notification centre) | y / height, rubber-banded past 1 | from a standstill opens past 0.625 | rubber-band strength, open threshold |
| `presence` (iOS control centre) | dy / `open-travel`, clamped; offset spring `100u·(1−e^(−d/270u))` with velocity hand-off | fling or past 0.4 | `open-travel`, spring curve constant, hand-off gain |
| `two-stage` (Pixel shade) | dy / `first-travel` from touch slop; second stage on a second pull | 56 dp or 125 dp/s; two fingers go straight to full | both travels, commit distance, commit velocity |

Shared rules, also knobs: a fling is 900 px/s; a release opens past 0.4; a drag on an open surface closes below 0.75; swipe up from the bottom always closes; a surface on its way out can be caught and moved on. Placements are part of the summon config: which zone of the top edge (`top`, `left-of-camera`, `right-of-camera`, measured from the display cutout) opens which surface.

### Banners are mandatory

Hiding the stock shade (disable flags on a binder token) also suppresses heads-up notifications and full-screen intents, including incoming calls. A shade fill therefore either supplies a `banner` component or inherits the engine default; the validator refuses a shade package that does neither. The default carries the launcher's banner policy: 7 s show time, ringing persists until answered, replies are not offered (the keyboard would sit under the overlay). The status bar, banners, panels and the app-opens-from-notification card share one window; the engine hosts all four in the shade surface so their z-order is fixed.

## Theme and tokens

A theme is a KDL file of tokens. Components reference tokens with `@name`; the engine resolves them at render time, so swapping a theme never touches a component. Resolution is the launcher's three-tier model, ported as is: `ref` tokens are raw values (a palette, a type scale), `sys` tokens are semantic roles that point at refs (`sys.signal`, `sys.surface`), and `comp` tokens are per-component roles that point at sys (`comp.shade.card.bg`). A component declares the `comp` tokens it uses in its `uses` block; a theme sets any tier and inherits the rest. Chains resolve up to six deep with provenance kept for the editor's inspector; light and dark are blended continuously rather than switched; `@palette` references (Material You, an imported base16) resolve at draw time. The groups below are the `sys` tier; every group has engine defaults, so a theme can set three colours and inherit the rest.

| Group | Tokens | Notes |
| --- | --- | --- |
| colour | `bg`, `surface`, `surface2`, `ink`, `ink2`, `muted`, `signal`, `ok`, `warn`, `danger`, `base00`…`base0F` | The base16 slots exist alongside the semantic ones, so a ported palette maps 1:1 and the semantic names derive from it by default (`bg=base00`, `ink=base05`, `signal=base09`…). A theme can set either side. |
| type | `font.ui`, `font.mono`, `font.display`, sizes `xs`…`xxl`, `tracking`, `leading` | Fonts by Google Fonts family name or `system`. Never bundled. |
| shape | `radius.sm/md/lg`, `squircle` (bool), `stroke` |  |
| space | `unit` (default 4 dp), `gap.sm/md/lg`, `inset` |  |
| motion | `dur.fast/base/slow`, `ease.out/io/spring`, `decode` (bool) | A theme can turn the decode effect on for text that changes. |
| glass | `blur`, `tint`, `tintAlpha`, `edge`, `refraction` (0–1), `grain` | One material, four tiers, picked per device and per situation. Components only say `background="@glass"`. See *Glass tiers* under Sandbox and performance. |
| icon | `pack` (an installed icon pack or `system`), `shape`, `stroke`, `mono` (tint icons to `ink`) | Icon packs are referenced, never embedded. |
| wallpaper | `slot` (`system`, `user`, or `none`), `blurUnderShade` | Wallpapers are the user's. A layout may suggest one by URL in its listing; the engine never bundles it. |

Importing palettes, built into the engine and the editor:

- **base16 / base24** YAML: read `base00`–`base0F` directly.
- **pywal / wallust** `colors.json`: `special.background/foreground/cursor` and `colors.color0`–`color15` map onto base16 with the standard terminal-to-base16 correspondence.
- **Catppuccin, Gruvbox, Nord, Rosé Pine, Tokyo Night, Everforest, Dracula**: shipped as named presets; `theme "catppuccin-mocha"` works with no file.
- **Hyprland `hyprland.conf` colour lines** and **Waybar `style.css` `@define-color`**: the editor parses both and offers the colours as a palette to assign.
- **Material You**: `theme "dynamic"` pulls the system palette on Android 12+ and keeps updating with the wallpaper.

Light and dark: a theme is one mode; a layout may name a `dark` and a `light` theme and the engine follows the system setting or a schedule. Contrast: the editor warns when `ink` on `bg` or `signal` on `surface` falls under 4.5:1, and the store shows the warning on the listing.

## File format and package layout

Everything is text in KDL, in a directory that is also a git repo. A package is one of three kinds and the manifest says which.

```
mira-paper/
  package.kdl          manifest: kind, name, version, engine, author, licence, capabilities (derived), listing text
  layout.kdl           kind=layout only: fills per surface, theme, per-fill props
  theme.kdl            kind=theme, or a layout's bundled theme
  components/
    clock.kdl
    bar.kdl
    bar/left.lua       a script can live inline or beside its component
  assets/
    arrow.svg          vector preferred; raster capped at 512 KB each, 4 MB per package
  README.md            shown in the store; screenshots referenced here
  LICENSE
```

Manifest, the required part:

```kdl
package "paper" kind="layout" version="2.1.0" {
  engine  ">=1.0 <2"
  author  "mira" url="https://github.com/mira"
  licence "CC-BY-4.0"
  title   "Paper"
  summary "A list, not a grid. Ink on white."
  hosts   "api.open-meteo.com"
  requires { icons "any" ; wallpaper "light" }
}
```

Rules:

- `kind` is `layout`, `theme`, or `component`. A component package may hold several components; a layout package may bundle components and a theme. The store lists all three kinds; a layout's listing shows which components it pulls from other packages.
- Dependencies are by package name and semver: `use "koji/glass-shade" ">=1.3"`. The engine installs them from the store with the user's consent, showing their capabilities together.
- Capabilities are never hand-written; the engine derives them from every `uses` block in the package and refuses a package whose listing claims fewer. The install prompt shows the union.

  Tier is derived the same way (decided 2026-11-03: one paid tier, **Pro**, sold as a subscription or a lifetime licence through Play Billing). A capability is either free or Pro; a package's tier is the highest its `uses` need. Free: every source and action that needs no Shizuku, the home, drawer, folder and search surfaces, themes, snippets and expression bindings. Pro: anything that touches system state or runs over other apps: the Shizuku-backed sources and actions, the shade, status and recents surfaces, overlay surfaces, all four glass tiers, scripts, rules, `background`, providers and remote components. The engine refuses to mount a Pro package on a free install and names the capability that needs Pro; the store badge comes from the same computation. A `uses` entry may be marked `optional`: the package then mounts without it and reads `env.caps.<name>` (false when the user lacks the tier, the device lacks the feature, or the user denied it) to degrade.
- **Versioning:** the engine version is `major.minor`. A package declares a range. The engine keeps every `1.x` package working; a `2.0` engine may break `1.x` packages and the store flags them. Primitives and sources are only ever added within a major.
- **Licence** is required and is one of CC0, CC-BY-4.0, CC-BY-SA-4.0, MIT, or `personal` (paid, no redistribution). The store shows it; forks must keep attribution, and the engine keeps the author chain in `package.kdl` under `derivedFrom`.
- **Hand-written and editor-written files are the same files.** The editor writes stable, diffable KDL with sorted attributes and no generated ids, so a git diff of an editor change is readable.
- Distribution: a data-only APK on Play (`nal pack --play` emits the project: a manifest with no activity, the package directory as assets, icon, listing text and screenshots rendered from `fixtures/`; the engine discovers installed packages through PackageManager the way launchers find icon packs), a git URL (`install https://github.com/mira/paper`), or a local directory (sideload with the app's file picker). All three install the same directory. Paid packages are Play only: the creator sets the price, Google pays the creator, and the engine checks `getInstallSourceInfo` is Play before mounting any package whose manifest says `paid`. Free packages may use any of the three. The platform takes nothing from creator sales; its revenue is Pro.
- Validation: `nal check .` in the CLI (and the editor) parses, resolves tokens, lints capabilities, renders every component at three sizes, and reports contrast and performance budget before publish.

## Porting dotfiles

A desktop rice is a set of programs, each with its own config. On the phone they become components in one package. This is the map a creator follows; the editor's import wizard follows the same one.

| Desktop piece | Becomes | What carries over | What changes |
| --- | --- | --- | --- |
| Hyprland `hyprland.conf` colours, gaps, rounding, blur, animations | `theme.kdl` | `col.active_border` → `signal`, `gaps_in/out` → `space.gap`, `rounding` → `radius`, `blur` size and passes → `glass.blur`, `bezier` curves → `motion.ease` by name | Window rules and workspaces have no phone equivalent; the home's pages are the closest thing and a layout can set their count |
| Quickshell / AGS / EWW bar | a `bar` component with slots, filling `shade` (pull-down) or sitting at the top of `home` | the module list and order become `slot` fills; each module's QML or JS logic becomes a Lua block over the same data (`time`, `battery`, `network`, `audio`, `media`, `notifications`) | no workspaces module; a `tasks` module is the analogue. D-Bus calls become `act.*` calls or feature requests |
| Quickshell / AGS launcher, Rofi, Wofi, Fuzzel | fills `drawer` or `search` | the list style, the prompt line, the fuzzy match (engine provides `apps` search), the theme | no run-command mode; `shortcuts` and `intent` cover what Rofi modes did |
| Dunst, Mako, SwayNC | the notifications part of a `shade` fill, or a `toast` component | the card style, timeouts, grouping, urgency colours (`ok/warn/danger`) | the engine delivers notifications; the component only draws and acts on them |
| Waybar `style.css` | `theme.kdl` colours and a `bar` component's view | `@define-color` lines import directly; module padding and fonts map to tokens | CSS selectors become component props; no arbitrary CSS |
| Hyprlock, Swaylock | nothing | — | Android owns the lock screen. The listing says so and the user keeps the system one |
| Swww, Hyprpaper, wallpaper scripts | `wallpaper` token and a listing suggestion | a wallpaper-cycling script becomes a component using `timer` and `act.wallpaper(uri)` (a `wallpaper.set` capability) | the image itself is the user's, chosen through the system picker |
| Kitty, Alacritty, Foot colours | palette import | the 16 colours map to base16 | the phone has no terminal surface; a `term`-styled home is a component that draws like one |
| Cava, Btop widgets | components over `audio` (visualiser gets a coarse level stream), `device`, `storage`, `battery` | the look | no raw audio samples; no process list |
| Pywal, Wallust | `theme "dynamic"` or a palette import | the generated palette | on Android 12+ Material You does the same job live |
| Neovim, tmux, zsh configs | nothing | — | out of scope; the port is the visual shell |
| Keybinds | gestures | `bind = SUPER, D, exec, rofi` becomes `gesture "swipe-up" surface="drawer"` in the layout; double-tap, long-press and edge swipes are available | no modifier keys; the gesture set is fixed by the engine |

What the import wizard does with a repo URL: clones it, finds the files above by name, offers the palette, proposes a package with one component per recognised program, writes the manifest, and leaves the Lua blocks as stubs with the right `uses` and `on` handlers for the creator to fill. It never claims to port logic automatically; it ports structure and colour and hands over the rest.

The three honest gaps, said on the listing when a port hits them: no lock screen, no status bar replacement, no arbitrary processes. Everything else in a typical rice has a home.

## Versatility: what else the runtime must allow

Added after reading the spec against a Quickshell user's first day. Each is a capability or primitive, so the sandbox rules in the next section still hold.

| Need | Addition | Capability |
| --- | --- | --- |
| Draw anything: visualisers, analogue clocks, graphs, custom glyphs | `canvas` primitive whose `path` attribute is a bound value: the script emits vector commands (`M`, `L`, `C`, `A`, `Z`, fill, stroke, gradient) as data; the engine rasterises on the UI thread. Scripts still never touch pixels. | none |
| Animated and reactive wallpapers | a `wallpaper` surface. The engine registers as an Android live wallpaper (WallpaperService) and a component fills the slot, frame rate capped at 30 and paused when covered. | `wallpaper.live` |
| Drag-driven UI, scrubbing, pinch | full pointer events (`pointer(phase, x, y, vx, vy, pointers)`), `pinch`, and a `spring` binding so a value animates physically toward state with interruptible velocity | none |
| Text input: search, rename, quick reply | `textfield` primitive with IME, `submit` and `change` events | none |
| Components talking | a per-package bus: `bus.emit(name, data)`, `bus.on(name, fn)`; package-level `shared` state; cross-package channels only when both packages declare the channel name | none within a package; `bus.cross` across |
| Sources the engine lacks | **providers**: a companion app on Play registers over a ContentProvider/AIDL contract and appears as a source; the engine shows the provider app's name and the user installs it from Play | `provider.<name>` |
| Work while nothing is shown | `background` capability: WorkManager wake at 15-minute minimum intervals, the script runs its `background` handler for up to 10 s | `background` |
| Data pushed in from Tasker, shell, other apps | `broadcast` source scoped to the engine's own intent action with a component-specific key | `broadcast.receive` |
| Images from the network | `image src="https://…"` under the package's `hosts` | `net.http` |
| Sounds | `act.sound(asset)` for package assets, respects silent mode | none |
| Accessibility | `label` attribute on every primitive, `role` on interactive ones; the validator fails a package with unlabeled controls | none |
| Localisation | `strings/<locale>.kdl`, referenced as `#key` | none |
| Work profile | `apps` includes work-profile apps with a `profile` field; the engine draws the badge | none |
| Dev loop | the engine watches a directory over ADB (`nal dev ./`) and hot-reloads components without losing state; the editor does the same over its own link | developer mode |

### The primitive set, in full

About forty nodes plus formatters, the size of QtQuick Controls' useful subset. Everything a creator builds is a tree of these.

| Group | Primitives |
| --- | --- |
| Layout | `box`, `row`, `column`, `stack`, `grid`, `scroll`, `pager` (snapping pages with indicator), `spacer`, `table`, `surface-slot` |
| Content | `text`, `marquee`, `icon`, `appicon`, `image` (asset or URL), `clock` (timezone, format, minute ticks, no script), `qr`, `widget` (AppWidget host) |
| Data display | `ring`, `bar`, `progress` (linear/circular, determinate/indeterminate), `sparkline`, `chip`, `badge` |
| Input | `button`, `switch`, `slider`, `textfield` |
| Containers with behaviour | `list` (virtualised, item template), `sheet` (drag-to-dismiss with spring and velocity), `popover` (anchored, scales from its trigger) |
| Effects | `glass`, `blur`, `mask` (shape, squircle or path), `transform3d`, `shader` (AGSL, time and touch uniforms, one per surface by default), `canvas` |
| Media | `web`, `video`, `map`, `lottie` |
| Remote | `remote` (a companion app's view via SurfaceControlViewHost) |

Formatters are attribute functions, not nodes, so the common twenty lines of Lua never get written: `{{$t | date:'EEE d MMM'}}`, `{{$n | number:1}}`, `{{$b | bytes}}`, `{{$s | duration}}`, `{{$p | percent}}`, `{{$x | upper}}`, `{{$x | pad:3}}`. Locale-aware by default.

Left out on purpose: `camera` (privacy and power; no rice needs it), a general `chart` node (canvas plus a Lua chart helper library covers it, and a chart primitive is an API that never finishes), and form controls beyond the four above (this is a shell, not an app framework).

The ceiling, stated exactly: creators cannot ship native renderers. Canvas plus script covers anything that is a drawing; what it cannot cover is another program's rendering (a web view, a map, a video, a camera preview, a 3D scene, an existing chart library). Three answers close most of that gap:

1. **Built-in media primitives**, shipped in the view interpreter: `web` (sandboxed WebView, no JS bridge, hosts listed; `web.view`), `video` (asset or URL, muted by default; `net.http` for URLs), `map` (tile layer with markers; `map.view`, `location.coarse` if centred on the user), `lottie` (vector animation from a package asset; none).
2. **Remote components**, the native escape hatch for visuals: a companion app on Play exposes a view through `SurfaceControlViewHost` (Android 12+), and the engine mounts it as a node, `remote "com.example.sparkline/Chart" props=…`, laying it out, drawing glass over it and forwarding input. The companion app draws the inside with any native code it likes; the user installs it from Play and sees its permissions there; the store never carries native code. Capability `remote.<package>`. Same shape as providers are for data. Built after the editor: surface lifecycle, input routing and the cost of many mounted remotes are the engineering risks.
3. **Promotion.** Primitives are only ever added within a major version, so adding one is cheap. A public request list, monthly engine releases, and the rule that a remote component three creators have independently built is considered for a built-in.

With those, the remaining line is: no native code in a store package, ever. That is what keeps the sandbox, the frame budget and Play policy true.

## Precision: the knobs the engine exposes

The platform is for people who notice everything. Primitives decide what can be built; this decides how exactly it renders. The rule: every value the engine itself uses is an attribute a creator can set. Nothing is hidden behind a preset.

| Area | Exposed |
| --- | --- |
| Type | Variable-font axes by tag (`wght`, `wdth`, `opsz`, `slnt`, custom) as numbers; OpenType features by tag (`tnum`, `smcp`, `liga`, `ss01`…); tracking in em; leading as a number; `baseline` alignment between siblings; optical margin alignment; hanging punctuation; hyphenation and line-break strategy; `decode` as a text effect with its own duration and glyph pool |
| Motion | any `cubic-bezier(a,b,c,d)`; springs by `stiffness/damping/mass` or by `duration/bounce`; per-property duration and curve on one transition; keyframes with per-segment easing; stagger as a formula (`index*40ms`, `distance*0.3ms`); gesture-linked (`progress="$drag"`) and scroll-linked animation; interruptible with velocity carried; a global time scale for slow-motion review; `reduced-motion` variants per animation |
| Colour | OKLCH alongside hex and HSL, with relative adjustments (`@signal.lighten(0.1)`); Display P3 where the panel supports it; gradient dithering on by default; alpha everywhere; live contrast in the editor against the real composited background |
| Layout | fractional dp; sub-pixel positioning with `snap` per node; constraints relative to siblings; safe-area and cutout insets as values; continuous corners with the squircle exponent exposed; shadows with offset, blur, spread and colour as separate values, inner shadows; gradient borders; `aspect`, `min`, `max` on every box |
| Haptics and sound | composed haptic patterns (tick, click, thud, spin, rise, fall with intensity and delay) on Android 13+, single effects before; sound with volume and pitch |
| Seeing the truth | in the editor and the on-device developer panel: pixel ruler; reference-image overlay at adjustable opacity; frame-by-frame scrub with an animation inspector; a picker that reads the composited pixel; a frame-time graph per component; a pixel diff between two states or against a reference, which is the launcher's existing fidelity harness turned into a feature |
| Fidelity | the editor and the phone run the same interpreter on the same files; the validator renders both and fails a package whose two renders differ |

Most of this costs editor surface and documentation, not engine work: Compose and Skia already do it, the engine just has to not hide it.

## Sandbox and performance

The render thread never waits on a script, and a script never reaches Android. Those two sentences are the whole design; the rest is how.

**Workers.** Each package gets one Lua VM on a background thread (pure-JVM Lua, the same on phone and desktop). The VM has no `os`, `io`, `require`, `load`, `debug` or `package`; it has `props`, `state`, `on`, `act`, `env`, `kv`, `timer`, `http` and the standard string, table and math libraries. Memory is capped per VM (8 MB default); a VM that exceeds it is restarted and the component shows its fallback.

**State sync.** A script's writes to `state` are batched and handed to the UI thread once per frame as an immutable snapshot. The engine diffs the snapshot against the last one and recomposes only the nodes whose bindings changed. A component with no script has no VM and no sync; it is a pure function of props and tokens.

**Events in.** Source updates are coalesced per frame before delivery: a `battery` stream never delivers more than once per frame, `sensors` are rate-limited to 10 Hz by default, `notifications` deliver a diff. Handlers run in the order events arrived.

**Budgets, enforced.** A handler over 8 ms is logged in the developer panel; over 50 ms it is killed and the component enters its error state until the next event. Timers can't fire faster than 250 ms unless the component declares `uses { timer fast }`, which is a capability the user sees as "updates several times a second". `http` is 2 KB/s per component and 20 requests a minute, with a shared cache keyed by URL.

Frame budget. The UI thread targets the display rate (60, 90, 120 Hz). The accepted trade: versatility outranks a small frame cost, so the budget is a bound, not zero. On the reference device a surface pull must hold the display rate with at most 1 dropped frame per 100 at 120 Hz, and an idle surface must sit under 1 ms per frame; a component in the store must render under 2 ms at three sizes and stay under 16 MB steady-state. Anything that cannot meet the pull bound is moved out of Compose into an engine-owned layer, which is the fallback for every primitive. The engine measures per component and shows it in the developer panel; the store's validation step enforces the limits.

Glass tiers, as proven on devices (the earlier virtual-display mirror crashed the system and is a dead end):

1. **In-app**: RenderEffect + AGSL over the engine's own content. Refraction, dispersion, depth and frost, from the launcher's `MaterialPainter` and `FrostCache`.
2. **Live, over another app**: repeated `captureDisplay` with `setUid` through Shizuku, \~8 ms a frame on the S24, Android 14+. It returns that app's layers only, never ours; secure content ends the stream. Frames land in a fixed set of GPU buffers allocated once. Created when a `@glass` surface opens over an app, destroyed when it closes, never left running.
3. **Window blur**: Samsung `semSetBlurInfo` per view (radius = 4.7 × sigma), or `FLAG_BLUR_BEHIND` with a 1/1024 dim where the OEM supports cross-window blur. Real live blur with no capture cost; no refraction.
4. **Static**: the task snapshot as a `HardwareBuffer`, baked at quarter scale with the tint. The floor on weak GPUs and battery saver, applied without the component knowing. Below it, opaque tint.

Glass is a surface-level material, not a modifier: the live paths rely on RenderNodes and cannot sit under a Compose clip or alpha layer, so the `glass` primitive owns its layer outright. The privacy posture and the per-tier cost are disclosed in settings, and the user can cap the tier.

**Play policy.** Scripts are interpreted, never native, and the bridge is a fixed allowlist, so a package from the store is data plus interpreted script with mediated access, which is what Play's policy permits. The engine never downloads dex or native code. `intent.send` uses an allowlist of actions and never targets another app's components directly.

**Developer panel.** Long-press the status line in any surface while developer mode is on: per-component render time, VM memory, event rate, denied capabilities, log lines from `log()`. The same panel is a tab in the desktop editor, attached to a phone over ADB or to the editor's own preview.

## Open questions and build order

Open:

- [x] Lua VM: which pure-JVM implementation (LuaJ is old but proven; Luak and Rembulan are newer). Benchmark on the Redmi. **LuaJ 3.0.1** behind a one-adapter interface (D-069, `docs/BENCH_STEP0.md`; measured on the S24, the Redmi run still owed).
- [x] KDL parser: existing JVM libraries are thin; decide whether to write our own (small grammar, and we want precise error messages). **Our own**, spans on every node (D-069).
- [x] Compose Multiplatform for the desktop editor: confirm RenderEffect and AGSL equivalents on desktop so the glass preview matches. **Confirmed**: the launcher's shaders compile unchanged in Skiko and match the S24 (D-069; CPU raster, the GPU backend unchecked).

  The switcher's thread: the launcher's deck renders on gesture-nav's own `HandlerThread` with a spare `SurfaceControlViewHost` kept warm there, which is what makes the first frame of a swipe instant. A Compose `deck` either renders on that thread too or accepts a first-frame stall. Measure before choosing; budget a week. **Measured (D-069):** possible from Compose 1.11.4 (1.7.5 crashes while an accessibility service is on), no skipped vsyncs, the first composition warmed ahead (151 ms cold); which Compose the engine moves to is still open.
- [ ] Whether `widgets.host` (AppWidget) components can live inside a scripted component or only as leaf nodes.
- [ ] The reference device for performance validation (Redmi Note 10 Pro is the floor candidate).
- [ ] Does `http` allow POST at launch, or GET only.

Build order, each step usable on its own:

1. **Platform layer.** Lift the launcher's proven code into an engine module with no behaviour change: the shell service and AIDL, safety net, window plumbing, motion and transitions, token resolver, glass renderer, fonts, apps, widgets, notifications, controls, media. The capability registry wraps the AIDL; the callback watches become flows. In the same step, replace Shizuku with our own host: starter, ADB pairing and connect client, mDNS, binder handover, root start, boot restart (Decisions, "no Shizuku"). Exit: the existing launcher runs on the module with no Shizuku installed, and comes back by itself after a reboot on the S24 and the Redmi.
2. **Interpreter core.** KDL parser, props/uses/state validation with reasons, token binding, the first twelve primitives (`box`, `row`, `column`, `stack`, `text`, `icon`, `image`, `glass`, `list`, `grid`, `slider`, `toggle`), transitions bound to surface progress. Exit: a KDL status bar renders inside the existing shade window, pixel-matched against the hand-drawn one, inside the frame budget on the reference device. This is the step that decides whether the Compose split holds.
3. **Shade from KDL.** Summon config and placements, the three presentation presets, banners, the iOS notification and control centres, then the Pixel shade as a second theme. Exit: parity with the launcher's shade inventory, both shades mixable per panel.
4. **Home and drawer from KDL.** `pager`, `sheet`, `search`, `menu`, `widget`, free-placement `grid` with the edit-mode state machine extracted. Folders and the seed layout, never finished in the launcher, land here.
5. **Switcher.** The `deck` primitive on the nav thread, `SurfaceControlViewHost` kept; the thread question answered by measurement.
6. **Scripting and media.** The Lua worker, state sync, event delivery, budgets, the developer panel; the plain-value projection of notification items and action handles; then `web`, `video`, `map`, `lottie`.
7. **Themes and import.** Palette importers and the presets over the three-tier resolver.
8. **Packages and CLI.** Manifest, dependencies, `nal check`, install from directory and git URL, the data-APK wrapper.
9. **Desktop editor.** Compose Multiplatform, same interpreter, the import wizard, live preview on a connected phone over the launcher's existing test hooks. Remote components after this.
10. **Store**, once two complete themes exist to sell.

The order starts from the shade rather than home because the shade touches every subsystem (sources, surfaces, glass, transitions, tokens) and already works on the S24, so it is the acceptance test. The launcher audit that produced this order, with the per-file verdicts and the device-proven facts behind it, is its own document: *Not a Launcher — Launcher Audit*.

## Additions, 2026-11-03

Taken from a survey of Rainmeter, Übersicht, KWGT/KLWP, Nova, Tasker, QML, SwiftUI, Slint, Figma, Framer, Origami, lazy.nvim, VS Code's marketplace and Obsidian's community store. Everything those do well that the spec lacked, with the step each lands in.

| Addition | What it is | Step |
| --- | --- | --- |
| Expression bindings | Any attribute may hold `{expr}` over props, state, sources and tokens: `width="{battery.level * 2}dp"`, `opacity="{shade.progress > 0.5 ? 1 : 0}"`. Arithmetic, comparison, ternary, string concat, the formatter functions. Compiled once, evaluated on the engine side per change, no Lua round-trip. Most of KWGT's presets are nothing but these. | 2 |
| Fixtures | `fixtures/` per package: sample notifications, tasks, media, battery states, long and empty names, 40 notifications, no SIM. The editor renders against them; `nal check` and the store validator render every component against the standard set and the package's own. The worst-case data lives here. | 2 |
| Hot reload keeps state | On `nal dev` reload, `state`, scroll positions and open folds survive when the schema did not change. | 2 |
| Rules | A layout-level block: `when battery < 15 { theme "dim" }`, `when app "com.spotify.music" { fill "shade" "koji/music-shade" }`, `when time "22:00-07:00" { variant "night" }`. Conditions over existing sources; effects limited to theme, mode, variant, fill and props. No scripts, so it is safe and the editor can show it. Pro. | 3 |
| Hero transitions | `hero="app:com.x"` on a node in one surface and on a node in the next; the engine morphs one into the other across surfaces, whoever wrote each, on the keyframe tracks. | 5 |
| Token modes | A theme defines any number of modes (light, dark, high-contrast, amoled, compact, large-text); the resolver already blends between two, so it generalises. Rules and the system switch modes. | 7 |
| Snippets | A package kind that overrides tokens or a few props of another package without forking it. Shareable, stackable, tiny; the store's cheap second tier of content. | 8 |
| `nal.lock` and `nal doctor` | The lockfile pins dependency versions so a layout renders the same next year. `doctor` checks Shizuku, the accessibility service, disable flags and OEM quirks and says what is wrong in plain words; it is the safety net's knowledge, surfaced. | 8 |
| Whole-setup export and import | One `.nal` archive: layout, fills, overrides, theme, rules, each package's `kv`. Restore in one tap. Importers for Nova and Lawnchair home grids. | 8 |
| History with visual diff | Layouts are git repos; the editor shows commits as a timeline with before/after renders and one-click revert. | 9 |

Deliberately not taken: native plugins (Rainmeter) and arbitrary JS on the canvas (Framer).

## Decisions, 2026-11-03

**Licence and openness.** Open source, on four conditions that together make a paywall-stripping fork pointless rather than impossible: (1) the name and the mark are trademarks (filed when revenue allows; until then, dated evidence of first use is kept: site, repo, Play listing; the FSL non-compete clause and common-law rights cover the gap); (2) a paid package verifies the signing certificate of the host that mounts it, so forks lose the creator ecosystem; (3) Play is the distribution, forks are sideloads; (4) tier is enforced only in the engine (`env.caps`, mount refusal) from Play Billing's signed purchase state, never in UI code. Licences: Functional Source License (FSL-1.1-Apache-2.0) for the app and engine; MIT for the format spec, `nal` CLI, validator, fixtures, VS Code extension and the AI skill.

**Budget, 2026-11-03.** Until revenue: the Claude subscription, the Play developer account and the domain only. Trademarks, a lawyer's reading, a second test phone and a paid security review are deferred, each with a stand-in: evidence of first use; policies drafted from Google's requirements and standard templates and marked as not yet lawyer-read; the Android 17 Pixel emulator plus a volunteer tester from the community; the public sandbox test suite, static analysis and open-source review.

**Public alpha gate: after step 4.** Shades (iOS and Pixel), home, drawer and switcher as text packages; onboarding without adb; zero P0 bugs; the three rules held on the S24 for every shipped package. Labelled 0.x, format unstable, said plainly. Ships with the VS Code extension (KDL grammar, `nal check` as a language server, snippets, fixture preview) and the AI skill (spec, primitive reference, fixtures, a lint loop). The desktop editor and the store come after.

**Shizuku after reboot.** Solved, and not the way first assumed. Shizuku's privileged service grants the manager app `WRITE_SECURE_SETTINGS` once; the `thedjchi/Shizuku` fork (Apache-2.0, v13.7.0, reviewed 2026-11-03 against upstream) uses it at boot to turn wireless debugging on itself (`adb_wifi_enabled = 1`), waits for Wi-Fi and adbd, connects with the stored pairing key over mDNS, starts the service, then turns wireless debugging off again. It therefore works on any Android 11+ phone whatever the OEM does with the toggle; the delay is Wi-Fi plus adbd plus mDNS. Review findings: the privileged server differs from upstream only by Android 17 shims; the accessibility service is scoped to the TV settings app and never runs on a phone; the self-updater pulls from the author's GitHub releases with a SHA-256 check (Android enforces the signing key); stealth mode re-signs a renamed copy locally; the Tasker start/stop receivers are token-gated; TCP mode leaves classic ADB on port 5555 open on the LAN until reboot. Maintenance is paused by its author.

Decision (2026-11-06, owner): **no Shizuku.** The app hosts its own shell-uid process from step 1. What Shizuku provided is small and Apache-2.0: the starter (script plus `app_process`), the ADB pairing and connect client (Kotlin plus the native SPAKE2 piece), mDNS discovery, the binder handover. We take those with attribution, add a root start (`su`) for devices that have it, and drop `ShizukuLink` and Shizuku's user-service hosting rather than porting them. Our shell process exposes one AIDL to one caller; there is no grant surface for other apps. Boot restart is the fork's sequence inside our app: the shell process grants the app `WRITE_SECURE_SETTINGS` once, and on boot the app enables wireless debugging, waits for Wi-Fi and adbd, connects with the stored key over mDNS, starts the process, disables wireless debugging. Reasons: one install and one onboarding; the privacy statement is exact; every Android-version fix is ours to ship the day it is needed instead of waiting on an upstream (the fork is five months stale, upstream missed the 16 and 17 local-network permissions). Cost: the ADB protocol surface is ours forever (roughly 3–4k lines, two to three weeks in step 1), and Play may ask what the shell process does; the disclosure page answers. Either way, a one-tap re-pair flow where pairing was lost, a "Pro resumes in about N s" state on boot, and a clean paused state for every Pro surface.

**Play merchant.** Sri Lanka is supported for developer and merchant registration (Google's list, checked 2026-11-03). Closed.

**Legal, before any public build.** Privacy policy and Play Data Safety form; the accessibility-service declaration with a prominent in-app disclosure; the rule that captured frames of other apps never leave the GPU and never persist; zero telemetry by default and no accounts; a Pro EULA; creator terms and a DMCA takedown flow; a lawyer reads the set once before the Play listing. The disclosure page names every permission and what it is used for.

**Security, made mechanical.** A threat-model document; the Lua sandbox stripped of `load`, `loadstring`, `string.dump`, `os`, `io`, `debug`, `require` and the Java bridge; static analysis in `scripts/check.sh` (detekt, plus semgrep rules for the capability bridge, the service and the parser); a review checklist for any PR touching those three; `SECURITY.md` with a disclosure address; an outside review of the sandbox and the Shizuku service before the store opens.

**Battery is a budget.** Measured: an hour of normal use with Pro on versus off; idle overlay under 1 %/h; live glass costed per minute of shade-open time, on the S24 and the Redmi. Shown: the validator computes a cost class per package from the capabilities it uses (live glass, `background`, sensors, http polling) and the store and the install prompt display it.

### Exit tests added by these decisions

- Step 2: expression bindings and fixtures exist; a reload keeps state.
- Step 3: the accessibility-overlay route is confirmed against Play's current policy, or replaced by an application overlay plus the raw touch stream, before the shade ships; the battery numbers above are measured.
- Step 4 (alpha): a volunteer on a non-Samsung phone (or, failing that, the Android 17 Pixel emulator) has run a build; the legal set exists, drafted from templates and marked as not yet lawyer-read; first-use evidence for the name is dated and kept.
- Step 6: the stripped Lua sandbox passes a written escape test suite, published with the source; static analysis runs in `check.sh`.
- Step 8: `nal.lock`, `doctor`, export/import and snippets; paid packages verify the host certificate.
- Before the store opens: a security review of the sandbox and the service, paid if affordable by then, otherwise the public test suite plus a request for community review; trademarks filed once revenue allows.
