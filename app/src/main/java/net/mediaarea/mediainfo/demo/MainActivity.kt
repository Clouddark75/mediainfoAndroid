package net.mediaarea.mediainfo.demo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
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
        
        // Manejar intent de apertura
        handleIntent(intent)
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }
    
    /**
     * Maneja intents cuando la app se abre con un archivo o URL
     */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                intent.data?.let { uri -> handleUri(uri) }
            }
            Intent.ACTION_SEND -> {
                if (intent.type?.startsWith("text/") == true) {
                    val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                    sharedText?.let { url ->
                        if (url.startsWith("http://") || url.startsWith("https://")) {
                            binding.etUrl.setText(url)
                            Toast.makeText(this, R.string.url_loaded, Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uri ->
                        handleUri(uri)
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris ->
                    if (uris.isNotEmpty()) {
                        handleUri(uris[0])
                        if (uris.size > 1) {
                            Toast.makeText(this, R.string.multiple_files_detected, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Determina si el URI es local o remoto y lo procesa
     */
    private fun handleUri(uri: Uri) {
        when (uri.scheme) {
            "http", "https", "rtsp", "rtmp", "mms", "ftp", "ftps", "sftp" -> {
                binding.etUrl.setText(uri.toString())
                Toast.makeText(this, getString(R.string.url_loaded_simple, uri.toString()), Toast.LENGTH_SHORT).show()
                analyzeFromUrlIncremental(uri.toString())
            }
            "file", "content" -> {
                Toast.makeText(this, R.string.analyzing_local, Toast.LENGTH_SHORT).show()
                analyzeLocalFile(uri)
            }
            else -> {
                Toast.makeText(this, getString(R.string.scheme_not_supported, uri.scheme), Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun setupUI() {
        binding.btnPickFile.setOnClickListener {
            pickMediaLauncher.launch(arrayOf("video/*", "audio/*", "image/*", "application/*"))
        }
        
        binding.btnAnalyzeUrlIncremental.setOnClickListener {
            val url = binding.etUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                analyzeFromUrlIncremental(url)
            } else {
                Toast.makeText(this, R.string.enter_valid_url, Toast.LENGTH_SHORT).show()
            }
        }
        
        binding.btnAnalyzeUrlFull.setOnClickListener {
            val url = binding.etUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                analyzeFromUrlFull(url)
            } else {
                Toast.makeText(this, R.string.enter_valid_url, Toast.LENGTH_SHORT).show()
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
        binding.tvVersion.text = getString(R.string.version_label, MediaInfoUtil.getVersion())
    }
    
    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.READ_MEDIA_VIDEO,
                        Manifest.permission.READ_MEDIA_AUDIO,
                        Manifest.permission.READ_MEDIA_IMAGES
                    ),
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
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = true
        binding.cardStats.visibility = View.VISIBLE
        binding.tvOutput.text = getString(R.string.analyzing_local_file)
        binding.tvStats.text = getString(R.string.file_label, uri.lastPathSegment ?: "desconocido")
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val pfd: ParcelFileDescriptor? = contentResolver.openFileDescriptor(uri, "r")
                val fd = pfd?.detachFd() ?: throw Exception(getString(R.string.error_opening_file))
                
                val result = MediaInfoUtil.getMediaInfo(fd, uri.lastPathSegment ?: "unknown", "Text")
                
                pfd?.close()
                
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.tvStats.text = getString(R.string.analysis_complete)
                    binding.tvOutput.text = result
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.tvStats.text = getString(R.string.analysis_error)
                    Toast.makeText(this@MainActivity, getString(R.string.error_format, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun analyzeFromUrlIncremental(url: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = false
        binding.progressBar.progress = 0
        binding.cardStats.visibility = View.VISIBLE
        binding.tvOutput.text = getString(R.string.starting_incremental_analysis)
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
                    binding.progressBar.visibility = View.GONE
                    
                    if (result.success) {
                        val statsText = buildString {
                            appendLine(getString(R.string.stats_completed_time, duration))
                            appendLine(getString(R.string.stats_downloaded_percent, result.percentDownloaded))
                            appendLine(getString(R.string.stats_data_processed, formatBytes(result.bytesDownloaded)))
                            if (result.totalFileSize > 0) {
                                appendLine(getString(R.string.stats_total_size, formatBytes(result.totalFileSize)))
                                val savedBytes = result.totalFileSize - result.bytesDownloaded
                                val savedPercent = ((savedBytes.toDouble() / result.totalFileSize) * 100).toInt()
                                appendLine(getString(R.string.stats_saved, formatBytes(savedBytes), savedPercent))
                            }
                        }
                        
                        binding.tvStats.text = statsText
                        binding.tvOutput.text = result.getSummary()
                        
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.analysis_completed_toast, result.percentDownloaded),
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        binding.tvStats.text = getString(R.string.analysis_error)
                        binding.tvOutput.text = getString(R.string.error_format, result.error)
                        Toast.makeText(this@MainActivity, getString(R.string.error_format, result.error), Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.tvStats.text = getString(R.string.analysis_error)
                    Toast.makeText(this@MainActivity, getString(R.string.error_format, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun analyzeFromUrlFull(url: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = false
        binding.progressBar.progress = 0
        binding.cardStats.visibility = View.VISIBLE
        binding.tvOutput.text = getString(R.string.downloading_full_file)
        binding.tvStats.text = ""
        
        lifecycleScope.launch {
            try {
                val startTime = System.currentTimeMillis()
                
                val result = MediaInfoStreamHelper.analyzeFromUrl(url, cacheDir) { progress ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        binding.progressBar.progress = progress
                        binding.tvStats.text = getString(R.string.downloading_progress, progress)
                    }
                }
                
                val endTime = System.currentTimeMillis()
                val duration = (endTime - startTime) / 1000.0
                
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.tvStats.text = getString(R.string.download_complete, duration)
                    binding.tvOutput.text = formatOutput(result)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.tvStats.text = getString(R.string.analysis_error)
                    Toast.makeText(this@MainActivity, getString(R.string.error_format, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> getString(R.string.bytes, bytes.toInt())
            bytes < 1024 * 1024 -> getString(R.string.kilobytes, bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> getString(R.string.megabytes, bytes / (1024.0 * 1024.0))
            else -> getString(R.string.gigabytes, bytes / (1024.0 * 1024.0 * 1024.0))
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
