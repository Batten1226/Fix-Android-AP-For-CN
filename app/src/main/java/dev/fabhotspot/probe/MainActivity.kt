package dev.fabhotspot.probe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.fabhotspot.probe.ui.ProbeScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 允许通过 adb 触发自动体检：
        //   adb shell am start -n dev.fabhotspot.probe/.MainActivity --ez autorun true
        val autoRun = intent?.getBooleanExtra("autorun", false) == true
        setContent { ProbeScreen(autoRun = autoRun) }
    }
}
