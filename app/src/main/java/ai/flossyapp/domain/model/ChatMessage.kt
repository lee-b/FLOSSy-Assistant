package ai.flossyapp.domain.model

import kotlinx.serialization.Serializable

/**
 * Represents a single message in the conversation history.
 */
@Serializable
data class ChatMessage(
    val id: Long = System.currentTimeMillis(),
    val role: MessageRole,
    val content: String,
    val reasoningContent: String? = null,
    val timestamp: Long = System.currentTimeMillis()
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
