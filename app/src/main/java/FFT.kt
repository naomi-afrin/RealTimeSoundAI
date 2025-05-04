import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.PI
import kotlin.math.log2


class FFT(private val n: Int) {
    private val cosTable = DoubleArray(n / 2)
    private val sinTable = DoubleArray(n / 2)

    init {
        for (i in 0 until n / 2) {
            cosTable[i] = cos(2.0 * PI * i / n)
            sinTable[i] = sin(2.0 * PI * i / n)
        }
    }

    fun fft(real: DoubleArray, imag: DoubleArray) {
        val bits = log2(n.toDouble()).toInt()
        for (i in 0 until n) {
            val j = Integer.reverse(i).ushr(32 - bits)
            if (j > i) {
                val tempReal = real[i]
                val tempImag = imag[i]
                real[i] = real[j]
                imag[i] = imag[j]
                real[j] = tempReal
                imag[j] = tempImag
            }
        }

        var size = 2
        while (size <= n) {
            val halfSize = size / 2
            val tableStep = n / size
            for (i in 0 until n step size) {
                for (j in 0 until halfSize) {
                    val k = j * tableStep
                    val tReal = cosTable[k] * real[i + j + halfSize] - sinTable[k] * imag[i + j + halfSize]
                    val tImag = sinTable[k] * real[i + j + halfSize] + cosTable[k] * imag[i + j + halfSize]

                    real[i + j + halfSize] = real[i + j] - tReal
                    imag[i + j + halfSize] = imag[i + j] - tImag
                    real[i + j] += tReal
                    imag[i + j] += tImag
                }
            }
            size *= 2
        }
    }

    fun computeMagnitude(real: DoubleArray, imag: DoubleArray): DoubleArray {
        return DoubleArray(n / 2) { i ->
            sqrt(real[i] * real[i] + imag[i] * imag[i])
        }
    }
}
