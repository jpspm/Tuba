package com.tuba.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.tuba.domain.Beach
import com.tuba.domain.Business
import com.tuba.domain.FlagReason
import com.tuba.domain.OfficialReading
import com.tuba.domain.OfficialStatus
import com.tuba.domain.Report
import com.tuba.domain.ReportKind
import com.tuba.domain.ReportType
import com.tuba.domain.RiskZone
import com.tuba.domain.Vote
import com.tuba.ui.map.MarineState
import com.tuba.ui.map.NearbyBeach
import kotlin.math.pow
import com.tuba.ui.formatIsoDate
import com.tuba.ui.theme.StatusColors
import com.tuba.ui.timeAgo
import com.tuba.ui.timeLeft

private const val DESCRIPTION_MAX = 280

/**
 * As bottom sheets ficam numa janela acima do mapa e cobrem o snackbar da tela.
 * Cada sheet desenha o mesmo snackbar dentro dela.
 */
val LocalSheetSnackbar = staticCompositionLocalOf<SnackbarHostState?> { null }

@Composable
private fun SheetSnackbar() {
    LocalSheetSnackbar.current?.let { SnackbarHost(it) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeachSheet(
    beach: Beach,
    zones: List<RiskZone>,
    businesses: List<Business>,
    onBusinessClick: (Business) -> Unit,
    marine: MarineState?,
    nearbyReports: List<Report>,
    onReportClick: (Report) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column {
                Text(beach.name, style = MaterialTheme.typography.headlineSmall)
                Text(beach.city, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            val official = beach.official
            val stale = official.isStale()
            val status = official.effectiveStatus()
            val statusColor = StatusColors.of(status)
            Surface(color = statusColor.copy(alpha = 0.12f), shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Balneabilidade oficial", style = MaterialTheme.typography.labelMedium)
                    Text(status.label, style = MaterialTheme.typography.titleMedium, color = statusColor)
                    if (stale && official.sampledOn != null) {
                        Text(
                            "O último boletim (coleta em ${formatIsoDate(official.sampledOn)}) dizia " +
                                "\"${official.status.label.lowercase()}\", mas tem mais de " +
                                "${OfficialReading.STALE_AFTER_DAYS} dias e pode não valer mais.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    val detail = buildString {
                        append("Fonte: ").append(official.source)
                        if (!stale) official.sampledOn?.let { append(" · coleta em ").append(formatIsoDate(it)) }
                    }
                    Text(detail, style = MaterialTheme.typography.bodySmall)
                    beach.reference?.let {
                        Text("Ponto de coleta: $it", style = MaterialTheme.typography.bodySmall)
                    }
                    if (status == OfficialStatus.IMPROPRIA) {
                        Text("Evite o banho de mar neste trecho.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            zones.forEach { zone ->
                Surface(color = Color(0xFFE65100).copy(alpha = 0.12f), shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("🦈 ${zone.name}", style = MaterialTheme.typography.titleSmall, color = Color(0xFFE65100))
                        Text(zone.description, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            MarineSection(marine)

            if (businesses.isNotEmpty()) {
                HorizontalDivider()
                Text("Serviços neste trecho", style = MaterialTheme.typography.titleSmall)
                businesses.forEach { business ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onBusinessClick(business) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(40.dp).background(Color(business.category.color), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Text(business.category.emoji, fontSize = 20.sp) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(business.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                listOfNotNull(
                                    business.category.label,
                                    "★ ${business.rating.toString().replace('.', ',')}",
                                    if (business.sponsored) "Patrocinado" else null,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("›", style = MaterialTheme.typography.titleLarge)
                    }
                }
                Text(
                    "Estabelecimentos simulados para demonstração.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()
            Text("Reports da comunidade por perto", style = MaterialTheme.typography.titleSmall)
            if (nearbyReports.isEmpty()) {
                Text(
                    "Nenhum report ativo a até 1,5 km. Segure o dedo no mapa para criar um.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                nearbyReports.forEach { report ->
                    ReportRow(report, onClick = { onReportClick(report) })
                }
            }

            Text(
                "A balneabilidade mede só a qualidade microbiológica da água. O app não substitui as " +
                    "bandeiras e as orientações dos guarda-vidas.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SheetSnackbar()
        }
    }
}

@Composable
private fun MarineSection(marine: MarineState?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Mar e vento agora", style = MaterialTheme.typography.titleSmall)
        when (marine) {
            null, MarineState.Loading -> Text(
                "Carregando previsão...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MarineState.Failed -> Text(
                "Não foi possível carregar a previsão.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is MarineState.Loaded -> {
                val c = marine.conditions
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricTile(
                        "🌊 Ondas",
                        c.waveHeightM?.let { "${it.format(1)} m" } ?: "—",
                        listOfNotNull(c.wavePeriodS?.let { "${it.format(0)} s" }, c.waveDirectionDeg?.let { compass(it) })
                            .joinToString(" · "),
                        Modifier.weight(1f),
                    )
                    MetricTile(
                        "💨 Vento",
                        c.windSpeedKmh?.let { "${it.format(0)} km/h" } ?: "—",
                        listOfNotNull(c.windGustsKmh?.let { "rajadas ${it.format(0)}" }, c.windDirectionDeg?.let { compass(it) })
                            .joinToString(" · "),
                        Modifier.weight(1f),
                    )
                    MetricTile(
                        "🌡️ Água",
                        c.waterTempC?.let { "${it.format(0)} °C" } ?: "—",
                        "",
                        Modifier.weight(1f),
                    )
                }
                Text(
                    "Previsão de modelo (Open-Meteo), não medição local.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MetricTile(title: String, value: String, detail: String, modifier: Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = modifier) {
        Column(Modifier.padding(10.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleMedium)
            if (detail.isNotEmpty()) {
                Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Direção de onde vem o vento/ondulação. */
private fun compass(degrees: Double): String {
    val names = listOf("N", "NE", "L", "SE", "S", "SO", "O", "NO")
    return "de " + names[(((degrees % 360) + 360) % 360 / 45.0 + 0.5).toInt() % 8]
}

private fun Double.format(decimals: Int): String {
    if (decimals == 0) return kotlin.math.round(this).toInt().toString()
    val factor = 10.0.pow(decimals)
    val rounded = kotlin.math.round(this * factor) / factor
    return rounded.toString().replace('.', ',')
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZoneSheet(zone: RiskZone, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("🦈 ${zone.name}", style = MaterialTheme.typography.headlineSmall)
            Text(zone.description, style = MaterialTheme.typography.bodyLarge)
            Text(
                "Fonte: ${zone.source}. Limites aproximados: confira a sinalização na praia.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbySheet(beaches: List<NearbyBeach>, onBeachClick: (Beach) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Praias perto de você", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            if (beaches.isEmpty()) {
                Text("Aguardando sua localização...", style = MaterialTheme.typography.bodyMedium)
            }
            beaches.forEach { (beach, distance) ->
                val status = beach.official.effectiveStatus()
                Row(
                    Modifier.fillMaxWidth().clickable { onBeachClick(beach) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(14.dp).background(StatusColors.of(status), CircleShape))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(beach.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${beach.city} · ${status.label}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(formatDistance(distance), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

private fun formatDistance(meters: Double): String = when {
    meters < 50 -> "aqui"
    meters < 1_000 -> "${(meters / 10).toInt() * 10} m"
    else -> "${(meters / 1_000).format(1)} km"
}

@Composable
private fun ReportRow(report: Report, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TypeBadge(report.type)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(report.type.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${report.createdAt.timeAgo()} · 👍 ${report.confirmations} · 👎 ${report.denials}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TypeBadge(type: ReportType, size: Int = 40) {
    Box(
        Modifier.size(size.dp).background(Color(type.color), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(type.emoji, fontSize = (size / 2).sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportSheet(
    report: Report,
    myVote: Vote?,
    isOwn: Boolean,
    onVote: (Vote) -> Unit,
    onFlag: (FlagReason) -> Unit,
    onDismiss: () -> Unit,
) {
    var showFlag by remember { mutableStateOf(false) }
    if (showFlag) {
        AlertDialog(
            onDismissRequest = { showFlag = false },
            title = { Text("Denunciar report") },
            text = {
                Column {
                    Text("Qual é o problema?", style = MaterialTheme.typography.bodyMedium)
                    FlagReason.entries.forEach { reason ->
                        TextButton(onClick = {
                            showFlag = false
                            onFlag(reason)
                        }) { Text(reason.label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showFlag = false }) { Text("Cancelar") } },
        )
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypeBadge(report.type, size = 52)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(report.type.label, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Reportado ${report.createdAt.timeAgo()} · ${report.expiresAt.timeLeft()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            report.description?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }

            if (isOwn) {
                Text(
                    "Este report é seu. Outros usuários podem confirmar ou negar.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    if (report.type.kind == ReportKind.ALERT) "Ainda está acontecendo?" else "Continua assim?",
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    VoteButton(
                        label = "👍 Confirmar (${report.confirmations})",
                        selected = myVote == Vote.CONFIRM,
                        onClick = { onVote(Vote.CONFIRM) },
                        modifier = Modifier.weight(1f),
                    )
                    VoteButton(
                        label = "👎 Não está mais (${report.denials})",
                        selected = myVote == Vote.DENY,
                        onClick = { onVote(Vote.DENY) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    "Confirmações estendem a validade. Com 3 ou mais negações (e mais negações que confirmações) o report some do mapa.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { showFlag = true }) { Text("🚩 Denunciar report") }
            }
            SheetSnackbar()
        }
    }
}

@Composable
private fun VoteButton(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewReportSheet(
    busy: Boolean,
    onSubmit: (ReportType, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var type by remember { mutableStateOf<ReportType?>(null) }
    var description by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Novo report", style = MaterialTheme.typography.headlineSmall)

            ReportKind.entries.forEach { kind ->
                Text(
                    if (kind == ReportKind.POSITIVE) "Condições boas" else "Alertas",
                    style = MaterialTheme.typography.titleSmall,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReportType.entries.filter { it.kind == kind }.forEach { option ->
                        FilterChip(
                            selected = type == option,
                            onClick = { type = option },
                            label = { Text("${option.emoji} ${option.label}") },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = description,
                onValueChange = { description = it.take(DESCRIPTION_MAX) },
                label = { Text("Detalhes (opcional)") },
                supportingText = { Text("${description.length}/$DESCRIPTION_MAX") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )

            type?.let {
                Text(
                    "Some do mapa em ${it.lifetimeHours}h, a menos que outros usuários confirmem.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(
                onClick = { type?.let { onSubmit(it, description) } },
                enabled = type != null && !busy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(),
            ) {
                Text(if (busy) "Publicando..." else "Publicar")
            }
            SheetSnackbar()
        }
    }
}

@Composable
fun LoginDialog(
    busy: Boolean,
    demoMode: Boolean,
    onSubmit: (email: String, password: String, createAccount: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var createAccount by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (createAccount) "Criar conta" else "Entrar") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Você precisa de uma conta para reportar e votar. Ver o mapa é livre.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (demoMode) {
                    Text(
                        "Modo demonstração: qualquer e-mail e senha com 6+ caracteres funciona.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("E-mail") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Senha") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { createAccount = !createAccount }) {
                    Text(if (createAccount) "Já tenho conta" else "Criar uma conta")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(email, password, createAccount) },
                enabled = !busy && email.isNotBlank() && password.isNotBlank(),
            ) { Text(if (busy) "Aguarde..." else if (createAccount) "Criar" else "Entrar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
