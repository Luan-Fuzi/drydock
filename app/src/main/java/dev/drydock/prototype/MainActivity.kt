package dev.drydock.prototype

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SelfCheckScreen()
                }
            }
        }
    }
}

@Composable
fun SelfCheckScreen() {
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ProotSelfCheck.Result?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Drydock 原型 · 步骤 1 自检", style = MaterialTheme.typography.titleLarge)

        Text(
            "libproot.so -r \$filesDir/rootfs -b /system /bin/sh -c 'uname -a'",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )

        Button(
            enabled = !running,
            onClick = {
                running = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        ProotSelfCheck.run(context.applicationContext)
                    }
                    result = r
                    running = false
                }
            },
        ) {
            Text(if (running) "运行中…" else "运行 proot 自检")
        }

        result?.let { r ->
            val pass = ProotSelfCheck.passed(r)
            Text(
                if (pass) "✓ 通过（exit=${r.exitCode}）" else "✗ 未通过（exit=${r.exitCode}）",
                color = if (pass) Color(0xFF4ADE80) else MaterialTheme.colorScheme.error,
            )
            Text(
                r.output.ifBlank { "(无输出)" },
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}
