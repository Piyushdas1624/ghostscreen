package com.etaegord.ghostscreen.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "protected_apps")
data class ProtectedApp(
    @PrimaryKey val packageName: String,
    val appName: String,
    val addedAt: Long = System.currentTimeMillis()
)
