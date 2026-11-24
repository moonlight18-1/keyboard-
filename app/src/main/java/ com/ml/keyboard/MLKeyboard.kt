package com.ml.keyboard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.*
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.SparseArray
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/* ============================ IME SERVICE ============================ */
class MLKeyboardService : InputMethodService() {
    private lateinit var prefs: SharedPreferences
    private lateinit var audio: AudioManager
    private var view: KBView? = null
    private val composing = StringBuilder()
    var shift = 0
    private var lastSpace = 0L
    private var lastShift = 0L
    private var editor: EditorInfo? = null

    private val words = arrayOf(
        "the","be","to","of","and","a","in","that","have","I","it","for","not","on","with","he","as","you",
        "do","at","this","but","his","by","from","they","we","say","her","she","or","an","will","my","one",
        "all","would","there","their","what","so","up","out","if","about","who","get","which","go","me",
        "when","make","can","like","time","no","just","him","know","take","people","into","year","your",
        "good","some","could","them","see","other","than","then","now","look","only","come","its","over",
        "think","also","back","after","use","two","how","our","work","first","well","way","even","new",
        "want","because","any","these","give","day","most","us","hello","hi","hey","thanks","thank",
        "please","sorry","welcome","love","great","awesome","keyboard","beautiful","today","tomorrow",
        "friend","family","happy","birthday","yes","okay","nice","cool","really","very","much","more",
        "morning","night","goodbye","maybe","always","never","again","here","where","why","how"
    )

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> view?.reload() }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("ml", MODE_PRIVATE)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val v = KBView(this); view = v; return v
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        editor = info
        composing.setLength(0)
        val cls = (info?.inputType ?: 0) and InputType.TYPE_MASK_CLASS
        if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME)
            view?.setPage(1)
        maybeAutoCap()
        view?.setSuggestions(defaults())
        view?.invalidate()
    }

    override fun onFinishInputView(finishingInput: Boolean) { finish() }
    override fun onEvaluateFullscreenMode(): Boolean = false

    fun pref(): SharedPreferences = prefs

    fun click() {
        if (prefs.getBoolean("sound", true)) audio.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD)
    }

    fun haptic(v: View) {
        if (prefs.getBoolean("haptic", true)) {
            val c = if (Build.VERSION.SDK_INT >= 27) HapticFeedbackConstants.KEYBOARD_TAP else HapticFeedbackConstants.VIRTUAL_KEY
            v.performHapticFeedback(c)
        }
    }

    fun toggleShift() {
        val now = System.currentTimeMillis()
        shift = when {
            shift == 2 -> 0
            shift == 1 && now - lastShift < 320 -> 2
            else -> 1
        }
        lastShift = now
    }

    private fun consumeShift() { if (shift == 1) shift = 0 }

    fun maybeAutoCap() {
        if (!prefs.getBoolean("autoCap", true) || shift != 0) return
        val t = currentInputConnection?.getTextBeforeCursor(3, 0)?.toString() ?: ""
        if (t.isEmpty() || t.endsWith(". ") || t.endsWith("! ") || t.endsWith("? ") || t.endsWith("\n")) shift = 1
    }

    fun finish() {
        if (composing.isNotEmpty()) {
            currentInputConnection?.finishComposingText()
            composing.setLength(0)
        }
    }

    fun typeChar(c: Char) {
        val ic = currentInputConnection ?: return
        if (c.isLetter()) {
            val ch = if (shift != 0) c.uppercaseChar() else c
            composing.append(ch)
            ic.setComposingText(composing, 1)
            consumeShift()
            view?.setSuggestions(suggest(composing.toString()))
        } else {
            finish()
            ic.commitText(c.toString(), 1)
            view?.setSuggestions(defaults())
        }
        view?.invalidate()
    }

    fun typeText(s: String) {
        finish()
        currentInputConnection?.commitText(s, 1)
        view?.setSuggestions(defaults())
        view?.invalidate()
    }

    fun backspace() {
        val ic = currentInputConnection ?: return
        if (composing.isNotEmpty()) {
            composing.deleteCharAt(composing.length - 1)
            ic.setComposingText(composing, 1)
            view?.setSuggestions(suggest(composing.toString()))
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }
        view?.invalidate()
    }

    fun space() {
        val ic = currentInputConnection ?: return
        finish()
        val now = System.currentTimeMillis()
        if (now - lastSpace < 300) { ic.deleteSurroundingText(1, 0); ic.commitText(". ", 1) }
        else ic.commitText(" ", 1)
        lastSpace = now
        maybeAutoCap()
        view?.setSuggestions(defaults())
        view?.invalidate()
    }

    fun enter() {
        val ic = currentInputConnection ?: return
        finish()
        val info = editor
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val multiline = info != null && (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        if (!multiline && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED)
            ic.performEditorAction(action)
        else ic.commitText("\n", 1)
        maybeAutoCap()
        view?.setSuggestions(defaults())
        view?.invalidate()
    }

    fun commitSuggestion(w: String) {
        val ic = currentInputConnection ?: return
        val word = if (shift != 0) w.replaceFirstChar { it.uppercaseChar() } else w
        ic.setComposingText(word, 1)
        ic.finishComposingText()
        ic.commitText(" ", 1)
        composing.setLength(0)
        consumeShift()
        maybeAutoCap()
        view?.setSuggestions(defaults())
        view?.invalidate()
    }

    fun defaults() = listOf("the", "and", "I")

    fun suggest(p: String): List<String> {
        if (p.isEmpty()) return defaults()
        val q = p.lowercase()
        return words.filter { it.startsWith(q) }.sortedBy { it.length }.take(3).ifEmpty { defaults() }
    }

    fun openSettings() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/* ============================ KEYBOARD VIEW ============================ */
class KBView(private val svc: MLKeyboardService) : View(svc) {
    companion object {
        const val C_SHIFT = -1; const val C_BACK = -2; const val C_ENTER = -3
        const val C_SPACE = -4; const val C_PAGE = -5; const val C_EMOJI = -6
    }

    private val prefs = svc.pref()
    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = v * d
    private fun t(vararg c: String) = IntArray(c.size) { Color.parseColor(c[it]) }

    // [bg, surface, key, press, fn, fnPress, text, muted, accent, accent2, onAccent, border, onAccent2]
    private val themes = arrayOf(
        t("#0B0E1D","#151A33","#262E55","#3D4C8C","#1C2342","#33406F","#EEF1FF","#9AA3C7","#22D3EE","#F471B5","#06222E","#2C3560","#3D0A26"),
        t("#0C0C0E","#17171A","#26262B","#3A3A42","#1B1B1F","#333338","#F4F4F5","#A1A1AA","#34D399","#FBBF24","#062018","#2E2E33","#201400"),
        t("#04121F","#0A2334","#16405C","#2A6A92","#103349","#215A7E","#EAF6FF","#8FB3C9","#38BDF8","#2DD4BF","#04202E","#1D4A66","#03302A"),
        t("#170A1E","#241031","#3A1E4E","#623381","#331A44","#542B6E","#FDF1F7","#C79AB8","#FB923C","#F43F5E","#2A0D02","#4A2A5E","#FFFFFF"),
        t("#ECE8DE","#FFFFFF","#FFFFFF","#E2DDCF","#F1EEE6","#D8D2C0","#232838","#6B7288","#4F46E5","#DB2777","#FFFFFF","#D9D4C6","#FFFFFF")
    )
    private var th = themes[0]

    private val ALT = mapOf(
        'a' to "àáâäæãåā", 'e' to "èéêëē", 'i' to "ìíîïī", 'o' to "òóôöœõø",
        'u' to "ùúûüū", 'c' to "çćč", 'n' to "ñ", 's' to "ßśš", 'y' to "ÿý", 'z' to "žźż"
    )
    private val PUNCT = ",!?;:'\"-…"
    private val EMOJIS = listOf(
        "😀","😄","😁","😆","😅","😂","🤣","😊","😇","🙂","😉","😍","🥰","😘","😜","🤪","🤗","🤔","😴","🥳","😎","🥺","😭","😡","🤯","😳","🙄","😬",
        "👋","✋","👌","✌️","🤞","🤟","🤘","🤙","👈","👉","👆","👇","👍","👎","✊","👊","🙏","👏","💪","🤝",
        "❤️","🧡","💛","💚","💙","💜","🖤","🤍","💖","💗","💓","💞","💕","💘","💔","✨","🔥","💯","⭐","🎉",
        "🐶","🐱","🐭","🐰","🦊","🐻","🐼","🐨","🐯","🦁","🐮","🐷","🐸","🐵","🐔","🐧","🦄","🐝","🦋","🐢",
        "🍕","🍔","🍟","🌮","🍜","🍣","🍎","🍉","🍓","🍒","🥭","☕","🍵","🧋","🍺","🍰","🍦","🍩",
        "⚽","🏀","🎮","🎲","🎯","🎨","🎬","🎤","🎧","🎸","🎹","🏆","🥇","🎁","🎈",
        "🌍","🏔️","🏖️","🌅","🌈","☀️","🌙","⛅","🚗","✈️","🚀","🏠","💡","📱","💻","⌨️","📷","🔋","🔑","💎","📚","✏️","💰","⏰"
    )

    private class Key(val label: String, val code: Int, val arg: Int = 0, val flex: Float = 1f, val kind: Int = 0, val alts: String = "") {
        var x = 0f; var y = 0f; var w = 0f; var h = 0f; var pressed = false
    }
    private class Press(val key: Key?, var x: Float, var y: Float, var done: Boolean = false,
                        var lp: Runnable? = null, var rep: Runnable? = null, var emoji: Int = -1,
                        var moved: Boolean = false, var lastY: Float = y)

    private val keys = ArrayList<Key>()
    private val presses = SparseArray<Press>()
    private val handler = Handler(Looper.getMainLooper())
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); textAlign = Paint.Align.CENTER }
    private val fm = Paint.FontMetrics()
    private val rf = RectF()

    private var page = 0
    private var pad = dp(5); private var gap = dp(5)
    private var keyH = dp(48); private var numH = keyH * 0.72f; private var sugH = dp(44)
    private var sugs = listOf("the", "and", "I")
    private var previewKey: Key? = null
    private var lpOwner = -1; private var lpKey: Key? = null; private var lpAlts = listOf<String>()
    private var lpSel = 0; private var lpLeft = 0f; private var lpTop = 0f; private var lpItemW = dp(38)
    private var scrollY = 0f; private var emojiPressed = -1
    private var emojis = listOf<String>()

    init {
        isHapticFeedbackEnabled = true
        reload()
    }

    fun reload() {
        th = themes[prefs.getInt("theme", 0).coerceIn(0, themes.size - 1)]
        keyH = dp(prefs.getInt("keyH", 48)); numH = keyH * 0.72f
        refreshEmojis()
        requestLayout()
        if (width > 0) layoutKeys()
        invalidate()
    }

    fun setPage(pg: Int) { page = pg; requestLayout(); if (width > 0) layoutKeys(); invalidate() }

    fun setSuggestions(list: List<String>) {
        sugs = listOf(list.getOrElse(1) { "" }, list.getOrElse(0) { "" }, list.getOrElse(2) { "" })
        invalidate()
    }

    private fun refreshEmojis() {
        val r = (prefs.getString("recent", "") ?: "").split(",").filter { it.isNotEmpty() }
        emojis = r + EMOJIS.filter { !r.contains(it) }
    }

    private fun addRecent(e: String) {
        val r = (prefs.getString("recent", "") ?: "").split(",").filter { it.isNotEmpty() }
        val n = (listOf(e) + r).distinct().take(24)
        prefs.edit().putString("recent", n.joinToString(",")).apply()
        refreshEmojis()
    }

    private fun numRow() = prefs.getBoolean("numRow", false)
    private fun extraNum() = if (numRow() && (page == 0 || page == 3)) numH + gap else 0f
    private fun totalH() = (sugH + pad * 2 + extraNum() + keyH * 4 + gap * 3).toInt()
    private fun bottomY() = height - pad - keyH
    private fun areaTop() = sugH + pad
    private fun areaBottom() = bottomY() - gap

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), totalH())
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { layoutKeys() }

    private fun ck(s: String, kind: Int = 0, alts: String = "") = Key(s, s[0].code, kind = kind, alts = alts)

    private fun row(y: Float, h: Float, items: List<Key>, inset: Float = 0f) {
        val flex = items.sumOf { it.flex.toDouble() }.toFloat()
        val avail = width - pad * 2 - inset * 2 - gap * (items.size - 1)
        var x = pad + inset
        for (k in items) { k.w = avail * k.flex / flex; k.h = h; k.x = x; k.y = y; keys.add(k); x += k.w + gap }
    }

    private fun letterRow(y: Float, s: String, inset: Float = 0f) {
        row(y, keyH, s.map { Key(it.toString(), it.code, alts = ALT[it] ?: "", kind = 0) }, inset)
    }

    private fun layoutKeys() {
        keys.clear()
        var y = areaTop()
        if (page == 0) {
            if (numRow()) { row(y, numH, "1234567890".map { ck(it.toString(), 1) }); y += numH + gap }
            letterRow(y, "qwertyuiop"); y += keyH + gap
            val inset = (width - pad * 2 + gap) / 10f * 0.5f
            letterRow(y, "asdfghjkl", inset); y += keyH + gap
            row(y, keyH, listOf(Key("⇧", C_SHIFT, flex = 1.5f, kind = 4)) + "zxcvbnm".map { Key(it.toString(), it.code, alts = ALT[it] ?: "") } + Key("⌫", C_BACK, flex = 1.5f, kind = 1))
        } else if (page == 1) {
            row(y, keyH, "1234567890".map { ck(it.toString()) }); y += keyH + gap
            row(y, keyH, listOf("@","#","$","_","&","-","+","(",")","/").map { ck(it) }); y += keyH + gap
            row(y, keyH, listOf(Key("2/2", C_PAGE, arg = 2, flex = 1.5f, kind = 1)) + listOf("*","\"","'",":",";","!","?").map { ck(it) } + Key("⌫", C_BACK, flex = 1.5f, kind = 1))
        } else if (page == 2) {
            row(y, keyH, listOf("~","`","|","•","√","π","÷","×","¶","∆").map { ck(it) }); y += keyH + gap
            row(y, keyH, listOf("€","£","¥","¢","^","°","=","{","}","\\").map { ck(it) }); y += keyH + gap
            row(y, keyH, listOf(Key("1/2", C_PAGE, arg = 1, flex = 1.5f, kind = 1)) + listOf("©","®","™","✓","[","]").map { ck(it) } + Key("⌫", C_BACK, flex = 1.5f, kind = 1))
        }
        val by = bottomY()
        val first = when (page) {
            0 -> Key("123", C_PAGE, arg = 1, flex = 1.4f, kind = 1)
            3 -> Key("ABC", C_PAGE, arg = 0, flex = 1.4f, kind = 1)
            else -> Key("ABC", C_PAGE, arg = 0, flex = 1.4f, kind = 1)
        }
        val bottom = ArrayList<Key>()
        bottom.add(first)
        bottom.add(Key("😊", C_EMOJI, flex = 1.2f, kind = if (page == 3) 4 else 1))
        if (page == 3) {
            bottom.add(Key(",", ','.code, flex = 1f, kind = 1, alts = PUNCT))
            bottom.add(Key("English", C_SPACE, flex = 4.2f, kind = 3))
            bottom.add(Key("⌫", C_BACK, flex = 1.2f, kind = 1))
        } else {
            bottom.add(Key(",", ','.code, flex = 1f, kind = 1, alts = PUNCT))
            bottom.add(Key("ML · English", C_SPACE, flex = 4.2f, kind = 3))
            bottom.add(Key(".", '.'.code, flex = 1f, kind = 1, alts = PUNCT))
        }
        bottom.add(Key("⏎", C_ENTER, flex = 1.6f, kind = 2))
        row(by, keyH, bottom)
    }

    private fun baseline(cy: Float): Float { tp.getFontMetrics(fm); return cy - (fm.ascent + fm.descent) / 2f }

    override fun onDraw(c: Canvas) {
        c.drawColor(th[0])
        p.color = th[1]; c.drawRect(0f, 0f, width.toFloat(), sugH, p)
        val zoneW = (width - dp(92)) / 3f
        for (i in 0..2) {
            tp.color = if (i == 1) th[8] else th[7]
            tp.textSize = dp(if (i == 1) 15 else 14)
            tp.typeface = Typeface.create("sans-serif-medium", if (i == 1) Typeface.BOLD else Typeface.NORMAL)
            c.drawText(sugs.getOrElse(i) { "" }, zoneW * (i + 0.5f), baseline(sugH / 2), tp)
        }
        p.color = th[11]
        c.drawRect(zoneW, dp(10), zoneW + d, sugH - dp(10), p)
        c.drawRect(zoneW * 2, dp(10), zoneW * 2 + d, sugH - dp(10), p)
        tp.typeface = Typeface.DEFAULT; tp.textSize = dp(18); tp.color = th[7]
        c.drawText("🎨", width - dp(68), baseline(sugH / 2), tp)
        c.drawText("⚙", width - dp(26), baseline(sugH / 2), tp)

        for (k in keys) drawKey(c, k)
        if (page == 3) drawEmoji(c)
        drawPopup(c)
    }

    private fun drawKey(c: Canvas, k: Key) {
        rf.set(k.x, k.y, k.x + k.w, k.y + k.h)
        p.style = Paint.Style.FILL
        p.color = when {
            k.pressed -> if (k.kind == 1) th[5] else th[3]
            k.kind == 4 -> if (svc.shift != 0) th[8] else th[4]
            k.kind == 2 -> th[9]
            k.kind == 1 || k.kind == 3 -> th[4]
            else -> th[2]
        }
        val rad = dp(10)
        c.drawRoundRect(rf, rad, rad, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = d; p.color = th[11]
        c.drawRoundRect(rf, rad, rad, p)
        p.style = Paint.Style.FILL

        var label = k.label
        if (k.code > 0 && label.length == 1 && svc.shift != 0) label = label.uppercase()
        tp.color = when {
            k.kind == 2 -> th[12]
            k.kind == 4 && svc.shift != 0 -> th[10]
            k.kind == 1 || k.kind == 3 -> th[7]
            else -> th[6]
        }
        tp.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        tp.textSize = when (k.kind) {
            0 -> keyH * 0.42f
            3 -> dp(12)
            else -> dp(if (label.length > 2) 13 else 20)
        }
        c.drawText(label, k.x + k.w / 2, baseline(k.y + k.h / 2), tp)
        if (k.kind == 4 && svc.shift == 2) {
            p.color = th[10]
            c.drawRoundRect(k.x + k.w / 2 - dp(7), k.y + k.h - dp(7), k.x + k.w / 2 + dp(7), k.y + k.h - dp(4), dp(2), dp(2), p)
        }
    }

    private fun drawEmoji(c: Canvas) {
        val top = areaTop(); val bot = areaBottom(); val cell = width / 7f
        c.save(); c.clipRect(0f, top, width.toFloat(), bot)
        tp.typeface = Typeface.DEFAULT; tp.textSize = dp(26); tp.color = th[6]
        for (i in emojis.indices) {
            val x = (i % 7) * cell
            val y = top + (i / 7) * cell - scrollY
            if (y + cell < top || y > bot) continue
            if (i == emojiPressed) {
                p.color = th[3]; rf.set(x + dp(3), y + dp(3), x + cell - dp(3), y + cell - dp(3))
                c.drawRoundRect(rf, dp(10), dp(10), p)
            }
            c.drawText(emojis[i], x + cell / 2, baseline(y + cell / 2), tp)
        }
        c.restore()
    }

    private fun drawPopup(c: Canvas) {
        val pk = previewKey
        if (pk != null && lpKey == null) {
            val w = max(dp(46), pk.w + dp(12)); val h = dp(56)
            val x = (pk.x + pk.w / 2 - w / 2).coerceIn(pad, width - w - pad)
            val y = pk.y - h - dp(6)
            rf.set(x, y, x + w, y + h); p.color = th[2]; c.drawRoundRect(rf, dp(12), dp(12), p)
            p.style = Paint.Style.STROKE; p.strokeWidth = d; p.color = th[11]; c.drawRoundRect(rf, dp(12), dp(12), p); p.style = Paint.Style.FILL
            tp.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); tp.textSize = dp(26); tp.color = th[6]
            val l = if (svc.shift != 0) pk.label.uppercase() else pk.label
            c.drawText(l, x + w / 2, baseline(y + h / 2), tp)
        }
        val lk = lpKey ?: return
        val n = lpAlts.size; val w = n * lpItemW + dp(10); val h = dp(52)
        rf.set(lpLeft, lpTop, lpLeft + w, lpTop + h); p.color = th[1]; c.drawRoundRect(rf, dp(14), dp(14), p)
        p.style = Paint.Style.STROKE; p.strokeWidth = d; p.color = th[11]; c.drawRoundRect(rf, dp(14), dp(14), p); p.style = Paint.Style.FILL
        for (i in 0 until n) {
            val x = lpLeft + dp(5) + i * lpItemW
            if (i == lpSel) { p.color = th[8]; rf.set(x, lpTop + dp(5), x + lpItemW - dp(3), lpTop + h - dp(5)); c.drawRoundRect(rf, dp(8), dp(8), p) }
            tp.textSize = dp(19); tp.color = if (i == lpSel) th[10] else th[6]
            c.drawText(lpAlts[i], x + (lpItemW - dp(3)) / 2, baseline(lpTop + h / 2), tp)
        }
    }

    private fun openLP(id: Int, k: Key) {
        lpOwner = id; lpKey = k; previewKey = null
        lpAlts = k.alts.map { if (svc.shift != 0) it.uppercaseChar().toString() else it.toString() }
        lpItemW = dp(38)
        val w = lpAlts.size * lpItemW + dp(10)
        lpLeft = (k.x + k.w / 2 - w / 2).coerceIn(pad, width - w - pad)
        lpTop = k.y - dp(60)
        lpSel = 0
        svc.haptic(this); invalidate()
    }

    private fun closeLP() { lpOwner = -1; lpKey = null; lpAlts = emptyList(); invalidate() }

    private fun maxScroll(): Float {
        val cell = width / 7f
        return max(0f, ceil(emojis.size / 7f).toFloat() * cell - (areaBottom() - areaTop()))
    }

    private fun emojiAt(x: Float, y: Float): Int {
        val cell = width / 7f
        val col = (x / cell).toInt()
        val r = ((y - areaTop() + scrollY) / cell).toInt()
        val idx = r * 7 + col
        return if (col in 0..6 && idx in emojis.indices) idx else -1
    }

    private fun handleSug(x: Float) {
        val right = width - dp(92)
        if (x > right) {
            if (x > width - dp(46)) svc.openSettings()
            else prefs.edit().putInt("theme", (prefs.getInt("theme", 0) + 1) % themes.size).apply()
            return
        }
        val idx = (x / (right / 3f)).toInt().coerceIn(0, 2)
        val w = sugs.getOrElse(idx) { "" }
        if (w.isNotEmpty()) svc.commitSuggestion(w)
    }

    private fun repeatBack(pr: Press) {
        svc.backspace()
        pr.rep = Runnable { repeatBack(pr) }
        handler.postDelayed(pr.rep!!, 55)
    }

    private fun startPress(id: Int, x: Float, y: Float) {
        if (y < sugH) { handleSug(x); presses.put(id, Press(null, x, y, true)); return }
        if (page == 3 && y < areaBottom()) {
            val idx = emojiAt(x, y)
            presses.put(id, Press(null, x, y, emoji = idx, lastY = y))
            emojiPressed = idx; invalidate(); return
        }
        val k = keys.firstOrNull { x >= it.x && x <= it.x + it.w && y >= it.y && y <= it.y + it.h }
        if (k == null) { presses.put(id, Press(null, x, y, true)); return }
        k.pressed = true
        val pr = Press(k, x, y); presses.put(id, pr)
        svc.click(); svc.haptic(this)
        when (k.code) {
            C_SHIFT -> { svc.toggleShift(); pr.done = true }
            C_PAGE -> { svc.finish(); page = k.arg; requestLayout(); layoutKeys(); pr.done = true }
            C_EMOJI -> { svc.finish(); page = if (page == 3) 0 else 3; requestLayout(); layoutKeys(); pr.done = true }
            C_ENTER -> { svc.enter(); pr.done = true }
            C_SPACE -> { svc.space(); pr.done = true }
            C_BACK -> {
                svc.backspace(); pr.done = true
                pr.rep = Runnable { repeatBack(pr) }; handler.postDelayed(pr.rep!!, 380)
            }
            else -> {
                previewKey = k
                if (k.alts.isNotEmpty()) { pr.lp = Runnable { openLP(id, k) }; handler.postDelayed(pr.lp!!, 330) }
            }
        }
        invalidate()
    }

    private fun movePress(id: Int, x: Float, y: Float) {
        val pr = presses.get(id) ?: return
        if (lpOwner == id && lpKey != null) {
            lpSel = ((x - lpLeft - dp(5)) / lpItemW).toInt().coerceIn(0, lpAlts.size - 1)
            invalidate(); return
        }
        if (pr.emoji >= 0) {
            val dy = y - pr.lastY
            if (abs(dy) > dp(4) || pr.moved) { pr.moved = true; scrollY = (scrollY - dy).coerceIn(0f, maxScroll()); emojiPressed = -1 }
            pr.lastY = y; invalidate(); return
        }
        if (!pr.done && (abs(x - pr.x) > dp(14) || abs(y - pr.y) > dp(14))) {
            pr.lp?.let { handler.removeCallbacks(it) }; pr.lp = null; previewKey = null; invalidate()
        }
    }

    private fun endPress(id: Int, commit: Boolean) {
        val pr = presses.get(id) ?: return
        presses.remove(id)
        pr.lp?.let { handler.removeCallbacks(it) }
        pr.rep?.let { handler.removeCallbacks(it) }
        pr.key?.pressed = false
        if (lpOwner == id) {
            if (commit && lpAlts.isNotEmpty()) svc.typeChar(lpAlts[lpSel][0])
            closeLP()
        } else if (pr.emoji >= 0) {
            if (commit && !pr.moved && pr.emoji < emojis.size) { val e = emojis[pr.emoji]; svc.typeText(e); addRecent(e) }
            emojiPressed = -1
        } else if (commit && pr.key != null && !pr.done) {
            svc.typeChar(pr.key.code.toChar())
        }
        previewKey = null
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex; startPress(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) movePress(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex; endPress(e.getPointerId(i), true)
            }
            MotionEvent.ACTION_CANCEL -> {
                for (i in 0 until presses.size()) endPress(presses.keyAt(i), false)
            }
        }
        return true
    }
}

/* ============================ SETTINGS APP ============================ */
class MainActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var status: TextView
    private val d get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("ml", MODE_PRIVATE)
        window.statusBarColor = Color.parseColor("#0B0E1D")
        window.navigationBarColor = Color.parseColor("#0B0E1D")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(30), dp(22), dp(24))
            setBackgroundColor(Color.parseColor("#0B0E1D"))
        }
        val scroll = ScrollView(this); scroll.addView(root)

        root.addView(tv("ML Keyboard", 30f, "#EEF1FF", true))
        root.addView(tv("Real system keyboard · English only", 13f, "#9AA3C7", false))
        status = tv("", 14f, "#22D3EE", true); root.addView(status)
        root.addView(space(10))
        root.addView(btn("1 · Enable ML Keyboard") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) })
        root.addView(space(8))
        root.addView(btn("2 · Choose ML Keyboard") {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        })
        root.addView(space(6))
        root.addView(tv("Enable it in system settings, then choose it as your keyboard. Long-press keys for accents & symbols. Tap 🎨 on the keyboard to switch theme, ⚙ to open this page.", 12f, "#9AA3C7", false))
        root.addView(space(16))
        root.addView(tv("SETTINGS", 12f, "#22D3EE", true))
        root.addView(toggle("Key sound", "sound", true))
        root.addView(toggle("Haptic feedback", "haptic", true))
        root.addView(toggle("Number row", "numRow", false))
        root.addView(toggle("Auto-capitalization", "autoCap", true))
        root.addView(space(8))
        root.addView(tv("Key height", 13f, "#EEF1FF", false))
        val seek = SeekBar(this).apply {
            max = 18; progress = prefs.getInt("keyH", 48) - 44
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { prefs.edit().putInt("keyH", 44 + p).apply() }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        root.addView(seek)
        root.addView(space(8))
        root.addView(tv("Theme", 13f, "#EEF1FF", false))
        val hs = HorizontalScrollView(this)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Midnight", "Carbon", "Ocean", "Sunset", "Paper").forEachIndexed { i, n ->
            row.addView(btn(n) { prefs.edit().putInt("theme", i).apply() }.apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
            })
        }
        hs.addView(row); root.addView(hs)
        root.addView(space(16))
        root.addView(tv("Try it here:", 13f, "#EEF1FF", false))
        val et = EditText(this).apply {
            hint = "Type with ML Keyboard…"
            setTextColor(Color.parseColor("#EEF1FF")); setHintTextColor(Color.parseColor("#9AA3C7"))
            setBackgroundColor(Color.parseColor("#151A33")); setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        root.addView(et)
        setContentView(scroll)
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_INPUT_METHODS)?.contains(packageName) == true
        val def = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith("$packageName/") == true
        status.text = when {
            def -> "✅ ML Keyboard is active"
            enabled -> "⚠️ Enabled — tap step 2 to select it"
            else -> "⚠️ Not enabled yet — tap step 1"
        }
    }

    private fun space(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h)) }
    private fun tv(s: String, size: Float, color: String, bold: Boolean) = TextView(this).apply {
        text = s; textSize = size; setTextColor(Color.parseColor(color))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }
    private fun btn(s: String, fn: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false; setTextColor(Color.parseColor("#06222E"))
        setBackgroundColor(Color.parseColor("#22D3EE")); setPadding(dp(14), dp(12), dp(14), dp(12))
        setOnClickListener { fn() }
    }
    private fun toggle(label: String, key: String, def: Boolean) = Switch(this).apply {
        text = label; textSize = 14f; setTextColor(Color.parseColor("#EEF1FF"))
        isChecked = prefs.getBoolean(key, def); setPadding(0, dp(8), 0, dp(8))
        setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean(key, v).apply() }
    }
}
