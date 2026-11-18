package com.tuba.data.fake

import com.tuba.domain.Beach
import com.tuba.domain.Business
import com.tuba.domain.BusinessCategory
import com.tuba.domain.CatalogItem
import com.tuba.domain.CatalogSection
import com.tuba.domain.GeoPoint
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Gera estabelecimentos fictícios na faixa de areia de cada trecho. Determinístico por praia,
 * para os mesmos pins aparecerem a cada abertura. Os nomes são inventados.
 */
object SimulatedBusinesses {
    private val names = mapOf(
        BusinessCategory.BEACH_BAR to listOf("Barraca Maré Mansa", "Barraca do Seu Zé", "Quiosque Sol a Pino", "Barraca Pé na Areia"),
        BusinessCategory.RESTAURANT to listOf("Sabor de Maré", "Peixada da Vila", "Cozinha do Pescador", "Casa do Caranguejo"),
        BusinessCategory.SURF_SCHOOL to listOf("Escola de Surf Onda Boa", "Swell Escola de Surf", "Remada Surf Clube"),
        BusinessCategory.RENTAL to listOf("Sombra & Água Fresca", "Guarda-Sol do Litoral", "Cadeira na Praia"),
        BusinessCategory.BOAT_TOUR to listOf("Jangada Azul Passeios", "Passeios Recife de Coral", "Catamarã Brisa"),
        BusinessCategory.LODGING to listOf("Pousada Brisa do Mar", "Pousada Coqueiral", "Pousada Areia Branca"),
    )

    private val descriptions = mapOf(
        BusinessCategory.BEACH_BAR to "Água de coco, petiscos e mesas na areia.",
        BusinessCategory.RESTAURANT to "Frutos do mar e pratos regionais de frente para o mar.",
        BusinessCategory.SURF_SCHOOL to "Aulas para iniciantes e aluguel de pranchas.",
        BusinessCategory.RENTAL to "Cadeiras, guarda-sóis e caixas térmicas por diária.",
        BusinessCategory.BOAT_TOUR to "Passeios até as piscinas naturais na maré baixa.",
        BusinessCategory.LODGING to "Quartos a poucos passos da praia, café da manhã incluso.",
    )

    private val offers = mapOf(
        BusinessCategory.BEACH_BAR to listOf("Água de coco grátis na primeira visita", "10% off mostrando o app"),
        BusinessCategory.RESTAURANT to listOf("Sobremesa por conta da casa", "10% off mostrando o app"),
        BusinessCategory.SURF_SCHOOL to listOf("Aula experimental pela metade do preço", "Pacote 5 aulas com 15% off"),
        BusinessCategory.RENTAL to listOf("Segunda cadeira grátis", "10% off mostrando o app"),
        BusinessCategory.BOAT_TOUR to listOf("Criança até 6 anos não paga", "10% off mostrando o app"),
        BusinessCategory.LODGING to listOf("Segunda diária com 20% de desconto", "Late check-out sem custo"),
    )

    private val hours = mapOf(
        BusinessCategory.BEACH_BAR to "Todos os dias, 8h às 17h",
        BusinessCategory.RESTAURANT to "Ter. a dom., 11h às 22h",
        BusinessCategory.SURF_SCHOOL to "Aulas às 7h, 9h e 15h (conforme a maré)",
        BusinessCategory.RENTAL to "Todos os dias, 7h às 17h",
        BusinessCategory.BOAT_TOUR to "Saídas na maré baixa; confira a tábua de marés",
        BusinessCategory.LODGING to "Check-in 14h · check-out 12h",
    )

    /** Preços-base em centavos; cada estabelecimento aplica uma variação própria. */
    private fun item(name: String, detail: String?, price: Int, unit: String? = null) = CatalogItem(name, detail, price, unit)

    private val catalogs: Map<BusinessCategory, List<CatalogSection>> = mapOf(
        BusinessCategory.BEACH_BAR to listOf(
            CatalogSection("Bebidas", listOf(
                item("Água de coco", "Gelada, servida no coco", 800),
                item("Caipirinha", "Limão, cajá ou maracujá", 2200),
                item("Cerveja long neck", null, 1200),
                item("Suco de fruta da estação", "Acerola, cajá, graviola", 1000),
            )),
            CatalogSection("Petiscos", listOf(
                item("Isca de peixe", "Com molho tártaro", 4800, "serve 2"),
                item("Camarão alho e óleo", null, 6500, "serve 2"),
                item("Queijo coalho na brasa", "Com melaço", 2800),
                item("Macaxeira frita", null, 2400),
            )),
        ),
        BusinessCategory.RESTAURANT to listOf(
            CatalogSection("Entradas", listOf(
                item("Casquinha de caranguejo", null, 2600),
                item("Bolinho de bacalhau", "6 unidades", 3200),
            )),
            CatalogSection("Pratos principais", listOf(
                item("Peixada pernambucana", "Com pirão e arroz", 13900, "serve 2"),
                item("Moqueca de camarão", "Com arroz e farofa", 15900, "serve 2"),
                item("Peixe grelhado do dia", "Com legumes", 6900),
                item("Arrumadinho de charque", null, 5400),
            )),
            CatalogSection("Sobremesas", listOf(
                item("Cartola", "Banana, queijo, açúcar e canela", 2200),
                item("Bolo de rolo com sorvete", null, 2400),
            )),
        ),
        BusinessCategory.SURF_SCHOOL to listOf(
            CatalogSection("Aulas", listOf(
                item("Aula experimental", "1h30, prancha e lycra inclusas", 12000, "por aluno"),
                item("Aula particular", "1h30 com instrutor exclusivo", 22000),
                item("Pacote 5 aulas", "Em grupo de até 4 alunos", 45000, "por aluno"),
            )),
            CatalogSection("Aluguel", listOf(
                item("Prancha soft", null, 6000, "diária"),
                item("Prancha de fibra", null, 8000, "diária"),
            )),
        ),
        BusinessCategory.RENTAL to listOf(
            CatalogSection("Diárias", listOf(
                item("Kit 2 cadeiras + guarda-sol", null, 4000, "diária"),
                item("Cadeira avulsa", null, 1500, "diária"),
                item("Caixa térmica com gelo", null, 2000, "diária"),
                item("Esteira de praia", null, 1000, "diária"),
            )),
        ),
        BusinessCategory.BOAT_TOUR to listOf(
            CatalogSection("Passeios", listOf(
                item("Jangada às piscinas naturais", "Cerca de 1h, saída na maré baixa", 4000, "por pessoa"),
                item("Passeio de catamarã", "2h com parada para banho", 9000, "por pessoa"),
                item("Pôr do sol no barco", "1h30 com água e frutas", 12000, "por pessoa"),
            )),
        ),
        BusinessCategory.LODGING to listOf(
            CatalogSection("Quartos", listOf(
                item("Quarto standard", "Casal, ar-condicionado, café incluso", 32000, "diária"),
                item("Quarto vista mar", "Casal com varanda", 45000, "diária"),
                item("Suíte família", "Até 4 pessoas", 58000, "diária"),
            )),
        ),
    )

    /** Mesmo cardápio, preços com variação de -10% a +15%, arredondados em R$ 0,50. */
    private fun priced(category: BusinessCategory, random: Random): List<CatalogSection> {
        val factor = 0.9 + random.nextInt(26) / 100.0
        return catalogs.getValue(category).map { section ->
            section.copy(items = section.items.map { it.copy(priceCents = ((it.priceCents * factor) / 50).roundToInt() * 50) })
        }
    }

    fun generate(beaches: List<Beach>): List<Business> = beaches.flatMap { beach ->
        val shore = shorePoints(beach)
        if (shore.size < 4) return@flatMap emptyList()

        val random = Random(beach.id.hashCode())
        val categories = BusinessCategory.entries.shuffled(random).take(2 + random.nextInt(2))
        categories.mapIndexed { index, category ->
            // Espalha os pins ao longo do trecho, evitando as pontas.
            val fraction = (index + 1.0) / (categories.size + 1)
            val point = shore[(fraction * (shore.size - 1)).roundToInt()]
            val sponsored = random.nextInt(3) == 0
            Business(
                id = "${beach.id}-${category.name.lowercase()}",
                beachId = beach.id,
                name = names.getValue(category).random(random),
                category = category,
                point = point,
                rating = 3.8 + random.nextInt(12) / 10.0,
                reviews = 12 + random.nextInt(400),
                description = descriptions.getValue(category),
                sponsored = sponsored,
                offer = if (sponsored) offers.getValue(category).random(random) else null,
                hours = hours.getValue(category),
                catalog = priced(category, random),
            )
        }
    }

    /**
     * O anel gerado por tools/build_areas.py começa pela borda do lado da terra e volta pela borda
     * do mar. A primeira metade é a faixa de areia, onde ficam os estabelecimentos.
     */
    private fun shorePoints(beach: Beach): List<GeoPoint> {
        val ring = beach.area?.ring ?: return emptyList()
        return ring.subList(0, (ring.size - 1) / 2)
    }
}
