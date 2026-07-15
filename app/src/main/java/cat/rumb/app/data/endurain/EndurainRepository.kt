package cat.rumb.app.data.endurain

import cat.rumb.app.data.prefs.EndurainPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

sealed interface UploadResult {
    data class Success(val activityIds: List<Long>) : UploadResult
    data class Failure(val code: Int?, val message: String) : UploadResult
    data object NotConfigured : UploadResult
}

/** High-level Endurain operations built on top of [EndurainClient] and [EndurainPreferences]. */
class EndurainRepository(private val prefs: EndurainPreferences) {

    private fun api(): EndurainApi? {
        val host = prefs.host ?: return null
        val key = prefs.apiKey ?: return null
        return EndurainClient.create(host, key)
    }

    /** Validates host + API key by hitting an authenticated endpoint. */
    suspend fun testConnection(): Result<Int> = withContext(Dispatchers.IO) {
        val api = api() ?: return@withContext Result.failure(IllegalStateException("No configurat"))
        runCatching {
            val response = api.activitiesCount()
            if (response.isSuccessful) response.body() ?: 0
            else throw IllegalStateException("HTTP ${response.code()}")
        }
    }

    /** Uploads a GPX document. [gpx] is the raw file text; [fileName] ends in .gpx. */
    suspend fun uploadGpx(gpx: String, fileName: String): UploadResult = withContext(Dispatchers.IO) {
        val api = api() ?: return@withContext UploadResult.NotConfigured
        try {
            // Content type must match the actual format so the server parses TCX laps/FIT correctly
            // (the file can be .tcx, not just .gpx).
            val body = gpx.toByteArray().toRequestBody(
                cat.rumb.app.data.gpx.mimeFor(cat.rumb.app.data.gpx.formatFor(fileName)).toMediaType(),
            )
            val part = MultipartBody.Part.createFormData("file", fileName, body)
            val response = api.uploadActivity(part)
            if (response.isSuccessful) {
                UploadResult.Success(response.body()?.map { it.id } ?: emptyList())
            } else {
                UploadResult.Failure(response.code(), "Error del servidor: HTTP ${response.code()}")
            }
        } catch (e: Exception) {
            UploadResult.Failure(null, e.message ?: "Error de xarxa")
        }
    }

    /** Lists remote activities for the "download planned routes" flow. */
    suspend fun listActivities(page: Int = 1, pageSize: Int = 25): Result<List<EndurainActivity>> =
        withContext(Dispatchers.IO) {
            val api = api() ?: return@withContext Result.failure(IllegalStateException("No configurat"))
            runCatching {
                val response = api.listActivities(page, pageSize)
                if (response.isSuccessful) response.body().orEmpty()
                else throw IllegalStateException("HTTP ${response.code()}")
            }
        }
}
