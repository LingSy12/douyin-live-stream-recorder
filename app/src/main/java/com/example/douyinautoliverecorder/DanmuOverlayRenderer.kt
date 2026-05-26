package com.example.douyinautoliverecorder

import android.content.Context
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import org.json.JSONObject
import java.io.File

data class OverlayFinalizeResult(
    val recording: PreparedRecording,
    val hadDanmu: Boolean,
    val overlayFailed: Boolean = false,
    val fallbackRecording: PreparedRecording? = null,
    val entryCount: Int = 0
)

object DanmuOverlayRenderer {
    private const val TAG = "DanmuOverlayRenderer"
    private const val DANMU_DURATION_MS = 9000L
    private const val CANVAS_W = 1080
    private const val CANVAS_H = 1920
    private const val CHAT_X = 42
    private const val CHAT_BASELINE_Y = 1470
    private const val CHAT_LANE_COUNT = 6
    private const val CHAT_LINE_HEIGHT = 106
    private const val CHAT_WRAP_LENGTH = 15
    private const val CHAT_LINE_STEP = 40
    private const val CHAT_FONT_SIZE = 34
    private const val SYSTEM_FONT_DIR = "/system/fonts"

    fun finalizeVideo(
        context: Context,
        settings: AppSettings,
        videoRecording: PreparedRecording,
        danmuRecording: PreparedRecording?
    ): OverlayFinalizeResult {
        if (danmuRecording == null || !danmuRecording.tempFile.exists() || danmuRecording.tempFile.length() < 8L) {
            danmuRecording?.let(StorageHelper::discardRecording)
            return OverlayFinalizeResult(recording = videoRecording, hadDanmu = false, entryCount = 0)
        }

        val entries = loadEntries(danmuRecording.tempFile)
        if (entries.isEmpty()) {
            StorageHelper.discardRecording(danmuRecording)
            return OverlayFinalizeResult(recording = videoRecording, hadDanmu = false, entryCount = 0)
        }

        Log.d(
            TAG,
            "overlay start video=${videoRecording.fileName} entries=${entries.size} first=${entries.first().offsetMs} last=${entries.last().offsetMs}"
        )

        val assFile = File(
            videoRecording.tempFile.parentFile,
            videoRecording.fileName.substringBeforeLast('.') + ".ass"
        )
        val renderedFile = File(
            videoRecording.tempFile.parentFile,
            videoRecording.fileName.substringBeforeLast('.') + "_overlay.mp4"
        )
        val fontSpec = resolveFontSpec()

        return try {
            Log.d(TAG, "overlay ass font=${fontSpec.path ?: "default"} family=${fontSpec.family}")
            assFile.writeText(buildAss(entries, fontSpec.family), Charsets.UTF_8)
            val rendered = burnOverlay(
                inputVideo = videoRecording.tempFile,
                assFile = assFile,
                outputVideo = renderedFile,
                bitrate = settings.bitrate,
                fontsDir = fontSpec.fontsDir
            )
            if (!rendered) {
                runCatching { renderedFile.delete() }
                OverlayFinalizeResult(
                    recording = videoRecording,
                    hadDanmu = false,
                    overlayFailed = true,
                    entryCount = entries.size
                )
            } else {
                OverlayFinalizeResult(
                    recording = videoRecording.copy(tempFile = renderedFile),
                    hadDanmu = true,
                    overlayFailed = false,
                    fallbackRecording = videoRecording,
                    entryCount = entries.size
                )
            }
        } finally {
            StorageHelper.discardRecording(danmuRecording)
            runCatching { assFile.delete() }
        }
    }

    private fun burnOverlay(
        inputVideo: File,
        assFile: File,
        outputVideo: File,
        bitrate: BitratePreset,
        fontsDir: String?
    ): Boolean {
        runCatching { outputVideo.delete() }
        val bitrateArg = "${bitrate.videoBitrateKbps}k"
        val filterVariants = listOf(
            buildVideoCompositeFilter(assFile, fontsDir),
            buildVideoCompositeFilter(assFile, null)
        ).distinct()
        val encoderVariants = listOf(
            listOf(
                "-c:v", "h264_mediacodec",
                "-b:v", bitrateArg,
                "-pix_fmt", "yuv420p",
                "-c:a", "copy"
            ),
            listOf(
                "-c:v", "libopenh264",
                "-b:v", bitrateArg,
                "-maxrate", bitrateArg,
                "-bufsize", "${bitrate.videoBitrateKbps * 2}k",
                "-pix_fmt", "yuv420p",
                "-c:a", "copy"
            ),
            listOf(
                "-c:v", "mpeg4",
                "-b:v", bitrateArg,
                "-pix_fmt", "yuv420p",
                "-c:a", "aac",
                "-b:a", "128k"
            )
        )
        var attempt = 0

        filterVariants.forEachIndexed { filterIndex, filter ->
            encoderVariants.forEach { encoderArgs ->
                attempt += 1
                Log.d(TAG, "overlay filter variant=${filterIndex + 1} attempt=$attempt")
                val args = mutableListOf(
                    "-y",
                    "-i", quote(inputVideo.absolutePath),
                    "-vf", quote(filter),
                    "-map", "0:v:0",
                    "-map", "0:a:0?"
                )
                args += encoderArgs
                args += listOf(
                    "-movflags", "+faststart",
                    quote(outputVideo.absolutePath)
                )
                val session = FFmpegKit.execute(args.joinToString(" "))
                if (ReturnCode.isSuccess(session.returnCode) && outputVideo.exists() && outputVideo.length() > 128 * 1024) {
                    Log.d(TAG, "overlay encode success attempt=$attempt size=${outputVideo.length()}")
                    return true
                }
                Log.w(
                    TAG,
                    "overlay encode attempt=$attempt failed: ${session.allLogsAsString.lines().lastOrNull { it.isNotBlank() }}"
                )
                runCatching { outputVideo.delete() }
            }
        }

        return false
    }

    private fun buildVideoCompositeFilter(assFile: File, fontsDir: String?): String {
        val scale = "scale=w='if(gt(a,${CANVAS_W.toDouble()}/$CANVAS_H),$CANVAS_W,-2)':h='if(gt(a,${CANVAS_W.toDouble()}/$CANVAS_H),-2,$CANVAS_H)'"
        val pad = "pad=$CANVAS_W:$CANVAS_H:($CANVAS_W-iw)/2:($CANVAS_H-ih)/2:color=0x000000"
        val ass = buildAssFilter(assFile, fontsDir)
        return listOf(scale, pad, ass).joinToString(",")
    }

    private fun buildAssFilter(assFile: File, fontsDir: String?): String {
        val parts = mutableListOf("'${escapeFilterPath(assFile.absolutePath)}'")
        if (!fontsDir.isNullOrBlank()) {
            parts += "fontsdir='${escapeFilterPath(fontsDir)}'"
        }
        return "ass=" + parts.joinToString(":")
    }

    private fun buildAss(entries: List<DanmuEntry>, fontFamily: String): String {
        val laneFreeAt = LongArray(CHAT_LANE_COUNT)
        val events = buildString {
            entries.forEach { entry ->
                val lane = selectLane(laneFreeAt, entry.offsetMs)
                laneFreeAt[lane] = entry.offsetMs + DANMU_DURATION_MS
                val text = wrapForChat(entry.text)
                val lineCount = text.count { it == '\n' } + 1
                val start = formatAssTime(entry.offsetMs)
                val end = formatAssTime(entry.offsetMs + DANMU_DURATION_MS)
                val posY = CHAT_BASELINE_Y - lane * CHAT_LINE_HEIGHT - (lineCount - 1) * CHAT_LINE_STEP
                append("Dialogue: 0,")
                append(start)
                append(',')
                append(end)
                append(",Danmu,,0,0,0,,{\\an1\\pos(")
                append(CHAT_X)
                append(',')
                append(posY)
                append(")\\fad(120,220)}")
                append(escapeAssText(text))
                appendLine()
            }
        }

        return """
            [Script Info]
            ScriptType: v4.00+
            PlayResX: $CANVAS_W
            PlayResY: $CANVAS_H
            WrapStyle: 2
            ScaledBorderAndShadow: yes

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: Danmu,$fontFamily,$CHAT_FONT_SIZE,&H00FFFFFF,&H00FFFFFF,&H00131A24,&H66000000,0,0,0,0,100,100,0,0,1,3,0,1,0,0,0,1

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            $events
        """.trimIndent()
    }

    private fun loadEntries(file: File): List<DanmuEntry> {
        return file.useLines { lines ->
            lines.mapNotNull { line ->
                runCatching {
                    val json = JSONObject(line)
                    val text = json.optString("text").trim()
                    val offsetMs = json.optLong("offsetMs", -1L)
                    if (text.isBlank() || offsetMs < 0L) {
                        null
                    } else {
                        DanmuEntry(offsetMs = offsetMs, text = text)
                    }
                }.getOrNull()
            }.sortedBy { it.offsetMs }.toList()
        }
    }

    private fun resolveFontSpec(): FontSpec {
        val candidates = listOf(
            FontSpec(path = "/system/fonts/NotoSansSC-Regular.otf", family = "Noto Sans SC", fontsDir = SYSTEM_FONT_DIR),
            FontSpec(path = "/system/fonts/NotoSansCJK-Regular.ttc", family = "Noto Sans CJK SC", fontsDir = SYSTEM_FONT_DIR),
            FontSpec(path = "/system/fonts/NotoSansCJKsc-Regular.otf", family = "Noto Sans CJK SC", fontsDir = SYSTEM_FONT_DIR),
            FontSpec(path = "/system/fonts/SourceHanSansSC-Regular.otf", family = "Source Han Sans SC", fontsDir = SYSTEM_FONT_DIR),
            FontSpec(path = "/system/fonts/DroidSansFallback.ttf", family = "Droid Sans Fallback", fontsDir = SYSTEM_FONT_DIR),
            FontSpec(path = "/system/fonts/MiSans-Regular.ttf", family = "MiSans", fontsDir = SYSTEM_FONT_DIR),
            FontSpec(path = "/system/fonts/Roboto-Regular.ttf", family = "Roboto", fontsDir = SYSTEM_FONT_DIR)
        )
        return candidates.firstOrNull { File(it.path ?: "").exists() }
            ?: FontSpec(path = null, family = "sans-serif", fontsDir = SYSTEM_FONT_DIR)
    }

    private fun selectLane(laneFreeAt: LongArray, startMs: Long): Int {
        laneFreeAt.forEachIndexed { index, freeAt ->
            if (freeAt <= startMs) {
                return index
            }
        }
        return laneFreeAt.indices.minByOrNull { laneFreeAt[it] } ?: 0
    }

    private fun wrapForChat(value: String): String {
        val text = value.replace("\r", "").replace("\n", " ").trim()
        if (text.length <= CHAT_WRAP_LENGTH) {
            return text
        }
        return text.chunked(CHAT_WRAP_LENGTH).joinToString("\n")
    }

    private fun escapeAssText(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("{", "\\{")
            .replace("}", "\\}")
            .replace("\n", "\\N")
            .replace("\r", "")
    }

    private fun formatAssTime(timeMs: Long): String {
        val totalCentiseconds = timeMs / 10
        val cs = totalCentiseconds % 100
        val totalSeconds = totalCentiseconds / 100
        val seconds = totalSeconds % 60
        val totalMinutes = totalSeconds / 60
        val minutes = totalMinutes % 60
        val hours = totalMinutes / 60
        return "%d:%02d:%02d.%02d".format(hours, minutes, seconds, cs)
    }

    private fun escapeFilterPath(path: String): String {
        return path
            .replace("\\", "/")
            .replace(":", "\\:")
            .replace("'", "\\'")
    }

    private fun quote(value: String): String {
        return "\"${value.replace("\"", "\\\"")}\""
    }

    private data class DanmuEntry(
        val offsetMs: Long,
        val text: String
    )

    private data class FontSpec(
        val path: String?,
        val family: String,
        val fontsDir: String?
    )
}

