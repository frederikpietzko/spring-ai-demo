package dev.frederikpietzko.springaidemo.coremcp.testsupport

import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource

private val committedConfiguration by lazy {
    YamlPropertySourceLoader().load("application.yaml", ClassPathResource("application.yaml")).single()
}

/**
 * A value of the committed `application.yaml`, unresolved (placeholders stay as written). The test context's own
 * `Environment` cannot show it: under `RANDOM_PORT` Boot adds `server.port=0` with higher precedence, and the
 * datasource URL comes from the test container.
 */
fun committedProperty(key: String): String? = committedConfiguration.getProperty(key)?.toString()
