package dev.frederikpietzko.springaidemo.cli

/** Where the CLI writes: standard output and standard error. Text is written as given, newlines included. */
interface Console {
    /** Writes [text] to standard output. */
    fun out(text: String)

    /** Writes [text] to standard error. */
    fun err(text: String)
}

/** The process's own standard output and standard error. */
object SystemConsole : Console {
    override fun out(text: String) {
        print(text)
        System.out.flush()
    }

    override fun err(text: String) {
        System.err.print(text)
        System.err.flush()
    }
}

/** Writes [line] and a newline to standard output. */
fun Console.outLine(line: String) = out(line + "\n")

/** Writes [line] and a newline to standard error. */
fun Console.errLine(line: String) = err(line + "\n")
