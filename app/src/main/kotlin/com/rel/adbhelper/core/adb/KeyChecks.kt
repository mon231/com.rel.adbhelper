package com.rel.adbhelper.core.adb

import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Date
import java.util.concurrent.TimeUnit

/** Re-issue the certificate when it expires within this window. */
internal val CERT_RENEW_WINDOW_MS: Long = TimeUnit.DAYS.toMillis(30)

/** True when [publicKey] is the public half of [privateKey] (RSA: equal modulus). */
internal fun keysMatch(privateKey: PrivateKey, publicKey: PublicKey): Boolean {
    val priv = privateKey as? RSAPrivateKey ?: return false
    val pub = publicKey as? RSAPublicKey ?: return false
    return priv.modulus == pub.modulus
}

/** True when a certificate valid until [notAfter] is expired or within 30 days of expiry at [now]. */
internal fun certNeedsReissue(notAfter: Date, now: Date): Boolean =
    notAfter.time - now.time <= CERT_RENEW_WINDOW_MS

/** What to do with the files on disk. The private key is the identity devices recognise. */
internal enum class IdentityAction { KEEP, REISSUE_CERT, REGENERATE }

/**
 * The private key is kept whenever it is usable; only an unusable key (missing, unparsable, or
 * not an RSA CRT key, so no certificate can be issued for it) gets replaced. Any certificate
 * problem is fixed by issuing a new certificate from the same key.
 */
internal fun identityAction(
    keyUsable: Boolean,
    certParses: Boolean,
    matches: Boolean,
    needsReissue: Boolean,
): IdentityAction = when {
    !keyUsable -> IdentityAction.REGENERATE
    !certParses || !matches || needsReissue -> IdentityAction.REISSUE_CERT
    else -> IdentityAction.KEEP
}
