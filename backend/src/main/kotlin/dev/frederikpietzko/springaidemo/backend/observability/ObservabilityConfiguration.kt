package dev.frederikpietzko.springaidemo.backend.observability

import io.micrometer.common.KeyValues
import org.springframework.ai.vectorstore.observation.DefaultVectorStoreObservationConvention
import org.springframework.ai.vectorstore.observation.VectorStoreObservationContext
import org.springframework.ai.vectorstore.observation.VectorStoreObservationConvention
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Keeps the claimant's words out of telemetry (ADR-0010). */
@Configuration(proxyBeanMethods = false)
class ObservabilityConfiguration {
    /**
     * The vector store span without the retrieval query: the query is the claimant's message, which the default
     * convention would record as the attribute `db.vector.query.content`. Everything else stays.
     */
    @Bean
    fun vectorStoreObservationConvention(): VectorStoreObservationConvention =
        object : DefaultVectorStoreObservationConvention() {
            override fun queryContent(keyValues: KeyValues, context: VectorStoreObservationContext): KeyValues =
                keyValues
        }
}
