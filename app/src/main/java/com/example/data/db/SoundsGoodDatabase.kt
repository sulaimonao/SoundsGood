package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TrackEntity::class,
        TrackStatsEntity::class,
        ListeningEventEntity::class,
        SmartPlaylistRuleEntity::class,
        PlaybackSnapshotEntity::class,
        SyncedPlaylistEntity::class,
        SyncedPlaylistEntryEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class SoundsGoodDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun statsDao(): StatsDao
    abstract fun smartPlaylistDao(): SmartPlaylistDao
    abstract fun playbackSnapshotDao(): PlaybackSnapshotDao
    abstract fun syncedPlaylistDao(): SyncedPlaylistDao

    companion object {
        @Volatile
        private var INSTANCE: SoundsGoodDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN syncLibraryId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE tracks ADD COLUMN syncTrackId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE tracks ADD COLUMN contentSha256 TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE tracks ADD COLUMN localFilePath TEXT DEFAULT NULL")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `synced_playlists` (
                        `playlistKey` TEXT NOT NULL,
                        `libraryId` TEXT NOT NULL,
                        `libraryName` TEXT NOT NULL,
                        `remotePlaylistId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `revision` TEXT NOT NULL,
                        `syncedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`playlistKey`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_synced_playlists_libraryId_remotePlaylistId`
                    ON `synced_playlists` (`libraryId`, `remotePlaylistId`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `synced_playlist_entries` (
                        `playlistKey` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        `trackKey` TEXT NOT NULL,
                        PRIMARY KEY(`playlistKey`, `position`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_synced_playlist_entries_playlistKey_position`
                    ON `synced_playlist_entries` (`playlistKey`, `position`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_synced_playlist_entries_trackKey`
                    ON `synced_playlist_entries` (`trackKey`)
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): SoundsGoodDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SoundsGoodDatabase::class.java,
                    "soundsgood.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
