package com.example.test

import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Bundle
import android.os.CountDownTimer
import android.telephony.SmsManager
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID

class MainActivity : AppCompatActivity() {

    // --- CONFIGURATION ---
    private val DEVICE_NAME = "ESP32_CrashLogger"
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    // --- UI & STATE ---
    private lateinit var tvStatus: TextView
    private lateinit var tvCountdown: TextView
    private lateinit var etPhone: EditText
    private lateinit var btnSavePhone: Button
    private lateinit var switchSound: Switch

    private var bluetoothSocket: BluetoothSocket? = null
    @Volatile private var isConnected = false
    @Volatile private var isReconnecting = true

    private var sosTimer: CountDownTimer? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var sharedPrefs: SharedPreferences
    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI
        tvStatus = findViewById(R.id.tvStatus)
        tvCountdown = findViewById(R.id.tvCountdown)
        etPhone = findViewById(R.id.etPhoneNumber)
        btnSavePhone = findViewById(R.id.btnSaveNumber)
        switchSound = findViewById(R.id.switchSosSound)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sharedPrefs = getSharedPreferences("CrashAppPrefs", Context.MODE_PRIVATE)

        loadSavedPreferences()
        setupListeners()
        requestPermissions()
        startSeamlessAutoConnect()
    }

    private fun loadSavedPreferences() {
        val savedNumber = sharedPrefs.getString("EMERGENCY_PHONE", "")
        val isSoundEnabled = sharedPrefs.getBoolean("PLAY_SOUND", true)

        etPhone.setText(savedNumber)
        switchSound.isChecked = isSoundEnabled
    }

    private fun setupListeners() {
        btnSavePhone.setOnClickListener {
            val number = etPhone.text.toString().trim()
            if (number.isNotEmpty()) {
                sharedPrefs.edit().putString("EMERGENCY_PHONE", number).apply()
                Toast.makeText(this, "Emergency Contact Saved", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Please enter a valid number", Toast.LENGTH_SHORT).show()
            }
        }

        switchSound.setOnCheckedChangeListener { _, isChecked ->
            sharedPrefs.edit().putBoolean("PLAY_SOUND", isChecked).apply()
        }
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.SEND_SMS
        )
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, permissions, 101)
        }
    }

    private fun startSeamlessAutoConnect() {
        Thread {
            while (isReconnecting) {
                if (!isConnected) {
                    runOnUiThread {
                        tvStatus.text = "Searching for ESP32..."
                        tvStatus.setTextColor(android.graphics.Color.YELLOW)
                    }
                    connectToESP32()
                }
                Thread.sleep(3000)
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun connectToESP32() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter

        val device = adapter?.bondedDevices?.find { it.name == DEVICE_NAME }
        if (device == null) {
            runOnUiThread {
                tvStatus.text = "Error: ESP32 not paired!"
                tvStatus.setTextColor(android.graphics.Color.RED)
            }
            return
        }

        try {
            bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            bluetoothSocket?.connect()

            isConnected = true
            runOnUiThread {
                tvStatus.text = "Connected & Monitoring"
                tvStatus.setTextColor(android.graphics.Color.GREEN)
            }

            listenForData()

        } catch (e: Exception) {
            isConnected = false
            try { bluetoothSocket?.close() } catch (ex: Exception) {}
        }
    }

    private fun listenForData() {
        val inputStream = bluetoothSocket?.inputStream ?: return
        val reader = BufferedReader(InputStreamReader(inputStream))

        while (isConnected) {
            try {
                val line = reader.readLine()
                if (line != null) {
                    if (line.contains("CMD:CRASH_DETECTED")) {
                        runOnUiThread { startSosProtocol() }
                    } else if (line.contains("CMD:SOS_CANCELLED")) {
                        runOnUiThread { cancelSosProtocol() }
                    }
                }
            } catch (e: Exception) {
                isConnected = false
                runOnUiThread {
                    tvStatus.text = "Connection Lost"
                    tvStatus.setTextColor(android.graphics.Color.RED)
                }
                break
            }
        }
    }

    private fun startSosProtocol() {
        tvStatus.text = "CRASH DETECTED!"
        tvStatus.setTextColor(android.graphics.Color.RED)

        // Play alarm sound if switch is checked
        if (switchSound.isChecked) {
            playSosSound()
        }

        sosTimer?.cancel()
        sosTimer = object : CountDownTimer(30000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = millisUntilFinished / 1000
                tvCountdown.text = "$secondsLeft"
                tvCountdown.setTextColor(android.graphics.Color.RED)
            }

            override fun onFinish() {
                tvCountdown.text = "SENT"
                stopSosSound()
                fetchLocationAndSendSMS()
            }
        }.start()
    }

    private fun cancelSosProtocol() {
        sosTimer?.cancel()
        stopSosSound()
        tvStatus.text = "Connected & Monitoring"
        tvStatus.setTextColor(android.graphics.Color.GREEN)
        tvCountdown.text = "SAFE"
        tvCountdown.setTextColor(android.graphics.Color.GREEN)
    }

    private fun playSosSound() {
        try {
            val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            mediaPlayer = MediaPlayer.create(applicationContext, alarmSound)
            mediaPlayer?.isLooping = true
            mediaPlayer?.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopSosSound() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    @SuppressLint("MissingPermission")
    private fun fetchLocationAndSendSMS() {
        val phoneNumber = sharedPrefs.getString("EMERGENCY_PHONE", "") ?: ""
        if (phoneNumber.isEmpty()) {
            Toast.makeText(this, "Error: No emergency contact saved!", Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(this, "Acquiring precise GPS lock...", Toast.LENGTH_SHORT).show()

        // Request a fresh, high-accuracy location instead of relying on the cache
        val cancellationTokenSource = CancellationTokenSource()
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationTokenSource.token)
            .addOnSuccessListener { location ->
                if (location != null) {
                    val mapsLink = "https://maps.google.com/?q=${location.latitude},${location.longitude}"
                    val message = "EMERGENCY: Motorbike crash detected! Location: $mapsLink"

                    try {
                        val smsManager = SmsManager.getDefault()
                        smsManager.sendTextMessage(phoneNumber, null, message, null, null)
                        Toast.makeText(this, "Emergency SMS Sent!", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "Error: SMS failed to send", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(this, "Error: GPS signal lost. Try moving outdoors.", Toast.LENGTH_LONG).show()
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Error: Could not retrieve location", Toast.LENGTH_LONG).show()
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        isReconnecting = false
        isConnected = false
        try { bluetoothSocket?.close() } catch (e: Exception) {}
        sosTimer?.cancel()
        stopSosSound()
    }
}