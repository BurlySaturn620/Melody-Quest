package code.name.monkey.retromusic.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_23_24 = object : Migration(23, 24) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("DROP TABLE LyricsEntity")
        database.execSQL("DROP TABLE BlackListStoreEntity")
    }
}

/** Melody Quest: table for tags looked up online. Must match [OnlineMetadataEntity] exactly. */
val MIGRATION_24_25 = object : Migration(24, 25) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `online_metadata` (" +
                    "`song_id` INTEGER NOT NULL, " +
                    "`path` TEXT NOT NULL, " +
                    "`date_modified` INTEGER NOT NULL, " +
                    "`status` INTEGER NOT NULL, " +
                    "`checked_at` INTEGER NOT NULL, " +
                    "`mbid` TEXT, " +
                    "`year` INTEGER NOT NULL, " +
                    "`artist` TEXT, " +
                    "`album_artist` TEXT, " +
                    "`composer` TEXT, " +
                    "`lyricist` TEXT, " +
                    "PRIMARY KEY(`song_id`))"
        )
    }
}
