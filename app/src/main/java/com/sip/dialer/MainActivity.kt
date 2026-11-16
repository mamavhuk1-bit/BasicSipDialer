package com.sip.dialer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.ContactsContract
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import org.linphone.core.*
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var core: Core
    private var currentCall: Call? = null
    private var isRecording = false
    private var currentRecordFilePath: String? = null
    private var lastSavedRecordFileName: String = "Not Recorded"
    private var mediaPlayer: MediaPlayer? = null

    private var proximityWakeLock: PowerManager.WakeLock? = null

    // Live Call Timer
    private val timerHandler = Handler(Looper.getMainLooper())
    private var callStartTimeMillis = 0L
    private var isTimerRunning = false
    private var lastFormattedDuration = "00:00"

    // Live Server Ping
    private val pingHandler = Handler(Looper.getMainLooper())
    private var isPinging = false

    // Call History Tracking
    private var activeCallNumber = ""
    private var activeCallType = "Outgoing"

    // Quality tracking variables
    private var lastQualityRating = 0f
    private var lastPacketLoss = 0f
    private var lastJitter = 0f
    private var lastDelay = 0f
    private var lastCodec = "Unknown"

    // UI Elements
    private lateinit var tvStatus: TextView
    private lateinit var tvLivePing: TextView
    private lateinit var btnNavKeypad: Button
    private lateinit var btnRecentCalls: Button
    private lateinit var btnToggleAccount: Button
    private lateinit var layoutLoginCard: LinearLayout
    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var etDomain: EditText
    private lateinit var spinnerTransport: Spinner
    private lateinit var btnLogin: Button
    private lateinit var etDialNumber: EditText
    private lateinit var btnBackspace: Button
    private lateinit var btnCall: Button
    private lateinit var btnContacts: Button
    private lateinit var btnRecordingsList: Button
    private lateinit var layoutInCall: LinearLayout
    private lateinit var tvCallState: TextView
    private lateinit var tvCallTimer: TextView
    private lateinit var btnMute: Button
    private lateinit var btnSpeaker: Button
    private lateinit var btnRecord: Button
    private lateinit var btnHangup: Button

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isTimerRunning) {
                val elapsedSec = (SystemClock.elapsedRealtime() - callStartTimeMillis) / 1000
                val mins = elapsedSec / 60
                val secs = elapsedSec % 60
                lastFormattedDuration = String.format(Locale.US, "%02d:%02d", mins, secs)
                tvCallTimer.text = "⏱ $lastFormattedDuration"
                timerHandler.postDelayed(this, 1000)
            }
        }
    }

    private val pingRunnable = object : Runnable {
        override fun run() {
            if (isPinging) {
                measureServerPing()
                pingHandler.postDelayed(this, 3000)
            }
        }
    }

    private val pickContactLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val contactUri = result.data?.data ?: return@registerForActivityResult
            val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
            contentResolver.query(contactUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val number = cursor.getString(numberIndex).replace("\\s+".toRegex(), "")
                    etDialNumber.setText(number)
                }
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.RECORD_AUDIO] != true) {
            Toast.makeText(this, "Microphone permission is required for calls!", Toast.LENGTH_LONG).show()
        }
    }

    private val coreListener = object : CoreListenerStub() {
        override fun onAccountRegistrationStateChanged(
            core: Core,
            account: Account,
            state: RegistrationState,
            message: String
        ) {
            runOnUiThread {
                when (state) {
                    RegistrationState.Ok -> {
                        tvStatus.text = "● Connected (${account.params.identityAddress?.username})"
                        tvStatus.setTextColor(0xFF69F0AE.toInt())
                        layoutLoginCard.visibility = View.GONE
                        startLivePing()
                    }
                    RegistrationState.Progress -> {
                        tvStatus.text = "● Connecting..."
                        tvStatus.setTextColor(0xFFFFD740.toInt())
                    }
                    RegistrationState.Failed -> {
                        tvStatus.text = "● Failed ($message)"
                        tvStatus.setTextColor(0xFFFF8A80.toInt())
                        layoutLoginCard.visibility = View.VISIBLE
                        stopLivePing()
                    }
                    else -> {
                        tvStatus.text = "● Offline"
                        tvStatus.setTextColor(0xFFFF8A80.toInt())
                        stopLivePing()
                    }
                }
            }
        }

        override fun onCallStateChanged(
            core: Core,
            call: Call,
            state: Call.State,
            message: String
        ) {
            runOnUiThread {
                when (state) {
                    Call.State.OutgoingInit, Call.State.OutgoingProgress, Call.State.OutgoingRinging -> {
                        currentCall = call
                        activeCallNumber = call.remoteAddress.username ?: call.remoteAddress.asStringUriOnly()
                        activeCallType = "Outgoing"
                        lastFormattedDuration = "00:00"
                        lastSavedRecordFileName = "Not Recorded"
                        layoutInCall.visibility = View.VISIBLE
                        tvCallState.text = "📞 Calling $activeCallNumber..."
                        tvCallTimer.text = "⏱ Ringing..."
                        enableProximitySensor(true)
                    }
                    Call.State.IncomingReceived -> {
                        currentCall = call
                        activeCallNumber = call.remoteAddress.username ?: call.remoteAddress.asStringUriOnly()
                        activeCallType = "Incoming"
                        lastFormattedDuration = "00:00"
                        lastSavedRecordFileName = "Not Recorded"
                        showIncomingCallDialog(call)
                    }
                    Call.State.Connected, Call.State.StreamsRunning -> {
                        currentCall = call
                        layoutInCall.visibility = View.VISIBLE
                        tvCallState.text = "🟢 Connected: $activeCallNumber"
                        startCallTimer()
                        enableProximitySensor(true)
                        captureCallMetrics(call)
                    }
                    Call.State.End, Call.State.Released -> {
                        stopCallTimer()
                        enableProximitySensor(false)
                        if (isRecording) {
                            try {
                                call.stopRecording()
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            isRecording = false
                            btnRecord.text = "⏺ Record"
                            btnRecord.setBackgroundColor(0xFF37474F.toInt())
                        }
                        if (activeCallNumber.isNotEmpty()) {
                            val finalType = if (lastFormattedDuration == "00:00" && activeCallType == "Incoming") "Missed" else activeCallType
                            saveCallToHistory(activeCallNumber, finalType, lastFormattedDuration)
                            activeCallNumber = ""
                        }
                        layoutInCall.visibility = View.GONE
                        currentCall = null
                        showCallQualitySummary()
                    }
                    Call.State.Error -> {
                        stopCallTimer()
                        enableProximitySensor(false)
                        isRecording = false
                        btnRecord.text = "⏺ Record"
                        btnRecord.setBackgroundColor(0xFF37474F.toInt())
                        if (activeCallNumber.isNotEmpty()) {
                            saveCallToHistory(activeCallNumber, "Failed", "00:00")
                            activeCallNumber = ""
                        }
                        layoutInCall.visibility = View.GONE
                        currentCall = null
                        Toast.makeText(this@MainActivity, "Call Error: $message", Toast.LENGTH_LONG).show()
                    }
                    else -> {}
                }
            }
        }

        override fun onCallStatsUpdated(core: Core, call: Call, stats: CallStats) {
            captureCallMetrics(call)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupProximitySensor()
        requestAppPermissions()
        initLinphoneCore()
        setupDialpad()
        setupListeners()
        loadSavedCredentialsAndAutoLogin()
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvLivePing = findViewById(R.id.tvLivePing)
        btnNavKeypad = findViewById(R.id.btnNavKeypad)
        btnRecentCalls = findViewById(R.id.btnRecentCalls)
        btnToggleAccount = findViewById(R.id.btnToggleAccount)
        layoutLoginCard = findViewById(R.id.layoutLoginCard)
        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        etDomain = findViewById(R.id.etDomain)
        spinnerTransport = findViewById(R.id.spinnerTransport)
        btnLogin = findViewById(R.id.btnLogin)
        etDialNumber = findViewById(R.id.etDialNumber)
        btnBackspace = findViewById(R.id.btnBackspace)
        btnCall = findViewById(R.id.btnCall)
        btnContacts = findViewById(R.id.btnContacts)
        btnRecordingsList = findViewById(R.id.btnRecordingsList)
        layoutInCall = findViewById(R.id.layoutInCall)
        tvCallState = findViewById(R.id.tvCallState)
        tvCallTimer = findViewById(R.id.tvCallTimer)
        btnMute = findViewById(R.id.btnMute)
        btnSpeaker = findViewById(R.id.btnSpeaker)
        btnRecord = findViewById(R.id.btnRecord)
        btnHangup = findViewById(R.id.btnHangup)

        val transports = arrayOf("UDP", "TCP", "TLS")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, transports)
        spinnerTransport.adapter = adapter
    }

    private fun getRecordingsDirectory(): File {
        val publicMusic = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val dir = File(publicMusic, "SIP_Recordings")
        if (!dir.exists()) {
            val created = dir.mkdirs()
            if (!created) {
                val fallback = File(getExternalFilesDir(null), "Recordings")
                if (!fallback.exists()) fallback.mkdirs()
                return fallback
            }
        }
        return dir
    }

    private fun prepareRecordFilePath(prefix: String): String {
        val dir = getRecordingsDirectory()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(dir, "${prefix}_$timestamp.wav")
        currentRecordFilePath = file.absolutePath
        return file.absolutePath
    }

    private fun startLivePing() {
        if (!isPinging) {
            isPinging = true
            pingHandler.post(pingRunnable)
        }
    }

    private fun stopLivePing() {
        isPinging = false
        pingHandler.removeCallbacks(pingRunnable)
        tvLivePing.text = "📶 -- ms"
    }

    private fun measureServerPing() {
        val rawDomain = etDomain.text.toString().trim()
        if (rawDomain.isEmpty()) return

        val parts = rawDomain.split(":")
        val host = parts[0]
        val port = if (parts.size > 1) parts[1].toIntOrNull() ?: 5060 else 5060

        thread {
            var pingMs = -1L
            try {
                val start = SystemClock.elapsedRealtime()
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), 2000)
                pingMs = SystemClock.elapsedRealtime() - start
                socket.close()
            } catch (e: Exception) {
                try {
                    val start = SystemClock.elapsedRealtime()
                    val reachable = java.net.InetAddress.getByName(host).isReachable(2000)
                    if (reachable) {
                        pingMs = SystemClock.elapsedRealtime() - start
                    }
                } catch (_: Exception) {}
            }

            runOnUiThread {
                if (pingMs >= 0) {
                    val color = when {
                        pingMs < 80 -> 0xFF69F0AE.toInt()
                        pingMs < 180 -> 0xFFFFD740.toInt()
                        else -> 0xFFFF8A80.toInt()
                    }
                    tvLivePing.text = "📶 ${pingMs} ms"
                    tvLivePing.setTextColor(color)
                } else if (currentCall != null && lastDelay > 0f) {
                    tvLivePing.text = "📶 ${lastDelay.toInt()} ms"
                    tvLivePing.setTextColor(0xFF69F0AE.toInt())
                } else {
                    tvLivePing.text = "📶 Online"
                    tvLivePing.setTextColor(0xFF80DEEA.toInt())
                }
            }
        }
    }

    private fun startCallTimer() {
        if (!isTimerRunning) {
            callStartTimeMillis = SystemClock.elapsedRealtime()
            isTimerRunning = true
            timerHandler.post(timerRunnable)
        }
    }

    private fun stopCallTimer() {
        isTimerRunning = false
        timerHandler.removeCallbacks(timerRunnable)
    }

    private fun setupProximitySensor() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximityWakeLock = powerManager.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "BasicSipDialer::ProximityLock"
            )
        }
    }

    private fun enableProximitySensor(enable: Boolean) {
        try {
            if (enable) {
                if (proximityWakeLock?.isHeld == false) {
                    proximityWakeLock?.acquire(60 * 60 * 1000L)
                }
            } else {
                if (proximityWakeLock?.isHeld == true) {
                    proximityWakeLock?.release()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun formatKeyLabel(mainDigit: String, subLetters: String): SpannableString {
        val full = if (subLetters.isEmpty()) mainDigit else "$mainDigit\n$subLetters"
        val span = SpannableString(full)
        span.setSpan(RelativeSizeSpan(1.35f), 0, mainDigit.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (subLetters.isNotEmpty()) {
            span.setSpan(RelativeSizeSpan(0.55f), mainDigit.length + 1, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return span
    }

    private fun setupDialpad() {
        val dialConfig = listOf(
            Triple(R.id.btn1, "1", ""),
            Triple(R.id.btn2, "2", "ABC"),
            Triple(R.id.btn3, "3", "DEF"),
            Triple(R.id.btn4, "4", "GHI"),
            Triple(R.id.btn5, "5", "JKL"),
            Triple(R.id.btn6, "6", "MNO"),
            Triple(R.id.btn7, "7", "PQRS"),
            Triple(R.id.btn8, "8", "TUV"),
            Triple(R.id.btn9, "9", "WXYZ"),
            Triple(R.id.btnStar, "*", ""),
            Triple(R.id.btn0, "0", "+"),
            Triple(R.id.btnHash, "#", "")
        )

        for ((id, digit, letters) in dialConfig) {
            val btn = findViewById<Button>(id)
            btn.text = formatKeyLabel(digit, letters)
            btn.setOnClickListener { view ->
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                etDialNumber.append(digit)
                currentCall?.sendDtmf(digit[0])
            }
        }

        findViewById<Button>(R.id.btn0).setOnLongClickListener {
            etDialNumber.append("+")
            true
        }

        btnBackspace.setOnClickListener {
            val text = etDialNumber.text.toString()
            if (text.isNotEmpty()) {
                etDialNumber.setText(text.substring(0, text.length - 1))
                etDialNumber.setSelection(etDialNumber.text.length)
            }
        }

        btnBackspace.setOnLongClickListener {
            etDialNumber.setText("")
            true
        }
    }

    private fun saveCallToHistory(number: String, type: String, duration: String) {
        val prefs = getSharedPreferences("SipPrefs", Context.MODE_PRIVATE)
        val historyJson = prefs.getString("call_history", "[]") ?: "[]"
        val jsonArray = JSONArray(historyJson)

        val timeStr = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date())
        val entry = JSONObject().apply {
            put("number", number)
            put("type", type)
            put("duration", duration)
            put("time", timeStr)
        }

        val updatedArray = JSONArray()
        updatedArray.put(entry)
        for (i in 0 until minOf(jsonArray.length(), 29)) {
            updatedArray.put(jsonArray.getJSONObject(i))
        }

        prefs.edit().putString("call_history", updatedArray.toString()).apply()
    }

    private fun showRecentCallsDialog() {
        val prefs = getSharedPreferences("SipPrefs", Context.MODE_PRIVATE)
        val historyJson = prefs.getString("call_history", "[]") ?: "[]"
        val jsonArray = JSONArray(historyJson)

        if (jsonArray.length() == 0) {
            Toast.makeText(this, "No recent calls yet!", Toast.LENGTH_SHORT).show()
            return
        }

        val displayItems = Array(jsonArray.length()) { i ->
            val obj = jsonArray.getJSONObject(i)
            val icon = when (obj.getString("type")) {
                "Outgoing" -> "📤"
                "Incoming" -> "📥"
                "Missed" -> "❌"
                else -> "⚠️"
            }
            val num = obj.getString("number")
            val dur = obj.getString("duration")
            val time = obj.getString("time")
            "$icon  $num  (⏱ $dur)\n      🕒 $time"
        }

        val numbers = Array(jsonArray.length()) { i ->
            jsonArray.getJSONObject(i).getString("number")
        }

        AlertDialog.Builder(this)
            .setTitle("🕒 Recent Calls (Tap to Call)")
            .setItems(displayItems) { _, which ->
                val selectedNumber = numbers[which]
                etDialNumber.setText(selectedNumber)
                makeCall(selectedNumber)
            }
            .setNeutralButton("Clear History") { _, _ ->
                prefs.edit().remove("call_history").apply()
                Toast.makeText(this, "Call history cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showRecordingsDialog() {
        val dir = getRecordingsDirectory()
        val files = dir.listFiles { file -> file.extension.equals("wav", ignoreCase = true) && file.length() > 44 }
            ?.sortedByDescending { it.lastModified() }

        if (files.isNullOrEmpty()) {
            Toast.makeText(this, "No recordings found in Music/SIP_Recordings", Toast.LENGTH_SHORT).show()
            return
        }

        val names = Array(files.size) { i ->
            val kb = files[i].length() / 1024
            "🎵 ${files[i].name} (${kb} KB)"
        }

        AlertDialog.Builder(this)
            .setTitle("🎧 Saved Call Recordings (Tap to Play)")
            .setItems(names) { _, which ->
                playRecordingFile(files[which])
            }
            .setNegativeButton("Close") { _, _ ->
                stopMediaPlayer()
            }
            .show()
    }

    private fun playRecordingFile(file: File) {
        try {
            stopMediaPlayer()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                start()
            }
            Toast.makeText(this, "Playing: ${file.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot play file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopMediaPlayer() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
    }

    private fun loadSavedCredentialsAndAutoLogin() {
        val prefs = getSharedPreferences("SipPrefs", Context.MODE_PRIVATE)
        val savedUser = prefs.getString("username", "") ?: ""
        val savedPass = prefs.getString("password", "") ?: ""
        val savedDomain = prefs.getString("domain", "") ?: ""
        val savedTransport = prefs.getString("transport", "UDP") ?: "UDP"

        if (savedUser.isNotEmpty() && savedPass.isNotEmpty() && savedDomain.isNotEmpty()) {
            etUsername.setText(savedUser)
            etPassword.setText(savedPass)
            etDomain.setText(savedDomain)
            val pos = when (savedTransport) {
                "TCP" -> 1
                "TLS" -> 2
                else -> 0
            }
            spinnerTransport.setSelection(pos)
            layoutLoginCard.visibility = View.GONE
            registerSipAccount(savedUser, savedPass, savedDomain, savedTransport)
        }
    }

    private fun saveCredentials(user: String, pass: String, domain: String, transport: String) {
        val prefs = getSharedPreferences("SipPrefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("username", user)
            .putString("password", pass)
            .putString("domain", domain)
            .putString("transport", transport)
            .apply()
    }

    private fun requestAppPermissions() {
        val needed = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS
        )
        permissionLauncher.launch(needed)
    }

    private fun initLinphoneCore() {
        val factory = Factory.instance()
        factory.setDebugMode(false, "BasicSipDialer")
        core = factory.createCore(null, null, this)
        core.addListener(coreListener)
        core.start()
    }

    private fun setupListeners() {
        btnNavKeypad.setOnClickListener {
            layoutLoginCard.visibility = View.GONE
        }

        btnRecentCalls.setOnClickListener {
            showRecentCallsDialog()
        }

        btnRecordingsList.setOnClickListener {
            showRecordingsDialog()
        }

        btnToggleAccount.setOnClickListener {
            layoutLoginCard.visibility =
                if (layoutLoginCard.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        btnLogin.setOnClickListener {
            val user = etUsername.text.toString().trim()
            val pass = etPassword.text.toString().trim()
            val domain = etDomain.text.toString().trim()
            val transportStr = spinnerTransport.selectedItem.toString()

            if (user.isEmpty() || pass.isEmpty() || domain.isEmpty()) {
                Toast.makeText(this, "Please fill all SIP fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveCredentials(user, pass, domain, transportStr)
            registerSipAccount(user, pass, domain, transportStr)
        }

        btnContacts.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
                pickContactLauncher.launch(intent)
            } else {
                requestAppPermissions()
            }
        }

        btnCall.setOnClickListener {
            val number = etDialNumber.text.toString().trim()
            if (number.isEmpty()) {
                Toast.makeText(this, "Enter a number to call", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            makeCall(number)
        }

        btnMute.setOnClickListener {
            core.isMicEnabled = !core.isMicEnabled
            btnMute.text = if (core.isMicEnabled) "🔇 Mute" else "🎙️ Unmute"
        }

        btnSpeaker.setOnClickListener {
            val currentDevice = core.outputAudioDevice
            val devices = core.audioDevices
            val speaker = devices.find { it.type == AudioDevice.Type.Speaker }
            val earpiece = devices.find { it.type == AudioDevice.Type.Earpiece }

            if (currentDevice?.type == AudioDevice.Type.Speaker && earpiece != null) {
                core.outputAudioDevice = earpiece
                btnSpeaker.text = "🔊 Speaker"
                enableProximitySensor(true)
            } else if (speaker != null) {
                core.outputAudioDevice = speaker
                btnSpeaker.text = "📱 Earpiece"
                enableProximitySensor(false)
            }
        }

        btnRecord.setOnClickListener {
            val call = currentCall ?: return@setOnClickListener
            if (call.state != Call.State.StreamsRunning && call.state != Call.State.Connected) {
                Toast.makeText(this, "Wait until call connects to record!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!isRecording) {
                try {
                    call.startRecording()
                    isRecording = true
                    btnRecord.text = "⏹ Stop"
                    btnRecord.setBackgroundColor(0xFFD50000.toInt())
                    lastSavedRecordFileName = File(currentRecordFilePath ?: "").name
                    Toast.makeText(this, "🔴 Recording started...", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Recording failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } else {
                try {
                    call.stopRecording()
                } catch (_: Exception) {}
                isRecording = false
                btnRecord.text = "⏺ Record"
                btnRecord.setBackgroundColor(0xFF37474F.toInt())
                Toast.makeText(this, "✅ Saved in Music/SIP_Recordings", Toast.LENGTH_SHORT).show()
            }
        }

        btnHangup.setOnClickListener {
            currentCall?.terminate()
        }
    }

    private fun registerSipAccount(username: String, password: String, domain: String, transportStr: String) {
        core.clearAccounts()
        core.clearAllAuthInfo()

        val transport = when (transportStr) {
            "TCP" -> TransportType.Tcp
            "TLS" -> TransportType.Tls
            else -> TransportType.Udp
        }

        val authInfo = Factory.instance().createAuthInfo(username, null, password, null, null, domain, null)
        core.addAuthInfo(authInfo)

        val accountParams = core.createAccountParams()
        val identity = Factory.instance().createAddress("sip:$username@$domain")
        accountParams.identityAddress = identity

        val serverAddress = Factory.instance().createAddress("sip:$domain")
        serverAddress?.transport = transport
        accountParams.serverAddress = serverAddress
        accountParams.isRegisterEnabled = true

        val account = core.createAccount(accountParams)
        core.addAccount(account)
        core.defaultAccount = account
    }

    private fun makeCall(destination: String) {
        val domain = etDomain.text.toString().trim()
        val sipUri = if (destination.startsWith("sip:")) destination else "sip:$destination@$domain"
        val remoteAddress = Factory.instance().createAddress(sipUri) ?: return

        val callParams = core.createCallParams(null) ?: return
        callParams.isAudioEnabled = true
        callParams.recordFile = prepareRecordFilePath("OutCall")

        lastQualityRating = 0f
        lastPacketLoss = 0f
        lastJitter = 0f
        lastDelay = 0f
        lastCodec = callParams.usedAudioPayloadType?.mimeType ?: "Default"

        core.inviteAddressWithParams(remoteAddress, callParams)
    }

    private fun showIncomingCallDialog(call: Call) {
        val caller = call.remoteAddress.username ?: call.remoteAddress.asStringUriOnly()
        AlertDialog.Builder(this)
            .setTitle("📲 Incoming SIP Call")
            .setMessage("Caller: $caller")
            .setPositiveButton("✅ Answer") { _, _ ->
                val params = core.createCallParams(call)
                params?.isAudioEnabled = true
                params?.recordFile = prepareRecordFilePath("InCall")
                call.acceptWithParams(params)
            }
            .setNegativeButton("❌ Decline") { _, _ ->
                call.decline(Reason.Declined)
            }
            .setCancelable(false)
            .show()
    }

    private fun captureCallMetrics(call: Call) {
        lastQualityRating = call.currentQuality
        val stats = call.audioStats
        if (stats != null) {
            lastPacketLoss = stats.receiverLossRate
            lastJitter = stats.jitterBufferSizeMs
            lastDelay = stats.roundTripDelay * 1000f
        }
        call.currentParams?.usedAudioPayloadType?.let {
            lastCodec = "${it.mimeType}/${it.clockRate}"
        }
    }

    private fun showCallQualitySummary() {
        val (qualityEmoji, qualityLabel) = when {
            lastQualityRating >= 4.0f -> "🌟" to "Excellent"
            lastQualityRating >= 3.0f -> "✅" to "Good"
            lastQualityRating >= 2.0f -> "⚠️" to "Fair"
            lastQualityRating > 0f -> "🔴" to "Poor"
            else -> "ℹ️" to "N/A (Short Call)"
        }

        val summary = """
            ⏱️ Call Duration: $lastFormattedDuration
            ────────────────────────
            $qualityEmoji Quality (MOS): ${String.format(Locale.US, "%.2f", lastQualityRating)} / 5.0 ($qualityLabel)
            📉 Packet Loss: ${String.format(Locale.US, "%.2f", lastPacketLoss)}%
            📶 Jitter Buffer: ${String.format(Locale.US, "%.1f", lastJitter)} ms
            ⚡ Latency / Delay: ${String.format(Locale.US, "%.0f", lastDelay)} ms
            🎙️ Audio Codec: $lastCodec
            ────────────────────────
            📁 Recording: $lastSavedRecordFileName
            📂 Folder: Music/SIP_Recordings/
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("📊 Call Summary & Quality")
            .setMessage(summary)
            .setPositiveButton("👍 OK", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCallTimer()
        stopLivePing()
        stopMediaPlayer()
        enableProximitySensor(false)
        core.removeListener(coreListener)
        core.stop()
    }
}
