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

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        install(ContentNegotiation) { json() }
        install(CORS) {
            anyHost()
            allowMethod(io.ktor.http.HttpMethod.Get)
            allowMethod(io.ktor.http.HttpMethod.Post)
            allowHeader(io.ktor.http.HttpHeaders.ContentType)
            allowHeader(io.ktor.http.HttpHeaders.Authorization)
        }
        routing {
            get("/ping") {
                call.respond(mapOf("status" to "ok"))
            }

            // Пример мок-эндпойнта для проверки:
            get("/ai/summary") {
                val mock = call.request.queryParameters["mock"] == "1"
                if (mock) {
                    call.respond(Summary("low", listOf(), listOf(), listOf(), mapOf("paracetamol" to "low")))
                } else {
                    call.respond(mapOf("error" to "not configured"))
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
