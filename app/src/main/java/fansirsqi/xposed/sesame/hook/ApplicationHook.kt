package fansirsqi.xposed.sesame.hook

import android.annotation.SuppressLint
import android.app.Application
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import fansirsqi.xposed.sesame.BuildConfig
import fansirsqi.xposed.sesame.SesameApplication
import fansirsqi.xposed.sesame.data.Config
import fansirsqi.xposed.sesame.data.General
import fansirsqi.xposed.sesame.data.Status
import fansirsqi.xposed.sesame.data.Status.Companion.load
import fansirsqi.xposed.sesame.data.Status.Companion.save
import fansirsqi.xposed.sesame.entity.AlipayVersion
import fansirsqi.xposed.sesame.hook.Toast.show
import fansirsqi.xposed.sesame.hook.TokenHooker.start
import fansirsqi.xposed.sesame.hook.XposedEnv.processName
import fansirsqi.xposed.sesame.hook.internal.LocationHelper
import fansirsqi.xposed.sesame.hook.internal.SecurityBodyHelper
import fansirsqi.xposed.sesame.hook.keepalive.SmartSchedulerManager
import fansirsqi.xposed.sesame.hook.keepalive.SmartSchedulerManager.cleanup
import fansirsqi.xposed.sesame.hook.keepalive.SmartSchedulerManager.schedule
import fansirsqi.xposed.sesame.hook.rpc.bridge.NewRpcBridge
import fansirsqi.xposed.sesame.hook.rpc.bridge.OldRpcBridge
import fansirsqi.xposed.sesame.hook.rpc.bridge.RpcBridge
import fansirsqi.xposed.sesame.hook.rpc.bridge.RpcVersion
import fansirsqi.xposed.sesame.hook.rpc.debug.DebugRpc
import fansirsqi.xposed.sesame.hook.rpc.intervallimit.RpcIntervalLimit.clearIntervalLimit
import fansirsqi.xposed.sesame.hook.server.ModuleHttpServer
import fansirsqi.xposed.sesame.hook.server.ModuleHttpServerManager.startIfNeeded
import fansirsqi.xposed.sesame.hook.simple.SimplePageManager.addHandler
import fansirsqi.xposed.sesame.hook.simple.SimplePageManager.enableWindowMonitoring
import fansirsqi.xposed.sesame.model.BaseModel.Companion.batteryPerm
import fansirsqi.xposed.sesame.model.BaseModel.Companion.checkInterval
import fansirsqi.xposed.sesame.model.BaseModel.Companion.debugMode
import fansirsqi.xposed.sesame.model.BaseModel.Companion.destroyData
import fansirsqi.xposed.sesame.model.BaseModel.Companion.execAtTimeList
import fansirsqi.xposed.sesame.model.BaseModel.Companion.newRpc
import fansirsqi.xposed.sesame.model.BaseModel.Companion.sendHookData
import fansirsqi.xposed.sesame.model.BaseModel.Companion.sendHookDataUrl
import fansirsqi.xposed.sesame.model.BaseModel.Companion.wakenAtTimeList
import fansirsqi.xposed.sesame.model.Model
import fansirsqi.xposed.sesame.task.MainTask
import fansirsqi.xposed.sesame.task.MainTask.Companion.newInstance
import fansirsqi.xposed.sesame.task.ModelTask.Companion.stopAllTask
import fansirsqi.xposed.sesame.task.TaskRunnerAdapter
import fansirsqi.xposed.sesame.util.*
import fansirsqi.xposed.sesame.util.AssetUtil.checkerDestFile
import fansirsqi.xposed.sesame.util.AssetUtil.copyStorageSoFileToPrivateDir
import fansirsqi.xposed.sesame.util.AssetUtil.dexkitDestFile
import fansirsqi.xposed.sesame.util.DataStore.init
import fansirsqi.xposed.sesame.util.Detector.loadLibrary
import fansirsqi.xposed.sesame.util.GlobalThreadPools.execute
import fansirsqi.xposed.sesame.util.GlobalThreadPools.shutdownAndRestart
import fansirsqi.xposed.sesame.util.Log.debug
import fansirsqi.xposed.sesame.util.Log.error
import fansirsqi.xposed.sesame.util.Log.printStackTrace
import fansirsqi.xposed.sesame.util.Log.record
import fansirsqi.xposed.sesame.util.Notify.stop
import fansirsqi.xposed.sesame.util.Notify.updateStatusText
import fansirsqi.xposed.sesame.util.PermissionUtil.checkBatteryPermissions
import fansirsqi.xposed.sesame.util.StatusManager.updateStatus
import fansirsqi.xposed.sesame.util.maps.UserMap
import fansirsqi.xposed.sesame.util.maps.UserMap.currentUid
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import lombok.Getter
import lombok.Setter
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.AutoCloseable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.Volatile

class ApplicationHook {
    var xposedInterface: XposedInterface? = null

    @Setter
    private val httpServer: ModuleHttpServer? = null

    private object BroadcastActions {
        const val RESTART: String = "com.eg.android.AlipayGphone.sesame.restart"
        const val RE_LOGIN: String = "com.eg.android.AlipayGphone.sesame.reLogin"
        const val STATUS: String = "com.eg.android.AlipayGphone.sesame.status"
        const val RPC_TEST: String = "com.eg.android.AlipayGphone.sesame.rpctest"
    }

    private object AlipayClasses {
        const val APPLICATION: String = "com.alipay.mobile.framework.AlipayApplication"
        const val SOCIAL_SDK: String = "com.alipay.mobile.personalbase.service.SocialSdkContactService"
        const val LAUNCHER_ACTIVITY: String = "com.alipay.mobile.quinox.LauncherActivity"
        const val SERVICE: String = "android.app.Service"
        const val LOADED_APK: String = "android.app.LoadedApk"
    }

    private class TaskLock : AutoCloseable {
        private val acquired: Boolean

        init {
            synchronized(taskLock) {
                if (isTaskRunning) {
                    acquired = false
                    throw IllegalStateException("任务已在运行中")
                }
                isTaskRunning = true
                acquired = true
            }
        }

        override fun close() {
            if (acquired) {
                synchronized(taskLock) {
                    isTaskRunning = false
                }
            }
        }
    }

    // --- 入口方法 ---
    fun loadPackage(lpparam: PackageLoadedParam) {
        if (General.PACKAGE_NAME != lpparam.getPackageName()) return
        handleHookLogic(
            lpparam.getClassLoader(),
            lpparam.getPackageName(),
            lpparam.getApplicationInfo().sourceDir,
            lpparam
        )
    }

    fun loadPackageCompat(lpparam: LoadPackageParam) {
        if (General.PACKAGE_NAME != lpparam.packageName) return
        val apkPath: String = (if (lpparam.appInfo != null) lpparam.appInfo.sourceDir else null)!!
        handleHookLogic(lpparam.classLoader, lpparam.packageName, apkPath, lpparam)
    }

    @SuppressLint("PrivateApi")
    private fun handleHookLogic(loader: ClassLoader?, packageName: String, apkPath: String, rawParam: Any?) {
        classLoader = loader
        // 1. 初始化配置读取
        val prefs = XSharedPreferences(General.MODULE_PACKAGE_NAME, SesameApplication.PREFERENCES_KEY)
        prefs.makeWorldReadable()

        // 2. 进程检查
        resolveProcessName(rawParam)
        if (!shouldHookProcess()) return

        init(Files.CONFIG_DIR)
        if (isHooked) return
        isHooked = true

        // 3. 基础环境 Hook
        ModuleStatus.detectFramework(classLoader!!)
        updateStatus(ModuleStatus.detectFramework(classLoader!!), packageName)
        VersionHook.installHook(classLoader)
        initReflection(classLoader!!)

        // 4. 功能模块 Hook
        try {
            CaptchaHook.setupHook(classLoader!!)
        } catch (t: Throwable) {
            printStackTrace(TAG, "验证码Hook初始化失败", t)
        }

        // 5. 核心生命周期 Hook
        hookApplicationAttach(packageName)
        hookLauncherResume()
        hookServiceLifecycle(apkPath)

        HookUtil.hookOtherService(classLoader!!)
    }

    private fun resolveProcessName(rawParam: Any?) {
        if (rawParam is LoadPackageParam) {
            finalProcessName = rawParam.processName
        } else if (rawParam is PackageLoadedParam) {
            finalProcessName = processName
        }
    }

    private fun shouldHookProcess(): Boolean {
        val isMainProcess = General.PACKAGE_NAME == finalProcessName
        if (!isMainProcess) {
            record(TAG, "跳过辅助进程: " + finalProcessName)
            return false
        }
        return true
    }

    private fun initReflection(loader: ClassLoader) {
        try {
            XposedHelpers.findClass(AlipayClasses.APPLICATION, loader)
            XposedHelpers.findClass(AlipayClasses.SOCIAL_SDK, loader)
        } catch (t: Throwable) {
            // ignore
        }

        try {
            @SuppressLint("PrivateApi") val loadedApkClass = loader.loadClass(AlipayClasses.LOADED_APK)
            deoptimizeClass(loadedApkClass)
        } catch (t: Throwable) {
            // ignore
        }
    }

    private fun hookApplicationAttach(packageName: String?) {
        try {
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "attach",
                Context::class.java,
                object : XC_MethodHook() {
                    @Throws(Throwable::class)
                    override fun afterHookedMethod(param: MethodHookParam) {
                        appContext = param.args[0] as Context?
                        mainHandler = Handler(Looper.getMainLooper())
                        Log.init(appContext!!)
                        ensureScheduler()

                        SecurityBodyHelper.init(classLoader!!)
                        LocationHelper.init(classLoader!!)

                        initVersionInfo(packageName)
                        loadLibs()
                        // 特殊版本处理
                        if (VersionHook.hasVersion() && alipayVersion.compareTo(AlipayVersion("10.7.26.8100")) == 0) {
                            HookUtil.fuckAccounLimit(classLoader!!)
                        }

                        initSimplePageManager()
                    }
                })
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "Hook attach failed", e)
        }
    }

    private fun hookLauncherResume() {
        try {
            XposedHelpers.findAndHookMethod(
                AlipayClasses.LAUNCHER_ACTIVITY,
                classLoader,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam?) {
                        val targetUid = HookUtil.getUserId(classLoader!!)
                        if (targetUid == null) {
                            show("用户未登录")
                            return
                        }
                        if (!init) {
                            if (initHandler()) init = true
                            return
                        }
                        val currentUid = currentUid
                        if (targetUid != currentUid) {
                            if (currentUid != null) {
                                initHandler()
                                lastExecTime = 0
                                show("用户已切换")
                                return
                            }
                            HookUtil.hookUser(classLoader!!)
                        }
                    }
                })
        } catch (t: Throwable) {
            printStackTrace(TAG, "Hook Launcher failed", t)
        }
    }

    private fun hookServiceLifecycle(apkPath: String) {
        try {
            XposedHelpers.findAndHookMethod(AlipayClasses.SERVICE, classLoader, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val appService = param.thisObject as Service
                    if (General.CURRENT_USING_SERVICE != appService.javaClass.getCanonicalName()) {
                        return
                    }

                    service = appService
                    appContext = appService.getApplicationContext()
                    ensureScheduler()

                    if (Detector.isLegitimateEnvironment(appContext!!)) {
                        Detector.dangerous(appContext!!)
                        return
                    }

                    DexKitBridge.create(apkPath).use { ignored ->
                        record(TAG, "Hook DexKit successfully")
                    }
                    // 初始化主任务
                    mainTask = newInstance("MAIN_TASK", Runnable { runMainTaskLogic() })

                    dayCalendar = Calendar.getInstance()
                    if (initHandler()) {
                        init = true
                    }
                }
            })

            XposedHelpers.findAndHookMethod(AlipayClasses.SERVICE, classLoader, "onDestroy", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val s = param.thisObject as Service
                    if (General.CURRENT_USING_SERVICE == s.javaClass.getCanonicalName()) {
                        updateStatusText("支付宝前台服务被销毁")
                        destroyHandler()
                        restartByBroadcast()
                    }
                }
            })
        } catch (t: Throwable) {
            printStackTrace(TAG, "Hook Service failed", t)
        }
    }

    private fun initVersionInfo(packageName: String?) {
        if (VersionHook.hasVersion()) {
            alipayVersion = VersionHook.getCapturedVersion() ?: AlipayVersion("")
            record(TAG, "📦 支付宝版本(Hook): " + alipayVersion.toString())
        } else {
            try {
                val pInfo: PackageInfo = appContext!!.getPackageManager().getPackageInfo(packageName!!, 0)
                alipayVersion = AlipayVersion(Objects.requireNonNullElse<String?>(pInfo.versionName, ""))
            } catch (e: Exception) {
                alipayVersion = AlipayVersion("")
            }
        }
    }

    private fun loadLibs() {
        loadNativeLibs(appContext!!, checkerDestFile)
        loadNativeLibs(appContext!!, dexkitDestFile)
    }

    // 滑块验证hook注册
    private fun initSimplePageManager() {
        if (shouldEnableSimplePageManager()) {
            enableWindowMonitoring(classLoader)
            addHandler("com.alipay.mobile.nebulax.xriver.activity.XRiverActivity", Captcha1Handler())
            addHandler("com.eg.android.AlipayGphone.AlipayLogin", Captcha2Handler())
        }
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private fun loadNativeLibs(context: Context, soFile: File) {
        try {
            val finalSoFile = copyStorageSoFileToPrivateDir(context, soFile)
            if (finalSoFile != null) {
                System.load(finalSoFile.getAbsolutePath())
            } else {
                loadLibrary(soFile.getName().replace(".so", "").replace("lib", ""))
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "载入so库失败: " + soFile.getName(), e)
        }
    }

    // --- 广播接收器 ---
    internal class AlipayBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            val action = intent.getAction()
            if (action == null) return

            if (finalProcessName != null && finalProcessName!!.endsWith(":widgetProvider")) {
                return  // 忽略小组件进程
            }

            when (action) {
                BroadcastActions.RESTART -> execute(Runnable { initHandler() })
                BroadcastActions.RE_LOGIN -> reOpenApp()
                BroadcastActions.RPC_TEST -> handleRpcTest(intent)
            }
        }

        private fun handleRpcTest(intent: Intent) {
            execute(Runnable {
                record(TAG, "RPC测试: " + intent)
                try {
                    val rpc = DebugRpc()
                    rpc.start(
                        intent.getStringExtra("method"),
                        intent.getStringExtra("data"),
                        intent.getStringExtra("type")
                    )
                } catch (t: Throwable) { /* ignore */
                }
            })
        }
    }

    companion object {
        const val TAG: String = "ApplicationHook" // 简化TAG
        var finalProcessName: String? = ""

        // 广播接收器实例，用于注销
        private var mBroadcastReceiver: AlipayBroadcastReceiver? = null

        @JvmField
        var classLoader: ClassLoader? = null

        private val microApplicationContextObject: Any? = null

        @JvmField
        @get:JvmStatic
        @SuppressLint("StaticFieldLeak")
        @Volatile
        var appContext: Context? = null

        // 任务锁
        private val taskLock = Any()

        @Volatile
        private var isTaskRunning = false

        @JvmStatic
        var alipayVersion: AlipayVersion = AlipayVersion("")

        @get:JvmStatic
        @Volatile
        var isHooked: Boolean = false
            private set

        /**
         * 检查支付宝版本是否需要启用SimplePageManager功能
         * @return true表示版本低于等于10.6.58.99999，需要启用；false表示不需要
         */
        @JvmStatic
        fun shouldEnableSimplePageManager(): Boolean {
            if (!VersionHook.hasVersion() || alipayVersion.toString().isEmpty()) {
                debug(TAG, "无法获取支付宝版本信息，跳过 SimplePageManager 初始化")
                return false
            }
            // 例如：10.6.58.8000 <= 10.6.58.99999，但 10.6.59 > 10.6.58.99999
            if (alipayVersion.compareTo(AlipayVersion("10.6.58.99999")) <= 0) {
                return true
            } else {
                debug(
                    TAG,
                    "支付宝版本 " + alipayVersion.toString() + " 高于 10.6.58，跳过 SimplePageManager 初始化"
                )
                return false
            }
        }

        @Volatile
        private var init = false

        @Volatile
        var dayCalendar: Calendar?

        @JvmField
        @Volatile
        var offline: Boolean = false

        @JvmStatic
        fun setOffline(value: Boolean) {
            offline = value
        }

        @JvmStatic
        val reLoginCount: AtomicInteger = AtomicInteger(0)

        @Volatile
        private var batteryPermissionChecked = false

        @SuppressLint("StaticFieldLeak")
        var service: Service? = null

        var mainHandler: Handler? = null

        var mainTask: MainTask? = null

        @Volatile
        var rpcBridge: RpcBridge? = null
        private val rpcBridgeLock = Any()

        private var rpcVersion: RpcVersion? = null

        @Volatile
        private var lastExecTime: Long = 0

        @Volatile
        var nextExecutionTime: Long = 0
        private const val MAX_INACTIVE_TIME: Long = 3600000 // 1小时

        // Deoptimize 方法缓存
        private val deoptimizeMethod: Method?

        init {
            dayCalendar = Calendar.getInstance()
            Companion.resetToMidnight(dayCalendar!!)
            var m: Method? = null
            try {
                m = XposedBridge::class.java.getDeclaredMethod("deoptimizeMethod", Member::class.java)
            } catch (t: Throwable) {
                // ignore
            }
            deoptimizeMethod = m
        }

        private fun runMainTaskLogic() {
            try {
                TaskLock().use { ignored ->
                    if (!init || !Config.isLoaded()) return
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastExecTime < 2000) {
                        record(TAG, "⚠️ 间隔过短，跳过")
                        schedule(checkInterval.value.toLong(), "间隔重试") {
                            execHandler()
                            Unit
                        }
                        return
                    }

                    val currentUid = currentUid
                    val targetUid = HookUtil.getUserId(classLoader!!)
                    if (targetUid == null || targetUid != currentUid) {
                        reOpenApp()
                        return
                    }

                    lastExecTime = currentTime
                    TaskRunnerAdapter().run()
                    scheduleNextExecutionInternal(lastExecTime)
                }
            } catch (e: IllegalStateException) {
                record(TAG, "⚠️ " + e.message)
            } catch (e: Exception) {
                Log.printStackTrace(TAG, e)
            }
        }

        // --- 辅助方法 ---
        private fun ensureScheduler() {
            if (appContext != null) {
                SmartSchedulerManager.initialize(appContext!!)
            }
        }

        @Throws(InvocationTargetException::class, IllegalAccessException::class)
        fun deoptimizeClass(c: Class<*>) {
            if (deoptimizeMethod == null) return
            for (m in c.getDeclaredMethods()) {
                if (m.getName() == "makeApplicationInner") {
                    deoptimizeMethod.invoke(null, m)
                }
            }
        }

        // --- 调度与执行 ---
        @JvmStatic
        fun scheduleNextExecution() {
            scheduleNextExecutionInternal(lastExecTime)
        }

        private fun scheduleNextExecutionInternal(lastTime: Long) {
            try {
                checkInactiveTime()
                val checkInterval = checkInterval.value
                val execAtTimeList = execAtTimeList.value

                if (execAtTimeList != null && execAtTimeList.contains("-1")) {
                    record(TAG, "定时执行未开启")
                    return
                }

                var delayMillis = checkInterval.toLong()
                var targetTime: Long = 0

                // ... (计算 delayMillis 逻辑保持不变) ...

                // 计算逻辑简化省略，保持原逻辑
                if (execAtTimeList != null) {
                    val lastCal = TimeUtil.getCalendarByTimeMillis(lastTime)
                    val nextCal = TimeUtil.getCalendarByTimeMillis(lastTime + checkInterval)
                    for (timeStr in execAtTimeList) {
                        val execCal = TimeUtil.getTodayCalendarByTimeStr(timeStr)
                        if (execCal != null && lastCal.compareTo(execCal) < 0 && nextCal.compareTo(execCal) > 0) {
                            record(TAG, "设置定时执行:" + timeStr)
                            targetTime = execCal.getTimeInMillis()
                            delayMillis = targetTime - lastTime
                            break
                        }
                    }
                }

                nextExecutionTime = if (targetTime > 0) targetTime else (lastTime + delayMillis)
                ensureScheduler()

                schedule(delayMillis, "轮询任务") {
                    execHandler()
                    Unit
                }
            } catch (e: Exception) {
                Log.printStackTrace(TAG, "scheduleNextExecution failed", e)
            }
        }

        // --- 初始化核心逻辑 ---
        @Synchronized
        private fun initHandler(): Boolean {
            try {
                if (init) destroyHandler()

                // 调试模式初始化
                if (BuildConfig.DEBUG) {
                    try {
                        startIfNeeded(8080, "ET3vB^#td87sQqKaY*eMUJXP", processName, General.PACKAGE_NAME)
                        Companion.registerBroadcastReceiver(appContext!!)
                    } catch (e: Throwable) { /* ignore */
                    }
                }

                ensureScheduler()
                Model.initAllModel()

                if (service == null) return false
                val userId = HookUtil.getUserId(classLoader!!)
                if (userId == null) {
                    show("用户未登录")
                    return false
                }

                HookUtil.hookUser(classLoader!!)
                record(TAG, "芝麻粒-TK 开始初始化...")

                Config.load(userId)
                if (!Config.isLoaded()) return false

                Notify.start(service!!)
                setWakenAtTimeAlarm()

                synchronized(rpcBridgeLock) {
                    rpcBridge = if (newRpc.value) NewRpcBridge() else OldRpcBridge()
                    rpcBridge!!.load()
                    rpcVersion = rpcBridge!!.getVersion()
                }

                if (newRpc.value && debugMode.value) {
                    HookUtil.hookRpcBridgeExtension(classLoader!!, sendHookData.value, sendHookDataUrl.value)
                    HookUtil.hookDefaultBridgeCallback(classLoader!!)
                }

                start(userId)
                checkBatteryPermission()

                Model.bootAllModel(classLoader)
                load(userId)
                updateDay()

                val successMsg = "Loaded SesameTk " + BuildConfig.VERSION_NAME + "✨"
                record(successMsg)
                show(successMsg)

                offline = false
                init = true
                execHandler()
                return true
            } catch (th: Throwable) {
                printStackTrace(TAG, "startHandler", th)
                return false
            }
        }

        private fun checkBatteryPermission() {
            if (!batteryPerm.value || batteryPermissionChecked) return

            val hasPermission = checkBatteryPermissions(appContext)
            batteryPermissionChecked = true
            if (!hasPermission) {
                record(TAG, "无后台运行权限，2秒后申请")
                mainHandler!!.postDelayed(Runnable {
                    if (!PermissionUtil.checkOrRequestBatteryPermissions(appContext!!)) {
                        show("请授予支付宝始终在后台运行权限")
                    }
                }, 2000)
            }
        }

        @Synchronized
        fun destroyHandler() {
            try {
                shutdownAndRestart()

                if (service != null) {
                    stopHandler()
                    destroyData()
                    Status.unload()
                    stop()
                    clearIntervalLimit()
                    Config.unload()
                    UserMap.unload()
                }

                cleanup()

                // 注销广播接收器
                unregisterBroadcastReceiver(appContext)

                synchronized(rpcBridgeLock) {
                    if (rpcBridge != null) {
                        rpcVersion = null
                        rpcBridge!!.unload()
                        rpcBridge = null
                    }
                    stopAllTask()
                }
            } catch (th: Throwable) {
                printStackTrace(TAG, "stopHandler err:", th)
            }
        }

        fun execHandler() {
            if (mainTask != null) mainTask!!.startTask(false)
        }

        private fun stopHandler() {
            if (mainTask != null) mainTask!!.stopTask()
            stopAllTask()
        }

        // --- 杂项方法 ---
        private fun checkInactiveTime() {
            if (lastExecTime == 0L) return
            val inactiveTime: Long = System.currentTimeMillis() - lastExecTime
            if (inactiveTime > MAX_INACTIVE_TIME) {
                record(TAG, "⚠️ 检测到长时间未执行(" + inactiveTime / 60000 + "m)，重新登录")
                reOpenApp()
            }
        }

        fun updateDay() {
            val now = Calendar.getInstance()
            if (dayCalendar == null || dayCalendar!!.get(Calendar.DAY_OF_MONTH) != now.get(Calendar.DAY_OF_MONTH)) {
                dayCalendar = now.clone() as Calendar
                Companion.resetToMidnight(dayCalendar!!)
                record(TAG, "日期更新")
                setWakenAtTimeAlarm()
            }
            try {
                save(now)
            } catch (ignored: Exception) {
            }
        }

        private fun resetToMidnight(calendar: Calendar) {
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
        }

        fun sendBroadcast(action: String?) {
            if (appContext != null) appContext!!.sendBroadcast(Intent(action))
        }

        fun sendBroadcastShell(API: String?, message: String?) {
            if (appContext == null) return
            val intent = Intent("fansirsqi.xposed.sesame.SHELL")
            intent.putExtra(API, message)
            appContext!!.sendBroadcast(intent, null)
        }

        @JvmStatic
        fun reLoginByBroadcast() {
            sendBroadcast(BroadcastActions.RE_LOGIN)
        }

        fun restartByBroadcast() {
            sendBroadcast(BroadcastActions.RESTART)
        }

        fun reOpenApp() {
            ensureScheduler()
            schedule(20000L, "重新登录") {
                try {
                    val intent = Intent(Intent.ACTION_VIEW)
                    intent.setClassName(General.PACKAGE_NAME, General.CURRENT_USING_ACTIVITY)
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    offline = true
                    if (appContext != null) appContext!!.startActivity(intent)
                } catch (e: Exception) {
                    error(TAG, "重启Activity失败: " + e.message)
                }
                Unit
            }
        }

        // --- 定时唤醒 ---
        private fun setWakenAtTimeAlarm() {
            if (appContext == null) return
            ensureScheduler()

            val wakenAtTimeList = wakenAtTimeList.value
            if (wakenAtTimeList != null && wakenAtTimeList.contains("-1")) return

            // 1. 每日0点
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.DAY_OF_MONTH, 1)
            resetToMidnight(calendar)
            val delayToMidnight = calendar.getTimeInMillis() - System.currentTimeMillis()

            if (delayToMidnight > 0) {
                schedule(delayToMidnight, "每日0点任务") {
                    record(TAG, "⏰ 0点任务触发")
                    updateDay()
                    execHandler()
                    setWakenAtTimeAlarm() // 递归设置明天
                    Unit
                }
            }

            // 2. 自定义时间
            if (wakenAtTimeList != null) {
                val now = Calendar.getInstance()
                for (timeStr in wakenAtTimeList) {
                    try {
                        val target = TimeUtil.getTodayCalendarByTimeStr(timeStr)
                        if (target != null && target.compareTo(now) > 0) {
                            val delay = target.getTimeInMillis() - System.currentTimeMillis()
                            schedule(delay, "自定义: " + timeStr) {
                                record(TAG, "⏰ 自定义触发: " + timeStr)
                                execHandler()
                                Unit
                            }
                        }
                    } catch (e: Exception) { /* ignore */
                    }
                }
            }
        }

        fun registerBroadcastReceiver(context: Context) {
            if (mBroadcastReceiver != null) return  // 防止重复注册

            try {
                mBroadcastReceiver = AlipayBroadcastReceiver()
                val filter = IntentFilter()
                filter.addAction(BroadcastActions.RESTART)
                filter.addAction(BroadcastActions.RE_LOGIN)
                filter.addAction(BroadcastActions.STATUS)
                filter.addAction(BroadcastActions.RPC_TEST)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(mBroadcastReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    ContextCompat.registerReceiver(
                        context,
                        mBroadcastReceiver,
                        filter,
                        ContextCompat.RECEIVER_NOT_EXPORTED
                    )
                }
                record(TAG, "BroadcastReceiver registered")
            } catch (th: Throwable) {
                mBroadcastReceiver = null
                printStackTrace(TAG, "Register Receiver failed", th)
            }
        }

        fun unregisterBroadcastReceiver(context: Context?) {
            if (mBroadcastReceiver == null || context == null) return
            try {
                context.unregisterReceiver(mBroadcastReceiver)
                record(TAG, "BroadcastReceiver unregistered")
            } catch (th: Throwable) {
                // ignore: receiver not registered
            } finally {
                mBroadcastReceiver = null
            }
        }
    }
}