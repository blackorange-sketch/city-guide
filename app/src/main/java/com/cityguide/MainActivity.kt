package com.cityguide

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.cityguide.data.Api
import com.cityguide.ui.CityGuideTheme
import com.cityguide.ui.MainScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.init(applicationContext)
        enableEdgeToEdge()
        setContent {
            CityGuideTheme {
                MainScreen()
            }
        }
    }
}
