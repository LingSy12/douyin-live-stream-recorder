package com.example.douyinautoliverecorder

import android.content.Context
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class RecordingStartResult(
    val started: Boolean,
    val outputPath: String? = null,
    val baseFileName: String? = null,
    val error: String? = null
)

data class RecordingFinishResult(
    val message: String,
    val isError: Boolean,
    val recording: PreparedRecording? = null
)

/**
 * Records a live stream into a single output file.
 *
 * A live pull URL can drop mid-broadcast (network blip, signed-URL expiry, server reset).
 * When that happens the engine does NOT end the recording: it re-probes the room for a fresh
 * stream URL and keeps capturing into a new segment. All segments are concatenated losslessly
 * into one MP4 when the broadcast genuinely ends, so a 2-hour live stays a single file instead
 * of being split into many short clips.
 */
class RecordingEngine(
    context: Context,
    private val scope: CoroutineScope
) {
    private val appContext = context.applicationContext

    private class ActiveRecording(
        val recording: PreparedRecording,
        initialStreamUrls: List<String>,
        val reprobeStreams: suspend () -> List<String>
    ) {
        @Volatile
        var sessionId: Long = -1L

        @Volatile
        var stopping: Boolean = false

        /** Current candidate stream URLs (refreshed by re-probing between segments). */
        @Volatile
        var streamUrls: List<String> = initialStreamUrls

        /** Index into [streamUrls] for the segment currently being attempted. */
        @Volatile
        var attemptIndex: Int = 0

        /** Number of segments started so far (also the next segment's number). */
        @Volatile
        var segmentIndex: Int = 0

        /** Consecutive re-probe rounds that produced no usable data. */
        @Volatile
        var emptyRounds: Int = 0

        @Volatile
        var currentSegmentFile: File? = null

        /** Completed segment files that hold usable video, in capture order. */
        val segmentFiles: MutableList<File> =
            java.util.Collections.synchronizedList(mutableListOf<File>())

        /** Guards [finalizeAndFinish] so it runs (and reports) exactly once. */
        val finalizing = AtomicBoolean(false)
    }

    private val active = ConcurrentHashMap<String, ActiveRecording>()

    fun isRecording(roomId: String): Boolean = active.containsKey(roomId)

    fun activeCount(): Int = active.size

    fun activeRoomIds(): Set<String> = active.keys.toSet()

    fun outputPath(roomId: String): String? = active[roomId]?.recording?.displayPath

    fun stop(roomId: String) {
        active[roomId]?.let { recording ->
            if (!recording.stopping) {
                recording.stopping = true
                if (recording.sessionId > 0) {
                    FFmpegKit.cancel(recording.sessionId)
                }
            }
        }
    }

    fun stopAll() {
        active.values.toList().forEach { recording ->
            if (!recording.stopping) {
                recording.stopping = true
                if (recording.sessionId > 0) {
                    FFmpegKit.cancel(recording.sessionId)
                }
            }
        }
    }

    fun start(
        roomId: String,
        streamUrls: List<String>,
        roomLabel: String,
        settings: AppSettings,
        reprobeStreams: suspend () -> List<String>,
        onFinished: (RecordingFinishResult) -> Unit
    ): RecordingStartResult {
        if (active.containsKey(roomId)) {
            val existing = active[roomId]
            return RecordingStartResult(
                started = true,
                outputPath = existing?.recording?.displayPath,
                baseFileName = existing?.recording?.fileName?.substringBeforeLast('.')
            )
        }

        val candidates = streamUrls
            .map(String::trim)
            .filter { it.startsWith("http", ignoreCase = true) }
            .distinct()
        if (candidates.isEmpty()) {
            return RecordingStartResult(started = false, error = AppText.liveNoStream(appContext))
        }

        val recording = StorageHelper.prepareRecording(
            context = appContext,
            settings = settings,
            roomLabel = roomLabel,
            extension = "mp4",
            mimeType = "video/mp4"
        ) ?: return RecordingStartResult(
            started = false,
            error = AppText.prepareStorageFailed(appContext)
        )

        val activeRecording = ActiveRecording(
            recording = recording,
            initialStreamUrls = candidates,
            reprobeStreams = reprobeStreams
        )
        active[roomId] = activeRecording
        launchSegment(roomId, activeRecording, onFinished)
        return RecordingStartResult(
            started = true,
            outputPath = recording.displayPath,
            baseFileName = recording.fileName.substringBeforeLast('.')
        )
    }

    /** Starts ffmpeg capturing the next segment, or finalizes if there is nothing left to do. */
    private fun launchSegment(
        roomId: String,
        activeRecording: ActiveRecording,
        onFinished: (RecordingFinishResult) -> Unit
    ) {
        if (active[roomId] !== activeRecording) {
            return
        }

        val stagingDir = activeRecording.recording.tempFile.parentFile
        val stagingReady = stagingDir != null && (stagingDir.exists() || stagingDir.mkdirs())
        val streamUrl = activeRecording.streamUrls.getOrElse(activeRecording.attemptIndex) {
            activeRecording.streamUrls.lastOrNull().orEmpty()
        }

        if (activeRecording.stopping || stagingDir == null || !stagingReady || streamUrl.isBlank()) {
            scope.launch { finalizeAndFinish(roomId, activeRecording, onFinished) }
            return
        }

        activeRecording.segmentIndex += 1
        val baseName = activeRecording.recording.fileName
            .substringBeforeLast('.')
            .ifBlank { "recording" }
        val segmentFile = File(stagingDir, "$baseName.part${activeRecording.segmentIndex}.ts")
        runCatching { segmentFile.delete() }
        activeRecording.currentSegmentFile = segmentFile

        val command = buildSegmentCommand(streamUrl, segmentFile.absolutePath)
        Log.d(TAG, "launch segment room=$roomId index=${activeRecording.segmentIndex}")
        val session = FFmpegKit.executeAsync(command) { completedSession ->
            handleSegmentCompletion(roomId, activeRecording, segmentFile, completedSession, onFinished)
        }
        activeRecording.sessionId = session.sessionId
        if (activeRecording.stopping && activeRecording.sessionId > 0) {
            FFmpegKit.cancel(activeRecording.sessionId)
        }
    }

    /** Called on the ffmpeg worker thread when a segment's capture ends. */
    private fun handleSegmentCompletion(
        roomId: String,
        activeRecording: ActiveRecording,
        segmentFile: File,
        completedSession: FFmpegSession,
        onFinished: (RecordingFinishResult) -> Unit
    ) {
        if (active[roomId] !== activeRecording) {
            runCatching { segmentFile.delete() }
            return
        }

        val returnCode = completedSession.returnCode
        val hadOutput = hasUsableOutput(segmentFile)
        if (hadOutput) {
            if (!activeRecording.segmentFiles.contains(segmentFile)) {
                activeRecording.segmentFiles.add(segmentFile)
            }
            activeRecording.emptyRounds = 0
        } else {
            runCatching { segmentFile.delete() }
        }

        // User asked to stop (or the whole service is shutting down): wrap up now.
        if (ReturnCode.isCancel(returnCode) || activeRecording.stopping) {
            scope.launch { finalizeAndFinish(roomId, activeRecording, onFinished) }
            return
        }

        // The segment captured real data, then ended. The broadcast may simply have finished,
        // or the pull URL may have dropped — re-probe to find out and continue if still live.
        if (hadOutput) {
            Log.d(
                TAG,
                "segment ended room=$roomId index=${activeRecording.segmentIndex} " +
                    "bytes=${segmentFile.length()} - re-probing for continuation"
            )
            scope.launch {
                delay(SHORT_BACKOFF_MS)
                reprobeAndContinue(roomId, activeRecording, onFinished)
            }
            return
        }

        // This URL produced nothing usable. Try the next candidate quality before re-probing.
        if (activeRecording.attemptIndex < activeRecording.streamUrls.lastIndex) {
            activeRecording.attemptIndex += 1
            scope.launch {
                delay(SHORT_BACKOFF_MS)
                launchSegment(roomId, activeRecording, onFinished)
            }
            return
        }

        // Every candidate URL failed this round.
        activeRecording.emptyRounds += 1
        if (activeRecording.emptyRounds >= MAX_EMPTY_ROUNDS) {
            val logs = completedSession.allLogsAsString.ifBlank {
                completedSession.failStackTrace.orEmpty()
            }
            Log.w(TAG, "giving up room=$roomId after ${activeRecording.emptyRounds} empty rounds")
            scope.launch {
                finalizeAndFinish(roomId, activeRecording, onFinished, failureLogs = logs)
            }
            return
        }

        scope.launch {
            delay(longBackoffMs(activeRecording.emptyRounds))
            reprobeAndContinue(roomId, activeRecording, onFinished)
        }
    }

    /** Re-probes the room; continues into a fresh segment if still live, otherwise finalizes. */
    private suspend fun reprobeAndContinue(
        roomId: String,
        activeRecording: ActiveRecording,
        onFinished: (RecordingFinishResult) -> Unit
    ) {
        if (active[roomId] !== activeRecording || activeRecording.stopping) {
            finalizeAndFinish(roomId, activeRecording, onFinished)
            return
        }

        val fresh = runCatching { activeRecording.reprobeStreams() }
            .getOrElse { error ->
                Log.w(TAG, "re-probe failed room=$roomId error=${error.message}")
                emptyList()
            }

        if (active[roomId] !== activeRecording || activeRecording.stopping) {
            finalizeAndFinish(roomId, activeRecording, onFinished)
            return
        }

        val freshCandidates = fresh
            .map(String::trim)
            .filter { it.startsWith("http", ignoreCase = true) }
            .distinct()

        if (freshCandidates.isEmpty()) {
            Log.d(TAG, "re-probe room=$roomId found no live stream - finalizing")
            finalizeAndFinish(roomId, activeRecording, onFinished)
            return
        }

        activeRecording.streamUrls = freshCandidates
        activeRecording.attemptIndex = 0
        launchSegment(roomId, activeRecording, onFinished)
    }

    /** Merges every captured segment into the final file and reports the result exactly once. */
    private fun finalizeAndFinish(
        roomId: String,
        activeRecording: ActiveRecording,
        onFinished: (RecordingFinishResult) -> Unit,
        failureLogs: String? = null
    ) {
        if (!activeRecording.finalizing.compareAndSet(false, true)) {
            return
        }

        val stopping = activeRecording.stopping
        val usableSegments = activeRecording.segmentFiles
            .toList()
            .filter { it.exists() && it.length() >= MIN_USABLE_BYTES }

        if (usableSegments.isEmpty()) {
            active.remove(roomId, activeRecording)
            cleanupSegments(activeRecording)
            StorageHelper.discardRecording(activeRecording.recording)
            if (stopping) {
                onFinished(
                    RecordingFinishResult(
                        message = AppText.recordingStopped(appContext),
                        isError = false,
                        recording = null
                    )
                )
            } else {
                val message = failureLogs
                    ?.lines()
                    ?.lastOrNull { it.isNotBlank() }
                    ?: AppText.recordingFailed(appContext)
                onFinished(
                    RecordingFinishResult(
                        message = message,
                        isError = true,
                        recording = null
                    )
                )
            }
            return
        }

        val merged = mergeSegments(activeRecording, usableSegments)
        cleanupSegments(activeRecording)
        active.remove(roomId, activeRecording)

        if (merged && hasUsableOutput(activeRecording.recording.tempFile)) {
            Log.d(
                TAG,
                "finalize room=$roomId merged ${usableSegments.size} segment(s) " +
                    "bytes=${activeRecording.recording.tempFile.length()}"
            )
            onFinished(
                RecordingFinishResult(
                    message = if (stopping) {
                        AppText.recordingStoppedSaved(appContext)
                    } else {
                        AppText.recordingSaved(appContext)
                    },
                    isError = false,
                    recording = activeRecording.recording
                )
            )
        } else {
            Log.w(TAG, "finalize room=$roomId merge failed segments=${usableSegments.size}")
            StorageHelper.discardRecording(activeRecording.recording)
            onFinished(
                RecordingFinishResult(
                    message = AppText.recordingFailed(appContext),
                    isError = true,
                    recording = null
                )
            )
        }
    }

    /**
     * Losslessly concatenates [segments] into the recording's final MP4.
     * Returns true on success. On failure it salvages the single largest segment so the user
     * still keeps the longest continuous clip.
     */
    private fun mergeSegments(
        activeRecording: ActiveRecording,
        segments: List<File>
    ): Boolean {
        val output = activeRecording.recording.tempFile
        val stagingDir = output.parentFile ?: return false
        runCatching { output.delete() }

        val listFile = File(stagingDir, "${output.nameWithoutExtension}.${activeRecording.segmentIndex}.concat.txt")
        val listContent = segments.joinToString(separator = "\n") { segment ->
            // concat demuxer entries are single-quoted; escape any embedded single quotes.
            "file '${segment.absolutePath.replace("'", "'\\''")}'"
        }
        val listReady = runCatching {
            listFile.writeText(listContent + "\n", Charsets.UTF_8)
        }.isSuccess

        var merged = false
        if (listReady) {
            merged = runFfmpeg(buildConcatCommand(listFile.absolutePath, output.absolutePath, applyAacFix = true)) ||
                runFfmpeg(buildConcatCommand(listFile.absolutePath, output.absolutePath, applyAacFix = false))
        }
        runCatching { listFile.delete() }

        if (merged && hasUsableOutput(output)) {
            return true
        }

        // Fallback: at least deliver the longest single segment.
        val largest = segments.maxByOrNull { it.length() } ?: return false
        Log.w(TAG, "concat failed - salvaging largest segment ${largest.name}")
        runCatching { output.delete() }
        val salvaged = runFfmpeg(buildRemuxCommand(largest.absolutePath, output.absolutePath, applyAacFix = true)) ||
            runFfmpeg(buildRemuxCommand(largest.absolutePath, output.absolutePath, applyAacFix = false))
        return salvaged && hasUsableOutput(output)
    }

    private fun runFfmpeg(command: String): Boolean {
        return runCatching {
            val session = FFmpegKit.execute(command)
            ReturnCode.isSuccess(session.returnCode)
        }.getOrElse { error ->
            Log.w(TAG, "ffmpeg command failed: ${error.message}")
            false
        }
    }

    private fun cleanupSegments(activeRecording: ActiveRecording) {
        activeRecording.segmentFiles.toList().forEach { runCatching { it.delete() } }
        activeRecording.segmentFiles.clear()
        activeRecording.currentSegmentFile?.let { runCatching { it.delete() } }
        activeRecording.currentSegmentFile = null
    }

    private fun hasUsableOutput(file: File): Boolean {
        return file.exists() && file.length() >= MIN_USABLE_BYTES
    }

    private fun longBackoffMs(emptyRounds: Int): Long {
        val multiplier = 1L shl (emptyRounds - 1).coerceIn(0, 4)
        return (SHORT_BACKOFF_MS * multiplier).coerceAtMost(MAX_BACKOFF_MS)
    }

    /** Captures one segment as MPEG-TS, which stays concatenable even if the capture is cut short. */
    private fun buildSegmentCommand(streamUrl: String, outputPath: String): String {
        val headers = "Referer: https://live.douyin.com/\r\nOrigin: https://live.douyin.com\r\nAccept: */*\r\n\r\n"
        val args = listOf(
            "-y",
            "-user_agent", quote(DEFAULT_USER_AGENT),
            "-headers", quote(headers),
            "-rw_timeout", "15000000",
            "-protocol_whitelist", "file,http,https,tcp,tls,crypto",
            "-allowed_extensions", "ALL",
            "-multiple_requests", "1",
            "-reconnect", "1",
            "-reconnect_streamed", "1",
            "-reconnect_delay_max", "5",
            "-fflags", "+genpts",
            "-i", quote(streamUrl),
            "-map", "0:v:0?",
            "-map", "0:a:0?",
            "-dn",
            "-sn",
            "-c", "copy",
            "-f", "mpegts",
            quote(outputPath)
        )
        return args.joinToString(" ")
    }

    private fun buildConcatCommand(
        listFilePath: String,
        outputPath: String,
        applyAacFix: Boolean
    ): String {
        val args = mutableListOf(
            "-y",
            "-fflags", "+genpts",
            "-protocol_whitelist", "file,crypto,data",
            "-f", "concat",
            "-safe", "0",
            "-i", quote(listFilePath),
            "-map", "0:v:0?",
            "-map", "0:a:0?",
            "-c", "copy"
        )
        if (applyAacFix) {
            args += listOf("-bsf:a", "aac_adtstoasc")
        }
        args += listOf(
            "-movflags", "+faststart",
            "-f", "mp4",
            quote(outputPath)
        )
        return args.joinToString(" ")
    }

    private fun buildRemuxCommand(
        inputPath: String,
        outputPath: String,
        applyAacFix: Boolean
    ): String {
        val args = mutableListOf(
            "-y",
            "-fflags", "+genpts",
            "-i", quote(inputPath),
            "-map", "0:v:0?",
            "-map", "0:a:0?",
            "-c", "copy"
        )
        if (applyAacFix) {
            args += listOf("-bsf:a", "aac_adtstoasc")
        }
        args += listOf(
            "-movflags", "+faststart",
            "-f", "mp4",
            quote(outputPath)
        )
        return args.joinToString(" ")
    }

    private fun quote(value: String): String {
        return "\"${value.replace("\"", "\\\"")}\""
    }

    companion object {
        private const val TAG = "RecordingEngine"
        private const val MIN_USABLE_BYTES = 64L * 1024L
        private const val SHORT_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 30_000L

        /** Stop trying to revive a stream after this many consecutive empty re-probe rounds. */
        private const val MAX_EMPTY_ROUNDS = 5
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
