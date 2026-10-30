---
type: Design Proposal
title: "Evoluzioni di nodi, servizi e Space"
description: "Hosting di più nodi in un servizio remoto, recupero dei nodi vuoti e possibili operazioni tra Space con tempi e coordinate espliciti."
tags: [badspace, architecture, partitioning, distribution]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-27T20:31:26Z }
---

# Stato e base della proposta

Questa pagina raccoglie evoluzioni da valutare, non funzionalità disponibili.
La base è già implementata: ogni nodo è riservato a un solo Space, che lo
crea tramite factory e lo identifica nell'API con un handle, e gli insiemi
di snapshot sono creati dallo Space, che verifica l'appartenenza delle
partizioni (vedi [architettura a livelli](/current/architecture/layers.md#nodi)).
Le evoluzioni seguenti partono da questa base.

Un **nodo** ospita partizioni di un solo Space e ne segue il tick. Un
**processo o servizio** può ospitare più nodi. Questa distinzione permette
la condivisione di risorse fisiche mantenendo indipendenti i tempi degli
Space, senza rendere ogni nodo capace di gestire più domini temporali.

# Perché usare più Space

Space distinti sono utili quando servono tempi logici indipendenti, per
esempio simulazioni che possono avanzare o fermarsi separatamente.
Separare sistemi di coordinate non richiede da solo Space diversi: con
un tick comune il software può selezionare insiemi distinti di partizioni.

Nel caso di un gioco con più sistemi solari, ogni sistema può essere
rappresentato da un insieme di partizioni nello stesso Space. I futuri
[gruppi e sottogruppi](/future/decisions/api-surface.md#gruppi-di-partizioni)
potrebbero agevolare sia configurazione sia selezione delle partizioni.
Space distinti sono un'alternativa quando l'indipendenza temporale serve
davvero, con costi aggiuntivi per le operazioni che attraversano gli Space.

# Più nodi nello stesso servizio remoto

Un futuro servizio potrebbe ospitare nodi di Space differenti, ed
eventualmente più nodi dello stesso Space. La factory remota potrebbe
creare un nodo nel servizio e restituire all'API un collegamento attraverso
il quale lo Space costruisce il proprio handle.

Il servizio va raggiunto mediante un indirizzo; il nodo ospitato deve
avere un'identità per l'instradamento interno. Forma e durata di questa
identità, eventuale identificazione dello Space sul servizio, creazione,
riconnessione e gestione degli errori restano da progettare con i
[nodi remoti](/future/decisions/tool-type.md). L'handle applicativo non
implica oggi un nome globale o un'identità persistente del nodo.

Ogni nodo mantiene il proprio tick, le partizioni e una propria directory
di persistenza. La condivisione del processo non deve mescolare elenchi,
caricamenti o eliminazioni dei salvataggi dei diversi nodi.

# Recupero dei nodi vuoti

Come ottimizzazione, il servizio potrebbe rimuovere un nodo dopo un
periodo di inattività, ma solo dopo aver accertato che non contiene più
partizioni né snapshot ancora utilizzabili. Una richiesta di rimozione
non basta: contano anche partizioni ritirate e acquisizioni ancora vive.

Il grace period inizia dopo che il nodo è effettivamente vuoto; non
permette di rimuovere dati solo perché uno Space è in pausa. La verifica
deve considerare operazioni in corso e impedire che una nuova operazione
renda il nodo nuovamente necessario durante la dismissione. Validità degli
handle, notifiche o ricreazione dopo la dismissione restano punti aperti.
I salvataggi su disco hanno una vita distinta: rimuovere il nodo non
significa eliminare i salvataggi. Nessun meccanismo di dismissione viene
richiesto dalla base attuale: i nodi restano allo Space per tutta la sua
vita.

# Integrazione e trasferimenti tra Space

L'hosting condiviso non abilita automaticamente query o trasferimenti
tra Space. Sono estensioni autonome, da introdurre per casi d'uso concreti.

Per una query tra Space non basta l'univocità degli ID: tick numericamente
uguali possono rappresentare tempi differenti e coordinate uguali possono
appartenere a riferimenti diversi. Occorre definire la relazione temporale,
le trasformazioni geometriche e il significato dell'aggregazione.

Il trasferimento di un'entità deve preservarne l'identità, ma richiede
anche una regola di passaggio tra tempi, coordinate e responsabilità dei
due Space, inclusi conferme, ripetizioni e destinazione ferma. Un riferimento
temporale comune è una possibile semplificazione da valutare se questo
trasferimento servirà. Non è una decisione di imporre ora un tick comune
a tutti gli Space. Il [protocollo di migrazione tra partizioni](/future/mechanisms/entity-migration.md)
non si estende automaticamente a domini temporali indipendenti.

Il [contratto degli ID](/current/decisions/entity-ids.md) rimane sufficiente
come astrazione: non si propone un nuovo schema o ID locali allo Space.
Il coordinamento dei generatori tra processi resta il tema già previsto
per la [generazione nel cluster](/future/decisions/entity-ids.md).

# Carico del processo e collocazione dei dati

Un eventuale bilanciamento delle risorse fisiche deve considerare il carico
complessivo dei nodi ospitati, anche se appartengono a Space diversi.
Misurare quel carico non richiede query applicative tra Space.

Il [trasferimento dell'esclusività a un altro writer](/future/mechanisms/rebalancing-and-split.md)
non equivale a spostare lo storage su un altro nodo o processo. Un futuro
trasferimento della collocazione deve essere progettato separatamente,
mantenendo l'appartenenza della partizione al suo Space.
