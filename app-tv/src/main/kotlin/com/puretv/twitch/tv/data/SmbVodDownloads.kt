package com.puretv.twitch.tv.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.FileSystemInformationClass
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2FileId
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import com.hierynomus.smbj.session.Session
import com.puretv.twitch.core.model.VideoInfo
import com.puretv.twitch.core.model.StreamQuality
import com.puretv.twitch.core.repository.VodRepository
import java.io.InputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.io.IOException
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.coroutineContext

data class SmbShareSettings(
    val host: String = "192.168.0.102",
    val share: String = "media",
    val folder: String = "shared",
    val username: String = "",
)

data class SmbSpaceStats(val totalBytes: Long, val usedBytes: Long, val freeBytes: Long)

@Serializable
data class SavedVod(
    val id: String,
    val channelLogin: String,
    val title: String,
    val durationSeconds: Long,
    val thumbnailUrl: String = "",
    val sourceStartSeconds: Long = 0L,
    val manifestName: String = "index.m3u8",
    val chatName: String = "chat.json",
    val savedAtMs: Long = System.currentTimeMillis(),
)

@Serializable
data class SavedChatMessage(
    val id: String,
    val offsetSeconds: Int,
    val username: String,
    val displayName: String,
    val color: String,
    val message: String,
    val translatedMessage: String? = null,
    val twitchEmotes: List<SavedTwitchEmote> = emptyList(),
    val badges: List<SavedChatBadge> = emptyList(),
)

@Serializable
data class SavedTwitchEmote(val id: String, val name: String)

@Serializable
data class SavedChatBadge(val setId: String, val version: String)

@Serializable
private data class TwitchDownloaderChatArchive(
    val video: TwitchDownloaderVideo? = null,
    val comments: List<TwitchDownloaderComment> = emptyList(),
)

@Serializable
private data class TwitchDownloaderVideo(
    val id: String = "",
    val start: Int = 0,
    val end: Int = 0,
)

@Serializable
private data class TwitchDownloaderComment(
    @SerialName("content_offset_seconds") val offsetSeconds: Int = 0,
    val commenter: TwitchDownloaderCommenter? = null,
    val message: TwitchDownloaderMessage? = null,
)

@Serializable
private data class TwitchDownloaderCommenter(val name: String = "")

@Serializable
private data class TwitchDownloaderMessage(
    val body: String = "",
    val fragments: List<TwitchDownloaderFragment> = emptyList(),
)

@Serializable
private data class TwitchDownloaderFragment(
    val text: String = "",
    val emoticon: TwitchDownloaderEmoticon? = null,
)

@Serializable
private data class TwitchDownloaderEmoticon(
    @SerialName("emoticon_id") val id: String = "",
)

private data class FoundTwitchDownloaderArchive(
    val chatPath: Path,
    val mediaPath: Path?,
    val archive: TwitchDownloaderChatArchive? = null,
    val videoStartSeconds: Int = 0,
    val videoEndSeconds: Int = 0,
)

private val downloaderArchiveCache = ConcurrentHashMap<String, FoundTwitchDownloaderArchive>()
private val twitchDownloaderJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val SRT_CUE_PATTERN = Regex("""(?m)^(\d+):(\d{2}):(\d{2}),(\d{3})\s+-->\s+(\d+):(\d{2}):(\d{2}),(\d{3})(.*)$""")

private fun srtTimestampMillis(hours: String, minutes: String, seconds: String, fraction: String): Long =
    (((hours.toLong() * 60L + minutes.toLong()) * 60L + seconds.toLong()) * 1_000L) + fraction.toLong()

private fun findTwitchDownloaderArchive(
    files: SmbFiles,
    target: ShareConnection,
    parent: Path,
    root: Path,
    vodId: String,
    channelLogin: String,
    loadComments: Boolean = false,
): FoundTwitchDownloaderArchive? {
    val cacheKey = "${target.settings.host}/${target.settings.share}/${target.settings.folder}/$vodId"
    downloaderArchiveCache[cacheKey]?.let { cached ->
        if (!loadComments || cached.archive != null) return cached
        val archive = decodeDownloaderArchive(files.readText(cached.chatPath), vodId) ?: return null
        return cached.copy(archive = archive).also { downloaderArchiveCache[cacheKey] = it }
    }
    if (channelLogin.isBlank()) return null

    for (directory in files.list(parent)) {
        if (directory == root || !files.isDirectory(directory) ||
            !directory.fileName.toString().contains(channelLogin, ignoreCase = true)
        ) continue
        val children = files.list(directory)
        val chatPath = children.firstOrNull {
            it.fileName.toString().endsWith(".json", ignoreCase = true) &&
                it.fileName.toString().contains("chat", ignoreCase = true)
        } ?: continue
        // Only inspect the small file header while resolving the media URI. The
        // archive's comments array can contain hundreds of thousands of entries;
        // parsing it here delays video startup even though playback only needs
        // the MP4 path and the VOD id. Load comments later, after video is ready.
        val header = runCatching { files.readPrefix(chatPath, DOWNLOADER_HEADER_BYTES) }.getOrNull() ?: continue
        if (twitchDownloaderVideoId(header) != vodId) continue
        val archive = if (loadComments) decodeDownloaderArchive(files.readText(chatPath), vodId) ?: continue else null
        val found = FoundTwitchDownloaderArchive(
            chatPath = chatPath,
            mediaPath = children.firstOrNull { it.fileName.toString().endsWith(".mp4", ignoreCase = true) },
            archive = archive,
            videoStartSeconds = twitchDownloaderVideoField(header, "start"),
            videoEndSeconds = twitchDownloaderVideoField(header, "end"),
        )
        downloaderArchiveCache[cacheKey] = found
        return found
    }
    return null
}

private fun decodeDownloaderArchive(content: String, vodId: String): TwitchDownloaderChatArchive? =
    runCatching {
        twitchDownloaderJson.decodeFromString<TwitchDownloaderChatArchive>(content)
    }.getOrNull()?.takeIf { it.video?.id == vodId }

private fun twitchDownloaderVideoId(header: String): String? {
    val videoStart = header.indexOf("\"video\"")
    if (videoStart < 0) return null
    val commentsStart = header.indexOf("\"comments\"", videoStart)
    val videoSection = header.substring(videoStart, if (commentsStart >= 0) commentsStart else header.length)
    return Regex("\"id\"\\s*:\\s*\"([0-9]+)\"").find(videoSection)?.groupValues?.get(1)
}

private fun twitchDownloaderVideoField(header: String, field: String): Int {
    val videoStart = header.indexOf("\"video\"")
    if (videoStart < 0) return 0
    val commentsStart = header.indexOf("\"comments\"", videoStart)
    val videoSection = header.substring(videoStart, if (commentsStart >= 0) commentsStart else header.length)
    return Regex("\"${Regex.escape(field)}\"\\s*:\\s*(\\d+)")
        .find(videoSection)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
}

/** Rebase full-VOD SRT cue times to the start and duration of a TwitchDownloader clip. */
internal fun alignSrtToClip(srt: String, clipStartSeconds: Int, clipDurationSeconds: Int?): String {
    if (clipStartSeconds <= 0) return srt
    val offsetMs = clipStartSeconds.toLong() * 1_000L
    val clipEndMs = clipDurationSeconds?.takeIf { it > 0 }?.let { offsetMs + it * 1_000L }

    fun timestamp(value: Long): String {
        val safe = value.coerceAtLeast(0L)
        val hours = safe / 3_600_000L
        val minutes = (safe / 60_000L) % 60L
        val seconds = (safe / 1_000L) % 60L
        val fraction = safe % 1_000L
        return "%02d:%02d:%02d,%03d".format(java.util.Locale.ROOT, hours, minutes, seconds, fraction)
    }

    return srt.removePrefix("\uFEFF")
        .split(Regex("\\r?\\n\\s*\\r?\\n"))
        .mapNotNull { block ->
            val lines = block.split(Regex("\\r?\\n")).toMutableList()
            val timingIndex = lines.indexOfFirst { SRT_CUE_PATTERN.matches(it) }
            if (timingIndex < 0) return@mapNotNull block
            val match = SRT_CUE_PATTERN.matchEntire(lines[timingIndex]) ?: return@mapNotNull block
            val originalStart = srtTimestampMillis(match.groupValues[1], match.groupValues[2], match.groupValues[3], match.groupValues[4])
            val originalEnd = srtTimestampMillis(match.groupValues[5], match.groupValues[6], match.groupValues[7], match.groupValues[8])
            if (originalEnd <= offsetMs || (clipEndMs != null && originalStart >= clipEndMs)) return@mapNotNull null

            val localStart = (originalStart - offsetMs).coerceAtLeast(0L)
            val localEnd = (originalEnd - offsetMs).let { end -> if (clipDurationSeconds != null && clipDurationSeconds > 0) end.coerceAtMost(clipDurationSeconds * 1_000L) else end }
            if (localEnd <= localStart) return@mapNotNull null
            lines[timingIndex] = "${timestamp(localStart)} --> ${timestamp(localEnd)}${match.groupValues[9]}"
            lines.joinToString("\n")
        }
        .joinToString("\n\n")
}

/** Avoid shifting a clip-relative SRT merely because its source video was trimmed. */
private fun isFullVodTimelineSrt(srt: String, clipStartSeconds: Int, vodEndSeconds: Int): Boolean {
    if (clipStartSeconds <= 0 || vodEndSeconds <= clipStartSeconds) return false
    val cues = SRT_CUE_PATTERN.findAll(srt).toList()
    if (cues.isEmpty()) return false
    val firstCueStart = cues.minOf { match ->
        srtTimestampMillis(match.groupValues[1], match.groupValues[2], match.groupValues[3], match.groupValues[4])
    }
    val lastCueEnd = cues.maxOf { match ->
        srtTimestampMillis(match.groupValues[5], match.groupValues[6], match.groupValues[7], match.groupValues[8])
    }
    val expectedEndMs = vodEndSeconds.toLong() * 1_000L
    val endToleranceMs = 5 * 60_000L
    return firstCueStart < clipStartSeconds.toLong() * 1_000L &&
        lastCueEnd in (expectedEndMs - endToleranceMs)..(expectedEndMs + endToleranceMs)
}

private const val DOWNLOADER_HEADER_BYTES = 16 * 1024

private fun attachTwitchDownloaderEmotes(
    saved: List<SavedChatMessage>,
    comments: List<TwitchDownloaderComment>,
): List<SavedChatMessage> {
    val commentsByText = comments.mapNotNull { comment ->
        val login = comment.commenter?.name.orEmpty().lowercase()
        val body = comment.message?.body.orEmpty()
        if (login.isBlank() || body.isBlank()) null else (login to body) to comment
    }.groupBy({ it.first }, { it.second })

    return saved.map { item ->
        if (item.twitchEmotes.isNotEmpty()) return@map item
        val candidates = commentsByText[item.username.lowercase() to item.message].orEmpty()
        val closest = candidates.minByOrNull { kotlin.math.abs(it.offsetSeconds.toLong() - item.offsetSeconds) }
            ?.takeIf { kotlin.math.abs(it.offsetSeconds.toLong() - item.offsetSeconds) <= 3L }
            ?: return@map item
        val emotes = closest.message?.fragments.orEmpty().mapNotNull { fragment ->
            val id = fragment.emoticon?.id.orEmpty()
            fragment.text.takeIf { id.isNotBlank() && it.isNotBlank() }?.let { SavedTwitchEmote(id, it) }
        }
        if (emotes.isEmpty()) item else item.copy(twitchEmotes = emotes)
    }
}

/** Stores HLS media and timed chat as a PureTV bundle on an SMB 2/3 share. */
class SmbVodDownloads(
    private val context: Context,
    private val settings: AppSettingsStore,
    private val vodRepository: VodRepository,
    private val translator: ChatslatorTranslationClient,
    private val tokenRefresher: TokenRefresher,
) {
    enum class DownloadStatus { IDLE, RUNNING, CANCELLING, COMPLETE, FAILED, CANCELLED }

    data class DownloadState(
        val video: VideoInfo? = null,
        val status: DownloadStatus = DownloadStatus.IDLE,
        val progress: Progress? = null,
        val error: String? = null,
        val savedBytes: Long? = null,
        val shareSpace: SmbSpaceStats? = null,
        val shareSpaceLoading: Boolean = false,
        val shareSpaceError: String? = null,
    ) {
        val isActive: Boolean get() = status == DownloadStatus.RUNNING || status == DownloadStatus.CANCELLING
    }

    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloadLock = Any()
    private var downloadJob: Job? = null
    private val _downloadState = MutableStateFlow(DownloadState())
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    /** Starts one app-scoped SMB download. Screen navigation cannot cancel this job. */
    fun startDownload(video: VideoInfo, quality: StreamQuality): Boolean = synchronized(downloadLock) {
        if (downloadJob?.isCompleted == false) return false
        _downloadState.value = DownloadState(
            video = video,
            status = DownloadStatus.RUNNING,
            progress = Progress(0, 0, "Starting"),
            shareSpaceLoading = true,
        )
        val job = downloadScope.launch(start = CoroutineStart.LAZY) {
            try {
                refreshShareSpace()
                val bytes = save(video, quality) { next ->
                    _downloadState.update { current ->
                        val previous = current.progress
                        val display = if (next.phase == "Downloading video" || previous == null) next else next.copy(
                            bytesTransferred = previous.bytesTransferred,
                            estimatedTotalBytes = previous.estimatedTotalBytes,
                            bytesPerSecond = previous.bytesPerSecond,
                        )
                        current.copy(progress = display)
                    }
                }
                _downloadState.update {
                    it.copy(status = DownloadStatus.COMPLETE, progress = null, error = null, savedBytes = bytes)
                }
            } catch (cancelled: CancellationException) {
                _downloadState.update { it.copy(status = DownloadStatus.CANCELLED, progress = null) }
                throw cancelled
            } catch (error: Throwable) {
                _downloadState.update {
                    it.copy(status = DownloadStatus.FAILED, progress = null, error = error.message ?: "VOD download failed")
                }
            } finally {
                synchronized(downloadLock) {
                    if (downloadJob === coroutineContext[Job]) downloadJob = null
                }
            }
        }
        downloadJob = job
        job.start()
        true
    }

    fun cancelDownload() = synchronized(downloadLock) {
        val job = downloadJob ?: return
        if (job.isActive) {
            _downloadState.update { it.copy(status = DownloadStatus.CANCELLING) }
            job.cancel()
        }
    }

    suspend fun refreshShareSpace() {
        _downloadState.update { it.copy(shareSpaceLoading = true, shareSpaceError = null) }
        val result = runCatching { shareSpace() }
        _downloadState.update {
            it.copy(
                shareSpace = result.getOrNull(),
                shareSpaceLoading = false,
                shareSpaceError = if (result.isFailure || result.getOrNull() == null) "Share capacity unavailable" else null,
            )
        }
    }

    data class Progress(
        val completed: Int,
        val total: Int,
        val phase: String,
        val bytesTransferred: Long = 0,
        val estimatedTotalBytes: Long? = null,
        val bytesPerSecond: Long? = null,
        val currentItemFraction: Float = 0f,
    ) {
        val fraction: Float? get() = if (total <= 0) null else
            ((completed + currentItemFraction.coerceIn(0f, 1f)) / total.toFloat()).coerceIn(0f, 1f)
    }

    suspend fun save(video: VideoInfo, quality: StreamQuality, onProgress: (Progress) -> Unit = {}): Long = withContext(Dispatchers.IO) {
        val target = connectionSettings()
        val parent = smbFolderPath(target.settings.folder).resolve("puretv-vods")
        val root = parent.resolve(smbFolderPath(video.id))
        val tempRoot = parent.resolve(".${smbFolderPath(video.id)}.partial")
        val backup = parent.resolve(".${smbFolderPath(video.id)}.backup")
        var downloadedBytes = 0L
        try {
        SmbFiles(target).use { files ->
            files.createDirectories(parent)
            if (!files.exists(root) && files.exists(backup)) files.move(backup, root)
            else if (files.exists(root)) files.deleteTree(backup)
            files.deleteTree(tempRoot)
            files.createDirectories(tempRoot)
            runCatching { tokenRefresher.refreshIfPossible() }
            val master = vodRepository.resolvePlayableVod(video.id, settings.flow.first().accessToken.takeIf(String::isNotBlank))
            val videoVariants = master.variants.filter { it.quality != com.puretv.twitch.core.model.StreamQuality.AUDIO_ONLY }
            val variant = videoVariants.filter { it.quality == quality }.maxByOrNull { it.bandwidth }
                ?: throw IllegalStateException("Twitch no longer provides ${quality.label} for this VOD")
            val playlistText = fetchText(variant.url)
            val lines = playlistText.lines()
            val resources = linkedMapOf<String, String>()
            lines.forEach { line ->
                val direct = line.trim()
                if (direct.isNotEmpty() && !direct.startsWith("#")) resources.putIfAbsent(resolveResource(variant.url, direct), "")
                Regex("URI=\"([^\"]+)\"").findAll(line).forEach { match ->
                    resources.putIfAbsent(resolveResource(variant.url, match.groupValues[1]), "")
                }
            }
            if (resources.isEmpty()) throw IllegalStateException("Twitch returned an empty VOD playlist")
            val total = resources.size
            val segmentSizes = mutableListOf<Long>()
            val downloadStartedNanos = System.nanoTime()
            var lastProgressNanos = downloadStartedNanos
            var currentSpeed = 0L
            resources.keys.toList().forEachIndexed { index, remote ->
                coroutineContext.ensureActive()
                val fileName = "media_${index.toString().padStart(6, '0')}.${extension(remote)}"
                val local = tempRoot.resolve(fileName)
                var currentSegmentBytes = 0L
                var currentSegmentSize: Long? = null
                fun publishDownloadProgress(force: Boolean = false) {
                    val now = System.nanoTime()
                    if (!force && now - lastProgressNanos < PROGRESS_UPDATE_INTERVAL_NANOS) return
                    lastProgressNanos = now
                    val elapsedSeconds = ((now - downloadStartedNanos) / 1_000_000_000.0).coerceAtLeast(0.1)
                    currentSpeed = (downloadedBytes / elapsedSeconds).toLong()
                    val averageSegmentSize = segmentSizes.average().takeIf { segmentSizes.isNotEmpty() }?.toLong()
                        ?: currentSegmentSize
                    val remainingItems = total - index - 1
                    val estimatedTotal = when {
                        index + 1 == total -> downloadedBytes
                        index >= 2 && averageSegmentSize != null ->
                            downloadedBytes + ((currentSegmentSize ?: averageSegmentSize) - currentSegmentBytes).coerceAtLeast(0L) + averageSegmentSize * remainingItems
                        else -> null
                    }
                    val itemFraction = currentSegmentSize?.takeIf { it > 0L }
                        ?.let { (currentSegmentBytes.toFloat() / it).coerceIn(0f, 1f) } ?: 0f
                    onProgress(
                        Progress(
                            completed = index,
                            total = total,
                            phase = "Downloading video",
                            bytesTransferred = downloadedBytes,
                            estimatedTotalBytes = estimatedTotal,
                            bytesPerSecond = currentSpeed,
                            currentItemFraction = itemFraction,
                        ),
                    )
                }
                val segmentSize = downloadResource(remote, files.output(local)) { delta, current, expected ->
                    downloadedBytes += delta
                    currentSegmentBytes = current
                    currentSegmentSize = expected
                    publishDownloadProgress()
                }
                segmentSizes += segmentSize
                resources[remote] = fileName
                publishDownloadProgress(force = true)
                onProgress(
                    Progress(
                        completed = index + 1,
                        total = total,
                        phase = "Downloading video",
                        bytesTransferred = downloadedBytes,
                        estimatedTotalBytes = if (index + 1 == total) downloadedBytes else null,
                        bytesPerSecond = currentSpeed,
                    ),
                )
            }
            val rewrittenPlaylist = rewriteHlsPlaylist(playlistText, variant.url, resources)

            // Preserve the time-sensitive VOD media first. Long VOD chat
            // histories can take a while to fetch and translate message by
            // message, so chat work must not delay the segment transfer.
            val comments = try {
                fetchAllComments(video.id, onProgress)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onProgress(Progress(total, total, "Video saved; chat unavailable"))
                emptyList()
            }
            val translateSettings = settings.chatTranslation.first()
            val chat = comments.mapIndexed { index, comment ->
                coroutineContext.ensureActive()
                val message = comment.message
                val translated = if (!message.isSystem && !message.deleted) {
                    runCatching {
                        translator.translate(message, translateSettings.targetLanguage, emptySet())?.text
                    }.getOrNull()
                } else null
                onProgress(Progress(index + 1, comments.size.coerceAtLeast(1), "Translating chat"))
                SavedChatMessage(
                    id = message.id,
                    offsetSeconds = comment.offsetSeconds,
                    username = message.username,
                    displayName = message.displayName,
                    color = message.color,
                    message = message.message,
                    translatedMessage = translated,
                    twitchEmotes = message.parsedParts.mapNotNull { part ->
                        (part as? com.puretv.twitch.core.model.MessagePart.TwitchEmote)
                            ?.let { SavedTwitchEmote(it.id, it.name) }
                    },
                )
            }
            files.writeText(tempRoot.resolve("index.m3u8"), rewrittenPlaylist)
            files.writeText(tempRoot.resolve("chat.json"), JSON.encodeToString(chat))
            files.writeText(
                tempRoot.resolve("metadata.json"),
                JSON.encodeToString(SavedVod(video.id, video.userLogin, video.title, video.durationSeconds, video.thumbnailUrl)),
            )
            coroutineContext.ensureActive()
            files.deleteTree(backup)
            if (files.exists(root)) files.move(root, backup)
            try {
                files.move(tempRoot, root)
                files.deleteTree(backup)
            } catch (error: Throwable) {
                if (!files.exists(root) && files.exists(backup)) files.move(backup, root)
                throw error
            }
            onProgress(
                Progress(
                    completed = total,
                    total = total,
                    phase = "Saved",
                    bytesTransferred = downloadedBytes,
                    estimatedTotalBytes = downloadedBytes,
                    bytesPerSecond = currentSpeed,
                ),
            )
        }
        downloadedBytes
        } catch (error: Throwable) {
            runCatching { SmbFiles(target).use { it.deleteTree(tempRoot) } }
            throw error
        }
    }

    suspend fun savedVods(): List<SavedVod> = withContext(Dispatchers.IO) {
        val target = connectionSettings()
        SmbFiles(target).use { files ->
            val root = smbFolderPath(target.settings.folder).resolve("puretv-vods")
            if (!files.exists(root)) return@withContext emptyList()
            files.list(root).mapNotNull { path ->
                runCatching {
                    val metadata = JSON.decodeFromString<SavedVod>(files.readText(path.resolve("metadata.json")))
                    val archiveStart = findTwitchDownloaderArchive(
                        files = files,
                        target = target,
                        parent = root,
                        root = path,
                        vodId = metadata.id,
                        channelLogin = metadata.channelLogin,
                    )?.videoStartSeconds?.toLong() ?: 0L
                    metadata.copy(sourceStartSeconds = maxOf(metadata.sourceStartSeconds, archiveStart))
                }.getOrNull()
            }.sortedByDescending { it.savedAtMs }
        }
    }

    suspend fun savedVod(id: String): SavedVod? = savedVods().firstOrNull { it.id == id }

    suspend fun mediaUri(id: String): String = withContext(Dispatchers.IO) {
        val target = connectionSettings()
        SmbFiles(target).use { files ->
            val parent = smbFolderPath(target.settings.folder).resolve("puretv-vods")
            val root = parent.resolve(smbFolderPath(id))
            val metadata = runCatching { JSON.decodeFromString<SavedVod>(files.readText(root.resolve("metadata.json"))) }.getOrNull()
            val archive = metadata?.let { findTwitchDownloaderArchive(files, target, parent, root, id, it.channelLogin) }
            val segmentedCopy = archive?.chatPath?.parent?.resolve("puretv-hls")?.resolve("index.m3u8")
                ?.takeIf(files::exists)
            val path = segmentedCopy ?: archive?.mediaPath ?: root.resolve("index.m3u8")
            val uri = "smb://${target.settings.host}/${target.settings.share}/${path.toString().replace('\\', '/').trim('/')}"
            uri
        }
    }

    suspend fun chat(id: String): List<SavedChatMessage> = withContext(Dispatchers.IO) {
        val target = connectionSettings()
        SmbFiles(target).use { files ->
            val parent = smbFolderPath(target.settings.folder).resolve("puretv-vods")
            val root = parent.resolve(smbFolderPath(id))
            JSON.decodeFromString<List<SavedChatMessage>>(files.readText(root.resolve("chat.json")))
        }
    }

    /** Loads optional TwitchDownloader emote metadata after the saved chat is already visible. */
    suspend fun attachArchivedEmotes(id: String, saved: List<SavedChatMessage>): List<SavedChatMessage> = withContext(Dispatchers.IO) {
        if (saved.isEmpty() || saved.all { it.twitchEmotes.isNotEmpty() }) return@withContext saved
        val target = connectionSettings()
        SmbFiles(target).use { files ->
            val parent = smbFolderPath(target.settings.folder).resolve("puretv-vods")
            val root = parent.resolve(smbFolderPath(id))
            val metadata = runCatching { JSON.decodeFromString<SavedVod>(files.readText(root.resolve("metadata.json"))) }.getOrNull()
                ?: return@use saved
            val archive = findTwitchDownloaderArchive(files, target, parent, root, id, metadata.channelLogin, loadComments = true)
                ?: return@use saved
            attachTwitchDownloaderEmotes(saved, archive.archive?.comments.orEmpty())
        }
    }

    /** Finds an SRT stored beside the matching TwitchDownloader archive on the share. */
    suspend fun subtitleUri(id: String): Uri? = withContext(Dispatchers.IO) {
        val target = connectionSettings()
        SmbFiles(target).use { files ->
            val parent = smbFolderPath(target.settings.folder).resolve("puretv-vods")
            val root = parent.resolve(smbFolderPath(id))
            val metadata = runCatching { JSON.decodeFromString<SavedVod>(files.readText(root.resolve("metadata.json"))) }.getOrNull()
                ?: return@use null
            val archive = findTwitchDownloaderArchive(files, target, parent, root, id, metadata.channelLogin)
                ?: return@use null
            val folder = archive.chatPath.parent
            val subtitles = files.list(folder).filter { it.fileName.toString().endsWith(".srt", ignoreCase = true) }
            val wanted = "${metadata.title}.srt"
            val subtitle = subtitles.firstOrNull { it.fileName.toString().equals(wanted, ignoreCase = true) }
                ?: subtitles.singleOrNull()
                ?: return@use null
            val encodedPath = subtitle.toString().replace('\\', '/').trim('/')
                .split('/').joinToString("/") { Uri.encode(it) }
            Uri.parse("smb://${Uri.encode(target.settings.host)}/${Uri.encode(target.settings.share)}/$encodedPath")
        }
    }

    suspend fun updateShare(value: SmbShareSettings, password: String) {
        settings.saveSmbShareSettings(value)
        settings.saveSmbPassword(password)
    }

    suspend fun shareSettings(): SmbShareSettings = settings.smbShareSettings.first()

    suspend fun shareSpace(): SmbSpaceStats? = withContext(Dispatchers.IO) {
        val target = connectionSettings()
        runCatching {
            configuredSmbClient().use { client ->
                client.connect(target.settings.host).use { connection ->
                    val authentication = if (target.settings.username.isBlank()) {
                        AuthenticationContext.guest()
                    } else {
                        AuthenticationContext(target.settings.username, target.password.toCharArray(), "")
                    }
                    val session = connection.authenticate(authentication)
                    session.use {
                        val diskShare = session.connectShare(target.settings.share) as DiskShare
                        diskShare.use { share ->
                            share.openDirectory(
                                "",
                                EnumSet.of(AccessMask.FILE_LIST_DIRECTORY, AccessMask.FILE_READ_ATTRIBUTES),
                                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_DIRECTORY),
                                SMB2ShareAccess.ALL,
                                SMB2CreateDisposition.FILE_OPEN,
                                EnumSet.of(SMB2CreateOptions.FILE_DIRECTORY_FILE),
                            ).use { directory ->
                                val rawInfo = queryFilesystemFullSize(
                                    session,
                                    connection.negotiatedProtocol.dialect,
                                    share.treeConnect.treeId,
                                    session.sessionId,
                                    directory.fileId,
                                )
                                val info = ByteBuffer.wrap(rawInfo).order(ByteOrder.LITTLE_ENDIAN)
                                require(info.remaining() >= FILE_FS_FULL_SIZE_INFO_BYTES) {
                                    "SMB server returned incomplete disk-space information"
                                }
                                val totalUnits = info.long.toULong().toLong()
                                val callerFreeUnits = info.long.toULong().toLong()
                                val actualFreeUnits = info.long.toULong().toLong()
                                val sectorsPerUnit = info.int.toLong() and 0xFFFF_FFFFL
                                val bytesPerSector = info.int.toLong() and 0xFFFF_FFFFL
                                val bytesPerUnit = Math.multiplyExact(sectorsPerUnit, bytesPerSector)
                                val totalBytes = Math.multiplyExact(totalUnits, bytesPerUnit)
                                val actualFreeBytes = Math.multiplyExact(actualFreeUnits, bytesPerUnit)
                                val callerFreeBytes = Math.multiplyExact(callerFreeUnits, bytesPerUnit).coerceAtMost(totalBytes)
                                SmbSpaceStats(
                                    totalBytes = totalBytes,
                                    usedBytes = (totalBytes - actualFreeBytes).coerceIn(0L, totalBytes),
                                    freeBytes = callerFreeBytes,
                                )
                            }
                        }
                    }
                }
            }
        }.getOrNull()
    }

    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        val target = connectionSettings()
        SmbFiles(target).use { files ->
            val root = smbFolderPath(target.settings.folder).resolve("puretv-vods")
            files.createDirectories(root)
            val probe = root.resolve(".puretv-write-test")
            files.writeText(probe, "ok")
            files.delete(probe)
        }
        "Connected to ${target.settings.host}/${target.settings.share}/${target.settings.folder}"
    }

    private suspend fun connectionSettings(): ShareConnection {
        val target = settings.smbShareSettings.first()
        require(target.host.isNotBlank() && target.share.isNotBlank()) { "Set the SMB server and share in Settings → Network" }
        require(target.host.matches(Regex("[A-Za-z0-9.-]+"))) { "SMB server must be a hostname or IPv4 address" }
        require(target.share.matches(Regex("[A-Za-z0-9._$ -]+")) && '/' !in target.share && '\\' !in target.share) {
            "SMB share name is invalid"
        }
        smbFolderPath(target.folder)
        return ShareConnection(target, settings.smbPassword())
    }

    private suspend fun fetchAllComments(vodId: String, onProgress: (Progress) -> Unit) = withContext(Dispatchers.IO) {
        val all = linkedMapOf<String, com.puretv.twitch.core.stream.ReplayComment>()
        var offset = 0
        repeat(MAX_COMMENT_PAGES) {
            coroutineContext.ensureActive()
            val batch = vodRepository.videoComments(vodId, offset)
            if (batch.comments.isEmpty()) return@withContext all.values.sortedBy { it.offsetSeconds }
            batch.comments.forEach { all.putIfAbsent(it.message.id, it) }
            val next = (batch.comments.maxOfOrNull { it.offsetSeconds } ?: offset) + 1
            onProgress(Progress(all.size, 0, "Loading chat · ${all.size} messages"))
            if (!batch.hasNextPage || next <= offset) return@withContext all.values.sortedBy { it.offsetSeconds }
            offset = next
        }
        all.values.sortedBy { it.offsetSeconds }
    }

    /** SMBJ's NIO adapter omits FileStore queries, so issue SMB2 QUERY_INFO for full-size info. */
    private fun queryFilesystemFullSize(
        session: Any,
        dialect: SMB2Dialect,
        treeId: Long,
        sessionId: Long,
        fileId: SMB2FileId,
    ): ByteArray {
        val loader = SMBClient::class.java.classLoader
        val requestClass = Class.forName("com.hierynomus.mssmb2.messages.SMB2QueryInfoRequest", true, loader)
        val queryTypeClass = Class.forName("com.hierynomus.mssmb2.messages.SMB2QueryInfoRequest\$SMB2QueryInfoType", true, loader)
        val queryType = queryTypeClass.enumConstants.orEmpty().first { (it as Enum<*>).name == "SMB2_0_INFO_FILESYSTEM" }
        val request = requestClass.constructors.single { it.parameterCount == 9 }.newInstance(
            dialect,
            treeId,
            sessionId,
            fileId,
            queryType,
            null,
            FileSystemInformationClass.FileFsFullSizeInformation,
            byteArrayOf(),
            emptySet<Any>(),
        )
        val packetClass = Class.forName("com.hierynomus.mssmb2.SMB2Packet", true, loader)
        val sendMethod = session.javaClass.methods.single { it.name == "send" && it.parameterTypes.contentEquals(arrayOf(packetClass)) }
        @Suppress("UNCHECKED_CAST")
        val responseFuture = sendMethod.invoke(session, request) as java.util.concurrent.Future<Any>
        val response = responseFuture.get(SMB_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return response.javaClass.getMethod("getOutputBuffer").invoke(response) as ByteArray
    }

    private fun fetchText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        return connection.inputStream.bufferedReader().use { it.readText() }.also { connection.disconnect() }
    }

    private suspend fun downloadResource(
        url: String,
        output: OutputStream,
        onBytes: (delta: Long, current: Long, expected: Long?) -> Unit,
    ): Long {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        var transferred = 0L
        try {
            connection.inputStream.use { input ->
                output.use { destination ->
                    val expected = connection.contentLengthLong.takeIf { it >= 0L }
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        destination.write(buffer, 0, count)
                        transferred += count
                        onBytes(count.toLong(), transferred, expected)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        return transferred
    }

    private fun resolveResource(base: String, reference: String): String {
        val resolved = URI(base).resolve(reference).toString()
        require(resolved.startsWith("https://", ignoreCase = true)) { "Twitch playlist contains an unsupported media URL" }
        return resolved
    }

    private fun extension(url: String): String =
        URI(url).path.substringAfterLast('.', "ts").takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) } ?: "ts"

    companion object {
        private const val MAX_COMMENT_PAGES = 20_000
        private const val PROGRESS_UPDATE_INTERVAL_NANOS = 250_000_000L
        private const val SMB_QUERY_TIMEOUT_SECONDS = 15L
        private const val FILE_FS_FULL_SIZE_INFO_BYTES = 32
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

internal fun smbFolderPath(value: String): Path {
    val components = value.replace('\\', '/').split('/').filter { it.isNotBlank() && it != "." }
    require(components.none { it == ".." }) { "SMB folder cannot contain '..'" }
    return components.fold(Path.of("")) { path, segment -> path.resolve(segment) }
}

internal fun rewriteHlsPlaylist(content: String, playlistUrl: String, resources: Map<String, String>): String =
    content.lines().joinToString("\n") { line ->
        var rewritten = line
        Regex("URI=\"([^\"]+)\"").findAll(line).toList().asReversed().forEach { match ->
            resources[resolvePlaylistResource(playlistUrl, match.groupValues[1])]?.let { local ->
                rewritten = rewritten.replace(match.value, "URI=\"$local\"")
            }
        }
        val direct = line.trim()
        if (direct.isNotEmpty() && !direct.startsWith("#")) {
            resources[resolvePlaylistResource(playlistUrl, direct)]?.let { rewritten = it }
        }
        rewritten
    }

private fun resolvePlaylistResource(base: String, reference: String): String = URI(base).resolve(reference).toString()

private data class ShareConnection(val settings: SmbShareSettings, val password: String)
private data class SmbConnectionKey(val host: String, val share: String, val username: String, val password: String)

/** Share SMB connections while a download and its HLS reads overlap. */
private object SmbSharePool {
    private data class Entry(val connection: SmbShareLease, var references: Int, var idleGeneration: Long = 0L)
    private val entries = mutableMapOf<SmbConnectionKey, Entry>()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var nextIdleGeneration = 0L

    private fun key(connection: ShareConnection) = SmbConnectionKey(
        connection.settings.host,
        connection.settings.share,
        connection.settings.username,
        connection.password,
    )

    @Synchronized
    fun acquire(connection: ShareConnection): Lease {
        val key = key(connection)
        val existing = entries[key]
        if (existing != null) {
            existing.references++
            existing.idleGeneration = 0L
            return Lease(key, existing.connection)
        }
        val shareConnection = SmbShareLease.connect(connection)
        entries[key] = Entry(shareConnection, 1)
        return Lease(key, shareConnection)
    }

    @Synchronized
    fun release(key: SmbConnectionKey, connection: SmbShareLease) {
        val entry = entries[key] ?: return
        if (entry.connection !== connection) return
        entry.references--
        if (entry.references <= 0) {
            val idleGeneration = ++nextIdleGeneration
            entry.idleGeneration = idleGeneration
            cleanupScope.launch {
                kotlinx.coroutines.delay(IDLE_CONNECTION_TTL_MS)
                val expired = synchronized(this@SmbSharePool) {
                    val current = entries[key]
                    if (current?.connection === connection && current.references == 0 && current.idleGeneration == idleGeneration) {
                        entries.remove(key)
                        true
                    } else false
                }
                if (expired) connection.close()
            }
        }
    }

    // Some SMB servers close idle sessions after roughly 30 seconds. Drop our
    // pooled connection sooner so a later HLS segment gets a fresh session.
    private const val IDLE_CONNECTION_TTL_MS = 10_000L

    class Lease internal constructor(
        private val key: SmbConnectionKey,
        val connection: SmbShareLease,
    ) : AutoCloseable {
        private var released = false
        @Synchronized
        override fun close() {
            if (released) return
            released = true
            release(key, connection)
        }
    }
}

private class SmbShareLease private constructor(
    private val client: SMBClient,
    private val connection: Connection,
    private val session: Session,
    val share: DiskShare,
) : AutoCloseable {
    override fun close() {
        runCatching { share.close() }
        runCatching { session.close() }
        runCatching { connection.close() }
        runCatching { client.close() }
    }

    companion object {
        fun connect(target: ShareConnection): SmbShareLease {
            val client = configuredSmbClient()
            var connection: Connection? = null
            var session: Session? = null
            try {
                connection = client.connect(target.settings.host)
                val authentication = if (target.settings.username.isBlank()) {
                    AuthenticationContext.guest()
                } else {
                    AuthenticationContext(target.settings.username, target.password.toCharArray(), "")
                }
                session = connection.authenticate(authentication)
                val share = session.connectShare(target.settings.share) as DiskShare
                return SmbShareLease(client, connection, session, share)
            } catch (error: Throwable) {
                runCatching { session?.close() }
                runCatching { connection?.close() }
                runCatching { client.close() }
                throw error
            }
        }
    }
}

private class SmbFiles(private val connection: ShareConnection) : AutoCloseable {
    private val lease: SmbSharePool.Lease
    private val share: DiskShare get() = lease.connection.share

    init {
        lease = SmbSharePool.acquire(connection)
    }

    fun exists(path: Path): Boolean {
        val smbPath = smbPath(path)
        return share.fileExists(smbPath) || share.folderExists(smbPath)
    }

    fun isDirectory(path: Path): Boolean = share.folderExists(smbPath(path))

    fun createDirectories(path: Path) {
        var current = ""
        smbPath(path).split('/').filter(String::isNotBlank).forEach { component ->
            current = if (current.isEmpty()) component else "$current/$component"
            if (!share.folderExists(current)) share.mkdir(current)
        }
    }

    fun output(path: Path): OutputStream {
        val file = share.openFile(
            smbPath(path),
            EnumSet.of(AccessMask.GENERIC_WRITE),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        )
        return object : FilterOutputStream(file.outputStream) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    file.close()
                }
            }
        }
    }

    fun writeText(path: Path, value: String) { output(path).bufferedWriter().use { it.write(value) } }
    fun input(path: Path): Pair<SmbFile, InputStream> {
        val file = share.openFile(
            smbPath(path),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        )
        return file to file.inputStream
    }

    fun readText(path: Path): String {
        val (file, input) = input(path)
        return try { input.bufferedReader().use { it.readText() } } finally { file.close() }
    }

    fun readPrefix(path: Path, maxBytes: Int): String {
        require(maxBytes > 0)
        val (file, input) = input(path)
        return try {
            val bytes = ByteArray(maxBytes)
            var count = 0
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read <= 0) break
                count += read
            }
            String(bytes, 0, count, Charsets.UTF_8)
        } finally {
            runCatching { input.close() }
            file.close()
        }
    }

    fun delete(path: Path) {
        val smbPath = smbPath(path)
        when {
            share.fileExists(smbPath) -> share.rm(smbPath)
            share.folderExists(smbPath) -> share.rmdir(smbPath, false)
        }
    }

    fun list(path: Path): List<Path> = share.list(smbPath(path))
        .mapNotNull { info -> info.fileName?.takeUnless { it == "." || it == ".." }?.let(path::resolve) }

    fun deleteTree(path: Path) {
        val target = smbPath(path)
        if (!share.folderExists(target)) {
            if (share.fileExists(target)) share.rm(target)
            return
        }
        list(path).forEach(::deleteTree)
        share.rmdir(target, false)
    }

    fun move(from: Path, to: Path) {
        val source = smbPath(from)
        val destination = smbPath(to)
        if (share.folderExists(source)) {
            share.openDirectory(
                source,
                EnumSet.of(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(SMB2CreateOptions.FILE_DIRECTORY_FILE),
            ).use { it.rename(destination, false) }
        } else {
            share.openFile(
                source,
                EnumSet.of(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
            ).use { it.rename(destination, false) }
        }
    }

    private fun smbPath(path: Path): String = path.toString().replace('\\', '/').trim('/')
    override fun close() = lease.close()
}

private const val SMB_CLIENT_TIMEOUT_SECONDS = 30L

/** SMBJ defaults socket reads to no timeout, which can leave screens and playback hanging forever. */
private fun configuredSmbClient(): SMBClient = SMBClient(
    SmbConfig.builder()
        .withTimeout(SMB_CLIENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .withSoTimeout(SMB_CLIENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build(),
)

/** Routes smb:// HLS resources to the SMB share and all other schemes to Media3's default source. */
class SmbRoutingDataSourceFactory(
    private val fallback: DataSource.Factory,
    private val settings: AppSettingsStore,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = object : DataSource {
        private var active: DataSource? = null
        private val listeners = mutableListOf<TransferListener>()
        @Synchronized
        override fun addTransferListener(transferListener: TransferListener) {
            listeners += transferListener
            active?.addTransferListener(transferListener)
        }
        @Synchronized
        override fun open(dataSpec: DataSpec): Long {
            val uri = dataSpec.uri
            val delegate = if (uri.scheme.equals("smb", ignoreCase = true)) SmbReadDataSource(settings) else fallback.createDataSource()
            active = delegate
            listeners.forEach(delegate::addTransferListener)
            return delegate.open(dataSpec)
        }
        @Synchronized
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = active?.read(buffer, offset, length) ?: -1
        @Synchronized
        override fun getUri(): Uri? = active?.uri
        @Synchronized
        override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders.orEmpty()
        @Synchronized
        override fun close() { active?.close(); active = null }
    }
}

private class SmbReadDataSource(private val settings: AppSettingsStore) : BaseDataSource(/* isNetwork = */ true) {
    /**
     * Media3 commonly asks a DataSource for tens of KiB at a time. On SMB that
     * turns into one synchronous network round trip per read, which can starve
     * playback on a TV over Wi-Fi. Read ahead in larger blocks and serve those
     * smaller player reads from memory.
     */
    private val readAheadBuffer = ByteArray(SMB_READ_AHEAD_BYTES)
    private var lease: SmbSharePool.Lease? = null
    private var file: SmbFile? = null
    private var currentUri: Uri? = null
    private var remaining = C.LENGTH_UNSET.toLong()
    private var remotePosition = 0L
    private var bufferedBytes = 0
    private var bufferOffset = 0
    private var transferWasStarted = false

    @Synchronized
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        try {
            val cfg = kotlinx.coroutines.runBlocking { settings.smbShareSettings.first() }
            val password = kotlinx.coroutines.runBlocking { settings.smbPassword() }
            val path = dataSpec.uri.path.orEmpty().removePrefix("/${cfg.share}/")
            val acquired = SmbSharePool.acquire(ShareConnection(cfg, password))
            lease = acquired
            val target = acquired.connection.share.openFile(
                path.trim('/'),
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
            )
            file = target
            // SMBJ exposes positional reads. Avoid InputStream.skip(), which may
            // emulate seeking by reading and discarding gigabytes when resuming a
            // large MP4 from an offset deep into a long VOD.
            remotePosition = dataSpec.position
            bufferedBytes = 0
            bufferOffset = 0
            currentUri = dataSpec.uri
            remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else dataSpec.length
            transferStarted(dataSpec)
            transferWasStarted = true
            return remaining
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    @Synchronized
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        if (bufferOffset >= bufferedBytes) {
            val request = if (remaining == C.LENGTH_UNSET.toLong()) readAheadBuffer.size
                else minOf(readAheadBuffer.size.toLong(), remaining).toInt()
            val count = file?.read(readAheadBuffer, remotePosition, 0, request) ?: C.RESULT_END_OF_INPUT
            if (count <= 0) return C.RESULT_END_OF_INPUT
            remotePosition += count
            bufferedBytes = count
            bufferOffset = 0
            bytesTransferred(count)
        }
        val available = bufferedBytes - bufferOffset
        val count = if (remaining == C.LENGTH_UNSET.toLong()) minOf(length, available)
            else minOf(length.toLong(), available.toLong(), remaining).toInt()
        readAheadBuffer.copyInto(buffer, offset, bufferOffset, bufferOffset + count)
        bufferOffset += count
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= count.toLong()
        return count
    }

    @Synchronized
    override fun getUri(): Uri? = currentUri
    @Synchronized
    override fun close() {
        currentUri = null
        runCatching { file?.close() }
        file = null
        bufferedBytes = 0
        bufferOffset = 0
        lease?.close()
        lease = null
        if (transferWasStarted) {
            transferWasStarted = false
            transferEnded()
        }
    }

    private companion object {
        const val SMB_READ_AHEAD_BYTES = 1024 * 1024
    }
}
