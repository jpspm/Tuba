package com.tuba.data

import com.tuba.domain.GeoPoint
import com.tuba.domain.MarineConditions
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class MarineResponse(val current: MarineCurrent? = null)

@Serializable
private data class MarineCurrent(
    @SerialName("wave_height") val waveHeight: Double? = null,
    @SerialName("wave_period") val wavePeriod: Double? = null,
    @SerialName("wave_direction") val waveDirection: Double? = null,
    @SerialName("sea_surface_temperature") val seaSurfaceTemperature: Double? = null,
)

@Serializable
private data class WindResponse(val current: WindCurrent? = null)

@Serializable
private data class WindCurrent(
    @SerialName("wind_speed_10m") val speed: Double? = null,
    @SerialName("wind_gusts_10m") val gusts: Double? = null,
    @SerialName("wind_direction_10m") val direction: Double? = null,
)

/** Previsão de ondas e vento da Open-Meteo (gratuita, sem chave, uso não comercial). */
class OpenMeteoRepository(private val client: HttpClient = HttpClient()) : MarineRepository {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getConditions(point: GeoPoint): MarineConditions = coroutineScope {
        // O modelo de ondas não cobre a faixa de terra: consulta ~1 km mar adentro (a costa de PE dá para leste).
        val offshore = GeoPoint(point.latitude, point.longitude + 0.01)
        val marine = async {
            json.decodeFromString<MarineResponse>(
                client.get("https://marine-api.open-meteo.com/v1/marine") {
                    parameter("latitude", offshore.latitude)
                    parameter("longitude", offshore.longitude)
                    parameter("current", "wave_height,wave_period,wave_direction,sea_surface_temperature")
                }.bodyAsText(),
            ).current
        }
        val wind = async {
            json.decodeFromString<WindResponse>(
                client.get("https://api.open-meteo.com/v1/forecast") {
                    parameter("latitude", point.latitude)
                    parameter("longitude", point.longitude)
                    parameter("current", "wind_speed_10m,wind_gusts_10m,wind_direction_10m")
                    parameter("wind_speed_unit", "kmh")
                }.bodyAsText(),
            ).current
        }
        val m = marine.await()
        val w = wind.await()
        MarineConditions(
            waveHeightM = m?.waveHeight,
            wavePeriodS = m?.wavePeriod,
            waveDirectionDeg = m?.waveDirection,
            waterTempC = m?.seaSurfaceTemperature,
            windSpeedKmh = w?.speed,
            windGustsKmh = w?.gusts,
            windDirectionDeg = w?.direction,
        )
    }
}
