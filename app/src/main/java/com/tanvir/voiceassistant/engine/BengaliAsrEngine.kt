package com.tanvir.voiceassistant.engine

import com.k2fsa.sherpa.onnx.*
import java.io.File

class BengaliAsrEngine(private val modelDirectory: File) {
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var committed = ""

    fun start() {
        val encoder = File(modelDirectory, "encoder.onnx")
        val decoder = File(modelDirectory, "decoder.onnx")
        val joiner = File(modelDirectory, "joiner.onnx")
        val tokens = File(modelDirectory, "tokens.txt")
        require(listOf(encoder, decoder, joiner, tokens).all { it.isFile }) {
            "Bengali ASR model files are missing"
        }
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = encoder.absolutePath,
                    decoder = decoder.absolutePath,
                    joiner = joiner.absolutePath
                ),
                tokens = tokens.absolutePath,
                numThreads = 2,
                modelType = "zipformer2"
            ),
            enableEndpoint = true
        )
        recognizer = OnlineRecognizer(config = config)
        stream = recognizer!!.createStream()
        committed = ""
    }

    fun accept(samples: FloatArray): String {
        val r = recognizer ?: error("ASR engine is not started")
        val s = stream ?: error("ASR stream is not started")
        s.acceptWaveform(samples, 16000)
        while (r.isReady(s)) r.decode(s)
        if (r.isEndpoint(s)) {
            val segment = r.getResult(s).text.trim()
            if (segment.isNotEmpty()) committed = listOf(committed, segment).filter { it.isNotEmpty() }.joinToString(" ")
            r.reset(s)
        }
        val partial = r.getResult(s).text.trim()
        return listOf(committed, partial).filter { it.isNotEmpty() }.joinToString(" ")
    }

    fun finish(): String {
        val r = recognizer ?: return committed
        val s = stream ?: return committed
        s.inputFinished()
        while (r.isReady(s)) r.decode(s)
        val tail = r.getResult(s).text.trim()
        val result = listOf(committed, tail).filter { it.isNotEmpty() }.joinToString(" ")
        close()
        return result
    }

    fun close() {
        stream?.release()
        stream = null
        recognizer?.release()
        recognizer = null
    }
}
