---
type: Architecture Primitive
title: Conservazione delle versioni
description: Ogni partizione conserva acquisibili la versione corrente e quelle dei k commit precedenti (k + 1 versioni, k configurabile, default 0); i reader acquisiscono una versione, la leggono e la rilasciano, con una scadenza opzionale, e una versione uscita dalla finestra resta leggibile finché ha acquisizioni valide.
tags: [badspace, architecture, partitioning, consistency]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-13T09:00:00Z }
---

# Primitiva

Ogni partizione può conservare, oltre alla versione corrente, gli
stati dei **k [commit](/architecture/partition-commit.md) precedenti**,
con k configurabile per partizione e **zero come default**: le versioni
conservate sono quindi k + 1, e con k = 0 resta solo la versione
corrente. Contano solo i commit effettivamente pubblicati: i numeri di
commit saltati non creano versioni. Ogni commit pubblica già uno
snapshot per l'[isolamento delle letture](/decisions/read-isolation.md),
e conservare le versioni significa tenere vive le ultime k + 1 radici. Gli
indici condividono i nodi non cambiati (copy-on-write); gli slot
costano una copia per versione.

Le k + 1 versioni conservate formano la **finestra di conservazione**:
sono le sole che ammettono **nuove acquisizioni**. Un reader legge una
versione solo attraverso un'acquisizione (vedi [Acquisizione e
rilascio](#acquisizione-e-rilascio)).

# Perché sta nel livello base

Senza questa primitiva le [letture coerenti](/mechanisms/consistent-reads.md)
su più partizioni non si possono costruire sopra in modo efficiente.
Per leggere lo stato al tick N da un writer che è già oltre, le sole
strade sono conservare le versioni passate oppure limitare il ritardo
tra i writer con una barriera: non esiste una terza via.

Essendo configurabile per partizione, la pagano solo le partizioni che
ne hanno bisogno (per esempio quelle di confine tra zone contigue).

# Acquisizione e rilascio

Un'**acquisizione** è il diritto di interrogare una versione: un
diritto solo logico, con identità propria. Più acquisizioni della
stessa versione, anche di reader diversi, sono indipendenti. Il
**rilascio** chiude una sola acquisizione: è idempotente, e dopo il
rilascio ogni nuova lettura attraverso quell'acquisizione fallisce. Il
rilascio non elimina per forza la versione.

Una versione si sceglie in quattro modi, tutti **atomici rispetto al
commit**: la scelta e l'acquisizione sono una sola operazione, quindi
un commit concorrente non può far sparire la versione tra le due.

| Selezione | Versione acquisita |
|---|---|
| Ultima | La versione corrente, anche quella vuota del commit 0 |
| Precedente di `offset` | Con `offset` = 0 la corrente; con `offset` = i l'i-esimo commit pubblicato prima della corrente. Fallisce se la versione non è nella finestra |
| Al tick, o prima | La versione acquisibile con il commit più grande ≤ tick; fallisce se non c'è |
| Esatta | La versione del commit indicato; fallisce se quel commit non è acquisibile |

L'ultimo commit della partizione resta leggibile come metadato, ma non
sostituisce l'acquisizione: leggere il commit e poi acquisirlo sono due
passi, e tra i due la finestra può cambiare.

**Esempio.** Commit pubblicati 100, 103 e 107, con k = 2: l'ultima è
107, la precedente di 1 è 103, la precedente di 2 è 100, al tick 105 o
prima dà 103, l'esatta 105 fallisce. Dopo il commit 110 la versione 100
esce dalla finestra: non si può più acquisire, ma le acquisizioni già
fatte su 100 restano valide.

Con k = 0 si acquisisce solo la versione corrente, ma una versione
acquisita resta leggibile anche dopo i commit successivi.

## Perché acquisizione e rilascio

Una prima direzione era leggere al commit N senza tenere la versione,
con errore fuori dalla finestra e senza rilascio esplicito. Non basta
per i reader che leggono a lungo la stessa versione, come il
salvataggio di uno snapshot su disco per la
[persistenza](/decisions/persistence.md): con molti commit nel
frattempo, la versione uscirebbe dalla finestra a metà del lavoro, e
una finestra abbastanza ampia per il caso più lento costerebbe memoria
a tutti i commit. Con l'acquisizione solo la versione che serve resta
in memoria, e la finestra resta piccola. Il costo del rilascio
esplicito è che un reader che non rilascia trattiene memoria: per
questo c'è la [scadenza](#scadenza) opzionale.

# Vita di una versione

1. **Acquisibile**: è nella finestra delle ultime k + 1 versioni.
2. **Pinnata**: è uscita dalla finestra, ma ha ancora acquisizioni
   valide o query già ammesse. Si legge attraverso quelle acquisizioni,
   ma non si può più acquisire: una versione uscita dalla finestra non
   torna mai acquisibile.
3. **Eliminabile**: è fuori dalla finestra, senza acquisizioni valide
   né query in corso. La memoria la libera il GC nel prototipo, e un
   conteggio dei riferimenti o l'epoch-based reclamation nel
   [linguaggio finale](/decisions/language.md); eliminabile non vuol
   dire liberata subito.

**Ammissione delle query.** Una query verifica l'acquisizione e prende
un riferimento interno stabile alla versione in un'unica operazione
atomica. Una query ammessa prima del rilascio o della scadenza può
finire; le query nuove sono respinte. I riferimenti delle query in
corso si contano a parte dalle acquisizioni (o con una protezione
equivalente), e si liberano anche quando la query fallisce. I
risultati di una query finita sono valori copiati e restano validi
dopo il rilascio: è vietata una nuova interrogazione della versione,
non l'uso dei dati già letti.

**Il writer non aspetta mai** query, acquisizioni o persistenza: i
reader lenti allungano solo la vita delle versioni in memoria. Le
acquisizioni appartengono al [nodo](/architecture/layers.md#nodi), non
al processo del reader, e si indicano con un identificatore anche
attraverso gRPC.

# Scadenza

Ogni acquisizione può avere una **scadenza** opzionale: una durata
misurata dal momento dell'acquisizione con il clock monotono locale del
nodo, non con l'ora di sistema né con il tick.

- **Nessuna scadenza** è una scelta valida ed esplicita
  dell'applicazione, utile per esempio per la persistenza lenta; il
  rilascio è allora tutto a carico del reader.
- Una durata **zero o negativa è rifiutata**. Una durata troppo grande
  per il clock vale come nessuna scadenza.
- La scadenza è **definitiva**: non si rinnova. Un'acquisizione nuova
  è possibile solo se la versione è ancora nella finestra, e non
  riattiva quella scaduta.
- Un'acquisizione scaduta si comporta come una rilasciata: le nuove
  letture falliscono, le query già ammesse finiscono e il loro
  risultato è valido, e un rilascio dopo la scadenza non fa niente.
- Il nodo elimina le acquisizioni scadute **da solo**, senza aspettare
  il reader che magari ha perso il collegamento: nella prima forma le
  cerca a ogni evento della partizione (acquisizione, rilascio,
  commit). Il limite è che una partizione senza eventi tiene in memoria
  le versioni con acquisizioni scadute fino all'evento successivo; un
  tempo di recupero garantito richiederebbe una pulizia periodica, oggi
  non prevista.

La scadenza dell'acquisizione è diversa dalla scadenza di una chiamata
remota, che è fuori da questa primitiva, e dalla scadenza delle letture
nella [rimozione differita](/mechanisms/consistent-reads.md#alternativa-rimozione-differita).

# Rimozione della partizione

Dopo la rimozione della partizione non si possono più fare scritture
né nuove acquisizioni, ma **le acquisizioni già fatte restano
leggibili** fino al rilascio o alla scadenza, e le query già ammesse
finiscono. Una versione letta dopo la rimozione non è sbagliata: mostra
la partizione al suo commit, dati vecchi ma coerenti.

Il catalogo delle versioni sopravvive quindi alla rimozione della
partizione dal nodo, finché ha acquisizioni valide. Una nuova
partizione con lo stesso ID è un'altra incarnazione: le acquisizioni
vecchie restano legate a quella rimossa e non vedono mai la nuova.

Una fase intermedia più semplice è **rifiutare la rimozione** finché la
partizione ha acquisizioni attive; non è però la semantica decisa, solo
un passo verso di essa.

# Commit e tick

La versione di un commit diventa acquisibile quando `commit(n)`
finisce, non a un istante preciso del tick: il nodo non promette che
sia visibile proprio all'inizio del tick successivo. Regole come
"leggere non prima di gTick − 1" e la barriera tra partizioni spettano
ai livelli superiori. Ogni partizione pubblica per conto suo, con il
suo ciclo di commit: non c'è atomicità tra partizioni.

# Nel prototipo

Oggi un reader prende la versione dell'ultimo commit con
`lastVersion()` e la tiene senza limiti; la conservazione delle k
versioni, l'acquisizione, il rilascio e la scadenza sono da
implementare (vedi [API da
esporre](/decisions/api-surface.md#acquisizione-delle-versioni)).

# Punti aperti

- Costo in memoria degli slot con k alto: una copia per versione, da
  misurare.
- Un'acquisizione senza scadenza può trattenere memoria senza limiti:
  è una responsabilità dell'applicazione, non un errore del database.
  Pochi metadati diagnostici (acquisizioni attive, versioni pinnate,
  commit pinnato più vecchio) solo se servono alla verifica.
- Pulizia periodica delle acquisizioni scadute, se servirà un tempo di
  recupero garantito.

# Correlati

- [Architettura minima](/architecture/minimal-core.md) — primitiva 5.
- [Isolamento delle letture](/decisions/read-isolation.md#accesso-dei-reader)
  — pubblicazione delle versioni e accesso controllato dei reader.
