package com.example.simplertmp

import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import java.util.logging.Level
import java.util.logging.Logger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec


/**
 * Some helper utilities for SHA256, mostly (used during handshake)
 * This is separated in order to be more easily replaced on platforms that
 * do not have the javax.crypto.* and/or java.security.* packages
 *
 * This implementation is directly inspired by the RTMPHandshake class of the
 * Red5  Open Source Flash Server project
 *
 * @author francois
 */
class Crypto {
    private lateinit var hmacSHA256: Mac

    /**
     * Calculates an HMAC SHA256 hash using a default key length.
     *
     * @return hmac hashed bytes
     */
    fun calculateHmacSHA256(input: ByteArray, key: ByteArray): ByteArray {
        try {
            hmacSHA256.init(SecretKeySpec(key, "HmacSHA256"))
            return hmacSHA256.doFinal(input)
        } catch (e: InvalidKeyException) {
            Logger.getLogger(TAG).log(Level.SEVERE, "Invalid key")
            throw e
        }
    }

    /**
     * Calculates an HMAC SHA256 hash using a set key length.
     *
     * @return hmac hashed bytes
     */
    fun calculateHmacSHA256(input: ByteArray, key: ByteArray, length: Int): ByteArray {
        try {
            hmacSHA256.init(SecretKeySpec(key, 0, length, "HmacSHA256"))
            return hmacSHA256.doFinal(input)
        } catch (e: InvalidKeyException) {
            Logger.getLogger(TAG).log(Level.SEVERE, "Invalid key")
            throw e
        }
    }

    companion object {
        private const val TAG = "Crypto"
    }

    init {
        try {
            hmacSHA256 = Mac.getInstance("HmacSHA256")
        } catch (e: SecurityException) {
            Logger.getLogger(TAG).log(Level.SEVERE, "Security exception when getting HMAC")
        } catch (e: NoSuchAlgorithmException) {
            Logger.getLogger(TAG).log(Level.SEVERE, "HMAC SHA256 does not exist")
        }
    }
}