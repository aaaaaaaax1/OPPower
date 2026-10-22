from pathlib import Path

path = Path(r"app/src/main/java/com/oppower/ui/MainPage.kt")
text = path.read_text(encoding="utf-8")

text = text.replace(
    "import androidx.compose.runtime.produceState\nimport androidx.compose.runtime.remember\n",
    "import androidx.compose.runtime.produceState\nimport androidx.compose.runtime.remember\nimport androidx.compose.runtime.rememberUpdatedState\n",
)
text = text.replace(
    "import androidx.compose.ui.draw.clip\nimport androidx.compose.ui.draw.shadow\n",
    "import androidx.compose.ui.draw.clip\nimport androidx.compose.ui.draw.drawWithCache\nimport androidx.compose.ui.draw.shadow\n",
)
text = text.replace(
    "    val battery = state.battery\n    var recordKind by remember { mutableStateOf<RecordKind?>(null) }\n",
    "    val battery = state.battery\n    val pageBackground = remember {\n        Brush.verticalGradient(listOf(Background, Surface.copy(alpha = 0.54f)))\n    }\n    var recordKind by remember { mutableStateOf<RecordKind?>(null) }\n",
    1,
)
text = text.replace(
    "            .background(Brush.verticalGradient(listOf(Background, Surface.copy(alpha = 0.54f))))\n",
    "            .background(pageBackground)\n",
    1,
)

old_charts_item = '''        item(key = "charts") {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                SectionHeader("实时曲线", "两张图可独立拖动")
                RealtimeCharts(
                    points = state.points,
                    recordIntervalMs = ChartHistoryStore.sampleIntervalMillis(
                        isCharging = state.battery.isCharging,
                        screenOn = true,
                    ),
                )
                Text(
                    "图表用于观察趋势；实际输入功率以外部功率计为准。",
                    color = TextSecondary,
                    fontSize = 9.sp,
                )
                Spacer(Modifier.height(12.dp))
            }
        }
'''
new_charts_items = '''        item(key = "charts-header", contentType = "section-header") {
            SectionHeader("实时曲线", "两张图可独立拖动")
        }

        item(key = "power-chart", contentType = "chart") {
            PowerRealtimeChart(
                points = state.points,
                recordIntervalMs = ChartHistoryStore.sampleIntervalMillis(
                    isCharging = state.battery.isCharging,
                    screenOn = true,
                ),
            )
        }

        item(key = "temperature-chart", contentType = "chart") {
            TemperatureRealtimeChart(points = state.points)
        }

        item(key = "charts-footer", contentType = "footer") {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(
                    "记录最近 8 小时 · 每次显示 2 小时 · 曲线按 LLPower 节奏采样 · 时间节点 15 分钟",
                    color = TextSecondary,
                    fontSize = 8.sp,
                )
                Text(
                    "图表用于观察趋势；实际输入功率以外部功率计为准。",
                    color = TextSecondary,
                    fontSize = 9.sp,
                )
                Spacer(Modifier.height(12.dp))
            }
        }
'''
if old_charts_item not in text:
    raise SystemExit("charts item block not found")
text = text.replace(old_charts_item, new_charts_items, 1)

start = text.index("@Composable\nprivate fun RealtimeCharts(")
end = text.index("@Composable\nprivate fun ChartLoadingCard", start)
new_realtime = '''@Composable
private fun PowerRealtimeChart(
    points: List<ChartPoint>,
    recordIntervalMs: Long,
) {
    val snapshot by produceState<CurveSnapshot?>(
        initialValue = null,
        points,
        recordIntervalMs,
    ) {
        value = withContext(Dispatchers.Default) {
            val end = points.lastOrNull()?.epochMillis ?: System.currentTimeMillis()
            CurveProcessing.processPower(
                points = points,
                windowStart = end - CurveProcessing.HISTORY_WINDOW_MS,
                windowEnd = end,
                recordIntervalMs = recordIntervalMs,
            )
        }
    }

    val data = snapshot
    if (data == null) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("功率曲线", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            ChartLoadingCard("实时功率")
        }
        return
    }

    var endFraction by remember { mutableFloatStateOf(1f) }
    val windowMillis = 2L * 60L * 60L * 1_000L
    val timelineNow = remember(points) { System.currentTimeMillis() }
    val historyEnd = points.lastOrNull()?.epochMillis ?: timelineNow
    val timelineEnd = maxOf(timelineNow, historyEnd)
    val availableStart = maxOf(
        historyEnd - CurveProcessing.HISTORY_WINDOW_MS,
        timelineEnd - CurveProcessing.HISTORY_WINDOW_MS,
    )
    val availableRange = (timelineEnd - availableStart - windowMillis).coerceAtLeast(0L)
    val windowStart = availableStart + (availableRange * endFraction.coerceIn(0f, 1f)).toLong()
    val windowEnd = windowStart + windowMillis
    val segments = remember(data, windowStart, windowEnd) {
        CurveProcessing.clip(data, windowStart, windowEnd)
    }

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("功率曲线", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        ChartCard(
            title = "实时功率",
            unit = "W",
            color = Cyan,
            segments = segments,
            windowStart = windowStart,
            windowMillis = windowMillis,
            axis = data.axis,
            onDrag = { dragAmount -> endFraction = (endFraction - dragAmount).coerceIn(0f, 1f) },
        )
    }
}

@Composable
private fun TemperatureRealtimeChart(points: List<ChartPoint>) {
    val snapshot by produceState<CurveSnapshot?>(
        initialValue = null,
        points,
    ) {
        value = withContext(Dispatchers.Default) {
            val end = points.lastOrNull()?.epochMillis ?: System.currentTimeMillis()
            CurveProcessing.processTemperature(
                points = points,
                windowStart = end - CurveProcessing.HISTORY_WINDOW_MS,
                windowEnd = end,
            )
        }
    }

    val data = snapshot
    if (data == null) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("温度曲线", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            ChartLoadingCard("电池温度")
        }
        return
    }

    var endFraction by remember { mutableFloatStateOf(1f) }
    val windowMillis = 2L * 60L * 60L * 1_000L
    val timelineNow = remember(points) { System.currentTimeMillis() }
    val historyEnd = points.lastOrNull()?.epochMillis ?: timelineNow
    val timelineEnd = maxOf(timelineNow, historyEnd)
    val availableStart = maxOf(
        historyEnd - CurveProcessing.HISTORY_WINDOW_MS,
        timelineEnd - CurveProcessing.HISTORY_WINDOW_MS,
    )
    val availableRange = (timelineEnd - availableStart - windowMillis).coerceAtLeast(0L)
    val windowStart = availableStart + (availableRange * endFraction.coerceIn(0f, 1f)).toLong()
    val windowEnd = windowStart + windowMillis
    val segments = remember(data, windowStart, windowEnd) {
        CurveProcessing.clip(data, windowStart, windowEnd)
    }

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("温度曲线", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        ChartCard(
            title = "电池温度",
            unit = "°C",
            color = Amber,
            segments = segments,
            windowStart = windowStart,
            windowMillis = windowMillis,
            axis = data.axis,
            onDrag = { dragAmount -> endFraction = (endFraction - dragAmount).coerceIn(0f, 1f) },
        )
    }
}

'''
text = text[:start] + new_realtime + text[end:]

start = text.index("@Composable\nprivate fun ChartCard(")
end = text.index("@Composable\nprivate fun TimelineLabels", start)
new_chart_card = '''@Composable
private fun ChartCard(
    title: String,
    unit: String,
    color: Color,
    segments: List<CurveSegment>,
    windowStart: Long,
    windowMillis: Long,
    axis: CurveAxis,
    onDrag: (Float) -> Unit,
) {
    val textMeasurer = rememberTextMeasurer()
    val currentOnDrag by rememberUpdatedState(onDrag)
    val tickCount = axis.tickCount
    val span = (axis.maximum - axis.minimum).coerceAtLeast(0.001f)
    val hasData = segments.any { it.points.isNotEmpty() }
    val axisLabels = remember(axis, unit) {
        val count = axis.tickCount
        val range = (axis.maximum - axis.minimum).coerceAtLeast(0.001f)
        List(count) { index ->
            val fraction = 1f - index / (count - 1).coerceAtLeast(1).toFloat()
            val value = axis.minimum + range * fraction
            if (unit == "W") {
                value.roundToInt().toString()
            } else {
                String.format(Locale.getDefault(), "%.1f", value)
            }
        }
    }
    Surface(
        color = Surface,
        shape = RoundedCornerShape(11.dp),
        border = BorderStroke(1.dp, Outline),
    ) {
        Column(Modifier.fillMaxWidth().padding(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("2 小时视图 · 可拖动", color = TextSecondary, fontSize = 8.sp)
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .padding(top = 6.dp)
                    .pointerInput(windowMillis) {
                        detectHorizontalDragGestures { _, dragAmount ->
                            currentOnDrag(dragAmount / size.width.coerceAtLeast(1))
                        }
                    },
            ) {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .padding(start = 35.dp, end = 12.dp, top = 10.dp, bottom = 14.dp)
                        .drawWithCache {
                            val right = size.width
                            val bottom = size.height
                            val axisStyle = TextStyle(color = TextSecondary, fontSize = 7.sp)
                            val measuredLabels = axisLabels.map { label ->
                                textMeasurer.measure(text = label, style = axisStyle)
                            }
                            val strokes: List<Pair<Path?, Offset?>> = segments.mapNotNull { segment ->
                                val points = segment.points
                                if (points.isEmpty()) return@mapNotNull null
                                if (points.size == 1) {
                                    val point = points.first()
                                    val x = right * ((point.epochMillis - windowStart).toFloat() / windowMillis).coerceIn(0f, 1f)
                                    val y = bottom - bottom * ((point.value - axis.minimum) / span).coerceIn(0f, 1f)
                                    return@mapNotNull Pair(null, Offset(x, y))
                                }
                                val path = Path()
                                points.forEachIndexed { index, point ->
                                    val x = right * ((point.epochMillis - windowStart).toFloat() / windowMillis).coerceIn(0f, 1f)
                                    val y = bottom - bottom * ((point.value - axis.minimum) / span).coerceIn(0f, 1f)
                                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                                }
                                Pair(path, null)
                            }
                            onDrawBehind {
                                val divisor = (tickCount - 1).coerceAtLeast(1).toFloat()
                                repeat(tickCount) { index ->
                                    val y = bottom * index / divisor
                                    drawLine(Outline, Offset(0f, y), Offset(right, y), 1f)
                                }
                                measuredLabels.forEachIndexed { index, measured ->
                                    val y = (bottom * index / divisor - measured.size.height / 2f)
                                        .coerceIn(0f, (bottom - measured.size.height).coerceAtLeast(0f))
                                    drawText(measured, topLeft = Offset(-measured.size.width - 5f, y))
                                }
                                strokes.forEach { (path, point) ->
                                    if (path != null) {
                                        drawPath(path, color, style = Stroke(3f))
                                    } else if (point != null) {
                                        drawCircle(color, radius = 3f, center = point)
                                    }
                                }
                            }
                        },
                )
                if (!hasData) {
                    Text("等待采样…", color = TextSecondary, fontSize = 9.sp, modifier = Modifier.align(Alignment.Center))
                }
            }
            TimelineLabels(windowStart, windowMillis)
        }
    }
}

'''
text = text[:start] + new_chart_card + text[end:]

old_timeline = '''@Composable
private fun TimelineLabels(windowStart: Long, windowMillis: Long) {
    val formatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        repeat(9) { index ->
            Text(
                formatter.format(Date(windowStart + windowMillis * index / 8L)),
                color = TextSecondary,
                fontSize = 7.sp,
            )
        }
    }
}
'''
new_timeline = '''@Composable
private fun TimelineLabels(windowStart: Long, windowMillis: Long) {
    val labels = remember(windowStart, windowMillis) {
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        List(9) { index ->
            formatter.format(Date(windowStart + windowMillis * index / 8L))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        labels.forEach { label ->
            Text(label, color = TextSecondary, fontSize = 7.sp)
        }
    }
}
'''
if old_timeline not in text:
    raise SystemExit("timeline block not found")
text = text.replace(old_timeline, new_timeline, 1)

# Avoid allocating a formatter on every one-second record-card recomposition.
needle = '''            val display = lastRecord
            if (display != null) {
                val delta = display.endPercent - display.startPercent
'''
replacement = '''            val display = lastRecord
            if (display != null) {
                val formatter = remember { SimpleDateFormat("今天 HH:mm", Locale.getDefault()) }
                val delta = display.endPercent - display.startPercent
'''
if needle not in text:
    raise SystemExit("record card marker not found")
text = text.replace(needle, replacement, 1)
text = text.replace(
    '${SimpleDateFormat("今天 HH:mm", Locale.getDefault()).format(Date(display.startedAtMillis))} · ${(display.endedAtMillis - display.startedAtMillis).durationText()}',
    '${formatter.format(Date(display.startedAtMillis))} · ${(display.endedAtMillis - display.startedAtMillis).durationText()}',
    1,
)

path.write_text(text, encoding="utf-8", newline="\n")
print("MainPage.kt patched")
