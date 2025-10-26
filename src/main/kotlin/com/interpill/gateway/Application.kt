package com.interpill.gateway

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

fun main() = EngineMain.main(arrayOf())

fun Application.module() {
    install(CORS) { anyHost() }
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }

    routing {
        get("/health") { call.respondText("OK") }

        post("/v1/check-interactions") {
            val req = call.receive<CheckReq>()
            // пока заглушка — вернём low; позже подключим OpenAI/Gemini
            val resp = CheckResp(
                riskLevel = "low",
                perDrug = req.meds.associateWith { "low" }
            )
            call.respond(resp)
        }
    }
}

@Serializable
data class CheckReq(
    val meds: List<String>,
    val profile: Map<String, String> = emptyMap(),
    val patientNotes: List<String> = emptyList()
)

@Serializable
data class CheckResp(
    val riskLevel: String,
    val highlights: List<String> = emptyList(),
    val recommendations: List<String> = emptyList(),
    val caveats: List<String> = emptyList(),
    val perDrug: Map<String, String> = emptyMap()
)
