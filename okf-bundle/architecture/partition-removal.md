---
type: Architecture Primitive
title: Rimozione della partizione
description: La rimozione di una partizione passa per quattro stati (attiva, in rimozione, ritirata, distrutta); remove() è l'ultima operazione del writer, la rimozione diventa effettiva al primo avanzamento del tick (removalTick), gli snapshot dei tick precedenti restano acquisibili finché sono nella finestra, e la partizione si distrugge quando nessun tick in cui esisteva è più nella finestra e nessuno usa i suoi snapshot.
tags: [badspace, architecture, partitioning, consistency]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-17T19:00:00Z }
---

# Primitiva

Una partizione rimossa non sparisce subito: i suoi snapshot dei tick in
cui esisteva restano acquisibili finché sono nella [finestra di
conservazione](/architecture/snapshot-retention.md). Uno snapshot letto
dopo la rimozione non è sbagliato: mostra la partizione a quel tick,
dati vecchi ma coerenti.

La partizione attraversa quattro stati:

| Stato | Nel codice | Scritture e commit | Nuove acquisizioni |
|---|---|---|---|
| Attiva | `ACTIVE` | Sì | Sì, nella finestra |
| In rimozione | `REMOVAL_PENDING` | No | Sì, nella finestra |
| Ritirata | `RETIRED` | No | Sì, nella finestra e per i tick `n < removalTick` |
| Distrutta | `DESTROYED` | No | No |

# Richiesta di rimozione

`remove()` porta la partizione nello stato **in rimozione**. È l'ultima
operazione del writer, e come le scritture richiede il token di
scrittura corrente (vedi [proprietà della
partizione](/architecture/partition-ownership.md)):

- dopo `remove()` non si possono più fare scritture né commit, e un
  secondo `remove()` fallisce con `IllegalStateException`; anche
  `claim` è rifiutato, quindi nessuno può più prendere la proprietà;
- `remove()` **scarta le scritture non confermate**: l'ultimo snapshot
  contiene solo l'ultimo stato confermato da `commit()`;
- con la [politica di
  rimozione](/decisions/api-surface.md#creazione-e-rimozione-delle-partizioni)
  `REQUIRE_EMPTY`, `remove()` controlla che sia vuoto lo **stato
  confermato del tick corrente**, cioè quello che sarà in `S(T)`. Chi
  vuole togliere le entità e poi la partizione chiama prima `commit()`,
  poi `remove()`.

La partizione esiste ancora nel tick corrente: un commit fatto prima di
`remove()` nello stesso tick entra nell'ultimo snapshot valido.

**Deroga alla prima versione della semantica.** Il documento sorgente
lasciava scritture e commit ammessi fino alla fine del tick, anche dopo
`remove()`. Con `REQUIRE_EMPTY` le scritture successive potevano
rendere la partizione di nuovo non vuota, e la rimozione effettiva
avviene in `setTick`, che non può fallire per una sola partizione. Con
`remove()` come ultima operazione il controllo si fa una volta sola, e
dopo nessuna scrittura lo rende falso.

# Rimozione effettiva

La rimozione diventa effettiva al **primo `setTick(t)` che fa avanzare
il tick** della partizione (vedi [tick logico](/architecture/logical-time.md)).
In quella transizione, atomica per la partizione:

1. si chiude lo stato confermato del tick precedente;
2. si definiscono gli snapshot degli eventuali tick intermedi;
3. il tick della partizione avanza a `t`;
4. la partizione diventa **ritirata** e memorizza `removalTick = t`.

La partizione esiste quindi per i tick `n < removalTick` e non esiste per
`n ≥ removalTick`. `removalTick` dipende dal primo avanzamento
effettivamente applicato, non dal tick della richiesta più uno. Un
`setTick` idempotente, che non fa avanzare il tick, non applica la
rimozione.

**Esempio.** La partizione è al tick 102, il writer chiama `remove()`, e
il primo avanzamento è `setTick(105)`: la rimozione è effettiva dal tick
105. Gli snapshot `S(102)`, `S(103)` e `S(104)` restano validi, senza
chiamate per i tick 103 e 104.

# Letture storiche

Una partizione ritirata continua a ricevere gli avanzamenti del tick,
che fanno scorrere la finestra. Con tick corrente `T`, creazione `C` e
rimozione `R`, un'acquisizione nuova di `S(n)` è ammessa solo se:

    max(T − k, C) ≤ n ≤ T − 1   e   n < R

Le acquisizioni già fatte restano leggibili fino al rilascio o alla
[scadenza](/architecture/snapshot-retention.md#scadenza), anche quando lo
snapshot esce dalla finestra; le query già ammesse finiscono.

**Esempio.** Con k = 5 e `removalTick = 105`: al tick 105 si acquisiscono
gli snapshot da `S(100)` a `S(104)`; al tick 109 resta `S(104)`; dal tick
110 non si acquisisce più niente.

# Distruzione

Una partizione ritirata diventa **distrutta** quando:

- nessun tick in cui esisteva è più nella finestra, cioè `T − k ≥ R`; e
- nessuno snapshot ha ancora utilizzi: acquisizioni valide o query in
  corso (vedi [vita di uno
  snapshot](/architecture/snapshot-retention.md#vita-di-uno-snapshot)).

Il momento giusto per controllarlo è la fine dell'ultimo snapshot: con
k ≥ 1, prima della rimozione l'ultimo snapshot è sempre nella finestra,
quindi gli snapshot finiscono tutti solo dopo il ritiro. Le risorse si
possono liberare anche dopo, non per forza dentro `setTick`; conta che
dopo l'uscita dalla finestra non siano più ammesse nuove acquisizioni.
La scadenza obbligatoria delle acquisizioni garantisce che la
distruzione arrivi sempre, se le query hanno durata finita.

Gli [ID delle partizioni](/decisions/entity-ids.md#id-delle-partizioni)
**non si riusano mai**: la partizione resta sul nodo con il suo ID fino
alla distruzione, letture e rilascio la trovano per ID, e il nodo
rifiuta una nuova partizione con quell'ID. Dopo la distruzione il nodo
non conosce più l'ID: risponde con un errore generico, senza
distinguere una partizione mai esistita da una distrutta, e un rilascio
non fa niente.

# Punti aperti

- Verifica dell'esistenza a un tick (`partitionExistsAt`) e
  dell'acquisibilità (`canAcquireSnapshot`): rimandate alle [letture
  coerenti](/mechanisms/consistent-reads.md). Senza tombstone delle
  partizioni, per un ID sconosciuto la risposta può essere solo
  "sconosciuto" (vedi [conservazione degli
  snapshot](/architecture/snapshot-retention.md#punti-aperti)).

# Nel prototipo

Gli stati sono l'enum `PartitionState` del service; lo storage tiene lo
stato in un campo `volatile`.

- **Richiesta di rimozione.** `removePartition` e `dropPartition` del
  nodo chiamano `requestRemoval` dello storage, che sotto il lock della
  partizione rifiuta una seconda richiesta e, per `REQUIRE_EMPTY`,
  controlla che l'ultimo commit non abbia entità; poi lo stato diventa
  `REMOVAL_PENDING`. Le scritture non confermate restano nello stato
  del writer, che nessuno legge più: l'ultimo snapshot è quello
  dell'ultimo commit. Il nodo rifiuta le operazioni del writer su una
  partizione non attiva, con `IllegalStateException`.
- **Ritiro.** Il primo `setTick` che fa avanzare il tick, con lo stato
  `REMOVAL_PENDING`, pubblica come sempre lo snapshot in attesa e passa
  al catalogo il tick di rimozione; lo stato diventa `RETIRED`. Il
  catalogo non dà nessun tick dalla rimozione in poi, con il motivo
  `AFTER_REMOVAL`, e il suo ultimo snapshot vale al più fino al tick
  prima della rimozione, quindi esce dalla finestra quando `T − k`
  raggiunge il tick di rimozione.
- **Distruzione.** Quando finisce l'ultimo snapshot lo stato diventa
  `DESTROYED`, e il thread che lo fa finire toglie la partizione dal
  nodo. Il nodo non ha più la mappa delle partizioni segnate: ogni
  `setTick` arriva a tutte le partizioni, anche a quelle ritirate, e
  cerca le loro acquisizioni scadute.
- **API.** La rimozione si chiede con `removePartition()` sull'handle
  del writer, che porta il token. Né l'handle né il proxy tengono lo
  stato della partizione: passano ogni chiamata al nodo, che rifiuta le
  operazioni del writer dopo la rimozione con `IllegalStateException`;
  acquisizioni, `tick()`, `creationTick()` e `ownership()` rispondono
  finché la partizione non è distrutta e poi falliscono con
  `IllegalArgumentException`.

I test verificano gli esempi di questa pagina (rimozione chiesta al
tick 102 e primo avanzamento a 105; con k = 5 al tick 109 resta
`S(104)` e al tick 110 la partizione è distrutta), il `setTick` con lo
stesso tick che non applica la rimozione, le scritture non confermate
scartate, `REQUIRE_EMPTY` sull'ultimo commit, la seconda richiesta
rifiutata, la partizione creata e rimossa prima del primo avanzamento
(esiste al suo tick di creazione), la distruzione all'avanzamento, al
rilascio, alla fine dell'ultima query e alla scadenza trovata da un
avanzamento del tick, l'ID della partizione ritirata rifiutato da
`createPartition` fino alla distruzione. Un test di concorrenza fa
correre acquisizioni e letture con i commit, la rimozione e gli
avanzamenti successivi: ogni lettura vede un commit intero, e al
rilascio dell'ultima acquisizione il nodo non ha più la partizione.

# Correlati

- [Conservazione degli snapshot](/architecture/snapshot-retention.md).
- [Tick logico](/architecture/logical-time.md).
- [API da esporre](/decisions/api-surface.md#creazione-e-rimozione-delle-partizioni)
  — forma delle chiamate ed errori.
- Da non confondere con la [rimozione
  differita](/mechanisms/consistent-reads.md#alternativa-rimozione-differita)
  delle entità durante la migrazione.
