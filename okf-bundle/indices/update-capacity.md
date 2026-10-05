---
type: Performance Model
title: Capacità degli aggiornamenti per tick
description: Quante entità per partizione si possono aggiornare in un tick, dati i tick al secondo, nel caso peggiore e in media; modello con il costo di un aggiornamento e di un cambio di foglia o di cella, e prima stima dalle misure.
tags: [badspace, spatial-indexing, performance, tick, benchmark]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-05T21:59:03Z }
---

# Domanda

Chi usa badspace sceglie quanti tick al secondo fare e deve sapere
quante entità può gestire per partizione nel caso peggiore. Questo
documento considera solo il peso dell'aggiornamento delle posizioni:
query, logica e rete usano il resto del tick.

Ottimizzare gli indici serve, ma a un certo punto si accettano dei
compromessi; per capire quando sono accettabili serve sapere fino a
che punto ci si può spingere.

# Modello

| Grandezza | Significato |
|---|---|
| `f` | tick al secondo; `DT = 1/f` è la durata di un tick |
| `N` | entità aggiornate in ogni tick |
| `v` | velocità delle entità; `d = v · DT` è lo spostamento in un tick |
| `L` | lato della foglia (o della cella, nella grid uniforme) |
| `r` | `d / L = v / (f · L)`, i lati di foglia percorsi in un tick |
| `P(r)` | probabilità di cambiare foglia in un tick (vedi [frequenza dei cambi di foglia](/indices/grid-quadtree.md#frequenza-dei-cambi-di-foglia)) |
| `c0` | costo di un aggiornamento senza cambio di foglia: scrivere la posizione e controllare il box |
| `c1` | costo in più di un cambio di foglia |
| `β` | quota del tick dedicata agli aggiornamenti delle posizioni |

Il tempo degli aggiornamenti in un tick e il vincolo del budget sono:

```
T_tick = N · (c0 + P(r) · c1)  ≤  β / f
```

quindi:

```
N_max(f) = β / ( f · (c0 + P(r) · c1) )
```

Nel **caso peggiore** tutte le entità cambiano foglia in ogni tick
(`P = 1`): succede con un gruppo denso di entità veloci, con `r ≥ 1`.

```
N_max = β / ( f · (c0 + c1) )
```

# Durata del tick

Contando i costi al secondo per ogni entità:

```
costo al secondo = f · c0  +  f · P(r) · c1
```

- **Il primo termine cresce con `f`:** ogni tick paga l'aggiornamento di
  tutte le entità. Il tick non può essere più corto del tempo per
  aggiornarle tutte senza cambi di foglia: `f ≤ β / (N · c0)`.
- **Il secondo termine ha un tetto:** per `r` piccolo, `f · P(r)` vale
  circa `(4/π) · v / L`, che non dipende da `f`. Quante volte un'entità
  attraversa un bordo in un secondo dipende dalla sua velocità e dal
  lato della foglia, non dai tick: tick più corti dividono gli stessi
  attraversamenti su più tick.
- **Con `r ≥ 1`** ogni tick è un cambio di foglia, e il secondo termine
  vale `f · c1`.

Aumentare `f` quindi non riduce mai il costo al secondo: al più lascia
invariata la parte dei cambi e aumenta quella degli aggiornamenti. Tick
più corti spezzano il lavoro, ma per un indice a celle rendono `N_max`
più piccolo, mai più grande.

# Tick che non bastano

Se i cambi di foglia fossero indipendenti, il loro numero in un tick
sarebbe una binomiale con media `N · P` e deviazione standard
`√(N · P · (1 − P))`: con `N` grande la variazione relativa è
piccolissima (con `N = 10⁵` e `P = 0,15`, circa lo 0,7% della media).

In pratica i tick troppo lunghi vengono da eventi correlati:

- **moti correlati:** una folla o un convoglio che entra in una zona
  densa fa cambiare foglia a tutti insieme;
- **raffiche di divisioni e riunioni** quando un gruppo attraversa una
  zona;
- **pause del garbage collector** in Java; in nativo non ci sono, ma ci
  sono altre fonti di variazione.

Per questo la garanzia si basa sul caso peggiore (`P = 1`); il modello
medio stima il margine tipico, e la distribuzione reale si misura con i
percentili del tempo per tick (p99, massimo), non con la binomiale.

# Prima stima

Dalle misure della macchina dei benchmark (2026-10-03, update con batch
di 100, 100 000 entità, un thread, Java), con la [griglia di
quadtree](/indices/grid-quadtree.md) con celle da 256:

- `UNIFORM`, LOCAL (15% di cambi di foglia): circa 45 ns per entità;
- `FAR_CLUSTER`, LOCAL (98% di cambi di foglia): circa 195 ns per
  entità.

Da questi due punti: `c0 ≈ 20 ns` e `c1 ≈ 180 ns`. Con `f = 60` (tick da
16,7 ms) e `β = 0,25` il budget è circa 4,2 ms per tick e per thread:

| Indice | Costo per entità, caso peggiore | N_max, caso peggiore | N_max con P = 15% |
|---|---|---|---|
| Griglia di quadtree, celle 256 | circa 200 ns | circa 21 000 | circa 90 000 |
| [Grid uniforme](/indices/uniform-grid.md), celle 400 | circa 65 ns (teletrasporto) | circa 64 000 | — |
| [Scansione lineare](/indices/linear-scan.md) | circa 22 ns | circa 190 000 | circa 190 000 |

Le misure con il profilo completo (2026-10-04, stessi parametri)
danno, con 100 000 entità, circa 44 ns per entità con `UNIFORM` e 160
ns con `FAR_CLUSTER`, quindi `c0 ≈ 23 ns` e `c1 ≈ 140 ns`. Il caso
peggiore però è il teletrasporto, non il movimento LOCAL. Con 1 000 000
di entità ogni aggiornamento costa di più, probabilmente perché i
dati non stanno più nella cache. Costo per entità nel caso peggiore tra tutte le
distribuzioni e i movimenti, con lo stesso budget di 4,2 ms:

| Indice | 100 000 entità | N_max | 1 000 000 di entità | N_max |
|---|---|---|---|---|
| Griglia di quadtree, celle 256 | circa 220 ns (`CORRIDORS`, teletrasporto) | circa 19 000 | circa 350 ns | circa 12 000 |
| Grid uniforme, celle 400 | circa 84 ns (`UNIFORM`, teletrasporto) | circa 50 000 | circa 112 ns | circa 37 000 |
| Scansione lineare | circa 28 ns | circa 150 000 | circa 33 ns | circa 127 000 |

Con 1 000 000 di entità `c0` e `c1` non si ricavano da questi due
punti: con più entità le foglie sono più piccole, e la frequenza dei
cambi di foglia con `UNIFORM` non è più il 15%.

I limiti della stima:

- è **per partizione e per thread**: le partizioni hanno writer su
  thread diversi (vedi [concorrenza](/decisions/concurrency.md)), quindi
  il totale cresce con le partizioni e i core;
- sono **numeri Java**: in nativo `c0` e `c1` scendono, ma il loro
  rapporto no;
- `c0` e `c1` vengono **da due soli punti**, quindi sono approssimati;
- considera **solo gli aggiornamenti**: la scansione lineare vince qui,
  ma perde di centinaia di volte nelle query.

# Misure da fare

- **Update al variare del passo:** il passo del movimento è un
  parametro dei benchmark (`step`); il piano `plans/update-step.json`
  misura `c0 + P(r) · c1` al variare di `r`, con i passi dello [scenario
  del sistema solare](/indices/solar-system-scenario.md), per ricavare
  `c0` e `c1` di ogni indice da più punti.
- **Benchmark a tick degli aggiornamenti:** dati indice, distribuzione,
  velocità `v` e tick al secondo `f`, aggiorna tutte le `N` entità a
  ogni tick e misura il tempo per tick (p50, p99, massimo) al variare
  di `N`, anche con moti correlati. Dà direttamente quante entità per
  partizione restano entro `β · DT` al p99.
- **Regola di dimensionamento per l'utente:** `N_max = β / (f · (c0 +
  c1))` con i valori di `c0` e `c1` per ogni indice.

# Correlati

- [Griglia di quadtree](/indices/grid-quadtree.md#frequenza-dei-cambi-di-foglia)
  — il modello della frequenza dei cambi di foglia `P(r)`.
- [Scenario del sistema solare](/indices/solar-system-scenario.md) — i
  valori di `v` e `f` usati nei benchmark.
- [Grid uniforme](/indices/uniform-grid.md) — lo stesso modello con `L`
  uguale al lato della cella.
- [Indicizzazione spaziale](/decisions/spatial-indexing.md) — la scelta
  dell'indice per partizione, che questo modello aiuta a fare.
- [Entità statiche vs dinamiche](/decisions/static-vs-dynamic-entities.md)
  — le partizioni statiche non hanno aggiornamenti delle posizioni.
