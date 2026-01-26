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
import net.mediaarea.mediainfo.lib.MediaInfo
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
        
        binding.btnAnalyzeUrl.setOnClickListener {
            val url = binding.etUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                analyzeFromUrl(url)
            } else {
                Toast.makeText(this, "Ingrese una URL válida", Toast.LENGTH_SHORT).show()
            }
        }
        
        binding.btnAnalyzeUrlPartial.setOnClickListener {
            val url = binding.etUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                analyzeFromUrlPartial(url)
            } else {
                Toast.makeText(this, "Ingrese una URL válida", Toast.LENGTH_SHORT).show()
            }
        }
        
        // URL de ejemplo
        binding.etUrl.setText("https://sample-videos.com/video321/mp4/720/big_buck_bunny_720p_1mb.mp4")
        
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
        binding.tvOutput.text = "Analizando archivo local..."
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val pfd: ParcelFileDescriptor? = contentResolver.openFileDescriptor(uri, "r")
                val fd = pfd?.detachFd() ?: throw Exception("No se pudo abrir el archivo")
                
                val result = MediaInfoUtil.getMediaInfo(fd, uri.lastPathSegment ?: "unknown", "MIXML")
                
                pfd?.close()
                
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = android.view.View.GONE
                    binding.tvOutput.text = formatXmlOutput(result)
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
    
    private fun analyzeFromUrl(url: String) {
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.progressBar.isIndeterminate = false
        binding.progressBar.progress = 0
        binding.tvOutput.text = "Descargando desde URL..."
        
        lifecycleScope.launch {
            try {
                val result = MediaInfoStreamHelper.analyzeFromUrl(url, cacheDir) { progress ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        binding.progressBar.progress = progress
                        binding.tvOutput.text = "Descargando: $progress%"
                    }
                }
                
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = android.view.View.GONE
                    binding.tvOutput.text = formatXmlOutput(result)
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
    
    private fun analyzeFromUrlPartial(url: String) {
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.progressBar.isIndeterminate = false
        binding.progressBar.progress = 0
        binding.tvOutput.text = "Descargando parcialmente desde URL..."
        
        lifecycleScope.launch {
            try {
                val result = MediaInfoStreamHelper.analyzeFromUrlPartial(url, cacheDir, 5 * 1024 * 1024) { progress ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        binding.progressBar.progress = progress
                        binding.tvOutput.text = "Descargando: $progress%"
                    }
                }
                
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = android.view.View.GONE
                    binding.tvOutput.text = formatXmlOutput(result)
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
    
    private fun formatXmlOutput(xml: String): String {
        return if (xml.length > 5000) {
            xml.substring(0, 5000) + "\n\n... (salida truncada)"
        } else {
            xml
        }
    }
}
