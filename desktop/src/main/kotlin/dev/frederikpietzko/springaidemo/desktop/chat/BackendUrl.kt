package dev.frederikpietzko.springaidemo.desktop.chat

/** Where the backend runs unless `BACKEND_URL` says otherwise (ADR-0003 port). */
private const val DEFAULT_BACKEND_URL = "http://localhost:8080"

/** The backend base URL: the environment variable `BACKEND_URL` read through [getenv], else localhost:8080. */
fun backendBaseUrl(getenv: (String) -> String?): String = getenv("BACKEND_URL") ?: DEFAULT_BACKEND_URL
