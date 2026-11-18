package com.tuba.data.supabase

import com.tuba.data.AuthRepository
import com.tuba.data.BeachRepository
import com.tuba.data.ReportRepository
import com.tuba.domain.AppUser
import com.tuba.domain.Area
import com.tuba.domain.Beach
import com.tuba.domain.FlagReason
import com.tuba.domain.GeoBounds
import com.tuba.domain.GeoPoint
import com.tuba.domain.OfficialReading
import com.tuba.domain.OfficialStatus
import com.tuba.domain.Report
import com.tuba.domain.ReportType
import com.tuba.domain.RiskKind
import com.tuba.domain.RiskZone
import com.tuba.domain.Vote
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

@Serializable
private data class BeachDto(
    val id: String,
    val name: String,
    val city: String,
    val reference: String? = null,
    val lat: Double,
    val lng: Double,
    val area: JsonObject? = null,
    val status: String? = null,
    @SerialName("sampled_on") val sampledOn: String? = null,
    val source: String? = null,
    @SerialName("bulletin_url") val bulletinUrl: String? = null,
)

@Serializable
private data class RiskZoneDto(
    val id: String,
    val kind: String,
    val name: String,
    val description: String,
    val source: String,
    val area: JsonObject,
)

@Serializable
private data class ReportDto(
    val id: String,
    @SerialName("user_id") val userId: String? = null,
    val type: String,
    val lat: Double,
    val lng: Double,
    val description: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("expires_at") val expiresAt: String,
    val upvotes: Int,
    val downvotes: Int,
)

@Serializable
private data class NewReportDto(
    val type: String,
    val lat: Double,
    val lng: Double,
    val description: String?,
    @SerialName("reporter_lat") val reporterLat: Double,
    @SerialName("reporter_lng") val reporterLng: Double,
)

@Serializable
private data class FlagDto(
    @SerialName("report_id") val reportId: String,
    @SerialName("user_id") val userId: String,
    val reason: String,
)

@Serializable
private data class VoteDto(
    @SerialName("report_id") val reportId: String,
    @SerialName("user_id") val userId: String,
    val value: Int,
)

private fun ReportType.dbName() = name.lowercase()

/** GeoJSON Polygon -> anel externo. */
private fun JsonObject.toArea(): Area? = runCatching {
    val ring = getValue("coordinates").jsonArray[0].jsonArray
    Area(ring.map { pos -> pos.jsonArray.let { GeoPoint(it[1].jsonPrimitive.double, it[0].jsonPrimitive.double) } })
}.getOrNull()

private fun ReportDto.toDomain(): Report? {
    val type = ReportType.entries.firstOrNull { it.dbName() == type } ?: return null
    return Report(
        id = id,
        userId = userId,
        type = type,
        point = GeoPoint(lat, lng),
        description = description,
        createdAt = Instant.parse(createdAt),
        expiresAt = Instant.parse(expiresAt),
        confirmations = upvotes,
        denials = downvotes,
    )
}

class SupabaseBeachRepository(private val client: SupabaseClient) : BeachRepository {
    override suspend fun getBeaches(): List<Beach> =
        client.from("beaches_with_status").select().decodeList<BeachDto>().map { dto ->
            Beach(
                id = dto.id,
                name = dto.name,
                city = dto.city,
                reference = dto.reference,
                point = GeoPoint(dto.lat, dto.lng),
                area = dto.area?.toArea(),
                official = OfficialReading(
                    status = when (dto.status) {
                        "propria" -> OfficialStatus.PROPRIA
                        "impropria" -> OfficialStatus.IMPROPRIA
                        else -> OfficialStatus.SEM_DADOS
                    },
                    sampledOn = dto.sampledOn,
                    source = dto.source ?: "CPRH",
                    bulletinUrl = dto.bulletinUrl,
                ),
            )
        }

    override suspend fun getRiskZones(): List<RiskZone> =
        client.from("risk_zones").select().decodeList<RiskZoneDto>().mapNotNull { dto ->
            val area = dto.area.toArea() ?: return@mapNotNull null
            RiskZone(dto.id, RiskKind.SHARK, dto.name, dto.description, dto.source, area)
        }
}

class SupabaseReportRepository(private val client: SupabaseClient) : ReportRepository {
    override suspend fun getActiveReports(bounds: GeoBounds): List<Report> =
        client.from("active_reports").select {
            filter {
                gte("lat", bounds.south)
                lte("lat", bounds.north)
                gte("lng", bounds.west)
                lte("lng", bounds.east)
            }
        }.decodeList<ReportDto>().mapNotNull { it.toDomain() }

    override suspend fun createReport(
        type: ReportType,
        point: GeoPoint,
        description: String?,
        reporterLocation: GeoPoint,
    ): Report {
        // O trigger valida proximidade e limite por hora, preenche autor e datas e descarta a posição.
        val created = client.from("reports").insert(
            NewReportDto(
                type.dbName(),
                point.latitude,
                point.longitude,
                description,
                reporterLocation.latitude,
                reporterLocation.longitude,
            ),
        ) {
            select(Columns.list("id", "user_id", "type", "lat", "lng", "description", "created_at", "expires_at", "upvotes", "downvotes"))
        }.decodeSingle<ReportDto>()
        return checkNotNull(created.toDomain())
    }

    override suspend fun vote(reportId: String, vote: Vote) {
        val userId = checkNotNull(client.auth.currentUserOrNull()?.id) { "Faça login para votar" }
        client.from("report_votes").upsert(VoteDto(reportId, userId, vote.value)) {
            onConflict = "report_id,user_id"
        }
    }

    override suspend fun flag(reportId: String, reason: FlagReason) {
        val userId = checkNotNull(client.auth.currentUserOrNull()?.id) { "Faça login para denunciar" }
        client.from("report_flags").insert(FlagDto(reportId, userId, reason.dbName))
    }

    override suspend fun myVotes(reportIds: List<String>): Map<String, Vote> {
        val userId = client.auth.currentUserOrNull()?.id ?: return emptyMap()
        if (reportIds.isEmpty()) return emptyMap()
        return client.from("report_votes").select {
            filter {
                eq("user_id", userId)
                isIn("report_id", reportIds)
            }
        }.decodeList<VoteDto>().associate { dto ->
            dto.reportId to if (dto.value > 0) Vote.CONFIRM else Vote.DENY
        }
    }
}

class SupabaseAuthRepository(
    private val client: SupabaseClient,
    scope: CoroutineScope,
) : AuthRepository {
    override val currentUser: StateFlow<AppUser?> = client.auth.sessionStatus
        .map { status ->
            (status as? SessionStatus.Authenticated)?.session?.user?.let { AppUser(it.id, it.email) }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    override suspend fun signIn(email: String, password: String) {
        client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }

    override suspend fun signUp(email: String, password: String) {
        client.auth.signUpWith(Email) {
            this.email = email
            this.password = password
        }
    }

    override suspend fun signOut() {
        client.auth.signOut()
    }
}
