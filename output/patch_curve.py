from pathlib import Path
path = Path(r"app/src/main/java/com/oppower/history/CurveProcessing.kt")
text = path.read_text(encoding="utf-8")
old = '''    private fun normalized(
        points: List<ChartPoint>,
        windowStart: Long,
        windowEnd: Long,
        value: (ChartPoint) -> Float,
    ): List<CurvePoint> {
        if (windowEnd < windowStart) return emptyList()
        val sums = linkedMapOf<Long, Double>()
        val counts = linkedMapOf<Long, Int>()
        points.asSequence()
            .filter { it.epochMillis in windowStart..windowEnd }
            .map { it.epochMillis to value(it) }
            .filter { (_, pointValue) -> pointValue.isFinite() }
            .forEach { (timestamp, pointValue) ->
                sums[timestamp] = (sums[timestamp] ?: 0.0) + pointValue
                counts[timestamp] = (counts[timestamp] ?: 0) + 1
            }
        return sums.map { (timestamp, sum) ->
            CurvePoint(timestamp, (sum / counts.getValue(timestamp)).toFloat())
        }.sortedBy { it.epochMillis }
    }
'''
new = '''    private fun normalized(
        points: List<ChartPoint>,
        windowStart: Long,
        windowEnd: Long,
        value: (ChartPoint) -> Float,
    ): List<CurvePoint> {
        if (windowEnd < windowStart || points.isEmpty()) return emptyList()

        // History is kept in timestamp order by ChartHistoryBus and SQLite.
        // Use the visible range directly instead of scanning all 24 hours on
        // every chart refresh, then collapse duplicate timestamps in one pass.
        var low = 0
        var high = points.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (points[middle].epochMillis < windowStart) low = middle + 1 else high = middle
        }
        val first = low
        high = points.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (points[middle].epochMillis <= windowEnd) low = middle + 1 else high = middle
        }
        val endExclusive = low
        if (first >= endExclusive) return emptyList()

        val result = ArrayList<CurvePoint>(endExclusive - first)
        var timestamp = Long.MIN_VALUE
        var sum = 0.0
        var count = 0
        for (index in first until endExclusive) {
            val point = points[index]
            val pointValue = value(point)
            if (!pointValue.isFinite()) continue
            if (timestamp != Long.MIN_VALUE && point.epochMillis != timestamp) {
                result += CurvePoint(timestamp, (sum / count).toFloat())
                sum = 0.0
                count = 0
            }
            timestamp = point.epochMillis
            sum += pointValue
            count++
        }
        if (count > 0) result += CurvePoint(timestamp, (sum / count).toFloat())
        return result
    }
'''
if old not in text:
    raise SystemExit('normalized block not found')
text = text.replace(old, new, 1)
path.write_text(text, encoding='utf-8', newline='\n')
print('CurveProcessing.kt patched')
