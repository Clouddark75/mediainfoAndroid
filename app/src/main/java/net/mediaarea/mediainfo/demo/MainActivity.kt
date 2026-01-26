package net.mediaarea.mediainfo.demo

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mediaarea.mediainfo.demo.databinding.ActivityMainBinding
import net.mediaarea.mediainfo.lib.MediaInfo
import net.mediaarea.mediainfo.lib.MediaInfoUtil

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    
    private var currentFormat = "Text"
    private var currentTheme = "default"
    private var currentOutput = ""
    private var currentFileName = ""
    private var currentUri: Uri? = null
    private var currentStreamUrl: String? = null
    private var trimSpaces = false
    
    private val pickMediaLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { analyzeLocalFile(it) }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        // Cargar tema guardado antes de setContentView
        prefs = getSharedPreferences("mediainfo_prefs", Context.MODE_PRIVATE)
        currentTheme = prefs.getString("theme", "default") ?: "default"
        applyTheme(currentTheme, false)
        
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupUI()
        checkPermissions()
        
        // Cargar formato guardado
        currentFormat = prefs.getString("format", "Text") ?: "Text"
        trimSpaces = prefs.getBoolean("trim_spaces", false)
        
        // Manejar intent de apertura
        handleIntent(intent)
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }
    
    private fun setupUI() {
        setSupportActionBar(binding.toolbar)
        
        // FAB Menu (3 dots)
        binding.fabMenu.setOnClickListener { view ->
            showPopupMenu(view)
        }
        
        // Mensaje inicial
        if (currentOutput.isEmpty()) {
            binding.tvOutput.text = getString(R.string.no_file_loaded)
            binding.tvSubtitle.visibility = View.GONE
        }
    }
    
    private fun showPopupMenu(view: View) {
        val popup = PopupMenu(this, view)
        popup.menuInflater.inflate(R.menu.menu_main, popup.menu)
        
        // Marcar formato actual con checkable behavior
        popup.menu.findItem(R.id.menu_output_format)?.subMenu?.apply {
            setGroupCheckable(0, true, true)
            when (currentFormat) {
                "Text" -> findItem(R.id.format_text)?.isChecked = true
                "HTML" -> findItem(R.id.format_html)?.isChecked = true
                "JSON" -> findItem(R.id.format_json)?.isChecked = true
                "XML" -> findItem(R.id.format_xml)?.isChecked = true
                "PBCore" -> findItem(R.id.format_pbcore)?.isChecked = true
                "EBUCore" -> findItem(R.id.format_ebucore)?.isChecked = true
            }
        }
        
        // Marcar trim spaces
        popup.menu.findItem(R.id.menu_trim_spaces)?.isChecked = trimSpaces
        
        // Marcar tema actual con checkable behavior
        popup.menu.findItem(R.id.menu_theme)?.subMenu?.apply {
            setGroupCheckable(0, true, true)
            when (currentTheme) {
                "default" -> findItem(R.id.theme_default)?.isChecked = true
                "dark" -> findItem(R.id.theme_dark)?.isChecked = true
            }
        }
        
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_open_file -> {
                    pickMediaLauncher.launch(arrayOf("video/*", "audio/*", "image/*", "application/*"))
                    true
                }
                R.id.menu_open_stream -> {
                    showOpenStreamDialog()
                    true
                }
                R.id.format_text -> {
                    changeFormat("Text")
                    true
                }
                R.id.format_html -> {
                    changeFormat("HTML")
                    true
                }
                R.id.format_json -> {
                    changeFormat("JSON")
                    true
                }
                R.id.format_xml -> {
                    changeFormat("XML")
                    true
                }
                R.id.format_pbcore -> {
                    changeFormat("PBCore")
                    true
                }
                R.id.format_ebucore -> {
                    changeFormat("EBUCore")
                    true
                }
                R.id.menu_trim_spaces -> {
                    toggleTrimSpaces()
                    true
                }
                R.id.menu_copy_clipboard -> {
                    copyToClipboard()
                    true
                }
                R.id.theme_default -> {
                    changeTheme("default")
                    true
                }
                R.id.theme_dark -> {
                    changeTheme("dark")
                    true
                }
                R.id.menu_about -> {
                    showAboutDialog()
                    true
                }
                R.id.menu_license -> {
                    showLicenseDialog()
                    true
                }
                R.id.menu_exit -> {
                    finish()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }
    
    private fun showOpenStreamDialog() {
        val input = TextInputEditText(this).apply {
            hint = getString(R.string.dialog_open_stream_hint)
            setText("https://")
        }
        
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_open_stream_title)
            .setView(input)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty() && (url.startsWith("http://") || url.startsWith("https://"))) {
                    analyzeFromStream(url)
                } else {
                    Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }
    
    private fun changeFormat(format: String) {
        currentFormat = format
        prefs.edit().putString("format", format).apply()
        Toast.makeText(this, getString(R.string.format_changed, format), Toast.LENGTH_SHORT).show()
        
        // Re-analizar archivo o stream según corresponda
        when {
            currentUri != null -> analyzeLocalFile(currentUri!!)
            currentStreamUrl != null -> analyzeFromStream(currentStreamUrl!!)
        }
    }
    
    private fun toggleTrimSpaces() {
        trimSpaces = !trimSpaces
        prefs.edit().putBoolean("trim_spaces", trimSpaces).apply()
        
        val message = if (trimSpaces) {
            getString(R.string.trim_spaces_enabled)
        } else {
            getString(R.string.trim_spaces_disabled)
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        
        // Re-renderizar SOLO si es Text/HTML
        if (currentOutput.isNotEmpty() && (currentFormat == "Text" || currentFormat == "HTML")) {
            refreshDisplay()
        }
    }
    
    private fun changeTheme(theme: String) {
        currentTheme = theme
        prefs.edit().putString("theme", theme).apply()
        applyTheme(theme, true)
    }
    
    private fun applyTheme(theme: String, recreate: Boolean) {
        when (theme) {
            "default" -> setTheme(R.style.Theme_MediaInfo_Default)
            "dark" -> setTheme(R.style.Theme_MediaInfo_Dark)
        }
        
        if (recreate) {
            val themeName = when (theme) {
                "default" -> getString(R.string.theme_default)
                "dark" -> getString(R.string.theme_dark)
                else -> theme
            }
            Toast.makeText(this, getString(R.string.theme_changed, themeName), Toast.LENGTH_SHORT).show()
            recreate()
        }
    }
    
    private fun copyToClipboard() {
        if (currentOutput.isEmpty()) {
            Toast.makeText(this, R.string.no_data_to_copy, Toast.LENGTH_SHORT).show()
            return
        }
        
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("MediaInfo", currentOutput)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }
    
    private fun showAboutDialog() {
        val version = MediaInfoUtil.getVersion()
        AlertDialog.Builder(this)
            .setTitle(R.string.about_title)
            .setMessage(getString(R.string.about_version, version) + "\n\n" + getString(R.string.about_description))
            .setPositiveButton(R.string.dialog_ok, null)
            .show()
    }
    
    private fun showLicenseDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.license_title)
            .setMessage(R.string.license_text)
            .setPositiveButton(R.string.dialog_ok, null)
            .show()
    }
    
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
                            analyzeFromStream(url)
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
                    }
                }
            }
        }
    }
    
    private fun handleUri(uri: Uri) {
        when (uri.scheme) {
            "http", "https", "rtsp", "rtmp", "mms", "ftp", "ftps", "sftp" -> {
                analyzeFromStream(uri.toString())
            }
            "file", "content" -> {
                analyzeLocalFile(uri)
            }
        }
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
        binding.tvOutput.text = getString(R.string.analyzing)
        
        // Obtener nombre limpio del archivo
        currentFileName = getCleanFileName(uri)
        currentUri = uri
        currentStreamUrl = null
        binding.tvSubtitle.text = currentFileName
        binding.tvSubtitle.visibility = View.VISIBLE
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val pfd: ParcelFileDescriptor? = contentResolver.openFileDescriptor(uri, "r")
                val fd = pfd?.detachFd() ?: throw Exception(getString(R.string.error_opening_file))
                
                // Intentar obtener ruta real, si falla usar URI
                val filePath = getRealPathFromUri(uri) ?: uri.toString()
                
                // Usar formato actual
                val formatParam = getMediaInfoFormatParam(currentFormat)
                val result = MediaInfoUtil.getMediaInfo(fd, filePath, formatParam)
                
                pfd?.close()
                
                currentOutput = result
                
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    refreshDisplay()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.tvOutput.text = getString(R.string.error_analyzing) + ": ${e.message}"
                }
            }
        }
    }
    
    private fun analyzeFromStream(url: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = false
        binding.progressBar.progress = 0
        binding.tvOutput.text = getString(R.string.analyzing_stream)
        
        // Limpiar nombre del archivo del URL
        currentFileName = getCleanFileNameFromUrl(url)
        currentStreamUrl = url
        currentUri = null
        binding.tvSubtitle.text = currentFileName
        binding.tvSubtitle.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            try {
                // Pasar URL completa para que MediaInfo la use como "Complete name"
                val result = MediaInfoStreamHelper.analyzeFromStreamIncremental(
                    url,
                    onProgress = { bytesRead, totalBytes, status ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            val progress = if (totalBytes != null && totalBytes > 0) {
                                ((bytesRead * 100) / totalBytes).toInt()
                            } else {
                                0
                            }
                            binding.progressBar.progress = progress
                        }
                    }
                )
                
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    
                    if (result.success) {
                        currentOutput = result.textOutput
                        refreshDisplay()
                    } else {
                        binding.tvOutput.text = getString(R.string.error_analyzing) + ": ${result.error}"
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.tvOutput.text = getString(R.string.error_analyzing) + ": ${e.message}"
                }
            }
        }
    }
    
    private fun getMediaInfoFormatParam(format: String): String {
        return when (format) {
            "Text" -> "Text"
            "HTML" -> "HTML"
            "JSON" -> "JSON"
            "XML" -> "MIXML"
            "PBCore" -> "PBCore"
            "EBUCore" -> "EBUCore"
            else -> "Text"
        }
    }
    
    /**
     * Refresca la visualización con el formato actual
     */
    private fun refreshDisplay() {
        if (currentOutput.isEmpty()) {
            binding.tvOutput.text = getString(R.string.no_file_loaded)
            return
        }
        
        val isDarkTheme = currentTheme == "dark"
        
        val formattedOutput = when (currentFormat) {
            "Text" -> {
                // Formatear texto con colores
                MediaInfoHtmlRenderer.parseAndFormat(this, currentOutput, isDarkTheme, trimSpaces)
            }
            "HTML" -> {
                // HTML generado por MediaInfo - renderizar como HTML
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    android.text.Html.fromHtml(currentOutput, android.text.Html.FROM_HTML_MODE_LEGACY)
                } else {
                    @Suppress("DEPRECATION")
                    android.text.Html.fromHtml(currentOutput)
                }
            }
            "JSON", "XML", "PBCore", "EBUCore" -> {
                // Para formatos estructurados, mostrar raw
                currentOutput
            }
            else -> currentOutput
        }
        
        binding.tvOutput.text = formattedOutput
    }
    
    /**
     * Obtiene el nombre limpio de un archivo desde un URI
     */
    private fun getCleanFileName(uri: Uri): String {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    cursor.getString(nameIndex)
                } else {
                    uri.lastPathSegment ?: "Unknown"
                }
            } ?: uri.lastPathSegment ?: "Unknown"
        } catch (e: Exception) {
            uri.lastPathSegment ?: "Unknown"
        }
    }
    
    /**
     * Limpia el nombre del archivo de un URL
     * Decodifica caracteres URL y elimina parámetros de query
     */
    private fun getCleanFileNameFromUrl(url: String): String {
        return try {
            // Extraer la parte del path después del último "/"
            val path = url.substringAfterLast('/')
            
            // Eliminar parámetros de query (después de "?")
            val withoutQuery = path.substringBefore('?')
            
            // Decodificar caracteres URL (%20 -> espacio, etc.)
            val decoded = java.net.URLDecoder.decode(withoutQuery, "UTF-8")
            
            // Limitar longitud y retornar
            if (decoded.length > 50) {
                decoded.take(47) + "..."
            } else {
                decoded
            }
        } catch (e: Exception) {
            // Si falla, usar fallback simple
            url.substringAfterLast('/').substringBefore('?').take(50)
        }
    }
    
    /**
     * Intenta obtener la ruta real del archivo desde un URI
     * Retorna null si no se puede obtener
     */
    @SuppressLint("Range")
    private fun getRealPathFromUri(uri: Uri): String? {
        return when {
            // DocumentProvider
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && DocumentsContract.isDocumentUri(this, uri) -> {
                when {
                    // ExternalStorageProvider
                    isExternalStorageDocument(uri) -> {
                        val docId = DocumentsContract.getDocumentId(uri)
                        val split = docId.split(":")
                        val type = split[0]
                        
                        if ("primary".equals(type, ignoreCase = true)) {
                            "${Environment.getExternalStorageDirectory()}/${split[1]}"
                        } else {
                            "/storage/${split[0]}/${split[1]}"
                        }
                    }
                    // DownloadsProvider
                    isDownloadsDocument(uri) -> {
                        val id = DocumentsContract.getDocumentId(uri)
                        
                        // Si el ID empieza con "raw:", es una ruta directa
                        if (id.startsWith("raw:")) {
                            return id.substring(4)
                        }
                        
                        // Intentar obtener desde MediaStore
                        val contentUriPrefixesToTry = arrayOf(
                            "content://downloads/public_downloads",
                            "content://downloads/my_downloads",
                            "content://downloads/all_downloads"
                        )
                        
                        for (prefix in contentUriPrefixesToTry) {
                            try {
                                val contentUri = ContentUris.withAppendedId(Uri.parse(prefix), id.toLong())
                                val path = getDataColumn(contentUri, null, null)
                                if (path != null) return path
                            } catch (e: Exception) {
                                continue
                            }
                        }
                        null
                    }
                    // MediaProvider
                    isMediaDocument(uri) -> {
                        val docId = DocumentsContract.getDocumentId(uri)
                        val split = docId.split(":")
                        val type = split[0]
                        
                        val contentUri = when (type) {
                            "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                            else -> null
                        }
                        
                        contentUri?.let {
                            val selection = "_id=?"
                            val selectionArgs = arrayOf(split[1])
                            getDataColumn(it, selection, selectionArgs)
                        }
                    }
                    else -> null
                }
            }
            // MediaStore (and general)
            "content".equals(uri.scheme, ignoreCase = true) -> {
                getDataColumn(uri, null, null)
            }
            // File
            "file".equals(uri.scheme, ignoreCase = true) -> {
                uri.path
            }
            else -> null
        }
    }
    
    @SuppressLint("Range")
    private fun getDataColumn(uri: Uri, selection: String?, selectionArgs: Array<String>?): String? {
        var cursor: Cursor? = null
        val column = "_data"
        val projection = arrayOf(column)
        
        try {
            cursor = contentResolver.query(uri, projection, selection, selectionArgs, null)
            if (cursor != null && cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndex(column)
                if (columnIndex != -1) {
                    return cursor.getString(columnIndex)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cursor?.close()
        }
        return null
    }
    
    private fun isExternalStorageDocument(uri: Uri): Boolean {
        return "com.android.externalstorage.documents" == uri.authority
    }
    
    private fun isDownloadsDocument(uri: Uri): Boolean {
        return "com.android.providers.downloads.documents" == uri.authority
    }
    
    private fun isMediaDocument(uri: Uri): Boolean {
        return "com.android.providers.media.documents" == uri.authority
    }
}
