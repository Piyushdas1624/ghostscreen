package com.etaegord.ghostscreen.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    // Protected Apps
    @Query("SELECT * FROM protected_apps ORDER BY addedAt DESC")
    fun getAllProtectedApps(): Flow<List<ProtectedApp>>

    @Query("SELECT * FROM protected_apps ORDER BY addedAt DESC")
    suspend fun getAllProtectedAppsList(): List<ProtectedApp>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProtectedApp(app: ProtectedApp)

    @Delete
    suspend fun deleteProtectedApp(app: ProtectedApp)

    @Query("SELECT EXISTS(SELECT * FROM protected_apps WHERE packageName = :packageName)")
    suspend fun isAppProtected(packageName: String): Boolean

    @Query("SELECT EXISTS(SELECT * FROM protected_apps WHERE packageName = :packageName)")
    fun isAppProtectedFlow(packageName: String): Flow<Boolean>

    // Audit Logs
    @Query("SELECT * FROM audit_logs ORDER BY timestamp DESC")
    fun getAllAuditLogs(): Flow<List<AuditLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAuditLog(log: AuditLog)

    @Query("DELETE FROM audit_logs")
    suspend fun clearAuditLogs()
}
