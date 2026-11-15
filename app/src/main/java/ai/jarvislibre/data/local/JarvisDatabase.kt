package ai.jarvislibre.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room Database singleton for the Jarvis Libre app.
 */
@Database(
    entities = [ChatMessageEntity::class],
    version = 1,
    exportSchema = true
)
abstract class JarvisDatabase : RoomDatabase() {
    
    abstract fun chatMessageDao(): ChatMessageDao
    
    companion object {
        private const val DATABASE_NAME = "local_assistant_db"
        
        @Volatile
        private var INSTANCE: JarvisDatabase? = null
        
        fun getInstance(context: Context): JarvisDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }
        
        private fun buildDatabase(context: Context): JarvisDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                JarvisDatabase::class.java,
                DATABASE_NAME
            )
            .fallbackToDestructiveMigration()
            .build()
        }
    }
}
