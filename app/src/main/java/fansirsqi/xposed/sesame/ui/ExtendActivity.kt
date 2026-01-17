package fansirsqi.xposed.sesame.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import fansirsqi.xposed.sesame.ui.screen.ExtendScreen
import fansirsqi.xposed.sesame.ui.extension.WatermarkLayer
import fansirsqi.xposed.sesame.ui.theme.AppTheme

class ExtendActivity : AppCompatActivity() {

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