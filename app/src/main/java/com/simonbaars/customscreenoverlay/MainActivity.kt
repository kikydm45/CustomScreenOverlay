package com.simonbaars.customscreenoverlay

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var list: LinearLayout
    private lateinit var input: EditText
    private lateinit var fontSp: Spinner
    private lateinit var effSp: Spinner
    private lateinit var colorRow: LinearLayout
    private lateinit var mainBtn: Button
    private var color = Color.WHITE
    private var editId = -1L
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

    private val palette = intArrayOf(
        Color.WHITE, Color.BLACK, 0xFFFF5252.toInt(), 0xFFFFAB40.toInt(), 0xFFFFEB3B.toInt(),
        0xFF69F0AE.toInt(), 0xFF00E5CC.toInt(), 0xFF448AFF.toInt(), 0xFFB388FF.toInt(), 0xFFFF80AB.toInt()
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(t: String) = TextView(this).apply {
        text = t
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun btn(t: String, f: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { f() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        input = EditText(this).apply { hint = "Tulis teks di sini" }
        fontSp = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, OverlayService.FONT_LABELS)
        }
        effSp = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, OverlayService.EFFECT_LABELS)
        }
        colorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        mainBtn = btn("Tampilkan") { submit() }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bulk = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(
                btn("Sembunyi semua") { send("HIDE_ALL") },
                btn("Tampil semua") { send("SHOW_ALL") },
                btn("Hapus semua") { send("STOP") }
            ).forEach {
                it.textSize = 11f
                addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }

        root.addView(input)
        root.addView(label("Font"))
        root.addView(fontSp)
        root.addView(label("Efek"))
        root.addView(effSp)
        root.addView(label("Warna"))
        root.addView(HorizontalScrollView(this).apply { addView(colorRow) })
        root.addView(mainBtn)
        root.addView(btn("Form baru") { resetForm() })
        root.addView(label("Semua teks"))
        root.addView(bulk)
        root.addView(TextView(this).apply {
            text = "Seret = pindah. Cubit/putar dua jari = ukuran & rotasi. Kontrol sembunyi/hapus juga ada di panel notifikasi. Ketuk teks di daftar untuk mengedit."
            textSize = 12f
            setPadding(0, dp(8), 0, dp(8))
        })
        root.addView(list)
        setContentView(ScrollView(this).apply { addView(root) })
        drawColors()
    }

    private fun drawColors() {
        colorRow.removeAllViews()
        for (c in palette) {
            val sel = c == color
            colorRow.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    setStroke(dp(if (sel) 4 else 1), if (sel) 0xFF888888.toInt() else 0x55888888)
                }
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
                setOnClickListener { color = c; drawColors() }
            })
        }
    }

    private fun send(action: String, extra: Intent.() -> Unit = {}) {
        startForegroundService(Intent(this, OverlayService::class.java).setAction(action).apply(extra))
    }

    private fun fillExtras(i: Intent, t: String) {
        i.putExtra("text", t)
            .putExtra("font", fontSp.selectedItemPosition)
            .putExtra("effect", effSp.selectedItemPosition)
            .putExtra("color", color)
    }

    private fun submit() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Izinkan 'Tampilkan di atas aplikasi lain' dulu", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val id = editId
        if (id >= 0) send("UPDATE") { putExtra("id", id); fillExtras(this, t) }
        else send("ADD") { fillExtras(this, t) }
        resetForm()
    }

    private fun resetForm() {
        editId = -1L
        input.text.clear()
        mainBtn.text = "Tampilkan"
    }

    private fun loadForm(o: JSONObject) {
        editId = o.optLong("id")
        input.setText(o.optString("text"))
        fontSp.setSelection(o.optInt("font"))
        effSp.setSelection(o.optInt("effect"))
        color = o.optInt("color", Color.WHITE)
        drawColors()
        mainBtn.text = "Perbarui teks"
    }

    private fun refresh() {
        list.removeAllViews()
        val arr = JSONArray(prefs.getString("items", "[]"))
        for (n in 0 until arr.length()) {
            val o = arr.getJSONObject(n)
            val id = o.optLong("id")
            val hidden = o.optBoolean("hidden")
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply {
                text = o.optString("text") + if (hidden) "  (tersembunyi)" else ""
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { loadForm(o) }
            })
            row.addView(btn(if (hidden) "Tampil" else "Sembunyi") { send("TOGGLE") { putExtra("id", id) } }.apply { textSize = 11f })
            row.addView(btn("Hapus") { send("REMOVE") { putExtra("id", id) } }.apply { textSize = 11f })
            list.addView(row)
        }
    }

    override fun onStart() {
        super.onStart()
        prefs.registerOnSharedPreferenceChangeListener(listener)
        refresh()
    }

    override fun onStop() {
        super.onStop()
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }
}
