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
    val modelName: String,
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
        private fun options(context: Context, backend: QnnDelegate.Options.BackendType, token: String) =
            QnnDelegate.Options().apply {
                setBackendType(backend)
                setSkelLibraryDir(context.applicationInfo.nativeLibraryDir)
                setLogLevel(QnnDelegate.Options.LogLevel.LOG_LEVEL_INFO)
                setCacheDir(context.cacheDir.absolutePath)
                setModelToken(token)
                if (backend == QnnDelegate.Options.BackendType.HTP_BACKEND) {
                    setHtpPrecision(QnnDelegate.Options.HtpPrecision.HTP_PRECISION_FP16)
                    setHtpPerformanceMode(QnnDelegate.Options.HtpPerformanceMode.PERFORMANCE_BURST)
                }
            }

        /** Intenta crear un intérprete con el delegado. Devuelve null y anota la causa si falla. */
        private fun attempt(
            context: Context, model: String, backend: QnnDelegate.Options.BackendType, notes: MutableList<String>,
        ): Pair<Interpreter, QnnDelegate>? {
            val label = "$model en ${backend.name}"
            var delegate: QnnDelegate? = null
            return try {
                val t0 = System.nanoTime()
                delegate = QnnDelegate(options(context, backend, model))
                val itp = Interpreter(loadModel(context, model), Interpreter.Options().addDelegate(delegate))
                notes += "OK $label (%.0f ms)".format((System.nanoTime() - t0) / 1e6)
                itp to delegate
            } catch (e: Throwable) {
                delegate?.close()
                notes += "FALLA $label: ${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull()}"
                null
            }
        }

        /**
         * Diagnóstico + creación. Prueba, en orden: un modelo mínimo (¿la NPU responde?),
         * la v2 con MIRROR_PAD y la v2 con relleno de ceros. Usa la primera v2 que funcione.
         */
        fun create(context: Context, notes: MutableList<String>): NpuUpscaler? {
            try {
                notes += "QNN delegado v" + QnnDelegate.getVersion().joinToString(".")
                for (cap in listOf(
                    QnnDelegate.Capability.HTP_RUNTIME_FP16, QnnDelegate.Capability.HTP_RUNTIME_QUANTIZED,
                    QnnDelegate.Capability.GPU_RUNTIME, QnnDelegate.Capability.DSP_RUNTIME,
                )) {
                    notes += "capacidad ${cap.name}: ${if (QnnDelegate.checkCapability(cap)) "sí" else "no"}"
                }
            } catch (e: Throwable) {
                notes += "no se pudo consultar capacidades: ${e.javaClass.simpleName}: ${e.message}"
            }
            val htp = QnnDelegate.Options.BackendType.HTP_BACKEND
            val probe = attempt(context, "probe_conv", htp, notes)
            probe?.let { (i, d) -> i.close(); d.close() }
            if (probe == null) {
                // ¿Funciona el delegado aunque sea en otro backend?
                attempt(context, "probe_conv", QnnDelegate.Options.BackendType.GPU_BACKEND, notes)
                    ?.let { (i, d) -> i.close(); d.close() }
            }
            for (model in listOf("student_v2", "student_v2_zp")) {
                val ok = attempt(context, model, htp, notes) ?: continue
                return NpuUpscaler(ok.first, ok.second, model)
            }
            return null
        }

        private fun loadModel(context: Context, name: String): MappedByteBuffer {
            val fd = context.assets.openFd("$name.tflite")
            FileInputStream(fd.fileDescriptor).use { s ->
                return s.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
    }
}
