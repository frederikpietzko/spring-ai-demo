package dev.frederikpietzko.springaidemo.backend

import com.tngtech.archunit.core.domain.JavaModifier
import com.tngtech.archunit.core.importer.ClassFileImporter
import dev.frederikpietzko.springaidemo.backend.testsupport.IntegrationTest
import dev.frederikpietzko.springaidemo.backend.testsupport.fixtures.abstractbase.AuditedProbeTable
import dev.frederikpietzko.springaidemo.backend.testsupport.fixtures.notanobject.NotAnObjectTable
import dev.frederikpietzko.springaidemo.backend.testsupport.fixtures.unmigrated.UnmigratedProbeTable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val TABLES_PACKAGE = "${BackendApplication::class.java.packageName}.persistence.tables"
private val ABSTRACT_BASE_PACKAGE = AuditedProbeTable::class.java.packageName
private val NOT_AN_OBJECT_PACKAGE = NotAnObjectTable::class.java.packageName
private val UNMIGRATED_PACKAGE = UnmigratedProbeTable::class.java.packageName

/**
 * Compares the Exposed table definitions with the schema Flyway built at startup. The comparison of the real tables
 * package is vacuous while that package is empty (no table, so nothing to compare). The three fixture-based tests are
 * always active: they prove that a table without a migration is reported, that discovery rejects a concrete table that
 * is not an object, and that an abstract base table is skipped so that only its concrete object is discovered.
 */
@IntegrationTest
class SchemaAgreementTest {
    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `migrations cover every table definition`() {
        val tables = discoverTables(TABLES_PACKAGE)

        assertEquals([], requiredStatements(tables))
    }

    @Test
    fun `a table without a migration is detected`() {
        val tables = discoverTables(UNMIGRATED_PACKAGE)

        val statements = requiredStatements(tables)

        assertEquals([UnmigratedProbeTable], tables)
        assertTrue(
            statements.any { it.contains("CREATE TABLE") && it.contains("unmigrated_probe") },
            "statements: $statements",
        )
    }

    @Test
    fun `a table definition that is not a singleton object fails discovery naming the class`() {
        val failure = assertFailsWith<IllegalStateException> { discoverTables(NOT_AN_OBJECT_PACKAGE) }

        assertTrue(failure.message.orEmpty().contains("NotAnObjectTable"))
    }

    @Test
    fun `an abstract base table is ignored and only its concrete object is discovered`() {
        val tables = discoverTables(ABSTRACT_BASE_PACKAGE)

        assertEquals([AuditedProbeTable], tables)
    }

    private fun requiredStatements(tables: List<Table>): List<String> =
        checkNotNull(
            TransactionTemplate(transactionManager).execute {
                MigrationUtils.statementsRequiredForDatabaseMigration(*tables.toTypedArray(), withLogs = false)
            },
        ) { "the transaction returned no statements" }

    /**
     * Finds every concrete [Table] class in [basePackage] (abstract base tables are skipped); a non-`object` one fails
     * loudly instead of being skipped.
     */
    private fun discoverTables(basePackage: String): List<Table> =
        ClassFileImporter().importPackages(basePackage)
            .filter { it.isAssignableTo(Table::class.java) && JavaModifier.ABSTRACT !in it.modifiers }
            .map { javaClass ->
                checkNotNull(Class.forName(javaClass.name).kotlin.objectInstance as Table?) {
                    "${javaClass.name} extends Table but is not an object, so it would escape the schema check"
                }
            }
}
