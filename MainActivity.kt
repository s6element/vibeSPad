package app.soundpad

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.io.File
import java.util.UUID

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var grid: GridLayout
    private lateinit var swInject: Switch
    private lateinit var swMic: Switch
    private lateinit var swMon: Switch
    private lateinit var swOverlay: Switch
    private var silent = false
    private val h = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            h.postDelayed(this, 1000)
        }
    }

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { refresh() } }
    private val binderListener = Shizuku.OnBinderReceivedListener { runOnUiThread { refresh() } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        buildUi()
        Shizuku.addRequestPermissionResultListener(permListener)
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        requestPermissions(
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 1
        )
        Engine.preload()
        rebuildGrid()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permListener)
        Shizuku.removeBinderReceivedListener(binderListener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        h.post(tick)
    }

    override fun onPause() {
        h.removeCallbacks(tick)
        super.onPause()
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        setTextColor(Color.WHITE)
        textSize = 15f
    }

    private fun sw(t: String) = Switch(this).apply {
        text = t
        setTextColor(Color.WHITE)
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun btn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        status = label("")
        col.addView(status)

        col.addView(btn("Разрешить Shizuku") { Shell.request(100) })
        col.addView(btn("Проверка прав") { probe() })

        swInject = sw("Передавать звук в микрофон")
        swInject.setOnCheckedChangeListener { _, on ->
            if (silent) return@setOnCheckedChangeListener
            if (on) startInject() else stopInject()
        }
        col.addView(swInject)

        swMic = sw("Подмешивать мой голос")
        swMic.isChecked = Store.mic
        swMic.setOnCheckedChangeListener { _, on ->
            if (silent) return@setOnCheckedChangeListener
            Store.mic = on
            Store.save()
            if (Engine.injecting) Engine.restart()
        }
        col.addView(swMic)

        swMon = sw("Слышать эффекты у себя")
        swMon.isChecked = Store.monitor
        swMon.setOnCheckedChangeListener { _, on ->
            if (silent) return@setOnCheckedChangeListener
            Store.monitor = on
            Store.save()
            Engine.restart()
        }
        col.addView(swMon)

        swOverlay = sw("Плавающая панель")
        swOverlay.isChecked = Store.overlay
        swOverlay.setOnCheckedChangeListener { _, on ->
            if (silent) return@setOnCheckedChangeListener
            if (on && !Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
                silent = true
                swOverlay.isChecked = false
                silent = false
                return@setOnCheckedChangeListener
            }
            Store.overlay = on
            Store.save()
            SoundpadService.poke(this)
        }
        col.addView(swOverlay)

        col.addView(label("Общая громкость"))
        col.addView(SeekBar(this).apply {
            max = 150
            progress = (Store.master * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    Store.master = p / 100f
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    Store.save()
                }
            })
        })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(btn("Тест-тон") { Engine.tone() })
        row.addView(btn("Стоп всё") { Engine.stopAll() })
        row.addView(btn("Добавить") { pick() })
        col.addView(row)

        grid = GridLayout(this).apply { columnCount = 3 }
        col.addView(grid)

        log = label("").apply {
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(0, dp(12), 0, 0)
        }
        col.addView(log)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            addView(col)
        })
    }

    private fun refresh() {
        val sb = StringBuilder()
        sb.append("Android ").append(Build.VERSION.SDK_INT).append(", ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
        sb.append("\nShizuku: ").append(
            when {
                !Shell.alive() -> "не запущен"
                !Shell.granted() -> "нет разрешения"
                else -> "готов"
            }
        )
        sb.append("\nПередача: ").append(if (Engine.injecting) "включена" else "выключена")
        if (Engine.injecting && Store.mic) sb.append("\nМикрофон, пик: ").append(Engine.micPeak)
        status.text = sb.toString()
        if (Engine.note.isNotEmpty()) log.text = Engine.note
        silent = true
        swInject.isChecked = Engine.injecting
        silent = false
    }

    private fun say(t: String) {
        Engine.note = t
        log.text = t
    }

    private fun probe() {
        if (!Shell.granted()) {
            say("Сначала запусти Shizuku и дай разрешение")
            return
        }
        Shell.withService(this) { inj ->
            Thread {
                val r = try {
                    inj.probe()
                } catch (e: Throwable) {
                    "ошибка: ${e.message}"
                }
                runOnUiThread { say(r) }
            }.start()
        }
    }

    private fun startInject() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            say("Нужно разрешение на микрофон")
            refresh()
            return
        }
        if (!Shell.granted()) {
            say("Shizuku не запущен или нет разрешения")
            refresh()
            return
        }
        Engine.note = ""
        SoundpadService.poke(this)
        Shell.withService(this) { inj ->
            Thread {
                val r = Engine.startInject(inj)
                runOnUiThread {
                    say(if (r.startsWith("ok")) "" else r)
                    refresh()
                    SoundpadService.poke(this)
                }
            }.start()
        }
    }

    private fun stopInject() {
        Engine.stopInject()
        SoundpadService.poke(this)
        refresh()
    }

    private fun pick() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(i, 2)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 2 || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        data.data?.let { uris.add(it) }
        data.clipData?.let { c -> for (i in 0 until c.itemCount) uris.add(c.getItemAt(i).uri) }
        Thread {
            for (u in uris) importOne(u)
            Engine.preload()
            runOnUiThread { rebuildGrid() }
        }.start()
    }

    private fun importOne(u: Uri) {
        var name = "sound"
        contentResolver.query(u, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) name = c.getString(i) ?: name
        }
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0) name.substring(dot) else ""
        val title = if (dot > 0) name.substring(0, dot) else name
        val file = UUID.randomUUID().toString() + ext
        try {
            contentResolver.openInputStream(u)?.use { ins ->
                File(Store.dir, file).outputStream().use { ins.copyTo(it) }
            }
            Store.add(title, file)
        } catch (e: Exception) {
            Engine.note = "не удалось импортировать $name"
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        refresh()
    }

    private fun rebuildGrid() {
        grid.removeAllViews()
        val w = (resources.displayMetrics.widthPixels - dp(32)) / 3 - dp(6)
        for (p in Store.pads) {
            grid.addView(Button(this).apply {
                text = p.name
                isAllCaps = false
                maxLines = 2
                textSize = 12f
                layoutParams = GridLayout.LayoutParams().apply {
                    width = w
                    height = dp(72)
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                }
                setOnClickListener { Engine.trigger(p) }
                setOnLongClickListener {
                    edit(p)
                    true
                }
            })
        }
    }

    private fun edit(p: Pad) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
        }
        val name = EditText(this).apply { setText(p.name) }
        val vol = SeekBar(this).apply {
            max = 200
            progress = (p.volume * 100).toInt()
        }
        val loop = CheckBox(this).apply {
            text = "Зациклить"
            isChecked = p.loop
        }
        box.addView(name)
        box.addView(TextView(this).apply { text = "Громкость" })
        box.addView(vol)
        box.addView(loop)
        AlertDialog.Builder(this)
            .setView(box)
            .setPositiveButton("Сохранить") { _, _ ->
                p.name = name.text.toString().ifBlank { p.name }
                p.volume = vol.progress / 100f
                p.loop = loop.isChecked
                Store.save()
                rebuildGrid()
            }
            .setNeutralButton("Удалить") { _, _ ->
                Engine.forget(p)
                Store.remove(p)
                rebuildGrid()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
