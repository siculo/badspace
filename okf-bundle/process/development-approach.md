---
type: Playbook
title: Approccio di sviluppo
description: BADSPACE viene sviluppato per prototipi minimali, verificati da test di integrazione e accompagnati da client d'esempio costruiti man mano, per validare le decisioni di progettazione sul campo; lo sviluppo è incrementale, ogni concetto nuovo porta con sé le estensioni che richiede e i meccanismi si implementano una volta e si estendono quando servono nuovi dati; i test partono dalla semantica, preferiscono componenti reali e verificano effetti, risorse e concorrenza; le scelte che dipendono dalle prestazioni si basano su benchmark relativi a un riferimento.
tags: [badspace, methodology, prototyping]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-17T14:00:00Z }
---

# Approccio

Procedere per **prototipi minimali** su cui ragionare e rivalutare le
scelte fatte, invece di progettare tutto a priori. Ogni prototipo è
verificato da **test di integrazione** e accompagnato da **client
d'esempio** che ne mostrano l'uso, per validare le decisioni sul campo.
I client non devono essere necessariamente un gioco.

Ogni decisione resta rivalutabile quando un prototipo porta
informazioni nuove.

# Sviluppo incrementale

Lo sviluppo è **incrementale**: ogni passo introduce solo quello che
serve ai concetti già presenti, e un concetto nuovo porta con sé le
estensioni che richiede, anche in parti del codice già scritte
(configurazione, API, contratto del nodo, storage, persistenza).
Un passo non anticipa parametri, campi o chiamate che riguardano
concetti che ancora non esistono.

Per esempio, alla creazione di una partizione si sceglie l'indice; il
numero k di commit passati da conservare si aggiunge alla
configurazione della partizione solo quando arrivano i
[commit](/architecture/partition-commit.md) e quindi gli snapshot,
perché prima k non ha significato.

## Meccanismi prima, estesi man mano

Un meccanismo si implementa una volta, appena serve, con l'insieme
minimo di dati che esistono in quel momento, e poi si estende quando
arrivano nuovi dati da gestire, invece di aspettare che ci siano
tutti. Per esempio la [persistenza](/decisions/persistence.md) si
implementa dopo i metadati, così salva già un insieme minimo ma
significativo di informazioni, e si estende alle tombstone quando
arrivano.

L'ordine dei prototipi tiene conto solo dei prerequisiti veri, cioè di
ciò che senza un altro pezzo non può funzionare.

# Test di integrazione e client d'esempio

Le due cose hanno ruoli diversi e stanno in moduli separati del
prototipo:

- **Test di integrazione.** Verificano l'API insieme ai nodi del
  servizio, dal punto di vista del software che usa BADSPACE. Stanno in
  un modulo che contiene solo test e dipende da common, service e api,
  così l'API non dipende dal servizio nemmeno nei suoi test. La
  semantica in dettaglio resta nei test unitari di ogni modulo.
- **Client d'esempio.** Mostrano come si usa BADSPACE. Ogni esempio è
  un modulo a sé, con un solo programma, invece di più programmi nello
  stesso modulo: per esempio un giro dell'API (spazi, partizioni,
  operazioni batch, query, tick e rimozione) e un writer che muove le
  entità a ogni tick mentre un reader mostra l'ultimo snapshot.

Quanti e quali client servano non è deciso: si costruiscono man mano,
accanto ai prototipi che li richiedono, per esempio uno scenario con
aree non contigue per la [migrazione](/mechanisms/entity-migration.md)
e uno con zone contigue per le [letture
coerenti](/mechanisms/consistent-reads.md).

# Scrittura e revisione dei test

Questi criteri valgono sia per i nuovi test sia per la revisione di quelli
esistenti, nei moduli unitari e nei test di integrazione descritti sopra.

- **Partire dalla semantica.** Prima di scrivere o modificare un test, leggere la semantica nel bundle, il contratto del componente e il codice che lo implementa. Verificare comportamenti e invarianti con risultati attesi indipendenti dall'implementazione. Quando il software evolve, rivedere criticamente gli scenari e le asserzioni: non limitarsi ad adattare i test per farli passare.
- **Preferire componenti reali.** Usare le implementazioni reali delle dipendenze quando possibile. Per osservare o controllare un'interazione, preferire una facade che delega all'implementazione reale, creata tramite il framework di testing quando possibile. Riservare i mock a verifiche mirate di delega, argomenti, errori o interazioni necessarie al contratto. Non ricostruire nei fake la logica del componente dipendente: anche quella logica deve evolvere e può divergere dalla semantica reale.
- **Scegliere il livello adatto.** Verificare la semantica dei componenti nei rispettivi moduli e i comportamenti dell'API insieme ai nodi reali in `prototype/integration-tests`, senza introdurre una dipendenza dell'API dal service. Una verifica del numero o dell'ordine delle chiamate è utile solo se quel vincolo ha un significato nel contratto; non sostituisce la verifica del risultato.
- **Limitare gli accessi agli internals.** Non aggiungere né mantenere proprietà, funzioni o visibilità più ampia esclusivamente per i test, salvo che la verifica sia critica e non sia possibile attraverso il comportamento osservabile, oppure che l'esposizione sia utile per inspection e monitoring. Motivare queste eccezioni. Preferire informazioni in sola lettura ed evitare di esporre strutture mutabili. Eliminare gli accessi di supporto quando non servono più.
- **Verificare effetti e risorse.** Un errore atteso deve lasciare invariato lo stato previsto dal contratto. Per acquisizioni, query e altre risorse, verificare rilascio e fine effettiva del ciclo di vita, anche dopo un fallimento. La continuità degli ID o il solo conteggio delle chiamate non dimostrano l'assenza di risorse residue. Conservare gli handle realmente ottenuti, bilanciare ogni ammissione con la fine della query e usare `try-with-resources` o `finally` per la pulizia.
- **Rendere significativi i test concorrenti.** Usare latch o barriere per garantire che le operazioni rilevanti avvengano nelle fasi previste; avviare più thread non basta. Non affidarsi a sleep o spin per ottenere un ordine. Verificare che ogni worker abbia eseguito il lavoro richiesto, propagare i suoi errori al test, limitare le attese e controllare la terminazione dopo un `join` con timeout. Sbloccare le barriere e terminare i worker anche quando un'asserzione fallisce. Gli stress test completano gli scenari sincronizzati, senza sostituirli.
- **Controllare il tempo nei test.** Per scadenze e altri comportamenti temporali, usare un clock sostituibile e controllato dal test. Usare il generatore di ID reale nei test del generatore; negli altri test preferire generatori deterministici quando lo schema degli ID non è rilevante.
- **Mantenere la suite essenziale.** Un comportamento per test, nomi che descrivono il comportamento e copertura sia 2D sia 3D quando applicabile. Prima di eliminare o accorpare un test, identificare quale verifica conserva la sua garanzia. Non eliminare una sovrapposizione solo perché lo stesso scenario compare a livelli diversi: può verificare responsabilità diverse. Eseguire i test pertinenti alle modifiche e la suite completa prima di concludere una revisione trasversale.

# Benchmark

Le scelte che dipendono dalle prestazioni, come quella degli [indici
spaziali](/indices/), si prendono sulla base di misure:

- **Strumento.** Un modulo di benchmark con JMH misura velocità e
  memoria attraverso il contratto del nodo, quindi anche il costo dello
  storage e non solo quello dell'indice. Le query si misurano su uno
  snapshot acquisito, l'unico modo di leggere una partizione; le
  scritture senza commit, così i nodi dell'indice cambiano sul posto.
- **Misure relative.** Il prototipo Java non dà tempi rappresentativi
  dell'implementazione finale, quindi ogni indice si confronta con un
  riferimento, la [scansione lineare](/indices/linear-scan.md), e si
  ragiona sui rapporti.
- **Profili.** Un profilo rapido (circa 15 minuti) per controllare
  durante lo sviluppo, e un profilo completo (alcune ore) per le misure
  da tenere.
- **Piani.** Le serie di misure si descrivono in file JSON, con gruppi
  di parametri per profilo; tutto il piano si controlla prima di
  partire.
- **Macchina dedicata.** Le misure da confrontare si fanno sulla stessa
  macchina, dedicata ai benchmark.
- **Risultati tracciabili.** Ogni risultato riporta il commit del codice
  misurato, e un report HTML accompagna i dati.
- **Scenari di riferimento.** I parametri si ricavano da scenari
  concreti, come lo [scenario del sistema
  solare](/indices/solar-system-scenario.md), e da modelli come la
  [capacità degli aggiornamenti per tick](/indices/update-capacity.md).

# Correlati

- Tutte le [decisioni](/decisions/) sono destinate a essere rivalutate
  man mano che i prototipi portano nuove informazioni.
- Il [linguaggio](/decisions/language.md) dei prototipi è scelto per
  iterare in fretta.
