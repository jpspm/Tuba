package com.tuba.data

import com.tuba.domain.AppUser
import com.tuba.domain.Beach
import com.tuba.domain.FlagReason
import com.tuba.domain.GeoBounds
import com.tuba.domain.GeoPoint
import com.tuba.domain.MarineConditions
import com.tuba.domain.Report
import com.tuba.domain.ReportType
import com.tuba.domain.RiskZone
import com.tuba.domain.Vote
import kotlinx.coroutines.flow.StateFlow

interface BeachRepository {
    /** Trechos monitorados com a leitura oficial mais recente. */
    suspend fun getBeaches(): List<Beach>

    suspend fun getRiskZones(): List<RiskZone>
}

interface ReportRepository {
    /** Reports ainda válidos (não expirados nem derrubados por votos ou denúncias) dentro de [bounds]. */
    suspend fun getActiveReports(bounds: GeoBounds): List<Report>

    /** [reporterLocation] é usada para validar que o usuário está perto do local; não é salva. */
    suspend fun createReport(
        type: ReportType,
        point: GeoPoint,
        description: String?,
        reporterLocation: GeoPoint,
    ): Report

    suspend fun vote(reportId: String, vote: Vote)

    suspend fun flag(reportId: String, reason: FlagReason)

    /** Votos do usuário logado nos reports informados. */
    suspend fun myVotes(reportIds: List<String>): Map<String, Vote>
}

interface MarineRepository {
    suspend fun getConditions(point: GeoPoint): MarineConditions
}

interface AuthRepository {
    val currentUser: StateFlow<AppUser?>

    suspend fun signIn(email: String, password: String)

    suspend fun signUp(email: String, password: String)

    suspend fun signOut()
}
