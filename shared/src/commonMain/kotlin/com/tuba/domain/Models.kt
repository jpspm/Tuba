package com.tuba.domain

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

data class GeoPoint(val latitude: Double, val longitude: Double) {
    /** Distância aproximada em metros (haversine). */
    fun distanceTo(other: GeoPoint): Double {
        val r = 6_371_000.0
        val dLat = (other.latitude - latitude).toRadians()
        val dLng = (other.longitude - longitude).toRadians()
        val a = sin(dLat / 2).pow(2) +
            cos(latitude.toRadians()) * cos(other.latitude.toRadians()) * sin(dLng / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    private fun Double.toRadians() = this * kotlin.math.PI / 180.0
}

/** Polígono simples (anel externo, sem buracos). */
data class Area(val ring: List<GeoPoint>) {
    /** Ray casting; suficiente para os polígonos pequenos do litoral. */
    operator fun contains(point: GeoPoint): Boolean {
        var inside = false
        var j = ring.lastIndex
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[j]
            if ((a.latitude > point.latitude) != (b.latitude > point.latitude) &&
                point.longitude < (b.longitude - a.longitude) * (point.latitude - a.latitude) /
                (b.latitude - a.latitude) + a.longitude
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    /** Distância aproximada até o polígono: 0 dentro dele, senão até o vértice mais próximo. */
    fun distanceTo(point: GeoPoint): Double =
        if (point in this) 0.0 else ring.minOf { it.distanceTo(point) }
}

data class GeoBounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    companion object {
        /** Litoral de Pernambuco, incluindo Fernando de Noronha. */
        val Pernambuco = GeoBounds(south = -9.5, west = -35.6, north = -3.7, east = -32.3)
    }
}

/** Situação de balneabilidade segundo o boletim oficial (CPRH). */
enum class OfficialStatus(val label: String) {
    PROPRIA("Própria para banho"),
    IMPROPRIA("Imprópria para banho"),
    SEM_DADOS("Sem dados recentes"),
}

data class OfficialReading(
    val status: OfficialStatus,
    /** Data da coleta, no formato ISO (aaaa-mm-dd). */
    val sampledOn: String?,
    val source: String,
    val bulletinUrl: String?,
) {
    /**
     * A CPRH coleta toda semana e cada boletim vale cerca de 10 dias. Depois disso, a
     * classificação não deve ser exibida como se fosse atual.
     */
    fun isStale(now: Instant = Clock.System.now()): Boolean {
        val date = sampledOn ?: return true
        val sampledAt = runCatching { Instant.parse("${date.take(10)}T12:00:00Z") }.getOrNull() ?: return true
        return now - sampledAt > STALE_AFTER_DAYS.days
    }

    /** Situação que o app deve exibir: dado velho vira "sem dados recentes". */
    fun effectiveStatus(now: Instant = Clock.System.now()): OfficialStatus =
        if (isStale(now)) OfficialStatus.SEM_DADOS else status

    companion object {
        const val STALE_AFTER_DAYS = 12
    }
}

data class Beach(
    val id: String,
    val name: String,
    val city: String,
    /** Referência do ponto de coleta no boletim, ex.: "Em frente à Praça dos Milagres". */
    val reference: String?,
    val point: GeoPoint,
    /** Trecho de praia e mar representado pelo ponto de coleta. */
    val area: Area?,
    val official: OfficialReading,
)

enum class RiskKind { SHARK }

data class RiskZone(
    val id: String,
    val kind: RiskKind,
    val name: String,
    val description: String,
    val source: String,
    val area: Area,
)

/** Condições do mar e do vento (Open-Meteo). Campos nulos quando o modelo não tem o dado. */
data class MarineConditions(
    val waveHeightM: Double?,
    val wavePeriodS: Double?,
    val waveDirectionDeg: Double?,
    val waterTempC: Double?,
    val windSpeedKmh: Double?,
    val windGustsKmh: Double?,
    val windDirectionDeg: Double?,
)

enum class ReportKind { POSITIVE, ALERT }

/**
 * Tipos de report que os usuários podem colocar no mapa.
 * [lifetimeHours] precisa bater com `report_lifetime()` no SQL do Supabase.
 */
enum class ReportType(
    val emoji: String,
    val label: String,
    val kind: ReportKind,
    val lifetimeHours: Int,
    val color: Long,
) {
    GOOD_SURF("🏄", "Bom para surf", ReportKind.POSITIVE, 4, 0xFF1E88E5),
    GOOD_SWIM("🏊", "Boa para nadar", ReportKind.POSITIVE, 4, 0xFF2E7D32),
    ROUGH_SEA("🌊", "Mar agitado / correnteza", ReportKind.ALERT, 4, 0xFF00838F),
    POLLUTION("🗑️", "Poluição / lixo", ReportKind.ALERT, 24, 0xFF8D6E63),
    SEWAGE("🚱", "Esgoto", ReportKind.ALERT, 48, 0xFF5D4037),
    SHARK("🦈", "Tubarão avistado", ReportKind.ALERT, 12, 0xFFC62828),
    JELLYFISH("🪼", "Águas-vivas", ReportKind.ALERT, 12, 0xFF8E24AA),
    OTHER_DANGER("⚠️", "Outro perigo", ReportKind.ALERT, 12, 0xFFEF6C00),
    CROWDED("👥", "Praia lotada", ReportKind.ALERT, 3, 0xFF3949AB),
}

enum class Vote(val value: Int) {
    CONFIRM(1),
    DENY(-1),
}

enum class FlagReason(val dbName: String, val label: String) {
    FALSE("falso", "Informação falsa"),
    OFFENSIVE("ofensivo", "Conteúdo ofensivo"),
    SPAM("spam", "Spam ou propaganda"),
}

object ReportRules {
    /** Distância máxima entre quem reporta e o local do report. */
    const val MAX_REPORT_DISTANCE_M = 2_000.0
    const val MAX_REPORTS_PER_HOUR = 5
    const val FLAGS_TO_HIDE = 3
}

data class Report(
    val id: String,
    val userId: String?,
    val type: ReportType,
    val point: GeoPoint,
    val description: String?,
    val createdAt: Instant,
    val expiresAt: Instant,
    val confirmations: Int,
    val denials: Int,
)

data class AppUser(val id: String, val email: String?)

/** Cores no estilo dos pins do Google Maps: comida laranja, hospedagem rosa, atividades azul/verde. */
enum class BusinessCategory(
    val emoji: String,
    val label: String,
    val color: Long,
    /** Título da seção de produtos na página do estabelecimento. */
    val catalogTitle: String,
    val bookingAction: String,
    /** Como contar quem participa da reserva: pessoas, hóspedes, alunos... */
    val partyLabel: String,
) {
    BEACH_BAR("🍹", "Barraca de praia", 0xFFF57C00, "Cardápio", "Reservar mesa", "Pessoas"),
    RESTAURANT("🍤", "Restaurante", 0xFFE65100, "Cardápio", "Reservar mesa", "Pessoas"),
    SURF_SCHOOL("🏄", "Escola de surf", 0xFF1E88E5, "Aulas e pacotes", "Agendar aula", "Alunos"),
    RENTAL("⛱️", "Aluguel de cadeiras e guarda-sol", 0xFF00897B, "Preços", "Reservar kit", "Kits"),
    BOAT_TOUR("⛵", "Passeios de barco", 0xFF3949AB, "Passeios", "Reservar passeio", "Pessoas"),
    LODGING("🛏️", "Pousada", 0xFFD81B60, "Quartos", "Reservar quarto", "Hóspedes"),
}

data class CatalogItem(
    val name: String,
    val detail: String?,
    val priceCents: Int,
    /** Ex.: "por pessoa", "diária". */
    val unit: String? = null,
)

data class CatalogSection(val title: String, val items: List<CatalogItem>)

/** Estabelecimento simulado, usado para demonstrar o modelo de anúncios por trecho. */
data class Business(
    val id: String,
    val beachId: String,
    val name: String,
    val category: BusinessCategory,
    val point: GeoPoint,
    val rating: Double,
    val reviews: Int,
    val description: String,
    val sponsored: Boolean,
    val offer: String?,
    val hours: String,
    val catalog: List<CatalogSection>,
)
