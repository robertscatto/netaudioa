package com.netaudio.stream

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

/** Cliente do protocolo NAS1: TCP (HELLO/CONFIG/READY) + UDP (header 16 bytes + PCM s16le). */
class StreamClient(
    private val host: String,
    private val port: Int,
    private val depth: Int,
    private val onLog: (String) -> Unit,
    private val onStats: (String) -> Unit
) {
    @Volatile private var running = false
    private var tcp: Socket? = null
    private var udp: DatagramSocket? = null

    // ---- jitter buffer ----
    private val lock = Object()
    private val buf = HashMap<Long, ByteArray>()
    private var expected: Long = -1
    private var started = false
    private var received = 0L
    private var lost = 0L
    private var late = 0L
    private var underruns = 0L

    private val mask = 0xFFFFFFFFL

    private fun before(a: Long, b: Long) = ((a - b) and mask) >= 0x80000000L

    private fun push(seq: Long, payload: ByteArray) = synchronized(lock) {
        received++
        if (expected < 0) expected = seq
        if (before(seq, expected)) { late++; return@synchronized }
        buf[seq] = payload
        if (buf.size >= depth) started = true
        if (buf.size > depth * 4) {                       // acumulou latência: pula para o mais novo
            val exp = expected
            expected = buf.keys.minByOrNull { (it - exp) and mask } ?: exp
        }
    }

    private fun pop(silence: ByteArray): ByteArray = synchronized(lock) {
        if (!started) return@synchronized silence
        var p = buf.remove(expected)
        if (p == null) {
            if (buf.isEmpty()) { underruns++; started = false; return@synchronized silence }
            lost++
            p = silence
        }
        expected = (expected + 1) and mask
        p
    }

    // ---- protocolo de controle ----
    private fun sendMsg(out: DataOutputStream, o: JSONObject) {
        val raw = o.toString().toByteArray(Charsets.UTF_8)
        out.writeInt(raw.size)
        out.write(raw)
        out.flush()
    }

    private fun recvMsg(inp: DataInputStream): JSONObject {
        val n = inp.readInt()
        if (n <= 0 || n > (1 shl 20)) throw IOException("mensagem de controle inválida")
        val b = ByteArray(n)
        inp.readFully(b)
        return JSONObject(String(b, Charsets.UTF_8))
    }

    /** Bloqueante: conecta, faz o handshake e toca até stop() ou queda da conexão. */
    fun run() {
        running = true
        var track: AudioTrack? = null
        try {
            val t = Socket()
            t.connect(InetSocketAddress(host, port), 5000)
            tcp = t
            val u = DatagramSocket()
            u.soTimeout = 500
            try { u.receiveBufferSize = 1 shl 20 } catch (_: Exception) {}
            udp = u

            val out = DataOutputStream(t.getOutputStream())
            val inp = DataInputStream(t.getInputStream())
            sendMsg(out, JSONObject().put("type", "HELLO").put("version", 1).put("udp_port", u.localPort))
            t.soTimeout = 10000
            val cfg = recvMsg(inp)
            if (cfg.optString("encoding") != "pcm_s16le") throw IllegalStateException("Formato de áudio não suportado")
            val rate = cfg.getInt("sample_rate")
            val channels = cfg.getInt("channels")
            val fpp = cfg.getInt("frames_per_packet")

            Thread { receiveLoop(u) }.start()
            sendMsg(out, JSONObject().put("type", "READY"))
            t.soTimeout = 0
            Thread {
                try { while (inp.read() >= 0) { } } catch (_: Exception) {}
                if (running) { onLog("Servidor encerrou a conexão."); running = false }
            }.start()

            val chMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val blockBytes = fpp * channels * 2
            val minBuf = AudioTrack.getMinBufferSize(rate, chMask, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setChannelMask(chMask)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, blockBytes * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            track.play()
            onLog("Conectado a $host: $rate Hz, $channels canais")

            val silence = ByteArray(blockBytes)
            var lastStat = 0L
            while (running) {
                val p = pop(silence)
                track.write(p, 0, p.size)               // bloqueante: o relógio da placa dita o ritmo
                val now = System.currentTimeMillis()
                if (now - lastStat > 1000) {
                    lastStat = now
                    onStats("recebidos $received | perdidos $lost | atrasados $late | rebuffers $underruns")
                }
            }
        } finally {
            running = false
            try { track?.stop() } catch (_: Exception) {}
            try { track?.release() } catch (_: Exception) {}
            try { tcp?.close() } catch (_: Exception) {}
            try { udp?.close() } catch (_: Exception) {}
        }
    }

    private fun receiveLoop(u: DatagramSocket) {
        val data = ByteArray(65535)
        val dp = DatagramPacket(data, data.size)
        while (running) {
            try {
                u.receive(dp)
            } catch (_: java.net.SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                return
            }
            val len = dp.length
            if (len < 16) continue
            val bb = ByteBuffer.wrap(data, 0, len)           // big-endian (network order)
            val magic = ByteArray(4); bb.get(magic)
            if (String(magic, Charsets.ISO_8859_1) != "NAS1") continue
            val ver = bb.get().toInt()
            val type = bb.get().toInt()
            val seq = bb.getInt().toLong() and mask
            bb.getInt()                                      // timestamp (reservado p/ sincronização)
            val plen = bb.getShort().toInt() and 0xFFFF
            if (ver != 1 || type != 1 || len - 16 != plen) continue
            push(seq, data.copyOfRange(16, len))
        }
    }

    fun stop() {
        running = false
        try { tcp?.close() } catch (_: Exception) {}
        try { udp?.close() } catch (_: Exception) {}
    }
}
