package me.rerere.search

/**
 * 搜索服务额度查询结果(可选能力, 目前仅 Tavily 实现)
 *
 * 口径: [used]/[limit] 为**账户级**(Tavily `account.plan_usage` / `account.plan_limit`),
 * 与 Tavily 官网首页显示一致; [keys] 为逐把 Key 的用量, 仅多 Key 时用于分开展示。
 *
 * @see SearchService.getUsage
 */
data class SearchUsage(
    val used: Long? = null,
    val limit: Long? = null,
    val keys: List<SearchKeyUsage> = emptyList(),
)

/**
 * 单把 API Key 的额度
 *
 * @param label 掩码后的 Key 标识(仅多 Key 场景用于区分每一行)
 * @param used 该 Key 的已用额度(key.usage)
 * @param limit 该 Key 的上限(key.limit); null 表示不限量
 * @param error 该 Key 查询失败的原因(单把失败不影响其余 Key)
 */
data class SearchKeyUsage(
    val label: String,
    val used: Long? = null,
    val limit: Long? = null,
    val error: String? = null,
)
