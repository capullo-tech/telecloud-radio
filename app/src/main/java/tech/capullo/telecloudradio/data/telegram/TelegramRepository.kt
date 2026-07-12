package tech.capullo.telecloudradio.data.telegram

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import tech.capullo.source.telegram.data.telegram.AuthState
import tech.capullo.source.telegram.data.telegram.ChatType
import tech.capullo.source.telegram.data.telegram.HistoryPage
import tech.capullo.source.telegram.data.telegram.MessageReactionsInfo
import tech.capullo.source.telegram.data.telegram.TelegramChat
import tech.capullo.source.telegram.data.telegram.TelegramClient
import tech.capullo.source.telegram.data.telegram.TelegramMessage
import tech.capullo.telecloudradio.data.db.AudioAnalysisDao
import tech.capullo.telecloudradio.data.db.MediaMessageDao
import tech.capullo.telecloudradio.data.db.MediaMessageEntity
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TelegramRepository @Inject constructor(
    private val client: TelegramClient,
    private val dao: MediaMessageDao,
    private val analysisDao: AudioAnalysisDao,
) {
    val authState: StateFlow<AuthState> = client.authState

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Emits (chatId, track title) after a pushed message was stored in the DB
    private val _newTrackStored = MutableSharedFlow<Pair<Long, String>>(extraBufferCapacity = 16)
    val newTrackStored: SharedFlow<Pair<Long, String>> = _newTrackStored.asSharedFlow()

    // Emits (chatId, messageIds) after tracks were removed from the DB
    private val _tracksDeleted = MutableSharedFlow<Pair<Long, List<Long>>>(extraBufferCapacity = 16)
    val tracksDeleted: SharedFlow<Pair<Long, List<Long>>> = _tracksDeleted.asSharedFlow()

    init {
        // Primary deletion path: Telegram pushes permanent message deletions live
        scope.launch {
            client.deletedMessages.collect { (chatId, ids) ->
                runCatching { removeTracks(chatId, ids) }
            }
        }
        // Telegram pushes new messages in real time; store audio ones for any
        // station we already know (synced at least once), regardless of which
        // screen is open.
        scope.launch {
            client.newAudioMessages.collect { msg ->
                runCatching {
                    if (insertIncomingMessage(msg)) {
                        val title = msg.audio?.title ?: msg.audio?.fileName ?: "Unknown"
                        _newTrackStored.tryEmit(msg.chatId to title)
                    }
                }
            }
        }
    }

    // Removes tracks that no longer exist in the chat: DB rows, downloaded files,
    // and analysis artifacts. Emits tracksDeleted so live queues drop them too.
    suspend fun removeTracks(chatId: Long, ids: List<Long>): List<Long> {
        val existing = dao.getByIds(ids).filter { it.chatId == chatId }
        if (existing.isEmpty()) return emptyList()
        existing.forEach { row ->
            row.localPath?.let { File(it).delete() }
            analysisDao.get(row.messageId)?.spectrogramPath?.let { File(it).delete() }
            analysisDao.delete(row.messageId)
        }
        val removedIds = existing.map { it.messageId }
        dao.deleteByIds(removedIds)
        _tracksDeleted.tryEmit(chatId to removedIds)
        return removedIds
    }

    private suspend fun insertIncomingMessage(msg: TelegramMessage): Boolean {
        if (msg.audio == null) return false
        if (dao.exists(msg.id) > 0) return false // already synced
        // Only accept chats we've synced before; reuse their station/groupType
        val known = dao.getAnyForChat(msg.chatId) ?: return false
        val station = known.station ?: return false
        dao.insertAll(listOf(msg.toEntity(station, known.groupType ?: "GROUP")))
        return true
    }

    fun setupParameters() = client.setupParameters()
    suspend fun setPhoneNumber(phone: String) = client.setPhoneNumber(phone)
    suspend fun checkCode(code: String) = client.checkCode(code)
    suspend fun checkPassword(password: String) = client.checkPassword(password)

    suspend fun getAudioGroups(limit: Int): List<TelegramChat> = client.getChats(limit).filter { it.type != ChatType.OTHER }

    // Downloads the crisp small avatar for a chat (TelegramChat.photoFileId); null if unavailable.
    suspend fun downloadChatPhoto(fileId: Int): String? = client.downloadChatPhoto(fileId)

    suspend fun syncAudioMessages(chat: TelegramChat): Int {
        val latestStored = dao.getLatestMessageId(chat.id) ?: 0L
        // Two passes: music messages + audio files sent as documents
        return syncPass(chat, latestStored) { from -> client.getChatHistory(chat.id, from, 100) } +
            syncPass(chat, latestStored) { from ->
                client.getChatDocumentHistory(chat.id, from, 100)
            }
    }

    private suspend fun syncPass(
        chat: TelegramChat,
        latestStored: Long,
        fetch: suspend (fromMessageId: Long) -> HistoryPage,
    ): Int {
        var fetchFromId = 0L // 0 = start from the newest message
        var newCount = 0
        while (true) {
            val page = fetch(fetchFromId)
            val fresh = page.messages.filter { it.id > latestStored && it.audio != null }
            if (fresh.isNotEmpty()) {
                dao.insertAll(fresh.map { it.toEntity(chat.title, chat.type.name) })
                newCount += fresh.size
            }
            // nextFromMessageId is the raw pagination cursor (0 = end of history);
            // stop once it reaches already-synced territory
            if (page.nextFromMessageId == 0L || page.nextFromMessageId <= latestStored) break
            fetchFromId = page.nextFromMessageId
        }
        return newCount
    }

    // Incremental sync when only the chatId is known (auto-open / periodic refresh):
    // station name and type are recovered from previously synced rows.
    suspend fun syncChatById(chatId: Long): Int {
        val known = dao.getAnyForChat(chatId) ?: return 0
        val station = known.station ?: return 0
        val type = runCatching { ChatType.valueOf(known.groupType ?: "") }
            .getOrDefault(ChatType.GROUP)
        return syncAudioMessages(TelegramChat(chatId, station, type))
    }

    suspend fun getTrackCount(chatId: Long) = dao.getTrackCount(chatId)
    suspend fun getTotalSize(chatId: Long) = dao.getTotalSize(chatId) ?: 0L
    suspend fun downloadFile(chatId: Long, messageId: Long, onProgress: (Float) -> Unit = {}) = client.downloadFile(chatId, messageId, onProgress)

    suspend fun refreshReactions(chatId: Long, messageId: Long): String? {
        val reactions = client.getMessageReactions(chatId, messageId)
        dao.updateReactions(messageId, reactions)
        return reactions
    }

    suspend fun getReactionsInfo(chatId: Long, messageId: Long): MessageReactionsInfo = client.getReactionsInfo(chatId, messageId)

    suspend fun setOwnReaction(
        chatId: Long,
        messageId: Long,
        emoji: String?,
    ): MessageReactionsInfo {
        client.setOwnReaction(chatId, messageId, emoji)
        val info = client.getReactionsInfo(chatId, messageId)
        dao.updateReactions(messageId, info.summary)
        return info
    }

    private fun TelegramMessage.toEntity(station: String, groupType: String) = MediaMessageEntity(
        messageId = id,
        chatId = chatId,
        date = date.toString(),
        senderId = senderId,
        senderUsername = senderUsername,
        caption = caption,
        fileName = audio?.fileName,
        fileUniqueId = audio?.fileUniqueId,
        fileId = audio?.fileId ?: 0,
        duration = audio?.duration,
        performer = audio?.performer,
        title = audio?.title,
        fileSize = audio?.fileSize,
        mimeType = audio?.mimeType,
        station = station,
        groupType = groupType,
        reactions = reactions,
    )
}
