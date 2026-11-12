package ai.flossyapp.data.local

import androidx.room.*
import kotlinx.serialization.Serializable

/**
 * Room entity for persisting chat messages locally.
 */
@Entity(tableName = "chat_messages")
@Serializable
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = System.currentTimeMillis(),
    
    @ColumnInfo(name = "role")
    val role: String, // USER, ASSISTANT, SYSTEM
    
    @ColumnInfo(name = "content")
    val content: String,
    
    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Room DAO for chat message operations.
 */
@Dao
interface ChatMessageDao {
    
    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
    suspend fun getAllMessages(): List<ChatMessageEntity>
    
    @Query("SELECT * FROM chat_messages WHERE role = :role ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastMessageByRole(role: String): ChatMessageEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<ChatMessageEntity>)
    
    @Query("DELETE FROM chat_messages")
    suspend fun clearAllMessages()
    
    @Query("DELETE FROM chat_messages WHERE timestamp < :olderThan")
    suspend fun deleteMessagesOlderThan(olderThan: Long)
}
