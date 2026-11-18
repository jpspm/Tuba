package com.tuba.data.fake

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
import com.tuba.domain.ReportRules
import com.tuba.domain.ReportType
import com.tuba.domain.RiskKind
import com.tuba.domain.RiskZone
import com.tuba.domain.Vote
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun parseRing(text: String): Area = Area(
    text.split(';').map { pair ->
        val (lat, lng) = pair.split(',')
        GeoPoint(lat.toDouble(), lng.toDouble())
    },
)

/** Trechos e boletim reais (gerados a partir de data/), sem backend. */
class FakeBeachRepository : BeachRepository {
    override suspend fun getBeaches(): List<Beach> = seedBeaches.map { seed ->
        Beach(
            id = seed.id,
            name = seed.name,
            city = seed.city,
            reference = seed.reference,
            point = GeoPoint(seed.lat, seed.lng),
            area = parseRing(seed.ring),
            official = OfficialReading(
                status = when (seed.status) {
                    "propria" -> OfficialStatus.PROPRIA
                    "impropria" -> OfficialStatus.IMPROPRIA
                    else -> OfficialStatus.SEM_DADOS
                },
                sampledOn = seedBulletinDate,
                source = "CPRH",
                bulletinUrl = null,
            ),
        )
    }

    override suspend fun getRiskZones(): List<RiskZone> = seedZones.map { zone ->
        RiskZone(
            id = zone.id,
            kind = RiskKind.SHARK,
            name = zone.name,
            description = zone.description,
            source = zone.source,
            area = parseRing(zone.ring),
        )
    }
}

/** Reproduz em memória as regras do banco: expiração, proximidade, limite por hora, votos e denúncias. */
class FakeReportRepository(private val auth: AuthRepository) : ReportRepository {
    private val mutex = Mutex()
    private val reports = mutableListOf<Report>()
    private val votes = mutableMapOf<Pair<String, String>, Vote>()
    private val flags = mutableMapOf<String, MutableSet<String>>()
    private var nextId = 1

    init {
        val now = Clock.System.now()
        fun seed(type: ReportType, point: GeoPoint, description: String?, minutesAgo: Int, up: Int, down: Int) {
            val createdAt = now - minutesAgo.minutes
            reports += Report(
                id = "demo-${nextId++}",
                userId = "demo-user",
                type = type,
                point = point,
                description = description,
                createdAt = createdAt,
                expiresAt = createdAt + type.lifetimeHours.hours,
                confirmations = up,
                denials = down,
            )
        }
        seed(ReportType.GOOD_SURF, GeoPoint(-8.5262, -35.0078), "Ondas de 1 m, séries constantes", 40, 5, 0)
        seed(ReportType.GOOD_SWIM, GeoPoint(-8.5040, -34.9985), "Piscinas naturais com maré baixa", 25, 8, 1)
        seed(ReportType.SHARK, GeoPoint(-8.1650, -34.9070), "Avistado perto da arrebentação", 90, 3, 0)
        seed(ReportType.SEWAGE, GeoPoint(-8.0905, -34.8785), "Língua negra na saída da galeria", 300, 6, 0)
        seed(ReportType.POLLUTION, GeoPoint(-8.2045, -34.9205), "Muito lixo na faixa de areia", 180, 2, 0)
        seed(ReportType.ROUGH_SEA, GeoPoint(-8.3385, -34.9505), "Correnteza forte, bandeira vermelha", 60, 4, 0)
        seed(ReportType.CROWDED, GeoPoint(-8.1165, -34.8905), null, 30, 1, 0)
        seed(ReportType.JELLYFISH, GeoPoint(-8.6985, -35.0775), "Várias na beira", 120, 2, 1)
    }

    override suspend fun getActiveReports(bounds: GeoBounds): List<Report> = mutex.withLock {
        val now = Clock.System.now()
        reports.filter { r ->
            r.expiresAt > now &&
                !(r.denials >= 3 && r.denials > r.confirmations) &&
                (flags[r.id]?.size ?: 0) < ReportRules.FLAGS_TO_HIDE &&
                r.point.latitude in bounds.south..bounds.north &&
                r.point.longitude in bounds.west..bounds.east
        }
    }

    override suspend fun createReport(
        type: ReportType,
        point: GeoPoint,
        description: String?,
        reporterLocation: GeoPoint,
    ): Report = mutex.withLock {
        val user = requireNotNull(auth.currentUser.value) { "Faça login para reportar" }
        require(point.distanceTo(reporterLocation) <= ReportRules.MAX_REPORT_DISTANCE_M) {
            "Você só pode reportar a até 2 km de onde está"
        }
        val now = Clock.System.now()
        val lastHour = reports.count { it.userId == user.id && it.createdAt > now - 1.hours }
        require(lastHour < ReportRules.MAX_REPORTS_PER_HOUR) {
            "Limite de ${ReportRules.MAX_REPORTS_PER_HOUR} reports por hora atingido. Tente mais tarde"
        }
        Report(
            id = "local-${nextId++}",
            userId = user.id,
            type = type,
            point = point,
            description = description,
            createdAt = now,
            expiresAt = now + type.lifetimeHours.hours,
            confirmations = 0,
            denials = 0,
        ).also { reports += it }
    }

    override suspend fun vote(reportId: String, vote: Vote): Unit = mutex.withLock {
        val user = requireNotNull(auth.currentUser.value) { "Faça login para votar" }
        val index = reports.indexOfFirst { it.id == reportId }
        require(index >= 0) { "Report não encontrado" }
        val report = reports[index]
        require(report.userId != user.id) { "Você não pode votar no seu próprio report" }

        val previous = votes.put(reportId to user.id, vote)
        var up = report.confirmations
        var down = report.denials
        when (previous) {
            Vote.CONFIRM -> up--
            Vote.DENY -> down--
            null -> Unit
        }
        when (vote) {
            Vote.CONFIRM -> up++
            Vote.DENY -> down++
        }
        // Confirmar estende a validade em metade do tempo de vida do tipo.
        val extended = Clock.System.now() + (report.type.lifetimeHours / 2.0).hours
        reports[index] = report.copy(
            confirmations = up,
            denials = down,
            expiresAt = if (vote == Vote.CONFIRM) maxOf(report.expiresAt, extended) else report.expiresAt,
        )
    }

    override suspend fun flag(reportId: String, reason: FlagReason): Unit = mutex.withLock {
        val user = requireNotNull(auth.currentUser.value) { "Faça login para denunciar" }
        val report = requireNotNull(reports.firstOrNull { it.id == reportId }) { "Report não encontrado" }
        require(report.userId != user.id) { "Você não pode denunciar o seu próprio report" }
        require(flags.getOrPut(reportId) { mutableSetOf() }.add(user.id)) { "Você já denunciou este report" }
    }

    override suspend fun myVotes(reportIds: List<String>): Map<String, Vote> = mutex.withLock {
        val user = auth.currentUser.value ?: return emptyMap()
        reportIds.mapNotNull { id -> votes[id to user.id]?.let { id to it } }.toMap()
    }
}

class FakeAuthRepository : AuthRepository {
    private val user = MutableStateFlow<AppUser?>(null)
    override val currentUser: StateFlow<AppUser?> = user.asStateFlow()

    override suspend fun signIn(email: String, password: String) {
        require(email.contains("@")) { "E-mail inválido" }
        require(password.length >= 6) { "A senha precisa ter pelo menos 6 caracteres" }
        user.value = AppUser(id = "local-$email", email = email)
    }

    override suspend fun signUp(email: String, password: String) = signIn(email, password)

    override suspend fun signOut() {
        user.value = null
    }
}
