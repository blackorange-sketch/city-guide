package com.cityguide

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.cityguide.data.Api
import com.cityguide.ui.CityGuideTheme
import com.cityguide.ui.MainScreen
import com.cityguide.ui.ThemeMode
import com.cityguide.ui.isDark

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.init(applicationContext)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        setContent {
            var mode by remember { mutableStateOf(ThemeMode.parse(prefs.getString("theme", null))) }
            val dark = isDark(mode)

            // Іконки системних панелей мають бути видимі на тлі обраної теми
            DisposableEffect(dark) {
                this@MainActivity.enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                    else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                    else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                )
                onDispose { }
            }

            CityGuideTheme(mode) {
                MainScreen(
                    themeMode = mode,
                    onCycleTheme = {
                        mode = mode.next()
                        prefs.edit().putString("theme", mode.name).apply()
                    }
                )
            }
        }
    }
}
