package com.nasari.gbaia

import android.content.Context
import com.qualcomm.qti.QnnDelegate
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * IA v2 en la NPU Hexagon (LiteRT + delegado QNN de Qualcomm, backend HTP).
 * El HTP del Poco F7 (SM8735) no acepta fp16: se usa el modelo cuantizado
 * (pesos int8, activaciones int16). Calcula los 48 canales antes del pixel shuffle;
 * el resto lo hace la GPU (npu_gl.c). Crear y usar solo desde el hilo del emulador.
 */
class NpuUpscaler private constructor(
    private val interpreter: Interpreter,
    private val delegate: QnnDelegate,
    val modelName: String,
) {
    // Tipo y cuantización de la entrada/salida (NPU_F32 = 0, NPU_I8 = 1, NPU_I16 = 2 en npu_gl.h)
    val inType: Int
    val inScale: Float
    val inZero: Int
    val outType: Int
    val outScale: Float
    val outZero: Int
    val input: ByteBuffer
    val output: ByteBuffer

    init {
        val i = interpreter.getInputTensor(0)
        val o = interpreter.getOutputTensor(0)
        inType = typeCode(i.dataType())
        outType = typeCode(o.dataType())
        inScale = i.quantizationParams().scale
        inZero = i.quantizationParams().zeroPoint
        outScale = o.quantizationParams().scale
        outZero = o.quantizationParams().zeroPoint
        input = ByteBuffer.allocateDirect(i.numBytes()).order(ByteOrder.nativeOrder())
        output = ByteBuffer.allocateDirect(o.numBytes()).order(ByteOrder.nativeOrder())
    }

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
        /** Más lento que esto por inferencia = no se aceleró (corre en CPU). */
        private const val MAX_MS = 25f

        private fun typeCode(t: DataType) = when (t) {
            DataType.INT8 -> 1
            DataType.INT16 -> 2
            else -> 0
        }

        private fun options(context: Context, token: String) =
            QnnDelegate.Options().apply {
                setBackendType(QnnDelegate.Options.BackendType.HTP_BACKEND)
                setSkelLibraryDir(context.applicationInfo.nativeLibraryDir)
                setLogLevel(QnnDelegate.Options.LogLevel.LOG_LEVEL_WARN)
                setCacheDir(context.cacheDir.absolutePath)
                setModelToken(token)
                setHtpPrecision(QnnDelegate.Options.HtpPrecision.HTP_PRECISION_QUANTIZED)
            }

        /** Crea el intérprete, lo prueba y solo lo acepta si de verdad va rápido. */
        private fun attempt(context: Context, model: String, notes: MutableList<String>): NpuUpscaler? {
            var delegate: QnnDelegate? = null
            var up: NpuUpscaler? = null
            return try {
                val t0 = System.nanoTime()
                delegate = QnnDelegate(options(context, model))
                val itp = Interpreter(loadModel(context, model), Interpreter.Options().addDelegate(delegate))
                val u = NpuUpscaler(itp, delegate, model)
                up = u
                val prep = (System.nanoTime() - t0) / 1e6
                repeat(4) { u.run() } // calentamiento + medición
                if (u.ms > MAX_MS) {
                    notes += "NO SIRVE $model: %.1f ms por cuadro (no se aceleró, corre en CPU)".format(u.ms)
                    u.close()
                    null
                } else {
                    notes += "OK $model: preparar %.0f ms, %.1f ms por cuadro".format(prep, u.ms)
                    u
                }
            } catch (e: Throwable) {
                if (up != null) up.close() else delegate?.close()
                notes += "FALLA $model: ${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull()}"
                null
            }
        }

        /** Modelos en orden de preferencia (los que no existan en assets se saltan). */
        val MODELS = listOf("student_v3_a8w8", "student_v2_a8w8", "student_v2_a16w8", "student_v2")

        /**
         * Prueba los modelos en orden de preferencia, saltando los que ya fallaron en este celular ([skip]).
         * Los que fallen ahora se agregan a [failed]. int16 y float no sirven en el SM8735 (su HTP solo
         * acelera int8), pero se dejan para otros chips.
         */
        fun create(context: Context, notes: MutableList<String>, skip: Set<String>, failed: MutableSet<String>): NpuUpscaler? {
            var first = true
            val available = context.assets.list("")?.toSet() ?: emptySet()
            val order = MODELS.filter { "$it.tflite" in available && it !in skip }
            if (skip.isNotEmpty()) notes += "se saltan (ya fallaron aquí): " + skip.joinToString()
            for (model in order) {
                val up = attempt(context, model, notes)
                if (first) {
                    first = false
                    // Tras el primer intento la librería ya está cargada: anotar capacidades
                    try {
                        notes += "QNN delegado v" + QnnDelegate.getVersion().joinToString(".")
                        for (cap in listOf(QnnDelegate.Capability.HTP_RUNTIME_FP16, QnnDelegate.Capability.HTP_RUNTIME_QUANTIZED)) {
                            notes += "capacidad ${cap.name}: ${if (QnnDelegate.checkCapability(cap)) "sí" else "no"}"
                        }
                    } catch (e: Throwable) {
                        notes += "no se pudo consultar capacidades: ${e.message}"
                    }
                }
                if (up != null) return up
                failed += model
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
