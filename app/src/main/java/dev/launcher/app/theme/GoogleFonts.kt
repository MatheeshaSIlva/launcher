package dev.launcher.app.theme

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import java.io.File
import java.io.FileInputStream

/**
 * Google Fonts on the phone (docs/PLAN_LAYOUTS_THEMES.md, B2d): any family of fonts.google.com, downloaded through Google
 * Play services' font provider (Android's "downloadable fonts"; the app asks the provider itself, as AndroidX's
 * FontsContractCompat does, since the platform's FontsContract is deprecated) and kept in files/fonts/google/, so from
 * then on it loads at once, offline too. Without Play services nothing is downloaded and the caller keeps its fallback.
 */
internal object GoogleFonts {
    private const val AUTHORITY = "com.google.android.gms.fonts"
    private const val PACKAGE = "com.google.android.gms"

    /** The file [name] at [weight] is kept in (it exists once downloaded). */
    fun file(ctx: Context, name: String, weight: Int): File =
        File(ctx.filesDir, "fonts/google/" + name.replace(' ', '_') + "-" + weight + ".ttf")

    /** [file] as a typeface at [weight] (a variable file set to it), or null if it does not load. */
    fun load(file: File, weight: Int): Typeface? = try {
        Typeface.Builder(file).setFontVariationSettings("'wght' $weight").setWeight(weight).build()
            ?: Typeface.Builder(file).setWeight(weight).build()
    } catch (_: Throwable) { null }

    /**
     * Downloads [name] at [weight] into [out] (on the caller's thread: a background one). Null when done, else why not
     * (logged by the caller).
     */
    fun download(ctx: Context, name: String, weight: Int, out: File): String? {
        val pm = ctx.packageManager
        val provider = pm.resolveContentProvider(AUTHORITY, 0) ?: return "no font provider (Google Play services is missing)"
        if (provider.packageName != PACKAGE) return "the font provider is not Google Play services' (${provider.packageName})"
        if (!signedByGoogle(pm)) return "Google Play services is not signed by Google"

        val base = Uri.Builder().scheme("content").authority(AUTHORITY).build()
        val files = Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("file").build()
        val query = "name=$name&weight=$weight&besteffort=true"
        var best: Uri? = null
        var bestScore = Int.MAX_VALUE
        var why = "the provider found no such font"
        val cursor = ctx.contentResolver.query(base, arrayOf("_id", "file_id", "font_weight", "font_italic", "result_code"),
            "query = ?", arrayOf(query), null) ?: return "the provider did not answer"
        cursor.use { c ->
            val id = c.getColumnIndex("_id"); val fileId = c.getColumnIndex("file_id")
            val w = c.getColumnIndex("font_weight"); val italic = c.getColumnIndex("font_italic"); val result = c.getColumnIndex("result_code")
            while (c.moveToNext()) {
                val code = if (result >= 0) c.getInt(result) else 0
                if (code != 0) { why = when (code) { 1 -> "no such font"; 2 -> "the font is unavailable"; 3 -> "the request was malformed"; else -> "provider result $code" }; continue }
                val uri = if (fileId >= 0) ContentUris.withAppendedId(files, c.getLong(fileId)) else ContentUris.withAppendedId(base, c.getLong(id))
                // The upright font nearest the weight asked for.
                val score = (if (w >= 0) kotlin.math.abs(c.getInt(w) - weight) else 0) + (if (italic >= 0 && c.getInt(italic) == 1) 10_000 else 0)
                if (score < bestScore) { bestScore = score; best = uri }
            }
        }
        val uri = best ?: return why
        out.parentFile?.mkdirs()
        val tmp = File(out.path + ".tmp")
        ctx.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
            FileInputStream(fd.fileDescriptor).use { input -> tmp.outputStream().use { input.copyTo(it) } }
        } ?: return "the provider gave no file"
        if (load(tmp, weight) == null) { tmp.delete(); return "the downloaded file is not a font" }
        if (!tmp.renameTo(out)) { tmp.delete(); return "could not keep the file" }
        return null
    }

    /** Play services is signed with one of Google's certificates (its release or its debug key). */
    private fun signedByGoogle(pm: PackageManager): Boolean = try {
        val info = pm.getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return false
        val signers = info.apkContentsSigners.orEmpty().toList() +
            (if (info.hasMultipleSigners()) emptyList() else info.signingCertificateHistory.orEmpty().toList())
        val known = CERTS.map { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }
        signers.any { s -> val b = s.toByteArray(); known.any { it.contentEquals(b) } }
    } catch (_: Throwable) { false }

    /**
     * Google Play services' signing certificates for its font provider (DER, base64): its development build's and its
     * release's. The same two AndroidX lists (`com_google_android_gms_fonts_certs`).
     */
    private val CERTS = listOf(
        "MIIEqDCCA5CgAwIBAgIJANWFuGx90071MA0GCSqGSIb3DQEBBAUAMIGUMQswCQYDVQQGEwJVUzETMBEGA1UECBMKQ2FsaWZvcm5p" +
            "YTEWMBQGA1UEBxMNTW91bnRhaW4gVmlldzEQMA4GA1UEChMHQW5kcm9pZDEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5k" +
            "cm9pZDEiMCAGCSqGSIb3DQEJARYTYW5kcm9pZEBhbmRyb2lkLmNvbTAeFw0wODA0MTUyMzM2NTZaFw0zNTA5MDEyMzM2NTZaMIGU" +
            "MQswCQYDVQQGEwJVUzETMBEGA1UECBMKQ2FsaWZvcm5pYTEWMBQGA1UEBxMNTW91bnRhaW4gVmlldzEQMA4GA1UEChMHQW5kcm9p" +
            "ZDEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZDEiMCAGCSqGSIb3DQEJARYTYW5kcm9pZEBhbmRyb2lkLmNvbTCC" +
            "ASAwDQYJKoZIhvcNAQEBBQADggENADCCAQgCggEBANbOLggKv+IxTdGNs8/TGFy0PTP6DHThvbbR24kT9ixcOd9W+EaBPWW+wPPK" +
            "QmsHxajtWjmQwWfna8mZuSeJS48LIgAZlKkpFeVyxW0qMBujb8X8ETrWy550NaFtI6t9+u7hZeTfHwqNvacKhp1RbE6dBRGWynwM" +
            "VX8XW8N1+UjFaq6GCJukT4qmpN2afb8sCjUigq0GuMwYXrFVee74bQgLHWGJwPmvmLHC69EH6kWr22ijx4OKXlSIx2xT1AsSHee7" +
            "0w5iDBiK4aph27yH3TxkXy9V89TDdexAcKk/cVHYNnDBapcavl7y0RiQ4biu8ymM8Ga/nmzhRKya6G0cGw8CAQOjgfwwgfkwHQYD" +
            "VR0OBBYEFI0cxb6VTEM8YYY6FbBMvAPyT+CyMIHJBgNVHSMEgcEwgb6AFI0cxb6VTEM8YYY6FbBMvAPyT+CyoYGapIGXMIGUMQsw" +
            "CQYDVQQGEwJVUzETMBEGA1UECBMKQ2FsaWZvcm5pYTEWMBQGA1UEBxMNTW91bnRhaW4gVmlldzEQMA4GA1UEChMHQW5kcm9pZDEQ" +
            "MA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZDEiMCAGCSqGSIb3DQEJARYTYW5kcm9pZEBhbmRyb2lkLmNvbYIJANWF" +
            "uGx90071MAwGA1UdEwQFMAMBAf8wDQYJKoZIhvcNAQEEBQADggEBABnTDPEF+3iSP0wNfdIjIz1AlnrPzgAIHVvXxunW7SBrDhEg" +
            "lQZBbKJEk5kT0mtKoOD1JMrSu1xuTKEBahWRbqHsXclaXjoBADb0kkjVEJu/Lh5hgYZnOjvlba8Ld7HCKePCVePoTJBdI4fvugnL" +
            "8TsgK05aIskyY0hKI9L8KfqfGTl1lzOv2KoWD0KWwtAWPoGChZxmQ+nBli+gwYMzM1vAkP+aayLe0a1EQimlOalO762r0GXO0ks+" +
            "UeXde2Z4e+8S/pf7pITEI/tP+MxJTALw9QUWEv9lKTk+jkbqxbsh8nfBUapfKqYn0eidpwq2AzVp3juYl7//fKnaPhJD9gs=",
        "MIIEQzCCAyugAwIBAgIJAMLgh0ZkSjCNMA0GCSqGSIb3DQEBBAUAMHQxCzAJBgNVBAYTAlVTMRMwEQYDVQQIEwpDYWxpZm9ybmlh" +
            "MRYwFAYDVQQHEw1Nb3VudGFpbiBWaWV3MRQwEgYDVQQKEwtHb29nbGUgSW5jLjEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMH" +
            "QW5kcm9pZDAeFw0wODA4MjEyMzEzMzRaFw0zNjAxMDcyMzEzMzRaMHQxCzAJBgNVBAYTAlVTMRMwEQYDVQQIEwpDYWxpZm9ybmlh" +
            "MRYwFAYDVQQHEw1Nb3VudGFpbiBWaWV3MRQwEgYDVQQKEwtHb29nbGUgSW5jLjEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMH" +
            "QW5kcm9pZDCCASAwDQYJKoZIhvcNAQEBBQADggENADCCAQgCggEBAKtWLgDYO6IIrgqWbxJOKdoR8qtW0I9Y4sypEwPpt1TTcvZA" +
            "pxsdyxMJZ2JORland2qSGT2y5b+3JKkedxiLDmpHpDsz2WCbdxgxRczfey5YZnTJ4VZbH0xqWVW/8lGmPav5xVwnIiJS6HXk+BVK" +
            "ZF+JcWjAsb/GEuq/eFdpuzSqeYTcfi6idkyugwfYwXFU1+5fZKUaRKYCwkkFQVfcAs1fXA5V+++FGfvjJ/CxURaSxaBvGdGDhfXE" +
            "28LWuT9ozCl5xw4Yq5OGazvV24mZVSoOO0yZ31j7kYvtwYK6NeADwbSxDdJEqO4k//0zOHKrUiGYXtqw/A0LFFtqoZKFjnkCAQOj" +
            "gdkwgdYwHQYDVR0OBBYEFMd9jMIhF1Ylmn/Tgt9r45jk14alMIGmBgNVHSMEgZ4wgZuAFMd9jMIhF1Ylmn/Tgt9r45jk14aloXik" +
            "djB0MQswCQYDVQQGEwJVUzETMBEGA1UECBMKQ2FsaWZvcm5pYTEWMBQGA1UEBxMNTW91bnRhaW4gVmlldzEUMBIGA1UEChMLR29v" +
            "Z2xlIEluYy4xEDAOBgNVBAsTB0FuZHJvaWQxEDAOBgNVBAMTB0FuZHJvaWSCCQDC4IdGZEowjTAMBgNVHRMEBTADAQH/MA0GCSqG" +
            "SIb3DQEBBAUAA4IBAQBt0lLO74UwLDYKqs6Tm8/yzKkEu116FmH4rkaymUIE0P9KaMftGlMexFlaYjzmB2OxZyl6euNXEsQH8gjw" +
            "yxCUKRJNexBiGcCEyj6z+a1fuHHvkiaai+KL8W1EyNmgjmyy8AW7P+LLlkR+ho5zEHatRbM/YAnqGcFh5iZBqpknHf1SKMXFh4dd" +
            "239FJ1jWYfbMDMy3NS5CTMQ2XFI1MvcyUTdZPErjQfTbQe3aDQsQcafEQPD+nqActifKZ0Np0IS9L9kR/wbNvyz6ENwPiTrjV2KR" +
            "kEjH78ZMcUQXg0L3BYHJ3lc69Vs5Ddf9uUGGMYldX3WfMBEmh/9iFBDAaTCK",
    )
}
