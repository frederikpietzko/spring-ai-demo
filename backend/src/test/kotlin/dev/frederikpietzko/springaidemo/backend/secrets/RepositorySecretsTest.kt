package dev.frederikpietzko.springaidemo.backend.secrets

import org.junit.jupiter.api.Tag
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** Runs only through the `secretsScan` Gradle task, which runs on every build (it tracks no inputs). */
@Tag("secrets-scan")
class RepositorySecretsTest {
    @Test
    fun `repository contains no secrets`() {
        val root = Path.of(checkNotNull(System.getProperty("secrets.scan.root")) { "secrets.scan.root is not set" })

        val findings = SecretScanner.scanTree(root).map { "${root.relativize(it.file)}:${it.line} (${it.rule})" }

        assertEquals([], findings)
    }
}
