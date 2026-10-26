---
type: Architecture Primitive
title: "Metadati applicativi e scritture condizionate"
description: "Metadati con chiavi stringa e valori long, letti dagli snapshot e scritti dal writer; batch condizionati atomici su posizioni e metadati."
tags: [badspace, architecture, partitioning, metadata, api]
status: stable
generated: { by: codex/gpt-6, at: 2026-10-25T09:04:35Z }
---

# Ruolo e garanzie

Ogni entità può avere metadati applicativi separati dalla posizione.
Il software ne sceglie il significato. Dati spaziali e metadati cambiano
nello stesso commit e si leggono dallo stesso snapshot; modificare i
metadati non aggiorna l’indice spaziale.

Le scritture condizionate verificano i valori attesi e applicano insieme
le modifiche sotto il lock della partizione. Questo evita il problema
di una verifica seguita da una scrittura su uno stato già cambiato.

## Metadati applicativi

I metadati applicativi di un'entità sono coppie chiave → valore, con
**chiavi stringa e valori `long`**. Bastano per tutti i casi d'uso
noti: una versione, una riserva come ID del processo, un tag di
responsabilità come ID, una scadenza come tick. Altri tipi di valore si
aggiungono solo quando un caso concreto li richiede.

Una chiave è una stringa UTF-16 ben formata, cioè **senza surrogate
isolati**: i salvataggi scrivono le chiavi in UTF-8, che non può
conservarli, e due chiavi diverse rischierebbero di diventare uguali. Le
scritture e le condizioni con una chiave non valida falliscono con
`IllegalArgumentException` già alla creazione, quindi non entrano in un
batch e non ne applicano una parte; nel prototipo il controllo è
`ApplicationMetadata.checkKey`. Ogni altra stringa è una chiave valida,
anche quella vuota.

Per ora **non ci sono limiti** sul numero di chiavi di un'entità né
sulla lunghezza delle chiavi: nessuna garanzia del sistema ne dipende,
e il costo di molte chiavi lo paga solo chi le usa.

La rimozione di un'entità ne cancella i metadati applicativi.

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
  in [Contratto dell’API](/current/api/operations.md#operazioni-sulle-entità).
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

Una condizione può chiedere che una chiave abbia il valore atteso oppure
sia assente. Per esempio il writer può applicare una proposta solo se
la versione applicativa dell’entità è ancora quella letta dal proponente.
Le condizioni si valutano sullo stato del writer, comprese le scritture
non ancora confermate.

## Forma

Una scrittura condizionata è un **batch** che contiene:

- le condizioni, ciascuna su una chiave di un'entità: "la chiave ha il
  valore v" oppure "la chiave è assente";
- le scritture da applicare: metadati e posizioni.

Il batch è **tutto-o-niente**, come ogni scrittura (vedi [scritture
tutto-o-niente](/current/api/operations.md#scritture-tutto-o-niente)):
si applica solo se tutte le condizioni sono vere. Per le riserve a due
serve proprio questo; chi vuole proposte indipendenti manda batch
separati.

Una condizione falsa **non è un errore** ma un esito atteso: la
chiamata non cambia la partizione e restituisce le condizioni fallite.
Le eccezioni restano per gli errori, per esempio un ID che non è nella
partizione.

# API dei metadati

Il writer offre la verifica dei valori attesi insieme a
scritture di posizioni e metadati; le letture pubbliche dei metadati
restano sulla view dello snapshot. I dati restituiti dalle query spaziali
contengono solo ID e posizione: chi non usa metadati non ne paga la lettura.

## Estensioni possibili nel software

- **Concorrenza ottimistica:** una versione applicativa controllata dal writer.
- **Riserve:** un valore identifica chi ha prenotato l’entità.
- **Responsabilità:** un ID indica il componente che gestisce l’entità.
- **Scadenze:** un valore rappresenta il tick dopo il quale il software rimuove l’entità.

Queste sono politiche applicative, non automatismi di BADSPACE.

# Correlati

- [Contratto dell’API](/current/api/operations.md#operazioni-sulle-entità).
- [Isolamento delle letture](/current/decisions/read-isolation.md#tutti-i-dati-delle-entità).
