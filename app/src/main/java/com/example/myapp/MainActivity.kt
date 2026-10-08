package com.example.myapp

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

// Light item: only two strings. The document Uri is built later, only for the few files in use.
class FileItem(val name: String, val docId: String)

class ListRes(val items: ArrayList<FileItem>, val error: String)

// Natural order: 1, 2, 3 ... 10, 11 (not 1, 10, 11, 2). Works for "5.pdf", "file7.txt", "IMG_0012.jpg" and so on.
fun natCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    val la = a.length
    val lb = b.length
    while (i < la && j < lb) {
        val ca = a[i]
        val cb = b[j]
        if (ca in '0'..'9' && cb in '0'..'9') {
            var si = i
            while (si < la && a[si] == '0') si++
            var sj = j
            while (sj < lb && b[sj] == '0') sj++
            var ei = si
            while (ei < la && a[ei] in '0'..'9') ei++
            var ej = sj
            while (ej < lb && b[ej] in '0'..'9') ej++
            val lenA = ei - si
            val lenB = ej - sj
            if (lenA != lenB) return if (lenA < lenB) -1 else 1
            var k = 0
            while (k < lenA) {
                val x = a[si + k]
                val y = b[sj + k]
                if (x != y) return if (x < y) -1 else 1
                k++
            }
            i = ei
            j = ej
        } else {
            val x = ca.lowercaseChar()
            val y = cb.lowercaseChar()
            if (x != y) return if (x < y) -1 else 1
            i++
            j++
        }
    }
    val ra = la - i
    val rb = lb - j
    return if (ra == rb) 0 else if (ra < rb) -1 else 1
}

fun mimeOfName(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    if (ext.isEmpty()) return "application/octet-stream"
    val m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    if (m != null) return m
    return when (ext) {
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "avif" -> "image/avif"
        "txt", "log", "md" -> "text/plain"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }
}

// Serves the picked files (any type) to other apps when they paste from the clipboard.
class ClipProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    private fun docOf(uri: Uri): Uri? {
        val segs = uri.pathSegments
        if (segs.size < 1) return null
        return Uri.parse(segs[0])
    }

    override fun getType(uri: Uri): String? {
        val nm = uri.lastPathSegment ?: ""
        val m = mimeOfName(nm)
        if (m != "application/octet-stream") return m
        val d = docOf(uri) ?: return m
        val ctx = context ?: return m
        val t: String? = try {
            ctx.contentResolver.getType(d)
        } catch (e: Exception) {
            null
        }
        return t ?: m
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        val d = docOf(uri) ?: return null
        val ctx = context ?: return null
        val name = uri.lastPathSegment ?: "file"
        var size = -1L
        try {
            ctx.contentResolver.query(d, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) {
                    size = c.getLong(0)
                }
            }
        } catch (e: Exception) {
        }
        val want: Array<String> = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = arrayOfNulls<Any>(want.size)
        for (i in want.indices) {
            if (want[i] == OpenableColumns.DISPLAY_NAME) {
                row[i] = name
            } else if (want[i] == OpenableColumns.SIZE) {
                if (size >= 0L) row[i] = size
            }
        }
        val mc = MatrixCursor(want)
        mc.addRow(row)
        return mc
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val d = docOf(uri) ?: return null
        val ctx = context ?: return null
        return ctx.contentResolver.openFileDescriptor(d, "r")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int = 0
}

// One shared brain used by both the main screen and the floating panel (same process, same state).
object Core {
    const val AUTH = "com.example.myapp.clip"

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Runnable>()
    private var inited = false
    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences

    @Volatile
    var files: List<FileItem> = emptyList()
    var treeUri: Uri? = null
    var batch: Int = 7
    var pos: Int = 0
    var sub: Int = 0
    var lastMsg: String = ""
    var lastError: String = ""
    var loading: Boolean = false
    var loadCount: Int = 0

    @Volatile
    private var loadToken: Int = 0

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        app = ctx.applicationContext
        prefs = app.getSharedPreferences("imgbatch", Context.MODE_PRIVATE)
        batch = prefs.getInt("batch", 7)
        pos = prefs.getInt("pos", 0)
        sub = prefs.getInt("sub", 0)
        val saved = prefs.getString("tree", null)
        if (saved != null) {
            treeUri = Uri.parse(saved)
            reload()
        }
    }

    fun addListener(r: Runnable) {
        if (!listeners.contains(r)) listeners.add(r)
    }

    fun removeListener(r: Runnable) {
        listeners.remove(r)
    }

    fun changed() {
        main.post {
            for (l in listeners) l.run()
        }
    }

    fun save() {
        val t = treeUri
        val e = prefs.edit().putInt("batch", batch).putInt("pos", pos).putInt("sub", sub)
        if (t != null) e.putString("tree", t.toString())
        e.apply()
    }

    fun setTree(u: Uri) {
        treeUri = u
        files = emptyList()
        pos = 0
        sub = 0
        lastMsg = ""
        lastError = ""
        save()
        reload()
    }

    // ---------- Folder reading (background, cached) ----------

    private fun cacheFile(t: Uri): File {
        return File(app.cacheDir, "all_" + t.toString().hashCode() + ".txt")
    }

    private fun loadCache(f: File): ArrayList<FileItem> {
        val out = ArrayList<FileItem>()
        try {
            if (!f.exists()) return out
            f.bufferedReader().use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    val tab = line.indexOf('\t')
                    if (tab > 0) {
                        out.add(FileItem(line.substring(tab + 1), line.substring(0, tab)))
                    }
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun saveCache(f: File, list: List<FileItem>) {
        try {
            f.bufferedWriter().use { w ->
                for (e in list) {
                    w.write(e.docId)
                    w.write("\t")
                    w.write(e.name)
                    w.write("\n")
                }
            }
        } catch (ex: Exception) {
        }
    }

    private fun sameList(a: List<FileItem>, b: List<FileItem>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            if (a[i].docId != b[i].docId) return false
        }
        return true
    }

    private fun applyList(list: List<FileItem>) {
        files = list
        if (pos > list.size) {
            pos = 0
            sub = 0
        }
        save()
        changed()
    }

    // The screen is usable at once from the saved list, then the real folder is read in the background.
    fun reload() {
        val t = treeUri
        if (t == null) {
            changed()
            return
        }
        loadToken += 1
        val token = loadToken
        val cf = cacheFile(t)
        val needCache = files.isEmpty()
        loading = true
        loadCount = 0
        changed()
        Thread {
            if (needCache) {
                val cached = loadCache(cf)
                if (cached.isNotEmpty()) {
                    main.post {
                        if (token == loadToken) applyList(cached)
                    }
                }
            }
            val res = listFolder(t, token)
            main.post {
                if (token == loadToken) {
                    lastError = res.error
                    loading = false
                    if (res.error.isNotEmpty() && files.isNotEmpty()) {
                        changed()
                    } else if (files.isEmpty() || !sameList(files, res.items)) {
                        applyList(res.items)
                    } else {
                        changed()
                    }
                }
            }
            if (res.error.isEmpty() && token == loadToken) {
                saveCache(cf, res.items)
            }
        }.start()
    }

    private fun listFolder(tree: Uri, token: Int): ListRes {
        val out = ArrayList<FileItem>()
        var err = ""
        try {
            val treeId = DocumentsContract.getTreeDocumentId(tree)
            val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            app.contentResolver.query(kids, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (token != loadToken) break
                    val id: String? = c.getString(0)
                    val name: String = c.getString(1) ?: ""
                    val mime: String = c.getString(2) ?: ""
                    if (id != null && mime != DocumentsContract.Document.MIME_TYPE_DIR) {
                        out.add(FileItem(name, id))
                        if (out.size % 2000 == 0) {
                            val cnt = out.size
                            main.post {
                                if (token == loadToken) {
                                    loadCount = cnt
                                    changed()
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            err = e.message ?: "error"
        }
        out.sortWith(Comparator<FileItem> { a, b ->
            val c = natCompare(a.name, b.name)
            if (c != 0) c else a.name.compareTo(b.name)
        })
        return ListRes(out, err)
    }

    // ---------- Batch helpers ----------

    fun docUri(e: FileItem): Uri {
        val t = treeUri ?: throw IllegalStateException("No folder")
        return DocumentsContract.buildDocumentUriUsingTree(t, e.docId)
    }

    fun providerUri(e: FileItem): Uri {
        return Uri.Builder()
            .scheme("content")
            .authority(AUTH)
            .appendPath(docUri(e).toString())
            .appendPath(e.name)
            .build()
    }

    fun batchEnd(): Int {
        return minOf(pos.toLong() + batch.toLong(), files.size.toLong()).toInt()
    }

    fun currentBatch(): ArrayList<FileItem> {
        if (pos >= files.size) return ArrayList<FileItem>()
        return ArrayList<FileItem>(files.subList(pos, batchEnd()))
    }

    @Suppress("DEPRECATION")
    fun buildClip(items: List<FileItem>): ClipData {
        val mimes = LinkedHashSet<String>()
        for (e in items) mimes.add(mimeOfName(e.name))
        val desc = ClipDescription("files", mimes.toTypedArray())
        val clip = ClipData(desc, ClipData.Item(providerUri(items[0])))
        for (k in 1 until items.size) {
            clip.addItem(ClipData.Item(providerUri(items[k])))
        }
        return clip
    }

    fun shareType(items: List<FileItem>): String {
        val first = mimeOfName(items[0].name)
        val major = first.substringBefore('/')
        var same = true
        var sameMajor = true
        for (e in items) {
            val m = mimeOfName(e.name)
            if (m != first) same = false
            if (m.substringBefore('/') != major) sameMajor = false
        }
        return if (same) first else if (sameMajor) "$major/*" else "*/*"
    }

    private fun putOnClipboard(items: List<FileItem>): String? {
        try {
            val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(buildClip(items))
            return null
        } catch (e: Exception) {
            return "Copy failed: " + e.message
        }
    }

    // ---------- Actions (return an error text, or null when fine) ----------

    fun copyBatch(): String? {
        val items = currentBatch()
        if (items.isEmpty()) return "Nothing to copy. Pick a folder or tap Reset."
        val err = putOnClipboard(items)
        if (err != null) return err
        lastMsg = "Copied " + items.first().name + " to " + items.last().name +
            " (" + items.size + " files). Now paste in the other app."
        pos = batchEnd()
        sub = 0
        save()
        changed()
        return null
    }

    fun copyOne(): String? {
        val total = files.size
        if (pos >= total) return "Nothing to copy. Pick a folder or tap Reset."
        val count = batchEnd() - pos
        if (sub >= count) sub = 0
        val e = files[pos + sub]
        val err = putOnClipboard(listOf(e))
        if (err != null) return err
        sub += 1
        if (sub >= count) {
            lastMsg = "Copied " + e.name + " (last one of this batch). Next batch is ready."
            pos = batchEnd()
            sub = 0
        } else {
            lastMsg = "Copied " + e.name + " (" + sub + " of " + count + "). Now paste."
        }
        save()
        changed()
        return null
    }

    fun afterShare(items: List<FileItem>) {
        lastMsg = "Shared " + items.first().name + " to " + items.last().name +
            " (" + items.size + " files)."
        pos = batchEnd()
        sub = 0
        save()
        changed()
    }

    fun prevBatch() {
        pos = maxOf(0, pos - batch)
        sub = 0
        lastMsg = "Went back one batch."
        save()
        changed()
    }

    fun resetAll() {
        pos = 0
        sub = 0
        lastMsg = "Reset to the start."
        save()
        changed()
    }

    fun changeBatch(n: Int) {
        batch = maxOf(1, n)
        sub = 0
        lastMsg = "Batch size set to " + batch + "."
        save()
        changed()
    }

    fun statusText(compact: Boolean): String {
        if (treeUri == null) {
            return if (compact) "No folder chosen. Open the app and pick a folder."
            else "No folder chosen yet.\nTap \"Pick folder\" and choose the folder with your files."
        }
        val total = files.size
        if (total == 0) {
            if (loading) return "Reading folder... " + loadCount + " files found"
            val extra = if (lastError.isEmpty()) "" else "\n(" + lastError + ")"
            return "No files found in that folder." + extra
        }
        if (pos >= total) {
            return "Done: all " + total + " files used.\nTap \"Reset\" to start again." +
                (if (lastMsg.isEmpty()) "" else "\n\n" + lastMsg)
        }
        val end = batchEnd()
        val sb = StringBuilder()
        sb.append("Folder: ").append(total).append(" files   |   Batch: ").append(batch)
        if (loading) sb.append("   (updating...)")
        sb.append("\n")
        sb.append("Next: ").append(files[pos].name).append(" to ").append(files[end - 1].name)
            .append("  (").append(end - pos).append(" files)\n")
        if (sub > 0 && pos + sub < end) {
            sb.append("Copy one: next is ").append(files[pos + sub].name).append("\n")
        }
        if (lastMsg.isNotEmpty()) {
            sb.append("\n").append(lastMsg)
        }
        return sb.toString()
    }
}

class MainActivity : Activity() {

    companion object {
        const val REQ_TREE = 101
        const val REQ_NOTIF = 102
    }

    private lateinit var rootView: LinearLayout
    private lateinit var status: TextView
    private lateinit var batchEdit: EditText
    private lateinit var strip: LinearLayout
    private lateinit var stripScroll: HorizontalScrollView

    private var thumbToken: Int = 0
    private var lastThumbKey: String = ""
    private val ui = Runnable { refreshAll() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Core.init(this)
        buildUi()
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        Core.addListener(ui)
        refreshAll()
    }

    override fun onPause() {
        super.onPause()
        Core.removeListener(ui)
        Core.save()
    }

    // ---------- UI ----------

    private fun dp(v: Int): Int {
        return (v * resources.displayMetrics.density).toInt()
    }

    private fun mkBtn(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.isFocusable = false
        b.textSize = 13f
        b.setOnClickListener { onClick() }
        return b
    }

    private fun mkRow(vararg views: View): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        for (v in views) {
            r.addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        return r
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(8), dp(8), dp(8), dp(8))
        root.isFocusable = true
        root.isFocusableInTouchMode = true

        val pickB = mkBtn("Pick folder") { pickFolder() }
        batchEdit = EditText(this)
        batchEdit.inputType = InputType.TYPE_CLASS_NUMBER
        batchEdit.setText(Core.batch.toString())
        batchEdit.gravity = Gravity.CENTER
        batchEdit.hint = "Batch"
        batchEdit.imeOptions = EditorInfo.IME_ACTION_DONE
        batchEdit.setOnEditorActionListener { _, _, _ ->
            applyBatchSize()
            true
        }
        val setB = mkBtn("Set size") { applyBatchSize() }
        root.addView(mkRow(pickB, batchEdit, setB))

        val copyB = mkBtn("Copy batch") { report(Core.copyBatch()) }
        val oneB = mkBtn("Copy one") { report(Core.copyOne()) }
        val shareB = mkBtn("Share batch") { shareBatch() }
        root.addView(mkRow(copyB, oneB, shareB))

        val prevB = mkBtn("Prev batch") { Core.prevBatch() }
        val resetB = mkBtn("Reset to 1") { Core.resetAll() }
        val refreshB = mkBtn("Refresh list") { Core.reload() }
        root.addView(mkRow(prevB, resetB, refreshB))

        val floatB = mkBtn("Float on top") { startFloat() }
        val stopFloatB = mkBtn("Stop float") { stopFloat() }
        root.addView(mkRow(floatB, stopFloatB))

        status = TextView(this)
        status.textSize = 15f
        status.setPadding(dp(4), dp(10), dp(4), dp(10))
        root.addView(
            status,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        stripScroll = HorizontalScrollView(this)
        strip = LinearLayout(this)
        strip.orientation = LinearLayout.HORIZONTAL
        stripScroll.addView(strip)
        root.addView(
            stripScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val hint = TextView(this)
        hint.textSize = 12f
        hint.setPadding(dp(4), dp(14), dp(4), dp(4))
        hint.text = "Works with any file type (pdf, txt, images, zip ...). Files are copied in name order: " +
            "1, 2, 3 ... 10, 11.\n" +
            "After Copy: open the other app, tap in the box, then paste (long-press > Paste, or Ctrl+V).\n" +
            "Float on top: shows a small panel over every app. Drag the top bar to move it, drag the " +
            "bottom-right corner (finger or mouse) to resize it.\n" +
            "Keys: C copy batch, O copy one, S share, B previous, R reset, F float."
        root.addView(
            hint,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        rootView = root
        setContentView(root)
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    private fun report(err: String?) {
        if (err != null) toast(err)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(batchEdit.windowToken, 0)
    }

    // ---------- Folder ----------

    private fun pickFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(i, REQ_TREE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_TREE && resultCode == RESULT_OK && data != null) {
            val u = data.data
            if (u != null) {
                try {
                    contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                }
                lastThumbKey = ""
                Core.setTree(u)
            }
        }
    }

    // ---------- Display ----------

    private fun refreshAll() {
        status.text = Core.statusText(false)
        if (!batchEdit.hasFocus() && batchEdit.text.toString() != Core.batch.toString()) {
            batchEdit.setText(Core.batch.toString())
        }
        val total = Core.files.size
        if (Core.treeUri == null || total == 0 || Core.pos >= total) {
            if (lastThumbKey != "") {
                strip.removeAllViews()
                thumbToken += 1
                lastThumbKey = ""
            }
            return
        }
        val from = Core.pos
        val end = Core.batchEnd()
        val key = from.toString() + ":" + end + ":" + total + ":" + System.identityHashCode(Core.files)
        if (key != lastThumbKey) {
            lastThumbKey = key
            loadThumbs(from, end)
        }
    }

    private fun extLabel(name: String): String {
        val e = name.substringAfterLast('.', "").uppercase()
        if (e.isEmpty()) return "FILE"
        return if (e.length > 5) e.substring(0, 5) else e
    }

    // Only the first 30 files of the batch get a tile. Images show a preview, other files show their type.
    private fun loadThumbs(from: Int, to: Int) {
        strip.removeAllViews()
        thumbToken += 1
        val myToken = thumbToken
        val shownEnd = minOf(to, from + 30)
        val snapshot = ArrayList<FileItem>(Core.files.subList(from, shownEnd))
        val thumbs = ArrayList<ImageView?>()
        val docs = ArrayList<Uri?>()
        for (e in snapshot) {
            val lp = LinearLayout.LayoutParams(dp(96), dp(96))
            lp.setMargins(dp(2), dp(2), dp(2), dp(2))
            if (mimeOfName(e.name).startsWith("image/")) {
                val iv = ImageView(this)
                iv.scaleType = ImageView.ScaleType.CENTER_CROP
                iv.setBackgroundColor(Color.LTGRAY)
                strip.addView(iv, lp)
                thumbs.add(iv)
                docs.add(Core.docUri(e))
            } else {
                val tv = TextView(this)
                tv.gravity = Gravity.CENTER
                tv.setBackgroundColor(Color.LTGRAY)
                tv.setTextColor(Color.BLACK)
                tv.textSize = 12f
                tv.maxLines = 4
                tv.ellipsize = TextUtils.TruncateAt.END
                tv.setPadding(dp(4), dp(4), dp(4), dp(4))
                tv.text = extLabel(e.name) + "\n" + e.name
                strip.addView(tv, lp)
                thumbs.add(null)
                docs.add(null)
            }
        }
        Thread {
            for (k in docs.indices) {
                if (myToken != thumbToken) break
                val iv = thumbs[k] ?: continue
                val d = docs[k] ?: continue
                val bmp: Bitmap? = decodeThumb(d, 192)
                if (bmp != null) {
                    runOnUiThread {
                        if (myToken == thumbToken) {
                            iv.setImageBitmap(bmp)
                        }
                    }
                }
            }
        }.start()
    }

    private fun decodeThumb(doc: Uri, target: Int): Bitmap? {
        try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            contentResolver.openInputStream(doc)?.use { BitmapFactory.decodeStream(it, null, o) }
            var s = 1
            while (o.outWidth / (s * 2) >= target && o.outHeight / (s * 2) >= target) {
                s *= 2
            }
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = s
            return contentResolver.openInputStream(doc)?.use { BitmapFactory.decodeStream(it, null, o2) }
        } catch (e: Throwable) {
            return null
        }
    }

    // ---------- Actions ----------

    private fun applyBatchSize() {
        val n: Int? = batchEdit.text.toString().trim().toIntOrNull()
        if (n == null || n < 1) {
            toast("Enter a number, 1 or more")
            return
        }
        hideKeyboard()
        batchEdit.clearFocus()
        rootView.requestFocus()
        Core.changeBatch(n)
    }

    private fun shareBatch() {
        val items = Core.currentBatch()
        if (items.isEmpty()) {
            toast("Nothing to share. Pick a folder or tap Reset.")
            return
        }
        try {
            val uris = ArrayList<Uri>()
            for (e in items) {
                uris.add(Core.providerUri(e))
            }
            val i = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE)
            if (uris.size == 1) {
                i.putExtra(Intent.EXTRA_STREAM, uris[0])
            } else {
                i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
            i.type = Core.shareType(items)
            i.clipData = Core.buildClip(items)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "Share files"))
            Core.afterShare(items)
        } catch (e: Exception) {
            toast("Share failed: " + e.message)
        }
    }

    // ---------- Floating always-on-top mode ----------

    private fun startFloat() {
        if (Core.treeUri == null) {
            toast("Pick a folder first")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("Turn on \"Display over other apps\" for this app, come back, then tap Float on top")
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName))
                )
            } catch (e: Exception) {
                toast("Open Settings > Apps > this app > Display over other apps")
            }
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return
        }
        launchFloat()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) {
            launchFloat()
        }
    }

    private fun launchFloat() {
        try {
            startForegroundService(Intent(this, FloatService::class.java))
            moveTaskToBack(true)
        } catch (e: Exception) {
            toast("Could not start floating panel: " + e.message)
        }
    }

    private fun stopFloat() {
        stopService(Intent(this, FloatService::class.java))
    }

    // ---------- Keyboard / mouse ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && currentFocus !is EditText) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_C -> {
                    report(Core.copyBatch())
                    return true
                }
                KeyEvent.KEYCODE_O -> {
                    report(Core.copyOne())
                    return true
                }
                KeyEvent.KEYCODE_S -> {
                    shareBatch()
                    return true
                }
                KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_DPAD_LEFT -> {
                    Core.prevBatch()
                    return true
                }
                KeyEvent.KEYCODE_R -> {
                    Core.resetAll()
                    return true
                }
                KeyEvent.KEYCODE_F -> {
                    startFloat()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL) {
            val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val h = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            val d: Float = if (h != 0f) h else v
            stripScroll.scrollBy((-d * dp(60)).toInt(), 0)
            return true
        }
        return super.onGenericMotionEvent(event)
    }
}

// Floating panel that stays on top of every app. Drag the top bar to move, drag the bottom-right corner to resize.
class FloatService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.myapp.STOP_FLOAT"
        const val CH = "floatpanel"
        const val NID = 7
    }

    private var wm: WindowManager? = null
    private var panel: View? = null
    private var statusTv: TextView? = null
    private val ui = Runnable { updateStatus() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Core.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startFg()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (panel == null) {
            createPanel()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Core.removeListener(ui)
        try {
            val p = panel
            if (p != null) wm?.removeView(p)
        } catch (e: Exception) {
        }
        panel = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun startFg() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH, "Floating panel", NotificationManager.IMPORTANCE_LOW))
        val fl = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), fl)
        val stopI = Intent(this, FloatService::class.java)
        stopI.action = ACTION_STOP
        val stop = PendingIntent.getService(this, 1, stopI, fl)
        val n = Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle("File Batch Copier is floating on top")
            .setContentText("Tap to open the app. Tap Stop to close the floating panel.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NID, n)
        }
    }

    private fun dp(v: Int): Int {
        return (v * resources.displayMetrics.density).toInt()
    }

    private fun report(err: String?) {
        if (err != null) Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
    }

    private fun updateStatus() {
        statusTv?.text = Core.statusText(true)
    }

    private fun chip(text: String, color: String, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(Color.WHITE)
        t.textSize = 12f
        t.gravity = Gravity.CENTER
        t.maxLines = 1
        t.setPadding(dp(6), dp(9), dp(6), dp(9))
        val g = GradientDrawable()
        g.setColor(Color.parseColor(color))
        g.cornerRadius = dp(6).toFloat()
        t.background = g
        t.isClickable = true
        t.setOnClickListener { onClick() }
        return t
    }

    private fun row(vararg v: View): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        for (x in v) {
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(2), dp(2), dp(2), dp(2))
            r.addView(x, lp)
        }
        return r
    }

    private fun savePanel(p: WindowManager.LayoutParams) {
        getSharedPreferences("imgbatch", Context.MODE_PRIVATE).edit()
            .putInt("fx", p.x).putInt("fy", p.y).putInt("fw", p.width).putInt("fh", p.height).apply()
    }

    private fun createPanel() {
        val wmgr = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = wmgr
        val sp = getSharedPreferences("imgbatch", Context.MODE_PRIVATE)
        val dm = resources.displayMetrics
        val minW = dp(170)
        val minH = dp(120)
        val w = sp.getInt("fw", dp(300)).coerceIn(minW, maxOf(minW, dm.widthPixels))
        val h = sp.getInt("fh", dp(240)).coerceIn(minH, maxOf(minH, dm.heightPixels))
        val p = WindowManager.LayoutParams(
            w,
            h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = sp.getInt("fx", dp(16)).coerceIn(0, maxOf(0, dm.widthPixels - w))
        p.y = sp.getInt("fy", dp(100)).coerceIn(0, maxOf(0, dm.heightPixels - h))

        val root = FrameLayout(this)
        val rootBg = GradientDrawable()
        rootBg.setColor(Color.parseColor("#F2202124"))
        rootBg.cornerRadius = dp(10).toFloat()
        rootBg.setStroke(dp(1), Color.parseColor("#5C6BC0"))
        root.background = rootBg

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        // Top bar: drag here to move
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        val barBg = GradientDrawable()
        barBg.setColor(Color.parseColor("#3949AB"))
        val rr = dp(10).toFloat()
        barBg.cornerRadii = floatArrayOf(rr, rr, rr, rr, 0f, 0f, 0f, 0f)
        bar.background = barBg
        bar.setPadding(dp(8), dp(3), dp(4), dp(3))
        val title = TextView(this)
        title.text = "File copier (drag here)"
        title.setTextColor(Color.WHITE)
        title.textSize = 12f
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        bar.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val openB = chip("Open", "#00695C") {
            val i = Intent(this, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(i)
            } catch (e: Exception) {
            }
        }
        val closeB = chip("X", "#B71C1C") { stopSelf() }
        val lpOpen = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lpOpen.setMargins(dp(2), 0, dp(2), 0)
        bar.addView(openB, lpOpen)
        val lpClose = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lpClose.setMargins(dp(2), 0, dp(2), 0)
        bar.addView(closeB, lpClose)
        col.addView(
            bar,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        // Body (scrolls, so every button stays reachable even when the panel is small)
        val scroll = ScrollView(this)
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(6), dp(4), dp(6), dp(32))
        val st = TextView(this)
        st.setTextColor(Color.WHITE)
        st.textSize = 12f
        st.setPadding(dp(2), dp(2), dp(2), dp(4))
        statusTv = st
        body.addView(st)
        body.addView(
            row(
                chip("Copy batch", "#2E7D32") { report(Core.copyBatch()) },
                chip("Copy one", "#00838F") { report(Core.copyOne()) }
            )
        )
        body.addView(
            row(
                chip("Prev", "#5D4037") { Core.prevBatch() },
                chip("Reset", "#6A1B9A") { Core.resetAll() },
                chip("Refresh", "#455A64") { Core.reload() }
            )
        )
        body.addView(
            row(
                chip("Batch -", "#37474F") { Core.changeBatch(Core.batch - 1) },
                chip("Batch +", "#37474F") { Core.changeBatch(Core.batch + 1) }
            )
        )
        scroll.addView(body)
        col.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Resize handle: bottom-right corner
        val handle = object : View(this@FloatService) {
            private val paint = Paint()

            init {
                paint.color = Color.WHITE
                paint.strokeWidth = dp(2).toFloat()
                paint.isAntiAlias = true
            }

            override fun onDraw(canvas: Canvas) {
                val ww = width.toFloat()
                val hh = height.toFloat()
                for (i in 1..3) {
                    val o = ww * i / 4f
                    canvas.drawLine(ww - o, hh - 2f, ww - 2f, hh - o, paint)
                }
            }
        }
        handle.setBackgroundColor(Color.parseColor("#33FFFFFF"))
        root.addView(handle, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.BOTTOM or Gravity.END))

        bar.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f
            var sy = 0f
            var ox = 0
            var oy = 0
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = e.rawX
                        sy = e.rawY
                        ox = p.x
                        oy = p.y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val d = resources.displayMetrics
                        p.x = (ox + (e.rawX - sx).toInt()).coerceIn(0, maxOf(0, d.widthPixels - p.width))
                        p.y = (oy + (e.rawY - sy).toInt()).coerceIn(0, maxOf(0, d.heightPixels - p.height))
                        try {
                            wmgr.updateViewLayout(root, p)
                        } catch (ex: Exception) {
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> savePanel(p)
                }
                return true
            }
        })

        handle.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f
            var sy = 0f
            var ow = 0
            var oh = 0
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = e.rawX
                        sy = e.rawY
                        ow = p.width
                        oh = p.height
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val d = resources.displayMetrics
                        p.width = (ow + (e.rawX - sx).toInt()).coerceIn(minW, maxOf(minW, d.widthPixels - p.x))
                        p.height = (oh + (e.rawY - sy).toInt()).coerceIn(minH, maxOf(minH, d.heightPixels - p.y))
                        try {
                            wmgr.updateViewLayout(root, p)
                        } catch (ex: Exception) {
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> savePanel(p)
                }
                return true
            }
        })

        try {
            wmgr.addView(root, p)
            panel = root
        } catch (e: Exception) {
            Toast.makeText(this, "Could not show floating panel: " + e.message, Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        Core.addListener(ui)
        updateStatus()
    }
}
