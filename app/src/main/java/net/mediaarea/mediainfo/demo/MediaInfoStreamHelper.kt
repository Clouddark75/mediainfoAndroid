package net.mediaarea.mediainfo.demo

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.mediaarea.mediainfo.lib.MediaInfo
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class MediaInfoStreamHelper {
    
    companion object {
        private const val TAG = "MediaInfoStreamHelper"
        private const val BUFFER_SIZE = 8192
        
        /**
         * Descarga un archivo desde HTTP/HTTPS y analiza con MediaInfo
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
                
                // Crear archivo temporal
                val tempFile = File.createTempFile("mediainfo_", ".tmp", cacheDir)
                tempFile.deleteOnExit()
                
                val outputStream = FileOutputStream(tempFile)
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                var totalBytesRead = 0L
                
                // Descargar archivo
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
                
                Log.d(TAG, "Archivo descargado: ${tempFile.absolutePath} (${tempFile.length()} bytes)")
                
                // Analizar con MediaInfo
                val mediaInfo = MediaInfo()
                val opened = mediaInfo.Open(tempFile.absolutePath)
                
                if (!opened) {
                    tempFile.delete()
                    throw Exception("No se pudo abrir el archivo con MediaInfo")
                }
                
                // Obtener información en formato XML
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
         * Descarga parcial para análisis rápido (primeros MB del archivo)
         */
        suspend fun analyzeFromUrlPartial(
            url: String,
            cacheDir: File,
            maxBytes: Long = 5 * 1024 * 1024, // 5 MB por defecto
            progressCallback: ((Int) -> Unit)? = null
        ): String = withContext(Dispatchers.IO) {
            
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            
            val request = Request.Builder()
                .url(url)
                .addHeader("Range", "bytes=0-${maxBytes - 1}")
                .build()
            
            try {
                val response = client.newCall(request).execute()
                
                if (!response.isSuccessful && response.code != 206) {
                    throw Exception("Error HTTP: ${response.code}")
                }
                
                val contentLength = response.body?.contentLength() ?: -1
                val inputStream = response.body?.byteStream() 
                    ?: throw Exception("No se pudo obtener el stream")
                
                // Crear archivo temporal
                val tempFile = File.createTempFile("mediainfo_partial_", ".tmp", cacheDir)
                tempFile.deleteOnExit()
                
                val outputStream = FileOutputStream(tempFile)
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                var totalBytesRead = 0L
                
                // Descargar archivo parcial
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
                
                Log.d(TAG, "Archivo parcial descargado: ${tempFile.absolutePath} (${tempFile.length()} bytes)")
                
                // Analizar con MediaInfo
                val mediaInfo = MediaInfo()
                val opened = mediaInfo.Open(tempFile.absolutePath)
                
                if (!opened) {
                    tempFile.delete()
                    throw Exception("No se pudo abrir el archivo con MediaInfo")
                }
                
                // Obtener información en formato XML
                mediaInfo.Option("Inform", "MIXML")
                val result = mediaInfo.Inform()
                
                mediaInfo.Close()
                tempFile.delete()
                
                result
                
            } catch (e: Exception) {
                Log.e(TAG, "Error al analizar desde URL (parcial)", e)
                throw e
            }
        }
    }
}
