package com.simonbaars.customscreenoverlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class OverlayService : Service() {

    private class Item(
        val id: Long, var text: String, var cx: Int, var cy: Int, var size: Float, var rot: Float,
        var color: Int, var font: Int, var effect: Int, var hidden: Boolean
    )

    private class V(val root: FrameLayout, val tv: TextView, val lp: WindowManager.LayoutParams)

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private val nm by lazy { getSystemService(NotificationManager::class.java) }
    private val items = mutableListOf<Item>()
    private val views = mutableMapOf<Long, V>()

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Teks melayang", NotificationManager.IMPORTANCE_LOW)
        )
        val arr = JSONArray(prefs.getString("items", "[]"))
        for (n in 0 until arr.length()) {
            val o = arr.getJSONObject(n)
            items.add(
                Item(
                    o.optLong("id"), o.optString("text"), o.optInt("cx", 300), o.optInt("cy", 400),
                    o.optDouble("size", 28.0).toFloat(), o.optDouble("rot", 0.0).toFloat(),
                    o.optInt("color", Color.WHITE), o.optInt("font", 0), o.optInt("effect", 1),
                    o.optBoolean("hidden", false)
                )
            )
        }
        items.forEach { show(it); notifyItem(it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, summary())
        if (intent != null) handle(intent)
        if (items.isEmpty()) stopSelf() else nm.notify(NOTIFICATION_ID, summary())
        return START_STICKY
    }

    private fun find(i: Intent) = items.find { it.id == i.getLongExtra("id", -1L) }

    private fun fill(item: Item, i: Intent) {
        i.getStringExtra("text")?.trim()?.takeIf { it.isNotEmpty() }?.let { item.text = it }
        item.font = i.getIntExtra("font", item.font).coerceIn(0, FONTS.size - 1)
        item.effect = i.getIntExtra("effect", item.effect).coerceIn(0, EFFECT_LABELS.size - 1)
        item.color = i.getIntExtra("color", item.color)
    }

    private fun handle(i: Intent) {
        when (i.action) {
            "ADD" -> {
                val t = i.getStringExtra("text")?.trim().orEmpty()
                if (t.isNotEmpty()) {
                    val m = resources.displayMetrics
                    val n = items.size % 6
                    val item = Item(
                        System.currentTimeMillis(), t, m.widthPixels / 2, m.heightPixels / 5 + n * 160,
                        28f, 0f, Color.WHITE, 0, 1, false
                    )
                    fill(item, i)
                    items.add(item)
                    show(item)
                    notifyItem(item)
                    save()
                }
            }
            "UPDATE" -> find(i)?.let { fill(it, i); relayout(it); notifyItem(it); save() }
            "TOGGLE" -> find(i)?.let {
                it.hidden = !it.hidden
                if (it.hidden) hide(it) else show(it)
                notifyItem(it)
                save()
            }
            "REMOVE" -> find(i)?.let {
                hide(it)
                nm.cancel(nid(it))
                items.remove(it)
                save()
            }
            "HIDE_ALL", "SHOW_ALL" -> {
                val h = i.action == "HIDE_ALL"
                items.forEach {
                    it.hidden = h
                    if (h) hide(it) else show(it)
                    notifyItem(it)
                }
                save()
            }
            "STOP" -> {
                items.forEach { hide(it); nm.cancel(nid(it)) }
                items.clear()
                save()
            }
        }
    }

    private fun style(item: Item, tv: TextView) {
        val p = (24 * resources.displayMetrics.density).toInt()
        tv.text = item.text
        tv.setPadding(p, p, p, p)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, item.size)
        tv.typeface = Typeface.create(FONTS[item.font], if (item.font == 0) Typeface.BOLD else Typeface.NORMAL)
        tv.setTextColor(item.color)
        when (item.effect) {
            0 -> tv.setShadowLayer(0f, 0f, 0f, 0)
            1 -> tv.setShadowLayer(8f, 3f, 3f, Color.BLACK)
            2 -> tv.setShadowLayer(24f, 0f, 0f, item.color)
            3 -> {
                tv.setTextColor(Color.WHITE)
                tv.setShadowLayer(30f, 0f, 0f, item.color)
            }
            else -> tv.setShadowLayer(1f, 6f, 6f, 0xAA000000.toInt())
        }
    }

    // Ukuran jendela = kotak pembatas teks yang sudah diputar, posisi berpusat di (cx, cy)
    private fun fit(item: Item, v: V) {
        v.tv.measure(0, 0)
        val w = v.tv.measuredWidth
        val h = v.tv.measuredHeight
        val r = Math.toRadians(item.rot.toDouble())
        val c = abs(cos(r))
        val s = abs(sin(r))
        v.lp.width = (w * c + h * s).toInt() + 2
        v.lp.height = (w * s + h * c).toInt() + 2
        v.lp.x = item.cx - v.lp.width / 2
        v.lp.y = item.cy - v.lp.height / 2
        (v.tv.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.width = w
            it.height = h
            v.tv.layoutParams = it
        }
        v.tv.rotation = item.rot
    }

    private fun show(item: Item) {
        if (item.hidden || views.containsKey(item.id)) return
        val tv = TextView(this)
        val root = FrameLayout(this)
        root.addView(tv, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        val lp = WindowManager.LayoutParams(
            -2, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        val v = V(root, tv, lp)
        style(item, tv)
        fit(item, v)
        root.setOnTouchListener(touch(item))
        wm.addView(root, lp)
        views[item.id] = v
    }

    private fun hide(item: Item) {
        views.remove(item.id)?.let { wm.removeView(it.root) }
    }

    private fun relayout(item: Item) {
        views[item.id]?.let {
            style(item, it.tv)
            fit(item, it)
            wm.updateViewLayout(it.root, it.lp)
        }
    }

    private fun dist(e: MotionEvent) =
        hypot(e.getRawX(1) - e.getRawX(0), e.getRawY(1) - e.getRawY(0))

    private fun ang(e: MotionEvent) =
        Math.toDegrees(atan2(e.getRawY(1) - e.getRawY(0), e.getRawX(1) - e.getRawX(0)).toDouble()).toFloat()

    private fun touch(item: Item): View.OnTouchListener {
        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        var d0 = 0f
        var a0 = 0f
        return View.OnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; ox = item.cx; oy = item.cy
                }
                MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                    d0 = dist(e); a0 = ang(e)
                }
                MotionEvent.ACTION_MOVE -> if (views.containsKey(item.id)) {
                    if (e.pointerCount >= 2) {
                        val d = dist(e)
                        val a = ang(e)
                        if (d0 > 0f) item.size = (item.size * d / d0).coerceIn(8f, 400f)
                        var da = a - a0
                        while (da > 180f) da -= 360f
                        while (da < -180f) da += 360f
                        item.rot += da
                        d0 = d; a0 = a
                    } else {
                        item.cx = ox + (e.rawX - sx).toInt()
                        item.cy = oy + (e.rawY - sy).toInt()
                    }
                    relayout(item)
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val k = if (e.actionIndex == 0) 1 else 0
                    sx = e.getRawX(k); sy = e.getRawY(k); ox = item.cx; oy = item.cy
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> save()
            }
            true
        }
    }

    private fun save() {
        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("text", it.text).put("cx", it.cx).put("cy", it.cy)
                    .put("size", it.size.toDouble()).put("rot", it.rot.toDouble()).put("color", it.color)
                    .put("font", it.font).put("effect", it.effect).put("hidden", it.hidden)
            )
        }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    private fun nid(item: Item) = (item.id % 1_000_000_000L).toInt() + 100

    private fun act(action: String, id: Long = -1L): PendingIntent = PendingIntent.getService(
        this, "$action$id".hashCode(),
        Intent(this, OverlayService::class.java).setAction(action).putExtra("id", id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun notifyItem(item: Item) {
        nm.notify(
            nid(item),
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(item.text)
                .setContentText(if (item.hidden) "Tersembunyi" else "Tampil di layar")
                .setGroup(GROUP)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(0, if (item.hidden) "Tampilkan" else "Sembunyikan", act("TOGGLE", item.id))
                .addAction(0, "Hapus", act("REMOVE", item.id))
                .build()
        )
    }

    private fun summary() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(getString(R.string.app_name))
        .setContentText("${items.size} teks")
        .setGroup(GROUP)
        .setGroupSummary(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        )
        .addAction(0, "Sembunyi semua", act("HIDE_ALL"))
        .addAction(0, "Tampil semua", act("SHOW_ALL"))
        .addAction(0, "Hapus semua", act("STOP"))
        .build()

    override fun onDestroy() {
        super.onDestroy()
        views.values.forEach { wm.removeView(it.root) }
        views.clear()
        items.forEach { nm.cancel(nid(it)) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val PREFS = "overlay_v2"
        val FONTS = arrayOf(
            "sans-serif", "sans-serif-black", "sans-serif-condensed", "sans-serif-light", "serif",
            "monospace", "cursive", "casual", "serif-monospace", "sans-serif-medium"
        )
        val FONT_LABELS = arrayOf(
            "Standar tebal", "Black", "Ramping", "Tipis", "Serif",
            "Monospace", "Tulisan tangan", "Casual", "Mesin ketik", "Medium"
        )
        val EFFECT_LABELS = arrayOf("Tanpa efek", "Bayangan", "Glow", "Neon", "3D")
        private const val CHANNEL_ID = "overlay_service_channel"
        private const val GROUP = "teks_melayang"
        private const val NOTIFICATION_ID = 1
    }
}
