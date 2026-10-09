package cat.rumb.app.data.premium

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Local receipt integrity check. Current subscription ownership still comes from Google Play. */
class PurchaseSignatureVerifier(publicKeyBase64: String) {
    private val publicKey = runCatching {
        val keyBytes = Base64.getDecoder().decode(publicKeyBase64.filterNot(Char::isWhitespace))
        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
    }.getOrNull()

    fun verify(originalJson: String, signatureBase64: String): Boolean {
        val key = publicKey ?: return false
        if (originalJson.isBlank() || signatureBase64.isBlank()) return false
        return runCatching {
            Signature.getInstance("SHA1withRSA").run {
                initVerify(key)
                update(originalJson.toByteArray(Charsets.UTF_8))
                verify(Base64.getDecoder().decode(signatureBase64))
            }
        }.getOrDefault(false)
    }
}
