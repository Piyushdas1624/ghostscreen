package com.etaegord.ghostscreen.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ProtectedApp::class, AuditLog::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        @Suppress("DEPRECATION")
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ghostscreen_database"
                )
                // ponytail: ceiling is destructive fallback; upgrade path = add Migration(1,2) when schema changes
                // or bump Room to 2.7+ for the boolean overload
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
