---
type: Glossary
title: Glossario
description: Definizioni dei termini ricorrenti di BADSPACE — partizione, nodo, software, API comune, commit, tick, fencing token, epoca, tombstone, scrittura condizionata, k, interfaccia.
tags: [badspace, glossary]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-09-29T10:47:38Z }
---

# Termini

| Termine | Significato |
|---|---|
| Partizione | Istanza indipendente del DB con un solo writer, più reader e indice proprio; in teoria copre tutto lo spazio. Vedi [partizionamento](/decisions/partitioning.md). |
| Nodo | Processo, locale o remoto, che ospita una o più partizioni; per ora serve un solo spazio. Il suo contratto è il futuro servizio gRPC. Vedi [architettura a livelli](/architecture/layers.md#nodi). |
| Software | Il software che usa BADSPACE (simulazione, monitoraggio, gaming e altro) e sceglie la strategia di partizionamento. Vedi [architettura a livelli](/architecture/layers.md). |
| API comune | Livello di supporto sopra le partizioni: ID, aggregazione, configurazione e meccanismi. Vedi [API da esporre](/decisions/api-surface.md). |
| Commit | Gruppo atomico di scritture di una partizione, identificato da un contatore monotono; è l'unica transazione del sistema e non coincide necessariamente con un tick. Vedi [commit della partizione](/architecture/partition-commit.md). |
| Tick | Intervallo di tempo entro cui il software esegue le sue operazioni (il frame time di un server di gioco); per partizione al più un commit per tick, tipicamente esattamente uno; locale o globale. |
| Fencing token | Numero di generazione che invalida le scritture di un writer non più proprietario. Vedi [proprietà della partizione](/architecture/partition-ownership.md). |
| Epoca | Contatore di sistema che cresce a ogni migrazione e stabilisce quale copia è valida. Vedi [metadati delle entità](/architecture/entity-metadata.md). |
| Tombstone | Metadati di sistema che restano dopo la rimozione e impediscono le resurrezioni. Vedi [tombstone](/architecture/tombstones.md). |
| Scrittura condizionata | Scrittura applicata solo se una condizione sui metadati è vera (compare-and-set). |
| k | Numero di commit passati conservati da una partizione, default 0. Vedi [conservazione delle versioni](/architecture/version-retention.md). |
| Interfaccia | Contratto astratto di operazioni con la stessa semantica, indipendente dal linguaggio. Vedi [linguaggio](/decisions/language.md#interfaccia). |
