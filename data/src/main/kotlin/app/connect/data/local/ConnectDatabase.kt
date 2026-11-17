package app.connect.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import app.connect.domain.model.Conversation
import app.connect.domain.model.ConversationId
import app.connect.domain.model.Message
import app.connect.domain.model.MessageStatus
import app.connect.domain.model.Profile
import app.connect.domain.model.UserId
import kotlinx.coroutines.flow.Flow

private const val LIST_SEPARATOR = "\u001F"

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val age: Int,
    val bio: String,
    val interests: String,
    val photoUrl: String?,
    val city: String?,
    /** When this row was last confirmed by the server. Drives the time-to-live check. */
    val fetchedAtMillis: Long,
) {
    fun toDomain() = Profile(
        id = UserId(id),
        displayName = displayName,
        age = age,
        bio = bio,
        interests = if (interests.isEmpty()) emptyList() else interests.split(LIST_SEPARATOR),
        photoUrl = photoUrl,
        city = city,
    )

    companion object {
        fun from(profile: Profile, fetchedAtMillis: Long) = ProfileEntity(
            id = profile.id.value,
            displayName = profile.displayName,
            age = profile.age,
            bio = profile.bio,
            interests = profile.interests.joinToString(LIST_SEPARATOR),
            photoUrl = profile.photoUrl,
            city = profile.city,
            fetchedAtMillis = fetchedAtMillis,
        )
    }
}

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val peerId: String,
    val peerName: String,
    val peerPhotoUrl: String?,
    val lastMessagePreview: String?,
    val lastMessageAtMillis: Long,
    val unreadCount: Int,
) {
    fun toDomain() = Conversation(
        id = ConversationId(id),
        peerId = UserId(peerId),
        peerName = peerName,
        peerPhotoUrl = peerPhotoUrl,
        lastMessagePreview = lastMessagePreview,
        lastMessageAtMillis = lastMessageAtMillis,
        unreadCount = unreadCount,
    )

    companion object {
        fun from(conversation: Conversation) = ConversationEntity(
            id = conversation.id.value,
            peerId = conversation.peerId.value,
            peerName = conversation.peerName,
            peerPhotoUrl = conversation.peerPhotoUrl,
            lastMessagePreview = conversation.lastMessagePreview,
            lastMessageAtMillis = conversation.lastMessageAtMillis,
            unreadCount = conversation.unreadCount,
        )
    }
}

/** Keyed by the client-generated id, so a message has the same row before and after the server accepts it. */
@Entity(tableName = "messages", indices = [Index("conversationId", "sentAtMillis")])
data class MessageEntity(
    @PrimaryKey val clientId: String,
    val conversationId: String,
    val senderId: String,
    val body: String,
    val sentAtMillis: Long,
    val status: String,
) {
    fun toDomain() = Message(
        clientId = clientId,
        conversationId = ConversationId(conversationId),
        senderId = UserId(senderId),
        body = body,
        sentAtMillis = sentAtMillis,
        status = MessageStatus.valueOf(status),
    )

    companion object {
        fun from(message: Message) = MessageEntity(
            clientId = message.clientId,
            conversationId = message.conversationId.value,
            senderId = message.senderId.value,
            body = message.body,
            sentAtMillis = message.sentAtMillis,
            status = message.status.name,
        )
    }
}

/** Small JSON values that need to survive a restart but do not deserve a table (the discovery filters). */
@Entity(tableName = "cache_entries")
data class CacheEntryEntity(
    @PrimaryKey val key: String,
    val json: String,
    val fetchedAtMillis: Long,
)

@Dao
interface ProfileDao {
    @Upsert
    suspend fun upsert(profile: ProfileEntity)

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun get(id: String): ProfileEntity?

    @Query("SELECT * FROM profiles WHERE id = :id")
    fun observe(id: String): Flow<ProfileEntity?>
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM conversations ORDER BY lastMessageAtMillis DESC, id")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT COALESCE(SUM(unreadCount), 0) FROM conversations")
    fun observeUnreadCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(unreadCount), 0) FROM conversations")
    suspend fun unreadCount(): Int

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun conversation(id: String): ConversationEntity?

    @Upsert
    suspend fun upsertConversations(conversations: List<ConversationEntity>)

    @Query("DELETE FROM conversations WHERE id NOT IN (:keep)")
    suspend fun deleteConversationsExcept(keep: List<String>)

    /** Replaces the list with what the server returned, in one transaction so observers see one change. */
    @Transaction
    suspend fun replaceConversations(conversations: List<ConversationEntity>) {
        deleteConversationsExcept(conversations.map { it.id })
        upsertConversations(conversations)
    }

    @Query("UPDATE conversations SET unreadCount = 0 WHERE id = :id")
    suspend fun clearUnread(id: String)

    @Query(
        "UPDATE conversations SET lastMessagePreview = :preview, lastMessageAtMillis = :atMillis, " +
            "unreadCount = unreadCount + :unreadDelta WHERE id = :id",
    )
    suspend fun touchConversation(id: String, preview: String, atMillis: Long, unreadDelta: Int): Int

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY sentAtMillis, clientId")
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE clientId = :clientId")
    suspend fun message(clientId: String): MessageEntity?

    @Upsert
    suspend fun upsertMessage(message: MessageEntity)

    @Upsert
    suspend fun upsertMessages(messages: List<MessageEntity>)
}

@Dao
interface CacheDao {
    @Upsert
    suspend fun put(entry: CacheEntryEntity)

    @Query("SELECT * FROM cache_entries WHERE `key` = :key")
    suspend fun get(key: String): CacheEntryEntity?

    @Query("DELETE FROM cache_entries WHERE `key` = :key")
    suspend fun remove(key: String)
}

@Database(
    entities = [ProfileEntity::class, ConversationEntity::class, MessageEntity::class, CacheEntryEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class ConnectDatabase : RoomDatabase() {
    abstract fun profiles(): ProfileDao

    abstract fun chat(): ChatDao

    abstract fun cache(): CacheDao

    companion object {
        fun persistent(context: Context): ConnectDatabase =
            Room.databaseBuilder(context, ConnectDatabase::class.java, "connect.db").build()

        fun inMemory(context: Context): ConnectDatabase =
            Room.inMemoryDatabaseBuilder(context, ConnectDatabase::class.java).build()
    }
}
