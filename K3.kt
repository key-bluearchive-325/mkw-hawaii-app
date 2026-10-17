package com.example.datausage

import android.content.Intent
import android.net.TrafficStats
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun Root() {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasUsageAccess(ctx)) }
    LaunchedEffect(granted) {
        while (!granted) { delay(1000); granted = hasUsageAccess(ctx) }
    }
    if (granted) UsageScreen() else {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("📶", fontSize = 56.sp)
            Spacer(Modifier.height(16.dp))
            Text("使用状況へのアクセスを許可してください", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "通信量と画面使用時間の取得に必要です。設定でこのアプリをオンにしてください。",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = {
                ctx.startActivity(
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }) { Text("設定を開く") }
        }
    }
}

@Composable
fun UsageScreen() {
    val ctx = LocalContext.current
    var mode by remember { mutableIntStateOf(0) }
    var offset by remember { mutableIntStateOf(0) }
    var sortBy by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    var data by remember { mutableStateOf(Loaded()) }
    var speed by remember { mutableStateOf(0L to 0L) }
    var batt by remember { mutableStateOf(readBattery(ctx)) }

    BackHandler(enabled = showSettings) { showSettings = false }

    LaunchedEffect(mode, offset) {
        while (true) {
            withContext(Dispatchers.IO) { runCatching { loadAll(ctx, mode, offset) }.getOrNull() }
                ?.let { data = it }
            batt = readBattery(ctx)
            if (offset < 0) break
            delay(3000)
        }
    }
    LaunchedEffect(Unit) {
        var rx = TrafficStats.getTotalRxBytes()
        var tx = TrafficStats.getTotalTxBytes()
        while (true) {
            delay(1000)
            val r = TrafficStats.getTotalRxBytes()
            val t = TrafficStats.getTotalTxBytes()
            speed = (r - rx) to (t - tx)
            rx = r; tx = t
        }
    }

    if (showSettings) { SettingsScreen { showSettings = false }; return }

    val sorted = if (sortBy == 0) data.apps.sortedByDescending { it.total }
    else data.apps.sortedByDescending { it.fgMs }
    val max = (data.apps.maxOfOrNull { it.total } ?: 1L).coerceAtLeast(1L).toFloat()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Header(speed, data.apps, batt) { showSettings = true } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("日", "週", "月").forEachIndexed { i, s ->
                    FilterChip(
                        selected = mode == i,
                        onClick = { mode = i; offset = 0 },
                        label = { Text(s) }, shape = RoundedCornerShape(50)
                    )
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { offset-- }) { Text("◀") }
                Text(
                    periodLabel(mode, offset), Modifier.weight(1f),
                    textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold, fontSize = 16.sp
                )
                TextButton(onClick = { offset++ }, enabled = offset < 0) { Text("▶") }
            }
        }
        if (mode > 0 && data.days.isNotEmpty()) item { DayChart(data.days, mode) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("アプリ別", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                FilterChip(
                    selected = sortBy == 1,
                    onClick = { sortBy = if (sortBy == 0) 1 else 0 },
                    label = { Text(if (sortBy == 0) "通信量順" else "使用時間順") },
                    shape = RoundedCornerShape(50)
                )
            }
        }
        if (sorted.isEmpty()) item {
            Text(
                "この期間のデータがありません（OSが保持している期間より前の可能性があります）",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp
            )
        }
        items(sorted, key = { it.pkg }) { a -> AppRow(a, max) }
    }
}

@Composable
fun Stat(label: String, value: String) {
    Column {
        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
        Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun Header(speed: Pair<Long, Long>, list: List<AppUsage>, batt: Batt, onSettings: () -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF5B6CFF), Color(0xFF9B5CFF))))
            .padding(20.dp)
    ) {
        Text(
            "⚙", fontSize = 22.sp, color = Color.White,
            modifier = Modifier.align(Alignment.TopEnd).clickable { onSettings() }.padding(4.dp)
        )
        Column {
            Text("リアルタイム速度", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
            Text("↓ ${fmt(speed.first)}/s", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("↑ ${fmt(speed.second)}/s", color = Color.White.copy(alpha = 0.9f), fontSize = 16.sp)
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("合計", fmt(list.sumOf { it.total }))
                Stat("Wi-Fi", fmt(list.sumOf { it.wifi }))
                Stat("モバイル", fmt(list.sumOf { it.mobile }))
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "🔋 ${batt.pct}%" + (if (batt.charging) " ⚡充電中" else "") + "  ・  🌡 ${"%.1f".format(batt.tempC)}°C",
                color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp
            )
        }
    }
}

@Composable
fun DayChart(days: List<DayTotal>, mode: Int) {
    val cs = MaterialTheme.colorScheme
    val max = (days.maxOfOrNull { it.wifi + it.mobile } ?: 1L).coerceAtLeast(1L).toFloat()
    Surface(shape = RoundedCornerShape(20.dp), color = cs.surface, tonalElevation = 1.dp) {
        Column(Modifier.padding(14.dp)) {
            Text("日ごとの通信量（最大 ${fmt(max.toLong())}）", fontSize = 12.sp, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().height(90.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                days.forEach { d ->
                    val t = d.wifi + d.mobile
                    val h = maxOf(2f, 90f * t / max).dp
                    Box(Modifier.weight(1f).height(h).clip(RoundedCornerShape(3.dp)).background(cs.surfaceVariant)) {
                        if (t > 0) Column(Modifier.fillMaxSize()) {
                            if (d.mobile > 0) Box(Modifier.weight(d.mobile.toFloat()).fillMaxWidth().background(MobileColor))
                            if (d.wifi > 0) Box(Modifier.weight(d.wifi.toFloat()).fillMaxWidth().background(WifiColor))
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                days.forEachIndexed { i, d ->
                    Text(
                        if (mode == 1 || i % 5 == 0) d.label else "",
                        Modifier.weight(1f), textAlign = TextAlign.Center,
                        fontSize = 9.sp, color = cs.onSurfaceVariant, maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
fun AppRow(a: AppUsage, max: Float) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(20.dp), color = cs.surface, tonalElevation = 1.dp) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (a.icon != null) {
                Image(a.icon, null, Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)))
            } else {
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(cs.surfaceVariant))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        a.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = cs.onSurface
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(fmt(a.total), fontWeight = FontWeight.Bold, fontSize = 15.sp, color = cs.primary)
                }
                Text(a.pkg, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(cs.surfaceVariant)) {
                    val f = (a.total / max).coerceIn(0f, 1f)
                    if (a.total > 0) {
                        Row(Modifier.fillMaxWidth(f).fillMaxHeight()) {
                            if (a.wifi > 0) Box(Modifier.weight(a.wifi.toFloat()).fillMaxHeight().background(WifiColor))
                            if (a.mobile > 0) Box(Modifier.weight(a.mobile.toFloat()).fillMaxHeight().background(MobileColor))
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = WifiColor)) { append("● ") }
                            append("${fmt(a.wifi)}   ")
                            withStyle(SpanStyle(color = MobileColor)) { append("● ") }
                            append(fmt(a.mobile))
                        },
                        fontSize = 11.sp, color = cs.onSurfaceVariant
                    )
                    Text("🕒 ${fmtTime(a.fgMs)}", fontSize = 11.sp, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}
// END