import SwiftUI
import UserNotifications

/// A tiny app whose only job is to post local notifications on cue, so the system shows real banners, stacks and
/// Notification Center content for the capture. Launched by the UI tests with `-post <set> <delay seconds>`.
@main
struct RefHostApp: App {
    private let delegate = Presenter()

    init() {
        UNUserNotificationCenter.current().delegate = delegate
        Poster.handle(ProcessInfo.processInfo.arguments)
    }

    var body: some Scene {
        WindowGroup { ContentView() }
    }
}

/// Banners also while this app is in front (the tests mostly go home first).
final class Presenter: NSObject, UNUserNotificationCenterDelegate {
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .list, .sound])
    }
}

struct ContentView: View {
    @State private var status = "starting"

    var body: some View {
        if ProcessInfo.processInfo.arguments.contains("-calibrate") {
            CalibrationView()
        } else {
            VStack(spacing: 16) {
                Text("Reference host").font(.title)
                Text(status).accessibilityIdentifier("status")
            }
            .task {
                let ok = await Poster.authorize()
                status = ok ? "authorized" : "denied"
            }
        }
    }
}

/// Known motion for checking the measurement chain (Simulator -> recording -> tracking -> spring fit) and for finding
/// the slow-motion factor: a red square moves on a white screen, first linearly for 1 s, then on three springs with
/// known SwiftUI parameters. `-slow F` stretches the pauses between them (the animations themselves are slowed by
/// the Simulator).
struct CalibrationView: View {
    @State private var x: CGFloat = 40
    static let moves: [(CGFloat, Animation, String)] = [
        (300, .linear(duration: 1.0), "linear 1.0"),
        (40, .spring(response: 0.5, dampingFraction: 0.8), "spring 0.5 0.8"),
        (300, .spring(response: 0.35, dampingFraction: 1.0), "spring 0.35 1.0"),
        (40, .spring(response: 0.6, dampingFraction: 0.6), "spring 0.6 0.6"),
    ]

    var body: some View {
        ZStack(alignment: .topLeading) {
            Color.white.ignoresSafeArea()
            Rectangle().fill(Color.red).frame(width: 60, height: 60).position(x: x + 30, y: 400)
        }
        .task {
            let args = ProcessInfo.processInfo.arguments
            let slow = args.firstIndex(of: "-slow").flatMap { Double(args[$0 + 1]) } ?? 1
            try? await Task.sleep(nanoseconds: UInt64(2.0 * slow * 1e9))
            for (to, anim, name) in Self.moves {
                print(String(format: "REFMARK %.3f calibrate %@", Date().timeIntervalSince1970, name))
                withAnimation(anim) { x = to }
                try? await Task.sleep(nanoseconds: UInt64(2.5 * slow * 1e9))
            }
        }
    }
}

enum Poster {
    static func authorize() async -> Bool {
        (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])) ?? false
    }

    /// `-post <set> <delay>`: schedules the set's notifications, the first after `delay` seconds.
    static func handle(_ args: [String]) {
        guard let i = args.firstIndex(of: "-post"), i + 2 < args.count else { return }
        let set = args[i + 1]
        let delay = Double(args[i + 2]) ?? 3
        Task {
            _ = await authorize()
            schedule(set, delay: delay)
        }
    }

    /// (thread, title, subtitle, body, gap before it in seconds)
    private typealias Note = (String, String, String?, String, Double)

    private static func notes(_ set: String) -> [Note] {
        switch set {
        case "one":
            return [("maya", "Maya", nil, "Are we still on for lunch tomorrow? I can book the place near the station.", 0)]
        case "two":
            // A second banner while the first still shows: how one replaces the other.
            return [("maya", "Maya", nil, "Are we still on for lunch tomorrow?", 0),
                    ("delivery", "Delivery", nil, "Your parcel is out for delivery and arrives today between 2 and 4 pm.", 2.5)]
        case "stack":
            // Notification Center content: a thread of four (a stack), and two single ones.
            var n: [Note] = (1...4).map { ("group", "Weekend trip", "Alex", "Message \($0): long enough to wrap onto a second line of the platter so the height shows.", 0.4) }
            n.append(("reminder", "Reminder", nil, "Water the plants", 0.4))
            n.append(("delivery", "Delivery", nil, "Your parcel is out for delivery.", 0.4))
            return n
        case "long":
            return [("long", "A rather long title that will not fit on one line of a banner", "Subtitle line",
                     "A body that runs over several lines so the banner's maximum height and its truncation show. " +
                     "It keeps going for a while longer than any real message would, just to be sure.", 0)]
        default:
            return [("misc", set, nil, "Test", 0)]
        }
    }

    static func schedule(_ set: String, delay: Double) {
        var t = delay
        for (n, note) in notes(set).enumerated() {
            t += note.4
            let c = UNMutableNotificationContent()
            c.title = note.1
            if let s = note.2 { c.subtitle = s }
            c.body = note.3
            c.threadIdentifier = note.0
            c.sound = .default
            let trigger = UNTimeIntervalNotificationTrigger(timeInterval: max(t, 0.5), repeats: false)
            UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "\(set)-\(n)-\(Date().timeIntervalSince1970)",
                                                                         content: c, trigger: trigger))
        }
    }
}
