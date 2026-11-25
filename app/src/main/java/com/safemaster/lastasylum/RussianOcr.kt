package com.safemaster.lastasylum

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The service must serialize read() and close() on its image worker. */
internal class RussianOcr(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private var api: TessBaseAPI? = null
    private var closed = false

    private fun engine(): TessBaseAPI {
        check(!closed) { "Русский OCR уже остановлен" }
        api?.let { return it }
        val root = File(app.filesDir, "tessdata-fast-4.1.0")
        val data = File(root, "tessdata")
        check(data.isDirectory || data.mkdirs()) { "Не удалось создать каталог моделей OCR" }
        for (lang in listOf("rus", "eng")) {
            val file = File(data, "$lang.traineddata")
            if (file.isFile && file.length() > 0L) continue
            val temp = File.createTempFile(lang, ".part", data)
            try {
                app.assets.open("tessdata/${file.name}").use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
                check(temp.length() > 0 && temp.renameTo(file)) { "Не удалось распаковать модель $lang" }
            } finally {
                temp.delete()
            }
        }
        val created = TessBaseAPI()
        try {
            check(created.init(root.absolutePath, "rus+eng")) { "Не удалось открыть модели rus+eng" }
            created.pageSegMode = TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
            api = created
            return created
        } catch (e: Throwable) {
            created.recycle()
            throw e
        }
    }

    /** Returns recognized lines in original screenshot coordinates. The caller owns bitmap. */
    fun read(bitmap: Bitmap, region: Rect? = null): List<MapText> {
        val bounds = Rect(0, 0, bitmap.width, bitmap.height)
        val area = region?.let(::Rect) ?: Rect(bounds)
        if (!area.intersect(bounds)) return emptyList()

        // Small map labels benefit from enlargement. Bound the work on tablets and high-DPI phones.
        val preferred = if (region != null) 3.0 else if (bitmap.width < 1200) 2.0 else 1.0
        val factor = minOf(preferred, sqrt(4_800_000.0 / (area.width().toDouble() * area.height())))
        val w = (area.width() * factor).roundToInt().coerceAtLeast(1)
        val h = (area.height() * factor).roundToInt().coerceAtLeast(1)
        val api = engine()
        val image = if (area == bounds && w == bitmap.width && h == bitmap.height) bitmap
                    else Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            if (image !== bitmap) {
                Canvas(image).drawBitmap(bitmap, area, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
            }
            api.setImage(image)
            if (api.getUTF8Text().isNullOrBlank()) return emptyList()
            val iterator = api.resultIterator ?: return emptyList()
            val lines = mutableListOf<MapText>()
            val level = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
            val scaleX = area.width().toDouble() / w
            val scaleY = area.height().toDouble() / h
            try {
                iterator.begin()
                do {
                    val text = iterator.getUTF8Text(level)?.trim().orEmpty()
                    if (text.isEmpty()) continue
                    val box = iterator.getBoundingRect(level)
                    val original = Rect(
                        area.left + floor(box.left * scaleX).toInt(),
                        area.top + floor(box.top * scaleY).toInt(),
                        area.left + ceil(box.right * scaleX).toInt(),
                        area.top + ceil(box.bottom * scaleY).toInt()
                    )
                    if (original.intersect(area)) {
                        lines.add(MapText(text, original.left, original.top, original.right, original.bottom,
                                          iterator.confidence(level)))
                    }
                } while (iterator.next(level))
            } finally {
                iterator.delete()
            }
            return lines
        } finally {
            api.clear()
            if (image !== bitmap) image.recycle()
        }
    }

    override fun close() {
        closed = true
        api?.recycle()
        api = null
    }
}
