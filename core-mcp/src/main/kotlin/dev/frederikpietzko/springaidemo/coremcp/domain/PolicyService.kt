package dev.frederikpietzko.springaidemo.coremcp.domain

import dev.frederikpietzko.springaidemo.coremcp.persistence.tables.Coverages
import dev.frederikpietzko.springaidemo.coremcp.persistence.tables.Customers
import dev.frederikpietzko.springaidemo.coremcp.persistence.tables.Policies
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Reads policies. */
@Service
@Transactional(readOnly = true)
@MustUseReturnValues
class PolicyService {
    /** The policy with [policyNumber] and its holder, or [PolicyLookup.NotFound]. */
    fun find(policyNumber: String): PolicyLookup {
        val row = (Policies innerJoin Customers).selectAll()
            .where { Policies.policyNumber eq policyNumber }
            .singleOrNull() ?: return PolicyLookup.NotFound
        val categories = Coverages.select(Coverages.category)
            .where { Coverages.policy eq row[Policies.id] }
            .map { it[Coverages.category] }
            .sorted()
        return PolicyLookup.Found(
            Policy(
                policyNumber = row[Policies.policyNumber],
                customerId = row[Customers.id].value,
                holderName = row[Customers.name],
                validFrom = row[Policies.validFrom],
                validTo = row[Policies.validTo],
                deductibleMinor = row[Policies.deductibleMinor],
                categories = categories,
            ),
        )
    }
}
