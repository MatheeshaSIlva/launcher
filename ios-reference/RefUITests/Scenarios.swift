import XCTest

/// Drives iOS's own system UI (SpringBoard: Control Center, Notification Center, banners, home) with exact touches, one
/// scenario per test. The capture script records the screen around each test; the tests save screenshots and the
/// accessibility tree (every element's frame) at the interesting moments, and print `REFMARK <unix time> <what>` lines
/// so the video can be lined up with the gestures. Nothing here asserts: a missing element is logged and skipped.
final class Scenarios: XCTestCase {
    private let sb = XCUIApplication(bundleIdentifier: "com.apple.springboard")
    private let host = XCUIApplication(bundleIdentifier: "dev.launcher.ref.host")

    private var w: CGFloat { sb.frame.width }
    private var h: CGFloat { sb.frame.height }
    private var outDir: URL? { ProcessInfo.processInfo.environment["REF_OUT"].map { URL(fileURLWithPath: $0) } }

    override func setUp() {
        continueAfterFailure = true
        // The test runner may be in front: start every scenario on the home screen, at rest.
        XCUIDevice.shared.press(.home)
        sleep(2)
        mark("ready \(Int(w))x\(Int(h))")
    }

    // MARK: - Helpers

    private func mark(_ s: String) {
        print(String(format: "REFMARK %.3f %@", Date().timeIntervalSince1970, s))
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

    private func wait(_ s: Double) { usleep(useconds_t(s * 1_000_000)) }

    private func save(_ data: Data, _ file: String) {
        guard let d = outDir else { return }
        do { try data.write(to: d.appendingPathComponent(file)) } catch { mark("could not write \(file): \(error)") }
    }

    private func shot(_ name: String) {
        let s = XCUIScreen.main.screenshot()
        let a = XCTAttachment(screenshot: s)
        a.name = name
        a.lifetime = .keepAlways
        add(a)
        save(s.pngRepresentation, "\(name).png")
        mark("shot \(name)")
    }

    /// The system UI's accessibility tree (types, labels, identifiers and frames in points).
    private func tree(_ name: String) {
        let t = sb.debugDescription
        let a = XCTAttachment(string: t)
        a.name = "\(name).txt"
        a.lifetime = .keepAlways
        add(a)
        save(Data(t.utf8), "\(name).txt")
        mark("tree \(name)")
    }

    private func allowNotifications() {
        let allow = sb.alerts.buttons["Allow"]
        if allow.waitForExistence(timeout: 5) { mark("allow notifications"); allow.tap() }
    }

    /// The host app schedules `set` (see RefHostApp) `delay` s from now; we go home before it arrives.
    private func post(_ set: String, in delay: Double) {
        host.launchArguments = ["-post", set, String(delay)]
        host.launch()
        allowNotifications()
        mark("posted \(set) in \(delay)s")
        XCUIDevice.shared.press(.home)
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

    // MARK: - Home and Control Center

    func test01_home() {
        shot("home")
        tree("home")
    }

    func test02_ccOpenClose() {
        openCC()
        wait(2.5)
        shot("cc-open")
        tree("cc-open")
        closeFromBottom("cc-close")
        wait(2)
        shot("cc-closed")
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
        openCC()
        wait(2)
        tree("cc-for-tap")
        tap("cc-empty", w / 2, h * 0.94)
        wait(2)
        shot("cc-after-tap")
    }

    func test05_ccEdit() {
        openCC()
        wait(2)
        longPress("cc-empty", w / 2, h * 0.92, 1.3)
        wait(2)
        shot("cc-edit")
        tree("cc-edit")
        let add = sb.buttons["Add a Control"]
        if add.exists {
            mark("tap Add a Control")
            add.tap()
            wait(2)
            shot("cc-gallery")
            tree("cc-gallery")
            drag("gallery-down", CGPoint(x: w / 2, y: h * 0.12), CGPoint(x: w / 2, y: h * 0.9), velocity: 1500)
            wait(2)
        } else {
            mark("no Add a Control button")
        }
        tap("cc-edit-empty", w / 2, h * 0.95)
        wait(2)
        shot("cc-edit-left")
        closeFromBottom("cc-close")
        wait(1.5)
    }

    func test06_ccExpandModule() {
        openCC()
        wait(2)
        // The connectivity block (top left); its frame comes from the tree, a guess if it has no known name.
        var p = CGPoint(x: w * 0.27, y: h * 0.27)
        let wifi = sb.buttons["Wi-Fi"]
        if wifi.exists { p = CGPoint(x: wifi.frame.midX, y: wifi.frame.midY); mark("wifi at \(wifi.frame)") }
        longPress("cc-module", p.x, p.y, 1.0)
        wait(2)
        shot("cc-expanded")
        tree("cc-expanded")
        tap("cc-outside", w / 2, h * 0.95)
        wait(2)
        shot("cc-collapsed")
        closeFromBottom("cc-close")
        wait(1.5)
    }

    func test07_ccSlider() {
        openCC()
        wait(2)
        let sliders = sb.sliders.allElementsBoundByIndex
        mark("sliders \(sliders.count): " + sliders.map { "\($0.label) \($0.frame)" }.joined(separator: "; "))
        if let s = sliders.first {
            let f = s.frame
            drag("slider-up", CGPoint(x: f.midX, y: f.maxY - 12), CGPoint(x: f.midX, y: f.minY - 90), velocity: 400, hold: 0.6)
            wait(1.5)
            drag("slider-down", CGPoint(x: f.midX, y: f.minY + 12), CGPoint(x: f.midX, y: f.maxY + 90), velocity: 400, hold: 0.6)
            wait(1.5)
        }
        closeFromBottom("cc-close")
        wait(1.5)
    }

    // MARK: - Notification Center

    func test08_ncOpenClose() {
        openNC()
        wait(2.5)
        shot("nc-open")
        tree("nc-open")
        closeFromBottom("nc-close")
        wait(2)
        shot("nc-closed")
    }

    func test09_ncPullHold() {
        drag("nc-pull-hold", CGPoint(x: 70, y: 2), CGPoint(x: 70, y: h * 0.35), velocity: 350, hold: 1.5)
        wait(2)
        shot("nc-after-slow-release")
        closeFromBottom("nc-close")
        wait(1.5)
    }

    func test10_ncStacks() {
        post("stack", in: 1)
        wait(7)
        openNC()
        wait(2.5)
        shot("nc-stacks")
        tree("nc-stacks")
        let cells = sb.cells.allElementsBoundByIndex
        mark("cells \(cells.count): " + cells.prefix(12).map { "\($0.label.prefix(40)) \($0.frame)" }.joined(separator: "; "))
        if let first = cells.first {
            tap("nc-stack", first.frame.midX, first.frame.midY)
            wait(2)
            shot("nc-stack-expanded")
            tree("nc-stack-expanded")
            let f = first.frame
            drag("nc-swipe-left", CGPoint(x: f.maxX - 30, y: f.midY), CGPoint(x: f.minX + 120, y: f.midY), velocity: 600)
            wait(1.5)
            shot("nc-swiped")
            tree("nc-swiped")
            tap("nc-elsewhere", w / 2, h * 0.9)
            wait(1.5)
        }
        closeFromBottom("nc-close")
        wait(1.5)
    }

    // MARK: - Banners

    func test11_bannerTimeout() {
        post("one", in: 4)
        wait(4.5)
        shot("banner")
        tree("banner")
        wait(9)
        shot("banner-gone")
    }

    func test12_bannerSwipeUp() {
        post("one", in: 3)
        wait(4.5)
        drag("banner-up", CGPoint(x: w / 2, y: 90), CGPoint(x: w / 2, y: 10), velocity: 900)
        wait(2)
        shot("banner-swiped")
    }

    func test13_bannerReplace() {
        post("two", in: 3)
        wait(12)
    }

    func test14_bannerPullDown() {
        post("one", in: 3)
        wait(4.5)
        drag("banner-down", CGPoint(x: w / 2, y: 90), CGPoint(x: w / 2, y: 330), velocity: 500, hold: 0.8)
        wait(2)
        shot("banner-pulled")
        tree("banner-pulled")
        tap("outside", w / 2, h * 0.9)
        wait(2)
    }

    func test15_bannerLong() {
        post("long", in: 3)
        wait(4.5)
        shot("banner-long")
        tree("banner-long")
        wait(8)
    }

    // MARK: - Apps (reference for open / close)

    func test16_appOpenClose() {
        let settings = sb.icons["Settings"]
        if settings.exists {
            mark("tap Settings icon \(settings.frame)")
            settings.tap()
            wait(2.5)
            shot("settings")
            closeFromBottom("app-home", velocity: 2000)
            wait(2)
            // A slow, held swipe up: the app as a card under the finger, then let go.
            settings.tap()
            wait(2.5)
            drag("app-card-hold", CGPoint(x: w / 2, y: h - 4), CGPoint(x: w / 2, y: h * 0.7), velocity: 300, hold: 0.8)
            wait(2)
            shot("after-card-hold")
            XCUIDevice.shared.press(.home)
            wait(1.5)
        } else {
            mark("no Settings icon")
        }
    }

    // MARK: - Home screen

    func test17_pages() {
        tree("home-pages")
        // To the next page (or the App Library) and back: page snap.
        drag("page-left", CGPoint(x: w * 0.85, y: h * 0.45), CGPoint(x: w * 0.25, y: h * 0.45), velocity: 1500)
        wait(1.5)
        shot("page-next")
        drag("page-right", CGPoint(x: w * 0.15, y: h * 0.45), CGPoint(x: w * 0.75, y: h * 0.45), velocity: 1500)
        wait(1.5)
        // A slow half drag, released: does it go or come back?
        drag("page-half", CGPoint(x: w * 0.8, y: h * 0.45), CGPoint(x: w * 0.45, y: h * 0.45), velocity: 250, hold: 0.5)
        wait(1.5)
        shot("page-after-half")
    }

    func test18_appLibrary() {
        // Swipe left until the App Library shows (it is the last page).
        for i in 0..<4 {
            drag("page-\(i)", CGPoint(x: w * 0.85, y: h * 0.45), CGPoint(x: w * 0.2, y: h * 0.45), velocity: 2000)
            wait(1.2)
        }
        shot("library")
        tree("library")
        // A category folder: the first collection with several apps (tap its small icons' area).
        let groups = sb.otherElements.matching(NSPredicate(format: "label CONTAINS[c] 'Utilities' OR label CONTAINS[c] 'Productivity' OR label CONTAINS[c] 'Creativity' OR label CONTAINS[c] 'Other'")).allElementsBoundByIndex
        mark("library groups \(groups.count): " + groups.prefix(8).map { "\($0.label) \($0.frame)" }.joined(separator: "; "))
        if let g = groups.first {
            let f = g.frame
            tap("library-folder", f.maxX - f.width * 0.25, f.maxY - f.height * 0.25)
            wait(2)
            shot("library-folder")
            tree("library-folder")
            tap("library-folder-out", w / 2, h * 0.93)
            wait(1.5)
        }
        // The search field: the list.
        tap("library-search", w / 2, 85)
        wait(2)
        shot("library-list")
        tree("library-list")
        drag("library-list-down", CGPoint(x: w / 2, y: h * 0.3), CGPoint(x: w / 2, y: h * 0.8), velocity: 1500)
        wait(1.5)
        XCUIDevice.shared.press(.home)
        wait(1.5)
    }

    func test19_spotlight() {
        drag("spotlight", CGPoint(x: w / 2, y: h * 0.35), CGPoint(x: w / 2, y: h * 0.6), velocity: 800)
        wait(2)
        shot("spotlight")
        tree("spotlight")
        drag("spotlight-up", CGPoint(x: w / 2, y: h * 0.75), CGPoint(x: w / 2, y: h * 0.3), velocity: 1500)
        wait(1.5)
        XCUIDevice.shared.press(.home)
        wait(1.5)
    }

    func test20_iconMenu() {
        let settings = sb.icons["Settings"]
        guard settings.exists else { mark("no Settings icon"); return }
        let f = settings.frame
        longPress("icon-menu", f.midX, f.midY, 0.9)
        wait(1.5)
        shot("icon-menu")
        tree("icon-menu")
        tap("icon-menu-out", w / 2, h * 0.08)
        wait(1.5)
    }

    func test21_editMode() {
        // Long press on an empty area of the home page: jiggle mode.
        longPress("home-empty", w / 2, h * 0.62, 1.6)
        wait(2)
        shot("edit")
        tree("edit")
        // Drag one icon over another's place: the others make room.
        let icons = sb.icons.allElementsBoundByIndex.filter { $0.frame.minY > 50 && $0.frame.maxY < h - 150 }
        mark("icons \(icons.count): " + icons.prefix(8).map { "\($0.label) \($0.frame)" }.joined(separator: "; "))
        if icons.count >= 3 {
            let a = icons[0].frame, b = icons[2].frame
            drag("icon-move", CGPoint(x: a.midX, y: a.midY), CGPoint(x: b.midX + 10, y: b.midY), velocity: 300, press: 0.6, hold: 1.2)
            wait(2)
            shot("edit-moved")
            // And back.
            let c = sb.icons.allElementsBoundByIndex.filter { $0.label == icons[0].label }.first?.frame ?? b
            drag("icon-back", CGPoint(x: c.midX, y: c.midY), CGPoint(x: a.midX, y: a.midY), velocity: 300, press: 0.6, hold: 1.2)
            wait(2)
        }
        let done = sb.buttons["Done"]
        if done.exists { mark("tap Done"); done.tap() } else { XCUIDevice.shared.press(.home) }
        wait(2)
        shot("edit-done")
    }

    func test23_folder() {
        // Make a folder (edit mode: an icon dropped on another), open and close it, then take it apart again.
        longPress("home-empty", w / 2, h * 0.62, 1.6)
        wait(2)
        let icons = sb.icons.allElementsBoundByIndex.filter { $0.frame.minY > 50 && $0.frame.maxY < h - 150 }
        guard icons.count >= 2 else { mark("too few icons"); return }
        let a = icons[0].frame, b = icons[1].frame
        drag("icon-onto", CGPoint(x: a.midX, y: a.midY), CGPoint(x: b.midX, y: b.midY), velocity: 300, press: 0.6, hold: 1.6)
        wait(2.5)
        shot("folder-made")
        tree("folder-made")
        tap("folder-made-out", w / 2, h * 0.08)
        wait(1)
        let done = sb.buttons["Done"]
        if done.exists { done.tap() } else { XCUIDevice.shared.press(.home) }
        wait(2)
        let folders = sb.icons.allElementsBoundByIndex.filter { $0.label.lowercased().contains("folder") || $0.identifier.lowercased().contains("folder") }
        mark("folders \(folders.count): " + folders.map { "\($0.label) \($0.frame)" }.joined(separator: "; "))
        if let f = folders.first?.frame {
            tap("folder-open", f.midX, f.midY)
            wait(2)
            shot("folder-open")
            tree("folder-open")
            tap("folder-close", w / 2, h * 0.92)
            wait(2)
            shot("folder-closed")
        }
    }

    func test22_appSwitcher() {
        let settings = sb.icons["Settings"]
        guard settings.exists else { mark("no Settings icon"); return }
        settings.tap()
        wait(2.5)
        // Up from the bar and hold: the App Switcher.
        drag("switcher", CGPoint(x: w / 2, y: h - 4), CGPoint(x: w / 2, y: h * 0.55), velocity: 500, hold: 1.0)
        wait(2)
        shot("switcher")
        tree("switcher")
        // Flick the card away, then home.
        drag("switcher-flick", CGPoint(x: w / 2, y: h * 0.5), CGPoint(x: w / 2, y: h * 0.1), velocity: 3000)
        wait(2)
        shot("switcher-flicked")
        XCUIDevice.shared.press(.home)
        wait(1.5)
    }
}
