package com.example.silenthours

import android.content.Context
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Update
import androidx.room.Delete
import androidx.room.Query

@Entity(tableName = "blocking_rules")
data class BlockingRuleEntity(
    @PrimaryKey val id: Int,
    val contactName: String,
    val phoneNumber: String,
    val startTime: String,
    val endTime: String,
    val daysOfWeek: String, // Store as JSON: "1,2,3,4,5"
    val allowEmergency: Boolean,
    val retryWindow: Int,
    val isEnabled: Boolean,
    val createdAt: Long
)

@Entity(tableName = "call_attempts")
data class CallAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val phoneNumber: String,
    val timestamp: Long,
    val wasBlocked: Boolean
)

@Dao
interface BlockingRuleDao {
    @Insert
    suspend fun insert(rule: BlockingRuleEntity)

    @Update
    suspend fun update(rule: BlockingRuleEntity)

    @Delete
    suspend fun delete(rule: BlockingRuleEntity)

    @Query("SELECT * FROM blocking_rules WHERE isEnabled = 1")
    suspend fun getAllActiveRules(): List<BlockingRuleEntity>

    @Query("SELECT * FROM blocking_rules WHERE phoneNumber = :phoneNumber AND isEnabled = 1")
    suspend fun getRuleByPhoneNumber(phoneNumber: String): List<BlockingRuleEntity>

    @Query("SELECT * FROM blocking_rules")
    suspend fun getAllRules(): List<BlockingRuleEntity>
}

@Dao
interface CallAttemptDao {
    @Insert
    suspend fun insert(attempt: CallAttemptEntity)

    @Query("SELECT * FROM call_attempts WHERE phoneNumber = :phoneNumber AND timestamp > :afterTime ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastAttempt(phoneNumber: String, afterTime: Long): CallAttemptEntity?

    @Query("DELETE FROM call_attempts WHERE timestamp < :beforeTime")
    suspend fun deleteOldAttempts(beforeTime: Long)
}

@Database(
    entities = [BlockingRuleEntity::class, CallAttemptEntity::class],
    version = 1
)
abstract class BlockingRuleDatabase : RoomDatabase() {
    abstract fun blockingRuleDao(): BlockingRuleDao
    abstract fun callAttemptDao(): CallAttemptDao

    companion object {
        @Volatile
        private var INSTANCE: BlockingRuleDatabase? = null

        fun getDatabase(context: Context): BlockingRuleDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    BlockingRuleDatabase::class.java,
                    "blocking_rules_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}