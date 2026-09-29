---
type: Design Decision
title: API da esporre
description: La superficie API pubblica di BADSPACE ha già un elenco di operazioni di base su entità, metadati, partizioni e commit, ma la forma delle chiamate è da definire; il partizionamento fissa già partizioni esplicite, generazione degli ID, aggregazione, scritture condizionate e API dei metadati.
tags: [badspace, design, api, partitioning]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-09-29T10:47:38Z }
---

# Stato

Le operazioni di base sono individuate (insert, remove, update
posizione, query di range, k-nearest, raycasting e le operazioni su
metadati, partizioni e commit); la loro forma è ancora da definire.

# Operazioni di base dell'API comune

Oltre ai meccanismi, l'API comune espone le operazioni di base su
entità, partizioni e commit. L'elenco non è chiuso: l'API potrà
evolvere aggiungendo il supporto a ulteriori meccanismi.

| Ambito | Operazione | Note |
|---|---|---|
| Entità | Creazione, modifica e rimozione | Su una o più entità per chiamata; gli [ID](/decisions/entity-ids.md) li genera l'API |
| Entità | Lettura per ID | Uno o più ID; la partizione la indica il software, che tiene la mappa ID → partizione |
| Entità | Ricerche per posizione | Range, k-nearest, raycast, su una o più partizioni (vedi [aggregazione](/mechanisms/query-aggregation.md)) |
| Metadati | Lettura e modifica | [Metadati](/architecture/entity-metadata.md) applicativi; quelli di sistema sono in sola lettura |
| Metadati | Scritture condizionate | Compare-and-set; sostituiscono le transazioni tra partizioni |
| Partizioni | Creazione e rimozione dinamica | Con scelta dell'indice e di k; è la base della scalabilità |
| Partizioni | Proprietà | Acquisire, rilasciare e trasferire l'esclusività di scrittura con il [fencing token](/architecture/partition-ownership.md) |
| Commit | `commit()` | Chiude il [commit](/architecture/partition-commit.md) corrente; per un server a frame, alla fine del tick |
| Commit | Ultimo commit persistito | [Durabilità osservabile](/architecture/observable-durability.md) |
| Commit | Lettura al tick N | Sulle partizioni con k ≥ 1 (vedi [conservazione delle versioni](/architecture/version-retention.md)) |

La mappa ID → partizione spetta al software, che decide dove creare le
entità e quando spostarle. È una scelta naturale quando tutte le entità
di un tipo stanno in una partizione; se ne usa più d'una, è il software
ad aggiornare la mappa a ogni migrazione.

# Vincoli dal partizionamento

Il [partizionamento del DB](/decisions/partitioning.md) e l'[architettura
a livelli](/architecture/layers.md) fissano già alcuni elementi:

- **Partizioni esplicite**: una scrittura indica la partizione di
  destinazione, una query l'insieme di partizioni da interrogare.
- **Generazione degli [ID](/decisions/entity-ids.md)** a carico
  dell'API comune.
- **Creazione e configurazione delle partizioni**, incluso l'indice da
  usare per ciascuna e il [nodo](/architecture/layers.md#nodi) che la
  ospita, scelto dal software.
- **[Aggregazione](/mechanisms/query-aggregation.md)** dei risultati di
  query su più partizioni.
- **Commit e scritture condizionate** del livello base (vedi
  [architettura minima](/architecture/minimal-core.md)).
- **[API dei metadati](/architecture/entity-metadata.md#api-dei-metadati)**.
- Primitiva di comodo **"sposta da A a B"**, che implementa la
  [migrazione](/mechanisms/entity-migration.md).

Le responsabilità di dettaglio dell'API comune e la forma delle
chiamate restano da definire.

# Correlati

- [Entità statiche vs dinamiche](/decisions/static-vs-dynamic-entities.md)
  e [indicizzazione spaziale](/decisions/spatial-indexing.md)
  vincolano ciò che l'API può esporre in modo efficiente.
