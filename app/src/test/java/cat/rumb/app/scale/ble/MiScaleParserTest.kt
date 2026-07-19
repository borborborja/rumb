package cat.rumb.app.scale.ble

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class MiScaleParserTest {

    /** Builds a 13-byte MIBCS2 frame from the fields the parser reads (LE). */
    private fun frame(
        unitFlags: Int = 0x00,
        statusFlags: Int,
        impedance: Int,
        weightRaw: Int,
    ): ByteArray {
        val b = ByteArray(13)
        b[0] = unitFlags.toByte()
        b[1] = statusFlags.toByte()
        // [2..8] timestamp — irrelevant to the parser, left zero.
        b[9] = (impedance and 0xFF).toByte()
        b[10] = ((impedance shr 8) and 0xFF).toByte()
        b[11] = (weightRaw and 0xFF).toByte()
        b[12] = ((weightRaw shr 8) and 0xFF).toByte()
        return b
    }

    @Test
    fun tooShortIsRejected() {
        assertThat(MiScaleParser.parseMibcs2(ByteArray(12))).isNull()
    }

    @Test
    fun stabilizedFrameWithImpedanceGivesWeightAndImpedance() {
        // 75.00 kg → raw 15000; impedance 500; stabilized (0x20) + impedance-present (0x02).
        val f = MiScaleParser.parseMibcs2(frame(statusFlags = 0x22, impedance = 500, weightRaw = 15000))!!
        assertThat(f.weightKg).isCloseTo(75.0, within(0.001))
        assertThat(f.impedanceOhm).isEqualTo(500)
        assertThat(f.stabilized).isTrue()
        assertThat(f.weightRemoved).isFalse()
    }

    @Test
    fun earlyFrameHasWeightButNoImpedanceAndIsNotStabilized() {
        // Just stepped on: weight is coming in, impedance bit not set yet.
        val f = MiScaleParser.parseMibcs2(frame(statusFlags = 0x00, impedance = 0, weightRaw = 14980))!!
        assertThat(f.weightKg).isCloseTo(74.9, within(0.01))
        assertThat(f.impedanceOhm).isNull()
        assertThat(f.stabilized).isFalse()
    }

    @Test
    fun impedanceBitSetButZeroOrMaxIsTreatedAsAbsent() {
        assertThat(MiScaleParser.parseMibcs2(frame(statusFlags = 0x02, impedance = 0, weightRaw = 15000))!!.impedanceOhm).isNull()
        assertThat(MiScaleParser.parseMibcs2(frame(statusFlags = 0x02, impedance = 0xFFFF, weightRaw = 15000))!!.impedanceOhm).isNull()
    }

    @Test
    fun weightRemovedBitIsReported() {
        val f = MiScaleParser.parseMibcs2(frame(statusFlags = 0x80, impedance = 0, weightRaw = 0))!!
        assertThat(f.weightRemoved).isTrue()
    }

    @Test
    fun poundUnitIsConvertedToKilograms() {
        // 100.0 lb (raw 10000, /100) → 45.36 kg.
        val f = MiScaleParser.parseMibcs2(frame(unitFlags = 0x01, statusFlags = 0x22, impedance = 500, weightRaw = 10000))!!
        assertThat(f.weightKg).isCloseTo(45.359, within(0.01))
    }
}
