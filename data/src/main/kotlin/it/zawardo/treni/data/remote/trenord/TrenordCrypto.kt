package it.zawardo.treni.data.remote.trenord

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Le risposte del BFF Trenord non sono JSON: sono AES cifrato.
 *
 * Non e' una misura di sicurezza — la chiave sta in chiaro nel bundle JavaScript
 * del loro store, da cui e' stata ricavata — ma un offuscamento. Senza questo
 * passaggio la risposta arriva come binario illeggibile.
 *
 * Lo schema replica esattamente quello del loro client:
 *
 * ```js
 * generateKey(k)      = SHA256(k)
 * decryptData(buf, k) = AES.decrypt(buf, k, { mode: ECB, padding: Pkcs7 })
 * ```
 *
 * ECB e' una scelta loro, non nostra: qui si sta solo leggendo un formato
 * altrui. `javax.crypto` e' nella piattaforma, quindi non serve alcuna
 * dipendenza aggiuntiva.
 */
internal object TrenordCrypto {

    private const val OBFUSCATION_KEY = "8hI&WK=1NQ55*f^yyZkdEGWYyN{S"

    private val key: SecretKeySpec by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(OBFUSCATION_KEY.toByteArray(Charsets.UTF_8))
        SecretKeySpec(digest, "AES")
    }

    /**
     * Restituisce il JSON in chiaro, o null se il corpo non e' decifrabile:
     * cambiassero chiave o schema, e' meglio degradare senza risultati che
     * far esplodere l'app.
     */
    fun decrypt(bytes: ByteArray): String? {
        if (bytes.isEmpty() || bytes.size % 16 != 0) return null
        return runCatching {
            // "AES/ECB/PKCS5Padding" e' l'equivalente JVM del Pkcs7 di CryptoJS:
            // per blocchi da 16 byte i due schemi coincidono.
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key)
            String(cipher.doFinal(bytes), Charsets.UTF_8)
        }.getOrNull()
    }
}
