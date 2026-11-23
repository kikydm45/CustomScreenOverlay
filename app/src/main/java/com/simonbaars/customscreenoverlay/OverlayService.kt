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
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject

class OverlayService : Service() {

    private class Item(val id: Long, val text: String, var x: Int, var y: Int, var size: Float)

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private val items = mutableListOf<Item>()
    private val views = mutableMapOf<Long, TextView>()

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("overlay_items", MODE_PRIVATE)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Overlay Service", NotificationManager.IMPORTANCE_LOW)
        )
        val arr = JSONArray(prefs.getString("items", "[]"))
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            items.add(Item(o.getLong("id"), o.getString("text"), o.getInt("x"), o.getInt("y"), o.getDouble("size").toFloat()))
        }
        items.forEach { show(it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification())
        when (intent?.action) {
            "ADD" -> {
                val t = intent.getStringExtra("text")?.trim().orEmpty()
                if (t.isNotEmpty()) {
                    val m = resources.displayMetrics
                    val n = items.size % 5
                    val item = Item(
                        System.currentTimeMillis(), t,
                        m.widthPixels / 6 + n * 60, m.heightPixels / 4 + n * 120, 24f
                    )
                    items.add(item)
                    show(item)
                    save()
                }
            }
            "REMOVE" -> remove(intent.getLongExtra("id", -1L))
            "STOP" -> {
                views.values.forEach { wm.removeView(it) }
                views.clear()
                items.clear()
                save()
            }
        }
        if (items.isEmpty()) stopSelf()
        return START_STICKY
    }

    private fun show(item: Item) {
        val tv = TextView(this).apply {
            text = item.text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, item.size)
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setShadowLayer(8f, 0f, 0f, Color.BLACK)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = item.x
            y = item.y
        }
        tv.setOnTouchListener(touch(item, tv, lp))
        wm.addView(tv, lp)
        views[item.id] = tv
    }

    private fun touch(item: Item, tv: TextView, lp: WindowManager.LayoutParams): View.OnTouchListener {
        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                item.size = (item.size * d.scaleFactor).coerceIn(8f, 300f)
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, item.size)
                return true
            }
        })
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                tv.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                remove(item.id)
            }
        })
        return View.OnTouchListener { _, e ->
            gd.onTouchEvent(e)
            scale.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y
                }
                MotionEvent.ACTION_MOVE -> if (e.pointerCount == 1 && views.containsKey(item.id)) {
                    lp.x = ox + (e.rawX - sx).toInt()
                    lp.y = oy + (e.rawY - sy).toInt()
                    wm.updateViewLayout(tv, lp)
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val i = if (e.actionIndex == 0) 1 else 0
                    sx = e.getRawX(i); sy = e.getRawY(i); ox = lp.x; oy = lp.y
                }
                MotionEvent.ACTION_UP -> {
                    item.x = lp.x; item.y = lp.y
                    save()
                }
            }
            true
        }
    }

    private fun remove(id: Long) {
        views.remove(id)?.let { wm.removeView(it) }
        items.removeAll { it.id == id }
        save()
        if (items.isEmpty()) stopSelf()
    }

    private fun save() {
        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("text", it.text)
                    .put("x", it.x).put("y", it.y).put("size", it.size.toDouble())
            )
        }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        views.values.forEach { wm.removeView(it) }
        views.clear()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(getString(R.string.overlay_service_notification))
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentIntent(
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        )
        .build()

    companion object {
        private const val CHANNEL_ID = "overlay_service_channel"
        private const val NOTIFICATION_ID = 1
    }
}
