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

    private func allowNotifications() {
        let allow = sb.alerts.buttons["Allow"]
        if allow.waitForExistence(timeout: 4) { mark("allow notifications"); allow.tap() }
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
    private func waitBanner(_ timeout: Double = 12) -> CGRect? {
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
        post("stack", in: 5)
        // Let every banner pass (sending each shown one away), then look at the list.
        waitBanner(12)
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
            post("one", in: 5)
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
            post("one", in: 5)
            if let f = waitBanner() {
                wait(1.0)
                drag("banner-up-\(i)", CGPoint(x: f.midX, y: f.midY), CGPoint(x: f.midX, y: 5), velocity: i <= 2 ? 800 : 2000)
            }
            wait(3)
        }
    }

    func test13_bannerReplace() {
        post("two", in: 5)
        waitBanner()
        wait(10)
    }

    func test14_bannerPullDown() {
        post("one", in: 5)
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
        post("long", in: 5)
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
            post("one", in: 5)
            if let f = waitBanner() {
                wait(0.8)
                drag("banner-pull-hold-\(i)", CGPoint(x: f.midX, y: f.midY), CGPoint(x: f.midX, y: f.midY + 40), velocity: 300, hold: 0.7)
                wait(2)
                drag("banner-up-hold-\(i)", CGPoint(x: f.midX, y: f.midY), CGPoint(x: f.midX, y: f.midY - 30), velocity: 300, hold: 0.7)
            }
            wait(3)
        }
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
