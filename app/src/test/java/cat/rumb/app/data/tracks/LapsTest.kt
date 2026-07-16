package cat.rumb.app.data.tracks

import cat.rumb.app.data.recording.LapMark
import cat.rumb.app.data.recording.LapMarkType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LapsTest {

    @Test
    fun approachBeforeFirstStartIsNotALap() {
        // START at seq 10, SPLIT at 20, END at 30; saved points seq 0..39.
        val marks = listOf(
            LapMark(10, 100.0, 60_000, LapMarkType.START),
            LapMark(20, 200.0, 120_000, LapMarkType.SPLIT),
            LapMark(30, 300.0, 180_000, LapMarkType.END),
        )
        val seqs = (0L until 40L).toList()
        val ranges = Laps.fromMarks(marks, seqs)

        // approach [0,10), lap1 [10,20), lap2 [20,30), return [30,40)
        assertThat(ranges.map { it.kind }).containsExactly(
            LapKind.APPROACH, LapKind.LAP, LapKind.LAP, LapKind.RETURN,
        )
        val laps = ranges.filter { it.kind == LapKind.LAP }
        assertThat(laps).hasSize(2)
        assertThat(laps[0].startIdx to laps[0].endIdx).isEqualTo(10 to 20)
        assertThat(laps[1].startIdx to laps[1].endIdx).isEqualTo(20 to 30)
    }

    @Test
    fun noEndMeansLastLapRunsToTheTrackEnd() {
        val marks = listOf(
            LapMark(5, 50.0, 30_000, LapMarkType.START),
            LapMark(15, 150.0, 90_000, LapMarkType.SPLIT),
        )
        val seqs = (0L until 25L).toList()
        val ranges = Laps.fromMarks(marks, seqs)
        assertThat(ranges.filter { it.kind == LapKind.LAP }.last().endIdx).isEqualTo(25)
        assertThat(ranges.none { it.kind == LapKind.RETURN }).isTrue()
    }

    @Test
    fun jsonRoundTrip() {
        val ranges = listOf(LapRange(1, 10, 20, LapKind.LAP), LapRange(2, 20, 30, LapKind.LAP))
        assertThat(Laps.decode(Laps.encode(ranges))).isEqualTo(ranges)
        assertThat(Laps.decode(null)).isEmpty()
        assertThat(Laps.decode("garbage")).isEmpty()
    }

    @Test
    fun emptyMarksYieldNoLaps() {
        assertThat(Laps.fromMarks(emptyList(), listOf(0L, 1L, 2L))).isEmpty()
    }

    @Test
    fun abandonedLapIsNotALapAndDoesNotTakeANumber() {
        // lap 1 [10,20) · gave up and came back to the line at 20 · retried [20,30) → that IS lap 2.
        val marks = listOf(
            LapMark(10, 100.0, 60_000, LapMarkType.START),
            LapMark(20, 200.0, 120_000, LapMarkType.ABORT),
            LapMark(30, 300.0, 180_000, LapMarkType.SPLIT),
            LapMark(40, 400.0, 240_000, LapMarkType.END),
        )
        val ranges = Laps.fromMarks(marks, (0L until 50L).toList())

        assertThat(ranges.map { it.kind }).containsExactly(
            LapKind.APPROACH, LapKind.ABORTED, LapKind.LAP, LapKind.LAP, LapKind.RETURN,
        )
        // Numbering skips the abandoned one: the laps you raced are 1 and 2, not 2 and 3.
        assertThat(ranges.filter { it.kind == LapKind.LAP }.map { it.index }).containsExactly(1, 2)
        val aborted = ranges.single { it.kind == LapKind.ABORTED }
        assertThat(aborted.startIdx to aborted.endIdx).isEqualTo(10 to 20)
    }

    // --- LapEdits: add / remove split (post-hoc editor transforms) ---

    // approach [0,10) · lap1 [10,20) · lap2 [20,30) · return [30,40)
    private val cuts = listOf(0, 10, 20, 30, 40)
    private val kinds = listOf(LapKind.APPROACH, LapKind.LAP, LapKind.LAP, LapKind.RETURN)

    @Test
    fun addSplitInsideLapSplitsItAndRenumbers() {
        val (c, k) = LapEdits.addSplit(cuts, kinds, 15)!!
        assertThat(c).containsExactly(0, 10, 15, 20, 30, 40)
        assertThat(k).containsExactly(LapKind.APPROACH, LapKind.LAP, LapKind.LAP, LapKind.LAP, LapKind.RETURN)
        val laps = LapEdits.rebuild(c, k).filter { it.kind == LapKind.LAP }
        assertThat(laps.map { it.index }).containsExactly(1, 2, 3) // renumbered in order
        assertThat(laps.map { it.startIdx to it.endIdx }).containsExactly(10 to 15, 15 to 20, 20 to 30)
    }

    @Test
    fun cannotAddOnApproachReturnOrOnExistingCut() {
        assertThat(LapEdits.canAdd(cuts, kinds, 5)).isFalse()  // inside APPROACH
        assertThat(LapEdits.canAdd(cuts, kinds, 35)).isFalse() // inside RETURN
        assertThat(LapEdits.canAdd(cuts, kinds, 20)).isFalse() // on an existing cut
        assertThat(LapEdits.addSplit(cuts, kinds, 5)).isNull()
    }

    @Test
    fun removeCutMergesTwoLaps() {
        val (c, k) = LapEdits.removeCut(cuts, kinds, 2)!! // the cut between lap1 and lap2 (index 2)
        assertThat(c).containsExactly(0, 10, 30, 40)
        assertThat(k).containsExactly(LapKind.APPROACH, LapKind.LAP, LapKind.RETURN)
        val laps = LapEdits.rebuild(c, k).filter { it.kind == LapKind.LAP }
        assertThat(laps).hasSize(1)
        assertThat(laps[0].startIdx to laps[0].endIdx).isEqualTo(10 to 30)
    }

    @Test
    fun cannotRemoveApproachOrReturnBoundary() {
        assertThat(LapEdits.canRemove(cuts, kinds, 1)).isFalse() // START boundary (approach|lap)
        assertThat(LapEdits.canRemove(cuts, kinds, 3)).isFalse() // END boundary (lap|return)
        assertThat(LapEdits.removeCut(cuts, kinds, 1)).isNull()
    }

    @Test
    fun addThenRemoveRoundTripsThroughJson() {
        val (c, k) = LapEdits.addSplit(cuts, kinds, 15)!!
        val (c2, k2) = LapEdits.removeCut(c, k, 2)!! // remove the just-added cut → back to original laps
        val ranges = LapEdits.rebuild(c2, k2)
        assertThat(Laps.decode(Laps.encode(ranges))).isEqualTo(ranges)
        assertThat(ranges.filter { it.kind == LapKind.LAP }.map { it.startIdx to it.endIdx })
            .containsExactly(10 to 20, 20 to 30)
    }
}
