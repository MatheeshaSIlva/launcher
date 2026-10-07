package dev.launcher.app;

// The app's end of a raw touch stream from the shell service (see IShellService.watchTouch). Positions are normalised to
// the touchscreen's range (0..1, the panel's natural orientation); times are System.nanoTime() in the shell process.
oneway interface ITouchStream {
    // action: 2 = the first finger moved, 1 = every finger is up (the stream ends).
    void onTouch(int action, float x, float y, long timeNanos) = 1;
}
