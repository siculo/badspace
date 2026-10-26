---
type: Design Decision
title: "Recupero e sviluppi della persistenza"
description: "Recupero ricostruttivo, tagli coerenti, durabilità osservabile, salvataggio asincrono e misure future."
tags: [badspace, documentation]
status: draft
generated: { by: codex/gpt-6, at: 2026-10-25T09:04:35Z }
---

# Perimetro

Questa pagina raccoglie proposte e decisioni ancora da attuare. Le operazioni già disponibili sono descritte in [Implementazione corrente](/current/index.md).

## Possibili attività future

Da valutare quando servono, nessuna è in corso:

- una rappresentazione compatta dei metadati nella copia e nel
  caricamento, per esempio array di chiavi e valori come nel file, se il
  costo dei metadati pesa nei casi d'uso;
- misure con il writer e i reader attivi durante copia e salvataggio;
- un recupero a freddo, con la cache del sistema operativo vuota;
- partizioni da 1 000 000 di entità e altri insiemi di metadati;
- altre destinazioni (disco NVMe, volume condiviso, object store).

# Recupero ricostruttivo

Per recuperare le partizioni ci sono due approcci: persistere lo stato
delle partizioni e dei nodi oltre agli snapshot, oppure **ricostruire lo
stato dai soli salvataggi**. La direzione scelta è la seconda, più
semplice da gestire: dopo un riavvio, o un crash, le partizioni si
ricreano caricando i loro salvataggi, e il resto dello stato è di
runtime e si ricrea.

L'identità persistente è il [nome della
partizione](/current/decisions/entity-ids.md#nome-della-partizione); l'ID è un
dato di runtime e non si persiste. Una partizione ricaricata ha lo
stesso nome e un ID nuovo, quindi i [token di
scrittura](/current/architecture/partition-ownership.md) dell'esecuzione
precedente, che contengono l'ID vecchio, non valgono più: il fencing
tra un'esecuzione e l'altra non richiede di persistere le generazioni.

Cosa si ricava dai salvataggi:

- **contenuto**: lo snapshot salvato;
- **stato dei protocolli**: epoca, stato, destinazione e
  [tombstone](/future/architecture/tombstones.md) sono nello snapshot di ogni
  commit, quindi nei salvataggi. La
  [migrazione](/future/mechanisms/entity-migration.md) non ha uno stato
  persistente separato: l'origine persiste la marcatura "in uscita"
  prima di spedire e la destinazione conferma dopo la persistenza, così
  una partizione ricaricata ritrova le entità da rispedire;
- **ultimo tick persistito** della [durabilità
  osservabile](/future/architecture/observable-durability.md): il tick più alto
  tra i salvataggi di un nome;
- **insieme delle partizioni** a un tick N: i nomi con un salvataggio
  al tick N, se il software ha salvato tutte le partizioni a quel tick
  con `save(N)`, comprese quelle create da uno split.

Non si persistono acquisizioni, sequenza dei commit, generazioni della
proprietà e tick interni, che sono stato di runtime; il generatore di
ID riceve un `generatorId` nuovo a ogni avvio.

**Il taglio è una politica del software.** Quale tick caricare per ogni
partizione lo sceglie il software, che ha i meccanismi per farlo:
`save(N)` per salvare tutte le partizioni allo stesso tick, l'elenco
dei salvataggi con nome e tick, e il caricamento. Il taglio coerente
(il tick più alto salvato da tutte le partizioni) è la scelta tipica,
non una regola: un taglio incoerente è possibile ed è responsabilità di
chi lo sceglie. Una funzione di supporto nell'API, per esempio il tick
più alto salvato da un insieme di nomi, si aggiunge se serve.

Limiti e condizioni:

- ogni riferimento persistito a una partizione usa il nome, mai l'ID;
- si perde il lavoro dopo l'ultimo salvataggio: i protocolli lo
  assumono già, perché non confermano niente prima della persistenza;
- con salvataggi completi l'ultimo tick persistito avanza al ritmo dei
  salvataggi, e la latenza della migrazione ne dipende. Se le misure lo
  rendono necessario, salvataggi incrementali (un salvataggio base e le
  differenze tra tick) restano un approccio ricostruttivo;
- il recupero di un solo nodo mentre gli altri continuano riporta le
  sue partizioni all'ultimo tick persistito, indietro rispetto allo
  spazio; va verificato con gli scenari di guasto della migrazione.

Sono rimandati il recupero di un nodo mentre gli altri continuano, la
continuità del tempo logico e l'esposizione dell'ultimo tick
persistito. Anche la persistenza della configurazione delle partizioni
(indice, finestra) è rimandata: si decide insieme al modo in cui il
software usa i nodi, crea le partizioni, spegne e riavvia il sistema e
recupera da un crash. Fino ad allora la configurazione la sceglie chi
carica. Il salvataggio deve comunque avere un esito di completamento o
errore.

# Punti aperti

- Eliminazione dei salvataggi e durabilità osservabile: l'ultimo tick
  persistito è il tick più alto tra i salvataggi di un nome, quindi
  eliminare il salvataggio più recente lo fa tornare indietro, mentre
  la migrazione conta sul fatto che non succeda. Si rivede con la
  durabilità osservabile.
- Salvataggio asincrono nel contratto del nodo, con il nodo gRPC e la
  durabilità osservabile.
- Durabilità del salvataggio secondo la piattaforma. Il nodo sincronizza
  file e directory sul disco, ma la garanzia dipende da sistema
  operativo, filesystem e disco: su macOS la sincronizzazione non svuota
  la cache del disco (serve `F_FULLFSYNC`), su Windows la directory non
  si sincronizza, e alcuni dischi ignorano il flush. Va rivista con
  l'implementazione finale e con destinazioni diverse da una directory
  locale (volume condiviso, object store), dove le garanzie cambiano; il
  nodo dovrà probabilmente dichiarare il livello di durabilità che offre.

# Salvataggio asincrono

Un salvataggio asincrono nel contratto del nodo si valuta con il [nodo
gRPC](/current/decisions/tool-type.md), dove una chiamata lunga occupa la
chiamata remota e il suo timeout, e con la [durabilità
osservabile](/future/architecture/observable-durability.md), dove serve sapere
quando un salvataggio è finito senza aspettarlo. La forma più probabile
è avviare il salvataggio e poi interrogarne lo stato.

# Collocazione della destinazione

I motivi:

- **Nessun trasferimento del contenuto.** Se scrivesse il software, a
  ogni salvataggio periodico tutto il contenuto della partizione
  passerebbe dal nodo al software: con un nodo remoto è un traffico
  proporzionale alla dimensione della partizione, ripetuto a ogni
  salvataggio.
- **Durabilità di una partizione.** La [durabilità
  osservabile](/future/architecture/observable-durability.md) è una proprietà
  della partizione: il nodo sa da solo quando un salvataggio è
  completo, senza che il software glielo comunichi.
- **Recupero dei nodi.** Il futuro recupero dopo un crash trova i dati
  delle partizioni sullo storage del nodo.
- **Forma a servizio.** È coerente con la possibile centralizzazione
  della destinazione in una forma a servizio (vedi [tipo di
  strumento](/current/decisions/tool-type.md)).
