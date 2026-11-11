package com.uniformitycomp.opencapture

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*

/** Native grouped forms, with the same Camera / Captures / Connection organization as iOS. */
class OpenCaptureScreens(private val activity: Activity, private val actions: Actions) {
    interface Actions {
        fun capture(); fun apply(); fun useCurrent(); fun restart()
        fun connect(); fun discover(); fun selectController(address: String)
        fun refreshCaptures(); fun exportCapabilities(); fun checkUpdate(); fun installUpdate()
    }
    private val ink = Color.rgb(28, 36, 40)
    private val muted = Color.rgb(98, 109, 115)
    private val teal = Color.rgb(0, 122, 116)
    private val background = Color.rgb(243, 245, 247)
    private val root = column()
    private val pages = FrameLayout(activity)
    private val tabs = mutableListOf<LinearLayout>()
    private val tabLabels = mutableListOf<TextView>()
    private val tabIcons = mutableListOf<ImageView>()
    private val cameraPage = column()
    private val capturePage = scroll()
    private val connectionPage = scroll()
    private val cameraScroll = scroll()
    private val cameraControls = mutableListOf<View>()
    private val captureBar = column()
    private var previewAspect = 4.0 / 3.0
    var selectedTab = 0; private set
    var showingArchive = false
    val title = text("OpenCapture", 20f).apply { gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD) }
    val texture = TextureView(activity).apply { isOpaque = false; contentDescription = "Full camera preview" }
    val preview = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK); addView(texture, FrameLayout.LayoutParams(-1, -1)) }
    val status = text("Opening camera…")
    val connectionStatus = text("Not connected")
    val cameraInfo = text("Reading camera capabilities…", 13f, muted)
    val lenses = Spinner(activity)
    val frames = EditText(activity)
    val exposure = EditText(activity)
    val iso = EditText(activity)
    val focus = EditText(activity)
    val settle = EditText(activity)
    val wbRed = EditText(activity)
    val wbGreen = EditText(activity)
    val wbBlue = EditText(activity)
    val manualExposure = Switch(activity).apply { text = "Manual exposure" }
    val manualFocus = Switch(activity).apply { text = "Manual focus" }
    val manualWB = Switch(activity).apply { text = "Manual white balance" }
    val address = EditText(activity)
    val token = EditText(activity)
    val captures = column()
    val nearby = column()
    val updateStatus = text("Updates come from GitHub. No PC connection is needed.", 13f, muted)
    val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { visibility = View.GONE; max = 100 }
    lateinit var captureButton: Button; private set
    lateinit var connectButton: Button; private set
    lateinit var applyButton: Button; private set
    lateinit var updateCheck: Button; private set
    lateinit var updateInstall: Button; private set

    init {
        root.setBackgroundColor(background)
        root.addView(title, LinearLayout.LayoutParams(-1, dp(48)))
        root.addView(pages, LinearLayout.LayoutParams(-1, 0, 1f))
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        pages.addView(cameraPage, FrameLayout.LayoutParams(-1, -1))
        pages.addView(capturePage.first, FrameLayout.LayoutParams(-1, -1))
        pages.addView(connectionPage.first, FrameLayout.LayoutParams(-1, -1))
        buildCamera()
        buildCaptures()
        buildConnection()
        val navigation = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.WHITE); elevation = dp(4).toFloat() }
        val icons = listOf(R.drawable.tab_camera, R.drawable.tab_captures, R.drawable.tab_connection)
        for ((index, name) in listOf("Camera", "Captures", "Connection").withIndex()) {
            val tab = column().apply { gravity = Gravity.CENTER; isClickable = true; isFocusable = true; contentDescription = name }
            val icon = ImageView(activity).apply { setImageResource(icons[index]); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            val label = text(name, 12f).apply { gravity = Gravity.CENTER }
            tab.addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)))
            tab.addView(label)
            tab.setOnClickListener { selectTab(index) }
            navigation.addView(tab, LinearLayout.LayoutParams(0, dp(60), 1f))
            tabs.add(tab); tabLabels.add(label); tabIcons.add(icon)
        }
        root.addView(navigation)
        activity.setContentView(root)
        pages.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> sizePreview() }
        selectTab(0)
    }

    private fun buildCamera() {
        cameraPage.addView(cameraScroll.first, LinearLayout.LayoutParams(-1, 0, 1f))
        val form = cameraScroll.second
        val previewCard = section(form, null)
        previewCard.setPadding(0, 0, 0, dp(8))
        previewCard.addView(preview, LinearLayout.LayoutParams(-1, dp(260)))
        preview.addView(text("Processed live preview", 11f, Color.WHITE).apply {
            setPadding(dp(8), dp(3), dp(8), dp(3)); setBackgroundColor(0x88000000.toInt())
        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
        status.setPadding(dp(12), dp(8), dp(12), 0)
        previewCard.addView(status)
        val capture = section(form, "Capture")
        capture.addView(lenses); cameraControls.add(lenses)
        capture.addView(text("Bayer RAW · individual DNG originals", 14f))
        capture.addView(cameraInfo)
        field(capture, "Frames (1–32)", frames, "4")
        field(capture, "Settling (ms)", settle, "750")
        val exp = section(form, "Exposure")
        exp.addView(manualExposure); cameraControls.add(manualExposure)
        val exposureFields = column()
        field(exposureFields, "Shutter (ms)", exposure, "100")
        field(exposureFields, "ISO", iso, "100")
        exp.addView(exposureFields)
        val autoExposure = text("Auto, locked for burst", 13f, muted); exp.addView(autoExposure)
        manualExposure.setOnCheckedChangeListener { _, value -> exposureFields.visibility = visible(value); autoExposure.visibility = visible(!value) }
        exposureFields.visibility = View.GONE
        val lock = section(form, "Focus and White Balance")
        lock.addView(manualFocus); cameraControls.add(manualFocus)
        val focusFields = column()
        field(focusFields, "Focus (0 infinity, 1 nearest)", focus, "0.5")
        lock.addView(focusFields); focusFields.visibility = View.GONE
        manualFocus.setOnCheckedChangeListener { _, value -> focusFields.visibility = visible(value) }
        lock.addView(manualWB); cameraControls.add(manualWB)
        val white = column()
        field(white, "Red gain", wbRed, "1")
        field(white, "Green gain", wbGreen, "1")
        field(white, "Blue gain", wbBlue, "1")
        lock.addView(white); white.visibility = View.GONE
        manualWB.setOnCheckedChangeListener { _, value -> white.visibility = visible(value) }
        cameraControls.add(button(lock, "Use current camera settings") { actions.useCurrent() })
        applyButton = button(lock, "Apply controls") { actions.apply() }; cameraControls.add(applyButton)
        cameraControls.add(button(section(form, null), "Restart camera") { actions.restart() })
        captureBar.setPadding(dp(16), dp(4), dp(16), dp(4)); captureBar.setBackgroundColor(Color.WHITE)
        captureButton = button(captureBar, "Capture 4 frames", primary = true) { actions.capture() }
        cameraPage.addView(captureBar)
        frames.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (captureButton.isEnabled && captureButton.text.startsWith("Capture")) captureButton.text = captureLabel()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun buildCaptures() {
        button(capturePage.second, "Refresh captures") { actions.refreshCaptures() }
        capturePage.second.addView(captures)
    }

    private fun buildConnection() {
        val form = connectionPage.second
        val discovery = section(form, "Nearby controllers")
        button(discovery, "Find nearby PCs") { actions.discover() }
        discovery.addView(nearby)
        nearby.addView(text("Search on the same Wi-Fi network as your PC.", 13f, muted))
        val server = section(form, "Controller")
        field(server, "PC address", address, "", numeric = false, cameraControl = false)
        address.hint = "192.168.1.10"
        val advanced = column().apply { visibility = View.GONE }
        field(advanced, "Pairing token", token, "", numeric = false, cameraControl = false)
        token.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        advanced.addView(text("Only needed when the PC requires a token. Kept in memory.", 12f, muted))
        button(server, "Advanced connection") { advanced.visibility = if (advanced.visibility == View.GONE) View.VISIBLE else View.GONE }
        server.addView(advanced)
        connectButton = button(server, "Connect", primary = true) { actions.connect() }
        server.addView(connectionStatus)
        val updates = section(form, "Updates")
        updates.addView(text("Version " + BuildConfig.VERSION_NAME + " (build " + BuildConfig.VERSION_CODE + ")"))
        updateCheck = button(updates, "Check for updates") { actions.checkUpdate() }
        updates.addView(updateStatus); updates.addView(progress)
        updateInstall = button(updates, "Download and install", primary = true) { actions.installUpdate() }
        updateInstall.visibility = View.GONE
        button(section(form, "Camera information"), "Export camera capabilities") { actions.exportCapabilities() }
    }

    fun selectTab(index: Int) {
        activity.currentFocus?.clearFocus()
        (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(root.windowToken, 0)
        selectedTab = index
        // Keep the TextureView attached and visible underneath opaque pages. Tab changes must
        // not tear down the camera, cancel a remote lease or interrupt an in-flight original.
        capturePage.first.visibility = visible(index == 1)
        connectionPage.first.visibility = visible(index == 2)
        cameraPage.importantForAccessibility = if (index == 0) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        title.text = listOf("OpenCapture", "Captures", "Connection")[index]
        tabs.forEachIndexed { i, tab ->
            tab.isSelected = i == index
            tabLabels[i].setTextColor(if (i == index) teal else muted)
            tabIcons[i].imageTintList = ColorStateList.valueOf(if (i == index) teal else muted)
        }
        if (index == 1) actions.refreshCaptures()
        if (index == 0) sizePreview()
    }

    fun setState(busy: Boolean, remote: Boolean, updating: Boolean, hasUpdate: Boolean) {
        cameraControls.forEach { it.isEnabled = !busy && !updating }
        address.isEnabled = !busy && !updating; token.isEnabled = !busy && !updating
        connectButton.text = if (remote) "Disconnect" else "Connect"
        connectButton.isEnabled = remote || (!updating && !busy)
        captureButton.isEnabled = busy || !updating
        captureButton.text = when { remote -> "Cancel current job"; busy -> "Cancel capture"; else -> captureLabel() }
        updateCheck.isEnabled = !updating && (!busy || remote)
        updateInstall.visibility = visible(hasUpdate)
        updateInstall.isEnabled = !busy && !updating
    }

    private fun captureLabel() = "Capture " + frames.text + if (frames.text.toString() == "1") " frame" else " frames"

    fun setPreviewAspect(aspect: Double) { if (aspect.isFinite() && aspect > 0) { previewAspect = aspect; sizePreview() } }
    private fun sizePreview() {
        val width = cameraScroll.first.width - dp(32)
        val height = pages.height - captureBar.height - dp(76)
        if (width <= 0 || height <= 0) return
        val desired = minOf((width / previewAspect).toInt(), height).coerceAtLeast(1)
        if (preview.layoutParams.height != desired) preview.layoutParams = preview.layoutParams.apply { this.height = desired }
    }

    fun showControllers(controllers: Map<String, String>) {
        nearby.removeAllViews()
        if (controllers.isEmpty()) nearby.addView(text("Searching… You can also enter the PC address below.", 13f, muted))
        controllers.forEach { (name, host) ->
            button(nearby, name + "\n" + host) { actions.selectController(host) }
        }
    }

    fun section(parent: LinearLayout, name: String?): LinearLayout {
        if (name != null) parent.addView(text(name.uppercase(), 12f, muted).apply { setPadding(dp(12), dp(15), 0, dp(5)) })
        return column().also {
            it.setPadding(dp(12), dp(8), dp(12), dp(8)); it.background = rounded(Color.WHITE)
            parent.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }
    }
    fun text(value: String, size: Float = 15f, color: Int = ink) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(color); setPadding(0, dp(4), 0, dp(4))
    }
    fun button(parent: LinearLayout, label: String, primary: Boolean = false, action: () -> Unit): Button =
        Button(activity).also {
            it.text = label; it.isAllCaps = false; it.textSize = 15f
            it.setTextColor(if (primary) Color.WHITE else teal)
            if (primary) it.backgroundTintList = ColorStateList.valueOf(teal)
            it.setOnClickListener { action() }
            parent.addView(it, LinearLayout.LayoutParams(-1, -2))
        }
    private fun field(parent: LinearLayout, label: String, view: EditText, value: String, numeric: Boolean = true, cameraControl: Boolean = true) {
        parent.addView(text(label, 13f, muted))
        view.setSingleLine(); view.textSize = 16f; view.setTextColor(ink)
        view.inputType = if (numeric) android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL else android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        view.setText(value); parent.addView(view)
        if (cameraControl) cameraControls.add(view)
    }
    private fun scroll(): Pair<ScrollView, LinearLayout> {
        val content = column().apply { setPadding(dp(16), dp(12), dp(16), dp(12)) }
        val view = ScrollView(activity).apply { isFillViewport = true; setBackgroundColor(this@OpenCaptureScreens.background); addView(content) }
        return view to content
    }
    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private fun rounded(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(12).toFloat() }
    private fun visible(value: Boolean) = if (value) View.VISIBLE else View.GONE
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
