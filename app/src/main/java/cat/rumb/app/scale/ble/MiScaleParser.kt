package cat.rumb.app.scale.ble

/** One decoded frame from the scale. [impedanceOhm] is null until the measurement settles enough to
 *  report a valid one; [stabilized] is the scale's own "weight has settled" flag. */
data class ScaleFrame(
    val weightKg: Double,
    val impedanceOhm: Int?,
    val stabilized: Boolean,
    val weightRemoved: Boolean,
)

/**
 * Parses the Mi Body Composition Scale measurement frame. Pure and JVM-testable — the one BLE piece
 * that can be checked without the hardware, by feeding it known byte layouts.
 *
 * Only the **Mi Body Composition Scale 2** (13-byte frame, GATT/advert service 0x181B) is decoded
 * here. The S400 speaks a different, newer protocol; it would be a second [parse]-shaped strategy
 * selected once its exact layout is sniffed on the device. The parser is deliberately isolated so
 * that swap costs nothing elsewhere.
 */
object MiScaleParser {

    /**
     * Decodes the 13-byte MIBCS2 body-composition frame, or null if it's too short. Layout (LE):
     * `[0]` unit flags · `[1]` status flags · `[2..8]` timestamp · `[9..10]` impedance · `[11..12]`
     * weight. Weight is raw/200 for kg, raw/100 for lb (then to kg). Impedance is only meaningful
     * once its status bit is set and the value is neither 0 nor 0xFFFF.
     */
    fun parseMibcs2(data: ByteArray): ScaleFrame? {
        if (data.size < 13) return null
        val unitFlags = data[0].toInt() and 0xFF
        val statusFlags = data[1].toInt() and 0xFF

        val stabilized = statusFlags and STABILIZED != 0
        val impedancePresent = statusFlags and IMPEDANCE_PRESENT != 0
        val weightRemoved = statusFlags and WEIGHT_REMOVED != 0

        val weightRaw = u16(data, 11)
        val weightKg = when {
            unitFlags and UNIT_LB != 0 -> weightRaw / 100.0 * LB_TO_KG
            unitFlags and UNIT_CATTY != 0 -> weightRaw / 100.0 * CATTY_TO_KG
            else -> weightRaw / 200.0
        }

        val impedanceRaw = u16(data, 9)
        val impedance = impedanceRaw.takeIf { impedancePresent && it != 0 && it != 0xFFFF }

        return ScaleFrame(weightKg, impedance, stabilized, weightRemoved)
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    // Status-flag bits (byte 1) and unit-flag bits (byte 0), per the reverse-engineered protocol.
    private const val IMPEDANCE_PRESENT = 0x02 // bit 1
    private const val STABILIZED = 0x20 // bit 5
    private const val WEIGHT_REMOVED = 0x80 // bit 7
    private const val UNIT_LB = 0x01 // bit 0
    private const val UNIT_CATTY = 0x10 // bit 4

    private const val LB_TO_KG = 0.45359237
    private const val CATTY_TO_KG = 0.5
}
