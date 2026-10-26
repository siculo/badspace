---
type: Reference
title: "Filosofia d’uso di BADSPACE"
description: "Che cosa offre il prototipo Java di BADSPACE, quali decisioni prende il software e come iniziare a leggerne la documentazione."
tags: [badspace, documentation]
status: stable
generated: { by: codex/gpt-6, at: 2026-10-25T09:04:35Z }
---

# Che cosa si costruisce con BADSPACE

BADSPACE è un framework con cui gli sviluppatori costruiscono lo strato
di gestione dei dati spaziali del proprio software. Gestisce entità
puntiformi con un ID e una posizione in 2D o 3D, aggiornabili e ricercabili
per posizione. Una simulazione, un sistema di monitoraggio o un gioco
può usarlo come parte della propria applicazione.

Il software divide i dati in **partizioni**. Ogni partizione contiene le
proprie entità, ha un indice spaziale e ammette un solo writer alla volta;
più reader possono leggerne gli stati confermati. Uno **spazio** raccoglie
partizioni della stessa dimensione. Un **nodo locale** ne ospita i dati.

# Come si usa

Il software sceglie come dividere le entità, su quale nodo creare ogni
partizione e quale indice usare. Tiene la corrispondenza tra ID e
partizione e decide quando scrivere, confermare e leggere. BADSPACE
fornisce le operazioni e ne mantiene le garanzie; le politiche restano
nel software.

Il ciclo fondamentale è: creare una partizione, prenderne la proprietà,
scrivere attraverso l’handle del writer, chiamare `commit()` e far
avanzare il tempo logico con `Space.setTick(...)`. Il commit conferma lo
stato; l’avanzamento del tick lo rende leggibile. Ogni lettore, compreso
il writer, acquisisce uno snapshot pubblicato e lo chiude dopo l’uso.

Le query disponibili sono la lettura per ID, la ricerca in una regione e
la ricerca delle entità più vicine, su una singola partizione. I metadati
applicativi associano valori `long` a chiavi stringa. Si possono salvare
snapshot e caricarne il contenuto in nuove partizioni.

# Perimetro della documentazione

Questa parte descrive il codice Java presente in `prototype/`: libreria
embedded, nodi locali, varianti 2D e 3D. Il prototipo serve a verificare
decisioni e costi; la documentazione distingue il comportamento attuale
dalle ipotesi di progetto.

Le operazioni su partizioni diverse sono indipendenti: non ci sono
transazioni tra partizioni né una pubblicazione simultanea dei loro
snapshot. Le query dell’API lavorano su una partizione alla volta.
Questi limiti sono rilevanti per chi costruisce il coordinamento nel software.

# Percorso di lettura

1. [Architettura a livelli](/current/architecture/layers.md): responsabilità e componenti.
2. [Componenti dell’API](/current/api/components.md): oggetti usati dal software e loro ciclo di vita.
3. [Casi d’uso attuali](/current/use-cases.md): esempi costruibili con le operazioni presenti.
4. [Contratto dell’API](/current/api/operations.md): chiamate, risultati ed errori.
5. [Approfondimenti](/current/architecture/index.md): proprietà, tempo logico, snapshot e storage.

Il [glossario](/current/glossary.md) è un riferimento da consultare durante
la lettura. La seconda parte del bundle raccoglie gli [sviluppi futuri](/future/index.md).
