package com.jarvis.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.provider.AlarmClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var sr: SpeechRecognizer? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Color.parseColor("#03090d")
        window.navigationBarColor = Color.parseColor("#03090d")
        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#03090d"))
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = WebViewClient()
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("file:///android_asset/index.html")
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("pt", "BR")
                tts?.setPitch(0.8f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { emit("ttsStart", "1") }
                    override fun onDone(id: String?) { emit("ttsEnd", "1") }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { emit("ttsEnd", "1") }
                })
                ttsReady = true
            }
        }
    }

    override fun onDestroy() {
        sr?.destroy(); tts?.shutdown(); web.destroy()
        super.onDestroy()
    }

    private fun emit(name: String, json: String) {
        runOnUiThread { web.evaluateJavascript("window.nativeEvent&&window.nativeEvent('$name',$json)", null) }
    }

    private fun runUi(f: () -> String): String {
        val latch = CountDownLatch(1)
        var r = "ok"
        runOnUiThread {
            r = try { f() } catch (e: Exception) { "Falhou: " + (e.message ?: e.javaClass.simpleName) }
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        return r
    }

    inner class Bridge {
        @JavascriptInterface
        fun listen() {
            runOnUiThread {
                sr?.destroy()
                if (!SpeechRecognizer.isRecognitionAvailable(this@MainActivity)) {
                    emit("error", "\"unavailable\""); emit("listenEnd", "1"); return@runOnUiThread
                }
                val r = SpeechRecognizer.createSpeechRecognizer(this@MainActivity)
                sr = r
                r.setRecognitionListener(object : RecognitionListener {
                    private fun first(b: Bundle?) =
                        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                    override fun onReadyForSpeech(p: Bundle?) { emit("listenStart", "1") }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(v: Float) {}
                    override fun onBufferReceived(p: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(t: Int, p: Bundle?) {}
                    override fun onPartialResults(p: Bundle?) {
                        val t = first(p); if (t.isNotEmpty()) emit("partial", JSONObject.quote(t))
                    }
                    override fun onResults(p: Bundle?) {
                        val t = first(p)
                        if (t.isNotEmpty()) emit("final", JSONObject.quote(t))
                        emit("listenEnd", "1")
                    }
                    override fun onError(e: Int) { emit("error", e.toString()); emit("listenEnd", "1") }
                })
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                r.startListening(i)
            }
        }

        @JavascriptInterface
        fun stopListen() { runOnUiThread { sr?.stopListening() } }

        @JavascriptInterface
        fun speak(text: String, rate: Float) {
            if (!ttsReady) { emit("ttsEnd", "1"); return }
            tts?.setSpeechRate(rate)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "j")
        }

        @JavascriptInterface
        fun stopSpeak() { tts?.stop() }

        @JavascriptInterface
        fun http(id: Int, body: String, key: String) {
            Thread {
                var code = 0
                var text = ""
                try {
                    val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
                    c.requestMethod = "POST"
                    c.connectTimeout = 20000
                    c.readTimeout = 90000
                    c.doOutput = true
                    c.setRequestProperty("content-type", "application/json")
                    c.setRequestProperty("x-api-key", key)
                    c.setRequestProperty("anthropic-version", "2023-06-01")
                    c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    code = c.responseCode
                    val s = if (code >= 400) c.errorStream else c.inputStream
                    text = s?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
                } catch (e: Exception) {
                    code = 0; text = "{\"error\":{\"message\":\"" + (e.message ?: "falha de rede").replace("\"", "'") + "\"}}"
                }
                val o = JSONObject().put("id", id).put("status", code).put("body", text)
                emit("api", o.toString())
            }.start()
        }

        @JavascriptInterface
        fun tool(name: String, args: String): String {
            return try {
                val a = JSONObject(args)
                when (name) {
                    "lanterna" -> {
                        val cm = getSystemService(CAMERA_SERVICE) as CameraManager
                        val id = cm.cameraIdList.firstOrNull {
                            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                        } ?: return "Este aparelho não tem lanterna."
                        val on = a.optBoolean("ligar", true)
                        cm.setTorchMode(id, on)
                        if (on) "Lanterna ligada." else "Lanterna desligada."
                    }
                    "bateria" -> {
                        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
                        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                        val st = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                        val ch = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
                        "Bateria em $pct%" + if (ch) ", carregando." else ", sem carregar."
                    }
                    "abrir_app" -> {
                        val nome = a.getString("nome")
                        val li = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        val m = packageManager.queryIntentActivities(li, 0)
                            .firstOrNull { it.loadLabel(packageManager).toString().contains(nome, true) }
                        val l = m?.let { packageManager.getLaunchIntentForPackage(it.activityInfo.packageName) }
                        if (l == null) "Não encontrei um app chamado $nome."
                        else runUi { startActivity(l); "Abrindo $nome." }
                    }
                    "abrir_link" -> {
                        val u = a.getString("url")
                        runUi { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))); "Link aberto." }
                    }
                    "ligar_para" -> {
                        val n = a.getString("numero")
                        runUi {
                            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(n))))
                            "Discador aberto com $n. O usuário precisa tocar para ligar."
                        }
                    }
                    "alarme" -> {
                        val h = a.getInt("hora"); val mi = a.optInt("minuto", 0)
                        val i = Intent(AlarmClock.ACTION_SET_ALARM)
                            .putExtra(AlarmClock.EXTRA_HOUR, h)
                            .putExtra(AlarmClock.EXTRA_MINUTES, mi)
                            .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("rotulo", "Jarvis"))
                            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        runUi { startActivity(i); "Alarme criado para %02d:%02d.".format(h, mi) }
                    }
                    "timer" -> {
                        val s = a.getInt("segundos")
                        val i = Intent(AlarmClock.ACTION_SET_TIMER)
                            .putExtra(AlarmClock.EXTRA_LENGTH, s)
                            .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("rotulo", "Jarvis"))
                            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        runUi { startActivity(i); "Timer de $s segundos iniciado." }
                    }
                    else -> "Ferramenta desconhecida: $name"
                }
            } catch (e: Exception) {
                "Falhou: " + (e.message ?: e.javaClass.simpleName)
            }
        }
    }
}
