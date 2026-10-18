package dev.launcher.app;

// The app's end of the shell's log watch (see IShellService.watchLog).
oneway interface ILogWatch {
    // A line of the watched tag that matched (the last one still in the log first, then each new one).
    void onLine(String line) = 1;
}
