package dev.launcher.app.apps

import android.content.pm.ApplicationInfo

/**
 * App Library categories, in iOS's names and default order. Android has no store categories, so [classify] combines a
 * table of well-known apps, the manifest's own category, and keywords in the package name. Unknown system apps go to
 * Utilities, unknown user apps to Other.
 */
enum class AppCategory(val title: String) {
    SOCIAL("Social"),
    ENTERTAINMENT("Entertainment"),
    PRODUCTIVITY("Productivity & Finance"),
    CREATIVITY("Creativity"),
    UTILITIES("Utilities"),
    INFORMATION("Information & Reading"),
    GAMES("Games"),
    TRAVEL("Travel"),
    SHOPPING("Shopping & Food"),
    HEALTH("Health & Fitness"),
    EDUCATION("Education"),
    OTHER("Other");

    companion object {
        private val KNOWN = mapOf(
            // Social
            "com.whatsapp" to SOCIAL, "com.whatsapp.w4b" to SOCIAL, "com.instagram.android" to SOCIAL,
            "com.facebook.katana" to SOCIAL, "com.facebook.orca" to SOCIAL, "com.snapchat.android" to SOCIAL,
            "org.telegram.messenger" to SOCIAL, "com.twitter.android" to SOCIAL, "com.reddit.frontpage" to SOCIAL,
            "com.discord" to SOCIAL, "com.linkedin.android" to SOCIAL, "com.viber.voip" to SOCIAL,
            "com.google.android.apps.messaging" to SOCIAL, "com.samsung.android.messaging" to SOCIAL,
            "com.android.mms" to SOCIAL, "com.google.android.dialer" to SOCIAL, "com.samsung.android.dialer" to SOCIAL,
            "com.android.dialer" to SOCIAL, "com.google.android.apps.tachyon" to SOCIAL, "org.thoughtcrime.securesms" to SOCIAL,
            // Entertainment
            "com.zhiliaoapp.musically" to ENTERTAINMENT, "com.ss.android.ugc.trill" to ENTERTAINMENT,
            "com.google.android.youtube" to ENTERTAINMENT, "com.google.android.apps.youtube.music" to ENTERTAINMENT,
            "com.netflix.mediaclient" to ENTERTAINMENT, "com.spotify.music" to ENTERTAINMENT,
            "com.amazon.avod.thirdpartyclient" to ENTERTAINMENT, "com.disney.disneyplus" to ENTERTAINMENT,
            "tv.twitch.android.app" to ENTERTAINMENT, "com.sec.android.app.music" to ENTERTAINMENT,
            "com.samsung.android.tvplus" to ENTERTAINMENT, "com.google.android.videos" to ENTERTAINMENT,
            // Productivity & Finance
            "com.google.android.gm" to PRODUCTIVITY, "com.google.android.calendar" to PRODUCTIVITY,
            "com.samsung.android.calendar" to PRODUCTIVITY, "com.google.android.apps.docs" to PRODUCTIVITY,
            "com.google.android.apps.docs.editors.docs" to PRODUCTIVITY, "com.google.android.apps.docs.editors.sheets" to PRODUCTIVITY,
            "com.google.android.apps.docs.editors.slides" to PRODUCTIVITY, "com.microsoft.office.outlook" to PRODUCTIVITY,
            "com.samsung.android.app.notes" to PRODUCTIVITY, "com.google.android.keep" to PRODUCTIVITY,
            "com.samsung.android.email.provider" to PRODUCTIVITY, "com.microsoft.teams" to PRODUCTIVITY,
            "com.slack" to PRODUCTIVITY, "com.notion.id" to PRODUCTIVITY, "com.google.android.apps.walletnfcrel" to PRODUCTIVITY,
            "com.samsung.android.spay" to PRODUCTIVITY, "com.paypal.android.p2pmobile" to PRODUCTIVITY,
            "com.microsoft.office.officehubrow" to PRODUCTIVITY, "com.google.android.apps.tasks" to PRODUCTIVITY,
            // Creativity
            "com.sec.android.app.camera" to CREATIVITY, "com.google.android.GoogleCamera" to CREATIVITY,
            "com.sec.android.gallery3d" to CREATIVITY, "com.google.android.apps.photos" to CREATIVITY,
            "com.adobe.lrmobile" to CREATIVITY, "com.picsart.studio" to CREATIVITY, "com.canva.editor" to CREATIVITY,
            "com.lemon.lvoverseas" to CREATIVITY, "com.sec.android.app.voicenote" to CREATIVITY,
            "com.samsung.android.app.pinboard" to CREATIVITY, "com.samsung.android.video" to CREATIVITY,
            // Utilities
            "com.android.settings" to UTILITIES, "com.sec.android.app.popupcalculator" to UTILITIES,
            "com.google.android.calculator" to UTILITIES, "com.sec.android.app.clockpackage" to UTILITIES,
            "com.google.android.deskclock" to UTILITIES, "com.sec.android.app.myfiles" to UTILITIES,
            "com.google.android.apps.nbu.files" to UTILITIES, "com.android.chrome" to UTILITIES,
            "com.sec.android.app.sbrowser" to UTILITIES, "org.mozilla.firefox" to UTILITIES, "com.brave.browser" to UTILITIES,
            "com.microsoft.emmx" to UTILITIES, "com.android.vending" to UTILITIES, "com.sec.android.app.samsungapps" to UTILITIES,
            "com.samsung.android.app.contacts" to UTILITIES, "com.google.android.contacts" to UTILITIES,
            "com.samsung.android.oneconnect" to UTILITIES, "com.samsung.android.voc" to UTILITIES,
            "com.google.android.googlequicksearchbox" to UTILITIES, "com.samsung.android.app.smartcapture" to UTILITIES,
            "moe.shizuku.privileged.api" to UTILITIES, "com.google.android.apps.authenticator2" to UTILITIES,
            // Information & Reading
            "com.google.android.apps.magazines" to INFORMATION, "com.sec.android.daemonapp" to INFORMATION,
            "com.google.android.apps.weather" to INFORMATION, "com.amazon.kindle" to INFORMATION,
            "com.google.android.apps.books" to INFORMATION, "org.wikipedia" to INFORMATION, "com.medium.reader" to INFORMATION,
            // Travel
            "com.google.android.apps.maps" to TRAVEL, "com.waze" to TRAVEL, "com.ubercab" to TRAVEL, "ee.mtakso.client" to TRAVEL,
            "com.airbnb.android" to TRAVEL, "com.booking" to TRAVEL, "com.pickme.passenger" to TRAVEL,
            // Shopping & Food
            "com.amazon.mShop.android.shopping" to SHOPPING, "com.daraz.android" to SHOPPING, "com.ebay.mobile" to SHOPPING,
            "com.alibaba.aliexpresshd" to SHOPPING, "com.ubercab.eats" to SHOPPING, "com.temu" to SHOPPING,
            // Health & Fitness
            "com.sec.android.app.shealth" to HEALTH, "com.google.android.apps.fitness" to HEALTH, "com.strava" to HEALTH,
            // Education
            "com.duolingo" to EDUCATION, "com.google.android.apps.classroom" to EDUCATION, "org.coursera.android" to EDUCATION,
        )

        private val KEYWORDS = listOf(
            listOf("game", "games", "puzzle", "chess", "racing") to GAMES,
            listOf("bank", "wallet", "finance", "money", "pay", "invest", "crypto", "office", "docs", "notes", "mail") to PRODUCTIVITY,
            listOf("camera", "photo", "gallery", "editor", "video", "draw", "paint", "studio") to CREATIVITY,
            listOf("chat", "messenger", "social", "dating") to SOCIAL,
            listOf("music", "player", "tv", "movie", "stream", "radio", "podcast") to ENTERTAINMENT,
            listOf("news", "weather", "reader", "books", "comic") to INFORMATION,
            listOf("maps", "travel", "taxi", "ride", "flight", "hotel", "transit") to TRAVEL,
            listOf("shop", "shopping", "food", "delivery", "market", "eats") to SHOPPING,
            listOf("health", "fitness", "workout", "sleep", "run") to HEALTH,
            listOf("learn", "school", "education", "study", "course") to EDUCATION,
            listOf("calculator", "clock", "files", "browser", "settings", "launcher", "keyboard", "vpn", "scanner") to UTILITIES,
        )

        fun classify(pkg: String, info: ApplicationInfo?, system: Boolean): AppCategory {
            KNOWN[pkg]?.let { return it }
            if (info != null) {
                @Suppress("DEPRECATION")
                if (info.flags and ApplicationInfo.FLAG_IS_GAME != 0) return GAMES
                when (info.category) {
                    ApplicationInfo.CATEGORY_GAME -> return GAMES
                    ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO -> return ENTERTAINMENT
                    ApplicationInfo.CATEGORY_IMAGE -> return CREATIVITY
                    ApplicationInfo.CATEGORY_SOCIAL -> return SOCIAL
                    ApplicationInfo.CATEGORY_NEWS -> return INFORMATION
                    ApplicationInfo.CATEGORY_MAPS -> return TRAVEL
                    ApplicationInfo.CATEGORY_PRODUCTIVITY -> return PRODUCTIVITY
                    ApplicationInfo.CATEGORY_ACCESSIBILITY -> return UTILITIES
                }
            }
            val words = pkg.lowercase().split('.', '_')
            for ((keys, cat) in KEYWORDS) if (words.any { w -> keys.any { w == it || w.endsWith(it) } }) return cat
            return if (system) UTILITIES else OTHER
        }
    }
}
