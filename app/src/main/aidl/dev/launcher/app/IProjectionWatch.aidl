package dev.launcher.app;

// The app's end of the shell's screen capture watch (see IShellService.watchProjection).
oneway interface IProjectionWatch {
    // The app whose screen capture (MediaProjection: a screen share, a recording, a cast) is active now; "" when none.
    void onProjection(String pkg) = 1;
}
