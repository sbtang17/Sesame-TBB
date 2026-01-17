package fansirsqi.xposed.sesame.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import fansirsqi.xposed.sesame.data.Config
import fansirsqi.xposed.sesame.entity.UserEntity
import fansirsqi.xposed.sesame.model.Model
import fansirsqi.xposed.sesame.task.antForest.AntForest
import fansirsqi.xposed.sesame.task.manualtask.FarmSubTask
import fansirsqi.xposed.sesame.ui.extension.WatermarkLayer
import fansirsqi.xposed.sesame.ui.theme.AppTheme
import fansirsqi.xposed.sesame.ui.viewmodel.MainViewModel
import fansirsqi.xposed.sesame.util.DataStore
import fansirsqi.xposed.sesame.util.Files
import fansirsqi.xposed.sesame.util.ToastUtil

/**
 * 手动任务 Fragment (Compose 实现)
 * 采用列表展示所有可用的子任务，点击即可运行
 */
class ManualTaskFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // 确保模型系统和配置已加载
        ensureConfigLoaded()

        return ComposeView(requireContext()).apply {
            setContent {
                AppTheme {
                    WatermarkLayer(uidList = MainViewModel.verifuids) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background
                        ) {
                            ManualTaskScreen(
                                onTaskClick = { task, params -> runTask(task, params) }
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * 确保当前用户的配置已加载到内存模型中
     */
    private fun ensureConfigLoaded() {
        // 1. 初始化所有模型实例
        Model.initAllModel()
        
        // 2. 获取活跃用户并加载其配置
        val activeUser = DataStore.get("activedUser", UserEntity::class.java)
        activeUser?.userId?.let { uid ->
            Config.load(uid)
        }
    }

    private fun runTask(task: FarmSubTask, params: Map<String, Any>) {
        try {
            // 1. 发送任务指令广播
            val intent = Intent("com.eg.android.AlipayGphone.sesame.manual_task")
            intent.putExtra("task", task.name)
            params.forEach { (key, value) ->
                when (value) {
                    is Int -> intent.putExtra(key, value)
                    is String -> intent.putExtra(key, value)
                    is Boolean -> intent.putExtra(key, value)
                }
            }
            requireContext().sendBroadcast(intent)
            ToastUtil.showToast(requireContext(), "🚀 已发送指令: ${task.displayName}")

            // 2. 跳转到日志页面
            openRecordLog()

        } catch (e: Exception) {
            ToastUtil.showToast(requireContext(), "❌ 发送失败: ${e.message}")
        }
    }

    private fun openRecordLog() {
        val logFile = Files.getRecordLogFile()
        if (!logFile.exists()) {
            ToastUtil.showToast(requireContext(), "日志文件尚未生成")
            return
        }
        val intent = Intent(requireContext(), LogViewerActivity::class.java).apply {
            data = logFile.toUri()
        }
        startActivity(intent)
    }
}

private val toolDisplayNameMap = mapOf(
    "BIG_EATER_TOOL" to "加饭卡",
    "NEWEGGTOOL" to "新蛋卡",
    "FENCETOOL" to "篱笆卡"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualTaskScreen(onTaskClick: (FarmSubTask, Map<String, Any>) -> Unit) {
    val tasks = FarmSubTask.entries.toTypedArray()
    
    // 从模型系统中读取实例（此时 getFields() 返回的字段已被 Config.load 挂载了正确的值）
    val antForestModel = remember { Model.getModel(AntForest::class.java) }

    // 初始化打地鼠参数
    val initialMode = remember(antForestModel) {
        val mode = antForestModel?.whackMoleMode?.value ?: 1
        if (mode == 0) 1 else mode
    }
    val initialGames = remember(antForestModel) {
        (antForestModel?.whackMoleGames?.value ?: 5).toString()
    }

    // 子任务状态
    var whackMoleMode by remember { mutableIntStateOf(initialMode) } 
    var whackMoleGames by remember { mutableStateOf(initialGames) }
    var specialFoodCount by remember { mutableStateOf("1") }
    
    // 道具使用状态
    var selectedTool by remember { mutableStateOf("BIG_EATER_TOOL") }
    var toolCount by remember { mutableStateOf("1") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("手动任务流程") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            items(tasks) { task ->
                val params = when (task) {
                    FarmSubTask.FOREST_WHACK_MOLE -> mapOf(
                        "whackMoleMode" to whackMoleMode,
                        "whackMoleGames" to (whackMoleGames.toIntOrNull() ?: 5)
                    )
                    FarmSubTask.FARM_SPECIAL_FOOD -> {
                        val count = specialFoodCount.toIntOrNull() ?: 0
                        mapOf("specialFoodCount" to count)
                    }
                    FarmSubTask.FARM_USE_TOOL -> mapOf(
                        "toolType" to selectedTool,
                        "toolCount" to (toolCount.toIntOrNull() ?: 1)
                    )
                    else -> emptyMap()
                }

                TaskItem(
                    task = task, 
                    onClick = { onTaskClick(task, params) },
                    hasSettings = task == FarmSubTask.FOREST_WHACK_MOLE || task == FarmSubTask.FARM_SPECIAL_FOOD || task == FarmSubTask.FARM_USE_TOOL,
                    whackMoleMode = whackMoleMode,
                    onModeChange = { whackMoleMode = it },
                    whackMoleGames = whackMoleGames,
                    onGamesChange = { whackMoleGames = it },
                    specialFoodCount = specialFoodCount,
                    onSpecialFoodCountChange = { specialFoodCount = it },
                    selectedTool = selectedTool,
                    onToolChange = { selectedTool = it },
                    toolCount = toolCount,
                    onToolCountChange = { toolCount = it }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = Color.LightGray)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskItem(
    task: FarmSubTask, 
    onClick: () -> Unit,
    hasSettings: Boolean = false,
    whackMoleMode: Int = 1,
    onModeChange: (Int) -> Unit = {},
    whackMoleGames: String = "5",
    onGamesChange: (String) -> Unit = {},
    specialFoodCount: String = "0",
    onSpecialFoodCountChange: (String) -> Unit = {},
    selectedTool: String = "BIG_EATER_TOOL",
    onToolChange: (String) -> Unit = {},
    toolCount: String = "1",
    onToolCountChange: (String) -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.displayName,
                    fontSize = 18.sp,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "点击立即运行",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
            
            if (hasSettings) {
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Run",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp).clickable { onClick() }
            )
        }

        AnimatedVisibility(visible = hasSettings && expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
            ) {
                if (task == FarmSubTask.FOREST_WHACK_MOLE) {
                    Text("运行模式选择:", style = MaterialTheme.typography.labelMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = whackMoleMode == 1, onClick = { onModeChange(1) })
                        Text("兼容", modifier = Modifier.clickable { onModeChange(1) })
                        Spacer(Modifier.width(16.dp))
                        RadioButton(selected = whackMoleMode == 2, onClick = { onModeChange(2) })
                        Text("激进", modifier = Modifier.clickable { onModeChange(2) })
                    }
                    
                    Spacer(Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = whackMoleGames,
                        onValueChange = onGamesChange,
                        label = { Text("执行局数") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                } else if (task == FarmSubTask.FARM_SPECIAL_FOOD) {
                    OutlinedTextField(
                        value = specialFoodCount,
                        onValueChange = onSpecialFoodCountChange,
                        label = { Text("使用总次数 (必须大于0)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                } else if (task == FarmSubTask.FARM_USE_TOOL) {
                    val tools = toolDisplayNameMap.keys.toList()
                    var toolExpanded by remember { mutableStateOf(false) }
                    
                    ExposedDropdownMenuBox(
                        expanded = toolExpanded,
                        onExpandedChange = { toolExpanded = !toolExpanded }
                    ) {
                        OutlinedTextField(
                            value = toolDisplayNameMap[selectedTool] ?: selectedTool,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("选择道具") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = toolExpanded) },
                            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = toolExpanded,
                            onDismissRequest = { toolExpanded = false }
                        ) {
                            tools.forEach { tool ->
                                DropdownMenuItem(
                                    text = { Text(toolDisplayNameMap[tool] ?: tool) },
                                    onClick = {
                                        onToolChange(tool)
                                        toolExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    
                    if (selectedTool == "NEWEGGTOOL") {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = toolCount,
                            onValueChange = onToolCountChange,
                            label = { Text("使用数量") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }
            }
        }
    }
}
