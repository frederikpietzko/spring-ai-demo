package dev.frederikpietzko.springaidemo.cli.daemon

/** Why [runDaemon] returned. */
enum class DaemonExit {
    /** Another daemon holds the lock of the state directory. */
    ALREADY_RUNNING,

    /** The daemon was idle for its idle time, or was asked to shut down. */
    SHUT_DOWN,
}
