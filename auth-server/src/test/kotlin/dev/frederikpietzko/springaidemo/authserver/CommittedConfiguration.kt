package dev.frederikpietzko.springaidemo.authserver

import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource

private val committedConfiguration by lazy {
    YamlPropertySourceLoader().load("application.yaml", ClassPathResource("application.yaml")).single()
}

/**
 * A value of the committed `application.yaml`, unresolved (placeholders stay as written). The test context's own
 * `Environment` cannot show it: under `RANDOM_PORT` Boot adds `server.port=0`, and the tests set the secrets.
 */
fun committedProperty(key: String): String =
    checkNotNull(committedConfiguration.getProperty(key)) { "$key is not in application.yaml" }.toString()
