package com.example.webmirror.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ResourceEntity::class, ProjectEntity::class],
    version = 2,
    exportSchema = false
)
abstract class MirrorDatabase : RoomDatabase() {

    abstract fun resourceDao(): ResourceDao
    abstract fun projectDao(): ProjectDao

    companion object {
        @Volatile
        private var INSTANCE: MirrorDatabase? = null

        fun getInstance(context: Context): MirrorDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MirrorDatabase::class.java,
                    "webmirror.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
