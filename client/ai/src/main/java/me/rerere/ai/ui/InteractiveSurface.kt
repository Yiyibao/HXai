package me.rerere.ai.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

/**
 * 智能交互界面 - GenUI/A2UI 协议的数据结构
 *
 * 这是 ChatGPT/Claude 智能 Chat 功能的核心数据模型。
 * 模型可以生成包含图表、表格、滑块等可交互组件的动态界面,
 * 用户操作后可以继续对话。
 */
@Serializable
@SerialName("interactive_surface_definition")
data class InteractiveSurface(
    /** 界面唯一标识 */
    val surfaceId: String = "surface_${Uuid.random()}",

    /** 界面标题 */
    val title: String,

    /** 协议版本 (1, 2, 3) */
    val version: Int = 3,

    /** 组件定义 */
    val components: List<SurfaceComponent>,

    /** 布局定义 (version 3+), null则按顺序排列 */
    val layout: List<List<String>>? = null,

    /** 当前界面状态 (组件ID -> 值) */
    val state: Map<String, JsonElement> = emptyMap(),

    /** 界面版本号,用于并发控制 */
    val revision: Int = 0,

    /** 所属消息ID */
    val messageId: Uuid? = null,

    /** 所属分支ID */
    val branchId: Uuid? = null,

    /** 创建时间戳 */
    val createdAt: Long = System.currentTimeMillis(),

    /** 最后更新时间戳 */
    val updatedAt: Long = System.currentTimeMillis(),

    /** 元数据 */
    override var metadata: JsonObject? = null
) : UIMessagePart() {

    /**
     * 验证状态是否有效
     */
    fun validateState(newState: Map<String, JsonElement>): Boolean {
        val stateKeys = components.filter { it.isStateful() }.map { it.id }.toSet()
        return newState.keys == stateKeys
    }

    /**
     * 更新状态并增加版本号
     */
    fun withState(newState: Map<String, JsonElement>): InteractiveSurface {
        require(validateState(newState)) { "Invalid state keys" }
        return copy(
            state = newState,
            revision = revision + 1,
            updatedAt = System.currentTimeMillis()
        )
    }
}

/**
 * 界面组件定义
 */
@Serializable
sealed class SurfaceComponent {
    abstract val id: String
    abstract val type: String

    abstract fun isStateful(): Boolean

    @Serializable
    @SerialName("text")
    data class Text(
        override val id: String,
        val text: String
    ) : SurfaceComponent() {
        override val type = "text"
        override fun isStateful() = false
    }

    @Serializable
    @SerialName("card")
    data class Card(
        override val id: String,
        val text: String
    ) : SurfaceComponent() {
        override val type = "card"
        override fun isStateful() = false
    }

    @Serializable
    @SerialName("table")
    data class Table(
        override val id: String,
        val columns: List<String>,
        val rows: List<List<String>>,
        val filter: String? = null,
        val filterColumn: Int? = null
    ) : SurfaceComponent() {
        override val type = "table"
        override fun isStateful() = false
    }

    @Serializable
    @SerialName("line_chart")
    data class LineChart(
        override val id: String,
        val points: List<Pair<Double, Double>>,
        val xLabel: String? = null,
        val yLabel: String? = null,
        val scale: String? = null,
        val selectable: Boolean = false
    ) : SurfaceComponent() {
        override val type = "line_chart"
        override fun isStateful() = selectable
    }

    @Serializable
    @SerialName("bar_chart")
    data class BarChart(
        override val id: String,
        val points: List<Pair<Double, Double>>,
        val xLabel: String? = null,
        val yLabel: String? = null,
        val scale: String? = null,
        val selectable: Boolean = false
    ) : SurfaceComponent() {
        override val type = "bar_chart"
        override fun isStateful() = selectable
    }

    @Serializable
    @SerialName("scatter_chart")
    data class ScatterChart(
        override val id: String,
        val points: List<Pair<Double, Double>>,
        val xLabel: String? = null,
        val yLabel: String? = null,
        val scale: String? = null,
        val selectable: Boolean = false
    ) : SurfaceComponent() {
        override val type = "scatter_chart"
        override fun isStateful() = selectable
    }

    @Serializable
    @SerialName("slider")
    data class Slider(
        override val id: String,
        val label: String,
        val min: Double,
        val max: Double,
        val value: Double
    ) : SurfaceComponent() {
        override val type = "slider"
        override fun isStateful() = true
    }

    @Serializable
    @SerialName("select")
    data class Select(
        override val id: String,
        val label: String,
        val options: List<String>,
        val value: String
    ) : SurfaceComponent() {
        override val type = "select"
        override fun isStateful() = true
    }

    @Serializable
    @SerialName("input")
    data class Input(
        override val id: String,
        val label: String,
        val value: String
    ) : SurfaceComponent() {
        override val type = "input"
        override fun isStateful() = true
    }

    @Serializable
    @SerialName("calculation")
    data class Calculation(
        override val id: String,
        val label: String,
        val parameter: String? = null,
        val a: Double? = null,
        val b: Double? = null,
        val expression: JsonElement? = null
    ) : SurfaceComponent() {
        override val type = "calculation"
        override fun isStateful() = false
    }

    @Serializable
    @SerialName("button")
    data class Button(
        override val id: String,
        val label: String,
        val action: String = "continue"
    ) : SurfaceComponent() {
        override val type = "button"
        override fun isStateful() = false
    }
}

/**
 * 界面动作请求
 */
@Serializable
data class SurfaceActionRequest(
    val surfaceId: String,
    val messageId: Uuid,
    val branchId: Uuid,
    val revision: Int,
    val state: Map<String, JsonElement>,
    val actionId: String? = null,
    val requestId: String = "action_${Uuid.random()}"
)

/**
 * 界面动作响应
 */
@Serializable
data class SurfaceActionResponse(
    val success: Boolean,
    val newRevision: Int? = null,
    val error: String? = null,
    val continueConversation: Boolean = false
)
