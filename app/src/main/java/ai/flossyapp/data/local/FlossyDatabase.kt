package ai.flossyapp.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room Database singleton for the FLOSSy Assistant app.
 */
@Database(
    entities = [ChatMessageEntity::class],
    version = 1,
    exportSchema = true
)
abstract class FlossyDatabase : RoomDatabase() {
    
    abstract fun chatMessageDao(): ChatMessageDao
    
    companion object {
        private const val DATABASE_NAME = "local_assistant_db"
        
        @Volatile
        private var INSTANCE: FlossyDatabase? = null
        
        fun getInstance(context: Context): FlossyDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }
        
        private fun buildDatabase(context: Context): FlossyDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                FlossyDatabase::class.java,
                DATABASE_NAME
            )
            .fallbackToDestructiveMigration()
            .build()
        }
    }
}
