package api

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

private val gson = Gson()
private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

data class HealthResponse(
    val ok: Boolean = false,
    @SerializedName("models_ready") val modelsReady: Boolean = false,
    val missing: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    @SerializedName("cuda_available") val cudaAvailable: Boolean = false,
    @SerializedName("demucs_providers") val demucsProviders: String? = null,
    @SerializedName("whisper_device") val whisperDevice: String? = null,
)

data class LanguageOption(
    val code: String,
    val name: String,
)

data class LanguagesResponse(
    val languages: List<LanguageOption>,
)

data class WorkRunSummary(
    @SerializedName("work_id") val workId: String,
    val title: String,
    val artist: String? = null,
    val language: String? = null,
    @SerializedName("lyrics_ready") val lyricsReady: Boolean = false,
    @SerializedName("has_backing") val hasBacking: Boolean = false,
    @SerializedName("has_video") val hasVideo: Boolean = false,
    @SerializedName("updated_at") val updatedAt: String? = null,
)

data class WorkListResponse(
    val runs: List<WorkRunSummary>,
)

data class WorkRunDetail(
    @SerializedName("work_id") val workId: String,
    val meta: Map<String, Any>? = null,
    @SerializedName("lyrics_text") val lyricsText: String = "",
    val files: Map<String, Boolean>? = null,
)

data class JobCreateResponse(
    @SerializedName("job_id") val jobId: String,
    @SerializedName("work_id") val workId: String? = null,
    val status: String,
)

data class JobStatusResponse(
    @SerializedName("job_id") val jobId: String,
    @SerializedName("work_id") val workId: String? = null,
    val status: String,
    val step: String,
    val message: String,
    @SerializedName("output_path") val outputPath: String? = null,
    val error: String? = null,
)

data class JobEvent(
    @SerializedName("job_id") val jobId: String? = null,
    @SerializedName("work_id") val workId: String? = null,
    val status: String? = null,
    val step: String? = null,
    val message: String? = null,
    @SerializedName("output_path") val outputPath: String? = null,
    @SerializedName("lyrics_text") val lyricsText: String? = null,
    val error: Boolean = false,
)

data class PrepareJobRequest(
    @SerializedName("youtube_url") val youtubeUrl: String,
    @SerializedName("lyrics_text") val lyricsText: String? = null,
    @SerializedName("bg_color") val bgColor: String,
    @SerializedName("output_dir") val outputDir: String? = null,
    @SerializedName("vocal_blend_percent") val vocalBlendPercent: Float = 20f,
    val language: String? = null,
)

data class RenderWorkRequest(
    @SerializedName("lyrics_text") val lyricsText: String,
    @SerializedName("bg_color") val bgColor: String? = null,
    @SerializedName("output_dir") val outputDir: String? = null,
)

data class ResyncLyricsRequest(
    @SerializedName("lyrics_text") val lyricsText: String? = null,
)

class BackendClient(
    private val baseUrl: String,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun health(): HealthResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/health").get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Health check failed: HTTP ${response.code}")
            }
            gson.fromJson(response.body?.string(), HealthResponse::class.java)
        }
    }

    suspend fun getLanguages(): List<LanguageOption> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/languages").get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Languages request failed: HTTP ${response.code}")
            }
            val body = gson.fromJson(response.body?.string(), LanguagesResponse::class.java)
            body.languages
        }
    }

    suspend fun listWork(): List<WorkRunSummary> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/work").get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Work list failed: HTTP ${response.code}")
            }
            val body = gson.fromJson(response.body?.string(), WorkListResponse::class.java)
            body.runs
        }
    }

    suspend fun getWork(workId: String): WorkRunDetail = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/work/$workId").get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Work load failed: HTTP ${response.code}")
            }
            gson.fromJson(response.body?.string(), WorkRunDetail::class.java)
        }
    }

    suspend fun prepareJob(requestBody: PrepareJobRequest): JobCreateResponse = withContext(Dispatchers.IO) {
        val body = gson.toJson(requestBody).toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/jobs/prepare")
            .post(body)
            .build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Prepare job failed: HTTP ${response.code} $raw")
            }
            gson.fromJson(raw, JobCreateResponse::class.java)
        }
    }

    suspend fun renderWork(workId: String, requestBody: RenderWorkRequest): JobCreateResponse =
        withContext(Dispatchers.IO) {
            val body = gson.toJson(requestBody).toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url("$baseUrl/work/$workId/render")
                .post(body)
                .build()
            http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("Render failed: HTTP ${response.code} $raw")
                }
                gson.fromJson(raw, JobCreateResponse::class.java)
            }
        }

    suspend fun resyncLyrics(workId: String, requestBody: ResyncLyricsRequest): JobCreateResponse =
        withContext(Dispatchers.IO) {
            val body = gson.toJson(requestBody).toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url("$baseUrl/work/$workId/resync-lyrics")
                .post(body)
                .build()
            http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("Re-sync failed: HTTP ${response.code} $raw")
                }
                gson.fromJson(raw, JobCreateResponse::class.java)
            }
        }

    suspend fun getJob(jobId: String): JobStatusResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/jobs/$jobId").get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Get job failed: HTTP ${response.code}")
            }
            gson.fromJson(response.body?.string(), JobStatusResponse::class.java)
        }
    }

    fun streamSetupModels(onEvent: (JobEvent) -> Unit, onComplete: (Throwable?) -> Unit): EventSource {
        val body = "{}".toRequestBody(jsonMediaType)
        val request = Request.Builder().url("$baseUrl/setup/models").post(body).build()
        return streamEvents(request, onEvent, onComplete, stopSteps = setOf("complete", "error"))
    }

    fun streamJobEvents(
        jobId: String,
        onEvent: (JobEvent) -> Unit,
        onComplete: (Throwable?) -> Unit,
        stopOnLyricsReady: Boolean = true,
    ): EventSource {
        val request = Request.Builder().url("$baseUrl/jobs/$jobId/events").get().build()
        val stopSteps = buildSet {
            add("complete")
            add("error")
            if (stopOnLyricsReady) add("lyrics_ready")
        }
        return streamEvents(request, onEvent, onComplete, stopSteps)
    }

    private fun streamEvents(
        request: Request,
        onEvent: (JobEvent) -> Unit,
        onComplete: (Throwable?) -> Unit,
        stopSteps: Set<String>,
    ): EventSource {
        val factory = EventSources.createFactory(http)
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        fun completeOnce(error: Throwable?) {
            if (completed.compareAndSet(false, true)) {
                onComplete(error)
            }
        }

        return factory.newEventSource(
            request,
            object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    val event = gson.fromJson(data, JobEvent::class.java)
                    onEvent(event)
                    if (event.step in stopSteps || event.error) {
                        eventSource.cancel()
                        completeOnce(null)
                    }
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                    val error = when {
                        t != null && !t.message.isNullOrBlank() -> t
                        t != null -> IllegalStateException("${t.javaClass.simpleName} (SSE connection lost)")
                        response != null -> IllegalStateException("SSE stream failed: HTTP ${response.code}")
                        else -> IllegalStateException("SSE connection lost")
                    }
                    completeOnce(error)
                }

                override fun onClosed(eventSource: EventSource) {
                    completeOnce(null)
                }
            },
        )
    }
}
