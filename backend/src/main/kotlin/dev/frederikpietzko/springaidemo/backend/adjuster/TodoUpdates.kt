package dev.frederikpietzko.springaidemo.backend.adjuster

import dev.frederikpietzko.springaidemo.chatprotocol.ChatEvent
import dev.frederikpietzko.springaidemo.chatprotocol.TodoItem
import dev.frederikpietzko.springaidemo.chatprotocol.TodoStatus
import org.springaicommunity.agent.tools.TodoWriteTool

/** The whole todo list of one `TodoWrite` call as the chat event the client shows, in the order of the items. */
fun TodoWriteTool.Todos.toTodoUpdate(): ChatEvent.TodoUpdate =
    ChatEvent.TodoUpdate(todos().map { TodoItem(it.content(), it.status().toTodoStatus(), it.activeForm()) })

private fun TodoWriteTool.Todos.Status.toTodoStatus(): TodoStatus = when (this) {
    TodoWriteTool.Todos.Status.pending -> TodoStatus.PENDING
    TodoWriteTool.Todos.Status.in_progress -> TodoStatus.IN_PROGRESS
    TodoWriteTool.Todos.Status.completed -> TodoStatus.COMPLETED
}
