La documentazione di questo progetto si trova nel bundle OKF dentro la directory `./okf-bundle`.
I documenti da usare come input per creare o aggiornare il bundle OKF si trovano nella directory `./okf-sources`, esclusa da git.

Per lo sviluppo del prototipo la lista delle cose da fare e delle decisioni da prendere è nel file `./TODO.md`. Gli indici spaziali e i loro test hanno una lista a parte, `./TODO-indices.md`: `TODO.md` usa gli indici così come sono e non dipende da quella lista. Il lavoro sugli indici è in pausa: non proporre attività di quella lista se non è richiesto.

Lo sviluppo è incrementale (vedi `okf-bundle/process/development-approach.md`): ogni attività introduce solo quello che serve ai concetti già presenti. Non anticipare parametri, campi o chiamate di concetti che ancora non esistono (per esempio k nella configurazione della partizione arriva con i commit); quando arriva un concetto nuovo, estendere le parti già scritte che lo richiedono (configurazione, API, contratto del nodo, storage, persistenza). Lo stesso vale per le attività del TODO.

Nel codice si usa solo l'inglese, a livello B2 (semplice e chiaro): identificatori, commenti, Javadoc, messaggi e stringhe. La documentazione del bundle OKF resta in italiano.

Il codice Java del prototipo segue le linee guida in `./prototype/coding-guidelines.md`.
