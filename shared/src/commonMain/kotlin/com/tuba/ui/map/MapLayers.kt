package com.tuba.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tuba.domain.Area
import com.tuba.domain.Beach
import com.tuba.domain.OfficialStatus
import com.tuba.domain.RiskZone
import com.tuba.ui.theme.StatusColors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.location.LocationState
import org.maplibre.compose.layers.LocationIndicatorLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable

private val SharkZoneColor = Color(0xFFE65100)

/** Áreas no estilo do mapa: zona de risco por baixo, trechos de praia por cima. */
@Composable
@MaplibreComposable
internal fun TubaLayers(
    beaches: List<Beach>,
    riskZones: List<RiskZone>,
    selectedBeachId: String?,
    locationState: LocationState,
    onBeachClick: (String) -> Unit,
    onZoneClick: (RiskZone) -> Unit,
) {
    val zonesJson = remember(riskZones) { featureCollection(riskZones.map { it.id to it.area }) }
    AreaLayers(
        id = "risk-shark",
        geoJson = zonesJson,
        fill = SharkZoneColor,
        fillOpacity = 0.10f,
        outline = SharkZoneColor,
        outlineWidth = 1.5f,
        dashed = true,
        onClick = { id -> riskZones.firstOrNull { it.id == id }?.let(onZoneClick) },
    )

    val byStatus = remember(beaches) {
        beaches.filter { it.area != null }.groupBy { it.official.effectiveStatus() }
    }
    OfficialStatus.entries.forEach { status ->
        val json = remember(byStatus, status) {
            featureCollection(byStatus[status].orEmpty().map { it.id to it.area!! })
        }
        val color = StatusColors.of(status)
        AreaLayers(
            id = "beach-${status.name.lowercase()}",
            geoJson = json,
            fill = color,
            fillOpacity = 0.45f,
            outline = color,
            outlineWidth = 1f,
            dashed = false,
            onClick = onBeachClick,
        )
    }

    val selected = beaches.firstOrNull { it.id == selectedBeachId }?.area
    val selectedJson = remember(selected) {
        featureCollection(listOfNotNull(selected?.let { "selected" to it }))
    }
    AreaLayers(
        id = "beach-selected",
        geoJson = selectedJson,
        fill = Color.Transparent,
        fillOpacity = 0f,
        outline = Color(0xFF0D47A1),
        outlineWidth = 3f,
        dashed = false,
        onClick = null,
    )

    LocationIndicatorLayer(id = "my-location", locationState = locationState)
}

@Composable
@MaplibreComposable
private fun AreaLayers(
    id: String,
    geoJson: String,
    fill: Color,
    fillOpacity: Float,
    outline: Color,
    outlineWidth: Float,
    dashed: Boolean,
    onClick: ((String) -> Unit)?,
) {
    // A fonte GeoJSON é imutável: uma nova é criada quando os dados mudam.
    val source = key(geoJson) { rememberGeoJsonSource(GeoJsonData.JsonString(geoJson)) }
    FillLayer(
        id = "$id-fill",
        source = source,
        color = const(fill),
        opacity = const(fillOpacity),
        onClick = onClick?.let { handler ->
            { features ->
                val featureId = features.firstNotNullOfOrNull { feature ->
                    feature.properties?.get("id")?.jsonPrimitive?.contentOrNull
                }
                if (featureId != null) {
                    handler(featureId)
                    ClickResult.Consume
                } else {
                    ClickResult.Pass
                }
            }
        },
    )
    LineLayer(
        id = "$id-line",
        source = source,
        color = const(outline),
        width = const(outlineWidth.dp),
        dasharray = if (dashed) const(listOf(3, 2)) else null,
    )
}

private fun featureCollection(items: List<Pair<String, Area>>): String = buildJsonObject {
    put("type", "FeatureCollection")
    put(
        "features",
        JsonArray(
            items.map { (id, area) ->
                buildJsonObject {
                    put("type", "Feature")
                    put("properties", buildJsonObject { put("id", id) })
                    put(
                        "geometry",
                        buildJsonObject {
                            put("type", "Polygon")
                            put(
                                "coordinates",
                                buildJsonArray {
                                    add(
                                        JsonArray(
                                            area.ring.map { p ->
                                                JsonArray(listOf(JsonPrimitive(p.longitude), JsonPrimitive(p.latitude)))
                                            },
                                        ),
                                    )
                                },
                            )
                        },
                    )
                }
            },
        ),
    )
}.toString()
