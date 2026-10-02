package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Parcelable
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.viewpager.widget.DirectionalViewPager
import androidx.viewpager.widget.ViewPager
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.GestureDetectorWithLongTap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import tachiyomi.decoder.ImageDecoder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Pager implementation that listens for tap and long tap and allows temporarily disabling touch
 * events in order to work with child views that need to disable touch events on this parent. The
 * pager can also be declared to be vertical by creating it with [isHorizontal] to false.
 */
open class Pager(
    context: Context,
    isHorizontal: Boolean = true,
) : DirectionalViewPager(context, isHorizontal) {

    // KMK -->
    private val horizontal = isHorizontal
    private var pageCurlEnabled = false
    private val pageCurlRenderer = PageCurlRenderer()
    private val snapshotPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val snapshotBounds = RectF()
    private var frontSnapshot: Bitmap? = null
    private var backSnapshot: Bitmap? = null
    // Textures are decoded off the UI thread once the pager settles, so a turn begins with both
    // sheets already in memory instead of paying for a decode inside the first draw of the turn.
    private val textures = HashMap<Int, Bitmap>()
    private val textureLock = Any()
    private var textureGeneration = 0
    private val textureScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var curlState = ViewPager.SCROLL_STATE_IDLE
    private var curlStartItem = 0
    private var curlDirection = 1
    private var curlProgress = 0f
    private var snapshotFailed = false
    private var dragTracked = false
    private var dragReleased = false
    private var releaseProgress = 0f
    private var downX = 0f
    private var downY = 0f
    private var fingerX = 0f
    private var fingerY = 0f

    var pageCurlBackgroundColor = Color.BLACK
        set(value) {
            field = value
            // Textures bake this colour in, so every one of them has to be rebuilt.
            clearTextures()
            invalidate()
        }

    init {
        addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageScrollStateChanged(state: Int) {
                if (curlState == ViewPager.SCROLL_STATE_IDLE) curlStartItem = currentItem
                curlState = state
                if (state == ViewPager.SCROLL_STATE_DRAGGING) dragTracked = true
                if (state == ViewPager.SCROLL_STATE_IDLE) {
                    curlStartItem = currentItem
                    curlProgress = 0f
                    dragTracked = false
                    dragReleased = false
                    snapshotFailed = false
                    invalidateSnapshots()
                    updatePageTransforms()
                    // Decode the neighbours now, while nothing is animating, so the next turn has its
                    // textures ready and does not pay for a decode in its first drawn frame.
                    trimTextures()
                    preloadTextures()
                }
            }

            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                if (curlState == ViewPager.SCROLL_STATE_IDLE) {
                    curlStartItem = currentItem
                    return
                }
                val distance = position + positionOffset - curlStartItem
                val direction = if (distance >= 0f) 1 else -1
                if (direction != curlDirection) invalidateSnapshots()
                curlDirection = direction
                curlProgress = abs(distance).coerceIn(0f, 1f)
                if (pageCurlEnabled) invalidate()
            }
        })
    }

    fun setPageCurlEnabled(enabled: Boolean) {
        val effectiveEnabled = enabled && horizontal
        if (pageCurlEnabled == effectiveEnabled) return
        pageCurlEnabled = effectiveEnabled
        // Keep the visible page's hit area stationary; hidden neighbors cannot take zoom taps.
        setPageTransformer(
            false,
            if (effectiveEnabled) {
                ViewPager.PageTransformer { page, position ->
                    page.translationX = if (snapshotFailed) 0f else -position * page.width
                    page.visibility = if (snapshotFailed || (position > -1f && position < 1f)) {
                        View.VISIBLE
                    } else {
                        View.INVISIBLE
                    }
                }
            } else {
                null
            },
            // Keep pages on a software layer: the curl renders them into a software bitmap.
            // The 2-arg overload defaults this to LAYER_TYPE_HARDWARE, which breaks the snapshot.
            View.LAYER_TYPE_NONE,
        )
        updatePageTransforms()
        if (!effectiveEnabled) clearTextures()
        invalidate()
    }

    private fun updatePageTransforms() {
        for (index in 0 until childCount) {
            val page = getChildAt(index)
            page.translationX = if (pageCurlEnabled && !snapshotFailed) (scrollX - page.left).toFloat() else 0f
            val position = if (width > 0) (page.left - scrollX).toFloat() / width else 0f
            page.visibility = if (!pageCurlEnabled || snapshotFailed || (position > -1f && position < 1f)) {
                View.VISIBLE
            } else {
                View.INVISIBLE
            }
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        // Only a turn that has actually started gets the curl; everything else draws normally.
        if (!pageCurlEnabled || width == 0 || height == 0 ||
            curlProgress <= 0f || curlProgress >= 1f || snapshotFailed
        ) {
            super.dispatchDraw(canvas)
            return
        }
        val outgoingItem = curlStartItem
        val incomingItem = curlStartItem + curlDirection
        // Textures come from the page artwork decoded in software, NOT from the live page views.
        // Those render through hardware bitmaps (SubsamplingScaleImageView.setHardwareConfig),
        // which cannot be drawn into a software bitmap — the draw silently produces nothing.
        try {
            frontSnapshot = textureFor(outgoingItem)
            backSnapshot = textureFor(incomingItem)
        } catch (_: OutOfMemoryError) {
            snapshotFailed = true
            clearTextures()
        }
        val front = frontSnapshot
        val back = backSnapshot
        if (snapshotFailed || front == null || back == null) {
            updatePageTransforms()
            super.dispatchDraw(canvas)
            return
        }
        val reverse = curlDirection < 0
        // A spread pivots about the spine (the view centre) while still carrying the same
        // finger-driven curl a single page gets, so the curl keeps following your finger.
        val spread = isSpread(curlStartItem)
        val hingeX = if (spread) width * 0.5f else -1f
        val bookTilt = if (spread) (curlProgress * PI).toFloat() else 0f
        // Relax the bend over the last third of the turn, so the final frame is an uncurled page and
        // the hand-over to the real view is seamless. Before that the curl is at full strength.
        val curlStrength = if (spread) ((1f - curlProgress) / BOOK_CURL_FADE).coerceIn(0f, 1f) else 1f
        // The curl always works in from the right edge of the screen; the hinge does the turning.
        val originX = width.toFloat()
        val anchor = if (dragTracked) downY.coerceIn(0f, height.toFloat()) else height * 0.85f
        var x = width * (1f - 2.2f * curlProgress)
        var y = anchor + height * 0.08f * sin(curlProgress * PI).toFloat()
        if (dragTracked) {
            // Blend from the edge into the touch point, avoiding a jump for central swipes.
            val attachment = (abs(fingerX - downX) / (width * 0.1f)).coerceIn(0f, 1f)
            val canonicalX = if (reverse) width - fingerX else fingerX
            x = width + (canonicalX - width) * attachment
            y = anchor + (fingerY - anchor) * attachment
            if (dragReleased) {
                val completing = curlProgress >= releaseProgress
                val amount = if (completing) {
                    (curlProgress - releaseProgress) / (1f - releaseProgress).coerceAtLeast(0.001f)
                } else {
                    (releaseProgress - curlProgress) / releaseProgress.coerceAtLeast(0.001f)
                }.coerceIn(0f, 1f)
                val targetX = if (completing) -width * 1.2f else width.toFloat()
                x += (targetX - x) * amount
                y += (anchor - y) * amount
            }
        }
        val saved = canvas.save()
        canvas.translate(scrollX.toFloat(), scrollY.toFloat())
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        snapshotBounds.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawBitmap(back, null, snapshotBounds, snapshotPaint)
        pageCurlRenderer.draw(
            canvas,
            width.toFloat(),
            height.toFloat(),
            anchor,
            originX,
            x,
            y,
            reverse,
            front,
            back,
            hingeX,
            bookTilt,
            curlStrength,
        )
        canvas.restoreToCount(saved)
    }

    /**
     * Texture for [item], from the preload cache when it is there or built on the spot otherwise.
     * The cache owns every bitmap it hands out, so callers must not recycle what they get back.
     */
    private fun textureFor(item: Int): Bitmap? {
        val generation = textureGeneration
        synchronized(textureLock) {
            textures[item]?.takeIf { !it.isRecycled }?.let { return it }
        }
        val built = buildTexture(item) ?: return null
        synchronized(textureLock) {
            if (generation != textureGeneration) {
                // The view was resized or recoloured while this was decoding.
                built.recycle()
                return null
            }
            textures[item]?.takeIf { !it.isRecycled }?.let {
                built.recycle()
                return it
            }
            textures[item] = built
            return built
        }
    }

    /**
     * Builds the curl texture for the page — or double-page spread — at [item].
     *
     * The live page views can't be used: SubsamplingScaleImageView renders through hardware
     * bitmaps, and drawing those into a software bitmap silently produces nothing. Reading the
     * pages' own streams keeps the texture in software and independent of the display pipeline.
     */
    private fun buildTexture(item: Int): Bitmap? {
        val joined = (adapter as? PagerViewerAdapter)?.joinedItems?.getOrNull(item) ?: return null
        val first = (joined.first as? ReaderPage)?.let(::decodeArtwork) ?: return null
        val second = (joined.second as? ReaderPage)?.let(::decodeArtwork)

        // Mirror PagerPageHolder.mergePages(): only two portrait pages are combined. A wide page
        // takes the whole spread on its own, so it stays single.
        val spread = if (second != null && !isWide(first) && !isWide(second)) {
            mergeSpread(first, second).also {
                first.recycle()
                second.recycle()
            }
        } else {
            second?.recycle()
            first
        }
        val texture = fitToTexture(spread)
        spread.recycle()
        return texture
    }

    /** Decodes a page's artwork into a software bitmap sized for the curl texture. */
    private fun decodeArtwork(page: ReaderPage): Bitmap? {
        val openStream = page.stream ?: return null
        var decoder: ImageDecoder? = null
        return try {
            decoder = ImageDecoder.newInstance(openStream(), false, null) ?: return null
            var sampleSize = 1
            val longEdge = max(decoder.width, decoder.height)
            while (longEdge / (sampleSize * 2) >= MAX_TEXTURE_EDGE.toInt()) sampleSize *= 2
            decoder.decode(sampleSize = sampleSize)
        } catch (_: Throwable) {
            null
        } finally {
            decoder?.recycle()
        }
    }

    /**
     * Lays two pages side by side exactly like ImageUtil.mergeBitmaps(), minus the JPEG round
     * trip, so the texture matches what the page view is already showing.
     */
    private fun mergeSpread(first: Bitmap, second: Bitmap): Bitmap {
        val maxHeight = max(first.height, second.height)
        val margin = centerMargin(first.height, second.height)
        val ltr = isLtr
        val result = Bitmap.createBitmap(
            first.width + second.width + margin,
            maxHeight,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(result)
        canvas.drawColor(pageCurlBackgroundColor)
        canvas.drawBitmap(first, null, place(first, if (ltr) 0 else second.width + margin, maxHeight), snapshotPaint)
        canvas.drawBitmap(second, null, place(second, if (ltr) first.width + margin else 0, maxHeight), snapshotPaint)
        return result
    }

    private fun place(bitmap: Bitmap, left: Int, maxHeight: Int): RectF {
        val top = (maxHeight - bitmap.height) / 2f
        return RectF(left.toFloat(), top, left + bitmap.width.toFloat(), top + bitmap.height)
    }

    /** Scales [source] into a freshly allocated texture bitmap sized for this view. */
    private fun fitToTexture(source: Bitmap): Bitmap {
        val scale = minOf(1f, MAX_TEXTURE_EDGE / maxOf(width, height))
        val bitmapWidth = (width * scale).toInt().coerceAtLeast(1)
        val bitmapHeight = (height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(pageCurlBackgroundColor)
        val fit = minOf(bitmapWidth.toFloat() / source.width, bitmapHeight.toFloat() / source.height)
        val drawWidth = source.width * fit
        val drawHeight = source.height * fit
        val left = (bitmapWidth - drawWidth) / 2f
        val top = (bitmapHeight - drawHeight) / 2f
        canvas.drawBitmap(source, null, RectF(left, top, left + drawWidth, top + drawHeight), snapshotPaint)
        return bitmap
    }

    /** True when the position holds two pages, i.e. an open-book spread. */
    private fun isSpread(item: Int): Boolean =
        (adapter as? PagerViewerAdapter)?.joinedItems?.getOrNull(item)?.second != null

    private fun isWide(bitmap: Bitmap): Boolean = bitmap.width > bitmap.height

    /** Page order within a spread, matching PagerPageHolder.mergePages(). */
    private val isLtr: Boolean
        get() {
            val viewer = (adapter as? PagerViewerAdapter)?.viewer ?: return true
            return (viewer !is R2LPagerViewer) xor viewer.config.invertDoublePages
        }

    /** Same rule as PagerPageHolder.calculateCenterMargin(). */
    private fun centerMargin(height1: Int, height2: Int): Int {
        val viewer = (adapter as? PagerViewerAdapter)?.viewer ?: return 0
        return if (
            viewer.config.centerMarginType and PagerConfig.CenterMarginType.DOUBLE_PAGE_CENTER_MARGIN > 0 &&
            !viewer.config.imageCropBorders
        ) {
            96 / (height.coerceAtLeast(1) / max(height1, height2).coerceAtLeast(1)).coerceAtLeast(1)
        } else {
            0
        }
    }

    private companion object {
        /** Longest edge, in pixels, of a curl texture. */
        const val MAX_TEXTURE_EDGE = 1600f

        /**
         * Fraction of a book turn, measured back from the end, over which the curl relaxes to a
         * flat page. Raise it for an earlier, gentler relax; lower it to hold the curl longer.
         */
        const val BOOK_CURL_FADE = 0.35f
    }

    /** Drops the renderer's references. The bitmaps themselves belong to the texture cache. */
    private fun invalidateSnapshots() {
        pageCurlRenderer.clear()
        frontSnapshot = null
        backSnapshot = null
    }

    /** Throws away every cached texture; they are sized and coloured for the current view. */
    private fun clearTextures() {
        pageCurlRenderer.clear()
        synchronized(textureLock) {
            textureGeneration++
            textures.values.forEach { if (!it.isRecycled) it.recycle() }
            textures.clear()
        }
        frontSnapshot = null
        backSnapshot = null
    }

    /** Drops textures for pages no longer within reach of the current one. */
    private fun trimTextures() {
        val start = currentItem
        synchronized(textureLock) {
            textures.keys
                .filter { it < start - 1 || it > start + 1 }
                .forEach { key ->
                    textures.remove(key)?.let { if (!it.isRecycled) it.recycle() }
                }
        }
    }

    /** Decodes the neighbouring pages in the background so the next turn starts without a decode. */
    private fun preloadTextures() {
        if (!pageCurlEnabled || width == 0 || height == 0) return
        val count = adapter?.count ?: 0
        for (item in intArrayOf(currentItem - 1, currentItem, currentItem + 1)) {
            if (item < 0 || item >= count) continue
            if (synchronized(textureLock) { textures[item] != null }) continue
            textureScope.launch { textureFor(item) }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clearTextures()
    }

    override fun onDetachedFromWindow() {
        textureScope.cancel()
        clearTextures()
        super.onDetachedFromWindow()
    }

    private fun trackCurlTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                fingerX = event.x
                fingerY = event.y
                dragTracked = false
                dragReleased = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragReleased && event.pointerCount == 1) {
                    fingerX = event.x
                    fingerY = event.y
                    if (pageCurlEnabled && dragTracked) invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragTracked) {
                    dragReleased = true
                    releaseProgress = curlProgress
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Pinch zoom remains owned by the image view, not by the curl.
                dragTracked = false
                dragReleased = true
            }
        }
    }
    // KMK <--

    /**
     * Tap listener function to execute when a tap is detected.
     */
    var tapListener: ((MotionEvent) -> Unit)? = null

    /**
     * Long tap listener function to execute when a long tap is detected.
     */
    var longTapListener: ((MotionEvent) -> Boolean)? = null

    // SY -->
    var isRestoring = false

    override fun onRestoreInstanceState(state: Parcelable?) {
        isRestoring = true
        val currentItem = currentItem
        super.onRestoreInstanceState(state)
        setCurrentItem(currentItem, false)
        isRestoring = false
    }
    // SY <--

    /**
     * Gesture listener that implements tap and long tap events.
     */
    private val gestureListener = object : GestureDetectorWithLongTap.Listener() {
        override fun onSingleTapConfirmed(ev: MotionEvent): Boolean {
            tapListener?.invoke(ev)
            return true
        }

        override fun onLongTapConfirmed(ev: MotionEvent) {
            val listener = longTapListener
            if (listener != null && listener.invoke(ev)) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }
    }

    /**
     * Gesture detector which handles motion events.
     */
    private val gestureDetector = GestureDetectorWithLongTap(context, gestureListener)

    /**
     * Whether the gesture detector is currently enabled.
     */
    private var isGestureDetectorEnabled = true

    /**
     * Dispatches a touch event.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // KMK -->
        if (horizontal) trackCurlTouch(ev)
        // KMK <--
        val handled = super.dispatchTouchEvent(ev)
        if (isGestureDetectorEnabled) {
            gestureDetector.onTouchEvent(ev)
        }
        return handled
    }

    /**
     * Whether the given [ev] should be intercepted. Only used to prevent crashes when child
     * views manipulate [requestDisallowInterceptTouchEvent].
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return try {
            super.onInterceptTouchEvent(ev)
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /**
     * Handles a touch event. Only used to prevent crashes when child views manipulate
     * [requestDisallowInterceptTouchEvent].
     */
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        return try {
            super.onTouchEvent(ev)
        } catch (e: NullPointerException) {
            false
        } catch (e: IndexOutOfBoundsException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /**
     * Executes the given key event when this pager has focus. Just do nothing because the reader
     * already dispatches key events to the viewer and has more control than this method.
     */
    override fun executeKeyEvent(event: KeyEvent): Boolean {
        // Disable viewpager's default key event handling
        return false
    }

    /**
     * Enables or disables the gesture detector.
     */
    fun setGestureDetectorEnabled(enabled: Boolean) {
        isGestureDetectorEnabled = enabled
    }
}
