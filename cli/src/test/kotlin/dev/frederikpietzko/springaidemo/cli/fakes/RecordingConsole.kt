package dev.frederikpietzko.springaidemo.cli.fakes

import dev.frederikpietzko.springaidemo.cli.Console

/** A [Console] that keeps what was written to standard output and standard error. */
class RecordingConsole : Console {
    private val outBuffer = StringBuffer()
    private val errBuffer = StringBuffer()

    /** Everything written to standard output. */
    val out: String get() = outBuffer.toString()

    /** Everything written to standard error. */
    val err: String get() = errBuffer.toString()

    override fun out(text: String) {
        val _ = outBuffer.append(text)
    }

    override fun err(text: String) {
        val _ = errBuffer.append(text)
    }
}
