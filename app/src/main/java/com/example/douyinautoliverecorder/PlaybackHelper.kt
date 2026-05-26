package com.example.douyinautoliverecorder

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object PlaybackHelper {

    fun openRecording(context: Context, recordingPath: String): Boolean {
        val uri = toPlaybackUri(context, recordingPath) ?: return false
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "video/mp4")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)

        if (intent.resolveActivity(context.packageManager) == null) {
            return false
        }

        context.startActivity(intent)
        return true
    }

    private fun toPlaybackUri(context: Context, recordingPath: String): Uri? {
        if (recordingPath.startsWith("content://")) {
            return Uri.parse(recordingPath)
        }

        val file = File(recordingPath)
        if (!file.exists()) {
            return null
        }

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }
}

