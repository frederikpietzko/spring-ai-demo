package dev.frederikpietzko.springaidemo.backend

import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.CompositeArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods
import dev.frederikpietzko.springaidemo.backend.testsupport.IntegrationTest
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.test.context.PropertyMapping
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.BootstrapWith
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.ContextCustomizerFactories
import org.springframework.test.context.ContextHierarchy
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.TestPropertySources
import org.springframework.test.context.bean.override.convention.TestBean
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoBeans
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBeans
import org.springframework.test.context.web.WebAppConfiguration

/**
 * Everything that changes the context cache key when it sits on a class or field of a test, including the repeatable
 * containers (`@TestPropertySources`, `@MockitoBeans`, `@MockitoSpyBeans`). `@TestBeans` does not exist in Spring
 * Framework 7.1.
 */
private val contextCustomisations: List<Class<out Annotation>> = [
    MockitoBean::class.java,
    MockitoBeans::class.java,
    MockitoSpyBean::class.java,
    MockitoSpyBeans::class.java,
    TestBean::class.java,
    DirtiesContext::class.java,
    TestPropertySource::class.java,
    TestPropertySources::class.java,
    ActiveProfiles::class.java,
    ContextConfiguration::class.java,
    ContextHierarchy::class.java,
    ContextCustomizerFactories::class.java,
    WebAppConfiguration::class.java,
    SpringBootTest::class.java,
]

/** Annotations that change the context (or reset it) from a method. */
private val methodCustomisations: List<Class<out Annotation>> =
    [DirtiesContext::class.java, DynamicPropertySource::class.java]

/** Annotations that add configuration or properties when they sit on, or are meta-annotated onto, a test class. */
private val contextConfiguring: List<Class<out Annotation>> =
    [Import::class.java, ImportAutoConfiguration::class.java, PropertyMapping::class.java]

/**
 * Fails an `@IntegrationTest` class that carries another annotation which is, or is meta-annotated with, any
 * annotation that configures or customises the context: [contextConfiguring], [contextCustomisations] or
 * `@BootstrapWith` (`@AutoConfigureMockMvc`, `@SpringJUnitConfig`, `@EnableXxx`, a direct `@Import`).
 */
private val contextAnnotationTypes: List<Class<out Annotation>> =
    contextConfiguring + contextCustomisations + BootstrapWith::class.java

private val carryNoContextConfiguringAnnotation = object : ArchCondition<JavaClass>(
    "carry no annotation that adds configuration or properties next to @IntegrationTest",
) {
    override fun check(item: JavaClass, events: ConditionEvents) {
        item.annotations
            .filter { it.rawType.name != IntegrationTest::class.java.name }
            .filter { annotation ->
                val type = annotation.rawType
                contextAnnotationTypes.any { type.isEquivalentTo(it) || type.isMetaAnnotatedWith(it) }
            }
            .forEach {
                events.add(
                    SimpleConditionEvent.violated(
                        item,
                        "${item.name} carries @${it.rawType.simpleName}, which changes the context cache key",
                    ),
                )
            }
    }
}

private val notBeNestedInAnIntegrationTest = object :
    ArchCondition<JavaClass>("not be nested in an @IntegrationTest class") {
    override fun check(item: JavaClass, events: ConditionEvents) {
        val nestedInIntegrationTest =
            item.enclosingClass.map { it.isAnnotatedWith(IntegrationTest::class.java) }.orElse(false)
        if (nestedInIntegrationTest) {
            events.add(SimpleConditionEvent.violated(item, "${item.name} is a configuration nested in a test class"))
        }
    }
}

private fun allOf(rules: List<ArchRule>): ArchRule = rules.reduce { all, next -> CompositeArchRule.of(all).and(next) }

/**
 * Makes the one-shared-context decision executable: every Spring context comes from `@IntegrationTest`, and nothing
 * may change the context cache key. Unlike the architecture rules this imports the test classes too. The tests share
 * one fake chat model and one database, so they must also run sequentially.
 */
@AnalyzeClasses(packagesOf = [BackendApplication::class])
class TestConventionsTest {
    @ArchTest
    val only_integration_test_starts_a_context: ArchRule =
        noClasses()
            .that().areNotAnnotatedWith(IntegrationTest::class.java)
            .and().doNotHaveFullyQualifiedName(IntegrationTest::class.java.name)
            .should().beMetaAnnotatedWith(BootstrapWith::class.java)
            .orShould().beMetaAnnotatedWith(ContextConfiguration::class.java)
            .because("every context test uses the shared @IntegrationTest annotation (no slice or @SpringJUnitConfig)")

    @ArchTest
    val no_class_customises_the_context: ArchRule =
        allOf(
            contextCustomisations.map {
                noClasses()
                    .that().doNotHaveFullyQualifiedName(IntegrationTest::class.java.name)
                    .should().beAnnotatedWith(it)
            },
        ).because("a customised context starts a second application context and database container")

    @ArchTest
    val no_integration_test_class_adds_configuration: ArchRule =
        classes().that().areAnnotatedWith(IntegrationTest::class.java).should(carryNoContextConfiguringAnnotation)
            .because("extra configuration on a test class changes the context cache key; put it in TestInfrastructure")

    @ArchTest
    val no_configuration_class_nested_in_an_integration_test: ArchRule =
        classes().that().areAnnotatedWith(Configuration::class.java)
            .or().areMetaAnnotatedWith(Configuration::class.java)
            .should(notBeNestedInAnIntegrationTest)
            .because("a nested configuration class changes the context cache key; put it in TestInfrastructure")

    @ArchTest
    val no_field_customises_the_context: ArchRule =
        allOf(contextCustomisations.map { noFields().should().beAnnotatedWith(it) })
            .because("a customised context starts a second application context and database container")

    @ArchTest
    val no_method_customises_the_context: ArchRule =
        allOf(methodCustomisations.map { noMethods().should().beAnnotatedWith(it) })
            .because("a context reset or dynamic property starts a second application context and database container")
}
