package dev.frederikpietzko.springaidemo.cli.daemon

import kotlinx.serialization.Serializable
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.moveTo
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** The environment variable that overrides the state directory. */
const val STATE_DIR_VARIABLE = "KAIJU_STATE_DIR"

/** What the daemon writes to `daemon.json` once it listens: its process, port and build. */
@Serializable
data class DaemonInfo(val pid: Long, val port: Int, val build: String)

/**
 * The daemon's files in one state directory: `daemon.lock` (held by the daemon for its lifetime), `daemon.json`
 * ([DaemonInfo]), `start.lock` (held by a CLI while it starts a daemon) and `daemon.log`.
 */
class DaemonFiles(val dir: Path) {
    /** Held by the running daemon. */
    val daemonLock: Path get() = dir.resolve("daemon.lock")

    /** The running daemon's [DaemonInfo]. */
    val info: Path get() = dir.resolve("daemon.json")

    /** Held by a CLI while it checks for and starts a daemon. */
    val startLock: Path get() = dir.resolve("start.lock")

    /** The daemon's output. */
    val log: Path get() = dir.resolve("daemon.log")

    /** The recorded [DaemonInfo], or `null` when there is none or it is unreadable. */
    fun readInfo(): DaemonInfo? = info.takeIf { it.exists() }?.let {
        try {
            DaemonJson.decodeFromString(DaemonInfo.serializer(), it.readText())
        } catch (_: kotlinx.serialization.SerializationException) {
            null
        }
    }

    /** Writes [value] to `daemon.json` in one step (write, then move). */
    fun writeInfo(value: DaemonInfo) {
        dir.createDirectories()
        val temp = dir.resolve("daemon.json.tmp")
        temp.writeText(DaemonJson.encodeToString(DaemonInfo.serializer(), value))
        val _ = temp.moveTo(info, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Removes `daemon.json`. */
    fun deleteInfo() {
        val _ = info.deleteIfExists()
    }

    /** Companion with the default location. */
    companion object {
        /** The state directory: [STATE_DIR_VARIABLE], else `.kaiju/` next to [executable], else `./.kaiju`. */
        fun locate(env: Map<String, String>, executable: Path?): DaemonFiles = DaemonFiles(
            env[STATE_DIR_VARIABLE]?.takeIf { it.isNotBlank() }?.let(::Path)
                ?: executable?.takeUnless(::isJavaLauncher)?.toAbsolutePath()?.parent?.resolve(".kaiju")
                ?: Path(".kaiju").toAbsolutePath(),
        )
    }
}

/** Whether [executable] is a `java` launcher, which means the CLI runs on the JVM, not as the native executable. */
fun isJavaLauncher(executable: Path): Boolean = executable.fileName?.toString() in JAVA_LAUNCHERS

private val JAVA_LAUNCHERS = setOf("java", "java.exe")

/** An exclusive lock on a file, held until [close]d. */
class HeldLock(private val channel: FileChannel, private val lock: FileLock) : AutoCloseable {
    override fun close() {
        lock.release()
        channel.close()
    }

    /** Opening and taking locks. */
    companion object {
        /** Takes the lock on [file] if no one holds it (in any process), or answers `null`. */
        fun tryTake(file: Path): HeldLock? {
            file.parent?.createDirectories()
            val channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            return if (lock == null) {
                channel.close()
                null
            } else {
                HeldLock(channel, lock)
            }
        }
    }
}
