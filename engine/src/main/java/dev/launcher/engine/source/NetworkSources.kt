package dev.launcher.engine.source

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.provider.Settings
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import dev.launcher.engine.expr.Value
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

/**
 * `network`: Wi-Fi (level 0 for none, else 1 to 3 arcs), cellular bars (0 to 4), service (`airplane`, `no-sim`,
 * `no-service` or `in-service`, in that order, as the bar picks what it shows), the type label (5G, LTE+, LTE, 3G, E or
 * nothing) and VPN, each read as StatusBarView's readers read them.
 */
data class NetworkState(
    val wifiLevel: Int = 0, val cellLevel: Int = 0, val inService: Boolean = true, val simAbsent: Boolean = false,
    val airplane: Boolean = false, val type: String = "", val vpn: Boolean = false,
) : SourceValue {
    val service get() = when { airplane -> "airplane"; simAbsent -> "no-sim"; !inService -> "no-service"; else -> "in-service" }

    override fun member(name: String) = when (name) {
        "wifi.level" -> Value.Num(wifiLevel.toDouble())
        "wifi.connected" -> Value.Bool.of(wifiLevel > 0)
        "cell.level" -> Value.Num(cellLevel.toDouble())
        "service" -> Value.Str(service)
        "airplane" -> Value.Bool.of(airplane)
        "type" -> Value.Str(type)
        "vpn" -> Value.Bool.of(vpn)
        else -> Value.Null
    }

    fun after(e: NetEvent): NetworkState = when (e) {
        is NetEvent.Wifi -> copy(wifiLevel = e.level?.coerceIn(1, 3) ?: 0)
        is NetEvent.Vpn -> copy(vpn = e.on)
        is NetEvent.Signal -> copy(cellLevel = e.level.coerceIn(0, 4))
        is NetEvent.Service -> copy(inService = e.inService, simAbsent = e.simAbsent)
        is NetEvent.Type -> copy(type = e.label)
        is NetEvent.Airplane -> copy(airplane = e.on)
    }
}

/** What the callbacks report, as plain data (no Android object past [AndroidNetwork]). */
sealed class NetEvent {
    /** A Wi-Fi network's level (null: lost). */
    data class Wifi(val level: Int?) : NetEvent()
    data class Vpn(val on: Boolean) : NetEvent()
    data class Signal(val level: Int) : NetEvent()
    data class Service(val inService: Boolean, val simAbsent: Boolean) : NetEvent()
    data class Type(val label: String) : NetEvent()
    data class Airplane(val on: Boolean) : NetEvent()

    companion object {
        /** StatusBarView's `typeLabel`: the override type first (5G, LTE+), then the network type. */
        fun typeLabel(overrideType: Int, networkType: Int): String = when (overrideType) {
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA, TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> "5G"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO, TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> "LTE+"
            else -> when (networkType) {
                TelephonyManager.NETWORK_TYPE_NR -> "5G"
                TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
                TelephonyManager.NETWORK_TYPE_UNKNOWN -> ""
                else -> "E"
            }
        }
    }
}

/** The network callbacks ([AndroidNetwork] in the app, a fake in tests). */
interface NetworkCallbacks {
    fun register(deliver: (NetEvent) -> Unit): () -> Unit
    fun airplaneNow(): Boolean
    fun simAbsentNow(): Boolean
}

/** `dnd`: Do Not Disturb, on for any interruption filter but "all" (and "unknown"). */
data class Dnd(val on: Boolean) : SourceValue {
    override fun member(name: String) = if (name == "on") Value.Bool.of(on) else Value.Null

    companion object {
        fun of(filter: Int) = Dnd(filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN)
    }
}

object NetworkSources {
    fun network(r: Receivers, callbacks: NetworkCallbacks): Source<NetworkState> {
        fun start() = NetworkState(airplane = callbacks.airplaneNow(), simAbsent = callbacks.simAbsentNow())
        return Source("network", "network.read", ::start, callbackFlow {
            var last = start()
            trySend(last)
            fun on(e: NetEvent) { last = last.after(e); trySend(last) }
            val offAirplane = r.register(listOf(Intent.ACTION_AIRPLANE_MODE_CHANGED)) { on(NetEvent.Airplane(callbacks.airplaneNow())) }
            val offCallbacks = callbacks.register(::on)
            awaitClose { offAirplane(); offCallbacks() }
        })
    }

    /** [filterNow]: the current interruption filter, `INTERRUPTION_FILTER_ALL` where it cannot be read (StatusBarView). */
    fun dnd(r: Receivers, filterNow: () -> Int) = Source("dnd", "none", { Dnd.of(filterNow()) },
        r.flow(listOf(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)) { Dnd.of(filterNow()) })
}

/** StatusBarView's two ConnectivityManager callbacks and two TelephonyCallbacks, delivering on [handler]'s thread. */
class AndroidNetwork(private val context: Context, private val handler: Handler) : NetworkCallbacks {
    private val cm get() = context.getSystemService(ConnectivityManager::class.java)
    private val tm get() = context.getSystemService(TelephonyManager::class.java)

    override fun airplaneNow() = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
    override fun simAbsentNow() = try { tm.simState == TelephonyManager.SIM_STATE_ABSENT } catch (_: Throwable) { false }

    override fun register(deliver: (NetEvent) -> Unit): () -> Unit {
        val wifi = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: android.net.Network, caps: NetworkCapabilities) {
                val rssi = caps.signalStrength
                @Suppress("DEPRECATION")
                deliver(NetEvent.Wifi(if (rssi == Int.MIN_VALUE || rssi == NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED) 3 else WifiManager.calculateSignalLevel(rssi, 4)))
            }
            override fun onLost(network: android.net.Network) = deliver(NetEvent.Wifi(null))
        }
        val vpn = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) = deliver(NetEvent.Vpn(true))
            override fun onLost(network: android.net.Network) = deliver(NetEvent.Vpn(false))
        }
        val offs = ArrayList<() -> Unit>()
        try {
            cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifi, handler)
            offs += { cm.unregisterNetworkCallback(wifi) }
        } catch (_: Throwable) { }
        try {
            cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), vpn, handler)
            offs += { cm.unregisterNetworkCallback(vpn) }
        } catch (_: Throwable) { }
        if (Build.VERSION.SDK_INT >= 31) offs += telephony(deliver)
        return { offs.forEach { try { it() } catch (_: Throwable) { } } }
    }

    @androidx.annotation.RequiresApi(31)
    private fun telephony(deliver: (NetEvent) -> Unit): List<() -> Unit> {
        val exec = java.util.concurrent.Executor { handler.post(it) }
        val signal = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener, TelephonyCallback.ServiceStateListener {
            override fun onSignalStrengthsChanged(ss: SignalStrength) = deliver(NetEvent.Signal(ss.level))
            override fun onServiceStateChanged(ss: ServiceState) = deliver(NetEvent.Service(ss.state == ServiceState.STATE_IN_SERVICE, simAbsentNow()))
        }
        val display = object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
            override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) = deliver(NetEvent.Type(NetEvent.typeLabel(info.overrideNetworkType, info.networkType)))
        }
        return listOf(signal, display).mapNotNull { cb ->
            try { tm.registerTelephonyCallback(exec, cb); { tm.unregisterTelephonyCallback(cb) } } catch (_: Throwable) { null }   // display info needs READ_PHONE_STATE
        }
    }
}
