package dev.frederikpietzko.springaidemo.authserver

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/** Entry point of the OAuth 2 authorization server that issues the agents' access tokens (ADR-0003, ADR-0009). */
@SpringBootApplication
class AuthServerApplication

fun main(args: Array<String>) {
    runApplication<AuthServerApplication>(*args)
}
