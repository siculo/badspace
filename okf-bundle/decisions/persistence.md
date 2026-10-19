---
type: Design Decision
title: Persistenza
description: Persistenza periodica del contenuto di una singola partizione, eseguita dal nodo che la gestisce su una destinazione configurata sul nodo, da una copia autonoma dello snapshot; l'API comanda il salvataggio e ne riceve l'esito; caricamento in una nuova partizione e misure dei costi nel prototipo, con recupero coerente dei nodi e durabilità osservabile rimandati.
tags: [badspace, design, persistence, partitioning]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-19T16:00:00Z }
---

# Perimetro attuale

Nel prototipo la persistenza si implementa dopo i
[metadati](/architecture/entity-metadata.md) e riguarda il **contenuto
di una singola partizione**: salvataggio periodico da uno snapshot e
caricamento dei dati in una nuova partizione. Serve anche a valutare i
tempi e i costi del salvataggio e del ripristino.

Si salvano tutti i dati delle entità presenti nello snapshot: **ID,
posizioni e metadati applicativi**. Gli indici si ricostruiscono durante
il caricamento delle entità, senza salvarne la struttura interna.
Quando arrivano nuovi dati, per esempio le
[tombstone](/architecture/tombstones.md), si estende il contenuto
persistito (vedi [approccio di sviluppo](/process/development-approach.md#meccanismi-prima-estesi-man-mano)).

# Copia e salvataggio

Lo snapshot pubblicato è immutabile e contiene solo lo stato confermato
dell'ultimo commit del tick completato (vedi [isolamento delle
letture](/decisions/read-isolation.md)). Il nodo che gestisce la
partizione esegue il salvataggio, su comando dell'API, con questi passi:

1. Acquisire lo snapshot della partizione.
2. Copiarne ID, posizioni e metadati mentre l'acquisizione è valida.
3. Rilasciare l'acquisizione.
4. Salvare la copia autonoma, senza dipendere dalla scadenza
   dell'acquisizione o dalla vita dello snapshot originale.

La copia deve essere completata prima della scadenza. La scrittura
successiva non ha quel vincolo. Il writer continua a lavorare durante
entrambe le fasi: non ci sono scritture sincrone nel game loop.
La copia avviene dentro il nodo e non passa dall'API (vedi
[conservazione degli snapshot](/architecture/snapshot-retention.md#copia-autonoma-per-la-persistenza)).
Una funzione di copia esposta al software resta utile per tenere uno
snapshot oltre la scadenza massima delle acquisizioni, ma non serve a
questo blocco.

# Destinazione

I dati persistiti vanno su una **destinazione configurata sul nodo**
che gestisce la partizione, non su un volume del software che usa
l'API. Nel prototipo la destinazione è una directory; più avanti può
essere un volume condiviso o un object store. L'API comanda il
salvataggio e riceve l'esito: completamento o errore.

I motivi:

- **Nessun trasferimento del contenuto.** Se scrivesse il software, a
  ogni salvataggio periodico tutto il contenuto della partizione
  passerebbe dal nodo al software: con un nodo remoto è un traffico
  proporzionale alla dimensione della partizione, ripetuto a ogni
  salvataggio.
- **Durabilità di una partizione.** La [durabilità
  osservabile](/architecture/observable-durability.md) è una proprietà
  della partizione: il nodo sa da solo quando un salvataggio è
  completo, senza che il software glielo comunichi.
- **Recupero dei nodi.** Il futuro recupero dopo un crash trova i dati
  delle partizioni sullo storage del nodo.
- **Forma a servizio.** È coerente con la possibile centralizzazione
  della destinazione in una forma a servizio (vedi [tipo di
  strumento](/decisions/tool-type.md)).

Al software, secondo l'[architettura a livelli](/architecture/layers.md),
restano le politiche: quando salvare, quali partizioni, quanti
salvataggi conservare e da quale salvataggio caricare. Un salvataggio
è identificato da un riferimento (partizione e tick) che il software
usa per il caricamento.

Il caricamento su un altro nodo richiede che la destinazione sia
raggiungibile da quel nodo, cioè uno storage condiviso. Con il solo
disco locale si carica sullo stesso nodo; lo spostamento tra nodi è un
caso successivo.

# Caricamento

Si crea una nuova partizione e si ricreano le entità con gli stessi ID,
posizioni e metadati, poi si conferma il contenuto prima di renderlo
disponibile ai reader. Gli indici si ricostruiscono dagli inserimenti.

Il tick segue il contesto di creazione della nuova partizione: può
partire da zero in una nuova esecuzione del tempo logico, mentre in uno
spazio già attivo segue il tick dello spazio. Non è necessario ripartire
dal tick dello snapshot salvato.

I metadati applicativi si caricano senza reinterpretarli: eventuali
valori che rappresentano tick o scadenze restano responsabilità del
software utilizzatore.

# Misure

Si misurano separatamente:

- tempo di copia dello snapshot;
- tempo di scrittura dei dati;
- tempo di caricamento, compresa la ricostruzione degli indici;
- dimensione dei dati salvati e memoria aggiuntiva occupata dalla copia.

# Recupero coerente dei nodi: lavoro successivo

Gli snapshot sono indipendenti per partizione, senza salvataggio atomico
tra partizioni. Il caricamento attuale ricrea il contenuto di una
partizione; il recupero della stessa partizione e di un nodo dopo un
crash richiede un contratto successivo. In un sistema con più nodi, il
crash di un nodo non ferma il tempo e le attività degli altri.

Sono quindi rimandati il recupero coerente dei nodi, la continuità del
tempo logico e della [proprietà](/architecture/partition-ownership.md),
e l'esposizione dell'ultimo tick persistito prevista dalla
[durabilità osservabile](/architecture/observable-durability.md).
Quest'ultima servirà ai protocolli che confermano operazioni solo dopo
la persistenza, come la [migrazione](/mechanisms/entity-migration.md).
Il salvataggio deve comunque avere un esito di completamento o errore.

# Punti aperti

- Frequenza e attivazione dei salvataggi periodici.
- Formato dei dati persistiti.
- Forma delle chiamate di copia, salvataggio e caricamento.
- Gestione di salvataggi interrotti e conservazione dei dati salvati.

# Correlati

- Il [tipo di strumento](/decisions/tool-type.md) — una futura forma a
  servizio potrebbe centralizzare dove vengono scritti gli snapshot.
