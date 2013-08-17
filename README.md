# asyncx

A Clojure library designed to provide Rx-style operations over core.async channels.

See [src/asyncx/core.clj][1] for what's available.
Cross reference with [my notes][2] and [MSDN][3].

## Status

*Highly experimental!*

I've learned *a lot* in writing this library. If I were to
do it again, I'd probably do many things differently. As is,
I certainly wouldn't run this code in production nor view
it as a exemplarly core.async usage.

I'll probably either fix up this library, or document the things I
learned. In the meantime, you can see my [discussion with lynaghk][1]
in Freenode's #clojure on 2013-08-17.

## License

Copyright © 2013 Brandon Bloom

Distributed under the Eclipse Public License, the same as Clojure.


[1]: ./src/asyncx/core.clj
[2]: ./notes
[3]: http://msdn.microsoft.com/en-us/library/system.reactive.linq.observable(v=vs.103).aspx
[4]: http://www.raynes.me/logs/irc.freenode.net/clojure/2013-08-17.txt
