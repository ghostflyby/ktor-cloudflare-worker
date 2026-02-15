@file:OptIn(ExperimentalJsExport::class, ExperimentalJsStatic::class)

package dev.ghostflyby


import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.delay
import web.http.Request
import web.http.Response
import kotlin.time.Duration.Companion.seconds

@JsModule("node:os")
external val os: dynamic

@JsModule("node:fs")
external val fs: dynamic

@JsModule("node:path")
external val path: dynamic

fun fakeRequire(module: String): dynamic {
    when (module) {
        "os" -> return os
        "fs" -> return fs
        "path" -> return path
    }
    throw Error("Cannot require module: $module")
}

fun fakeEval(s: String): dynamic {
    if (s == "require") return ::fakeRequire
    throw Error("Cannot eval: $s")
}

val a = run {
    js("globalThis").eval = ::fakeEval
}

val server = embeddedServer(CFWorker) {
    serverConfig {
        watchPaths = emptyList()
    }
    routing {
        get("/") {
            call.respondText("Hello, World!")
        }
        post("/echo") {
            val receivedText = call.receiveText()
            call.respondText("Echo: $receivedText")
        }
        get("stream") {
            call.respondBytesWriter {
                writeByteArray("Streaming response...".encodeToByteArray())
            }
        }
        get("/delayed") {
            delay(5.seconds)
            call.respondText("This response was delayed by 5 seconds.")
        }
    }
}

var started = false

suspend fun ensuredStarted() {
    if (!started) {
        server.startSuspend(wait = true)
    }
    started = true
}

@JsExport
suspend fun fetch(request: Request): Response {
    ensuredStarted()
    val result = server.engine.handle(request)
    return result
}