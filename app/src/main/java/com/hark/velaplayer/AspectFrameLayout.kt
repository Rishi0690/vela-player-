package com.hark.velaplayer

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/** A frame that is always 16:9 (width decides height), so grid thumbnails line up. */
class AspectFrameLayout @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : FrameLayout(c, a) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(w * 9 / 16, MeasureSpec.EXACTLY))
    }
}
