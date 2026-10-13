package dev.launcher.app;

import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import dev.launcher.app.ITouchStream;
import dev.launcher.app.ITopEdge;
import dev.launcher.app.IWindowWatch;

// Runs in the Shizuku user-service process (uid 2000 via wireless debugging).
// Transaction codes are explicit so the app and an older still-running service never disagree.
interface IShellService {
    // Reserved by Shizuku: called when the service is unbound or removed.
    void destroy() = 16777114;

    String identity() = 1;

    // Runs a shell command and returns its output plus "[exit N]" (8 s timeout).
    String runShell(String command) = 2;

    // Starts a shell command and returns at once; output is discarded.
    String runDetached(String command) = 3;

    // Downloads inside the shell process so the shell installer can read the file. Returns "OK <bytes>" or "ERROR: ...".
    String downloadFile(String url, String dest) = 4;

    // Watchdog. A shell loop outside both processes runs the restore plan when the heartbeat stops changing for 4 checks.
    // Arm: stores the plan (shell commands, may be empty), starts the loop if it is not running. Reply contains
    // "FIRED <time>" once if the loop fired since the last arm.
    String watchdogArm(String restorePlan) = 5;
    oneway void heartbeat() = 6;
    String watchdogStatus() = 7;

    // runShell with a caller-chosen timeout, for slow diagnostics such as logcat dumps.
    String runShellTimeout(String command, int timeoutMs) = 8;

    // Sets StatusBarManager disable/disable2 flags through IStatusBarService with a token owned by this process: the system
    // drops them when this process dies. While any flag is set, the service also clears them when `client` (the app) dies.
    // Used for hiding the stock status bar and for blocking the stock home/recents gestures.
    String setDisableFlags(int what1, int what2, IBinder client) = 9;

    // Recent task ids, most recent first (the home task is not listed).
    int[] recentTaskIds(int max) = 10;

    // Snapshot of a task as a HARDWARE bitmap (never copied in this process). fresh=false: cached snapshot (1-3 ms,
    // may be old); fresh=true: newly taken. Null when none (e.g. secure apps).
    Bitmap taskSnapshot(int taskId, boolean fresh) = 11;

    // Brings a recent task to the front (IActivityTaskManager.startActivityFromRecents).
    String switchToTask(int taskId) = 12;

    // Recent tasks as "taskId packageName activityClass", most recent first (the home task is not listed; builds before
    // 2026-10-14 sent no class).
    String[] recentTasks(int max) = 13;

    // 14, 15: retired (mirrorDisplay probe; it crashed the system on the S24). Do not reuse these codes.

    // Like switchToTask, with ActivityOptions (e.g. "no animation": our own card animates instead).
    String switchToTaskWithOptions(int taskId, in Bundle options) = 16;

    // Probe (blocking, a few seconds): can the shell animate real app windows through a remote transition? Opens
    // [component] with its window growing from a small card, then starts [homeComponent] with the app's window shrinking
    // away slowly. Returns a report of what the system handed over and how the frames went.
    String probeLiveTransition(String component, String homeComponent) = 17;

    // Starts / switches with a remote transition of ours that ends at once, so the system plays no animation of its own
    // (our cards are the animation) without touching the system animation scales. Returns "result <code>" or "ERROR: ...".
    String startNoAnim(in Intent intent, int userId) = 18;
    String switchToTaskNoAnim(int taskId) = 19;
    // "requests=N invoked=N consumed=N errors=N": whether the system hands those transitions to us.
    String noAnimStats() = 20;

    // The window manager's lines about the system bar appearance (for our status bar's light/dark content).
    String windowAppearance() = 21;

    // A task's snapshot as its graphics buffer (fresh as in taskSnapshot); the app wraps it into a hardware bitmap. Returning
    // a Bitmap (taskSnapshot) made Binder read the hardware bitmap back into a 10 MB software copy in this process, which the
    // app then uploaded to the GPU at its first draw (5+ ms, traced on the S24) and scanned on its UI thread. Null when none.
    HardwareBuffer taskSnapshotBuffer(int taskId, boolean fresh) = 22;

    // Closes a recent task (IActivityTaskManager.removeTask; the App Switcher's flick up). "ok", "not removed" or "ERROR: ...".
    String removeTask(int taskId) = 23;
    // The system's last picture of a task, reduced (half size) when it has to be read from storage; the full one when the
    // system still holds it. For the App Switcher's stacked cards. Null when none.
    HardwareBuffer taskSnapshotBufferLow(int taskId) = 24;

    // Visible tasks floating over the others (picture-in-picture, pop-up/freeform windows) as
    // "taskId package windowingMode left top right bottom" (display px). Our card window leaves holes for them, so a video
    // keeps playing in front of launch and close animations instead of vanishing under them.
    String[] floatingWindows() = 25;

    // Display brightness for Control Center's slider (default display): the current value (linear float, as the display
    // manager holds it, -1 if unknown), its range [min, max], and setting it: temporary while the finger moves (not
    // saved, as SystemUI's slider does), committed on release (saved). Oneway: called on every frame of a drag.
    float brightness() = 26;
    float[] brightnessRange() = 27;
    oneway void setBrightness(float value, boolean commit) = 28;

    // Streams the touchscreen's raw first-finger position to [listener] until every finger is up (read from /dev/input,
    // which the shell may read). For a pull from the status bar: the window manager hands a pull from the top edge over
    // to the stock status bar after 24 dp (DisplayPolicy.requestTransientBars: transferTouch), so our window stops
    // receiving it; the shade keeps following the finger from this stream. Starts at once; a second call replaces the first.
    void watchTouch(in ITouchStream listener) = 29;
    void stopTouch() = 30;

    // While an app hides the status bar (immersive): tells [listener] when a finger comes down within [band] of the
    // screen's top edge and moves [travel] down (fractions of the screen's height, in screen coordinates for display
    // [rotation]), as the system shows the bars for a moment then. Only reads /dev/input, never takes the touch from the
    // app; one thread asleep until a touch. Runs until stopEdge; a second call replaces the first.
    void watchEdge(in ITopEdge listener, int rotation, float band, float travel) = 32;
    void stopEdge() = 33;

    // Sends a notification's PendingIntent as the user's tap, with the same instant remote transition as startNoAnim (no
    // system animation: our card grows out of the notification). Returns "result <code>" or "ERROR: ...".
    String sendNoAnim(in PendingIntent pi) = 31;

    // When (uptime ms, the same clock in every process) a start of [component] (package/class) through our instant
    // transition was last on screen (its start transaction committed); 0 if never. A close over a turned display starts
    // home only once its cover is.
    long transitionShownAt(String component) = 34;

    // One touch event injected as the touchscreen's (INJECT_EVENTS) for the app under the gesture strip: a MotionEvent
    // action (DOWN, MOVE, UP, CANCEL), screen x/y, downTime/eventTime (uptime ms), as input device deviceId (the finger's
    // own: Android refuses a second device's touch while one touches). Into windows of targetUid only (-1: any window).
    // waitForResult: returns whether a window of that uid took it (a DOWN aimed while the strip is still in the way is
    // refused); else queued and true.
    boolean injectTouch(int action, float x, float y, long downTime, long eventTime, int deviceId, int targetUid, boolean waitForResult) = 35;

    // Whether [pkg]'s visible task will go into picture-in-picture by itself when the user leaves it (its PiP parameters'
    // auto-enter: YouTube while a video plays): "" if not, else "auto" and, if the app gave one, its source rect hint
    // (where the video is in its window, px): "auto l t r b".
    String pipHint(String pkg) = 36;

    // The live frames of the windows whose names contain one of [names] (a mini player's package): [listener] is told each
    // time they move, resize, come or go (SurfaceFlinger's window information: also while SystemUI moves a mini player under
    // the finger). Replaces the previous watch; "ok" or "ERROR: ...". stopWindows ends it.
    String watchWindows(in IWindowWatch listener, in String[] names) = 37;
    void stopWindows() = 38;
}
