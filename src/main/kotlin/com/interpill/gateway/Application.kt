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
import kotlinx.serialization.encodeToString
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

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

            // --- Mock AI summary for quick checks ---
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

            // ================== SUPPORT: real email sending via Resend ==================
            post("/support/send") {
                // 1) Auth
                val expected = allowedToken()
                if (expected.isNullOrBlank()) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "not configured"))
                    return@post
                }
                if (call.bearerToken() != expected) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorised"))
                    return@post
                }

                // 2) Parse input
                val req = runCatching { call.receive<SupportRequest>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad json"))
                    return@post
                }
                val fromEmail = req.from?.trim().orEmpty()
                val message = req.message?.trim().orEmpty()
                if (fromEmail.isEmpty() || message.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "fields 'from' and 'message' are required"))
                    return@post
                }

                // 3) Env & fallbacks
                val resendKey = System.getenv("RESEND_API_KEY").orEmpty()
                val supportTo = System.getenv("SUPPORT_EMAIL").orEmpty()
                val supportFrom = System.getenv("SUPPORT_FROM").orEmpty().ifBlank { "onboarding@resend.dev" }
                if (resendKey.isBlank() || supportTo.isBlank()) {
                    // No provider configured => keep mock behaviour, but with 200 to not confuse UI
                    call.respond(HttpStatusCode.OK, mapOf("status" to "ok", "note" to "email provider not configured (mock)"))
                    return@post
                }

                // 4) Build payload for Resend
                val subject = "Support Interpill"
                val textBody = buildString {
                    appendLine("From: $fromEmail")
                    appendLine()
                    appendLine(message)
                }
                val payload = ResendEmail(
                    from = supportFrom,
                    to = listOf(supportTo),
                    subject = subject,
                    text = textBody
                )
                val json = Json { ignoreUnknownKeys = true }
                val body = json.encodeToString(payload)

                // 5) POST to Resend
                val http = HttpClient.newHttpClient()
                val request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.resend.com/emails"))
                    .header("Authorization", "Bearer $resendKey")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()

                val resp = runCatching { http.send(request, HttpResponse.BodyHandlers.ofString()) }.getOrElse { e ->
                    call.respond(HttpStatusCode.BadGateway, mapOf("error" to "provider unreachable", "detail" to e.message))
                    return@post
                }

                when (resp.statusCode()) {
                    200, 201, 202 -> call.respond(HttpStatusCode.Accepted, mapOf("status" to "queued"))
                    400 -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to "provider rejected request"))
                    401, 403 -> call.respond(HttpStatusCode.BadGateway, mapOf("error" to "invalid provider key"))
                    else -> call.respond(HttpStatusCode.BadGateway, mapOf("error" to "provider error", "code" to resp.statusCode()))
                }
            }
            // ==========================================================================

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
data class SupportRequest(
    val from: String? = null,
    val message: String? = null
)

@Serializable
private data class ResendEmail(
    val from: String,
    val to: List<String>,
    val subject: String,
    val text: String
)
