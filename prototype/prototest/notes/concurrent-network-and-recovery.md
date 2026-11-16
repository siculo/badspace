# Rete concorrente e stato di errore del proxy

`FakeNetworkInterface` e `NodeProxy` possono essere condivisi tra più thread.
`NodeProxy` delega l'esecuzione delle chiamate a un `RemoteCallExecutor`
dedicato. La gestione di retry, errori, concorrenza e recupero locale è descritta
nelle [Javadoc di `RemoteCallExecutor`](../src/main/java/prototest/proxy/RemoteCallExecutor.java).

## Limiti

I limiti del registro delle richieste sono descritti nelle
[note sulle richieste da più proxy](multiple-proxies.md).
Il simulatore propaga direttamente le eccezioni del servizio: non simula
la perdita di una risposta contenente un errore del servizio.
