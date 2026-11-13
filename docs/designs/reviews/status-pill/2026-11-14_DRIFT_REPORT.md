## status-pill.md

*   **The banner's width is worked out in the launcher's points, not in dp.** The document says the banner is 380 dp, or
    the screen less 16 dp each side. `KdlBar.place()` passes `bar.width / Scale.unitPx(ctx)` to `bannerWidth`, and that
    is the screen's width in the launcher's own unit (402 pt across a phone in portrait), but the result is set as a `dp`
    value. The two units agree only when the phone is at least 402 dp wide at its default display size (the S24, 411 dp).
    On a narrower phone the banner comes out wider than the document says. The Redmi Note 10 Pro (about 392 dp) would
    get a 370 dp banner with about 11 dp each side, not 16. `KdlBarChoiceTest.bannerFitsNarrowScreens` tests the
    function with dp inputs (360, 411) that the app never passes it.
*   **In detailed mode the pill does not switch to a notification state.** Goal 2 says one state at a time, a
    notification outranking charging and media. In the code every pill condition is
    `notifications.arriving && @mode != 'detailed'`, so while a detailed banner shows, the pill keeps its lower state: it
    stays at the idle, charging or media width and keeps showing the time, the level or the song. `IslandKdlTest` holds
    this on purpose (`detailedModeGrowsTheBannerBelowAndLeavesThePillAlone`). Neither Goal 2 nor CUJ 5 says it.
*   **Remove animations still draws the union, not plain shapes.** CUJ 9 says the banner and the pill "draw as plain
    shapes, nothing stretched". With motion reduced, `BlobPainter.drawn` skips only the elastic's deformation and its
    neck. On Android 13 and later the shapes still go through the smooth-union shader (`drawJoined`), so they would still
    swell and join wherever they come within `smooth`. Only below Android 13 are they drawn plainly.
*   **Not every banner piece slides, and not every piece arrives one after another.** CUJ 5 says the letter, the sender,
    the message and "now" each slide in out of a blur, one after another. In `island.kdl` "now" (`Banner time`) enters
    with `fade blur` only, without `slide`, and it shares its delay with the message line (348 ms for a first message,
    158 ms for a replacement). So the last two pieces arrive together.
*   **A message on the banner at the lock is redacted in place.** The document says only that a message already on the
    banner at the lock never plays its exit with its text. In the code, `ArrivalTracker.update` also keeps the shown
    arrival (with its timer) and rewrites it as soon as the lock is read: the sender becomes the app's name and the text
    goes blank. Because the item's key (`seq`) does not change, the words change in one frame, with no exit and no enter.
    This also happens to a notification its app marks secret: one that was already showing stays on the banner with its
    app's name until its 4 s run out, although Goal 5 says a secret one shows nothing while locked. The document mentions
    neither case.
*   **The island's alert rule leaves out the ringing notifications that the document says stay with the shade.** The
    Coexistence section says calls and alarms ring as the shade's banners and are not on the island. `KdlArrivals.alerts`
    excludes ongoing notifications, but it does not exclude the ones the shade treats as ringing (`Notifs.Item.urgent`: a
    full-screen intent, or a call with an answer action). A ringing notification that is not marked ongoing can therefore
    arrive on the island while the shade also shows it as a banner (`Shade.kt` lets only non-urgent ones give way).
*   **The alert rule in the code has conditions the document leaves out.** CUJ 4 defines "alerts" as importance with sound
    or vibration, passing Do Not Disturb, and not ongoing, a group summary or media. `KdlArrivals.alerts` also requires a
    title or a text, and it ignores the launcher's own notifications except those on the `TEST_NOTIFY` channel. The
    document also does not say that when a notification has no title, the minimal pill shows its text where the sender
    would be (`name = title ?: text`) and the banner shows no message line.
*   **The media cover is conditional.** CUJ 2 says the cover, the title and the bars arrive in that order. The cover shows
    only when `media.has-art`; without art the slot stays empty and only the title and the bars arrive.
*   **The diagram puts two engine classes on the app side.** The Technical Plan's diagram draws `SessionMedia` and
    `DeviceDisplayInfo` in the app (`:app`) column. Both live in `:engine`
    (`engine/src/main/java/dev/launcher/engine/source/SessionMedia.kt`, `DeviceDisplayInfo.kt`). The app only builds
    them in `KdlBar.make`.
*   **The component's own header comment contradicts the document.** At line 16, `island.kdl` still says that the
    detailed `mode` "comes with E2.14c-2 and shows as minimal now". The component's own detailed banner (from line 99
    onwards) and the document both say detailed mode is built and shows on Android 13 and later.
*   **The emulator proof runs its stages in a different order.** The Verification Plan lists `rest`, `rolls`, `media`,
    `media-details`, `arrivals`, `reduced`, `detailed`. `IslandProof.run` runs `rest`, `rolls`, `reduced`, `media`,
    `media-details`, `arrivals`, `detailed`. The `reduced` stage checks only that the time and level land at once with
    motion reduced. Nothing there checks the blob or the banner under Remove animations; only the unit test
    `reducedMotionRedrawsTheElasticBlobAtRest` covers that.
