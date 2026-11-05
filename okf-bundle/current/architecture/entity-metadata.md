---
type: Architecture Primitive
title: "Metadati applicativi"
description: "Metadati con chiavi stringa e valori long, letti dagli snapshot e scritti dal writer; decisioni del writer prese sugli snapshot."
tags: [badspace, architecture, partitioning, metadata, api]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-11-05T10:14:34Z }
---

# Ruolo e garanzie

Ogni entità può avere metadati applicativi separati dalla posizione.
Il software ne sceglie il significato. Dati spaziali e metadati cambiano
nello stesso commit e si leggono dallo stesso snapshot; modificare i
metadati non aggiorna l’indice spaziale.

Il writer decide le scritture leggendo uno snapshot e poi scrive senza
condizioni: BADSPACE non verifica valori attesi al momento della
scrittura (vedi [decisioni su uno snapshot](#decisioni-su-uno-snapshot)).

## Metadati applicativi

I metadati applicativi di un'entità sono coppie chiave → valore, con
**chiavi stringa e valori `long`**. Bastano per tutti i casi d'uso
noti: una versione, una riserva come ID del processo, un tag di
responsabilità come ID, una scadenza come tick. Altri tipi di valore si
aggiungono solo quando un caso concreto li richiede.

Una chiave è una stringa UTF-16 ben formata, cioè **senza surrogate
isolati**: i salvataggi scrivono le chiavi in UTF-8, che non può
conservarli, e due chiavi diverse rischierebbero di diventare uguali. Le
scritture con una chiave non valida falliscono con
`IllegalArgumentException` già alla creazione, quindi non entrano in un
batch e non ne applicano una parte; nel prototipo il controllo è
`ApplicationMetadata.checkKey`. Ogni altra stringa è una chiave valida,
anche quella vuota.

Per ora **non ci sono limiti** sul numero di chiavi di un'entità né
sulla lunghezza delle chiavi: nessuna garanzia del sistema ne dipende,
e il costo di molte chiavi lo paga solo chi le usa.

La rimozione di un'entità ne cancella i metadati applicativi. Una
scrittura dei metadati su un'entità che non è nella partizione non fa
niente: non crea un'entità senza posizione e non resta in attesa di una
`set`. Le scritture valgono sullo stato del momento, una dopo l'altra:
dopo una `remove` e una nuova `set` l'entità riparte senza metadati, e
una scrittura fatta prima della `set` che l'ha aggiunta è persa.

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
- **Contratto del nodo.** Le scritture dei metadati arrivano in un batch
  di modifiche (`EntityWrite2`/`EntityWrite3`), con la scrittura della
  modalità diretta o con il commit della modalità con buffer: liste di `MetadataWrite`, ciascuna su
  una chiave di un'entità, dove `Put` imposta il valore e `Delete` toglie
  la chiave. Valgono con la regola sequenziale, nelle due [modalità di
  scrittura](/current/architecture/partition-commit.md#modalità-di-scrittura): il nodo le applica nel loro ordine, o il buffer
  dell'handle le compone con lo stesso risultato, quindi per la stessa
  chiave vince l'ultima e una scrittura su un'entità che in quel momento
  non c'è non fa niente (vedi [scritture accumulate](/current/architecture/partition-commit.md#scritture-accumulate-nel-writer)). `getMetadataAll(acquisition, entityIds)`
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
  in [Contratto dell’API](/current/api/operations.md#operazioni-sulle-entità).
- **Lettura.** I metadati si leggono per ID con una lettura separata da
  quella delle entità: `Entity2`/`Entity3` restano ID e posizione, così
  le query spaziali non pagano i metadati. Come ogni lettura, passa per
  uno snapshot acquisito.

# Decisioni su uno snapshot

Il writer legge uno snapshot, decide con la propria logica e scrive
senza condizioni. Per esempio accetta una riserva se nello snapshot
l'entità non ha la chiave della riserva, e poi la scrive con
`putMetadata`. Con un [writer unico](/current/decisions/concurrency.md)
nessun altro scrive nella partizione tra la lettura e la scrittura.

Lo snapshot però non vede tutto quello che il writer ha già scritto:

- le scritture non confermate, perché le letture passano solo dagli
  snapshot (vedi [isolamento delle letture](/current/decisions/read-isolation.md));
- le scritture confermate nel tick corrente, anche da un proprietario
  precedente, perché lo snapshot di un tick si pubblica quando il tick
  avanza (vedi [commit della partizione](/current/architecture/partition-commit.md)).

Le decisioni che lo snapshot non vede ancora, di solito quelle del tick
corrente, il writer le tiene da sé, per esempio in un insieme degli ID
riservati nel tick, e le controlla insieme allo snapshot. Con un writer per tick, che legge l'ultimo
snapshot all'inizio del tick, lo snapshot comprende tutti i commit dei
tick precedenti (vedi [un writer per tick](/current/architecture/partition-ownership.md#un-writer-per-tick)).
L'esempio `snapshot-decisions` del prototipo mostra due riserve nello
stesso tick: lo snapshot dice libera l'entità anche alla seconda, e la
decisione del writer la rifiuta.

Un processo che non è il writer propone la scrittura al writer, che la
valuta allo stesso modo e la applica con il suo token.

# API dei metadati

Il writer scrive posizioni e metadati senza condizioni; le letture
pubbliche dei metadati restano sulla view dello snapshot. I dati restituiti dalle query spaziali
contengono solo ID e posizione: chi non usa metadati non ne paga la lettura.

## Estensioni possibili nel software

- **Concorrenza ottimistica:** una versione applicativa che il writer confronta con quella letta dal proponente, sullo snapshot e sulle proprie decisioni del tick.
- **Riserve:** un valore identifica chi ha prenotato l’entità.
- **Responsabilità:** un ID indica il componente che gestisce l’entità.
- **Scadenze:** un valore rappresenta il tick dopo il quale il software rimuove l’entità.

Queste sono politiche applicative, non automatismi di BADSPACE.

# Correlati

- [Contratto dell’API](/current/api/operations.md#operazioni-sulle-entità).
- [Isolamento delle letture](/current/decisions/read-isolation.md#tutti-i-dati-delle-entità).
