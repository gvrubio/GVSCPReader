package com.gv.scpreader

import android.content.Context
import android.util.AttributeSet
import android.webkit.WebView

/** WebView that reports how far through the page the reader is (0..1). */
class ReaderWebView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    WebView(context, attrs) {

    var onScrollFraction: ((Float) -> Unit)? = null

    fun scrollFraction(): Float {
        val range = contentHeight * resources.displayMetrics.density - height
        return if (range <= 1f) 1f else (scrollY / range).coerceIn(0f, 1f)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        onScrollFraction?.invoke(scrollFraction())
    }
}
