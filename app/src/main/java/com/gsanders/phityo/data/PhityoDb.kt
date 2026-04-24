package com.gsanders.phityo.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Session::class], version = 1, exportSchema = false)
abstract class PhityoDb : RoomDatabase() {
    abstract fun sessionDao(): SessionDao

    companion object {
        @Volatile private var instance: PhityoDb? = null

        fun get(ctx: Context): PhityoDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    ctx.applicationContext,
                    PhityoDb::class.java,
                    "phityo.db"
                ).build().also { instance = it }
            }
    }
}
