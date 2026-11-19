package dev.frederikpietzko.springaidemo.coremcp

import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.jetbrains.exposed.v1.core.Table
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.TestPropertySource

/** Test classes are analysed too: the context rule is about them. */
@AnalyzeClasses(packagesOf = [CoreMcpApplication::class])
class ArchitectureTest {
    /**
     * The migration generator only reads `<core-mcp package>.persistence.tables`, so a table anywhere else would
     * silently get no migration. "No class outside the package is a Table" is never vacuous.
     */
    @ArchTest
    val tables_live_in_tables_package: ArchRule =
        noClasses()
            .that().resideOutsideOfPackage("${CoreMcpApplication::class.java.packageName}.persistence.tables..")
            .should().beAssignableTo(Table::class.java)
            .because("the migration generator reads only the persistence tables package")

    /**
     * All tests share one application context through `@CoreMcpIntegrationTest` (an annotation, so it is exempt). A
     * test class carrying one of these itself would start a second context. Field and method annotations
     * (`@MockitoBean`, `@DynamicPropertySource`) and nested `@TestConfiguration` are not covered: a review item.
     */
    @ArchTest
    val tests_share_one_context: ArchRule =
        noClasses()
            .that().areNotAnnotations()
            .should().beAnnotatedWith(SpringBootTest::class.java)
            .orShould().beAnnotatedWith(TestPropertySource::class.java)
            .orShould().beAnnotatedWith(Import::class.java)
            .orShould().beAnnotatedWith(ContextConfiguration::class.java)
            .orShould().beAnnotatedWith(ActiveProfiles::class.java)
            .orShould().beAnnotatedWith(DirtiesContext::class.java)
            .because("every core-mcp test shares one context through @CoreMcpIntegrationTest")
}
