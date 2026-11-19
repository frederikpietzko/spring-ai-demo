package dev.frederikpietzko.springaidemo.backend.secrets

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.extension
import kotlin.io.path.inputStream
import kotlin.io.path.name
import kotlin.io.path.relativeTo

/** A suspected secret: the [file], the 1-based [line] and the name of the [rule] that matched. */
data class SecretFinding(val file: Path, val line: Int, val rule: String)

/**
 * Scanner behind `secretsScan`: walks a directory tree and returns findings with file and line (tested against
 * temporary directories). It owns the definition of the scanned file set: every text file below the root, except build
 * output and tool directories, binary files (by extension, or by a NUL byte in the first bytes) and the documented
 * local-only files. The Gradle task does not track inputs (it runs on every build), so no file list has to be kept in
 * sync with this one.
 *
 * Two kinds of rule. Shape rules (provider keys, JWTs, `Bearer` tokens, encoded passwords (`{noop}` plus a literal),
 * private-key headers, URL userinfo with a literal password) apply to every scanned file. The assignment rule
 * (credential name, then a literal value) depends on the file kind: source files (`.kt`, `.kts`, `.java`, `.gradle`,
 * other code) check only quoted string literals, so declarations (`val token: String`), calls and property reads are
 * never values; yaml, yml, properties and env files check quoted and bare values and, for a name at the start of a
 * line, the whole rest of the line; every other file (md, json, http, extensionless, ...) checks quoted and bare words.
 */
object SecretScanner {
    private class Pattern(val rule: String, val regex: Regex)

    private val patterns: List<Pattern> = [
        Pattern("anthropic-key", Regex("""sk-ant-[A-Za-z0-9_-]{20,}""")),
        Pattern("generic-sk-key", Regex("""(?<![A-Za-z0-9_-])sk-[A-Za-z0-9_-]{32,}""")),
        Pattern("aws-access-key-id", Regex("""AKIA[0-9A-Z]{16}""")),
        Pattern("github-token", Regex("""gh[pousr]_[A-Za-z0-9]{36,}""")),
        Pattern("private-key-header", Regex("""-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----""")),
        Pattern("jwt", Regex("""eyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]*""")),
        Pattern("bearer-token", Regex("""(?i)\bBearer\s+(?![$<{])[A-Za-z0-9._~+/-]{16,}=*""")),
        // Spring Security password-encoder prefix followed by a literal: a credential by construction
        Pattern("encoded-password", Regex("""\{(?:noop|bcrypt|pbkdf2|scrypt|sha256|argon2)}[A-Za-z0-9]""")),
        // URL userinfo with a literal password (user, colon, password, at-sign); the user may be a whole placeholder
        // (with or without default), a placeholder password is no literal
        Pattern(
            "url-credentials",
            Regex("""[A-Za-z][A-Za-z0-9+.-]*://(?:\x24\{[^}]*}|(?!\x24\{)[^/\s:@]+):(?!\x24\{)[^/\s@]+@"""),
        ),
    ]

    /**
     * `<name containing api key, secret, token, password or credential> (: or =) <value>`. The value is one quoted
     * literal (whitespace allowed) and, for non-source files, alternatively a bare token that runs to the end of the
     * line (the yaml, properties and shell shape). Source files may carry a Kotlin type between name and `=`
     * (`val password: String = "..."`). That keeps prose ("the secret: the finding ...") and calls
     * (`password = providers.gradleProperty(...)`) from being read as credentials. Names with a space (`"API Key"`)
     * are deliberately not matched because they read like prose, and neither is the plural `tokens` (`max-tokens`
     * counts model tokens, it is not a credential).
     */
    private fun assignmentRegex(kind: FileKind) = Regex(
        """(?<![A-Za-z0-9_.{$-])""" + NAME + """["']?""" +
            (if (kind == FileKind.SOURCE) """(?:\s*:\s*[A-Za-z0-9_.<>?]+)?""" else "") +
            """\s*[:=]\s*(?:["'](?<quoted>[^"']+)["']""" +
            (if (kind != FileKind.SOURCE) """|(?<bare>[^\s"'`()<>\[\],;]+)\s*(?:#.*)?$""" else "") + ")",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Config files (yaml, yml, properties, env) only: a name at the start of the line (optionally after `- ` or
     * `export `) takes everything up to the end of the line or a ` #` comment as its bare value, so passwords with
     * spaces or punctuation (`password: pa;ss(word) 42`) are checked. Anchored at the line start so prose
     * (`A suspected secret: the finding ...`) stays clean.
     */
    private val lineValueAssignment = Regex(
        """^\s*(?:-\s+)?(?:export\s+)?""" + NAME + """["']?\s*[:=]\s*(?:(?<quoted>(?!)))?""" +
            """(?<bare>[^\s"'][^\r\n]*?)\s*(?:\s#.*)?$""",
        RegexOption.IGNORE_CASE,
    )

    private val assignments: Map<FileKind, List<Regex>> = mapOf(
        FileKind.SOURCE to [assignmentRegex(FileKind.SOURCE)],
        FileKind.CONFIG to [assignmentRegex(FileKind.CONFIG), lineValueAssignment],
        FileKind.OTHER to [assignmentRegex(FileKind.OTHER)],
    )

    private enum class FileKind { SOURCE, CONFIG, OTHER }

    /** Extensions of config files whose bare values run to the end of the line. */
    private val configExtensions: Set<String> = ["yaml", "yml", "properties", "env"]

    /** Extensions of source files; every other file checks quoted and bare values. */
    private val sourceExtensions: Set<String> = [
        "kt", "kts", "java", "gradle", "groovy", "scala", "js", "mjs", "ts", "tsx", "jsx", "py", "swift", "go", "rs",
        "c", "h", "cpp", "hpp", "cs",
    ]

    /** `${NAME}`, `${NAME:default}` or `${NAME:-default}`; the default is greedy, so nested braces stay in it. */
    private val placeholder = Regex(
        """^\$\{(?<variable>[A-Za-z0-9_.-]+)(?::-?(?<default>.*))?}$""",
        RegexOption.DOT_MATCHES_ALL,
    )

    /** Documented local-development defaults (compose.yaml, application.yaml, README.md): name to its one literal. */
    private val allowedLiterals = mapOf(
        "DB_USER" to "demo", // local compose database user
        "DB_PASSWORD" to "demo", // local compose database password
        "POSTGRES_USER" to "demo", // same credentials in compose.yaml
        "POSTGRES_PASSWORD" to "demo", // same credentials in compose.yaml
    )

    private val excludedDirectories: Set<String> = ["build", ".gradle", ".git", ".idea", ".kotlin", "node_modules"]
    private val excludedPaths: Set<String> = [".claude/worktrees"]

    /** Local-only files: the git-ignored `.env` and `*.key` (.gitignore) and the per-developer Claude settings. */
    private val localOnlyPaths: Set<String> = [".claude/settings.local.json"]

    private val binaryExtensions: Set<String> = [
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "tiff", "ico", "icns", "heic", "jar", "war", "class", "zip", "gz",
        "tgz", "tar", "bz2", "xz", "7z", "pdf", "ttf", "otf", "woff", "woff2", "eot", "mp3", "mp4", "mov", "wav", "ogg",
        "dll", "so", "dylib", "exe", "bin", "pyc", "wasm", "db", "sqlite", "p12", "pfx", "jks",
    ]

    private const val BINARY_SNIFF_BYTES = 8192

    /** Scans every included file below [root]; excluded directories are never entered. */
    fun scanTree(root: Path): List<SecretFinding> {
        val files = mutableListOf<Path>()
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                    if (dir == root || !isExcludedDirectory(dir.relativeTo(root))) {
                        FileVisitResult.CONTINUE
                    } else {
                        FileVisitResult.SKIP_SUBTREE
                    }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile && isScannedFile(file.relativeTo(root))) files.add(file)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return files.sorted().flatMap { file ->
            file.readText()?.let { scanLines(file, it.lines(), kindOf(file)) }.orEmpty()
        }
    }

    private fun isExcludedDirectory(relative: Path): Boolean =
        relative.name in excludedDirectories ||
            relative.joinToString("/") in excludedPaths

    private fun isScannedFile(relative: Path): Boolean =
        relative.joinToString("/") !in localOnlyPaths &&
            relative.name != ".env" &&
            relative.extension != "key" &&
            relative.extension.lowercase() !in binaryExtensions

    /** The file as text (malformed UTF-8 is replaced, never fatal), or null when its content is binary. */
    private fun Path.readText(): String? {
        val bytes = inputStream().use { input ->
            val head = input.readNBytes(BINARY_SNIFF_BYTES)
            if (head.any { it == 0.toByte() }) return null
            head + input.readAllBytes()
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun kindOf(file: Path): FileKind {
        val extension = file.extension.lowercase()
        return when {
            extension in sourceExtensions -> FileKind.SOURCE
            extension in configExtensions || file.name.startsWith(".env") -> FileKind.CONFIG
            else -> FileKind.OTHER
        }
    }

    private fun scanLines(file: Path, lines: List<String>, kind: FileKind): List<SecretFinding> =
        lines.flatMapIndexed { index, text ->
            val line = index + 1
            val shapes = patterns.filter { it.regex.containsMatchIn(text) }
                .map { SecretFinding(file = file, line = line, rule = it.rule) }
            val assigned = if (hasSecretAssignment(text, kind)) {
                [SecretFinding(file = file, line = line, rule = "assignment")]
            } else {
                []
            }
            shapes + assigned
        }

    /** Keeps scanning inside a harmless value, so `endpoint: http://host/x?client_secret=abc` is still found. */
    private fun hasSecretAssignment(text: String, kind: FileKind): Boolean =
        assignments.getValue(kind).any { assignment ->
            var match = assignment.find(text)
            while (match != null) {
                val value = match.groups["quoted"] ?: match.groups["bare"]
                val name = match.groups["name"]?.value.orEmpty()
                if (isSecretValue(name, value?.value.orEmpty(), kind == FileKind.SOURCE)) return true
                match = assignment.find(text, startIndex = (value?.range?.first ?: match.range.first) + 1)
            }
            false
        }

    /**
     * A placeholder without default is harmless; its default is allowed when documented for the variable and is
     * otherwise checked like any value (recursively, so `${A:${B:hunter2}}` is found and `${TTL:5m}` is not).
     */
    private fun isSecretValue(name: String, value: String, source: Boolean): Boolean {
        val reference = placeholder.matchEntire(value)
        return when {
            reference != null -> {
                val default = reference.groups["default"]?.value.orEmpty()
                val documented = allowedLiterals[reference.groups["variable"]?.value?.uppercase()]
                default.isNotEmpty() && default != documented && isSecretValue(name, default, source)
            }
            isHarmlessLiteral(name, value) -> false
            source && isCredentialReference(value) -> false
            else -> value.any { it.isLetterOrDigit() } && allowedLiterals[name.uppercase()] != value
        }
    }

    /**
     * Booleans, well-known OAuth words and URLs without userinfo, query or fragment carry no secret. Numbers and
     * durations are harmless only next to a name that is not itself a password, secret, api key or credential
     * (`DB_PASSWORD=20251119` is a password). The same holds for a token-only name (no password, secret, api key
     * or credential part) with a value shorter than 12 characters or with whitespace: that is model token text
     * (`"token":"Hel"`), not a credential.
     */
    private fun isHarmlessLiteral(name: String, value: String): Boolean {
        val secretName = secretNamePart.containsMatchIn(name)
        return value.equals("true", ignoreCase = true) || value.equals("false", ignoreCase = true) ||
            value.lowercase() in wellKnownValues ||
            (!secretName && (value.matches(numeric) || value.matches(duration) || isTokenText(value))) ||
            value.matches(plainUrl)
    }

    private fun isTokenText(value: String) = value.length < MIN_TOKEN_LENGTH || value.any { it.isWhitespace() }

    /** In source, a quoted env-var name or property path that names a credential is a reference, not a value. */
    private fun isCredentialReference(value: String) =
        credentialReference.matches(value) && credentialWord.containsMatchIn(value)

    private const val MIN_TOKEN_LENGTH = 12
    private const val NAME =
        """(?<name>[A-Za-z0-9_.-]*(?:api[_-]?key|secret|token(?!s\b)|passw(?:or)?d|credential)[A-Za-z0-9_.-]*)"""
    private val credentialReference = Regex("""[A-Z][A-Z0-9_]*|[a-z0-9-]+(?:\.[a-z0-9-]+){2,}""")
    private val credentialWord = Regex("""api[_-]?key|secret|token|passw|credential""", RegexOption.IGNORE_CASE)
    private val secretNamePart = Regex("""passw|secret|api[_-]?key|credential""", RegexOption.IGNORE_CASE)
    private val wellKnownValues = setOf(
        "bearer", "basic", "authorization", "client_secret_basic", "client_secret_post", "private_key_jwt", "none",
    )
    private val numeric = Regex("""[0-9][0-9._]*""")
    private val duration = Regex("""(?:[0-9]+(?:ms|s|m|h|d)|PT[0-9]+[HMS])""", RegexOption.IGNORE_CASE)
    private val plainUrl = Regex("""[A-Za-z][A-Za-z0-9+.-]*://[^/@\s?#]*(?:/[^\s?#]*)?""")
}
