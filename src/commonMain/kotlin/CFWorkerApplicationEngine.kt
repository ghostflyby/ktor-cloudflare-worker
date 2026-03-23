@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.ghostflyby

import dev.ghostflyby.cloudflare.workers.waitUntil
import io.ktor.events.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.util.pipeline.*
import io.ktor.utils.io.*
import io.ktor.utils.io.pool.*
import js.buffer.ArrayBuffer
import js.buffer.ArrayBufferLike
import js.coroutines.asPromise
import js.coroutines.promise
import js.iterable.toList
import js.typedarrays.Uint8Array
import js.typedarrays.toByteArray
import js.typedarrays.toUint8Array
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import web.abort.asCoroutineScope
import web.http.BodyInit
import web.http.Request
import web.http.Response
import web.http.ResponseInit
import web.streams.ReadableStream
import web.streams.ReadableStreamDefaultController
import web.streams.UnderlyingDefaultSource
import web.streams.read

class CFWorkerApplicationEngine(
    environment: ApplicationEnvironment,
    monitor: Events, developmentMode: Boolean,
    val configuration: Configuration,
    private val applicationProvider: () -> Application
) : BaseApplicationEngine(environment, monitor, developmentMode) {

    class Configuration : BaseApplicationEngine.Configuration()

    private val startGate = CompletableDeferred<Unit>()
    private var started = false

    private lateinit var application: Application

    override fun start(wait: Boolean): ApplicationEngine {
        started = true
        application = applicationProvider()
        startGate.complete(Unit)
        return this
    }

    suspend fun handle(request: Request): Response {
        startGate.await()
        if (!started) {
            throw IllegalStateException("CFWorkerApplicationEngine is not started")
        }
        val deferred = CompletableDeferred<Response>()
        val scope = request.asCoroutineScope()
        scope.launch {
            try {
                pipeline.execute(
                    CFWorkerCall(
                        request,
                        deferred,
                        application,
                        scope,
                    )
                )
            } catch (t: Throwable) {
                if (!deferred.isCompleted) {
                    val headers = web.http.Headers()
                    val body = BodyInit("Internal Server Error: ${t.message}")
                    deferred.complete(Response(body, ResponseInit(headers, 500, "Internal Server Error")))
                }
                throw t
            }
        }
        return deferred.await()
    }

    override fun stop(gracePeriodMillis: Long, timeoutMillis: Long) {

    }

}

fun <T : ArrayBufferLike> ReadableStream<Uint8Array<T>>.toByteChannel(scope: CoroutineScope): ByteChannel {
    val channel = ByteChannel(autoFlush = true)
    val reader = getReader()
    scope.launch {
        try {
            while (true) {
                val result = reader.read()
                if (result.done) break
                val bytes = result.value?.toByteArray() ?: break
                channel.writeByteArray(bytes)
            }
            channel.close()
        } catch (t: Throwable) {
            channel.close(t)
        } finally {
            try {
                reader.releaseLock()
            } catch (_: Throwable) {
            }
        }
    }

    return channel
}

private fun ByteReadChannel.toReadableStream(scope: CoroutineScope): ReadableStream<Uint8Array<ArrayBuffer>> {
    return ReadableStream(
        UnderlyingDefaultSource(
            start = { _: ReadableStreamDefaultController<Uint8Array<ArrayBuffer>> ->
                // Unit
            },
            pull = { c: ReadableStreamDefaultController<Uint8Array<ArrayBuffer>> ->
                scope.promise {
                    val buffer = ByteArrayPool.borrow()
                    try {
                        val n = readAvailable(buffer)
                        if (n <= 0) {
                            c.close()
                            return@promise
                        }

                        val src = buffer.sliceArray(0 until n)

                        c.enqueue(src.toUint8Array())
                    } finally {
                        ByteArrayPool.recycle(buffer)
                    }
                }.asDynamic()
            },
            cancel = { reason: Any? ->
                scope.promise {
                    val cause = when (reason) {
                        is Throwable -> reason
                        null -> CancellationException("ReadableStream cancelled")
                        else -> CancellationException(reason.toString())
                    }
                    cancel(cause)
                }.asDynamic()
            }
        )
    )
}


internal class CFRequest(
    call: CFWorkerCall,
    private val request: Request
) : BaseApplicationRequest(call) {
    override val engineHeaders: Headers by lazy {
        Headers.build {
            // JS Headers.forEach provides (value, key); order matters.
            request.headers.forEach { value, key ->
                this.append(key, value)
            }
        }
    }
    override val engineReceiveChannel: ByteReadChannel =
        request.body?.toByteChannel(call.scope) ?: ByteReadChannel.Empty

    override val cookies: RequestCookies by lazy {
        RequestCookies(this)
    }

    private val url = Url(request.url)


    override val local: RequestConnectionPoint by lazy {
        object : RequestConnectionPoint {
            override val scheme: String
                get() = url.protocol.name
            override val version: String by lazy {
                request.asDynamic().cf?.httpVersion
            }


            @Deprecated("Use localPort or serverPort instead", level = DeprecationLevel.ERROR)
            override val port: Int
                get() = url.port
            override val localPort: Int
                get() = url.port
            override val serverPort: Int
                get() = url.port

            @Deprecated("Use localHost or serverHost instead", level = DeprecationLevel.ERROR)
            override val host: String
                get() = url.host
            override val localHost: String
                get() = url.host
            override val serverHost: String
                get() = url.host
            override val localAddress: String = ""

            override val uri: String
                get() = url.fullPath

            override val method: HttpMethod by lazy {
                HttpMethod.parse(request.method.toString())
            }

            override val remoteHost: String get() = remoteAddress
            override val remotePort: Int = 0
            override val remoteAddress: String = request.headers.get("CF-Connecting-IP") ?: ""

        }
    }

    override val queryParameters: Parameters = url.parameters

    override val rawQueryParameters: Parameters = url.parameters

}

internal class CFResponse(call: CFWorkerCall, private val response: CompletableDeferred<Response>) :
    BaseApplicationResponse(call) {
    override suspend fun respondUpgrade(upgrade: OutgoingContent.ProtocolUpgrade) {
    }

    private val scope: CoroutineScope = call.scope

    override suspend fun responseChannel(): ByteWriteChannel {
        val channel = ByteChannel(autoFlush = true)
        val stream = channel.toReadableStream(scope)
        val deferred = CompletableDeferred<Unit>()
        waitUntil(deferred.asPromise())
        val response = Response(stream, responseInit())
        this.response.complete(response)
        return channel
    }

    override suspend fun respondOutgoingContent(content: OutgoingContent) {
        val response = content.toResponse()
        if (response != null) {
            this.response.complete(response)
            return
        }
        return super.respondOutgoingContent(content)
    }

    fun responseInit(): ResponseInit {
        val status = status() ?: HttpStatusCode.OK
        val code = status.value.toShort()
        val des = status.description
        return ResponseInit(rawHeaders, code, des)
    }


    private fun OutgoingContent.toResponse(): Response? {
        fun responseInit(): ResponseInit {
            commitHeaders(this@toResponse)
            return this@CFResponse.responseInit()
        }

        fun BodyInit.response(): Response {
            return Response(this, responseInit())
        }
        return when (this) {
            is ByteArrayContent -> BodyInit(bytes().toUint8Array()).response()
            is TextContent -> BodyInit(text).response()
            is OutgoingContent.NoContent -> Response(init = responseInit())
            else -> null
        }
    }

    override fun setStatus(statusCode: HttpStatusCode) {
    }

    private val rawHeaders = web.http.Headers()

    override val headers: ResponseHeaders = object : ResponseHeaders() {
        override fun engineAppendHeader(name: String, value: String) {
            rawHeaders.append(name, value)
        }

        override fun getEngineHeaderNames(): List<String> {
            return rawHeaders.keys().toList()
        }

        override fun getEngineHeaderValues(name: String): List<String> {
            return rawHeaders.values().toList()
        }

    }

}

internal class CFWorkerCall(
    jsRequest: Request,
    jsResponse: CompletableDeferred<Response>,
    application: Application,
    val scope: CoroutineScope,
) : BaseApplicationCall(application) {
    override val request = CFRequest(this, jsRequest)
    override val response = CFResponse(this, jsResponse)

    init {
        putResponseAttribute()
    }

    override val parameters: Parameters = request.queryParameters

    override val coroutineContext get() = scope.coroutineContext

}

object CFWorker :
    ApplicationEngineFactory<CFWorkerApplicationEngine, CFWorkerApplicationEngine.Configuration> {
    override fun configuration(configure: CFWorkerApplicationEngine.Configuration.() -> Unit): CFWorkerApplicationEngine.Configuration {
        return CFWorkerApplicationEngine.Configuration().apply(configure)
    }

    override fun create(
        environment: ApplicationEnvironment,
        monitor: Events,
        developmentMode: Boolean,
        configuration: CFWorkerApplicationEngine.Configuration,
        applicationProvider: () -> Application
    ): CFWorkerApplicationEngine {
        return CFWorkerApplicationEngine(environment, monitor, developmentMode, configuration, applicationProvider)
    }
}
