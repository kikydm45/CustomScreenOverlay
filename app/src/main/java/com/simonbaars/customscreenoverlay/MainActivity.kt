package com.simonbaars.customscreenoverlay

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var list: LinearLayout
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("overlay_items", MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val input = EditText(this).apply { hint = "Tulis teks di sini" }
        val addBtn = Button(this).apply {
            text = "Tampilkan"
            setOnClickListener {
                val t = input.text.toString().trim()
                if (t.isEmpty()) return@setOnClickListener
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "Izinkan 'Tampilkan di atas aplikasi lain' dulu", Toast.LENGTH_LONG).show()
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                    return@setOnClickListener
                }
                send("ADD") { putExtra("text", t) }
                input.text.clear()
            }
        }
        val clearBtn = Button(this).apply {
            text = "Hapus semua"
            setOnClickListener { send("STOP") }
        }
        val info = TextView(this).apply {
            text = "Seret teks untuk memindah, cubit dua jari untuk memperbesar/mengecilkan, tekan lama teks untuk menghapusnya."
            setPadding(0, pad, 0, pad)
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(input)
        root.addView(addBtn)
        root.addView(clearBtn)
        root.addView(info)
        root.addView(list)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun send(action: String, extra: Intent.() -> Unit = {}) {
        startForegroundService(Intent(this, OverlayService::class.java).setAction(action).apply(extra))
    }

    private fun refresh() {
        list.removeAllViews()
        val arr = JSONArray(prefs.getString("items", "[]"))
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.getLong("id")
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply {
                text = o.getString("text")
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(this).apply {
                text = "Hapus"
                setOnClickListener { send("REMOVE") { putExtra("id", id) } }
            })
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
