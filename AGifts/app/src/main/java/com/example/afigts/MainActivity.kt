package com.example.afigts

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.afigts.data.AppDb
import com.example.afigts.ui.AFigtsApp
import com.example.afigts.ui.theme.AFigtsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 数据库需在 Compose 首次组合前完成初始化
        AppDb.init(applicationContext)
        enableEdgeToEdge()
        setContent {
            AFigtsTheme {
                AFigtsApp()
            }
        }
    }
}
