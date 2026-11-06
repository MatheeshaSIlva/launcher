package dev.launcher.engine.adb

import android.net.Network
import java.net.InetAddress

/** An mDNS-resolved ADB endpoint. [network] is unavailable before API 33 and may be null after it. */
data class AdbEndpoint(val address: InetAddress, val port: Int, val network: Network?) {
    init {
        require(port in 1..65_535) { "ADB endpoint port is outside 1..65535: $port" }
    }
}
