# inky.cc

A ClojureScript sketchbook.

[![Build Status](https://travis-ci.org/zkim/nsfw.png)](https://travis-ci.org/zk/inky)

![](http://f.cl.ly/items/3N443a2i1m0j21053A3N/Screen%20Shot%202013-12-23%20at%203.45.41%20PM.png)


## Working on Sketches Locally

Inky has a dev mode to work on sketches locally. Why? Sub-second
recompiles and source maps.

This works by cloning a gist into the `src/gists` directory, and
visiting `localhost:5000/dev?ns=<ns>`. Specifics below:

1. Get the web-app running locally (clone project & `bin/dev`). Only
   `$PORT` is required for local sketch work.
2. Clone a gist into the `src/gists` directory, i.e. `cd src/gists &&
   git clone git@gist.github.com:<gist id>.git`
3. Visit `http://localhost:5000/dev?ns=<ns of cljs file in gist>`
4. Changes are automatically recompiled, and reloaded in your browser.

One gotcha, for source maps to correctly resolve your file, you must
name it after the last part of the ns. For example, if the ns if your
sketch is `foo.bar.baz`, name the file `baz.cljs` (in the root
directory of the gist).

Starter gist to fork: https://gist.github.com/zk/8108564


## Prereqs

* https://github.com/ddollar/foreman
* http://www.mongodb.org/ (brew / apt / yum ok)


## Config

Env vars:

* `PORT` -- web port to bind to
* `AWS_ACCESS_ID` -- s3 creds
* `AWS_SECRET_ACCESS_KEY`
* `AWS_S3_BUCKET` -- s3 bucket to store compiled code
* `GA_TRACKING_ID` -- Google Analytics
* `GA_TRACKING_HOST` -- ex. 'inky.cc'
* `MONGO_URL` -- DB connection url, mongodb:// format
* `GH_CLIENT_ID` -- GH app creds
* `GH_CLIENT_SECRET`


## Dev

Copy bin/dev.sample to bin/dev, fill in appropriate env vars. Note that the env vars are only necessary if you're working on inky. If you're working on a sketch locally, only `$PORT` is required (provided by foreman).

**Warning:** There's a nasty bug where foreman won't correctly kill java processes it starts **when foreman exits due to a child process exiting** (this behavior doesn't manifest when you SIGINT foreman). You may need to manually kill previous processes manually.


## Testing

Run `bin/test`


## Deploy

Deploys to Heroku. Run `bin/ship`

* Don't forget to bump inky's version number any time deps change
  (including dep version changes).


## TODO

* A bunch of stuff tracking compiles is in mem, prevents scale-out.
* Report compilation progress / errors on compile page
* Handle s3 connection error / timeout (errors into compiling state
  right now).


## Internals

### Job States

Inky runs several worker threads internally to compile gists, which
pull from a job queue backed by MongoDB (`:compile-jobs`). Jobs
transition through several states as they are compiled, and as errors
pop up. All flags are unix timestamps unless otherwise specified.

* Enqueued -- `:created` is set.
* Compiling -- `:created` and `:started` are set.
* Compile completed successfully -- `:created`, `:started`, and
  `:succeeded` are set.
* Compile errored -- `:created`, `:started`, and `:failed` are set
  (see `:error-cause` for message).

This is a first cut on representing the different job states.


## License

Copyright © 2013 Zachary Kim http://zacharykim.com

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
