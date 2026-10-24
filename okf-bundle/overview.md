---
type: Reference
title: Panoramica di BADSPACE
description: Panoramica di BADSPACE, un framework con cui gli sviluppatori costruiscono lo strato di gestione di una base dati real-time per entità in uno spazio 2D o 3D del proprio software, e delle sue decisioni architetturali principali.
tags: [badspace, design]
status: stable
generated: { by: codex/gpt-6, at: 2026-10-19T14:17:50Z }
---

# Cos'è BADSPACE

BADSPACE è un **framework con cui gli sviluppatori costruiscono lo
strato che gestisce la base dati del proprio software**. Fornisce il
supporto per realizzare un tipo particolare di base dati: entità
collocate in uno spazio a 2 o 3 dimensioni, aggiornate in tempo reale e
ricercabili per posizione. Si rivolge, per esempio, a software di
simulazione, monitoraggio o gaming (nel bundle, semplicemente
"software"; vedi il [glossario](/glossary.md)).

Il framework offre primitive e meccanismi da comporre attraverso la
sua API. Gli sviluppatori costruiscono lo strato di gestione della
base dati scegliendo le politiche e il coordinamento adatti al
software: come dividere il mondo in partizioni, dove ospitarle, quando
aggiornare e confermare i dati, quali query eseguire e quando migrare
le entità. La ripartizione delle responsabilità è descritta
nell'[architettura a livelli](/architecture/layers.md).

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

Il progetto è in fase **prototipale**: un prototipo in Java, con codice
da buttare, serve a validare le decisioni sul campo. Il bundle descrive
la forma attesa di BADSPACE, che potrà cambiare man mano che i prototipi
porteranno informazioni nuove. Le fondamenta architetturali sono
stabili, mentre linguaggio finale, tipo delle coordinate, API di
dettaglio e persistenza sono ancora aperti.

# Decisioni principali

La scelta portante è il partizionamento con un solo writer per
partizione: quasi tutte le altre decisioni ne discendono.

- [Tipo di strumento](/decisions/tool-type.md) — libreria embedded in
  prima battuta, pensata per evolvere in seguito verso un servizio gRPC;
  la superficie di rete è il contratto dei nodi che ospitano le
  partizioni. BADSPACE non controlla gli accessi: il suo unico
  utilizzatore è il software, che filtra i client esterni.
- [Linguaggio](/decisions/language.md) — prototipi in Java, linguaggio
  finale ancora aperto tra C11, C++ e Rust; il [confronto tra C e
  Rust](/decisions/c-vs-rust.md) è in corso e la decisione non è ancora
  presa.
- [Partizionamento del DB](/decisions/partitioning.md) — partizioni
  indipendenti, single-writer, con indice proprio, create e rimosse
  dinamicamente; strategia scelta dal software.
- [ID delle entità](/decisions/entity-ids.md) — univoci su tutte le
  partizioni, a 64 bit, creati da un generatore dietro un contratto;
  schema Snowflake, sostituibile.
- [Indicizzazione spaziale](/decisions/spatial-indexing.md) — supporto a
  tipi diversi di indice spaziale, scelto per partizione.
- [Supporto a 2D e 3D](/decisions/2d-3d-support.md) — implementazioni
  parallele dietro un'interfaccia comune.
- [Tipo delle coordinate](/decisions/coordinate-type.md) — `double` nei
  prototipi; scelta finale rimandata.
- [Entità statiche vs dinamiche](/decisions/static-vs-dynamic-entities.md)
  — strutture dati separate, query di prossimità unificate; caso
  particolare di partizionamento.
- [Concorrenza](/decisions/concurrency.md) — un solo writer per
  partizione.
- [Isolamento delle letture](/decisions/read-isolation.md) — il writer
  prepara un nuovo snapshot della partizione a ogni commit, che diventa
  visibile ai reader all'avanzamento del tick.
- [API da esporre](/decisions/api-surface.md) — operazioni di base
  individuate, forma da definire.
- [Persistenza](/decisions/persistence.md) — snapshot per partizione;
  nel prototipo si salva una copia autonoma dei dati e si carica il
  contenuto in una nuova partizione, misurandone i costi. Durabilità
  osservabile e recupero coerente dei nodi sono rimandati; formato e
  destinazione restano aperti.

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

# Indici spaziali

La sezione [indici spaziali](/indices/) ha un documento per ogni tipo
di indice, con il funzionamento, le problematiche e le decisioni, più i
documenti che servono a confrontarli: scenari di riferimento e modelli
delle prestazioni.

# Processo

Lo sviluppo procede per [prototipi minimali](/process/development-approach.md):
ogni prototipo è verificato da test di integrazione e accompagnato da
client d'esempio, per validare le decisioni sul campo invece di
progettare tutto a priori.
