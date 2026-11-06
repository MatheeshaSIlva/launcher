package dev.launcher.engine.source

import android.app.NotificationManager
import android.content.Intent
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import dev.launcher.engine.expr.Value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** E2 slice 11: `network` and `dnd` over fake callbacks, read as StatusBarView reads them. */
class NetworkSourcesTest {
    private class FakeReceivers : Receivers {
        val registered = ArrayList<Pair<List<String>, (Broadcast) -> Unit>>()
        override fun register(actions: List<String>, deliver: (Broadcast) -> Unit): () -> Unit {
            val r = actions to deliver
            registered += r
            return { registered.remove(r) }
        }
        override fun sticky(action: String): Broadcast? = null
        fun send(action: String) = registered.toList().filter { action in it.first }.forEach { it.second(Broadcast(action) { _, d -> d }) }
    }

    private class FakeNetwork : NetworkCallbacks {
        var airplane = false
        var simAbsent = false
        var deliver: ((NetEvent) -> Unit)? = null
        override fun register(deliver: (NetEvent) -> Unit): () -> Unit { this.deliver = deliver; return { this.deliver = null } }
        override fun airplaneNow() = airplane
        override fun simAbsentNow() = simAbsent
        fun send(e: NetEvent) = deliver!!(e)
    }

    private val receivers = FakeReceivers()
    private val callbacks = FakeNetwork()
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Test fun networkFollowsItsCallbacks() {
        callbacks.simAbsent = true
        val source = NetworkSources.network(receivers, callbacks)
        assertEquals(NetworkState(simAbsent = true), source.current())   // as StatusBarView at attach: no Wi-Fi, 0 bars
        assertEquals("network.read", source.capability)
        val seen = ArrayList<NetworkState>()
        val job = scope.launch { source.values.collect { seen += it } }
        callbacks.send(NetEvent.Wifi(0))     // calculateSignalLevel's 0: one arc, as the view coerces it
        callbacks.send(NetEvent.Wifi(5))     // never more than three
        callbacks.send(NetEvent.Signal(7))   // nor more than four bars
        callbacks.simAbsent = false
        callbacks.send(NetEvent.Service(inService = false, simAbsent = callbacks.simAbsentNow()))
        callbacks.send(NetEvent.Type("5G"))
        callbacks.send(NetEvent.Vpn(true))
        callbacks.airplane = true
        receivers.send(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        callbacks.send(NetEvent.Wifi(null))  // lost
        val last = seen.last()
        assertEquals(NetworkState(wifiLevel = 0, cellLevel = 4, inService = false, simAbsent = false, airplane = true, type = "5G", vpn = true), last)
        assertEquals(listOf(0, 1, 3, 3, 3, 3, 3, 3, 0), seen.map { it.wifiLevel })
        job.cancel()
        assertEquals(null, callbacks.deliver)
        assertEquals(0, receivers.registered.size)
    }

    @Test fun serviceIsWhatTheBarShowsFirst() {
        assertEquals("airplane", NetworkState(airplane = true, simAbsent = true, inService = false).service)
        assertEquals("no-sim", NetworkState(simAbsent = true, inService = false).service)
        assertEquals("no-service", NetworkState(inService = false).service)
        assertEquals("in-service", NetworkState().service)
        val n = NetworkState(wifiLevel = 2, cellLevel = 3, type = "LTE", vpn = true)
        assertEquals(Value.Num(2.0), n.member("wifi.level"))
        assertSame(Value.Bool.TRUE, n.member("wifi.connected"))
        assertSame(Value.Bool.FALSE, NetworkState().member("wifi.connected"))
        assertEquals(Value.Num(3.0), n.member("cell.level"))
        assertEquals(Value.Str("LTE"), n.member("type"))
        assertSame(Value.Bool.TRUE, n.member("vpn"))
    }

    @Test fun typeLabelsAsTheViewDecidesThem() {
        val none = TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE
        assertEquals("5G", NetEvent.typeLabel(TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA, TelephonyManager.NETWORK_TYPE_LTE))
        assertEquals("5G", NetEvent.typeLabel(TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED, TelephonyManager.NETWORK_TYPE_LTE))
        assertEquals("LTE+", NetEvent.typeLabel(TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA, TelephonyManager.NETWORK_TYPE_LTE))
        assertEquals("LTE+", NetEvent.typeLabel(TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO, TelephonyManager.NETWORK_TYPE_LTE))
        assertEquals("5G", NetEvent.typeLabel(none, TelephonyManager.NETWORK_TYPE_NR))
        assertEquals("LTE", NetEvent.typeLabel(none, TelephonyManager.NETWORK_TYPE_LTE))
        assertEquals("3G", NetEvent.typeLabel(none, TelephonyManager.NETWORK_TYPE_HSPAP))
        assertEquals("3G", NetEvent.typeLabel(none, TelephonyManager.NETWORK_TYPE_UMTS))
        assertEquals("", NetEvent.typeLabel(none, TelephonyManager.NETWORK_TYPE_UNKNOWN))
        assertEquals("E", NetEvent.typeLabel(none, TelephonyManager.NETWORK_TYPE_EDGE))
    }

    @Test fun dndFollowsTheInterruptionFilter() {
        var filter = NotificationManager.INTERRUPTION_FILTER_ALL
        val source = NetworkSources.dnd(receivers) { filter }
        assertEquals(Dnd(false), source.current())
        val seen = ArrayList<Dnd>()
        val job = scope.launch { source.values.collect { seen += it } }
        filter = NotificationManager.INTERRUPTION_FILTER_PRIORITY
        receivers.send(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        filter = NotificationManager.INTERRUPTION_FILTER_NONE
        receivers.send(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)   // still on: no new value
        filter = NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        receivers.send(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        assertEquals(listOf(Dnd(true), Dnd(false)), seen)
        assertEquals(Dnd(true), Dnd.of(NotificationManager.INTERRUPTION_FILTER_ALARMS))
        assertSame(Value.Bool.TRUE, Dnd(true).member("on"))
        job.cancel()
    }
}
