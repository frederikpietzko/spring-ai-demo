package dev.frederikpietzko.springaidemo.coremcp.testsupport

import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import javax.sql.DataSource

/**
 * Applies the demo seed set [name] (`core-mcp/src/main/resources/demo-seed/<name>.sql`) through Spring's JDBC script
 * runner, the way a test checks that the plain SQL runs outside `psql` too. A test that applies a seed applies `reset`
 * afterwards, so the shared database ends at the migration state.
 */
fun DataSource.applySeed(name: String) {
    ResourceDatabasePopulator(ClassPathResource("demo-seed/$name.sql")).execute(this)
}
