package me.rerere.rikkahub.feature.studychat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import me.rerere.ai.provider.Modality
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.feature.material.MaterialFile
import me.rerere.rikkahub.feature.material.ui.StudyFileCard
import me.rerere.rikkahub.feature.studychat.CompanionVisualState
import me.rerere.rikkahub.feature.studychat.StudyCompanion
import me.rerere.rikkahub.feature.studychat.StudyStatusBar
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import kotlin.uuid.Uuid

/**
 * MathStudyAgent 16页全景预览与验证画廊 (P01–P16)
 * 供自我审查、验收与 Compose 交互验证
 * 遵守白底淡粉规范，纯真实控件组合，不直接贴图
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyHandoffGalleryPage() {
    var selectedTab by remember { mutableIntStateOf(0) }
    val pageTitles = listOf(
        "P01 聊天首页", "P02 教学聊天", "P03 材料列表", "P04 原图核对",
        "P05 记忆列表", "P06 记忆详情", "P07 成果列表", "P08 成果预览",
        "P09 设置中心", "P10 错误恢复", "P11 模型选择", "P12 提供商管理",
        "P13 提供商编辑", "P14 模型能力", "P15 键盘输入", "P16 大字体详情"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "前端交接验收画廊 (P01–P16)",
                        color = Color(0xFF29272B),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                navigationIcon = { BackButton() },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFFFFFFF))
            )
        },
        containerColor = Color(0xFFFFFFFF)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 顶部分页标签横滑
            SecondaryScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color(0xFFFFFFFF),
                contentColor = Color(0xFF925568),
                edgePadding = 16.dp,
            ) {
                pageTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = title.take(7),
                                color = if (selectedTab == index) Color(0xFF925568) else Color(0xFF777178),
                                fontSize = 13.sp,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            // 当前选中页面展示区
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                when (selectedTab) {
                    0 -> GalleryPage01()
                    1 -> GalleryPage02()
                    2 -> GalleryPage03()
                    3 -> GalleryPage04()
                    4 -> GalleryPage05()
                    5 -> GalleryPage06()
                    6 -> GalleryPage07()
                    7 -> GalleryPage08()
                    8 -> GalleryPage09()
                    9 -> GalleryPage10()
                    10 -> GalleryPage11()
                    11 -> GalleryPage12()
                    12 -> GalleryPage13()
                    13 -> GalleryPage14()
                    14 -> GalleryPage15()
                    15 -> GalleryPage16()
                }
            }
        }
    }
}

//region P01: 聊天首页
@Composable
private fun GalleryPage01() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PageBadge("P01 · 聊天首页（空态）", "白底、淡粉快捷按钮、顶端单个36dp页芽、输入框内右侧模型框")

        // 顶栏状态
        Surface(
            color = Color(0xFFFFFFFF),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFFEFECEF), RoundedCornerShape(14.dp))
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StudyCompanion()
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("高一数学 · 默认学习者", color = Color(0xFF925568), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("静候提问 · 可以继续提问", color = Color(0xFF777178), fontSize = 12.sp)
                }
                Surface(
                    color = Color(0xFFFFF1F5),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("引导教学", color = Color(0xFF925568), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
        }

        // 快捷入口
        Text("快捷开始：", color = Color(0xFF777178), fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {},
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF8D5DE), contentColor = Color(0xFF925568)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("拍题提问")
            }
            Button(
                onClick = {},
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFF1F5), contentColor = Color(0xFF925568)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("导入讲义/试卷")
            }
        }

        // 近期会话
        Surface(
            color = Color(0xFFFFFFFF),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFFEFECEF), RoundedCornerShape(14.dp))
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("近期练习会话", fontWeight = FontWeight.SemiBold, color = Color(0xFF29272B), fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                Text("● 三角恒等变换与反求导综合 · 20分钟前", color = Color(0xFF777178), fontSize = 13.sp)
                Text("● 函数奇偶性与单调性综合练习 · 昨天", color = Color(0xFF777178), fontSize = 13.sp)
            }
        }

        Spacer(Modifier.weight(1f, fill = false))

        // 底部输入框与模型框
        MockInputBoxWithModel()
    }
}
//endregion

//region P02: 教学聊天
@Composable
private fun GalleryPage02() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PageBadge("P02 · 教学聊天", "用户浅粉气泡(#FFF1F5)、助手白底、真实公式、输入框模型框、停止按钮")

        // 助手状态条
        Surface(
            color = Color(0xFFFFFFFF),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFFEFECEF), RoundedCornerShape(12.dp))
        ) {
            Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StudyCompanion(state = CompanionVisualState.GENERATING)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("小林 · 高一数学", color = Color(0xFF925568), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("正在生成解答… (模型: GPT-4o)", color = Color(0xFF777178), fontSize = 12.sp)
                }
                Button(
                    onClick = {},
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF8D5DE), contentColor = Color(0xFF925568)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text("停止", fontSize = 12.sp)
                }
            }
        }

        // 用户消息气泡（浅粉背景）
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = Color(0xFFFFF1F5),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .border(0.5.dp, Color(0xFFF8D5DE), RoundedCornerShape(16.dp))
            ) {
                Text(
                    text = "请问这个函数的定义域该怎么求？\nf(x) = sqrt(x-1) / (x-2)",
                    color = Color(0xFF29272B),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }

        // 助手消息正文（纯白底板）
        Surface(
            color = Color(0xFFFFFFFF),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFFEFECEF), RoundedCornerShape(16.dp))
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("解答步骤：", fontWeight = FontWeight.Bold, color = Color(0xFF925568), fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                val d = "$"
                MarkdownBlock(
                    content = """
我们分两步考虑定义域约束：
1. **二次根式非负**：${d}x - 1 \ge 0 \implies x \ge 1${d}
2. **分式分母非零**：${d}x - 2 \ne 0 \implies x \ne 2${d}

综合两点，定义域为 ${d}[1, 2) \cup (2, +\infty)${d}。
""".trimIndent()
                )
            }
        }

        // 底部输入框
        MockInputBoxWithModel(generating = true)
    }
}
//endregion

//region P03: 材料列表
@Composable
private fun GalleryPage03() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PageBadge("P03 · 材料列表", "优化MaterialPage材料分支，StudyFileCard真实范围与独立状态")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {},
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF8D5DE), contentColor = Color(0xFF925568)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("上传文件或讲义")
            }
        }

        StudyFileCard(
            file = MaterialFile(
                id = "mat_01",
                name = "2026高考数学一轮复习导数专题.pdf",
                extension = "pdf",
                profileId = "profile_1",
                size = 1048576,
                sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                processingState = "PROCESSED",
                detail = "第 1–16 页（含手写批注）",
                output = false
            ),
            onCheckSymbol = {}
        )

        StudyFileCard(
            file = MaterialFile(
                id = "mat_02",
                name = "三角函数综合诊断题题干.docx",
                extension = "docx",
                profileId = "profile_1",
                size = 45020,
                sha256 = "c4ca4238a0b923820dcc509a6f75849b",
                processingState = "PROCESSING",
                detail = "解析中…",
                output = false
            )
        )
    }
}
//endregion

//region P04: 原图核对
@Composable
private fun GalleryPage04() {
    var showSheet by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PageBadge("P04 · 原图核对", "StudySymbolCheckSheet：原图预览、两个未预选候选、手动输入、确认与稍后处理")

        Button(
            onClick = { showSheet = true },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF8D5DE), contentColor = Color(0xFF925568)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("打开原图核对 BottomSheet (P04 演示)")
        }

        Text("点击上方按钮可直接呼出真实 StudySymbolCheckSheet，遮盖主导航并提供真实候选与手动输入。", color = Color(0xFF777178), fontSize = 13.sp)

        // 行内预览卡片
        Surface(
            color = Color(0xFFFFF1F5),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFFF8D5DE), RoundedCornerShape(16.dp))
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("核对演示状态：", fontWeight = FontWeight.Bold, color = Color(0xFF925568))
                Spacer(Modifier.height(6.dp))
                Text("原图识别片段: f'(x) = 2\\sin(x)\\cos(x) ?")
                Text("候选 1: f'(x) = 2\\sin(x)\\cos(x)")
                Text("候选 2: f'(x) = 2\\sin(x) + \\cos(x)")
                Text("手动输入: 支持 LaTeX 补正")
            }
        }

        StudySymbolCheckSheet(
            visible = showSheet,
            onDismissRequest = { showSheet = false },
            onConfirm = { showSheet = false },
            onPostpone = { showSheet = false }
        )
    }
}
//endregion

//region P05: 记忆列表
@Composable
private fun GalleryPage05() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PageBadge("P05 · 记忆列表", "按档案隔离、卡点/作答证据/复习事项分层，自动记忆开关，页芽折角舒展")

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("自动抽取长期记忆", fontWeight = FontWeight.Bold, color = Color(0xFF29272B))
                Text("每日上限 20 次，保留删除抑制", fontSize = 12.sp, color = Color(0xFF777178))
            }
            Switch(
                checked = true,
                onCheckedChange = {},
                colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFFF8D5DE), checkedThumbColor = Color(0xFFFFFFFF))
            )
        }

        // 记忆卡片
        Surface(
            color = Color(0xFFFFFFFF),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFFEFECEF), RoundedCornerShape(14.dp))
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("当前卡点：复合函数求导链式法则", fontWeight = FontWeight.Bold, color = Color(0xFF925568))
                    Surface(color = Color(0xFFFFF1F5), shape = RoundedCornerShape(6.dp)) {
                        Text("待核对候选", color = Color(0xFF925568), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
                Text("学习者经常遗漏内层函数 u(x) 的导数项。", color = Color(0xFF29272B), fontSize = 14.sp)
                Text("来源：2026-10-07 导数练习会话 · 独立尝试未完成", color = Color(0xFF777178), fontSize = 12.sp)
            }
        }
    }
}
//endregion

//region P06: 记忆详情
@Composable
private fun GalleryPage06() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PageBadge("P06 · 记忆详情", "事实、来源、证据、后续作答分层，底部更正/删除始终可达，移除主导航")

        Surface(
            color = Color(0xFFFFF1F5),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFFF8D5DE), RoundedCornerShape(14.dp))
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("事实描述：", color = Color(0xFF925568), fontWeight = FontWeight.Bold)
                Text("学习者在计算 ln(x^2 + 1) 求导时，容易只写 1/(x^2 + 1)，遗漏 2x 乘项。", color = Color(0xFF29272B), fontSize = 14.sp)
            }
        }

        Surface(
            color = Color(0xFFFFFFFF),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFFEFECEF), RoundedCornerShape(14.dp))
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("溯源与证据：", color = Color(0xFF29272B), fontWeight = FontWeight.Bold)
                Text("来源会话: c3a1098b-2144-48f1-839c-5d18725f0e91", color = Color(0xFF777178), fontSize = 12.sp)
                Text("证据类型: 提示后部分纠正 (WITH_HINT)", color = Color(0xFF777178), fontSize = 12.sp)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = {}, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) {
                Text("删除记忆", color = Color(0xFFBA1A1A))
            }
            Button(onClick = {}, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF8D5DE), contentColor = Color(0xFF925568)), shape = RoundedCornerShape(10.dp)) {
                Text("更正记忆")
            }
        }
    }
}
//endregion

//region P07: 成果列表
@Composable
private fun GalleryPage07() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PageBadge("P07 · 成果列表", "真实文件名称与格式，中性/粉色线条图标，打开/另存为/分享操作")

        StudyFileCard(
            file = MaterialFile(
                id = "art_01",
                name = "导数几何意义与切线方程解析讲义.md",
                extension = "md",
                profileId = "profile_1",
                size = 3280,
                sha256 = "a1b2c3d4e5f60718293a4b5c6d7e8f90",
                processingState = "SAVED",
                detail = "已登记成果 · Markdown 格式",
                output = true
            ),
            isArtifact = true
        )

        StudyFileCard(
            file = MaterialFile(
                id = "art_02",
                name = "三角函数专题练习错题整理.docx",
                extension = "docx",
                profileId = "profile_1",
                size = 18400,
                sha256 = "b2c3d4e5f60718293a4b5c6d7e8f90a1",
                processingState = "SAVED",
                detail = "已登记成果 · Word 格式",
                output = true
            ),
            isArtifact = true
        )
    }
}
//endregion

//region P08: 成果预览
@Composable
private fun GalleryPage08() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PageBadge("P08 · 成果预览", "真实数学渲染器，定义域 [1,2) ∪ (2,+∞) 正确，草稿/已导出分离")

        Surface(
            color = Color(0xFFFFF1F5),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "状态：已登记成果 · 属于当前学习者",
                color = Color(0xFF925568),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(10.dp)
            )
        }

        Surface(
            color = Color(0xFFFFFFFF),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFFEFECEF), RoundedCornerShape(16.dp))
        ) {
            Column(Modifier.padding(16.dp)) {
                val d = "$"
                MarkdownBlock(
                    content = """
# 函数定义域推导成果

已知函数为 ${d}f(x) = \frac{\sqrt{x - 1}}{x - 2}${d}：
- 根号非负：${d}x \ge 1${d}
- 分母非零：${d}x \ne 2${d}

最终定义域：
${d}${d}D = [1, 2) \cup (2, +\infty)${d}${d}
""".trimIndent()
                )
            }
        }
    }
}
//endregion

//region P09: 设置中心
@Composable
private fun GalleryPage09() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PageBadge("P09 · 设置", "减少动画 (reduceMotion) 默认开启、提供商与模型管理、返回式子页")

        Surface(
            color = Color(0xFFFFFFFF),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFFEFECEF), RoundedCornerShape(14.dp))
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("减少动画 (Reduce Motion)", fontWeight = FontWeight.Bold, color = Color(0xFF29272B))
                        Text("开启后关闭待机眨眼等装饰动画，立即进入终态", fontSize = 12.sp, color = Color(0xFF777178))
                    }
                    Switch(
                        checked = true,
                        onCheckedChange = {},
                        colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFFF8D5DE), checkedThumbColor = Color(0xFFFFFFFF))
                    )
                }

                HorizontalDivider(color = Color(0xFFEFECEF))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("服务提供商与模型配置 (P12)", fontWeight = FontWeight.SemiBold, color = Color(0xFF29272B))
                    Text("进入 →", color = Color(0xFF925568), fontSize = 13.sp)
                }

                HorizontalDivider(color = Color(0xFFEFECEF))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("当前界面主题：白色淡粉 · MathStudy", color = Color(0xFF29272B))
                    Text("已启用", color = Color(0xFF925568), fontSize = 12.sp)
                }
            }
        }
    }
}
//endregion

//region P10: 错误恢复
@Composable
private fun GalleryPage10() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PageBadge("P10 · 错误与恢复", "StudyErrorRecoveryCard：区分中断、失败与停止；保留已生成内容；真实重新生成")

        StudyErrorRecoveryCard(
            state = "INTERRUPTED",
            errorMessage = "网络连接超时，已接收的公式推导步骤已安全保留在消息列表中。",
            onRetry = {}
        )

        StudyErrorRecoveryCard(
            state = "CANCELLED",
            errorMessage = "已根据用户要求停止生成，已有推导保留供参考。",
            onRetry = {}
        )

        StudyErrorRecoveryCard(
            state = "FAILED",
            errorMessage = "服务提供商返回 HTTP 500 临时故障，可点击重新生成重试。",
            onRetry = {}
        )
    }
}
//endregion

//region P11: 模型选择
@Composable
private fun GalleryPage11() {
    var showModelSheet by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PageBadge("P11 · 模型选择", "按 providerId 分组 ModalBottomSheet、搜索、能力标签、选中底板、管理入口")

        Button(
            onClick = { showModelSheet = true },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF8D5DE), contentColor = Color(0xFF925568)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("呼出模型选择 BottomSheet (P11 演示)")
        }

        // 静态说明
        Text("可在此验证按提供商分组、本地实时搜索、选中标记及下一轮生效状态提示。", color = Color(0xFF777178), fontSize = 13.sp)

        val mockProviders = listOf(
            ProviderSetting.OpenAI(
                id = Uuid.random(),
                name = "OpenAI 官方服务",
                enabled = true,
                models = listOf(
                    Model(id = Uuid.random(), modelId = "gpt-4o", displayName = "GPT-4o (全能)", abilities = listOf(ModelAbility.TOOL), inputModalities = listOf(Modality.TEXT, Modality.IMAGE), type = ModelType.CHAT),
                    Model(id = Uuid.random(), modelId = "o1-mini", displayName = "o1-mini (深度思考)", abilities = listOf(ModelAbility.REASONING), type = ModelType.CHAT),
                )
            ),
            ProviderSetting.OpenAI(
                id = Uuid.random(),
                name = "DeepSeek 官方平台",
                enabled = true,
                models = listOf(
                    Model(id = Uuid.random(), modelId = "deepseek-reasoner", displayName = "DeepSeek-R1 (推理)", abilities = listOf(ModelAbility.REASONING), type = ModelType.CHAT),
                    Model(id = Uuid.random(), modelId = "deepseek-chat", displayName = "DeepSeek-V3", abilities = listOf(ModelAbility.TOOL), type = ModelType.CHAT),
                )
            )
        )

        StudyModelPickerSheet(
            visible = showModelSheet,
            onDismissRequest = { showModelSheet = false },
            providers = mockProviders,
            currentModelId = mockProviders[0].models[0].id,
            isGenerating = true,
            onSelectModel = { showModelSheet = false },
            onManageProviders = { showModelSheet = false }
        )
    }
}
//endregion

//region P12: 提供商管理
@Composable
private fun GalleryPage12() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PageBadge("P12 · 提供商管理", "列表读真实配置、启停开关 120ms 色变、编辑与模型管理入口")

        Surface(
            color = Color(0xFFFFFFFF),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFFEFECEF), RoundedCornerShape(14.dp))
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("DeepSeek Platform", fontWeight = FontWeight.Bold, color = Color(0xFF29272B), fontSize = 15.sp)
                    Switch(checked = true, onCheckedChange = {}, colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFFF8D5DE), checkedThumbColor = Color(0xFFFFFFFF)))
                }
                Text("接口地址: https://api.deepseek.com · 2个模型", color = Color(0xFF777178), fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF8D5DE), contentColor = Color(0xFF925568)), shape = RoundedCornerShape(8.dp)) {
                        Text("编辑配置", fontSize = 12.sp)
                    }
                    Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFF1F5), contentColor = Color(0xFF925568)), shape = RoundedCornerShape(8.dp)) {
                        Text("管理模型", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
//endregion

//region P13: 提供商编辑
@Composable
private fun GalleryPage13() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PageBadge("P13 · 提供商编辑", "草稿表单、密钥默认遮蔽、测试连接与保存独立、返回确认")

        OutlinedTextField(
            value = "DeepSeek Platform",
            onValueChange = {},
            label = { Text("提供商名称") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )
        OutlinedTextField(
            value = "https://api.deepseek.com",
            onValueChange = {},
            label = { Text("Base URL") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )
        OutlinedTextField(
            value = "sk-••••••••••••••••••••••••",
            onValueChange = {},
            label = { Text("API Key (已遮蔽)") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )
        Button(
            onClick = {},
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF8D5DE), contentColor = Color(0xFF925568)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("保存设置")
        }
    }
}
//endregion

//region P14: 提供商模型管理
@Composable
private fun GalleryPage14() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PageBadge("P14 · 提供商模型管理", "模型列表、多选能力（文本/深度思考/视觉/工具）、modelId 判重")

        Surface(
            color = Color(0xFFFFFFFF),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFFEFECEF), RoundedCornerShape(14.dp))
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("deepseek-reasoner", fontWeight = FontWeight.Bold, color = Color(0xFF29272B))
                Text("显示名称: DeepSeek R1 深度思考", color = Color(0xFF777178), fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(color = Color(0xFFFFF1F5), shape = RoundedCornerShape(6.dp)) {
                        Text("文本对话", color = Color(0xFF925568), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                    Surface(color = Color(0xFFFFF1F5), shape = RoundedCornerShape(6.dp)) {
                        Text("深度思考", color = Color(0xFF925568), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
            }
        }
    }
}
//endregion

//region P15: 键盘输入
@Composable
private fun GalleryPage15() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PageBadge("P15 · 键盘输入布局", "系统 IME 上浮、输入区贴键盘上方、隐藏底部主导航、停止仍可达")

        Text("实际运行中，ChatPage 布局通过 WindowInsets.isImeVisible 在软键盘弹起时隐藏主导航栏，并将输入框与停止按钮贴紧键盘上方，保证草稿与操作焦点不丢失。", color = Color(0xFF777178), fontSize = 13.sp)

        // 模拟键盘贴合区
        Surface(
            color = Color(0xFFF8F5F7),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFFEFECEF), RoundedCornerShape(16.dp))
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("模拟系统 IME 弹起状态：", color = Color(0xFF925568), fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                MockInputBoxWithModel(text = "正在输入数学推导过程…")
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = Color(0xFFEFECEF),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("【系统输入法区域】(应用内不画假键盘，由 Android IME 托管)", color = Color(0xFF777178), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
//endregion

//region P16: 大字体详情
@Composable
private fun GalleryPage16() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PageBadge("P16 · 大字体详情（200% 可访问性）", "全部文字采用 sp、拒绝固定文本卡高度、长公式横向滚动、按钮纵向堆叠")

        Surface(
            color = Color(0xFFFFF1F5),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFFF8D5DE), RoundedCornerShape(16.dp))
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // 模拟大字体标题
                Text(
                    text = "大字体示例：复合函数链式求导卡点",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF925568),
                    lineHeight = 32.sp
                )

                Text(
                    text = "在系统字体放大至 200% 时，文字自然换行且完整可见，不发生固定高度截断或遮挡。",
                    fontSize = 18.sp,
                    color = Color(0xFF29272B),
                    lineHeight = 28.sp
                )

                // 横向滚动长公式
                Text("超长公式支持横向滚动：", fontSize = 16.sp, color = Color(0xFF777178))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .background(Color(0xFFFFFFFF), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = "f'(x) = \\lim_{\\Delta x \\to 0} \\frac{f(x+\\Delta x) - f(x)}{\\Delta x} = 2x \\cdot \\sin(x^2 + 1) + (x^2 + 1) \\cdot \\cos(x^2 + 1) \\cdot 2x",
                        fontSize = 18.sp,
                        color = Color(0xFF29272B)
                    )
                }

                // 纵向堆叠操作按钮
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {},
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF8D5DE), contentColor = Color(0xFF925568)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("回看来源会话", fontSize = 18.sp, modifier = Modifier.padding(vertical = 4.dp))
                    }
                    OutlinedButton(
                        onClick = {},
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("更正记忆内容", fontSize = 18.sp, modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        }
    }
}
//endregion

@Composable
private fun PageBadge(title: String, desc: String) {
    Surface(
        color = Color(0xFFFFF1F5),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(text = title, fontWeight = FontWeight.Bold, color = Color(0xFF925568), fontSize = 15.sp)
            Spacer(Modifier.height(2.dp))
            Text(text = desc, color = Color(0xFF777178), fontSize = 12.sp)
        }
    }
}

@Composable
private fun MockInputBoxWithModel(
    text: String = "",
    generating: Boolean = false
) {
    Surface(
        color = Color(0xFFFFFFFF),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFFF8D5DE), RoundedCornerShape(16.dp))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text.ifBlank { "输入数学问题或推导步骤…" },
                color = if (text.isBlank()) Color(0xFF777178) else Color(0xFF29272B),
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )

            // 输入框右侧模型框（浅粉交互底色）
            Surface(
                color = Color(0xFFFFF1F5),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .border(0.5.dp, Color(0xFFF8D5DE), RoundedCornerShape(10.dp))
                    .clickable {}
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("GPT-4o", color = Color(0xFF925568), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(4.dp))
                    Text("▼", color = Color(0xFF925568), fontSize = 8.sp)
                }
            }

            Spacer(Modifier.width(8.dp))

            // 发送按钮
            Surface(
                color = if (text.isBlank() && !generating) Color(0xFFF8F5F7) else Color(0xFFF8D5DE),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = if (generating) "■" else "↑",
                        color = if (text.isBlank() && !generating) Color(0xFF777178) else Color(0xFF925568),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}
