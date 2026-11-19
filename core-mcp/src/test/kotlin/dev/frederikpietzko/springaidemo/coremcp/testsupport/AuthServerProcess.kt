package dev.frederikpietzko.springaidemo.coremcp.testsupport

import org.awaitility.Awaitility.await
import org.awaitility.core.ConditionTimeoutException
import org.springframework.context.ApplicationListener
import org.springframework.context.event.ContextClosedEvent
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

const val CLAIMANT_AGENT = "claimant-agent"
const val ADJUSTER_AGENT = "adjuster-agent"
const val CLAIMANT_SCOPES = "policies:read claims:read claims:write"
const val ADJUSTER_SCOPES = "$CLAIMANT_SCOPES payout:write analytics:read"

private const val MAIN_CLASS = "dev.frederikpietzko.springaidemo.authserver.AuthServerApplicationKt"
private val STARTUP_TIMEOUT = Duration.ofSeconds(90)
private val POLL_INTERVAL = Duration.ofMillis(200)
private const val STOP_TIMEOUT_SECONDS = 10L

/**
 * The real `auth-server` application, started as a separate JVM on a free loopback port with client secrets generated
 * for this run (ADR-0009). Its classpath comes from the Gradle configuration `authServerRuntime`. The process is
 * stopped when the application context closes, before the JVM exits.
 */
class AuthServerProcess : ApplicationListener<ContextClosedEvent> {
    private val claimantSecret = UUID.randomUUID().toString()
    private val adjusterSecret = UUID.randomUUID().toString()
    private val port = freeLoopbackPort()
    private val log = File.createTempFile("auth-server-", ".log").apply { deleteOnExit() }
    private val http = HttpClient.newHttpClient()

    /** The issuer of every token, as `core-mcp` must be configured to expect it. */
    val issuer = "http://127.0.0.1:$port"

    private val process: Process = start()

    /** `true` while the forked auth-server runs. */
    val isRunning: Boolean get() = process.isAlive

    /** A fresh access token for [clientId] with exactly [scopes], from the token endpoint of the process. */
    fun token(clientId: String, scopes: String): String {
        val secret = if (clientId == CLAIMANT_AGENT) claimantSecret else adjusterSecret
        val basic = Base64.getEncoder().encodeToString("$clientId:$secret".toByteArray())
        val request = HttpRequest.newBuilder(URI.create("$issuer/oauth2/token"))
            .header("Authorization", "Basic $basic")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "grant_type=client_credentials&scope=${URLEncoder.encode(scopes, Charsets.UTF_8)}",
                ),
            )
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == HTTP_OK) {
            "no token for $clientId: ${response.statusCode()} ${response.body()}"
        }
        return JsonMapper.shared().readTree(response.body())["access_token"].stringValue()
    }

    override fun onApplicationEvent(event: ContextClosedEvent) {
        process.destroy()
        if (!process.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly().waitFor()
    }

    private fun start(): Process {
        val classpath = checkNotNull(System.getProperty("auth-server.classpath")) {
            "system property auth-server.classpath is missing (set by the Gradle test task)"
        }
        val java = File(System.getProperty("java.home"), "bin/java").path
        val arguments = ["--server.port=$port", "--server.address=127.0.0.1"]
        val builder = ProcessBuilder(java, "-cp", classpath, MAIN_CLASS, *arguments.toTypedArray())
            .redirectErrorStream(true)
            .redirectOutput(log)
        builder.environment() += mapOf(
            "AUTH_SERVER_URL" to issuer,
            "CLAIMANT_AGENT_SECRET" to claimantSecret,
            "ADJUSTER_AGENT_SECRET" to adjusterSecret,
        )
        return builder.start().also { awaitReady(it) }
    }

    /** Waits for the metadata endpoint; a crash or a timeout fails with the child's output. */
    private fun awaitReady(process: Process) {
        try {
            await().atMost(STARTUP_TIMEOUT).pollInterval(POLL_INTERVAL).until {
                check(process.isAlive) { "auth-server exited with ${process.exitValue()}:\n${log.readText()}" }
                answersMetadata()
            }
        } catch (timeout: ConditionTimeoutException) {
            process.destroyForcibly()
            throw IllegalStateException("auth-server did not start in $STARTUP_TIMEOUT:\n${log.readText()}", timeout)
        }
    }

    private fun answersMetadata(): Boolean =
        try {
            val request = HttpRequest.newBuilder(URI.create("$issuer/.well-known/oauth-authorization-server")).build()
            http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == HTTP_OK
        } catch (_: IOException) {
            false
        }

    private companion object {
        const val HTTP_OK = 200

        fun freeLoopbackPort(): Int = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
    }
}
