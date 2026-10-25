---
type: Reference
title: "Architettura a livelli"
description: "Software, API pubblica e nodi locali del prototipo Java: responsabilità, dipendenze e organizzazione dei moduli."
tags: [badspace, documentation]
status: stable
generated: { by: codex/gpt-6, at: 2026-10-25T17:19:04Z }
---

# Livelli

Gli sviluppatori accedono a BADSPACE attraverso l’API pubblica. La
libreria e i nodi locali lavorano nello stesso processo del software.

```text
Software: politiche, coordinamento, uso dei risultati
    |
API: spazi, proxy delle partizioni, writer, snapshot acquisiti
    |
Contratto dei nodi: identificatori e operazioni sulle partizioni
    |
Nodi locali: storage e indici di ciascuna partizione
```

Uno spazio contiene partizioni 2D oppure 3D. Le partizioni possono stare
su nodi locali diversi; ogni nodo serve un solo spazio. Il software
sceglie il nodo alla creazione o al caricamento della partizione.

# Chi decide cosa

| Livello | Responsabilità |
|---|---|
| Software | Suddivisione dei dati, nomi delle partizioni, scelta dei nodi e degli indici, mappa ID → partizione, assegnazione dei writer, avanzamento del tick, periodicità e conservazione dei salvataggi |
| API | Configurazione e creazione delle partizioni, generazione degli ID attraverso i generatori ricevuti, proxy delle partizioni, handle del writer e view degli snapshot acquisiti |
| Nodo e partizioni | Dati e indici, controllo del token di scrittura, batch tutto-o-niente, commit, pubblicazione e conservazione degli snapshot, acquisizioni, scadenze, rimozione e persistenza |

Il contratto dei nodi e le interfacce dello storage sono interni al
sistema. Le regole d’uso rivolte agli sviluppatori si esprimono nell’API;
i livelli sottostanti realizzano le garanzie delle operazioni. I controlli
servono a prevenire errori concreti, secondo il principio di
[fiducia negli sviluppatori](/process/development-approach.md#fiducia-negli-sviluppatori).

# Nodi

`LocalPartitionNode2` e `LocalPartitionNode3` ospitano più partizioni.
Ogni partizione ha storage, indice, proprietà e snapshot propri. Writer
di partizioni diverse possono lavorare su thread diversi.

Il diagramma segue le chiamate dal software a due partizioni di uno
stesso nodo locale. I riquadri delle partizioni evidenziano la separazione
dei loro dati; tutti i componenti sono nello stesso processo.

```text
+--------------------------------------------------------------------------+
| Stesso processo                                                          |
|                                                                          |
|     +----------------------+                                             |
|     | Software             |                                             |
|     +----------------------+                                             |
|                 |                                                        |
|       Chiamate applicative                                               |
|                 v                                                        |
|     +----------------------+                                             |
|     | API pubblica         |                                             |
|     +----------------------+                                             |
|                 |                                                        |
|        Contratto dei nodi                                                |
|                 v                                                        |
|   +------------------------------------------------------------------+   |
|   | Nodo locale                                                      |   |
|   |                                                                  |   |
|   |  +---------------------------+   +---------------------------+   |   |
|   |  | Partizione A              |   | Partizione B              |   |   |
|   |  |                           |   |                           |   |   |
|   |  | Storage e snapshot        |   | Storage e snapshot        |   |   |
|   |  |         |                 |   |         |                 |   |   |
|   |  | Indice spaziale           |   | Indice spaziale           |   |   |
|   |  |                           |   |                           |   |   |
|   |  +---------------------------+   +---------------------------+   |   |
|   |                                                                  |   |
|   +------------------------------------------------------------------+   |
|                                                                          |
+--------------------------------------------------------------------------+
```

Il proxy `Partition2` o `Partition3` conosce nodo e ID della partizione;
il software usa il nome pubblico della partizione. Il contratto
`PartitionNode2`/`PartitionNode3` riceve ID, token di scrittura e
identificatori delle acquisizioni. Il nodo si fida dell’univocità degli
ID generati dall’API e non verifica quelli presenti su altri nodi.

Il tick dello spazio viene propagato ai nodi e alle loro partizioni.
La propagazione non è atomica tra partizioni: durante l’avanzamento una
partizione può essere ancora al tick precedente. Il dettaglio è nel
[tempo logico](/current/architecture/logical-time.md).

# Moduli del prototipo

| Modulo | Contenuto |
|---|---|
| `common` | Record geometrici e dei dati, configurazioni degli indici, identificatori e contratti dei nodi |
| `api` | Interfacce pubbliche, spazi, proxy, writer, snapshot acquisiti e generatori di ID |
| `service` | Nodi locali, storage, indici, strutture persistenti e salvataggi |
| `integration-tests` | Verifica dell’API insieme ai nodi locali |
| `samples` | Esempi eseguibili di operazioni e casi d’uso |
| `benchmark` | Misure dei costi delle operazioni |

# Lettura successiva

- [Componenti dell’API](/current/api/components.md): come il software usa questi livelli.
- [Nucleo delle partizioni](/current/architecture/minimal-core.md): garanzie offerte dal livello base.
