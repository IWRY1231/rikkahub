package me.rerere.rikkahub.ui.components.ai

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.Connect
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.theme.extendColors
import org.koin.compose.koinInject
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * 模型响应测试：单次流式请求，返回首个可见 token 的延迟与整体吞吐。
 *
 * 设计要点（与设置页 ProviderConnectionTester 的差异）：
 * - 只做**流式**一路：真实聊天走的就是流式，能同时覆盖鉴权/网络/模型可用性/流式解析；
 * - 额外测 **TTFT（首字延迟）**：用户体感最直接的指标；
 * - 提示词要求"一句话"，并用 [TEST_MAX_TOKENS] 限制输出，避免浪费额度；
 * - 不使用工具/推理参数，保持与"裸聊"一致的最小面。
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
 * 状态按 modelId 存放，因此：
 * - 同一模型在"收藏区"和"普通列表"两条入口共享同一份结果（避免重复请求）；
 * - 每个模型的测试互相独立，可并发；
 * - sheet 关闭后状态随 remember 释放（不长期驻留）。
 */
class ModelResponseTester internal constructor(
    private val providerManager: ProviderManager,
    private val scope: CoroutineScope,
) {
    private val results = mutableStateMapOf<Uuid, ModelTestResult>()
    // 并发安全：多个模型的测试可同时进行，且 finally 块在各自协程中执行
    private val jobs = ConcurrentHashMap<Uuid, Job>()

    fun resultOf(modelId: Uuid): ModelTestResult? = results[modelId]

    /** 启动一次测试；已在运行中的模型会被忽略（防连点） */
    internal fun test(
        model: Model,
        providerSetting: ProviderSetting,
    ) {
        val modelId = model.id
        if (jobs[modelId]?.isActive == true) return

        results[modelId] = ModelTestResult.Running
        val startedAt = System.currentTimeMillis()
        jobs[modelId] = scope.launch {
            try {
                val provider = providerManager.getProviderByType(providerSetting)
                var firstTokenAt: Long? = null
                var text = StringBuilder()
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
                        customHeaders = model.customHeaders,
                        customBody = model.customBodies,
                    ),
                ).collect { chunk ->
                    when (chunk) {
                        is StreamChunk.TextDelta -> {
                            if (firstTokenAt == null) firstTokenAt = System.currentTimeMillis()
                            text.append(chunk.text)
                        }

                        is StreamChunk.Usage -> {
                            usageTokens = chunk.usage.completionTokens
                        }

                        else -> Unit
                    }
                }

                val totalMs = System.currentTimeMillis() - startedAt
                val sample = text.toString().trim()
                if (firstTokenAt == null && sample.isEmpty()) {
                    // 流正常结束但没有任何文本：连接没问题，但模型没产出内容。
                    // 文案交给 UI 层本地化（这里只标记类型）
                    results[modelId] = ModelTestResult.Failure(
                        type = EMPTY_RESPONSE_TYPE,
                        message = "",
                    )
                    return@launch
                }
                val estimatedTokens = if (usageTokens > 0) {
                    usageTokens
                } else {
                    // 粗略估算：4 字符 ≈ 1 token（中英混排下的常用近似）
                    (sample.length / 4).coerceAtLeast(1)
                }
                val elapsedForRate = (totalMs - (firstTokenAt ?: startedAt)).coerceAtLeast(1L)
                results[modelId] = ModelTestResult.Success(
                    firstTokenMs = (firstTokenAt ?: startedAt) - startedAt,
                    totalMs = totalMs,
                    completionTokens = estimatedTokens,
                    tokensPerSecond = if (estimatedTokens >= 2 && elapsedForRate >= 50) {
                        estimatedTokens.toDouble() / (elapsedForRate / 1000.0)
                    } else {
                        null
                    },
                    sample = sample,
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                results[modelId] = ModelTestResult.Failure(
                    type = error.javaClass.simpleName,
                    message = error.message ?: error.toString(),
                )
            } finally {
                jobs.remove(modelId)
            }
        }
    }

    internal fun dismiss(modelId: Uuid) {
        jobs.remove(modelId)?.cancel()
        results.remove(modelId)
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
    val scope = rememberCoroutineScope()
    return remember(providerManager, scope) { ModelResponseTester(providerManager, scope) }
}

/**
 * 模型项右侧的「测试响应」按钮（放在收藏按钮右边）。
 *
 * 紧凑样式：用 40dp 触控目标 + 18dp 图标，与相邻收藏按钮的视觉节奏一致；
 * 运行中显示进度指示器并禁用点击（防连点浪费额度）。
 */
@Composable
fun ModelTestButton(
    model: Model,
    providerSetting: ProviderSetting,
    tester: ModelResponseTester,
    modifier: Modifier = Modifier,
) {
    val running = tester.resultOf(model.id) is ModelTestResult.Running
    val context = LocalContext.current

    IconButton(
        onClick = {
            tester.test(model, providerSetting)
            Toast.makeText(
                context,
                context.getString(R.string.model_list_test_running),
                Toast.LENGTH_SHORT,
            ).show()
        },
        enabled = !running,
        modifier = modifier.size(40.dp),
    ) {
        if (running) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
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

/**
 * 测试结果行（就地展开在模型项下方）。
 *
 * 成功：绿色 · 首字延迟 · 吞吐 · 回复片段（片段可确认"模型真的回了话"）
 * 失败：红色 · 异常类型 + 消息；点右侧叉号清除结果
 */
@Composable
fun ModelTestResultRow(
    model: Model,
    tester: ModelResponseTester,
    modifier: Modifier = Modifier,
) {
    val result = tester.resultOf(model.id) ?: return
    val context = LocalContext.current
    val sample = (result as? ModelTestResult.Success)?.sample?.replace("\n", " ")

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (result) {
            is ModelTestResult.Running -> {
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                Text(
                    text = stringResource(R.string.model_list_test_running),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is ModelTestResult.Success -> {
                Icon(
                    imageVector = HugeIcons.CheckmarkCircle02,
                    contentDescription = stringResource(R.string.model_list_test_ok_title),
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.extendColors.green6,
                )
                Text(
                    text = buildString {
                        append(
                            context.getString(
                                R.string.model_list_test_result_ok,
                                result.firstTokenMs,
                            )
                        )
                        result.tokensPerSecond?.let { rate ->
                            append(" · ")
                            append(
                                context.getString(
                                    R.string.model_list_test_result_speed,
                                    String.format("%.1f", rate),
                                )
                            )
                        }
                        sample?.takeIf { it.isNotBlank() }?.let { text ->
                            append(" · ")
                            append(text.take(40))
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.extendColors.green6,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            is ModelTestResult.Failure -> {
                Icon(
                    imageVector = HugeIcons.Cancel01,
                    contentDescription = stringResource(R.string.model_list_test_failed_title),
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.extendColors.red6,
                )
                Text(
                    text = if (result.type == EMPTY_RESPONSE_TYPE) {
                        context.getString(R.string.model_list_test_result_empty)
                    } else {
                        context.getString(
                            R.string.model_list_test_result_fail,
                            result.type,
                            result.message.take(120),
                        )
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.extendColors.red6,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // 清除结果（成功/失败都允许），避免结果长期占位
        if (result !is ModelTestResult.Running) {
            IconButton(
                onClick = { tester.dismiss(model.id) },
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Cancel01,
                    contentDescription = stringResource(R.string.model_list_test_dismiss),
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

