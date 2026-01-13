package com.example.sound_detection

import android.Manifest
import android.content.pm.PackageManager
import android.media.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.sound_detection.ui.theme.Sound_detectionTheme
import kotlinx.coroutines.*
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import okhttp3.*
import java.util.concurrent.TimeUnit
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.appcompat.app.AppCompatDelegate //
import androidx.compose.foundation.background
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.graphics.Color

// progressbar
private var recordingProgress by mutableStateOf(0f) // 0f = 0%, 1f = 100%





        class MainActivity : ComponentActivity() {

    // === TFLite model + UI state ===
    private lateinit var tflite: Interpreter
    private val MODEL_PATH = "my_5layer_model2.tflite"
    private var resultText by mutableStateOf("Waiting to Start...")
    private var resultText2 by mutableStateOf("")
    private var isRecording by mutableStateOf(false)
    private var isPlaying by mutableStateOf(false)
    private var lastBuffer: ShortArray? = null  // store the last recorded buffer


    // For showing both results
    private var lastPrediction: String = ""
    private var lastConfidence: String = ""
    private var lastDirection: String = ""

    // For showing both results
    private var viewPrediction: String = "unknown"
    private var viewConfidence: String = "unknown"
    private var viewDirection: String = "unknown"

    // === Audio config ===
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private var bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    private lateinit var audioRecord: AudioRecord
    private val outputLength = 5
    private var valuesFound = false




    // threading
    private val handler = Handler(Looper.getMainLooper())

    // === ESP32 WebSocket client ===
    private val esp32WebSocketUrl = "ws://192.168.4.1:81"
//    private var okHttpClient: OkHttpClient? = null
//    private var webSocket: WebSocket? = null

    private lateinit var espWebSocket: WebSocketClient
    private var isWebSocketConnected = false

    // Colors
    val darkBackground = Color(0xFF121212)
    val cardBackground = Color(0xFF1E1E1E)
    val buttonBlue = Color(0xFF1E88E5)
    val textWhite = Color.White
    val textGray = Color(0xFFB0B0B0)

    // for progress bar
    private var progressOn = false



    // Load TFLite model
    private fun loadModel(): Interpreter {
        val assetFileDescriptor = assets.openFd(MODEL_PATH)
        val fileInputStream = assetFileDescriptor.createInputStream()
        val fileChannel = fileInputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        val modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        return Interpreter(modelBuffer)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tflite = loadModel()
        setupESPWebSocket()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)



        setContent {
            Sound_detectionTheme{ // <-- force dark theme
                Column(
                        modifier = Modifier
                                .fillMaxSize()
                                .background(darkBackground)
                                .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                ) {
                    Text(
                            text = resultText,
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color(0xFFFFFFFF), // White text
                            modifier = Modifier.padding(16.dp)

                    )


                    // Linear progress bar
                    LinearProgressIndicator(
                            progress = { recordingProgress },  // lambda returning current progress
                            modifier = Modifier
                                    .fillMaxWidth()
                                    .height(20.dp)   // adjust thickness
                                    .padding(bottom = 16.dp),
                            color = Color(0xFF2962FF),      // blue color
                            trackColor = Color(0xFF555555)  // dark gray track
                    )



                    // Start/Stop toggle button
                    Button(
                            onClick = {
                    if (!isRecording) {
                        if (hasRecordAudioPermission()) {
                            resultText = "Recording..."
                            resultText2 = ""
                            startPhoneRecordingLoop()
                        } else {
                            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    } else {
                        stopRecording()
                    }
                        },
                    colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2962FF), // Blue
                            contentColor = Color.White           // Text color
                    ),
                            modifier = Modifier.padding(8.dp)
                    ) {
                        Text(if (!isRecording) "Start Recording" else "Stop Recording")
                    }




//                    // Playback button
//                    Button(
//                            onClick = {
//                                    lastBuffer?.let {
//                        if (!isPlaying) {
//                            isPlaying = true
//                        } else {
//                            // Stop playback
//                            isPlaying = false
//                            // Implement AudioTrack stop if needed
//                        }
//                    }
//                        },
//                    modifier = Modifier.padding(8.dp)
//                    ) {
//                        Text(if (!isPlaying) "Play Audio" else "Stop Audio")
//                    }
//                    Spacer(modifier = Modifier.height(16.dp))

                    // Direction and Confidence Text
                    Text(
                            text = resultText2,
                            color = Color(0xFFB0B0B0), // Light gray
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(8.dp)
                    )

                }
            }
        }
    }

    // Permission
    private val requestPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) startPhoneRecordingLoop()
        else resultText = "Permission to record audio denied."
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    // -------------------------
    // PHONE RECORDING LOOP
    // -------------------------
    private fun initializeAudioRecord() {
        bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
        )
    }

    private fun animateRecordingProgress() {
        CoroutineScope(Dispatchers.Main).launch {
            while (isRecording) {
                val totalDuration = 3000L  // 3 seconds
                val stepTime = 16L         // update ~60 FPS
                val steps = (totalDuration / stepTime).toInt()

                for (i in 1..steps) {
                    if (!isRecording) break
//                    if (!progressOn)break
                    recordingProgress = i / steps.toFloat()
                    delay(stepTime)
                }

                if(lastPrediction == ""){
                    Log.d("pause", "pause")
                    delay(100)
                }


                Log.d("Result", "Going to updateUIWithResults() function")
                recordingProgress = 0f  // reset after 3s
                // ✅ Now update UI when progress bar finishes
                if (isRecording) {
                    updateUIWithResults()
                    withContext(Dispatchers.Main) {
                        resultText = "Prediction: $viewPrediction\nDirection: $viewDirection"
                        resultText2 = "Confidence: $viewConfidence%"
                    }

                }

            }
        }
    }


    private fun startPhoneRecordingLoop() {
        initializeAudioRecord()
        isRecording = true
        animateRecordingProgress()  // start smooth progress animation


        CoroutineScope(Dispatchers.IO).launch {
            while (isRecording) {
                valuesFound= false
                audioRecord.startRecording()
//                withContext(Dispatchers.Main) {
//                    resultText = "Prediction: $viewPrediction\nDirection: $viewDirection"
//                    resultText2 = "Confidence: $viewConfidence%"
//                }
                Log.d("record", "Recording from phone for 3 seconds started")
                val buffer = ShortArray(sampleRate * 3) // 3s buffer
                var totalRead = 0
                while (totalRead < buffer.size && isRecording) {
                    val read = audioRecord.read(buffer, totalRead, buffer.size - totalRead)
                    if (read > 0) totalRead += read
                }


                lastBuffer = buffer  // store last 3-second audio


                val startTime = System.currentTimeMillis()
                Log.d("Direction", "Going to requestDirectionFromESP32() function")
                // Ask ESP32 for direction
                requestDirectionFromESP32()

                Log.d("Model", "Going to runModel() function")
                // Run ML model
                runModel(buffer)
                Log.d("Result", "Got results")
                valuesFound = true


//                Log.d("Result", "Going to updateUIWithResults() function")
                // Update UI with latest prediction, confidence, and direction
//                progressOn = false  // stop progress and reset bar
//                updateUIWithResults()

                if (isPlaying  == true){
                    playAudio(buffer)
                }
                val endTime = System.currentTimeMillis()
                Log.d("Time", "Time taken between recording: ${endTime - startTime}ms")



            }
        }
    }

    private fun runModel(buffer: ShortArray) {
        val floatInput = buffer.map { it.toFloat() / 32768.0f }.toFloatArray()
        val input2D = arrayOf(floatInput)
        val output = Array(1) { FloatArray(outputLength) }

        tflite.run(input2D, output)

        val predictedIndex = output[0].indices.maxByOrNull { output[0][it] } ?: -1
        val predictedLabel = when (predictedIndex) {
            0 -> "Other"
            1 -> "Car Horn 🚗"
            2 -> "Scream 🔊"
            3 -> "Dog Bark 🐕"
            4 -> "Calling Bell 🔔"
            else -> "Unknown"
        }
        val predictionConfidence =
                output[0].getOrNull(predictedIndex)?.let { "%.2f".format(it * 100) } ?: "N/A"

        lastPrediction = predictedLabel
        lastConfidence = predictionConfidence
        Log.d("Model", "Prediction: $predictedLabel, Confidence: $predictionConfidence")
    }

    // -------------------------
    // ESP32 WEBSOCKET
    // -------------------------
    private fun setupESPWebSocket() {
        val wsUri = URI("ws://192.168.4.1:81/")
        espWebSocket = object : WebSocketClient(wsUri) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                isWebSocketConnected = true
                Log.d("ESP32-WS", "Connected to ESP32")
            }

            override fun onMessage(message: String?) {
                message?.let {
                    lastDirection = it
//                    handler.post {
//                        resultText =
//                            "Prediction: $lastPrediction\nConfidence: $lastConfidence%\nDirection: $lastDirection"
//                    }
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                isWebSocketConnected = false
                Log.d("ESP32-WS", "WebSocket closed: $reason")
            }

            override fun onError(ex: Exception?) {
                isWebSocketConnected = false
                Log.e("ESP32-WS", "WebSocket error: ${ex?.message}")
            }
        }
        espWebSocket.connect()
    }


    private fun requestDirectionFromESP32() {
        if (isWebSocketConnected) {
            espWebSocket.send("GET_DIRECTION")
            Log.d("ESP32-WS", "Sent GET_DIRECTION")
        } else {
            Log.d("ESP32-WS", "WebSocket not connected yet")
            viewDirection = "socketFalse"
        }
    }

    // Function to Vibrate
    private fun vibrateOneSecond() {
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
                    getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        val effect = VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE)
        vibrator.vibrate(effect)
    }



    // Function to update UI
    private fun updateUIWithResults() {
        viewPrediction = lastPrediction
        viewConfidence = lastConfidence
        viewDirection = lastDirection

        if (viewDirection == "Right"){
            viewDirection = "Right ➡️"
        }
        if (viewDirection == "Left"){
            viewDirection = "Left ⬅️"
        }
        if (viewDirection == "Front"){
            viewDirection = "Front ⬆️"
        }
        if (viewDirection == "Back"){
            viewDirection = "Back ⬇️"}

        if (viewDirection == "No Sound"){
            viewDirection = "No Sound"}
        else{
            viewDirection = "Unknown"
        }


        // To activate vibration
        if (viewPrediction != "Other") {
            Log.d("vibrate", "Going to vibrateOneSecond() function because prediction is$lastPrediction")
            vibrateOneSecond()
        }
    }


    // playback
    private fun playAudio(buffer: ShortArray) {
        // Play on main thread so AudioTrack works reliably
        handler.post {
            try {
                val audioTrack = AudioTrack(
                        AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build(),
                        AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build(),
                        buffer.size * 2,
                        AudioTrack.MODE_STATIC,
                        AudioManager.AUDIO_SESSION_ID_GENERATE
                )

                val audioBuffer = ByteBuffer.allocate(buffer.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                buffer.forEach { audioBuffer.putShort(it) }
                audioTrack.write(audioBuffer.array(), 0, audioBuffer.array().size)
                audioTrack.play()

                // stop after ~3s (safe guard)
                handler.postDelayed({
                try {
                    audioTrack.stop()
                    audioTrack.release()
                } catch (e: Exception) {
                    Log.w("AudioTrack", "stop/release error: ${e.message}")
                }
                }, 3100L)
            } catch (e: Exception) {
                Log.e("AudioTrack", "Playback failed: ${e.message}")
            }
        }
    }


    // -------------------------
    // STOP
    // -------------------------
    private fun stopRecording() {
        if (!isRecording) return
                isRecording = false
        try {
            audioRecord.stop()
            audioRecord.release()
        } catch (_: Exception) {}
        resultText = "Recording stopped."
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        try { tflite.close() } catch (_: Exception) {}
        try { espWebSocket.close() } catch (_: Exception) {}

    }
}
