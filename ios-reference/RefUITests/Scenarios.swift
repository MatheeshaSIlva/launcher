import XCTest

/// Drives iOS's own system UI (SpringBoard: Control Center, Notification Center, banners, home) with exact touches, one
/// scenario per test. The capture script records the screen around each test; the tests attach screenshots and the
/// accessibility tree (every element's frame) at the interesting moments, and print `REFMARK <unix time> <what>` lines
/// so the video can be lined up with the gestures. Key gestures run three times (the CI machine drops frames now and
/// then; three runs give steadier fits). Nothing here asserts: a missing element is logged and skipped.
///
/// Lessons from the first pass: synthesized touches start ~1 s after the call (never time a gesture from its mark, take
/// it from the video); the home button returns to the page the last app was opened from (press it twice for page one);
/// banners must be waited for by their element (`NotificationShortLookView`), not by a delay.
final class Scenarios: XCTestCase {
    private let sb = XCUIApplication(bundleIdentifier: "com.apple.springboard")
    private let host = XCUIApplication(bundleIdentifier: "dev.launcher.ref.host")

    private var w: CGFloat { sb.frame.width }
    private var h: CGFloat { sb.frame.height }

    /// The slow-motion pass (Simulator's Slow Animations on) stretches every pause by this factor.
    private let slow = Double(ProcessInfo.processInfo.environment["REF_SLOW"] ?? "1") ?? 1

    override func setUp() {
        continueAfterFailure = true
        home()
        mark("ready \(Int(w))x\(Int(h)) slow \(slow)")
    }

    // MARK: - Helpers

    private func mark(_ s: String) {
        print(String(format: "REFMARK %.3f %@", Date().timeIntervalSince1970, s))
    }

    /// Home, page one, nothing open (twice: the first press may only leave an app or close a panel).
    private func home() {
        XCUIDevice.shared.press(.home)
        wait(1.2)
        XCUIDevice.shared.press(.home)
        wait(1.5)
        // Spotlight's search (and its keyboard) outlived the home button in pass 4: swipe it away.
        if sb.keyboards.firstMatch.exists {
            mark("keyboard still up: swiping it away")
            pt(w / 2, h - 4).press(forDuration: 0.05, thenDragTo: pt(w / 2, h * 0.45), withVelocity: XCUIGestureVelocity(rawValue: 1500), thenHoldForDuration: 0)
            wait(1.5)
            XCUIDevice.shared.press(.home)
            wait(1.5)
        }
    }

    private func pt(_ x: CGFloat, _ y: CGFloat) -> XCUICoordinate {
        sb.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: x, dy: y))
    }

    /// A finger down at `a`, held `press` s, moved to `b` at `velocity` pt/s, held `hold` s there, lifted.
    private func drag(_ name: String, _ a: CGPoint, _ b: CGPoint, velocity: CGFloat = 1500, press: TimeInterval = 0.05, hold: TimeInterval = 0) {
        mark("drag \(name) begin \(Int(a.x)),\(Int(a.y)) -> \(Int(b.x)),\(Int(b.y)) v\(Int(velocity)) hold \(hold)")
        pt(a.x, a.y).press(forDuration: press, thenDragTo: pt(b.x, b.y), withVelocity: XCUIGestureVelocity(rawValue: velocity), thenHoldForDuration: hold)
        mark("drag \(name) lifted")
    }

    private func tap(_ name: String, _ x: CGFloat, _ y: CGFloat) {
        mark("tap \(name) \(Int(x)),\(Int(y))")
        pt(x, y).tap()
    }

    private func longPress(_ name: String, _ x: CGFloat, _ y: CGFloat, _ seconds: TimeInterval = 1.2) {
        mark("long \(name) \(Int(x)),\(Int(y)) \(seconds)s")
        pt(x, y).press(forDuration: seconds)
        mark("long \(name) lifted")
    }

    private func wait(_ s: Double) { usleep(useconds_t(s * slow * 1_000_000)) }

    private func shot(_ name: String) {
        let a = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        a.name = name
        a.lifetime = .keepAlways
        add(a)
        mark("shot \(name)")
    }

    /// The system UI's accessibility tree (types, labels, identifiers and frames in points).
    private func tree(_ name: String) {
        let a = XCTAttachment(string: sb.debugDescription)
        a.name = "\(name).txt"
        a.lifetime = .keepAlways
        add(a)
        mark("tree \(name)")
    }

    private func allowNotifications(_ timeout: Double = 4) {
        let allow = sb.alerts.buttons["Allow"]
        if allow.waitForExistence(timeout: timeout) { mark("allow notifications"); allow.tap() }
    }

    /// Grants the host app notifications before anything posts (pass 8 lost every notification: the prompt came after
    /// the 4 s wait). Waits for the prompt as long as it takes, then for the host to say "authorized".
    func test01b_grantNotifications() {
        host.launchArguments = []
        host.launch()
        allowNotifications(30)
        let status = host.staticTexts["status"]
        let t0 = Date()
        while Date().timeIntervalSince(t0) < 15 && status.label != "authorized" { usleep(500_000) }
        mark("notifications: \(status.exists ? status.label : "no status")")
        home()
    }

    /// The host app schedules `set` (see RefHostApp) `delay` s from now; we go home (page one) before it arrives.
    private func post(_ set: String, in delay: Double) {
        host.launchArguments = ["-post", set, String(delay)]
        host.launch()
        allowNotifications()
        mark("posted \(set) in \(delay)s")
        home()
    }

    private var banner: XCUIElement {
        sb.descendants(matching: .any).matching(identifier: "NotificationShortLookView").firstMatch
    }

    /// Waits for a banner; logs and returns its frame.
    @discardableResult
    private func waitBanner(_ timeout: Double = 30) -> CGRect? {
        guard banner.waitForExistence(timeout: timeout * slow) else { mark("no banner"); return nil }
        let f = banner.frame
        mark("banner at \(f) '\(banner.label)'")
        return f
    }

    // Control Center is pulled from the right of the Dynamic Island, Notification Center from the left (iOS 27).
    private func openCC(_ name: String = "cc-open", velocity: CGFloat = 1500) {
        drag(name, CGPoint(x: w - 36, y: 2), CGPoint(x: w - 36, y: h * 0.55), velocity: velocity)
    }

    private func openNC(_ name: String = "nc-open", velocity: CGFloat = 1500) {
        drag(name, CGPoint(x: 70, y: 2), CGPoint(x: 70, y: h * 0.62), velocity: velocity)
    }

    /// The home indicator's swipe up closes whichever panel is open.
    private func closeFromBottom(_ name: String, velocity: CGFloat = 1500) {
        drag(name, CGPoint(x: w / 2, y: h - 4), CGPoint(x: w / 2, y: h * 0.45), velocity: velocity)
    }

    // MARK: - Setup

    /// The Simulator's Control Center starts empty: add the controls the gallery offers, so opening has modules to move.
    func test00_setupControls() {
        openCC()
        wait(2)
        let names = ["Scan Code", "Sleep", "Chill", "Productivity", "Wellbeing", "New Reminder"]
        for n in names {
            longPress("cc-empty", w / 2, h * 0.93, 1.3)
            wait(1.5)
            let add = sb.buttons["Add a Control"]
            guard add.waitForExistence(timeout: 3) else { mark("no Add a Control"); break }
            add.tap()
            wait(2)
            let c = sb.buttons[n].firstMatch
            if c.waitForExistence(timeout: 3) {
                mark("add control \(n) at \(c.frame)")
                c.tap()
            } else {
                mark("control \(n) not offered")
                drag("gallery-down", CGPoint(x: w / 2, y: h * 0.12), CGPoint(x: w / 2, y: h * 0.9), velocity: 1500)
            }
            wait(2)
            tap("cc-edit-done", w / 2, h * 0.96)
            wait(1.5)
        }
        shot("cc-populated")
        tree("cc-populated")
        closeFromBottom("cc-close")
        wait(1.5)
    }

    // MARK: - Home and Control Center

    func test01_home() {
        shot("home")
        tree("home")
    }

    func test02_ccOpenClose() {
        for i in 1...5 {
            openCC("cc-open-\(i)")
            wait(2)
            if i == 1 { shot("cc-open"); tree("cc-open") }
            closeFromBottom("cc-close-\(i)")
            wait(2)
        }
    }

    func test03_ccPullHold() {
        // The panel under a resting finger at a quarter, then released slowly: does it open or fall back?
        drag("cc-pull-hold", CGPoint(x: w - 36, y: 2), CGPoint(x: w - 36, y: h * 0.25), velocity: 350, hold: 1.5)
        wait(2)
        shot("cc-after-slow-release")
        closeFromBottom("cc-close")
        wait(1.5)
        // Further, held, then a fast flick up from where it rests.
        drag("cc-pull-deep", CGPoint(x: w - 36, y: 2), CGPoint(x: w - 36, y: h * 0.8), velocity: 600, hold: 1.0)
        wait(2)
        shot("cc-after-deep-pull")
        drag("cc-flick-up", CGPoint(x: w / 2, y: h * 0.8), CGPoint(x: w / 2, y: h * 0.4), velocity: 3000)
        wait(2)
    }

    func test04_ccTapClose() {
        for i in 1...2 {
            openCC("cc-open-\(i)")
            wait(2)
            tap("cc-empty-\(i)", w / 2, h * 0.95)
            wait(2)
        }
    }

    func test05_ccEdit() {
        openCC()
        wait(2)
        longPress("cc-empty", w / 2, h * 0.93, 1.3)
        wait(2)
        shot("cc-edit")
        tree("cc-edit")
        // Move the first control two cells to the right: the others make room.
        let first = sb.buttons.matching(NSPredicate(format: "label IN %@", ["Scan Code", "Sleep", "Chill"])).firstMatch
        if first.exists {
            let f = first.frame
            mark("move control from \(f)")
            drag("cc-move", CGPoint(x: f.midX, y: f.midY), CGPoint(x: f.midX + 170, y: f.midY), velocity: 300, press: 0.8, hold: 1.0)
            wait(2)
            shot("cc-moved")
        }
        tap("cc-edit-done", w / 2, h * 0.96)
        wait(2)
        closeFromBottom("cc-close")
        wait(1.5)
    }

    func test06_ccExpandModule() {
        openCC()
        wait(2)
        let c = sb.buttons.matching(NSPredicate(format: "label IN %@", ["Sleep", "Chill", "Scan Code"])).firstMatch
        if c.exists {
            let f = c.frame
            longPress("cc-module", f.midX, f.midY, 1.0)
            wait(2)
            shot("cc-expanded")
            tree("cc-expanded")
            tap("cc-outside", w / 2, h * 0.95)
            wait(2)
        } else {
            mark("no control to press")
        }
        closeFromBottom("cc-close")
        wait(1.5)
    }

    func test07_ccToggle() {
        // A control's press and toggle (Ambient Music: Sleep), twice: on, then off.
        openCC()
        wait(2)
        let c = sb.buttons["Sleep"].firstMatch
        if c.exists {
            let f = c.frame
            tap("cc-toggle-on", f.midX, f.midY)
            wait(2)
            shot("cc-toggled")
            tap("cc-toggle-off", f.midX, f.midY)
            wait(2)
        }
        closeFromBottom("cc-close")
        wait(1.5)
    }

    // MARK: - Notification Center

    func test08_ncOpenClose() {
        for i in 1...5 {
            openNC("nc-open-\(i)")
            wait(2)
            if i == 1 { shot("nc-open"); tree("nc-open") }
            closeFromBottom("nc-close-\(i)")
            wait(2)
        }
    }

    func test09_ncPullHold() {
        drag("nc-pull-hold", CGPoint(x: 70, y: 2), CGPoint(x: 70, y: h * 0.35), velocity: 350, hold: 1.5)
        wait(2)
        shot("nc-after-slow-release")
        closeFromBottom("nc-close")
        wait(1.5)
        // Opened, then pushed back up by a slow drag from the middle (does it follow, how far before it goes?).
        openNC()
        wait(2)
        drag("nc-push-up", CGPoint(x: w / 2, y: h * 0.7), CGPoint(x: w / 2, y: h * 0.45), velocity: 300, hold: 1.0)
        wait(2)
        shot("nc-after-push")
        closeFromBottom("nc-close-2")
        wait(1.5)
    }

    func test10_ncStacks() {
        post("stack", in: 20)
        // Let every banner pass (sending each shown one away), then look at the list.
        waitBanner(30)
        var quiet = 0.0
        let t0 = Date()
        while quiet < 4 && Date().timeIntervalSince(t0) < 60 {
            if banner.exists {
                quiet = 0
                let f = banner.frame
                drag("banner-away", CGPoint(x: f.midX, y: f.midY), CGPoint(x: f.midX, y: 5), velocity: 1500)
            } else {
                quiet += 0.5
            }
            wait(0.5)
        }
        mark("banners over")
        openNC()
        wait(2.5)
        shot("nc-stacks")
        tree("nc-stacks")
        let cells = sb.descendants(matching: .any).matching(identifier: "NotificationShortLookView").allElementsBoundByIndex
        mark("platters \(cells.count): " + cells.prefix(8).map { "\($0.label.prefix(50)) \($0.frame)" }.joined(separator: "; "))
        if let first = cells.first {
            let f = first.frame
            tap("nc-stack", f.midX, f.midY)
            wait(2)
            shot("nc-stack-expanded")
            tree("nc-stack-expanded")
            let after = sb.descendants(matching: .any).matching(identifier: "NotificationShortLookView").allElementsBoundByIndex
            if let g = after.dropFirst().first?.frame ?? after.first?.frame {
                drag("nc-swipe-left", CGPoint(x: g.maxX - 30, y: g.midY), CGPoint(x: g.minX + 150, y: g.midY), velocity: 500)
                wait(1.5)
                shot("nc-swiped")
                tree("nc-swiped")
                tap("nc-elsewhere", w / 2, h * 0.85)
                wait(1.5)
            }
        }
        closeFromBottom("nc-close")
        wait(1.5)
    }

    // MARK: - Banners (always found by their element, then acted on)

    func test11_bannerTimeout() {
        for i in 1...3 {
            post("one", in: 20)
            if waitBanner() != nil {
                if i == 1 { shot("banner"); tree("banner") }
                // Until it has gone by itself.
                let t0 = Date()
                while banner.exists && Date().timeIntervalSince(t0) < 15 * slow { usleep(250_000) }
                mark("banner gone after \(String(format: "%.2f", Date().timeIntervalSince(t0))) s")
            }
            wait(2)
        }
    }

    func test12_bannerSwipeUp() {
        for i in 1...4 {
            post("one", in: 20)
            if let f = waitBanner() {
                wait(1.0)
                drag("banner-up-\(i)", CGPoint(x: f.midX, y: f.midY), CGPoint(x: f.midX, y: 5), velocity: i <= 2 ? 800 : 2000)
            }
            wait(3)
        }
    }

    func test13_bannerReplace() {
        post("two", in: 20)
        waitBanner()
        wait(10)
    }

    func test14_bannerPullDown() {
        post("one", in: 20)
        if let f = waitBanner() {
            wait(1.0)
            drag("banner-down", CGPoint(x: f.midX, y: f.midY), CGPoint(x: f.midX, y: f.midY + 250), velocity: 500, hold: 0.8)
            wait(2)
            shot("banner-pulled")
            tree("banner-pulled")
            tap("outside", w / 2, h * 0.9)
            wait(2)
        }
    }

    func test15_bannerLong() {
        post("long", in: 20)
        if waitBanner() != nil {
            shot("banner-long")
            tree("banner-long")
        }
        wait(8)
    }

    // MARK: - Apps (reference for open / close)

    func test16_appOpenClose() {
        let settings = sb.icons["Settings"]
        guard settings.waitForExistence(timeout: 3) else { mark("no Settings icon"); return }
        mark("Settings icon at \(settings.frame)")
        for i in 1...5 {
            mark("open settings \(i)")
            settings.tap()
            wait(2.5)
            if i == 1 { shot("settings") }
            closeFromBottom("app-home-\(i)", velocity: i >= 4 ? 3500 : 2000)
            wait(2.5)
        }
        // A slow, held swipe up: the app as a card under the finger, then let go.
        settings.tap()
        wait(2.5)
        drag("app-card-hold", CGPoint(x: w / 2, y: h - 4), CGPoint(x: w / 2, y: h * 0.75), velocity: 250, hold: 0.4)
        wait(2.5)
        shot("after-card-hold")
    }

    // MARK: - Home screen

    func test17_pages() {
        tree("home-pages")
        for i in 1...5 {
            drag("page-left-\(i)", CGPoint(x: w * 0.85, y: h * 0.5), CGPoint(x: w * 0.25, y: h * 0.5), velocity: 1500)
            wait(1.5)
            drag("page-right-\(i)", CGPoint(x: w * 0.15, y: h * 0.5), CGPoint(x: w * 0.75, y: h * 0.5), velocity: 1500)
            wait(1.5)
        }
        // A slow half drag, released: does it go or come back?
        drag("page-half", CGPoint(x: w * 0.8, y: h * 0.5), CGPoint(x: w * 0.45, y: h * 0.5), velocity: 250, hold: 0.5)
        wait(1.5)
        shot("page-after-half")
    }

    func test18_appLibrary() {
        for i in 0..<4 {
            drag("page-\(i)", CGPoint(x: w * 0.85, y: h * 0.5), CGPoint(x: w * 0.2, y: h * 0.5), velocity: 2000)
            wait(1.2)
        }
        shot("library")
        tree("library")
        // A category's small icon group opens the category folder.
        let folder = sb.icons.matching(NSPredicate(format: "label ENDSWITH 'folder'")).allElementsBoundByIndex
            .first { $0.frame.width > 0 && $0.frame.minX >= 0 && $0.frame.maxX <= w }
        if let folder = folder {
            let f = folder.frame
            mark("library folder \(folder.label) at \(f)")
            for i in 1...2 {
                tap("library-folder-\(i)", f.midX, f.midY)
                wait(2)
                if i == 1 { shot("library-folder"); tree("library-folder") }
                tap("library-folder-out-\(i)", w / 2, h * 0.94)
                wait(2)
            }
        }
        // Pull down: the A-Z list.
        drag("library-list", CGPoint(x: w / 2, y: h * 0.45), CGPoint(x: w / 2, y: h * 0.75), velocity: 800)
        wait(2)
        shot("library-list")
        tree("library-list")
        home()
    }

    func test19_spotlight() {
        for i in 1...2 {
            drag("spotlight-\(i)", CGPoint(x: w / 2, y: h * 0.4), CGPoint(x: w / 2, y: h * 0.62), velocity: 800)
            wait(2)
            if i == 1 { shot("spotlight"); tree("spotlight") }
            home()
        }
    }

    func test20_iconMenu() {
        let settings = sb.icons["Settings"]
        guard settings.waitForExistence(timeout: 3) else { mark("no Settings icon"); return }
        let f = settings.frame
        for i in 1...4 {
            longPress("icon-menu-\(i)", f.midX, f.midY, 0.9)
            wait(2)
            if i == 1 { shot("icon-menu"); tree("icon-menu") }
            tap("icon-menu-out-\(i)", w / 2, 40)
            wait(2)
        }
    }

    func test21_editMode() {
        longPress("home-empty", w / 2, h * 0.66, 1.6)
        wait(2)
        shot("edit")
        tree("edit")
        let a = sb.icons["Photos"].frame, b = sb.icons["News"].frame
        mark("Photos \(a) News \(b)")
        if a.width > 0 && b.width > 0 {
            drag("icon-move", CGPoint(x: a.midX, y: a.midY), CGPoint(x: b.midX + 20, y: b.midY), velocity: 300, press: 0.6, hold: 1.5)
            wait(2)
            shot("edit-moved")
            let c = sb.icons["Photos"].frame
            drag("icon-back", CGPoint(x: c.midX, y: c.midY), CGPoint(x: a.midX - 20, y: a.midY), velocity: 300, press: 0.6, hold: 1.5)
            wait(2)
        }
        let done = sb.buttons["Done"]
        if done.exists { mark("tap Done"); done.tap() } else { home() }
        wait(2)
        shot("edit-done")
    }

    func test22_appSwitcher() {
        let settings = sb.icons["Settings"]
        guard settings.waitForExistence(timeout: 3) else { mark("no Settings icon"); return }
        settings.tap()
        wait(2.5)
        drag("switcher", CGPoint(x: w / 2, y: h - 4), CGPoint(x: w / 2, y: h * 0.55), velocity: 500, hold: 1.0)
        wait(2)
        shot("switcher")
        tree("switcher")
        drag("switcher-scroll", CGPoint(x: w * 0.3, y: h * 0.5), CGPoint(x: w * 0.9, y: h * 0.5), velocity: 1200)
        wait(2)
        drag("switcher-flick", CGPoint(x: w / 2, y: h * 0.5), CGPoint(x: w / 2, y: h * 0.1), velocity: 3000)
        wait(2)
        shot("switcher-flicked")
        home()
    }

    func test23_folder() {
        // Home page two holds the Simulator's own "Utilities" folder: open and close it a few times.
        drag("page-2", CGPoint(x: w * 0.85, y: h * 0.5), CGPoint(x: w * 0.25, y: h * 0.5), velocity: 1500)
        wait(1.5)
        let folder = sb.icons.matching(NSPredicate(format: "label CONTAINS[c] 'folder'")).allElementsBoundByIndex
            .first { $0.frame.width > 0 && $0.frame.minX >= 0 && $0.frame.maxX <= w }
        guard let f = folder?.frame else { mark("no folder on screen"); tree("no-folder"); return }
        mark("folder \(folder!.label) at \(f)")
        for i in 1...5 {
            tap("folder-open-\(i)", f.midX, f.midY)
            wait(2)
            if i == 1 { shot("folder-open"); tree("folder-open") }
            tap("folder-close-\(i)", w / 2, h * 0.9)
            wait(2)
        }
        // And a folder made by dropping one icon on another (page one, edit mode, a fast move then a hold).
        home()
        longPress("home-empty", w / 2, h * 0.66, 1.6)
        wait(2)
        let a = sb.icons["Maps"].frame, b = sb.icons["News"].frame
        mark("Maps \(a) News \(b)")
        if a.width > 0 && b.width > 0 {
            drag("icon-onto", CGPoint(x: a.midX, y: a.midY), CGPoint(x: b.midX, y: b.midY), velocity: 1500, press: 0.6, hold: 1.8)
            wait(2.5)
            shot("folder-made")
            tree("folder-made")
        }
        let done = sb.buttons["Done"]
        if done.exists { done.tap() } else { home() }
        wait(2)
    }

    // MARK: - Releases from a standstill (the finger rests before it lets go: the spring starts at speed 0)

    func test30_ccHoldRelease() {
        for i in 1...5 {
            drag("cc-open-hold-\(i)", CGPoint(x: w - 36, y: 2), CGPoint(x: w - 36, y: h * 0.30), velocity: 800, hold: 0.7)
            wait(2)
            drag("cc-close-hold-\(i)", CGPoint(x: w / 2, y: h - 4), CGPoint(x: w / 2, y: h * 0.75), velocity: 800, hold: 0.7)
            wait(2)
            home()
        }
    }

    func test31_ncHoldRelease() {
        for i in 1...5 {
            drag("nc-open-hold-\(i)", CGPoint(x: 70, y: 2), CGPoint(x: 70, y: h * 0.55), velocity: 800, hold: 0.7)
            wait(2)
            drag("nc-close-hold-\(i)", CGPoint(x: w / 2, y: h - 4), CGPoint(x: w / 2, y: h * 0.45), velocity: 800, hold: 0.7)
            wait(2)
            home()
        }
    }

    func test32_bannerHoldRelease() {
        // A banner pulled down a little, held, let go: it springs back from a standstill.
        for i in 1...3 {
            post("one", in: 20)
            if let f = waitBanner() {
                wait(0.8)
                drag("banner-pull-hold-\(i)", CGPoint(x: f.midX, y: f.midY), CGPoint(x: f.midX, y: f.midY + 40), velocity: 300, hold: 0.7)
                wait(2)
                drag("banner-up-hold-\(i)", CGPoint(x: f.midX, y: f.midY), CGPoint(x: f.midX, y: f.midY - 30), velocity: 300, hold: 0.7)
            }
            wait(3)
        }
    }

    func test33_pageHoldRelease() {
        // Half a page, held still, let go: the snap from a standstill (both directions).
        for i in 1...4 {
            drag("page-hold-left-\(i)", CGPoint(x: w * 0.8, y: h * 0.5), CGPoint(x: w * 0.3, y: h * 0.5), velocity: 600, hold: 0.7)
            wait(1.5)
            drag("page-hold-right-\(i)", CGPoint(x: w * 0.2, y: h * 0.5), CGPoint(x: w * 0.7, y: h * 0.5), velocity: 600, hold: 0.7)
            wait(1.5)
        }
    }

    func test34_iconMenuClean() {
        let settings = sb.icons["Settings"]
        guard settings.waitForExistence(timeout: 3) else { mark("no Settings icon"); return }
        let f = settings.frame
        for i in 1...4 {
            longPress("icon-menu-\(i)", f.midX, f.midY, 0.9)
            wait(2)
            if i == 1 { shot("icon-menu"); tree("icon-menu") }
            tap("icon-menu-out-\(i)", w / 2, 40)
            wait(2)
        }
    }

    // MARK: - Notification Center in depth (pass 7)

    /// Waits out every banner of a posted set (sending each shown one away).
    private func letBannersPass() {
        waitBanner(30)
        var quiet = 0.0
        let t0 = Date()
        while quiet < 4 && Date().timeIntervalSince(t0) < 90 {
            if banner.exists {
                quiet = 0
                let f = banner.frame
                drag("banner-away", CGPoint(x: f.midX, y: f.midY), CGPoint(x: f.midX, y: 5), velocity: 1500)
            } else {
                quiet += 0.5
            }
            wait(0.5)
        }
        mark("banners over")
    }

    private var platters: [XCUIElement] {
        sb.descendants(matching: .any).matching(identifier: "NotificationShortLookView").allElementsBoundByIndex
    }

    func test40_ncLongList() {
        // A list longer than the screen: what happens at the top (under the clock) and at the bottom (over the buttons)
        // while it scrolls, step by step with the finger resting (no fling).
        post("many", in: 20)
        letBannersPass()
        openNC()
        wait(2.5)
        shot("nc-long-0")
        tree("nc-long-0")
        for i in 1...6 {
            drag("nc-scroll-up-\(i)", CGPoint(x: w / 2, y: h * 0.78), CGPoint(x: w / 2, y: h * 0.78 - 110), velocity: 250, hold: 0.8)
            wait(1.2)
            shot("nc-long-\(i)")
            if i % 2 == 0 { tree("nc-long-\(i)") }
        }
        // Held mid-scroll (finger down) to see the list while it is being dragged.
        drag("nc-scroll-held", CGPoint(x: w / 2, y: h * 0.7), CGPoint(x: w / 2, y: h * 0.7 - 80), velocity: 200, hold: 2.5)
        // Back down to the end: fling, then the overscroll at the bottom.
        drag("nc-fling-down", CGPoint(x: w / 2, y: h * 0.3), CGPoint(x: w / 2, y: h * 0.9), velocity: 2500)
        wait(2)
        shot("nc-long-back")
        drag("nc-pull-past-end", CGPoint(x: w / 2, y: h * 0.4), CGPoint(x: w / 2, y: h * 0.75), velocity: 300, hold: 1.0)
        wait(2)
        shot("nc-long-after-pull")
        tree("nc-long-after-pull")
        closeFromBottom("nc-close")
        wait(1.5)
    }

    func test41_ncLongLook() {
        // Press and hold a notification in Notification Center: the expanded notification and its menu, and what happens to
        // the list behind it. Then a tap outside.
        openNC()
        wait(2.5)
        guard let p = platters.last else { mark("no platter"); closeFromBottom("nc-close"); return }
        let f = p.frame
        for i in 1...2 {
            longPress("nc-hold-\(i)", f.midX, f.midY, 1.2)
            wait(2)
            if i == 1 { shot("nc-longlook"); tree("nc-longlook") }
            tap("nc-longlook-out-\(i)", w / 2, 60)
            wait(2)
        }
        closeFromBottom("nc-close")
        wait(1.5)
    }

    func test42_ncTapOpen() {
        // A tap on a notification in Notification Center opens its app: how the app comes out of the platter and what the
        // sheet does meanwhile. Three times (the host app is closed between them).
        for i in 1...3 {
            openNC("nc-open-\(i)")
            wait(2.5)
            guard let p = platters.last else { mark("no platter"); closeFromBottom("nc-close"); return }
            let f = p.frame
            mark("tap platter at \(f)")
            tap("nc-tap-\(i)", f.midX, f.midY)
            wait(2.5)
            if i == 1 { shot("nc-opened-app") }
            home()
            wait(1)
        }
    }

    func test43_ccGallery() {
        // Every control iOS offers: the "Add a Control" gallery, page by page (labels and frames in the trees).
        openCC()
        wait(2)
        longPress("cc-empty", w / 2, h * 0.93, 1.3)
        wait(1.5)
        let add = sb.buttons["Add a Control"]
        guard add.waitForExistence(timeout: 3) else { mark("no Add a Control"); closeFromBottom("cc-close"); return }
        add.tap()
        wait(2.5)
        shot("gallery-0")
        tree("gallery-0")
        for i in 1...10 {
            drag("gallery-up-\(i)", CGPoint(x: w / 2, y: h * 0.82), CGPoint(x: w / 2, y: h * 0.32), velocity: 700, hold: 0.6)
            wait(1.2)
            shot("gallery-\(i)")
            tree("gallery-\(i)")
        }
        home()
    }

    func test44_ccModules() {
        // The controls added in setup, each pressed and held (expanded modules) and tapped, after a fresh open.
        openCC()
        wait(2)
        shot("cc-modules")
        tree("cc-modules")
        let buttons = sb.buttons.allElementsBoundByIndex.filter { $0.frame.minY > 120 && $0.frame.maxY < h * 0.8 && $0.frame.width > 50 }
        mark("cc buttons: " + buttons.prefix(12).map { "\($0.label) \($0.frame)" }.joined(separator: "; "))
        for (n, b) in buttons.prefix(3).enumerated() {
            let f = b.frame
            longPress("cc-hold-\(n)", f.midX, f.midY, 1.0)
            wait(2)
            shot("cc-held-\(n)")
            tree("cc-held-\(n)")
            tap("cc-out-\(n)", w / 2, h * 0.95)
            wait(1.5)
        }
        closeFromBottom("cc-close")
        wait(1.5)
    }

    // MARK: - Notification Center: collapsed, expanded, scrolled, held, opened (pass 8)

    /** Platters on screen now (frames inside the visible area), top to bottom. */
    private func visiblePlatters() -> [XCUIElement] {
        platters.filter { $0.frame.minY > 90 && $0.frame.maxY < h - 110 && $0.frame.height > 30 }.sorted { $0.frame.minY < $1.frame.minY }
    }

    func test45_ncList() {
        post("many", in: 20)
        letBannersPass()
        openNC()
        wait(2.5)
        shot("nc-collapsed")
        tree("nc-collapsed")
        // The collapsed stack at the bottom: a tap fans it out into the list.
        let first = visiblePlatters().last ?? platters.last
        if let f = first?.frame {
            mark("collapsed front at \(f)")
            tap("nc-expand", f.midX, f.midY)
            wait(2.5)
            shot("nc-expanded")
            tree("nc-expanded")
        }
        // The list scrolled up step by step (the finger rests before it lifts: no fling).
        for i in 1...6 {
            drag("nc-scroll-up-\(i)", CGPoint(x: w / 2, y: h * 0.72), CGPoint(x: w / 2, y: h * 0.72 - 120), velocity: 250, hold: 0.8)
            wait(1.2)
            shot("nc-scrolled-\(i)")
            if i % 2 == 0 { tree("nc-scrolled-\(i)") }
        }
        // Held mid-scroll, then back down slowly (never a fling down: that hides the notifications).
        drag("nc-scroll-held", CGPoint(x: w / 2, y: h * 0.7), CGPoint(x: w / 2, y: h * 0.7 - 90), velocity: 200, hold: 2.5)
        for i in 1...3 {
            drag("nc-scroll-down-\(i)", CGPoint(x: w / 2, y: h * 0.4), CGPoint(x: w / 2, y: h * 0.4 + 120), velocity: 250, hold: 0.8)
            wait(1.0)
        }
        shot("nc-scrolled-back")
        // Press and hold a platter in the middle of the screen: the long look.
        let vis = visiblePlatters()
        if let p = vis.dropFirst(vis.count / 2).first {
            let f = p.frame
            mark("hold platter at \(f)")
            longPress("nc-hold", f.midX, f.midY, 1.2)
            wait(2)
            shot("nc-longlook")
            tree("nc-longlook")
            tap("nc-longlook-out", w / 2, h * 0.08)
            wait(2)
            shot("nc-after-longlook")
        } else { mark("no visible platter to hold") }
        // A tap on a platter opens its app (the host app).
        if let p = visiblePlatters().last {
            let f = p.frame
            mark("tap platter at \(f)")
            tap("nc-open-app", f.midX, f.midY)
            wait(2.5)
            shot("nc-opened-app")
        } else { mark("no visible platter to tap") }
        home()
        wait(1)
    }

    func test46_ncHideShow() {
        // Swiped down, the notifications hide into a count at the bottom; a tap (or a swipe up) brings them back.
        openNC()
        wait(2.5)
        shot("nc-before-hide")
        drag("nc-hide", CGPoint(x: w / 2, y: h * 0.62), CGPoint(x: w / 2, y: h * 0.92), velocity: 1200)
        wait(2)
        shot("nc-hidden")
        tree("nc-hidden")
        let count = sb.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS[c] 'Notification'")).allElementsBoundByIndex
            .filter { $0.frame.minY > h * 0.6 }.first
        if let f = count?.frame {
            mark("count at \(f)")
            tap("nc-show", f.midX, f.midY)
            wait(2)
            shot("nc-shown-again")
            tree("nc-shown-again")
        } else {
            drag("nc-show-swipe", CGPoint(x: w / 2, y: h * 0.85), CGPoint(x: w / 2, y: h * 0.55), velocity: 800)
            wait(2)
            shot("nc-shown-again")
        }
        closeFromBottom("nc-close")
        wait(1.5)
    }

    // MARK: - Apple's own apps

    /// Apple's apps do not look like SpringBoard: solid grouped lists, glass only on bars and buttons. The Settings app is
    /// the reference for the launcher's settings in the iOS theme: its pages, the large title collapsing, a push and the
    /// way back (button, edge swipe held half way, a swipe from the middle), switches, choice rows, sliders, search.
    private let prefs = XCUIApplication(bundleIdentifier: "com.apple.Preferences")

    private func prefsTree(_ name: String) {
        let a = XCTAttachment(string: prefs.debugDescription)
        a.name = "\(name).txt"
        a.lifetime = .keepAlways
        add(a)
        mark("tree \(name)")
    }

    /// Taps the row named `label` on the page shown, scrolling the page up a little at a time until it is on screen.
    @discardableResult
    private func openRow(_ label: String) -> Bool {
        let e = prefs.staticTexts[label].firstMatch
        for i in 0..<6 {
            if e.exists && e.isHittable && e.frame.maxY < h * 0.82 { break }
            drag("find-\(label)-\(i)", CGPoint(x: w / 2, y: h * 0.7), CGPoint(x: w / 2, y: h * 0.45), velocity: 400, hold: 0.4)
            wait(0.8)
        }
        guard e.exists && e.isHittable else { mark("no row \(label)"); return false }
        let f = e.frame
        tap("open-\(label)", f.midX, f.midY)
        return true
    }

    private func backButton(_ name: String) {
        let b = prefs.navigationBars.buttons.firstMatch
        guard b.exists else { mark("no back button"); return }
        mark("back button at \(b.frame) label \(b.label)")
        tap(name, b.frame.midX, b.frame.midY)
    }

    private func freshSettings() {
        prefs.terminate()
        wait(1)
        prefs.activate()
        wait(3)
    }

    func test50_settingsApp() {
        freshSettings()
        shot("settings-root")
        prefsTree("settings-root")
        // Scrolled slowly (held, no fling): the large title goes under the bar.
        drag("scroll-up", CGPoint(x: w / 2, y: h * 0.7), CGPoint(x: w / 2, y: h * 0.55), velocity: 200, hold: 0.8)
        wait(1)
        shot("settings-scrolled-a")
        drag("scroll-up-more", CGPoint(x: w / 2, y: h * 0.7), CGPoint(x: w / 2, y: h * 0.4), velocity: 300, hold: 0.8)
        wait(1)
        shot("settings-scrolled-b")
        prefsTree("settings-scrolled")
        // Pulled down past the top and held: the large title stretches?
        drag("pull-down", CGPoint(x: w / 2, y: h * 0.3), CGPoint(x: w / 2, y: h * 0.75), velocity: 400, hold: 1.0)
        wait(1.5)
        shot("settings-pulled-back")
        // Push and pop with the back button, three times.
        for i in 1...3 {
            guard openRow("General") else { break }
            wait(2)
            if i == 1 { shot("general"); prefsTree("general") }
            backButton("back-\(i)")
            wait(2)
        }
        // The way back by an edge swipe: held half way and let go, then a full one.
        if openRow("General") {
            wait(2)
            drag("edge-half", CGPoint(x: 1, y: h * 0.5), CGPoint(x: w * 0.42, y: h * 0.5), velocity: 250, hold: 0.8)
            wait(2)
            shot("after-edge-half")
            drag("edge-full", CGPoint(x: 1, y: h * 0.5), CGPoint(x: w * 0.9, y: h * 0.5), velocity: 900)
            wait(2)
            shot("after-edge-full")
        }
        // From the middle of the page (iOS 26 lets a swipe back start anywhere?).
        if openRow("General") {
            wait(2)
            drag("middle-swipe", CGPoint(x: w * 0.3, y: h * 0.55), CGPoint(x: w * 0.9, y: h * 0.55), velocity: 900)
            wait(2)
            shot("after-middle-swipe")
            if prefs.navigationBars.buttons.firstMatch.exists { backButton("back-after-middle"); wait(2) }
        }
        // Pages with other row kinds: info rows (About), choices with checks, switches.
        if openRow("General") {
            wait(2)
            if openRow("About") { wait(2); shot("about"); prefsTree("about"); backButton("back-about"); wait(2) }
            backButton("back-general")
            wait(2)
        }
        if openRow("Home Screen & App Library") { wait(2); shot("home-screen"); prefsTree("home-screen"); backButton("back-home-screen"); wait(2) }
        if openRow("Appearance") { wait(2); shot("appearance"); prefsTree("appearance"); backButton("back-appearance"); wait(2) }
        if openRow("Accessibility") {
            wait(2)
            shot("accessibility")
            if openRow("Display & Text Size") {
                wait(2)
                shot("display-text")
                prefsTree("display-text")
                // A switch flipped on and off (On/Off Labels changes nothing else).
                let sw = prefs.switches["On/Off Labels"].firstMatch
                if sw.exists {
                    let f = sw.frame
                    mark("switch at \(f)")
                    tap("switch-on", f.maxX - 20, f.midY)
                    wait(1.5)
                    shot("switch-on")
                    tap("switch-off", f.maxX - 20, f.midY)
                    wait(1.5)
                } else { mark("no On/Off Labels switch") }
                backButton("back-display")
                wait(2)
            }
            backButton("back-accessibility")
            wait(2)
        }
        // Search: the pill at the bottom.
        let search = prefs.searchFields.firstMatch
        if search.exists {
            mark("search at \(search.frame)")
            tap("search", search.frame.midX, search.frame.midY)
            wait(2)
            shot("search-open")
            prefsTree("search-open")
            let cancel = prefs.buttons["Cancel"].firstMatch
            if cancel.exists { tap("search-cancel", cancel.frame.midX, cancel.frame.midY) } else { XCUIDevice.shared.press(.home) }
            wait(2)
        } else { mark("no search field") }
        home()
    }

    // MARK: - Landscape (Matheesha, 2026-10-13: "fix both issues. observe IOS first")

    /// A point on the host app's screen as it is turned now ([nx], [ny] fractions of its width and height).
    private func hostPt(_ nx: CGFloat, _ ny: CGFloat) -> XCUICoordinate {
        host.coordinate(withNormalizedOffset: CGVector(dx: nx, dy: ny))
    }

    private func hostDrag(_ name: String, _ a: (CGFloat, CGFloat), _ b: (CGFloat, CGFloat), velocity: CGFloat = 1500, press: TimeInterval = 0.05, hold: TimeInterval = 0) {
        mark("drag \(name) begin \(a) -> \(b) v\(Int(velocity)) hold \(hold) (host fractions, \(Int(host.frame.width))x\(Int(host.frame.height)))")
        hostPt(a.0, a.1).press(forDuration: press, thenDragTo: hostPt(b.0, b.1), withVelocity: XCUIGestureVelocity(rawValue: velocity), thenHoldForDuration: hold)
        mark("drag \(name) lifted")
    }

    /// The host app full screen with its pattern, the device turned to landscape.
    private func landscapeHost() {
        host.launchArguments = ["-pattern"]
        host.launch()
        wait(1.5)
        XCUIDevice.shared.orientation = .landscapeLeft
        wait(2.5)
        mark("landscape host \(host.frame) springboard \(sb.frame)")
    }

    private func portrait() {
        XCUIDevice.shared.orientation = .portrait
        wait(2)
    }

    /// Control Center in landscape over an app: where everything goes (screenshot and tree), how it opens and closes, its
    /// edit mode, whether it scrolls; Notification Center beside it for comparison.
    func test60_ccLandscape() {
        landscapeHost()
        shot("land-app")
        for i in 1...3 {
            hostDrag("land-cc-open-\(i)", (0.93, 0.005), (0.93, 0.62))
            wait(2)
            if i == 1 { shot("land-cc-open"); tree("land-cc-open") }
            hostDrag("land-cc-close-\(i)", (0.5, 0.99), (0.5, 0.45))
            wait(2)
        }
        // A pull held part way: the panel under the finger in landscape.
        hostDrag("land-cc-hold", (0.93, 0.005), (0.93, 0.3), velocity: 300, hold: 1.0)
        wait(2)
        shot("land-cc-after-hold")
        // Does it scroll (up, then down)?
        hostDrag("land-cc-scroll-up", (0.5, 0.8), (0.5, 0.25), velocity: 800, hold: 0.3)
        wait(1.5)
        shot("land-cc-scrolled-up")
        tree("land-cc-scrolled-up")
        hostDrag("land-cc-scroll-down", (0.5, 0.3), (0.5, 0.85), velocity: 800, hold: 0.3)
        wait(1.5)
        shot("land-cc-scrolled-down")
        // Edit mode in landscape.
        hostPt(0.5, 0.92).press(forDuration: 1.3)
        mark("land-cc-edit long press")
        wait(2)
        shot("land-cc-edit")
        tree("land-cc-edit")
        hostDrag("land-cc-edit-scroll", (0.5, 0.8), (0.5, 0.25), velocity: 800, hold: 0.3)
        wait(1.5)
        shot("land-cc-edit-scrolled")
        hostPt(0.5, 0.97).tap()
        mark("land-cc-edit done")
        wait(1.5)
        hostDrag("land-cc-close", (0.5, 0.99), (0.5, 0.45))
        wait(2)
        // Notification Center in landscape.
        hostDrag("land-nc-open", (0.1, 0.005), (0.1, 0.62))
        wait(2)
        shot("land-nc-open")
        tree("land-nc-open")
        hostDrag("land-nc-close", (0.5, 0.99), (0.5, 0.45))
        wait(2)
        portrait()
        home()
    }

    /// Notification Center in landscape with notifications (Matheesha, 2026-10-20: "The landscape notification shade could
    /// use some attention. its current state is very unintuitive"): where the clock, the list, its stacks and the buttons
    /// go; one notification, then many (collapsed, fanned out, scrolled).
    func test62_ncLandscape() {
        post("one", in: 15)
        letBannersPass()
        landscapeHost()
        hostDrag("land-nc1-open", (0.1, 0.005), (0.1, 0.62))
        wait(2.5)
        shot("land-nc1")
        tree("land-nc1")
        hostDrag("land-nc1-close", (0.5, 0.99), (0.5, 0.45))
        wait(2)
        portrait()
        post("many", in: 20)
        letBannersPass()
        landscapeHost()
        hostDrag("land-nc-open", (0.1, 0.005), (0.1, 0.62))
        wait(2.5)
        shot("land-nc-collapsed")
        tree("land-nc-collapsed")
        // The collapsed stack: a tap on it fans it out (tapped through the element itself: its frame is in portrait points).
        if let p = platters.filter({ $0.isHittable }).last ?? platters.last {
            mark("land collapsed front at \(p.frame)")
            p.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
            wait(2.5)
            shot("land-nc-expanded")
            tree("land-nc-expanded")
        } else { mark("land: no platter") }
        // Scrolled up step by step, the finger resting before it lifts.
        for i in 1...3 {
            hostDrag("land-nc-scroll-\(i)", (0.5, 0.8), (0.5, 0.45), velocity: 250, hold: 0.8)
            wait(1.2)
            shot("land-nc-scrolled-\(i)")
        }
        tree("land-nc-scrolled-3")
        hostDrag("land-nc-close", (0.5, 0.99), (0.5, 0.45))
        wait(2)
        portrait()
        home()
    }

    /// Going home from an app in landscape (home stays upright on an iPhone): the swipe up, three times at the usual
    /// speed, then held part way (the card under the finger), then the App Switcher from landscape.
    func test61_homeFromLandscape() {
        for i in 1...3 {
            landscapeHost()
            if i == 1 { shot("land-before-home") }
            hostDrag("land-home-\(i)", (0.5, 0.99), (0.5, 0.45), velocity: 2000)
            wait(3)
            if i == 1 { shot("land-after-home"); tree("land-after-home") }
        }
        landscapeHost()
        hostDrag("land-card-hold", (0.5, 0.99), (0.5, 0.7), velocity: 250, hold: 1.5)
        wait(2.5)
        shot("land-after-card-hold")
        landscapeHost()
        hostDrag("land-switcher", (0.5, 0.99), (0.5, 0.55), velocity: 500, hold: 1.0)
        wait(2)
        shot("land-switcher")
        tree("land-switcher")
        portrait()
        home()
    }

    // MARK: - Every Control Center control, tapped and held (2026-10-14, Matheesha: "check how each button behaves in
    // the qs panel of ios - one tap, long press - and make sure ours align")

    /// A file-safe name for a label.
    private func slug(_ s: String) -> String {
        let a = s.lowercased().map { $0.isLetter || $0.isNumber ? $0 : "-" }
        return String(a.prefix(28))
    }

    /// Control Center's controls as they show now: every element with a label inside the grid (between the status row and
    /// the bottom), one per place (a module and its own parts listed separately), top to bottom, left to right.
    private func ccControls() -> [(String, CGRect)] {
        var out: [(String, CGRect)] = []
        let all = sb.buttons.allElementsBoundByIndex + sb.sliders.allElementsBoundByIndex + sb.switches.allElementsBoundByIndex
        for e in all {
            let f = e.frame
            let l = e.label
            if l.isEmpty || f.width < 20 || f.height < 20 || f.width > w * 0.95 { continue }
            if f.minY < 100 || f.maxY > h * 0.9 { continue }
            if out.contains(where: { abs($0.1.midX - f.midX) < 6 && abs($0.1.midY - f.midY) < 6 }) { continue }
            out.append((l, f))
        }
        return out.sorted { abs($0.1.minY - $1.1.minY) > 8 ? $0.1.minY < $1.1.minY : $0.1.minX < $1.1.minX }
    }

    /// Adds each of [names] the gallery offers (a fresh simulator's Control Center has few controls).
    private func addControls(_ names: [String]) {
        openCC()
        wait(2)
        for n in names {
            longPress("cc-empty", w / 2, h * 0.93, 1.3)
            wait(1.5)
            let add = sb.buttons["Add a Control"]
            guard add.waitForExistence(timeout: 3) else { mark("no Add a Control"); break }
            add.tap()
            wait(2)
            let c = sb.buttons[n].firstMatch
            if c.waitForExistence(timeout: 3) {
                mark("add control \(n)")
                c.tap()
            } else {
                mark("control \(n) not offered")
                drag("gallery-down", CGPoint(x: w / 2, y: h * 0.12), CGPoint(x: w / 2, y: h * 0.9), velocity: 1500)
            }
            wait(2)
            tap("cc-edit-done", w / 2, h * 0.96)
            wait(1.5)
        }
        home()
    }

    func test70_ccEveryControl() {
        addControls(["Flashlight", "Timer", "Calculator", "Camera", "Dark Mode", "Low Power Mode", "Orientation Lock",
                     "Silent Mode", "Alarm", "Stopwatch", "Quick Note", "Voice Memo", "Text Size", "Magnifier", "Screen Recording"])
        openCC()
        wait(2)
        shot("cc-all")
        tree("cc-all")
        let controls = ccControls()
        mark("cc controls: " + controls.map { "\($0.0) \($0.1)" }.joined(separator: "; "))
        for (n, c) in controls.enumerated() {
            let (label, f) = c
            let name = String(format: "%02d-", n) + slug(label)
            // A tap, from a fresh Control Center.
            home()
            openCC("cc-open-tap-\(n)")
            wait(2)
            tap("tap-" + name, f.midX, f.midY)
            wait(1.8)
            shot("tap-" + name)
            tree("tap-" + name)
            // A long press, from a fresh Control Center.
            home()
            openCC("cc-open-hold-\(n)")
            wait(2)
            longPress("hold-" + name, f.midX, f.midY, 1.0)
            wait(1.5)
            shot("hold-" + name)
            tree("hold-" + name)
        }
        home()
    }

    func test71_ccParts() {
        // Now Playing's parts (its artwork, its title, its middle), the "+" and the power button, and inside the expanded
        // connectivity module Wi-Fi and Bluetooth held (their lists on iOS).
        openCC()
        wait(2)
        let media = sb.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS[c] %@ OR label CONTAINS[c] %@", "Not Playing", "Now Playing")).firstMatch
        if media.exists {
            let f = media.frame
            mark("media at \(f)")
            for (part, x, y) in [("art", f.minX + f.width * 0.25, f.minY + f.height * 0.28), ("title", f.midX, f.minY + f.height * 0.6),
                                 ("middle", f.midX, f.midY)] {
                home(); openCC(); wait(2)
                tap("media-" + part, x, y)
                wait(1.8)
                shot("media-tap-" + part)
                tree("media-tap-" + part)
            }
        } else {
            mark("no media module")
        }
        for (part, x) in [("plus", 36.0), ("power", Double(w) - 36.0)] {
            home(); openCC(); wait(2)
            tap("top-" + part, CGFloat(x), 70)
            wait(1.8)
            shot("top-tap-" + part)
            tree("top-tap-" + part)
            home(); openCC(); wait(2)
            longPress("top-hold-" + part, CGFloat(x), 70, 1.0)
            wait(1.5)
            shot("top-hold-" + part)
        }
        home(); openCC(); wait(2)
        let wifi = sb.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS[c] %@", "Wi-Fi")).firstMatch
        if wifi.exists {
            let f = wifi.frame
            longPress("conn-expand", f.midX, f.midY, 1.0)
            wait(2)
            shot("conn-expanded")
            tree("conn-expanded")
            for n in ["Wi-Fi", "Bluetooth", "Airplane Mode", "Cellular", "AirDrop", "Personal Hotspot"] {
                let e = sb.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS[c] %@", n)).firstMatch
                if !e.exists { mark("expanded: no \(n)"); continue }
                let g = e.frame
                longPress("conn-hold-" + slug(n), g.midX, g.midY, 1.0)
                wait(1.8)
                shot("conn-hold-" + slug(n))
                tree("conn-hold-" + slug(n))
                // Back to the expanded module (a list closes with a tap outside it).
                home(); openCC(); wait(2)
                longPress("conn-expand-again", f.midX, f.midY, 1.0)
                wait(2)
            }
        } else {
            mark("no Wi-Fi in Control Center")
        }
        home()
    }

    /// Home's own gestures beside the pages (2026-10-14): the Search pill pressed and slid (iOS scrubs through the pages),
    /// a quick swipe on it, a swipe right on the first page (the Today View), and a dock icon held.
    func test72_homeGestures() {
        shot("home-start")
        tree("home-start")
        let search = sb.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@", "Search")).firstMatch
        let f = search.exists ? search.frame : CGRect(x: w / 2 - 40, y: h - 140, width: 80, height: 30)
        mark("search pill at \(f)")
        // Pressed and slid slowly to the right, held at the end, then lifted.
        drag("pill-scrub-right", CGPoint(x: f.midX, y: f.midY), CGPoint(x: f.midX + 140, y: f.midY), velocity: 120, press: 0.6, hold: 0.8)
        wait(1.0)
        shot("pill-after-scrub-right")
        tree("pill-after-scrub-right")
        wait(2.5)
        shot("pill-later")
        // Back, sliding left.
        drag("pill-scrub-left", CGPoint(x: f.midX, y: f.midY), CGPoint(x: f.midX - 140, y: f.midY), velocity: 120, press: 0.6, hold: 0.8)
        wait(1.0)
        shot("pill-after-scrub-left")
        home()
        // A quick swipe across the pill (no hold first).
        drag("pill-swipe", CGPoint(x: f.midX + 60, y: f.midY), CGPoint(x: f.midX - 80, y: f.midY), velocity: 1500)
        wait(1.0)
        shot("pill-after-swipe")
        home()
        // A swipe right on the first page: the Today View.
        drag("today", CGPoint(x: w * 0.15, y: h * 0.45), CGPoint(x: w * 0.85, y: h * 0.45), velocity: 1200)
        wait(1.5)
        shot("today")
        tree("today")
        home()
        // A dock icon held.
        longPress("dock-hold", w * 0.37, h - 70, 1.0)
        wait(1.5)
        shot("dock-held")
        tree("dock-held")
        home()
    }

    // MARK: - Calibration

    /// Known springs and a linear move (RefHost's CalibrationView): proves the measurement chain and gives the
    /// slow-motion factor.
    func test98_calibrate() {
        host.launchArguments = ["-calibrate", "-slow", String(slow)]
        host.launch()
        mark("calibration launched")
        wait(2 + 4 * 2.5 + 1.5)
        mark("calibration done")
        home()
    }
}
