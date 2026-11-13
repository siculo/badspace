---
type: Architecture Primitive
title: "Costi del buffer"
description: "Punti aperti dopo il refactoring delle scritture: costi del buffer e controlli da riesaminare per la migrazione."
tags: [badspace, architecture, transactions, api]
status: draft
timestamp: 2026-11-13T12:00:00Z
---

# Stato

Le [scritture accumulate nel writer](/current/architecture/partition-commit.md#scritture-accumulate-nel-writer),
le [modalità di scrittura](/current/architecture/partition-commit.md#modalità-di-scrittura)
e il flush sono implementati e descritti nella parte corrente. Questa
pagina raccoglie solo ciò che resta aperto: il profilo completo delle
misure e le ottimizzazioni che ne possono seguire e i controlli da
riesaminare per la migrazione.

Le [misure](/current/architecture/partition-commit.md#costi-del-buffer-e-del-commit)
disponibili usano il profilo rapido; il profilo completo resta da fare.
BADSPACE non impone limiti alla dimensione dei commit: i costi sono
documentati e il writer, se serve, divide le scritture con il flush.

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
  [flush](/current/architecture/partition-commit.md#modalità-di-scrittura)
  la riduce già, se il writer manda le scritture a pezzi, senza toccare
  l'atomicità del commit; un'applicazione del batch fuori dal lock
  seguita da uno scambio sotto il lock richiede di progettare la
  preparazione su una copia dello stato.

Con OpenJDK 25.0.2 due run lunghi su cinque si sono fermati per un crash
della JVM: un `SIGSEGV` nel codice compilato da C2 (OSR) di
`WriteBuffer2.changes()`, e un altro in un thread della garbage
collection G1, tutti e due leggendo l'heap. Il prototipo non usa codice
nativo né `Unsafe`, e il kernel non ha registrato errori di memoria. Con
Temurin 25.0.5 tutto il piano, con gli stessi compiler blackholes di JMH,
è arrivato in fondo senza crash e con gli stessi tempi: la causa
probabile è la build OpenJDK 25.0.2. Va confermato con altri run, e i
benchmark si fanno con Temurin 25.0.5.

# Controlli della migrazione

La rimozione delle conditional writes richiede di riesaminare, quando
si arriverà alla migrazione, i controlli su epoca, stato e destinazione
previsti dai [metadati di sistema](/future/architecture/entity-metadata.md).
Leggere uno snapshot non garantisce da solo le precedenti precondizioni
sullo stato più recente: il protocollo futuro va riallineato, senza
reintrodurre implicitamente le primitive eliminate nell'API applicativa.
