package ai.flossyapp

import android.app.Application

/**
 * Application class for FLOSSy Assistant.
 * 
 * Provides application-level singleton access and initializes core components.
 */
class FlossyApplication : Application() {

    companion object {
        @Volatile
        private var INSTANCE: FlossyApplication? = null
        
        /**
         * Get the application instance from anywhere in the app.
         * Use with caution - prefer dependency injection or passing context explicitly.
         */
        fun getInstance(): FlossyApplication {
            return INSTANCE ?: throw IllegalStateException(
                "Application not initialized. Call getApplicationContext() first."
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        synchronized(this) {
            if (INSTANCE == null) {
                INSTANCE = this
            }
        }
    }
}
