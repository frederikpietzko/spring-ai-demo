package dev.frederikpietzko.springaidemo.coremcp

import com.tngtech.archunit.core.domain.JavaModifier
import com.tngtech.archunit.core.importer.ClassFileImporter
import dev.frederikpietzko.springaidemo.coremcp.testsupport.CoreMcpIntegrationTest
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import kotlin.test.Test
import kotlin.test.assertEquals

private val TABLES_PACKAGE = "${CoreMcpApplication::class.java.packageName}.persistence.tables"

/**
 * Compares the Exposed table definitions with the schema Flyway built at startup (a slim copy of the backend test;
 * the backend proves with fixtures that the comparison detects a missing migration).
 */
@CoreMcpIntegrationTest
class SchemaAgreementTest {
    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `migrations cover every table definition`() {
        val tables = discoverTables(TABLES_PACKAGE)

        val statements = requiredStatements(tables)

        assertEquals(6, tables.size, "tables: $tables")
        assertEquals([], statements)
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
