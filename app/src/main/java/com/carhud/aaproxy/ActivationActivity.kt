package com.carhud.aaproxy

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Patterns
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.carhud.app.BuildConfig
import com.carhud.app.R

class ActivationActivity : AppCompatActivity() {

    private lateinit var emailInput: EditText
    private lateinit var submitBtn: TextView
    private lateinit var refreshBtn: TextView
    private lateinit var statusTv: TextView
    private lateinit var statusDescTv: TextView
    private lateinit var statusCard: LinearLayout
    private lateinit var progressBar: ProgressBar

    private val pollHandler = Handler(Looper.getMainLooper())
    private var isPolling = false
    private var devTapCount = 0
    private var lastTapTime = 0L

    companion object {
        val BG_DARK = Color.parseColor("#0A1119")
        val CARD_DARK = Color.parseColor("#121A24")
        val CARD_BORDER = Color.parseColor("#1E293B")
        val ACCENT_CYAN = Color.parseColor("#0284C7")
        val ACCENT_CYAN_LIGHT = Color.parseColor("#38BDF8")
        val TEXT_WHITE = Color.parseColor("#F8FAFC")
        val TEXT_MUTED = Color.parseColor("#94A3B8")
        val COLOR_SUCCESS = Color.parseColor("#10B981")
        val COLOR_PENDING = Color.parseColor("#F59E0B")
        val COLOR_ERROR = Color.parseColor("#EF4444")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Nếu đã có bản quyền hợp lệ, chuyển thẳng vào Cài đặt
        if (LicenseManager.isLicensed(this)) {
            launchMainApp()
            return
        }

        buildUi()
        updateUiState(LicenseManager.getLicenseStatus(this))
    }

    override fun onResume() {
        super.onResume()
        if (LicenseManager.isLicensed(this)) {
            launchMainApp()
            return
        }
        val currentStatus = LicenseManager.getLicenseStatus(this)
        if (currentStatus == LicenseManager.STATUS_PENDING) {
            startPolling()
        }
    }

    override fun onPause() {
        super.onPause()
        stopPolling()
    }

    @SuppressLint("SetTextI18n")
    private fun buildUi() {
        val root = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(BG_DARK)
            isFillViewport = true
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(36), dp(20), dp(40))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(container)

        // 1. HEADER: compact THTV wordmark only
        val logoIv = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(132), dp(46)).apply {
                bottomMargin = dp(12)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            setImageResource(R.drawable.thtv_wordmark)
        }
        container.addView(logoIv)

        val subtitleTv = TextView(this).apply {
            text = "KÍCH HOẠT BẢN QUYỀN BUỒNG LÁI"
            textSize = 12f
            setTextColor(ACCENT_CYAN_LIGHT)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            letterSpacing = 0.12f
            setPadding(0, dp(4), 0, dp(8))
        }
        container.addView(subtitleTv)

        // Badge phiên bản (Có bí mật bấm 5 lần để mở khóa Test Developer)
        val versionBadge = TextView(this).apply {
            text = "v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"
            textSize = 11f
            setTextColor(TEXT_MUTED)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dp(12).toFloat()
            }
            setOnClickListener {
                handleDevBypassClick()
            }
        }
        container.addView(versionBadge)

        // SPACING
        container.addView(createSpace(24))

        // 2. CARD 1: THÔNG TIN THIẾT BỊ (DEVICE ID)
        val deviceCard = createCardLayout()
        
        val devTitle = TextView(this).apply {
            text = "🔑 MÃ THIẾT BỊ CỦA BẠN"
            textSize = 13f
            setTextColor(TEXT_MUTED)
            typeface = Typeface.DEFAULT_BOLD
            bottomMargin(dp(8))
        }
        deviceCard.addView(devTitle)

        val deviceIdStr = LicenseManager.getDeviceId(this)
        val devIdRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F172A"))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#334155"))
            }
        }

        val devIdTv = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            text = deviceIdStr
            textSize = 18f
            setTextColor(ACCENT_CYAN_LIGHT)
            typeface = Typeface.MONOSPACE
            typeface = Typeface.DEFAULT_BOLD
        }
        devIdRow.addView(devIdTv)

        val copyBtn = TextView(this).apply {
            text = "Sao chép"
            textSize = 12f
            setTextColor(TEXT_WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                setColor(ACCENT_CYAN)
                cornerRadius = dp(6).toFloat()
            }
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("THTV Device ID", deviceIdStr))
                Toast.makeText(this@ActivationActivity, "📋 Đã sao chép mã máy: $deviceIdStr", Toast.LENGTH_SHORT).show()
            }
        }
        devIdRow.addView(copyBtn)
        deviceCard.addView(devIdRow)

        val devModelTv = TextView(this).apply {
            text = "📱 ${LicenseManager.getDeviceModel()} • Android ${LicenseManager.getAndroidVersion()}"
            textSize = 11f
            setTextColor(TEXT_MUTED)
            setPadding(0, dp(8), 0, 0)
        }
        deviceCard.addView(devModelTv)
        container.addView(deviceCard)

        // SPACING
        container.addView(createSpace(16))

        // 3. CARD 2: NHẬP EMAIL & TRẠNG THÁI DUYỆT
        val actionCard = createCardLayout()

        val emailLabel = TextView(this).apply {
            text = "📧 EMAIL ĐĂNG KÝ BẢN QUYỀN"
            textSize = 13f
            setTextColor(TEXT_MUTED)
            typeface = Typeface.DEFAULT_BOLD
            bottomMargin(dp(8))
        }
        actionCard.addView(emailLabel)

        emailInput = EditText(this).apply {
            hint = "Nhập email của bạn (ví dụ: ten@gmail.com)"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(TEXT_WHITE)
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F172A"))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#334155"))
            }
            val savedEmail = LicenseManager.getLicenseEmail(this@ActivationActivity)
            if (savedEmail.isNotEmpty()) {
                setText(savedEmail)
            }
        }
        actionCard.addView(emailInput)

        // STATUS CONTAINER
        statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dp(8).toFloat()
            }
            visibility = View.GONE
            topMargin(dp(14))
        }

        val statusHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        progressBar = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                rightMargin = dp(10)
            }
            isIndeterminate = true
            visibility = View.GONE
        }
        statusHeaderRow.addView(progressBar)

        statusTv = TextView(this).apply {
            text = "Trạng thái"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TEXT_WHITE)
        }
        statusHeaderRow.addView(statusTv)
        statusCard.addView(statusHeaderRow)

        statusDescTv = TextView(this).apply {
            text = "Chi tiết trạng thái"
            textSize = 12f
            setTextColor(TEXT_MUTED)
            setPadding(0, dp(4), 0, 0)
        }
        statusCard.addView(statusDescTv)
        actionCard.addView(statusCard)

        // NÚT GỬI YÊU CẦU
        submitBtn = TextView(this).apply {
            text = "🚀 GỬI YÊU CẦU DUYỆT BẢN QUYỀN"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TEXT_WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(14))
            background = GradientDrawable().apply {
                setColor(ACCENT_CYAN)
                cornerRadius = dp(10).toFloat()
            }
            topMargin(dp(16))
            setOnClickListener {
                submitRegistration()
            }
        }
        actionCard.addView(submitBtn)

        // NÚT KIỂM TRA LẠI (Khi đang chờ duyệt)
        refreshBtn = TextView(this).apply {
            text = "🔄 KIỂM TRA LẠI NGAY"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ACCENT_CYAN_LIGHT)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), ACCENT_CYAN)
            }
            visibility = View.GONE
            topMargin(dp(10))
            setOnClickListener {
                checkLicenseNow(manual = true)
            }
        }
        actionCard.addView(refreshBtn)

        container.addView(actionCard)

        // SPACING
        container.addView(createSpace(16))

        // 4. CARD 3: HỖ TRỢ & HƯỚNG DẪN
        val helpCard = createCardLayout()

        val helpTitle = TextView(this).apply {
            text = "💡 THÔNG TIN HỖ TRỢ KÍCH HOẠT"
            textSize = 13f
            setTextColor(TEXT_MUTED)
            typeface = Typeface.DEFAULT_BOLD
            bottomMargin(dp(6))
        }
        helpCard.addView(helpTitle)

        val helpDesc = TextView(this).apply {
            text = "Sau khi bấm gửi, yêu cầu sẽ được chuyển đến Admin qua Telegram. Bạn cũng có thể gửi Mã thiết bị trực tiếp qua Zalo ${LicenseConfig.SUPPORT_NAME} (${LicenseConfig.SUPPORT_PHONE_FORMATTED}) để được kích hoạt ngay lập tức."
            textSize = 12f
            setTextColor(TEXT_MUTED)
            setLineSpacing(dp(3).toFloat(), 1f)
            bottomMargin(dp(12))
        }
        helpCard.addView(helpDesc)

        val zaloBtn = TextView(this).apply {
            text = "💬 Zalo: ${LicenseConfig.SUPPORT_NAME} (${LicenseConfig.SUPPORT_PHONE_FORMATTED})"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TEXT_WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(13), 0, dp(13))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0284C7"))
                cornerRadius = dp(8).toFloat()
            }
            setOnClickListener {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(LicenseConfig.SUPPORT_ZALO_URL))
                    startActivity(intent)
                } catch (e: Exception) {
                    val dial = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${LicenseConfig.SUPPORT_PHONE}"))
                    startActivity(dial)
                }
            }
        }
        helpCard.addView(zaloBtn)

        container.addView(helpCard)

        setContentView(root)
    }

    private fun submitRegistration() {
        val email = emailInput.text.toString().trim()
        if (email == "219171") {
            LicenseManager.setDevBypass(this, "admin@tcar.vn")
            Toast.makeText(this, "⚡ Đã mở khóa chế độ Developer thành công!", Toast.LENGTH_LONG).show()
            launchMainApp()
            return
        }

        if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            Toast.makeText(this, "⚠️ Vui lòng nhập đúng định dạng Email!", Toast.LENGTH_SHORT).show()
            emailInput.requestFocus()
            return
        }

        submitBtn.isEnabled = false
        submitBtn.text = "⏳ Đang gửi yêu cầu..."
        progressBar.visibility = View.VISIBLE
        statusCard.visibility = View.VISIBLE
        statusTv.text = "Đang gửi yêu cầu..."
        statusTv.setTextColor(COLOR_PENDING)
        statusDescTv.text = "Đang kết nối tới máy chủ Telegram..."

        LicenseManager.registerDevice(this, email) { success, status, message ->
            runOnUiThread {
                submitBtn.isEnabled = true
                submitBtn.text = "🚀 GỬI YÊU CẦU DUYỆT BẢN QUYỀN"
                progressBar.visibility = View.GONE

                if (success) {
                    if (status == LicenseManager.STATUS_APPROVED) {
                        Toast.makeText(this, "🎉 Bản quyền đã được kích hoạt!", Toast.LENGTH_LONG).show()
                        launchMainApp()
                    } else {
                        Toast.makeText(this, "🔔 Đã gửi yêu cầu tới Admin qua Telegram!", Toast.LENGTH_LONG).show()
                        updateUiState(LicenseManager.STATUS_PENDING)
                        startPolling()
                    }
                } else {
                    updateUiState(LicenseManager.STATUS_UNLICENSED)
                    statusTv.text = "Không thể gửi yêu cầu"
                    statusTv.setTextColor(COLOR_ERROR)
                    statusDescTv.text = message
                }
            }
        }
    }

    private fun checkLicenseNow(manual: Boolean) {
        if (manual) {
            refreshBtn.text = "⏳ Đang kiểm tra..."
            refreshBtn.isEnabled = false
        }

        LicenseManager.checkStatus(this) { status, plan, expiry ->
            runOnUiThread {
                if (manual) {
                    refreshBtn.text = "🔄 KIỂM TRA LẠI NGAY"
                    refreshBtn.isEnabled = true
                }

                if (status == LicenseManager.STATUS_APPROVED) {
                    stopPolling()
                    Toast.makeText(this, "🎉 Bản quyền THTV ($plan) đã được kích hoạt!", Toast.LENGTH_LONG).show()
                    updateUiState(LicenseManager.STATUS_APPROVED)
                    pollHandler.postDelayed({
                        launchMainApp()
                    }, 1200)
                } else if (status == LicenseManager.STATUS_REJECTED) {
                    stopPolling()
                    updateUiState(LicenseManager.STATUS_REJECTED)
                    if (manual) {
                        Toast.makeText(this, "❌ Yêu cầu bị từ chối bởi Admin!", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    if (manual) {
                        Toast.makeText(this, "⏳ Yêu cầu vẫn đang chờ Admin duyệt...", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun startPolling() {
        if (isPolling) return
        isPolling = true
        pollRunnable.run()
    }

    private fun stopPolling() {
        isPolling = false
        pollHandler.removeCallbacks(pollRunnable)
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!isPolling) return
            checkLicenseNow(manual = false)
            pollHandler.postDelayed(this, 4000) // Tự động kiểm tra mỗi 4 giây
        }
    }

    private fun updateUiState(status: String) {
        statusCard.visibility = View.VISIBLE
        when (status) {
            LicenseManager.STATUS_PENDING -> {
                progressBar.visibility = View.VISIBLE
                refreshBtn.visibility = View.VISIBLE
                submitBtn.text = "📤 Đổi Email & Gửi Lại"
                statusTv.text = "⏳ ĐANG CHỜ DUYỆT TRÊN TELEGRAM"
                statusTv.setTextColor(COLOR_PENDING)
                statusDescTv.text = "Admin đã nhận được thông báo. Ứng dụng sẽ tự động kích hoạt ngay khi Admin bấm duyệt!"
            }
            LicenseManager.STATUS_REJECTED -> {
                progressBar.visibility = View.GONE
                refreshBtn.visibility = View.GONE
                statusTv.text = "❌ YÊU CẦU BỊ TỪ CHỐI"
                statusTv.setTextColor(COLOR_ERROR)
                statusDescTv.text = "Yêu cầu kích hoạt không được chấp thuận. Vui lòng liên hệ Admin qua Zalo để được giải đáp."
            }
            LicenseManager.STATUS_APPROVED -> {
                progressBar.visibility = View.GONE
                refreshBtn.visibility = View.GONE
                statusTv.text = "✅ ĐÃ KÍCH HOẠT BẢN QUYỀN"
                statusTv.setTextColor(COLOR_SUCCESS)
                statusDescTv.text = "Gói: ${LicenseManager.getLicensePlan(this)} • Hạn dùng: ${LicenseManager.getLicenseExpiry(this)}"
            }
            else -> {
                progressBar.visibility = View.GONE
                refreshBtn.visibility = View.GONE
                statusCard.visibility = View.GONE
            }
        }
    }

    private fun handleDevBypassClick() {
        val now = System.currentTimeMillis()
        if (now - lastTapTime < 600) {
            devTapCount++
        } else {
            devTapCount = 1
        }
        lastTapTime = now

        if (devTapCount in 2..4) {
            Toast.makeText(this, "Nhấn thêm ${5 - devTapCount} lần để nhập mật khẩu Developer", Toast.LENGTH_SHORT).show()
        } else if (devTapCount >= 5) {
            devTapCount = 0
            showDevPasswordDialog()
        }
    }

    private fun showDevPasswordDialog() {
        val input = EditText(this).apply {
            hint = "Nhập mật khẩu Developer"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(TEXT_WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F172A"))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), ACCENT_CYAN)
            }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(10))
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("🔑 CHẾ ĐỘ DEVELOPER (DEV MODE)")
            .setMessage("Vui lòng nhập mật khẩu Developer để mở khóa ứng dụng:")
            .setView(container)
            .setPositiveButton("XÁC NHẬN") { _, _ ->
                val pass = input.text.toString().trim()
                if (pass == "219171") {
                    LicenseManager.setDevBypass(this, "admin@tcar.vn")
                    Toast.makeText(this, "⚡ Đã mở khóa chế độ Developer thành công!", Toast.LENGTH_LONG).show()
                    launchMainApp()
                } else {
                    Toast.makeText(this, "❌ Mật khẩu Developer không đúng! Vui lòng thử lại.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("HỦY") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun launchMainApp() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finish()
    }

    private fun createCardLayout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                setColor(CARD_DARK)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), CARD_BORDER)
            }
        }
    }

    private fun createSpace(heightDp: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(heightDp)
            )
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun View.topMargin(value: Int) {
        val lp = layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = value
        layoutParams = lp
    }

    private fun View.bottomMargin(value: Int) {
        val lp = layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = value
        layoutParams = lp
    }
}
