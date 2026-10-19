---
type: Design Decision
title: Persistenza
description: Persistenza periodica del contenuto di una singola partizione da una copia autonoma dello snapshot; caricamento in una nuova partizione e misure dei costi nel prototipo, con recupero coerente dei nodi e durabilità osservabile rimandati.
tags: [badspace, design, persistence, partitioning]
status: draft
generated: { by: codex/gpt-6, at: 2026-10-19T14:17:50Z }
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
letture](/decisions/read-isolation.md)). Il salvataggio segue questi passi:

1. Acquisire lo snapshot della partizione.
2. Copiarne ID, posizioni e metadati mentre l'acquisizione è valida.
3. Rilasciare l'acquisizione.
4. Salvare la copia autonoma, senza dipendere dalla scadenza
   dell'acquisizione o dalla vita dello snapshot originale.

La copia deve essere completata prima della scadenza. La scrittura
successiva non ha quel vincolo. Il writer continua a lavorare durante
entrambe le fasi: non ci sono scritture sincrone nel game loop.
La funzione di copia è ancora da progettare (vedi [conservazione degli
snapshot](/architecture/snapshot-retention.md)).

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
- Destinazione e formato dei dati persistiti.
- Forma delle chiamate di copia, salvataggio e caricamento.
- Gestione di salvataggi interrotti e conservazione dei dati salvati.

# Correlati

- Il [tipo di strumento](/decisions/tool-type.md) — una futura forma a
  servizio potrebbe centralizzare dove vengono scritti gli snapshot.
