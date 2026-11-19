package dev.frederikpietzko.springaidemo.backend

import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.jetbrains.exposed.v1.core.Table

@AnalyzeClasses(packagesOf = [BackendApplication::class], importOptions = [ImportOption.DoNotIncludeTests::class])
class ArchitectureTest {
    /**
     * The migration generator only reads `<backend package>.persistence.tables` (derived from [BackendApplication], as
     * in the schema agreement test), so a table anywhere else, even in another `persistence.tables` package, would
     * silently get no migration. Written as "no class outside the package is a Table" because the `that()` set
     * (all backend classes) is never empty, so the rule is not vacuous before the first table exists.
     */
    @ArchTest
    val tables_live_in_tables_package: ArchRule =
        noClasses()
            .that().resideOutsideOfPackage("${BackendApplication::class.java.packageName}.persistence.tables..")
            .should().beAssignableTo(Table::class.java)
            .because("the migration generator reads only the persistence tables package")

    /** ADR-0006: `PgVectorStore` owns the vector table, so the RAG code never uses Exposed (no table object for it). */
    @ArchTest
    val rag_does_not_use_exposed: ArchRule =
        noClasses()
            .that().resideInAPackage("${BackendApplication::class.java.packageName}.rag..")
            .should().dependOnClassesThat().resideInAPackage("org.jetbrains.exposed..")
            .because("ADR-0006: the vector store owns the vector table, Exposed does not map it")

    /**
     * The claim intake is domain code: the chat layer maps its results to protocol events and wording, never the other
     * way round. Full package prefixes: a bare `..chat..` would also match Spring AI's `org.springframework.ai.chat`.
     */
    @ArchTest
    val intake_does_not_depend_on_chat: ArchRule =
        noClasses()
            .that().resideInAPackage("${BackendApplication::class.java.packageName}.intake..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "${BackendApplication::class.java.packageName}.chat..",
                "dev.frederikpietzko.springaidemo.chatprotocol..",
            )
            .because("the chat layer maps intake results to events and wording, the intake knows no protocol")
}
