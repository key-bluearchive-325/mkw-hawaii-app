package com.example.datausage

import android.app.Activity
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private fun sumByUid(nsm: NetworkStatsManager, type: Int, start: Long, end: Long): Map<Int, Long> {
    val map = HashMap<Int, Long>()
    val stats = nsm.querySummary(type, null, start, end)
    val bucket = NetworkStats.Bucket()
    while (stats.hasNextBucket()) {
        stats.getNextBucket(bucket)
        map[bucket.uid] = (map[bucket.uid] ?: 0L) + bucket.rxBytes + bucket.txBytes
    }
    stats.close()
    return map
}

private fun deviceTotal(nsm: NetworkStatsManager, type: Int, s: Long, e: Long): Long =
    runCatching {
        val b = nsm.querySummaryForDevice(type, null, s, e)
        b.rxBytes + b.txBytes
    }.getOrDefault(0L)

private val iconCache = HashMap<String, ImageBitmap?>()

private fun iconOf(ctx: Context, pkg: String): ImageBitmap? = synchronized(iconCache) {
    iconCache.getOrPut(pkg) {
        runCatching {
            ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap()
        }.getOrNull()
    }
}

/** mode: 0=日, 1=週(月曜始まり), 2=月 / offset: 0=今, -1=ひとつ前 */
fun rangeOf(mode: Int, offset: Int): Pair<Long, Long> {
    val c = Calendar.getInstance().apply {
        firstDayOfWeek = Calendar.MONDAY
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    when (mode) {
        0 -> c.add(Calendar.DAY_OF_YEAR, offset)
        1 -> { c.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY); c.add(Calendar.WEEK_OF_YEAR, offset) }
        else -> { c.set(Calendar.DAY_OF_MONTH, 1); c.add(Calendar.MONTH, offset) }
    }
    val s = c.timeInMillis
    when (mode) {
        0 -> c.add(Calendar.DAY_OF_YEAR, 1)
        1 -> c.add(Calendar.WEEK_OF_YEAR, 1)
        else -> c.add(Calendar.MONTH, 1)
    }
    return s to minOf(c.timeInMillis, System.currentTimeMillis())
}

fun periodLabel(mode: Int, offset: Int): String {
    val c = Calendar.getInstance().apply { timeInMillis = rangeOf(mode, offset).first }
    return when (mode) {
        0 -> SimpleDateFormat("M月d日(E)", Locale.JAPAN).format(c.time)
        1 -> {
            val f = SimpleDateFormat("M/d", Locale.JAPAN)
            val st = f.format(c.time)
            c.add(Calendar.DAY_OF_YEAR, 6)
            "$st 〜 ${f.format(c.time)}"
        }
        else -> SimpleDateFormat("yyyy年M月", Locale.JAPAN).format(c.time)
    }
}

fun loadAll(ctx: Context, mode: Int, offset: Int): Loaded {
    val (start, end) = rangeOf(mode, offset)
    val nsm = ctx.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
    val wifi = sumByUid(nsm, ConnectivityManager.TYPE_WIFI, start, end)
    val mobile = sumByUid(nsm, ConnectivityManager.TYPE_MOBILE, start, end)
    val pm = ctx.packageManager

    // [0]=wifi, [1]=mobile, [2]=画面使用時間(ms)
    val byPkg = HashMap<String, LongArray>()
    fun keyOf(uid: Int) = pm.getPackagesForUid(uid)?.firstOrNull() ?: "uid:$uid"
    wifi.forEach { (uid, v) -> byPkg.getOrPut(keyOf(uid)) { LongArray(3) }[0] += v }
    mobile.forEach { (uid, v) -> byPkg.getOrPut(keyOf(uid)) { LongArray(3) }[1] += v }

    val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    usm.queryAndAggregateUsageStats(start, end).forEach { (p, s) ->
        if (s.totalTimeInForeground > 0) byPkg.getOrPut(p) { LongArray(3) }[2] = s.totalTimeInForeground
    }

    val apps = byPkg.mapNotNull { (pkg, a) ->
        if (a.all { it == 0L }) return@mapNotNull null
        val label = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull() ?: if (pkg.startsWith("uid:")) "システム/その他 ($pkg)" else pkg
        AppUsage(label, pkg, a[0], a[1], a[2], iconOf(ctx, pkg))
    }

    val days = if (mode == 0) emptyList() else {
        val out = ArrayList<DayTotal>()
        val c = Calendar.getInstance().apply { timeInMillis = start }
        val wf = SimpleDateFormat("E", Locale.JAPAN)
        while (c.timeInMillis < end) {
            val s = c.timeInMillis
            val label = if (mode == 1) wf.format(Date(s)) else c.get(Calendar.DAY_OF_MONTH).toString()
            c.add(Calendar.DAY_OF_YEAR, 1)
            val e = minOf(c.timeInMillis, end)
            out += DayTotal(
                label,
                deviceTotal(nsm, ConnectivityManager.TYPE_WIFI, s, e),
                deviceTotal(nsm, ConnectivityManager.TYPE_MOBILE, s, e)
            )
        }
        out
    }
    return Loaded(apps, days)
}

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(20.dp), color = cs.surface, tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = cs.onSurface)
            content()
        }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val prefs = remember { ctx.getSharedPreferences("s", 0) }
    var hz by remember { mutableStateOf(prefs.getBoolean("hz", true)) }
    var preset by remember { mutableIntStateOf(prefs.getInt("preset", 0)) }
    var name by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf("📶") }
    var colorIdx by remember { mutableIntStateOf(0) }
    val colors = listOf(0xFF5B6CFFL, 0xFFE5484DL, 0xFF30A46CL, 0xFFF5A524L, 0xFF111111L)
    BackHandler { onBack() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("◀ 戻る") }
                Spacer(Modifier.width(8.dp))
                Text("設定", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = cs.onBackground)
            }
        }
        item {
            Section("表示") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("高リフレッシュレート（最大120Hz）", color = cs.onSurface)
                        Text(
                            "対応端末でスクロールが滑らかになります。バッテリー消費は少し増えます。",
                            fontSize = 12.sp, color = cs.onSurfaceVariant
                        )
                    }
                    Switch(checked = hz, onCheckedChange = {
                        hz = it
                        prefs.edit().putBoolean("hz", it).apply()
                        (ctx as? Activity)?.let { a -> applyRefreshRate(a, it) }
                    })
                }
            }
        }
        item {
            Section("ホーム画面のアイコンと名前（プリセット）") {
                PRESET_LABELS.forEachIndexed { i, label ->
                    val select = {
                        preset = i
                        prefs.edit().putInt("preset", i).apply()
                        setLauncherPreset(ctx, i)
                        Toast.makeText(ctx, "反映まで数秒〜数十秒かかります", Toast.LENGTH_SHORT).show()
                    }
                    Row(Modifier.fillMaxWidth().clickable { select() }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = preset == i, onClick = { select() })
                        Text(label, color = cs.onSurface)
                    }
                }
            }
        }
        item {
            Section("好きな名前・アイコンでホーム画面に追加") {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("アプリ名") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = emoji, onValueChange = { emoji = it.take(4) },
                    label = { Text("アイコンの絵文字") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    colors.forEachIndexed { i, c ->
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(Color(c))
                                .then(if (colorIdx == i) Modifier.border(3.dp, cs.onSurface, CircleShape) else Modifier)
                                .clickable { colorIdx = i }
                        )
                    }
                }
                Button(onClick = {
                    val ok = pinShortcut(ctx, name, emoji, Color(colors[colorIdx]).toArgb())
                    Toast.makeText(ctx, if (ok) "追加の確認画面で許可してください" else "このホーム画面は非対応です", Toast.LENGTH_SHORT).show()
                }) { Text("ホーム画面に追加") }
            }
        }
        item {
            Section("パッケージ名") {
                Text(
                    "アプリ内からは変更できません。GitHub の Actions → Build APK → Run workflow で、アプリ名とパッケージ名を入力してビルドし直してください（別のアプリとしてインストールされます）。",
                    fontSize = 13.sp, color = cs.onSurfaceVariant
                )
            }
        }
    }
}
// END