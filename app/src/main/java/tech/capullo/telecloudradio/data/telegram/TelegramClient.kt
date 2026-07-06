package tech.capullo.telecloudradio.data.telegram

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface TelegramClient {
    val authState: StateFlow<AuthState>

    // Audio messages pushed by Telegram in real time (any chat)
    val newAudioMessages: SharedFlow<TelegramMessage>

    // Permanently deleted messages pushed by Telegram: chatId to messageIds
    val deletedMessages: SharedFlow<Pair<Long, List<Long>>>

    // Sends SetTdlibParameters using saved credentials; call after credentials are stored.
    fun setupParameters()
    suspend fun setPhoneNumber(phone: String)
    suspend fun checkCode(code: String)
    suspend fun checkPassword(password: String)
    suspend fun getChats(limit: Int): List<TelegramChat>
    suspend fun getMessageReactions(chatId: Long, messageId: Long): String?
    suspend fun getReactionsInfo(chatId: Long, messageId: Long): MessageReactionsInfo
    // emoji = null clears the current user's reaction
    suspend fun setOwnReaction(chatId: Long, messageId: Long, emoji: String?)
    // Audio (music) messages
    suspend fun getChatHistory(chatId: Long, fromMessageId: Long, limit: Int): HistoryPage
    // Documents whose mime/extension is audio (files sent "as file")
    suspend fun getChatDocumentHistory(chatId: Long, fromMessageId: Long, limit: Int): HistoryPage
    suspend fun downloadFile(chatId: Long, messageId: Long, onProgress: (Float) -> Unit = {}): String
    fun close()
}

// One page of chat history; nextFromMessageId is 0 when there are no more results.
// messages may be empty while more history remains (page had no audio in it).
data class HistoryPage(
    val messages: List<TelegramMessage>,
    val nextFromMessageId: Long,
)

data class ReactorEntry(
    val emoji: String,
    val name: String,
    val isSelf: Boolean,
)

data class MessageReactionsInfo(
    val summary: String?,          // concatenated emoji, same format as MediaMessageEntity.reactions
    val ownEmoji: String?,         // reaction chosen by the current user, if any
    val canListReactors: Boolean,  // false in channels (anonymous reactions)
    val reactors: List<ReactorEntry>,
    val available: List<String>,   // emoji the current user may react with
)

sealed class AuthState {
    data object Unknown : AuthState()
    data object WaitParameters : AuthState()
    data object WaitPhone : AuthState()
    data object WaitCode : AuthState()
    data object WaitPassword : AuthState()
    data object Ready : AuthState()
    data class Error(val message: String) : AuthState()
}

data class TelegramChat(
    val id: Long,
    val title: String,
    val type: ChatType,
)

data class TelegramMessage(
    val id: Long,
    val chatId: Long,
    val date: Long,
    val senderId: Long?,
    val senderUsername: String?,
    val caption: String?,
    val audio: TelegramAudio?,
    val reactions: String?,
)

data class TelegramAudio(
    val fileId: Int,
    val fileName: String?,
    val mimeType: String?,
    val duration: Int,
    val performer: String?,
    val title: String?,
    val fileSize: Long,
    val fileUniqueId: String,
)

enum class ChatType { GROUP, SUPERGROUP, CHANNEL, OTHER }

class TelegramException(val code: Int, override val message: String) : Exception(message)
