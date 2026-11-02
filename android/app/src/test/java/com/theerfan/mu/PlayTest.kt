package com.theerfan.mu

import com.theerfan.mu.play.Grade
import com.theerfan.mu.play.Judgement
import com.theerfan.mu.play.Kind
import com.theerfan.mu.play.feedback
import com.theerfan.mu.play.summarize
import com.theerfan.mu.ui.TunerMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayTest {
    private fun j(kind: Kind, grade: Grade, idx: Int, dt: Float = 0f, det: Int = -1, miss: Int = -1, extra: Int = -1) =
        Judgement(kind, grade, idx, dt, 0f, det, 0f, -1f, miss, extra)

    @Test fun unpackMatchesNativeLayout() {
        val a = floatArrayOf(2f, 1f, 5f, -42f, 3.5f, 50f, 12f, 0.3f, 11f, -1f)
        val x = Judgement.unpack(a, 1).single()
        assertEquals(Kind.WRONG, x.kind); assertEquals(Grade.GOOD, x.grade); assertEquals(5, x.index)
        assertEquals(-42f, x.dtMs); assertEquals(50, x.detMidi); assertEquals(11, x.missingPc)
    }

    @Test fun summaryCountsAndStreaks() {
        val js = listOf(
            j(Kind.HIT, Grade.PERFECT, 0, 10f), j(Kind.HIT, Grade.GOOD, 1, 60f), j(Kind.WRONG, Grade.PERFECT, 2, 5f, det = 50),
            j(Kind.MISS, Grade.NONE, 3), j(Kind.HIT, Grade.OK, 4, -100f), j(Kind.PARTIAL, Grade.PERFECT, 5), j(Kind.EXTRA, Grade.NONE, -1),
        )
        val s = summarize(js, 7) { it == 1 || it == 2 }
        assertEquals(1, s.perfect); assertEquals(1, s.good); assertEquals(1, s.ok); assertEquals(1, s.partial)
        assertEquals(1, s.wrong); assertEquals(2, s.miss)  // index 3 + index 6 never judged
        assertEquals(1, s.extra); assertEquals(2, s.bestStreak)
        assertEquals(100 + 70 + 40 + 50, s.score); assertEquals(700, s.maxScore)
        assertEquals(1, s.chordHits); assertEquals(2, s.chordTotal)
    }

    @Test fun feedbackTexts() {
        assertEquals("Perfect", feedback(j(Kind.HIT, Grade.PERFECT, 0, 12f), listOf(40)))
        assertEquals("Late 62 ms", feedback(j(Kind.HIT, Grade.GOOD, 0, 62f), listOf(40)))
        assertEquals("Early 101 ms", feedback(j(Kind.HIT, Grade.OK, 0, -101f), listOf(40)))
        assertEquals("Wrong note: D3 (want A2)", feedback(j(Kind.WRONG, Grade.PERFECT, 0, det = 50), listOf(45)))
        assertEquals("Wrong chord: missing B", feedback(j(Kind.WRONG, Grade.PERFECT, 0, miss = 11), listOf(48, 52, 55, 59)))
        assertEquals("Wrong chord: missing B, extra C", feedback(j(Kind.WRONG, Grade.GOOD, 0, 50f, miss = 11, extra = 0), listOf(48, 52, 55, 59)))
    }

    @Test fun tunerMath() {
        val (n, c) = TunerMath.nearest(110.0)
        assertEquals(45, n); assertEquals(0.0, c, 1e-6)
        val (n2, c2) = TunerMath.nearest(442.0)
        assertEquals(69, n2); assertTrue(c2 in 7.0..8.5)
        assertEquals(0, TunerMath.nearestString(83.0, listOf(40, 45, 50, 55, 59, 64)))
        assertEquals(3.0, TunerMath.median(listOf(5.0, 1.0, 3.0)), 0.0)
    }
}
