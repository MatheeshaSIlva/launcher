package dev.launcher.app.motion.transition

import org.json.JSONArray
import org.json.JSONObject

/**
 * A preset's transition token as it is written (`assets/motion/<id>.json`, the user's edits), docs/PLAN_PANEL_TRANSITIONS.md:
 *
 * ```
 * "motion.transition.nc.open":  {"transition": {"type": "push", "direction": "down", "drive": "finger"}, "src": "..."}
 * "motion.transition.nc.close": {"transition": {"reverse": true}, "src": "..."}
 * "motion.transition.cc.open":  {"transition": {"type": "custom", "from": "dissolve", "direction": "down", "drive": "release",
 *                                  "tracks": {"bg.blur": [{"at": 0, "v": 0, "ease": "ease-out"}, {"at": 1, "v": 12, "abs": true}],
 *                                             "content.opacity": [{"at": 0.4, "v": 0, "ease": {"bezier": [0.2, 0, 0, 1]}},
 *                                                                 {"at": 1, "v": 1}]}}}
 * ```
 * A track on a clock of its own: `"clock.y": {"clock": {"role": "motion.nc.clock-drop", "cue": "start", "delay": 0},
 * "keys": [...]}` (cues: start, landed, end); its first keyframe may start from the value showing then: `{"at": 0, "v": "current"}`.
 * A track on the connection's progress counted from where it started: `{"progress": "relative", "keys": [...]}`.
 * A Figma type is written as its name alone (its tracks are made from it); a custom one with its tracks. A look's keyframe
 * is a share of the theme's value unless `"abs": true`. Eases: linear, hold, jump, ease-in, ease-out, ease-in-out,
 * ease-in-back, ease-out-back, ease-in-out-back, `{"bezier": [x1, y1, x2, y2]}`, `{"power": p, "out": true}`.
 */
sealed class TransitionSpec {
    data class Own(val transition: Transition) : TransitionSpec()
    /** The way back is the opening reversed ([TransitionTypes.closingFor]). */
    object ReverseOfOpening : TransitionSpec() { override fun toString() = "ReverseOfOpening" }
}

object TransitionJson {
    private val named = linkedMapOf(
        "linear" to Easing.Linear, "hold" to Easing.Hold, "jump" to Easing.Jump,
        "ease-in" to Easing.EASE_IN, "ease-out" to Easing.EASE_OUT, "ease-in-out" to Easing.EASE_IN_OUT,
        "ease-in-back" to Easing.EASE_IN_BACK, "ease-out-back" to Easing.EASE_OUT_BACK, "ease-in-out-back" to Easing.EASE_IN_OUT_BACK)

    /** Reads a token's `transition` object; throws with the reason if it is not one. */
    fun parse(o: JSONObject): TransitionSpec {
        if (o.optBoolean("reverse", false)) return TransitionSpec.ReverseOfOpening
        val type = TransitionType.of(o.getString("type"))
        val direction = Direction.of(o.optString("direction", Direction.DOWN.id))
        val drive = Drive.of(o.optString("drive", Drive.FINGER.id))
        if (type != TransitionType.CUSTOM) {
            require(!o.has("tracks")) { "a ${type.id} transition has no tracks of its own (write it as custom, from ${type.id})" }
            return TransitionSpec.Own(TransitionTypes.opening(type, direction, drive))
        }
        val from = o.optString("from").ifEmpty { null }?.let { TransitionType.of(it) }
        require(from != TransitionType.CUSTOM) { "a custom transition comes from a Figma type, not from custom" }
        val tracks = ArrayList<Track>()
        val t = o.optJSONObject("tracks") ?: JSONObject()
        for (id in t.keys()) {
            val prop = Prop.of(id)
            // A list of keyframes follows the connection's progress; an object has a clock of its own and its keyframes.
            val v = t.get(id)
            val timing: TrackTiming = if (v !is JSONObject) TrackTiming.Progress
                else if (v.optString("progress") == "relative") TrackTiming.Relative
                else (v.optJSONObject("clock") ?: throw IllegalArgumentException("track $id: a track written as an object has a clock or \"progress\": \"relative\"")).let { c ->
                    TrackTiming.Clock(c.getString("role"), Cue.of(c.optString("cue", Cue.START.id)), c.optDouble("delay", 0.0).toFloat())
                }
            val arr = if (v is JSONObject) v.getJSONArray("keys") else t.getJSONArray(id)
            tracks += Track(prop, List(arr.length()) { keyframe(prop, arr.getJSONObject(it)) }, timing)
        }
        return TransitionSpec.Own(Transition(TransitionType.CUSTOM, direction, drive, tracks.sortedBy { it.prop.ordinal }, from))
    }

    private fun keyframe(p: Prop, k: JSONObject): Keyframe {
        val at = k.getDouble("at").toFloat()
        // ("v": "current": from the value showing when its clock starts.)
        val current = k.opt("v") == "current"
        val v = if (current) 0f else k.getDouble("v").toFloat()
        require(v.isFinite()) { "track ${p.id}: a value that is no number" }
        return Keyframe(at, v, k.optBoolean("abs", false), ease(p, k.opt("ease")), fromCurrent = current)
    }

    private fun ease(p: Prop, e: Any?): Easing = when (e) {
        null -> Easing.Linear
        is String -> named[e] ?: throw IllegalArgumentException("track ${p.id}: no ease '$e'")
        is JSONObject -> when {
            e.has("bezier") -> e.getJSONArray("bezier").let { b ->
                require(b.length() == 4) { "track ${p.id}: a bezier has four numbers" }
                Easing.Bezier(b.getDouble(0).toFloat(), b.getDouble(1).toFloat(), b.getDouble(2).toFloat(), b.getDouble(3).toFloat())
            }
            e.has("power") -> Easing.Power(e.getDouble("power").toFloat(), e.optBoolean("out", false))
            else -> throw IllegalArgumentException("track ${p.id}: an ease that is neither a bezier nor a power")
        }
        else -> throw IllegalArgumentException("track ${p.id}: an ease that is neither a name nor an object")
    }

    /** The token's `transition` object for [s] (read back by [parse] as the same). */
    fun write(s: TransitionSpec): JSONObject = when (s) {
        TransitionSpec.ReverseOfOpening -> JSONObject().put("reverse", true)
        is TransitionSpec.Own -> {
            val t = s.transition
            val o = JSONObject().put("type", t.type.id).put("direction", t.direction.id).put("drive", t.drive.id)
            if (t.type == TransitionType.CUSTOM) {
                t.from?.let { o.put("from", it.id) }
                val tracks = JSONObject()
                for (tr in t.tracks) {
                    val keys = JSONArray().apply {
                        for (k in tr.keys) put(JSONObject().put("at", k.at.toDouble()).put("v", if (k.fromCurrent) "current" else k.value.toDouble()).apply {
                            if (k.absolute) put("abs", true)
                            if (k.ease != Easing.Linear) put("ease", writeEase(k.ease))
                        })
                    }
                    when (val c = tr.timing) {
                        TrackTiming.Progress -> tracks.put(tr.prop.id, keys)
                        TrackTiming.Relative -> tracks.put(tr.prop.id, JSONObject().put("keys", keys).put("progress", "relative"))
                        is TrackTiming.Clock -> tracks.put(tr.prop.id, JSONObject().put("keys", keys).put("clock", JSONObject()
                            .put("role", c.role).put("cue", c.cue.id).apply { if (c.delayMs != 0f) put("delay", c.delayMs.toDouble()) }))
                    }
                }
                o.put("tracks", tracks)
            }
            o
        }
    }

    private fun writeEase(e: Easing): Any = named.entries.firstOrNull { it.value == e }?.key ?: when (e) {
        is Easing.Bezier -> JSONObject().put("bezier", JSONArray().put(e.x1.toDouble()).put(e.y1.toDouble()).put(e.x2.toDouble()).put(e.y2.toDouble()))
        is Easing.Power -> JSONObject().put("power", e.exponent.toDouble()).put("out", e.out)
        else -> throw IllegalStateException("ease $e has no name")
    }
}
