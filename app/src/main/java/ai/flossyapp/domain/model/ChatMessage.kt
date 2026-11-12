package ai.flossyapp.domain.model

import kotlinx.serialization.Serializable

/**
 * One tool call an assistant turn made, as the chat shows it: the tool's
 * name, the JSON arguments it was given, and the result it produced. The
 * record rides inside its assistant message, so old conversations without
 * it deserialize untouched (the field defaults to null).
 */
@Serializable
data class ToolCallRecord(
    val name: String,
    val arguments: String,
    val result: String
)

/**
 * Represents a single message in the conversation history.
 */
@Serializable
data class ChatMessage(
    val id: Long = System.currentTimeMillis(),
    val role: MessageRole,
    val content: String,
    val reasoningContent: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val toolCalls: List<ToolCallRecord>? = null
) {
    companion object {
        fun user(message: String) = ChatMessage(
            role = MessageRole.USER,
            content = message
        )
        
        fun assistant(content: String) = ChatMessage(
            role = MessageRole.ASSISTANT,
            content = content
        )
        
        fun assistant(content: String, reasoningContent: String?) = ChatMessage(
            role = MessageRole.ASSISTANT,
            content = content,
            reasoningContent = reasoningContent
        )

        /**
         * A tool call the assistant made, drawn as its own compact row:
         * the tool name collapsed, the arguments and the result expanded
         * on tap.
         */
        fun assistantToolCall(call: ToolCallRecord) = ChatMessage(
            role = MessageRole.ASSISTANT,
            content = "",
            toolCalls = listOf(call)
        )
    }
}

/**
 * Represents the role of a message sender.
 */
enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}
