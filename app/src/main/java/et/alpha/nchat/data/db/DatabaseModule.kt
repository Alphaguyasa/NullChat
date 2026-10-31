package et.alpha.nchat.data.db

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): NChatDatabase =
        Room.databaseBuilder(context, NChatDatabase::class.java, NChatDatabase.NAME)
            .addMigrations(*NChatDatabase.MIGRATIONS)
            // No fallbackToDestructiveMigration: see the migration policy on
            // NChatDatabase. A crash on a missing migration is a bug we must fix;
            // silently wiping the user's only copy of their history is not.
            .build()

    @Provides
    fun provideMessageDao(database: NChatDatabase): MessageDao = database.messageDao()

    @Provides
    fun providePeerDao(database: NChatDatabase): PeerDao = database.peerDao()

    @Provides
    fun provideConversationDao(database: NChatDatabase): ConversationDao =
        database.conversationDao()

    @Provides
    fun provideForwardQueueDao(database: NChatDatabase): ForwardQueueDao =
        database.forwardQueueDao()

    @Provides
    fun provideTimerChangeDao(database: NChatDatabase): TimerChangeDao =
        database.timerChangeDao()
}
