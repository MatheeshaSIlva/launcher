package dev.launcher.app;

// The app's end of the shell's window watch (see IShellService.watchWindows).
oneway interface IWindowWatch {
    // The watched windows now: for each, the index of the name it matched ([which], into the names asked for) and its frame
    // on the display ([frames]: left, top, right, bottom, px, four per window). Empty arrays: none of them shows. [at]: when
    // SurfaceFlinger's report reached the shell (uptime ms).
    void onWindows(in int[] which, in float[] frames, long at) = 1;
}
