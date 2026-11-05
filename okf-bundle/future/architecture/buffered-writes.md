---
type: Architecture Primitive
title: "Scritture accumulate nel writer"
description: "Parti ancora da fare del refactoring delle scritture accumulate: costi del buffer ed esiti remoti."
tags: [badspace, architecture, transactions, api]
status: draft
timestamp: 2026-11-05T18:54:02Z
---

# Stato e obiettivo

Direzione concordata, implementata in gran parte. Sono disponibili `set`
con inserimento se assente, la rimozione idempotente, la rimozione delle
conditional writes, il vincolo sulla
[provenienza degli ID](/current/decisions/entity-ids.md#provenienza-degli-id-delle-entità)
e le [scritture accumulate nel writer](/current/architecture/partition-commit.md#scritture-accumulate-nel-writer):
l'handle compone le scritture per entità e solo `commit()` le manda al
nodo, in un batch applicato all'ultimo commit, con fallimenti e
recupero verificati sul batch (vedi
[fallimenti della partizione](/current/architecture/partition-failures.md#nel-prototipo)).
Sono definiti anche lo stato del buffer dopo successo, rifiuto e
fallimento, uguale a quello delle scritture inviate una alla volta, e il
rapporto con il recupero sul nodo (vedi
[stato del buffer](/current/architecture/partition-commit.md#scritture-accumulate-nel-writer)).
Le prime [misure](/current/architecture/partition-commit.md#costi-del-buffer-e-del-commit)
di memoria del buffer e di durata del commit sono fatte con il profilo
rapido. Sul nodo locale il buffer costa più di quanto risparmia, quindi
il nodo sceglie la [modalità di scrittura](/current/architecture/partition-commit.md#modalità-di-scrittura): il nodo locale è nella
modalità diretta, con ogni scrittura subito sul nodo, e il buffer resta
per il nodo remoto, con gli stessi risultati; la modalità diretta costa
da 1,0 a 1,4 volte il tick di `main`, fino a 2,1 volte con la scansione
lineare e batch grandi. Con il buffer il writer può mandare le scritture
a pezzi con il [flush](/current/architecture/partition-commit.md#modalità-di-scrittura). Restano da fare il profilo completo, le
decisioni su limiti e ottimizzazioni che ne seguono, e il protocollo
remoto degli esiti.

Il software legge uno snapshot, esegue la propria logica e costruisce le
scritture, come descritto nelle
[decisioni su uno snapshot](/current/architecture/entity-metadata.md#decisioni-su-uno-snapshot):
non legge lo stato mutabile attraverso l'esito di una scrittura.
Lo snapshot letto può non comprendere commit ancora pending, anche di un
precedente proprietario; il refactoring non introduce letture del pending.

# Costi da valutare

Le misure con il profilo rapido, nella modalità con buffer, mostrano tre
costi da valutare:

- **Memoria del buffer**, circa 105 byte per entità con le sole `set`,
  dopo le mappe dei metadati create alla prima scrittura: ogni modifica
  ha ancora un oggetto, e la mappa per ID usa chiavi `Long` e una voce
  per entità. Una tabella con chiavi `long` e i campi della modifica in
  array paralleli la ridurrebbe ancora, con una misura JMH che la
  giustifichi.
- **Tempo del buffer**, circa 25–140 ns per entità scritta, che con
  batch grandi porta il tick dal writer fino a circa 2 volte il tick con
  le scritture date direttamente allo storage, e fino a 3 volte il tick
  di `main` con le scritture inviate una alla volta. Per questo il nodo
  locale usa la modalità diretta.
- **Durata del lock**, uguale a tutto il commit del batch: un
  avanzamento del tick aspetta l'intero batch in corso, al più uno con il
  lock equo della partizione. Con più partizioni sempre occupate sullo
  stesso nodo l'avanzamento le aspetta una alla volta; un avanzamento in
  parallelo, o rimandato al writer che lo applica alla fine del commit,
  le aspetterebbe insieme, con più coordinamento da progettare. Il
  [flush](/current/architecture/partition-commit.md#modalità-di-scrittura) la riduce già, se il writer manda le scritture a pezzi,
  senza toccare l'atomicità del commit; un limite alla dimensione dei
  commit la toglierebbe ai commit divisi, e un'applicazione del batch fuori dal lock seguita da
  uno scambio sotto il lock richiede di progettare la preparazione su una
  copia dello stato.

Con OpenJDK 25.0.2 due run lunghi su cinque si sono fermati per un crash
della JVM: un `SIGSEGV` nel codice compilato da C2 (OSR) di
`WriteBuffer2.changes()`, e un altro in un thread della garbage
collection G1, tutti e due leggendo l'heap. Il prototipo non usa codice
nativo né `Unsafe`, e il kernel non ha registrato errori di memoria. Con
Temurin 25.0.5 tutto il piano, con gli stessi compiler blackholes di JMH,
è arrivato in fondo senza crash e con gli stessi tempi: la causa
probabile è la build OpenJDK 25.0.2. Va confermato con altri run, e i
benchmark si fanno con Temurin 25.0.5.

# Rifiuti ed esiti

Rifiuti e fallimenti certi del nodo locale, con lo stato delle scritture
non confermate dopo ciascuno nelle due modalità, sono descritti nelle
[modalità di scrittura](/current/architecture/partition-commit.md#modalità-di-scrittura).
Per il remoto serve distinguere rifiuto, fallimento certo ed esito
incerto. Un batch a esito incerto resta congelato, senza commit successivi
che possano superarlo, finché si recupera l'esito. Servono riconoscimento
dei tentativi ripetuti e un esito stabile: il tick non identifica da solo
un commit, perché ne sono ammessi più di uno nello stesso tick.
Il protocollo e la conservazione degli esiti si progettano con il
[nodo gRPC](/future/analyses/grpc-node.md), senza anticiparli nel refactoring locale.
La ripetizione di `set` non rende automaticamente sicura la ripetizione
dell'intero commit, che potrebbe altrimenti ricadere in un tick diverso.
Lo stesso vale per il flush: ripeterlo dopo un esito incerto non è
sempre sicuro, perché le scritture dei metadati che valgono solo se
l'entità era nella base dipendono dallo stato su cui si applicano, e un
primo flush riuscito cambia quello stato.

# Attuazione e verifiche

Il gruppo di attività è nel [TODO del prototipo](../../../TODO.md#fare-scritture).
Ogni passo comprende 2D e 3D e aggiorna i chiamanti, i test e la relativa
documentazione. La rappresentazione concreta del batch, i limiti di memoria
e dimensione, e la durata del lock durante commit grandi vanno valutati
senza introdurre ora il trasporto remoto o nuove attività sugli indici.

La composizione è verificata contro l'esecuzione sequenziale; i test
dell'API verificano, con il buffer, zero chiamate al nodo durante
l'accumulazione, un solo invio al commit e lo stato del buffer dopo
rifiuti e fallimenti, e nella modalità diretta una chiamata per
scrittura; i test end-to-end girano nelle due modalità;
i test del nodo verificano rifiuti, fallimenti, recupero, token superati
e concorrenza con tick e cambio di proprietà sul batch.

La rimozione delle conditional writes richiede di riesaminare, quando
si arriverà alla migrazione, i controlli su epoca, stato e destinazione
previsti dai [metadati di sistema](/future/architecture/entity-metadata.md).
Leggere uno snapshot non garantisce da solo le precedenti precondizioni
sullo stato più recente: il protocollo futuro va riallineato, senza
reintrodurre implicitamente le primitive eliminate nell'API applicativa.
