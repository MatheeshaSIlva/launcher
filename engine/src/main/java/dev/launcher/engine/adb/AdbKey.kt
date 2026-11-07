package dev.launcher.engine.adb

import android.annotation.SuppressLint
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/** Adapted from Shizuku v13.6.0 AdbKey.kt (Apache-2.0); see adb-NOTICE. */
class AdbKey internal constructor(private val privateKey: RSAPrivateCrtKey, name: String) {
    private val publicKey = KeyFactory.getInstance("RSA").generatePublic(
        RSAPublicKeySpec(privateKey.modulus, privateKey.publicExponent),
    )
    init {
        require(privateKey.modulus.bitLength() == 2048 && privateKey.publicExponent == RSAKeyGenParameterSpec.F4)
        require(name.length in 1..64 && name.all { it in '!'..'~' }) { "Invalid ADB identity name" }
    }

    private val publicBytes = run {
        val modulus = privateKey.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val rr = BigInteger.ONE.shiftLeft(4096).mod(modulus)
        val buffer = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(64).putInt(modulus.modInverse(r32).negate().toInt())
        for (value in arrayOf(modulus, rr)) repeat(64) { buffer.putInt(value.shiftRight(it * 32).toInt()) }
        buffer.putInt(privateKey.publicExponent.toInt())
        Base64.getEncoder().encode(buffer.array()) + " $name\u0000".toByteArray(Charsets.US_ASCII)
    }
    val adbPublicKey: ByteArray get() = publicBytes.copyOf()

    private val certificate: X509Certificate by lazy {
        val subject = X500Name("CN=not-a-launcher-adb")
        val encoded = X509v3CertificateBuilder(subject, BigInteger.ONE, Date(0), Date(4_102_444_800_000L),
            subject, SubjectPublicKeyInfo.getInstance(publicKey.encoded))
            .build(JcaContentSignerBuilder("SHA256withRSA").build(privateKey)).encoded
        CertificateFactory.getInstance("X.509").generateCertificate(encoded.inputStream()) as X509Certificate
    }

    private val keyManager = object : X509ExtendedKeyManager() {
        override fun chooseClientAlias(types: Array<out String>, issuers: Array<out Principal>?, socket: Socket?) =
            if ("RSA" in types) "adb" else null
        override fun getCertificateChain(alias: String?) = if (alias == "adb") arrayOf(certificate) else null
        override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == "adb") privateKey else null
        override fun getClientAliases(type: String?, issuers: Array<out Principal>?) =
            if (type == "RSA") arrayOf("adb") else null
        override fun getServerAliases(type: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseServerAlias(type: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    }

    /**
     * ADB uses self-signed certificates, not Web PKI. Pairing authenticates with SPAKE2 plus the TLS
     * exporter; adbd subsequently authorizes our stored RSA identity. This context must stay ADB-only.
     * It does not establish a Web-PKI authenticated server identity for the connection client.
     */
    internal val sslContext: SSLContext by lazy {
        SSLContext.getInstance("TLSv1.3").apply {
            init(arrayOf(keyManager), arrayOf(AdbTrustManager()), SecureRandom())
        }
    }
}

@SuppressLint("TrustAllX509TrustManager", "CustomX509TrustManager")
private class AdbTrustManager : X509ExtendedTrustManager() {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
