---
type: Glossary
title: Glossario
description: Definizioni dei termini ricorrenti di BADSPACE — spazio, partizione, nodo, software, API comune, politica di rimozione, generatorId, commit, tick, snapshot, slot, copy-on-write e transient, fencing token, epoca, tombstone, scrittura condizionata, k, limiti delle coordinate, cella, foglia, interfaccia.
tags: [badspace, glossary]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-06T16:41:33Z }
---

# Termini

| Termine | Significato |
|---|---|
| Spazio | Insieme di entità in 2D o in 3D, diviso in partizioni; il contenuto dello spazio è la somma del contenuto delle sue partizioni. Dallo spazio si creano e si rimuovono le partizioni, e l'API genera gli ID delle partizioni univoci nello spazio. Vedi [API da esporre](/decisions/api-surface.md#creazione-e-rimozione-delle-partizioni). |
| Partizione | Istanza indipendente del DB con un solo writer, più reader e indice proprio; in teoria copre tutto lo spazio. Vedi [partizionamento](/decisions/partitioning.md). |
| Nodo | Processo, locale o remoto, che ospita una o più partizioni; per ora serve un solo spazio. Il suo contratto è il futuro servizio gRPC. Vedi [architettura a livelli](/architecture/layers.md#nodi). |
| Software | Il software che usa BADSPACE (simulazione, monitoraggio, gaming e altro) e sceglie la strategia di partizionamento. Vedi [architettura a livelli](/architecture/layers.md). |
| API comune | Livello di supporto sopra le partizioni: ID, aggregazione, configurazione e meccanismi. Vedi [API da esporre](/decisions/api-surface.md). |
| Politica di rimozione | Scelta fatta alla creazione di una partizione su cosa succede alle sue entità quando la si rimuove: si rimuove solo se vuota (`REQUIRE_EMPTY`, il default) o insieme alle entità (`DISCARD_ENTITIES`). Vedi [API da esporre](/decisions/api-surface.md#creazione-e-rimozione-delle-partizioni). |
| generatorId | Identificativo del generatore di ID dentro un ID Snowflake; ogni istanza dell'API ne riceve uno nuovo a ogni avvio. Vedi [ID delle entità](/decisions/entity-ids.md#generatore). |
| Commit | Gruppo atomico di scritture di una partizione, identificato da un contatore monotono; è l'unica transazione del sistema e non coincide necessariamente con un tick. Vedi [commit della partizione](/architecture/partition-commit.md). |
| Tick | Intervallo di tempo entro cui il software esegue le sue operazioni (il frame time di un server di gioco); per partizione al più un commit per tick, tipicamente esattamente uno; locale o globale. |
| Snapshot | Versione completa e immutabile di una partizione (dati delle entità e indice) pubblicata a ogni commit; i reader leggono uno snapshot senza lock. Vedi [isolamento delle letture](/decisions/read-isolation.md). |
| Slot | Posizione di un'entità negli array dello storage di una partizione; gli indici parlano di slot, non di ID. Lo slot di un'entità può cambiare quando si rimuovono altre entità. Vedi [isolamento delle letture](/decisions/read-isolation.md#gli-slot). |
| Copy-on-write | Modo di creare una nuova versione di una struttura copiando solo le parti che cambiano e condividendo le altre con le versioni precedenti; una struttura fatta così si dice *persistente*. Vedi [isolamento delle letture](/decisions/read-isolation.md#gli-indici). |
| Transient | Versione modificabile di una struttura persistente, usata dal writer durante un commit: una parte si copia alla prima modifica del commit, e le modifiche successive vanno sulla copia. Al commit diventa immutabile. Vedi [isolamento delle letture](/decisions/read-isolation.md#il-commit). |
| Fencing token | Numero di generazione che invalida le scritture di un writer non più proprietario. Vedi [proprietà della partizione](/architecture/partition-ownership.md). |
| Epoca | Contatore di sistema che cresce a ogni migrazione e stabilisce quale copia è valida. Vedi [metadati delle entità](/architecture/entity-metadata.md). |
| Tombstone | Metadati di sistema che restano dopo la rimozione e impediscono le resurrezioni. Vedi [tombstone](/architecture/tombstones.md). |
| Scrittura condizionata | Scrittura applicata solo se una condizione sui metadati è vera (compare-and-set). |
| k | Numero di commit passati conservati da una partizione, default 0. Vedi [conservazione delle versioni](/architecture/version-retention.md). |
| Limiti delle coordinate | Intervallo `[min, max]`, uguale su tutti gli assi, delle coordinate che le entità di una partizione possono avere; lo fissa l'indice, e le scritture fuori dai limiti falliscono. Le query non hanno limiti. Vedi [indicizzazione spaziale](/decisions/spatial-indexing.md#limiti-delle-coordinate). |
| Cella | Quadrato (2D) o cubo (3D) di lato fisso in cui un indice a celle divide lo spazio, come la [grid uniforme](/indices/uniform-grid.md) e la [griglia di quadtree](/indices/grid-quadtree.md). |
| Foglia | Nodo di un quadtree o di un octree senza figli, che contiene le entità della sua area; quando ne ha troppe si divide. Vedi [quadtree e octree](/indices/quadtree.md). |
| Interfaccia | Contratto astratto di operazioni con la stessa semantica, indipendente dal linguaggio. Vedi [linguaggio](/decisions/language.md#interfaccia). |
