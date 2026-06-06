package com.example.douyinautoliverecorder

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PreparedRecording(
    val tempFile: File,
    val fileName: String,
    val displayPath: String,
    val mimeType: String,
    val storageMode: StorageMode,
    val treeUri: String?
)

object StorageHelper {
    private const val PUBLIC_RELATIVE_PATH = "DCIM/Screen recordings"
    private const val PUBLIC_SUBDIRECTORY = "Screen recordings"
    private const val COPY_BUFFER_SIZE = 8 * 1024 * 1024

    fun prepareRecording(
        context: Context,
        settings: AppSettings,
        roomLabel: String,
        extension: String = "mp4",
        mimeType: String = "video/mp4"
    ): PreparedRecording? {
        val safeRoom = sanitizeSegment(roomLabel.ifBlank { "room" })
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "${safeRoom}_${timestamp}.${extension.trimStart('.')}"
        return buildPreparedRecording(context, settings, fileName, mimeType)
    }

    fun prepareCompanionRecording(
        context: Context,
        settings: AppSettings,
        baseFileName: String,
        extension: String,
        mimeType: String
    ): PreparedRecording? {
        val baseName = sanitizeSegment(baseFileName.substringBeforeLast('.').ifBlank { baseFileName })
        val fileName = "${baseName}.${extension.trimStart('.')}"
        return buildPreparedRecording(context, settings, fileName, mimeType)
    }

    fun publishRecording(
        context: Context,
        recording: PreparedRecording,
        onProgress: ((Int) -> Unit)? = null,
        keepSourceFile: Boolean = false
    ): String? {
        return when (recording.storageMode) {
            StorageMode.PUBLIC_MEDIA -> publishToPublicMedia(context, recording, onProgress, keepSourceFile)
            StorageMode.DOCUMENT_TREE -> publishToTree(context, recording, onProgress, keepSourceFile)
        }
    }

    fun renameRecording(
        recording: PreparedRecording,
        newFileName: String
    ): PreparedRecording? {
        val targetFile = File(recording.tempFile.parentFile, newFileName)
        if (recording.tempFile.absolutePath == targetFile.absolutePath) {
            return recording.copy(
                fileName = newFileName,
                displayPath = buildDisplayPath(recording.storageMode, newFileName)
            )
        }

        runCatching { targetFile.delete() }
        return if (recording.tempFile.renameTo(targetFile)) {
            recording.copy(
                tempFile = targetFile,
                fileName = newFileName,
                displayPath = buildDisplayPath(recording.storageMode, newFileName)
            )
        } else {
            null
        }
    }

    fun discardRecording(recording: PreparedRecording) {
        runCatching { recording.tempFile.delete() }
    }

    private fun buildPreparedRecording(
        context: Context,
        settings: AppSettings,
        fileName: String,
        mimeType: String
    ): PreparedRecording? {
        // In-progress capture segments AND the merged output live here until published.
        // This must NOT be cacheDir: Android may evict cacheDir files at any time (even
        // mid-merge) under storage pressure, which silently destroys an in-progress recording
        // (observed: merge failing with "No such file or directory" and footage lost on Stop).
        // Use app-specific external files storage (not evictable, roomy), falling back to the
        // internal files dir if external storage is unavailable.
        val stagingBase = context.getExternalFilesDir(null) ?: context.filesDir
        val stagingDir = File(stagingBase, "recording-staging")
        if (!stagingDir.exists() && !stagingDir.mkdirs()) {
            return null
        }

        val tempFile = File(stagingDir, fileName)
        val displayPath = buildDisplayPath(settings.storageMode, fileName)

        return PreparedRecording(
            tempFile = tempFile,
            fileName = fileName,
            displayPath = displayPath,
            mimeType = mimeType,
            storageMode = settings.storageMode,
            treeUri = settings.storageTreeUri
        )
    }

    private fun publishToPublicMedia(
        context: Context,
        recording: PreparedRecording,
        onProgress: ((Int) -> Unit)?,
        keepSourceFile: Boolean
    ): String? {
        if (!recording.tempFile.exists()) {
            return null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, recording.fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, recording.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, PUBLIC_RELATIVE_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(collectionUriForMimeType(recording.mimeType), values)
                ?: return null

            return try {
                context.contentResolver.openOutputStream(uri, "w")?.buffered(COPY_BUFFER_SIZE)?.use { sink ->
                    recording.tempFile.inputStream().buffered(COPY_BUFFER_SIZE).use { input ->
                        copyWithProgress(input, sink, recording.tempFile.length(), onProgress)
                    }
                } ?: return null
                context.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null
                )
                // Published successfully; only now is it safe to drop the staging source.
                if (!keepSourceFile) {
                    discardRecording(recording)
                }
                recording.displayPath
            } catch (_: Throwable) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                null
            }
        }

        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        val folder = File(root, PUBLIC_SUBDIRECTORY)
        if (!folder.exists() && !folder.mkdirs()) {
            return null
        }

        val target = File(folder, recording.fileName)
        return try {
            target.outputStream().buffered(COPY_BUFFER_SIZE).use { sink ->
                recording.tempFile.inputStream().buffered(COPY_BUFFER_SIZE).use { input ->
                    copyWithProgress(input, sink, recording.tempFile.length(), onProgress)
                }
            }
            MediaScannerConnection.scanFile(
                context,
                arrayOf(target.absolutePath),
                arrayOf(recording.mimeType),
                null
            )
            // Published successfully; only now is it safe to drop the staging source.
            if (!keepSourceFile) {
                discardRecording(recording)
            }
            target.absolutePath
        } catch (_: Throwable) {
            runCatching { target.delete() }
            null
        }
    }

    private fun publishToTree(
        context: Context,
        recording: PreparedRecording,
        onProgress: ((Int) -> Unit)?,
        keepSourceFile: Boolean
    ): String? {
        val treeUriRaw = recording.treeUri ?: return null
        if (!recording.tempFile.exists()) {
            return null
        }

        val treeDocument = DocumentFile.fromTreeUri(context, Uri.parse(treeUriRaw)) ?: return null
        val target = treeDocument.createFile(recording.mimeType, recording.fileName) ?: return null

        return try {
            context.contentResolver.openOutputStream(target.uri, "w")?.buffered(COPY_BUFFER_SIZE)?.use { sink ->
                recording.tempFile.inputStream().buffered(COPY_BUFFER_SIZE).use { input ->
                    copyWithProgress(input, sink, recording.tempFile.length(), onProgress)
                }
            } ?: return null
            // Published successfully; only now is it safe to drop the staging source.
            if (!keepSourceFile) {
                discardRecording(recording)
            }
            target.uri.toString()
        } catch (_: Throwable) {
            runCatching { target.delete() }
            null
        }
    }

    private fun copyWithProgress(
        input: InputStream,
        output: OutputStream,
        totalBytes: Long,
        onProgress: ((Int) -> Unit)?
    ) {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var copiedBytes = 0L
        var lastPercent = -1
        reportProgress(0, onProgress)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            output.write(buffer, 0, read)
            copiedBytes += read
            val percent = if (totalBytes <= 0L) {
                0
            } else {
                ((copiedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
            }
            if (percent != lastPercent) {
                reportProgress(percent, onProgress)
                lastPercent = percent
            }
        }
        output.flush()
        reportProgress(100, onProgress)
    }

    private fun reportProgress(percent: Int, onProgress: ((Int) -> Unit)?) {
        onProgress?.invoke(percent.coerceIn(0, 100))
    }

    private fun collectionUriForMimeType(mimeType: String): Uri {
        return when {
            mimeType.startsWith("video/", ignoreCase = true) -> {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }

            mimeType.startsWith("audio/", ignoreCase = true) -> {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }

            else -> MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
    }

    private fun buildDisplayPath(storageMode: StorageMode, fileName: String): String {
        return when (storageMode) {
            StorageMode.PUBLIC_MEDIA -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "$PUBLIC_RELATIVE_PATH/$fileName"
            } else {
                val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                File(root, "$PUBLIC_SUBDIRECTORY/$fileName").absolutePath
            }

            StorageMode.DOCUMENT_TREE -> fileName
        }
    }

    private fun sanitizeSegment(value: String): String {
        val cleaned = value
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), "_")
            .trim('_')

        return cleaned.take(48).ifBlank { "room" }
    }
}
