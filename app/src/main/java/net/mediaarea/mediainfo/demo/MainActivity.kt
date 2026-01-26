package net.mediaarea.mediainfo.demo

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mediaarea.mediainfo.demo.databinding.ActivityMainBinding
import net.mediaarea.mediainfo.lib.MediaInfoUtil

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    
    private val pickMediaLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { analyzeLocalFile(it) }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupUI()
        checkPermissions()
    }
    
    private fun setupUI() {
        binding.btnPickFile.setOnClickListener {
            pickMediaLauncher.launch(arrayOf("video/*", "audio/*"))
        }
        
        binding.btnAnalyzeUrlIncremental.setOnClickListener {
            val url = binding.etUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                analyzeFromUrlIncremental(url)
            } else {
                Toast.makeText(this, "Ingrese una URL válida", Toast.LENGTH_SHORT).show()
            }
        }
        
        binding.btnAnalyzeUrlFull.setOnClickListener {
            val url = binding.etUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                analyzeFromUrlFull(url)
            } else {
                Toast.makeText(this, "Ingrese una URL válida", Toast.LENGTH_SHORT).show()
            }
        }
        
        // URLs de ejemplo para probar
        val exampleUrls = listOf(
            "https://sample-videos.com/video321/mp4/720/big_buck_bunny_720p_1mb.mp4",
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
            "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/360/Big_Buck_Bunny_360_10s_1MB.mp4"
        )
        binding.etUrl.setText(exampleUrls[0])
        
        // Mostrar versión de MediaInfo
        binding.tvVersion.text = "MediaInfo ${MediaInfoUtil.getVersion()}"
    }
    
    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO),
                    100
                )
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                    100
                )
            }
        }
    }
    
    private fun analyzeLocalFile(uri: Uri) {
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.progressBar.isIndeterminate = true
        binding.tvOutput.text = "Analizando archivo local..."
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val pfd: ParcelFileDescriptor? = contentResolver.openFileDescriptor(uri, "r")
                val fd = pfd?.detachFd() ?: throw Exception("No se pudo abrir el archivo")
                
                val result = MediaInfoUtil.getMediaInfo(fd, uri.lastPathSegment ?: "unknown", "Text")
                
                pfd?.close()
                
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = android.view.View.GONE
                    binding.tvOutput.text = result
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = android.view.View.GONE
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    /**
     * Análisis incremental (streaming) - MÉTODO RECOMENDADO
     * Solo descarga lo mínimo necesario y se detiene automáticamente
     */
    private fun analyzeFromUrlIncremental(url: String) {
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.progressBar.isIndeterminate = false
        binding.progressBar.progress = 0
        binding.tvOutput.text = "Iniciando análisis incremental..."
        binding.tvStats.text = ""
        
        lifecycleScope.launch {
            try {
                val startTime = System.currentTimeMillis()
                
                val result = MediaInfoStreamHelper.analyzeFromStreamIncremental(url) { bytesRead, totalBytes, status ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        val progress = if (totalBytes != null && totalBytes > 0) {
                            ((bytesRead * 100) / totalBytes).toInt()
                        } else {
                            0
                        }
                        binding.progressBar.progress = progress
                        binding.tvStats.text = status
                    }
                }
                
                val endTime = System.currentTimeMillis()
                val duration = (endTime - startTime) / 1000.0
                
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = android.view.View.GONE
                    
                    if (result.success) {
                        val statsText = buildString {
                            appendLine("✅ Análisis completado en ${"%.2f".format(duration)} segundos")
                            appendLine("📊 Descargado: ${result.percentDownloaded}% del archivo")
                            appendLine("💾 Datos procesados: ${formatBytes(result.bytesDownloaded)}")
                            if (result.totalFileSize > 0) {
                                appendLine("📁 Tamaño total: ${formatBytes(result.totalFileSize)}")
                                val savedBytes = result.totalFileSize - result.bytesDownloaded
                                val savedPercent = ((savedBytes.toDouble() / result.totalFileSize) * 100).toInt()
                                appendLine("⚡ Ahorro: ${formatBytes(savedBytes)} ($savedPercent%)")
                            }
                        }
                        
                        binding.tvStats.text = statsText
                        binding.tvOutput.text = result.getSummary()
                        
                        Toast.makeText(
                            this@MainActivity,
                            "¡Análisis completado! Solo se descargó el ${result.percentDownloaded}% del archivo",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        binding.tvStats.text = "❌ Error en el análisis"
                        binding.tvOutput.text = "Error: ${result.error}"
                        Toast.makeText(this@MainActivity, "Error: ${result.error}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = android.view.View.GONE
                    binding.tvStats.text = "❌ Error"
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    /**
     * Análisis con descarga completa - Para comparación
     */
    private fun analyzeFromUrlFull(url: String) {
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.progressBar.isIndeterminate = false
        binding.progressBar.progress = 0
        binding.tvOutput.text = "Descargando archivo completo..."
        binding.tvStats.text = ""
        
        lifecycleScope.launch {
            try {
                val startTime = System.currentTimeMillis()
                
                val result = MediaInfoStreamHelper.analyzeFromUrl(url, cacheDir) { progress ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        binding.progressBar.progress = progress
                        binding.tvStats.text = "Descargando: $progress%"
                    }
                }
                
                val endTime = System.currentTimeMillis()
                val duration = (endTime - startTime) / 1000.0
                
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = android.view.View.GONE
                    binding.tvStats.text = "✅ Descarga completa en ${"%.2f".format(duration)} segundos"
                    binding.tvOutput.text = formatOutput(result)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = android.view.View.GONE
                    binding.tvStats.text = "❌ Error"
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
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
    
    private fun formatOutput(output: String): String {
        return if (output.length > 5000) {
            output.substring(0, 5000) + "\n\n... (salida truncada para visualización)"
        } else {
            output
        }
    }
}
