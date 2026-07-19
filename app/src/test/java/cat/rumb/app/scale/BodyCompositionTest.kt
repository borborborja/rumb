package cat.rumb.app.scale

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class BodyCompositionTest {

    // A reference case worked out BY HAND from the published equations, so the test is an independent
    // check rather than a mirror of the code: 75 kg, 500 Ω, 175 cm, 30 y, male.
    //   BMI  = 75 / 1.75²                              = 24.49
    //   LBM  = (175·9.058/100)·1.75 + 75·0.32 + 12.226 − 500·0.0068 − 30·0.0542 = 58.9401
    //   fat% = (1 − (58.9401 − 0.8)/75)·100            = 22.48
    //   water= (100 − 22.48)·0.7·0.98                  = 53.18
    //   bone = −(0.18016894 − 58.9401·0.05158) + 0.1   = 2.96
    //   musc = 75 − 0.2248·75 − 2.96                    = 55.18
    //   visc = −(175·0.143 − 75·(0.765 − 175·0.0015)) + 30·0.15 − 5 = 12.16
    //   BMR  = 877.8 + 75·14.916 − 175·0.726 − 30·8.976 = 1600.17
    //   prot = (55.18/75)·100 − 53.18                   = 20.39
    //   mAge = 175·−0.7471 + 75·0.9161 + 30·0.4184 + 500·0.0517 + 54.2267 = 30.59
    @Test
    fun referenceMaleMatchesHandComputedValues() {
        val m = BodyComposition.compute(75.0, 500, 175, 30, Sex.MALE)
        assertThat(m.weightKg).isEqualTo(75.0)
        assertThat(m.bmi!!).isCloseTo(24.49, within(0.02))
        assertThat(m.bodyFatPct!!).isCloseTo(22.48, within(0.05))
        assertThat(m.waterPct!!).isCloseTo(53.18, within(0.05))
        assertThat(m.boneMassKg!!).isCloseTo(2.96, within(0.02))
        assertThat(m.muscleMassKg!!).isCloseTo(55.18, within(0.05))
        assertThat(m.visceralFat!!).isCloseTo(12.16, within(0.05))
        assertThat(m.bmrKcal!!).isCloseTo(1600.17, within(0.5))
        assertThat(m.proteinPct!!).isCloseTo(20.39, within(0.1))
        assertThat(m.metabolicAge!!).isCloseTo(30.59, within(0.1))
    }

    @Test
    fun withoutImpedanceOnlyWeightAndBmiComeBack() {
        val m = BodyComposition.compute(80.0, null, 180, 40, Sex.MALE)
        assertThat(m.weightKg).isEqualTo(80.0)
        assertThat(m.bmi!!).isCloseTo(80.0 / (1.8 * 1.8), within(0.001))
        assertThat(m.bodyFatPct).isNull()
        assertThat(m.waterPct).isNull()
        assertThat(m.muscleMassKg).isNull()
        assertThat(m.metabolicAge).isNull()
    }

    @Test
    fun withoutHeightThereIsNoBmiEither() {
        val m = BodyComposition.compute(80.0, 500, 0, 40, Sex.MALE)
        assertThat(m.bmi).isNull()
        assertThat(m.bodyFatPct).isNull()
    }

    @Test
    fun unknownSexOrAgeBlocksComposition() {
        assertThat(BodyComposition.compute(75.0, 500, 175, 30, null).bodyFatPct).isNull()
        assertThat(BodyComposition.compute(75.0, 500, 175, 0, Sex.MALE).bodyFatPct).isNull()
    }

    @Test
    fun moreImpedanceMeansMoreFat() {
        // Impedance lowers lean mass, so a higher reading must not lower the fat estimate.
        val low = BodyComposition.compute(75.0, 400, 175, 30, Sex.MALE).bodyFatPct!!
        val high = BodyComposition.compute(75.0, 600, 175, 30, Sex.MALE).bodyFatPct!!
        assertThat(high).isGreaterThan(low)
    }

    @Test
    fun sexChangesTheEstimate() {
        val male = BodyComposition.compute(70.0, 500, 170, 35, Sex.MALE)
        val female = BodyComposition.compute(70.0, 500, 170, 35, Sex.FEMALE)
        // Same body, different model branch → the female fat estimate is higher, BMR lower.
        assertThat(female.bodyFatPct!!).isGreaterThan(male.bodyFatPct!!)
        assertThat(female.bmrKcal!!).isLessThan(male.bmrKcal!!)
    }

    @Test
    fun everyMetricStaysInItsSaneRange() {
        // Sweep extremes; nothing escapes its clamp or turns into NaN.
        for (w in listOf(40.0, 75.0, 150.0)) {
            for (imp in listOf(200, 500, 900)) {
                for (h in listOf(150, 175, 200)) {
                    for (a in listOf(18, 45, 80)) {
                        for (s in listOf(Sex.MALE, Sex.FEMALE)) {
                            val m = BodyComposition.compute(w, imp, h, a, s)
                            assertThat(m.bodyFatPct!!).isBetween(5.0, 75.0)
                            assertThat(m.waterPct!!).isBetween(35.0, 75.0)
                            assertThat(m.boneMassKg!!).isBetween(0.5, 8.0)
                            assertThat(m.muscleMassKg!!).isBetween(10.0, 120.0)
                            assertThat(m.visceralFat!!).isBetween(1.0, 50.0)
                            assertThat(m.bmrKcal!!).isBetween(500.0, 10000.0)
                            assertThat(m.metabolicAge!!).isBetween(15.0, 80.0)
                        }
                    }
                }
            }
        }
    }
}
