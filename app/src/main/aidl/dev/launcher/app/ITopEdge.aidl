package dev.launcher.app;

// The app's end of the shell's top-edge watch (see IShellService.watchEdge).
oneway interface ITopEdge {
    // A finger came down at the screen's top edge and moved down: [x] where across the screen (0..1, screen coordinates).
    void onEdgeSwipe(float x) = 1;
}
