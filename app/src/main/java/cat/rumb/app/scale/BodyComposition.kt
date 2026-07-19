package cat.rumb.app.scale

/** Biological sex, the only two the body-composition model branches on. */
enum class Sex { MALE, FEMALE }

/**
 * One weigh-in's derived metrics. [weightKg] is always present; [bmi] needs a height; everything
 * else is body composition and needs the scale's impedance (plus height/age/sex) — those fields are
 * null for a weight-only reading.
 */
data class BodyMetrics(
    val weightKg: Double,
    val bmi: Double?,
    val bodyFatPct: Double?,
    val waterPct: Double?,
    val muscleMassKg: Double?,
    val boneMassKg: Double?,
    val visceralFat: Double?,
    val bmrKcal: Double?,
    val proteinPct: Double?,
    val metabolicAge: Double?,
)

/**
 * Estimates body composition from a Mi Body Composition Scale reading. The scale only measures
 * WEIGHT and IMPEDANCE; fat/water/muscle/bone/visceral/BMR/metabolic-age are all DERIVED from those
 * plus the person's height, age and sex, using the widely-reverse-engineered Xiaomi algorithm.
 *
 * These are estimates and label themselves as such — they approximate the official Mi Fitness app's
 * numbers without being identical. Implemented clean-room from the published equations (not ported
 * from any GPL source), so it stays JVM-pure and is the module's most testable core.
 */
object BodyComposition {

    /**
     * @param impedanceOhm null (or ≤0) → only weight and, if [heightCm] is known, BMI are returned.
     * @param sex null (unknown) → composition can't be derived, so only weight/BMI come back.
     */
    fun compute(
        weightKg: Double,
        impedanceOhm: Int?,
        heightCm: Int,
        ageYears: Int,
        sex: Sex?,
    ): BodyMetrics {
        val bmi = if (heightCm > 0) {
            val h = heightCm / 100.0
            weightKg / (h * h)
        } else {
            null
        }

        // Composition needs a real impedance, height, age and sex; otherwise it's a weight-only read.
        val canDerive = impedanceOhm != null && impedanceOhm > 0 && heightCm > 0 && ageYears > 0 && sex != null
        if (!canDerive) {
            return BodyMetrics(weightKg, bmi, null, null, null, null, null, null, null, null)
        }
        val impedance = impedanceOhm!!.toDouble()
        val height = heightCm.toDouble()
        val age = ageYears.toDouble()
        val male = sex == Sex.MALE

        // Lean-body-mass coefficient — the impedance-driven backbone of every composition metric.
        val lbm = (height * 9.058 / 100.0) * (height / 100.0) +
            weightKg * 0.32 + 12.226 -
            impedance * 0.0068 -
            age * 0.0542

        val fat = bodyFat(weightKg, height, age, male, lbm)
        val water = waterPct(fat)
        val bone = boneMass(male, lbm)
        val muscle = clamp(
            weightKg - (fat * 0.01) * weightKg - bone,
            10.0, 120.0,
        )
        val visceral = visceralFat(weightKg, height, age, male)
        val bmr = bmr(weightKg, height, age, male)
        val protein = clamp((muscle / weightKg) * 100.0 - water, 5.0, 32.0)
        val metabolicAge = metabolicAge(weightKg, height, age, impedance, male)

        return BodyMetrics(
            weightKg = weightKg,
            bmi = bmi,
            bodyFatPct = fat,
            waterPct = water,
            muscleMassKg = muscle,
            boneMassKg = bone,
            visceralFat = visceral,
            bmrKcal = bmr,
            proteinPct = protein,
            metabolicAge = metabolicAge,
        )
    }

    private fun bodyFat(weightKg: Double, height: Double, age: Double, male: Boolean, lbm: Double): Double {
        val const = when {
            !male && age <= 49 -> 9.25
            !male -> 7.25
            else -> 0.8
        }
        val coeff = when {
            male && weightKg < 61 -> 0.98
            !male && weightKg > 60 -> if (height > 160) 0.96 * 1.03 else 0.96
            !male && weightKg < 50 -> if (height > 160) 1.02 * 1.03 else 1.02
            else -> 1.0
        }
        var fat = (1.0 - ((lbm - const) * coeff) / weightKg) * 100.0
        if (fat > 63) fat = 75.0
        return clamp(fat, 5.0, 75.0)
    }

    private fun waterPct(bodyFatPct: Double): Double {
        val water = (100.0 - bodyFatPct) * 0.7
        val coeff = if (water <= 50) 1.02 else 0.98
        val adjusted = if (water * coeff >= 65) 75.0 else water * coeff
        return clamp(adjusted, 35.0, 75.0)
    }

    private fun boneMass(male: Boolean, lbm: Double): Double {
        val base = if (male) 0.18016894 else 0.245691014
        var bone = -(base - lbm * 0.05158)
        bone += if (bone > 2.2) 0.1 else -0.1
        val cap = if (male) 5.2 else 5.1
        if (bone > cap) bone = 8.0
        return clamp(bone, 0.5, 8.0)
    }

    private fun visceralFat(weightKg: Double, height: Double, age: Double, male: Boolean): Double {
        val vf = if (!male) {
            if (weightKg > -(13 - height * 0.5)) {
                val denom = (height * 1.45 + height * 0.1158 * height) - 120
                weightKg * 500 / denom - 6 + age * 0.07
            } else {
                val sub = 0.691 + height * -0.0024 + height * -0.0024
                -(height * 0.027 - sub * weightKg) + age * 0.07 - age
            }
        } else {
            if (height < weightKg * 1.6) {
                val sub = -(height * 0.4 - height * (height * 0.0826))
                weightKg * 305 / (sub + 48) - 2.9 + age * 0.15
            } else {
                val sub = 0.765 + height * -0.0015
                -(height * 0.143 - weightKg * sub) + age * 0.15 - 5.0
            }
        }
        return clamp(vf, 1.0, 50.0)
    }

    private fun bmr(weightKg: Double, height: Double, age: Double, male: Boolean): Double {
        var bmr = if (male) {
            877.8 + weightKg * 14.916 - height * 0.726 - age * 8.976
        } else {
            864.6 + weightKg * 10.2036 - height * 0.39336 - age * 6.204
        }
        val cap = if (male) 2322.0 else 2996.0
        if (bmr > cap) bmr = 5000.0
        return clamp(bmr, 500.0, 10000.0)
    }

    private fun metabolicAge(weightKg: Double, height: Double, age: Double, impedance: Double, male: Boolean): Double {
        val ma = if (male) {
            height * -0.7471 + weightKg * 0.9161 + age * 0.4184 + impedance * 0.0517 + 54.2267
        } else {
            height * -1.1165 + weightKg * 1.5784 + age * 0.4615 + impedance * 0.0415 + 83.2548
        }
        return clamp(ma, 15.0, 80.0)
    }

    private fun clamp(v: Double, min: Double, max: Double): Double = v.coerceIn(min, max)
}
