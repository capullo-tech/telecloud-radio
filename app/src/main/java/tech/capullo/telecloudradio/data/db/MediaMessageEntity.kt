package tech.capullo.telecloudradio.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "media_messages")
data class MediaMessageEntity(
    @PrimaryKey val messageId: Long,
    val chatId: Long,
    val date: String,
    val senderId: Long?,
    val senderUsername: String?,
    val caption: String?,
    val fileName: String?,
    val fileUniqueId: String?,
    val fileId: Int,
    val duration: Int?,
    val performer: String?,
    val title: String?,
    val fileSize: Long?,
    val mimeType: String?,
    val station: String?,
    val groupType: String?,
    val reactions: String?,
    val localPath: String? = null,
)
