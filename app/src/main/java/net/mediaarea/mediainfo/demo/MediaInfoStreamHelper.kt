package net.mediaarea.mediainfo.demo

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.mediaarea.mediainfo.lib.MediaInfo
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

class MediaInfoStreamHelper {
    
    companion object {
        private const val TAG = "MediaInfoStreamHelper"
        private const val CHUNK_SIZE = 64 * 1024 // 64 KB por chunk
        private const val MAX_BUFFER_SIZE = 120 * 1024 * 1024 // Máximo 10 MB
        
        /**
         * Analiza un stream HTTP/HTTPS de forma incremental sin descargar el archivo completo.
         * Se detiene automáticamente cuando MediaInfo tiene suficiente información.
         */
        suspend fun analyzeFromStreamIncremental(
            url: String,
            progressCallback: ((bytesRead: Long, totalBytes: Long?, status: String) -> Unit)? = null
        ): StreamAnalysisResult = withContext(Dispatchers.IO) {
            
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            
            val request = Request.Builder()
                .url(url)
                .build()
            
            var inputStream: InputStream? = null
            
            try {
                val response = client.newCall(request).execute()
                
                if (!response.isSuccessful) {
                    throw Exception("Error HTTP: ${response.code} - ${response.message}")
                }
                
                val contentLength = response.body?.contentLength() ?: -1L
                inputStream = response.body?.byteStream() 
                    ?: throw Exception("No se pudo obtener el stream")
                
                Log.d(TAG, "Iniciando análisis incremental. Content-Length: $contentLength bytes")
                progressCallback?.invoke(0, contentLength, "Iniciando análisis...")
                
                // Crear instancia de MediaInfo
                val mediaInfo = MediaInfo()
                
                // Abrir buffer para análisis incremental
                mediaInfo.Open_Buffer_Init(contentLength, 0)
                
                val buffer = ByteArray(CHUNK_SIZE)
                var totalBytesRead = 0L
                var chunkCount = 0
                var continueReading = true
                
                // Leer y procesar chunks
                while (continueReading && totalBytesRead < MAX_BUFFER_SIZE) {
                    val bytesRead = inputStream.read(buffer)
                    
                    if (bytesRead == -1) {
                        Log.d(TAG, "Fin del stream alcanzado")
                        break
                    }
                    
                    totalBytesRead += bytesRead
                    chunkCount++
                    
                    // Enviar datos a MediaInfo
                    val state = mediaInfo.Open_Buffer_Continue(buffer, bytesRead.toLong())
                    
                    val percentComplete = if (contentLength > 0) {
                        ((totalBytesRead * 100) / contentLength).toInt()
                    } else {
                        0
                    }
                    
                    Log.d(TAG, "Chunk $chunkCount: $bytesRead bytes (Total: $totalBytesRead, Estado: $state)")
                    progressCallback?.invoke(
                        totalBytesRead, 
                        contentLength, 
                        "Procesando: ${formatBytes(totalBytesRead)} ($percentComplete%)"
                    )
                    
                    // Verificar si MediaInfo ya tiene suficiente información
                    // Estado & 0x08 (bit 3) indica que el análisis está completo
                    if ((state.toInt() and 0x08) != 0) {
                        Log.d(TAG, "MediaInfo tiene información completa. Deteniendo descarga.")
                        progressCallback?.invoke(
                            totalBytesRead, 
                            contentLength, 
                            "Análisis completo con ${formatBytes(totalBytesRead)}"
                        )
                        continueReading = false
                    }
                    
                    // Seguridad: detener si hemos leído más del límite
                    if (totalBytesRead >= MAX_BUFFER_SIZE) {
                        Log.w(TAG, "Alcanzado límite máximo de buffer ($MAX_BUFFER_SIZE bytes)")
                        continueReading = false
                    }
                }
                
                // Finalizar el buffer
                mediaInfo.Open_Buffer_Finalize()
                
                // Obtener información
                mediaInfo.Option("Inform", "MIXML")
                val xmlResult = mediaInfo.Inform()
                
                // También obtener formato texto para resumen rápido
                mediaInfo.Option("Inform", "Text")
                val textResult = mediaInfo.Inform()
                
                // Obtener información general
                val format = mediaInfo.Get(MediaInfo.Stream.General, 0, "Format")
                val duration = mediaInfo.Get(MediaInfo.Stream.General, 0, "Duration/String")
                val fileSize = mediaInfo.Get(MediaInfo.Stream.General, 0, "FileSize/String")
                val bitRate = mediaInfo.Get(MediaInfo.Stream.General, 0, "OverallBitRate/String")
                
                // === INFORMACIÓN DE VIDEO ===
                val videoInfo = if (mediaInfo.Count_Get(MediaInfo.Stream.Video) > 0) {
                    VideoStreamInfo(
                        format = mediaInfo.Get(MediaInfo.Stream.Video, 0, "Format"),
                        bitRate = mediaInfo.Get(MediaInfo.Stream.Video, 0, "BitRate/String"),
                        streamSize = mediaInfo.Get(MediaInfo.Stream.Video, 0, "StreamSize/String"),
                        width = mediaInfo.Get(MediaInfo.Stream.Video, 0, "Width"),
                        height = mediaInfo.Get(MediaInfo.Stream.Video, 0, "Height"),
                        frameRate = mediaInfo.Get(MediaInfo.Stream.Video, 0, "FrameRate"),
                        codecId = mediaInfo.Get(MediaInfo.Stream.Video, 0, "CodecID")
                    )
                } else null
                
                // === INFORMACIÓN DE AUDIO ===
                val audioInfo = if (mediaInfo.Count_Get(MediaInfo.Stream.Audio) > 0) {
                    AudioStreamInfo(
                        format = mediaInfo.Get(MediaInfo.Stream.Audio, 0, "Format"),
                        bitRate = mediaInfo.Get(MediaInfo.Stream.Audio, 0, "BitRate/String"),
                        streamSize = mediaInfo.Get(MediaInfo.Stream.Audio, 0, "StreamSize/String"),
                        channels = mediaInfo.Get(MediaInfo.Stream.Audio, 0, "Channels"),
                        samplingRate = mediaInfo.Get(MediaInfo.Stream.Audio, 0, "SamplingRate/String"),
                        language = mediaInfo.Get(MediaInfo.Stream.Audio, 0, "Language")
                    )
                } else null
                
                // === INFORMACIÓN DE SUBTÍTULOS ===
                val subtitleCount = mediaInfo.Count_Get(MediaInfo.Stream.Text).toInt()
                val subtitlesInfo = mutableListOf<SubtitleStreamInfo>()
                
                for (i in 0 until subtitleCount) {
                    subtitlesInfo.add(
                        SubtitleStreamInfo(
                            format = mediaInfo.Get(MediaInfo.Stream.Text, i, "Format"),
                            bitRate = mediaInfo.Get(MediaInfo.Stream.Text, i, "BitRate/String"),
                            streamSize = mediaInfo.Get(MediaInfo.Stream.Text, i, "StreamSize/String"),
                            duration = mediaInfo.Get(MediaInfo.Stream.Text, i, "Duration/String"),
                            countOfElements = mediaInfo.Get(MediaInfo.Stream.Text, i, "Count"),
                            language = mediaInfo.Get(MediaInfo.Stream.Text, i, "Language"),
                            title = mediaInfo.Get(MediaInfo.Stream.Text, i, "Title"),
                            codecId = mediaInfo.Get(MediaInfo.Stream.Text, i, "CodecID")
                        )
                    )
                }
                
                mediaInfo.Close()
                
                val percentDownloaded = if (contentLength > 0) {
                    ((totalBytesRead.toDouble() / contentLength) * 100).toInt()
                } else {
                    0
                }
                
                Log.d(TAG, """
                    |Análisis completado:
                    |  - Bytes descargados: $totalBytesRead / $contentLength ($percentDownloaded%)
                    |  - Chunks procesados: $chunkCount
                    |  - Formato: $format
                    |  - Duración: $duration
                    |  - Video streams: ${if (videoInfo != null) 1 else 0}
                    |  - Audio streams: ${if (audioInfo != null) 1 else 0}
                    |  - Subtitle streams: $subtitleCount
                """.trimMargin())
                
                StreamAnalysisResult(
                    success = true,
                    xmlOutput = xmlResult,
                    textOutput = textResult,
                    bytesDownloaded = totalBytesRead,
                    totalFileSize = contentLength,
                    percentDownloaded = percentDownloaded,
                    format = format,
                    duration = duration,
                    fileSize = fileSize,
                    bitRate = bitRate,
                    videoInfo = videoInfo,
                    audioInfo = audioInfo,
                    subtitlesInfo = subtitlesInfo
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "Error en análisis incremental", e)
                StreamAnalysisResult(
                    success = false,
                    error = e.message ?: "Error desconocido"
                )
            } finally {
                inputStream?.close()
            }
        }
        
        /**
         * Análisis con descarga completa (método anterior como fallback)
         */
        suspend fun analyzeFromUrl(
            url: String,
            cacheDir: File,
            progressCallback: ((Int) -> Unit)? = null
        ): String = withContext(Dispatchers.IO) {

            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(url)
                .build()

            try {
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    throw Exception("Error HTTP: ${response.code}")
                }

                val contentLength = response.body?.contentLength() ?: -1
                val inputStream = response.body?.byteStream() 
                    ?: throw Exception("No se pudo obtener el stream")

                val tempFile = File.createTempFile("mediainfo_", ".tmp", cacheDir)
                tempFile.deleteOnExit()

                val outputStream = FileOutputStream(tempFile)
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead

                    if (contentLength > 0) {
                        val progress = ((totalBytesRead * 100) / contentLength).toInt()
                        progressCallback?.invoke(progress)
                    }
                }

                outputStream.close()
                inputStream.close()

                val mediaInfo = MediaInfo()

                // Use Open_Buffer methods instead of Open()
                val fileBytes = tempFile.readBytes()
                mediaInfo.Open_Buffer_Init(fileBytes.size.toLong(), 0)
                mediaInfo.Open_Buffer_Continue(fileBytes, fileBytes.size.toLong())
                mediaInfo.Open_Buffer_Finalize()

                mediaInfo.Option("Inform", "MIXML")
                val result = mediaInfo.Inform()

                mediaInfo.Close()
                tempFile.delete()

                result

            } catch (e: Exception) {
                Log.e(TAG, "Error al analizar desde URL", e)
                throw e
            }
        }
        
        /**
         * Formatea bytes a formato legible (KB, MB, GB)
         */
        private fun formatBytes(bytes: Long): String {
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> String.format("%.2f KB", bytes / 1024.0)
                bytes < 1024 * 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
                else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            }
        }
    }
    
    /**
     * Información del stream de video
     */
    data class VideoStreamInfo(
        val format: String = "",
        val bitRate: String = "",
        val streamSize: String = "",
        val width: String = "",
        val height: String = "",
        val frameRate: String = "",
        val codecId: String = ""
    )
    
    /**
     * Información del stream de audio
     */
    data class AudioStreamInfo(
        val format: String = "",
        val bitRate: String = "",
        val streamSize: String = "",
        val channels: String = "",
        val samplingRate: String = "",
        val language: String = ""
    )
    
    /**
     * Información del stream de subtítulos
     */
    data class SubtitleStreamInfo(
        val format: String = "",
        val bitRate: String = "",
        val streamSize: String = "",
        val duration: String = "",
        val countOfElements: String = "",
        val language: String = "",
        val title: String = "",
        val codecId: String = ""
    )
    
    /**
     * Resultado del análisis de stream
     */
    data class StreamAnalysisResult(
        val success: Boolean,
        val xmlOutput: String = "",
        val textOutput: String = "",
        val bytesDownloaded: Long = 0,
        val totalFileSize: Long = -1,
        val percentDownloaded: Int = 0,
        val format: String = "",
        val duration: String = "",
        val fileSize: String = "",
        val bitRate: String = "",
        val videoInfo: VideoStreamInfo? = null,
        val audioInfo: AudioStreamInfo? = null,
        val subtitlesInfo: List<SubtitleStreamInfo> = emptyList(),
        val error: String? = null
    ) {
        fun getSummary(): String {
            return if (success) {
                buildString {
                    appendLine("=== RESUMEN DEL ANÁLISIS ===")
                    appendLine("Descargado: ${formatBytes(bytesDownloaded)} de ${if (totalFileSize > 0) formatBytes(totalFileSize) else "desconocido"} ($percentDownloaded%)")
                    appendLine()
                    appendLine("Formato: $format")
                    if (duration.isNotEmpty()) appendLine("Duración: $duration")
                    if (fileSize.isNotEmpty()) appendLine("Tamaño: $fileSize")
                    if (bitRate.isNotEmpty()) appendLine("Bitrate General: $bitRate")
                    appendLine()
                    
                    // Información de Video
                    videoInfo?.let { video ->
                        appendLine("=== VIDEO ===")
                        if (video.format.isNotEmpty()) appendLine("  Formato: ${video.format}")
                        if (video.bitRate.isNotEmpty()) appendLine("  Bitrate: ${video.bitRate}")
                        if (video.streamSize.isNotEmpty()) appendLine("  Stream Size: ${video.streamSize}")
                        if (video.width.isNotEmpty() && video.height.isNotEmpty()) {
                            appendLine("  Resolución: ${video.width}x${video.height}")
                        }
                        if (video.frameRate.isNotEmpty()) appendLine("  Frame Rate: ${video.frameRate}")
                        if (video.codecId.isNotEmpty()) appendLine("  Codec ID: ${video.codecId}")
                        appendLine()
                    }
                    
                    // Información de Audio
                    audioInfo?.let { audio ->
                        appendLine("=== AUDIO ===")
                        if (audio.format.isNotEmpty()) appendLine("  Formato: ${audio.format}")
                        if (audio.bitRate.isNotEmpty()) appendLine("  Bitrate: ${audio.bitRate}")
                        if (audio.streamSize.isNotEmpty()) appendLine("  Stream Size: ${audio.streamSize}")
                        if (audio.channels.isNotEmpty()) appendLine("  Canales: ${audio.channels}")
                        if (audio.samplingRate.isNotEmpty()) appendLine("  Sample Rate: ${audio.samplingRate}")
                        if (audio.language.isNotEmpty()) appendLine("  Idioma: ${audio.language}")
                        appendLine()
                    }
                    
                    // Información de Subtítulos
                    if (subtitlesInfo.isNotEmpty()) {
                        appendLine("=== SUBTÍTULOS (${subtitlesInfo.size} streams) ===")
                        subtitlesInfo.forEachIndexed { index, subtitle ->
                            appendLine("  --- Subtítulo ${index + 1} ---")
                            if (subtitle.format.isNotEmpty()) appendLine("    Formato: ${subtitle.format}")
                            if (subtitle.bitRate.isNotEmpty()) appendLine("    Bitrate: ${subtitle.bitRate}")
                            if (subtitle.streamSize.isNotEmpty()) appendLine("    Stream Size: ${subtitle.streamSize}")
                            if (subtitle.duration.isNotEmpty()) appendLine("    Duración: ${subtitle.duration}")
                            if (subtitle.countOfElements.isNotEmpty()) appendLine("    Count of Elements: ${subtitle.countOfElements}")
                            if (subtitle.language.isNotEmpty()) appendLine("    Idioma: ${subtitle.language}")
                            if (subtitle.title.isNotEmpty()) appendLine("    Título: ${subtitle.title}")
                            if (subtitle.codecId.isNotEmpty()) appendLine("    Codec ID: ${subtitle.codecId}")
                            appendLine()
                        }
                    }
                    
                    appendLine("=== INFORMACIÓN COMPLETA ===")
                    appendLine(textOutput)
                }
            } else {
                "Error: $error"
            }
        }
        
        private fun formatBytes(bytes: Long): String {
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> String.format("%.2f KB", bytes / 1024.0)
                bytes < 1024 * 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
                else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            }
        }
    }
}
