package com.example.datausage

import android.app.Activity
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.BatteryManager
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap

const val NS = "com.example.datausage"
val PRESET_LABELS = listOf("📶 データ通信量(標準)", "⇅ 通信モニター", "◯ Data Ring", "⚡ Bolt")
val WifiColor = Color(0xFF4FC3F7)
val MobileColor = Color(0xFFFFB74D)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8FA8FF),
    background = Color(0xFF0E1117),
    surface = Color(0xFF171B24),
    onBackground = Color(0xFFE8EAF0),
    onSurface = Color(0xFFE8EAF0),
    onSurfaceVariant = Color(0xFF9AA3B5),
    surfaceVariant = Color(0xFF262C38)
)
private val LightScheme = lightColorScheme(
    primary = Color(0xFF4A63E7),
    background = Color(0xFFF3F5FB),
    surface = Color.White,
    onBackground = Color(0xFF14171F),
    onSurface = Color(0xFF14171F),
    onSurfaceVariant = Color(0xFF6B7385),
    surfaceVariant = Color(0xFFE4E8F2)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyRefreshRate(this, getSharedPreferences("s", 0).getBoolean("hz", true))
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Root() }
            }
        }
    }
}

@Suppress("DEPRECATION")
fun applyRefreshRate(act: Activity, enabled: Boolean) {
    val d = act.windowManager.defaultDisplay
    val cur = d.mode
    val best = d.supportedModes
        .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
        .maxByOrNull { it.refreshRate } ?: return
    val lp = act.window.attributes
    lp.preferredDisplayModeId = if (enabled) best.modeId else 0
    lp.preferredRefreshRate = if (enabled) best.refreshRate else 0f
    act.window.attributes = lp
}

fun setLauncherPreset(ctx: Context, sel: Int) {
    val pm = ctx.packageManager
    fun cn(i: Int) = ComponentName(ctx.packageName, "$NS.Alias${i + 1}")
    pm.setComponentEnabledSetting(cn(sel), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
    PRESET_LABELS.indices.filter { it != sel }.forEach {
        pm.setComponentEnabledSetting(cn(it), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
    }
}

fun pinShortcut(ctx: Context, name: String, emoji: String, bg: Int): Boolean {
    val sm = ctx.getSystemService(ShortcutManager::class.java)
    if (!sm.isRequestPinShortcutSupported) return false
    val bmp = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = bg
    c.drawRoundRect(0f, 0f, 192f, 192f, 48f, 48f, p)
    p.color = 0xFFFFFFFF.toInt()
    p.textSize = 104f
    p.textAlign = Paint.Align.CENTER
    val y = 96f - (p.descent() + p.ascent()) / 2
    c.drawText(emoji.ifBlank { "📶" }, 96f, y, p)
    val intent = Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
    val info = ShortcutInfo.Builder(ctx, "c${System.currentTimeMillis()}")
        .setShortLabel(name.ifBlank { "通信量" })
        .setIcon(android.graphics.drawable.Icon.createWithBitmap(bmp))
        .setIntent(intent)
        .build()
    return sm.requestPinShortcut(info, null)
}

data class AppUsage(
    val label: String, val pkg: String,
    val wifi: Long, val mobile: Long, val fgMs: Long,
    val icon: ImageBitmap?
) {
    val total get() = wifi + mobile
}

data class DayTotal(val label: String, val wifi: Long, val mobile: Long)
data class Loaded(val apps: List<AppUsage> = emptyList(), val days: List<DayTotal> = emptyList())
data class Batt(val pct: Int, val charging: Boolean, val tempC: Float)

fun readBattery(ctx: Context): Batt {
    val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val lv = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val sc = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val t = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
    return Batt(
        if (lv < 0) 0 else lv * 100 / sc,
        st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL,
        t / 10f
    )
}

fun hasUsageAccess(ctx: Context): Boolean {
    val ops = ctx.getSystemService(AppOpsManager::class.java)
    return ops.unsafeCheckOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
    ) == AppOpsManager.MODE_ALLOWED
}

fun fmt(b: Long): String = when {
    b >= 1L shl 30 -> "%.2f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}

fun fmtTime(ms: Long): String {
    val m = ms / 60000
    return when {
        m >= 60 -> "${m / 60}時間${m % 60}分"
        m >= 1 -> "${m}分"
        ms > 0 -> "1分未満"
        else -> "-"
    }
}
// END