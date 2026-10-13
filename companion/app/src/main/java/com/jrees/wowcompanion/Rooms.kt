package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

private fun lp(w: Int, h: Int, weight: Float = 0f) =
    LinearLayout.LayoutParams(w, h).apply { if (weight > 0f) this.weight = weight }

private fun header(c: Context, title: String): TextView = TextView(c).apply {
    text = title
    setTextColor(Theme.gold)
    textSize = 21f
    setTypeface(null, Typeface.BOLD)
    setPadding(20, 14, 20, 12)
    setBackgroundColor(Theme.panel)
}
