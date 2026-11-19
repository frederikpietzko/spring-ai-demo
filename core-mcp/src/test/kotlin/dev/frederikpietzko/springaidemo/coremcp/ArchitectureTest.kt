package dev.frederikpietzko.springaidemo.coremcp

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import dev.frederikpietzko.springaidemo.coremcp.security.CallerAccess
import org.jetbrains.exposed.v1.core.Table
import org.springframework.ai.mcp.annotation.McpResource
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.TestPropertySource
import kotlin.test.assertTrue

/** Classes with at least one `@McpTool` or `@McpResource` method: what a client of core-mcp can reach. */
private val declaresMcpToolsOrResources =
    DescribedPredicate.describe<JavaClass>("declare MCP tools or resources") { javaClass ->
        javaClass.methods.any { it.isAnnotatedWith(McpTool::class.java) || it.isAnnotatedWith(McpResource::class.java) }
    }

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

    /**
     * Every tool and resource checks its caller (ADR-0009), so a tool added later cannot be left open by accident.
     * Depending on [CallerAccess] does not prove the right scope is checked; the tests of each tool do.
     */
    @ArchTest
    val tool_and_resource_classes_use_caller_access: ArchRule =
        classes()
            .that(declaresMcpToolsOrResources)
            .should().dependOnClassesThat().belongToAnyOf(CallerAccess::class.java)
            .because("core-mcp, not the agent, enforces what a token may do (ADR-0009)")

    /** Keeps the rule above from passing on an empty selection: the five existing classes must be among it. */
    @ArchTest
    fun the_caller_access_rule_selects_the_tool_classes(classes: JavaClasses) {
        val selected = classes.that(declaresMcpToolsOrResources).map { it.simpleName }.toSet()

        assertTrue(
            selected.containsAll(["AnalyticsTools", "ClaimTools", "CoreResources", "PayoutTools", "PolicyTools"]),
            "$selected",
        )
    }
}
