La documentazione di questo progetto si trova nel bundle OKF dentro la directory `./okf-bundle`.
I documenti da usare come input per creare o aggiornare il bundle OKF si trovano nella directory `./okf-sources`, esclusa da git.

Le skill per gli agenti si trovano in `./.agents/skills`. Claude Code le legge da `./.claude/skills`, che è esclusa da git e viene creata dagli script `./agents-setup.sh` (Linux e macOS) e `./agents-setup.cmd` (Windows). Se le skill non sono disponibili, eseguire lo script. Quando serve un'altra configurazione per gli agenti, aggiungerla a entrambi gli script e non al repository.

Per lo sviluppo del prototipo la lista delle cose da fare e delle decisioni da prendere è nel file `./TODO.md`. Gli indici spaziali e i loro test hanno una lista a parte, `./TODO-indices.md`: `TODO.md` usa gli indici così come sono e non dipende da quella lista. Il lavoro sugli indici è in pausa: non proporre attività di quella lista se non è richiesto.

Lo sviluppo è incrementale (vedi `okf-bundle/process/development-approach.md`): ogni attività introduce solo quello che serve ai concetti già presenti. Non anticipare parametri, campi o chiamate di concetti che ancora non esistono (per esempio k nella configurazione della partizione arriva con i commit); quando arriva un concetto nuovo, estendere le parti già scritte che lo richiedono (configurazione, API, contratto del nodo, storage, persistenza). Lo stesso vale per le attività del TODO.

Nel codice si usa solo l'inglese, a livello B2 (semplice e chiaro): identificatori, commenti, Javadoc, messaggi e stringhe. La documentazione del bundle OKF resta in italiano.

Il codice Java del prototipo segue le linee guida in `./prototype/coding-guidelines.md`.

La documentazione del bundle distingue `okf-bundle/current/`, che descrive
BADSPACE così com’è nell’implementazione corrente, e `okf-bundle/future/`,
che raccoglie funzionalità da realizzare, direzioni già scelte e proposte
ancora da valutare. Il processo di sviluppo resta in `okf-bundle/process/`.
Verificare sul codice e sui test ciò che si presenta come disponibile;
non dedurlo dal solo stato di una decisione o di un TODO. Nella parte
corrente limitare i riferimenti al futuro ai casi motivati, necessari per
spiegare un limite o una scelta attuale, indicandoli chiaramente come futuri.

Organizzare la documentazione in modo progressivo, introducendo i concetti
principali prima di quelli che ne richiedono la comprensione. Il percorso
di riferimento è: filosofia d’uso di BADSPACE; architettura generale;
componenti principali dell’API; problemi risolti e motivazioni delle
scelte; approfondimenti dei meccanismi specifici. Adattare l’ordine quando
serve alla comprensione: problemi e motivazioni possono seguire ciascun
componente, insieme ai relativi meccanismi, evitando anticipazioni e
rinvii che obbligano a conoscere concetti non ancora introdotti.

Durante lo sviluppo aggiornare la documentazione man mano: quando una
funzionalità viene implementata, rimuoverne la descrizione da `future/`
e integrarla in `current/` nel percorso di lettura appropriato. Separare
le eventuali estensioni ancora future, evitando descrizioni duplicate o
superate. Aggiornare contestualmente indici, collegamenti, glossari e
casi d’uso interessati.

I diagrammi esplicativi si scrivono direttamente nei documenti Markdown
come disegni ASCII in blocchi di codice `text`, seguendo le convenzioni
di Svgbob. Il disegno deve essere leggibile nel sorgente con un carattere
monospaziato, senza viewer, plugin o conversioni. Per il momento non
usare Mermaid e non generare o conservare file SVG o altre immagini dei
diagrammi nel bundle, salvo richiesta esplicita.

Usare spazi, mai tabulazioni; allineare bordi, collegamenti e frecce.
Per i riquadri usare `+`, `-` e `|`; per le frecce usare `-->`, `<--`,
`v` e `^`; per eventi puntuali usare `*` e per guide tratteggiate `:`.
Racchiudere tra virgolette doppie le etichette che contengono caratteri
interpretabili come geometria, per esempio `"S(10): A"` o `"writer(token)"`.
Le virgolette delimitano il testo secondo le convenzioni Svgbob.
Preferire figure entro circa 90 colonne; dividere quelle troppo larghe
o complesse in più esempi, anziché ridurre la leggibilità delle etichette.

I diagrammi arricchiscono le spiegazioni: ciascuno chiarisce una relazione
o un meccanismo preciso, con etichette in italiano e una breve spiegazione
del suo perimetro. Mantenerli allineati al testo e alla distinzione tra
`current/` e `future/` durante lo sviluppo. Rileggerli direttamente nel
Markdown per verificare allineamenti, direzione dei collegamenti e
condizioni delle transizioni. Nei diagrammi temporali mantenere un asse
orizzontale, mostrare la durata delle scritture e allineare la disponibilità
degli snapshot ai confini dei tick; indicare quando le distanze non sono
in scala.
