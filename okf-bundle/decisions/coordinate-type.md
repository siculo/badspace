---
type: Design Decision
title: Tipo delle coordinate
description: Nei prototipi le coordinate sono double; la scelta per l'implementazione finale (double, interi a 64 bit o tipo generico) è rimandata, con i limiti di precisione, rappresentazione e determinismo di ciascuna opzione.
tags: [badspace, design, coordinates, precision]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-05T12:53:10Z }
---

# Decisione

- **Prototipi**: coordinate `double`, in metri. Il tipo resta confinato
  negli array privati delle partizioni e nei record `Point2`/`Point3`
  dell'API, così da poterlo cambiare con un intervento meccanico.
- **Implementazione finale**: scelta **rimandata**. L'argomento è
  delicato e va ripreso alla luce dei prototipi e del
  [linguaggio](/decisions/language.md) scelto.

# Limiti del double

Un `double` (IEEE 754 binary64) ha 53 bit di mantissa. La distanza tra
due valori consecutivi (ulp) cresce con il valore: tra 2ᵉ e 2ᵉ⁺¹ vale
2ᵉ⁻⁵².

Con una precisione minima richiesta di **1 cm**, la potenza di 2 più
grande che non supera 0,01 m è 2⁻⁷ m (≈ 0,78 cm): serve e ≤ 45, cioè
**|x| < 2⁴⁶ m ≈ 7,04·10¹³ m ≈ 470 UA** per ogni coordinata.

| Distanza | Metri | Rispetto al limite |
|---|---|---|
| Orbita di Nettuno (~30 UA) | 4,5·10¹² | dentro, margine ×15 |
| Eliopausa (~120 UA) | 1,8·10¹³ | dentro |
| **Limite double al cm** | **7,0·10¹³** | ~470 UA |
| Proxima Centauri (~4,2 anni luce) | 4,0·10¹⁶ | fuori, ×570 |

Per confronto, un `float` (24 bit di mantissa) al centimetro arriva
solo a ±131 km (2¹⁷ m).

Due avvertenze:

- **La precisione è relativa, non assoluta**: altissima vicino
  all'origine, peggiora allontanandosi. Il centimetro vale al bordo.
- **Gli errori si accumulano**: integrando posizioni a ogni tick,
  lontano dall'origine gli spostamenti piccoli vengono arrotondati male
  o persi. Il limite pratico è più basso di quello teorico. Per
  esempio, a 200 UA l'ulp è circa 3,9 mm: un'entità a 10 km/h con 30
  tick al secondo si sposta di circa 24 ulp per tick, a 1 km/h di circa
  2 (vedi lo [scenario del sistema
  solare](/indices/solar-system-scenario.md#precisione)).

# Limiti nei prototipi

Le entità di una partizione hanno coordinate dentro i limiti fissati
dal suo indice (vedi [limiti delle
coordinate](/decisions/spatial-indexing.md#limiti-delle-coordinate)),
mai oltre un tetto globale di `|c| ≤ 2^60`. `NaN` è sempre rifiutato.
Con interi a 64 bit i limiti ci sarebbero comunque: il contratto dei
limiti vale per entrambe le scelte.

# Alternativa: interi a 64 bit

Coordinate come interi con segno a 64 bit in centimetri: limite 2⁶³ cm
≈ **9,2·10¹⁶ m ≈ 9,7 anni luce**, circa 1300 volte l'intervallo del
`double` a parità di precisione.

- **Pro**: precisione uniforme su tutto lo spazio; confronti esatti;
  risultati identici su qualunque piattaforma (utile per repliche e
  cluster).
- **Contro**: il quadrato delle distanze (x² + y² + z²) va in overflow
  già intorno a 3·10⁹ m, quindi serve un tipo più largo (128 bit o
  `double`) per i calcoli; unità e precisione diventano fisse per tutto
  lo spazio.

# Rappresentazione e portabilità

- **Occupazione**: 8 byte sia per `double` sia per l'intero a 64 bit.
  Un'entità 3D occupa 24 byte di coordinate più 8 di
  [ID](/decisions/entity-ids.md): 32 byte, circa 32 MB per un milione di
  entità, indice escluso.
- **Standard**: `double` è IEEE 754 binary64 in Java per specifica e su
  tutte le architetture in uso in C, C++ e Rust; l'intero a 64 bit è in
  complemento a due.
- **In C usare `int64_t`**, non `long`: su Windows `long` è a 32 bit.
- **Ordine dei byte**: i bit sono gli stessi, l'ordine in memoria no
  (x86 e ARM little-endian; la serializzazione Java big-endian). Il
  formato di [persistenza](/decisions/persistence.md) e di rete deve
  fissarlo; gRPC/protobuf lo fa già.
- **Determinismo dei calcoli**: `+ − × ÷ √` sono arrotondate
  correttamente e danno risultati identici ovunque. Le differenze
  vengono da contrazione in FMA e `-ffast-math` in C/C++, e dalle
  funzioni trascendenti (`sin`, `cos`, `exp`) che variano tra librerie
  matematiche; Java offre `StrictMath`.
- **Overflow degli interi**: in Java il valore riparte dall'altro
  estremo; in C/C++ su interi con segno è comportamento indefinito; in
  Rust panic in debug e riavvolgimento in release.

# Rinviare la scelta

Come si può tenere aperto il tipo nei diversi linguaggi:

- **Java**: i generici non vanno bene. Funzionano solo con oggetti
  (boxing di ogni coordinata, niente `double[]` compatto), non
  permettono aritmetica sul parametro di tipo e rendono le prestazioni
  ancora meno rappresentative. Il progetto Valhalla non è disponibile in
  Java 25. Soluzione: `double` confinato in pochi punti.
- **C**: `typedef` scelto in compilazione (per esempio `bs_coord` e
  `bs_coord_wide`), come `btScalar` di Bullet Physics. Nessun costo in
  esecuzione, ma una sola scelta per tutto l'eseguibile.
- **Rust e C++**: generici (trait o template) monomorfizzati, senza
  costo in esecuzione, con un tipo associato "largo" per distanze e
  quadrati (per esempio `f64 → f64`, `i64 → i128`). Le varianti possono
  convivere nello stesso programma.

Il costo reale del rinvio non è il tipo ma la **semantica diversa**:
overflow, tipo largo per le distanze, confronti esatti contro
tolleranze. Gli algoritmi di [indice](/decisions/spatial-indexing.md) e
di query devono essere pensati per entrambi i casi.

# Mitigazione

Nei casi d'uso con aree non contigue (per esempio sistemi solari
separati; vedi [casi d'uso](/use-cases.md)) ogni area può avere il suo
spazio o la sua partizione con **coordinate locali**: dentro un
sistema il `double` basta, e il passaggio tra aree è una
[migrazione](/mechanisms/entity-migration.md).

# Punti aperti

- Tipo delle coordinate nell'implementazione finale: `double`, intero a
  64 bit o generico.
- Unità di misura: fissata dalla libreria o scelta dal software.
- Esigenza di determinismo tra macchine diverse.
- Esistenza di casi d'uso con uno spazio continuo oltre qualche
  centinaio di UA.

# Correlati

- Il [linguaggio](/decisions/language.md) determina come si può
  rendere generico il tipo.
- Il [supporto a 2D e 3D](/decisions/2d-3d-support.md) moltiplica le
  varianti: dimensioni per tipo delle coordinate.
- L'[indicizzazione spaziale](/decisions/spatial-indexing.md) dipende
  dalla semantica del tipo (overflow, confronti).
