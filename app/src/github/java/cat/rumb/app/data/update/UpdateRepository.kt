package cat.rumb.app.data.update

import cat.rumb.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class UpdateInfo(
    val version: String,
    val changelog: String,
    val apkUrl: String,
    val sizeBytes: Long,
)

/** Checks GitHub Releases for a newer signed APK than the installed [BuildConfig.VERSION_NAME]. */
class UpdateRepository(
    private val owner: String = "borborborja",
    private val repo: String = "rumb",
    private val api: GitHubApi = GitHubApi.create(),
) {
    /** Returns update details if a newer release exists, null if up to date. Throws on network error. */
    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        val release = api.latestRelease(owner, repo)
        val remote = release.tagName.removePrefix("v").trim()
        if (compareVersions(remote, BuildConfig.VERSION_NAME) <= 0) return@withContext null
        val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
            ?: return@withContext null
        UpdateInfo(
            version = remote,
            changelog = release.body?.takeIf { it.isNotBlank() } ?: release.name ?: "v$remote",
            apkUrl = apk.downloadUrl,
            sizeBytes = apk.size,
        )
    }

    companion object {
        /** Semantic-ish comparison: returns >0 if a>b, 0 if equal, <0 if a<b. Non-numeric parts ignored. */
        fun compareVersions(a: String, b: String): Int {
            val pa = a.split('.', '-').mapNotNull { it.toIntOrNull() }
            val pb = b.split('.', '-').mapNotNull { it.toIntOrNull() }
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val x = pa.getOrElse(i) { 0 }
                val y = pb.getOrElse(i) { 0 }
                if (x != y) return x - y
            }
            return 0
        }
    }
}
