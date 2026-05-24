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
        private const val SEEK_CHUNK_SIZE = 16 * 1024 * 1024  // 16 MB — recomendado por el autor de MediaInfoLib
        private const val MAX_SEQUENTIAL_SIZE = 30 * 1024 * 1024
        
        /**
         * Método principal de análisis - auto-detecta el mejor método
         */
        suspend fun analyzeFromStreamIncremental(
            url: String,
            progressCallback: ((bytesRead: Long, totalBytes: Long?, status: String) -> Unit)? = null
        ): StreamAnalysisResult = withContext(Dispatchers.IO) {

            // OkHttpClient con manejo manual de redirects para preservar el header Range.
            // El followRedirects automático de OkHttp elimina headers como Range al redirigir,
            // lo que rompe servidores WebDAV/locales (MiXplorer, etc.) que usan 127.0.0.1:PORT.
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .followRedirects(false)        // Manejar redirects manualmente
                .followSslRedirects(false)
                .addInterceptor { chain ->
                    var request = chain.request()
                    var response = chain.proceed(request)
                    var redirectCount = 0
                    while (redirectCount < 5 && (response.code == 301 || response.code == 302 ||
                            response.code == 303 || response.code == 307 || response.code == 308)) {
                        redirectCount++
                        val location = response.header("Location") ?: break
                        response.close()
                        val newUrl = request.url.resolve(location) ?: break
                        // Preservar todos los headers incluyendo Range
                        request = request.newBuilder().url(newUrl).build()
                        response = chain.proceed(request)
                    }
                    response
                }
                .build()
            
            try {
                // Intentar obtener información del servidor
                val serverInfo = detectServerCapabilities(client, url)
                
                Log.d(TAG, """
                    Capacidades del servidor:
                    - Content-Length: ${serverInfo.contentLength}
                    - Soporta HEAD: ${serverInfo.supportsHead}
                    - Soporta Range: ${serverInfo.supportsRange}
                    - Método recomendado: ${serverInfo.recommendedMethod}
                """.trimIndent())
                
                // Usar el mejor método disponible
                when (serverInfo.recommendedMethod) {
                    AnalysisMethod.SEEKING -> {
                        analyzeWithSeeking(client, url, serverInfo.contentLength, progressCallback)
                    }
                    AnalysisMethod.SEQUENTIAL -> {
                        analyzeSequential(client, url, serverInfo.contentLength, progressCallback)
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error en análisis", e)
                StreamAnalysisResult(
                    success = false,
                    error = "Error: ${e.message}"
                )
            }
        }
        
        private fun detectServerCapabilities(client: OkHttpClient, url: String): ServerInfo {
            var contentLength = -1L
            var supportsHead = false
            var supportsRange = false

            // 1. HEAD request para obtener Content-Length y Accept-Ranges
            try {
                val headResponse = client.newCall(
                    Request.Builder().url(url).head().build()
                ).execute()

                if (headResponse.isSuccessful) {
                    supportsHead = true
                    contentLength = headResponse.body?.contentLength() ?: -1L
                    // Algunos servidores declaran soporte de Range en el HEAD
                    val acceptRanges = headResponse.header("Accept-Ranges")
                    if (acceptRanges != null && acceptRanges != "none") {
                        supportsRange = true
                    }
                    Log.d(TAG, "HEAD: Content-Length=$contentLength Accept-Ranges=$acceptRanges")
                }
                headResponse.close()
            } catch (e: Exception) {
                Log.w(TAG, "HEAD falló: ${e.message}")
            }

            // 2. Si HEAD falló o no dio Content-Length, intentar GET
            if (contentLength <= 0) {
                try {
                    val getResponse = client.newCall(
                        Request.Builder().url(url).build()
                    ).execute()

                    if (getResponse.isSuccessful) {
                        contentLength = getResponse.body?.contentLength() ?: -1L
                        Log.d(TAG, "GET: Content-Length=$contentLength")
                    }
                    getResponse.body?.byteStream()?.read(ByteArray(512))
                    getResponse.close()
                } catch (e: Exception) {
                    Log.w(TAG, "GET falló: ${e.message}")
                }
            }

            // 3. Verificar Range solo si tenemos Content-Length y aún no confirmamos soporte
            if (contentLength > 0 && !supportsRange) {
                try {
                    val rangeResponse = client.newCall(
                        Request.Builder()
                            .url(url)
                            .addHeader("Range", "bytes=0-1023")
                            .build()
                    ).execute()

                    when (rangeResponse.code) {
                        206 -> {
                            // Respuesta ideal: Partial Content
                            supportsRange = true
                            Log.d(TAG, "Range: 206 Partial Content ✓")
                        }
                        200 -> {
                            // Algunos servidores locales/WebDAV responden 200 pero sí sirven
                            // el rango correcto. Verificar por Content-Range o tamaño de respuesta.
                            val contentRange = rangeResponse.header("Content-Range")
                            val bodyLen = rangeResponse.body?.contentLength() ?: -1L
                            if (contentRange != null || bodyLen in 1..2048) {
                                supportsRange = true
                                Log.d(TAG, "Range: 200 con Content-Range=$contentRange bodyLen=$bodyLen → asumiendo soporte ✓")
                            } else {
                                Log.d(TAG, "Range: 200 sin indicadores → sin soporte de Range")
                            }
                        }
                        else -> Log.d(TAG, "Range: código ${rangeResponse.code} → sin soporte")
                    }
                    rangeResponse.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Range test falló: ${e.message}")
                }
            }

            val recommendedMethod = when {
                contentLength > 0 && supportsRange -> AnalysisMethod.SEEKING
                else -> AnalysisMethod.SEQUENTIAL
            }

            Log.d(TAG, "Método: $recommendedMethod (contentLength=$contentLength supportsRange=$supportsRange)")
            return ServerInfo(contentLength, supportsHead, supportsRange, recommendedMethod)
        }
        
        /**
         * Análisis con seeking (método óptimo)
         */
        private suspend fun analyzeWithSeeking(
            client: OkHttpClient,
            url: String,
            contentLength: Long,
            progressCallback: ((bytesRead: Long, totalBytes: Long?, status: String) -> Unit)?
        ): StreamAnalysisResult = withContext(Dispatchers.IO) {

            Log.d(TAG, "Usando método SEEKING - Tamaño: ${formatBytes(contentLength)}")
            progressCallback?.invoke(0, contentLength, "Iniciando análisis...")

            val mediaInfo = MediaInfo()
            mediaInfo.Open_Buffer_Init(contentLength, 0)

            var totalBytesRead = 0L
            var currentPosition = 0L
            var continueLoop = true

            // Loop fiel al patrón correcto de MediaInfoLib:
            // 1. Leer chunk desde currentPosition
            // 2. Enviar a Open_Buffer_Continue → chequear bit3 (finalizado)
            // 3. Llamar Open_Buffer_Continue_GoTo_Get → si !=-1, hacer seek y reiniciar
            // 4. Si GoTo==-1 y bit3==0, avanzar secuencialmente
            // El autor de MediaInfoLib recomienda chunks de ~16MB

            while (continueLoop) {
                val remaining = contentLength - currentPosition
                if (remaining <= 0) break

                val toRead = minOf(SEEK_CHUNK_SIZE.toLong(), remaining)
                Log.d(TAG, "Leyendo ${formatBytes(toRead)} desde ${formatBytes(currentPosition)}")

                val chunkData = readRangeSafe(client, url, currentPosition, currentPosition + toRead - 1)
                if (chunkData == null || chunkData.isEmpty()) {
                    Log.w(TAG, "No se pudo leer en ${formatBytes(currentPosition)}, abortando")
                    break
                }

                totalBytesRead += chunkData.size
                val state = mediaInfo.Open_Buffer_Continue(chunkData, chunkData.size.toLong())

                progressCallback?.invoke(
                    totalBytesRead, contentLength,
                    "Analizando: ${formatBytes(totalBytesRead)} / ${formatBytes(contentLength)}"
                )

                Log.d(TAG, "state=0x${state.toString(16)} pos=${formatBytes(currentPosition)}")

                // Bit3: MediaInfo tiene suficiente información
                if ((state.toInt() and 0x08) != 0) {
                    Log.d(TAG, "MediaInfo finalizó")
                    break
                }

                // Chequear si MediaInfo pide un seek
                val seekTo = mediaInfo.Open_Buffer_Continue_GoTo_Get()
                if (seekTo != -1L) {
                    Log.d(TAG, "Seek solicitado → ${formatBytes(seekTo)}")
                    currentPosition = seekTo
                    mediaInfo.Open_Buffer_Init(contentLength, currentPosition)
                } else {
                    // Sin seek: avanzar secuencialmente
                    currentPosition += chunkData.size
                    // Si llegamos al final sin que MediaInfo finalizara, salir
                    if (currentPosition >= contentLength) {
                        Log.d(TAG, "Fin del archivo alcanzado")
                        break
                    }
                }
            }

            mediaInfo.Open_Buffer_Finalize()
            Log.d(TAG, "Total descargado: ${formatBytes(totalBytesRead)} / ${formatBytes(contentLength)}")

            val result = extractCompleteInfo(mediaInfo, totalBytesRead, contentLength, url)
            mediaInfo.Close()
            result
        }
        
        /**
         * Análisis secuencial (método de fallback)
         */
        private suspend fun analyzeSequential(
            client: OkHttpClient,
            url: String,
            contentLength: Long,
            progressCallback: ((bytesRead: Long, totalBytes: Long?, status: String) -> Unit)?
        ): StreamAnalysisResult = withContext(Dispatchers.IO) {
            
            Log.d(TAG, "Usando método SECUENCIAL")
            progressCallback?.invoke(0, contentLength, "Iniciando análisis secuencial...")
            
            val request = Request.Builder()
                .url(url)
                .build()
            
            var inputStream: InputStream? = null
            
            try {
                val response = client.newCall(request).execute()
                
                if (!response.isSuccessful) {
                    throw Exception("Error HTTP: ${response.code}")
                }
                
                val actualContentLength = response.body?.contentLength() ?: contentLength
                inputStream = response.body?.byteStream() 
                    ?: throw Exception("No se pudo obtener el stream")
                
                val mediaInfo = MediaInfo()
                mediaInfo.Open_Buffer_Init(actualContentLength, 0)
                
                val buffer = ByteArray(SEEK_CHUNK_SIZE)
                var totalBytesRead = 0L
                
                while (totalBytesRead < MAX_SEQUENTIAL_SIZE) {
                    val bytesRead = inputStream.read(buffer)
                    
                    if (bytesRead == -1) {
                        Log.d(TAG, "Fin del stream alcanzado")
                        break
                    }
                    
                    totalBytesRead += bytesRead
                    
                    val state = mediaInfo.Open_Buffer_Continue(buffer, bytesRead.toLong())
                    
                    progressCallback?.invoke(
                        totalBytesRead,
                        actualContentLength,
                        "Procesando: ${formatBytes(totalBytesRead)}"
                    )
                    
                    // Verificar si tiene suficiente información
                    if ((state.toInt() and 0x08) != 0) {
                        Log.d(TAG, "Análisis completo con ${formatBytes(totalBytesRead)}")
                        break
                    }
                }
                
                mediaInfo.Open_Buffer_Finalize()
                
                val result = extractCompleteInfo(mediaInfo, totalBytesRead, actualContentLength, url)
                mediaInfo.Close()
                
                result
                
            } finally {
                inputStream?.close()
            }
        }
        
        /**
         * Lee un rango de bytes de forma segura
         */
        private fun readRangeSafe(client: OkHttpClient, url: String, start: Long, end: Long): ByteArray? {
            return try {
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Range", "bytes=$start-$end")
                    .build()
                
                val response = client.newCall(request).execute()
                
                val data = when {
                    response.code == 206 || response.isSuccessful -> {
                        response.body?.bytes()
                    }
                    else -> {
                        Log.w(TAG, "Error leyendo rango $start-$end: ${response.code}")
                        null
                    }
                }
                
                response.close()
                data
                
            } catch (e: Exception) {
                Log.e(TAG, "Excepción leyendo rango $start-$end: ${e.message}")
                null
            }
        }
        
        /**
         * Extrae toda la información de MediaInfo
         */
        private fun extractCompleteInfo(
            mediaInfo: MediaInfo,
            bytesDownloaded: Long,
            totalFileSize: Long,
            sourceUrl: String = ""
        ): StreamAnalysisResult {

            mediaInfo.Option("Inform", "MIXML")
            val xmlResult = mediaInfo.Inform()
            
            mediaInfo.Option("Inform", "Text")
            val textResult = mediaInfo.Inform()
            
            // Información general
            val format = mediaInfo.Get(MediaInfo.Stream.General, 0, "Format")
            val duration = mediaInfo.Get(MediaInfo.Stream.General, 0, "Duration/String")
            val fileSize = mediaInfo.Get(MediaInfo.Stream.General, 0, "FileSize/String")
            val bitRate = mediaInfo.Get(MediaInfo.Stream.General, 0, "OverallBitRate/String")
            
            // VIDEO
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
            
            // AUDIO
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
            
            // SUBTÍTULOS
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
         * Método legacy para compatibilidad
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
        
        private fun formatBytes(bytes: Long): String {
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> String.format("%.2f KB", bytes / 1024.0)
                bytes < 1024 * 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
                else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            }
        }
    }
    
    enum class AnalysisMethod {
        SEEKING,
        SEQUENTIAL
    }
    
    data class ServerInfo(
        val contentLength: Long,
        val supportsHead: Boolean,
        val supportsRange: Boolean,
        val recommendedMethod: AnalysisMethod
    )
    
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
