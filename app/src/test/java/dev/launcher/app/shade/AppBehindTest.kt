package dev.launcher.app.shade

import org.junit.Assert.assertEquals
import org.junit.Test

class AppBehindTest {
    private val own = "dev.launcher.app"

    @Test fun aCardBeingBroughtWins() =
        assertEquals("com.b", AppBehind.of("com.b", "com.a", "com.a", own))

    @Test fun theLastWindowInFrontIsTheApp() =
        assertEquals("com.a", AppBehind.of(null, "com.a", "com.a", own))

    @Test fun systemUisWindowIsNotTheAppBehind() =
        assertEquals("com.sec.android.app.clockpackage",
            AppBehind.of(null, "com.android.systemui", "com.sec.android.app.clockpackage", own))

    @Test fun theSystemsOwnWindowIsNotEither() =
        assertEquals("com.a", AppBehind.of(null, "android", "com.a", own))

    @Test fun withNothingBetterTheLastWindowStays() =
        assertEquals("com.android.systemui", AppBehind.of(null, "com.android.systemui", own, own))
}
