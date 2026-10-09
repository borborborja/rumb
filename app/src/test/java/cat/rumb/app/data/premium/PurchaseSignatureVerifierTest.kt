package cat.rumb.app.data.premium

import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PurchaseSignatureVerifierTest {
    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded)
    private val receipt = """{"packageName":"cat.rumb.app","productId":"rumb_premium","purchaseState":0}"""

    private fun sign(data: String): String = Base64.getEncoder().encodeToString(
        Signature.getInstance("SHA1withRSA").run {
            initSign(keyPair.private)
            update(data.toByteArray(Charsets.UTF_8))
            sign()
        },
    )

    @Test fun authenticReceiptVerifies() {
        assertThat(PurchaseSignatureVerifier(publicKey).verify(receipt, sign(receipt))).isTrue()
    }

    @Test fun modifiedReceiptOrSignatureFails() {
        val verifier = PurchaseSignatureVerifier(publicKey)
        assertThat(verifier.verify(receipt.replace("rumb_premium", "other"), sign(receipt))).isFalse()
        assertThat(verifier.verify(receipt, "not base64")).isFalse()
    }

    @Test fun absentOrMalformedKeyFailsClosed() {
        assertThat(PurchaseSignatureVerifier("").verify(receipt, sign(receipt))).isFalse()
        assertThat(PurchaseSignatureVerifier("invalid").verify(receipt, sign(receipt))).isFalse()
    }

    @Test fun wrongSigningKeyFails() {
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val otherKey = Base64.getEncoder().encodeToString(other.public.encoded)
        assertThat(PurchaseSignatureVerifier(otherKey).verify(receipt, sign(receipt))).isFalse()
    }
}
