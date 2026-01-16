package fansirsqi.xposed.sesame.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import fansirsqi.xposed.sesame.ui.screen.ExtendScreen
import fansirsqi.xposed.sesame.ui.extension.WatermarkLayer
import fansirsqi.xposed.sesame.ui.theme.AppTheme

class ExtendActivity : ComponentActivity() { // 注意：改继承 ComponentActivity 或 AppCompatActivity

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        setContent {
            AppTheme {
                WatermarkLayer {
                    ExtendScreen(onBackClick = { finish() })
                }
            }

        }
    }
}