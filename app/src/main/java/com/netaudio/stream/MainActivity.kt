package com.netaudio.stream

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var hostEt: EditText
    private lateinit var portEt: EditText
    private lateinit var depthEt: EditText
    private lateinit var btn: Button
    private lateinit var statusTv: TextView
    private lateinit var logTv: TextView

    private val poll = object : Runnable {
        override fun run() {
            btn.text = if (StreamService.running) "Desconectar" else "Conectar"
            statusTv.text = StreamService.status
            logTv.text = synchronized(StreamService.logs) { StreamService.logs.joinToString("\n") }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("nas", MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val match = ViewGroup.LayoutParams.MATCH_PARENT

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun label(t: String) = TextView(this).apply { text = t; setPadding(0, pad / 2, 0, 0) }

        root.addView(TextView(this).apply { text = "NetAudio Stream"; textSize = 22f })
        root.addView(TextView(this).apply {
            text = "Toca no celular o áudio do PC. Inicie o servidor no PC e informe o IP dele aqui."
        })

        root.addView(label("IP do servidor"))
        hostEt = EditText(this).apply {
            hint = "ex.: 192.168.0.10"
            setText(prefs.getString("host", ""))
            inputType = InputType.TYPE_CLASS_PHONE
        }
        root.addView(hostEt, LinearLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(label("Porta TCP"))
        portEt = EditText(this).apply {
            setText(prefs.getString("port", "7000")); inputType = InputType.TYPE_CLASS_NUMBER
        }
        root.addView(portEt, LinearLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(label("Buffer (pacotes de 10 ms; mais = menos cortes, mais atraso)"))
        depthEt = EditText(this).apply {
            setText(prefs.getString("depth", "8")); inputType = InputType.TYPE_CLASS_NUMBER
        }
        root.addView(depthEt, LinearLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT))

        btn = Button(this).apply { text = "Conectar" }
        root.addView(btn, LinearLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT))
        statusTv = TextView(this).apply { text = "Desconectado"; setPadding(0, pad / 2, 0, pad / 2) }
        root.addView(statusTv)
        logTv = TextView(this).apply { textSize = 12f }
        root.addView(logTv)

        btn.setOnClickListener {
            if (StreamService.running) {
                startService(Intent(this, StreamService::class.java).setAction(StreamService.ACTION_STOP))
            } else {
                val host = hostEt.text.toString().trim()
                if (host.isEmpty()) {
                    Toast.makeText(this, "Informe o IP do servidor", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val port = portEt.text.toString().toIntOrNull() ?: 7000
                val depth = (depthEt.text.toString().toIntOrNull() ?: 8).coerceIn(2, 40)
                prefs.edit().putString("host", host).putString("port", port.toString())
                    .putString("depth", depth.toString()).apply()
                val i = Intent(this, StreamService::class.java)
                    .setAction(StreamService.ACTION_START)
                    .putExtra("host", host).putExtra("port", port).putExtra("depth", depth)
                startForegroundService(i)
            }
        }

        setContentView(ScrollView(this).apply { addView(root) })
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() { super.onResume(); handler.post(poll) }
    override fun onPause() { handler.removeCallbacks(poll); super.onPause() }
}
