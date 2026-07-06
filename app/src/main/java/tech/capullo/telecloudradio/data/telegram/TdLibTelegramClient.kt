package tech.capullo.telecloudradio.data.telegram

import android.content.Context
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import tech.capullo.telecloudradio.data.credentials.CredentialsRepository
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Requires the :tdlib module (Java sources + native libs).
// Run scripts/setup_tdlib.sh once to populate it.
@Singleton
class TdLibTelegramClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val credentials: CredentialsRepository,
) : TelegramClient {

    private val _authState = MutableStateFlow<AuthState>(AuthState.Unknown)
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _newAudioMessages = MutableSharedFlow<TelegramMessage>(extraBufferCapacity = 16)
    override val newAudioMessages: SharedFlow<TelegramMessage> = _newAudioMessages.asSharedFlow()

    private val _deletedMessages = MutableSharedFlow<Pair<Long, List<Long>>>(extraBufferCapacity = 16)
    override val deletedMessages: SharedFlow<Pair<Long, List<Long>>> = _deletedMessages.asSharedFlow()

    // For async work spawned from TDLib's update callback (which must not block)
    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client: Client = Client.create(::handleUpdate, null, null)

    // fileId → progress callback, active only while a download is in flight
    private val progressCallbacks = ConcurrentHashMap<Int, (Float) -> Unit>()

    // senderId → username ("@name") or null when the user has none; avoids repeated GetUser calls
    private val usernameCache = ConcurrentHashMap<Long, String>()
    private val noUsername = ""

    private val audioExtensions = setOf(
        "mp3", "m4a", "flac", "ogg", "opus", "wav", "aac", "wma", "aiff", "aif", "alac", "ape",
    )

    init {
        Client.execute(TdApi.SetLogVerbosityLevel(0))
    }

    override fun setupParameters() {
        if (!credentials.hasCredentials()) return
        val params = TdApi.SetTdlibParameters()
        params.databaseDirectory = context.filesDir.absolutePath + "/tdlib"
        params.filesDirectory = context.filesDir.absolutePath + "/tdlib/files"
        params.useFileDatabase = true
        params.useChatInfoDatabase = true
        params.useMessageDatabase = true
        params.useSecretChats = false
        params.apiId = credentials.apiId
        params.apiHash = credentials.apiHash
        params.systemLanguageCode = "en"
        params.deviceModel = Build.MODEL
        params.systemVersion = Build.VERSION.RELEASE
        params.applicationVersion = "0.1.0"
        client.send(params) {}
    }

    private fun handleUpdate(update: TdApi.Object) {
        if (update is TdApi.UpdateNewMessage) {
            val telegramMessage = update.message.toTelegramMessage() ?: return
            Log.d("TeleCloud", "UpdateNewMessage audio chat=${telegramMessage.chatId} id=${telegramMessage.id}")
            clientScope.launch {
                val withUsername = telegramMessage.senderId
                    ?.let { telegramMessage.copy(senderUsername = resolveUsername(it)) }
                    ?: telegramMessage
                _newAudioMessages.emit(withUsername)
            }
            return
        }
        if (update is TdApi.UpdateDeleteMessages) {
            // fromCache deletions are TDLib housekeeping, not real chat deletions
            if (update.isPermanent && !update.fromCache) {
                Log.d("TeleCloud", "UpdateDeleteMessages chat=${update.chatId} ids=${update.messageIds.size}")
                _deletedMessages.tryEmit(update.chatId to update.messageIds.toList())
            }
            return
        }
        if (update is TdApi.UpdateFile) {
            progressCallbacks[update.file.id]?.let { callback ->
                val expected = update.file.expectedSize.takeIf { it > 0 } ?: update.file.size
                if (expected > 0) {
                    callback((update.file.local.downloadedSize.toFloat() / expected).coerceIn(0f, 1f))
                }
            }
            return
        }
        if (update !is TdApi.UpdateAuthorizationState) return
        _authState.value = when (update.authorizationState) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                // Auto-setup if credentials are already saved (subsequent launches)
                if (credentials.hasCredentials()) setupParameters()
                AuthState.WaitParameters
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> AuthState.WaitPhone
            is TdApi.AuthorizationStateWaitCode -> AuthState.WaitCode
            is TdApi.AuthorizationStateWaitPassword -> AuthState.WaitPassword
            is TdApi.AuthorizationStateReady -> AuthState.Ready
            is TdApi.AuthorizationStateClosed -> AuthState.Unknown
            else -> AuthState.Unknown
        }
    }

    override suspend fun setPhoneNumber(phone: String) {
        send<TdApi.Ok>(TdApi.SetAuthenticationPhoneNumber(phone, null))
    }

    override suspend fun checkCode(code: String) {
        send<TdApi.Ok>(TdApi.CheckAuthenticationCode(code))
    }

    override suspend fun checkPassword(password: String) {
        send<TdApi.Ok>(TdApi.CheckAuthenticationPassword(password))
    }

    override suspend fun getMessageReactions(chatId: Long, messageId: Long): String? = runCatching { send<TdApi.Message>(TdApi.GetMessage(chatId, messageId)).extractReactions() }
        .getOrNull()

    override suspend fun getChats(limit: Int): List<TelegramChat> {
        val chats = send<TdApi.Chats>(TdApi.GetChats(TdApi.ChatListMain(), limit))
        val result = mutableListOf<TelegramChat>()
        for (chatId in chats.chatIds) {
            runCatching { result.add(send<TdApi.Chat>(TdApi.GetChat(chatId)).toTelegramChat()) }
        }
        return result
    }

    override suspend fun getChatHistory(
        chatId: Long,
        fromMessageId: Long,
        limit: Int,
    ): HistoryPage = searchChat(chatId, fromMessageId, limit, TdApi.SearchMessagesFilterAudio())

    override suspend fun getChatDocumentHistory(
        chatId: Long,
        fromMessageId: Long,
        limit: Int,
    ): HistoryPage = searchChat(chatId, fromMessageId, limit, TdApi.SearchMessagesFilterDocument())

    // SearchChatMessages fetches directly from the server, bypassing the
    // local-cache-only limitation of GetChatHistory. Non-audio results are
    // dropped by the converter (documents pass only with audio mime/extension).
    private suspend fun searchChat(
        chatId: Long,
        fromMessageId: Long,
        limit: Int,
        filter: TdApi.SearchMessagesFilter,
    ): HistoryPage {
        val result = send<TdApi.FoundChatMessages>(
            TdApi.SearchChatMessages(
                chatId,
                null,
                "",
                null,
                fromMessageId,
                0,
                limit,
                filter,
            ),
        )
        Log.d("TeleCloud", "search($filter) chat=$chatId from=$fromMessageId → ${result.messages.size} messages nextFrom=${result.nextFromMessageId}")
        val messages = result.messages.mapNotNull { it.toTelegramMessage() }
            .map { msg ->
                msg.senderId?.let { msg.copy(senderUsername = resolveUsername(it)) } ?: msg
            }
        return HistoryPage(messages, result.nextFromMessageId)
    }

    override suspend fun downloadFile(chatId: Long, messageId: Long, onProgress: (Float) -> Unit): String {
        // Re-fetch the message to get a fresh file reference (Telegram refs can expire).
        val message = send<TdApi.Message>(TdApi.GetMessage(chatId, messageId))
        val fileId = when (val c = message.content) {
            is TdApi.MessageAudio -> c.audio.audio.id
            is TdApi.MessageDocument -> c.document.document.id
            else -> throw TelegramException(0, "Message $messageId is not audio")
        }
        progressCallbacks[fileId] = onProgress
        try {
            val file = send<TdApi.File>(TdApi.DownloadFile(fileId, 1, 0, 0, true))
            return file.local.path
        } finally {
            progressCallbacks.remove(fileId)
        }
    }

    override suspend fun getReactionsInfo(chatId: Long, messageId: Long): MessageReactionsInfo {
        val message = send<TdApi.Message>(TdApi.GetMessage(chatId, messageId))
        val reactions = message.interactionInfo?.reactions
        val summary = message.extractReactions()
        val ownEmoji = reactions?.reactions
            ?.firstOrNull { it.isChosen }
            ?.let { (it.type as? TdApi.ReactionTypeEmoji)?.emoji }

        val available = runCatching {
            send<TdApi.AvailableReactions>(TdApi.GetMessageAvailableReactions(chatId, messageId, 25))
        }.getOrNull()?.let { avail ->
            (avail.topReactions + avail.recentReactions + avail.popularReactions)
                .mapNotNull { (it.type as? TdApi.ReactionTypeEmoji)?.emoji }
                .distinct()
        } ?: emptyList()

        val canList = reactions?.canGetAddedReactions == true
        val reactors = if (canList) {
            runCatching {
                send<TdApi.AddedReactions>(
                    TdApi.GetMessageAddedReactions(chatId, messageId, null, "", 100),
                ).reactions.mapNotNull { added ->
                    val emoji = (added.type as? TdApi.ReactionTypeEmoji)?.emoji ?: return@mapNotNull null
                    ReactorEntry(emoji, senderName(added.senderId), added.isOutgoing)
                }
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        return MessageReactionsInfo(
            summary = summary,
            ownEmoji = ownEmoji,
            canListReactors = canList,
            reactors = reactors,
            available = available,
        )
    }

    override suspend fun setOwnReaction(chatId: Long, messageId: Long, emoji: String?) {
        // SetMessageReactions replaces all reactions chosen by the current user
        val types: Array<TdApi.ReactionType> =
            if (emoji == null) emptyArray() else arrayOf(TdApi.ReactionTypeEmoji(emoji))
        send<TdApi.Ok>(TdApi.SetMessageReactions(chatId, messageId, types, false))
    }

    private suspend fun senderName(sender: TdApi.MessageSender): String = when (sender) {
        is TdApi.MessageSenderUser -> runCatching {
            val user = send<TdApi.User>(TdApi.GetUser(sender.userId))
            listOf(user.firstName, user.lastName).filter { it.isNotBlank() }.joinToString(" ")
                .ifBlank { user.usernames?.activeUsernames?.firstOrNull()?.let { "@$it" } ?: "User ${sender.userId}" }
        }.getOrDefault("User ${sender.userId}")
        is TdApi.MessageSenderChat -> runCatching {
            send<TdApi.Chat>(TdApi.GetChat(sender.chatId)).title
        }.getOrDefault("Chat ${sender.chatId}")
        else -> "Unknown"
    }

    private suspend fun resolveUsername(userId: Long): String? {
        usernameCache[userId]?.let { return it.takeIf { u -> u != noUsername } }
        val username = runCatching {
            send<TdApi.User>(TdApi.GetUser(userId)).usernames?.activeUsernames?.firstOrNull()
        }.getOrNull()
        usernameCache[userId] = username ?: noUsername
        return username
    }

    override fun close() {
        client.send(TdApi.Close()) {}
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : TdApi.Object> send(function: TdApi.Function<out TdApi.Object>): T = suspendCancellableCoroutine { cont ->
        client.send(function) { result ->
            if (result is TdApi.Error) {
                cont.resumeWithException(TelegramException(result.code, result.message))
            } else {
                cont.resume(result as T)
            }
        }
    }

    private fun TdApi.Chat.toTelegramChat() = TelegramChat(
        id = id,
        title = title,
        type = when (val t = type) {
            is TdApi.ChatTypeBasicGroup -> ChatType.GROUP
            is TdApi.ChatTypeSupergroup -> if (t.isChannel) ChatType.CHANNEL else ChatType.SUPERGROUP
            else -> ChatType.OTHER
        },
    )

    private fun TdApi.Message.toTelegramMessage(): TelegramMessage? {
        val userId = (senderId as? TdApi.MessageSenderUser)?.userId
        val audio: TelegramAudio
        val captionText: String?
        when (val c = content) {
            is TdApi.MessageAudio -> {
                audio = TelegramAudio(
                    fileId = c.audio.audio.id,
                    fileName = c.audio.fileName.takeIf { it.isNotBlank() },
                    mimeType = c.audio.mimeType,
                    duration = c.audio.duration,
                    performer = c.audio.performer.takeIf { it.isNotBlank() },
                    title = c.audio.title.takeIf { it.isNotBlank() },
                    fileSize = c.audio.audio.size,
                    fileUniqueId = c.audio.audio.remote.uniqueId,
                )
                captionText = c.caption.text.takeIf { it.isNotBlank() }
            }
            // Audio sent "as file" arrives as a document — accept it when the
            // mime/extension is audio, without music metadata (no duration/tags)
            is TdApi.MessageDocument -> {
                if (!isAudioDocument(c.document.mimeType, c.document.fileName)) return null
                audio = TelegramAudio(
                    fileId = c.document.document.id,
                    fileName = c.document.fileName.takeIf { it.isNotBlank() },
                    mimeType = c.document.mimeType.takeIf { it.isNotBlank() },
                    duration = 0,
                    performer = null,
                    title = null,
                    fileSize = c.document.document.size,
                    fileUniqueId = c.document.document.remote.uniqueId,
                )
                captionText = c.caption.text.takeIf { it.isNotBlank() }
            }
            else -> return null
        }
        return TelegramMessage(
            id = id,
            chatId = chatId,
            date = date.toLong(),
            senderId = userId,
            senderUsername = null,
            caption = captionText,
            audio = audio,
            reactions = extractReactions(),
        )
    }

    private fun isAudioDocument(mimeType: String?, fileName: String?): Boolean {
        if (mimeType?.startsWith("audio/") == true) return true
        val ext = fileName?.substringAfterLast('.', "")?.lowercase() ?: return false
        return ext in audioExtensions
    }

    private fun TdApi.Message.extractReactions(): String? {
        val list = interactionInfo?.reactions?.reactions?.takeIf { it.isNotEmpty() } ?: return null
        return list.mapNotNull { r ->
            (r.type as? TdApi.ReactionTypeEmoji)?.emoji
        }.joinToString("").takeIf { it.isNotBlank() }
    }
}
