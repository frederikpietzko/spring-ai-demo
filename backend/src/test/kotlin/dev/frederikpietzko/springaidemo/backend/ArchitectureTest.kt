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
}
