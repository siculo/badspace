---
type: Reference
title: Architettura a livelli
description: I tre livelli di BADSPACE partizionato — software, API comune di supporto, layer delle partizioni ospitato dai nodi — chi decide cosa e dove passa il futuro confine di rete; gli sviluppatori accedono attraverso l'API, mentre il contratto dei nodi è interno al sistema.
tags: [badspace, architecture, partitioning, api]
status: stable
generated: { by: codex/gpt-6, at: 2026-10-18T09:35:42Z }
---

# Livelli

Gli sviluppatori usano BADSPACE come **framework per costruire lo
strato di gestione della base dati del proprio software**. Attraverso
l'API compongono le primitive e i meccanismi disponibili, scegliendo
le politiche e il coordinamento adatti alla propria applicazione. Nel
diagramma questo strato fa parte del software ed è costruito sopra
l'API comune.

```
┌ processo del software ───────────────────────────────────┐
│ software (simulazione, monitoraggio, gioco, …)           │
│         │   sceglie la strategia di partizionamento      │
│         │   e il nodo di ogni partizione                 │
│   API comune sopra le partizioni (libreria)              │
│         │   supporto per interagire con le partizioni    │
│         │   + operazioni comuni                          │
└─────────┼────────────────────────────────────────────────┘
          │   contratto dei nodi: chiamata locale oggi, gRPC domani
  layer delle partizioni, ospitato da uno o più nodi
            istanze indipendenti, single-writer, indice proprio
```

Lo strato intermedio è una **API di supporto**, non un livello che
decide al posto del software. È una **libreria** che resta nel processo
del software (vedi [tipo di strumento](/decisions/tool-type.md)). Il
futuro confine di rete passa **sotto** l'API, tra l'API e i
[nodi](#nodi) che ospitano le partizioni: identificatori, aggregazione e
migrazione devono vedere più partizioni, quindi stanno sopra.

Il **punto di accesso degli sviluppatori è l'API comune**. Il contratto
dei nodi e le interfacce dello storage sono interni al sistema, anche
quando i nodi sono remoti. Le regole sulle operazioni esposte al
software si esprimono nell'API; i livelli sottostanti realizzano le
garanzie delle primitive senza duplicare quelle restrizioni per
difendersi da chiamate dirette. Per esempio, l'API distingue i
[metadati](/architecture/entity-metadata.md) applicativi da quelli di
sistema; la partizione offre il supporto comune e valuta le condizioni
insieme alle scritture, atomicamente.

# Chi decide cosa

| Livello | Responsabilità |
|---|---|
| Software | strategia di partizionamento, nodo su cui creare ogni partizione, mappa ID → partizione, quando migrare un'entità, quali partizioni interrogare, quando far avanzare il tick (gTick) e la barriera tra partizioni, rilascio e scadenza degli snapshot che acquisisce |
| API comune | generazione degli [ID](/decisions/entity-ids.md) di entità e partizioni, [aggregazione](/mechanisms/query-aggregation.md), creazione e configurazione delle partizioni (incluso l'indice), meccanismi come la [migrazione](/mechanisms/entity-migration.md) e le [letture coerenti](/mechanisms/consistent-reads.md) |
| Layer delle partizioni | le primitive dell'[architettura minima](/architecture/minimal-core.md), offerte dai nodi, compresi il [tick logico](/architecture/logical-time.md) e la conservazione e l'acquisizione degli snapshot |

Le decisioni sul ciclo di vita delle istanze (quali e quante
partizioni, nodi e istanze dell'API) stanno sopra i meccanismi che le
realizzano: vedi il principio "Istanze dentro un sistema"
nell'[architettura minima](/architecture/minimal-core.md#principi).

Le partizioni sono **esplicite**: una scrittura indica la partizione di
destinazione, una query l'insieme di partizioni da interrogare.

# Nodi

Un **nodo** è un processo, locale o remoto, che ospita una o più
partizioni. Le partizioni di uno spazio possono stare su nodi diversi,
anche su macchine diverse: il software sceglie su quale nodo creare
ogni partizione.

- **Contratto a identificatori.** Tra processi non si passano
  riferimenti a oggetti: il contratto dei nodi usa l'ID della partizione
  e gli ID delle entità. È la forma del futuro servizio gRPC; oggi
  l'implementazione è una chiamata nello stesso processo. Anche le
  [acquisizioni degli snapshot](/architecture/snapshot-retention.md#acquisizione-e-rilascio)
  passano come identificatori, e le tiene il nodo.
- **Proxy nell'API.** L'oggetto partizione visto dal software è un
  proxy che conosce il proprio nodo e l'ID della propria partizione, e
  offre le acquisizioni e la proprietà; le scritture passano da un
  handle del writer che porta il [token di
  scrittura](/architecture/partition-ownership.md), anche questo un
  identificatore nel contratto dei nodi.
- **Il nodo si fida degli ID.** Gli ID di entità e partizioni li genera
  l'API; il nodo non li genera né verifica che siano unici altrove. Gli
  [ID delle partizioni](/decisions/entity-ids.md#id-delle-partizioni)
  sono univoci tra spazi e mai riusati.
- **Un nodo serve un solo spazio**, per ora.

## Punti aperti

- Nodi condivisi tra spazi: gli ID delle partizioni, univoci tra spazi,
  non sono più un ostacolo; resta da decidere se servono.
- Chiamate a lotti ed errori remoti nel contratto dei nodi.

# Correlati

- [Partizionamento del DB](/decisions/partitioning.md).
- [API da esporre](/decisions/api-surface.md).
