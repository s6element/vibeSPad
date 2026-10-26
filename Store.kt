package app.soundpad

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class Pad(
    val id: String,
    var name: String,
    val file: String,
    var volume: Float = 1f,
    var loop: Boolean = false,
)

object Store {
    private var ctx: Context? = null
    val pads = ArrayList<Pad>()
    var master = 1f
    var mic = false
    var monitor = false
    var overlay = false

    val dir: File
        get() = File(ctx!!.filesDir, "sounds").also { it.mkdirs() }

    private fun prefs() = ctx!!.getSharedPreferences("soundpad", Context.MODE_PRIVATE)

    @Synchronized
    fun init(c: Context) {
        if (ctx != null) return
        ctx = c.applicationContext
        val p = prefs()
        master = p.getFloat("master", 1f)
        mic = p.getBoolean("mic", false)
        monitor = p.getBoolean("monitor", false)
        overlay = p.getBoolean("overlay", false)
        val arr = JSONArray(p.getString("pads", "[]"))
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            pads.add(
                Pad(
                    o.getString("id"),
                    o.getString("name"),
                    o.getString("file"),
                    o.optDouble("vol", 1.0).toFloat(),
                    o.optBoolean("loop", false),
                )
            )
        }
    }

    fun save() {
        val arr = JSONArray()
        for (p in pads) {
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("file", p.file)
                    .put("vol", p.volume.toDouble())
                    .put("loop", p.loop)
            )
        }
        prefs().edit()
            .putString("pads", arr.toString())
            .putFloat("master", master)
            .putBoolean("mic", mic)
            .putBoolean("monitor", monitor)
            .putBoolean("overlay", overlay)
            .apply()
    }

    fun add(name: String, file: String): Pad {
        val p = Pad(UUID.randomUUID().toString(), name, file)
        pads.add(p)
        save()
        return p
    }

    fun remove(p: Pad) {
        pads.remove(p)
        File(dir, p.file).delete()
        save()
    }
}
