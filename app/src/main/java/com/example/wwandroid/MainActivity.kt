package com.example.wwandroid
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
class MainActivity : ComponentActivity() {
    val client = OkHttpClient()
    internal var webView: WebView? = null
    private lateinit var prefs: SharedPreferences

    companion object {
        private const val TAG = "GameManager"
        private const val BASE_URL = "https://gamandroid.cyou/app.php"
        var USER_AGENT = ""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = getSharedPreferences("game_prefs", Context.MODE_PRIVATE)

        USER_AGENT = WebView(this).settings.userAgentString

        setContent {
            SendRequest(this@MainActivity)
        }
    }

    suspend fun getGameInformation() {
        Log.d(TAG, "🚀 getGameInformation() START")
        decodeGameInformation()
        Log.d(TAG, "✅ getGameInformation() END")
    }

    private suspend fun decodeGameInformation() {
        Log.d(TAG, "decodeGameInformation()")

        val taskLink = prefs.getString("taskLink", "")
        Log.d(TAG, "taskLink: $taskLink")

        if (!taskLink.isNullOrEmpty()) {
            Log.d(TAG, "taskLink exists → openGameInformation()")
            openGameInformation()
            return
        }

        val controlsLink = prefs.getString("controlsLink", null)
        if (controlsLink == null) {
            Log.d(TAG, "No controlsLink → configureGameInformation()")
            configureGameInformation()
        }

        val fcmToken = prefs.getString("fcmToken", "null") ?: "null"
        val queryItems = listOf("firebase_push_token" to fcmToken)
        val domainLink = prefs.getString("controlsLink", "") ?: ""

        Log.d(TAG, "domainLink: $domainLink")
        if (domainLink.isEmpty()) {
            Log.w(TAG, "Empty domainLink → EXIT")
            return
        }

        val controlsLinkUrl = buildUrl(domainLink, queryItems)
        if (controlsLinkUrl == null) {
            Log.e(TAG,  "Invalid controlsLink URL")
            return
        }

        Log.d(TAG, "POST to: $controlsLinkUrl")

        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(controlsLinkUrl)
                .post("".toRequestBody("application/json".toMediaType()))
                .addHeader("User-Agent", USER_AGENT)
                .build()

            try {
                val response = client.newCall(request).execute()
                Log.d(TAG, "📥 Response code: ${response.code}")

                if (!response.isSuccessful) {
                    Log.e(TAG, "HTTP Error: ${response.code}")
                    response.close()
                    return@withContext
                }

                val responseData = response.body?.string()
                Log.d(TAG, "📦 Response data: $responseData")

                val clientResponse = parseGameInformationResponse(responseData)
                response.close()

                clientResponse?.let { responseObj ->
                    prefs.edit { putString("client_id", responseObj.client_id) }

                    responseObj.response?.let { taskLink ->
                        if (isValidUrl(taskLink)) {
                            Log.d(TAG, "💾 Save taskLink: $taskLink")
                            prefs.edit { putString("taskLink", taskLink) }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "decodeGameInformation error: ${e.message}")
            }
        }
    }

    private suspend fun setupGameInformation(): String? {
        Log.d(TAG, "setupGameInformation()")

        val userId = prefs.getString("userId", "1") ?: "1"

        return withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL?action=check_info"
                val request = Request.Builder()
                    .url(url)
                    .post("".toRequestBody("application/json".toMediaType()))
                    .addHeader("User-Agent", USER_AGENT)
                    .addHeader("client-uuid", userId)
                    .build()

                val response = client.newCall(request).execute()
                Log.d(TAG, "setup response: ${response.code}")

                response.header("service-link")?.let { serviceLink ->
                    Log.d(TAG, "service-link header: $serviceLink")
                    response.close()
                    return@withContext serviceLink
                }

                response.close()
                null
            } catch (e: Exception) {
                Log.e(TAG, "setupGameInformation error: ${e.message}")
                null
            }
        }
    }

    private suspend fun configureGameInformation() {
        Log.d(TAG, "configureGameInformation()")

        var userId = prefs.getString("userId", "") ?: ""
        if (userId.isEmpty()) {
            userId = UUID.randomUUID().toString()
            prefs.edit { putString("userId", userId) }
            Log.d(TAG, "New userId: $userId")
        }

        val response = setupGameInformation()
        if (response != null && isValidUrl(response)) {
            Log.d(TAG, "Save controlsLink: $response")
            prefs.edit { putString("controlsLink", response) }
        }
    }

    private suspend fun openGameInformation() {
        Log.d(TAG, "🎮 openGameInformation()")

        val clientId = prefs.getString("client_id", "1") ?: "1"
        val fcmToken = prefs.getString("fcmToken", "null") ?: "null"

        val queryItems = listOf(
            "client_id" to clientId,
            "firebase_push_token" to fcmToken
        )

        val domainLink = prefs.getString("controlsLink", "") ?: ""
        if (domainLink.isEmpty()) {
            Log.w(TAG, "Empty domainLink")
            return
        }

        val controlsLinkUrl = buildUrl(domainLink, queryItems)
        if (controlsLinkUrl == null) {
            Log.e(TAG, "Invalid controlsLink URL")
            return
        }

        Log.d(TAG, "POST openGame: $controlsLinkUrl")

        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(controlsLinkUrl)
                .post("".toRequestBody("application/json".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .addHeader("client-uuid", prefs.getString("userId", "1") ?: "1")
                .addHeader("User-Agent", USER_AGENT)
                .build()

            try {
                val response = client.newCall(request).execute()
                Log.d(TAG, "📥 openGame response: ${response.code}")

                if (!response.isSuccessful) {
                    Log.e(TAG, "HTTP Error: ${response.code}")
                    response.close()
                    return@withContext
                }

                val responseData = response.body?.string()
                Log.d(TAG, "📦 openGame data: $responseData")

                val clientResponse = parseGameInformationResponse(responseData)
                response.close()

                clientResponse?.response?.let { taskLink ->
                    if (isValidUrl(taskLink)) {
                        Log.d(TAG, "💾 Update taskLink: $taskLink")
                        prefs.edit { putString("taskLink", taskLink) }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "openGameInformation error: ${e.message}")
            }
        }
    }

    private fun buildUrl(baseUrl: String, params: List<Pair<String, String>>): String? {
        val uri = baseUrl.toUri().buildUpon()
        params.forEach { (key, value) -> uri.appendQueryParameter(key, value) }
        return uri.build().toString()
    }

    private fun isValidUrl(url: String): Boolean {
        return try {
            url.toUri().scheme != null
        } catch (e: Exception) {
            false
        }
    }

    private fun parseGameInformationResponse(json: String?): GameInformationResponse? {
        return try {
            json?.let {
                val jsonObj = JSONObject(it)
                GameInformationResponse(
                    client_id = jsonObj.optString("client_id"),
                    response = jsonObj.optString("response")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse error: ${e.message}")
            null
        }
    }
}

data class GameInformationResponse(
    val client_id: String,
    val response: String?
)


@Composable
fun SendRequest(activity: MainActivity) {
    var isLoaded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        if (!isLoaded) {
            Button(
                onClick = {
                    activity.lifecycleScope.launch {
                        activity.getGameInformation()
                        isLoaded = true
                    }
                }
            ) {
                Text("Send request")
            }
        } else {
            WebViewScreen(activity)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewScreen(activity: MainActivity) {
    val prefs = activity.getSharedPreferences("game_prefs", Context.MODE_PRIVATE)
    val taskLink = prefs.getString("taskLink", "") ?: ""

    var canGoBack by remember { mutableStateOf(false) }
    var isAllowedToShow by remember { mutableStateOf(false) }
    var isChecking by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 24.dp, start = 16.dp, end = 16.dp, bottom = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        if (taskLink.isNotEmpty()) {
            AndroidView(
                factory = { context ->
                    val webView = WebView(context)
                    activity.webView = webView

                    webView.settings.javaScriptEnabled = true
                    webView.settings.domStorageEnabled = true
                    webView.settings.userAgentString = MainActivity.USER_AGENT

                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            canGoBack = webView.canGoBack()
                        }
                    }

                    if (!isChecking && !isAllowedToShow) {
                        isChecking = true
                        activity.lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val request = Request.Builder()
                                    .url(taskLink)
                                    .header("User-Agent", MainActivity.USER_AGENT)
                                    .build()

                                val response = activity.client.newCall(request).execute()
                                val code = response.code

                                if (code == 200 || code in 300..399) {
                                    withContext(Dispatchers.Main) {
                                        isAllowedToShow = true
                                        webView.loadUrl(taskLink)
                                    }
                                }

                                response.close()
                            } catch (_: Exception) {
                            } finally {
                                withContext(Dispatchers.Main) {
                                    isChecking = false
                                }
                            }
                        }
                    }

                    webView
                },
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(canGoBack) {
                        detectHorizontalDragGestures { change, dragAmount ->
                            val fromLeftEdge = change.position.x < 32.dp.toPx()
                            if (fromLeftEdge && dragAmount > 20 && canGoBack) {
                                activity.webView?.goBack()
                            }
                        }
                    }
            )

//            if (canGoBack && isAllowedToShow) {
//                Button(
//                    onClick = {
//                        val wv = activity.webView
//                        if (wv != null && wv.canGoBack()) {
//                            wv.goBack()
//                        }
//                    },
//                    modifier = Modifier
//                        .align(Alignment.TopStart)
//                        .padding(4.dp)
//                ) {
//                    Icon(
//                        imageVector = Icons.Filled.ArrowBack,
//                        contentDescription = ""
//                    )
//                }
//            }
        } else {
            Text("Empty task link")
        }
    }
}
