package dev.frederikpietzko.springaidemo.cli

import kotlin.system.exitProcess

/** The `kaiju` executable. */
fun main(args: Array<String>) {
    exitProcess(kaiju(args.toList(), System.getenv(), SystemConsole))
}
