---
type: Language Comparison
title: Confronto tra C e Rust
description: Confronto tra C e Rust per l'implementazione finale su quattro temi — overflow degli interi, coordinate di tipo diverso, astrazione su 2D/3D e complessità del codice delle strutture degli indici (borrow checker).
tags: [badspace, design, language, c, rust]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-05T14:03:15Z }
---

# Scopo

Elementi per la scelta del [linguaggio](/decisions/language.md)
dell'implementazione finale, limitati a C (C11) e Rust. Non è una
decisione: la scelta resta aperta.

# Overflow degli interi

**C:**

- overflow su interi **con segno**: comportamento indefinito (UB). Il
  compilatore può assumere che non avvenga ed eliminare controlli come
  `if (a + 1 < a)`;
- overflow su interi **senza segno**: definito, modulo 2ⁿ;
- strumenti: `__builtin_mul_overflow` e simili (GCC/Clang), `ckd_mul` in
  `<stdckdint.h>` (C23), `-fwrapv` per rendere definito il wrapping,
  `-fsanitize=signed-integer-overflow` nei test.

**Rust:**

- mai UB per l'overflow aritmetico: panic in debug, wrapping in release
  (i controlli si riattivano con `overflow-checks = true` nel profilo);
- il comportamento si sceglie in modo esplicito:

  | Metodo | Comportamento |
  |---|---|
  | `checked_mul` | `Option`, `None` se overflow |
  | `wrapping_mul` | va a capo |
  | `saturating_mul` | si ferma al minimo o al massimo |
  | `overflowing_mul` | risultato e flag |

- tipi `Wrapping<T>` e `Saturating<T>`; `i128` è nativo. In C `__int128`
  è un'estensione di GCC/Clang, non standard.

In entrambi i linguaggi i cast (`as` in Rust) troncano in silenzio; Rust
ha `try_from` e `try_into` per le conversioni controllate.

Con coordinate intere a 64 bit (vedi [tipo delle
coordinate](/decisions/coordinate-type.md)): il quadrato di un valore
resta in `int64` se |v| ≤ 2³¹ − 1, la distanza al quadrato in 3D se ogni
componente è entro 30 bit. Al millimetro, 2³⁰ mm ≈ 1070 km: oltre serve
saturare, usare 128 bit per quel calcolo o passare a `double`.

# Coordinate di tipo diverso

Se la scelta del [tipo delle coordinate](/decisions/coordinate-type.md)
resta aperta, conta come il linguaggio scrive codice generico.

**Rust** — i generici sono parte del linguaggio:

- un **trait** descrive ciò che serve alle coordinate e le strutture sono
  generiche su di esso;
- la **monomorfizzazione** genera una versione per ogni tipo, senza costo
  a runtime;
- un **tipo associato** dichiara il tipo "largo" per le distanze:

```rust
trait Coord: Copy + PartialOrd {
    type Wide: PartialOrd;
    fn dist2(self, other: Self) -> Self::Wide;
}
impl Coord for i64 {
    type Wide = i128;
    fn dist2(self, o: Self) -> i128 { let d = i128::from(self) - i128::from(o); d * d }
}
impl Coord for f32 {
    type Wide = f64;
    fn dist2(self, o: Self) -> f64 { let d = f64::from(self) - f64::from(o); d * d }
}
```

- il tipo si sceglie in compilazione (`cfg`, feature di Cargo) o lo
  sceglie chi usa la libreria; per un indice conviene un trait proprio e
  piccolo più che `num-traits`.

**C** — strumenti più rudimentali:

- **macro** che generano il codice per ogni tipo
  (`DEFINE_INDEX(i64, i128)`): diffuse, ma con errori difficili da
  leggere;
- **include ripetuto** di un file "template" con `#define COORD_T`;
- **`_Generic`** (C11): solo dispatch su funzioni già scritte, non genera
  codice;
- **`void*`** con la dimensione del tipo a runtime: flessibile, ma senza
  sicurezza di tipo e più lento;
- **`typedef`** scelto in compilazione: una sola scelta per tutto
  l'eseguibile.

**FFI:** i generici di Rust non attraversano il confine C. Un'API C
espone un'istanza per tipo (`badspace_i64_insert`,
`badspace_f32_insert`, ...), di solito generata con una macro: i tipi
supportati si decidono alla compilazione della libreria.

In Rust l'algoritmo si scrive una volta e un tipo nuovo costa poche
righe; in C è fattibile, con più disciplina e più manutenzione.

# Astrazione sulla dimensione (2D/3D)

Il [supporto a 2D e 3D](/decisions/2d-3d-support.md) usa
implementazioni parallele. In Rust i **const generics** permettono
anche di scrivere il codice una volta sola, con la dimensione come
parametro del tipo:

```rust
#[derive(Clone, Copy)]
struct Point<C: Coord, const D: usize>([C; D]);

struct Aabb<C: Coord, const D: usize> { min: Point<C, D>, max: Point<C, D> }

impl<C: Coord, const D: usize> Aabb<C, D> {
    fn contains(&self, p: &Point<C, D>) -> bool {
        (0..D).all(|i| self.min.0[i] <= p.0[i] && p.0[i] <= self.max.0[i])
    }
}
```

- punti, box, distanze, intersezioni e k-d tree (asse `depth % D`) si
  scrivono una volta; `D` è noto in compilazione, quindi i cicli si
  srotolano senza costo;
- **limite:** i nodi con 2^D figli (quadtree/octree) non si scrivono come
  `[Node; 1 << D]` in Rust stabile (`generic_const_exprs` è solo
  nightly). Alternative: `Vec` o `Box<[Node]>` per i figli, un trait
  `Dim` con marker `D2` e `D3`, oppure strutture che non dipendono da
  2^D.

In C l'unica via è quella delle implementazioni parallele, o delle
macro.

# Complessità del codice delle strutture degli indici

## Cosa serve agli indici

Le strutture degli [indici](/indices/) non sono alberi "puri", con un
solo proprietario per nodo e visite solo dall'alto:

- **riferimenti all'indietro**: slot → foglia o cella e posizione nella
  lista, per aggiornare e rimuovere in O(1);
- **riferimento al genitore**: lo spostamento verso una foglia vicina
  risale fino al primo nodo che contiene la nuova posizione (vedi
  [griglia di quadtree](/indices/grid-quadtree.md));
- **modifiche su due nodi insieme**: un cambio di foglia toglie lo slot
  da una foglia e lo mette in un'altra; la rimozione con swap sposta
  l'ultimo slot nel buco e ne aggiorna il riferimento all'indietro;
- **cambi di forma**: una foglia piena diventa nodo interno (divisione),
  i figli tornano una foglia (riunione);
- **code di priorità di nodi** per il k-nearest best-first.

Sono grafi con cicli e più riferimenti allo stesso nodo: in sostanza,
alberi navigabili in entrambe le direzioni.

## C: gestione immediata

- I puntatori si usano liberamente (genitore, figli, riferimenti
  all'indietro) e il codice segue l'algoritmo da vicino. Due nodi da
  modificare sono due puntatori.
- Il costo è tutto a carico di chi sviluppa: **puntatori pendenti** (un
  `realloc` sposta gli elementi di un array; una riunione libera nodi
  ancora puntati), **use-after-free** e doppie liberazioni, modifiche
  durante una visita. Nessun aiuto dal compilatore: servono
  AddressSanitizer, Valgrind e i test con la [scansione
  lineare](/indices/linear-scan.md) come riferimento.
- Per ridurre i rischi si usa spesso un'**arena**: nodi in un array,
  indici `uint32_t` al posto dei puntatori. Il `realloc` non è più un
  problema, ma un indice vecchio punta in silenzio a un nodo riusato.

## Rust: il borrow checker

Ogni valore ha un solo proprietario; in ogni momento ci sono o tanti
riferimenti in lettura o un solo riferimento mutabile. Un albero con
`Box` per i figli rispetta queste regole; genitore e riferimenti
all'indietro no.

| Approccio | Pro | Contro |
|---|---|---|
| `Box<Node>` per i figli | semplice, idiomatico | niente genitore né riferimenti all'indietro |
| `Rc<RefCell<Node>>` e `Weak` | modella i cicli | verboso, costo a runtime, panic se un prestito fallisce |
| **Arena con indici** (`Vec<Node>`, `u32`) | idiomatico per alberi e grafi, layout contiguo | il compilatore controlla l'arena, non gli indici |
| `unsafe` con puntatori grezzi | codice vicino al C | si perdono le garanzie in quel modulo |

La scelta naturale è l'**arena con indici**, lo stesso layout che si
userebbe in C. Il codice Rust e il codice C diventano molto simili. Un
indice vecchio non causa UB: punta a un nodo sbagliato (errore logico)
o va fuori dai limiti (panic). Con gli **indici generazionali** si
rileva anche il riuso di un nodo.

## Punti di attrito in Rust, con l'arena

- **Due nodi mutabili insieme:** `&mut nodes[a]` e `&mut nodes[b]` non
  possono coesistere, anche con `a != b`. Soluzioni:
  `get_disjoint_mut([a, b])` (stabile da Rust 1.86, anche su `HashMap`),
  `split_at_mut`, oppure leggere, rilasciare e scrivere dopo.
- **Aggiungere nodi tenendo un riferimento:** durante una divisione
  `nodes.push(...)` può riallocare il `Vec`, quindi il compilatore
  rifiuta un `&mut nodes[i]` ancora vivo. In C lo stesso codice compila
  e lascia un puntatore pendente. Si lavora per indici e si riprende il
  riferimento dopo il `push`: codice un po' più frammentato, ma il bug è
  escluso.
- **Cambio di forma di un nodo:** con
  `enum Node { Leaf(..), Internal(..) }` la divisione usa
  `std::mem::take` o `std::mem::replace` per spostare fuori le entità
  prima di sovrascrivere il nodo; in C si cambia un tag.
- **Visite che modificano:** un iteratore tiene un prestito sull'arena;
  si raccolgono prima gli indici da modificare o si usa un ciclo su
  indici.
- **Code di priorità:** `f64` non implementa `Ord`; per la `BinaryHeap`
  serve un piccolo tipo con `total_cmp`.
- **Metodi helper:** un metodo `&mut self` presta tutta la struttura;
  spesso si passano solo i campi che servono (`&mut self.nodes`,
  `&mut self.slots`) a funzioni libere.

## Librerie

Il vantaggio di Rust sulle librerie riguarda i **mattoni** di base, che
con Cargo si aggiungono con una riga:

- arene e alberi con indici: `slotmap` (chiavi generazionali, accesso
  mutabile a più elementi insieme), `slab`, `thunderdome`, `indextree`
  (albero con genitore, figli e fratelli); `petgraph` per grafi
  generici;
- hash map, array dinamici e heap sono nella libreria standard.

In C gli stessi mattoni (arena, array dinamico, hash map, heap) vanno
scritti e mantenuti, o presi da fuori senza un package manager.

Le librerie non tolgono tutto l'attrito: la regola di un solo `&mut`
resta, e un albero generico non ha il layout più adatto a un quadtree
(blocchi di 2^D figli, liste di slot nelle foglie). Il codice
dell'indice vero e proprio si scrive a mano in entrambi i linguaggi.

## Quanto pesa

- **Righe di codice:** con l'arena, simili. Rust risparmia le utility e
  ne aggiunge qualcuna per gli attriti.
- **Tempo di scrittura:** più alto in Rust all'inizio, soprattutto per
  chi non conosce il linguaggio; dopo i primi schemi (arena, indici,
  `get_disjoint_mut`, riferimento ripreso dopo `push`) l'attrito cala
  molto.
- **Tempo di debug:** più basso in Rust. Puntatori pendenti,
  use-after-free e modifiche durante una visita diventano errori di
  compilazione o panic nel punto esatto. Restano gli errori logici sugli
  indici, come in C con l'arena.
- **Prestazioni:** con l'arena equivalenti. Il controllo dei limiti
  costa poco ed è spesso tolto dal compilatore; nei punti caldi si può
  evitare con `get_unchecked` in `unsafe`. Il layout conta poco anche
  per le misure del prototipo (vedi [costo delle
  scritture](/indices/grid-quadtree.md#problematiche-aperte)).
- **Concorrenza:** con [un writer e più reader](/decisions/concurrency.md)
  per partizione, Rust controlla in compilazione che la condivisione tra
  thread sia corretta (`Send`, `Sync`); in C resta a carico di chi
  sviluppa.

## Sintesi

In C le strutture degli indici si scrivono come si pensano, e il rischio
si sposta sul debug e sui sanitizer. In Rust la forma con puntatori
liberi richiede `unsafe`; la forma idiomatica è l'arena con indici, che
è anche il layout naturale in C. Con l'arena la differenza si riduce a
pochi schemi da imparare: il prezzo si paga soprattutto all'inizio, in
cambio di meno bug di memoria.

# Correlati

- [Linguaggio](/decisions/language.md) — la scelta tra C11, C++ e Rust.
- [Tipo delle coordinate](/decisions/coordinate-type.md) — overflow e
  tipo largo per le distanze.
- [Supporto a 2D e 3D](/decisions/2d-3d-support.md) — implementazioni
  parallele o generiche sulla dimensione.
- [Griglia di quadtree](/indices/grid-quadtree.md) e [Quadtree e
  octree](/indices/quadtree.md) — le strutture che pongono i problemi di
  riferimenti e modifiche.
