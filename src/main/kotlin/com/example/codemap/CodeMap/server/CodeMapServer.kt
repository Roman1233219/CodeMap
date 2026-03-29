package com.example.codemap.CodeMap.server

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.io.File
import java.nio.charset.StandardCharsets

class CodeMapServer(private val projectPath: String) {
    private var server: HttpServer? = null

    fun start(port: Int = 8080) {
        if (server != null) return
        try {
            server = HttpServer.create(InetSocketAddress(port), 0)
            server?.createContext("/") { exchange ->
                val path = exchange.requestURI.path
                val (response, contentType) = when {
                    path == "/" || path == "/index.html" -> {
                        val html = javaClass.getResourceAsStream("/webapp/index.html")?.bufferedReader()?.readText() ?: "HTML Not Found"
                        html to "text/html"
                    }
                    path == "/visualization.js" -> {
                        val js = javaClass.getResourceAsStream("/webapp/visualization.js")?.bufferedReader()?.readText() ?: ""
                        js to "application/javascript"
                    }
                    path == "/system_functions.json" -> {
                        val json = javaClass.getResourceAsStream("/webapp/system_functions.json")?.bufferedReader()?.readText() ?: "{}"
                        json to "application/json"
                    }
                    path == "/data.json" -> {
                        val file = File("$projectPath/codemap_data.json")
                        val json = if (file.exists()) file.readText(StandardCharsets.UTF_8) else "{}"
                        json to "application/json"
                    }
                    else -> "404 Not Found" to "text/plain"
                }

                val bytes = response.toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "$contentType; charset=utf-8")
                // Добавляем CORS, чтобы JS мог грузить данные, если запуск идет не через сервер (на всякий случай)
                exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server?.executor = null
            server?.start()
            println("CodeMap Server started on port ${port}")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stop() {
        server?.stop(0)
        server = null
    }
}
