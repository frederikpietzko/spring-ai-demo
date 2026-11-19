package dev.frederikpietzko.springaidemo.backend.evaluation

import org.springframework.ai.chat.client.ChatClientRequest
import org.springframework.ai.chat.client.ChatClientResponse
import org.springframework.ai.chat.client.advisor.api.CallAdvisor
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain
import org.springframework.core.Ordered
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Records the raw answers of a judge. The evaluators report only pass or fail (a pass needs the exact answer "yes"),
 * so a failure message names the judge's [last] answer: a "no" and a "Yes." look the same from the outside.
 */
class JudgeAnswers : CallAdvisor {
    private val answers = CopyOnWriteArrayList<String>()

    /** The latest judge answer, as the model wrote it; `null` before the first. */
    val last: String? get() = answers.lastOrNull()

    override fun getName(): String = "JudgeAnswers"

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    override fun adviseCall(request: ChatClientRequest, chain: CallAdvisorChain): ChatClientResponse =
        chain.nextCall(request).also { answers += it.chatResponse()?.result?.output?.text.orEmpty() }
}
