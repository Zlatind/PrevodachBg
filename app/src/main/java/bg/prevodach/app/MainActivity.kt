package bg.prevodach.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.webkit.WebViewAssetLoader
import android.webkit.WebResourceResponse
import android.util.Base64
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val chooseFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = fileCallback
        fileCallback = null
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val data = result.data!!
            val uris = mutableListOf<Uri>()
            data.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
            } ?: data.data?.let { uris.add(it) }
            callback?.onReceiveValue(uris.toTypedArray())
        } else callback?.onReceiveValue(null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = true
        webView.settings.setSupportZoom(true)
        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                request?.let { assetLoader.shouldInterceptRequest(it.url) }?.let { return it }
                return super.shouldInterceptRequest(view, request)
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val host = request.url.host ?: return false
                return !(host == "appassets.androidplatform.net" || host.endsWith("cdnjs.cloudflare.com"))
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.fileCallback?.onReceiveValue(null)
                this@MainActivity.fileCallback = filePathCallback
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                        "text/plain", "application/pdf",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    ))
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                }
                chooseFile.launch(intent)
                return true
            }
        }
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun translateGoogle(text: String, target: String, apiKey: String, callbackId: Int) {
            Thread {
                val result = JSONObject()
                var connection: HttpURLConnection? = null
                try {
                    require(apiKey.isNotBlank()) { "Липсва Google API ключ." }
                    val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
                    connection = (URL("https://translation.googleapis.com/language/translate/v2?key=$encodedKey").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 20000
                        readTimeout = 60000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    }
                    val body = JSONObject().put("q", text).put("target", target).put("format", "text").toString()
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    val code = connection.responseCode
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "{}"
                    val response = JSONObject(responseText)
                    if (code !in 200..299) {
                        val message = response.optJSONObject("error")?.optString("message") ?: "HTTP $code"
                        throw IllegalStateException(message)
                    }
                    val translation = response.optJSONObject("data")?.optJSONArray("translations")?.optJSONObject(0)
                        ?: throw IllegalStateException("Google API не върна превод.")
                    result.put("ok", true)
                    result.put("translatedText", translation.optString("translatedText"))
                    result.put("detectedSourceLanguage", translation.optString("detectedSourceLanguage"))
                } catch (e: Exception) {
                    result.put("ok", false)
                    result.put("error", e.message ?: "Грешка при връзка с Google Translation API.")
                } finally {
                    connection?.disconnect()
                }
                webView.post {
                    webView.evaluateJavascript("window.onGoogleTranslationResult && window.onGoogleTranslationResult($callbackId, ${result});", null)
                }
            }.start()
        }

        @JavascriptInterface
        fun saveFile(filename: String, mimeType: String, base64Data: String) {
            try {
                val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                val safeName = filename.replace(Regex("[^a-zA-Z0-9._А-Яа-яЁё -]"), "_")
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                }
                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Не може да се създаде файл в Downloads")
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw IllegalStateException("Не може да се запише файлът")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                runOnUiThread { Toast.makeText(this@MainActivity, "Файлът е записан в Downloads", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this@MainActivity, "Грешка при запис: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    @Deprecated("Deprecated in Android API")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
