# Richieste da più proxy

## Obiettivo

Permettere a più istanze di `NodeProxy` di invocare lo stesso endpoint
senza scambiare una richiesta nuova per il retry di un altro proxy.

## Modifica

`RequestId` contiene l'UUID dell'istanza del proxy e una sequenza crescente
locale, a partire da zero. Un nuovo proxy genera un nuovo UUID, anche se
prende il posto di un proxy precedente. L'unicità tra istanze si basa su
`UUID.randomUUID()`, senza un coordinatore centrale.

Il contatore è atomico per le chiamate concorrenti; all'esaurimento rifiuta
di avanzare anziché ricominciare e riutilizzare gli ID. Ogni chiamata logica
genera un solo ID, conservato per tutti i tentativi, anche dopo una richiesta
o una risposta persa.

Il registro usa l'intero ID come chiave per gli effetti e i risultati.
La soglia delle sequenze scadute è distinta per proxy e può solo crescere:
la pulizia delle richieste di un proxy non invalida quelle degli altri.
Il registro mantiene la serializzazione delle operazioni già presente.

La sequenza esprime l'ordine di generazione nello stesso proxy, non
l'ordine di arrivo né un ordine globale tra proxy diversi.

## Limiti e regola sull'esito incerto

Resta il criterio precedente di scadenza, ora applicato per proxy: quando
viene rimossa una richiesta, le sequenze fino alla soglia scaduta vengono
rifiutate. Non risolve le richieste fuori ordine a cavallo della scadenza:
una richiesta più bassa può essere rifiutata anche se mai eseguita o se la
sua risposta è ancora nel registro. Questo richiede un esperimento distinto
sulla politica di conservazione e sull'ammissione delle richieste tardive.
La pulizia resta attivata dalle chiamate e non da un timer; anche le soglie
dei proxy terminati restano in memoria. Gli errori del servizio non vengono
ancora registrati come risultati da ripetere.

Con esito incerto il nodo va considerato da recuperare, ricaricandone lo
stato da uno snapshot persistente. Il recupero non è implementato in questo
esperimento; il successivo [esperimento sullo stato di errore del proxy](concurrent-network-and-recovery.md)
introduce il blocco locale del proxy. Un timeout del client non dimostra
che l'operazione non sia stata eseguita e non autorizza a proseguire come
se il nodo fosse sicuramente integro.
