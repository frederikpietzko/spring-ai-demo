package dev.frederikpietzko.springaidemo.coremcp

import org.jetbrains.exposed.v1.spring.boot4.autoconfigure.ExposedAutoConfiguration
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import java.time.Clock

/**
 * Entry point of the insurer core system: claims data reachable only over MCP (ADR-0003). Exposed owns transactions
 * through Spring, so Boot's own manager is excluded (as in the backend).
 */
@SpringBootApplication
@ImportAutoConfiguration(
    value = [ExposedAutoConfiguration::class],
    exclude = [DataSourceTransactionManagerAutoConfiguration::class],
)
class CoreMcpApplication {
    /** The time source of filing and payouts, injectable so it is never hidden in the services. */
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

fun main(args: Array<String>) {
    runApplication<CoreMcpApplication>(*args)
}
