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
        private const val CHUNK_SIZE = 256 * 1024 // 256 KB por chunk (aumentado para mejor rendimiento)
        private const val INITIAL_READ_SIZE = 20 * 1024 * 1024 // 20 MB inicial
        
        /**
         * Analiza un stream HTTP/HTTPS con soporte de seeking para obtener metadatos completos.
         * Este método implementa el algoritmo correcto para obtener bit rate, stream size, etc.
         */
        suspend fun analyzeFromStreamWithSeeking(
            url: String,
            progressCallback: ((bytesRead: Long, totalBytes: Long?, status: String) -> Unit)? = null
        ): StreamAnalysisResult = withContext(Dispatchers.IO) {
            
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            
            try {
                // Primero, obtener el tamaño total del archivo
                val headRequest = Request.Builder()
                    .url(url)
                    .head()
                    .build()
                
                val headResponse = client.newCall(headRequest).execute()
                val contentLength = headResponse.body?.contentLength() ?: -1L
                headResponse.close()
                
                if (contentLength <= 0) {
                    throw Exception("No se pudo determinar el tamaño del archivo")
                }
                
                Log.d(TAG, "Tamaño del archivo: $contentLength bytes")
                progressCallback?.invoke(0, contentLength, "Iniciando análisis con seeking...")
                
                // Crear instancia de MediaInfo
                val mediaInfo = MediaInfo()
                
                // Inicializar con el tamaño total conocido
                mediaInfo.Open_Buffer_Init(contentLength, 0)
                
                var totalBytesRead = 0L
                val readChunks = mutableListOf<Pair<Long, ByteArray>>() // Guardar chunks leídos
                
                // FASE 1: Leer el inicio del archivo (primeros 20 MB o menos)
                Log.d(TAG, "FASE 1: Leyendo inicio del archivo...")
                val initialSize = minOf(INITIAL_READ_SIZE.toLong(), contentLength)
                val initialData = readRange(client, url, 0, initialSize - 1)
                
                if (initialData != null) {
                    totalBytesRead = initialData.size.toLong()
                    readChunks.add(Pair(0L, initialData))
                    
                    // Enviar datos iniciales
                    val state = mediaInfo.Open_Buffer_Continue(initialData, initialData.size.toLong())
                    
                    progressCallback?.invoke(
                        totalBytesRead,
                        contentLength,
                        "Fase 1: Analizando inicio (${formatBytes(totalBytesRead)})"
                    )
                    
                    Log.d(TAG, "Estado después de leer inicio: $state (0x${state.toString(16)})")
                }
                
                // FASE 2: Verificar si MediaInfo necesita más datos (seeking)
                var seekPosition = mediaInfo.Open_Buffer_Continue_GoTo_Get()
                var seekAttempts = 0
                val maxSeekAttempts = 10 // Limitar intentos de seeking
                
                while (seekPosition >= 0 && seekAttempts < maxSeekAttempts) {
                    seekAttempts++
                    Log.d(TAG, "FASE 2: MediaInfo solicita seeking a posición: $seekPosition")
                    
                    progressCallback?.invoke(
                        totalBytesRead,
                        contentLength,
                        "Fase 2: Buscando metadatos adicionales (intento $seekAttempts)"
                    )
                    
                    // Determinar cuánto leer desde esta posición
                    val remainingBytes = contentLength - seekPosition
                    val bytesToRead = minOf(CHUNK_SIZE.toLong(), remainingBytes)
                    
                    if (bytesToRead <= 0) break
                    
                    // Leer desde la posición solicitada
                    val seekData = readRange(client, url, seekPosition, seekPosition + bytesToRead - 1)
                    
                    if (seekData != null) {
                        totalBytesRead += seekData.size
                        readChunks.add(Pair(seekPosition, seekData))
                        
                        // Informar a MediaInfo de la nueva posición y datos
                        mediaInfo.Open_Buffer_Init(contentLength, seekPosition)
                        val state = mediaInfo.Open_Buffer_Continue(seekData, seekData.size.toLong())
                        
                        Log.d(TAG, "Estado después de seeking: $state (0x${state.toString(16)})")
                        
                        progressCallback?.invoke(
                            totalBytesRead,
                            contentLength,
                            "Leyendo posición ${formatBytes(seekPosition)} (${formatBytes(totalBytesRead)} total)"
                        )
                        
                        // Verificar si necesita más seeking
                        seekPosition = mediaInfo.Open_Buffer_Continue_GoTo_Get()
                    } else {
                        Log.w(TAG, "No se pudo leer datos en posición: $seekPosition")
                        break
                    }
                }
                
                // FASE 3: Leer el final del archivo si es necesario
                // Algunos metadatos (como índices) están al final del archivo
                val endSize = minOf(5 * 1024 * 1024L, contentLength / 10) // Últimos 5 MB o 10% del archivo
                val endStart = maxOf(0, contentLength - endSize)
                
                if (endStart > 0 && !readChunks.any { it.first >= endStart }) {
                    Log.d(TAG, "FASE 3: Leyendo final del archivo...")
                    val endData = readRange(client, url, endStart, contentLength - 1)
                    
                    if (endData != null) {
                        totalBytesRead += endData.size
                        readChunks.add(Pair(endStart, endData))
                        
                        mediaInfo.Open_Buffer_Init(contentLength, endStart)
                        mediaInfo.Open_Buffer_Continue(endData, endData.size.toLong())
                        
                        progressCallback?.invoke(
                            totalBytesRead,
                            contentLength,
                            "Fase 3: Analizando final (${formatBytes(totalBytesRead)} total)"
                        )
                    }
                }
                
                // Finalizar el análisis
                mediaInfo.Open_Buffer_Finalize()
                
                Log.d(TAG, "Análisis completado. Total descargado: ${formatBytes(totalBytesRead)} de ${formatBytes(contentLength)}")
                progressCallback?.invoke(totalBytesRead, contentLength, "Finalizando análisis...")
                
                // Extraer toda la información
                val result = extractCompleteInfo(mediaInfo, totalBytesRead, contentLength)
                
                mediaInfo.Close()
                
                result
                
            } catch (e: Exception) {
                Log.e(TAG, "Error en análisis con seeking", e)
                StreamAnalysisResult(
                    success = false,
                    error = e.message ?: "Error desconocido"
                )
            }
        }
        
        /**
         * Lee un rango específico de bytes desde una URL usando HTTP Range requests
         */
        private fun readRange(client: OkHttpClient, url: String, start: Long, end: Long): ByteArray? {
            return try {
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Range", "bytes=$start-$end")
                    .build()
                
                val response = client.newCall(request).execute()
                
                if (response.isSuccessful || response.code == 206) { // 206 = Partial Content
                    response.body?.bytes()
                } else {
                    Log.w(TAG, "Error al leer rango $start-$end: ${response.code}")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Excepción al leer rango $start-$end", e)
                null
            }
        }
        
        /**
         * Extrae toda la información de MediaInfo
         */
        private fun extractCompleteInfo(
            mediaInfo: MediaInfo,
            bytesDownloaded: Long,
            totalFileSize: Long
        ): StreamAnalysisResult {
            
            // Obtener información en diferentes formatos
            mediaInfo.Option("Inform", "MIXML")
            val xmlResult = mediaInfo.Inform()
            
            mediaInfo.Option("Inform", "Text")
            val textResult = mediaInfo.Inform()
            
            // Información general
            val format = mediaInfo.Get(MediaInfo.Stream.General, 0, "Format")
            val duration = mediaInfo.Get(MediaInfo.Stream.General, 0, "Duration/String")
            val fileSize = mediaInfo.Get(MediaInfo.Stream.General, 0, "FileSize/String")
            val bitRate = mediaInfo.Get(MediaInfo.Stream.General, 0, "OverallBitRate/String")
            
            // === INFORMACIÓN DE VIDEO ===
            val videoInfo = if (mediaInfo.Count_Get(MediaInfo.Stream.Video) > 0) {
                VideoStreamInfo(
                    format = mediaInfo.Get(MediaInfo.Stream.Video, 0, "Format"),
                    bitRate = mediaInfo.Get(MediaInfo.Stream.Video, 0, "BitRate/String"),
                    bitRateNominal = mediaInfo.Get(MediaInfo.Stream.Video, 0, "BitRate_Nominal/String"),
                    streamSize = mediaInfo.Get(MediaInfo.Stream.Video, 0, "StreamSize/String"),
                    width = mediaInfo.Get(MediaInfo.Stream.Video, 0, "Width"),
                    height = mediaInfo.Get(MediaInfo.Stream.Video, 0, "Height"),
                    frameRate = mediaInfo.Get(MediaInfo.Stream.Video, 0, "FrameRate"),
                    codecId = mediaInfo.Get(MediaInfo.Stream.Video, 0, "CodecID"),
                    duration = mediaInfo.Get(MediaInfo.Stream.Video, 0, "Duration/String")
                )
            } else null
            
            // === INFORMACIÓN DE AUDIO ===
            val audioInfo = if (mediaInfo.Count_Get(MediaInfo.Stream.Audio) > 0) {
                AudioStreamInfo(
                    format = mediaInfo.Get(MediaInfo.Stream.Audio, 0, "Format"),
                    bitRate = mediaInfo.Get(MediaInfo.Stream.Audio, 0, "BitRate/String"),
                    bitRateNominal = mediaInfo.Get(MediaInfo.Stream.Audio, 0, "BitRate_Nominal/String"),
                    streamSize = mediaInfo.Get(MediaInfo.Stream.Audio, 0, "StreamSize/String"),
                    channels = mediaInfo.Get(MediaInfo.Stream.Audio, 0, "Channels"),
                    samplingRate = mediaInfo.Get(MediaInfo.Stream.Audio, 0, "SamplingRate/String"),
                    language = mediaInfo.Get(MediaInfo.Stream.Audio, 0, "Language"),
                    duration = mediaInfo.Get(MediaInfo.Stream.Audio, 0, "Duration/String")
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
                        countOfElements2 = mediaInfo.Get(MediaInfo.Stream.Text, i, "ElementCount"),
                        language = mediaInfo.Get(MediaInfo.Stream.Text, i, "Language"),
                        title = mediaInfo.Get(MediaInfo.Stream.Text, i, "Title"),
                        codecId = mediaInfo.Get(MediaInfo.Stream.Text, i, "CodecID"),
                        frameRate = mediaInfo.Get(MediaInfo.Stream.Text, i, "FrameRate")
                    )
                )
            }
            
            val percentDownloaded = if (totalFileSize > 0) {
                ((bytesDownloaded.toDouble() / totalFileSize) * 100).toInt()
            } else {
                0
            }
            
            return StreamAnalysisResult(
                success = true,
                xmlOutput = xmlResult,
                textOutput = textResult,
                bytesDownloaded = bytesDownloaded,
                totalFileSize = totalFileSize,
                percentDownloaded = percentDownloaded,
                format = format,
                duration = duration,
                fileSize = fileSize,
                bitRate = bitRate,
                videoInfo = videoInfo,
                audioInfo = audioInfo,
                subtitlesInfo = subtitlesInfo
            )
        }
        
        /**
         * Método heredado - análisis secuencial simple (sin seeking)
         * Mantener para compatibilidad
         */
        suspend fun analyzeFromStreamIncremental(
            url: String,
            progressCallback: ((bytesRead: Long, totalBytes: Long?, status: String) -> Unit)? = null
        ): StreamAnalysisResult {
            // Redirigir al nuevo método con seeking
            return analyzeFromStreamWithSeeking(url, progressCallback)
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
        val bitRateNominal: String = "",
        val streamSize: String = "",
        val width: String = "",
        val height: String = "",
        val frameRate: String = "",
        val codecId: String = "",
        val duration: String = ""
    )
    
    /**
     * Información del stream de audio
     */
    data class AudioStreamInfo(
        val format: String = "",
        val bitRate: String = "",
        val bitRateNominal: String = "",
        val streamSize: String = "",
        val channels: String = "",
        val samplingRate: String = "",
        val language: String = "",
        val duration: String = ""
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
        val countOfElements2: String = "",
        val language: String = "",
        val title: String = "",
        val codecId: String = "",
        val frameRate: String = ""
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
                        if (video.bitRateNominal.isNotEmpty()) appendLine("  Bitrate Nominal: ${video.bitRateNominal}")
                        if (video.streamSize.isNotEmpty()) appendLine("  Stream Size: ${video.streamSize}")
                        if (video.width.isNotEmpty() && video.height.isNotEmpty()) {
                            appendLine("  Resolución: ${video.width}x${video.height}")
                        }
                        if (video.frameRate.isNotEmpty()) appendLine("  Frame Rate: ${video.frameRate}")
                        if (video.duration.isNotEmpty()) appendLine("  Duración: ${video.duration}")
                        if (video.codecId.isNotEmpty()) appendLine("  Codec ID: ${video.codecId}")
                        appendLine()
                    }
                    
                    // Información de Audio
                    audioInfo?.let { audio ->
                        appendLine("=== AUDIO ===")
                        if (audio.format.isNotEmpty()) appendLine("  Formato: ${audio.format}")
                        if (audio.bitRate.isNotEmpty()) appendLine("  Bitrate: ${audio.bitRate}")
                        if (audio.bitRateNominal.isNotEmpty()) appendLine("  Bitrate Nominal: ${audio.bitRateNominal}")
                        if (audio.streamSize.isNotEmpty()) appendLine("  Stream Size: ${audio.streamSize}")
                        if (audio.channels.isNotEmpty()) appendLine("  Canales: ${audio.channels}")
                        if (audio.samplingRate.isNotEmpty()) appendLine("  Sample Rate: ${audio.samplingRate}")
                        if (audio.duration.isNotEmpty()) appendLine("  Duración: ${audio.duration}")
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
                            if (subtitle.countOfElements2.isNotEmpty()) appendLine("    Element Count: ${subtitle.countOfElements2}")
                            if (subtitle.frameRate.isNotEmpty()) appendLine("    Frame Rate: ${subtitle.frameRate}")
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
