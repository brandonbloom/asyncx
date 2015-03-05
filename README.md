# asyncx

An abandoned Clojure experiment to provide Rx-style operations over core.async channels.

See [src/asyncx/core.clj][1] for what's available.
Cross reference with [my notes][2] and [MSDN][3].

## Status: A Bad Idea

I've learned *a lot* in writing this library. If I were to
do it again, I wouldn't persue anything resembling Rx. As is,
I certainly wouldn't run this code in production nor view
it as a exemplarly core.async usage.

Furthermore, this was written before core.async channels became
transducers-aware. Use that instead!

## License

Copyright © 2013 Brandon Bloom

Distributed under the Eclipse Public License, the same as Clojure.


[1]: ./src/asyncx/core.clj
[2]: ./notes
[3]: http://msdn.microsoft.com/en-us/library/system.reactive.linq.observable(v=vs.103).aspx
