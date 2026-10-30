---
type: Reference
title: "Architettura a livelli"
description: "Software, API pubblica e nodi locali del prototipo Java: responsabilità, dipendenze e organizzazione dei moduli."
tags: [badspace, documentation]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-27T20:31:26Z }
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
su nodi locali diversi. Lo spazio crea i propri nodi e restituisce per
ognuno un handle; ogni nodo appartiene a un solo spazio. Il software
sceglie con l’handle il nodo alla creazione o al caricamento della
partizione.

# Chi decide cosa

| Livello | Responsabilità |
|---|---|
| Software | Suddivisione dei dati, nomi delle partizioni, factory dei nodi e loro risorse, scelta dei nodi e degli indici, mappa ID → partizione, assegnazione dei writer, avanzamento del tick, periodicità e conservazione dei salvataggi |
| API | Creazione e registrazione dei nodi, verifica che nodi e partizioni appartengano allo spazio, configurazione e creazione delle partizioni, generazione degli ID attraverso i generatori ricevuti, proxy delle partizioni, handle dei nodi e del writer, insiemi e view degli snapshot acquisiti |
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

Il software non passa allo spazio un nodo già costruito: gli passa una
**factory dei nodi** (`PartitionNodeFactory2`/`PartitionNodeFactory3`),
di solito un riferimento al costruttore del nodo locale, e lo spazio
la chiama con `createNode(factory)`. La factory configura le risorse del
nodo, per esempio la directory dei salvataggi, e a ogni chiamata deve
costruire un nodo nuovo, vuoto e al tick 0, che nessun altro spazio usa.
Lo spazio porta il nodo al proprio tick, lo registra e restituisce
l’**handle del nodo** (`Node2`/`Node3`).

L’handle appartiene allo spazio che l’ha creato. Non ha ID né nome
pubblico, non si costruisce fuori dall’API e non cambia il tick del nodo.
Le operazioni dello spazio che scelgono un nodo rifiutano l’handle di un
altro spazio, con `IllegalArgumentException`, prima di generare ID, fare
I/O o chiamare il nodo. Il controllo usa l’identità dello spazio: due
spazi con la stessa configurazione restano distinti.

```text
Il software                         Lo spazio
    |                                   |
    | "createNode(factory)"             |
    | --------------------------------> |
    |                                   | chiama la factory, fuori dal lock
    |                                   | sotto lock: "setTick(tick)" sul nodo
    |                                   | e registrazione del nodo
    |          handle del nodo          |
    | <-------------------------------- |
    |                                   |
    | "createPartition(handle, ...)"    |
    | --------------------------------> | verifica che l'handle sia suo
```

Il perimetro di questo diagramma è la sola creazione del nodo e il suo
primo uso; la concorrenza con gli avanzamenti è descritta nel
[tempo logico](/current/architecture/logical-time.md#tick-dello-spazio-del-nodo-e-della-partizione).
Uno stesso oggetto nodo restituito due volte dalla factory allo stesso
spazio viene rifiutato; quello consegnato a due spazi diversi non è
rilevabile e viola il contratto della factory, perché l’avanzamento di
uno spazio farebbe avanzare le partizioni dell’altro.

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

Il diagramma seguente mostra i riferimenti fra gli oggetti dell’API per
uno spazio 2D. Per lo spazio 3D valgono le stesse relazioni, con i tipi `3`.
Le frecce dal proxy indicano i riferimenti che conserva dopo la creazione.

```text
                    +-------------------------+
                    | Node2                   |
                    | handle del nodo         |
                    +-------------------------+
                      |                     |
     +----------------+                     +----------+
     | riferimento a Space      riferimento al nodo    |
     v                                                 v
+----------+          "setTick(tick)"          +------------------+
| Space2   | --------------------------------> | PartitionNode2   |
+----------+                                   +------------------+
     ^                                                 ^
     | riferimento a Space                             | riferimento al nodo
     |                                                 |
     +------------------+    +-------------------------+
                        |    |
                   +----------------------------+
                   | Partition2                 |
                   | ID, nome, configurazione   |
                   +----------------------------+
```

Il software passa l’handle a `Space2.createPartition` o a
`Space2.loadPartition`; non raggiunge il contratto del nodo attraverso
l’API. Lo spazio registra i nodi che crea per propagare loro i tick, ma
non conserva gli handle né i proxy delle partizioni. Il proxy restituito
conserva lo spazio e il contratto del nodo scelto con l’handle. Il nodo
conserva lo stato interno della partizione, raggiunto tramite l’ID, non
il proxy `Partition2`.

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
