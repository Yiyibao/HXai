package me.rerere.rikkahub.flutter

import android.content.Context
import android.content.Intent
import android.os.Bundle
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import me.rerere.ai.provider.ProviderManager
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.feature.material.MaterialFiles
import me.rerere.rikkahub.feature.memory.LearningMemoryStore
import me.rerere.rikkahub.flutter.generated.StudyHostApi
import me.rerere.rikkahub.service.ChatService
import org.koin.core.context.GlobalContext

class StudyFlutterActivity : FlutterActivity() {

    private var fileSelection: kotlinx.coroutines.CompletableDeferred<List<android.net.Uri>>? = null
    private suspend fun pickFiles(): List<android.net.Uri> {
        check(fileSelection == null) { "文件选择器已打开" }
        val result = kotlinx.coroutines.CompletableDeferred<List<android.net.Uri>>()
        fileSelection = result
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }, 7101)
            return result.await()
        } finally { fileSelection = null }
    }
    private var saveSelection: kotlinx.coroutines.CompletableDeferred<android.net.Uri?>? = null
    private suspend fun saveFile(name: String, mime: String): android.net.Uri? {
        check(saveSelection == null) { "另存选择器已打开" }
        val result = kotlinx.coroutines.CompletableDeferred<android.net.Uri?>()
        saveSelection = result
        try {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = mime; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_TITLE, name)
            }, 7102)
            return result.await()
        } finally { saveSelection = null }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7102) saveSelection?.complete(if (resultCode == RESULT_OK) data?.data else null)
        if (requestCode == 7101) {
            val uris = if (resultCode != RESULT_OK) emptyList() else data?.clipData?.let { clip ->
                (0 until clip.itemCount).map { clip.getItemAt(it).uri }
            } ?: listOfNotNull(data?.data)
            fileSelection?.complete(uris)
        }
    }
    private var facade: StudyHostFacade? = null
    private var eventChannel: EventChannel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        if (me.rerere.rikkahub.utils.CrashHandler.hasCrashed(this)) {
            startActivity(Intent(this, me.rerere.rikkahub.ui.activity.SafeModeActivity::class.java))
            finish()
            return
        }
        super.onCreate(savedInstanceState)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        val appearance = getSharedPreferences("study_appearance", Context.MODE_PRIVATE)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "me.rerere.rikkahub/appearance")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "load" -> result.success(mapOf(
                        "theme" to appearance.getString("theme", "light"),
                        "accentColor" to appearance.getString("accentColor", "pink"),
                        "fontScale" to appearance.getFloat("fontScale", 1f).toDouble(),
                        "systemFont" to appearance.getBoolean("systemFont", true),
                        "reduceMotion" to appearance.getBoolean("reduceMotion", false),
                        "autoFollow" to appearance.getBoolean("autoFollow", true),
                    ))
                    "save" -> {
                        val args = call.arguments as? Map<*, *>
                        val editor = appearance.edit()
                        (args?.get("theme") as? String)?.let { editor.putString("theme", it) }
                        (args?.get("accentColor") as? String)?.let { editor.putString("accentColor", it) }
                        (args?.get("fontScale") as? Number)?.let { editor.putFloat("fontScale", it.toFloat()) }
                        (args?.get("systemFont") as? Boolean)?.let { editor.putBoolean("systemFont", it) }
                        (args?.get("reduceMotion") as? Boolean)?.let { editor.putBoolean("reduceMotion", it) }
                        (args?.get("autoFollow") as? Boolean)?.let { editor.putBoolean("autoFollow", it) }
                        editor.apply()
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "me.rerere.rikkahub/external_links")
            .setMethodCallHandler { call, result ->
                if (call.method != "open") { result.notImplemented(); return@setMethodCallHandler }
                try {
                    val uri = android.net.Uri.parse(call.arguments as? String ?: "")
                    require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo.isNullOrBlank())
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    result.success(null)
                } catch (e: Exception) { result.error("OPEN_FAILED", "无法打开来源链接", null) }
            }

        val koin = GlobalContext.get()
        val settingsStore: SettingsStore = koin.get()
        val conversationRepo: ConversationRepository = koin.get()
        val chatService: ChatService = koin.get()
        val learningMemories: LearningMemoryStore = koin.get()
        val materialFiles: MaterialFiles = koin.get()
        val providerManager: ProviderManager = koin.get()

        val hostFacade = StudyHostFacade(
            context = applicationContext,
            settingsStore = settingsStore,
            conversationRepo = conversationRepo,
            chatService = chatService,
            learningMemories = learningMemories,
            materialFiles = materialFiles,
            providerManager = providerManager,
            pickFiles = ::pickFiles,
            saveFile = ::saveFile,
        )
        this.facade = hostFacade
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "me.rerere.rikkahub/frontend_features")
            .setMethodCallHandler { call, result ->
                hostFacade.handleFrontendFeature(call.method, call.arguments as? Map<*, *> ?: emptyMap<String, Any>(), result)
            }

        StudyHostApi.setUp(flutterEngine.dartExecutor.binaryMessenger, hostFacade)

        val channel = EventChannel(flutterEngine.dartExecutor.binaryMessenger, "me.rerere.rikkahub/study_events")
        channel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                hostFacade.eventSink = events
            }

            override fun onCancel(arguments: Any?) {
                hostFacade.eventSink = null
            }
        })
        this.eventChannel = channel
    }

    override fun onDestroy() {
        fileSelection?.cancel()
        saveSelection?.cancel()
        facade?.close()
        super.onDestroy()
    }

    companion object {
        const val ENGINE_ID = "study_flutter_engine"

        fun start(context: Context) {
            val intent = Intent(context, StudyFlutterActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
