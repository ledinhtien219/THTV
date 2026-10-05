package com.carhud.aaproxy

import android.content.BroadcastReceiver
import com.carhud.app.R
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.roundToInt

class PhoneSearchActivity : AppCompatActivity() {

    private lateinit var searchInput: EditText
    private var boundInput: CarInputSession? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val dismissListener: () -> Unit = {
        mainHandler.post {
            if (!isFinishing && !isDestroyed) {
                finish()
            }
        }
    }

    private val exitReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == CarAppShutdownManager.ACTION_FULL_EXIT) {
                finishAndRemoveTask()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val exitFilter = IntentFilter(CarAppShutdownManager.ACTION_FULL_EXIT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(exitReceiver, exitFilter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(exitReceiver, exitFilter)
        }

        // Show over lock screen and wake screen so user can type immediately
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        CarMediaManager.registerSearchDismissListener(dismissListener)

        val sessionId = intent.getStringExtra("INPUT_SESSION_ID")
        boundInput = sessionId?.let { CarMediaManager.phoneInput(it) }
        if (sessionId != null && (boundInput == null || boundInput?.closed == true)) {
            Toast.makeText(this, "Ô nhập đã đóng. Chạm lại ô cần nhập trên xe.", Toast.LENGTH_LONG).show()
            finish(); return
        }
        val initialQuery = boundInput?.text ?: intent.getStringExtra("INITIAL_QUERY").orEmpty()

        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#B3000000"))
            setOnClickListener {
                finish()
            }
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.parseColor("#131E2A"), 18f, Color.parseColor("#00E5FF"), 2)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            isClickable = true
        }

        // 1. Header row
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }

            val icon = TextView(this@PhoneSearchActivity).apply {
                text = "📱"
                textSize = 20f
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = dp(8)
                }
            }
            addView(icon)

            val titleCol = LinearLayout(this@PhoneSearchActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

                val title = TextView(this@PhoneSearchActivity).apply {
                    text = "Bàn phím CarHUD cho Ô tô"
                    textSize = 15.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                }
                addView(title)

                val subtitle = TextView(this@PhoneSearchActivity).apply {
                    text = if (boundInput != null) "Nhập vào đúng ô đang chọn trên xe" else "Tìm YouTube bằng nút Tìm"
                    textSize = 11.5f
                    setTextColor(Color.parseColor("#80D8FF"))
                }
                addView(subtitle)
            }
            addView(titleCol)

            val closeBtn = TextView(this@PhoneSearchActivity).apply {
                text = "✕"
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#FF5252"))
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener { finish() }
            }
            addView(closeBtn)
        }
        card.addView(header)

        // 2. Search Input Row
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.parseColor("#1B2938"), 12f, Color.parseColor("#37474F"), 1)
            setPadding(dp(12), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }

            searchInput = EditText(this@PhoneSearchActivity).apply {
                hint = boundInput?.hint ?: "Nhập tên bài hát hoặc video YouTube..."
                setHintTextColor(Color.parseColor("#78909C"))
                setTextColor(Color.WHITE)
                textSize = 15.5f
                typeface = Typeface.DEFAULT_BOLD
                background = null
                isSingleLine = true
                imeOptions = EditorInfo.IME_ACTION_DONE
                inputType = android.text.InputType.TYPE_CLASS_TEXT
                if (initialQuery.isNotEmpty()) {
                    setText(initialQuery)
                    setSelection(initialQuery.length)
                }

                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        if (boundInput != null) boundInput?.update(s?.toString().orEmpty())
                        else CarMediaManager.updateSearchText(s?.toString().orEmpty())
                    }
                    override fun afterTextChanged(s: Editable?) {}
                })

                setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                        commitKeyboardText()
                        true
                    } else false
                }

                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                        commitKeyboardText()
                        true
                    } else false
                }
            }
            addView(searchInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val clearBtn = TextView(this@PhoneSearchActivity).apply {
                text = "⌫"
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#FFB300"))
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener {
                    searchInput.setText("")
                }
            }
            addView(clearBtn)

            val goBtn = TextView(this@PhoneSearchActivity).apply {
                text = if (boundInput != null) "↵ Nhập" else "🔍 Tìm"
                textSize = 14.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                background = rounded(Color.parseColor("#1E88E5"), 10f, 0, 0)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener {
                    executeSubmit()
                }
            }
            addView(goBtn)
        }
        card.addView(inputRow)

        // 3. Quick Suggestion Chips
        val chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            val chipRow = LinearLayout(this@PhoneSearchActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                val suggestions = arrayOf("Nhạc trẻ", "Remix", "Nhạc chill", "Bolero", "EDM", "Nhạc Tết", "Karaoke", "Top Hits", "Lofi")
                for (s in suggestions) {
                    val chip = TextView(this@PhoneSearchActivity).apply {
                        text = s
                        textSize = 12.5f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(Color.parseColor("#80D8FF"))
                        background = rounded(Color.parseColor("#1E2D3D"), 8f, Color.parseColor("#37474F"), 1)
                        setPadding(dp(12), dp(5), dp(12), dp(5))
                        setOnClickListener {
                            searchInput.setText(s)
                            executeSubmit()
                        }
                    }
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        rightMargin = dp(6)
                    }
                    addView(chip, lp)
                }
            }
            addView(chipRow)
        }
        if (boundInput == null) card.addView(chipScroll)

        val cardParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
            leftMargin = dp(20)
            rightMargin = dp(20)
        }
        rootLayout.addView(card, cardParams)

        setContentView(rootLayout)
    }

    private fun commitKeyboardText() {
        if (boundInput != null) executeSubmit()
        else (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    private fun executeSubmit() {
        boundInput?.let { input ->
            input.submit(searchInput.text.toString()) { accepted ->
                if (accepted) finish()
                else searchInput.error = "Ô nhập đã thay đổi. Chạm lại ô trên xe."
            }
            return
        }
        val q = searchInput.text.toString().trim()
        if (q.isNotEmpty()) {
            CarMediaManager.submitSearchQuery(q)
            Toast.makeText(this, "Đang tìm trên xe: $q", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || !::searchInput.isInitialized) return
        searchInput.postDelayed({
            searchInput.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
        }, 150)
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) boundInput?.cancel()
        try { unregisterReceiver(exitReceiver) } catch (e: Exception) {}
        CarMediaManager.unregisterSearchDismissListener(dismissListener)
        CarMediaManager.cancelPhoneSearchNotification(this)
        super.onDestroy()
    }

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int, strokeDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeDp > 0) {
                setStroke(dp(strokeDp), stroke)
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()
}
