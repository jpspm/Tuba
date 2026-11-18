# Tuba

App mobile (Kotlin Multiplatform + Compose Multiplatform) que mostra no mapa a qualidade das praias de Pernambuco para banho e esportes.

- **Balneabilidade oficial (CPRH):** os 27 pontos de coleta aparecem como **áreas** (faixa de areia e mar) coloridas pela situação: própria, imprópria ou sem dados recentes. Boletim com mais de 12 dias desde a coleta é exibido como "sem dados recentes".
- **Área de risco de tubarão:** o trecho do Decreto Estadual 21.402/1999 (Praia do Farol, em Olinda, até o Paiva), tracejado no mapa e destacado nas praias afetadas.
- **Reports da comunidade, no estilo Waze:** 🏄 surf, 🏊 nadar, 🌊 mar agitado, 🗑️ poluição, 🚱 esgoto, 🦈 tubarão, 🪼 águas-vivas, ⚠️ outro perigo, 👥 lotada. Cada report expira sozinho e pode ser confirmado, negado ou denunciado. Só é possível reportar a até 2 km de onde você está, com no máximo 5 reports por hora.
- **Mar e vento:** ondas, vento e temperatura da água via Open-Meteo, na tela de cada praia.
- **Perto de mim:** praias mais próximas, ordenadas pela distância até a área de cada uma.
- **Estabelecimentos (simulação):** barracas, restaurantes, escolas de surf, aluguel, passeios e pousadas fictícios, com pins no estilo Google Maps na faixa de areia. Cada um tem página própria com cardápio, quartos ou preços e pedido de reserva dentro do app (não enviado a ninguém). Aparecem só em trechos próprios para banho, e escolas de surf não aparecem na zona de tubarão. Servem para demonstrar o modelo de receita com negócios locais. Gerados em `data/fake/SimulatedBusinesses.kt`.

Ver o mapa é livre. Reportar, votar e denunciar exigem login.

## Stack

| Camada | Escolha |
|---|---|
| UI | Compose Multiplatform 1.12 (Android e iOS) |
| Mapa | MapLibre Compose 0.20 (runtime OpenGL no Android) + estilo OpenFreeMap |
| Localização | provedor do MapLibre Compose (pede a permissão em cada plataforma) |
| Previsão | Open-Meteo (Marine + Forecast) |
| Backend | Supabase via supabase-kt 3.9 (**ainda não ativado**, veja abaixo) |
| Build | Kotlin 2.4, AGP 9.5, Gradle 9.6 |

## Estrutura

```
shared/                 módulo KMP com domínio, dados, ViewModel e UI
  domain/                 modelos (Beach, Area, RiskZone, Report...)
  data/fake/              repositórios em memória (modo demonstração) + SeedData.kt gerado
  data/supabase/          repositórios do Supabase
  data/OpenMeteoRepository.kt
  ui/map/                 mapa, camadas de áreas (MapLayers.kt), marcadores, ViewModel
  ui/sheets/              detalhes de praia, report, zona, "perto de mim", login
androidApp/             app Android
iosApp/                 fontes SwiftUI que hospedam o Compose
data/
  cprh_points.json        os 27 pontos da CPRH + zona de risco (edite aqui)
  boletins/AAAA-MM-DD.csv boletins transcritos (data = coleta)
tools/
  build_areas.py          gera as áreas a partir da costa do OSM
  import_bulletin.py      valida um boletim e gera o SQL de importação
supabase/               migrations, seeds e testes SQL (backend, ainda não ativado)
```

## Rodando

Sem configurar nada, o app roda em **modo demonstração**: usa os 27 trechos e o boletim mais recente de `data/boletins/` (dados reais), mas com reports de exemplo e login local (qualquer e-mail, senha com 6+ caracteres). Nada é enviado para servidor.

```
./gradlew :androidApp:installDebug
```

Ou abra a pasta no Android Studio e rode a configuração `androidApp`. O JDK do Android Studio serve (`JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"`).

### iOS

Precisa de macOS com Xcode. Crie um projeto iOS em `iosApp/` (ou gere um com o KMP wizard da JetBrains) usando os arquivos de `iosApp/iosApp/` e:

1. adicione uma Run Script phase antes de "Compile Sources":
   ```
   cd "$SRCROOT/.."
   ./gradlew :shared:embedAndSignAppleFrameworkForXcode
   ```
2. adicione ao `Info.plist` a chave `NSLocationWhenInUseUsageDescription`, por exemplo: "Usamos sua localização para mostrar praias próximas e validar reports."

A parte iOS ainda não foi compilada (o desenvolvimento até aqui foi no Windows).

## Atualizando os dados

### Boletim semanal da CPRH

1. Transcreva o boletim em `data/boletins/AAAA-MM-DD.csv` (data da **coleta**), com `point_id,status` (`propria` ou `impropria`). Use os ids de `data/cprh_points.json`.
2. Rode `python tools/build_areas.py` para atualizar o `SeedData.kt` do modo demonstração.
3. Para o Supabase (quando ativado): `python tools/import_bulletin.py data/boletins/AAAA-MM-DD.csv` gera o SQL, e `--apply` envia direto (requer `SUPABASE_URL` e `SUPABASE_SERVICE_ROLE_KEY`).

O site da CPRH bloqueia acesso automatizado, então a transcrição é manual. O boletim de 09/11/2026 foi transcrito de matérias do Diario de Pernambuco e do Papo de Peso (13/11/2026).

### Áreas dos trechos

`python tools/build_areas.py` baixa a linha de costa do OpenStreetMap (com cache em `tools/.cache/`; use `--refresh` para baixar de novo), encaixa cada ponto nela e gera uma faixa de até 1,8 km de costa, com 35 m de areia e 220 m de mar. Ele regenera `data/areas.geojson` (dá para inspecionar em geojson.io), `supabase/seed.sql` e `SeedData.kt`.

**Limitações:** as coordenadas dos pontos são estimativas a partir das referências do boletim, e os trechos são uma aproximação, não limites oficiais. Os limites da zona de tubarão também são aproximados. Corrija em `data/cprh_points.json` e rode o script de novo.

## Backend (Supabase) — ainda não ativado

O schema está escrito, mas não foi aplicado nem testado contra um banco:

- `supabase/migrations/0001_init.sql`: praias, leituras, reports, votos, RLS.
- `supabase/migrations/0002_areas_location_moderation.sql`: áreas, zonas de risco, proximidade de 2 km, limite por hora, denúncias.
- `supabase/tests/` + `tools/test_db.sh`: testes das regras num Postgres descartável (Docker).

Para ativar: rode as migrations, `seed.sql` e `readings_*.sql`, preencha `supabase.url` e `supabase.anonKey` no `local.properties` e recompile.

## Atribuições

- Mapa e linha de costa: © colaboradores do OpenStreetMap (ODbL), tiles OpenFreeMap / OpenMapTiles.
- Balneabilidade: CPRH. Área de risco: CEMIT / Decreto Estadual 21.402/1999.
- Previsão: Open-Meteo (uso não comercial gratuito; uso comercial exige plano pago).

A balneabilidade mede só a qualidade microbiológica da água. O app não substitui as bandeiras e as orientações dos guarda-vidas.
