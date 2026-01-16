package fansirsqi.xposed.sesame.ui.viewmodel

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import fansirsqi.xposed.sesame.SesameApplication.Companion.PREFERENCES_KEY
import fansirsqi.xposed.sesame.entity.RpcDebugItem
import fansirsqi.xposed.sesame.ui.LogViewerActivity
import fansirsqi.xposed.sesame.util.Files
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.ToastUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// 弹窗状态
sealed class RpcDialogState {
    data object None : RpcDialogState()
    data class Edit(val item: RpcDebugItem?, val initialJson: String) : RpcDialogState() // item为null表示新增
    data class DeleteConfirm(val item: RpcDebugItem) : RpcDialogState()
    data class RestoreConfirm(val items: List<RpcDebugItem>) : RpcDialogState()
}

class RpcDebugViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE)
    private val objectMapper = ObjectMapper()

    // UI State
    private val _items = MutableStateFlow<List<RpcDebugItem>>(emptyList())
    val items = _items.asStateFlow()

    private val _dialogState = MutableStateFlow<RpcDialogState>(RpcDialogState.None)
    val dialogState = _dialogState.asStateFlow()

    init {
        loadItems()
        if (_items.value.isEmpty() && !prefs.contains("rpc_debug_items")) {
            loadDefaultItems()
        }
    }

    // --- 加载与保存 ---

    private fun loadItems() {
        try {
            val jsonString = prefs.getString("rpc_debug_items", null)
            if (jsonString != null) {
                val list = objectMapper.readValue(jsonString, object : TypeReference<List<RpcDebugItem>>() {})
                _items.value = list
            }
        } catch (e: Exception) {
            Log.e("RpcDebug", "Load failed", e)
        }
    }

    private fun saveItems() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jsonString = objectMapper.writeValueAsString(_items.value)
                prefs.edit { putString("rpc_debug_items", jsonString) }
            } catch (e: Exception) {
                Log.e("RpcDebug", "Save failed", e)
            }
        }
    }

    // --- 业务操作 ---

    fun showAddDialog(context: Context) {
        // 自动读取剪贴板
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        val initialJson = prepareJsonInput(clipText)
        _dialogState.value = RpcDialogState.Edit(null, initialJson)
    }

    fun showEditDialog(item: RpcDebugItem) {
        val json = try {
            val map = mapOf("Name" to item.name, "methodName" to item.method, "requestData" to item.requestData)
            objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(map)
        } catch (_: Exception) {
            "{}"
        }
        _dialogState.value = RpcDialogState.Edit(item, json)
    }

    fun showDeleteDialog(item: RpcDebugItem) {
        _dialogState.value = RpcDialogState.DeleteConfirm(item)
    }

    fun dismissDialog() {
        _dialogState.value = RpcDialogState.None
    }

    fun saveItem(name: String, jsonText: String, editingItem: RpcDebugItem?) {
        try {
            val (parsedName, method, requestData) = parseJsonFields(jsonText)
            val finalName = name.ifEmpty { parsedName }

            if (method.isEmpty()) {
                ToastUtil.makeText("methodName 不能为空", Toast.LENGTH_SHORT).show()
                return
            }

            val currentList = _items.value.toMutableList()

            if (editingItem != null) {
                // 编辑：找到原对象位置并替换 (因为 RpcDebugItem 可能是可变的，但在 Compose 中最好由不可变列表驱动)
                // 这里假设 RpcDebugItem 是可变的，为了 Compose 更新，我们需要复制列表
                val index = currentList.indexOf(editingItem)
                if (index != -1) {
                    // 更新对象属性
                    editingItem.name = finalName
                    editingItem.method = method
                    editingItem.requestData = requestData
                    // 触发 Flow 更新
                    _items.value = ArrayList(currentList)
                }
            } else {
                // 新增
                val newItem = RpcDebugItem(name = finalName, method = method, requestData = requestData)
                currentList.add(newItem)
                _items.value = currentList
            }

            saveItems()
            dismissDialog()
            ToastUtil.makeText("保存成功", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            ToastUtil.makeText("JSON 错误: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun deleteItem(item: RpcDebugItem) {
        val list = _items.value.toMutableList()
        list.remove(item)
        _items.value = list
        saveItems()
        dismissDialog()
    }

    fun runRpcItem(item: RpcDebugItem, activityContext: Context) {
        viewModelScope.launch {
            try {
                val logFile = Files.getDebugLogFile()
                Files.clearFile(logFile)
                val intent = Intent("com.eg.android.AlipayGphone.sesame.rpctest").apply {
                    putExtra("method", item.method)
                    putExtra("data", item.getRequestDataString())
                    putExtra("type", "Rpc")
                }
                activityContext.sendBroadcast(intent)
                ToastUtil.makeText("已发送: ${item.getDisplayName()}", Toast.LENGTH_SHORT).show()
                // 轮询等待日志写入（Logback 是异步写入的，需要等待）
                var waitCount = 0
                val maxWait = 30 // 最多等待 3 秒（30 * 100ms）
                while (waitCount < maxWait) {
                    delay(100)
                    if (logFile.exists() && logFile.length() > 0) {
                        // 日志文件有内容了，再等待一小段时间确保写入完成
                        delay(200)
                        break
                    }
                    waitCount++
                }
                // 跳转日志
                try {
                    val logIntent = Intent(activityContext, LogViewerActivity::class.java).apply {
                        data = logFile.toUri()
                    }
                    activityContext.startActivity(logIntent)
                } catch (_: Exception) {
                    ToastUtil.makeText("无法打开日志", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                ToastUtil.makeText("执行失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // --- 工具功能 ---

    fun backupToClipboard(context: Context) {
        if (_items.value.isEmpty()) return
        try {
            val json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(_items.value)
            copyToClipboard("RPC Backup", json, context)
            ToastUtil.makeText("已备份到剪贴板", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            ToastUtil.makeText("备份失败", Toast.LENGTH_SHORT).show()
        }
    }

    fun tryRestoreFromClipboard(context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        if (!text.trim().startsWith("[")) {
            ToastUtil.makeText(context, "剪贴板不是数组格式", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val list = objectMapper.readValue(text, object : TypeReference<List<RpcDebugItem>>() {})
            _dialogState.value = RpcDialogState.RestoreConfirm(list)
        } catch (_: Exception) {
            ToastUtil.makeText(context, "解析失败", Toast.LENGTH_SHORT).show()
        }
    }

    fun confirmRestore(newItems: List<RpcDebugItem>) {
        // 补全 ID
        newItems.forEach { if (it.id.isEmpty()) it.id = System.currentTimeMillis().toString() }
        _items.value = newItems
        saveItems()
        dismissDialog()
        ToastUtil.makeText("恢复成功", Toast.LENGTH_SHORT).show()
    }

    fun loadDefaultItems() {
        val defaultList = listOf(
            RpcDebugItem(
                name = "雇佣黄金鸡",
                method = "com.alipay.antfarm.hireAnimal",
                requestData = listOf(mapOf("hireActionType" to "HIRE_IN_SELF_FARM", "sceneCode" to "ANTFARM")) // 简化示例
            ),
            RpcDebugItem(
                name = "雇佣黄金鸡",
                method = "com.alipay.antfarm.hireAnimal",
                requestData = listOf(mapOf("hireActionType" to "HIRE_IN_SELF_FARM", "sceneCode" to "ANTFARM")) // 简化示例
            )
        )
        // 简单合并逻辑：略
        _items.value = defaultList
        saveItems()
    }

    fun shareItem(item: RpcDebugItem, context: Context) {
        try {
            val map = mapOf("Name" to item.name, "methodName" to item.method, "requestData" to item.requestData)
            val json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(map)
            copyToClipboard("RPC Item", json, context)
            ToastUtil.makeText("已复制", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
    }

    private fun copyToClipboard(label: String, text: String, context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
    }

    // 复用原有的 JSON 解析逻辑 (略微精简)
    private fun prepareJsonInput(text: String): String {
        return if (text.contains("{")) text else """{ "methodName": "", "requestData": [{}] }"""
    }

    private fun parseJsonFields(json: String): Triple<String, String, Any?> {
        val map = objectMapper.readValue(json, Map::class.java)
        return Triple(
            (map["Name"] ?: map["name"])?.toString() ?: "",
            (map["methodName"] ?: map["method"])?.toString() ?: "",
            map["requestData"] ?: map["RequestData"],
        )
    }

    /**
     * 尝试格式化 JSON 字符串
     * @return 格式化后的 JSON，如果解析失败返回 null
     */
    fun tryFormatJson(jsonStr: String): String? {
        if (jsonStr.isBlank()) return null
        return try {
            // 1. 先解析成通用对象 (Map 或 List)
            val obj = objectMapper.readValue(jsonStr, Any::class.java)
            // 2. 再用 PrettyPrinter 输出
            objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj)
        } catch (e: Exception) {
            // 解析失败（格式错误），返回 null 让 UI 提示
            null
        }
    }
}