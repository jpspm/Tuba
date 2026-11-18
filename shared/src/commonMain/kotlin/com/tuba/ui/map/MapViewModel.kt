package com.tuba.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tuba.data.fake.SimulatedBusinesses
import com.tuba.di.AppContainer
import com.tuba.ui.business.BookingRequest
import com.tuba.domain.AppUser
import com.tuba.domain.Beach
import com.tuba.domain.Business
import com.tuba.domain.BusinessCategory
import com.tuba.domain.FlagReason
import com.tuba.domain.GeoBounds
import com.tuba.domain.GeoPoint
import com.tuba.domain.MarineConditions
import com.tuba.domain.OfficialStatus
import com.tuba.domain.Report
import com.tuba.domain.ReportKind
import com.tuba.domain.ReportRules
import com.tuba.domain.ReportType
import com.tuba.domain.RiskKind
import com.tuba.domain.RiskZone
import com.tuba.domain.Vote
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class MapFilter(val label: String) {
    ALL("Tudo"),
    POSITIVE("Banho e esportes"),
    ALERTS("Alertas"),
}

sealed interface Selection {
    data class BeachSelected(val beachId: String) : Selection
    data class ReportSelected(val reportId: String) : Selection
    data class BusinessSelected(val business: Business) : Selection
    data class ZoneSelected(val zone: RiskZone) : Selection
    data class NewReport(val point: GeoPoint) : Selection
    data object Nearby : Selection
}

sealed interface MarineState {
    data object Loading : MarineState
    data class Loaded(val conditions: MarineConditions) : MarineState
    data object Failed : MarineState
}

data class NearbyBeach(val beach: Beach, val distanceM: Double)

data class MapUiState(
    val demoMode: Boolean,
    val loading: Boolean = true,
    val beaches: List<Beach> = emptyList(),
    val riskZones: List<RiskZone> = emptyList(),
    /** Estabelecimentos simulados de todos os trechos; a UI mostra só os dos trechos próprios. */
    val businesses: List<Business> = emptyList(),
    val reports: List<Report> = emptyList(),
    val myVotes: Map<String, Vote> = emptyMap(),
    val user: AppUser? = null,
    val myLocation: GeoPoint? = null,
    val filter: MapFilter = MapFilter.ALL,
    val selection: Selection? = null,
    val marine: Map<String, MarineState> = emptyMap(),
    val pickingLocation: Boolean = false,
    val showLogin: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    /** Incrementado quando a UI deve pedir a permissão de localização. */
    val locationRequests: Int = 0,
) {
    val visibleReports: List<Report>
        get() = when (filter) {
            MapFilter.ALL -> reports
            MapFilter.POSITIVE -> reports.filter { it.type.kind == ReportKind.POSITIVE }
            MapFilter.ALERTS -> reports.filter { it.type.kind == ReportKind.ALERT }
        }

    fun beach(id: String): Beach? = beaches.firstOrNull { it.id == id }

    /**
     * Só trechos próprios exibem estabelecimentos: não se anuncia banho onde a água está imprópria.
     * Escolas de surf também ficam de fora das zonas de tubarão, onde o decreto proíbe o esporte.
     */
    val visibleBusinesses: List<Business>
        get() {
            val proper = beaches.filter { it.official.effectiveStatus() == OfficialStatus.PROPRIA }
            val ids = proper.map { it.id }.toSet()
            val sharkZoneIds = proper.filter { zonesAt(it).any { zone -> zone.kind == RiskKind.SHARK } }.map { it.id }.toSet()
            return businesses.filter { b ->
                b.beachId in ids && !(b.category == BusinessCategory.SURF_SCHOOL && b.beachId in sharkZoneIds)
            }
        }

    fun businessesAt(beach: Beach): List<Business> =
        visibleBusinesses.filter { it.beachId == beach.id }.sortedByDescending { it.sponsored }

    fun reportsNear(beach: Beach, radiusMeters: Double = 1_500.0): List<Report> =
        reports.filter { r ->
            (beach.area?.distanceTo(r.point) ?: beach.point.distanceTo(r.point)) <= radiusMeters
        }.sortedByDescending { it.createdAt }

    fun zonesAt(beach: Beach): List<RiskZone> =
        riskZones.filter { zone -> beach.point in zone.area || beach.area?.ring?.any { it in zone.area } == true }

    fun nearbyBeaches(limit: Int = 8): List<NearbyBeach> {
        val me = myLocation ?: return emptyList()
        return beaches
            .map { NearbyBeach(it, it.area?.distanceTo(me) ?: it.point.distanceTo(me)) }
            .sortedBy { it.distanceM }
            .take(limit)
    }
}

class MapViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(MapUiState(demoMode = container.demoMode))
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    /** Ação que ficou pendente aguardando login ou localização. */
    private var pendingAction: (() -> Unit)? = null

    init {
        viewModelScope.launch {
            container.auth.currentUser.collect { user ->
                _state.update { it.copy(user = user) }
                refreshVotes()
            }
        }
        viewModelScope.launch {
            runCatchingUi {
                val beaches = container.beaches.getBeaches()
                val zones = container.beaches.getRiskZones()
                _state.update {
                    it.copy(beaches = beaches, riskZones = zones, businesses = SimulatedBusinesses.generate(beaches))
                }
            }
        }
        viewModelScope.launch {
            while (true) {
                refreshReports()
                delay(60.seconds)
            }
        }
    }

    fun refreshReports() {
        viewModelScope.launch {
            runCatchingUi {
                val reports = container.reports.getActiveReports(GeoBounds.Pernambuco)
                _state.update { it.copy(reports = reports) }
                refreshVotes()
            }
            _state.update { it.copy(loading = false) }
        }
    }

    private suspend fun refreshVotes() {
        if (_state.value.user == null) {
            _state.update { it.copy(myVotes = emptyMap()) }
            return
        }
        val ids = _state.value.reports.map { it.id }
        runCatchingUi { _state.update { it.copy(myVotes = container.reports.myVotes(ids)) } }
    }

    fun updateLocation(location: GeoPoint?) {
        val hadLocation = _state.value.myLocation != null
        _state.update { it.copy(myLocation = location) }
        if (!hadLocation && location != null) {
            pendingAction?.let { action ->
                pendingAction = null
                action()
            }
        }
    }

    fun setFilter(filter: MapFilter) = _state.update { it.copy(filter = filter) }

    fun selectBeach(beachId: String) {
        _state.update { it.copy(selection = Selection.BeachSelected(beachId)) }
        loadMarine(beachId)
    }

    private fun loadMarine(beachId: String) {
        val beach = _state.value.beach(beachId) ?: return
        if (_state.value.marine[beachId] is MarineState.Loaded) return
        _state.update { it.copy(marine = it.marine + (beachId to MarineState.Loading)) }
        viewModelScope.launch {
            val result = try {
                MarineState.Loaded(container.marine.getConditions(beach.point))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MarineState.Failed
            }
            _state.update { it.copy(marine = it.marine + (beachId to result)) }
        }
    }

    fun selectReport(report: Report) = _state.update { it.copy(selection = Selection.ReportSelected(report.id)) }

    fun selectBusiness(business: Business) =
        _state.update { it.copy(selection = Selection.BusinessSelected(business)) }

    fun requestBooking(business: Business, request: BookingRequest) = _state.update {
        it.copy(
            message = "Pedido de reserva para ${request.party} ${business.category.partyLabel.lowercase()} " +
                "enviado a ${business.name} (simulação).",
        )
    }

    fun selectZone(zone: RiskZone) = _state.update { it.copy(selection = Selection.ZoneSelected(zone)) }

    fun showNearby() = requireLocation {
        _state.update { it.copy(selection = Selection.Nearby) }
    }

    fun dismissSelection() = _state.update { it.copy(selection = null) }

    fun startPickingLocation() = requireLogin {
        requireLocation { _state.update { it.copy(pickingLocation = true, selection = null) } }
    }

    fun cancelPickingLocation() = _state.update { it.copy(pickingLocation = false) }

    fun startReportAt(point: GeoPoint) = requireLogin {
        requireLocation {
            val me = _state.value.myLocation ?: return@requireLocation
            if (me.distanceTo(point) > ReportRules.MAX_REPORT_DISTANCE_M) {
                _state.update {
                    it.copy(message = "Escolha um local a até 2 km de onde você está. Reports só valem para o que você vê.")
                }
            } else {
                _state.update { it.copy(pickingLocation = false, selection = Selection.NewReport(point)) }
            }
        }
    }

    fun submitReport(type: ReportType, point: GeoPoint, description: String) {
        val me = _state.value.myLocation ?: return requestLocation()
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            runCatchingUi {
                val report = container.reports.createReport(type, point, description.trim().ifBlank { null }, me)
                _state.update {
                    it.copy(
                        reports = it.reports + report,
                        selection = null,
                        message = "Report publicado. Obrigado!",
                    )
                }
            }
            _state.update { it.copy(busy = false) }
        }
    }

    fun vote(report: Report, vote: Vote) = requireLogin {
        viewModelScope.launch {
            runCatchingUi {
                container.reports.vote(report.id, vote)
                val reports = container.reports.getActiveReports(GeoBounds.Pernambuco)
                _state.update {
                    it.copy(
                        reports = reports,
                        myVotes = it.myVotes + (report.id to vote),
                        selection = if (reports.any { r -> r.id == report.id }) it.selection else null,
                        message = if (vote == Vote.CONFIRM) "Obrigado por confirmar!" else "Obrigado pelo aviso!",
                    )
                }
            }
        }
    }

    fun flag(report: Report, reason: FlagReason) = requireLogin {
        viewModelScope.launch {
            runCatchingUi {
                container.reports.flag(report.id, reason)
                val reports = container.reports.getActiveReports(GeoBounds.Pernambuco)
                _state.update {
                    it.copy(
                        reports = reports,
                        selection = if (reports.any { r -> r.id == report.id }) it.selection else null,
                        message = "Denúncia enviada. Com ${ReportRules.FLAGS_TO_HIDE} denúncias o report sai do mapa.",
                    )
                }
            }
        }
    }

    fun showLogin() = _state.update { it.copy(showLogin = true) }

    fun dismissLogin() {
        pendingAction = null
        _state.update { it.copy(showLogin = false) }
    }

    fun signIn(email: String, password: String, createAccount: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            runCatchingUi {
                if (createAccount) {
                    container.auth.signUp(email.trim(), password)
                } else {
                    container.auth.signIn(email.trim(), password)
                }
                if (container.auth.currentUser.value == null) {
                    // Supabase com confirmação de e-mail ativa: ainda não há sessão.
                    _state.update { it.copy(message = "Confira seu e-mail para confirmar a conta.") }
                } else {
                    _state.update { it.copy(showLogin = false, user = container.auth.currentUser.value) }
                    val action = pendingAction
                    pendingAction = null
                    action?.invoke()
                }
            }
            _state.update { it.copy(busy = false) }
        }
    }

    fun signOut() {
        viewModelScope.launch { runCatchingUi { container.auth.signOut() } }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun requireLogin(action: () -> Unit) {
        if (_state.value.user != null) {
            action()
        } else {
            pendingAction = action
            _state.update { it.copy(showLogin = true) }
        }
    }

    private fun requireLocation(action: () -> Unit) {
        if (_state.value.myLocation != null) {
            action()
        } else {
            pendingAction = action
            requestLocation()
        }
    }

    private fun requestLocation() = _state.update {
        it.copy(
            locationRequests = it.locationRequests + 1,
            message = "Precisamos da sua localização. Se ela não aparecer, ative o GPS.",
        )
    }

    private suspend fun runCatchingUi(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(message = friendlyMessage(e)) }
        }
    }
}

/** Mensagens levantadas pelos triggers do banco chegam embrulhadas no erro do PostgREST. */
private val knownMessages = listOf(
    "Ative a localização para reportar",
    "Você só pode reportar a até 2 km de onde está",
    "Limite de 5 reports por hora atingido. Tente mais tarde",
)

private fun friendlyMessage(e: Exception): String {
    val raw = e.message.orEmpty()
    knownMessages.firstOrNull { it in raw }?.let { return it }
    return when {
        "duplicate key" in raw && "report_flags" in raw -> "Você já denunciou este report"
        "row-level security" in raw -> "Ação não permitida"
        raw.isBlank() -> "Algo deu errado. Tente novamente."
        raw.length > 160 -> "Algo deu errado. Tente novamente."
        else -> raw
    }
}
