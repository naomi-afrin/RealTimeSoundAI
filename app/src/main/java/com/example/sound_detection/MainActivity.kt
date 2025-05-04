package com.example.sound_detection

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.media.MediaRecorder
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
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.chaquo.python.PyObject
import kotlin.reflect.typeOf
import kotlinx.coroutines.*
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import org.tensorflow.lite.Interpreter
import java.nio.channels.FileChannel









class MainActivity : ComponentActivity() {

    private lateinit var soundClassifier: SoundClassifier
    private lateinit var labels: List<String>
    private var detectionResult by mutableStateOf("Waiting to Start...")
    private var confidence by mutableStateOf("")
    private var isRecording by mutableStateOf(false)
    private lateinit var audioRecord: AudioRecord
    private lateinit var recordingThread: Thread
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private val sampleRate = 22050
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private var bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    private lateinit var audioData: ShortArray
    private lateinit var byteBuffer: ByteBuffer
    private val inputLength = 128 * 128
    private val outputLength = 4
    private val recordingIntervalMillis: Long = 3000
    private lateinit var tflite: Interpreter  // 👈 to store model
    private val MODEL_PATH = "sound_classifier_2_17.tflite"
    private var play = false
    private var lastBuffer: ShortArray? = null
    private var playButton = "Play Audio"


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
        tflite = loadModel() // ← call Interpreter
        // Initialize Python
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        setContent {
            Sound_detectionTheme {
                // UI Layout
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // Show the detection result
                    Text(
                        text = detectionResult,
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.padding(16.dp)
                    )

                    // Start Button
                    Button(
                        onClick = {
                            if (!isRecording) {
                                if (hasRecordAudioPermission()) {
                                    startRecordingLoop()  // Start recording if permission is granted
                                    detectionResult = "Recording Started..."
                                } else {
                                    // Request permission if not granted
                                    requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        },
                        modifier = Modifier.padding(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary) // Filled button
                    ) {
                        Text(text = "Start Recording")
                    }

                    // Stop Button
                    Button(
                        onClick = {
                            stopRecording()  // Stop recording when the button is pressed
                            detectionResult = "Recording Stopped."
                        },
                        modifier = Modifier.padding(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary) // Filled button
                    ) {
                        Text(text = "Stop Recording")
                    }

                    // Play back button
                    Button(
                        onClick = {
                            if (play == true){
                                play = false
                                playButton = "Play Audio"
                            }
                            else{
                                Log.d("play","play is true")
                                play = true
                                playButton = "Stop Audio"
                            }
                        },
                        modifier = Modifier.padding(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary) // Filled button
                    ) {
                        Text(text = playButton)
                    }
                }
            }
        }

    }



    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                startRecordingLoop() // Start recording if permission is granted
            } else {
                detectionResult = "Permission to record audio denied."
            }
        }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    // initialize the AudioRecord object.
    private fun initializeAudioRecord() {
        bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )
        audioData = ShortArray(bufferSize)

    }


    private fun stopRecording() {
        // Stop recording and release resources
        if (isRecording) {
            isRecording = false
            audioRecord.stop()
            audioRecord.release()
            Log.d("AudioRecord", "Recording stopped and resources released.")
        }
    }



    private fun startRecordingLoop() {
        if (!hasRecordAudioPermission()) {
            detectionResult = "Permission not granted!"
            return
        }

        initializeAudioRecord()
        isRecording = true


        CoroutineScope(Dispatchers.IO).launch {
            while (isRecording) {
                audioRecord.startRecording()
                detectionResult = "will record for 3 seconds"
                val buffer = ShortArray(sampleRate * 3) // creates buffer of 3 seconds of audio at 22050 Hz
                var totalRead = 0

                while (totalRead < buffer.size) {
                    val read = audioRecord.read(
                        buffer,
                        totalRead,
                        buffer.size - totalRead
                    ) // recorded audio data from the AudioRecord object into a buffer.
                    if (read > 0) {
                        totalRead += read
                    }
                }
                val startTime = System.currentTimeMillis()

                detectionResult = "3 seconds of audio recorded."
                // for playing audio
                Log.d("AudioRecord", "will play audio because play is $play")
                if (play == true){
                    playAudio(buffer)
                }
                
                // for sending to python
                // Convert ShortArray to FloatArray and normalize it
                val floatInput = buffer.map { it.toFloat() / 32768.0f }.toFloatArray()


                // max and min of floatInput and buffer
                findMaxAndMinValues(buffer)
                val maxValue = floatInput.maxOrNull() ?: 0f
                val minValue = floatInput.minOrNull() ?: 0f

                Log.d("FloatInput", "Max float value: $maxValue")
                Log.d("FloatInput", "Min float value: $minValue")

                // Call Python preprocessing
                val inputTensor4D = preprocessWithPython(floatInput)
                Log.d("Preprocess", "Preprocessed tensor shape: ${inputTensor4D.size}")


                // model prediction
                // Create a container for the output
                val output = Array(1) { FloatArray(outputLength) }
                Log.d("AudioRecord", "will run model")
                // Run inference
                tflite.run(inputTensor4D, output)

                // Find the predicted label and confidence
                val predictedIndex = output[0].indices.maxByOrNull { output[0][it] } ?: -1
                val predictedLabel = when (predictedIndex) {
                    0 -> "Other"
                    1 -> "Car Horn"
                    2 -> "Dog Bark"
                    3 -> "Scream"
                    else -> "Unknown"
                }
                val predictionConfidence = output[0].getOrNull(predictedIndex)?.let { "%.2f".format(it * 100) } ?: "N/A"
                detectionResult = "result is"
                // Update UI
                withContext(Dispatchers.Main) {
                    detectionResult = "Detected: $predictedLabel"
                    confidence = "Confidence: $predictionConfidence%"
                }


         
                delay(1000) // rest of the 1 wait
                val endTime = System.currentTimeMillis()
                val duration = endTime - startTime

                Log.d("DetectionTime", "Inference took $duration ms")

            }
        }

    }



    fun preprocessWithPython(floatInput: FloatArray): Array<Array<Array<FloatArray>>> {
        val py = Python.getInstance()
        val pyFunction = py.getModule("sound_classifier") // Get the Python module named 'sound_classifier'
        val inpTensor_4D: Array<Array<Array<FloatArray>>> = pyFunction.callAttr("preprocess_audio", floatInput).toJava(Array<Array<Array<FloatArray>>>::class.java)
        println("inpTensor_4D: $inpTensor_4D")
        return inpTensor_4D

    }



    // to check max and min value in buffer
    fun findMaxAndMinValues(buffer: ShortArray) {
        var maxValue = Short.MIN_VALUE
        var minValue = Short.MAX_VALUE

        for (i in buffer.indices) {
            if (buffer[i] > maxValue) {
                maxValue = buffer[i]
            }
            if (buffer[i] < minValue) {
                minValue = buffer[i]
            }
        }

        Log.d("AudioBuffer", "Maximum value in the buffer: $maxValue")
        Log.d("AudioBuffer", "Minimum value in the buffer: $minValue")
    }
    
    fun playAudio(buffer: ShortArray){
        Log.d("AudioRecord", "Playing back recorded audio.")

        // for playing audio
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


        // Play back the recorded audio
        val audioBuffer = ByteBuffer.allocate(buffer.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.forEach { audioBuffer.putShort(it) }


        audioTrack.write(audioBuffer.array(), 0, audioBuffer.array().size)
        audioTrack.play()
        Log.d("AudioRecord", "Played back recorded audio.")

        Handler(Looper.getMainLooper()).postDelayed({
            audioTrack.stop()
            audioTrack.release()
            Log.d("AudioRecord", "Playback stopped and released.")
        }, 3000)
        
    }









}
