
package com.persiandubai

import androidx.compose.material3.*
import androidx.compose.runtime.*

import android.net.Uri
import android.os.Bundle
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            PersianDubApp()
        }
    }
}

@androidx.compose.runtime.Composable
fun PersianDubApp() {

    var selectedFile by remember {
        mutableStateOf<Uri?>(null)
    }

    var fileName by remember {
        mutableStateOf("هنوز ویدئویی انتخاب نشده است")
    }

    var serverUrl by remember {
        mutableStateOf("https://YOUR-SERVER-URL/")
    }

    var preserveMusic by remember {
        mutableStateOf(true)
    }

    var separateSpeakers by remember {
        mutableStateOf(true)
    }

    val context = androidx.compose.ui.platform.LocalContext.current

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            selectedFile = uri
            fileName = uri.lastPathSegment ?: "ویدئوی انتخاب‌شده"
        }
    }

    androidx.compose.material3.MaterialTheme {
        androidx.compose.foundation.layout.Column(
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
                text = "Persian Dub AI",
                style = MaterialTheme.typography.headlineMedium
            )

            Text(
                text = "دوبله هوشمند ویدئو به فارسی",
                style = MaterialTheme.typography.bodyLarge
            )

            Spacer(modifier = Modifier.height(30.dp))

            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("آدرس سرور دوبله") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    filePicker.launch("video/*")
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.CloudUpload,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("انتخاب ویدئو")
            }

            Text(
                text = fileName,
                modifier = Modifier.padding(12.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = preserveMusic,
                    onCheckedChange = { preserveMusic = it }
                )
                Text("حفظ موسیقی و صدای پس‌زمینه")
            }

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = separateSpeakers,
                    onCheckedChange = { separateSpeakers = it }
                )
                Text("تشخیص و تفکیک گویندگان")
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    // اتصال به موتور دوبله در بخش بعدی
                },
                enabled = selectedFile != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("شروع دوبله")
            }

            Spacer(modifier = Modifier.height(30.dp))

            Text(
                text = "نسخه آزمایشی 0.4.0",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
