package com.oppower.history

import com.oppower.model.ChartPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class ChartHistoryCodecTest {
    @Test
    fun roundTripKeepsEveryChartField() {
        val original = listOf(
            ChartPoint(1_000L, 18.5f, 39.25f, 57, true),
            ChartPoint(31_000L, 0f, 36.5f, 56, false),
        )

        assertEquals(original, ChartHistoryCodec.decode(ChartHistoryCodec.encode(original)))
    }

    @Test
    fun malformedLinesAreSkipped() {
        val decoded = ChartHistoryCodec.decode(
            "epoch,power,temp,soc,ac\n" +
                "bad\n" +
                "1000,12.5,40.0,50,1\n" +
                "2000,nope,40.0,50,1\n",
        )

        assertEquals(1, decoded.size)
        assertEquals(1_000L, decoded.single().epochMillis)
    }
}
