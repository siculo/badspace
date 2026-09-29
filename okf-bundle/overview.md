---
type: Reference
title: Panoramica di BADSPACE
description: Panoramica di progettazione di BADSPACE, un database real-time per entità in uno spazio 2D o 3D, base per software di simulazione, monitoraggio o gaming, e delle sue decisioni architetturali principali.
tags: [badspace, design]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-09-29T07:36:20Z }
---

# Cos'è BADSPACE

BADSPACE è un database real-time per entità collocate in uno spazio a 2
o 3 dimensioni, pensato per server e applicazioni che devono fare
ricerche per posizione. Tiene traccia della posizione delle entità e
risponde a query di prossimità mentre il software che lo usa lavora sui
dati. È pensato come base su cui costruire soluzioni software di tipo
diverso, per esempio di simulazione, monitoraggio o gaming (nel bundle,
semplicemente "software"; vedi il [glossario](/glossary.md)).

Supporta la creazione, la cancellazione e l'aggiornamento delle entità
e le ricerche per posizione: range (tutto ciò che sta in un'area),
k-nearest (le k entità più vicine), raycasting (cosa interseca un
raggio) e simili. Il problema che risolve è mantenere queste risposte
veloci e coerenti mentre migliaia di entità si muovono a ogni tick,
anche quando il mondo è diviso in più partizioni.

È progettato per essere performante e soprattutto **scalabile**: la
scalabilità viene dalla possibilità di creare e rimuovere partizioni
dinamicamente, distribuendo il carico man mano che cresce o cala (vedi
[partizionamento](/decisions/partitioning.md)).

# Stato del progetto

BADSPACE non esiste ancora: il bundle ne descrive la forma attesa, che
potrà cambiare man mano che i prototipi porteranno informazioni nuove.
Il progetto è in fase di design: le fondamenta architetturali sono
stabili, mentre linguaggio finale, tipo delle coordinate, strutture di
indice, API di dettaglio e persistenza sono ancora aperti.

# Decisioni principali

La scelta portante è il partizionamento con un solo writer per
partizione: quasi tutte le altre decisioni ne discendono.

- [Tipo di strumento](/decisions/tool-type.md) — libreria embedded in
  prima battuta, pensata per evolvere in seguito verso un servizio gRPC.
- [Linguaggio](/decisions/language.md) — prototipi in Java, linguaggio
  finale ancora aperto tra C11, C++ e Rust.
- [Partizionamento del DB](/decisions/partitioning.md) — partizioni
  indipendenti, single-writer, con indice proprio, create e rimosse
  dinamicamente; strategia scelta dal software.
- [ID delle entità](/decisions/entity-ids.md) — Snowflake a 64 bit,
  univoci su tutte le partizioni.
- [Indicizzazione spaziale](/decisions/spatial-indexing.md) — scelta per
  partizione; strutture ancora aperte: grid, quadtree/octree o R-tree.
- [Supporto a 2D e 3D](/decisions/2d-3d-support.md) — implementazioni
  parallele dietro un'interfaccia comune.
- [Tipo delle coordinate](/decisions/coordinate-type.md) — `double` nei
  prototipi; scelta finale rimandata.
- [Entità statiche vs dinamiche](/decisions/static-vs-dynamic-entities.md)
  — strutture dati separate, query di prossimità unificate; caso
  particolare di partizionamento.
- [Concorrenza](/decisions/concurrency.md) — un solo writer per
  partizione.
- [API da esporre](/decisions/api-surface.md) — operazioni di base
  individuate, forma da definire.
- [Persistenza](/decisions/persistence.md) — rimandata.

# Architettura

BADSPACE è organizzato in [tre livelli](/architecture/layers.md):
software, API comune di supporto e layer delle partizioni.
Il layer delle partizioni offre un insieme minimo di primitive (vedi
[architettura minima](/architecture/minimal-core.md)), sopra le quali
si costruiscono i meccanismi:

- [migrazione di entità](/mechanisms/entity-migration.md);
- [letture coerenti](/mechanisms/consistent-reads.md) su più partizioni;
- [aggregazione delle query](/mechanisms/query-aggregation.md);
- [ribilanciamento e split](/mechanisms/rebalancing-and-split.md).

Lo stesso nucleo regge configurazioni molto diverse: vedi i [casi
d'uso](/use-cases.md).

# Processo

Lo sviluppo procede per [prototipi minimali](/process/development-approach.md):
ogni prototipo è accompagnato da uno o più client di test per validare
le decisioni sul campo, invece di progettare tutto a priori.
