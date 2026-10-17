---
type: Architecture Primitive
title: Proprietà della partizione
description: Ogni partizione ha al più un writer; la proprietà ha una generazione che cresce a ogni cambio, si legge con ownership(p), si prende con claim(p, expectedGeneration), un compare-and-set sulla generazione, e si lascia con release(token); scritture, commit e remove() portano il token (fencing token), e la partizione rifiuta quelli di una generazione vecchia, scartando le scritture non confermate a ogni cambio. Sopra la primitiva si costruiscono schemi diversi di gestione della proprietà.
tags: [badspace, architecture, partitioning, concurrency]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-17T19:00:00Z }
---

# Primitiva

Ogni partizione ha **al più un writer** e più reader. La partizione non
sceglie chi scrive: offre una primitiva piccola, e lo schema con cui la
proprietà passa da un writer all'altro lo decide il software (vedi
[schemi costruiti sopra](#schemi-costruiti-sopra)).

Senza questa primitiva un writer rallentato che ha perso la proprietà
potrebbe ancora scrivere: per esempio un writer fermo per una pausa del
GC, o dietro una rete lenta verso un nodo remoto, che riprende quando la
partizione è già passata a un altro writer. Con il **fencing token** è
la partizione a rifiutare le sue scritture, anche se lui non sa ancora
di aver perso la proprietà.

# Stato di proprietà

Ogni partizione ha uno stato di proprietà con due parti:

- la **generazione**, un contatore che cresce di 1 a **ogni** cambio di
  stato: presa della proprietà, passaggio a un altro writer, revoca,
  rilascio;
- il segno che dice se la partizione **ha un proprietario**.

La partizione nasce **senza proprietario**, alla generazione 0. Dato che
ogni cambio incrementa la generazione, una generazione identifica un
solo stato di proprietà.

Il **token di scrittura** (fencing token) è la coppia (ID della
partizione, generazione). Gli [ID delle
partizioni](/decisions/entity-ids.md#id-delle-partizioni) non si
riusano e la generazione cresce soltanto, quindi un token non torna mai
valido.

# Operazioni

| Operazione | Effetto |
|---|---|
| `ownership(p)` | Legge lo stato: la generazione e se la partizione ha un proprietario. È solo una lettura: la partizione non avvisa nessuno dei cambi. |
| `claim(p, expectedGeneration)` | Se la generazione è ancora `expectedGeneration`, la porta a quella successiva con un proprietario e restituisce il token nuovo; altrimenti fallisce e lo stato non cambia. |
| `release(token)` | Se il token è quello corrente, porta la generazione a quella successiva senza proprietario; altrimenti non fa niente (idempotente). |

`claim` è un **compare-and-set sulla generazione**: chi lo chiama dice
quale stato di proprietà vuole sostituire, e se nel frattempo lo stato
è cambiato la sua operazione fallisce invece di fare danni. È l'unica
operazione che dà la proprietà: prendere una partizione libera, passarla
a un altro writer e revocarla al proprietario sono la stessa chiamata, e
cambia solo chi la fa e quale generazione passa:

| Caso | Chi chiama | Generazione passata |
|---|---|---|
| Prendere una partizione libera | Il writer che la vuole | Quella dello stato libero, letta con `ownership(p)` |
| Passare la proprietà da A a B | A | La propria, dal suo token; A manda a B il token nuovo |
| Revocare la proprietà ad A | Un coordinatore | Quella attuale, letta con `ownership(p)` |

Non servono operazioni separate per questi casi: controlli come "solo se
la partizione è libera" o "solo il proprietario può passarla" sono
politiche, e chi chiama li fa già scegliendo la generazione. La
partizione non controlla chi chiama `claim` (vedi
[accesso](/decisions/tool-type.md#accesso)).

# Scritture con il token

Le scritture, `commit()` e `remove()` portano il token. La partizione
lo confronta con la generazione corrente **sotto il lock della
partizione**, lo stesso che ordina scritture, commit e avanzamento del
tick (vedi [concorrenza](/decisions/concurrency.md)): se il token non è
quello corrente, l'operazione è rifiutata con un errore dedicato e la
partizione non cambia.

Restano fuori dal fencing:

- l'avanzamento del [tick](/architecture/logical-time.md), che arriva
  dal software e non è un'operazione del writer;
- le letture, che passano tutte dalle
  [acquisizioni](/architecture/snapshot-retention.md#acquisizione-e-rilascio)
  degli snapshot: un writer, aggiornato o in ritardo, che acquisisce uno
  snapshot vede solo commit pubblicati, come ogni reader, e non vede mai
  le scritture non confermate di un altro writer.

# Cambio di proprietà

- **Il confine è il commit.** A ogni cambio di generazione (`claim` o
  `release`) le scritture non confermate si scartano. Un commit fatto
  prima del cambio resta confermato, anche se lo snapshot del suo tick
  non è ancora pubblicato: il nuovo proprietario parte da quello stato,
  e i suoi commit nello stesso tick si aggiungono, con l'ultimo che
  definisce lo snapshot del tick (vedi [commit](/architecture/partition-commit.md#commit-e-tick)).
- **Rilascio senza commit.** Un writer può decidere di non confermare le
  sue scritture e rilasciare il token: le scritture si scartano.
- **Nessuna via di mezzo.** Grazie al lock, ogni operazione del vecchio
  writer avviene tutta prima del cambio oppure è rifiutata: il writer sa
  con esattezza quali dei suoi commit sono entrati.
- **Il vecchio writer lo scopre alla prossima operazione**, che viene
  rifiutata; può anche controllare `ownership(p)`.
- **Rimozione.** `remove()` richiede il token corrente: chi vuole
  rimuovere una partizione di cui non è proprietario fa prima `claim`.
  Dalla [rimozione](/architecture/partition-removal.md) in poi `claim`
  è rifiutato.

# Schemi costruiti sopra

| Schema | Come si realizza |
|---|---|
| **Owner fisso** | Il writer, per esempio il game loop, fa `claim(p, 0)` alla creazione e tiene il token per sempre. |
| **Cooperativo** | Il proprietario A chiama `claim` con la generazione del suo token e manda a B il token nuovo: in nessun momento ci sono due token validi. |
| **Coordinatore** | I writer non chiamano mai `claim`: il coordinatore legge `ownership(p)`, chiama `claim` e consegna il token al writer scelto. Con due coordinatori in corsa, per esempio durante un failover, vince al massimo uno per generazione. |
| **Coordinatore che aspetta il writer** | Il coordinatore chiede ad A di finire, con un canale del software; A fa l'ultimo commit e chiama `release`; il coordinatore vede con `ownership(p)` la partizione libera, oppure riceve la generazione da A, e chiama `claim`. In alternativa A restituisce il token al coordinatore, che chiama `claim` con la sua generazione: la partizione non resta mai senza proprietario. |
| **Presa forzata** | Se A non risponde, il coordinatore chiama `claim` con la generazione di A, senza il suo token: le scritture non confermate di A si scartano e le sue operazioni successive sono rifiutate. Con l'attesa dello schema precedente dà un passaggio ordinato con una scadenza. |
| **Worker in competizione** | Un worker legge lo stato e, se la partizione è libera, chiama `claim` con quella generazione; se un altro l'ha presa prima, la generazione è cambiata e `claim` fallisce. |
| **Lease** | Heartbeat e scadenza stanno nel coordinatore, che revoca con `claim`: la partizione non ha bisogno di orologi, e la sicurezza viene dal fencing. |
| **Split e ribilanciamento** | Vedi [ribilanciamento e split](/mechanisms/rebalancing-and-split.md): per lo split si crea una partizione libera, la si prende, la si riempie e la si passa a un altro writer; per il ribilanciamento basta un `claim` sulla partizione. |

# Uso

- Base della decisione sulla [concorrenza](/decisions/concurrency.md).
- Il passaggio della proprietà serve alle operazioni in blocco
  ([ribilanciamento e split](/mechanisms/rebalancing-and-split.md)),
  non alle migrazioni di routine, che usano la
  [migrazione](/mechanisms/entity-migration.md).
- La forma nell'API e nel contratto del nodo è in [API da
  esporre](/decisions/api-surface.md#proprietà-e-handle-del-writer).

# Nel prototipo

Lo stato di proprietà è nel nodo: i record `Ownership` (generazione,
posseduta) e `WriteToken` (ID della partizione, generazione ≥ 1) sono in
`common`, e il contratto del nodo ha `ownership(PartitionId)`,
`claim(PartitionId, long)`, che restituisce `Optional<WriteToken>`, e
`release(WriteToken)`. Le scritture, `commit` e le due operazioni di
rimozione ricevono il `WriteToken` al posto dell'ID della partizione.
Nell'API la partizione ha `ownership()`, `claim(long)` e
`writer(WriteToken)`, e l'handle `PartitionWriter2`/`PartitionWriter3`
porta il token in ogni scrittura, commit e rimozione, con `close()` che
lo rilascia (vedi [proprietà e handle del
writer](/decisions/api-surface.md#proprietà-e-handle-del-writer)). Lo
spazio crea le partizioni senza proprietario.

- **Stato.** Lo storage della partizione tiene un `Ownership` in un
  campo `volatile`: `claim` e `release` lo sostituiscono sotto il lock
  della partizione, e `ownership(p)` lo legge da ogni thread senza
  lock, con generazione e proprietario sempre insieme.
- **Scritture scartate.** A ogni cambio di generazione lo storage
  riporta lo stato del writer all'ultimo commit con le stesse
  operazioni delle scritture: toglie le entità che il commit non ha,
  rimette quelle che ha tolto e riporta le altre alla posizione del
  commit. L'indice riceve le notifiche come per ogni scrittura, quindi
  il meccanismo vale per tutti gli indici senza operazioni nuove; gli
  slot possono finire in un ordine diverso da quello del commit, e il
  commit successivo copia quello che è cambiato. Senza scritture dopo
  l'ultimo commit non fa niente.
- **Controllo del token.** Ogni scrittura, commit e richiesta di
  rimozione dello storage riceve la generazione e la confronta con lo
  stato di proprietà subito dopo aver preso il lock della partizione,
  insieme agli altri controlli dell'input: con una generazione che non
  è del proprietario attuale, o senza proprietario, fallisce con
  `OwnershipChangedException` (in `common`) e la partizione non cambia.
  Il nodo controlla prima lo stato della partizione, quindi dopo la
  richiesta di rimozione un'operazione del writer fallisce con
  `IllegalStateException` anche con un token vecchio.
- **Rimozione e distruzione.** Dalla richiesta di rimozione `claim`
  fallisce con `IllegalStateException` e `release` non fa niente;
  `ownership` risponde finché la partizione non è distrutta. Dopo la
  distruzione `ownership` e `claim` falliscono con l'errore generico,
  come per un ID sconosciuto, e `release` non fa niente.

I test verificano la generazione iniziale, `claim` con la generazione
giusta, vecchia e futura, il passaggio con il vecchio token che non
vale più, `release` ripetuto e con un token non corrente, le scritture
non confermate scartate a `claim` e a `release` con i commit
precedenti confermati (anche prima del primo commit e nello stesso
tick), sequenze casuali di scritture scartate con lo stato e l'indice
uguali all'ultimo commit per la scansione lineare, la grid uniforme e
la griglia di quadtree, la generazione negativa, la rimozione e la
partizione distrutta. Per il token verificano che scritture, commit e
rimozioni con un token vecchio o senza proprietario siano rifiutati
senza cambiare la partizione, e, con latch e barriere, senza sleep: un
commit in corso quando arriva `claim` resta confermato, perché `claim`
aspetta il lock, e dopo il token vecchio è rifiutato; di più `claim`
concorrenti sulla stessa generazione ne vince uno solo; un `release`
durante le scritture lascia esattamente l'ultimo commit finito;
`claim` e avanzamenti del tick concorrenti lasciano coerenti
generazione e tick. Gli altri test del service prendono la proprietà
con un helper prima della prima scrittura. I test dell'API controllano
che l'handle passi il suo token in ogni operazione e che rifiuti il
token di un'altra partizione; i test di integrazione verificano la
partizione nuova senza proprietario, il passaggio cooperativo con il
vecchio handle rifiutato, la revoca da un coordinatore che perde le
scritture non confermate, `close()` dopo un'eccezione come rollback e
`close()` dopo la distruzione.

# Punti aperti

- **Persistenza della generazione.** Con la
  [persistenza](/decisions/persistence.md) la generazione va salvata
  con la partizione: dopo un riavvio un token vecchio non deve tornare
  valido.

# Correlati

- [Architettura minima](/architecture/minimal-core.md) — primitiva 1.
- [Tipo di strumento](/decisions/tool-type.md#accesso) — il fencing
  protegge da errori e ritardi, non da chiamanti malevoli.
