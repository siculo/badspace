# BADSPACE: Database spaziale real-time per gioco — note di progettazione

2026-09-22 · @Someone

## Tipo di strumento

Tre approcci possibili: **libreria embedded** (integrata nel processo del server di gioco, massima velocità), **servizio standalone** con protocollo di rete proprio (riusabile da altri linguaggi, ma con latenza di rete), oppure un **ibrido**. Si parte dalla libreria embedded in Rust, pensata per poter evolvere in seguito verso un servizio esposto via gRPC.

## Indicizzazione spaziale

Più strutture possibili, con trade-off diversi: **grid uniforme** (semplice, O(1) su densità regolare), **quadtree/octree** (si adatta a densità non uniformi), **R-tree** (buono per bounding box eterogenei). La libreria dovrebbe poter supportare più tipi di indicizzazione in futuro, ma questa flessibilità è rimandata a una fase successiva.

## Supporto a 2D e 3D

Soluzione scelta: **implementazioni parallele** (`Point2`/`Point3`, `Quadtree`/`Octree`) dietro un'**interfaccia comune** per le operazioni condivise, invece di forzare una genericità unica su N dimensioni.

## Entità statiche vs dinamiche

Separare le due categorie a livello di struttura dati: gli **statici** possono usare strutture costruite una volta e mai ricalcolate (build-once, query-many), i **dinamici** richiedono strutture economiche da aggiornare a ogni tick. Le query di prossimità devono comunque poter interrogare entrambe le categorie insieme.

## Gestione della concorrenza

Da valutare tra **single-writer** (un solo scrittore, tipicamente il game loop, con lettori concorrenti su snapshot — semplice, deterministico) e **multi-writer** (lock granulari o strutture lock-free, più scalabile ma molto più complesso da mantenere coerente). Nessuna decisione definitiva ancora.

## API da esporre

Punto da valutare in una fase successiva: quali operazioni offrire (es. insert, remove, update posizione, query di range, k-nearest, raycasting) e con quale forma.

## Persistenza

Aspetto importante ma rimandato: snapshot periodici dello stato, eventualmente verso un DB esterno, senza scritture sincrone nel game loop.

## Approccio di sviluppo

Procedere per **prototipi minimali** su cui ragionare e rivalutare le scelte fatte, invece di progettare tutto a priori. Il primo prototipo sarà accompagnato da un gioco di test (client + server) per validare le decisioni sul campo.
