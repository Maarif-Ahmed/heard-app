package com.maxicon.heard.network

import android.content.Context
import com.maxicon.heard.model.CaptionFrame
import com.maxicon.heard.model.FrameType
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CaptionWebServer(
    private val context: Context,
    val listenPort: Int = 8765
) : NanoWSD(listenPort) {
    private val sockets = CopyOnWriteArraySet<ClientSocket>()
    private val lock = Any()
    private val finalizedLines = ArrayDeque<String>()
    private var partialLine: String = ""
    private var latestStatus: String = "Ready"
    private var latestLanguageTag: String = "en-US"
    private var latestSettingsJson: String? = null
    private var started = false
    private val json = Json { encodeDefaults = true }

    /** Set by the ViewModel before starting the server. WebSocket connections must supply this. */
    var sessionToken: String? = null

    fun startIfNeeded() {
        if (started) {
            return
        }
        start(SOCKET_READ_TIMEOUT, false)
        started = true
    }

    fun stopServer() {
        if (!started) {
            return
        }
        stop()
        started = false
        sockets.forEach { socket ->
            runCatching {
                socket.close(WebSocketFrame.CloseCode.NormalClosure, "Server stopped", false)
            }
        }
        sockets.clear()
    }

    fun resetSession(
        status: String = "Ready",
        languageTag: String = latestLanguageTag
    ) {
        synchronized(lock) {
            finalizedLines.clear()
            partialLine = ""
            latestStatus = status
            if (languageTag.isNotBlank()) {
                latestLanguageTag = languageTag
            }
        }
    }

    fun publishSettings(frame: CaptionFrame) {
        val payload = json.encodeToString(frame)
        latestSettingsJson = payload
        sockets.forEach { socket ->
            runCatching { socket.send(payload) }
                .onFailure { sockets.remove(socket) }
        }
    }

    fun publish(frame: CaptionFrame) {
        val outbound = synchronized(lock) {
            if (frame.status.isNotBlank()) {
                latestStatus = frame.status
            }
            if (frame.languageTag.isNotBlank()) {
                latestLanguageTag = frame.languageTag
            }

            when (frame.type) {
                FrameType.PARTIAL -> {
                    partialLine = frame.text
                }

                FrameType.FINAL -> {
                    partialLine = ""
                    if (frame.text.isNotBlank()) {
                        finalizedLines.addLast(frame.text)
                        while (finalizedLines.size > 8) {
                            finalizedLines.removeFirst()
                        }
                    }
                }

                else -> Unit
            }

            frame.copy(
                text = if (frame.type == FrameType.PARTIAL) partialLine else frame.text,
                status = if (frame.status.isNotBlank()) frame.status else latestStatus,
                languageTag = if (frame.languageTag.isNotBlank()) frame.languageTag else latestLanguageTag,
                finalizedLines = if (frame.type == FrameType.PARTIAL) {
                    emptyList()
                } else {
                    finalizedLines.toList()
                }
            )
        }

        val payload = json.encodeToString(outbound)
        sockets.forEach { socket ->
            runCatching { socket.send(payload) }
                .onFailure { sockets.remove(socket) }
        }
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        if (handshake.uri != "/ws") return RejectSocket(handshake)
        val token = sessionToken
        if (token != null) {
            val supplied = handshake.parameters["t"]?.firstOrNull()
            if (supplied.isNullOrBlank() || supplied != token) return RejectSocket(handshake)
        }
        return ClientSocket(handshake)
    }

    override fun serveHttp(session: IHTTPSession): Response {
        val path = session.uri.removePrefix("/").ifBlank { "index.html" }
        return when (path) {
            "index.html" -> assetResponse("web/index.html", "text/html; charset=utf-8")
            "styles.css" -> assetResponse("web/styles.css", "text/css; charset=utf-8")
            "app.js" -> assetResponse("web/app.js", "application/javascript; charset=utf-8")
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "Not found")
        }
    }

    private fun assetResponse(path: String, mimeType: String): Response {
        return try {
            val stream = context.assets.open(path)
            newChunkedResponse(Response.Status.OK, mimeType, stream).apply {
                addHeader("Cache-Control", "no-store")
            }
        } catch (_: IOException) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "Asset not found")
        }
    }

    private fun snapshotFrame(): CaptionFrame {
        return synchronized(lock) {
            CaptionFrame(
                type = FrameType.SNAPSHOT,
                text = partialLine,
                finalizedLines = finalizedLines.toList(),
                status = latestStatus,
                languageTag = latestLanguageTag
            )
        }
    }

    private inner class ClientSocket(handshakeRequest: IHTTPSession) : WebSocket(handshakeRequest) {
        override fun onOpen() {
            sockets.add(this)
            runCatching {
                send(json.encodeToString(snapshotFrame()))
            }
            latestSettingsJson?.let { settingsJson ->
                runCatching { send(settingsJson) }
            }
        }

        override fun onClose(
            code: WebSocketFrame.CloseCode?,
            reason: String?,
            initiatedByRemote: Boolean
        ) {
            sockets.remove(this)
        }

        override fun onMessage(message: WebSocketFrame?) = Unit

        override fun onPong(pong: WebSocketFrame?) = Unit

        override fun onException(exception: IOException?) {
            sockets.remove(this)
        }
    }

    private inner class RejectSocket(handshakeRequest: IHTTPSession) : WebSocket(handshakeRequest) {
        override fun onOpen() {
            close(WebSocketFrame.CloseCode.PolicyViolation, "Use /ws", false)
        }

        override fun onClose(
            code: WebSocketFrame.CloseCode?,
            reason: String?,
            initiatedByRemote: Boolean
        ) = Unit

        override fun onMessage(message: WebSocketFrame?) = Unit

        override fun onPong(pong: WebSocketFrame?) = Unit

        override fun onException(exception: IOException?) = Unit
    }
}
