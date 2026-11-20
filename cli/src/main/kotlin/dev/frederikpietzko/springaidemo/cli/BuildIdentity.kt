package dev.frederikpietzko.springaidemo.cli

import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.inputStream

private const val BUFFER_SIZE = 64 * 1024

/** The build identity of [executable]: the SHA-256 of the file in hex, or `unknown` when there is no such file. */
fun buildIdentity(executable: Path?): String {
    val file = executable?.takeIf { it.exists() } ?: return "unknown"
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHexString()
}
