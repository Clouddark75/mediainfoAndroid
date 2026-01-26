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
        private const val MAX_BUFFER_SIZE = 10 * 1024 * 1024 // Máximo 10 MB
        
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
                
                // Obtener información específica
                val format = mediaInfo.Get(MediaInfo.Stream.General, 0, "Format")
                val duration = mediaInfo.Get(MediaInfo.Stream.General, 0, "Duration/String")
                val fileSize = mediaInfo.Get(MediaInfo.Stream.General, 0, "FileSize/String")
                val bitRate = mediaInfo.Get(MediaInfo.Stream.General, 0, "OverallBitRate/String")
                
                val videoFormat = if (mediaInfo.Count_Get(MediaInfo.Stream.Video) > 0) {
                    mediaInfo.Get(MediaInfo.Stream.Video, 0, "Format")
                } else ""
                
                val audioFormat = if (mediaInfo.Count_Get(MediaInfo.Stream.Audio) > 0) {
                    mediaInfo.Get(MediaInfo.Stream.Audio, 0, "Format")
                } else ""
                
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
                    videoFormat = videoFormat,
                    audioFormat = audioFormat
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
                val opened = mediaInfo.Open(tempFile.absolutePath)
                
                if (!opened) {
                    tempFile.delete()
                    throw Exception("No se pudo abrir el archivo con MediaInfo")
                }
                
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
        val videoFormat: String = "",
        val audioFormat: String = "",
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
                    if (bitRate.isNotEmpty()) appendLine("Bitrate: $bitRate")
                    if (videoFormat.isNotEmpty()) appendLine("Video: $videoFormat")
                    if (audioFormat.isNotEmpty()) appendLine("Audio: $audioFormat")
                    appendLine()
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
