---
type: Benchmark Scenario
title: Scenario del sistema solare
description: Scenario di riferimento per dimensionare i benchmark degli indici, con posizioni fino a 200 UA in metri, entità fino a 1000 km/h e 30 tick al secondo; da qui il passo per tick, la dimensione minima delle celle e il limite di precisione dei double.
tags: [badspace, spatial-indexing, benchmark, scenario, tick, precision]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-05T12:53:10Z }
---

# Ipotesi

Uno scenario concreto per scegliere i valori dei benchmark, in
particolare il passo del movimento e la dimensione delle celle:

| Grandezza | Valore |
|---|---|
| Spazio | posizioni fino a **200 UA** dall'origine: 200 × 149 597 870 700 m ≈ 2,99·10¹³ m |
| Unità delle coordinate | **1 metro**, come nei prototipi (vedi [tipo delle coordinate](/decisions/coordinate-type.md)) |
| Velocità massima delle entità | **1000 km/h** ≈ 277,8 m/s |
| Tick al secondo | **30** (`DT = 1/30 s`) |

# Passo per tick

Lo spostamento in un tick è `d = v · DT`: con la velocità massima vale
circa **9,26 m**.

| v (km/h) | d per tick | `step` nei benchmark |
|---|---|---|
| 1000 | 9,26 m | 10 |
| 300 | 2,78 m | 3 |
| 100 | 0,93 m | 1 |
| 30 | 0,28 m | 0,3 |
| 10 | 0,09 m | 0,1 |

Conseguenze:

- il passo di default dei benchmark, **10 unità**, è l'entità più veloce
  dello scenario, arrotondata per eccesso;
- le misure già fatte con `FAR_CLUSTER` (foglie di 2–4 m, `r` tra 2,5 e
  5, 98% di cambi di foglia, vedi la [frequenza dei cambi di
  foglia](/indices/grid-quadtree.md#frequenza-dei-cambi-di-foglia)) sono
  quindi già il **caso peggiore** dello scenario;
- passi più lunghi di 10 non servono, a meno di tick più lenti o
  velocità più alte.

# Limiti degli indici

Gli indici a celle accettano coordinate fino a `2^30 · cellSize` (vedi
[limiti delle
coordinate](/decisions/spatial-indexing.md#limiti-delle-coordinate)).
Per 200 UA serve `cellSize ≥ 2,99·10¹³ / 2^30 ≈ 27 860 m`; per la
[griglia di quadtree](/indices/grid-quadtree.md), che vuole una potenza
di 2, almeno **32768**.

| Indice | Limite | In UA |
|---|---|---|
| `GRID_QUADTREE_256` | 2,7·10¹¹ m | circa 1,8 |
| `UNIFORM_GRID_400` | 4,3·10¹¹ m | circa 2,9 |
| `GRID_QUADTREE_32768` | 3,5·10¹³ m | circa 235 |

- Con celle da 32768 conta solo la **dimensione delle foglie**, che
  dipende dalla densità (`L ≈ √(k / ρ)`): con 24 livelli le foglie
  arrivano a circa 2 mm, quindi la profondità basta.
- Una [grid uniforme](/indices/uniform-grid.md) da 32768 non cambia
  quasi mai cella (`r ≈ 3·10⁻⁴`), ma le sue query nei cluster densi
  costano molto.

# Precisione

A 3·10¹³ m la distanza tra due double (ulp) è 2⁻⁸ m ≈ **3,9 mm**. Uno
spostamento in un tick vale:

| v (km/h) | d per tick | In ulp | Effetto |
|---|---|---|---|
| 1000 | 9,26 m | circa 2400 | nessuno |
| 10 | 9 cm | circa 24 | errore di circa il 4% sullo spostamento |
| 1 | 9 mm | circa 2 | movimento a scatti |

È il caso concreto dell'avvertenza sugli errori che si accumulano nel
[tipo delle coordinate](/decisions/coordinate-type.md#limiti-del-double).
`FAR_CLUSTER`, a 10⁸ m (circa 0,67 UA, ulp circa 15 nm), non lo mostra.

# Nei benchmark

- **Parametro `step`:** la lunghezza di uno spostamento LOCAL, default
  10.
- **Distribuzione `EDGE_CLUSTER`:** un cluster denso a 200 UA. Non è tra
  i valori di default, perché la accettano solo gli indici con celle di
  almeno 32768. `FAR_CLUSTER` non è stato spostato: le posizioni
  uscirebbero dai limiti degli indici con celle piccole e i piani
  esistenti fallirebbero.
- **Piano `plans/update-step.json`:** update LOCAL con passi 0,1, 0,3,
  1, 3 e 10, con `GRID_QUADTREE_32768`, `GRID_QUADTREE_32768_32` e
  `LINEAR_SCAN` su `UNIFORM`, `FAR_CLUSTER` ed `EDGE_CLUSTER`;
  `UNIFORM_GRID_400` come riferimento con `r` noto (`L` è il lato della
  cella), senza `EDGE_CLUSTER`, che è fuori dai suoi limiti.
- **Misura indicativa** (2026-10-05, non sulla macchina dedicata ai
  benchmark): update LOCAL con batch di 100, `EDGE_CLUSTER`, 100 000
  entità, `GRID_QUADTREE_32768`: 3,5 µs con passo 0,1, 16,2 µs con
  passo 10.

# Limiti dello scenario

- I benchmark sono **2D**; il sistema solare è 3D, anche se quasi piano.
  In 3D, per `r` piccolo, `P(r) ≈ 1,5 · r` invece di `1,27 · r`.
- Il numero di entità e la loro densità non fanno parte dello scenario:
  li danno le distribuzioni e le dimensioni dei benchmark.

# Correlati

- [Capacità degli aggiornamenti per tick](/indices/update-capacity.md)
  — usa `v` e `f` di questo scenario per stimare `N_max`.
- [Griglia di quadtree](/indices/grid-quadtree.md#frequenza-dei-cambi-di-foglia)
  — il modello `P(r)` che le misure al variare del passo verificano.
- [Indicizzazione spaziale](/decisions/spatial-indexing.md#limiti-delle-coordinate)
  — i limiti delle coordinate fissati dall'indice.
- [Tipo delle coordinate](/decisions/coordinate-type.md) — precisione
  del double e unità in metri.
