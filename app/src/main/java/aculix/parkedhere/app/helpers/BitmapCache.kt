package aculix.parkedhere.app.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.collection.LruCache
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat

/**
 * A utility class which caches the Bitmap using LRU cache and hence
 * can be used to reuse the Map marker
 */
class BitmapCache(
    val context: Context,
    val size: Int // How many different TYPES of markers do you have?
) {
    private val cache = LruCache<Int, Bitmap>(size)

    // a quick and nasty little hash function
    @Suppress("MagicNumber") // ktlint/detekt exception
    private fun hash(drawable: Int, color: Int? = null): Int {
        var hash = 17
        hash = hash * 31 + drawable
        hash = hash * 31 + (color ?: 0)
        return hash
    }

    fun getBitmap(
        @DrawableRes drawable: Int,
        @ColorRes tintColor: Int? = null
    ): Bitmap? {
        // each drawable/tint combination needs it's own record in the cache
        // For example,
        // a red car marker (R.drawable.ic_car, R.color.red)
        // and a blue car marker (R.drawable.ic_car, R.color.blue)
        // and a green car marker (R.drawable.ic_car, R.color.green)
        // are all different bitmaps, but all created with the same drawable
        val currentHash = hash(drawable, tintColor ?: 0)

        if (cache[currentHash] == null) {
            // if it's not in the cache, create it
            drawable.toBitmap(context, tintColor)?.let {
                // then add it to the cache
                cache.put(currentHash, it)
            }
        }
        return cache[currentHash]
    }

    /**
     * Converts a Vector drawable to a Bitmap
     *
     * @param tintColor Tint color to be applied to the Vector drawable
     *
     *@return Converted Bitmap
     */
   private fun Int.toBitmap(context: Context, @ColorRes tintColor: Int? = null): Bitmap? {

        // retrieve the actual drawable
        val drawable = ContextCompat.getDrawable(context, this) ?: return null
        drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        val bm = Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)

        // add the tint if it exists
        tintColor?.let {
            DrawableCompat.setTint(drawable, ContextCompat.getColor(context, it))
        }
        // draw it onto the bitmap
        val canvas = Canvas(bm)
        drawable.draw(canvas)
        return bm
    }
}
