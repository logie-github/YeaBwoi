package com.logie.wifecraft.simulation

/** A tiny key-value store (SharedPreferences on the phone, a map in tests). */
interface ToyKeyValue {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

class MapToyKeyValue(val map: MutableMap<String, String> = HashMap()) : ToyKeyValue {
    override fun getString(key: String) = map[key]
    override fun putString(key: String, value: String) { map[key] = value }
}

/** Toy interaction's settings, kept across runs: Reactions (Off, Subtle, Natural, Intense; Natural by default), the climax option, the spoken lines, the vibrator's strength. */
class ToyPrefs(private val kv: ToyKeyValue) {
    var level: ReactionLevel
        get() = ReactionLevel.of(kv.getString(LEVEL))
        set(v) = kv.putString(LEVEL, v.name)
    var climax: Boolean
        get() = kv.getString(CLIMAX) != "off"
        set(v) = kv.putString(CLIMAX, if (v) "on" else "off")
    var lines: Boolean
        get() = kv.getString(LINES) != "off"
        set(v) = kv.putString(LINES, if (v) "on" else "off")
    var vibration: Float
        get() = kv.getString(VIB)?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.5f
        set(v) = kv.putString(VIB, v.coerceIn(0f, 1f).toString())

    private companion object { const val LEVEL = "toy.reactions"; const val CLIMAX = "toy.climax"; const val LINES = "toy.lines"; const val VIB = "toy.vibration" }
}
