# Awaze

A clojure client library for AWS services, using the
[AWS Java SDK][awssdk].

The library is implemented using reflection to generate code, and therefore avoids
reflection at compile time or runtime when using the library.


Each AWS service is provided in a namespace under
`com.palletops.awaze.`, eg.  `com.palletops.awaze.ec2` for
EC2.

For each SDK function, two clojure functions are implemented.

The first is a direct wrapper that calls the sdk function directly.
This take an initial `credentials` argument, that expects a data map
with `:access-key`, `:secret-key` and optionally `:endpoint` keys.

```clj
(require '[com.palletops.awaze.ec2 :as ec2 :refer [ec2]])
(ec2/describe-instances
  {:access-key "AKIRIEDKE5ZBZG5VVCA"
   :secret-key "76dDdsKDJdsKDH+Uyuiy678Khjhkh8797vbnvnv"})
```

The second generated function, with a `-map` suffix, has the same
arguments and generates a data map that can be executed via a client
executor function.

```clj
(require '[com.palletops.awaze.ec2 :as ec2 :refer [ec2]])
(let [m (ec2/describe-instances-map
         {:access-key "AKIRIEDKE5ZBZG5VVCA"
          :secret-key "76dDdsKDJdsKDH+Uyuiy678Khjhkh8797vbnvnv"})]
  (ec2/ec2 m))
```

Originally based on [amazonica][amazonica].

## Install

Add the following to your dependencies:

```clj
[com.palletops/awaze "0.1.0"]
```

## Usage

Each Amazon service client is in it's own namespace, in
`com.palletops.awaze.*`.

Each method of the client generates two clojure functions, one which executes
the client method directly, and one, with a `-map` suffix, that generates a map.
The map can be passed to a multimethod, with the same name as the service, which
actually runs the client method.

[API docs](http:/pallet.github.com/awaze/0.1/api/index.html).

[Annotated source](http:/pallet.github.com/awaze/0.1/uberdoc.html).

## Known issues

Setters with overloaded type arguments are not handled properly, and still cause
reflection.

## License

Copyright © 2013 Hugo Duncan.

Distributed under the Eclipse Public License.

Any code from Amazonica is:

Copyright (C) 2013 Michael Cohen

[awssdk]: http://aws.amazon.com/sdkforjava/ "AWS Java SDK"
[amazonica]: https://github.com/mcohen01/amazonica
