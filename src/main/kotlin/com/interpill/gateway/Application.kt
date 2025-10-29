Вот полный обновлённый файл с «graceful» обработкой ошибок для `/ai/summary`.
Он реализует реальный вызов Gemini (Models API), маппит `429 RESOURCE_EXHAUSTED` и другие коды в понятные ответы, извлекает JSON из ответа модели (в т.ч. из блока `json … `), валидирует его и отдаёт чистый объект `Summary`. Если что-то идёт не так — возвращает аккуратные 4xx/5xx с человекочитаемым сообщением.

````kotlin
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
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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

            // -------- AI summary (mock + real) --------
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
                val bodyText = call.receiveText().trim()

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

                // ----- Extract prompt from JSON or raw text -----
                val prompt: String = runCatching {
                    if (bodyText.startsWith("{")) {
                        val obj = json.parseToJsonElement(bodyText).jsonObject
                        (obj["prompt"] ?: obj["text"] ?: obj["message"])?.jsonPrimitive?.content
                            ?: error("missing 'prompt'")
                    } else {
                        bodyText
                    }
                }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad json or missing prompt"))
                    return@post
                }

                // ----- Call Gemini -----
                val apiKey = System.getenv("GEMINI_API_KEY").orEmpty()
                val model = System.getenv("GEMINI_MODEL").orEmpty().ifBlank { "gemini-1.5-flash" }
                if (apiKey.isBlank()) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "ai not configured"))
                    return@post
                }

                val url =
                    "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

                val requestPayload = buildJsonObject {
                    put("contents", buildJsonArray {
                        add(buildJsonObject {
                            put("parts", buildJsonArray {
                                add(buildJsonObject { put("text", prompt) })
                            })
                        })
                    })
                    put("generationConfig", buildJsonObject {
                        put("temperature", 0.0)
                    })
                }.toString()

                val (code, resp) = safeHttpPostJson(url, requestPayload, timeout = 20.seconds)

                // Map common Gemini errors to user-friendly responses
                if (code == 401 || code == 403) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorised"))
                    return@post
                }
                if (code == 429) {
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf(
                            "error" to "AI service temporarily busy",
                            "note" to "The AI system is overloaded (rate limited). Please try again in a moment."
                        )
                    )
                    return@post
                }
                if (code in 500..599) {
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf("error" to "AI upstream unavailable")
                    )
                    return@post
                }
                if (code !in 200..299) {
                    // Try to surface short message if present
                    val short = extractErrorMessage(resp)
                    call.respond(HttpStatusCode.BadGateway, mapOf("error" to (short ?: "ai_failed")))
                    return@post
                }

                // ----- Parse Gemini response → text -----
                val modelText = runCatching {
                    val root = json.parseToJsonElement(resp).jsonObject
                    val candidates = root["candidates"]?.jsonArray ?: error("no candidates")
                    val first = candidates.first().jsonObject
                    val parts = first["content"]?.jsonObject?.get("parts")?.jsonArray ?: error("no parts")
                    parts.first().jsonObject["text"]?.jsonPrimitive?.content ?: error("no text")
                }.getOrElse {
                    call.respond(HttpStatusCode.BadGateway, mapOf("error" to "invalid_ai_response"))
                    return@post
                }

                // ----- Extract JSON block from model text -----
                val jsonBlock = extractJsonBlock(modelText)?.trim() ?: modelText.trim()

                // ----- Validate to Summary -----
                val summary = runCatching { json.decodeFromString(Summary.serializer(), jsonBlock) }
                    .getOrElse {
                        call.respond(HttpStatusCode.BadGateway, mapOf("error" to "invalid_ai_json"))
                        return@post
                    }

                call.respond(summary)
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

                // простая проверка формата e-mail
                val emailRegex = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$".toRegex()
                if (!emailRegex.matches(cleanFrom)) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid email format"))
                    return@post
                }

                val payload = ResendEmail(
                    from = "onboarding@resend.dev", // быстрый старт без домена
                    to = listOf(supportEmail),
                    subject = "Support Interpill",
                    text = buildString {
                        append("From: $cleanFrom\n\n")
                        append(inDto.message.trim())
                    },
                    reply_to = cleanFrom
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
                        connectTimeout = 10_000
                        readTimeout = 15_000
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

/* ----------------- helpers ----------------- */

private fun safeHttpPostJson(
    url: String,
    body: String,
    headers: Map<String, String> = emptyMap(),
    timeout: Duration = 20.seconds
): Pair<Int, String> {
    return try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            doOutput = true
            connectTimeout = timeout.inWholeMilliseconds.toInt()
            readTimeout = (timeout.inWholeMilliseconds * 2).toInt()
        }
        conn.outputStream.use { os ->
            os.write(body.toByteArray(StandardCharsets.UTF_8))
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val resp = (stream ?: conn.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        code to resp
    } catch (t: Throwable) {
        // Сведём сетевую ошибку к 503, чтобы UI показал дружелюбный текст
        503 to """{"error":{"message":"network failure: ${t.message}"}}"""
    }
}

private fun extractJsonBlock(text: String): String? {
    // вытащить содержимое из ```json ... ``` либо из первых {…}
    val fenced = Regex("```json\\s*([\\s\\S]*?)\\s*```", RegexOption.IGNORE_CASE)
        .find(text)?.groupValues?.getOrNull(1)
    if (!fenced.isNullOrBlank()) return fenced
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    return if (start >= 0 && end > start) text.substring(start, end + 1) else null
}

private fun extractErrorMessage(body: String): String? {
    return try {
        val root = Json.parseToJsonElement(body).jsonObject
        when {
            root["error"] is JsonObject -> {
                val err = root["error"]!!.jsonObject
                err["message"]?.jsonPrimitive?.content
                    ?: err["status"]?.jsonPrimitive?.content
                    ?: "ai_error"
            }
            root["message"] is JsonPrimitive -> root["message"]!!.jsonPrimitive.content
            else -> null
        }
    } catch (_: Throwable) { null }
}
````
