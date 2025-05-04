//package com.example.sound_detection
//
//import android.Manifest
//import android.content.pm.PackageManager
//import android.media.AudioFormat
//import android.media.AudioRecord
//import android.media.MediaRecorder
//import android.os.Bundle
//import android.os.Handler
//import android.os.Looper
//import android.util.Log
//import androidx.activity.ComponentActivity
//import androidx.activity.compose.setContent
//import androidx.activity.result.contract.ActivityResultContracts
//import androidx.compose.foundation.layout.*
//import androidx.compose.material3.*
//import androidx.compose.runtime.*
//import androidx.compose.ui.Alignment
//import androidx.compose.ui.Modifier
//import androidx.compose.ui.unit.dp
//import androidx.core.content.ContextCompat
//import com.example.sound_detection.ui.theme.Sound_detectionTheme
//import java.io.IOException
//import java.nio.ByteBuffer
//import java.nio.ByteOrder
//import java.util.concurrent.Executors
//import java.util.concurrent.ScheduledExecutorService
//import java.util.concurrent.TimeUnit
//import com.chaquo.python.Python
//import com.chaquo.python.android.AndroidPlatform
//import kotlin.reflect.typeOf
//import kotlinx.coroutines.*
//
//
//class MainActivity : ComponentActivity() {
//
//    private lateinit var soundClassifier: SoundClassifier
//    private lateinit var labels: List<String>
//    private var detectionResult by mutableStateOf("Waiting to Start...")
//    private var confidence by mutableStateOf("")
//    private var isRecording by mutableStateOf(false)
//    private lateinit var audioRecord: AudioRecord
//    private lateinit var recordingThread: Thread
//    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
//    private val handler = Handler(Looper.getMainLooper())
//
//    private val sampleRate = 22050
//    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
//    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
//    private var bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
//    private lateinit var audioData: ShortArray
//    private lateinit var byteBuffer: ByteBuffer
//    private val inputLength = 128 * 128
//    private val outputLength = 4
//    private val recordingIntervalMillis: Long = 3000
//    private val MODEL_PATH = "sound_classifier_2_17.tflite"
//
//    private val requestPermissionLauncher =
//            registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
//        if (isGranted) {
//            initializeAudioRecord()
//            startRecordingAndAnalysis()
//        } else {
//            detectionResult = "Audio recording permission denied."
//        }
//    }
//    private val mainScope = CoroutineScope(Dispatchers.Main + Job())  // coroutine scope
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//
//        labels = listOf("other", "car horn", "scream", "dog bark")
//        Log.d("MainActivity", "in onCreate")
//
//        try {
//            soundClassifier = SoundClassifier(this, outputLength,sampleRate, 128, 128, 128)
//        } catch (e: IOException) {
//            detectionResult = "Failed to load TFLite model."
//            e.printStackTrace()
//        }
//        if (! Python.isStarted()) {
//            Python.start(AndroidPlatform(this))
//        }
//
//
//        setContent {
//            Sound_detectionTheme {
//                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
//                        Column(
//                                modifier = Modifier
//                                        .fillMaxSize()
//                                        .padding(innerPadding)
//                                        .padding(16.dp),
//                                horizontalAlignment = Alignment.CenterHorizontally,
//                                verticalArrangement = Arrangement.Center
//                        ) {
//                    Text(text = "Detected: ${detectionResult.uppercase()}", style = MaterialTheme.typography.headlineMedium)
//                    if (confidence.isNotEmpty()) {
//                        Text(text = "Confidence: $confidence", style = MaterialTheme.typography.bodyLarge)
//                    }
//                    Spacer(modifier = Modifier.height(32.dp))
//                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
//                        Button(onClick = { checkAndRequestAudioPermission() }, enabled = !isRecording) {
//                            detectionResult = "start recording"
//                            Text("Start")
//                        }
//                        Button(onClick = { stopRecordingAndAnalysis() }, enabled = isRecording) {
//                            Text("Stop")
//                        }
//                    }
//                }
//                }
//            }
//        }
//    }
//
//    private fun checkAndRequestAudioPermission() {
//        if (ContextCompat.checkSelfPermission(
//                this,
//                Manifest.permission.RECORD_AUDIO
//        ) == PackageManager.PERMISSION_GRANTED
//        ) {
//            initializeAudioRecord()
//            startRecordingAndAnalysis()
//        } else {
//            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
//        }
//    }
//
//    private fun initializeAudioRecord() {
//        if (bufferSize <= 0) {
//            Log.e("MainActivity", "Error: Invalid buffer size: $bufferSize")
//            bufferSize = 8192
//        }
//
//        try {
//            audioRecord = AudioRecord(
//                    MediaRecorder.AudioSource.MIC,
//                    sampleRate,
//                    channelConfig,
//                    audioFormat,
//                    bufferSize
//            )
//
//            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
//                Log.e("MainActivity", "Error initializing AudioRecord")
//                detectionResult = "Error initializing AudioRecord"
//                return
//            }
//
//            audioData = ShortArray(bufferSize / 2)
//            byteBuffer = ByteBuffer.allocateDirect(inputLength * 2)
//            byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
//        } catch (e: SecurityException) {
//            Log.e("MainActivity", "SecurityException: ${e.message}")
//            detectionResult = "Permission denied for audio recording."
//            return
//        }
//    }
//
//    // sets up the AudioRecord properly with a buffer large enough to hold 3 seconds of mono audio at 22050 Hz.
//    private fun createAudioRecord(): AudioRecord {
//        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
//        val threeSecBufferSize = sampleRate * 3 * 2  // 3 seconds * 22050 samples/sec * 2 bytes per sample
//        val finalBufferSize = maxOf(minBufferSize, threeSecBufferSize)
//
//        return AudioRecord(
//                MediaRecorder.AudioSource.MIC,
//                sampleRate,
//                channelConfig,
//                audioFormat,
//                finalBufferSize
//        )
//    }
//
//
//    private fun startRecordingAndAnalysis() {
//        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
//            Log.e("MainActivity", "AudioRecord not initialized")
//            detectionResult = "AudioRecord not initialized"
//            return
//        }
//
//        audioRecord.startRecording()
//        isRecording = false
//        detectionResult = "Listening..."
//        recordingThread = Thread { readAudioData() }
//        recordingThread.start()
//        startRecognitionLoop()
//    }
//
//    private fun readAudioData() {
//
//        while (isRecording) {
//            detectionResult = "Recording"
//            val bytesRead = audioRecord.read(audioData, 0, audioData.size)
//            if (bytesRead > 0) {
//                byteBuffer.clear()
//                for (i in 0 until bytesRead) {
//                    byteBuffer.putShort(audioData[i])
//                }
//            } else {
//                Log.e("MainActivity", "Error reading audio data: $bytesRead")
//                if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION) {
//                    handler.post {
//                        detectionResult = "Error reading audio data: ERROR_INVALID_OPERATION"
//                    }
//                    stopRecordingAndAnalysis()
//                } else if (bytesRead == AudioRecord.ERROR_BAD_VALUE) {
//                    handler.post {
//                        detectionResult = "Error reading audio data: ERROR_BAD_VALUE"
//                    }
//                    stopRecordingAndAnalysis()
//                }
//            }
//        }
//    }
//
//    private fun stopRecordingAndAnalysis() {
//        isRecording = false
//        handler.post { detectionResult = "Stopped." }
//
//        if (::audioRecord.isInitialized) {
//            try {
//                audioRecord.stop()
//                audioRecord.release()
//            } catch (e: IllegalStateException) {
//                Log.e("MainActivity", "Error stopping AudioRecord: ${e.message}")
//            }
//        }
//
//        if (::recordingThread.isInitialized) {
//            try {
//                recordingThread.join()
//            } catch (e: InterruptedException) {
//                Log.e("MainActivity", "Error joining recording thread: ${e.message}")
//            }
//        }
//        executor.shutdown()
//    }
//
//    private fun startRecognitionLoop() {
//        executor.scheduleWithFixedDelay({
//        if (isRecording) {
//            isRecording = false
//            detectionResult = "Will go to perfromSoundRecognition"
//            performSoundRecognition()
//            executor.scheduleWithFixedDelay({
//            }, 0, 500, TimeUnit.MILLISECONDS)
//        }
//        else{
//            detectionResult = "Recording for 3 second"
//            isRecording = true
//            executor.scheduleWithFixedDelay({
//            }, 0, 1000, TimeUnit.MILLISECONDS)
//        }
//        }, 0, recordingIntervalMillis, TimeUnit.MILLISECONDS)
//    }
//
//    private fun performSoundRecognition() {
//        detectionResult = "in performSoundRecognition"
//        byteBuffer.rewind() // very important! start from beginning
//        val shortBuffer = byteBuffer.asShortBuffer() // interpret as shorts
//        val shortArray = ShortArray(shortBuffer.remaining())
//        shortBuffer.get(shortArray) // read all shorts
//
//        val floatInput = FloatArray(shortArray.size)
//        for (i in shortArray.indices) {
//            floatInput[i] = shortArray[i] / 32768.0f
//        }
//        detectionResult = "going to get results"
//
//        //val results = soundClassifier.recognizeSound(floatInput)
//        val py = Python.getInstance()
//        val pyFunction = py.getModule("sound_classifier") // Get the Python module named 'random'
//        //val result = randomModule.callAttr("preprocess_audio", floatInput).toString() // Call the 'ok' function and get the result
//        val inpTensor_4D: Array<Array<Array<FloatArray>>> = pyFunction.callAttr("preprocess_audio", floatInput).toJava(Array<Array<Array<FloatArray>>>::class.java)
//        println("inpTensor_4D: $inpTensor_4D")
//        println("inpTensor_4D: ${inpTensor_4D?.contentDeepToString()}")
//        detectionResult = inpTensor_4D::class.java.toString()
//        executor.scheduleWithFixedDelay({
//                detectionResult = inpTensor_4D::class.java.toString()
//        }, 0, 500, TimeUnit.MILLISECONDS)
//
//        detectionResult = "got results"
//        executor.scheduleWithFixedDelay({
//                detectionResult = "got results"
////            handler.post {
////                if (detectionResult != null) {
////                    displayResults(detectionResult)
////                } else {
////                    detectionResult = "Recognition failed"
////                }
////            }
//        }, 0, 500, TimeUnit.MILLISECONDS)
//
//    }
//
//    private fun displayResults(results: FloatArray) {
//        val classNames = arrayOf("other", "car horn", "scream", "dog bark")
//        var maxConfidence = -1.0f
//        var predictedClass = "Unknown"
//        for (i in results.indices) {
//            if (results[i] > maxConfidence) {
//                maxConfidence = results[i]
//                predictedClass = classNames[i]
//            }
//        }
//        confidence = String.format("%.2f", maxConfidence)
//        detectionResult = predictedClass
//    }
//
//    override fun onDestroy() {
//        super.onDestroy()
//        stopRecordingAndAnalysis()
//    }
//}
