package com.offlinevideocompat

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.util.ArrayDeque

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var log: TextView
    private var treeUri: Uri? = null
    private val videos = ArrayDeque<Uri>()
    private var converted = 0
    private var failed = 0
    private var total = 0
    private var busy = false
    private val pickFolder = 41

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 34, 28, 24)
            setBackgroundColor(0xFFF4F6FA.toInt())
        }
        val title = TextView(this).apply {
            text = "ממיר וידאו אופליין"
            textSize = 26f
            setTextColor(0xFF17243A.toInt())
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "מנוע FFmpeg להמרת מגוון רחב של פורמטים ל-MP4"
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 24)
            setTextColor(0xFF526078.toInt())
        }
        status = TextView(this).apply {
            text = "עדיין לא נבחרה תיקייה"
            textSize = 16f
            setTextColor(0xFF17243A.toInt())
            setPadding(0, 18, 0, 18)
        }
        val choose = Button(this).apply {
            text = "בחירת תיקייה"
            setOnClickListener { if (!busy) openFolderPicker() }
        }
        val scan = Button(this).apply {
            text = "סריקה והמרת כל הסרטונים"
            setOnClickListener { if (!busy) scanAndConvert() }
        }
        log = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF34445E.toInt())
            setPadding(0, 18, 0, 0)
        }
        val scroll = ScrollView(this).apply { addView(log) }
        root.addView(title, matchWrap())
        root.addView(subtitle, matchWrap())
        root.addView(status, matchWrap())
        root.addView(choose, matchWrap())
        root.addView(scan, matchWrap())
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 12 }

    private fun openFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(intent, pickFolder)
    }

    @Deprecated("Deprecated by platform, retained for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickFolder && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            contentResolver.takePersistableUriPermission(uri, flags)
            treeUri = uri
            status.text = "התיקייה נבחרה. אפשר לסרוק ולהמיר."
            log.text = "הגישה לתיקייה נשמרה גם לפעמים הבאות."
        }
    }

    private fun scanAndConvert() {
        val root = treeUri
        if (root == null) {
            status.text = "קודם צריך לבחור תיקייה"
            return
        }
        videos.clear()
        val queue = ArrayDeque<String>()
        val visited = HashSet<String>()
        try {
            queue.add(DocumentsContract.getTreeDocumentId(root))
            while (queue.isNotEmpty()) {
                val parentId = queue.removeFirst()
                if (!visited.add(parentId)) continue
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(root, parentId)
                contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(idCol)
                        val mime = cursor.getString(mimeCol)
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) queue.add(id)
                        else if (mime?.startsWith("video/") == true) videos.add(DocumentsContract.buildDocumentUriUsingTree(root, id))
                    }
                }
            }
            converted = 0
            failed = 0
            total = videos.size
            if (videos.isEmpty()) {
                status.text = "לא נמצאו סרטונים בתיקייה"
                log.text = ""
            } else {
                busy = true
                log.text = "נמצאו $total סרטונים. מתחיל עיבוד עם FFmpeg..."
                convertNext()
            }
        } catch (e: Exception) {
            status.text = "לא הצלחתי לסרוק את התיקייה"
            log.text = e.localizedMessage ?: e.javaClass.simpleName
        }
    }

    private fun convertNext() {
        if (videos.isEmpty()) {
            busy = false
            status.text = "הסתיים: $converted הומרו, $failed נכשלו"
            log.append("\n\nההמרה הסתיימה. הקבצים נמצאים ב-Movies/Converted.")
            return
        }

        val uri = videos.removeFirst()
        val index = converted + failed + 1
        val name = queryName(uri) ?: "video_$index"
        status.text = "ממיר $index מתוך $total: $name"
        Thread {
            val input = File(cacheDir, "ffmpeg_input_$index.tmp")
            val output = File(cacheDir, "ffmpeg_output_$index.mp4")
            try {
                contentResolver.openInputStream(uri)?.use { source ->
                    input.outputStream().use { target -> source.copyTo(target) }
                } ?: throw IllegalStateException("לא ניתן לקרוא את הסרטון")
                output.delete()

                val command = "-y -i ${quote(input.absolutePath)} -map 0:v:0 -map 0:a? -sn -dn " +
                    "-c:v libopenh264 -b:v 2500k -maxrate 3000k -bufsize 5000k -pix_fmt yuv420p " +
                    "-c:a aac -b:a 128k -movflags +faststart ${quote(output.absolutePath)}"

                FFmpegKit.executeAsync(command) { session ->
                    runOnUiThread {
                        try {
                            if (ReturnCode.isSuccess(session.returnCode) && output.exists() && output.length() > 0) {
                                saveToMovies(output, safeName(name))
                                converted++
                                log.append("\n✓ $name")
                            } else {
                                failed++
                                val detail = session.failStackTrace ?: session.allLogsAsString
                                log.append("\n✗ נכשל: $name")
                                if (!detail.isNullOrBlank()) log.append("\n" + detail.takeLast(450))
                            }
                        } catch (e: Exception) {
                            failed++
                            log.append("\n✗ שמירה נכשלה: $name — ${e.localizedMessage}")
                        } finally {
                            input.delete()
                            output.delete()
                            convertNext()
                        }
                    }
                }
            } catch (e: Exception) {
                input.delete()
                output.delete()
                runOnUiThread {
                    failed++
                    log.append("\n✗ שגיאה בקובץ $name: ${e.localizedMessage}")
                    convertNext()
                }
            }
        }.start()
    }

    private fun quote(path: String): String = "'" + path.replace("'", "'\\''") + "'"

    private fun queryName(uri: Uri): String? = contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }

    private fun safeName(name: String): String {
        val base = name.substringBeforeLast('.', name).replace(Regex("[^A-Za-z0-9א-ת _-]"), "_").trim().ifBlank { "video" }
        return "$base.mp4"
    }

    private fun saveToMovies(source: File, fileName: String) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (android.os.Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Converted")
        }
        val target = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("לא ניתן ליצור קובץ יעד")
        try {
            contentResolver.openOutputStream(target)?.use { out -> source.inputStream().use { input -> input.copyTo(out) } }
                ?: throw IllegalStateException("לא ניתן לכתוב קובץ")
        } catch (e: Exception) {
            contentResolver.delete(target, null, null)
            throw e
        }
    }
}
