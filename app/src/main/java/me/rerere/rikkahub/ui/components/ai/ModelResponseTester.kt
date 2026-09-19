package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Connect
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * 模型响应测试：单次流式请求，返回首个可见 token 的延迟与整体吞吐。
 *
 * 设计要点（与设置页 ProviderConnectionTester 的差异）：
 * - 只做**流式**一路：真实聊天走的就是流式，能同时覆盖鉴权/网络/模型可用性/流式解析；
 * - 额外测 **TTFT（首字延迟）**：用户体感最直接的指标；
 * - 提示词要求"一句话"，并用 [TEST_MAX_TOKENS] 限制输出，避免浪费额度；
 * - 思考强度默认 [ReasoningLevel.AUTO]（与真实聊天的 assistant 默认值一致）：对支持思考的模型
 *   才会下发思考参数，因此不影响普通模型；对"必须带思考参数否则 400"的模型能避免报错。
 */
private const val TEST_MAX_TOKENS = 64
private const val TEST_SYSTEM_PROMPT = "You are a helpful assistant."
private const val TEST_USER_PROMPT = "Say hi in one short sentence."

/** 测试结果：成功时给出延迟/吞吐/回复片段 */
sealed interface ModelTestResult {
    data object Running : ModelTestResult

    data class Success(
        /** 首字延迟（毫秒）：从发起到第一个文本增量 */
        val firstTokenMs: Long,
        /** 总耗时（毫秒） */
        val totalMs: Long,
        /** 输出 token 数（多数提供商在流末尾回传 Usage；缺省时按字符估算） */
        val completionTokens: Int,
        /** 吞吐（token/秒）；耗时过短时不计算，返回 null */
        val tokensPerSecond: Double?,
        /** 回复片段（用于确认模型真的回了话，而不只是连通） */
        val sample: String,
    ) : ModelTestResult

    data class Failure(
        /** 异常类型名（如 HttpException / UnknownHostException）；[EMPTY_RESPONSE_TYPE] 表示"连上但没内容" */
        val type: String,
        val message: String,
    ) : ModelTestResult
}

/** 流正常结束但无文本产出（非异常，文案由 UI 层本地化） */
internal const val EMPTY_RESPONSE_TYPE = "EmptyResponse"

/**
 * 模型响应测试状态持有者。
 *
 * 状态按 modelId 存放，因此同一模型在"收藏区"和"普通列表"两条入口共享同一份运行状态
 * （避免同模型被并发测试）；sheet 关闭后状态随 remember 释放。
 */
class ModelResponseTester internal constructor(
    private val providerManager: ProviderManager,
) {
    private val results = mutableStateMapOf<Uuid, ModelTestResult>()

    /** 是否正在测试（按钮据此显示进度圈并禁用点击） */
    fun isRunning(modelId: Uuid): Boolean = results[modelId] is ModelTestResult.Running

    /**
     * 执行一次测试并返回结果（结果由调用方决定如何呈现）。
     *
     * 是 suspend 函数：调用方在自己的协程里 await，sheet 关闭导致作用域取消时，
     * 请求会随之取消（不会留下悬挂状态）。
     */
    internal suspend fun test(
        model: Model,
        providerSetting: ProviderSetting,
        /** 思考强度；见类注释 */
        reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    ): ModelTestResult {
        val modelId = model.id
        results[modelId] = ModelTestResult.Running
        val startedAt = System.currentTimeMillis()
        return try {
            runTest(model, providerSetting, reasoningLevel, startedAt)
        } finally {
            results.remove(modelId)
        }
    }

    private suspend fun runTest(
        model: Model,
        providerSetting: ProviderSetting,
        reasoningLevel: ReasoningLevel,
        startedAt: Long,
    ): ModelTestResult = try {
        val provider = providerManager.getProviderByType(providerSetting)
        var firstTokenAt: Long? = null
        val text = StringBuilder()
        var usageTokens = 0

        provider.streamText(
            providerSetting = providerSetting,
            messages = listOf(
                UIMessage.system(TEST_SYSTEM_PROMPT),
                UIMessage.user(TEST_USER_PROMPT),
            ),
            params = TextGenerationParams(
                model = model,
                maxTokens = TEST_MAX_TOKENS,
                reasoningLevel = reasoningLevel,
                customHeaders = model.customHeaders,
                customBody = model.customBodies,
            ),
        ).collect { chunk ->
            when (chunk) {
                is StreamChunk.TextDelta -> {
                    if (firstTokenAt == null) firstTokenAt = System.currentTimeMillis()
                    text.append(chunk.text)
                }

                is StreamChunk.Usage -> usageTokens = chunk.usage.completionTokens

                else -> Unit
            }
        }

        val totalMs = System.currentTimeMillis() - startedAt
        val sample = text.toString().trim()
        if (firstTokenAt == null && sample.isEmpty()) {
            // 流正常结束但没有任何文本：连接没问题，但模型没产出内容。
            // 文案交给 UI 层本地化（这里只标记类型）
            ModelTestResult.Failure(type = EMPTY_RESPONSE_TYPE, message = "")
        } else {
            val estimatedTokens = if (usageTokens > 0) {
                usageTokens
            } else {
                // 粗略估算：4 字符 ≈ 1 token（中英混排下的常用近似）
                (sample.length / 4).coerceAtLeast(1)
            }
            val firstTokenMs = (firstTokenAt ?: startedAt) - startedAt
            val elapsedForRate = (totalMs - firstTokenMs).coerceAtLeast(1L)
            ModelTestResult.Success(
                firstTokenMs = firstTokenMs,
                totalMs = totalMs,
                completionTokens = estimatedTokens,
                tokensPerSecond = if (estimatedTokens >= 2 && elapsedForRate >= 50) {
                    estimatedTokens.toDouble() / (elapsedForRate / 1000.0)
                } else {
                    null
                },
                sample = sample,
            )
        }
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        ModelTestResult.Failure(
            type = error.javaClass.simpleName,
            message = error.message ?: error.toString(),
        )
    }
}

/**
 * 记住一个 [ModelResponseTester]（按 sheet 生命周期存活）。
 *
 * 依赖注入方式与 [ProviderBalanceText] 一致：直接用容器里的 ProviderManager。
 */
@Composable
fun rememberModelResponseTester(): ModelResponseTester {
    val providerManager = koinInject<ProviderManager>()
    return remember(providerManager) { ModelResponseTester(providerManager) }
}

/**
 * 模型项右侧的「测试响应」按钮（紧邻收藏按钮右侧）。
 *
 * 排版说明：用 [clickable] 的 32dp Box 而非 IconButton —— m3 1.5.0-alpha 起 IconButton 强制
 * 48dp 最小触控目标且不再响应 `LocalMinimumInteractiveComponentEnforcement`（pitfalls #24），
 * 两个 IconButton 并排会把 tail 撑到 96dp+，使收藏按钮被迫大幅左移。
 *
 * 交互：点击后按钮转为进度圈并禁用（防连点浪费额度）；**结果通过 toast 上报**，不占列表空间；
 * 测试中不弹 toast。与收藏按钮之间沿用 tail 所在 Row 的 12dp 间距（用户要求"紧凑点也没关系"）。
 */
@Composable
fun ModelTestButton(
    model: Model,
    providerSetting: ProviderSetting,
    tester: ModelResponseTester,
    modifier: Modifier = Modifier,
) {
    val running = tester.isRunning(model.id)
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier
            // 32dp 视觉尺寸 + clickable（无 IconButton 的 48dp 强制触控目标），
            // 配合收藏按钮即可在 tail 内留出空间而不挤压模型名区域
            .size(32.dp)
            .clip(CircleShape)
            .clickable(enabled = !running) {
                scope.launch {
                    val result = tester.test(model, providerSetting)
                    toaster.show(
                        message = result.toToastMessage(context),
                        type = if (result is ModelTestResult.Success) {
                            ToastType.Success
                        } else {
                            ToastType.Error
                        },
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (running) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                imageVector = HugeIcons.Connect,
                contentDescription = stringResource(R.string.model_list_test_response),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 把测试结果转成一行 toast 文案（成功：首字延迟 [+ 吞吐]；失败：类型 + 原因） */
private fun ModelTestResult.toToastMessage(context: android.content.Context): String = when (this) {
    // 不会走到（结果只在测试结束后上报），保留分支以满足穷尽性
    is ModelTestResult.Running -> context.getString(R.string.model_list_test_running)

    is ModelTestResult.Success -> buildString {
        append(context.getString(R.string.model_list_test_result_ok, firstTokenMs))
        tokensPerSecond?.let { rate ->
            append(" · ")
            append(
                context.getString(
                    R.string.model_list_test_result_speed,
                    String.format("%.1f", rate),
                )
            )
        }
    }

    is ModelTestResult.Failure -> if (type == EMPTY_RESPONSE_TYPE) {
        context.getString(R.string.model_list_test_result_empty)
    } else {
        context.getString(R.string.model_list_test_result_fail, type, message.take(160))
    }
}
