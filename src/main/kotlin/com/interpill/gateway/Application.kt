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
import kotlinx.serialization.json.JsonElement
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

            val json = Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                prettyPrint = false
            }

            // -------- AI summary (mock GET) --------
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

            // -------- AI summary (real POST entrypoint, с graceful errors) --------
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
                val raw = runCatching { call.receiveText() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "empty body"))
                    return@post
                }

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

                // Здесь у вас может быть реальный вызов Gemini/Vertex AI
                // Ниже — заглушка, показывающая, как маппить 429 → 503 с дружелюбным текстом.
                // Замените блок try { … } на ваш настоящий HTTP-клиент к Gemini.
                val upstreamResult: Result<Summary> = runCatching {
                    // TODO: call Gemini here and parse JSON → Summary
                    // временно вернём 503-like поведение по ключевому слову "simulate429"
                    if (raw.contains("simulate429", ignoreCase = true)) {
                        error("UPSTREAM_429")
                    }
                    // демо-ответ
                    Summary(
                        riskLevel = "low",
                        highlights = listOf("No clinically meaningful interactions detected."),
                        recommendations = listOf("Use as directed."),
                        caveats = emptyList(),
                        perDrug = mapOf("paracetamol" to "low")
                    )
                }

                upstreamResult.onSuccess { summary ->
                    call.respond(summary)
                }.onFailure { t ->
                    val msg = t.message.orEmpty()
                    when {
                        msg.contains("UPSTREAM_429") ||
                        msg.contains("RESOURCE_EXHAUSTED", true) -> {
                            call.respond(
                                HttpStatusCode.ServiceUnavailable,
                                mapOf(
                                    "error" to "AI service temporarily busy",
                                    "note" to "The AI system is currently overloaded. Please try again later."
                                )
                            )
                        }
                        msg.contains("timeout", true) -> {
                            call.respond(
                                HttpStatusCode.GatewayTimeout,
                                mapOf("error" to "AI request timeout", "note" to "Please retry in a moment.")
                            )
                        }
                        msg.contains("UNAUTH", true) || msg.contains("401") -> {
                            call.respond(HttpStatusCode.BadGateway, mapOf("error" to "upstream unauthorised"))
                        }
                        else -> {
                            call.respond(
                                HttpStatusCode.BadGateway,
                                mapOf("error" to "upstream failure", "details" to (msg.take(200)))
                            )
                        }
                    }
                }
            }

            // -------- Support: send email via Resend --------
            post("/support/send") {
                val expected = allowedToken()
                if (expected.isNullOrBlank()) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "not configured"))
                    return@post
                }
                if (call.bearerToken() != expected) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorised"))
                    return@post
                }

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

                val resendKey = System.getenv("RESEND_API_KEY").orEmpty()
                val supportEmail = System.getenv("SUPPORT_EMAIL").orEmpty()
                    .ifBlank { "olga.l.belyaeva@gmail.com" }

                if (resendKey.isBlank()) {
                    call.respond(
                        HttpStatusCode.Accepted,
                        mapOf("status" to "ok", "note" to "email provider not configured (mock)")
                    )
                    return@post
                }

                val cleanFrom = inDto.from.trim()
                val emailRegex = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$".toRegex()
                if (!emailRegex.matches(cleanFrom)) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid email format"))
                    return@post
                }

                val payload = ResendEmail(
                    from = "onboarding@resend.dev",
                    to = listOf(supportEmail),
                    subject = "Support Interpill",
                    text = buildString {
                        append("From: $cleanFrom\n\n")
                        append(inDto.message.trim())
                    },
                    reply_to = cleanFrom
                )
                val payloadJson = json.encodeToString(ResendEmail.serializer(), payload)

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
data class SummaryIn(
    val meds: List<String>,
    val profile: JsonElement? = null,
    val patientNotes: List<String> = emptyList()
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
