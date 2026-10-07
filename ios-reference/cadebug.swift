// Switches one of the Simulator's CoreAnimation debug options (what Simulator.app's Debug menu does), for the
// capture's slow-motion pass: `debug_slow_animations` slows every CoreAnimation animation (about 10 times).
//   xcrun swift ios-reference/cadebug.swift UDID debug_slow_animations 1|0
// CoreSimulator's private API (-[SimDevice setCADebugOption:enabled:]), the same call the Simulator app makes.
import Foundation

let args = CommandLine.arguments
guard args.count >= 4 else { print("usage: cadebug UDID OPTION 1|0"); exit(2) }
let udid = args[1].uppercased(), option = args[2], enabled = args[3] == "1"

guard dlopen("/Library/Developer/PrivateFrameworks/CoreSimulator.framework/CoreSimulator", RTLD_NOW) != nil else {
    print("cadebug: CoreSimulator not found"); exit(1)
}

func developerDir() -> String {
    if let d = ProcessInfo.processInfo.environment["DEVELOPER_DIR"], !d.isEmpty { return d }
    let p = Process()
    p.executableURL = URL(fileURLWithPath: "/usr/bin/xcode-select")
    p.arguments = ["-p"]
    let pipe = Pipe()
    p.standardOutput = pipe
    try? p.run()
    p.waitUntilExit()
    return String(data: pipe.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8)?
        .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
}

guard let contextClass = NSClassFromString("SimServiceContext") as? NSObject.Type,
      let context = contextClass.perform(NSSelectorFromString("sharedServiceContextForDeveloperDir:error:"),
                                         with: developerDir(), with: nil)?.takeUnretainedValue() as? NSObject,
      let deviceSet = context.perform(NSSelectorFromString("defaultDeviceSetWithError:"), with: nil)?
          .takeUnretainedValue() as? NSObject,
      let devices = deviceSet.value(forKey: "devices") as? [NSObject] else {
    print("cadebug: no device set"); exit(1)
}
guard let device = devices.first(where: { ($0.value(forKey: "UDID") as? NSUUID)?.uuidString == udid }) else {
    print("cadebug: no device \(udid)"); exit(1)
}
let sel = NSSelectorFromString("setCADebugOption:enabled:")
guard device.responds(to: sel) else { print("cadebug: setCADebugOption:enabled: not available"); exit(1) }
typealias Fn = @convention(c) (AnyObject, Selector, NSString, ObjCBool) -> ObjCBool
let fn = unsafeBitCast(device.method(for: sel), to: Fn.self)
let ok = fn(device, sel, option as NSString, ObjCBool(enabled)).boolValue
print("cadebug: \(option) \(enabled ? "on" : "off") -> \(ok)")
exit(ok ? 0 : 1)
