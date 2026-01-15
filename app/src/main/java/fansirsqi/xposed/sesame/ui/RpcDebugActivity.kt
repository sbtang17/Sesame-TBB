package fansirsqi.xposed.sesame.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import fansirsqi.xposed.sesame.ui.screen.RpcDebugScreen
import fansirsqi.xposed.sesame.ui.screen.WatermarkLayer
import fansirsqi.xposed.sesame.ui.theme.AppTheme

class RpcDebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                WatermarkLayer {
                    RpcDebugScreen(onBack = { finish() })
                }
            }

        }
    }
}