package com.sip.dialer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import android.provider.ContactsContract
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.linphone.core.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var core: Core
    private var currentCall: Call? = null
    private var isRecording = false

    // Proximity Sensor WakeLock (Screen off near ear)
    private var proximityWakeLock: PowerManager.WakeLock? = null

    // Quality tracking variables
    private var lastQualityRating = 0f
    private var lastPacketLoss = 0f
    private var lastJitter = 0f
    private var lastDelay = 0f
    private var lastCodec = "Unknown"

    // UI Elements
    private lateinit var tvStatus: TextView
    private lateinit var btnToggleAccount: Button
    private lateinit var layoutLoginCard: LinearLayout
    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var etDomain: EditText
    private lateinit var spinnerTransport: Spinner
    private lateinit var btnLogin: Button
    private lateinit var etDialNumber: EditText
    private lateinit var btnBackspace: Button
    private lateinit var btnContacts: Button
    private lateinit var btnCall: Button
    private lateinit var layoutInCall: LinearLayout
    private lateinit var tvCallState: TextView
    private lateinit var btnMute: Button
    private lateinit var btnSpeaker: Button
    private lateinit var btnRecord: Button
    private lateinit var btnHangup: Button

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
                    }
                    RegistrationState.Progress -> {
                        tvStatus.text = "● Connecting..."
                        tvStatus.setTextColor(0xFFFFD740.toInt())
                    }
                    RegistrationState.Failed -> {
                        tvStatus.text = "● Failed ($message)"
                        tvStatus.setTextColor(0xFFFF5252.toInt())
                        layoutLoginCard.visibility = View.VISIBLE
                    }
                    else -> {
                        tvStatus.text = "● Offline"
                        tvStatus.setTextColor(0xFFFF5252.toInt())
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
                        layoutInCall.visibility = View.VISIBLE
                        tvCallState.text = "Calling ${call.remoteAddress.username ?: call.remoteAddress.asStringUriOnly()}..."
                        enableProximitySensor(true)
                    }
                    Call.State.IncomingReceived -> {
                        currentCall = call
                        showIncomingCallDialog(call)
                    }
                    Call.State.Connected, Call.State.StreamsRunning -> {
                        currentCall = call
                        layoutInCall.visibility = View.VISIBLE
                        tvCallState.text = "Connected: ${call.remoteAddress.username ?: "Active Call"}"
                        enableProximitySensor(true)
                        captureCallMetrics(call)
                    }
                    Call.State.End, Call.State.Released -> {
                        enableProximitySensor(false)
                        if (isRecording) {
                            call.stopRecording()
                            isRecording = false
                            btnRecord.text = "Record"
                        }
                        layoutInCall.visibility = View.GONE
                        currentCall = null
                        showCallQualitySummary()
                    }
                    Call.State.Error -> {
                        enableProximitySensor(false)
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
        btnToggleAccount = findViewById(R.id.btnToggleAccount)
        layoutLoginCard = findViewById(R.id.layoutLoginCard)
        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        etDomain = findViewById(R.id.etDomain)
        spinnerTransport = findViewById(R.id.spinnerTransport)
        btnLogin = findViewById(R.id.btnLogin)
        etDialNumber = findViewById(R.id.etDialNumber)
        btnBackspace = findViewById(R.id.btnBackspace)
        btnContacts = findViewById(R.id.btnContacts)
        btnCall = findViewById(R.id.btnCall)
        layoutInCall = findViewById(R.id.layoutInCall)
        tvCallState = findViewById(R.id.tvCallState)
        btnMute = findViewById(R.id.btnMute)
        btnSpeaker = findViewById(R.id.btnSpeaker)
        btnRecord = findViewById(R.id.btnRecord)
        btnHangup = findViewById(R.id.btnHangup)

        val transports = arrayOf("UDP", "TCP", "TLS")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, transports)
        spinnerTransport.adapter = adapter
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
                    proximityWakeLock?.acquire(60 * 60 * 1000L) // Max 1 hour safety timeout
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

    private fun setupDialpad() {
        val dialButtons = mapOf(
            R.id.btn0 to "0", R.id.btn1 to "1", R.id.btn2 to "2",
            R.id.btn3 to "3", R.id.btn4 to "4", R.id.btn5 to "5",
            R.id.btn6 to "6", R.id.btn7 to "7", R.id.btn8 to "8",
            R.id.btn9 to "9", R.id.btnStar to "*", R.id.btnHash to "#"
        )

        for ((id, digit) in dialButtons) {
            findViewById<Button>(id).setOnClickListener { view ->
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                etDialNumber.append(digit)
                // Send DTMF if inside an active call
                currentCall?.sendDtmf(digit[0])
            }
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
            btnMute.text = if (core.isMicEnabled) "Mute" else "Unmute"
        }

        btnSpeaker.setOnClickListener {
            val currentDevice = core.outputAudioDevice
            val devices = core.audioDevices
            val speaker = devices.find { it.type == AudioDevice.Type.Speaker }
            val earpiece = devices.find { it.type == AudioDevice.Type.Earpiece }

            if (currentDevice?.type == AudioDevice.Type.Speaker && earpiece != null) {
                core.outputAudioDevice = earpiece
                btnSpeaker.text = "Speaker"
                enableProximitySensor(true)
            } else if (speaker != null) {
                core.outputAudioDevice = speaker
                btnSpeaker.text = "Earpiece"
                enableProximitySensor(false) // Keep screen on when on loudspeaker
            }
        }

        btnRecord.setOnClickListener {
            val call = currentCall ?: return@setOnClickListener
            if (!isRecording) {
                call.startRecording()
                isRecording = true
                btnRecord.text = "Stop Rec"
                Toast.makeText(this, "Recording started...", Toast.LENGTH_SHORT).show()
            } else {
                call.stopRecording()
                isRecording = false
                btnRecord.text = "Record"
                Toast.makeText(this, "Recording saved!", Toast.LENGTH_SHORT).show()
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
        val recordDir = getExternalFilesDir("Recordings")
        if (recordDir != null && !recordDir.exists()) recordDir.mkdirs()

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val recordFile = File(recordDir, "Call_$timestamp.wav")
        callParams.recordFile = recordFile.absolutePath

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
            .setTitle("Incoming SIP Call")
            .setMessage("Call from: $caller")
            .setPositiveButton("Answer") { _, _ ->
                val params = core.createCallParams(call)
                val recordDir = getExternalFilesDir("Recordings")
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                params?.recordFile = File(recordDir, "InCall_$timestamp.wav").absolutePath
                call.acceptWithParams(params)
            }
            .setNegativeButton("Decline") { _, _ ->
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
        val qualityLabel = when {
            lastQualityRating >= 4.0f -> "Excellent"
            lastQualityRating >= 3.0f -> "Good"
            lastQualityRating >= 2.0f -> "Fair"
            lastQualityRating > 0f -> "Poor"
            else -> "N/A (Call too short)"
        }

        val summary = """
            Quality Score (MOS): ${String.format(Locale.US, "%.2f", lastQualityRating)} / 5.0 ($qualityLabel)
            Packet Loss: ${String.format(Locale.US, "%.2f", lastPacketLoss)}%
            Jitter Buffer: ${String.format(Locale.US, "%.1f", lastJitter)} ms
            Round-Trip Delay: ${String.format(Locale.US, "%.0f", lastDelay)} ms
            Audio Codec: $lastCodec
            
            Recordings saved in:
            Android/data/com.sip.dialer/files/Recordings/
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Call Ended - Quality Report")
            .setMessage(summary)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        enableProximitySensor(false)
        core.removeListener(coreListener)
        core.stop()
    }
}
