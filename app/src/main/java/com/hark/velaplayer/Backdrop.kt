package com.hark.velaplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.net.Uri
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * v2.18 background photos: ONE app-wide backdrop system on top of [Atmosphere].
 *
 * When the user picks a photo (built-in or their own), [Atmosphere.applyTo] / [Atmosphere.viewBackground] draw a
 * [BackdropDrawable] instead of the plain atmosphere, so every themed screen (Home, folders, playlists, search,
 * Settings, Theme editor, Vault, Widgets, Focus, pickers) shows the same photo with the same appearance. The player
 * never uses it (live video). Glass panels, sheets, dialogs and menus keep their own translucent glass on top.
 *
 * Prefs (persist across restarts): `bg_source` ("" = theme atmosphere, "b:<id>" built-in, "custom"), `bg_style`
 * ("clear" | "glass"), `bg_blur` 0–100, `bg_dim` 0–90, `bg_custom_stamp` (cache buster), `bg_version` (bumped on
 * every change; [ThemedActivity] recreates stale screens on resume).
 *
 * Readability: a theme-tinted scrim (dark: deep atmosphere ink, light: frosted white) whose strength is the user's
 * Dim value but never less than what the photo's own brightness needs, plus a slightly stronger band under the
 * toolbar and above the navigation bar. Photos are centre-cropped to the window (never stretched), processed once and
 * cached in memory (blurred glass = downscaled + stack blur, drawn back up with filtering: cheap and smooth).
 */
object Backdrop {
    class Builtin(val id: String, val label: String, val res: Int, val original: Boolean)

    val BUILTINS = listOf(
        Builtin("vela_prism", "Vela Prism", R.drawable.bgp_vela_prism, true),
        Builtin("vela_aurora", "Vela Aurora", R.drawable.bgp_vela_aurora, true),
        Builtin("vela_velvet", "Vela Velvet", R.drawable.bgp_vela_velvet, true),
        Builtin("vela_gold", "Vela Gold", R.drawable.bgp_vela_gold, true),
        Builtin("vela_nebula", "Vela Nebula", R.drawable.bgp_vela_nebula, true),
        Builtin("frost_glass", "Frost Glass", R.drawable.bgp_frost_glass, true),
        Builtin("cinema", "Cinema", R.drawable.bgp_cinema, false),
        Builtin("neon_rain", "Neon Rain", R.drawable.bgp_neon_rain, false),
        Builtin("misty_peaks", "Misty Peaks", R.drawable.bgp_misty_peaks, false),
        Builtin("ocean_dusk", "Ocean Dusk", R.drawable.bgp_ocean_dusk, false),
        Builtin("desert_night", "Desert Night", R.drawable.bgp_desert_night, false),
        Builtin("sakura", "Sakura", R.drawable.bgp_sakura, false),
    )

    const val STYLE_CLEAR = "clear"
    const val STYLE_GLASS = "glass"
    const val SRC_CUSTOM = "custom"
    const val DEFAULT_BLUR = 55
    const val DEFAULT_DIM = 35

    data class Spec(val source: String, val style: String, val blur: Int, val dim: Int, val stamp: Long) {
        val glass get() = style == STYLE_GLASS
        val key get() = "$source|$style|${if (glass) blur else 0}|$stamp"
    }

    fun spec(c: Context): Spec {
        val sp = Prefs.sp(c)
        return Spec(sp.getString("bg_source", "") ?: "", sp.getString("bg_style", STYLE_GLASS) ?: STYLE_GLASS,
            sp.getInt("bg_blur", DEFAULT_BLUR).coerceIn(0, 100), sp.getInt("bg_dim", DEFAULT_DIM).coerceIn(0, 90),
            sp.getLong("bg_custom_stamp", 0L))
    }

    fun version(c: Context) = Prefs.sp(c).getInt("bg_version", 0)

    /** Saves one or more settings and bumps the version so open screens pick it up. */
    fun update(c: Context, source: String? = null, style: String? = null, blur: Int? = null, dim: Int? = null, stamp: Long? = null) {
        val e = Prefs.sp(c).edit()
        source?.let { e.putString("bg_source", it) }
        style?.let { e.putString("bg_style", it) }
        blur?.let { e.putInt("bg_blur", it.coerceIn(0, 100)) }
        dim?.let { e.putInt("bg_dim", it.coerceIn(0, 90)) }
        stamp?.let { e.putLong("bg_custom_stamp", it) }
        e.putInt("bg_version", version(c) + 1).apply()
    }

    fun customFile(c: Context) = File(File(c.filesDir, "backdrop"), "custom.jpg")
    fun hasCustom(c: Context) = customFile(c).let { it.exists() && it.length() > 0 }

    // v2.18.1: several gallery photos. Legacy single photo = source "custom" (custom.jpg); new ones = "c:<file name>".
    private fun dir(c: Context) = File(c.filesDir, "backdrop")
    fun isCustom(source: String) = source == SRC_CUSTOM || source.startsWith("c:")
    fun fileFor(c: Context, source: String): File? = when {
        source == SRC_CUSTOM -> customFile(c)
        source.startsWith("c:") -> source.removePrefix("c:").takeIf { it.isNotEmpty() && !it.contains('/') }?.let { File(dir(c), it) }
        else -> null
    }?.takeIf { it.exists() && it.length() > 0 }

    /** Imported photos, newest first. */
    fun customs(c: Context): List<String> {
        val out = ArrayList<Pair<Long, String>>()
        customFile(c).takeIf { it.exists() && it.length() > 0 }?.let { out += it.lastModified() to SRC_CUSTOM }
        dir(c).listFiles()?.filter { it.name.startsWith("c_") && it.name.endsWith(".jpg") && it.length() > 0 }?.forEach { out += it.lastModified() to "c:" + it.name }
        return out.sortedByDescending { it.first }.map { it.second }
    }

    /**
     * Deletes an imported photo. If it is the active background, switches to the default theme background first
     * (appearance settings kept), so no screen ever points at a missing file.
     */
    fun deleteCustom(c: Context, source: String): Boolean {
        if (!isCustom(source)) return false
        if (spec(c).source == source) update(c, source = "")
        synchronized(lock) {
            if (srcCacheKey?.startsWith("$source|") == true) { srcCacheKey = null; srcCache = null }
            if (cacheKey?.startsWith("$source|") == true) { cacheKey = null; cache = null }
        }
        val f = fileFor(c, source) ?: return true
        return f.delete() || !f.exists()
    }

    /** Restore to default (v2.18.2): ONLY blur and dim go back to their defaults; photo, style and theme are kept. */
    fun restoreDefaults(c: Context) = update(c, blur = DEFAULT_BLUR, dim = DEFAULT_DIM)
    fun builtin(id: String) = BUILTINS.firstOrNull { it.id == id }

    /** A photo is selected and still available. */
    fun active(c: Context): Boolean {
        val s = spec(c).source
        return when {
            s.isEmpty() -> false
            isCustom(s) -> fileFor(c, s) != null
            s.startsWith("b:") -> builtin(s.removePrefix("b:")) != null
            else -> false
        }
    }

    // ------------------------------------------------------------------ processing + cache

    class Processed(val bitmap: Bitmap, val luminance: Float)

    private val lock = Any()
    private var cacheKey: String? = null
    private var cache: Processed? = null
    // Decoded 720 px source for Blurred glass, so moving the blur slider only re-blurs (never re-decodes).
    private var srcCacheKey: String? = null
    private var srcCache: Bitmap? = null

    /** The processed photo for the current settings (cached; first call decodes, later calls are free). */
    fun processed(c: Context): Processed? {
        val s = spec(c)
        if (!active(c)) return null
        synchronized(lock) {
            val (sw, sh) = screenSize(c)
            val key = s.key + "|" + sw + "x" + sh
            if (cacheKey == key) cache?.let { return it }
            cache = null // release the old photo before decoding the new one
            val p = try { process(c.applicationContext, s) } catch (_: Throwable) { null } ?: return null
            cacheKey = key; cache = p
            return p
        }
    }

    /** Warms the cache off the main thread (app start, after a change). */
    fun prewarm(c: Context) { val app = c.applicationContext; Thread { processed(app) }.start() }

    /** EXIF rotation of an imported photo (built-ins are stored upright). */
    private fun rotationOf(c: Context, s: Spec): Int {
        if (!isCustom(s.source)) return 0
        val f = fileFor(c, s.source) ?: return 0
        return try {
            when (android.media.ExifInterface(f.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90, android.media.ExifInterface.ORIENTATION_TRANSPOSE -> 90
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                android.media.ExifInterface.ORIENTATION_ROTATE_270, android.media.ExifInterface.ORIENTATION_TRANSVERSE -> 270
                else -> 0
            }
        } catch (_: Exception) { 0 }
    }

    private fun open(c: Context, s: Spec, opts: BitmapFactory.Options): Bitmap? =
        if (isCustom(s.source)) fileFor(c, s.source)?.let { BitmapFactory.decodeFile(it.path, opts) }
        else builtin(s.source.removePrefix("b:"))?.let { BitmapFactory.decodeResource(c.resources, it.res, opts) }

    private fun rotate(b: Bitmap, deg: Int): Bitmap {
        if (deg == 0) return b
        val out = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
        if (out !== b) b.recycle()
        return out
    }

    /** Upright photo with its long side at least [maxSide] (power-of-two subsampling only, never below it). */
    private fun decodeSource(c: Context, s: Spec, maxSide: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(c, s, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var sample = 1
        while (max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val b = open(c, s, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false }) ?: return null
        return rotate(b, rotationOf(c, s))
    }

    /** Full physical screen size in the current orientation (the backdrop runs under the transparent bars). */
    fun screenSize(c: Context): Pair<Int, Int> {
        val dm = android.util.DisplayMetrics()
        var w = 0; var h = 0
        // v2.20: the window is edge to edge, so the backdrop covers the whole window including the bars. Android 11+:
        // WindowMetrics (Display.getRealMetrics is deprecated and only valid from a visual context); older: real metrics.
        if (android.os.Build.VERSION.SDK_INT >= 30) try {
            val b = (c.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).maximumWindowMetrics.bounds
            w = b.width(); h = b.height()
        } catch (_: Exception) { }
        if (w <= 0 || h <= 0) try {
            @Suppress("DEPRECATION")
            (c.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(dm)
            w = dm.widthPixels; h = dm.heightPixels
        } catch (_: Exception) { }
        if (w <= 0 || h <= 0) { w = c.resources.displayMetrics.widthPixels; h = c.resources.displayMetrics.heightPixels }
        val portrait = c.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
        return if (portrait) Pair(min(w, h), max(w, h)) else Pair(max(w, h), min(w, h))
    }

    /**
     * Clear mode: the photo at the screen's real resolution. Decodes only as much as needed (power-of-two subsample
     * that never drops below the cover size), then ONE high-quality filtered scale + centre crop to exactly the screen
     * (aspect preserved, no stretching, no gaps). Small photos are kept as they are and only scaled up when drawn.
     */
    private fun processClear(c: Context, s: Spec): Bitmap? {
        val (sw, sh) = screenSize(c)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(c, s, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        val rot = rotationOf(c, s)
        val bw = if (rot % 180 == 0) o.outWidth else o.outHeight
        val bh = if (rot % 180 == 0) o.outHeight else o.outWidth
        val cover = max(sw.toFloat() / bw, sh.toFloat() / bh)
        var sample = 1
        if (cover < 1f) while (sample * 2 <= (1f / cover)) sample *= 2
        // Keep the decode within a safe bitmap size (very large panoramas).
        while ((o.outWidth / sample).toLong() * (o.outHeight / sample) > 36_000_000L) sample *= 2
        val dec = rotate(open(c, s, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false }) ?: return null, rot)
        val k = max(sw.toFloat() / dec.width, sh.toFloat() / dec.height)
        if (k >= 1f) {
            // Smaller than the screen: crop to the screen's aspect at native resolution; the drawable scales it up.
            val cw = min(dec.width, (sw / k).roundToInt().coerceAtLeast(1)); val ch = min(dec.height, (sh / k).roundToInt().coerceAtLeast(1))
            if (cw == dec.width && ch == dec.height) return dec
            val out = Bitmap.createBitmap(dec, (dec.width - cw) / 2, (dec.height - ch) / 2, cw, ch)
            if (out !== dec) dec.recycle()
            return out
        }
        val cw = min(dec.width, (sw / k).roundToInt()); val ch = min(dec.height, (sh / k).roundToInt())
        val out = Bitmap.createBitmap(dec, (dec.width - cw) / 2, (dec.height - ch) / 2, cw, ch, Matrix().apply { setScale(k, k) }, true)
        if (out !== dec) dec.recycle()
        return out
    }

    private fun process(c: Context, s: Spec): Processed? {
        if (!s.glass) {
            val full = processClear(c, s) ?: return null
            return Processed(full, luminance(full))
        }
        val srcKey = "${s.source}|${s.stamp}"
        val src = (if (srcCacheKey == srcKey) srcCache else null) ?: (decodeSource(c, s, 720) ?: return null).also { srcCacheKey = srcKey; srcCache = it }
        val t = s.blur / 100f
        // Stronger blur = smaller working copy + larger radius. Drawn back up with bilinear filtering.
        val targetLong = (220 - 150 * t).roundToInt().coerceIn(48, 240)
        val scale = targetLong.toFloat() / max(src.width, src.height)
        val small = Bitmap.createScaledBitmap(src, max(1, (src.width * scale).roundToInt()), max(1, (src.height * scale).roundToInt()), true)
        val radius = (2 + 12 * t).roundToInt()
        val blurred = if (s.blur <= 0) small else stackBlur(small, radius).also { if (small !== src) small.recycle() }
        return Processed(blurred, luminance(blurred))
    }

    private fun luminance(b: Bitmap): Float {
        val w = min(32, b.width); val h = min(32, b.height)
        val tiny = Bitmap.createScaledBitmap(b, w, h, true)
        val px = IntArray(w * h); tiny.getPixels(px, 0, w, 0, 0, w, h)
        if (tiny !== b) tiny.recycle()
        var sum = 0.0
        for (p in px) sum += (0.2126 * Color.red(p) + 0.7152 * Color.green(p) + 0.0722 * Color.blue(p)) / 255.0
        return (sum / px.size).toFloat()
    }

    /** Classic stack blur (Mario Klingemann), in place on a mutable copy. Fast on the small working bitmap. */
    private fun stackBlur(sentBitmap: Bitmap, radius: Int): Bitmap {
        val bitmap = sentBitmap.copy(Bitmap.Config.ARGB_8888, true)
        if (radius < 1) return bitmap
        val w = bitmap.width; val h = bitmap.height
        val pix = IntArray(w * h); bitmap.getPixels(pix, 0, w, 0, 0, w, h)
        val wm = w - 1; val hm = h - 1; val wh = w * h; val div = radius + radius + 1
        val r = IntArray(wh); val g = IntArray(wh); val bl = IntArray(wh)
        var rsum: Int; var gsum: Int; var bsum: Int; var x: Int; var y: Int; var i: Int; var p: Int; var yp: Int; var yi: Int; var yw: Int
        val vmin = IntArray(max(w, h))
        var divsum = (div + 1) shr 1; divsum *= divsum
        val dv = IntArray(256 * divsum); i = 0
        while (i < 256 * divsum) { dv[i] = i / divsum; i++ }
        yw = 0; yi = 0
        val stack = Array(div) { IntArray(3) }
        var stackpointer: Int; var stackstart: Int; var sir: IntArray; var rbs: Int; val r1 = radius + 1
        var routsum: Int; var goutsum: Int; var boutsum: Int; var rinsum: Int; var ginsum: Int; var binsum: Int
        y = 0
        while (y < h) {
            bsum = 0; gsum = 0; rsum = 0; boutsum = 0; goutsum = 0; routsum = 0; binsum = 0; ginsum = 0; rinsum = 0
            i = -radius
            while (i <= radius) {
                p = pix[yi + min(wm, max(i, 0))]
                sir = stack[i + radius]
                sir[0] = (p and 0xff0000) shr 16; sir[1] = (p and 0x00ff00) shr 8; sir[2] = p and 0x0000ff
                rbs = r1 - Math.abs(i)
                rsum += sir[0] * rbs; gsum += sir[1] * rbs; bsum += sir[2] * rbs
                if (i > 0) { rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2] } else { routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2] }
                i++
            }
            stackpointer = radius
            x = 0
            while (x < w) {
                r[yi] = dv[rsum]; g[yi] = dv[gsum]; bl[yi] = dv[bsum]
                rsum -= routsum; gsum -= goutsum; bsum -= boutsum
                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]
                routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]
                if (y == 0) vmin[x] = min(x + radius + 1, wm)
                p = pix[yw + vmin[x]]
                sir[0] = (p and 0xff0000) shr 16; sir[1] = (p and 0x00ff00) shr 8; sir[2] = p and 0x0000ff
                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                rsum += rinsum; gsum += ginsum; bsum += binsum
                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer % div]
                routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2]
                yi++; x++
            }
            yw += w; y++
        }
        x = 0
        while (x < w) {
            bsum = 0; gsum = 0; rsum = 0; boutsum = 0; goutsum = 0; routsum = 0; binsum = 0; ginsum = 0; rinsum = 0
            yp = -radius * w
            i = -radius
            while (i <= radius) {
                yi = max(0, yp) + x
                sir = stack[i + radius]
                sir[0] = r[yi]; sir[1] = g[yi]; sir[2] = bl[yi]
                rbs = r1 - Math.abs(i)
                rsum += r[yi] * rbs; gsum += g[yi] * rbs; bsum += bl[yi] * rbs
                if (i > 0) { rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2] } else { routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2] }
                if (i < hm) yp += w
                i++
            }
            yi = x; stackpointer = radius
            y = 0
            while (y < h) {
                pix[yi] = (0xff000000.toInt() and pix[yi]) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]
                rsum -= routsum; gsum -= goutsum; bsum -= boutsum
                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]
                routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]
                if (x == 0) vmin[y] = min(y + r1, hm) * w
                p = x + vmin[y]
                sir[0] = r[p]; sir[1] = g[p]; sir[2] = bl[p]
                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                rsum += rinsum; gsum += ginsum; bsum += binsum
                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer]
                routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2]
                yi += w; y++
            }
            x++
        }
        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
        return bitmap
    }

    // ------------------------------------------------------------------ custom photo import

    /** Copies a gallery photo into private storage (downscaled to ≤1600 px, EXIF rotation applied). Run off the main thread. */
    /**
     * v2.18.2: copies the gallery photo into private storage BYTE FOR BYTE (no re-encode, no downscale), so Clear mode
     * shows the original quality. Rotation comes from its EXIF at display time. Run off the main thread.
     */
    fun importCustom(c: Context, uri: Uri): String? {
        val d = dir(c); d.mkdirs()
        val tmp = File(d, "import.tmp")
        return try {
            c.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } } ?: return null
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.path, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) { tmp.delete(); return null }
            val name = "c_${System.currentTimeMillis()}.jpg" // name only; BitmapFactory reads any format it supports
            val f = File(d, name)
            if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
            "c:$name"
        } catch (_: Throwable) { tmp.delete(); null }
    }

    /** Small thumbnail for the picker tiles. */
    fun thumb(c: Context, source: String, maxSide: Int = 360): Bitmap? = try {
        decodeSource(c, Spec(source, STYLE_CLEAR, 0, 0, 0L), maxSide)
    } catch (_: Throwable) { null }
}

/**
 * The photo backdrop drawn behind a whole window (or, aligned with [align], behind an opaque animated layer that must
 * continue it seamlessly). Centre-crops the photo to the WINDOW frame, so it never stretches and never leaves gaps.
 */
class BackdropDrawable(c: Context, private val atmo: Atmosphere.Atmo, private val photo: Backdrop.Processed, private val spec: Backdrop.Spec) : Drawable() {
    private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
        if (spec.glass) colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.18f) })
    }
    private val scrim = Paint(Paint.DITHER_FLAG)
    private val bands = Paint(Paint.DITHER_FLAG)
    private val grain = Paint().apply { shader = BitmapShader(AtmosphereDrawable.grainBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
    private val m = Matrix()
    private var offsetY = 0
    private var frameH = 0
    private val density = c.resources.displayMetrics.density

    /**
     * v2.18.2: the photo alone controls the background. The scrim is NEUTRAL (black in dark mode, white in light mode;
     * never the theme's atmosphere colours or glows) and exactly the user's Dim, so Dim 0 shows the photo untouched.
     */
    private val scrimAlpha: Float = (spec.dim / 100f).coerceIn(0f, 0.9f)
    private val scrimColor: Int = if (atmo.dark) Color.BLACK else Color.WHITE

    fun align(offset: Int, windowHeight: Int) { offsetY = offset; frameH = windowHeight; onBoundsChange(bounds); invalidateSelf() }

    override fun onBoundsChange(b: Rect) {
        val w = b.width().toFloat().coerceAtLeast(1f)
        val h = (if (frameH > 0) frameH else b.height()).toFloat().coerceAtLeast(1f)
        val bw = photo.bitmap.width.toFloat(); val bh = photo.bitmap.height.toFloat()
        val s = max(w / bw, h / bh)
        m.reset(); m.setScale(s, s)
        m.postTranslate(b.left + (w - bw * s) / 2f, b.top + (h - bh * s) / 2f - offsetY)
        val oy = -offsetY.toFloat() + b.top
        scrim.color = Atmosphere.alpha(scrimColor, (scrimAlpha * 255).roundToInt())
        // A little extra under the toolbar and above the nav bar, where text and icons sit on the photo.
        // Thin neutral bands under the status bar / above the nav bar for their icons; lighter when Dim is low.
        val edge = Atmosphere.alpha(scrimColor, ((0.10f + 0.20f * (scrimAlpha / 0.9f)) * 255).roundToInt())
        val clear = Atmosphere.alpha(scrimColor, 0)
        bands.shader = LinearGradient(0f, oy, 0f, oy + h, intArrayOf(edge, clear, clear, edge), floatArrayOf(0f, 0.08f, 0.92f, 1f), Shader.TileMode.CLAMP)
    }

    override fun draw(c: Canvas) {
        val b = bounds
        c.save(); c.clipRect(b)
        c.drawBitmap(photo.bitmap, m, photoPaint)
        if (scrimAlpha > 0f) c.drawRect(b, scrim)
        c.drawRect(b, bands)
        if (spec.glass) c.drawRect(b, grain)
        c.restore()
    }

    override fun setAlpha(alpha: Int) { photoPaint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.OPAQUE
}
