package fr.kinwaypoint.app.security

import android.util.Base64
import fr.kinwaypoint.app.data.PinState
import fr.kinwaypoint.app.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Code PIN parent.
 *
 * Le PIN n'est jamais stocké en clair : seule une empreinte PBKDF2 salée l'est.
 * Après plusieurs erreurs, la saisie est bloquée de plus en plus longtemps
 * (le compteur est persistant : tuer l'appli ne le remet pas à zéro).
 */
object Pin {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 8
    private const val FREE_ATTEMPTS = 5
    private const val ITERATIONS = 60_000
    private const val KEY_BITS = 256

    sealed interface Result {
        data object Ok : Result
        data class Wrong(val remainingBeforeLock: Int) : Result
        data class Locked(val untilMillis: Long) : Result
    }

    fun isWellFormed(pin: String): Boolean =
        pin.length in MIN_LENGTH..MAX_LENGTH && pin.all(Char::isDigit)

    suspend fun set(repository: SettingsRepository, pin: String) {
        require(isWellFormed(pin))
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = withContext(Dispatchers.Default) { derive(pin, salt) }
        repository.savePin(encode(hash), encode(salt))
    }

    suspend fun verify(repository: SettingsRepository, pin: String, now: Long = System.currentTimeMillis()): Result {
        val state = repository.pin.first()
        if (!state.isSet) return Result.Ok
        if (state.lockedUntil > now) return Result.Locked(state.lockedUntil)

        val expected = decode(state.hash)
        val actual = withContext(Dispatchers.Default) { derive(pin, decode(state.salt)) }

        return if (MessageDigest.isEqual(expected, actual)) {
            repository.recordPinResult(0, 0L)
            Result.Ok
        } else {
            val fails = state.failedAttempts + 1
            val lockedUntil = lockUntil(fails, now)
            repository.recordPinResult(fails, lockedUntil)
            if (lockedUntil > now) Result.Locked(lockedUntil) else Result.Wrong(FREE_ATTEMPTS - fails)
        }
    }

    /** 5 essais libres, puis 30 s, 1 min, 2 min… plafonné à 1 h. */
    private fun lockUntil(fails: Int, now: Long): Long {
        if (fails < FREE_ATTEMPTS) return 0L
        val step = (fails - FREE_ATTEMPTS).coerceAtMost(7)
        val seconds = (30L shl step).coerceAtMost(3600L)
        return now + seconds * 1000L
    }

    fun isLocked(state: PinState, now: Long = System.currentTimeMillis()) = state.lockedUntil > now

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(text: String) = Base64.decode(text, Base64.NO_WRAP)
}
