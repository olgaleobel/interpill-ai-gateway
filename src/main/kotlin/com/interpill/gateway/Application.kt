package com.interpill.gateway

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*       // <-- нужно для call.receiveText()
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

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

            // helpers
            fun allowedToken(): String? =
                System.getenv("AI_PROXY_TOKEN") ?: System.getenv("GATEWAY_API_KEY")

            fun ApplicationCall.bearerToken(): String? =
                request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()

            // GET /ai/summary?mock=1 — для быстрой проверки (требует токен)
            get("/debug/env") {
                val a = System.getenv("AI_PROXY_TOKEN")
                val g = System.getenv("GATEWAY_API_KEY")
                call.respond(mapOf("AI_PROXY_TOKEN" to a, "GATEWAY_API_KEY" to g))
            }
            
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
                val _body = call.receiveText() // здесь позже распарсим JSON при необходимости

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
    }.start(wait = true)
}

@Serializable
data class Summary(
    val riskLevel: String,
    val highlights: List<String>,
    val recommendations: List<String>,
    val caveats: List<String>,
    val perDrug: Map<String, String>
)
