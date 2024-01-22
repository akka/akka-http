Akka HTTP
=========

The Akka HTTP modules implement a full server- and client-side HTTP stack on top
of akka-actor and akka-stream. It's not a web-framework but rather a more
general toolkit for providing and consuming HTTP-based services. While
interaction with a browser is of course also in scope it is not the primary
focus of Akka HTTP.

Akka HTTP follows a rather open design and many times offers several different
API levels for "doing the same thing". You get to pick the API level of
abstraction that is most suitable for your application. This means that, if you
have trouble achieving something using a high-level API, there's a good chance
that you can get it done with a low-level API, which offers more flexibility but
might require you to write more application code.

Learn more at [akka.io](https://akka.io/).

Documentation
-------------

The documentation is available at
[doc.akka.io](https://doc.akka.io/docs/akka-http/current/), for
[Scala](https://doc.akka.io/docs/akka-http/current/scala/http/) and
[Java](https://doc.akka.io/docs/akka-http/current/java/http/).


Current versions of all Akka libraries
--------------------------------------

The current versions of all Akka libraries are listed on the [Akka Dependencies](https://doc.akka.io/docs/akka-dependencies/current/) page. Releases of the Akka HTTP libraries in this repository are listed on the [GitHub releases](https://github.com/akka/akka-http/releases) page.


Community
---------
You can join these groups and chats to discuss and ask Akka related questions:

- Forums: [discuss.akka.io](https://discuss.akka.io)
- Q&A: [![stackoverflow: #akka-http][stackoverflow-badge]][stackoverflow]
- Issue tracker: [![github: akka/akka-http][github-issues-badge]][github-issues] (Please use the issue
  tracker for bugs and reasonable feature requests. Please ask usage questions on the other channels.)

All of our forums, chat rooms, and issue trackers are governed by our [Code Of Conduct](https://www.lightbend.com/conduct).

In addition to that, you may enjoy following:

- The [news](https://akka.io/blog/news-archive.html) section of the page, which is updated whenever a new version is released
- The [Akka Team Blog](https://akka.io/blog)
- [@akkateam](https://twitter.com/akkateam) on Twitter

[stackoverflow-badge]: https://img.shields.io/badge/stackoverflow%3A-akka--http-blue.svg?style=flat-square
[stackoverflow]:       https://stackoverflow.com/questions/tagged/akka-http
[github-issues-badge]: https://img.shields.io/badge/github%3A-issues-blue.svg?style=flat-square
[github-issues]:       https://github.com/akka/akka-http/issues

Contributing
------------
Contributions are *very* welcome!

If you see an issue that you'd like to see fixed, the best way to make it happen is to help out by submitting a pull request.
For ideas of where to contribute, [tickets marked as "help wanted"](https://github.com/akka/akka-http/labels/help%20wanted) are a good starting point.

Refer to the [CONTRIBUTING.md](CONTRIBUTING.md) file for more details about the workflow,
and general hints on how to prepare your pull request. You can also ask for clarifications or guidance in GitHub issues directly.

Maintenance
-----------

This project is maintained by Lightbend's core Akka Team as well as the extended Akka HTTP Team, consisting of excellent and experienced developers who have shown their dedication and knowledge about HTTP and the codebase. This team may grow dynamically, and it is possible to propose new members to it.

Joining the extended team in such form gives you, in addition to street-cred, of course committer rights to this repository as well as higher impact onto the roadmap of the project. Come and join us!

License
-------

Akka HTTP is licensed under the Business Source License 1.1, see LICENSE.txt.
