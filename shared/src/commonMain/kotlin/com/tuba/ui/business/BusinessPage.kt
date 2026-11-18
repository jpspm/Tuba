package com.tuba.ui.business

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tuba.domain.Beach
import com.tuba.domain.Business
import com.tuba.domain.BusinessCategory
import com.tuba.domain.CatalogItem
import com.tuba.domain.OfficialStatus
import com.tuba.ui.theme.StatusColors

data class BookingRequest(val name: String, val date: String, val party: Int, val notes: String)

/** Página de um estabelecimento simulado: dados, catálogo e pedido de reserva. */
@Composable
fun BusinessPage(
    business: Business,
    beach: Beach?,
    onBook: (BookingRequest) -> Unit,
    onBack: () -> Unit,
) {
    var showBooking by remember { mutableStateOf(false) }
    val color = Color(business.category.color)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                // Cabeçalho colorido pela categoria, no lugar da foto.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(color)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .height(150.dp),
                ) {
                    TextButton(onClick = onBack, modifier = Modifier.padding(4.dp)) {
                        Text("←  Voltar", color = Color.White)
                    }
                    Text(
                        business.category.emoji,
                        fontSize = 64.sp,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    SimulationBadge(Modifier.align(Alignment.TopEnd).padding(12.dp))
                }

                Column(
                    Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(business.name, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "${business.category.label} · ★ ${business.rating.toString().replace('.', ',')} " +
                                "(${business.reviews} avaliações)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(business.description, style = MaterialTheme.typography.bodyLarge)
                    }

                    if (business.sponsored && business.offer != null) {
                        Surface(color = color.copy(alpha = 0.14f), shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                Text("Patrocinado", style = MaterialTheme.typography.labelMedium, color = color)
                                Text("🎁 ${business.offer}", style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }

                    InfoRow("🕒", business.hours)
                    beach?.let { b ->
                        val status = b.official.effectiveStatus()
                        InfoRow("📍", "Praia: ${b.name}, ${b.city}")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).background(StatusColors.of(status), CircleShape))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Balneabilidade: ${status.label.lowercase()} (CPRH)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (status == OfficialStatus.PROPRIA) StatusColors.Proper else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    InfoRow("📞", "Contato e reservas pelo app")

                    HorizontalDivider()
                    Text(business.category.catalogTitle, style = MaterialTheme.typography.titleLarge)
                    business.catalog.forEach { section ->
                        Text(
                            section.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = color,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        section.items.forEach { CatalogRow(it) }
                    }

                    Text(
                        "Estabelecimento fictício, criado para demonstrar como negócios locais aparecem no app. " +
                            "Nomes, preços e avaliações são simulados.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
                Button(
                    onClick = { showBooking = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(16.dp)
                        .height(52.dp),
                ) { Text(business.category.bookingAction, fontWeight = FontWeight.SemiBold) }
            }
        }
    }

    if (showBooking) {
        BookingDialog(
            category = business.category,
            onConfirm = {
                showBooking = false
                onBook(it)
            },
            onDismiss = { showBooking = false },
        )
    }
}

@Composable
private fun SimulationBadge(modifier: Modifier = Modifier) {
    Surface(color = Color.Black.copy(alpha = 0.35f), shape = CircleShape, modifier = modifier) {
        Text(
            "simulação",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun InfoRow(icon: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 16.sp)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CatalogRow(item: CatalogItem) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge)
            listOfNotNull(item.detail, item.unit).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(formatPrice(item.priceCents), style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun BookingDialog(
    category: BusinessCategory,
    onConfirm: (BookingRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var party by remember { mutableIntStateOf(2) }
    val dateLabel = if (category == BusinessCategory.LODGING) "Check-in e check-out (ex.: 20/12 a 23/12)" else "Data e horário (ex.: 20/12, 13h)"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(category.bookingAction) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    label = { Text("Seu nome") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it.take(40) },
                    label = { Text(dateLabel) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(category.partyLabel, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { if (party > 1) party-- }) { Text("−") }
                    Text("$party", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 12.dp))
                    OutlinedButton(onClick = { if (party < 20) party++ }) { Text("+") }
                }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it.take(140) },
                    label = { Text("Observações (opcional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Simulação: o pedido não é enviado a nenhum estabelecimento.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(BookingRequest(name.trim(), date.trim(), party, notes.trim())) },
                enabled = name.isNotBlank() && date.isNotBlank(),
            ) { Text("Enviar pedido") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

private fun formatPrice(cents: Int): String {
    val reais = cents / 100
    val centavos = (cents % 100).toString().padStart(2, '0')
    val grouped = reais.toString().reversed().chunked(3).joinToString(".").reversed()
    return "R$ $grouped,$centavos"
}
