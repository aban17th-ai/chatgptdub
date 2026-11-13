
package com.persiandubai

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.InputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PersianDubApp()
        }
    }
}

@Composable
fun PersianDubApp() {

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val preferences = remember {
        context.getSharedPreferences(
            "persian_dub_settings",
            Context.MODE_PRIVATE
        )
    }

    var selectedFile by remember { mutableStateOf<Uri?>(null) }

    var fileName by remember {
        mutableStateOf("هنوز ویدئویی انتخاب نشده است")
    }

    var serverUrl by remember {
        mutableStateOf("https://YOUR-SERVER-URL/")
    }

    var geminiApiKey by remember {
        mutableStateOf(
            preferences.getString("gemini_api_key", "") ?: ""
        )
    }

    var keyValid by remember { mutableStateOf(false) }
    var isValidating by remember { mutableStateOf(false) }

    var preserveMusic by remember { mutableStateOf(true) }
    var separateSpeakers by remember { mutableStateOf(true) }

    var isWorking by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var statusText by remember { mutableStateOf("") }
    var keyStatus by remember { mutableStateOf("") }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            selectedFile = uri
            fileName = getDisplayName(context, uri)
            statusText = "ویدئو انتخاب شد."
        }
    }

    MaterialTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Spacer(modifier = Modifier.height(25.dp))

            Icon(
                imageVector = Icons.Default.Movie,
                contentDescription = null,
                modifier = Modifier.size(65.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                "Persian Dub AI",
                style = MaterialTheme.typography.headlineMedium
            )

            Text("دوبله هوشمند ویدئو به فارسی")

            Spacer(modifier = Modifier.height(25.dp))

            OutlinedTextField(
                value = serverUrl,
                onValueChange = {
                    serverUrl = it
                    keyValid = false
                    keyStatus = ""
                },
                label = { Text("آدرس سرور دوبله") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isWorking && !isValidating
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = geminiApiKey,
                onValueChange = {
                    geminiApiKey = it
                    keyValid = false
                    keyStatus = ""
                    preferences.edit()
                        .putString("gemini_api_key", it)
                        .apply()
                },
                label = { Text("کلید API جمنای") },
                placeholder = { Text("کلید Gemini را وارد کن") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                enabled = !isWorking && !isValidating
            )

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = {
                    scope.launch {
                        isValidating = true
                        keyValid = false
                        keyStatus = "در حال بررسی کلید Gemini..."

                        try {
                            val baseUrl = serverUrl.trim()

                            require(
                                baseUrl.startsWith("https://") ||
                                baseUrl.startsWith("http://")
                            ) {
                                "آدرس سرور معتبر نیست."
                            }

                            require(geminiApiKey.isNotBlank()) {
                                "ابتدا کلید Gemini را وارد کن."
                            }

                            val api = createDubApi(baseUrl)

                            val result = api.validateGeminiKey(
                                GeminiKeyRequest(
                                    api_key = geminiApiKey.trim()
                                )
                            )

                            keyValid = result.valid

                            keyStatus = if (result.valid) {
                                "کلید Gemini معتبر است."
                            } else {
                                result.message.ifBlank {
                                    "کلید Gemini معتبر نیست."
                                }
                            }

                        } catch (e: Exception) {
                            keyValid = false
                            keyStatus =
                                "خطا در بررسی کلید: ${e.message ?: "ارتباط ناموفق"}"
                        } finally {
                            isValidating = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isWorking && !isValidating
            ) {
                Text(
                    if (isValidating) {
                        "در حال بررسی..."
                    } else {
                        "بررسی اعتبار کلید Gemini"
                    }
                )
            }

            if (keyStatus.isNotBlank()) {
                Text(
                    text = keyStatus,
                    color = if (keyValid) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.padding(8.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = { filePicker.launch("video/*") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isWorking && !isValidating
            ) {
                Icon(Icons.Default.CloudUpload, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("انتخاب ویدئو")
            }

            Text(
                text = fileName,
                modifier = Modifier.padding(12.dp)
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = preserveMusic,
                    onCheckedChange = { preserveMusic = it },
                    enabled = !isWorking
                )
                Text("حفظ موسیقی و صدای پس‌زمینه")
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = separateSpeakers,
                    onCheckedChange = { separateSpeakers = it },
                    enabled = !isWorking
                )
                Text("تشخیص و تفکیک گویندگان")
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    val uri = selectedFile

                    if (uri == null) {
                        statusText = "ابتدا یک ویدئو انتخاب کن."
                        return@Button
                    }

                    if (!keyValid) {
                        statusText = "ابتدا اعتبار کلید Gemini را بررسی کن."
                        return@Button
                    }

                    scope.launch {
                        isWorking = true
                        progress = 0
                        statusText = "در حال آماده‌سازی ویدئو..."

                        var tempFile: File? = null

                        try {
                            val baseUrl = serverUrl.trim()

                            require(
                                baseUrl.startsWith("https://") ||
                                baseUrl.startsWith("http://")
                            ) {
                                "آدرس سرور معتبر نیست."
                            }

                            tempFile = withContext(Dispatchers.IO) {
                                val file = File(
                                    context.cacheDir,
                                    "upload_${System.currentTimeMillis()}.mp4"
                                )

                                context.contentResolver
                                    .openInputStream(uri)
                                    ?.use { input ->
                                        file.outputStream().use { output ->
                                            input.copyTo(output)
                                        }
                                    } ?: throw Exception(
                                        "خواندن فایل ویدئو ناموفق بود."
                                    )

                                file
                            }

                            statusText = "در حال ارسال ویدئو به سرور..."

                            val api = createDubApi(baseUrl)

                            val mediaType = "video/mp4".toMediaType()

                            val mediaPart =
                                MultipartBody.Part.createFormData(
                                    "media",
                                    fileName,
                                    tempFile.asRequestBody(mediaType)
                                )

                            val textType = "text/plain".toMediaType()

                            val language =
                                "fa".toRequestBody(textType)

                            val preserve = preserveMusic
                                .toString()
                                .toRequestBody(textType)

                            val speakers = separateSpeakers
                                .toString()
                                .toRequestBody(textType)

                            val apiKey = geminiApiKey.trim()
                                .toRequestBody(textType)

                            val job = api.createJob(
                                mediaPart,
                                language,
                                preserve,
                                speakers,
                                apiKey
                            )

                            statusText =
                                "ویدئو ارسال شد. پردازش دوبله آغاز شد."

                            var completed = false

                            for (attempt in 1..600) {
                                delay(3000)

                                val result = api.getJob(job.job_id)

                                progress =
                                    result.progress.coerceIn(0, 100)

                                statusText = result.message.ifBlank {
                                    "در حال پردازش دوبله..."
                                }

                                when (result.status.lowercase()) {
                                    "completed" -> {
                                        completed = true
                                        break
                                    }

                                    "failed", "error" -> {
                                        throw Exception(
                                            result.message.ifBlank {
                                                "پردازش دوبله ناموفق بود."
                                            }
                                        )
                                    }
                                }
                            }

                            if (!completed) {
                                throw Exception(
                                    "زمان انتظار برای پردازش به پایان رسید."
                                )
                            }

                            statusText =
                                "دوبله آماده شد. در حال دریافت فایل..."

                            withContext(Dispatchers.IO) {
                                api.downloadJob(job.job_id).use { body ->
                                    saveVideo(
                                        context,
                                        body.byteStream(),
                                        "PersianDub_${System.currentTimeMillis()}.mp4"
                                    )
                                }
                            }

                            progress = 100
                            statusText =
                                "دوبله با موفقیت دریافت و ذخیره شد."

                        } catch (e: Exception) {
                            statusText =
                                "خطا: ${e.message ?: "خطای نامشخص"}"
                        } finally {
                            tempFile?.delete()
                            isWorking = false
                        }
                    }
                },
                enabled = selectedFile != null &&
                    !isWorking &&
                    !isValidating &&
                    keyValid,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (isWorking) {
                        "در حال دوبله..."
                    } else {
                        "شروع دوبله"
                    }
                )
            }

            if (isWorking || progress > 0) {
                Spacer(modifier = Modifier.height(20.dp))

                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    "$progress٪",
                    modifier = Modifier.padding(8.dp)
                )
            }

            if (statusText.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(modifier = Modifier.height(30.dp))

            Text(
                "نسخه آزمایشی 0.5.0",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

fun getDisplayName(context: Context, uri: Uri): String {
    var name = "video.mp4"

    context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(
                OpenableColumns.DISPLAY_NAME
            )
            if (index >= 0) {
                name = cursor.getString(index) ?: name
            }
        }
    }

    return name
}

fun saveVideo(
    context: Context,
    input: InputStream,
    fileName: String
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(
                MediaStore.Video.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_MOVIES}/PersianDubAI"
            )
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val uri = context.contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: throw Exception("ساخت فایل خروجی ناموفق بود.")

        try {
            context.contentResolver.openOutputStream(uri)?.use {
                input.copyTo(it)
            } ?: throw Exception("ذخیره فایل ناموفق بود.")

            val finished = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }

            context.contentResolver.update(uri, finished, null, null)

        } catch (e: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw e
        }

    } else {

        val directory = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            "PersianDubAI"
        )

        directory.mkdirs()

        val outputFile = File(directory, fileName)

        outputFile.outputStream().use {
            input.copyTo(it)
        }
    }
}
