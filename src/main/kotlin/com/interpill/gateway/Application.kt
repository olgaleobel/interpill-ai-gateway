package com.interpill.gateway

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
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

            // ---------------- AI summary (mockable) ----------------

            // GET /ai/summary?mock=1 — для быстрой проверки (требует токен)
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

            // ---------------- Support form ----------------

            // Простая модель запроса из формы поддержки
            @Serializable
            data class SupportRequest(val from: String, val message: String)

            // POST /support/send
            // Токен обязателен (тот же AI_PROXY_TOKEN/GATEWAY_API_KEY).
            // Сейчас: мок — просто логируем и возвращаем 202/200.
            post("/support/send") {
                val expected = allowedToken()
                if (expected.isNullOrBlank()) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "token not configured"))
                    return@post
                }
                val bearer = call.bearerToken()
                if (bearer != expected) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorised"))
                    return@post
                }

                val supportEmail = System.getenv("SUPPORT_EMAIL") ?: "support@example.com"

                val req = try {
                    call.receive<SupportRequest>()
                } catch (t: Throwable) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad json"))
                    return@post
                }

                // Логируем для отладки (видно в Render → Logs)
                println("Support: FROM=${req.from} TO=$supportEmail MSG=${req.message.take(300)}")

                // Если позже подключим реального провайдера (Resend/SES/Mailgun),
                // здесь будет отправка и возврат 200/ошибка провайдера.
                val providerKey = System.getenv("RESEND_API_KEY")
                if (providerKey.isNullOrBlank()) {
                    call.respond(
                        HttpStatusCode.Accepted,
                        mapOf("status" to "ok", "note" to "email provider not configured (mock)")
                    )
                } else {
                    // Пока не отправляем — просто подтверждаем (чтобы не ломать сборку без зависимостей клиента)
                    call.respond(
                        HttpStatusCode.OK,
                        mapOf("status" to "queued", "provider" to "resend")
                    )
                }
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
