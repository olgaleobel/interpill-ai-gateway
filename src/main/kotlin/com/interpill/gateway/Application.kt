package com.interpill.gateway

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import io.ktor.http.*

fun main() {
    // Позволяет Render/докеру задавать порт через переменную окружения PORT
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        install(ContentNegotiation) { json() }
        install(CORS) {
            anyHost()
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.Authorization)
        }

        routing {
    // health
    get("/")       { call.respondText("interpill-ai-gateway up") }
    get("/health") { call.respondText("OK") }
    get("/ping")   { call.respondText("pong") }

    // helper: достать токен из заголовка и env
    fun allowedToken(): String? =
        System.getenv("AI_PROXY_TOKEN") ?: System.getenv("GATEWAY_API_KEY")

    fun ApplicationCall.bearerToken(): String? =
        request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()

    // GET /ai/summary?mock=1 — для быстрой проверки, тоже требует токен
    get("/ai/summary") {
        val expected = allowedToken()
        if (expected.isNullOrBlank()) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "not configured"))
            return@get
        }
        if (call.bearerToken() != expected) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorised"))
            return@get
        }

        val mock = call.request.queryParameters["mock"] == "1"
        if (mock) {
            call.respond(
                Summary(
                    riskLevel = "low",
                    highlights = emptyList(),
                    recommendations = emptyList(),
                    caveats = emptyList(),
                    perDrug = mapOf("paracetamol" to "low")
                )
            )
        } else {
            call.respond(HttpStatusCode.NotImplemented, mapOf("error" to "use POST for real mode"))
        }
    }

    // POST /ai/summary (поддерживает ?mock=1)
    post("/ai/summary") {
        val expected = allowedToken()
        if (expected.isNullOrBlank()) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "not configured"))
            return@post
        }
        if (call.bearerToken() != expected) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorised"))
            return@post
        }

        val mock = call.request.queryParameters["mock"] == "1"
        val _body = call.receiveText() // при желании распарсишь JSON позже

        if (mock) {
            call.respond(
                Summary(
                    riskLevel = "low",
                    highlights = emptyList(),
                    recommendations = emptyList(),
                    caveats = emptyList(),
                    perDrug = mapOf("paracetamol" to "low")
                )
            )
            return@post
        }

        // TODO: реальный вызов Gemini через System.getenv("GEMINI_API_KEY")
        call.respond(HttpStatusCode.NotImplemented, mapOf("error" to "real mode not wired"))
    }
}

    
