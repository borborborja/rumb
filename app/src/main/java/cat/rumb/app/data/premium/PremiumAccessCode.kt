package cat.rumb.app.data.premium

import java.security.MessageDigest

/** A visible demo grant is separate from paid Google Play ownership. */
class PremiumAccessCodeVerifier(expectedSha256: String) {
    private val expectedDigest = expectedSha256.trim().takeIf {
        it.matches(Regex("[0-9a-fA-F]{64}"))
    }?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()

    fun isValid(code: String): Boolean {
        val expected = expectedDigest ?: return false
        val normalized = normalize(code)
        if (normalized.isEmpty()) return false
        val actual = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        return MessageDigest.isEqual(expected, actual)
    }

    companion object {
        fun normalize(code: String): String = code.trim()
    }
}

object PremiumAccessPolicy {
    fun withSubscription(state: PremiumState, active: Boolean): PremiumState = state.copy(
        subscriptionActive = active,
        hasPremium = active || state.accessCodeActive,
    )

    fun withAccessCode(state: PremiumState, active: Boolean): PremiumState = state.copy(
        accessCodeActive = active,
        hasPremium = state.subscriptionActive || active,
    )
}
