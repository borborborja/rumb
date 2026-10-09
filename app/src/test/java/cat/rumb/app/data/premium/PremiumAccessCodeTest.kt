package cat.rumb.app.data.premium

import java.security.MessageDigest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PremiumAccessCodeTest {
    private val code = "RUMB-example-test-code"
    private val hash = MessageDigest.getInstance("SHA-256")
        .digest(code.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private val verifier = PremiumAccessCodeVerifier(hash)

    @Test fun validCodeTrimsWhitespaceAndRemainsCaseSensitive() {
        assertThat(verifier.isValid("  $code\n")).isTrue()
        assertThat(verifier.isValid(code.lowercase())).isFalse()
    }

    @Test fun tamperedCodeOrThePublicDigestCannotActivateDemo() {
        assertThat(verifier.isValid("$code-tampered")).isFalse()
        assertThat(verifier.isValid(hash)).isFalse()
        assertThat(verifier.isValid("")).isFalse()
    }

    @Test fun absentOrMalformedPublicHashFailsClosed() {
        assertThat(PremiumAccessCodeVerifier("").isValid(code)).isFalse()
        assertThat(PremiumAccessCodeVerifier("invalid").isValid(code)).isFalse()
    }

    @Test fun demoAndSubscriptionAreSeparateGrants() {
        val demo = PremiumAccessPolicy.withAccessCode(PremiumState(), verifier.isValid(code))
        assertThat(demo.hasPremium).isTrue()
        assertThat(demo.accessCodeActive).isTrue()
        assertThat(demo.subscriptionActive).isFalse()
        assertThat(demo.ownershipVerified).isFalse()

        val emptyStoreResult = PremiumAccessPolicy.withSubscription(demo, false)
        assertThat(emptyStoreResult.hasPremium).isTrue()
        assertThat(emptyStoreResult.subscriptionActive).isFalse()
    }

    @Test fun removingDemoDoesNotRevokeAnActualSubscription() {
        val paid = PremiumAccessPolicy.withSubscription(PremiumState(), true)
        val combined = PremiumAccessPolicy.withAccessCode(paid, true)
        val demoRemoved = PremiumAccessPolicy.withAccessCode(combined, false)
        assertThat(demoRemoved.hasPremium).isTrue()
        assertThat(demoRemoved.subscriptionActive).isTrue()
        assertThat(demoRemoved.accessCodeActive).isFalse()
    }

    @Test fun aTamperedStoredCodeRemovesDemoWithoutCreatingPaidOwnership() {
        val demo = PremiumAccessPolicy.withAccessCode(PremiumState(), true)
        val rechecked = PremiumAccessPolicy.withAccessCode(demo, verifier.isValid("$code-x"))
        assertThat(rechecked.hasPremium).isFalse()
        assertThat(rechecked.accessCodeActive).isFalse()
        assertThat(rechecked.subscriptionActive).isFalse()
    }
}
