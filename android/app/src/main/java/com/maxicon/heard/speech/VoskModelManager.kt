package com.maxicon.heard.speech

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object VoskModelManager {

    enum class Status { NOT_DOWNLOADED, DOWNLOADING, READY, ERROR }

    private const val MODEL_NAME = "vosk-model-small-en-us-0.15"
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"
    private const val MODELS_DIR = "vosk_models"

    fun status(context: Context): Status = statusForDir(context.filesDir)

    fun modelPath(context: Context): String? {
        val dir = File(File(context.filesDir, MODELS_DIR), MODEL_NAME)
        return if (dir.exists() && dir.isDirectory && !dir.list().isNullOrEmpty()) dir.absolutePath else null
    }

    suspend fun download(
        context: Context,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val modelsRoot = File(context.filesDir, MODELS_DIR)
        val targetDir = File(modelsRoot, MODEL_NAME)
        val tempZip = File(modelsRoot, "$MODEL_NAME.zip.tmp")
        var completed = false

        try {
            modelsRoot.mkdirs()
            tempZip.delete()

            val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.connect()

            val totalBytes = connection.contentLengthLong

            FileOutputStream(tempZip).use { out ->
                connection.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        ensureActive()
                        out.write(buf, 0, read)
                        downloaded += read
                        if (totalBytes > 0) {
                            onProgress((downloaded.toFloat() / totalBytes * 0.9f).coerceAtMost(0.9f))
                        }
                    }
                }
            }

            ensureActive()
            onProgress(0.9f)
            targetDir.deleteRecursively()
            targetDir.mkdirs()

            ZipInputStream(FileInputStream(tempZip)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    ensureActive()
                    val relative = entry.name.substringAfter("/")
                    if (relative.isNotBlank()) {
                        val outFile = File(targetDir, relative)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out -> zis.copyTo(out) }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            completed = true
            onProgress(1f)
            true
        } finally {
            tempZip.delete()
            if (!completed) targetDir.deleteRecursively()
        }
    }

    fun delete(context: Context) = modelDir(context).deleteRecursively()

    internal fun statusForDir(filesDir: File): Status {
        val dir = File(File(filesDir, MODELS_DIR), MODEL_NAME)
        return if (dir.exists() && dir.isDirectory && !dir.list().isNullOrEmpty()) Status.READY else Status.NOT_DOWNLOADED
    }

    internal fun deleteFromDir(filesDir: File) =
        File(File(filesDir, MODELS_DIR), MODEL_NAME).deleteRecursively()

    private fun modelDir(context: Context): File =
        File(File(context.filesDir, MODELS_DIR), MODEL_NAME)
}
