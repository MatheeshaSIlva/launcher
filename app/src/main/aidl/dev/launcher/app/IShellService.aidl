package dev.launcher.app;

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
}
