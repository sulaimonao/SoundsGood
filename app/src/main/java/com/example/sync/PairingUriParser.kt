package com.example.sync

import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI

/**
 * Validated pairing credentials kept strictly in memory for the current sync session.
 * Never logs or exposes [token] in [toString].
 */
data class PairingCredentials(
    val host: String,
    val port: Int,
    val fingerprintHex: String,
    val fingerprintBytes: ByteArray,
    val token: String,
    val address: Inet4Address
) {
    val displayAddress: String
        get() = "$host:$port"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairingCredentials) return false
        return host == other.host &&
            port == other.port &&
            fingerprintHex == other.fingerprintHex &&
            fingerprintBytes.contentEquals(other.fingerprintBytes) &&
            token == other.token
    }

    override fun hashCode(): Int {
        var result = host.hashCode()
        result = 31 * result + port
        result = 31 * result + fingerprintHex.hashCode()
        result = 31 * result + fingerprintBytes.contentHashCode()
        result = 31 * result + token.hashCode()
        return result
    }

    override fun toString(): String {
        return "PairingCredentials(host=$host, port=$port, fingerprint=${fingerprintHex.take(8)}..., token=***)"
    }
}

sealed interface PairingParseResult {
    data class Valid(val credentials: PairingCredentials) : PairingParseResult
    data class Invalid(val reason: String) : PairingParseResult
}

object PairingUriParser {

    private val FINGERPRINT_REGEX = Regex("^[0-9a-f]{64}$")
    private val TOKEN_REGEX = Regex("^[A-Za-z0-9_-]{43}$")
    private val PORT_REGEX = Regex("^[1-9][0-9]{3,4}$")
    private val IPV4_OCTET_REGEX = Regex("^(0|[1-9][0-9]{0,2})$")
    private val EXPECTED_QUERY_KEYS = setOf("v", "host", "port", "fingerprint", "token")

    fun parse(rawInput: String): PairingParseResult {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return PairingParseResult.Invalid("Paste a soundsgood://pair link from the Mac companion.")
        }
        if (trimmed.contains('#')) {
            return PairingParseResult.Invalid("Pairing link must not contain a URI fragment.")
        }
        if (trimmed.any { it.isWhitespace() }) {
            return PairingParseResult.Invalid("Pairing link contains unexpected whitespace.")
        }

        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return PairingParseResult.Invalid("Malformed pairing URI.")
        }

        if (uri.scheme != "soundsgood") {
            return PairingParseResult.Invalid("Pairing link must use the soundsgood:// scheme.")
        }
        if (uri.rawUserInfo != null) {
            return PairingParseResult.Invalid("Pairing link must not include userinfo.")
        }
        if (uri.rawAuthority != "pair" || uri.host != "pair" || uri.port != -1) {
            return PairingParseResult.Invalid("Pairing link host must be exactly 'pair' with no authority port.")
        }
        if (!uri.rawPath.isNullOrEmpty()) {
            return PairingParseResult.Invalid("Pairing link must have an empty path.")
        }
        if (uri.rawFragment != null) {
            return PairingParseResult.Invalid("Pairing link must not contain a fragment.")
        }

        val rawQuery = uri.rawQuery
            ?: return PairingParseResult.Invalid("Pairing link is missing required query parameters.")

        val params = parseStrictQuery(rawQuery)
            ?: return PairingParseResult.Invalid(
                "Pairing link must contain exact unique parameters: v, host, port, fingerprint, token."
            )

        if (params["v"] != "1") {
            return PairingParseResult.Invalid("Unsupported pairing protocol version (expected v=1).")
        }

        val hostStr = params["host"].orEmpty()
        val ipv4Bytes = parseCanonicalPrivateIpv4(hostStr)
            ?: return PairingParseResult.Invalid(
                "Host must be a canonical private IPv4 address (10.x, 172.16-31.x, or 192.168.x)."
            )

        val portStr = params["port"].orEmpty()
        if (!PORT_REGEX.matches(portStr)) {
            return PairingParseResult.Invalid("Port must be a number from 1024 to 65535.")
        }
        val port = portStr.toIntOrNull()?.takeIf { it in 1024..65535 }
            ?: return PairingParseResult.Invalid("Port must be in the range 1024..65535.")

        val fingerprintHex = params["fingerprint"].orEmpty()
        if (!FINGERPRINT_REGEX.matches(fingerprintHex)) {
            return PairingParseResult.Invalid("Certificate fingerprint must be 64 lowercase hexadecimal characters.")
        }

        val token = params["token"].orEmpty()
        if (!TOKEN_REGEX.matches(token)) {
            return PairingParseResult.Invalid("Session token must be 43 unpadded base64url characters.")
        }

        val address = InetAddress.getByAddress(hostStr, ipv4Bytes) as Inet4Address
        val fingerprintBytes = hexToBytes(fingerprintHex)

        return PairingParseResult.Valid(
            PairingCredentials(
                host = hostStr,
                port = port,
                fingerprintHex = fingerprintHex,
                fingerprintBytes = fingerprintBytes,
                token = token,
                address = address
            )
        )
    }

    private fun parseStrictQuery(rawQuery: String): Map<String, String>? {
        if (rawQuery.isEmpty()) return null
        val segments = rawQuery.split('&')
        val map = LinkedHashMap<String, String>(5)
        for (segment in segments) {
            if (segment.isEmpty()) return null
            val eqIdx = segment.indexOf('=')
            if (eqIdx <= 0 || eqIdx == segment.lastIndex) return null
            val key = segment.substring(0, eqIdx)
            val value = segment.substring(eqIdx + 1)
            // Reject percent-encoding or extra '=' characters in strict pairing fields
            if (key !in EXPECTED_QUERY_KEYS) return null
            if (value.contains('=') || value.contains('%') || value.contains('+')) return null
            if (map.containsKey(key)) return null
            map[key] = value
        }
        if (map.keys != EXPECTED_QUERY_KEYS) return null
        return map
    }

    /**
     * Parses canonical dotted-decimal IPv4 bytes strictly without DNS lookups and checks
     * RFC 1918 private network membership (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16).
     */
    fun parseCanonicalPrivateIpv4(host: String): ByteArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in 0 until 4) {
            val part = parts[i]
            if (!IPV4_OCTET_REGEX.matches(part)) return null
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            octets[i] = value
        }
        val a = octets[0]
        val b = octets[1]
        val isPrivateRfc1918 = (a == 10) ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168)
        if (!isPrivateRfc1918) return null
        return byteArrayOf(
            octets[0].toByte(),
            octets[1].toByte(),
            octets[2].toByte(),
            octets[3].toByte()
        )
    }

    fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val out = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            val hi = Character.digit(hex[i], 16)
            val lo = Character.digit(hex[i + 1], 16)
            out[i / 2] = ((hi shl 4) or lo).toByte()
            i += 2
        }
        return out
    }

    fun bytesToLowerHex(bytes: ByteArray): String {
        val hexChars = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(hexChars[v ushr 4])
            sb.append(hexChars[v and 0x0F])
        }
        return sb.toString()
    }

    fun redactSecrets(message: String?, token: String?): String {
        val base = message ?: "Sync error"
        if (token.isNullOrEmpty()) return base
        return base.replace(token, "***")
    }
}
