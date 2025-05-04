package com.example.sound_detection

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

class SoundClassifier(
    private val context: Context,
    private val outputLength: Int, // Receive outputLength
    private val sampleRate: Int,  // Use the sampleRate
    private val nMelBands: Int,
    private val imgHeight: Int,
    private val imgWidth: Int,
) {

    private var interpreter: Interpreter? = null
    private val modelFileName = "sound_classifier_2_17.tflite"

    init {
        interpreter = initializeInterpreter()
    }

    @Throws(IOException::class)
    private fun loadModelFile(): MappedByteBuffer {
        val assetManager = context.assets
        val fileDescriptor = assetManager.openFd(modelFileName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        val mappedByteBuffer =  fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        inputStream.close() // Close the input stream
        fileDescriptor.close()
        return mappedByteBuffer
    }

    private fun initializeInterpreter(): Interpreter? {
        return try {
            val modelByteBuffer = loadModelFile()
            Interpreter(modelByteBuffer)
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }

    fun recognizeSound(audioData: FloatArray): FloatArray? {
        interpreter?.let {
            // 1. Preprocess the audio data to get the Mel spectrogram
            Log.d("SoundClassifier", "recognizeSound() function called")

            val melSpectrogram = preprocessAudio(audioData)

            // 2. Create input ByteBuffer.
            val inputBuffer = ByteBuffer.allocateDirect(imgHeight * imgWidth * 1 * Float.SIZE_BYTES).apply {
                order(ByteOrder.nativeOrder())
                asFloatBuffer().put(melSpectrogram)
            }

            // 3. Create output ByteBuffer.
            val outputBuffer = ByteBuffer.allocateDirect(outputLength * Float.SIZE_BYTES).apply { // Use outputLength
                order(ByteOrder.nativeOrder())
                asFloatBuffer()
            }
            val startTime = System.currentTimeMillis() //start time
            it.run(inputBuffer.rewind(), outputBuffer.rewind())
            val endTime = System.currentTimeMillis()// end time
            Log.d("SoundClassifier", "Inference Time: ${endTime - startTime} ms")

            val output = FloatArray(outputLength) // Create FloatArray to hold the result
            outputBuffer.rewind()
            outputBuffer.asFloatBuffer().get(output)  // Get the output
            return output
        }
        return null
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }

    private fun preprocessAudio(audioData: FloatArray): FloatArray {
        Log.d("SoundClassifier", "in preprocessAudio() function")
        // 1. Flatten the audio data (if needed) and Calculate Mel Spectrogram
        val flattenedAudioData = flattenAudioData(audioData) // Ensure audio is 1D
        val melSpectrogram = calculateMelSpectrogram(flattenedAudioData)
        // 2.  Convert to dB
        val melSpectrogramDb = convertToDB(melSpectrogram)
        // 3. Resize
        val melSpectrogramResized = resizeMelSpectrogram(melSpectrogramDb)

        return melSpectrogramResized
    }

    private fun flattenAudioData(audioData: FloatArray): FloatArray {
        //  In the python code,  audio.flatten() is used.  If the audio is already 1D, this does nothing.
        //  In Kotlin,  FloatArray is already 1D, so we just return it.  If for some reason, you had a multi-dimensional
        //  array, you'd need to flatten it here.
        Log.d("SoundClassifier", "flattenAudioData() function called")
        return audioData;
    }


    private fun calculateMelSpectrogram(audioData: FloatArray): Array<FloatArray> {
        Log.d("SoundClassifier", "in calculateMelSpectrogram() function")
        val frameLength = 2048
        val hopLength = 512
        val numFrames = (audioData.size - frameLength) / hopLength + 1


        val melFilter = calculateMelFilterBank(sampleRate, nMelBands, frameLength)
        val spectrogram = calculateSpectrogram(audioData, frameLength, hopLength)

        val melSpectrogram = Array(nMelBands) { FloatArray(numFrames) }

        for (i in 0 until nMelBands) {
            for (j in 0 until numFrames) {
                var melValue = 0.0f
                for (k in 0 until frameLength / 2 + 1) {
                    melValue += spectrogram[j][k] * melFilter[i][k]
                }
                melSpectrogram[i][j] = melValue
            }
        }
        return melSpectrogram
    }


    private fun calculateSpectrogram(audioData: FloatArray, frameLength: Int, hopLength: Int): Array<FloatArray> {
        Log.d("SoundClassifier", "in calculateSpectrogram() function")
        val numFrames = (audioData.size - frameLength) / hopLength + 1
        val spectrogram = Array(numFrames) { FloatArray(frameLength / 2 + 1) }
        val fft = FFT(frameLength) // Create FFT object once
        Log.d("SoundClassifier", "FFT done")
        val window = hammingWindow(frameLength) // Calculate window once
        Log.d("SoundClassifier", "hammingWindow done")
        Log.d("SoundClassifier", "numFrames"+numFrames.toString())

        for (i in 0 until numFrames) {
            Log.d("SoundClassifier", "loop:"+i.toString())
            val frameStart = i * hopLength
            val frameEnd = (frameStart + frameLength).coerceAtMost(audioData.size) // Ensure frameEnd doesn't exceed audioData.size
            val actualFrameLength = frameEnd - frameStart
            val frameToProcess = audioData.sliceArray(frameStart until frameEnd)

            // Apply windowing function
            val windowedFrame = if (actualFrameLength == frameLength) {
                frameToProcess.zip(window) { x, w -> x * w }.toFloatArray()
            } else {
                // Handle shorter frames at the end of the audio
                val shortWindow = hammingWindow(actualFrameLength)
                frameToProcess.zip(shortWindow) { x, w -> x * w }.toFloatArray()
            }

            fft.fft(windowedFrame) // Use the same FFT object
            Log.d("SoundClassifier", "hi")
            val mag = fft.getMagnitude()

            for (j in 0 until actualFrameLength / 2 + 1) {
                spectrogram[i][j] = if (j < mag.size) mag[j] else 0f
            }
        }
        Log.d("SoundClassifier", "numFrames done")
        Log.d("SoundClassifier", "return calculateSpectrogram() function")
        return spectrogram
    }


    private fun calculateMelFilterBank(sampleRate: Int, nMelBands: Int, frameLength: Int): Array<FloatArray> {
        Log.d("SoundClassifier", "in calculateMelFilterBank() function")
        val nFft = frameLength / 2 + 1
        val fMin = 0.0f
        val fMax = sampleRate / 2.0f
        val melMin = freqToMel(fMin)
        val melMax = freqToMel(fMax)

        val melPoints = FloatArray(nMelBands + 2)
        for (m in 0..nMelBands + 1) {
            melPoints[m] = melMin + (melMax - melMin) * m / (nMelBands + 1)
        }

        val freqPoints = melPoints.map { melToFreq(it) }

        val melFilter = Array(nMelBands) { FloatArray(nFft) }

        for (m in 0 until nMelBands) {
            val leftMel = melPoints[m]
            val centerMel = melPoints[m + 1]
            val rightMel = melPoints[m + 2]

            val leftFreq = freqPoints[m].toFloat()
            val centerFreq = freqPoints[m + 1].toFloat()
            val rightFreq = freqPoints[m + 2].toFloat()


            for (k in 0 until nFft) {
                val freqK = (k * sampleRate) / frameLength.toFloat()
                melFilter[m][k] = when {
                    freqK < leftFreq || freqK > rightFreq -> 0.0f
                    freqK == centerFreq -> 1.0f
                    freqK > leftFreq && freqK < centerFreq -> (freqK - leftFreq) / (centerFreq - leftFreq)
                    freqK > centerFreq && freqK < rightFreq -> (rightFreq - freqK) / (rightFreq - centerFreq)
                    else -> 0.0f
                }
            }
        }
        return melFilter
    }

    private fun freqToMel(freq: Float): Float {
        return 2595f * log10(1 + freq / 700f)
    }

    private fun melToFreq(mel: Float): Float {
        return 700f * (10f.pow(mel / 2595f) - 1)
    }

    private fun convertToDB(melSpectrogram: Array<FloatArray>): FloatArray {
        Log.d("SoundClassifier", "in convertToDB() function")
        val minDB = -80.0f
        var maxMelValue = Float.MIN_VALUE
        for (row in melSpectrogram) {
            for (value in row) {
                if (value > maxMelValue) {
                    maxMelValue = value
                }
            }
        }
        val ref = if (maxMelValue > 0) maxMelValue else 1.0f

        val melSpectrogramDb = FloatArray(imgHeight * imgWidth)

        for (i in 0 until imgHeight) {
            for (j in 0 until imgWidth) {
                val melValue = melSpectrogram[i][j]
                melSpectrogramDb[i * imgWidth + j] = when {
                    melValue > 0 -> 10 * log10(melValue / ref).coerceAtLeast(minDB)
                    else -> minDB
                }
            }
        }
        return melSpectrogramDb
    }

    private fun resizeMelSpectrogram(melSpectrogram: FloatArray): FloatArray {
        Log.d("SoundClassifier", "in resizeMelSpectrogram() function")
        val originalSize = kotlin.math.sqrt(melSpectrogram.size.toFloat()).toInt()
        val resizedSpectrogram = FloatArray(imgHeight * imgWidth)
        val heightRatio = originalSize.toFloat() / imgHeight
        val widthRatio = originalSize.toFloat() / imgWidth

        for (i in 0 until imgHeight) {
            for (j in 0 until imgWidth) {
                val origX = j * widthRatio
                val origY = i * heightRatio

                val x1 = origX.toInt()
                val y1 = origY.toInt()
                val x2 = (x1 + 1).coerceAtMost(originalSize - 1)
                val y2 = (y1 + 1).coerceAtMost(originalSize - 1)

                val a = melSpectrogram[y1 * originalSize + x1]
                val b = melSpectrogram[y1 * originalSize + x2]
                val c = melSpectrogram[y2 * originalSize + x1]
                val d = melSpectrogram[y2 * originalSize + x2]

                val xWeight = origX - x1
                val yWeight = origY - y1

                resizedSpectrogram[i * imgWidth + j] = a * (1 - xWeight) * (1 - yWeight) +
                        b * xWeight * (1 - yWeight) +
                        c * (1 - xWeight) * yWeight +
                        d * xWeight * yWeight
            }
        }
        return resizedSpectrogram
    }


    class FFT(size: Int) {
        private val size = size
        private val log2Size = (kotlin.math.log2(size.toFloat()) / kotlin.math.log2(2.0f)).toInt()
        private val cosTable = FloatArray(size / 2)
        private val sinTable = FloatArray(size / 2)
        private val data = FloatArray(size * 2) // Interleaved real and imaginary

        init {
            if (size and (size - 1) != 0) {  // Check if size is a power of 2
                throw IllegalArgumentException("Size must be a power of 2")
            }
            for (i in 0 until size / 2) {
                cosTable[i] = kotlin.math.cos(-2 * Math.PI * i / size).toFloat()
                sinTable[i] = kotlin.math.sin(-2 * Math.PI * i / size).toFloat()
            }
        }

        fun fft(input: FloatArray) {
            if (input.size != size) {
                throw IllegalArgumentException("Data length must equal size")
            }
            //Interleave real and imaginary parts.
            for(i in 0 until size){
                data[2*i] = input[i]
                data[2*i + 1] = 0f
            }
            var a = data
            var b = FloatArray(size * 2)

            Log.d("SoundClassifier", log2Size.toString())
            for (i in 0 until log2Size) {
                Log.d("SoundClassifier", "in fft loop:"+i.toString())
                val n = size shr (i + 1)
                for (j in 0 until size step 2 * n) {
                    for (k in 0 until n) {
                        val p = j + (k shl 1)
                        val q = p + (n shl 1)
                        val wR = cosTable[k * (size shr (i + 1))]
                        val wI = sinTable[k * (size shr (i + 1))]
                        val tempR = a[q] * wR - a[q + 1] * wI
                        val tempI = a[q] * wI + a[q + 1] * wR
                        a[q] = a[p] - tempR
                        a[q + 1] = a[p + 1] - tempI
                        a[p] += tempR
                        a[p + 1] += tempI
                    }
                }
            }
            Log.d("SoundClassifier", "in fft")
        }

        fun getMagnitude(): FloatArray {
            val magnitude = FloatArray(size / 2 + 1)
            for (i in 0 until size / 2 + 1) {
                val real = data[2 * i]
                val imag = data[2 * i + 1]
                magnitude[i] = kotlin.math.sqrt(real * real + imag * imag)
            }
            return magnitude
        }
    }

    private fun hammingWindow(size: Int): FloatArray {
        val window = FloatArray(size)
        for (n in 0 until size) {
            window[n] = 0.54f - 0.46f * cos((2 * Math.PI * n) / (size - 1)).toFloat()
        }
        return window
    }
}
