package com.orexii.aimarkdownreader.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Kokoro on ONNX Runtime. The Java API is the same in the Android library (the app) and the desktop jar (the
 * JVM tests), so this compiles against the jar and runs on either.
 */
class OrtInference(
    modelPath: String,
    threads: Int,
    configure: (OrtSession.SessionOptions) -> Unit = {},
) : KokoroInference, AutoCloseable {
    // No usage telemetry. On Android the app also leaves out the library's telemetry initializer (its manifest).
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment().apply { runCatching { setTelemetry(false) } }
    private val session: OrtSession = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(threads)
        options.setInterOpNumThreads(1)
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        configure(options)
        environment.createSession(modelPath, options)
    }

    override fun run(ids: LongArray, style: FloatArray, speed: Float): Pair<FloatArray, FloatArray> {
        OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { input ->
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(style), longArrayOf(1, style.size.toLong())).use { styleTensor ->
                OnnxTensor.createTensor(environment, FloatBuffer.wrap(floatArrayOf(speed)), longArrayOf(1)).use { speedTensor ->
                    session.run(mapOf("input_ids" to input, "style" to styleTensor, "speed" to speedTensor)).use { result ->
                        return floats(result, "waveform") to floats(result, "durations")
                    }
                }
            }
        }
    }

    private fun floats(result: OrtSession.Result, name: String): FloatArray {
        val tensor = result.get(name).orElseThrow { IllegalStateException("The model gave no $name.") } as OnnxTensor
        val buffer = tensor.floatBuffer
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    override fun close() = session.close()

    companion object {
        /** Threads for speech: half the cores, 1 to 8 (the extension's automatic setting). */
        fun automaticThreads(cores: Int = Runtime.getRuntime().availableProcessors()): Int = (cores / 2).coerceIn(1, 8)
    }
}
