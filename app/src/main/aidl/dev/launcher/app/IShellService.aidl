package dev.launcher.app;

import android.graphics.Bitmap;

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

    // Recent tasks as "taskId packageName", most recent first (the home task is not listed).
    String[] recentTasks(int max) = 13;

    // 14, 15: retired (mirrorDisplay probe; it crashed the system on the S24). Do not reuse these codes.
}
