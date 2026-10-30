---
type: Design Decision
title: "Estensioni dell’API"
description: "Rollback, recupero, letture confermate del writer, operazioni future su più partizioni, gruppi, raycast e questioni aperte del contratto dei nodi."
tags: [badspace, documentation]
status: draft
generated: { by: codex/gpt-6, at: 2026-10-30T14:51:33Z }
---

# Stato

Questa pagina descrive estensioni dell’API. Il [contratto attuale](/current/api/operations.md)
espone operazioni su singole partizioni e, con `SnapshotSet2`/`SnapshotSet3`,
l’acquisizione dello stesso tick e le query range e k-nearest su più
partizioni, con i fallimenti restituiti come esito (vedi [query su più
partizioni](/current/architecture/multi-partition-queries.md)). Le firme future si definiscono
quando si introduce il relativo concetto, senza anticiparle nel codice.

# Operazioni previste

| Ambito | Estensione | Stato |
|---|---|---|
| Transazioni | Rollback esplicito, recupero dello stato di lavoro, errori strutturati e salute consultabile della partizione | Comportamento definito nel [contratto transazionale](/future/architecture/transactional-components.md); firme da introdurre con l'implementazione |
| Writer | Letture dell'ultimo stato confermato, pending compreso, senza acquisizione e con token valido per chiamata | Riservate al proprietario; i reader ordinari restano sugli snapshot pubblicati |
| Query | Raycast: primo hit e tutti gli hit, anche aggregati su più partizioni | Da progettare per entità puntiformi; richiede di riprendere il lavoro sugli indici |
| Metadati | Metadati di sistema, condizioni, tombstone e loro eliminazione | Da introdurre con i protocolli di migrazione |
| Persistenza | Esposizione dell’ultimo tick persistito e salvataggio asincrono | Rimandati a durabilità osservabile e nodi remoti |
| Coordinamento | Scelta del tick N comune, nuovo tentativo quando N esce dalla finestra di una partizione, eventuale barriera; verifica e acquisizione con esito senza eccezione sulla singola partizione | Da valutare con le letture coerenti |
| Migrazione | Operazione di supporto per spostare entità tra partizioni | Protocollo progettato, API di dettaglio aperta |

# Altre estensioni da valutare

- Overload con array separati per le coordinate, se il costo dei record lo richiede.
- Altre regioni di query: capsula, frustum e poligono.
- Più query o commit di più partizioni in una chiamata al nodo.
- Scadenza massima delle acquisizioni configurabile sul nodo.
- Controllo anticipato della scadenza nella view per risparmiare chiamate remote.
- Copia autonoma dello snapshot esposta al software, distinta dalla copia interna per i salvataggi.

# Gruppi di partizioni

I gruppi sono un tema futuro. Possono semplificare sia la configurazione
condivisa delle partizioni (indice, k, politica di rimozione), sia la
gestione degli insiemi su cui eseguire interrogazioni. Si potrà valutare
una gerarchia di gruppi e sottogruppi.

Il raggruppamento organizzativo non coincide necessariamente con una
configurazione uniforme: per esempio un sistema solare può contenere
partizioni statiche e dinamiche con indici differenti. Relazione tra
configurazione e selezione, ereditarietà delle impostazioni, appartenenze
multiple e composizione dei sottogruppi sono punti da progettare in seguito,
non requisiti già fissati.

L'API dei gruppi si costruisce sopra quella delle singole partizioni e
degli insiemi di snapshot. Selezionare un gruppo non rende confrontabili
coordinate di riferimenti diversi e non introduce query tra Space. La
relazione con Space e tempi indipendenti è descritta nelle
[evoluzioni di nodi e Space](/future/architecture/nodes-and-spaces.md).

# Vincoli dal partizionamento

Il [partizionamento del DB](/current/decisions/partitioning.md) e l'[architettura
a livelli](/current/architecture/layers.md) sono i prerequisiti già
implementati. Le estensioni devono conservarne le responsabilità:

- **Partizioni esplicite**: una scrittura indica la partizione di
  destinazione; una query su più partizioni indica l’insieme di
  partizioni da interrogare, come già fa l’[insieme di
  snapshot](/current/architecture/multi-partition-queries.md).
- **Generazione degli [ID](/current/decisions/entity-ids.md)** a carico
  dell'API comune.
- **Creazione e configurazione delle partizioni**, incluso l'indice da
  usare per ciascuna e il [nodo](/current/architecture/layers.md#nodi) che la
  ospita, scelto dal software.
- **Aggregazione** dei risultati di query su più partizioni: range e
  k-nearest sono [disponibili](/current/architecture/multi-partition-queries.md),
  raycast e deduplica sono [estensioni future](/future/mechanisms/query-aggregation.md).
- **Commit e scritture condizionate** del livello base (vedi
  [architettura minima](/future/architecture/minimal-core.md)).
- **[API dei metadati](/future/architecture/entity-metadata.md#api-dei-metadati)**.
- Primitiva di comodo **"sposta da A a B"**, che implementa la
  [migrazione](/future/mechanisms/entity-migration.md).

Le responsabilità di dettaglio dell'API comune e la forma delle
chiamate restano da definire.
