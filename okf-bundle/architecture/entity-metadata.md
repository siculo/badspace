---
type: Architecture Primitive
title: Metadati delle entità
description: L'API distingue metadati di sistema (epoca, stato, destinazione) in sola lettura e metadati applicativi liberi, con chiavi stringa e valori long; il nodo offre un supporto comune, con condizioni e scritture applicate atomicamente nella partizione; le scritture condizionate sono batch tutto-o-niente in cui una condizione falsa è un esito e non un errore, e sostituiscono le transazioni tra partizioni.
tags: [badspace, architecture, partitioning, metadata, api]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-19T12:00:00Z }
---

# Primitiva

Ogni entità ha, accanto ai dati, dei metadati che l'API distingue in
due spazi.

**Metadati di sistema**, in sola lettura per il software:

- **epoca**: contatore che cresce a ogni migrazione e viaggia con
  l'entità;
- **stato**: viva, in uscita verso X, rimossa;
- **destinazione** (facoltativa): la partizione verso cui è migrata.

Cambiano solo attraverso le operazioni dei protocolli (per esempio la
[migrazione](/mechanisms/entity-migration.md)), perché da loro
dipendono le garanzie: se il software potesse scrivere
l'epoca, potrebbe rompere il protocollo senza accorgersene.

**Metadati applicativi**: slot liberi per il software,
leggibili e scrivibili.

## Responsabilità dei livelli

Il punto di accesso degli sviluppatori è l'[API
comune](/architecture/layers.md), che distingue i due spazi: espone la
lettura di entrambi e la scrittura diretta dei soli metadati
applicativi. I metadati di sistema cambiano attraverso le operazioni
dei protocolli implementati sopra le primitive della partizione.

Il **nodo offre un supporto comune** per memorizzare e aggiornare i
metadati, includerli negli snapshot e applicare scritture condizionate.
Non duplica le restrizioni dell'API per proteggere da chiamate dirette
alle interfacce sottostanti, che sono interne al sistema. Questo non
impone una sola mappa generica o una particolare rappresentazione
fisica dei due spazi.

Le regole dei protocolli, come il congelamento delle entità in uscita,
si applicano nell'API che esegue le transizioni. Nella partizione resta
ciò che richiede **accesso atomico allo stato**: per esempio confrontare
l'epoca e applicare l'inserimento in un'unica operazione, oppure
rimuovere solo se stato, epoca e destinazione sono ancora quelli
attesi. Una verifica nell'API seguita da una scrittura incondizionata
non sostituisce una scrittura condizionata nella partizione.

La collocazione dei controlli segue la [fiducia negli
sviluppatori](/process/development-approach.md#fiducia-negli-sviluppatori):
ogni controllo risponde a un errore concreto da prevenire o a una
garanzia dell'operazione.

## Proprietà comuni

- dati e metadati cambiano nello stesso
  [commit](/architecture/partition-commit.md), in modo atomico;
- i metadati sono **separati dai dati spaziali**: modificarli non tocca
  l'indice;
- i metadati fanno parte dello snapshot pubblicato a ogni commit, come
  ogni dato delle entità (vedi [isolamento delle
  letture](/decisions/read-isolation.md#tutti-i-dati-delle-entità)).

## Metadati applicativi

I metadati applicativi di un'entità sono coppie chiave → valore, con
**chiavi stringa e valori `long`**. Bastano per tutti i casi d'uso
noti: una versione, una riserva come ID del processo, un tag di
responsabilità come ID, una scadenza come tick. Altri tipi di valore si
aggiungono solo quando un caso concreto li richiede.

Per ora **non ci sono limiti** sul numero di chiavi di un'entità né
sulla lunghezza delle chiavi: nessuna garanzia del sistema ne dipende,
e il costo di molte chiavi lo paga solo chi le usa. I limiti arriveranno
con un concetto che ne dà uno vero, per esempio la dimensione massima
di un messaggio gRPC o di un blocco di persistenza.

La rimozione di un'entità ne cancella i metadati applicativi. La
possibilità di tenerli nella [tombstone](/architecture/tombstones.md)
arriva con le tombstone.

## Nel prototipo

- **Storage.** La partizione tiene una mappa persistente copy-on-write
  ID dell'entità → metadati applicativi (`MutableMetadata`): la
  `HashTrie` (una HAMT), nel package `badspace.service.persistent`
  comune agli indici e allo storage. Nella mappa ci sono solo le entità
  con metadati; i metadati di un'entità sono un valore immutabile con
  chiavi ordinate e valori in array paralleli, che una scrittura
  sostituisce. I metadati cambiano di rado, quindi il copy-on-write
  costa meno della copia completa; uno snapshot di un commit senza
  modifiche ai metadati condivide la versione di quello precedente.
- **Snapshot.** Ogni commit chiude la versione della mappa
  (`MetadataSnapshot`) e la mette nello snapshot della partizione
  insieme a slot e indice. Le scritture non confermate si scartano
  riportando la mappa alla versione dell'ultimo commit, senza ripetere
  le scritture al contrario.
- **Contratto del nodo.** `updateMetadataAll(token, writes)` applica una
  lista di `MetadataWrite`, ciascuna su una chiave di un'entità: `Put`
  imposta il valore, `Delete` toglie la chiave. Le scritture valgono
  nell'ordine della lista, quindi per la stessa chiave vince l'ultima;
  la chiamata è tutto-o-niente e fallisce con
  `NoSuchElementException` se un'entità non è nella partizione, anche
  inserita e non ancora confermata. Come le altre scritture prende il
  token del proprietario, e il cambio di proprietà scarta anche i
  metadati non confermati. `getMetadataAll(acquisition, entityIds)`
  restituisce un `ApplicationMetadata` (ID e mappa chiave → valore) per
  ogni ID presente nello snapshot, nello stesso ordine, con la mappa
  vuota per un'entità senza metadati; gli ID assenti si saltano, come
  in `getAll`.
- **API.** Le scritture stanno sull'handle del writer, nell'interfaccia
  comune `PartitionWriter` perché non dipendono dalla dimensione:
  `putMetadata(id, key, value)` e `deleteMetadata(id, key)` sono batch
  di una scrittura, `updateMetadataAll(writes)` il batch. Le letture
  stanno sulla view dello snapshot: `getMetadata(id)` restituisce un
  `Optional` della mappa, vuoto se l'ID non è nello snapshot, e
  `getMetadataAll(ids)` la lista di `ApplicationMetadata`. La forma è
  in [API da esporre](/decisions/api-surface.md#operazioni-sulle-entità).
- **Scritture condizionate.** Il batch è `ConditionalWrite2`/
  `ConditionalWrite3`: condizioni `MetadataCondition` (`ValueIs`, la
  chiave ha il valore; `Absent`, la chiave non ha valore), posizioni
  nuove di entità esistenti e scritture dei metadati. `updateIf(token,
  write)` nel contratto del nodo, e `updateIf(write)` sull'handle del
  writer, controlla prima l'input (ID nella partizione, anche quelli
  delle condizioni, e limiti delle posizioni), poi le condizioni sullo
  stato del writer, scritture non confermate comprese, e applica
  posizioni e metadati solo se sono tutte vere; tutto sotto il lock
  della partizione, quindi nessun'altra scrittura si mette in mezzo.
  Restituisce le condizioni false nel loro ordine, lista vuota se la
  scrittura è applicata. Il caso minimo è
  `compareAndSetMetadata(id, key, expected, value)` sull'handle, che
  restituisce `true` se ha scritto. Un processo che non è il writer
  propone la scrittura al writer, che la applica con il suo token.
- **Lettura.** I metadati si leggono per ID con una lettura separata da
  quella delle entità: `Entity2`/`Entity3` restano ID e posizione, così
  le query spaziali non pagano i metadati. Come ogni lettura, passa per
  uno snapshot acquisito.

# Scritture condizionate

Le scritture possono essere **condizionate** sui metadati, di sistema o
applicativi, per esempio:

- "inserisci se l'ID è assente o se l'epoca in arrivo è maggiore";
- "rimuovi se l'entità è in uscita con epoca e";
- "applica se la versione applicativa è ancora v".

Le scritture condizionate sostituiscono le transazioni tra partizioni.

## Forma

Una scrittura condizionata è un **batch** che contiene:

- le condizioni, ciascuna su una chiave di un'entità: "la chiave ha il
  valore v" oppure "la chiave è assente";
- le scritture da applicare: metadati e posizioni.

Il batch è **tutto-o-niente**, come ogni scrittura (vedi [scritture
tutto-o-niente](/decisions/api-surface.md#scritture-tutto-o-niente)):
si applica solo se tutte le condizioni sono vere. Per le riserve a due
serve proprio questo; chi vuole proposte indipendenti manda batch
separati.

Una condizione falsa **non è un errore** ma un esito atteso: la
chiamata non cambia la partizione e restituisce le condizioni fallite.
Le eccezioni restano per gli errori, per esempio un ID che non è nella
partizione.

Quando un'entità viene rimossa, i suoi metadati di sistema
sopravvivono come [tombstone](/architecture/tombstones.md). I metadati
applicativi non entrano nella tombstone, a meno che non venga
richiesto esplicitamente al momento della rimozione.

# API dei metadati

I metadati aprono a estensioni sia dell'API comune sia di meccaniche
implementate dal software, quindi hanno un'API propria:

- lettura dei metadati di sistema e applicativi, per ID;
- scrittura dei metadati applicativi, semplice o condizionata;
- scritture condizionate di dati e metadati insieme, con condizione sui
  metadati (nel caso minimo un compare-and-set su un valore);
- lettura delle tombstone, inclusa la destinazione;
- eliminazione delle tombstone, usata dai meccanismi superiori.

## Estensioni possibili

- **Concorrenza ottimistica.** Una versione applicativa per entità
  permette a un processo che non è il writer di proporre modifiche del
  tipo "applica se la versione è ancora v". Il writer le applica o le
  rifiuta senza lock.
- **Riserve.** Un processo prenota un'entità per un'operazione, per
  esempio un'interazione tra due giocatori.
- **Tag di responsabilità.** Il processo che gestisce l'entità e decide
  se spostarla, distinto dal writer della partizione.
- **Scadenze.** Un'entità scade dopo un certo tick, come un proiettile.
- **Filtri nelle query**, per esempio escludere le copie in uscita o
  selezionare per tag. Richiedono indici sui metadati: fuori dal livello
  base, e rimandati insieme a quegli indici; eventualmente si
  realizzano dopo.

# Punti aperti

- Nomi e firme delle chiamate dell'API dei metadati che ancora non ci
  sono (metadati di sistema, condizioni sui metadati di sistema,
  tombstone): si definiscono con lo sviluppo del prototipo. Lettura,
  scrittura e scritture condizionate dei metadati applicativi sono [nel
  prototipo](#nel-prototipo).

# Correlati

- [Architettura minima](/architecture/minimal-core.md) — primitiva 3.
- [API da esporre](/decisions/api-surface.md).
