# inky.cc

A ClojureScript sketchbook.

[![Build Status](https://travis-ci.org/zkim/nsfw.png)](https://travis-ci.org/zk/inky)

![](http://f.cl.ly/items/3N443a2i1m0j21053A3N/Screen%20Shot%202013-12-23%20at%203.45.41%20PM.png)

## Working on Sketches Locally

Inky has a dev mode to work on sketches locally. Why? Sub-second
recompiles and source maps.

This works by cloning a gist into the `src/gists` directory, and
visiting `localhost:5000/dev?ns=<ns>`. Specifics below:

1. Get the web-app running locally (clone project & `bin/dev`).
2. Clone a gist into the `src/gists` directory, i.e. `cd src/gists &&
   git clone git@gist.github.com:<gist id>.git`
3. Visit `http://localhost:5000/dev?ns=<ns of cljs file in gist>`
4. Changes are automatically recompiled, and reloaded in your browser.

One gotcha, for source maps to correctly resolve your file, you must
name it after the last part of the ns. For example, if the ns if your
sketch is `foo.bar.baz`, name the file `baz.cljs` (in the root
directory of the gist).

Starter gist to fork: https://gist.github.com/zk/8108564


## Config

Env vars:

* `PORT` -- web port to bind to
* `AWS_ACCESS_ID` -- s3 creds
* `AWS_SECRET_ACCESS_KEY`
* `AWS_S3_BUCKET` -- s3 bucket to store compiled code
* `GA_TRACKING_ID` -- Google Analytics
* `GA_TRACKING_HOST` -- ex. 'inky.cc'


## Dev

Run `bin/dev`


## Testing

Run `bin/test`


## Deploy

Deploys to Heroku. Run `bin/ship`

* Don't forget to bump inky's version number any time deps change
  (including dep version changes).


## TODO

* A bunch of stuff tracking compiles is in mem, prevents scale-out.
* Report compilation progress / errors on compile page


## License

Copyright © 2013 Zachary Kim http://zacharykim.com

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
