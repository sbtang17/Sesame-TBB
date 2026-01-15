package fansirsqi.xposed.sesame.ui.screen

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import fansirsqi.xposed.sesame.entity.RpcDebugItem
import fansirsqi.xposed.sesame.ui.viewmodel.RpcDebugViewModel
import fansirsqi.xposed.sesame.ui.viewmodel.RpcDialogState
import fansirsqi.xposed.sesame.util.ToastUtil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RpcDebugScreen(
    onBack: () -> Unit,
    viewModel: RpcDebugViewModel = viewModel()
) {
    val items by viewModel.items.collectAsState()
    val dialogState by viewModel.dialogState.collectAsState()
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }


    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // 使用 Column 垂直排列主标题和副标题
                    Column {
                        Text(
                            text = "RPC 调试",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "⚠️ 敏感功能，请谨慎操作", // 警告文案
                            style = MaterialTheme.typography.labelMedium, // 使用较小的字号
                            color = MaterialTheme.colorScheme.error // 使用错误色(红色)示警，或者使用 outline 变体
                        )
                    }
                },
                navigationIcon = { /* IconButton(onClick = onBack) ... */ },
                actions = {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, "更多")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("备份数据") },
                            onClick = { viewModel.backupToClipboard(context); showMenu = false }
                        )
                        DropdownMenuItem(
                            text = { Text("恢复数据") },
                            onClick = { viewModel.tryRestoreFromClipboard(context); showMenu = false }
                        )
                        DropdownMenuItem(
                            text = { Text("加载默认") },
                            onClick = { viewModel.loadDefaultItems(); showMenu = false }
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.showAddDialog(context) }) {
                Icon(Icons.Default.Add, "添加")
            }
        }
    ) { padding ->
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("暂无数据，请点击右下角添加", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items) { item ->
                    RpcItemCard(
                        item = item,
                        onRun = { viewModel.runRpcItem(item, context) },
                        onEdit = { viewModel.showEditDialog(item) },
                        onDelete = { viewModel.showDeleteDialog(item) },
                        onCopy = { viewModel.shareItem(item,context) }
                    )
                }
            }
        }

        // 处理所有弹窗
        RpcDialogHandler(dialogState, viewModel)
    }
}

@Composable
fun RpcItemCard(
    item: RpcDebugItem,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onRun() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            // 标题行
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.getDisplayName(), // 假设 item 有这个方法
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                // 快捷运行按钮
                IconButton(onClick = onRun) {
                    Icon(Icons.Default.PlayArrow, "运行", tint = MaterialTheme.colorScheme.primary)
                }
            }

            // 方法名
            Text(
                text = item.method,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(8.dp))
            // 修正：使用 HorizontalDivider 替代 Divider 已弃用
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )

            // 操作栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onCopy) { Text("复制") }
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
fun RpcDialogHandler(state: RpcDialogState, viewModel: RpcDebugViewModel) {
    when (state) {
        is RpcDialogState.None -> {}

        is RpcDialogState.Edit -> {
            var name by remember { mutableStateOf(state.item?.name ?: "") }
            var json by remember { mutableStateOf(state.initialJson) }

            AlertDialog(
                onDismissRequest = { viewModel.dismissDialog() },
                title = { Text(if (state.item == null) "添加调试项" else "编辑调试项") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("名称 (可选)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = json,
                            onValueChange = { json = it },
                            label = { Text("JSON 数据") },
                            modifier = Modifier.fillMaxWidth().height(200.dp), // 高度大一点
                            textStyle = LocalTextStyle.current.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            ),
                            // 🔥 酷炫功能在这里：格式化按钮
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        val formatted = viewModel.tryFormatJson(json)
                                        if (formatted != null) {
                                            json = formatted
                                            ToastUtil.makeText( "✨ JSON 已格式化", Toast.LENGTH_SHORT).show()
                                        } else {
                                            ToastUtil.makeText( "格式错误，无法格式化", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                ) {
                                    // 使用 AutoFixHigh 图标，寓意“自动修复/美化”
                                    Icon(Icons.Default.AutoFixHigh, contentDescription = "格式化 JSON")
                                }
                            }
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = { viewModel.saveItem(name, json, state.item) }) {
                        Text("保存")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissDialog() }) { Text("取消") }
                }
            )
        }

        is RpcDialogState.DeleteConfirm -> {
            AlertDialog(
                onDismissRequest = { viewModel.dismissDialog() },
                title = { Text("确认删除") },
                text = { Text("确定要删除 \"${state.item.getDisplayName()}\" 吗？") },
                confirmButton = {
                    TextButton(onClick = { viewModel.deleteItem(state.item) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Text("删除")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissDialog() }) { Text("取消") }
                }
            )
        }

        is RpcDialogState.RestoreConfirm -> {
            AlertDialog(
                onDismissRequest = { viewModel.dismissDialog() },
                title = { Text("确认恢复") },
                text = { Text("将恢复 ${state.items.size} 项数据，当前列表将被覆盖。") },
                confirmButton = {
                    TextButton(onClick = { viewModel.confirmRestore(state.items) }) {
                        Text("恢复")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissDialog() }) { Text("取消") }
                }
            )
        }
    }
}