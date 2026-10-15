---
type: Design Decision
title: ID delle entità
description: Gli ID delle entità sono univoci su tutte le partizioni, mai riusati e senza indicazione della partizione; anche gli ID delle partizioni sono univoci tra spazi e mai riusati. Li crea, uno per tipo di ID, un generatore dell'API comune dietro un contratto proprio, senza garanzie di ordine né di segno. Lo schema scelto, sostituibile, è Snowflake a 64 bit: un generatore per processo API, con generatorId assegnato a ogni avvio.
tags: [badspace, design, partitioning, ids]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-15T10:00:00Z }
---

# Decisione

- Gli ID sono **univoci per tutte le partizioni**.
- La generazione è **esterna al layer delle partizioni** ed è
  responsabilità dell'[API comune](/architecture/layers.md), non del
  software.
- La generazione passa per un **generatore di ID** con un contratto
  proprio (vedi [Contratto del generatore](#contratto-del-generatore)).
- Schema scelto oggi: **stile Snowflake**, sostituibile. Un ID a 64 bit
  composto da un timestamp, un identificativo del generatore e una
  sequenza locale: univoco senza coordinamento a runtime, regge anche il
  passaggio al cluster. I dettagli sono nella sezione [Schema
  Snowflake](#schema-snowflake).
- Gli ID **non vanno riusati**, altrimenti un riferimento vecchio
  finisce su un'entità nuova. Con 64 bit si può semplicemente non
  riusarli mai.
- L'ID **non indica la partizione**: nessuno deve ricavare la partizione
  dall'ID, altrimenti una [migrazione](/mechanisms/entity-migration.md)
  rompe l'invariante.

La mappa ID → partizione è del software; l'API potrebbe
offrirla in futuro come aiuto opzionale.

# Contratto del generatore

Il resto del sistema vede il generatore solo attraverso questo
contratto:

- Una sola operazione: restituisce un **nuovo ID**, un intero a 64 bit
  (`long`).
- Gli ID sono **univoci su tutte le partizioni e su tutti gli spazi**
  del processo, e il generatore non restituisce mai due volte lo stesso
  ID, neanche dopo un riavvio dell'istanza. L'univocità vale per un
  tipo di ID: ID di entità e ID di partizioni vengono da generatori
  diversi e possono avere lo stesso valore.
- L'ID **non indica la partizione**.
- **Nessuna garanzia di ordine né di segno**: qualunque valore `long` è
  un ID valido, e nessuna parte del sistema deve dipendere dall'ordine,
  dalla densità o dal contenuto dei bit degli ID.
- L'operazione è **sincrona**; può fallire solo in casi eccezionali,
  che il generatore segnala.
- L'API usa **un solo generatore per tipo di ID, condiviso da tutti gli
  spazi**: uno per le entità e uno per le
  [partizioni](#id-delle-partizioni). Lo spazio li riceve nella sua
  configurazione.
- La scelta dello schema e la sua configurazione (per Snowflake il
  `generatorId`) sono di chi crea il generatore, non degli spazi.

Il contratto esiste perché lo schema si possa sostituire quando un
deployment lo richiede: per esempio blocchi di ID presi in lease da un
contatore centrale (ID densi, nessuna dipendenza dall'orologio), una
sequenza di un DB, o un contatore semplice nei test. Il tipo `long`
esclude gli schemi più larghi di 64 bit, come gli UUID, perché gli ID
stanno in array primitivi.

# Schema Snowflake

Snowflake è l'implementazione scelta del [contratto](#contratto-del-generatore).

## Formato dell'ID

| Campo | Bit | Contenuto |
|---|---|---|
| Segno | 1 | sempre 0: l'ID è positivo |
| Timestamp | 41 | millisecondi dall'epoca scelta, circa 69 anni |
| Generatore | 10 | `generatorId`, da 0 a 1023 |
| Sequenza | 12 | da 0 a 4095 nello stesso millisecondo |

Un generatore produce al massimo 4096 ID al millisecondo, cioè
4.096.000 al secondo.

L'**epoca** è il **2026-01-01T00:00:00Z**: il campo timestamp copre
quindi fino al 2095 circa.

L'**epoca** e la **posizione del timestamp** (i 41 bit alti) si
fissano una volta per tutte come costanti: gli ID vivono a lungo nei
riferimenti del software, e cambiarle dopo può far coincidere ID nuovi
con ID vecchi.

La **divisione dei bit bassi** tra `generatorId` e sequenza si può
invece cambiare anche più avanti, per esempio da 41/10/12 a 41/12/10.
Gli ID generati dopo il cambio hanno un timestamp maggiore di tutti
quelli vecchi e quindi non coincidono con nessuno di loro, purché il
timestamp resti negli stessi bit e il tempo del generatore non torni
indietro al momento del cambio. La scelta è rimandata (vedi [Punti
aperti](#punti-aperti)).

Gli ID di un generatore Snowflake sono crescenti; è una proprietà
dello schema, non del contratto.

## Un generatore per tipo di ID e per processo

C'è **un solo generatore per tipo di ID e per processo API, condiviso
da tutti gli spazi**. Per Snowflake è anche un vincolo: l'unicità
dipende dal `generatorId`, e due generatori dello stesso tipo di ID
nello stesso processo con lo stesso `generatorId` produrrebbero ID
uguali. I generatori delle entità e delle partizioni possono invece
avere lo stesso `generatorId`: producono gli stessi numeri, ma per due
tipi di ID diversi.

## Tempo logico

Il generatore usa un **tempo logico che non scende mai**: il massimo
tra l'ultimo timestamp usato e l'orologio.

- Se l'orologio torna indietro, il generatore continua dall'ultimo
  timestamp, senza attendere.
- Se la sequenza di un millisecondo è esaurita, il generatore passa al
  millisecondo successivo anche se l'orologio non ci è ancora arrivato
  ("prestito" dal futuro).
- Il tempo logico può essere in anticipo sull'orologio reale solo fino
  a un **limite di anticipo**. Superare il limite è un caso eccezionale
  e viene segnalato.

Così `nextId()` è **sincrona** e nel funzionamento normale **non
blocca mai**.

## Assegnazione del generatorId

Il `generatorId` arriva **dall'esterno** del generatore, e ogni istanza
ne riceve **uno nuovo a ogni avvio**. Lo assegna il **livello che
gestisce le istanze**, con uno stato persistente che dice quali
`generatorId` sono in uso e quando sono stati rilasciati. Il generatore
offre il meccanismo; l'assegnazione è una decisione presa più in alto
(vedi il principio "Istanze dentro un sistema"
nell'[architettura minima](/architecture/minimal-core.md#principi)).

- Un `generatorId` rilasciato si riusa solo dopo un **periodo di
  attesa** almeno pari al limite di anticipo: così il nuovo proprietario
  parte da un tempo successivo a qualunque timestamp usato dal vecchio.
- Nel cluster l'assegnazione passa per un **lease** con scadenza.

Questo schema risolve insieme due problemi: i **riavvii**, perché
un'istanza riavviata non riusa il `generatorId` di prima con un tempo
che potrebbe essere indietro, e l'**assegnazione nel cluster**.

Con il riuso, i `generatorId` necessari sono quelli delle istanze
attive più quelli rilasciati da meno del periodo di attesa: 1024 bastano
per il numero di istanze previsto.

## Nel prototipo

Il prototipo ha un solo processo API e non ha il componente di
assegnazione:

- `generatorId` fisso, preso dalla configurazione;
- all'avvio il generatore attende un tempo pari al limite di anticipo
  prima di emettere il primo ID.

# ID delle partizioni

Anche gli ID delle partizioni li genera l'API comune, con un
**generatore di ID proprio**, separato da quello delle entità ma con lo
stesso [contratto](#contratto-del-generatore): partizioni ed entità
sono oggetti di tipo diverso. Gli ID delle partizioni sono univoci tra
spazi e processi e **mai riusati**. Le partizioni possono stare su
[nodi](/architecture/layers.md#nodi) diversi, e nessun nodo da solo può
garantirne l'univocità. I nodi si fidano degli ID ricevuti, di entità e
di partizioni: che un ID di partizione non torni mai è una precondizione
del contratto del nodo.

Il mai riuso serve alla [rimozione della
partizione](/architecture/snapshot-retention.md#rimozione-della-partizione)
in due fasi: una partizione segnata come rimossa resta sul nodo con il
suo ID finché ha snapshot non finiti, e le acquisizioni già fatte la
trovano per ID. Il nodo rifiuta la creazione di una partizione con un
ID ancora presente, anche se la partizione è segnata come rimossa.

# Punti aperti

- Snowflake: divisione dei bit tra `generatorId` e sequenza. L'alternativa
  principale è 41/12/10: 4096 generatori e 1.024.000 ID al secondo
  per generatore. La decisione è rimandata alla fase di chiusura
  (implementazione finale e forma a cluster), perché dipende dal
  numero di processi API e dagli ID al secondo richiesti a ogni
  generatore; cambiarla dopo non crea collisioni (vedi [Formato
  dell'ID](#formato-dellid)).

# Correlati

- [Partizionamento del DB](/decisions/partitioning.md).
- [Proprietà della partizione](/architecture/partition-ownership.md) —
  lo stesso schema di lease serve per assegnare i `generatorId` nel
  cluster.
- L'univocità degli ID è ciò che permette la deduplica
  nell'[aggregazione](/mechanisms/query-aggregation.md) e le
  [tombstone](/architecture/tombstones.md).
