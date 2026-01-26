package net.mediaarea.mediainfo.demo

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.core.content.ContextCompat

object MediaInfoHtmlRenderer {
    
    /**
     * Convierte la salida HTML de MediaInfo en texto formateado con secciones
     */
    fun parseAndFormat(context: Context, htmlOutput: String, isDarkTheme: Boolean): CharSequence {
        if (htmlOutput.isEmpty()) return ""
        
        val lines = htmlOutput.lines()
        val builder = SpannableStringBuilder()
        
        val labelColor = if (isDarkTheme) {
            ContextCompat.getColor(context, R.color.theme_dark_text_label)
        } else {
            ContextCompat.getColor(context, R.color.theme_default_text_label)
        }
        
        val valueColor = if (isDarkTheme) {
            ContextCompat.getColor(context, R.color.theme_dark_text_primary)
        } else {
            ContextCompat.getColor(context, R.color.theme_default_text_primary)
        }
        
        var currentSection = ""
        
        for (line in lines) {
            val trimmed = line.trim()
            
            // Detectar secciones (General, Video, Audio, Text, etc.)
            if (trimmed.matches(Regex("^(General|Video|Audio|Text|Image|Menu|Other)(\\s+#\\d+)?$"))) {
                // Nueva sección
                if (builder.isNotEmpty()) {
                    builder.append("\n\n")
                }
                
                val start = builder.length
                builder.append(trimmed)
                builder.setSpan(
                    StyleSpan(Typeface.BOLD),
                    start,
                    builder.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                builder.setSpan(
                    ForegroundColorSpan(labelColor),
                    start,
                    builder.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                builder.append("\n")
                currentSection = trimmed
                continue
            }
            
            // Parsear líneas con formato "Label : Value" o "Label                    Value"
            val colonIndex = trimmed.indexOf(':')
            if (colonIndex > 0) {
                val label = trimmed.substring(0, colonIndex).trim()
                val value = trimmed.substring(colonIndex + 1).trim()
                
                if (label.isNotEmpty() && value.isNotEmpty()) {
                    // Label en color especial
                    val labelStart = builder.length
                    builder.append(label)
                    builder.setSpan(
                        ForegroundColorSpan(labelColor),
                        labelStart,
                        builder.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    
                    builder.append("    ")
                    
                    // Value en color normal
                    val valueStart = builder.length
                    builder.append(value)
                    builder.setSpan(
                        ForegroundColorSpan(valueColor),
                        valueStart,
                        builder.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    
                    builder.append("\n")
                }
            } else if (trimmed.isNotEmpty() && !trimmed.startsWith("<") && !trimmed.startsWith("<!")) {
                // Línea sin formato especial
                builder.append(trimmed)
                builder.append("\n")
            }
        }
        
        return builder
    }
    
    /**
     * Parsea la salida de MediaInfo en formato "Inform" (Text) y la formatea
     */
    fun parseTextFormat(context: Context, textOutput: String, isDarkTheme: Boolean): CharSequence {
        if (textOutput.isEmpty()) return ""
        
        val lines = textOutput.lines()
        val builder = SpannableStringBuilder()
        
        val labelColor = if (isDarkTheme) {
            ContextCompat.getColor(context, R.color.theme_dark_text_label)
        } else {
            ContextCompat.getColor(context, R.color.theme_default_text_label)
        }
        
        val valueColor = if (isDarkTheme) {
            ContextCompat.getColor(context, R.color.theme_dark_text_primary)
        } else {
            ContextCompat.getColor(context, R.color.theme_default_text_primary)
        }
        
        for (line in lines) {
            val trimmed = line.trim()
            
            // Detectar secciones
            if (trimmed.matches(Regex("^(General|Video|Audio|Text|Image|Menu|Other)(\\s+#\\d+)?$"))) {
                if (builder.isNotEmpty()) {
                    builder.append("\n\n")
                }
                
                val start = builder.length
                builder.append(trimmed)
                builder.setSpan(
                    StyleSpan(Typeface.BOLD),
                    start,
                    builder.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                builder.setSpan(
                    ForegroundColorSpan(labelColor),
                    start,
                    builder.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                builder.append("\n")
                continue
            }
            
            // Detectar líneas con formato "Label    : Value"
            val parts = trimmed.split(Regex("\\s{2,}:"), limit = 2)
            if (parts.size == 2) {
                val label = parts[0].trim()
                val value = parts[1].trim()
                
                val labelStart = builder.length
                builder.append(label)
                builder.setSpan(
                    ForegroundColorSpan(labelColor),
                    labelStart,
                    builder.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                
                builder.append("    ")
                
                val valueStart = builder.length
                builder.append(value)
                builder.setSpan(
                    ForegroundColorSpan(valueColor),
                    valueStart,
                    builder.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                
                builder.append("\n")
            } else if (trimmed.isNotEmpty()) {
                builder.append(trimmed)
                builder.append("\n")
            }
        }
        
        return builder
    }
}
