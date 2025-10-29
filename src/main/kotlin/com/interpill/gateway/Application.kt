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
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

fun main() {
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
            // --- health ---
            get("/")       { call.respondText("interpill-ai-gateway up") }
            get("/health") { call.respondText("OK") }
            get("/ping")   { call.respondText("pong") }

            // --- helpers ---
            fun allowedToken(): String? =
                System.getenv("AI_PROXY_TOKEN") ?: System.getenv("GATEWAY_API_KEY")

            fun ApplicationCall.bearerToken(): String? =
                request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()

            val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

            // -------- AI summary (mock/placeholder) --------
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
                val _body = call.receiveText()

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

                call.respond(HttpStatusCode.NotImplemented, mapOf("error" to "real mode not wired"))
            }

            // -------- Support: send email via Resend --------
            post("/support/send") {
                // 1) auth by bearer
                val expected = allowedToken()
                if (expected.isNullOrBlank()) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "not configured"))
                    return@post
                }
                if (call.bearerToken() != expected) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorised"))
                    return@post
                }

                // 2) read input
                val bodyText = call.receiveText()
                val inDto = try {
                    json.decodeFromString(SupportIn.serializer(), bodyText)
                } catch (_: Throwable) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad json"))
                    return@post
                }

                if (inDto.from.isBlank() || inDto.message.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "from and message are required"))
                    return@post
                }

                // 3) env for Resend
                val resendKey = System.getenv("RESEND_API_KEY").orEmpty()
                val supportEmail = System.getenv("SUPPORT_EMAIL").orEmpty()
                    .ifBlank { "olga.l.belyaeva@gmail.com" }

                if (resendKey.isBlank()) {
                    // нет ключа → работаем как мок, но сообщаем явно
                    call.respond(
                        HttpStatusCode.Accepted,
                        mapOf("status" to "ok", "note" to "email provider not configured (mock)")
                    )
                    return@post
                }

                // 4) build payload for Resend
                val cleanFrom = inDto.from.trim()
                val safeReplyTo = if (cleanFrom.contains("@")) cleanFrom else "onboarding@resend.dev"

                val payload = ResendEmail(
                    from = "onboarding@resend.dev",      // быстрый старт без домена
                    to = listOf(supportEmail),
                    subject = "Support Interpill",
                    text = buildString {
                        append("From: $cleanFrom\n\n")
                        append(inDto.message.trim())
                    },
                    reply_to = safeReplyTo
                )

                val payloadJson = json.encodeToString(ResendEmail.serializer(), payload)


                // 5) POST to Resend
                val ok = try {
                    val url = URL("https://api.resend.com/emails")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Authorization", "Bearer $resendKey")
                        setRequestProperty("Content-Type", "application/json")
                        doOutput = true
                    }
                    conn.outputStream.use { it.write(payloadJson.toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    code in 200..299
                } catch (_: Throwable) { false }

                if (ok) {
                    call.respond(HttpStatusCode.Accepted, SupportOut(status = "queued"))
                } else {
                    call.respond(HttpStatusCode.BadGateway, mapOf("error" to "resend_failed"))
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

@Serializable
data class SupportIn(
    val from: String,
    val message: String
)

@Serializable
data class SupportOut(val status: String)

@Serializable
private data class ResendEmail(
    val from: String,
    val to: List<String>,
    val subject: String,
    val text: String,
    val reply_to: String? = null
)
