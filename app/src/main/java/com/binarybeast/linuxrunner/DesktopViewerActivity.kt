package com.binarybeast.linuxrunner

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.binarybeast.linuxrunner.databinding.ActivityDesktopViewerBinding
import kotlinx.coroutines.launch

/**
 * This is the screen the user actually spends their time in: it shows the
 * distro's real graphical desktop (XFCE/LXQt), not a terminal.
 *
 * VNC rendering approach: noVNC (bundled under assets/novnc/) running
 * inside a plain WebView, rather than a native VNC client library.
 * Reason: noVNC is a mature, widely-used open-source JS VNC client with a
 * small, stable, well-documented API (RFB class) — it doesn't have the
 * "unverified native API surface" problem that a native Android VNC
 * library dependency would have here. WebView + a bundled local HTML page
 * is also a much more reliable Gradle dependency: no external repo, no
 * version drift.
 *
 * Flow, all automatic:
 *   1. Bind to LinuxSessionService (starts proot in the background)
 *   2. Ask it to start the VNC server inside the rootfs
 *   3. Load novnc/vnc.html in the WebView, passing host/port/password as
 *      URL parameters, which the bundled JS reads and uses to auto-connect.
 *
 * See README section "noVNC assets" for what still needs to be placed in
 * assets/novnc/ before this compiles and runs end-to-end.
 */
class DesktopViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDesktopViewerBinding
    private var sessionService: LinuxSessionService? = null
    private lateinit var distro: Distro

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            sessionService = (service as LinuxSessionService.LocalBinder).getService()
            startDesktopAndConnect()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            sessionService = null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDesktopViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val distroId = intent.getStringExtra(EXTRA_DISTRO_ID) ?: error("EXTRA_DISTRO_ID is required")
        distro = DistroCatalog.byId(distroId)

        binding.vncWebView.settings.javaScriptEnabled = true
        binding.vncWebView.settings.domStorageEnabled = true
        // noVNC talks to vncserver over a raw TCP-like WebSocket bridge;
        // websockify (started alongside vncserver, see ProotEngine) exposes
        // that on localhost, so no network permission beyond loopback is needed.

        val serviceIntent = Intent(this, LinuxSessionService::class.java)
        serviceIntent.putExtra(LinuxSessionService.EXTRA_DISTRO_ID, distro.id)
        startForegroundService(serviceIntent)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

        setupToolbarControls()
    }

    /**
     * Wires the floating toolbar: a keyboard toggle (since the WebView's
     * canvas-rendered remote desktop won't reliably summon the system IME
     * on tap the way a normal text field would) and an explicit exit
     * button (since the system back gesture may be consumed by the remote
     * desktop instead of the app).
     */
    /** Ctrl/Alt behave as sticky modifiers: tap to arm, tap the next key, then auto-release — this is what makes Ctrl+C / Alt+Tab actually work from a touchscreen. */
    private var ctrlArmed = false
    private var altArmed = false

    private fun setupToolbarControls() {
        binding.toggleKeyboardButton.setOnClickListener { toggleKeyboard() }
        binding.exitDesktopButton.setOnClickListener { confirmExit() }
        binding.toggleSpecialKeysButton.setOnClickListener { toggleSpecialKeysRow() }

        binding.ctrlToggle.setOnCheckedChangeListener { _, checked -> ctrlArmed = checked }
        binding.altToggle.setOnCheckedChangeListener { _, checked -> altArmed = checked }

        binding.escKey.setOnClickListener { sendSpecialKeyWithModifiers("Escape") }
        binding.tabKey.setOnClickListener { sendSpecialKeyWithModifiers("Tab") }
        binding.leftKey.setOnClickListener { sendSpecialKeyWithModifiers("Left") }
        binding.rightKey.setOnClickListener { sendSpecialKeyWithModifiers("Right") }
        binding.upKey.setOnClickListener { sendSpecialKeyWithModifiers("Up") }
        binding.downKey.setOnClickListener { sendSpecialKeyWithModifiers("Down") }

        // Every character typed into the invisible trigger field is
        // forwarded to noVNC as a real keystroke via its documented
        // sendKey()-equivalent JS call, then the field is cleared — this
        // is what lets the system keyboard actually type into the remote
        // XFCE session instead of typing into an Android widget.
        binding.hiddenKeyboardTrigger.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(editable: Editable?) {
                val typed = editable?.toString().orEmpty()
                if (typed.isNotEmpty()) {
                    if (ctrlArmed || altArmed) {
                        // A regular character typed while Ctrl/Alt is armed
                        // (e.g. Ctrl then "c") is a shortcut, not text —
                        // route it through the modifier-aware path and
                        // auto-release the modifier, same as the special
                        // key buttons do.
                        sendCharWithModifiers(typed.last())
                    } else {
                        forwardTypedTextToVnc(typed)
                    }
                    editable?.clear()
                }
            }
        })
    }

    private fun toggleSpecialKeysRow() {
        val row = binding.specialKeysScroll
        row.visibility = if (row.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    /** Sends a named special key (Escape/Tab/arrows), honoring any armed Ctrl/Alt, then auto-releases the modifiers. */
    private fun sendSpecialKeyWithModifiers(keyName: String) {
        binding.vncWebView.evaluateJavascript(
            "window.binaryBeastSendSpecialKey && window.binaryBeastSendSpecialKey('$keyName', $ctrlArmed, $altArmed);",
            null
        )
        releaseModifiers()
    }

    /** Sends a plain character combined with Ctrl and/or Alt (e.g. Ctrl+C), then auto-releases the modifiers. */
    private fun sendCharWithModifiers(char: Char) {
        binding.vncWebView.evaluateJavascript(
            "window.binaryBeastSendCharWithModifiers && window.binaryBeastSendCharWithModifiers('${char}', $ctrlArmed, $altArmed);",
            null
        )
        releaseModifiers()
    }

    private fun releaseModifiers() {
        ctrlArmed = false
        altArmed = false
        binding.ctrlToggle.isChecked = false
        binding.altToggle.isChecked = false
    }

    private fun toggleKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        binding.hiddenKeyboardTrigger.requestFocus()
        imm.toggleSoftInputFromWindow(binding.hiddenKeyboardTrigger.windowToken, 0, 0)
    }

    /**
     * Sends characters to the remote desktop through noVNC's RFB instance,
     * exposed on the page as window.binaryBeastRfb by vnc.html. Uses the
     * documented sendKey()/rfb keyboard events rather than trying to
     * simulate raw key codes from Kotlin.
     */
    private fun forwardTypedTextToVnc(text: String) {
        val escaped = text.replace("\\", "\\\\").replace("'", "\\'")
        binding.vncWebView.evaluateJavascript(
            "window.binaryBeastSendText && window.binaryBeastSendText('$escaped');",
            null
        )
    }

    private fun confirmExit() {
        AlertDialog.Builder(this)
            .setTitle("إغلاق سطح المكتب؟")
            .setMessage("الجلسة هتفضل شغالة في الخلفية لحد ما توقفها من الإشعار أو تدخل تاني.")
            .setPositiveButton("رجوع للقائمة") { _, _ -> finish() }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun startDesktopAndConnect() {
        lifecycleScope.launch {
            val port = sessionService?.startDesktop() ?: return@launch
            val password = sessionService?.getVncPassword() ?: return@launch
            connectViewer(port, password)
        }
    }

    private fun connectViewer(websocketPort: Int, password: String) {
        binding.connectingLabel.text = "جاري الاتصال بسطح المكتب..."

        // vnc.html (bundled asset) reads host/port/password/autoconnect from
        // the URL query string and calls noVNC's RFB() connect API itself —
        // keeping all noVNC-specific JS in one small, inspectable file
        // instead of injecting JS strings from Kotlin.
        val url = "file:///android_asset/novnc/vnc.html" +
            "?host=127.0.0.1&port=$websocketPort&password=$password&autoconnect=true&resize=scale"

        binding.vncWebView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                binding.connectingLabel.visibility = View.GONE
            }
        }
        binding.vncWebView.loadUrl(url)
    }

    override fun onDestroy() {
        unbindService(connection)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_DISTRO_ID = "distro_id"
    }
}
