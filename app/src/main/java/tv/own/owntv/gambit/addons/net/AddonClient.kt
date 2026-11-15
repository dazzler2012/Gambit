package tv.own.owntv.gambit.addons.net

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import tv.own.owntv.gambit.addons.model.AddonManifest
import tv.own.owntv.gambit.addons.model.AddonStream
import tv.own.owntv.gambit.addons.model.MetaDetail
import tv.own.owntv.gambit.addons.model.MetaPreview

/**
 * GETs against one Stremio addon. Every call returns an [AddonResult]; only coroutine cancellation is
 * thrown (and it cancels the HTTP call). Nothing here logs: request URLs can hold debrid keys.
 *
 * [source] on each call is how errors name the addon: never the URL, only its host and, once the
 * manifest is known, its name (pass InstalledAddon.source). It defaults to the host alone.
 *
 * @param baseClient the app's shared client (connection pool, User-Agent); a copy with [timeoutMs] as
 *   the whole-call limit is used, so a slow addon can't hold up the others.
 */
class AddonClient(baseClient: OkHttpClient, timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
    private val http = baseClient.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()

    suspend fun manifest(url: AddonUrl, source: AddonSource = AddonSource(null, url.host)): AddonResult<AddonManifest> =
        get(url.manifestUrl, source).flatMap { AddonJson.manifest(it, source) }

    suspend fun catalog(
        url: AddonUrl,
        type: String,
        catalogId: String,
        extra: CatalogExtraArgs = CatalogExtraArgs(),
        source: AddonSource = AddonSource(null, url.host),
    ): AddonResult<List<MetaPreview>> =
        get(url.catalogUrl(type, catalogId, extra), source).flatMap { AddonJson.catalog(it, source, type) }

    suspend fun meta(url: AddonUrl, type: String, id: String, source: AddonSource = AddonSource(null, url.host)): AddonResult<MetaDetail> =
        get(url.metaUrl(type, id), source).flatMap { AddonJson.meta(it, source, type) }

    suspend fun streams(url: AddonUrl, type: String, videoId: String, source: AddonSource = AddonSource(null, url.host)): AddonResult<List<AddonStream>> =
        get(url.streamUrl(type, videoId), source).flatMap { AddonJson.streams(it, source) }

    private suspend fun get(requestUrl: String, source: AddonSource): AddonResult<String> {
        val request = try {
            Request.Builder().url(requestUrl).header("Accept", "application/json").build()
        } catch (_: IllegalArgumentException) {
            return AddonResult.Err(AddonError.InvalidUrl)
        }
        val call = http.newCall(request)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resume(AddonResult.Err(failure(e, source)))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use {
                            if (!it.isSuccessful) AddonResult.Err(AddonError.HttpStatus(source, it.code))
                            else AddonResult.Ok(it.body.string())
                        }
                    } catch (e: IOException) {
                        AddonResult.Err(failure(e, source))
                    }
                    if (cont.isActive) cont.resume(result)
                }
            })
        }
    }

    /** Exception messages can quote the URL, so only the exception type is kept. */
    private fun failure(e: IOException, source: AddonSource): AddonError =
        if (e is InterruptedIOException) AddonError.Timeout(source)
        else AddonError.Network(source, e.javaClass.simpleName)

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L
    }
}

private inline fun <T, R> AddonResult<T>.flatMap(transform: (T) -> AddonResult<R>): AddonResult<R> = when (this) {
    is AddonResult.Ok -> transform(value)
    is AddonResult.Err -> this
}
