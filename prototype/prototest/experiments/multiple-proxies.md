# Richieste da più proxy

## Obiettivo

Permettere a più istanze di `NodeProxy` di invocare lo stesso endpoint
senza scambiare una richiesta nuova per il retry di un altro proxy.

Prima ogni proxy ripartiva dal numero zero e il registro usava solo quel
numero come chiave: la prima creazione del secondo proxy poteva tornare
senza errore e senza creare la partizione richiesta. Una lettura poteva
restituire il risultato registrato per un altro proxy.

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

## Verifiche

`MultipleProxiesTest` attraversa il proxy e l'adattatore server reali;
per i retry attraversa anche la rete fittizia, con latenza zero ed esiti
espliciti. Non usa `TestService`, i cui writer e reader saltano ancora
la rete: quel problema del vecchio supporto di test resta da correggere.

Sono verificati:

- creazione di due partizioni da proxy diversi sullo stesso endpoint;
- restituzione di risultati diversi a richieste con la stessa sequenza
  ma provenienti da proxy diversi;
- sostituzione di un proxy con una nuova istanza, senza riuso delle richieste;
- scritture di due proxy con risposte perse, senza duplicare il contenuto;
- commit con risposta persa, senza pubblicare una seconda volta il buffer vuoto;
- mantenimento dell'ID nei retry e avanzamento tra operazioni nuove;
- scritture concorrenti dei due proxy, conservando entrambi i contributi.

`RequestsRegisterTest` verifica risultati separati, arrivo fuori ordine
prima delle scadenze e isolamento delle soglie di scadenza. Usa un clock
controllato, senza attese reali.

Comando dalla radice del repository:

```sh
mvn -f prototype/pom.xml -pl prototest test
```

Risultato: 17 test passati, di cui 9 nuovi, nessun errore o fallimento.

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
stato da uno snapshot persistente. Il recupero e la marcatura del nodo non
sono implementati in questo esperimento. Un timeout del client non dimostra
che l'operazione non sia stata eseguita e non autorizza a proseguire come
se il nodo fosse sicuramente integro.
