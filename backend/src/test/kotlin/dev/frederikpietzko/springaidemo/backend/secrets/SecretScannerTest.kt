package dev.frederikpietzko.springaidemo.backend.secrets

import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Secrets are assembled at runtime so that this source never matches its own repository scan.
 *
 * Rows carry the file name because the assignment rule depends on the file kind: source files (`.kt`, `.kts`,
 * `.java`, `.gradle`, other code) check only quoted string literals (with an optional Kotlin type,
 * `val password: String = "..."`); yaml, yml, properties and env files check quoted values, bare words and whole-line
 * values (`password: pa;ss(word) 42`); every other file (md, json, http, extensionless, ...) checks quoted and bare
 * words. Shape rules (keys, JWTs, `Bearer` tokens, encoded passwords (`{noop}` plus a literal), private-key headers,
 * userinfo URLs) apply to every file.
 *
 * Deliberately not detected (they would flag prose or code): credential names with a space such as `"API Key": "..."`,
 * values that do not sit on one line, bare values in source files (`val token: String`, `lateinit var apiKey: String`,
 * `val token = chunk.result.output.text`), a quoted value that is itself an env-var name or a 3+ segment property path
 * with a credential word (`"ANTHROPIC_API_KEY"`, `"spring.ai.anthropic.api-key"`), JSON written as an escaped Kotlin
 * string literal (`"{\"token\":\"Hel\"}"`), a value shorter than 12 characters or with whitespace next to a name that
 * is only a token (model token text such as `"token":"Hel"`), whole-line values after a name that is not at the start
 * of a line or outside yaml, yml, properties and env files, unusual Kotlin type annotations (`() -> String`), numbers
 * and durations next to a name without a password, secret, api key or credential part, the well-known OAuth words
 * (`Bearer`, `client_secret_basic`), a bare `true`/`false`, and placeholders without a default or with a harmless
 * default. Deliberately detected: bare words in config files (`password: kaiju.demo`), long token values
 * (`access_token: abc123def456`) and secret defaults inside placeholders, however nested.
 */
class SecretScannerTest {
    @TempDir
    private lateinit var dir: Path

    @ParameterizedTest(name = "{0}")
    @MethodSource("secretShapes")
    fun `detects a secret and names the file and line`(fileName: String, rule: String, line: String) {
        val file = dir.resolve(fileName)
        file.writeText("first line\nsecond line\n$line\nlast line\n")

        val findings = SecretScanner.scanTree(dir)

        assertTrue(findings.any { it.file == file && it.line == 3 && it.rule == rule }, "findings: $findings")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("harmlessLines")
    fun `ignores placeholders, documented defaults, empty values and prose`(fileName: String, line: String) {
        dir.resolve(fileName).writeText("$line\n")

        assertEquals([], SecretScanner.scanTree(dir))
    }

    @Test
    fun `a documented default value is only allowed for its own key`() {
        dir.resolve("config.yaml").writeText("api_key: demo\n")

        assertEquals(["assignment"], SecretScanner.scanTree(dir).map { it.rule })
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            ".env.local", ".env.production", "secrets.env", "server.pem", "requests.http", "id_rsa",
            "deploy/id_ed25519",
            "notes.md", "config/app.conf", "Dockerfile", ".claude/settings.json", ".github/workflows/ci.yml",
        ],
    )
    fun `scans text files of every shape`(relativePath: String) {
        val file = dir.resolve(relativePath)
        file.parent.createDirectories()
        file.writeText("sk-ant-" + "x".repeat(40))

        assertEquals([relativePath], SecretScanner.scanTree(dir).map { dir.relativize(it.file).toString() }.distinct())
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "build/out.txt", "module/build/out.txt", ".gradle/state.txt", ".git/config.txt", ".idea/workspace.xml",
            ".kotlin/errors.txt", "node_modules/pkg/readme.md", ".claude/worktrees/other/notes.md", "app.png",
            "lib/tool.jar", ".env", "module/.env", "signing.key", ".claude/settings.local.json",
        ],
    )
    fun `skips build output, tool directories, binary types and documented local-only files`(relativePath: String) {
        val file = dir.resolve(relativePath)
        file.parent.createDirectories()
        file.writeText("sk-ant-" + "x".repeat(40))

        assertEquals([], SecretScanner.scanTree(dir))
    }

    @Test
    fun `scans a file that is not valid UTF-8 instead of failing`() {
        val file = dir.resolve("legacy.properties")
        file.writeBytes("# caf\u00e9\napi_key=abc123def456\n".toByteArray(Charsets.ISO_8859_1))

        val findings = SecretScanner.scanTree(dir)

        assertEquals([SecretFinding(file = file, line = 2, rule = "assignment")], findings)
    }

    @Test
    fun `skips a file whose content is binary`() {
        dir.resolve("blob.dat").writeBytes(byteArrayOf(0, 1, 2) + ("sk-ant-" + "x".repeat(40)).toByteArray())

        assertEquals([], SecretScanner.scanTree(dir))
    }

    companion object {
        private const val CONFIG = "notes.md"
        private const val YAML = "application.yaml"
        private const val KOTLIN = "Main.kt"

        private fun config(rule: String, line: String) = arrayOf(CONFIG, rule, line)

        private fun yaml(rule: String, line: String) = arrayOf(YAML, rule, line)

        private fun kotlin(rule: String, line: String) = arrayOf(KOTLIN, rule, line)

        private fun harmless(fileName: String, vararg lines: String) = lines.map { arrayOf(fileName, it) }

        @JvmStatic
        fun secretShapes(): List<Array<String>> = [
            config("anthropic-key", "sk-ant-" + "x".repeat(40)),
            config("generic-sk-key", "key = sk-" + "a".repeat(40)),
            config("aws-access-key-id", "AKIA" + "ABCDEFGH12345678"),
            config("github-token", "ghp_" + "a".repeat(36)),
            config("private-key-header", "-----BEGIN " + "RSA PRIVATE KEY-----"),
            config("jwt", "eyJ" + "a".repeat(12) + ".eyJ" + "b".repeat(12) + "." + "c".repeat(12)),
            config("assignment", "api_key: " + "abc123def456"),
            config("assignment", "DB_PASSWORD=" + "hunter2"),
            config("assignment", "val apiKey = \"" + "abc123def456" + "\""),
            config("assignment", "\"password\": \"" + "hunter2" + "\""),
            config("assignment", "password: \"" + "correct horse battery" + "\""),
            config("assignment", "client-secret: {noop}" + "s3cret"),
            config("assignment", "password: \${DB_PASSWORD:" + "Pr0dPass}"),
            config("assignment", "passwd=" + "hunter2"),
            config("assignment", "credential: " + "abc123def456"),
            config("assignment", "access_token: " + "abc123def456"),
            config("assignment", "token-uri: postgres://user:" + "pw@host/db"),
            config("url-credentials", "postgres://user:" + "pw@host/db"),
            config("assignment", "DB_PASSWORD=" + "20251119"),
            config("assignment", "POSTGRES_PASSWORD: " + "12345678"),
            config("assignment", "password: " + "12345678"),
            config("assignment", "token-uri: https://auth.example.com/oauth2/token?client_secret=" + "abc123def"),
            config("assignment", "client-secret: " + "s3cr3t-Value"),
            config("assignment", "password: \${DB_PASSWORD:" + "1234}"),
            config("assignment", "{\"token\":\"" + "abc123def456ghi789" + "\"}"),
            config("anthropic-key", "{\"token\":\"sk-ant-" + "x".repeat(40) + "\"}"),
            // bare words: with the type-annotation and property-path exemptions gone these are real values
            arrayOf("compose.yaml", "assignment", "POSTGRES_PASSWORD: " + "Postgres"),
            arrayOf(".env.local", "assignment", "DB_PASSWORD=" + "Changeme"),
            yaml("assignment", "password: " + "kaiju.demo"),
            yaml("assignment", "client-secret: " + "claimant.secret"),
            yaml("assignment", "api-key: " + "Abcdefghijklmnopqrstuvwxyz"),
            yaml("assignment", "client-secret: \${CLAIMANT_CLIENT_SECRET:{noop}" + "claimant-secret}"),
            yaml("assignment", "password: \${DB_PASSWORD:\${LEGACY:" + "hunter2}}"),
            arrayOf(
                "compose.yaml",
                "url-credentials",
                "DATABASE_URL: postgres://\${POSTGRES_USER}:" + "s3cret@db/backend",
            ),
        ] + codeAndTokenShapes()

        private fun codeAndTokenShapes(): List<Array<String>> = [
            // typed declarations, builder calls with an encoded password, credential-named constants with a literal
            kotlin("assignment", "val password: String = \"" + "Kaiju2026" + "\""),
            kotlin("assignment", "const val API_KEY: String = \"" + "abc123def456" + "\""),
            kotlin("encoded-password", ".clientSecret(\"{noop}" + "adjuster-secret\")"),
            kotlin("assignment", "val clientSecret = \"" + "claimant.secret" + "\""),
            kotlin("assignment", "val password = \"" + "HUNTER2" + "\""),
            yaml("encoded-password", "client-secret: \"{bcrypt}" + "abc123\""),
            // bearer tokens: short or missing JWT signatures and opaque tokens
            arrayOf(
                "requests.http",
                "jwt",
                "Authorization: Bearer " + "eyJhbGciOiJIUzI1NiJ9" + ".eyJzdWIiOiIxIn0." + "abc",
            ),
            arrayOf(
                "requests.http",
                "jwt",
                "Authorization: Bearer " + "eyJhbGciOiJub25lIn0" + ".eyJzdWIiOiIxMjM0NTY3ODkwIn0.",
            ),
            arrayOf("requests.http", "bearer-token", "Authorization: Bearer " + "9f8e7d6c5b4a3f2e1d0c9b8a7f6e5d4c"),
            // bare values in config files run to the end of the line (or a comment)
            yaml("assignment", "password: " + "pa;ss(word) 42"),
            yaml("assignment", "password: " + "correct horse battery staple # local only"),
            arrayOf(".env.local", "assignment", "DB_PASSWORD=" + "Xk9;mP2,qL7"),
            // a token-only name with a long value is still a credential
            kotlin("assignment", "val body = \"\"\"{\"token\":\"" + "abc123def456ghi789" + "\"}\"\"\""),
            // source files: quoted literals only
            kotlin("assignment", "val password = \"" + "hunter2" + "\""),
            kotlin("anthropic-key", "val apiKey = \"sk-ant-" + "x".repeat(40) + "\""),
            kotlin("assignment", "val apiKey = \"" + "abc123def456" + "\""),
            arrayOf("build.gradle.kts", "assignment", "password = \"" + "hunter2" + "\""),
            arrayOf("Config.java", "assignment", "String password = \"" + "hunter2" + "\";"),
        ]

        @JvmStatic
        fun harmlessLines(): List<Array<String>> =
            harmless(
                YAML,
                "password: \${DB_PASSWORD:demo}",
                "password: \${DB_PASSWORD}",
                "password: \${DB_PASSWORD:-}",
                "DB_PASSWORD: demo",
                "POSTGRES_PASSWORD=demo",
                "DB_USER=demo",
                "POSTGRES_USER: demo",
                "password:",
                "api_key=",
                "The api key is read from the environment.",
                "Never commit a token or password to this file.",
                "A suspected secret: the finding is reported.",
                "\"Authorization\": \"\${API_KEY:-}\"",
                "max-tokens: 1024",
                "spring.ai.anthropic.chat.options.max-tokens=1024",
                "token-uri: http://localhost:9000/oauth2/token",
                "token-uri: \${TOKEN_URI:http://localhost:9000/oauth2/token}",
                "token-type: Bearer",
                "token-endpoint-auth-method: client_secret_basic",
                "client-authentication-methods: client_secret_basic",
                "access-token-time-to-live: 5m",
                "access-token-time-to-live: \${TTL:5m}",
                "access-token-time-to-live: PT5M",
                "client-secret: \${CLAIMANT_CLIENT_SECRET}",
                "enabled-secret: true",
                "enabled-secret: \${X:true}",
                "See favor-cacheabletask-and-disablecachingbydefault-over-cacheif-spec-and-donotcacheif-string-spec.md",
            ) + harmless("compose.yaml", "POSTGRES_PASSWORD: \${POSTGRES_PASSWORD:-demo}", "POSTGRES_USER: demo") +
                harmless(".env.local", "DB_PASSWORD=demo") +
                harmless(
                KOTLIN,
                "fun check(secret: String) = Unit",
                "val signingPassword = providers.gradleProperty(\"signingPassword\")",
                "val totalTokens = 42",
                "    val token: String",
                "    val token: String?",
                "    lateinit var apiKey: String",
                "    val token = chunk.result.output.text",
                "data class TextDelta(val token: String)",
                "const val TOKEN_HEADER = \"Authorization\"",
                "val json = \"{\\\"type\\\":\\\"TextDelta\\\",\\\"token\\\":\\\"Hel\\\"}\"",
                "val json = \"\"\"{\"type\":\"TextDelta\",\"token\":\"Hel\"}\"\"\"",
                "assertEquals(\"\"\"{\"token\":\"lo\"}\"\"\", body)",
                "assertEquals(TextDelta(token = \"Hel\"), decoded)",
                "flowOf(TextDelta(token = \"Hel\"), TextDelta(token = \"lo\"), Done)",
                "data: {\"type\":\"TextDelta\",\"token\":\" world\"}",
                "registeredClient.clientSecret(\"{noop}\${props.secret}\")",
                "private const val API_KEY_PROPERTY = \"spring.ai.anthropic.api-key\"",
                "private const val API_KEY_ENV = \"ANTHROPIC_API_KEY\"",
                "val authorization = \"Bearer \$token\"",
            ) + harmless("payload.json", "{\"type\":\"TextDelta\",\"token\":\"Hel\"}") +
                harmless(CONFIG, "Requests carry a Bearer JWT issued by the auth server.") +
                harmless(
                    YAML,
                    "datasource-url: jdbc:postgresql://" + "\${DB_USER:demo}:\${DB_PASSWORD}@localhost/backend",
                )
    }
}
