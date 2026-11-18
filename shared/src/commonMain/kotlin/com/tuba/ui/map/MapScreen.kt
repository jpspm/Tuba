package com.tuba.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.tuba.domain.GeoPoint
import com.tuba.ui.sheets.BeachSheet
import com.tuba.ui.business.BusinessPage
import com.tuba.ui.sheets.LocalSheetSnackbar
import com.tuba.ui.sheets.LoginDialog
import com.tuba.ui.sheets.NearbySheet
import com.tuba.ui.sheets.NewReportSheet
import com.tuba.ui.sheets.ReportSheet
import com.tuba.ui.sheets.ZoneSheet
import com.tuba.ui.theme.StatusColors
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.location.LocationPermission
import org.maplibre.compose.location.rememberLocationState
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.include
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

/** Estilo vetorial gratuito baseado em OpenStreetMap, sem chave de API. */
private const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

/** Abaixo deste zoom os trechos ficam pequenos demais e o mapa mostra marcadores. */
private const val AREAS_MIN_ZOOM = 12.0

/** A partir deste zoom os pins de estabelecimentos mostram o nome. */
private const val BUSINESS_LABEL_MIN_ZOOM = 14.5

private fun GeoPoint.toPosition() = Position(longitude = longitude, latitude = latitude)
private fun Position.toGeoPoint() = GeoPoint(latitude = latitude, longitude = longitude)

private suspend fun MapState.flyTo(point: GeoPoint, zoom: Double) =
    animateCamera(CameraUpdate(center = point.toPosition(), zoom = zoom))

@Composable
fun MapScreen(viewModel: MapViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val locationState = rememberLocationState()

    val selectedBeachId = (state.selection as? Selection.BeachSelected)?.beachId
    val mapState = rememberMapState(
        baseStyle = BaseStyle.Uri(MAP_STYLE_URL),
        initialCameraPosition = CameraPosition(
            center = Position(longitude = -34.95, latitude = -8.28),
            zoom = 9.0,
        ),
    ) {
        TubaLayers(
            beaches = state.beaches,
            riskZones = state.riskZones,
            selectedBeachId = selectedBeachId,
            locationState = locationState,
            onBeachClick = viewModel::selectBeach,
            onZoneClick = viewModel::selectZone,
        )
    }
    val showAreas by remember { derivedStateOf { mapState.cameraPosition.zoom >= AREAS_MIN_ZOOM } }
    val showBusinessLabels by remember { derivedStateOf { mapState.cameraPosition.zoom >= BUSINESS_LABEL_MIN_ZOOM } }

    val interactions = remember(viewModel) {
        MapInteractions {
            callbacks {
                longClick {
                    onEvent { event ->
                        event.position?.let { viewModel.startReportAt(it.toGeoPoint()) }
                        ClickResult.Consume
                    }
                }
            }
        }
    }

    // Localização: repassa ao ViewModel e pede permissão quando ele solicitar.
    val myPosition = locationState.lastLocation?.position
    LaunchedEffect(myPosition) { viewModel.updateLocation(myPosition?.toGeoPoint()) }
    LaunchedEffect(state.locationRequests) {
        if (state.locationRequests > 0 && locationState.permission !is LocationPermission.Granted) {
            locationState.requestPermission()
        }
    }
    LaunchedEffect(locationState.permission) {
        if (locationState.permission is LocationPermission.Granted && locationState.lastLocation == null) {
            locationState.retry()
        }
    }
    LaunchedEffect(state.pickingLocation) {
        if (state.pickingLocation) state.myLocation?.let { mapState.flyTo(it, 15.0) }
    }

    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = state.pickingLocation,
        onBackCompleted = viewModel::cancelPickingLocation,
    )
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = state.selection is Selection.BusinessSelected,
        onBackCompleted = viewModel::dismissSelection,
    )

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    Box(Modifier.fillMaxSize()) {
        val soon = Clock.System.now() + 1.hours
        MaplibreMap(
            modifier = Modifier.fillMaxSize(),
            state = mapState,
            interactions = interactions,
            overlay = {
                include(MapOverlay.Default)
                if (!showAreas) {
                    state.beaches.forEach { beach ->
                        key(beach.id) {
                            BeachMarker(
                                beach = beach,
                                onClick = { viewModel.selectBeach(beach.id) },
                                modifier = Modifier.placedAt(beach.point.toPosition()),
                            )
                        }
                    }
                }
                if (showAreas) {
                    state.visibleBusinesses.forEach { business ->
                        key(business.id) {
                            BusinessPin(
                                business = business,
                                onClick = { viewModel.selectBusiness(business) },
                                modifier = Modifier.placedAt(business.point.toPosition(), Alignment.BottomCenter),
                            )
                        }
                        if (showBusinessLabels) {
                            key("${business.id}-label") {
                                BusinessLabel(
                                    business = business,
                                    // À direita do pin, na altura do círculo.
                                    modifier = Modifier
                                        .placedAt(business.point.toPosition(), Alignment.BottomStart)
                                        .padding(start = 18.dp, bottom = 18.dp),
                                )
                            }
                        }
                    }
                }
                state.visibleReports.forEach { report ->
                    key(report.id) {
                        ReportMarker(
                            report = report,
                            faded = report.expiresAt < soon,
                            onClick = { viewModel.selectReport(report) },
                            modifier = Modifier.placedAt(report.point.toPosition(), Alignment.BottomCenter),
                        )
                    }
                }
            },
        )

        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TopBar(
                demoMode = state.demoMode,
                loggedIn = state.user != null,
                onLogin = viewModel::showLogin,
                onLogout = viewModel::signOut,
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(
                    onClick = viewModel::showNearby,
                    label = { Text("📍 Perto de mim") },
                    colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surface),
                )
                MapFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = state.filter == filter,
                        onClick = { viewModel.setFilter(filter) },
                        label = { Text(filter.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        if (state.pickingLocation) {
            Text(
                "📍",
                fontSize = 40.sp,
                modifier = Modifier.align(Alignment.Center).offset(y = (-20).dp),
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(16.dp),
                shape = MaterialTheme.shapes.large,
                tonalElevation = 4.dp,
                shadowElevation = 6.dp,
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Mova o mapa até o local do report", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Precisa ser a até 2 km de onde você está.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = viewModel::cancelPickingLocation, modifier = Modifier.weight(1f)) {
                            Text("Cancelar")
                        }
                        Button(
                            onClick = { viewModel.startReportAt(mapState.cameraPosition.center.toGeoPoint()) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Usar este local") }
                    }
                }
            }
        } else {
            Column(
                Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(16.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Legend()
                SmallFloatingActionButton(
                    onClick = {
                        val me = state.myLocation
                        if (me == null) {
                            viewModel.showNearby()
                        } else {
                            scope.launch { mapState.flyTo(me, 14.0) }
                        }
                    },
                ) { Text("◎", fontSize = 20.sp) }
                ExtendedFloatingActionButton(
                    onClick = viewModel::startPickingLocation,
                    text = { Text("Reportar") },
                    icon = { Text("＋", fontSize = 18.sp) },
                )
            }
        }

        (state.selection as? Selection.BusinessSelected)?.let { selection ->
            BusinessPage(
                business = selection.business,
                beach = state.beach(selection.business.beachId),
                onBook = { request -> viewModel.requestBooking(selection.business, request) },
                onBack = viewModel::dismissSelection,
            )
        }

        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 88.dp),
        )
    }

    CompositionLocalProvider(LocalSheetSnackbar provides snackbar) {
        when (val selection = state.selection) {
            is Selection.BeachSelected -> state.beach(selection.beachId)?.let { beach ->
                BeachSheet(
                    beach = beach,
                    zones = state.zonesAt(beach),
                    businesses = state.businessesAt(beach),
                    onBusinessClick = viewModel::selectBusiness,
                    marine = state.marine[beach.id],
                    nearbyReports = state.reportsNear(beach),
                    onReportClick = viewModel::selectReport,
                    onDismiss = viewModel::dismissSelection,
                )
            }
            is Selection.ReportSelected -> state.reports.firstOrNull { it.id == selection.reportId }?.let { report ->
                ReportSheet(
                    report = report,
                    myVote = state.myVotes[report.id],
                    isOwn = report.userId != null && report.userId == state.user?.id,
                    onVote = { vote -> viewModel.vote(report, vote) },
                    onFlag = { reason -> viewModel.flag(report, reason) },
                    onDismiss = viewModel::dismissSelection,
                )
            }
            // A página do estabelecimento é desenhada no Box principal, em tela cheia.
            is Selection.BusinessSelected -> Unit
            is Selection.ZoneSelected -> ZoneSheet(zone = selection.zone, onDismiss = viewModel::dismissSelection)
            is Selection.NewReport -> NewReportSheet(
                busy = state.busy,
                onSubmit = { type, description -> viewModel.submitReport(type, selection.point, description) },
                onDismiss = viewModel::dismissSelection,
            )
            Selection.Nearby -> NearbySheet(
                beaches = state.nearbyBeaches(),
                onBeachClick = { beach ->
                    viewModel.selectBeach(beach.id)
                    scope.launch { mapState.flyTo(beach.point, 14.5) }
                },
                onDismiss = viewModel::dismissSelection,
            )
            null -> Unit
        }
    }

    if (state.showLogin) {
        LoginDialog(
            busy = state.busy,
            demoMode = state.demoMode,
            onSubmit = viewModel::signIn,
            onDismiss = viewModel::dismissLogin,
        )
    }
}

@Composable
private fun TopBar(demoMode: Boolean, loggedIn: Boolean, onLogin: () -> Unit, onLogout: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🏖️ Tuba", style = MaterialTheme.typography.titleMedium)
            if (demoMode) {
                Spacer(Modifier.width(8.dp))
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape) {
                    Text(
                        "demonstração",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = if (loggedIn) onLogout else onLogin) {
                Text(if (loggedIn) "Sair" else "Entrar")
            }
        }
    }
}

@Composable
private fun Legend() {
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp, shadowElevation = 2.dp) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LegendRow(StatusColors.Proper, "Própria (CPRH)")
            LegendRow(StatusColors.Improper, "Imprópria (CPRH)")
            LegendRow(StatusColors.NoData, "Sem dados recentes")
            LegendRow(Color(0xFFE65100), "Risco de tubarão")
        }
    }
}

@Composable
private fun LegendRow(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
