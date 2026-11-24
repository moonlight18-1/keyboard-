package com.ml.keyboard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.SparseArray
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/* ======================= PALETTES ======================= */
object Pal {
    private fun p(vararg c: String) = IntArray(c.size) { Color.parseColor(c[it]) }
    val names = listOf("Midnight", "Carbon", "Ocean", "Sunset", "Forest", "Paper")
    val themes = arrayOf(
        p("#0B0E1D","#141A33","#252D53","#3E4E90","#1B2240","#333F70","#EEF1FF","#98A2C6","#22D3EE","#F471B5","#04222D","#3A0A24","#2A3358","#14FFFFFF"),
        p("#0A0A0C","#141417","#232329","#3A3A45","#17171B","#30303A","#F5F5F7","#9C9CA8","#34D399","#FBBF24","#052018","#241700","#2C2C34","#12FFFFFF"),
        p("#04121F","#0A2334","#16405C","#2A6A92","#0E2E44","#215A7E","#EAF6FF","#8FB3C9","#38BDF8","#2DD4BF","#04202E","#03302A","#1C4763","#14FFFFFF"),
        p("#170A1E","#241031","#3A1E4E","#623381","#2C1440","#542B6E","#FDF1F7","#C79AB8","#FB923C","#F43F5E","#2A0D02","#FFFFFF","#47285C","#14FFFFFF"),
        p("#06140F","#0D2117","#1C3A28","#316045","#12291C","#2A5540","#ECFDF3","#93B8A3","#4ADE80","#A3E635","#052015","#16290A","#244A34","#12FFFFFF"),
        p("#EDE9E1","#FFFFFF","#FFFFFF","#DFDACB","#F2EFE8","#D9D3C4","#22283A","#6C7288","#4F46E5","#DB2777","#FFFFFF","#FFFFFF","#DCD7C9","#00000000")
    )
}

/* ======================= SERVICE ======================= */
class MLKeyboardService : InputMethodService() {
    lateinit var prefs: SharedPreferences
    private lateinit var audio: AudioManager
    private var kb: KBView? = null
    private val comp = StringBuilder()
    var shift = 0
    private var lastSpace = 0L
    private var lastShift = 0L
    private var info: EditorInfo? = null
    private var learned = LinkedHashSet<String>()

    private val dict = arrayOf(
        "the","be","to","of","and","a","in","that","have","I","it","for","not","on","with","he","as","you","do","at",
        "this","but","his","by","from","they","we","say","her","she","or","an","will","my","one","all","would","there",
        "their","what","so","up","out","if","about","who","get","which","go","me","when","make","can","like","time",
        "no","just","him","know","take","people","into","year","your","good","some","could","them","see","other",
        "than","then","now","look","only","come","its","over","think","also","back","after","use","two","how","our",
        "work","first","well","way","even","new","want","because","any","these","give","day","most","us","hello",
        "hi","hey","thanks","thank","please","sorry","welcome","love","great","awesome","keyboard","beautiful",
        "today","tomorrow","yesterday","friend","family","happy","birthday","yes","okay","nice","cool","really",
        "very","much","more","morning","night","goodbye","maybe","always","never","again","here","where","why",
        "should","thing","things","something","anything","everything","nothing","someone","everyone",
        "message","phone","computer","music","movie","game","food","water","coffee","school","house",
        "money","world","life","country","city","street","weather","summer","winter","spring","autumn"
    )

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
        if (k != "used" && k != "learned" && k != "recent") kb?.reload()
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("ml", MODE_PRIVATE)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        learned = LinkedHashSet((prefs.getString("learned", "") ?: "").split(",").filter { it.isNotEmpty() })
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onDestroy()
    }

    override fun onCreateInputView(): View { val v = KBView(this); kb = v; return v }

    override fun onStartInputView(i: EditorInfo?, restarting: Boolean) {
        info = i; comp.setLength(0)
        prefs.edit().putBoolean("used", true).apply()
        val cls = (i?.inputType ?: 0) and InputType.TYPE_MASK_CLASS
        if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE) kb?.setPage(1)
        autoCap(); kb?.setWords(defaults()); kb?.invalidate()
    }

    override fun onFinishInputView(finishingInput: Boolean) { finish() }
    override fun onEvaluateFullscreenMode(): Boolean = false

    fun click() { if (prefs.getBoolean("sound", true)) audio.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD) }

    fun buzz() {
        if (prefs.getBoolean("haptic", true)) {
            val c = if (Build.VERSION.SDK_INT >= 27) HapticFeedbackConstants.KEYBOARD_TAP else HapticFeedbackConstants.VIRTUAL_KEY
            kb?.performHapticFeedback(c)
        }
    }

    fun toggleShift() {
        val n = System.currentTimeMillis()
        shift = if (shift == 2) 0 else if (shift == 1 && n - lastShift < 320) 2 else 1
        lastShift = n
    }

    fun autoCap() {
        if (!prefs.getBoolean("autoCap", true) || shift != 0) return
        val t = currentInputConnection?.getTextBeforeCursor(3, 0)?.toString() ?: ""
        if (t.isEmpty() || t.endsWith(". ") || t.endsWith("! ") || t.endsWith("? ") || t.endsWith("\n")) shift = 1
    }

    fun finish() {
        if (comp.isNotEmpty()) {
            currentInputConnection?.finishComposingText()
            learn(comp.toString()); comp.setLength(0)
        }
    }

    private fun learn(w: String) {
        if (!prefs.getBoolean("learn", true)) return
        val s = w.trim()
        if (s.length < 2 || !s.all { it.isLetter() }) return
        val lw = s.lowercase()
        learned.remove(lw); learned.add(lw)
        while (learned.size > 240) learned.remove(learned.first())
        prefs.edit().putString("learned", learned.joinToString(",")).apply()
    }

    fun type(c: Char) {
        val ic = currentInputConnection ?: return
        if (c.isLetter()) {
            comp.append(if (shift != 0) c.uppercaseChar() else c)
            ic.setComposingText(comp, 1)
            if (shift == 1) shift = 0
            kb?.setWords(suggest(comp.toString()))
        } else { finish(); ic.commitText(c.toString(), 1); kb?.setWords(defaults()) }
        kb?.invalidate()
    }

    fun text(s: String) {
        finish(); currentInputConnection?.commitText(s, 1)
        kb?.setWords(defaults()); kb?.invalidate()
    }

    fun back() {
        val ic = currentInputConnection ?: return
        if (comp.isNotEmpty()) {
            comp.deleteCharAt(comp.length - 1); ic.setComposingText(comp, 1)
            kb?.setWords(suggest(comp.toString()))
        } else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        kb?.invalidate()
    }

    fun space() {
        val ic = currentInputConnection ?: return
        finish()
        val n = System.currentTimeMillis()
        if (prefs.getBoolean("dblSpace", true) && n - lastSpace < 320) {
            ic.deleteSurroundingText(1, 0); ic.commitText(". ", 1)
        } else ic.commitText(" ", 1)
        lastSpace = n; autoCap(); kb?.setWords(defaults()); kb?.invalidate()
    }

    fun enter() {
        val ic = currentInputConnection ?: return
        finish()
        val act = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val multi = info != null && (info!!.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        if (!multi && act != EditorInfo.IME_ACTION_NONE && act != EditorInfo.IME_ACTION_UNSPECIFIED) ic.performEditorAction(act)
        else ic.commitText("\n", 1)
        autoCap(); kb?.setWords(defaults()); kb?.invalidate()
    }

    fun pick(w: String) {
        val ic = currentInputConnection ?: return
        val s = if (shift != 0) w.replaceFirstChar { it.uppercaseChar() } else w
        ic.setComposingText(s, 1); ic.finishComposingText(); ic.commitText(" ", 1)
        learn(s); comp.setLength(0); if (shift == 1) shift = 0
        autoCap(); kb?.setWords(defaults()); kb?.invalidate()
    }

    fun cursor(dir: Int) {
        finish()
        sendDownUpKeyEvents(if (dir < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
    }

    fun edge(home: Boolean) {
        finish()
        sendDownUpKeyEvents(if (home) KeyEvent.KEYCODE_MOVE_HOME else KeyEvent.KEYCODE_MOVE_END)
    }

    fun edit(id: Int) { finish(); currentInputConnection?.performContextMenuAction(id) }

    fun defaults() = listOf("the", "and", "I")

    fun suggest(p: String): List<String> {
        if (p.isEmpty()) return defaults()
        val q = p.lowercase()
        val out = ArrayList<String>()
        for (w in learned.reversed()) if (w.startsWith(q) && w != q) { out.add(w); if (out.size == 3) break }
        if (out.size < 3) for (w in dict) if (w.startsWith(q) && !out.contains(w)) { out.add(w); if (out.size == 3) break }
        if (out.isEmpty()) out.add(p)
        return out.sortedBy { it.length }
    }

    fun openSettings() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/* ======================= KEYBOARD VIEW ======================= */
class KBView(private val svc: MLKeyboardService) : View(svc) {
    companion object {
        const val SHIFT = -1; const val BACK = -2; const val ENTER = -3; const val SPACE = -4
        const val PAGE = -5; const val EMOJI = -6
        const val SELALL = -10; const val COPY = -11; const val PASTE = -12; const val CUT = -13
        const val LEFT = -14; const val RIGHT = -15; const val HOME = -16; const val END = -17
    }

    private val prefs = svc.prefs
    private val dens = resources.displayMetrics.density
    private fun dp(v: Int) = v * dens
    private val themes = Pal.themes
    private var themeIdx = 0
    private var colFrom = themes[0]
    private var colTo = themes[0]
    private var mix = 1f

    private fun lerp(a: Int, b: Int, t: Float): Int {
        val i = 1f - t
        return Color.argb((Color.alpha(a) * i + Color.alpha(b) * t).toInt(),
            (Color.red(a) * i + Color.red(b) * t).toInt(),
            (Color.green(a) * i + Color.green(b) * t).toInt(),
            (Color.blue(a) * i + Color.blue(b) * t).toInt())
    }

    private fun col(i: Int) = if (mix >= 1f) colTo[i] else lerp(colFrom[i], colTo[i], mix)

    private val ALT = mapOf(
        'a' to "àáâäæãåā", 'e' to "èéêëē", 'i' to "ìíîïī", 'o' to "òóôöœõø",
        'u' to "ùúûüū", 'c' to "çćč", 'n' to "ñ", 's' to "ßśš", 'y' to "ÿý", 'z' to "žźż"
    )
    private val PUNCT = ",!?;:'\"-…"
    private val catIcons = listOf("🕘","😀","👋","❤️","🐶","🍔","⚽")
    private val catData = listOf(
        listOf<String>(),
        listOf("😀","😄","😁","😆","😅","😂","🤣","😊","😇","🙂","😉","😍","🥰","😘","😜","🤪","🤗","🤔","😴","🥳","😎","🥺","😭","😡","🤯","😳","🙄","😬","😷","🤒","🥶","🥵"),
        listOf("👋","✋","👌","✌️","🤞","🤟","🤘","🤙","👈","👉","👆","👇","👍","👎","✊","👊","🙏","👏","💪","🤝","🫶","🤌","☝️","🖐️"),
        listOf("❤️","🧡","💛","💚","💙","💜","🖤","🤍","💖","💗","💓","💞","💕","💘","💔","✨","🔥","💯","⭐","🎉","🎊","💫","🌟","💐"),
        listOf("🐶","🐱","🐭","🐰","🦊","🐻","🐼","🐨","🐯","🦁","🐮","🐷","🐸","🐵","🐔","🐧","🦄","🐝","🦋","🐢","🐬","🦉","🐺","🦜"),
        listOf("🍕","🍔","🍟","🌮","🍜","🍣","🍎","🍉","🍓","🍒","🥭","☕","🍵","🧋","🍺","🍰","🍦","🍩","🥐","🍗","🥗","🍿","🧀","🍫"),
        listOf("⚽","🏀","🎮","🎲","🎯","🎨","🎬","🎤","🎧","🎸","🎹","🏆","🥇","🎁","🎈","🌍","🏔️","🏖️","🌅","🌈","☀️","🌙","🚗","✈️","🚀","🏠","💡","📱","💻","⌨️","📷","🔋","🔑","💎","📚","✏️","💰","⏰")
    )

    private class Key(val label: String, val code: Int, val arg: Int = 0, val flex: Float = 1f, val kind: Int = 0, val alts: String = "") {
        var x = 0f; var y = 0f; var w = 0f; var h = 0f; var down = false; var anim = 0f
    }

    private class Press(val key: Key?, var sx: Float, var sy: Float, var lx: Float, var ly: Float,
                        var done: Boolean = false, var lp: Runnable? = null, var rep: Runnable? = null,
                        var emoji: Int = -1, var moved: Boolean = false, var acc: Float = 0f, var vel: Float = 0f)

    private class Rip(val k: Key, var x: Float, var y: Float, var t: Float)

    private val keys = ArrayList<Key>()
    private val presses = SparseArray<Press>()
    private val hd = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val fmet = Paint.FontMetrics()
    private val rect = RectF()
    private val rips = ArrayList<Rip>()
    private var page = 0
    private var edit = false
    private var themeBar = false
    private var pad = dp(5)
    private var gap = dp(5)
    private var kh = dp(48)
    private var nh = kh * 0.72f
    private var sh = dp(46)
    private var words = listOf("the", "and", "I")
    private var preview: Key? = null
    private var lpKey: Key? = null
    private var lpOwner = -1
    private var lpAlts = listOf<String>()
    private var lpSel = 0
    private var lpX = 0f
    private var lpY = 0f
    private var lpW = dp(38)
    private var lpSelX = 0f
    private var scroll = 0f
    private var emoCat = 0
    private var emoPressed = -1
    private var emojis = listOf<String>()
    private var pageT = 1f
    private var popT = 0f
    private var sugT = 1f
    private var barT = 0f
    private var lastFrame = 0L
    private var flingV = 0f

    init { isHapticFeedbackEnabled = true; reload() }

    fun reload() {
        val i = prefs.getInt("theme", 0).coerceIn(0, themes.size - 1)
        if (i != themeIdx) { colFrom = IntArray(14) { col(it) }; themeIdx = i; colTo = themes[i]; mix = 0f }
        kh = dp(prefs.getInt("keyH", 48)); nh = kh * 0.72f
        refreshEmojis(); requestLayout(); if (width > 0) layoutKeys(); invalidate()
    }

    fun setPage(p: Int) {
        page = p; edit = false; pageT = 0f
        requestLayout(); if (width > 0) layoutKeys(); invalidate()
    }

    fun setWords(l: List<String>) {
        val n = listOf(l.getOrElse(1) { "" }, l.getOrElse(0) { "" }, l.getOrElse(2) { "" })
        if (n != words) { words = n; sugT = 0f }
        invalidate()
    }

    private fun refreshEmojis() {
        if (emoCat == 0) {
            val r = (prefs.getString("recent", "") ?: "").split(",").filter { it.isNotEmpty() }
            emojis = if (r.isEmpty()) catData[1] else r
        } else emojis = catData[emoCat]
    }

    private fun addRecent(e: String) {
        val r = (prefs.getString("recent", "") ?: "").split(",").filter { it.isNotEmpty() }
        prefs.edit().putString("recent", (listOf(e) + r).distinct().take(28).joinToString(",")).apply()
        refreshEmojis()
    }

    private fun numOn() = prefs.getBoolean("numRow", true)
    private fun extra() = if (page == 3 || (page == 0 && numOn() && !edit)) nh + gap else 0f
    private fun totalH() = (sh + pad * 2 + extra() + kh * 4 + gap * 3).toInt()
    private fun emoTop() = sh + pad
    private fun chipH() = dp(34)
    private fun gridTop() = emoTop() + chipH() + dp(4)
    private fun gridBot() = height - pad - kh - gap

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), totalH())
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { layoutKeys() }

    private fun row(y: Float, hh: Float, items: List<Key>, inset: Float = 0f) {
        val f = items.sumOf { it.flex.toDouble() }.toFloat()
        val avail = width - pad * 2 - inset * 2 - gap * (items.size - 1)
        var x = pad + inset
        for (k in items) { k.w = avail * k.flex / f; k.h = hh; k.x = x; k.y = y; keys.add(k); x += k.w + gap }
    }

    private fun letters(y: Float, s: String, inset: Float = 0f) =
        row(y, kh, s.map { Key(it.toString(), it.code, alts = ALT[it] ?: "") }, inset)

    private fun sym(s: List<String>) = s.map { Key(it, it[0].code, kind = 1) }

    private fun layoutKeys() {
        keys.clear()
        var y = sh + pad
        if (page == 0) {
            if (edit) {
                row(y, kh, listOf(Key("All", SELALL, kind = 1), Key("Copy", COPY, kind = 1),
                    Key("Paste", PASTE, kind = 1), Key("Cut", CUT, kind = 1), Key("←", LEFT, kind = 1),
                    Key("→", RIGHT, kind = 1), Key("Home", HOME, kind = 1), Key("End", END, kind = 1)))
            } else {
                if (numOn()) { row(y, nh, sym("1234567890".map { it.toString() })); y += nh + gap }
                letters(y, "qwertyuiop")
            }
            y += kh + gap
            letters(y, "asdfghjkl", (width - pad * 2 + gap) / 10f * 0.5f)
            y += kh + gap
            row(y, kh, listOf(Key("⇧", SHIFT, flex = 1.5f, kind = 4)) +
                "zxcvbnm".map { Key(it.toString(), it.code, alts = ALT[it] ?: "") } +
                Key("⌫", BACK, flex = 1.5f, kind = 1))
        } else if (page == 1) {
            row(y, kh, sym("1234567890".map { it.toString() })); y += kh + gap
            row(y, kh, sym(listOf("@","#","$","_","&","-","+","(",")","/"))); y += kh + gap
            row(y, kh, listOf(Key("2/2", PAGE, arg = 2, flex = 1.5f, kind = 1)) +
                sym(listOf("*","\"","'",":",";","!","?")) + Key("⌫", BACK, flex = 1.5f, kind = 1))
        } else if (page == 2) {
            row(y, kh, sym(listOf("~","`","|","•","√","π","÷","×","¶","∆"))); y += kh + gap
            row(y, kh, sym(listOf("€","£","¥","¢","^","°","=","{","}","\\"))); y += kh + gap
            row(y, kh, listOf(Key("1/2", PAGE, arg = 1, flex = 1.5f, kind = 1)) +
                sym(listOf("©","®","™","✓","[","]")) + Key("⌫", BACK, flex = 1.5f, kind = 1))
        }
        val b = ArrayList<Key>()
        b.add(if (page == 0) Key("123", PAGE, arg = 1, flex = 1.4f, kind = 1)
              else Key("ABC", PAGE, arg = 0, flex = 1.4f, kind = 1))
        b.add(Key("😊", EMOJI, flex = 1.2f, kind = if (page == 3) 4 else 1))
        b.add(Key(",", ','.code, flex = 1f, kind = 1, alts = PUNCT))
        b.add(Key("English", SPACE, flex = 4.2f, kind = 3))
        if (page == 3) b.add(Key("⌫", BACK, flex = 1.2f, kind = 1))
        else b.add(Key(".", '.'.code, flex = 1f, kind = 1, alts = PUNCT))
        b.add(Key("⏎", ENTER, flex = 1.6f, kind = 2))
        row(height - pad - kh, kh, b)
    }

    private fun bl(cy: Float): Float {
        tp.getFontMetrics(fmet)
        return cy - (fmet.ascent + fmet.descent) / 2f
    }

    private fun step(c: Float, t: Float, k: Float) = if (abs(t - c) < 0.002f) t else c + (t - c) * k

    private fun tick(): Boolean {
        val now = System.currentTimeMillis()
        val dt = if (lastFrame == 0L) 16f else (now - lastFrame).coerceIn(1L, 48L).toFloat()
        lastFrame = now
        val anim = prefs.getBoolean("anim", true)
        val k = if (anim) 1f - exp(-dt / 50.0).toFloat() else 1f
        var busy = false
        for (key in keys) {
            val t = if (key.down) 1f else 0f
            key.anim = step(key.anim, t, k)
            if (key.anim != t) busy = true
        }
        pageT = step(pageT, 1f, k); if (pageT != 1f) busy = true
        val pt = if (preview != null || lpKey != null) 1f else 0f
        popT = step(popT, pt, k); if (popT != pt) busy = true
        sugT = step(sugT, 1f, k); if (sugT != 1f) busy = true
        val bt = if (themeBar) 1f else 0f
        barT = step(barT, bt, k); if (barT != bt) busy = true
        if (mix < 1f) { mix = if (anim) min(1f, mix + dt / 260f) else 1f; busy = true }
        val it = rips.iterator()
        while (it.hasNext()) {
            val r = it.next(); r.t += dt / 340f
            if (r.t >= 1f) it.remove() else busy = true
        }
        if (abs(flingV) > 0.05f) {
            scroll = (scroll + flingV * dt / 16f).coerceIn(0f, maxScroll())
            flingV *= exp(-dt / 120.0).toFloat(); busy = true
        } else flingV = 0f
        if (lpKey != null) {
            val t = lpX + dp(5) + lpSel * lpW
            lpSelX = step(lpSelX, t, k); if (lpSelX != t) busy = true
        }
        return busy
    }

    override fun onDraw(c: Canvas) {
        val busy = tick()
        c.drawColor(col(0))
        drawBar(c)
        if (pageT < 0.999f) {
            c.saveLayerAlpha(0f, sh, width.toFloat(), height.toFloat(), (pageT * 255).toInt())
            c.translate(0f, (1f - pageT) * dp(8))
            drawBody(c)
            c.restore()
        } else drawBody(c)
        for (r in rips) {
            c.save()
            c.clipRect(r.k.x, r.k.y, r.k.x + r.k.w, r.k.y + r.k.h)
            paint.color = col(8); paint.alpha = ((1f - r.t) * 42).toInt()
            c.drawCircle(r.x, r.y, r.t * max(r.k.w, r.k.h), paint)
            paint.alpha = 255
            c.restore()
        }
        drawPop(c)
        if (busy) postInvalidateOnAnimation()
    }

    private fun drawBody(c: Canvas) {
        for (k in keys) drawKey(c, k)
        if (page != 3) return
        val cw = width / 7f
        for (i in catIcons.indices) {
            val x = i * cw
            if (i == emoCat) {
                paint.color = col(8); paint.alpha = 40
                rect.set(x + dp(4), emoTop(), x + cw - dp(4), emoTop() + chipH())
                c.drawRoundRect(rect, dp(10), dp(10), paint)
                paint.alpha = 255
            }
            tp.typeface = Typeface.DEFAULT; tp.textSize = dp(19); tp.color = col(6)
            tp.alpha = if (i == emoCat) 255 else 150
            c.drawText(catIcons[i], x + cw / 2, bl(emoTop() + chipH() / 2), tp)
            tp.alpha = 255
        }
        c.save()
        c.clipRect(0f, gridTop(), width.toFloat(), gridBot())
        tp.typeface = Typeface.DEFAULT; tp.textSize = dp(26); tp.color = col(6)
        for (i in emojis.indices) {
            val x = (i % 7) * cw
            val y = gridTop() + (i / 7) * cw - scroll
            if (y + cw < gridTop() || y > gridBot()) continue
            if (i == emoPressed) {
                paint.color = col(3)
                rect.set(x + dp(3), y + dp(3), x + cw - dp(3), y + cw - dp(3))
                c.drawRoundRect(rect, dp(10), dp(10), paint)
            }
            c.drawText(emojis[i], x + cw / 2, bl(y + cw / 2), tp)
        }
        c.restore()
    }

    private fun drawBar(c: Canvas) {
        paint.color = col(1)
        c.drawRect(0f, 0f, width.toFloat(), sh, paint)
        val zone = width - dp(96)
        val a = 1f - barT
        if (a > 0.01f) {
            val zw = zone / 3f
            for (i in 0..2) {
                tp.color = if (i == 1) col(8) else col(7)
                tp.alpha = (a * sugT * 255).toInt()
                tp.textSize = dp(if (i == 1) 15 else 14)
                tp.typeface = Typeface.create("sans-serif-medium", if (i == 1) Typeface.BOLD else Typeface.NORMAL)
                c.drawText(words.getOrElse(i) { "" }, zw * (i + 0.5f), bl(sh / 2) + (1f - sugT) * dp(6), tp)
            }
            tp.alpha = 255
            paint.color = col(12); paint.alpha = (a * 255).toInt()
            c.drawRect(zw, dp(11), zw + dens, sh - dp(11), paint)
            c.drawRect(zw * 2, dp(11), zw * 2 + dens, sh - dp(11), paint)
            paint.alpha = 255
        }
        if (barT > 0.01f) {
            val zw = zone / 6f
            for (i in themes.indices) {
                val cx = zw * (i + 0.5f)
                val r = dp(13) * barT
                paint.color = themes[i][8]; paint.alpha = (barT * 255).toInt()
                c.drawCircle(cx, sh / 2, r, paint)
                paint.color = themes[i][9]
                c.drawCircle(cx + r * 0.32f, sh / 2 + r * 0.32f, r * 0.45f, paint)
                if (i == themeIdx) {
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2); paint.color = col(6)
                    c.drawCircle(cx, sh / 2, r + dp(3), paint)
                    paint.style = Paint.Style.FILL
                }
                paint.alpha = 255
            }
        }
        tp.typeface = Typeface.DEFAULT; tp.textSize = dp(17)
        val icons = listOf("✥", "◑", "⚙")
        for (i in 0..2) {
            val on = (i == 0 && edit) || (i == 1 && themeBar)
            tp.color = if (on) col(8) else col(7)
            c.drawText(icons[i], zone + dp(16) + dp(32) * i, bl(sh / 2), tp)
        }
    }

    private fun drawKey(c: Canvas, k: Key) {
        val a = k.anim
        val s = 1f - 0.045f * a
        val cx = k.x + k.w / 2
        val cy = k.y + k.h / 2
        val w = k.w * s
        val hh = k.h * s
        rect.set(cx - w / 2, cy - hh / 2, cx + w / 2, cy + hh / 2)
        val shiftOn = k.kind == 4 && svc.shift != 0
        val c0 = when { shiftOn -> col(8); k.kind == 2 -> col(9); k.kind == 1 || k.kind == 3 -> col(4); else -> col(2) }
        val c1 = when { shiftOn -> col(8); k.kind == 2 -> col(9); k.kind == 1 || k.kind == 3 -> col(5); else -> col(3) }
        paint.color = lerp(c0, c1, a)
        val r = dp(11)
        c.drawRoundRect(rect, r, r, paint)
        val sheen = col(13)
        if (Color.alpha(sheen) > 0) {
            c.save()
            c.clipRect(rect.left, rect.top, rect.right, rect.top + hh * 0.5f)
            paint.color = sheen
            c.drawRoundRect(rect, r, r, paint)
            c.restore()
        }
        paint.style = Paint.Style.STROKE; paint.strokeWidth = dens; paint.color = col(12)
        c.drawRoundRect(rect, r, r, paint)
        paint.style = Paint.Style.FILL
        var lab = k.label
        if (k.code > 0 && lab.length == 1 && svc.shift != 0) lab = lab.uppercase()
        tp.color = when { k.kind == 2 -> col(11); shiftOn -> col(10); k.kind == 1 || k.kind == 3 -> col(7); else -> col(6) }
        tp.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        tp.textSize = when { k.kind == 0 -> kh * 0.42f; k.kind == 3 -> dp(12); lab.length > 2 -> dp(12); else -> dp(19) }
        c.drawText(lab, cx, bl(cy), tp)
        if (k.kind == 3) {
            tp.textSize = dp(13); tp.color = col(7); tp.alpha = 110
            c.drawText("‹", k.x + dp(11), bl(cy), tp)
            c.drawText("›", k.x + k.w - dp(11), bl(cy), tp)
            tp.alpha = 255
        }
        if (k.kind == 4 && svc.shift == 2) {
            paint.color = col(10)
            c.drawRoundRect(cx - dp(7), k.y + k.h - dp(8), cx + dp(7), k.y + k.h - dp(5), dp(2), dp(2), paint)
        }
    }

    private fun drawPop(c: Canvas) {
        if (popT < 0.01f) return
        val al = (popT * 255).toInt()
        val pk = preview
        if (pk != null && lpKey == null) {
            val w = max(dp(48), pk.w + dp(14))
            val hh = dp(58)
            val x = (pk.x + pk.w / 2 - w / 2).coerceIn(pad, width - w - pad)
            val y = pk.y - hh - dp(8)
            val s = 0.86f + 0.14f * popT
            c.save()
            c.translate(x + w / 2, y + hh); c.scale(s, s); c.translate(-(x + w / 2), -(y + hh))
            rect.set(x, y, x + w, y + hh)
            paint.color = col(2); paint.alpha = al
            c.drawRoundRect(rect, dp(13), dp(13), paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dens
            paint.color = col(12); paint.alpha = al
            c.drawRoundRect(rect, dp(13), dp(13), paint)
            paint.style = Paint.Style.FILL; paint.alpha = 255
            tp.color = col(6); tp.alpha = al; tp.textSize = dp(27)
            tp.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            c.drawText(if (svc.shift != 0) pk.label.uppercase() else pk.label, x + w / 2, bl(y + hh / 2), tp)
            tp.alpha = 255
            c.restore()
        }
        if (lpKey == null) return
        val n = lpAlts.size
        val w = n * lpW + dp(10)
        val hh = dp(52)
        rect.set(lpX, lpY, lpX + w, lpY + hh)
        paint.color = col(1); paint.alpha = al
        c.drawRoundRect(rect, dp(14), dp(14), paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = dens
        paint.color = col(12); paint.alpha = al
        c.drawRoundRect(rect, dp(14), dp(14), paint)
        paint.style = Paint.Style.FILL
        paint.color = col(8); paint.alpha = al
        rect.set(lpSelX, lpY + dp(5), lpSelX + lpW - dp(3), lpY + hh - dp(5))
        c.drawRoundRect(rect, dp(8), dp(8), paint)
        paint.alpha = 255
        tp.textSize = dp(19); tp.alpha = al
        for (i in 0 until n) {
            tp.color = if (i == lpSel) col(10) else col(6)
            c.drawText(lpAlts[i], lpX + dp(5) + i * lpW + (lpW - dp(3)) / 2, bl(lpY + hh / 2), tp)
        }
        tp.alpha = 255
    }

    private fun maxScroll() = max(0f, ceil(emojis.size / 7f).toFloat() * (width / 7f) - (gridBot() - gridTop()))

    private fun emojiAt(x: Float, y: Float): Int {
        val cw = width / 7f
        val cc = (x / cw).toInt()
        val rr = ((y - gridTop() + scroll) / cw).toInt()
        val i = rr * 7 + cc
        return if (cc in 0..6 && i in emojis.indices) i else -1
    }

    private fun openLP(id: Int, k: Key) {
        lpOwner = id; lpKey = k; preview = null
        lpAlts = k.alts.map { if (svc.shift != 0) it.uppercaseChar().toString() else it.toString() }
        lpW = dp(38)
        val w = lpAlts.size * lpW + dp(10)
        lpX = (k.x + k.w / 2 - w / 2).coerceIn(pad, width - w - pad)
        lpY = k.y - dp(60)
        lpSel = 0; lpSelX = lpX + dp(5)
        svc.buzz(); invalidate()
    }

    private fun closeLP() { lpOwner = -1; lpKey = null; lpAlts = emptyList(); invalidate() }

    private fun barTap(x: Float) {
        val zone = width - dp(96)
        if (x > zone) {
            when (((x - zone) / dp(32)).toInt()) {
                0 -> { edit = !edit; page = 0; pageT = 0f; requestLayout(); layoutKeys() }
                1 -> themeBar = !themeBar
                else -> svc.openSettings()
            }
            invalidate(); return
        }
        if (themeBar) {
            prefs.edit().putInt("theme", (x / (zone / 6f)).toInt().coerceIn(0, themes.size - 1)).apply()
            return
        }
        val w = words.getOrElse((x / (zone / 3f)).toInt().coerceIn(0, 2)) { "" }
        if (w.isNotEmpty()) svc.pick(w)
    }

    private fun repBack(p: Press) { svc.back(); p.rep = Runnable { repBack(p) }; hd.postDelayed(p.rep!!, 55) }
    private fun repCur(p: Press, d: Int) { svc.cursor(d); p.rep = Runnable { repCur(p, d) }; hd.postDelayed(p.rep!!, 90) }

    private fun startPress(id: Int, x: Float, y: Float) {
        if (y < sh) { barTap(x); presses.put(id, Press(null, x, y, x, y, true)); return }
        if (page == 3) {
            if (y < emoTop() + chipH()) {
                emoCat = (x / (width / 7f)).toInt().coerceIn(0, 6)
                scroll = 0f; flingV = 0f; refreshEmojis(); pageT = 0f
                presses.put(id, Press(null, x, y, x, y, true)); invalidate(); return
            }
            if (y < gridBot()) {
                val i = emojiAt(x, y)
                presses.put(id, Press(null, x, y, x, y, emoji = i))
                emoPressed = i; invalidate(); return
            }
        }
        val k = keys.firstOrNull { x >= it.x && x <= it.x + it.w && y >= it.y && y <= it.y + it.h }
        if (k == null) { presses.put(id, Press(null, x, y, x, y, true)); return }
        k.down = true
        if (prefs.getBoolean("anim", true)) rips.add(Rip(k, x, y, 0f))
        val p = Press(k, x, y, x, y)
        presses.put(id, p)
        svc.click(); svc.buzz()
        when (k.code) {
            SHIFT -> { svc.toggleShift(); p.done = true }
            PAGE -> { svc.finish(); page = k.arg; edit = false; pageT = 0f; requestLayout(); layoutKeys(); p.done = true }
            EMOJI -> { svc.finish(); page = if (page == 3) 0 else 3; pageT = 0f; requestLayout(); layoutKeys(); p.done = true }
            ENTER -> { svc.enter(); p.done = true }
            SPACE -> { }
            BACK -> { svc.back(); p.done = true; p.rep = Runnable { repBack(p) }; hd.postDelayed(p.rep!!, 380) }
            SELALL -> { svc.edit(android.R.id.selectAll); p.done = true }
            COPY -> { svc.edit(android.R.id.copy); p.done = true }
            PASTE -> { svc.edit(android.R.id.paste); p.done = true }
            CUT -> { svc.edit(android.R.id.cut); p.done = true }
            LEFT -> { svc.cursor(-1); p.done = true; p.rep = Runnable { repCur(p, -1) }; hd.postDelayed(p.rep!!, 380) }
            RIGHT -> { svc.cursor(1); p.done = true; p.rep = Runnable { repCur(p, 1) }; hd.postDelayed(p.rep!!, 380) }
            HOME -> { svc.edge(true); p.done = true }
            END -> { svc.edge(false); p.done = true }
            else -> {
                preview = k
                if (k.alts.isNotEmpty()) { p.lp = Runnable { openLP(id, k) }; hd.postDelayed(p.lp!!, 320) }
            }
        }
        invalidate()
    }

    private fun movePress(id: Int, x: Float, y: Float) {
        val p = presses.get(id) ?: return
        if (lpOwner == id && lpKey != null) {
            lpSel = ((x - lpX - dp(5)) / lpW).toInt().coerceIn(0, lpAlts.size - 1)
            invalidate(); return
        }
        if (p.emoji >= 0) {
            val dy = y - p.ly
            if (abs(dy) > dp(3) || p.moved) {
                p.moved = true
                scroll = (scroll - dy).coerceIn(0f, maxScroll())
                emoPressed = -1; p.vel = -dy
            }
            p.ly = y; invalidate(); return
        }
        if (p.key != null && p.key.code == SPACE) {
            if (abs(x - p.sx) > dp(8)) p.moved = true
            if (p.moved) {
                p.acc += x - p.lx
                while (p.acc > dp(14)) { svc.cursor(1); p.acc -= dp(14) }
                while (p.acc < -dp(14)) { svc.cursor(-1); p.acc += dp(14) }
            }
            p.lx = x; return
        }
        if (!p.done && (abs(x - p.sx) > dp(16) || abs(y - p.sy) > dp(16))) {
            p.lp?.let { hd.removeCallbacks(it) }
            p.lp = null; preview = null; invalidate()
        }
    }

    private fun endPress(id: Int, ok: Boolean) {
        val p = presses.get(id) ?: return
        presses.remove(id)
        p.lp?.let { hd.removeCallbacks(it) }
        p.rep?.let { hd.removeCallbacks(it) }
        p.key?.down = false
        if (lpOwner == id) {
            if (ok && lpAlts.isNotEmpty()) svc.type(lpAlts[lpSel][0])
            closeLP()
        } else if (p.emoji >= 0) {
            if (ok && !p.moved && p.emoji in emojis.indices) {
                val e = emojis[p.emoji]; svc.text(e); addRecent(e)
            } else if (p.moved) flingV = p.vel
            emoPressed = -1
        } else if (ok && p.key != null && !p.done) {
            if (p.key.code == SPACE) { if (!p.moved) svc.space() }
            else svc.type(p.key.code.toChar())
        }
        preview = null
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                startPress(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) movePress(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                endPress(e.getPointerId(i), true)
            }
            MotionEvent.ACTION_CANCEL -> {
                val ids = ArrayList<Int>()
                for (i in 0 until presses.size()) ids.add(presses.keyAt(i))
                for (id in ids) endPress(id, false)
            }
        }
        return true
    }
}

/* ======================= AURORA HEADER ======================= */
class AuroraView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var live = true
    private fun blob(c: Canvas, x: Float, y: Float, r: Float, color: Int, a: Int) {
        val g = RadialGradient(x, y, r,
            intArrayOf(Color.argb(a, Color.red(color), Color.green(color), Color.blue(color)), Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        p.shader = g
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        p.shader = null
    }
    override fun onDraw(c: Canvas) {
        c.drawColor(Color.parseColor("#080B18"))
        val t = System.currentTimeMillis() / 1000f
        val w = width.toFloat(); val h = height.toFloat()
        blob(c, w * 0.18f + sin(t * 0.25f) * w * 0.08f, h * 0.05f, w * 0.75f, Color.parseColor("#22D3EE"), 52)
        blob(c, w * 0.88f + cos(t * 0.19f) * w * 0.07f, h * 0.25f, w * 0.68f, Color.parseColor("#F471B5"), 46)
        blob(c, w * 0.5f + sin(t * 0.15f + 1f) * w * 0.1f, h * 1.05f, w * 0.8f, Color.parseColor("#8B5CF6"), 40)
        if (live) postInvalidateDelayed(40)
    }
    override fun onWindowVisibilityChanged(v: Int) {
        super.onWindowVisibilityChanged(v)
        live = v == VISIBLE
        if (live) invalidate()
    }
}

/* ======================= LIVE MINI KEYBOARD ======================= */
class MiniKeyboard(ctx: Context) : View(ctx) {
    private val prefs = ctx.getSharedPreferences("ml", Context.MODE_PRIVATE)
    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = v * d
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val fm = Paint.FontMetrics()
    private val r = RectF()
    private val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private val seq = "mlkeyboard"
    private var stepI = 0
    private var acc = 0f
    private var last = 0L
    private var idx = prefs.getInt("theme", 0)
    private var from = idx
    private var mix = 1f
    private var live = true

    private fun mixc(i: Int): Int {
        val a = Pal.themes[from.coerceIn(0, Pal.themes.size - 1)][i]
        val b = Pal.themes[idx.coerceIn(0, Pal.themes.size - 1)][i]
        if (mix >= 1f) return b
        val q = 1f - mix
        return Color.argb(255,
            (Color.red(a) * q + Color.red(b) * mix).toInt(),
            (Color.green(a) * q + Color.green(b) * mix).toInt(),
            (Color.blue(a) * q + Color.blue(b) * mix).toInt())
    }
    private fun base(cy: Float): Float { t.getFontMetrics(fm); return cy - (fm.ascent + fm.descent) / 2f }

    override fun onDraw(c: Canvas) {
        val now = System.currentTimeMillis()
        val dt = if (last == 0L) 16f else (now - last).coerceIn(1L, 48L).toFloat()
        last = now
        val cur = prefs.getInt("theme", 0)
        if (cur != idx) { from = idx; idx = cur; mix = 0f }
        if (mix < 1f) mix = min(1f, mix + dt / 300f)
        acc += dt
        if (acc > 480f) { acc = 0f; stepI = (stepI + 1) % seq.length }
        val glow = max(0f, 1f - acc / 340f)
        val hot = seq[stepI]

        val pad = dp(9); val gap = dp(4)
        val kh = dp(25)
        r.set(0f, 0f, width.toFloat(), height.toFloat())
        p.color = mixc(0); c.drawRoundRect(r, dp(16), dp(16), p)
        p.style = Paint.Style.STROKE; p.strokeWidth = d; p.color = mixc(12)
        c.drawRoundRect(r, dp(16), dp(16), p); p.style = Paint.Style.FILL

        var y = pad
        for (ri in rows.indices) {
            val s = rows[ri]
            val inset = if (ri == 1) dp(9) else if (ri == 2) dp(16) else 0f
            val avail = width - pad * 2 - inset * 2 - gap * (s.length - 1)
            val kw = avail / s.length
            var x = pad + inset
            for (ch in s) {
                val on = ch == hot
                r.set(x, y, x + kw, y + kh)
                p.color = if (on) mixc(8) else mixc(2)
                if (on) p.alpha = (110 + 145 * glow).toInt()
                c.drawRoundRect(r, dp(6), dp(6), p)
                p.alpha = 255
                t.textSize = dp(10); t.color = if (on) mixc(10) else mixc(6)
                t.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                c.drawText(ch.toString(), x + kw / 2, base(y + kh / 2), t)
                x += kw + gap
            }
            y += kh + gap
        }
        val bw = width - pad * 2
        r.set(pad, y, pad + bw * 0.16f, y + kh)
        p.color = mixc(4); c.drawRoundRect(r, dp(6), dp(6), p)
        r.set(pad + bw * 0.19f, y, pad + bw * 0.80f, y + kh)
        p.color = mixc(4); c.drawRoundRect(r, dp(6), dp(6), p)
        t.textSize = dp(9); t.color = mixc(7)
        c.drawText("English", pad + bw * 0.495f, base(y + kh / 2), t)
        r.set(pad + bw * 0.83f, y, width - pad, y + kh)
        p.color = mixc(9); c.drawRoundRect(r, dp(6), dp(6), p)
        t.color = mixc(11); t.textSize = dp(11)
        c.drawText("⏎", pad + bw * 0.915f, base(y + kh / 2), t)
        if (live) postInvalidateOnAnimation()
    }
    override fun onWindowVisibilityChanged(v: Int) {
        super.onWindowVisibilityChanged(v)
        live = v == VISIBLE
        if (live) { last = 0L; invalidate() }
    }
}

/* ======================= THEME TILE ======================= */
class ThemeTile(ctx: Context, val index: Int) : View(ctx) {
    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = v * d
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val fm = Paint.FontMetrics()
    private val r = RectF()
    var selected = false
    var onTap: ((Int) -> Unit)? = null
    private fun base(cy: Float): Float { t.getFontMetrics(fm); return cy - (fm.ascent + fm.descent) / 2f }

    override fun onDraw(c: Canvas) {
        val th = Pal.themes[index]
        val w = width.toFloat(); val h = height.toFloat()
        r.set(d, d, w - d, h - d)
        p.color = th[0]; c.drawRoundRect(r, dp(15), dp(15), p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = if (selected) dp(2) else d
        p.color = if (selected) th[8] else th[12]
        c.drawRoundRect(r, dp(15), dp(15), p)
        p.style = Paint.Style.FILL
        val pad = dp(11)
        val kw = (w - pad * 2 - dp(8)) / 3f
        var x = pad
        for (i in 0..2) {
            r.set(x, dp(15), x + kw, dp(37))
            p.color = if (i == 1) th[8] else th[2]
            c.drawRoundRect(r, dp(6), dp(6), p)
            x += kw + dp(4)
        }
        r.set(pad, dp(43), w - pad, dp(53))
        p.color = th[4]; c.drawRoundRect(r, dp(5), dp(5), p)
        r.set(w - pad - dp(22), dp(43), w - pad, dp(53))
        p.color = th[9]; c.drawRoundRect(r, dp(5), dp(5), p)
        t.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        t.textSize = dp(11); t.color = if (selected) th[8] else th[7]
        c.drawText(Pal.names[index], w / 2, base(h - dp(16)), t)
        if (selected) {
            p.color = th[8]
            c.drawCircle(w - dp(14), dp(13), dp(4), p)
        }
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) { onTap?.invoke(index); performClick() }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}

/* ======================= SEGMENTED TABS ======================= */
class SegTabs(ctx: Context, private val labels: List<String>) : View(ctx) {
    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = v * d
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val fm = Paint.FontMetrics()
    private val r = RectF()
    private var pos = 0f
    var index = 0
    var onSelect: ((Int) -> Unit)? = null
    private fun base(cy: Float): Float { t.getFontMetrics(fm); return cy - (fm.ascent + fm.descent) / 2f }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        r.set(0f, 0f, w, h)
        p.color = Color.parseColor("#111730"); c.drawRoundRect(r, dp(14), dp(14), p)
        p.style = Paint.Style.STROKE; p.strokeWidth = d; p.color = Color.parseColor("#232C50")
        c.drawRoundRect(r, dp(14), dp(14), p); p.style = Paint.Style.FILL
        val cw = w / labels.size
        pos += (index - pos) * 0.22f
        if (abs(index - pos) < 0.002f) pos = index.toFloat()
        val x = pos * cw
        r.set(x + dp(4), dp(4), x + cw - dp(4), h - dp(4))
        p.color = Color.parseColor("#22D3EE"); p.alpha = 34
        c.drawRoundRect(r, dp(11), dp(11), p); p.alpha = 255
        p.style = Paint.Style.STROKE; p.strokeWidth = d; p.color = Color.parseColor("#6622D3EE")
        c.drawRoundRect(r, dp(11), dp(11), p); p.style = Paint.Style.FILL
        t.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        t.textSize = dp(13)
        for (i in labels.indices) {
            t.color = if (i == index) Color.parseColor("#22D3EE") else Color.parseColor("#8E99C0")
            c.drawText(labels[i], cw * (i + 0.5f), base(h / 2), t)
        }
        if (pos != index.toFloat()) postInvalidateOnAnimation()
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_UP) {
            val i = (e.x / (width / labels.size)).toInt().coerceIn(0, labels.size - 1)
            if (i != index) { index = i; onSelect?.invoke(i); performClick() }
            invalidate()
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}

/* ======================= APP ======================= */
class MainActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private val dens get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * dens).toInt()
    private val BG = Color.parseColor("#080B18")
    private val CARD = Color.parseColor("#111730")
    private val LINE = Color.parseColor("#232C50")
    private val TXT = Color.parseColor("#EDF0FF")
    private val MUT = Color.parseColor("#8E99C0")
    private val ACC = Color.parseColor("#22D3EE")
    private val OK = Color.parseColor("#34D399")
    private val WARN = Color.parseColor("#FBBF24")
    private var statusView: TextView? = null
    private val tiles = ArrayList<ThemeTile>()
    private val pages = ArrayList<LinearLayout>()
    private var seg: SegTabs? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("ml", MODE_PRIVATE)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)

        val hero = FrameLayout(this)
        val aur = AuroraView(this)
        aur.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        hero.addView(aur)
        val hc = LinearLayout(this)
        hc.orientation = LinearLayout.VERTICAL
        hc.setPadding(dp(22), dp(40), dp(22), dp(26))
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val mono = TextView(this)
        mono.text = "ML"
        mono.textSize = 17f
        mono.gravity = Gravity.CENTER
        mono.setTextColor(Color.WHITE)
        mono.setTypeface(mono.typeface, Typeface.BOLD)
        val mg = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#8B5CF6"), Color.parseColor("#F471B5")))
        mg.cornerRadius = dp(14).toFloat()
        mono.background = mg
        mono.layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
        top.addView(mono)
        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        titles.setPadding(dp(14), 0, 0, 0)
        titles.addView(label("ML Keyboard", 25f, TXT, true))
        titles.addView(label("ENGLISH  ·  VERSION 2.0", 10.5f, MUT, false))
        top.addView(titles)
        hc.addView(top)
        val st = label("Checking…", 12f, ACC, true)
        st.setPadding(dp(14), dp(8), dp(14), dp(8))
        val sg = GradientDrawable()
        sg.cornerRadius = dp(99).toFloat()
        sg.setColor(Color.parseColor("#141A33"))
        sg.setStroke(dp(1), LINE)
        st.background = sg
        val slp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        slp.topMargin = dp(18)
        st.layoutParams = slp
        hc.addView(st)
        statusView = st
        hero.addView(hc)
        root.addView(hero)

        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(16), dp(4), dp(16), dp(30))

        val prev = card("LIVE PREVIEW")
        val mini = MiniKeyboard(this)
        val mlp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(148))
        mlp.topMargin = dp(10)
        mini.layoutParams = mlp
        prev.addView(mini)
        prev.addView(label("This is your real keyboard theme. Change it below and watch it update.", 11.5f, MUT, false))
        body.addView(prev)

        val setup = card("GET STARTED")
        setup.addView(step(1, "Enable ML Keyboard", "Allow it in system settings", true) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })
        setup.addView(step(2, "Choose ML Keyboard", "Make it your active keyboard", false) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        })
        body.addView(setup)

        val tabs = SegTabs(this, listOf("Look", "Typing", "Guide"))
        val tlp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46))
        tlp.topMargin = dp(16)
        tabs.layoutParams = tlp
        tabs.onSelect = { i -> showPage(i) }
        seg = tabs
        body.addView(tabs)

        val p0 = LinearLayout(this); p0.orientation = LinearLayout.VERTICAL
        val look = card("THEME")
        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        tiles.clear()
        for (r in 0..1) {
            val rowL = LinearLayout(this)
            rowL.orientation = LinearLayout.HORIZONTAL
            val rlp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            rlp.topMargin = dp(if (r == 0) 10 else 8)
            rowL.layoutParams = rlp
            for (cc in 0..2) {
                val i = r * 3 + cc
                val tile = ThemeTile(this, i)
                val lp = LinearLayout.LayoutParams(0, dp(94), 1f)
                if (cc < 2) lp.marginEnd = dp(8)
                tile.layoutParams = lp
                tile.onTap = { k ->
                    prefs.edit().putInt("theme", k).apply()
                    markTiles()
                }
                tiles.add(tile)
                rowL.addView(tile)
            }
            grid.addView(rowL)
        }
        look.addView(grid)
        p0.addView(look)

        val size = card("KEY SIZE")
        val hv = label("${prefs.getInt("keyH", 48)} dp", 26f, ACC, true)
        val hlp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        hlp.topMargin = dp(6)
        hv.layoutParams = hlp
        size.addView(hv)
        size.addView(label("Drag to make keys taller or shorter.", 11.5f, MUT, false))
        val seek = SeekBar(this)
        seek.max = 18
        seek.progress = prefs.getInt("keyH", 48) - 44
        seek.progressTintList = ColorStateList.valueOf(ACC)
        seek.thumbTintList = ColorStateList.valueOf(ACC)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) {
                prefs.edit().putInt("keyH", 44 + v).apply()
                hv.text = "${44 + v} dp"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        size.addView(seek)
        size.addView(toggleRow("Number row", "Extra row of digits on top", "numRow", true))
        size.addView(toggleRow("Animations", "Smooth motion and key ripples", "anim", true))
        p0.addView(size)

        val p1 = LinearLayout(this); p1.orientation = LinearLayout.VERTICAL
        val feel = card("FEEDBACK")
        feel.addView(toggleRow("Key sound", "Soft click on every tap", "sound", true))
        feel.addView(toggleRow("Haptic feedback", "Small vibration on tap", "haptic", true))
        p1.addView(feel)
        val smart = card("SMART TYPING")
        smart.addView(toggleRow("Auto-capitalization", "Capitalize new sentences", "autoCap", true))
        smart.addView(toggleRow("Double-space period", "Two spaces make \". \"", "dblSpace", true))
        smart.addView(toggleRow("Learn my words", "Suggestions improve as you type", "learn", true))
        p1.addView(smart)

        val p2 = LinearLayout(this); p2.orientation = LinearLayout.VERTICAL
        val guide = card("GESTURES")
        guide.addView(guideRow("Swipe space bar", "Move the text cursor left and right"))
        guide.addView(guideRow("Long-press a letter", "Pick accents like à é ñ ü"))
        guide.addView(guideRow("Long-press . or ,", "Quick punctuation panel"))
        guide.addView(guideRow("Double-tap ⇧", "Lock capitals"))
        guide.addView(guideRow("Tap ✥", "Select all, copy, paste, arrows"))
        guide.addView(guideRow("Tap ◑", "Switch theme without leaving the app you are in"))
        p2.addView(guide)
        val priv = card("PRIVACY")
        priv.addView(label("ML Keyboard has no internet permission. Learned words, recent emoji and settings never leave your phone.", 12f, MUT, false))
        p2.addView(priv)

        pages.clear()
        pages.add(p0); pages.add(p1); pages.add(p2)
        for (i in pages.indices) {
            pages[i].visibility = if (i == 0) View.VISIBLE else View.GONE
            body.addView(pages[i])
        }

        val test = card("TRY IT HERE")
        val et = EditText(this)
        et.hint = "Type with ML Keyboard…"
        et.setTextColor(TXT)
        et.setHintTextColor(MUT)
        et.setPadding(dp(14), dp(14), dp(14), dp(14))
        val eg = GradientDrawable()
        eg.setColor(Color.parseColor("#0C1128"))
        eg.cornerRadius = dp(14).toFloat()
        eg.setStroke(dp(1), LINE)
        et.background = eg
        val elp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        elp.topMargin = dp(10)
        et.layoutParams = elp
        test.addView(et)
        body.addView(test)
        body.addView(label("ML Keyboard  ·  Made for fast, clean English typing.", 11f, MUT, false))

        root.addView(body)
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(BG)
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(root)
        setContentView(scroll)
        body.alpha = 0f
        body.translationY = dp(14).toFloat()
        body.animate().alpha(1f).translationY(0f).setDuration(420).start()
        markTiles()
    }

    override fun onResume() { super.onResume(); refresh(); markTiles() }

    private fun showPage(i: Int) {
        for (k in pages.indices) {
            val v = pages[k]
            if (k == i) {
                v.visibility = View.VISIBLE
                v.alpha = 0f
                v.translationY = dp(10).toFloat()
                v.animate().alpha(1f).translationY(0f).setDuration(260).start()
            } else v.visibility = View.GONE
        }
    }

    private fun markTiles() {
        val cur = prefs.getInt("theme", 0)
        for (i in tiles.indices) { tiles[i].selected = i == cur; tiles[i].invalidate() }
    }

    private fun refresh() {
        val s = statusView ?: return
        var enabled = false
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            for (im in imm.enabledInputMethodList) if (im.packageName == packageName) enabled = true
        } catch (e: Throwable) { enabled = false }
        val used = prefs.getBoolean("used", false)
        s.text = when {
            used && enabled -> "●  Active — ML Keyboard is in use"
            enabled -> "●  Enabled — tap step 2 below"
            else -> "●  Not enabled yet — tap step 1 below"
        }
        s.setTextColor(if (enabled) OK else WARN)
    }

    private fun label(txt: String, size: Float, color: Int, bold: Boolean): TextView {
        val v = TextView(this)
        v.text = txt
        v.textSize = size
        v.setTextColor(color)
        if (bold) v.setTypeface(v.typeface, Typeface.BOLD)
        v.setPadding(0, dp(3), 0, dp(3))
        return v
    }

    private fun card(title: String): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.setPadding(dp(16), dp(15), dp(16), dp(16))
        val g = GradientDrawable()
        g.setColor(CARD)
        g.cornerRadius = dp(20).toFloat()
        g.setStroke(dp(1), LINE)
        l.background = g
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(14)
        l.layoutParams = lp
        l.addView(label(title, 10.5f, ACC, true))
        return l
    }

    private fun step(n: Int, title: String, sub: String, primary: Boolean, fn: () -> Unit): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(10), dp(12), dp(12), dp(12))
        val g = GradientDrawable()
        g.cornerRadius = dp(15).toFloat()
        if (primary) g.setColor(Color.parseColor("#16233F")) else g.setColor(Color.parseColor("#0E1430"))
        g.setStroke(dp(1), LINE)
        row.background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33FFFFFF")), g, null)
        val badge = TextView(this)
        badge.text = n.toString()
        badge.textSize = 13f
        badge.gravity = Gravity.CENTER
        badge.setTypeface(badge.typeface, Typeface.BOLD)
        badge.setTextColor(if (primary) Color.parseColor("#04222D") else ACC)
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        if (primary) bg.setColor(ACC) else { bg.setColor(Color.TRANSPARENT); bg.setStroke(dp(2), ACC) }
        badge.background = bg
        badge.layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
        row.addView(badge)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(13), 0, 0, 0)
        col.addView(label(title, 14f, TXT, true))
        col.addView(label(sub, 11.5f, MUT, false))
        col.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(col)
        val arrow = label("›", 22f, MUT, false)
        row.addView(arrow)
        row.isClickable = true
        row.setOnClickListener { fn() }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(10)
        row.layoutParams = lp
        return row
    }

    private fun guideRow(title: String, sub: String): View {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.setPadding(0, dp(9), 0, dp(9))
        l.addView(label(title, 13.5f, TXT, true))
        l.addView(label(sub, 11.5f, MUT, false))
        return l
    }

    private fun toggleRow(title: String, sub: String, key: String, def: Boolean): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(10), 0, dp(10))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(0, 0, dp(12), 0)
        col.addView(label(title, 14f, TXT, false))
        col.addView(label(sub, 11.5f, MUT, false))
        col.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(col)
        val s = Switch(this)
        s.isChecked = prefs.getBoolean(key, def)
        s.thumbTintList = ColorStateList.valueOf(if (s.isChecked) ACC else Color.parseColor("#4A5578"))
        s.trackTintList = ColorStateList.valueOf(if (s.isChecked) Color.parseColor("#3322D3EE") else Color.parseColor("#202748"))
        s.setOnCheckedChangeListener { _, v ->
            prefs.edit().putBoolean(key, v).apply()
            s.thumbTintList = ColorStateList.valueOf(if (v) ACC else Color.parseColor("#4A5578"))
            s.trackTintList = ColorStateList.valueOf(if (v) Color.parseColor("#3322D3EE") else Color.parseColor("#202748"))
        }
        row.addView(s)
        return row
    }
}
