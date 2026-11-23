package com.nasari.gbaia

import android.content.Context
import com.qualcomm.qti.QnnDelegate
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * IA v2 en la NPU Hexagon (LiteRT + delegado QNN de Qualcomm, backend HTP).
 * Calcula los 48 canales antes del pixel shuffle; el resto lo hace la GPU (npu_gl.c).
 * Crear y usar solo desde el hilo del emulador.
 */
class NpuUpscaler private constructor(
    private val interpreter: Interpreter,
    private val delegate: QnnDelegate,
) {
    val input: ByteBuffer = ByteBuffer.allocateDirect(160 * 240 * 3 * 4).order(ByteOrder.nativeOrder())
    val output: ByteBuffer = ByteBuffer.allocateDirect(160 * 240 * 48 * 4).order(ByteOrder.nativeOrder())

    /** Promedio de ms por inferencia. */
    var ms = -1f
        private set

    fun run() {
        input.rewind()
        output.rewind()
        val t0 = System.nanoTime()
        interpreter.run(input, output)
        val t = (System.nanoTime() - t0) / 1e6f
        ms = if (ms < 0) t else ms * 0.9f + t * 0.1f
    }

    fun close() {
        interpreter.close()
        delegate.close()
    }

    companion object {
        /** Devuelve el upscaler o lanza una excepción con la causa (para el reporte). */
        fun create(context: Context): NpuUpscaler {
            val options = QnnDelegate.Options().apply {
                setBackendType(QnnDelegate.Options.BackendType.HTP_BACKEND)
                setSkelLibraryDir(context.applicationInfo.nativeLibraryDir)
            }
            val delegate = QnnDelegate(options)
            val opts = Interpreter.Options().addDelegate(delegate)
            val interpreter = try {
                Interpreter(loadModel(context), opts)
            } catch (e: Throwable) {
                delegate.close()
                throw e
            }
            return NpuUpscaler(interpreter, delegate)
        }

        private fun loadModel(context: Context): MappedByteBuffer {
            val fd = context.assets.openFd("student_v2.tflite")
            FileInputStream(fd.fileDescriptor).use { s ->
                return s.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
    }
}
