package me.rerere.rikkahub.feature.studychat.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import me.rerere.ai.provider.ProviderManager
import me.rerere.rikkahub.ui.pages.setting.components.ProviderConfigure
import me.rerere.rikkahub.feature.studychat.isValidStudyProvider
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * P12 提供商管理页面
 * 返回式子页面，读真实提供商配置，启停开关、模型管理入口、连接编辑入口
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyProviderPage() {
    val nav = LocalNavController.current
    val settingsStore = koinInject<SettingsStore>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var showAddDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ProviderSetting?>(null) }
    var mutationBusy by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    fun runProviderAction(action: suspend () -> Unit) {
        if (mutationBusy) return
        mutationBusy = true
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { snackbar.showSnackbar("配置保存失败，请重试") }
            finally { mutationBusy = false }
        }
    }


    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "服务提供商管理",
                        color = Color(0xFF29272B),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                navigationIcon = { BackButton() },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFFFFFFF))
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = Color(0xFFF8D5DE),
                contentColor = Color(0xFF925568),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("+", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = Color(0xFFFFFFFF)
    ) { padding ->
        if (settings.providers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("当前没有配置任何 AI 服务提供商", color = Color(0xFF777178))
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { showAddDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFF8D5DE),
                            contentColor = Color(0xFF925568)
                        )
                    ) {
                        Text("添加提供商")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(vertical = 14.dp)
            ) {
                items(settings.providers, key = { it.id.toString() }) { provider ->
                    Surface(
                        color = Color(0xFFFFFFFF),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color(0xFFEFECEF), RoundedCornerShape(16.dp))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = provider.name,
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                color = Color(0xFF29272B),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 16.sp
                                            )
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Surface(
                                            color = if (provider.enabled) Color(0xFFFFF1F5) else Color(0xFFF8F5F7),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = if (provider.enabled) "已启用" else "已禁用",
                                                color = if (provider.enabled) Color(0xFF925568) else Color(0xFF777178),
                                                fontSize = 11.sp,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = "包含 ${provider.models.size} 个模型",
                                        style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF777178))
                                    )
                                }

                                // 启停开关
                                Switch(
                                    enabled = !mutationBusy,
                                    checked = provider.enabled,
                                    onCheckedChange = { checked ->
                                        runProviderAction {
                                            settingsStore.update { old ->
                                                old.copy(
                                                    providers = old.providers.map { p ->
                                                        if (p.id == provider.id) p.copyProvider(enabled = checked) else p
                                                    }
                                                )
                                            }
                                        }
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color(0xFFFFFFFF),
                                        checkedTrackColor = Color(0xFFF8D5DE),
                                        uncheckedThumbColor = Color(0xFF777178),
                                        uncheckedTrackColor = Color(0xFFEFECEF)
                                    )
                                )
                            }

                            // Actions wrap at large font scales instead of squeezing the delete label.
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        nav.navigate(Screen.SettingProviderDetail(provider.id.toString()))
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFF8D5DE),
                                        contentColor = Color(0xFF925568)
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text("编辑配置", fontSize = 12.sp)
                                }

                                Button(
                                    onClick = {
                                        nav.navigate(Screen.StudyProviderModels(provider.id.toString()))
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFFFF1F5),
                                        contentColor = Color(0xFF925568)
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text("管理模型 (${provider.models.size})", fontSize = 12.sp)
                                }

                                TextButton(
                                    onClick = { deleteTarget = provider },
                                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFBA1A1A))
                                ) {
                                    Text("删除", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 添加新提供商对话框
    if (showAddDialog) {
        var newName by remember { mutableStateOf("自定义 OpenAI 兼容接口") }
        var newUrl by remember { mutableStateOf("https://api.openai.com/v1") }
        var newKey by remember { mutableStateOf("") }
        var maskNewKey by remember { mutableStateOf(true) }

        AlertDialog(
            onDismissRequest = { if (!mutationBusy) showAddDialog = false },
            title = { Text("添加 AI 提供商") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("提供商名称") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newUrl,
                        onValueChange = { newUrl = it },
                        label = { Text("API 地址 Base URL") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newKey,
                        onValueChange = { newKey = it },
                        label = { Text("API Key (可选)") },
                        visualTransformation = if (maskNewKey) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                        trailingIcon = {
                            TextButton(onClick = { maskNewKey = !maskNewKey }) {
                                Text(if (maskNewKey) "显示" else "遮蔽", fontSize = 12.sp, color = Color(0xFF925568))
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = !mutationBusy && isValidStudyProvider(ProviderSetting.OpenAI(name = newName.trim(), baseUrl = newUrl.trim())),
                    onClick = {
                        val newProvider = ProviderSetting.OpenAI(
                            id = Uuid.random(),
                            name = newName.trim(),
                            baseUrl = newUrl.trim(),
                            apiKey = newKey.trim(),
                            enabled = true,
                            models = emptyList()
                        )
                        runProviderAction {
                            settingsStore.update { old -> old.copy(providers = old.providers + newProvider) }
                            showAddDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF8D5DE),
                        contentColor = Color(0xFF925568)
                    )
                ) {
                    Text("添加")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("取消") }
            },
            containerColor = Color(0xFFFFFFFF),
            shape = RoundedCornerShape(16.dp)
        )
    }

    // 删除确认对话框
    if (deleteTarget != null) {
        val target = deleteTarget!!
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("确认删除提供商 ${target.name}？") },
            text = {
                Text(
                    "删除后该提供商下所有模型将被移除。如果该模型当前正在使用，将需要重新选择可用模型。",
                    color = Color(0xFF777178),
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        runProviderAction {
                            settingsStore.update { old ->
                                old.copy(providers = old.providers.filter { it.id != target.id })
                            }
                            deleteTarget = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFFDAD6),
                        contentColor = Color(0xFF93000A)
                    )
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
            containerColor = Color(0xFFFFFFFF),
            shape = RoundedCornerShape(16.dp)
        )
    }
}

/**
 * P13 提供商编辑页面
 * 表单本地草稿，保存走现有配置存储，返回时未保存改动需确认
 * 密钥默认遮蔽，测试连接与启用开关分离
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyProviderDetailPage(providerId: String) {
    MaterialTheme(colorScheme = me.rerere.rikkahub.ui.theme.presets.StudyWhitePinkThemePreset.getColorScheme(dark = false)) {
        StudyProviderDetailContent(providerId)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudyProviderDetailContent(providerId: String) {
    val nav = LocalNavController.current
    val settingsStore = koinInject<SettingsStore>()
    val manager = koinInject<ProviderManager>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val provider = settings.providers.firstOrNull { it.id.toString() == providerId }
    // Keep a stable editing snapshot while settingsFlow changes elsewhere.
    var original by remember(providerId) { mutableStateOf<ProviderSetting?>(null) }
    var draft by remember(providerId) { mutableStateOf<ProviderSetting?>(null) }
    LaunchedEffect(provider) {
        if (original == null && provider != null) {
            original = provider
            draft = provider
        }
    }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var nextScreen by remember { mutableStateOf<Screen?>(null) }
    var testStatus by remember { mutableStateOf("未测试") }
    var testJob by remember { mutableStateOf<Job?>(null) }
    var configurationVersion by remember { mutableLongStateOf(0L) }

    fun leave(destination: Screen? = null) {
        nextScreen = destination
        if (draft != original) showUnsavedDialog = true
        else if (destination != null) nav.navigate(destination) else nav.popBackStack()
    }
    BackHandler { if (!saving) leave() }
    DisposableEffect(Unit) { onDispose { testJob?.cancel() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("编辑提供商配置") },
                navigationIcon = {
                    TextButton(enabled = !saving, onClick = { leave() }) { Text("返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        },
        containerColor = Color.White
    ) { padding ->
        val editing = draft
        if (provider == null || editing == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("未找到指定的提供商")
            }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ProviderConfigure(provider = editing) { changed ->
                if (!saving) {
                    draft = changed
                    configurationVersion++
                    testJob?.cancel()
                    testJob = null
                    testStatus = "未测试（配置已改变）"
                    error = null
                }
            }
            Surface(color = Color(0xFFFFF1F5), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("连接检查：$testStatus")
                    Text("请求当前草稿配置的模型列表；成功不代表所有模型均可生成。")
                    Button(
                        enabled = !saving && isValidStudyProvider(editing),
                        onClick = {
                            if (testJob != null) {
                                configurationVersion++
                                testJob?.cancel()
                                testJob = null
                                testStatus = "已取消"
                            } else {
                                val requestVersion = configurationVersion
                                val snapshot = editing
                                testStatus = "检查中…"
                                testJob = scope.launch {
                                    try {
                                        val models = manager.getProviderByType(snapshot).listModels(snapshot)
                                        if (requestVersion == configurationVersion) {
                                            testStatus = "模型列表请求成功（${models.size} 个模型）"
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        if (requestVersion == configurationVersion) {
                                            testStatus = "连接检查失败，请检查地址、凭据和网络"
                                        }
                                    } finally {
                                        if (requestVersion == configurationVersion) testJob = null
                                    }
                                }
                            }
                        }
                    ) { Text(if (testJob == null) "检查连接" else "取消检查") }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                enabled = !saving && isValidStudyProvider(editing),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (!saving) {
                        saving = true
                        scope.launch {
                            try {
                                settingsStore.update { old ->
                                    val current = old.providers.firstOrNull { it.id == editing.id }
                                    require(current == original) { "配置已改变，请重新进入编辑页面" }
                                    old.copy(providers = old.providers.map {
                                        if (it.id == editing.id) editing else it
                                    })
                                }
                                nav.popBackStack()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                error = "保存失败或配置已改变，草稿已保留。请重试或重新进入编辑页面。"
                            } finally {
                                saving = false
                            }
                        }
                    }
                }
            ) { Text(if (saving) "保存中…" else "保存设置") }
            TextButton(enabled = !saving, onClick = { leave(Screen.StudyProviderModels(providerId)) }) {
                Text("管理模型")
            }
            TextButton(enabled = !saving, onClick = { leave(Screen.SettingProviderAdvanced(providerId)) }) {
                Text("高级设置与模型获取")
            }
        }
    }
    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text("有未保存的修改") },
            text = { Text("离开将放弃当前草稿，确认继续吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showUnsavedDialog = false
                    val destination = nextScreen
                    if (destination == null) nav.popBackStack() else nav.navigate(destination)
                }) { Text("放弃修改并继续") }
            },
            dismissButton = {
                TextButton(onClick = { showUnsavedDialog = false }) { Text("继续编辑") }
            }
        )
    }
}

/**
 * P14 提供商模型管理页面
 * 绑定特定提供商，新增/删除模型，独立多选能力 (文本/深度思考/视觉识别/工具调用)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyProviderModelsPage(providerId: String) {
    val nav = LocalNavController.current
    val settingsStore = koinInject<SettingsStore>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val providerUuid = remember(providerId) {
        try { Uuid.parse(providerId) } catch (_: Exception) { null }
    }
    val provider = remember(settings.providers, providerUuid) {
        settings.providers.firstOrNull { it.id == providerUuid }
    }

    var showAddModelDialog by remember { mutableStateOf(false) }
    var modelBusy by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    fun runModelAction(action: suspend () -> Unit) {
        if (modelBusy) return
        modelBusy = true
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { snackbar.showSnackbar("模型保存失败或 ID 重复，草稿已保留，请重试") }
            finally { modelBusy = false }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "${provider?.name ?: "提供商"} · 模型管理",
                        color = Color(0xFF29272B),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                navigationIcon = { BackButton() },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFFFFFFF))
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddModelDialog = true },
                containerColor = Color(0xFFF8D5DE),
                contentColor = Color(0xFF925568),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("+", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = Color(0xFFFFFFFF)
    ) { padding ->
        if (provider == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("未找到指定的提供商", color = Color(0xFF777178))
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            items(provider.models, key = { it.id.toString() }) { model ->
                Surface(
                    color = Color(0xFFFFFFFF),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFFEFECEF), RoundedCornerShape(14.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = model.displayName,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    color = Color(0xFF29272B),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp
                                )
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "模型 ID: ${model.modelId}",
                                style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF777178))
                            )
                            Spacer(Modifier.height(6.dp))
                            // 多选能力标签
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (model.type == ModelType.CHAT) {
                                    ModelBadge("文本对话")
                                }
                                if (model.abilities.contains(ModelAbility.REASONING)) {
                                    ModelBadge("深度思考")
                                }
                                if (model.inputModalities.contains(me.rerere.ai.provider.Modality.IMAGE)) {
                                    ModelBadge("视觉识别")
                                }
                                if (model.abilities.contains(ModelAbility.TOOL)) {
                                    ModelBadge("工具调用")
                                }
                            }
                        }

                        IconButton(
                            onClick = {
                                runModelAction {
                                    settingsStore.update { old ->
                                        old.copy(providers = old.providers.map { if (it.id == provider.id) it.delModel(model) else it })
                                    }
                                }
                            }
                        ) {
                            Text("×", fontSize = 20.sp, color = Color(0xFFBA1A1A))
                        }
                    }
                }
            }
        }
    }

    if (showAddModelDialog && provider != null) {
        var newModelId by remember { mutableStateOf("") }
        var newDisplayName by remember { mutableStateOf("") }
        var isReasoning by remember { mutableStateOf(false) }
        var isVision by remember { mutableStateOf(false) }
        var isTools by remember { mutableStateOf(false) }
        var errorMsg by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { if (!modelBusy) showAddModelDialog = false },
            title = { Text("添加新模型") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newModelId,
                        onValueChange = {
                            newModelId = it
                            errorMsg = null
                        },
                        label = { Text("模型标识 (modelId，必填)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = newDisplayName,
                        onValueChange = { newDisplayName = it },
                        label = { Text("显示名称 (可选)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text("模型能力 (独立多选配置)：", style = MaterialTheme.typography.labelMedium, color = Color(0xFF925568))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = true, onCheckedChange = null, enabled = false)
                        Text("文本对话 (基础)", fontSize = 13.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isReasoning, onCheckedChange = { isReasoning = it })
                        Text("深度思考 / 推理 (Reasoning)", fontSize = 13.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isVision, onCheckedChange = { isVision = it })
                        Text("视觉识别 (Vision)", fontSize = 13.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isTools, onCheckedChange = { isTools = it })
                        Text("工具调用 (Function Calling)", fontSize = 13.sp)
                    }

                    if (errorMsg != null) {
                        Text(errorMsg.orEmpty(), color = Color(0xFFBA1A1A), fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !modelBusy && newModelId.isNotBlank(),
                    onClick = {
                        val trimmedId = newModelId.trim()
                        if (trimmedId.isBlank()) {
                            errorMsg = "模型 ID 不能为空"
                            return@Button
                        }
                        if (provider.models.any { it.modelId.equals(trimmedId, ignoreCase = true) }) {
                            errorMsg = "该提供商已存在同名模型 ID"
                            return@Button
                        }

                        val abilities = buildList {
                            if (isReasoning) add(ModelAbility.REASONING)
                            if (isTools) add(ModelAbility.TOOL)
                        }
                        val modalities = buildList {
                            add(me.rerere.ai.provider.Modality.TEXT)
                            if (isVision) add(me.rerere.ai.provider.Modality.IMAGE)
                        }

                        val newModel = Model(
                            id = Uuid.random(),
                            modelId = trimmedId,
                            displayName = newDisplayName.trim().ifBlank { trimmedId },
                            abilities = abilities,
                            inputModalities = modalities,
                            type = ModelType.CHAT
                        )

                        runModelAction {
                            settingsStore.update { old ->
                                val current = old.providers.single { it.id == provider.id }
                                require(current.models.none { it.modelId.equals(trimmedId, ignoreCase = true) })
                                old.copy(providers = old.providers.map { if (it.id == provider.id) it.addModel(newModel) else it })
                            }
                            showAddModelDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF8D5DE),
                        contentColor = Color(0xFF925568)
                    )
                ) {
                    Text("保存添加")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddModelDialog = false }) { Text("取消") }
            },
            containerColor = Color(0xFFFFFFFF),
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
private fun ModelBadge(text: String) {
    Surface(
        color = Color(0xFFFFF1F5),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = text,
            color = Color(0xFF925568),
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
