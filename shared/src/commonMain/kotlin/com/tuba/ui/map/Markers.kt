package com.tuba.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tuba.domain.Beach
import com.tuba.domain.Business
import com.tuba.domain.OfficialStatus
import com.tuba.domain.Report
import com.tuba.ui.theme.StatusColors

private val PinTail = GenericShape { size, _ ->
    moveTo(0f, 0f)
    lineTo(size.width, 0f)
    lineTo(size.width / 2f, size.height)
    close()
}

/** Ponto oficial de monitoramento: círculo colorido pela situação do boletim. */
@Composable
fun BeachMarker(beach: Beach, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val status = beach.official.effectiveStatus()
    val color = StatusColors.of(status)
    Box(
        modifier = modifier
            .size(26.dp)
            .shadow(3.dp, CircleShape)
            .background(Color.White, CircleShape)
            .border(3.dp, color, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = when (status) {
                OfficialStatus.PROPRIA -> "✓"
                OfficialStatus.IMPROPRIA -> "✕"
                OfficialStatus.SEM_DADOS -> "?"
            },
            color = color,
            fontSize = 13.sp,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** Gota no estilo do Google Maps: círculo com ponta embaixo; a ponta marca o local. */
private val MapPinShape = GenericShape { size, _ ->
    val r = size.width / 2f
    addArc(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.width), 140f, 260f)
    lineTo(r, size.height)
    close()
}

/** Pin de estabelecimento. Use com alinhamento BottomCenter para a ponta cair no local. */
@Composable
fun BusinessPin(business: Business, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = Color(business.category.color)
    Box(
        modifier = modifier
            .size(width = 30.dp, height = 40.dp)
            .shadow(3.dp, MapPinShape)
            .background(color, MapPinShape)
            .border(1.5.dp, Color.White, MapPinShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            Modifier.padding(top = 4.dp).size(22.dp).background(Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(business.category.emoji, fontSize = 13.sp)
        }
    }
}

/** Nome ao lado do pin, com contorno para ler sobre qualquer fundo (como no Google Maps). */
@Composable
fun BusinessLabel(business: Business, modifier: Modifier = Modifier) {
    val color = Color(business.category.color)
    Box(modifier) {
        // Contorno: o mesmo texto em branco, mais grosso, por baixo.
        Text(
            business.name,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium.copy(
                drawStyle = Stroke(width = 6f, join = StrokeJoin.Round),
            ),
            maxLines = 1,
        )
        Text(business.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
    }
}

/** Report de usuário: balão com o ícone do tipo, mais apagado conforme se aproxima de expirar. */
@Composable
fun ReportMarker(report: Report, faded: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = Color(report.type.color)
    Column(
        modifier = modifier
            .alpha(if (faded) 0.6f else 1f)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .shadow(4.dp, CircleShape)
                .background(color, CircleShape)
                .border(2.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(report.type.emoji, fontSize = 19.sp)
        }
        Box(Modifier.size(width = 12.dp, height = 8.dp).background(color, PinTail))
    }
}
