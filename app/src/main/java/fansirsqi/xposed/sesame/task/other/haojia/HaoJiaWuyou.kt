package fansirsqi.xposed.sesame.task.other.haojia

import fansirsqi.xposed.sesame.util.GlobalThreadPools
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.ResChecker
import fansirsqi.xposed.sesame.util.TaskBlacklist // 引入黑名单工具
import fansirsqi.xposed.sesame.util.TimeUtil
import org.json.JSONObject

object HaoJiaWuyou {
    private const val TAG = "好家无忧卡"

    fun start() {
        Log.record(TAG, "开始执行")
        try {
            doSignIn()
            doTasks()
        } catch (e: Exception) {
            Log.printStackTrace(TAG, e)
        }
        Log.record(TAG, "执行结束")
    }

    /**
     * 处理签到
     */
    private fun doSignIn() {
        try {
            val resp = HaoJiaRpcCall.querySignIn()
            if (!ResChecker.checkRes(TAG, resp)) return

            val jo = JSONObject(resp)
            val component = jo.optJSONObject("components")
                ?.optJSONObject("independent_component_sign_in_00966139_independent_component_sign_in_recall")

            if (component == null || !component.optBoolean("isSuccess")) {
                Log.record(TAG, "签到查询失败")
                return
            }

            val content = component.optJSONObject("content") ?: return
            val orderInfoList = content.optJSONArray("playSignInOrderInfoList") ?: return

            if (orderInfoList.length() > 0) {
                val orderInfo = orderInfoList.getJSONObject(0)
                val templateInfo = orderInfo.optJSONObject("playSignInTemplateInfo") ?: return
                val signCode = templateInfo.optString("code")

                // 检查今日是否已签到
                val todayStr = TimeUtil.getDateStrNoSplite() // 格式 yyyyMMdd
                val recordList = orderInfo.optJSONArray("signInRecordInfoList")
                var signed = false
                if (recordList != null) {
                    for (i in 0 until recordList.length()) {
                        val record = recordList.getJSONObject(i)
                        if (record.optString("date") == todayStr) {
                            signed = true
                            break
                        }
                    }
                }

                if (signed) {
                    Log.record(TAG, "今日已签到")
                } else if (signCode.isNotEmpty()) {
                    Log.record(TAG, "开始签到...")
                    val signResp = HaoJiaRpcCall.doSignIn(signCode)
                    if (ResChecker.checkRes(TAG, signResp) && signResp.contains("\"success\":true")) {
                        Log.record(TAG, "签到成功")
                    } else {
                        Log.record(TAG, "签到失败: $signResp")
                    }
                }
            }

        } catch (e: Exception) {
            Log.printStackTrace(TAG, e)
        }
    }

    /**
     * 处理任务
     */
    private fun doTasks() {
        try {
            val resp = HaoJiaRpcCall.queryTaskList()
            if (!ResChecker.checkRes(TAG, resp)) return

            val jo = JSONObject(resp)
            val component = jo.optJSONObject("components")
                ?.optJSONObject("independent_component_task_reward_00793835_independent_component_task_reward_query")

            val content = component?.optJSONObject("content") ?: return
            val taskList = content.optJSONArray("playTaskOrderInfoList") ?: return

            if (taskList.length() == 0) {
                Log.record(TAG, "暂无任务")
                return
            }

            for (i in 0 until taskList.length()) {
                val task = taskList.getJSONObject(i)
                val displayInfo = task.optJSONObject("displayInfo")
                val taskName = displayInfo?.optString("activityName") ?: "未知任务"
                val taskCode = task.optString("code")
                val taskStatus = task.optString("taskStatus") // init:未完成, finish:已完成
                val browseTime = displayInfo?.optInt("browseTime", 0) ?: 0

                // 1. 黑名单检查
                if (TaskBlacklist.isTaskInBlacklist(taskName)) {
                    Log.record(TAG, "跳过黑名单任务: $taskName")
                    continue
                }

                if (taskStatus == "init" && taskCode.isNotEmpty()) {
                    // 2. 关键词过滤（可选，根据需求保留或移除）
                    if (taskName.contains("开通") || taskName.contains("办理") || taskName.contains("咨询")) {
                        // Log.record(TAG, "跳过任务(关键词过滤): $taskName")
                        continue
                    }

                    Log.record(TAG, "开始任务: $taskName")

                    // 模拟浏览
                    if (browseTime > 0) {
                        Log.record(TAG, "浏览任务: $taskName, 等待 ${browseTime}秒")
                        GlobalThreadPools.sleepCompat((browseTime * 1000).toLong())
                    } else {
                        GlobalThreadPools.sleepCompat(1000)
                    }

                    // 提交任务/领取奖励
                    val applyResp = HaoJiaRpcCall.applyTask(taskCode)
                    // 解析结果
                    val resJo = JSONObject(applyResp)
                    // 注意：这里的 ResChecker 只是基础检查，具体业务成功需要看 internal result
                    if (ResChecker.checkRes(TAG, applyResp)) {
                        val applyComp = resJo.optJSONObject("components")
                            ?.optJSONObject("independent_component_task_reward_00793835_independent_component_task_reward_apply")

                        if (applyComp != null && applyComp.optBoolean("isSuccess")) {
                            Log.record(TAG, "任务完成: $taskName")
                        } else {
                            // 获取具体的错误信息
                            val errorMsg = applyComp?.optString("resultView")
                                ?: resJo.optString("resultView", "未知错误")

                            Log.record(TAG, "任务失败: $taskName, 原因: $errorMsg")

                            // 3. 失败自动加入黑名单
                            TaskBlacklist.autoAddToBlacklist(taskName, taskName, errorMsg)
                        }
                    } else {
                        // RPC 层面失败
                        val errorMsg = resJo.optString("resultView", applyResp)
                        Log.record(TAG, "任务RPC失败: $taskName, 原因: $errorMsg")
                        TaskBlacklist.autoAddToBlacklist(taskName, taskName, errorMsg)
                    }
                }
            }

        } catch (e: Exception) {
            Log.printStackTrace(TAG, e)
        }
    }
}