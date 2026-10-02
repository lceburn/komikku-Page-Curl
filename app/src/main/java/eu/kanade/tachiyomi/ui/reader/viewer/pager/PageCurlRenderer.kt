package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import kotlin.math.abs

// KMK -->
/** A depth-sorted, lit cylinder mesh with separate artwork on each side of the sheet. */
internal class PageCurlRenderer {
    private val geometry = CurlGeometry()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val silhouette = Path()
    private val points = FloatArray((COLUMNS + 1) * (ROWS + 1) * 4)
    private val triangleBuckets = IntArray(TRIANGLES)
    private val counts = IntArray(BUCKETS)
    private val starts = IntArray(BUCKETS + 1)
    private val cursors = IntArray(BUCKETS)
    private val vertices = FloatArray(TRIANGLES * 6)
    private val textures = FloatArray(TRIANGLES * 6)
    private val colors = IntArray(TRIANGLES * 3)
    private var frontBitmap: Bitmap? = null
    private var backBitmap: Bitmap? = null
    private var frontShader: BitmapShader? = null
    private var backShader: BitmapShader? = null

    fun clear() {
        frontBitmap = null
        backBitmap = null
        frontShader = null
        backShader = null
        paint.shader = null
    }

    /**
     * [originX] is the outer edge of the curling sheet — the view's right edge for a single page,
     * or the spine in a double-page spread. The mesh still spans the full width, so the page on
     * the far side of the crease is left untouched.
     *
     * [hingeX] is negative for a single page, or the spine's x when a spread turns as one page
     * pivoting about it. [bookTilt] is that pivot angle in radians (0 = flat, PI = turned over),
     * and [curlStrength] scales the spread's curl (0 = flat sheet, 1 = full finger-driven curl).
     */
    fun draw(
        canvas: Canvas,
        width: Float,
        height: Float,
        anchorY: Float,
        originX: Float,
        fingerX: Float,
        fingerY: Float,
        reverse: Boolean,
        front: Bitmap,
        back: Bitmap,
        hingeX: Float,
        bookTilt: Float,
        curlStrength: Float,
    ) {
        if (frontBitmap !== front) {
            frontBitmap = front
            frontShader = BitmapShader(front, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        if (backBitmap !== back) {
            backBitmap = back
            backShader = BitmapShader(back, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        // A spread pivots about the spine while still carrying its finger-driven curl.
        if (hingeX >= 0f) {
            geometry.configureBook(hingeX, bookTilt, curlStrength, originX, anchorY, fingerX, fingerY)
        } else {
            geometry.configure(originX, anchorY, fingerX, fingerY)
        }
        for (row in 0..ROWS) {
            for (column in 0..COLUMNS) {
                val offset = (row * (COLUMNS + 1) + column) * 4
                geometry.project(column * width / COLUMNS, row * height / ROWS, points, offset)
                if (reverse) points[offset] = width - points[offset]
            }
        }

        // Project a soft shadow onto the next page before drawing the bent surface.
        silhouette.rewind()
        silhouette.moveTo(points[0], points[1])
        for (column in 1..COLUMNS) outlinePoint(column)
        for (row in 1..ROWS) outlinePoint(row * (COLUMNS + 1) + COLUMNS)
        for (column in COLUMNS - 1 downTo 0) outlinePoint(ROWS * (COLUMNS + 1) + column)
        for (row in ROWS - 1 downTo 1) outlinePoint(row * (COLUMNS + 1))
        silhouette.close()
        for (step in 8 downTo 1) {
            val saved = canvas.save()
            val offset = geometry.shadowScale * step * 0.08f
            canvas.translate(
                geometry.normalX * offset * (if (reverse) -1f else 1f),
                geometry.normalY * offset,
            )
            shadowPaint.color = Color.argb(7, 0, 0, 0)
            canvas.drawPath(silhouette, shadowPaint)
            canvas.restoreToCount(saved)
        }

        counts.fill(0)
        for (triangle in 0 until TRIANGLES) {
            val a = pointIndex(triangle, 0) * 4
            val b = pointIndex(triangle, 1) * 4
            val c = pointIndex(triangle, 2) * 4
            val depth = (points[a + 2] + points[b + 2] + points[c + 2]) / 3f
            val backFacing = points[a + 3] + points[b + 3] + points[c + 3] < 0f
            val level = if (geometry.depthScale > 0f) {
                (depth / (geometry.depthScale * 2f) * (DEPTH_LEVELS - 1)).toInt().coerceIn(0, DEPTH_LEVELS - 1)
            } else {
                0
            }
            val bucket = level * 2 + if (backFacing) 1 else 0
            triangleBuckets[triangle] = bucket
            counts[bucket]++
        }
        starts[0] = 0
        for (bucket in 0 until BUCKETS) {
            starts[bucket + 1] = starts[bucket] + counts[bucket]
            cursors[bucket] = starts[bucket]
        }
        for (triangle in 0 until TRIANGLES) {
            val bucket = triangleBuckets[triangle]
            val output = cursors[bucket]++ * 3
            val backFacing = bucket % 2 == 1
            val bitmap = if (backFacing) back else front
            for (vertex in 0..2) {
                val index = pointIndex(triangle, vertex)
                val point = index * 4
                val destination = (output + vertex) * 2
                vertices[destination] = points[point]
                vertices[destination + 1] = points[point + 1]
                val u = (index % (COLUMNS + 1)).toFloat() / COLUMNS
                // Opposite texture coordinates keep the destination page readable on the back.
                textures[destination] = (if (backFacing != reverse) 1f - u else u) * bitmap.width
                textures[destination + 1] = (index / (COLUMNS + 1)).toFloat() / ROWS * bitmap.height
                val light = (255f * (0.58f + 0.42f * abs(points[point + 3]))).toInt().coerceIn(0, 255)
                colors[output + vertex] = Color.rgb(light, light, light)
            }
        }
        // Batch into depth/side groups instead of making thousands of Canvas calls.
        for (bucket in 0 until BUCKETS) {
            if (counts[bucket] == 0) continue
            paint.shader = if (bucket % 2 == 0) frontShader else backShader
            canvas.drawVertices(
                Canvas.VertexMode.TRIANGLES,
                counts[bucket] * 6,
                vertices,
                starts[bucket] * 6,
                textures,
                starts[bucket] * 6,
                colors,
                starts[bucket] * 3,
                null,
                0,
                0,
                paint,
            )
        }
        paint.shader = null
    }

    private fun outlinePoint(index: Int) {
        silhouette.lineTo(points[index * 4], points[index * 4 + 1])
    }

    private fun pointIndex(triangle: Int, vertex: Int): Int {
        val cell = triangle / 2
        val topLeft = (cell / COLUMNS) * (COLUMNS + 1) + cell % COLUMNS
        return if (triangle % 2 == 0) {
            when (vertex) {
                0 -> topLeft
                1 -> topLeft + 1
                else -> topLeft + COLUMNS + 1
            }
        } else {
            when (vertex) {
                0 -> topLeft + 1
                1 -> topLeft + COLUMNS + 2
                else -> topLeft + COLUMNS + 1
            }
        }
    }

    private companion object {
        const val COLUMNS = 48
        const val ROWS = 32
        const val TRIANGLES = COLUMNS * ROWS * 2
        const val DEPTH_LEVELS = 24
        const val BUCKETS = DEPTH_LEVELS * 2
    }
}
// KMK <--
