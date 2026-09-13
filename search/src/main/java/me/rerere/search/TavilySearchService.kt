package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.util.KeyRoulette
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.SearchService.Companion.json
import me.rerere.search.SearchService.Companion.keyRoulette
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "TavilySearchService"

object TavilySearchService : SearchService<SearchServiceOptions.TavilyOptions> {
    override val name: String = "Tavily"

    @Composable
    override fun Description() {
        val urlHandler = LocalUriHandler.current
        TextButton(
            onClick = {
                urlHandler.openUri("https://app.tavily.com/home")
            }
        ) {
            Text(stringResource(R.string.click_to_get_api_key))
        }
    }

    override fun parameters(options: SearchServiceOptions.TavilyOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
                put("topic", buildJsonObject {
                    put("type", "string")
                    put("description", "search topic (one of `general`, `news`, `finance`)")
                    put("enum", buildJsonArray {
                        add("general")
                        add("news")
                        add("finance")
                    })
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.TavilyOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "url to scrape")
                })
            },
            required = listOf("url")
        )

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.TavilyOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
            val topic = params["topic"]?.jsonPrimitive?.contentOrNull ?: "general"

            // Validate topic
            if (topic !in listOf("general", "news", "finance")) {
                error("topic must be one of `general`, `news`, `finance`")
            }

            val body = buildJsonObject {
                put("query", query)
                put("max_results", commonOptions.resultSize)
                put("search_depth", serviceOptions.depth.ifEmpty { "advanced" })
                put("topic", topic)
                put("include_answer", "advanced")
                put("include_images", true)
            }
            val apiKey = keyRoulette.next(serviceOptions.apiKey, serviceOptions.id.toString())

            val request = Request.Builder()
                .url("https://api.tavily.com/search")
                .post(body.toString().toRequestBody())
                .addHeader("Authorization", "Bearer $apiKey")
                .build()
            val response = httpClient.newCall(request).await()
            if (response.isSuccessful) {
                val response = response.body.string().let {
                    json.decodeFromString<SearchResponse>(it)
                }

                return@withContext Result.success(
                    SearchResult(
                        answer = response.answer,
                        items = response.results.map {
                            SearchResultItem(
                                title = it.title,
                                url = it.url,
                                text = it.content
                            )
                        },
                        images = response.images,
                    ))
            } else {
                error("response failed #${response.code}")
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.TavilyOptions
    ): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        runCatching {
            val url = params["url"]?.jsonPrimitive?.content ?: error("url is required")
            val body = buildJsonObject {
                put("urls", buildJsonArray {
                    add(url)
                })
            }
            val apiKey = keyRoulette.next(serviceOptions.apiKey, serviceOptions.id.toString())
            val request = Request.Builder()
                .url("https://api.tavily.com/extract")
                .post(body.toString().toRequestBody())
                .addHeader("Authorization", "Bearer $apiKey")
                .build()
            val response = httpClient.newCall(request).await()
            if (response.isSuccessful) {
                val response = response.body.string().let {
                    json.decodeFromString<ScrapeResponse>(it)
                }
                return@withContext Result.success(
                    ScrapedResult(
                        urls = response.results.map {
                            ScrapedResultUrl(
                                url = it.url,
                                content = it.rawContent,
                            )
                        }
                    )
                )
            } else {
                error("response failed #${response.code}")
            }
        }
    }

    /**
     * 额度查询: 逐把 Key 请求 GET https://api.tavily.com/usage
     * 单把 Key 失败只影响该项(以 error 回传), 不中断其余 Key
     */
    override suspend fun getUsage(
        serviceOptions: SearchServiceOptions.TavilyOptions
    ): Result<SearchUsage> = withContext(Dispatchers.IO) {
        runCatching {
            val keys = KeyRoulette.split(serviceOptions.apiKey)
            if (keys.isEmpty()) error("API key is empty")
            SearchUsage(items = keys.map { queryKeyUsage(it) })
        }
    }

    private suspend fun queryKeyUsage(apiKey: String): SearchKeyUsage {
        val label = maskApiKey(apiKey)
        return try {
            val request = Request.Builder()
                .url("https://api.tavily.com/usage")
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()
            val response = httpClient.newCall(request).await()
            if (!response.isSuccessful) {
                return SearchKeyUsage(label = label, error = usageErrorMessage(response))
            }
            val usage = json.decodeFromString<UsageResponse>(response.body.string())
            val keyUsage = usage.key
                ?: return SearchKeyUsage(label = label, error = "invalid usage response")
            SearchKeyUsage(
                label = label,
                used = keyUsage.usage,
                limit = keyUsage.limit,
            )
        } catch (e: Exception) {
            SearchKeyUsage(label = label, error = e.message ?: "unknown error")
        }
    }

    @Serializable
    data class SearchResponse(
        val query: String,
        val followUpQuestions: String? = null,
        val answer: String? = null,
        val images: List<String> = emptyList(),
        val results: List<TavilySearchService.SearchResultItem>,
    )

    @Serializable
    data class SearchResultItem(
        val title: String,
        val url: String,
        val content: String,
        val score: Double,
        val rawContent: String? = null
    )

    @Serializable
    data class ScrapeResponse(
        val results: List<ScrapedResultItem>,
    )

    @Serializable
    data class ScrapedResultItem(
        val url: String,
        @SerialName("raw_content")
        val rawContent: String,
    )

    @Serializable
    data class UsageResponse(
        val key: UsageKey? = null,
    )

    @Serializable
    data class UsageKey(
        val usage: Long? = null,
        val limit: Long? = null,
    )
}

/** 掩码 Key, 仅用于额度列表区分每一行(不展示完整密钥) */
private fun maskApiKey(apiKey: String): String =
    if (apiKey.length <= 12) apiKey else "${apiKey.take(8)}…${apiKey.takeLast(4)}"

/** 额度接口失败时尽量回传服务端 detail.error, 否则回传 HTTP 状态码 */
private fun usageErrorMessage(response: okhttp3.Response): String {
    val detail = runCatching {
        SearchService.json.parseToJsonElement(response.body.string())
            .jsonObject["detail"]?.jsonObject?.get("error")?.jsonPrimitive?.content
    }.getOrNull()
    return detail ?: "HTTP ${response.code}"
}
